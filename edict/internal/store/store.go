// Package store 提供 edict 数据的存取层，封装 SQLite 读写与 JSON 列编解码。
package store

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"edict/internal/db"
	"edict/internal/model"
)

// Store 封装数据库连接。
type Store struct {
	DB *sql.DB
}

type dbTask struct {
	ID          string
	Title       string
	State       string
	Org         string
	Official    string
	Now         string
	ETA         string
	Block       string
	AC          string
	Output      string
	Archived    int
	ArchivedAt  string
	ReviewRound int
	FlowLog     string
	Todos       string
	SourceMeta  string
	UpdatedAt   string
}

// New 构造 Store。
func New(d *sql.DB) *Store { return &Store{DB: d} }

// ── Agents ──

// EnsureAgents 播种（upsert）Agent 名册。
func (s *Store) EnsureAgents(agents []model.AgentInfo) error {
	for _, a := range agents {
		if _, err := s.DB.Exec(
			`INSERT INTO agents (id, label, emoji, role, model) VALUES (?,?,?,?,?)
			 ON CONFLICT(id) DO UPDATE SET label=excluded.label, emoji=excluded.emoji, role=excluded.role`,
			a.ID, a.Label, a.Emoji, a.Role, a.Model,
		); err != nil {
			return err
		}
	}
	return nil
}

// ListAgents 返回所有 Agent（含其已存技能）。
func (s *Store) ListAgents() ([]model.AgentInfo, error) {
	rows, err := s.DB.Query(`SELECT id, label, emoji, role, model FROM agents ORDER BY id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []model.AgentInfo{}
	for rows.Next() {
		var a model.AgentInfo
		if err := rows.Scan(&a.ID, &a.Label, &a.Emoji, &a.Role, &a.Model); err != nil {
			return nil, err
		}
		a.Skills = []model.SkillInfo{} // v1: 技能列表留空，后续从 skills 表补全
		out = append(out, a)
	}
	return out, rows.Err()
}

// SetAgentModel 更新 Agent 模型并记录变更日志。
func (s *Store) SetAgentModel(agentID, newModel string) error {
	var old string
	if err := s.DB.QueryRow(`SELECT model FROM agents WHERE id=?`, agentID).Scan(&old); err != nil {
		return err
	}
	if _, err := s.DB.Exec(`UPDATE agents SET model=? WHERE id=?`, newModel, agentID); err != nil {
		return err
	}
	_, err := s.DB.Exec(
		`INSERT INTO model_change_log (at, agent_id, old_model, new_model, rolled_back) VALUES (?,?,?,?,0)`,
		db.NowRFC3339(), agentID, old, newModel,
	)
	return err
}

// ── Dispatch channel ──

func (s *Store) GetDispatchChannel() string {
	var ch string
	_ = s.DB.QueryRow(`SELECT channel FROM dispatch_channel WHERE id=1`).Scan(&ch)
	if ch == "" {
		return "openclaw"
	}
	return ch
}

func (s *Store) SetDispatchChannel(ch string) error {
	_, err := s.DB.Exec(
		`INSERT INTO dispatch_channel (id, channel) VALUES (1, ?)
		 ON CONFLICT(id) DO UPDATE SET channel=excluded.channel`, ch)
	return err
}

// ── Model change log ──

func (s *Store) ListModelChangeLog() ([]model.ChangeLogEntry, error) {
	rows, err := s.DB.Query(`SELECT at, agent_id, old_model, new_model, rolled_back FROM model_change_log ORDER BY id DESC LIMIT 100`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []model.ChangeLogEntry{}
	for rows.Next() {
		var e model.ChangeLogEntry
		var rb int
		if err := rows.Scan(&e.At, &e.AgentID, &e.OldModel, &e.NewModel, &rb); err != nil {
			return nil, err
		}
		e.RolledBack = rb != 0
		out = append(out, e)
	}
	return out, rows.Err()
}

// ── Tasks ──

// CreateTask 新建任务，初始状态 Taizi。
func (s *Store) CreateTask(t *model.Task) error {
	now := db.NowRFC3339()
	flow, _ := json.Marshal(t.FlowLog)
	todos, _ := json.Marshal(t.Todos)
	meta, _ := json.Marshal(t.SourceMeta)
	if t.Org == "" {
		t.Org = "太子"
	}
	_, err := s.DB.Exec(
		`INSERT INTO tasks (id, title, state, org, official, now, eta, block, ac, output, archived, archived_at, review_round, flow_log, todos, source_meta, created_at, updated_at)
		 VALUES (?,?,?,?,?,?,?,?,?,?,0,'',0,?,?,?,?,?)`,
		t.ID, t.Title, t.State, t.Org, t.Official, t.Now, t.ETA, t.Block, t.AC, t.Output,
		string(flow), string(todos), string(meta), now, now,
	)
	return err
}

// GetTask 按 ID 取任务。
func (s *Store) GetTask(id string) (*model.Task, error) {
	dt, err := s.scanTask(`SELECT id, title, state, org, official, now, eta, block, ac, output, archived, archived_at, review_round, flow_log, todos, source_meta, updated_at FROM tasks WHERE id=?`, id)
	if err != nil {
		return nil, err
	}
	return dt, nil
}

// ListTasks 列出任务；includeArchived=false 时过滤已归档。
func (s *Store) ListTasks(includeArchived bool) ([]model.Task, error) {
	q := `SELECT id, title, state, org, official, now, eta, block, ac, output, archived, archived_at, review_round, flow_log, todos, source_meta, updated_at FROM tasks`
	if !includeArchived {
		q += ` WHERE archived=0`
	}
	q += ` ORDER BY updated_at DESC`
	rows, err := s.DB.Query(q)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []model.Task{}
	for rows.Next() {
		dt, err := scanTaskRow(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, *dt)
	}
	return out, rows.Err()
}

// UpdateTask 全量更新任务行（调用方需先组装好字段）。
func (s *Store) UpdateTask(t *model.Task) error {
	flow, _ := json.Marshal(t.FlowLog)
	todos, _ := json.Marshal(t.Todos)
	meta, _ := json.Marshal(t.SourceMeta)
	archived := 0
	if t.Archived {
		archived = 1
	}
	_, err := s.DB.Exec(
		`UPDATE tasks SET title=?, state=?, org=?, official=?, now=?, eta=?, block=?, ac=?, output=?, archived=?, archived_at=?, review_round=?, flow_log=?, todos=?, source_meta=?, updated_at=? WHERE id=?`,
		t.Title, t.State, t.Org, t.Official, t.Now, t.ETA, t.Block, t.AC, t.Output, archived, t.ArchivedAt, t.ReviewRound, string(flow), string(todos), string(meta), db.NowRFC3339(), t.ID,
	)
	return err
}

// ── Activity ──

// AppendActivity 追加一条任务动态。
func (s *Store) AppendActivity(taskID, kind string, data map[string]any) error {
	b, _ := json.Marshal(data)
	_, err := s.DB.Exec(
		`INSERT INTO activity (task_id, kind, at, data) VALUES (?,?,?,?)`,
		taskID, kind, db.NowRFC3339(), string(b),
	)
	return err
}

// GetTaskActivity 取任务动态（按时间正序）。
func (s *Store) GetTaskActivity(taskID string) ([]model.ActivityEntry, error) {
	rows, err := s.DB.Query(`SELECT kind, at, data FROM activity WHERE task_id=? ORDER BY id ASC`, taskID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []model.ActivityEntry{}
	for rows.Next() {
		var kind, at, raw string
		if err := rows.Scan(&kind, &at, &raw); err != nil {
			return nil, err
		}
		var e model.ActivityEntry
		_ = json.Unmarshal([]byte(raw), &e)
		if e.Kind == "" {
			e.Kind = kind
		}
		if e.At == nil {
			e.At = at
		}
		out = append(out, e)
	}
	return out, rows.Err()
}

// ── 内部 ──

func (s *Store) scanTask(query string, args ...any) (*model.Task, error) {
	rows, err := s.DB.Query(query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	if !rows.Next() {
		if err := rows.Err(); err != nil {
			return nil, err
		}
		return nil, fmt.Errorf("task not found")
	}
	return scanTaskRow(rows)
}

func scanTaskRow(rows *sql.Rows) (*model.Task, error) {
	var dt dbTask
	if err := rows.Scan(
		&dt.ID, &dt.Title, &dt.State, &dt.Org, &dt.Official, &dt.Now, &dt.ETA, &dt.Block,
		&dt.AC, &dt.Output, &dt.Archived, &dt.ArchivedAt, &dt.ReviewRound, &dt.FlowLog, &dt.Todos, &dt.SourceMeta, &dt.UpdatedAt,
	); err != nil {
		return nil, err
	}
	t := &model.Task{
		ID:          dt.ID,
		Title:       dt.Title,
		State:       dt.State,
		Org:         dt.Org,
		Official:    dt.Official,
		Now:         dt.Now,
		ETA:         dt.ETA,
		Block:       dt.Block,
		AC:          dt.AC,
		Output:      dt.Output,
		ReviewRound: dt.ReviewRound,
		Archived:    dt.Archived != 0,
		ArchivedAt:  dt.ArchivedAt,
		UpdatedAt:   dt.UpdatedAt,
	}
	_ = json.Unmarshal([]byte(dt.FlowLog), &t.FlowLog)
	_ = json.Unmarshal([]byte(dt.Todos), &t.Todos)
	_ = json.Unmarshal([]byte(dt.SourceMeta), &t.SourceMeta)
	if t.FlowLog == nil {
		t.FlowLog = []model.FlowEntry{}
	}
	if t.Todos == nil {
		t.Todos = []model.TodoItem{}
	}
	if t.SourceMeta == nil {
		t.SourceMeta = map[string]any{}
	}
	t.Heartbeat = computeHeartbeat(t.State, t.UpdatedAt)
	return t, nil
}

// computeHeartbeat 依据状态与最近更新时间推导心跳（v1 近似实现）。
func computeHeartbeat(state, updatedAt string) model.Heartbeat {
	terminal := state == "Done" || state == "Cancelled"
	if terminal {
		return model.Heartbeat{Status: "idle", Label: "已完结"}
	}
	ts, err := time.Parse(time.RFC3339, updatedAt)
	if err != nil {
		return model.Heartbeat{Status: "unknown", Label: "未知"}
	}
	age := time.Since(ts)
	switch {
	case age < 5*time.Minute:
		return model.Heartbeat{Status: "active", Label: "活跃"}
	case age < 30*time.Minute:
		return model.Heartbeat{Status: "warn", Label: "迟缓"}
	default:
		return model.Heartbeat{Status: "stalled", Label: "停滞"}
	}
}

// TitleSafe 简单清洗标题，避免空标题。
func TitleSafe(s string) string {
	s = strings.TrimSpace(s)
	if s == "" {
		return "未命名旨意"
	}
	return s
}
