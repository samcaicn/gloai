package server

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"tenant-memory/internal/config"
	"tenant-memory/internal/store"
	"tenant-memory/internal/usage"
)

// mockLLMServer 模拟 OpenAI 兼容的 /chat/completions，返回 choices 与 usage。
// 故意 sleep 一小段时间，使调用耗时（毫秒）可被测出且 > 0。
func mockLLMServer(t *testing.T, token string, prompt, completion, total, cached, reasoning int) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/chat/completions" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		if r.Header.Get("Authorization") != "Bearer "+token {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		time.Sleep(5 * time.Millisecond)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"choices": []map[string]any{
				{"message": map[string]any{"role": "assistant", "content": "hi there"}},
			},
			"usage": map[string]any{
				"prompt_tokens":        prompt,
				"completion_tokens":    completion,
				"total_tokens":         total,
				"prompt_tokens_details": map[string]any{"cached_tokens": cached},
				"completion_tokens_details": map[string]any{"reasoning_tokens": reasoning},
			},
		})
	}))
}

type usageCapture struct {
	mu  sync.Mutex
	got []usage.UsageRecord
}

func (c *usageCapture) fn(r usage.UsageRecord) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.got = append(c.got, r)
}

func (c *usageCapture) last() (usage.UsageRecord, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.got) == 0 {
		return usage.UsageRecord{}, false
	}
	return c.got[len(c.got)-1], true
}

// TestChatRecordsSystemLLMTokenUsage 验证应用侧（/chat 调用系统 LLM）会解析
// 响应中的 usage 并上报一条 UsageRecord，且记录归属到正确的租户。
func TestChatRecordsSystemLLMTokenUsage(t *testing.T) {
	const token = "plt-TOKEN"
	llmSrv := mockLLMServer(t, token, 11, 6, 17, 1, 2)
	defer llmSrv.Close()

	st, err := store.Open("sqlite", filepath.Join(t.TempDir(), "tms.db"), t.TempDir())
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	defer st.Close()

	cfg := &config.Config{
		Store:     "sqlite",
		LLMBaseURL: llmSrv.URL,
		LLMAPIKey:  token,
		LLMModel:   "gpt-4o-mini",
		RetrieveK:  5,
	}
	srv := New(cfg, st)

	cap := &usageCapture{}
	usage.SetRecorder(cap.fn)
	defer usage.SetRecorder(nil)

	body, _ := json.Marshal(map[string]string{"tenant_id": "app-tenant", "message": "hello"})
	req := httptest.NewRequest(http.MethodPost, "/chat", bytes.NewReader(body))
	rec := httptest.NewRecorder()
	srv.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("chat status = %d, body = %s", rec.Code, rec.Body.String())
	}

	rec2, ok := cap.last()
	if !ok {
		t.Fatal("no UsageRecord emitted for /chat call")
	}
	if rec2.TenantID != "app-tenant" {
		t.Fatalf("usage tenant attribution wrong: %q", rec2.TenantID)
	}
	if rec2.ModelType != "chat" || rec2.Model != "gpt-4o-mini" {
		t.Fatalf("usage model info wrong: type=%q model=%q", rec2.ModelType, rec2.Model)
	}
	if rec2.PromptTokens != 11 || rec2.CompletionTokens != 6 || rec2.TotalTokens != 17 ||
		rec2.CachedTokens != 1 || rec2.ReasoningTokens != 2 {
		t.Fatalf("usage token mismatch: %+v", rec2)
	}
	if rec2.DurationMS < 1 {
		t.Fatalf("call duration not recorded: %+v", rec2)
	}
}

// TestChatEndpointRequiresInput 校验参数缺失时的边界行为（不改主流程）。
func TestChatEndpointRequiresInput(t *testing.T) {
	st, err := store.Open("sqlite", filepath.Join(t.TempDir(), "tms.db"), t.TempDir())
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	defer st.Close()
	srv := New(&config.Config{Store: "sqlite", LLMModel: "gpt-4o-mini", RetrieveK: 5}, st)

	body, _ := json.Marshal(map[string]string{"tenant_id": "", "message": "x"})
	req := httptest.NewRequest(http.MethodPost, "/chat", bytes.NewReader(body))
	rec := httptest.NewRecorder()
	srv.Handler().ServeHTTP(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("want 400 for missing tenant_id, got %d", rec.Code)
	}
}
