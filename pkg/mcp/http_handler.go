// colearn - Ultra-lightweight personal AI agent
// License: MIT
//
// Copyright (c) 2026 colearn contributors

package mcp

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"log/slog"

	"github.com/modelcontextprotocol/go-sdk/mcp"

	"github.com/colearn/colearn/pkg/tools"
)

const (
	// StreamableHTTPPath is the MCP Streamable HTTP endpoint path on the gateway.
	StreamableHTTPPath = "/api/v2/mcp"

	// McpSessionIDHeader is the MCP session ID header used for Streamable HTTP.
	McpSessionIDHeader = "Mcp-Session-Id"
)

// mcpToolHandler wraps a colearn ToolRegistry so that MCP clients can call
// the gateway's tools via the standard MCP Streamable HTTP transport.
type mcpToolHandler struct {
	registry *tools.ToolRegistry
}

// NewStreamableHTTPHandler creates a Streamable HTTP MCP handler that exposes
// the given ToolRegistry as MCP tools. Each tool call is dispatched to the
// registry's Execute method.
func NewStreamableHTTPHandler(registry *tools.ToolRegistry) *mcp.StreamableHTTPHandler {
	server := mcp.NewServer(&mcp.Implementation{
		Name:    "colearn",
		Version: "1.0.0",
	}, &mcp.ServerOptions{
		Logger: slog.New(slog.NewTextHandler(os.Stderr, nil)),
	})

	if registry != nil {
		registerRegistryTools(server, registry)
	}

	return mcp.NewStreamableHTTPHandler(func(_ *http.Request) *mcp.Server {
		return server
	}, &mcp.StreamableHTTPOptions{
		JSONResponse: true,
	})
}

// registerRegistryTools registers all visible tools from the registry as MCP tools.
func registerRegistryTools(server *mcp.Server, registry *tools.ToolRegistry) {
	defs := registry.GetDefinitions()
	for _, def := range defs {
		fn, ok := def["function"].(map[string]any)
		if !ok {
			continue
		}
		name, _ := fn["name"].(string)
		if name == "" {
			continue
		}
		desc, _ := fn["description"].(string)
		params, _ := fn["parameters"].(map[string]any)

		tool := &mcp.Tool{
			Name:        name,
			Description: desc,
			InputSchema: mapToJSONSchema(params),
		}

		handler := &mcpToolHandler{registry: registry}
		server.AddTool(tool, handler.handleCall)
	}
}

// mapToJSONSchema converts a provider-style params map into a JSON Schema
// object that the MCP SDK accepts.
func mapToJSONSchema(params map[string]any) any {
	if params == nil {
		return map[string]any{"type": "object"}
	}
	return params
}

// handleCall dispatches a tool invocation to the colearn registry and
// converts the ToolResult back into an MCP CallToolResult.
func (h *mcpToolHandler) handleCall(ctx context.Context, req *mcp.CallToolRequest) (*mcp.CallToolResult, error) {
	name := req.Params.Name

	args := make(map[string]any)
	if req.Params.Arguments != nil {
		if err := json.Unmarshal(req.Params.Arguments, &args); err != nil {
			return nil, fmt.Errorf("mcp tool %q: failed to unmarshal arguments: %w", name, err)
		}
	}

	result := h.registry.Execute(ctx, name, args)
	if result == nil {
		return &mcp.CallToolResult{
			Content: []mcp.Content{
				&mcp.TextContent{Text: name + " returned no output"},
			},
		}, nil
	}

	text := result.ContentForLLM()

	if result.IsError {
		return &mcp.CallToolResult{
			IsError: true,
			Content: []mcp.Content{
				&mcp.TextContent{Text: text},
			},
		}, nil
	}

	return &mcp.CallToolResult{
		Content: []mcp.Content{
			&mcp.TextContent{Text: text},
		},
	}, nil
}
