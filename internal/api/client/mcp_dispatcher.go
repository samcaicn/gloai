package clientapi

import (
	"context"
	"fmt"
	"log/slog"
	"time"

	"github.com/ceoadmin/CEOadmin/internal/store"
)

// MCPDispatcher handles MCP action dispatch for client actions.
type MCPDispatcher struct {
	Store store.Store
}

func NewMCPDispatcher(s store.Store) *MCPDispatcher {
	return &MCPDispatcher{Store: s}
}

type MCPRequest struct {
	ID     string         `json:"id"`
	Action string         `json:"action"`
	Params map[string]any `json:"params"`
}

type MCPResponse struct {
	ID     string         `json:"id"`
	OK     bool           `json:"ok"`
	Data   any            `json:"data,omitempty"`
	Error  *MCPError      `json:"error,omitempty"`
}

type MCPError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

func (d *MCPDispatcher) Dispatch(ctx context.Context, client *store.Client, req MCPRequest) MCPResponse {
	switch req.Action {
	case "client.bind":
		return d.handleClientBind(ctx, client, req)
	case "client.bind.status":
		return d.handleClientBindStatus(ctx, client, req)
	case "client.unbind":
		return d.handleClientUnbind(ctx, client, req)
	case "client.unbind.status":
		return d.handleClientUnbindStatus(ctx, client, req)
	default:
		return MCPResponse{
			ID: req.ID,
			OK: false,
			Error: &MCPError{
				Code:    "action_unknown",
				Message: fmt.Sprintf("unknown action: %s", req.Action),
			},
		}
	}
}

func (d *MCPDispatcher) handleClientBind(ctx context.Context, client *store.Client, req MCPRequest) MCPResponse {
	joinCode, ok := req.Params["join_code"].(string)
	if !ok || joinCode == "" {
		return MCPResponse{
			ID: req.ID,
			OK: false,
			Error: &MCPError{
				Code:    "invalid_params",
				Message: "join_code is required",
			},
		}
	}

	// Check if client is already bound
	if client.Status == "active" && client.TenantID != "" {
		return MCPResponse{
			ID: req.ID,
			OK: true,
			Data: map[string]any{
				"status":      "already_bound",
				"tenant_id":   client.TenantID,
				"request_id":  "",
			},
		}
	}

	// Find tenant by join_code
	tenant, err := d.findTenantByJoinCode(ctx, joinCode)
	if err != nil || tenant == nil {
		return MCPResponse{
			ID: req.ID,
			OK: false,
			Error: &MCPError{
				Code:    "invalid_join_code",
				Message: "invalid join code",
			},
		}
	}

	// Create bind request
	bindReq := &store.BindRequest{
		ID:        fmt.Sprintf("req_%d", time.Now().UnixNano()),
		ClientID:  client.ClientID,
		JoinCode:  joinCode,
		TenantID:  tenant.ID,
		Status:    "pending",
		CreatedAt: time.Now().Unix(),
		UpdatedAt: time.Now().Unix(),
		ExpiresAt: time.Now().Add(24 * time.Hour).Unix(),
	}

	if _, err := d.Store.CreateBindRequest(ctx, bindReq); err != nil {
		slog.Error("create bind request failed", "err", err)
		return MCPResponse{
			ID: req.ID,
			OK: false,
			Error: &MCPError{
				Code:    "internal_error",
				Message: "failed to create bind request",
			},
		}
	}

	return MCPResponse{
		ID: req.ID,
		OK: true,
		Data: map[string]any{
			"status":      "pending_approval",
			"request_id":  bindReq.ID,
			"tenant_id":   tenant.ID,
		},
	}
}

func (d *MCPDispatcher) handleClientBindStatus(ctx context.Context, client *store.Client, req MCPRequest) MCPResponse {
	requestID, ok := req.Params["request_id"].(string)
	if !ok || requestID == "" {
		return MCPResponse{
			ID: req.ID,
			OK: false,
			Error: &MCPError{
				Code:    "invalid_params",
				Message: "request_id is required",
			},
		}
	}

	bindReq, err := d.Store.GetBindRequest(ctx, requestID)
	if err != nil || bindReq == nil {
		return MCPResponse{
			ID: req.ID,
			OK: false,
			Error: &MCPError{
				Code:    "not_found",
				Message: "bind request not found",
			},
		}
	}

	// Verify this bind request belongs to this client
	if bindReq.ClientID != client.ClientID {
		return MCPResponse{
			ID: req.ID,
			OK: false,
			Error: &MCPError{
				Code:    "forbidden",
				Message: "bind request does not belong to this client",
			},
		}
	}

	return MCPResponse{
		ID: req.ID,
		OK: true,
		Data: map[string]any{
			"status":      bindReq.Status,
			"request_id":  bindReq.ID,
			"tenant_id":   bindReq.TenantID,
		},
	}
}

func (d *MCPDispatcher) handleClientUnbind(ctx context.Context, client *store.Client, req MCPRequest) MCPResponse {
	// Client can request unbinding
	bindReq := &store.BindRequest{
		ID:        fmt.Sprintf("req_%d", time.Now().UnixNano()),
		ClientID:  client.ClientID,
		TenantID:  client.TenantID,
		Status:    "pending",
		Reason:    "client requested unbind",
		CreatedAt: time.Now().Unix(),
		UpdatedAt: time.Now().Unix(),
		ExpiresAt: time.Now().Add(24 * time.Hour).Unix(),
	}

	if _, err := d.Store.CreateBindRequest(ctx, bindReq); err != nil {
		slog.Error("create unbind request failed", "err", err)
		return MCPResponse{
			ID: req.ID,
			OK: false,
			Error: &MCPError{
				Code:    "internal_error",
				Message: "failed to create unbind request",
			},
		}
	}

	return MCPResponse{
		ID: req.ID,
		OK: true,
		Data: map[string]any{
			"status":      "pending",
			"request_id":  bindReq.ID,
		},
	}
}

func (d *MCPDispatcher) handleClientUnbindStatus(ctx context.Context, client *store.Client, req MCPRequest) MCPResponse {
	requestID, ok := req.Params["request_id"].(string)
	if !ok || requestID == "" {
		return MCPResponse{
			ID: req.ID,
			OK: false,
			Error: &MCPError{
				Code:    "invalid_params",
				Message: "request_id is required",
			},
		}
	}

	bindReq, err := d.Store.GetBindRequest(ctx, requestID)
	if err != nil || bindReq == nil {
		return MCPResponse{
			ID: req.ID,
			OK: false,
			Error: &MCPError{
				Code:    "not_found",
				Message: "unbind request not found",
			},
		}
	}

	if bindReq.ClientID != client.ClientID {
		return MCPResponse{
			ID: req.ID,
			OK: false,
			Error: &MCPError{
				Code:    "forbidden",
				Message: "unbind request does not belong to this client",
			},
		}
	}

	return MCPResponse{
		ID: req.ID,
		OK: true,
		Data: map[string]any{
			"status":      bindReq.Status,
			"request_id":  bindReq.ID,
		},
	}
}

func (d *MCPDispatcher) findTenantByJoinCode(ctx context.Context, joinCode string) (*store.User, error) {
	return d.Store.FindTenantByJoinCode(joinCode)
}