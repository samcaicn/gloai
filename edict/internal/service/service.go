// Package service 实现 edict 的核心业务逻辑：
// Agent 名册（三省六部）、任务状态机与各类操作。
package service

import (
	"fmt"
	"math/rand"
	"strings"
	"time"

	"edict/internal/model"
	"edict/internal/store"
)

// Service 聚合 store 与业务规则。
type Service struct {
	Store *store.Store
}

func New(s *store.Store) *Service { return &Service{Store: s} }

// ── Agent 名册（三省六部 · 12 Agent）──

// DefaultAgents 返回静态 Agent 名册。模型默认 "default"，可由 set-model 覆盖。
func DefaultAgents() []model.AgentInfo {
	return []model.AgentInfo{
		{ID: "taizi", Label: "太子", Emoji: "🤴", Role: "分拣·提炼旨意", Model: "default"},
		{ID: "zhongshu", Label: "中书省", Emoji: "📜", Role: "规划·拟定方案", Model: "default"},
		{ID: "menxia", Label: "门下省", Emoji: "🔍", Role: "审核·封驳", Model: "default"},
		{ID: "shangshu", Label: "尚书省", Emoji: "⚒️", Role: "派发·统筹执行", Model: "default"},
		{ID: "hubu", Label: "户部", Emoji: "💰", Role: "钱粮·经济", Model: "default"},
		{ID: "libu", Label: "礼部", Emoji: "📚", Role: "礼仪·文化", Model: "default"},
		{ID: "bingbu", Label: "兵部", Emoji: "⚔️", Role: "军务·征伐", Model: "default"},
		{ID: "xingbu", Label: "刑部", Emoji: "⚖️", Role: "刑名·法务", Model: "default"},
		{ID: "gongbu", Label: "工部", Emoji: "🔧", Role: "营建·百工", Model: "default"},
		{ID: "libu_hr", Label: "吏部", Emoji: "📋", Role: "官员·考核", Model: "default"},
		{ID: "zaochao", Label: "早朝", Emoji: "🌅", Role: "起居·上朝仪式", Model: "default"},
		{ID: "qintianjian", Label: "钦天监", Emoji: "🔭", Role: "观测·历法", Model: "default"},
	}
}

// KnownModels 返回可选项的模型列表（与 Python backend 的 known_models 对齐）。
func KnownModels() []model.KnownModel {
	return []model.KnownModel{
		{ID: "default", Label: "默认模型", Provider: "openclaw"},
		{ID: "auto", Label: "自动", Provider: "openclaw"},
		{ID: "ark-code-latest", Label: "方舟代码", Provider: "ark"},
		{ID: "gpt-4o-mini", Label: "GPT-4o mini", Provider: "openai"},
		{ID: "claude-sonnet", Label: "Claude Sonnet", Provider: "anthropic"},
	}
}

// ── 任务状态机 ──

// StateTransitions 映射各状态允许的下一状态（对齐 Python backend STATE_TRANSITIONS）。
var StateTransitions = map[string][]string{
	"Pending":        {"Taizi", "Cancelled"},
	"Taizi":          {"Zhongshu", "Cancelled"},
	"Zhongshu":       {"Menxia", "Cancelled", "Blocked"},
	"Menxia":         {"Assigned", "Zhongshu", "Cancelled"},
	"Assigned":       {"Doing", "Next", "Cancelled", "Blocked"},
	"Next":           {"Doing", "Cancelled", "Blocked"},
	"Doing":          {"Review", "Done", "Blocked", "Cancelled"},
	"Review":         {"Done", "Menxia", "Doing", "Cancelled", "PendingConfirm"},
	"PendingConfirm": {"Done", "Review", "Cancelled"},
	"Blocked":        {"Taizi", "Zhongshu", "Menxia", "Assigned", "Next", "Doing", "Review", "Cancelled"},
}

// NextState 给出"御前推进"时的规范下一状态。
var NextState = map[string]string{
	"Pending":        "Taizi",
	"Taizi":          "Zhongshu",
	"Zhongshu":       "Menxia",
	"Menxia":         "Assigned",
	"Assigned":       "Doing",
	"Next":           "Doing",
	"Doing":          "Review",
	"Review":         "Done",
	"PendingConfirm": "Done",
}

// OrgForState 返回某状态对应的执行部门（对齐 Python org_for_state）。
func OrgForState(state, assigneeOrg string) string {
	switch state {
	case "Taizi":
		return "太子"
	case "Zhongshu":
		return "中书省"
	case "Menxia":
		return "门下省"
	case "Assigned", "Review", "PendingConfirm":
		return "尚书省"
	case "Doing", "Next":
		if assigneeOrg != "" {
			return assigneeOrg
		}
		return "六部"
	}
	if assigneeOrg != "" {
		return assigneeOrg
	}
	return "太子"
}

func allowed(from, to string) bool {
	for _, n := range StateTransitions[from] {
		if n == to {
			return true
		}
	}
	return false
}

func (s *Service) transition(taskID, to, remark string) (*model.Task, error) {
	t, err := s.Store.GetTask(taskID)
	if err != nil {
		return nil, err
	}
	from := t.State
	if !allowed(from, to) {
		return nil, fmt.Errorf("非法的状态流转: %s -> %s", from, to)
	}
	t.FlowLog = append(t.FlowLog, model.FlowEntry{
		At:     nowISO(),
		From:   from,
		To:     to,
		Remark: remark,
	})
	t.State = to
	t.Org = OrgForState(to, t.Org)
	t.PrevState = from
	// 御批/完成保护：已完成或已取消不可再被覆盖
	if to == "Done" {
		t.Now = "✅ 已完结"
	}
	_ = s.Store.UpdateTask(t)
	_ = s.Store.AppendActivity(taskID, "state", map[string]any{
		"from": from, "to": to, "remark": remark,
	})
	return t, nil
}

// AdvanceState 御前推进（取规范下一状态）。
func (s *Service) AdvanceState(taskID, comment string) (*model.Task, error) {
	t, err := s.Store.GetTask(taskID)
	if err != nil {
		return nil, err
	}
	next, ok := NextState[t.State]
	if !ok {
		return nil, fmt.Errorf("当前状态 %s 无法推进", t.State)
	}
	if next == t.State {
		return t, nil
	}
	return s.transition(taskID, next, comment)
}

// AdvanceTo 直接流转到指定状态（对齐 kanban_update.py 的 state/flow 命令）。
func (s *Service) AdvanceTo(taskID, toState, remark string) (*model.Task, error) {
	return s.transition(taskID, toState, remark)
}

// TaskAction 处理 block/resume/cancel/done/restart 等操作。
func (s *Service) TaskAction(taskID, action, reason string) (*model.Task, error) {
	target := map[string]string{
		"block":   "Blocked",
		"resume":  "Menxia",
		"unblock": "Menxia",
		"cancel":  "Cancelled",
		"done":    "Done",
		"restart": "Taizi",
	}[action]
	if target == "" {
		return nil, fmt.Errorf("未知操作: %s", action)
	}
	return s.transition(taskID, target, reason)
}

// ReviewAction 门下省审议结果：approve/seal -> Done, reject -> Menxia, revise -> Doing。
func (s *Service) ReviewAction(taskID, action, comment string) (*model.Task, error) {
	target := map[string]string{
		"approve": "Done",
		"seal":    "Done",
		"reject":  "Menxia",
		"revise":  "Doing",
	}[action]
	if target == "" {
		return nil, fmt.Errorf("未知审议动作: %s", action)
	}
	t, err := s.transition(taskID, target, comment)
	if err != nil {
		return nil, err
	}
	if action == "reject" || action == "revise" {
		t.ReviewRound++
		_ = s.Store.UpdateTask(t)
	}
	return t, nil
}

// SetArchive 归档/取消归档。
func (s *Service) SetArchive(taskID string, archived bool) (*model.Task, error) {
	t, err := s.Store.GetTask(taskID)
	if err != nil {
		return nil, err
	}
	t.Archived = archived
	if archived {
		t.ArchivedAt = nowISO()
	} else {
		t.ArchivedAt = ""
	}
	if err := s.Store.UpdateTask(t); err != nil {
		return nil, err
	}
	_ = s.Store.AppendActivity(taskID, "archive", map[string]any{"archived": archived})
	return t, nil
}

// ArchiveAllDone 归档所有 Done 任务，返回数量。
func (s *Service) ArchiveAllDone() (int, error) {
	all, err := s.Store.ListTasks(true)
	if err != nil {
		return 0, err
	}
	n := 0
	for i := range all {
		if all[i].State == "Done" && !all[i].Archived {
			all[i].Archived = true
			all[i].ArchivedAt = nowISO()
			_ = s.Store.UpdateTask(&all[i])
			n++
		}
	}
	return n, nil
}

// CreateTask 新建旨意（初始状态 Taizi）。
func (s *Service) CreateTask(p model.CreateTaskPayload) (*model.Task, error) {
	t := &model.Task{
		ID:        GenTaskID(),
		Title:     store.TitleSafe(p.Title),
		State:     "Taizi",
		Org:       OrgForState("Taizi", p.Org),
		Official:  "emperor",
		Now:       fmt.Sprintf("📜 新旨意下达，待%s分拣", OrgForState("Taizi", p.Org)),
		ETA:       "-",
		Block:     "无",
		FlowLog:   []model.FlowEntry{},
		Todos:     []model.TodoItem{},
		SourceMeta: map[string]any{},
	}
	if p.ID != "" {
		t.ID = p.ID
	}
	if p.Official != "" {
		t.Official = p.Official
	}
	if p.Org != "" {
		t.SourceMeta["targetDept"] = p.Org
	}
	if err := s.Store.CreateTask(t); err != nil {
		return nil, err
	}
	_ = s.Store.AppendActivity(t.ID, "create", map[string]any{"title": t.Title, "org": t.Org})
	return t, nil
}

// CompleteTask 完成任务并写入产出（对齐 kanban_update.py done）。
func (s *Service) CompleteTask(taskID, output string) (*model.Task, error) {
	t, err := s.transition(taskID, "Done", "已完成")
	if err != nil {
		return nil, err
	}
	t.Output = output
	if err := s.Store.UpdateTask(t); err != nil {
		return nil, err
	}
	_ = s.Store.AppendActivity(taskID, "done", map[string]any{"output": output})
	return t, nil
}

// GenTaskID 生成形如 EDICT-20260808-3F7 的任务 ID。
func GenTaskID() string {
	return fmt.Sprintf("EDICT-%s-%04X", time.Now().Format("20060102"), rand.Intn(0xFFFF))
}

func nowISO() string {
	return time.Now().UTC().Format(time.RFC3339)
}

// SetTodo 新增/更新子任务（对齐 kanban_update.py 的 todo 命令）。
func (s *Service) SetTodo(taskID, todoID, title, status, detail string) (*model.Task, error) {
	t, err := s.Store.GetTask(taskID)
	if err != nil {
		return nil, err
	}
	if status == "" {
		status = "not-started"
	}
	found := false
	for i := range t.Todos {
		if fmt.Sprintf("%v", t.Todos[i].ID) == todoID {
			t.Todos[i].Title = title
			t.Todos[i].Status = status
			t.Todos[i].Detail = detail
			found = true
			break
		}
	}
	if !found {
		t.Todos = append(t.Todos, model.TodoItem{ID: todoID, Title: title, Status: status, Detail: detail})
	}
	if err := s.Store.UpdateTask(t); err != nil {
		return nil, err
	}
	_ = s.Store.AppendActivity(taskID, "todo", map[string]any{"id": todoID, "title": title, "status": status})
	return t, nil
}

// AppendProgress 记录实时进展（对齐 kanban_update.py 的 progress 命令）。
// todosPipe 形如 "1|2|3" 表示对应序号的子任务标记为已完成。
func (s *Service) AppendProgress(taskID, text, todosPipe string, tokens int, cost float64, elapsed int) (*model.Task, error) {
	t, err := s.Store.GetTask(taskID)
	if err != nil {
		return nil, err
	}
	t.Now = text
	if todosPipe != "" {
		done := map[string]bool{}
		for _, p := range strings.Split(todosPipe, "|") {
			p = strings.TrimSpace(p)
			if p != "" {
				done[p] = true
			}
		}
		for i := range t.Todos {
			id := fmt.Sprintf("%v", t.Todos[i].ID)
			if done[id] {
				t.Todos[i].Status = "completed"
			}
		}
	}
	if err := s.Store.UpdateTask(t); err != nil {
		return nil, err
	}
	_ = s.Store.AppendActivity(taskID, "progress", map[string]any{
		"text": text, "tokens": tokens, "cost": cost, "elapsed": elapsed,
	})
	return t, nil
}
