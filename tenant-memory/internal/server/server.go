package server

import (
	"encoding/json"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"

	"tenant-memory/internal/config"
	"tenant-memory/internal/embed"
	"tenant-memory/internal/llm"
	"tenant-memory/internal/store"
	"tenant-memory/internal/usage"
)

// Server 持有配置、存储与 LLM 客户端。
type Server struct {
	cfg   *config.Config
	st    store.Store
	llm   *llm.Client
	embed embed.Embedder
}

// New 构造服务。
func New(cfg *config.Config, st store.Store) *Server {
	var e embed.Embedder
	if cfg.EmbedAPIKey != "" {
		e = &embed.RemoteEmbedder{
			BaseURL: cfg.EmbedBaseURL,
			APIKey:  cfg.EmbedAPIKey,
			Model:   cfg.EmbedModel,
			HTTP:    &http.Client{},
		}
	} else {
		e = &embed.LocalEmbedder{}
	}
	return &Server{cfg: cfg, st: st, llm: llm.New(cfg.LLMBaseURL, cfg.LLMAPIKey, cfg.LLMModel), embed: e}
}

// Retrieve 对该租户的 memories 做向量召回，返回与 query 最相关的 top-K。
// 当 query 与所有记忆都不相关（最高相似度 <= 0，例如无关 query 或全负向量）时，
// 不再返回空结果，而是回退到最近的 k 条记忆，保证仍注入个性化上下文。
func (s *Server) Retrieve(tenant, query string, k int) ([]store.Memory, error) {
	mems, err := s.st.ListMemories(tenant, "")
	if err != nil {
		return nil, err
	}
	if len(mems) == 0 {
		return nil, nil
	}
	texts := make([]string, 0, len(mems)+1)
	texts = append(texts, query)
	for _, m := range mems {
		texts = append(texts, m.Content)
	}
	vecs, err := s.embed.Embed(texts)
	if err != nil {
		return nil, err
	}
	qv := vecs[0]
	type scored struct {
		m     store.Memory
		score float64
	}
	ranked := make([]scored, 0, len(mems))
	for i, m := range mems {
		sc := embed.Cosine(qv, vecs[i+1])
		// Keep every memory with a non-negative similarity. Negative scores are
		// possible with some embedding models; an unrelated query under the
		// local TF-IDF embedder yields 0 for all of them (see the fallback below).
		if sc >= 0 {
			ranked = append(ranked, scored{m, sc})
		}
	}
	sort.Slice(ranked, func(i, j int) bool { return ranked[i].score > ranked[j].score })

	// If nothing is even weakly relevant to the query (best score <= 0 — e.g. an
	// unrelated query, or all-negative similarities), don't return an empty
	// context. Fall back to the most recent k memories instead.
	if len(ranked) == 0 || ranked[0].score <= 0 {
		recent := make([]store.Memory, len(mems))
		copy(recent, mems)
		sort.Slice(recent, func(i, j int) bool { return recent[i].CreatedAt > recent[j].CreatedAt })
		if k > 0 && len(recent) > k {
			recent = recent[:k]
		}
		return recent, nil
	}

	if k > 0 && len(ranked) > k {
		ranked = ranked[:k]
	}
	out := make([]store.Memory, len(ranked))
	for i, r := range ranked {
		out[i] = r.m
	}
	return out, nil
}

// Handler 返回路由。
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", s.healthz)
	mux.HandleFunc("/debug", s.handleDebug)
	mux.HandleFunc("/context/", s.handleContext)
	mux.HandleFunc("/tenants/", s.handleTenants)
	mux.HandleFunc("/chat", s.handleChat)
	mux.HandleFunc("/usage", s.handleUsage)
	return mux
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

// handleDebug 暴露启动预检结果，便于运维/排错时快速确认各依赖是否就绪。
func (s *Server) handleDebug(w http.ResponseWriter, r *http.Request) {
	results := s.Preflight()
	writeJSON(w, 200, map[string]any{
		"store":     s.cfg.Store,
		"model":     s.cfg.LLMModel,
		"retrieve_k": s.cfg.RetrieveK,
		"checks":    results,
		"fatal_failure": HasFatalFailure(results),
	})
}

func (s *Server) healthz(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, map[string]any{"status": "ok", "store": s.cfg.Store, "model": s.cfg.LLMModel})
}

// /context/<tenant> 返回渲染好的记忆上下文（供注入 prompt）。
// 带 ?q= 时对该租户记忆做向量召回，仅返回最相关的 top-K；否则返回全部。
func (s *Server) handleContext(w http.ResponseWriter, r *http.Request) {
	id := strings.TrimPrefix(r.URL.Path, "/context/")
	if id == "" {
		http.Error(w, "tenant id required", http.StatusBadRequest)
		return
	}
	q := r.URL.Query().Get("q")
	var (
		ctx string
		err error
	)
	if q != "" {
		mems, rerr := s.Retrieve(id, q, s.cfg.RetrieveK)
		if rerr != nil {
			http.Error(w, rerr.Error(), http.StatusInternalServerError)
			return
		}
		var p *store.Profile
		if p, err = s.st.GetProfile(id); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		ctx = store.RenderText(p, mems)
	} else {
		ctx, err = s.st.RenderContext(id)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
	}
	writeJSON(w, 200, map[string]string{"tenant_id": id, "context": ctx})
}

// /tenants/<id>/profile | /tenants/<id>/memories[/mid]
func (s *Server) handleTenants(w http.ResponseWriter, r *http.Request) {
	rest := strings.TrimPrefix(r.URL.Path, "/tenants/")
	parts := strings.Split(rest, "/")
	if len(parts) < 1 || parts[0] == "" {
		http.Error(w, "tenant id required", http.StatusBadRequest)
		return
	}
	id := parts[0]
	if err := s.st.EnsureTenant(id); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if len(parts) >= 2 && parts[1] == "profile" {
		s.handleProfile(w, r, id)
		return
	}
	if len(parts) >= 2 && parts[1] == "memories" {
		mid := ""
		if len(parts) >= 3 {
			mid = parts[2]
		}
		s.handleMemories(w, r, id, mid)
		return
	}
	http.Error(w, "not found", http.StatusNotFound)
}

func (s *Server) handleProfile(w http.ResponseWriter, r *http.Request, id string) {
	switch r.Method {
	case http.MethodGet:
		p, err := s.st.GetProfile(id)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		writeJSON(w, 200, p)
	case http.MethodPost:
		var body struct {
			Name        string            `json:"name"`
			Preferences map[string]string `json:"preferences"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		if err := s.st.SetProfile(id, body.Name, body.Preferences); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		writeJSON(w, 200, map[string]string{"status": "ok", "tenant_id": id})
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (s *Server) handleMemories(w http.ResponseWriter, r *http.Request, id, mid string) {
	switch r.Method {
	case http.MethodGet:
		if mid != "" {
			m, err := s.st.GetMemory(id, mid)
			if err != nil {
				http.Error(w, err.Error(), http.StatusNotFound)
				return
			}
			writeJSON(w, 200, m)
			return
		}
		typ := r.URL.Query().Get("type")
		mems, err := s.st.ListMemories(id, typ)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		writeJSON(w, 200, mems)
	case http.MethodPost:
		var body struct {
			Type    string `json:"type"`
			Content string `json:"content"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.Content == "" {
			http.Error(w, "content required", http.StatusBadRequest)
			return
		}
		m, err := s.st.AddMemory(id, body.Type, body.Content)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		writeJSON(w, 201, m)
	case http.MethodDelete:
		if mid == "" {
			http.Error(w, "memory id required", http.StatusBadRequest)
			return
		}
		if err := s.st.DeleteMemory(id, mid); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		writeJSON(w, 200, map[string]string{"status": "ok", "deleted": mid})
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

// POST /chat {tenant_id, message} -> 注入记忆上下文后调用 LLM。
func (s *Server) handleChat(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "POST required", http.StatusMethodNotAllowed)
		return
	}
	var req struct {
		TenantID string `json:"tenant_id"`
		Message  string `json:"message"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.TenantID == "" || req.Message == "" {
		http.Error(w, "tenant_id and message required", http.StatusBadRequest)
		return
	}
	if err := s.st.EnsureTenant(req.TenantID); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	// 向量召回与本租户最相关的 top-K 记忆，再渲染成上下文
	mems, rerr := s.Retrieve(req.TenantID, req.Message, s.cfg.RetrieveK)
	if rerr != nil {
		http.Error(w, "retrieve: "+rerr.Error(), http.StatusInternalServerError)
		return
	}
	p, perr := s.st.GetProfile(req.TenantID)
	if perr != nil {
		http.Error(w, perr.Error(), http.StatusInternalServerError)
		return
	}
	ctx := store.RenderText(p, mems)
	system := "你是企业级 AI 助手。请依据以下该租户的个性化记忆与偏好进行个性化、连贯的回复。\n\n" + ctx
	reply, u, err := s.llm.Chat(system, req.Message)
	if err != nil {
		http.Error(w, "llm: "+err.Error(), http.StatusBadGateway)
		return
	}
	if u != nil {
		usage.Record(usage.UsageRecord{
			TenantID:         req.TenantID,
			ModelType:        "chat",
			Model:            s.cfg.LLMModel,
			PromptTokens:     u.PromptTokens,
			CompletionTokens: u.CompletionTokens,
			TotalTokens:      u.TotalTokens,
			CachedTokens:     u.CachedTokens,
			ReasoningTokens:  u.ReasoningTokens,
			DurationMS:       u.DurationMS,
			CreatedAt:        time.Now().Unix(),
		})
	}
	writeJSON(w, 200, map[string]any{
		"tenant_id":    req.TenantID,
		"reply":        reply,
		"context_used": ctx,
	})
}

// GET /usage?tenant_id=<id> 返回按 (租户, 模型, 类型) 聚合的 token 用量，
// 与 Hub 的 llm_usage 记录口径一致，便于核对应用侧使用系统 LLM 的消耗。
func (s *Server) handleUsage(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "GET required", http.StatusMethodNotAllowed)
		return
	}
	f := store.UsageFilter{TenantID: r.URL.Query().Get("tenant_id")}
	if v := r.URL.Query().Get("limit"); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			f.Limit = n
		}
	}
	rows, err := s.st.ListLLMUsageAgg(f)
	if err != nil {
		http.Error(w, "aggregate: "+err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, 200, map[string]any{"usage": rows})
}
