package ai

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// platformChatServer mirrors chatServer but accepts an arbitrary bearer token,
// letting us drive ResolveSystemAIConfig's ACC_PRODUCT_CONFIG_V2 path with a
// realistic platform-supplied token instead of the shared "test-key". It sleeps
// a little so the call duration is measurably > 0 and can be asserted.
func platformChatServer(t *testing.T, token string, prompt, completion, total, cached, reasoning int) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer "+token {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		time.Sleep(5 * time.Millisecond)
		_ = json.NewEncoder(w).Encode(chatResponse{
			Choices: []chatChoice{{Message: chatResponseMessage{Role: "assistant", Content: strPtr("hi")}}},
			Usage: &chatUsage{
				PromptTokens:     prompt,
				CompletionTokens: completion,
				TotalTokens:      total,
				PromptTokensDetails: &struct {
					CachedTokens int `json:"cached_tokens"`
				}{CachedTokens: cached},
				CompletionTokensDetails: &struct {
					ReasoningTokens int `json:"reasoning_tokens"`
				}{ReasoningTokens: reasoning},
			},
		})
	}))
}

// TestSystemLLMFallbackRecordsTenantTokens proves the tenant/Hub path records
// token usage even when it reaches the system LLM purely through the platform
// unified interface (ACC_PRODUCT_CONFIG_V2), i.e. when the operator-configured
// ai.* interface is absent. This is the "统一" (unified) guarantee: the Hub's
// tenants and the tenant-memory sidecar application resolve to the same system
// LLM, and tenant calls still emit a proper UsageRecord.
func TestSystemLLMFallbackRecordsTenantTokens(t *testing.T) {
	const platToken = "plt-TOKEN"
	srv := platformChatServer(t, platToken, 11, 6, 17, 1, 0)
	defer srv.Close()
	t.Setenv("ACC_PRODUCT_CONFIG_V2", `{"endpoint":"`+srv.URL+`","authentication":{"attributes":{"token":"`+platToken+`"}}}`)

	cap := &captureRecorder{}
	SetUsageRecorder(cap.fn)
	defer SetUsageRecorder(nil)

	// No ai.* configured → must fall back to the platform interface.
	cfg, ok := ResolveSystemAIConfig(func(prefix string) (map[string]string, error) {
		return map[string]string{}, nil
	})
	if !ok {
		t.Fatal("expected system LLM config from ACC_PRODUCT_CONFIG_V2")
	}
	if cfg.BaseURL != srv.URL || cfg.APIKey != platToken {
		t.Fatalf("unexpected system LLM config: %+v", cfg)
	}

	ctx := ContextWithMeta(context.Background(), "tenant-X", "chan-Y")
	if _, err := CompleteMessages(ctx, cfg, []Message{{Role: "user", Content: "hi"}}, nil); err != nil {
		t.Fatalf("CompleteMessages: %v", err)
	}

	rec, ok := cap.last()
	if !ok {
		t.Fatal("no usage record emitted for tenant-path call")
	}
	if rec.TenantID != "tenant-X" || rec.ChannelID != "chan-Y" {
		t.Fatalf("tenant/channel attribution wrong: %+v", rec)
	}
	if rec.ModelType != "chat" || rec.Model != "gpt-4o-mini" || rec.TotalTokens != 17 ||
		rec.PromptTokens != 11 || rec.CompletionTokens != 6 || rec.CachedTokens != 1 {
		t.Fatalf("token usage mismatch: %+v", rec)
	}
	if rec.DurationMS < 1 {
		t.Fatalf("call duration not recorded: %+v", rec)
	}
}
