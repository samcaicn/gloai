// colearn - Ultra-lightweight personal AI agent
// License: MIT
//
// Copyright (c) 2026 colearn contributors

// Package tupai implements the tup /api/v2/mcp MCP protocol (device_token
// Bearer auth, JSON-RPC-shaped body, OpenAI-compatible SSE streaming).
package tupai

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/colearn/colearn/pkg/providers/common"
	"github.com/colearn/colearn/pkg/providers/protocoltypes"
)

type (
	ToolCall               = protocoltypes.ToolCall
	FunctionCall           = protocoltypes.FunctionCall
	LLMResponse            = protocoltypes.LLMResponse
	StreamChunk            = protocoltypes.StreamChunk
	UsageInfo              = protocoltypes.UsageInfo
	Message                = protocoltypes.Message
	ToolDefinition         = protocoltypes.ToolDefinition
	ToolFunctionDefinition = protocoltypes.ToolFunctionDefinition
)

const (
	defaultBaseURL        = "https://ai.tuptup.top"
	defaultRequestTimeout = 120 * time.Second
	mcpEndpoint           = "/api/v2/mcp"
)

// Provider implements the tup client MCP protocol (/api/v2/mcp).
type Provider struct {
	deviceToken string
	apiBase     string
	userAgent   string
	httpClient  *http.Client
}

// NewProvider creates a new tup MCP provider. deviceToken is the Bearer token
// obtained from device registration / binding.
func NewProvider(deviceToken, apiBase, userAgent string) *Provider {
	return NewProviderWithTimeout(deviceToken, apiBase, userAgent, 0)
}

// NewProviderWithTimeout creates a tup MCP provider with a custom request timeout.
func NewProviderWithTimeout(deviceToken, apiBase, userAgent string, timeoutSeconds int) *Provider {
	baseURL := common.NormalizeBaseURL(apiBase, defaultBaseURL, false)
	timeout := defaultRequestTimeout
	if timeoutSeconds > 0 {
		timeout = time.Duration(timeoutSeconds) * time.Second
	}
	return &Provider{
		deviceToken: deviceToken,
		apiBase:     strings.TrimRight(baseURL, "/"),
		userAgent:   userAgent,
		httpClient: &http.Client{
			Timeout: timeout,
		},
	}
}

// mcpBody is the JSON-RPC-style envelope for /api/v2/mcp.
type mcpBody struct {
	ID     string         `json:"id"`
	Action string         `json:"action"`
	Params map[string]any `json:"params"`
}

// mcpResponse is the non-streaming JSON response envelope.
type mcpResponse struct {
	ID    string         `json:"id"`
	OK    bool           `json:"ok"`
	Data  map[string]any `json:"data,omitempty"`
	Error *mcpError      `json:"error,omitempty"`
}

type mcpError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

// requestLLMParams builds the llm.* params from a colearn message slice.
func requestLLMParams(messages []Message, model string, options map[string]any, stream bool) map[string]any {
	params := map[string]any{
		"messages": common.SerializeMessages(messages),
		"model":    model,
		"stream":   stream,
	}
	if t, ok := common.AsFloat(options["temperature"]); ok {
		params["temperature"] = t
	}
	if mt, ok := common.AsInt(options["max_tokens"]); ok && mt > 0 {
		params["max_tokens"] = mt
	}
	return params
}

func (p *Provider) newRequest(ctx context.Context, action string, params map[string]any) (*http.Request, error) {
	body := mcpBody{
		ID:     newID(),
		Action: action,
		Params: params,
	}
	jsonData, err := json.Marshal(body)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal tup request: %w", err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, p.apiBase+mcpEndpoint, bytes.NewReader(jsonData))
	if err != nil {
		return nil, fmt.Errorf("failed to create tup request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json, text/event-stream")
	if p.userAgent != "" {
		req.Header.Set("User-Agent", p.userAgent)
	}
	if p.deviceToken != "" {
		req.Header.Set("Authorization", "Bearer "+p.deviceToken)
	}
	return req, nil
}

// GetDefaultModel returns a default model identifier.
func (p *Provider) GetDefaultModel() string { return "gpt-4o-mini" }

// Chat performs a non-streaming llm.request.
func (p *Provider) Chat(
	ctx context.Context,
	messages []Message,
	_ []ToolDefinition,
	model string,
	options map[string]any,
) (*LLMResponse, error) {
	if p.deviceToken == "" {
		return nil, fmt.Errorf("device_token not configured")
	}
	req, err := p.newRequest(ctx, "llm.request", requestLLMParams(messages, model, options, false))
	if err != nil {
		return nil, err
	}
	resp, err := p.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("tup llm.request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, common.HandleErrorResponse(resp, p.apiBase)
	}

	var env mcpResponse
	if err := json.NewDecoder(resp.Body).Decode(&env); err != nil {
		return nil, fmt.Errorf("tup llm.request: decode response: %w", err)
	}
	if !env.OK {
		return nil, fmt.Errorf("tup llm.request error: %s", errorString(env.Error))
	}
	return parseLLMData(env.Data)
}

// ChatStream implements token streaming via ChatStreamEvents.
func (p *Provider) ChatStream(
	ctx context.Context,
	messages []Message,
	tools []ToolDefinition,
	model string,
	options map[string]any,
	onChunk func(accumulated string),
) (*LLMResponse, error) {
	return p.ChatStreamEvents(
		ctx,
		messages,
		tools,
		model,
		options,
		func(chunk StreamChunk) {
			if onChunk != nil && strings.TrimSpace(chunk.Content) != "" {
				onChunk(chunk.Content)
			}
		},
	)
}

// BindResult is the outcome of a client.bind MCP call.
type BindResult struct {
	Approved  bool
	Pending   bool
	RequestID string
	TenantID  string
	Message   string
}

// Bind calls the client.bind MCP action with an 8-digit join code, binding this
// device to the target tenant. The server may auto-approve (status "approved")
// or create a pending approval request ("pending_approval").
func (p *Provider) Bind(ctx context.Context, joinCode string) (*BindResult, error) {
	req, err := p.newRequest(ctx, "client.bind", map[string]any{"join_code": joinCode})
	if err != nil {
		return nil, err
	}
	resp, err := p.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("tup client.bind failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, common.HandleErrorResponse(resp, p.apiBase)
	}

	var env mcpResponse
	if err := json.NewDecoder(resp.Body).Decode(&env); err != nil {
		return nil, fmt.Errorf("tup client.bind: decode response: %w", err)
	}
	if !env.OK {
		return nil, fmt.Errorf("tup client.bind error: %s", errorString(env.Error))
	}

	status, _ := env.Data["status"].(string)
	message, _ := env.Data["message"].(string)
	requestID, _ := env.Data["request_id"].(string)
	tenantID, _ := env.Data["tenant_id"].(string)
	switch status {
	case "approved":
		return &BindResult{Approved: true, TenantID: tenantID, Message: message}, nil
	case "pending_approval":
		return &BindResult{Pending: true, RequestID: requestID, Message: message}, nil
	default:
		if message != "" {
			return nil, fmt.Errorf("%s", message)
		}
		if status != "" {
			return nil, fmt.Errorf("tup client.bind status: %s", status)
		}
		return nil, fmt.Errorf("tup client.bind: empty response")
	}
}

// ChatStreamEvents performs a streaming llm.stream_request and parses the SSE
// events (open / message / usage / done / error).
func (p *Provider) ChatStreamEvents(
	ctx context.Context,
	messages []Message,
	tools []ToolDefinition,
	model string,
	options map[string]any,
	onChunk func(StreamChunk),
) (*LLMResponse, error) {
	if p.deviceToken == "" {
		return nil, fmt.Errorf("device_token not configured")
	}
	req, err := p.newRequest(ctx, "llm.stream_request", requestLLMParams(messages, model, options, true))
	if err != nil {
		return nil, err
	}

	// Streaming must not be bounded by httpClient.Timeout (it covers body reads).
	streamClient := &http.Client{Transport: p.httpClient.Transport}
	resp, err := streamClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("tup llm.stream_request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, common.HandleErrorResponse(resp, p.apiBase)
	}

	return parseSSEStream(ctx, resp.Body, onChunk)
}

func errorString(e *mcpError) string {
	if e == nil {
		return "unknown"
	}
	if e.Message != "" {
		return e.Message
	}
	return e.Code
}

func newID() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return fmt.Sprintf("%d", time.Now().UnixNano())
	}
	return hex.EncodeToString(b)
}

// parseLLMData builds an LLMResponse from the "data" of an mcp envelope.
func parseLLMData(data map[string]any) (*LLMResponse, error) {
	content, _ := data["content"].(string)
	finish, _ := data["finish_reason"].(string)
	if finish == "" {
		finish = "stop"
	}
	return &LLMResponse{
		Content:      content,
		FinishReason: finish,
		Usage:        parseUsage(data["usage"]),
	}, nil
}

func parseUsage(raw any) *UsageInfo {
	if raw == nil {
		return nil
	}
	m, ok := raw.(map[string]any)
	if !ok {
		if s, ok := raw.(string); ok {
			var mm map[string]any
			if json.Unmarshal([]byte(s), &mm) == nil {
				m = mm
			}
		}
	}
	if m == nil {
		return nil
	}
	return &UsageInfo{
		PromptTokens:     intField(m, "prompt_tokens", "input_tokens"),
		CompletionTokens: intField(m, "completion_tokens", "output_tokens"),
		TotalTokens:      intField(m, "total_tokens"),
	}
}

func toInt(v any) int {
	switch n := v.(type) {
	case float64:
		return int(n)
	case int:
		return n
	case int64:
		return int(n)
	case json.Number:
		i, _ := n.Int64()
		return int(i)
	}
	return 0
}

func intField(m map[string]any, keys ...string) int {
	for _, k := range keys {
		if v, ok := m[k]; ok {
			return toInt(v)
		}
	}
	return 0
}