package ai

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"

	"github.com/ceoadmin/CEOadmin/internal/store"
)

// memUsageStore 是一个最小化的内存 UsageStore 实现，用于在测试中断言
// ai 包在每次 LLM 调用后产出的 UsageRecord 经 recorder 落库（与 main.go 的
// 接线方式一致）后能聚合出可查询的行，包括调用耗时。它不依赖 sqlite 包，
// 因此即便仓储层其它未完成的特性（如 SkillStore）导致 sqlite 包暂时无法
// 编译，本测试仍可独立验证用量记录链路。
type memUsageStore struct {
	mu   sync.Mutex
	rows []store.LLMUsageRecord
}

func (m *memUsageStore) RecordLLMUsage(r *store.LLMUsageRecord) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.rows = append(m.rows, *r)
	return nil
}

func (m *memUsageStore) ListLLMUsageAgg(f store.UsageFilter) ([]store.UsageAggregate, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	type aggKey struct{ tenant, model, modelType string }
	agg := map[aggKey]*store.UsageAggregate{}
	for _, r := range m.rows {
		if f.TenantID != "" && r.TenantID != f.TenantID {
			continue
		}
		if f.Model != "" && r.Model != f.Model {
			continue
		}
		k := aggKey{r.TenantID, r.Model, r.ModelType}
		a, ok := agg[k]
		if !ok {
			a = &store.UsageAggregate{TenantID: r.TenantID, Model: r.Model, ModelType: r.ModelType}
			agg[k] = a
		}
		a.PromptTokens += r.PromptTokens
		a.CompletionTokens += r.CompletionTokens
		a.TotalTokens += r.TotalTokens
		a.CachedTokens += r.CachedTokens
		a.ReasoningTokens += r.ReasoningTokens
		a.DurationMS += r.DurationMS
		a.CallCount++
		if r.CreatedAt > a.LastAt {
			a.LastAt = r.CreatedAt
		}
	}
	out := make([]store.UsageAggregate, 0, len(agg))
	for _, a := range agg {
		out = append(out, *a)
	}
	return out, nil
}

// captureRecorder collects emitted UsageRecords for assertions.
type captureRecorder struct {
	mu  sync.Mutex
	got []UsageRecord
}

func (c *captureRecorder) fn(_ context.Context, r UsageRecord) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.got = append(c.got, r)
}

func (c *captureRecorder) last() (UsageRecord, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.got) == 0 {
		return UsageRecord{}, false
	}
	return c.got[len(c.got)-1], true
}

func chatServer(t *testing.T, prompt, completion, total, cached, reasoning int) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer test-key" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
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

func embedServer(t *testing.T, total int) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer test-key" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		_ = json.NewEncoder(w).Encode(embedResponse{
			Data: []embedData{{Index: 0, Embedding: []float32{0.1, 0.2}}},
			Usage: &struct {
				PromptTokens int `json:"prompt_tokens"`
				TotalTokens  int `json:"total_tokens"`
			}{PromptTokens: total, TotalTokens: total},
		})
	}))
}

func strPtr(s string) *string { return &s }

func TestRecorderChatRecordsUpstreamTokens(t *testing.T) {
	srv := chatServer(t, 10, 5, 15, 2, 1)
	defer srv.Close()

	cap := &captureRecorder{}
	SetUsageRecorder(cap.fn)
	defer SetUsageRecorder(nil)

	cfg := store.AIConfig{BaseURL: srv.URL, APIKey: "test-key", Model: "gpt-4o"}
	ctx := ContextWithMeta(context.Background(), "tenant-1", "chan-9")

	res, err := CompleteMessages(ctx, cfg, []Message{{Role: "user", Content: "hi"}}, nil)
	if err != nil {
		t.Fatalf("CompleteMessages: %v", err)
	}
	if res.Content != "hi" {
		t.Fatalf("unexpected content %q", res.Content)
	}

	rec, ok := cap.last()
	if !ok {
		t.Fatal("recorder was not called")
	}
	if rec.ModelType != "chat" {
		t.Fatalf("model_type = %q, want chat", rec.ModelType)
	}
	if rec.Model != "gpt-4o" {
		t.Fatalf("model = %q", rec.Model)
	}
	if rec.TenantID != "tenant-1" || rec.ChannelID != "chan-9" {
		t.Fatalf("tenant/channel = %q/%q", rec.TenantID, rec.ChannelID)
	}
	if rec.PromptTokens != 10 || rec.CompletionTokens != 5 || rec.TotalTokens != 15 ||
		rec.CachedTokens != 2 || rec.ReasoningTokens != 1 {
		t.Fatalf("usage mismatch: %+v", rec)
	}
}

func TestRecorderEmbeddingRecordsUpstreamTokens(t *testing.T) {
	srv := embedServer(t, 7)
	defer srv.Close()

	cap := &captureRecorder{}
	SetUsageRecorder(cap.fn)
	defer SetUsageRecorder(nil)

	cfg := store.AIConfig{BaseURL: srv.URL, APIKey: "test-key", Model: "gpt-4o", EmbeddingModel: "text-embedding-3"}
	ctx := ContextWithMeta(context.Background(), "tenant-2", "")

	vecs, err := Embed(ctx, cfg, []string{"hello"})
	if err != nil {
		t.Fatalf("Embed: %v", err)
	}
	if len(vecs) != 1 || len(vecs[0]) != 2 {
		t.Fatalf("unexpected embeddings: %v", vecs)
	}

	rec, ok := cap.last()
	if !ok {
		t.Fatal("recorder was not called for embedding")
	}
	if rec.ModelType != "embedding" {
		t.Fatalf("model_type = %q, want embedding", rec.ModelType)
	}
	if rec.Model != "text-embedding-3" {
		t.Fatalf("model = %q, want text-embedding-3", rec.Model)
	}
	if rec.PromptTokens != 7 || rec.TotalTokens != 7 {
		t.Fatalf("usage mismatch: %+v", rec)
	}
}

func TestRecorderSkipsSystemCalls(t *testing.T) {
	srv := chatServer(t, 1, 1, 2, 0, 0)
	defer srv.Close()

	cap := &captureRecorder{}
	SetUsageRecorder(cap.fn)
	defer SetUsageRecorder(nil)

	cfg := store.AIConfig{BaseURL: srv.URL, APIKey: "test-key", Model: "gpt-4o"}
	ctx := ContextSystem(context.Background())

	if _, err := CompleteMessages(ctx, cfg, []Message{{Role: "user", Content: "hi"}}, nil); err != nil {
		t.Fatalf("CompleteMessages: %v", err)
	}
	if len(cap.got) != 0 {
		t.Fatalf("system call should not be recorded, got %d", len(cap.got))
	}
}

func TestRecorderNilIsSafe(t *testing.T) {
	srv := chatServer(t, 1, 1, 2, 0, 0)
	defer srv.Close()

	SetUsageRecorder(nil) // no recorder installed
	cfg := store.AIConfig{BaseURL: srv.URL, APIKey: "test-key", Model: "gpt-4o"}
	ctx := ContextWithMeta(context.Background(), "t", "c")

	// Must not panic and must still return the completion.
	if _, err := CompleteMessages(ctx, cfg, []Message{{Role: "user", Content: "hi"}}, nil); err != nil {
		t.Fatalf("CompleteMessages: %v", err)
	}
}

// TestRecorderWritesToStore proves the full chain: the ai package emits a
// UsageRecord on every LLM call, and wiring that into a real store (exactly as
// main.go does) lands a queryable row. This is the end-to-end accounting path.
func TestRecorderWritesToStore(t *testing.T) {
	srv := chatServer(t, 12, 8, 20, 3, 1)
	defer srv.Close()

	st := &memUsageStore{}

	var recordedDur int64
	SetUsageRecorder(func(_ context.Context, r UsageRecord) {
		recordedDur = r.DurationMS
		_ = st.RecordLLMUsage(&store.LLMUsageRecord{
			TenantID:         r.TenantID,
			ChannelID:        r.ChannelID,
			Model:            r.Model,
			ModelType:        r.ModelType,
			PromptTokens:     r.PromptTokens,
			CompletionTokens: r.CompletionTokens,
			TotalTokens:      r.TotalTokens,
			CachedTokens:     r.CachedTokens,
			ReasoningTokens:  r.ReasoningTokens,
			DurationMS:       r.DurationMS,
		})
	})
	defer SetUsageRecorder(nil)

	cfg := store.AIConfig{BaseURL: srv.URL, APIKey: "test-key", Model: "gpt-4o"}
	ctx := ContextWithMeta(context.Background(), "real-tenant", "real-channel")

	if _, err := CompleteMessages(ctx, cfg, []Message{{Role: "user", Content: "hi"}}, nil); err != nil {
		t.Fatalf("CompleteMessages: %v", err)
	}

	rows, err := st.ListLLMUsageAgg(store.UsageFilter{Limit: 10})
	if err != nil {
		t.Fatalf("aggregate: %v", err)
	}
	if len(rows) != 1 {
		t.Fatalf("expected 1 aggregated row, got %d: %+v", len(rows), rows)
	}
	r := rows[0]
	if r.TenantID != "real-tenant" || r.Model != "gpt-4o" || r.ModelType != "chat" {
		t.Fatalf("unexpected row: %+v", r)
	}
	if r.PromptTokens != 12 || r.CompletionTokens != 8 || r.TotalTokens != 20 || r.CachedTokens != 3 || r.ReasoningTokens != 1 || r.CallCount != 1 {
		t.Fatalf("unexpected tokens: %+v", r)
	}
	if r.DurationMS != recordedDur {
		t.Fatalf("aggregated duration %d != recorded duration %d", r.DurationMS, recordedDur)
	}
}
