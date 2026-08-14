package actions

import (
	"fmt"

	"time"

	"github.com/ceoadmin/CEOadmin/internal/mcp/shared"
	"github.com/ceoadmin/CEOadmin/internal/store"
)

// LLMManager handles LLM operations
type LLMManager struct {
	store store.Store
}

func NewLLMManager(s store.Store) *LLMManager {
	return &LLMManager{store: s}
}

func (m *LLMManager) Request(ctx *shared.Context, params map[string]any) (any, error) {
	messages, _ := params["messages"].([]any)
	if len(messages) == 0 {
		return nil, shared.MissingParam("messages")
	}

	model, _ := params["model"].(string)
	if model == "" || model == "default" || model == "auto" {
		model = ""
	}

	temperature := 0.7
	if t, ok := params["temperature"].(float64); ok {
		temperature = t
	}

	maxTokens := 0
	if mt, ok := params["max_tokens"].(float64); ok {
		maxTokens = int(mt)
	}
	if maxTokens < 0 || maxTokens > 8192 {
		maxTokens = 0
	}

	// Token reserve logic (if enabled)
	// Would use token_ledger reserve/settle/release here

	streamID := fmt.Sprintf("llm-%d", time.Now().UnixMilli())
	estimatedTokens := m.estimateTokens(messages, maxTokens)

	// Reserve tokens (if tenant has billing enabled)
	reserveID := m.reserveTokens(ctx, estimatedTokens, streamID)

	// Call LLM provider
	response, err := m.callLLMProvider(ctx, messages, model, temperature, maxTokens)
	if err != nil {
		if reserveID != "" {
			m.releaseTokens(ctx, reserveID, streamID)
		}
		return nil, err
	}

	// Settle tokens
	actualTokens := 0
	if response.Usage != nil {
		if total, ok := response.Usage["total_tokens"].(float64); ok {
			actualTokens = int(total)
		}
	}
	m.settleTokens(ctx, reserveID, actualTokens, streamID)

	return map[string]any{
		"content": response.Content,
		"model":   response.Model,
		"usage":   response.Usage,
	}, nil
}

func (m *LLMManager) StreamRequest(ctx *shared.Context, params map[string]any) (any, error) {
	// Streaming version - would return SSE stream
	// For MCP, we return the stream ID and the client polls/connects via SSE
	return map[string]any{
		"stream_id": fmt.Sprintf("llm-%d", time.Now().UnixMilli()),
		"endpoint":  "/api/v2/mcp/stream",
	}, nil
}

type LLMResponse struct {
	Content string
	Model   string
	Usage   map[string]any
}

func (m *LLMManager) callLLMProvider(ctx *shared.Context, messages []any, model string, temperature float64, maxTokens int) (*LLMResponse, error) {
	// Implementation would call the actual LLM provider (OpenAI, Anthropic, etc.)
	// This is a stub
	return &LLMResponse{
		Content: "LLM response placeholder",
		Model:   "gpt-4o-mini",
		Usage:   map[string]any{"total_tokens": 100},
	}, nil
}

func (m *LLMManager) estimateTokens(messages []any, maxTokens int) int {
	if maxTokens > 0 {
		return maxTokens
	}
	// Rough estimation: 1 token ≈ 4 chars
	chars := 0
	for _, m := range messages {
		if mm, ok := m.(map[string]any); ok {
			if content, ok := mm["content"].(string); ok {
				chars += len(content)
			}
		}
	}
	return max(1, chars/4)
}

func (m *LLMManager) reserveTokens(ctx *shared.Context, estimated int, streamID string) string {
	// Would call token_ledger.reserve_garlic_for_llm
	// Return reserve_id
	return ""
}

func (m *LLMManager) settleTokens(ctx *shared.Context, reserveID string, actualTokens int, streamID string) {
	if reserveID == "" {
		return
	}
	// Would call token_ledger.settle_garlic_for_llm or release
}

func (m *LLMManager) releaseTokens(ctx *shared.Context, reserveID string, streamID string) {
	if reserveID == "" {
		return
	}
	// Would call token_ledger.release_garlic_for_llm
}
