package api

import (
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"edict/internal/model"
	"edict/internal/service"
)

func nowISO() string { return time.Now().UTC().Format(time.RFC3339) }

// ── 核心数据 ──

func (s *Server) handleLiveStatus(w http.ResponseWriter, r *http.Request) {
	tasks, err := s.Store.ListTasks(false)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err)
		return
	}
	writeJSON(w, http.StatusOK, model.LiveStatus{Tasks: tasks, SyncStatus: model.SyncStatus{OK: true}})
}

func (s *Server) handleAgentConfig(w http.ResponseWriter, r *http.Request) {
	agents, err := s.Store.ListAgents()
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err)
		return
	}
	writeJSON(w, http.StatusOK, model.AgentConfig{
		Agents:          agents,
		KnownModels:     service.KnownModels(),
		DispatchChannel: s.Store.GetDispatchChannel(),
	})
}

func (s *Server) handleModelChangeLog(w http.ResponseWriter, r *http.Request) {
	log, err := s.Store.ListModelChangeLog()
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err)
		return
	}
	writeJSON(w, http.StatusOK, log)
}

func (s *Server) handleAgentsStatus(w http.ResponseWriter, r *http.Request) {
	agents, _ := s.Store.ListAgents()
	infos := make([]model.AgentStatusInfo, 0, len(agents))
	for _, a := range agents {
		infos = append(infos, model.AgentStatusInfo{
			ID: a.ID, Label: a.Label, Emoji: a.Emoji, Role: a.Role,
			Status: "offline", StatusLabel: "离线",
		})
	}
	writeJSON(w, http.StatusOK, model.AgentsStatusData{
		OK:        true,
		Gateway:   model.GatewayStatus{Alive: false, Probe: false, Status: "unknown"},
		Agents:    infos,
		CheckedAt: nowISO(),
	})
}

// TODO(phase2): 官员 token/费用统计需对接 OpenClaw 运行时埋点。
func (s *Server) handleOfficialsStats(w http.ResponseWriter, r *http.Request) {
	d := model.OfficialsData{Officials: []model.OfficialInfo{}}
	d.TopOfficial = ""
	writeJSON(w, http.StatusOK, d)
}

// ── 任务动态 / 调度 ──

func (s *Server) handleTaskActivity(w http.ResponseWriter, r *http.Request) {
	id := param(r, "id")
	acts, err := s.Store.GetTaskActivity(id)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err)
		return
	}
	writeJSON(w, http.StatusOK, model.TaskActivityData{OK: true, Activity: acts})
}

func (s *Server) handleSchedulerState(w http.ResponseWriter, r *http.Request) {
	id := param(r, "id")
	var info model.SchedulerInfo
	var exists int
	_ = s.Store.DB.QueryRow(
		`SELECT 1 FROM scheduler WHERE task_id=?`, id,
	).Scan(&exists)
	if exists == 1 {
		_ = s.Store.DB.QueryRow(
			`SELECT retry_count, escalation_level, last_dispatch_status, last_progress_at, last_dispatch_at, last_dispatch_agent, enabled, auto_rollback, stall_threshold_sec FROM scheduler WHERE task_id=?`,
			id,
		).Scan(&info.RetryCount, &info.EscalationLevel, &info.LastDispatchStatus, &info.LastProgressAt,
			&info.LastDispatchAt, &info.LastDispatchAgent, &info.Enabled, &info.AutoRollback, &info.StallThresholdSec)
		writeJSON(w, http.StatusOK, model.SchedulerStateData{OK: true, Scheduler: &info})
		return
	}
	writeJSON(w, http.StatusOK, model.SchedulerStateData{OK: false, Error: "scheduler 未初始化"})
}

func (s *Server) handleSchedulerScan(w http.ResponseWriter, r *http.Request) {
	var p struct {
		ThresholdSec int `json:"thresholdSec"`
	}
	_ = decodeBody(r, &p)
	if p.ThresholdSec <= 0 {
		p.ThresholdSec = 180
	}
	// TODO(phase2): 扫描 Doing/Review 状态任务，按阈值识别停滞并给出建议动作。
	writeJSON(w, http.StatusOK, map[string]any{
		"ok": true, "count": 0, "actions": []model.ScanAction{}, "checkedAt": nowISO(),
		"message": "scheduler scan stub (phase 1)",
	})
}

func (s *Server) handleSchedulerRetry(w http.ResponseWriter, r *http.Request) {
	var p struct {
		TaskID string `json:"taskId"`
		Reason string `json:"reason"`
	}
	_ = decodeBody(r, &p)
	_ = p
	writeOK(w, "scheduler retry stub (phase 1)")
}

func (s *Server) handleSchedulerEscalate(w http.ResponseWriter, r *http.Request) {
	var p struct {
		TaskID string `json:"taskId"`
		Reason string `json:"reason"`
	}
	_ = decodeBody(r, &p)
	writeOK(w, "scheduler escalate stub (phase 1)")
}

func (s *Server) handleSchedulerRollback(w http.ResponseWriter, r *http.Request) {
	var p struct {
		TaskID string `json:"taskId"`
		Reason string `json:"reason"`
	}
	_ = decodeBody(r, &p)
	writeOK(w, "scheduler rollback stub (phase 1)")
}

// ── 天下要闻 ──

func (s *Server) handleMorningBrief(w http.ResponseWriter, r *http.Request) {
	var cats string
	_ = s.Store.DB.QueryRow(`SELECT categories FROM morning_brief WHERE id=1`).Scan(&cats)
	if cats == "" {
		cats = "{}"
	}
	var m map[string][]model.MorningNewsItem
	_ = json.Unmarshal([]byte(cats), &m)
	writeJSON(w, http.StatusOK, model.MorningBrief{Categories: m})
}

func (s *Server) handleMorningConfig(w http.ResponseWriter, r *http.Request) {
	var categories, keywords, feeds, webhook string
	_ = s.Store.DB.QueryRow(
		`SELECT categories, keywords, custom_feeds, feishu_webhook FROM morning_config WHERE id=1`,
	).Scan(&categories, &keywords, &feeds, &webhook)
	cfg := model.SubConfig{FeishuWebhook: webhook}
	_ = json.Unmarshal([]byte(orDefault(categories, "[]")), &cfg.Categories)
	_ = json.Unmarshal([]byte(orDefault(keywords, "[]")), &cfg.Keywords)
	_ = json.Unmarshal([]byte(orDefault(feeds, "[]")), &cfg.CustomFeeds)
	writeJSON(w, http.StatusOK, cfg)
}

func (s *Server) handleSaveMorningConfig(w http.ResponseWriter, r *http.Request) {
	var cfg model.SubConfig
	if err := decodeBody(r, &cfg); err != nil {
		writeErr(w, http.StatusBadRequest, err)
		return
	}
	cats, _ := json.Marshal(cfg.Categories)
	kws, _ := json.Marshal(cfg.Keywords)
	feeds, _ := json.Marshal(cfg.CustomFeeds)
	_, err := s.Store.DB.Exec(
		`INSERT INTO morning_config (id, categories, keywords, custom_feeds, feishu_webhook) VALUES (1,?,?,?,?)
		 ON CONFLICT(id) DO UPDATE SET categories=excluded.categories, keywords=excluded.keywords, custom_feeds=excluded.custom_feeds, feishu_webhook=excluded.feishu_webhook`,
		string(cats), string(kws), string(feeds), cfg.FeishuWebhook,
	)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err)
		return
	}
	writeOK(w, "morning config saved")
}

// TODO(phase2): 抓取 RSS + LLM 摘要，写入 morning_brief。
func (s *Server) handleMorningRefresh(w http.ResponseWriter, r *http.Request) {
	writeOK(w, "morning brief refresh stub (phase 1)")
}

// ── 技能 ──

func (s *Server) handleSkillContent(w http.ResponseWriter, r *http.Request) {
	agentID := param(r, "agentId")
	skillName := param(r, "skillName")
	writeJSON(w, http.StatusOK, model.SkillContentResult{
		OK: false, Agent: agentID, Name: skillName, Error: "skill content not available in phase 1",
	})
}

func (s *Server) handleRemoteSkillsList(w http.ResponseWriter, r *http.Request) {
	rows, err := s.Store.DB.Query(
		`SELECT skill_name, agent_id, source_url, description, local_path, added_at, last_updated, status FROM remote_skills ORDER BY added_at DESC`,
	)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err)
		return
	}
	defer rows.Close()
	var items []model.RemoteSkillItem
	for rows.Next() {
		var it model.RemoteSkillItem
		_ = rows.Scan(&it.SkillName, &it.AgentID, &it.SourceURL, &it.Description, &it.LocalPath, &it.AddedAt, &it.LastUpdated, &it.Status)
		items = append(items, it)
	}
	writeJSON(w, http.StatusOK, model.RemoteSkillsListResult{OK: true, RemoteSkills: items, Count: len(items), ListedAt: nowISO()})
}

func (s *Server) handleAddSkill(w http.ResponseWriter, r *http.Request) {
	// TODO(phase2): 写入本地技能（skills 表/目录）。
	writeOK(w, "add-skill stub (phase 1)")
}

func (s *Server) handleAddRemoteSkill(w http.ResponseWriter, r *http.Request) {
	var p struct {
		AgentID    string `json:"agentId"`
		SkillName  string `json:"skillName"`
		SourceURL  string `json:"sourceUrl"`
		Description string `json:"description"`
	}
	if err := decodeBody(r, &p); err != nil {
		writeErr(w, http.StatusBadRequest, err)
		return
	}
	now := nowISO()
	_, err := s.Store.DB.Exec(
		`INSERT INTO remote_skills (skill_name, agent_id, source_url, description, local_path, added_at, last_updated, status) VALUES (?,?,?,?,?,?,?,?)
		 ON CONFLICT(skill_name, agent_id) DO UPDATE SET source_url=excluded.source_url, description=excluded.description, last_updated=excluded.last_updated`,
		p.SkillName, p.AgentID, p.SourceURL, p.Description, "skills/"+p.AgentID+"/"+p.SkillName, now, now, "valid",
	)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok": true, "skillName": p.SkillName, "agentId": p.AgentID, "source": p.SourceURL,
		"localPath": "skills/" + p.AgentID + "/" + p.SkillName, "size": 0, "addedAt": now,
	})
}

func (s *Server) handleUpdateRemoteSkill(w http.ResponseWriter, r *http.Request) {
	var p struct {
		AgentID   string `json:"agentId"`
		SkillName string `json:"skillName"`
	}
	_ = decodeBody(r, &p)
	_, err := s.Store.DB.Exec(`UPDATE remote_skills SET last_updated=? WHERE skill_name=? AND agent_id=?`, nowISO(), p.SkillName, p.AgentID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err)
		return
	}
	writeOK(w, "remote skill updated")
}

func (s *Server) handleRemoveRemoteSkill(w http.ResponseWriter, r *http.Request) {
	var p struct {
		AgentID   string `json:"agentId"`
		SkillName string `json:"skillName"`
	}
	_ = decodeBody(r, &p)
	_, err := s.Store.DB.Exec(`DELETE FROM remote_skills WHERE skill_name=? AND agent_id=?`, p.SkillName, p.AgentID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err)
		return
	}
	writeOK(w, "remote skill removed")
}

// ── 操作类 ──

func (s *Server) handleCreateTask(w http.ResponseWriter, r *http.Request) {
	var p model.CreateTaskPayload
	if err := decodeBody(r, &p); err != nil {
		writeErr(w, http.StatusBadRequest, err)
		return
	}
	t, err := s.Svc.CreateTask(p)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "message": "created", "taskId": t.ID})
}

func (s *Server) handleAdvanceState(w http.ResponseWriter, r *http.Request) {
	var p struct {
		TaskID  string `json:"taskId"`
		Comment string `json:"comment"`
	}
	if err := decodeBody(r, &p); err != nil {
		writeErr(w, http.StatusBadRequest, err)
		return
	}
	if _, err := s.Svc.AdvanceState(p.TaskID, p.Comment); err != nil {
		writeErr(w, http.StatusBadRequest, err)
		return
	}
	writeOK(w, "advanced")
}

func (s *Server) handleTaskAction(w http.ResponseWriter, r *http.Request) {
	var p struct {
		TaskID string `json:"taskId"`
		Action string `json:"action"`
		Reason string `json:"reason"`
	}
	if err := decodeBody(r, &p); err != nil {
		writeErr(w, http.StatusBadRequest, err)
		return
	}
	if _, err := s.Svc.TaskAction(p.TaskID, p.Action, p.Reason); err != nil {
		writeErr(w, http.StatusBadRequest, err)
		return
	}
	writeOK(w, "action: "+p.Action)
}

func (s *Server) handleReviewAction(w http.ResponseWriter, r *http.Request) {
	var p struct {
		TaskID  string `json:"taskId"`
		Action  string `json:"action"`
		Comment string `json:"comment"`
	}
	if err := decodeBody(r, &p); err != nil {
		writeErr(w, http.StatusBadRequest, err)
		return
	}
	if _, err := s.Svc.ReviewAction(p.TaskID, p.Action, p.Comment); err != nil {
		writeErr(w, http.StatusBadRequest, err)
		return
	}
	writeOK(w, "review: "+p.Action)
}

func (s *Server) handleArchiveTask(w http.ResponseWriter, r *http.Request) {
	var p struct {
		TaskID        string `json:"taskId"`
		Archived      bool   `json:"archived"`
		ArchiveAllDone bool  `json:"archiveAllDone"`
	}
	if err := decodeBody(r, &p); err != nil {
		writeErr(w, http.StatusBadRequest, err)
		return
	}
	if p.ArchiveAllDone {
		n, err := s.Svc.ArchiveAllDone()
		if err != nil {
			writeErr(w, http.StatusInternalServerError, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "count": n})
		return
	}
	if _, err := s.Svc.SetArchive(p.TaskID, p.Archived); err != nil {
		writeErr(w, http.StatusBadRequest, err)
		return
	}
	writeOK(w, "archive updated")
}

// TODO(phase2): 经网关唤醒 Agent 运行时。
func (s *Server) handleAgentWake(w http.ResponseWriter, r *http.Request) {
	var p struct {
		AgentID string `json:"agentId"`
	}
	_ = decodeBody(r, &p)
	writeOK(w, "agent wake stub (phase 1): "+p.AgentID)
}

func (s *Server) handleSetModel(w http.ResponseWriter, r *http.Request) {
	var p struct {
		AgentID string `json:"agentId"`
		Model   string `json:"model"`
	}
	if err := decodeBody(r, &p); err != nil {
		writeErr(w, http.StatusBadRequest, err)
		return
	}
	if err := s.Store.SetAgentModel(p.AgentID, p.Model); err != nil {
		writeErr(w, http.StatusInternalServerError, err)
		return
	}
	writeOK(w, "model set for " + p.AgentID)
}

func (s *Server) handleSetDispatchChannel(w http.ResponseWriter, r *http.Request) {
	var p struct {
		Channel string `json:"channel"`
	}
	if err := decodeBody(r, &p); err != nil {
		writeErr(w, http.StatusBadRequest, err)
		return
	}
	if err := s.Store.SetDispatchChannel(p.Channel); err != nil {
		writeErr(w, http.StatusInternalServerError, err)
		return
	}
	writeOK(w, "dispatch channel: "+p.Channel)
}

// ── 朝堂议政（LLM，Phase 1 占位）──

func (s *Server) handleCourtDiscussStart(w http.ResponseWriter, r *http.Request) {
	var p struct {
		Topic    string   `json:"topic"`
		Officials []string `json:"officials"`
		TaskID   string   `json:"taskId"`
	}
	_ = decodeBody(r, &p)
	sessionID := "CD-" + strings.ToUpper(time.Now().Format("20060102-150405"))
	officalsJSON, _ := json.Marshal(p.Officials)
	_, _ = s.Store.DB.Exec(
		`INSERT INTO court_discuss (session_id, topic, officials, task_id, round, messages, created_at, updated_at) VALUES (?,?,?,?,0,'[]',?,?)
		 ON CONFLICT(session_id) DO UPDATE SET topic=excluded.topic`,
		sessionID, p.Topic, string(officalsJSON), p.TaskID, nowISO(), nowISO(),
	)
	writeJSON(w, http.StatusOK, model.CourtDiscussResult{
		OK: true, SessionID: sessionID, Topic: p.Topic, Round: 0,
		SceneNote: "朝堂议政占位（Phase 1，未接入 LLM）", TotalMessages: 0,
	})
}

func (s *Server) handleCourtDiscussAdvance(w http.ResponseWriter, r *http.Request) {
	var p struct {
		SessionID   string `json:"sessionId"`
		UserMessage string `json:"userMessage"`
		Decree      string `json:"decree"`
	}
	_ = decodeBody(r, &p)
	writeJSON(w, http.StatusOK, model.CourtDiscussResult{
		OK: true, SessionID: p.SessionID, Round: 1,
		SceneNote: "朝堂议政推进占位（Phase 1，未接入 LLM）", TotalMessages: 0,
	})
}

func (s *Server) handleCourtDiscussConclude(w http.ResponseWriter, r *http.Request) {
	var p struct {
		SessionID string `json:"sessionId"`
	}
	_ = decodeBody(r, &p)
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "summary": "（Phase 1 占位）"})
}

func (s *Server) handleCourtDiscussDestroy(w http.ResponseWriter, r *http.Request) {
	var p struct {
		SessionID string `json:"sessionId"`
	}
	_ = decodeBody(r, &p)
	_, _ = s.Store.DB.Exec(`DELETE FROM court_discuss WHERE session_id=?`, p.SessionID)
	writeOK(w, "court discuss destroyed")
}

func (s *Server) handleCourtDiscussFate(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "event": "（Phase 1 占位）"})
}

// ── 工具 ──

func orDefault(s, def string) string {
	if s == "" {
		return def
	}
	return s
}
