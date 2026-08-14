package tenantapi

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"

	"github.com/ceoadmin/CEOadmin/internal/api/shared"
	"github.com/ceoadmin/CEOadmin/internal/auth"
	"github.com/ceoadmin/CEOadmin/internal/store"
)

// TenantBindHandler handles tenant-level device bind approval.
type TenantBindHandler struct {
	Store store.Store
}

func NewTenantBindHandler(s store.Store) *TenantBindHandler {
	return &TenantBindHandler{Store: s}
}

// GET /api/tenant/client-binds — list bind requests for current tenant.
func (h *TenantBindHandler) HandleListBinds(w http.ResponseWriter, r *http.Request) {
	slog.Info("HandleListBinds called", "path", r.URL.Path, "method", r.Method)
	userID := auth.UserIDFromContext(r.Context())
	if userID == "" {
		shared.JSONError(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	// Get user to find their tenant_id
	user, err := h.Store.GetUserByID(userID)
	if err != nil {
		shared.JSONError(w, "user not found", http.StatusNotFound)
		return
	}
	tenantID := user.ID // Users are tenants in this model

	// Parse query params
	status := r.URL.Query().Get("status")
	if status == "" {
		status = "pending"
	}

	// List bind requests for this tenant
	binds, err := h.listTenantBindRequests(r.Context(), tenantID, status)
	if err != nil {
		slog.Error("list tenant binds failed", "tenant", tenantID, "err", err)
		shared.JSONError(w, "query failed", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	if binds == nil {
		binds = []map[string]any{}
	}
	json.NewEncoder(w).Encode(map[string]any{
		"binds": binds,
		"total": len(binds),
	})
}

func (h *TenantBindHandler) listTenantBindRequests(ctx context.Context, tenantID, status string) ([]map[string]any, error) {
	// Get bind requests directly by tenant_id (includes pending requests)
	reqs, err := h.Store.ListBindRequestsByTenant(ctx, tenantID, status)
	if err != nil {
		return nil, err
	}

	var allBinds []map[string]any
	for _, req := range reqs {
		// Get client info for each bind request
		client, _ := h.Store.GetClientByID(ctx, req.ClientID)
		clientToken := ""
		fingerprint := ""
		clientInfo := ""
		if client != nil {
			clientToken = client.DeviceToken
			fingerprint = client.Fingerprint
			clientInfo = client.ClientInfo
		}
		allBinds = append(allBinds, map[string]any{
			"id":           req.ID,
			"client_id":    req.ClientID,
			"join_code":    req.JoinCode,
			"tenant_id":    req.TenantID,
			"status":       req.Status,
			"reason":       req.Reason,
			"created_at":   req.CreatedAt,
			"updated_at":   req.UpdatedAt,
			"expires_at":   req.ExpiresAt,
			"device_token": clientToken,
			"fingerprint":  fingerprint,
			"client_info":  clientInfo,
		})
	}

	return allBinds, nil
}

func (h *TenantBindHandler) getBindRequestsByTenant(ctx context.Context, tenantID, status string) ([]map[string]any, error) {
	// This needs a store method to query bind_requests by tenant_id
	// For now, return empty - will add to store interface later
	return []map[string]any{}, nil
}

// PUT /api/tenant/client-binds/{id}/approve — approve bind request.
func (h *TenantBindHandler) HandleApproveBind(w http.ResponseWriter, r *http.Request) {
	userID := auth.UserIDFromContext(r.Context())
	if userID == "" {
		shared.JSONError(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	bindID := r.PathValue("id")
	if bindID == "" {
		shared.JSONError(w, "bind ID required", http.StatusBadRequest)
		return
	}

	// Get bind request
	req, err := h.Store.GetBindRequest(r.Context(), bindID)
	if err != nil || req == nil {
		shared.JSONError(w, "bind request not found", http.StatusNotFound)
		return
	}

	// Verify tenant ownership
	user, err := h.Store.GetUserByID(userID)
	if err != nil {
		shared.JSONError(w, "user not found", http.StatusNotFound)
		return
	}
	if req.TenantID != user.ID {
		shared.JSONError(w, "forbidden", http.StatusForbidden)
		return
	}

	if req.Status != "pending" {
		shared.JSONError(w, "bind request already processed", http.StatusBadRequest)
		return
	}

	// Update bind request status
	req.Status = "approved"
	req.UpdatedAt = store.Now()
	if err := h.Store.UpdateBindRequest(r.Context(), req); err != nil {
		slog.Error("update bind request failed", "id", bindID, "err", err)
		shared.JSONError(w, "update failed", http.StatusInternalServerError)
		return
	}

	// Update client: set tenant_id and status=active
	client, err := h.Store.GetClientByClientID(r.Context(), req.ClientID)
	if err != nil || client == nil {
		shared.JSONError(w, "client not found", http.StatusNotFound)
		return
	}
	client.TenantID = user.ID
	client.Status = "active"
	client.UpdatedAt = store.Now()
	if err := h.Store.UpdateClient(r.Context(), client); err != nil {
		slog.Error("update client failed", "client", req.ClientID, "err", err)
		shared.JSONError(w, "client update failed", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"success":   true,
		"bind_id":   bindID,
		"client_id": req.ClientID,
		"tenant_id": user.ID,
	})
}

// PUT /api/tenant/client-binds/{id}/reject — reject bind request.
func (h *TenantBindHandler) HandleRejectBind(w http.ResponseWriter, r *http.Request) {
	userID := auth.UserIDFromContext(r.Context())
	if userID == "" {
		shared.JSONError(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	bindID := r.PathValue("id")
	if bindID == "" {
		shared.JSONError(w, "bind ID required", http.StatusBadRequest)
		return
	}

	var reqBody struct {
		Reason string `json:"reason"`
	}
	if err := json.NewDecoder(r.Body).Decode(&reqBody); err != nil {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}

	// Get bind request
	req, err := h.Store.GetBindRequest(r.Context(), bindID)
	if err != nil || req == nil {
		shared.JSONError(w, "bind request not found", http.StatusNotFound)
		return
	}

	// Verify tenant ownership
	user, err := h.Store.GetUserByID(userID)
	if err != nil {
		shared.JSONError(w, "user not found", http.StatusNotFound)
		return
	}
	if req.TenantID != user.ID {
		shared.JSONError(w, "forbidden", http.StatusForbidden)
		return
	}

	if req.Status != "pending" {
		shared.JSONError(w, "bind request already processed", http.StatusBadRequest)
		return
	}

	// Update bind request status
	req.Status = "rejected"
	req.Reason = reqBody.Reason
	req.UpdatedAt = store.Now()
	if err := h.Store.UpdateBindRequest(r.Context(), req); err != nil {
		slog.Error("update bind request failed", "id", bindID, "err", err)
		shared.JSONError(w, "update failed", http.StatusInternalServerError)
		return
	}

	// Optionally revoke client
	client, _ := h.Store.GetClientByID(r.Context(), req.ClientID)
	if client != nil {
		client.Status = "revoked"
		client.UpdatedAt = store.Now()
		h.Store.UpdateClient(r.Context(), client)
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"success": true,
		"bind_id": bindID,
	})
}

// Add to store interface: ListClientsByTenant
