// Package api 实现 edict 的 HTTP 服务：路由、JSON 端点、静态资源托管。
// 端点契约严格对齐 edict/edict/frontend/src/api.ts，React 前端无需改动即可对接。
package api

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"edict/internal/model"
	"edict/internal/service"
	"edict/internal/store"
)

// Server 持有业务服务与静态资源根目录。
type Server struct {
	Svc       *service.Service
	Store     *store.Store
	WebRoot   string
	routes    []route
}

type route struct {
	method string
	segs   []string
	h      http.HandlerFunc
}

// New 构造 Server 并注册全部路由。
func New(svc *service.Service, webRoot string) *Server {
	s := &Server{Svc: svc, Store: svc.Store, WebRoot: webRoot}
	s.register()
	return s
}

// ── 路由注册 ──

func (s *Server) add(method, pattern string, h http.HandlerFunc) {
	segs := strings.Split(strings.Trim(pattern, "/"), "/")
	s.routes = append(s.routes, route{method: method, segs: segs, h: h})
}

func (s *Server) register() {
	// 核心数据
	s.add(http.MethodGet, "/api/live-status", s.handleLiveStatus)
	s.add(http.MethodGet, "/api/agent-config", s.handleAgentConfig)
	s.add(http.MethodGet, "/api/model-change-log", s.handleModelChangeLog)
	s.add(http.MethodGet, "/api/agents-status", s.handleAgentsStatus)
	s.add(http.MethodGet, "/api/officials-stats", s.handleOfficialsStats)

	// 任务动态 / 调度
	s.add(http.MethodGet, "/api/task-activity/{id}", s.handleTaskActivity)
	s.add(http.MethodGet, "/api/scheduler-state/{id}", s.handleSchedulerState)
	s.add(http.MethodPost, "/api/scheduler-scan", s.handleSchedulerScan)
	s.add(http.MethodPost, "/api/scheduler-retry", s.handleSchedulerRetry)
	s.add(http.MethodPost, "/api/scheduler-escalate", s.handleSchedulerEscalate)
	s.add(http.MethodPost, "/api/scheduler-rollback", s.handleSchedulerRollback)

	// 天下要闻
	s.add(http.MethodGet, "/api/morning-brief", s.handleMorningBrief)
	s.add(http.MethodGet, "/api/morning-config", s.handleMorningConfig)
	s.add(http.MethodPost, "/api/morning-config", s.handleSaveMorningConfig)
	s.add(http.MethodPost, "/api/morning-brief/refresh", s.handleMorningRefresh)

	// 技能
	s.add(http.MethodGet, "/api/skill-content/{agentId}/{skillName}", s.handleSkillContent)
	s.add(http.MethodGet, "/api/remote-skills-list", s.handleRemoteSkillsList)
	s.add(http.MethodPost, "/api/add-skill", s.handleAddSkill)
	s.add(http.MethodPost, "/api/add-remote-skill", s.handleAddRemoteSkill)
	s.add(http.MethodPost, "/api/update-remote-skill", s.handleUpdateRemoteSkill)
	s.add(http.MethodPost, "/api/remove-remote-skill", s.handleRemoveRemoteSkill)

	// 操作类
	s.add(http.MethodPost, "/api/create-task", s.handleCreateTask)
	s.add(http.MethodPost, "/api/advance-state", s.handleAdvanceState)
	s.add(http.MethodPost, "/api/task-action", s.handleTaskAction)
	s.add(http.MethodPost, "/api/review-action", s.handleReviewAction)
	s.add(http.MethodPost, "/api/archive-task", s.handleArchiveTask)
	s.add(http.MethodPost, "/api/agent-wake", s.handleAgentWake)
	s.add(http.MethodPost, "/api/set-model", s.handleSetModel)
	s.add(http.MethodPost, "/api/set-dispatch-channel", s.handleSetDispatchChannel)

	// 朝堂议政（LLM，Phase 1 占位）
	s.add(http.MethodPost, "/api/court-discuss/start", s.handleCourtDiscussStart)
	s.add(http.MethodPost, "/api/court-discuss/advance", s.handleCourtDiscussAdvance)
	s.add(http.MethodPost, "/api/court-discuss/conclude", s.handleCourtDiscussConclude)
	s.add(http.MethodPost, "/api/court-discuss/destroy", s.handleCourtDiscussDestroy)
	s.add(http.MethodGet, "/api/court-discuss/fate", s.handleCourtDiscussFate)
}

// ServeHTTP 实现 http.Handler：先匹配 API 路由，否则尝试静态资源。
func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if params, h, ok := s.match(r); ok {
		r = withParams(r, params)
		h(w, r)
		return
	}
	s.serveStatic(w, r)
}

// match 解析路径参数。
func (s *Server) match(r *http.Request) (map[string]string, http.HandlerFunc, bool) {
	segs := strings.Split(strings.Trim(r.URL.Path, "/"), "/")
	for _, rt := range s.routes {
		if rt.method != r.Method {
			continue
		}
		if len(rt.segs) != len(segs) {
			continue
		}
		params := map[string]string{}
		ok := true
		for i, seg := range rt.segs {
			if strings.HasPrefix(seg, "{") && strings.HasSuffix(seg, "}") {
				params[seg[1:len(seg)-1]] = segs[i]
			} else if seg != segs[i] {
				ok = false
				break
			}
		}
		if ok {
			return params, rt.h, true
		}
	}
	return nil, nil, false
}

type ctxKey string

const paramsKey ctxKey = "params"

func withParams(r *http.Request, params map[string]string) *http.Request {
	ctx := contextWithParams(r, params)
	return r.WithContext(ctx)
}

// ── JSON 辅助 ──

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeOK(w http.ResponseWriter, msg string) {
	writeJSON(w, http.StatusOK, model.ActionResult{OK: true, Message: msg})
}

func writeErr(w http.ResponseWriter, status int, err error) {
	writeJSON(w, status, model.ActionResult{OK: false, Error: err.Error()})
}

func decodeBody(r *http.Request, v any) error {
	defer r.Body.Close()
	return json.NewDecoder(r.Body).Decode(v)
}

func param(r *http.Request, name string) string {
	if m, ok := r.Context().Value(paramsKey).(map[string]string); ok {
		return m[name]
	}
	return ""
}

// ── 静态资源（托管已构建的 React 前端）──

func (s *Server) serveStatic(w http.ResponseWriter, r *http.Request) {
	if s.WebRoot == "" {
		writeJSON(w, http.StatusNotFound, map[string]any{
			"ok":    false,
			"error": "static hosting 未启用：启动时用 -web 指定前端 dist 目录",
		})
		return
	}
	root := filepath.Clean(s.WebRoot)
	upath := strings.TrimPrefix(r.URL.Path, "/")
	if upath == "" {
		upath = "index.html"
	}
	full := filepath.Join(root, filepath.Clean(upath))
	if !strings.HasPrefix(full, root) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	info, err := os.Stat(full)
	if err != nil || info.IsDir() {
		// SPA fallback：未匹配到静态文件时回退到 index.html
		data, e := os.ReadFile(filepath.Join(root, "index.html"))
		if e != nil {
			writeJSON(w, http.StatusNotFound, map[string]any{"ok": false, "error": "not found"})
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write(data)
		return
	}
	http.ServeFile(w, r, full)
}
