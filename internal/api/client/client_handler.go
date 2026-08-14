package clientapi

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/ceoadmin/CEOadmin/internal/store"
)

// ClientHandler handles device/client registration and MCP dispatch.
type ClientHandler struct {
	Store      store.Store
	Dispatcher *MCPDispatcher
}

// NewClientHandler creates a new ClientHandler.
func NewClientHandler(s store.Store) *ClientHandler {
	return &ClientHandler{
		Store:      s,
		Dispatcher: NewMCPDispatcher(s),
	}
}

// HandleRegisterFingerprint handles POST /api/v1/client/fingerprint
// Public endpoint - no auth required.
func (h *ClientHandler) HandleRegisterFingerprint(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()

	var req struct {
		Fingerprint    string          `json:"fingerprint"`
		ClientInfo     json.RawMessage `json:"client_info"`
		CapabilityTags json.RawMessage `json:"capability_tags"`
		HardwareConfig json.RawMessage `json:"hardware_config"`
		ConsentGranted bool            `json:"consent_granted"`
		ConsentID      string          `json:"consent_id"`
		JoinCode       string          `json:"join_code"`
		RSAPublicKey   string          `json:"rsa_public_key"`
	}

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	if req.Fingerprint == "" {
		http.Error(w, "fingerprint is required", http.StatusBadRequest)
		return
	}

	// Check if client already exists
	existing, err := h.Store.GetClientByFingerprint(ctx, req.Fingerprint)
	if err != nil {
		slog.Error("fingerprint lookup failed", "err", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	var client *store.Client
	now := time.Now().Unix()

	if existing != nil {
		// Existing client - update last_seen and return existing token
		client = existing
		client.LastSeenAt = now
		client.UpdatedAt = now
		// Extend expiry by 12 hours from now
		client.ExpiresAt = now + 12*3600
		if err := h.Store.UpdateClient(ctx, client); err != nil {
			slog.Error("update client failed", "err", err)
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		// Determine activation state
		activationRequired := client.Status == "unbound" || client.TenantID == ""
		currentState := client.Status
		if currentState == "" {
			currentState = "unbound"
		}

		h.writeFingerprintResponse(w, client, activationRequired, currentState)
		return
	}

	// New client - generate credentials
	clientID, err := generateClientID()
	if err != nil {
		slog.Error("generate client_id failed", "err", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	deviceToken, err := generateDeviceToken()
	if err != nil {
		slog.Error("generate device_token failed", "err", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	deviceSecret, err := generateDeviceSecret()
	if err != nil {
		slog.Error("generate device_secret failed", "err", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	client = &store.Client{
		ID:             generateID(),
		ClientID:       clientID,
		DeviceToken:    deviceToken,
		DeviceSecret:   deviceSecret,
		Fingerprint:    req.Fingerprint,
		ClientInfo:     string(req.ClientInfo),
		CapabilityTags: string(req.CapabilityTags),
		RiskLevel:      "trust",
		RiskScore:      0,
		Status:         "unbound",
		ExpiresAt:      now + 12*3600,
		CreatedAt:      now,
		UpdatedAt:      now,
		LastSeenAt:     now,
	}

	if _, err := h.Store.CreateClient(ctx, client); err != nil {
		slog.Error("create client failed", "err", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	h.writeFingerprintResponse(w, client, true, "unbound")
}

// writeFingerprintResponse writes the fingerprint registration response.
func (h *ClientHandler) writeFingerprintResponse(w http.ResponseWriter, client *store.Client, activationRequired bool, currentState string) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"success":             true,
		"client_id":           client.ClientID,
		"device_token":        client.DeviceToken,
		"device_secret_b64":   base64.StdEncoding.EncodeToString([]byte(client.DeviceSecret)),
		"risk_level":          client.RiskLevel,
		"risk_score":          client.RiskScore,
		"activation_required": activationRequired,
		"current_state":       currentState,
		"next_step":           "ok",
	})
}

// HandleMCPDispatch handles POST /api/v2/mcp
// Requires Bearer token auth.
func (h *ClientHandler) HandleMCPDispatch(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()

	// Extract Bearer token
	authHeader := r.Header.Get("Authorization")
	if !strings.HasPrefix(authHeader, "Bearer ") {
		h.writeMCPError(w, "missing or invalid Authorization header", http.StatusUnauthorized)
		return
	}
	token := strings.TrimPrefix(authHeader, "Bearer ")
	if token == "" {
		h.writeMCPError(w, "empty token", http.StatusUnauthorized)
		return
	}

	// Verify signature if present
	if err := h.verifySignature(r, token); err != nil {
		h.writeMCPError(w, "signature verification failed: "+err.Error(), http.StatusUnauthorized)
		return
	}

	// Look up client by device_token
	client, err := h.Store.GetClientByDeviceToken(ctx, token)
	if err != nil {
		slog.Warn("mcp: token lookup failed", "err", err)
		h.writeMCPError(w, "invalid token", http.StatusUnauthorized)
		return
	}
	if client == nil {
		h.writeMCPError(w, "invalid token", http.StatusUnauthorized)
		return
	}

	// Check expiry
	if client.ExpiresAt > 0 && time.Now().Unix() > client.ExpiresAt {
		h.writeMCPError(w, "device token expired", http.StatusUnauthorized)
		return
	}

	// Check status
	if client.Status == "revoked" {
		h.writeMCPError(w, "device token has been revoked", http.StatusUnauthorized)
		return
	}

	// Update last_seen
	h.Store.UpdateClientLastSeen(ctx, client.ID)

	// Parse MCP request
	var mcpReq struct {
		ID     string         `json:"id"`
		Action string         `json:"action"`
		Params map[string]any `json:"params"`
	}
	if err := json.NewDecoder(r.Body).Decode(&mcpReq); err != nil {
		h.writeMCPError(w, "invalid request body", http.StatusBadRequest)
		return
	}

	if mcpReq.Action == "" {
		h.writeMCPError(w, "action is required", http.StatusBadRequest)
		return
	}

	// Dispatch action via MCP dispatcher
	resp := h.Dispatcher.Dispatch(ctx, client, MCPRequest{
		ID:     mcpReq.ID,
		Action: mcpReq.Action,
		Params: mcpReq.Params,
	})

	if !resp.OK {
		h.writeMCPError(w, resp.Error.Message, http.StatusBadRequest)
		return
	}

	h.writeMCPResponse(w, resp.ID, resp.Data)
}

// verifySignature verifies the HMAC-SHA256 signature.
func (h *ClientHandler) verifySignature(r *http.Request, deviceToken string) error {
	// Get client to retrieve device_secret
	ctx := r.Context()
	client, err := h.Store.GetClientByDeviceToken(ctx, deviceToken)
	if err != nil || client == nil {
		return fmt.Errorf("client not found")
	}

	// Check for signature headers
	timestamp := r.Header.Get("x-claw-timestamp")
	nonce := r.Header.Get("x-claw-nonce")
	signature := r.Header.Get("x-claw-signature")

	if timestamp == "" || nonce == "" || signature == "" {
		// Signature headers not provided - skip verification for now
		return nil
	}

	// Verify timestamp window (5 minutes)
	ts := int64(0)
	if _, err := fmt.Sscanf(timestamp, "%d", &ts); err != nil {
		return fmt.Errorf("invalid timestamp")
	}
	if time.Now().Unix()-ts > 300 || ts-time.Now().Unix() > 300 {
		return fmt.Errorf("timestamp out of window")
	}

	// Read body
	body, err := io.ReadAll(r.Body)
	if err != nil {
		return fmt.Errorf("read body failed")
	}

	// Build canonical string
	method := r.Method
	path := r.URL.Path
	query := r.URL.RawQuery

	// Sort query params
	var sortedQuery string
	if query != "" {
		// Simple implementation - in production use proper canonicalization
		sortedQuery = query
	}

	bodyHash := sha256.Sum256(body)
	bodyHashHex := hex.EncodeToString(bodyHash[:])

	canonical := fmt.Sprintf("%s\n%s\n%s\n%s\n%s\n%s", strings.ToUpper(method), path, sortedQuery, timestamp, nonce, bodyHashHex)

	// Compute expected signature
	mac := hmac.New(sha256.New, []byte(client.DeviceSecret))
	mac.Write([]byte(canonical))
	expectedSig := hex.EncodeToString(mac.Sum(nil))

	if !hmac.Equal([]byte(expectedSig), []byte(signature)) {
		return fmt.Errorf("signature mismatch")
	}

	return nil
}

func (h *ClientHandler) writeMCPError(w http.ResponseWriter, msg string, code int) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(map[string]any{
		"ok":    false,
		"error": map[string]string{"code": "unauthorized", "message": msg},
	})
}

func (h *ClientHandler) writeMCPResponse(w http.ResponseWriter, id string, data any) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"id":   id,
		"ok":   true,
		"data": data,
	})
}

func generateClientID() (string, error) {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return "cli_" + base64.RawURLEncoding.EncodeToString(b), nil
}

func generateDeviceToken() (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return "dt_" + base64.RawURLEncoding.EncodeToString(b), nil
}

func generateDeviceSecret() (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return "ds_" + base64.RawURLEncoding.EncodeToString(b), nil
}

func generateID() string {
	b := make([]byte, 16)
	rand.Read(b)
	return hex.EncodeToString(b)
}
