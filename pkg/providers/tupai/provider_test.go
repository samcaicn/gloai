package tupai

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func testMessages() []Message {
	return []Message{
		{Role: "system", Content: "You are a test assistant."},
		{Role: "user", Content: "hi"},
	}
}

// TestChatNonStreaming verifies the llm.request wire shape against a fake MCP
// server that mirrors the real tup backend response envelope.
func TestChatNonStreaming(t *testing.T) {
	var gotBody mcpBody
	var gotAuth string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v2/mcp" {
			t.Errorf("path = %q, want /api/v2/mcp", r.URL.Path)
		}
		gotAuth = r.Header.Get("Authorization")
		if err := json.NewDecoder(r.Body).Decode(&gotBody); err != nil {
			t.Fatalf("decode request: %v", err)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"id":"req-1","ok":true,"data":{"content":"hello from tup","model":"gpt-4o-mini","usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12},"finish_reason":"stop"}}`))
	}))
	defer srv.Close()

	p := NewProvider("dev-token-123", srv.URL, "colearn/test")
	resp, err := p.Chat(context.Background(), testMessages(), nil, "gpt-4o-mini", map[string]any{
		"temperature": 0.5,
		"max_tokens":   64,
	})
	if err != nil {
		t.Fatalf("Chat: %v", err)
	}

	if gotAuth != "Bearer dev-token-123" {
		t.Errorf("Authorization = %q, want Bearer dev-token-123", gotAuth)
	}
	if gotBody.Action != "llm.request" {
		t.Errorf("action = %q, want llm.request", gotBody.Action)
	}
	if gotBody.ID == "" {
		t.Error("request id must not be empty")
	}
	params, ok := gotBody.Params["messages"].([]any)
	if !ok || len(params) != 2 {
		t.Fatalf("params.messages = %#v, want 2 messages", gotBody.Params["messages"])
	}
	if gotBody.Params["model"] != "gpt-4o-mini" {
		t.Errorf("model = %v", gotBody.Params["model"])
	}
	if gotBody.Params["temperature"] != float64(0.5) {
		t.Errorf("temperature = %v", gotBody.Params["temperature"])
	}
	if gotBody.Params["max_tokens"] != float64(64) {
		t.Errorf("max_tokens = %v", gotBody.Params["max_tokens"])
	}
	if gotBody.Params["stream"] != false {
		t.Errorf("stream = %v, want false", gotBody.Params["stream"])
	}

	if resp.Content != "hello from tup" {
		t.Errorf("Content = %q", resp.Content)
	}
	if resp.Usage == nil || resp.Usage.PromptTokens != 10 || resp.Usage.TotalTokens != 12 {
		t.Errorf("Usage = %#v", resp.Usage)
	}
	if resp.FinishReason != "stop" {
		t.Errorf("FinishReason = %q", resp.FinishReason)
	}
}

// TestChatMCPError verifies the error envelope is surfaced.
func TestChatMCPError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"id":"req-1","ok":false,"error":{"code":"device_token_invalid","message":"bad token"}}`))
	}))
	defer srv.Close()

	p := NewProvider("bad-token", srv.URL, "colearn/test")
	_, err := p.Chat(context.Background(), testMessages(), nil, "gpt-4o-mini", nil)
	if err == nil {
		t.Fatal("expected error for ok:false envelope")
	}
	if !strings.Contains(err.Error(), "bad token") {
		t.Errorf("error = %q, want to contain %q", err, "bad token")
	}
}

// TestChatMissingToken verifies device_token is required.
func TestChatMissingToken(t *testing.T) {
	p := NewProvider("", "https://www.tuptup.top", "colearn/test")
	_, err := p.Chat(context.Background(), testMessages(), nil, "gpt-4o-mini", nil)
	if err == nil {
		t.Fatal("expected error when device_token is empty")
	}
}

// TestSSEParse simulates the exact SSE stream the real backend emits
// (see server/mcp_adapter/actions/llm_stream.py).
func TestSSEParse(t *testing.T) {
	raw := strings.Join([]string{
		"event: open\n",
		`data: {"id":"chatcmpl-abc","model":"gpt-4o-mini","created":1,"object":"chat.completion.chunk"}`,
		"\n\n",
		"event: message\n",
		`data: {"id":"chatcmpl-abc","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"你好"},"finish_reason":null}]}`,
		"\n\n",
		": heartbeat\n\n",
		"event: message\n",
		`data: {"id":"chatcmpl-abc","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"，世界"},"finish_reason":null}]}`,
		"\n\n",
		"event: usage\n",
		`data: {"id":"chatcmpl-abc","input_tokens":12,"output_tokens":5,"total_tokens":17}`,
		"\n\n",
		"event: done\n",
		`data: {"reason":"stop","total_ms":123}`,
		"\n\n",
	}, "")

	var chunks []string
	resp, err := parseSSEStream(context.Background(), strings.NewReader(raw), func(c StreamChunk) {
		chunks = append(chunks, c.Content)
	})
	if err != nil {
		t.Fatalf("parseSSEStream: %v", err)
	}

	if len(chunks) != 2 {
		t.Errorf("chunk count = %d, want 2 (got %v)", len(chunks), chunks)
	}
	if len(chunks) > 0 && chunks[0] != "你好" {
		t.Errorf("chunk[0] = %q", chunks[0])
	}
	if len(chunks) > 1 && chunks[1] != "你好，世界" {
		t.Errorf("chunk[1] = %q, want accumulated text 你好，世界", chunks[1])
	}
	if resp.Content != "你好，世界" {
		t.Errorf("Content = %q, want 你好，世界", resp.Content)
	}
	if resp.FinishReason != "stop" {
		t.Errorf("FinishReason = %q", resp.FinishReason)
	}
	if resp.Usage == nil || resp.Usage.PromptTokens != 12 || resp.Usage.CompletionTokens != 5 || resp.Usage.TotalTokens != 17 {
		t.Errorf("Usage = %#v", resp.Usage)
	}
}

// TestSSEError verifies the error event aborts streaming.
func TestSSEError(t *testing.T) {
	raw := strings.Join([]string{
		"event: message\n",
		`data: {"id":"x","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"partial"},"finish_reason":null}]}`,
		"\n\n",
		"event: error\n",
		`data: {"code":"llm_upstream_error","message":"upstream 500"}`,
		"\n\n",
	}, "")

	_, err := parseSSEStream(context.Background(), strings.NewReader(raw), nil)
	if err == nil {
		t.Fatal("expected error event to abort stream")
	}
	if !strings.Contains(err.Error(), "upstream 500") {
		t.Errorf("error = %q", err)
	}
}

// TestSSEStreamRequest ensures the wire body for streaming uses
// llm.stream_request with stream: true.
func TestSSEStreamRequest(t *testing.T) {
	var gotBody mcpBody
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := json.NewDecoder(r.Body).Decode(&gotBody); err != nil {
			t.Fatalf("decode request: %v", err)
		}
		w.Header().Set("Content-Type", "text/event-stream")
		_, _ = w.Write([]byte("event: message\n"))
		_, _ = w.Write([]byte(`data: {"id":"x","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"streamed"},"finish_reason":null}]}`))
		_, _ = w.Write([]byte("\n\n"))
		_, _ = w.Write([]byte("event: done\n"))
		_, _ = w.Write([]byte(`data: {"reason":"stop"}`))
		_, _ = w.Write([]byte("\n\n"))
	}))
	defer srv.Close()

	p := NewProvider("dev-token-123", srv.URL, "colearn/test")
	resp, err := p.ChatStreamEvents(context.Background(), testMessages(), nil, "gpt-4o-mini", nil, nil)
	if err != nil {
		t.Fatalf("ChatStreamEvents: %v", err)
	}
	if gotBody.Action != "llm.stream_request" {
		t.Errorf("action = %q, want llm.stream_request", gotBody.Action)
	}
	if gotBody.Params["stream"] != true {
		t.Errorf("stream = %v, want true", gotBody.Params["stream"])
	}
	if resp.Content != "streamed" {
		t.Errorf("Content = %q", resp.Content)
	}
}
