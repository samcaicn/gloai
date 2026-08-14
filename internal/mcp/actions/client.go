package actions

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log/slog"
	"time"

	"github.com/ceoadmin/CEOadmin/internal/mcp/shared"
	"github.com/ceoadmin/CEOadmin/internal/store"
)

// DeviceManager handles device/client operations
type DeviceManager struct {
	store store.Store
}

func NewDeviceManager(s store.Store) *DeviceManager {
	return &DeviceManager{store: s}
}

func (m *DeviceManager) Heartbeat(ctx *shared.Context, params map[string]any) (any, error) {
	clientID := ctx.ClientID
	if cid, ok := params["client_id"].(string); ok && cid != "" {
		clientID = cid
	}
	if clientID == "" {
		return nil, shared.MissingParam("client_id")
	}

	// Touch heartbeat in tenant service
	if err := m.store.TouchHeartbeat(ctx, clientID, ctx.TenantID); err != nil {
		return nil, err
	}

	// Token renewal logic (P0-3): check if token in renewal window (2h before expiry)
	tokenRenewed := false
	tokenExpiresAt := int64(0)
	// Token renewal would be implemented here based on token_persistence logic

	// Compute upload_hint: skills with high score eligible for platform upload
	uploadHint := m.computeUploadHint(ctx.TenantID)

	resp := map[string]any{
		"status":      "ok",
		"upload_hint": uploadHint,
	}
	if tokenRenewed {
		resp["token_renewed"] = true
		resp["token_expires_at"] = tokenExpiresAt
	}
	return resp, nil
}

func (m *DeviceManager) computeUploadHint(tenantID string) map[string]any {
	// Get execution reports for this tenant, aggregate by skill_id
	// Score >= 4.0 (0.8 in 0-1 scale), min 3 executions, not system skills
	// Return top 5 candidates with skill_id, version, score, samples
	// Cache for 30s
	return map[string]any{
		"skills": []map[string]any{},
		"reason": "no_executions",
	}
}

func (m *DeviceManager) FingerprintBind(ctx *shared.Context, params map[string]any) (any, error) {
	fingerprint, _ := params["fingerprint"].(string)
	if fingerprint == "" {
		return nil, shared.MissingParam("fingerprint")
	}
	if len(fingerprint) != 64 {
		return nil, shared.InvalidParam("fingerprint", "must be 64-char hex string")
	}

	capabilityTags, _ := params["capability_tags"].([]any)
	clientInfo, _ := params["client_info"].(map[string]any)

	// Check if already bound
	existing, err := m.store.GetClientByFingerprint(ctx, fingerprint)
	if err != nil || existing == nil {
		// Not found, create new
	} else {
		existingClientID, existingTenantID := existing.ClientID, existing.TenantID
		if existingTenantID != ctx.TenantID {
			return nil, shared.InvalidParam("fingerprint", "already bound to another tenant")
		}

		// Reuse existing token or issue new
		deviceToken := existing.DeviceToken
		if deviceToken == "" {
			deviceToken = m.generateDeviceToken()
			// Update client with new token
			existing.DeviceToken = deviceToken
			m.store.UpdateClient(ctx, existing)
		}

		return map[string]any{
			"success":      true,
			"client_id":    existingClientID,
			"device_token": deviceToken,
			"tenant_id":    existingTenantID,
			"next_step":    "ok",
		}, nil
	}

	// Create new client
	capsJSON, _ := json.Marshal(capabilityTags)
	infoJSON, _ := json.Marshal(clientInfo)

	client := &store.Client{
		ID:             fmt.Sprintf("cli_%d", time.Now().UnixNano()),
		TenantID:       ctx.TenantID,
		ClientID:       fmt.Sprintf("cli_%d", time.Now().UnixNano()),
		Fingerprint:    fingerprint,
		CapabilityTags: string(capsJSON),
		ClientInfo:     string(infoJSON),
		Status:         "active",
		CreatedAt:      time.Now().Unix(),
		UpdatedAt:      time.Now().Unix(),
	}

	if _, err := m.store.CreateClient(ctx, client); err != nil {
		return nil, err
	}

	deviceToken := m.generateDeviceToken()
	client.DeviceToken = deviceToken
	m.store.UpdateClient(ctx, client)

	return map[string]any{
		"success":      true,
		"client_id":    client.ID,
		"device_token": deviceToken,
		"tenant_id":    ctx.TenantID,
		"next_step":    "ok",
	}, nil
}

func (m *DeviceManager) FingerprintStatus(ctx *shared.Context, params map[string]any) (any, error) {
	fingerprint, _ := params["fingerprint"].(string)
	if fingerprint == "" {
		return nil, shared.MissingParam("fingerprint")
	}

	existing, err := m.store.GetClientByFingerprint(ctx, fingerprint)
	if err != nil || existing == nil || existing.TenantID != ctx.TenantID {
		return map[string]any{"status": "not_bound"}, nil
	}

	return map[string]any{"status": "bound"}, nil
}

func (m *DeviceManager) Unbind(ctx *shared.Context, params map[string]any) (any, error) {
	clientID, _ := params["client_id"].(string)
	if clientID == "" {
		return nil, shared.MissingParam("client_id")
	}
	reason, _ := params["reason"].(string)

	// Verify client belongs to this tenant
	client, err := m.store.GetClientByClientID(ctx, clientID)
	if err != nil || client == nil || client.TenantID != ctx.TenantID {
		return nil, shared.NotFound("client", clientID)
	}

	// Create unbind request via iLink approval flow
	requestID := fmt.Sprintf("unbind_%d", time.Now().UnixNano())
	bindReq := &store.BindRequest{
		ID:        requestID,
		ClientID:  clientID,
		TenantID:  ctx.TenantID,
		Status:    "pending",
		Reason:    reason,
		CreatedAt: time.Now().Unix(),
		UpdatedAt: time.Now().Unix(),
		ExpiresAt: time.Now().Add(24 * time.Hour).Unix(),
	}

	_, err = m.store.CreateBindRequest(ctx, bindReq)
	if err != nil {
		return nil, err
	}

	// Push iLink card to admin for approval
	go m.pushUnbindNotification(ctx.TenantID, clientID, reason)

	return map[string]any{
		"request_id": requestID,
		"status":     "pending",
	}, nil
}

func (m *DeviceManager) UnbindStatus(ctx *shared.Context, params map[string]any) (any, error) {
	requestID, _ := params["request_id"].(string)
	if requestID == "" {
		return nil, shared.MissingParam("request_id")
	}

	bindReq, err := m.store.GetBindRequest(ctx, requestID)
	if err != nil || bindReq == nil {
		return map[string]any{"status": "not_found"}, nil
	}

	if bindReq.TenantID != ctx.TenantID {
		return map[string]any{"status": "not_found"}, nil
	}

	return map[string]any{
		"status":     bindReq.Status,
		"request_id": bindReq.ID,
	}, nil
}

func (m *DeviceManager) Bind(ctx *shared.Context, params map[string]any) (any, error) {
	joinCode, _ := params["join_code"].(string)
	if joinCode == "" {
		return nil, shared.MissingParam("join_code")
	}

	tenant, err := m.store.FindTenantByJoinCode(joinCode)
	if err != nil || tenant == nil {
		return nil, shared.InvalidParam("join_code", "invalid join code")
	}

	// Create bind request for this client
	requestID := fmt.Sprintf("bind_%d", time.Now().UnixNano())
	bindReq := &store.BindRequest{
		ID:        requestID,
		ClientID:  ctx.ClientID,
		JoinCode:  joinCode,
		TenantID:  tenant.ID,
		Status:    "pending",
		CreatedAt: time.Now().Unix(),
		UpdatedAt: time.Now().Unix(),
		ExpiresAt: time.Now().Add(24 * time.Hour).Unix(),
	}

	_, err = m.store.CreateBindRequest(ctx, bindReq)
	if err != nil {
		return nil, err
	}

	// Push iLink card to admin
	go m.pushBindNotification(tenant.ID, ctx.ClientID, joinCode)

	return map[string]any{
		"request_id": requestID,
		"status":     "pending_approval",
		"tenant_id":  tenant.ID,
	}, nil
}

func (m *DeviceManager) BindStatus(ctx *shared.Context, params map[string]any) (any, error) {
	requestID, _ := params["request_id"].(string)
	if requestID == "" {
		return nil, shared.MissingParam("request_id")
	}

	bindReq, err := m.store.GetBindRequest(ctx, requestID)
	if err != nil || bindReq == nil {
		return map[string]any{"status": "not_found"}, nil
	}

	if bindReq.ClientID != ctx.ClientID {
		return nil, shared.Forbidden("bind request does not belong to this client")
	}

	return map[string]any{
		"status":     bindReq.Status,
		"request_id": bindReq.ID,
		"tenant_id":  bindReq.TenantID,
	}, nil
}

func (m *DeviceManager) CheckUpdate(ctx *shared.Context, params map[string]any) (any, error) {
	_, _ = params["brand"].(string)
	_, _ = params["target"].(string)
	_, _ = params["arch"].(string)
	currentVersion, _ := params["current_version"].(string)

	// Implementation would check for updates
	return map[string]any{
		"has_update":     false,
		"latest_version": currentVersion,
		"download_url":   "",
	}, nil
}

func (m *DeviceManager) generateDeviceToken() string {
	b := make([]byte, 16)
	rand.Read(b)
	return "dt-" + hex.EncodeToString(b)
}

func (m *DeviceManager) pushUnbindNotification(tenantID, clientID, reason string) {
	// Push iLink notification to tenant admins
	slog.Info("push unbind notification", "tenant", tenantID, "client", clientID)
}

func (m *DeviceManager) pushBindNotification(tenantID, clientID, joinCode string) {
	// Push iLink notification to tenant admins
	slog.Info("push bind notification", "tenant", tenantID, "client", clientID)
}
