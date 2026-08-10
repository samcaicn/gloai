package api

import (
	"github.com/ceoadmin/CEOadmin/internal/api/tenantchat"
)

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/ceoadmin/CEOadmin/internal/ai"
	"github.com/ceoadmin/CEOadmin/internal/auth"
	"github.com/ceoadmin/CEOadmin/internal/store"
	"github.com/ceoadmin/CEOadmin/internal/tenantchat"
)

// memConfig is an in-memory configStore for the tenant-chat API tests.
type memConfig struct {
	m map[string]string
}

func newMemConfig() *memConfig { return &memConfig{m: map[string]string{}} }

func (s *memConfig) GetConfig(k string) (string, error) { return s.m[k], nil }
func (s *memConfig) SetConfig(k, v string) error        { s.m[k] = v; return nil }
func (s *memConfig) DeleteConfig(k string) error        { delete(s.m, k); return nil }
func (s *memConfig) ListConfigByPrefix(p string) (map[string]string, error) {
	out := map[string]string{}
	for k, v := range s.m {
		if strings.HasPrefix(k, p) {
			out[k] = v
		}
	}
	return out, nil
}

func ctxWithUser(uid string) context.Context {
	return auth.WithUserID(context.Background(), uid)
}

// mkReq builds a request with the user context, JSON content type, and the
// {id} path value set (handlers read r.PathValue("id")).
func mkReq(t *testing.T, method, path, id string, body []byte, uid string) *http.Request {
	t.Helper()
	var r *http.Request
	if body != nil {
		r = httptest.NewRequest(method, path, bytes.NewReader(body))
	} else {
		r = httptest.NewRequest(method, path, nil)
	}
	if id != "" {
		r.SetPathValue("id", id)
	}
	r.Header.Set("Content-Type", "application/json")
	return r.WithContext(ctxWithUser(uid))
}

// newTenantChatServer wires the global tenantchat.Default to a fresh in-memory
// store with the system OpenAI interface configured, and installs a fake LLM so
// control actions don't hit the network.
func newTenantChatServer(t *testing.T) (*tenantchatapi.TenantChatHandler, *memConfig) {
	t.Helper()
	mc := newMemConfig()
	mc.SetConfig("ai.api_key", "sk-test")
	mc.SetConfig("ai.model", "gpt-test")
	tenantchat.Default.Init(mc)
	tenantchat.SetAICompletion(func(_ context.Context, _ store.AIConfig, _ []ai.Message, _ []ai.Tool) (*ai.CompletionResult, error) {
		return &ai.CompletionResult{Content: "（模拟回复）"}, nil
	})
	t.Cleanup(func() { tenantchat.SetAICompletion(ai.CompleteMessages) })
	return tenantchatapi.NewTenantChatHandler(nil), mc
}

func decodeView(t *testing.T, rec *httptest.ResponseRecorder) tenantchatapi.TenantChatView {
	t.Helper()
	var v tenantchatapi.TenantChatView
	if err := json.Unmarshal(rec.Body.Bytes(), &v); err != nil {
		t.Fatalf("decode view: %v (body=%s)", err, rec.Body.String())
	}
	return v
}

func decodeMine(t *testing.T, rec *httptest.ResponseRecorder) tenantchatapi.TenantChatMineView {
	t.Helper()
	var v tenantchatapi.TenantChatMineView
	if err := json.Unmarshal(rec.Body.Bytes(), &v); err != nil {
		t.Fatalf("decode mine: %v (body=%s)", err, rec.Body.String())
	}
	return v
}

func TestTenantChatMineEmpty(t *testing.T) {
	tcH, _ := newTenantChatServer(t)
	rec := httptest.NewRecorder()
	req := mkReq(t, http.MethodGet, "/api/tenant-chat/conversations/mine", "", nil, "userA")
	tcH.HandleTenantChatMine(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("code = %d", rec.Code)
	}
	m := decodeMine(t, rec)
	if len(m.Conversations) != 0 {
		t.Errorf("expected no conversations yet, got %d", len(m.Conversations))
	}
	if !m.AIConfigured {
		t.Errorf("expected ai_configured true (api_key set)")
	}
	if m.Passive == nil {
		t.Errorf("passive profile should always be present (even if disabled)")
	}
}

func TestTenantChatCreateJoinPersonaConfigControl(t *testing.T) {
	tcH, _ := newTenantChatServer(t)

	// Create as 甲 (userA)
	rec := httptest.NewRecorder()
	req := mkReq(t, http.MethodPost, "/api/tenant-chat/conversations", "", nil, "userA")
	tcH.HandleTenantChatCreate(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("create code = %d, body=%s", rec.Code, rec.Body.String())
	}
	created := decodeView(t, rec)
	if created.MySide != "A" {
		t.Errorf("mySide = %q, want A", created.MySide)
	}
	convID := created.Conversation.ID
	code := created.Conversation.InviteCode

	// Mine as userA should list it.
	rec = httptest.NewRecorder()
	req = mkReq(t, http.MethodGet, "/api/tenant-chat/conversations/mine", "", nil, "userA")
	tcH.HandleTenantChatMine(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("mine code = %d", rec.Code)
	}
	m := decodeMine(t, rec)
	if len(m.Conversations) != 1 || m.Conversations[0].Conversation.ID != convID {
		t.Errorf("mine did not return the created conversation")
	}

	// 甲 edits own persona.
	body, _ := json.Marshal(map[string]string{"name": "甲改名", "system_prompt": "pA"})
	rec = httptest.NewRecorder()
	req = mkReq(t, http.MethodPut, "/api/tenant-chat/conversations/"+convID+"/persona", convID, body, "userA")
	tcH.HandleTenantChatPersona(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("persona code = %d, body=%s", rec.Code, rec.Body.String())
	}

	// Join as 乙 (userB) with the code.
	body, _ = json.Marshal(map[string]string{"id": convID, "code": code})
	rec = httptest.NewRecorder()
	req = mkReq(t, http.MethodPost, "/api/tenant-chat/conversations/join", "", body, "userB")
	tcH.HandleTenantChatJoin(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("join code = %d, body=%s", rec.Code, rec.Body.String())
	}
	joined := decodeView(t, rec)
	if joined.MySide != "B" {
		t.Errorf("join mySide = %q, want B", joined.MySide)
	}

	// 乙 edits own persona.
	body, _ = json.Marshal(map[string]string{"name": "乙改名", "system_prompt": "pB"})
	rec = httptest.NewRecorder()
	req = mkReq(t, http.MethodPut, "/api/tenant-chat/conversations/"+convID+"/persona", convID, body, "userB")
	tcH.HandleTenantChatPersona(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("userB persona code = %d", rec.Code)
	}

	// Config update (participant allowed).
	body, _ = json.Marshal(map[string]interface{}{"topic": "话题X", "max_rounds": 5, "delay_ms": 200})
	rec = httptest.NewRecorder()
	req = mkReq(t, http.MethodPut, "/api/tenant-chat/conversations/"+convID+"/config", convID, body, "userA")
	tcH.HandleTenantChatConfig(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("config code = %d, body=%s", rec.Code, rec.Body.String())
	}

	// Step generates a message (paired + AI configured + fake LLM).
	rec = httptest.NewRecorder()
	req = mkReq(t, http.MethodPost, "/api/tenant-chat/conversations/"+convID+"/control", convID, []byte(`{"action":"step"}`), "userA")
	tcH.HandleTenantChatControl(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("step code = %d, body=%s", rec.Code, rec.Body.String())
	}
	rec = httptest.NewRecorder()
	req = mkReq(t, http.MethodGet, "/api/tenant-chat/conversations/"+convID, convID, nil, "userA")
	tcH.HandleTenantChatGet(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("get code = %d", rec.Code)
	}
	got := decodeView(t, rec)
	if len(got.Conversation.Messages) != 1 {
		t.Errorf("expected 1 message after step, got %d", len(got.Conversation.Messages))
	}
}

func TestTenantChatPersonaRequiresParticipant(t *testing.T) {
	tcH, _ := newTenantChatServer(t)
	rec := httptest.NewRecorder()
	req := mkReq(t, http.MethodPost, "/api/tenant-chat/conversations", "", nil, "userA")
	tcH.HandleTenantChatCreate(rec, req)
	convID := decodeView(t, rec).Conversation.ID

	// A non-participant cannot edit persona.
	body, _ := json.Marshal(map[string]string{"name": "x"})
	rec = httptest.NewRecorder()
	req = mkReq(t, http.MethodPut, "/api/tenant-chat/conversations/"+convID+"/persona", convID, body, "intruder")
	tcH.HandleTenantChatPersona(rec, req)
	if rec.Code != http.StatusForbidden {
		t.Errorf("intruder persona code = %d, want 403", rec.Code)
	}
}

func TestTenantChatJoinBadCode(t *testing.T) {
	tcH, _ := newTenantChatServer(t)
	rec := httptest.NewRecorder()
	req := mkReq(t, http.MethodPost, "/api/tenant-chat/conversations", "", nil, "userA")
	tcH.HandleTenantChatCreate(rec, req)
	convID := decodeView(t, rec).Conversation.ID

	body, _ := json.Marshal(map[string]string{"id": convID, "code": "deadbeef"})
	rec = httptest.NewRecorder()
	req = mkReq(t, http.MethodPost, "/api/tenant-chat/conversations/join", "", body, "userB")
	tcH.HandleTenantChatJoin(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Errorf("join bad code code = %d, want 400", rec.Code)
	}
}

func TestTenantChatConfigBounds(t *testing.T) {
	tcH, _ := newTenantChatServer(t)
	rec := httptest.NewRecorder()
	req := mkReq(t, http.MethodPost, "/api/tenant-chat/conversations", "", nil, "userA")
	tcH.HandleTenantChatCreate(rec, req)
	convID := decodeView(t, rec).Conversation.ID

	cases := []map[string]interface{}{
		{"max_rounds": 999},
		{"max_rounds": -1},
		{"delay_ms": 99999},
		{"delay_ms": -1},
	}
	for _, c := range cases {
		b, _ := json.Marshal(c)
		rec = httptest.NewRecorder()
		req = mkReq(t, http.MethodPut, "/api/tenant-chat/conversations/"+convID+"/config", convID, b, "userA")
		tcH.HandleTenantChatConfig(rec, req)
		if rec.Code != http.StatusBadRequest {
			t.Errorf("config bounds code = %d, want 400 for %v", rec.Code, c)
		}
	}
}

func TestTenantChatControlUnknownAction(t *testing.T) {
	tcH, _ := newTenantChatServer(t)
	rec := httptest.NewRecorder()
	req := mkReq(t, http.MethodPost, "/api/tenant-chat/conversations", "", nil, "userA")
	tcH.HandleTenantChatCreate(rec, req)
	convID := decodeView(t, rec).Conversation.ID

	rec = httptest.NewRecorder()
	req = mkReq(t, http.MethodPost, "/api/tenant-chat/conversations/"+convID+"/control", convID, []byte(`{"action":"bogus"}`), "userA")
	tcH.HandleTenantChatControl(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Errorf("unknown action code = %d, want 400", rec.Code)
	}
}

func TestTenantChatPassiveSetAndGet(t *testing.T) {
	tcH, _ := newTenantChatServer(t)

	// Default profile.
	rec := httptest.NewRecorder()
	req := mkReq(t, http.MethodGet, "/api/tenant-chat/passive", "", nil, "userA")
	tcH.HandleTenantChatPassiveGet(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("passive get code = %d", rec.Code)
	}

	// Set enabled + handle + persona.
	body, _ := json.Marshal(map[string]interface{}{
		"enabled": true, "handle": "alice", "name": "Alice",
		"system_prompt": "I am Alice", "topic": "hi", "max_rounds": 9, "delay_ms": 700,
	})
	rec = httptest.NewRecorder()
	req = mkReq(t, http.MethodPut, "/api/tenant-chat/passive", "", body, "userA")
	tcH.HandleTenantChatPassiveSet(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("passive set code = %d, body=%s", rec.Code, rec.Body.String())
	}

	// Another user cannot take the same handle.
	body2, _ := json.Marshal(map[string]interface{}{"enabled": true, "handle": "alice", "name": "x"})
	rec = httptest.NewRecorder()
	req = mkReq(t, http.MethodPut, "/api/tenant-chat/passive", "", body2, "userB")
	tcH.HandleTenantChatPassiveSet(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Errorf("duplicate handle code = %d, want 400", rec.Code)
	}

	// Verify saved profile.
	rec = httptest.NewRecorder()
	req = mkReq(t, http.MethodGet, "/api/tenant-chat/passive", "", nil, "userA")
	tcH.HandleTenantChatPassiveGet(rec, req)
	var prof map[string]interface{}
	json.Unmarshal(rec.Body.Bytes(), &prof)
	if prof["enabled"] != true || prof["handle"] != "alice" || prof["name"] != "Alice" {
		t.Errorf("passive profile not persisted: %+v", prof)
	}
}

func TestTenantChatStartPassive(t *testing.T) {
	tcH, _ := newTenantChatServer(t)

	// userB configures + enables passive profile.
	body, _ := json.Marshal(map[string]interface{}{
		"enabled": true, "handle": "bob", "name": "Bob",
		"system_prompt": "I am Bob", "topic": "我们聊点啥", "max_rounds": 7, "delay_ms": 600,
	})
	rec := httptest.NewRecorder()
	req := mkReq(t, http.MethodPut, "/api/tenant-chat/passive", "", body, "userB")
	tcH.HandleTenantChatPassiveSet(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("passive set code = %d", rec.Code)
	}

	// userA starts a chat with userB via the handle.
	rec = httptest.NewRecorder()
	req = mkReq(t, http.MethodPost, "/api/tenant-chat/conversations/start-passive", "", []byte(`{"handle":"bob"}`), "userA")
	tcH.HandleTenantChatStartPassive(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("start-passive code = %d, body=%s", rec.Code, rec.Body.String())
	}
	v := decodeView(t, rec)
	if v.MySide != "A" {
		t.Errorf("active user should be 甲 (mySide=A), got %q", v.MySide)
	}
	c := v.Conversation
	if c.Participants[tenantchat.SideA].UserID != "userA" || c.Participants[tenantchat.SideB].UserID != "userB" {
		t.Errorf("seats wrong: %+v", c.Participants)
	}
	if c.Participants[tenantchat.SideB].Name != "Bob" || c.Participants[tenantchat.SideB].SystemPrompt != "I am Bob" {
		t.Errorf("乙 persona should come from passive profile: %+v", c.Participants[tenantchat.SideB])
	}
	if c.Topic != "我们聊点啥" {
		t.Errorf("topic should come from passive profile, got %q", c.Topic)
	}
}

func TestTenantChatStartPassiveErrors(t *testing.T) {
	tcH, _ := newTenantChatServer(t)

	// Unknown handle.
	rec := httptest.NewRecorder()
	req := mkReq(t, http.MethodPost, "/api/tenant-chat/conversations/start-passive", "", []byte(`{"handle":"ghost"}`), "userA")
	tcH.HandleTenantChatStartPassive(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Errorf("unknown handle code = %d, want 400", rec.Code)
	}

	// Disabled passive user.
	body, _ := json.Marshal(map[string]interface{}{"enabled": false, "handle": "bob"})
	rec = httptest.NewRecorder()
	req = mkReq(t, http.MethodPut, "/api/tenant-chat/passive", "", body, "userB")
	tcH.HandleTenantChatPassiveSet(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("passive set code = %d", rec.Code)
	}
	rec = httptest.NewRecorder()
	req = mkReq(t, http.MethodPost, "/api/tenant-chat/conversations/start-passive", "", []byte(`{"handle":"bob"}`), "userA")
	tcH.HandleTenantChatStartPassive(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Errorf("disabled passive code = %d, want 400", rec.Code)
	}
}
