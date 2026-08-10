package tenantchatapi

import "github.com/ceoadmin/CEOadmin/internal/api/shared"

import (
	"encoding/json"
	"net/http"

	"github.com/ceoadmin/CEOadmin/internal/auth"
	"github.com/ceoadmin/CEOadmin/internal/store"
	"github.com/ceoadmin/CEOadmin/internal/tenantchat"
)

// TenantChatView is the JSON shape returned to clients. my_side tells the UI
// which seat the current user owns (so it can gate persona editing); it is
// empty for non-participants / admins.
type TenantChatView struct {
	Conversation *tenantchat.Conversation `json:"conversation"`
	MySide       string                   `json:"my_side,omitempty"`
	AIConfigured bool                     `json:"ai_configured"`
}

// TenantChatMineView is the dashboard payload: all of the user's conversations
// plus their passive-session profile.
type TenantChatMineView struct {
	Conversations []TenantChatView           `json:"conversations"`
	AIConfigured  bool                       `json:"ai_configured"`
	Passive       *tenantchat.PassiveProfile `json:"passive"`
}

func (s *TenantChatHandler) tenantChatIsAdmin(uid string) bool {
	u, err := s.Store.GetUserByID(uid)
	if err != nil {
		return false
	}
	return store.IsAdmin(u.Role)
}

// canAccessConversation reports whether the current user may view / control the
// conversation: they must be a participant or an admin.
func (s *TenantChatHandler) canAccessConversation(uid string, conv *tenantchat.Conversation) bool {
	return conv.IsParticipant(uid) || s.tenantChatIsAdmin(uid)
}

// GET /api/tenant-chat/conversations/mine — all conversations the current user
// participates in, plus their passive-session profile.
func (s *TenantChatHandler) HandleTenantChatMine(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserIDFromContext(r.Context())
	convs, err := tenantchat.Default.ConversationsForUser(uid)
	if err != nil {
		shared.JSONError(w, err.Error(), http.StatusInternalServerError)
		return
	}
	views := make([]TenantChatView, 0, len(convs))
	for _, c := range convs {
		v := TenantChatView{Conversation: c, AIConfigured: tenantchat.Default.GlobalAIConfigured()}
		if side, ok := c.SideOf(uid); ok {
			v.MySide = string(side)
		}
		views = append(views, v)
	}
	passive, _ := tenantchat.Default.GetPassiveProfile(uid)
	out := TenantChatMineView{
		Conversations: views,
		AIConfigured:  tenantchat.Default.GlobalAIConfigured(),
		Passive:       passive,
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(out)
}

// GET /api/tenant-chat/passive — the current user's passive-session profile.
func (s *TenantChatHandler) HandleTenantChatPassiveGet(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserIDFromContext(r.Context())
	prof, err := tenantchat.Default.GetPassiveProfile(uid)
	if err != nil {
		shared.JSONError(w, err.Error(), http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(prof)
}

// PUT /api/tenant-chat/passive — save the current user's passive-session
// settings (the parameters used when others start a chat with them).
func (s *TenantChatHandler) HandleTenantChatPassiveSet(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserIDFromContext(r.Context())
	var req struct {
		Enabled      bool   `json:"enabled"`
		Handle       string `json:"handle"`
		Name         string `json:"name"`
		SystemPrompt string `json:"system_prompt"`
		Topic        string `json:"topic"`
		MaxRounds    int    `json:"max_rounds"`
		DelayMs      int    `json:"delay_ms"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}
	if err := tenantchat.Default.SetPassiveProfile(uid, req.Enabled, req.Handle, req.Name, req.SystemPrompt, req.Topic, req.MaxRounds, req.DelayMs); err != nil {
		shared.JSONError(w, err.Error(), http.StatusBadRequest)
		return
	}
	shared.JSONOK(w)
}

// GET /api/tenant-chat/passive/users — list users that have enabled passive
// chat, for a separate "find someone to chat with" discovery page.
func (s *TenantChatHandler) HandleTenantChatPassiveUsers(w http.ResponseWriter, r *http.Request) {
	list, err := tenantchat.Default.ListPassiveUsers()
	if err != nil {
		shared.JSONError(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if list == nil {
		list = []tenantchat.PassiveProfile{}
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(list)
}

// POST /api/tenant-chat/conversations/start-passive — start a chat with a
// passive user (别人找你聊), identified by their handle (or, for older callers,
// their raw user id). The caller becomes 甲; the passive user is 乙 and their
// seat is pre-filled from their profile.
func (s *TenantChatHandler) HandleTenantChatStartPassive(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Handle string `json:"handle"`
		// Deprecated: kept so links and clients built before handles existed
		// keep working.
		TargetUserID string `json:"target_user_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}
	target := req.Handle
	if target == "" {
		target = req.TargetUserID
	}
	if target == "" {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}
	uid := auth.UserIDFromContext(r.Context())
	conv, err := tenantchat.Default.StartPassive(target, uid)
	if err != nil {
		shared.JSONError(w, err.Error(), http.StatusBadRequest)
		return
	}
	view := TenantChatView{Conversation: conv, MySide: "A", AIConfigured: tenantchat.Default.GlobalAIConfigured()}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(view)
}

// GET /api/tenant-chat/conversations/{id} — a specific conversation, only for
// participants or admins.
func (s *TenantChatHandler) HandleTenantChatGet(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserIDFromContext(r.Context())
	id := r.PathValue("id")
	conv, ok := tenantchat.Default.Get(id)
	if !ok {
		shared.JSONError(w, "对聊会话不存在", http.StatusNotFound)
		return
	}
	if !s.canAccessConversation(uid, conv) {
		shared.JSONError(w, "无权访问该会话", http.StatusForbidden)
		return
	}
	view := TenantChatView{Conversation: conv, AIConfigured: tenantchat.Default.GlobalAIConfigured()}
	if side, ok := conv.SideOf(uid); ok {
		view.MySide = string(side)
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(view)
}

// POST /api/tenant-chat/conversations — create a new conversation. The caller
// becomes 甲 and receives an invite code to share with 乙.
func (s *TenantChatHandler) HandleTenantChatCreate(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserIDFromContext(r.Context())
	conv, err := tenantchat.Default.Create(uid)
	if err != nil {
		shared.JSONError(w, err.Error(), http.StatusBadRequest)
		return
	}
	view := TenantChatView{Conversation: conv, MySide: "A", AIConfigured: tenantchat.Default.GlobalAIConfigured()}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(view)
}

// POST /api/tenant-chat/conversations/join — join a conversation as 乙 using
// its id + invite code.
func (s *TenantChatHandler) HandleTenantChatJoin(w http.ResponseWriter, r *http.Request) {
	var req struct {
		ID   string `json:"id"`
		Code string `json:"code"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.ID == "" || req.Code == "" {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}
	uid := auth.UserIDFromContext(r.Context())
	conv, err := tenantchat.Default.Join(req.ID, req.Code, uid)
	if err != nil {
		shared.JSONError(w, err.Error(), http.StatusBadRequest)
		return
	}
	view := TenantChatView{Conversation: conv, MySide: "B", AIConfigured: tenantchat.Default.GlobalAIConfigured()}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(view)
}

// PUT /api/tenant-chat/conversations/{id}/persona — update YOUR OWN seat only.
func (s *TenantChatHandler) HandleTenantChatPersona(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	uid := auth.UserIDFromContext(r.Context())
	conv, ok := tenantchat.Default.Get(id)
	if !ok {
		shared.JSONError(w, "对聊会话不存在", http.StatusNotFound)
		return
	}
	if !conv.IsParticipant(uid) {
		shared.JSONError(w, "你不是该会话的参与者", http.StatusForbidden)
		return
	}
	var req struct {
		Name         string `json:"name"`
		SystemPrompt string `json:"system_prompt"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}
	if req.Name == "" && req.SystemPrompt == "" {
		shared.JSONError(w, "name or system_prompt required", http.StatusBadRequest)
		return
	}
	if err := tenantchat.Default.SetOwnPersona(id, uid, req.Name, req.SystemPrompt); err != nil {
		shared.JSONError(w, err.Error(), http.StatusBadRequest)
		return
	}
	shared.JSONOK(w)
}

// PUT /api/tenant-chat/conversations/{id}/config — update shared topic / rounds
// / delay. Allowed for participants or admins.
func (s *TenantChatHandler) HandleTenantChatConfig(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	uid := auth.UserIDFromContext(r.Context())
	conv, ok := tenantchat.Default.Get(id)
	if !ok {
		shared.JSONError(w, "对聊会话不存在", http.StatusNotFound)
		return
	}
	if !s.canAccessConversation(uid, conv) {
		shared.JSONError(w, "无权访问该会话", http.StatusForbidden)
		return
	}
	var req struct {
		Topic     string `json:"topic"`
		MaxRounds int    `json:"max_rounds"`
		DelayMs   int    `json:"delay_ms"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}
	if req.MaxRounds < 0 || req.MaxRounds > 200 {
		shared.JSONError(w, "max_rounds must be between 0 and 200", http.StatusBadRequest)
		return
	}
	if req.DelayMs < 0 || req.DelayMs > 30000 {
		shared.JSONError(w, "delay_ms must be between 0 and 30000", http.StatusBadRequest)
		return
	}
	if err := tenantchat.Default.SetConfig(id, req.Topic, req.MaxRounds, req.DelayMs); err != nil {
		shared.JSONError(w, err.Error(), http.StatusBadRequest)
		return
	}
	shared.JSONOK(w)
}

// POST /api/tenant-chat/conversations/{id}/control — {action: start|pause|step|reset}.
func (s *TenantChatHandler) HandleTenantChatControl(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	uid := auth.UserIDFromContext(r.Context())
	conv, ok := tenantchat.Default.Get(id)
	if !ok {
		shared.JSONError(w, "对聊会话不存在", http.StatusNotFound)
		return
	}
	if !s.canAccessConversation(uid, conv) {
		shared.JSONError(w, "无权访问该会话", http.StatusForbidden)
		return
	}
	var req struct {
		Action string `json:"action"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Action == "" {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}
	var err error
	switch req.Action {
	case "start":
		err = tenantchat.Default.Start(id)
	case "pause":
		tenantchat.Default.Pause(id)
	case "step":
		err = tenantchat.Default.Step(id)
	case "reset":
		tenantchat.Default.Reset(id)
	default:
		shared.JSONError(w, "unknown action", http.StatusBadRequest)
		return
	}
	if err != nil {
		shared.JSONError(w, err.Error(), http.StatusBadRequest)
		return
	}
	shared.JSONOK(w)
}

// ---- Per-tenant personalized memory ----

// GET /api/tenant-chat/memory — list the current tenant's memories (?type= filter).
func (s *TenantChatHandler) HandleTenantChatMemoryList(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserIDFromContext(r.Context())
	_ = tenantchat.Default.EnsureMemoryTenant(uid)
	typ := r.URL.Query().Get("type")
	mems, err := tenantchat.Default.ListMemories(uid, typ)
	if err != nil {
		shared.JSONError(w, err.Error(), http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(mems)
}

// POST /api/tenant-chat/memory — add a memory {type, content}.
func (s *TenantChatHandler) HandleTenantChatMemoryAdd(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserIDFromContext(r.Context())
	var req struct {
		Type    string `json:"type"`
		Content string `json:"content"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Content == "" {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}
	_ = tenantchat.Default.EnsureMemoryTenant(uid)
	m, err := tenantchat.Default.AddMemory(uid, req.Type, req.Content)
	if err != nil {
		shared.JSONError(w, err.Error(), http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(m)
}

// GET /api/tenant-chat/memory/profile — the current tenant's memory profile.
func (s *TenantChatHandler) HandleTenantChatMemoryProfileGet(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserIDFromContext(r.Context())
	_ = tenantchat.Default.EnsureMemoryTenant(uid)
	p, err := tenantchat.Default.GetMemoryProfile(uid)
	if err != nil {
		shared.JSONError(w, err.Error(), http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(p)
}

// PUT /api/tenant-chat/memory/profile — set name / preferences.
func (s *TenantChatHandler) HandleTenantChatMemoryProfileSet(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserIDFromContext(r.Context())
	var req struct {
		Name        string            `json:"name"`
		Preferences map[string]string `json:"preferences"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}
	_ = tenantchat.Default.EnsureMemoryTenant(uid)
	if err := tenantchat.Default.SetMemoryProfile(uid, req.Name, req.Preferences); err != nil {
		shared.JSONError(w, err.Error(), http.StatusBadRequest)
		return
	}
	shared.JSONOK(w)
}

// DELETE /api/tenant-chat/memory/{mid} — delete a memory by id.
func (s *TenantChatHandler) HandleTenantChatMemoryDelete(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserIDFromContext(r.Context())
	id := r.PathValue("mid")
	if id == "" {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}
	if err := tenantchat.Default.DeleteMemory(uid, id); err != nil {
		shared.JSONError(w, err.Error(), http.StatusInternalServerError)
		return
	}
	shared.JSONOK(w)
}
