package store

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"time"
)

// Client represents a registered device/client for MCP access.
type Client struct {
	ID              string `json:"id"`
	TenantID        string `json:"tenant_id"`
	ClientID        string `json:"client_id"`
	DeviceToken     string `json:"device_token"`
	DeviceSecret    string `json:"device_secret"`     // HMAC secret for request signing
	Fingerprint     string `json:"fingerprint"`
	ClientInfo      string `json:"client_info"`       // JSON blob
	CapabilityTags  string `json:"capability_tags"`   // JSON blob
	RiskLevel       string `json:"risk_level"`        // trust, review, block
	RiskScore       int    `json:"risk_score"`
	Status          string `json:"status"`            // unbound, active, revoked
	ExpiresAt       int64  `json:"expires_at"`        // Unix timestamp
	CreatedAt       int64  `json:"created_at"`
	UpdatedAt       int64  `json:"updated_at"`
	LastSeenAt      int64  `json:"last_seen_at"`
}

// BindRequest represents a pending bind request (join_code flow).
type BindRequest struct {
	ID          string `json:"id"`
	ClientID    string `json:"client_id"`
	JoinCode    string `json:"join_code"`
	TenantID    string `json:"tenant_id"`
	Status      string `json:"status"`       // pending, approved, rejected
	Reason      string `json:"reason"`
	CreatedAt   int64  `json:"created_at"`
	UpdatedAt   int64  `json:"updated_at"`
	ExpiresAt   int64  `json:"expires_at"`
}

// ClientStore interface for device/client management.
type ClientStore interface {
	// CreateClient registers a new device/client from fingerprint.
	CreateClient(ctx context.Context, client *Client) (*Client, error)

	// GetClientByDeviceToken looks up a client by device_token.
	GetClientByDeviceToken(ctx context.Context, token string) (*Client, error)

	// GetClientByID looks up a client by ID.
	GetClientByID(ctx context.Context, id string) (*Client, error)

	// GetClientByClientID looks up a client by ClientID (cli_ prefix).
	GetClientByClientID(ctx context.Context, clientID string) (*Client, error)

	// GetClientByFingerprint looks up a client by fingerprint.
	GetClientByFingerprint(ctx context.Context, fingerprint string) (*Client, error)

	// UpdateClient updates client fields.
	UpdateClient(ctx context.Context, client *Client) error

	// UpdateClientLastSeen updates the last_seen_at timestamp.
	UpdateClientLastSeen(ctx context.Context, id string) error

	// RevokeClient marks a client as revoked.
	RevokeClient(ctx context.Context, id string) error

	// CreateBindRequest creates a pending bind request.
	CreateBindRequest(ctx context.Context, req *BindRequest) (*BindRequest, error)

	// GetBindRequest looks up a bind request by ID.
	GetBindRequest(ctx context.Context, id string) (*BindRequest, error)

	// UpdateBindRequest updates a bind request status.
	UpdateBindRequest(ctx context.Context, req *BindRequest) error

	// ListBindRequestsByClient lists bind requests for a client.
	ListBindRequestsByClient(ctx context.Context, clientID string) ([]BindRequest, error)

	// ListClientsByTenant lists all clients for a tenant.
	ListClientsByTenant(ctx context.Context, tenantID string) ([]Client, error)

	// ListBindRequestsByTenant lists all bind requests for a tenant.
	ListBindRequestsByTenant(ctx context.Context, tenantID, status string) ([]BindRequest, error)

	// CleanExpiredClients removes expired clients (for cleanup jobs).
	CleanExpiredClients(ctx context.Context) (int, error)
}

// GenerateDeviceToken creates a secure device token.
func GenerateDeviceToken() (string, error) {
	return generateToken("dt_", 32)
}

// GenerateDeviceSecret creates a secure HMAC secret for signing.
func GenerateDeviceSecret() (string, error) {
	return generateToken("ds_", 32)
}

// GenerateClientID creates a client ID.
func GenerateClientID() (string, error) {
	return generateToken("cli_", 16)
}

// GenerateRequestID creates a request ID for bind/unbind operations.
func GenerateRequestID() (string, error) {
	return generateToken("req_", 16)
}

// generateToken generates a random token with prefix.
func generateToken(prefix string, bytes int) (string, error) {
	b := make([]byte, bytes)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return prefix + base64.RawURLEncoding.EncodeToString(b), nil
}

// Now returns current Unix timestamp.
func Now() int64 {
	return time.Now().Unix()
}

// Expiry12Hours returns timestamp 12 hours from now.
func Expiry12Hours() int64 {
	return Now() + 12*3600
}

// Expiry24Hours returns timestamp 24 hours from now.
func Expiry24Hours() int64 {
	return Now() + 24*3600
}