package postgres

import (
	"context"
	"database/sql"
	"fmt"

	"github.com/ceoadmin/CEOadmin/internal/store"
)

// CreateClient inserts a new client.
func (db *DB) CreateClient(ctx context.Context, c *store.Client) (*store.Client, error) {
	now := db.now().Unix()
	c.CreatedAt = now
	c.UpdatedAt = now
	if c.ExpiresAt == 0 {
		c.ExpiresAt = now + 12*3600 // 12 hours default
	}
	if c.Status == "" {
		c.Status = "unbound"
	}
	if c.RiskLevel == "" {
		c.RiskLevel = "trust"
	}

	clientInfo := "{}"
	if c.ClientInfo != "" {
		clientInfo = c.ClientInfo
	}
	capabilityTags := "[]"
	if c.CapabilityTags != "" {
		capabilityTags = c.CapabilityTags
	}

	_, err := db.ExecContext(ctx, `
		INSERT INTO clients (id, tenant_id, client_id, device_token, device_secret, fingerprint, client_info, capability_tags, risk_level, risk_score, status, expires_at, created_at, updated_at, last_seen_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15)
	`, c.ID, c.TenantID, c.ClientID, c.DeviceToken, c.DeviceSecret, c.Fingerprint, clientInfo, capabilityTags, c.RiskLevel, c.RiskScore, c.Status, c.ExpiresAt, c.CreatedAt, c.UpdatedAt, c.LastSeenAt)
	if err != nil {
		return nil, fmt.Errorf("insert client: %w", err)
	}
	return c, nil
}

// GetClientByDeviceToken looks up a client by device_token.
func (db *DB) GetClientByDeviceToken(ctx context.Context, token string) (*store.Client, error) {
	row := db.QueryRowContext(ctx, `
		SELECT id, tenant_id, client_id, device_token, device_secret, fingerprint, client_info, capability_tags, risk_level, risk_score, status, expires_at, created_at, updated_at, last_seen_at
		FROM clients WHERE device_token = $1
	`, token)
	return scanClient(row)
}

// GetClientByID looks up a client by ID.
func (db *DB) GetClientByID(ctx context.Context, id string) (*store.Client, error) {
	row := db.QueryRowContext(ctx, `
		SELECT id, tenant_id, client_id, device_token, device_secret, fingerprint, client_info, capability_tags, risk_level, risk_score, status, expires_at, created_at, updated_at, last_seen_at
		FROM clients WHERE id = $1
	`, id)
	return scanClient(row)
}

// GetClientByClientID looks up a client by ClientID (cli_ prefix).
func (db *DB) GetClientByClientID(ctx context.Context, clientID string) (*store.Client, error) {
	row := db.QueryRowContext(ctx, `
		SELECT id, tenant_id, client_id, device_token, device_secret, fingerprint, client_info, capability_tags, risk_level, risk_score, status, expires_at, created_at, updated_at, last_seen_at
		FROM clients WHERE client_id = $1
	`, clientID)
	return scanClient(row)
}

// GetClient looks up a client by clientID and tenantID.
func (db *DB) GetClient(ctx context.Context, clientID, tenantID string) (*store.Client, error) {
	row := db.QueryRowContext(ctx, `
		SELECT id, tenant_id, client_id, device_token, device_secret, fingerprint, client_info, capability_tags, risk_level, risk_score, status, expires_at, created_at, updated_at, last_seen_at
		FROM clients WHERE client_id = $1 AND tenant_id = $2
	`, clientID, tenantID)
	return scanClient(row)
}

// GetClientByFingerprint looks up a client by fingerprint.
func (db *DB) GetClientByFingerprint(ctx context.Context, fingerprint string) (*store.Client, error) {
	row := db.QueryRowContext(ctx, `
		SELECT id, tenant_id, client_id, device_token, device_secret, fingerprint, client_info, capability_tags, risk_level, risk_score, status, expires_at, created_at, updated_at, last_seen_at
		FROM clients WHERE fingerprint = $1
	`, fingerprint)
	return scanClient(row)
}

// FindFingerprint is an alias for GetClientByFingerprint.
func (db *DB) FindFingerprint(ctx context.Context, fingerprint string) (*store.Client, error) {
	return db.GetClientByFingerprint(ctx, fingerprint)
}

// TouchHeartbeat updates the client's last_seen_at timestamp (no-op for PostgreSQL).
func (db *DB) TouchHeartbeat(ctx context.Context, clientID, tenantID string) error {
	return nil
}

// UpdateClient updates client fields.
func (db *DB) UpdateClient(ctx context.Context, c *store.Client) error {
	c.UpdatedAt = db.now().Unix()

	clientInfo := "{}"
	if c.ClientInfo != "" {
		clientInfo = c.ClientInfo
	}
	capabilityTags := "[]"
	if c.CapabilityTags != "" {
		capabilityTags = c.CapabilityTags
	}

	_, err := db.ExecContext(ctx, `
		UPDATE clients SET tenant_id=$1, client_id=$2, device_token=$3, device_secret=$4, fingerprint=$5, client_info=$6, capability_tags=$7, risk_level=$8, risk_score=$9, status=$10, expires_at=$11, updated_at=$12, last_seen_at=$13
		WHERE id=$14
	`, c.TenantID, c.ClientID, c.DeviceToken, c.DeviceSecret, c.Fingerprint, clientInfo, capabilityTags, c.RiskLevel, c.RiskScore, c.Status, c.ExpiresAt, c.UpdatedAt, c.LastSeenAt, c.ID)
	return err
}

// UpdateClientLastSeen updates the last_seen_at timestamp.
func (db *DB) UpdateClientLastSeen(ctx context.Context, id string) error {
	now := db.now().Unix()
	_, err := db.ExecContext(ctx, `
		UPDATE clients SET last_seen_at=$1, updated_at=$1 WHERE id=$2
	`, now, id)
	return err
}

// RevokeClient marks a client as revoked.
func (db *DB) RevokeClient(ctx context.Context, id string) error {
	now := db.now().Unix()
	_, err := db.ExecContext(ctx, `
		UPDATE clients SET status='revoked', updated_at=$1 WHERE id=$2
	`, now, id)
	return err
}

// CreateBindRequest creates a pending bind request.
func (db *DB) CreateBindRequest(ctx context.Context, req *store.BindRequest) (*store.BindRequest, error) {
	now := db.now().Unix()
	req.CreatedAt = now
	req.UpdatedAt = now
	if req.ExpiresAt == 0 {
		req.ExpiresAt = now + 24*3600 // 24 hours
	}
	if req.Status == "" {
		req.Status = "pending"
	}

	_, err := db.ExecContext(ctx, `
		INSERT INTO bind_requests (id, client_id, join_code, tenant_id, status, reason, created_at, updated_at, expires_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
	`, req.ID, req.ClientID, req.JoinCode, req.TenantID, req.Status, req.Reason, req.CreatedAt, req.UpdatedAt, req.ExpiresAt)
	if err != nil {
		return nil, fmt.Errorf("insert bind request: %w", err)
	}
	return req, nil
}

// GetBindRequest looks up a bind request by ID.
func (db *DB) GetBindRequest(ctx context.Context, id string) (*store.BindRequest, error) {
	row := db.QueryRowContext(ctx, `
		SELECT id, client_id, join_code, tenant_id, status, reason, created_at, updated_at, expires_at
		FROM bind_requests WHERE id = $1
	`, id)
	return scanBindRequest(row)
}

// UpdateBindRequest updates a bind request status.
func (db *DB) UpdateBindRequest(ctx context.Context, req *store.BindRequest) error {
	req.UpdatedAt = db.now().Unix()
	_, err := db.ExecContext(ctx, `
		UPDATE bind_requests SET client_id=$1, join_code=$2, tenant_id=$3, status=$4, reason=$5, updated_at=$6, expires_at=$7
		WHERE id=$8
	`, req.ClientID, req.JoinCode, req.TenantID, req.Status, req.Reason, req.UpdatedAt, req.ExpiresAt, req.ID)
	return err
}

// ListBindRequestsByClient lists bind requests for a client.
func (db *DB) ListBindRequestsByClient(ctx context.Context, clientID string) ([]store.BindRequest, error) {
	rows, err := db.QueryContext(ctx, `
		SELECT id, client_id, join_code, tenant_id, status, reason, created_at, updated_at, expires_at
		FROM bind_requests WHERE client_id = $1 ORDER BY created_at DESC
	`, clientID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var reqs []store.BindRequest
	for rows.Next() {
		req, err := scanBindRequest(rows)
		if err != nil {
			return nil, err
		}
		reqs = append(reqs, *req)
	}
	return reqs, rows.Err()
}

// CleanExpiredClients removes expired clients.
func (db *DB) CleanExpiredClients(ctx context.Context) (int, error) {
	res, err := db.ExecContext(ctx, `
		DELETE FROM clients WHERE expires_at > 0 AND expires_at < $1
	`, db.now().Unix())
	if err != nil {
		return 0, err
	}
	n, _ := res.RowsAffected()
	return int(n), nil
}

// ListBindRequestsByTenant lists all bind requests for a tenant.
func (db *DB) ListBindRequestsByTenant(ctx context.Context, tenantID, status string) ([]store.BindRequest, error) {
	query := `
		SELECT id, client_id, join_code, tenant_id, status, reason, created_at, updated_at, expires_at
		FROM bind_requests WHERE tenant_id = $1
	`
	args := []any{tenantID}
	if status != "" && status != "all" {
		query += " AND status = $2"
		args = append(args, status)
	}
	query += " ORDER BY created_at DESC"

	rows, err := db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var reqs []store.BindRequest
	for rows.Next() {
		req, err := scanBindRequest(rows)
		if err != nil {
			return nil, err
		}
		if req != nil {
			reqs = append(reqs, *req)
		}
	}
	return reqs, rows.Err()
}

// ListClientsByTenant lists all clients for a tenant.
func (db *DB) ListClientsByTenant(ctx context.Context, tenantID string) ([]store.Client, error) {
	rows, err := db.QueryContext(ctx, `
		SELECT id, tenant_id, client_id, device_token, device_secret, fingerprint, client_info, capability_tags, risk_level, risk_score, status, expires_at, created_at, updated_at, last_seen_at
		FROM clients WHERE tenant_id = $1
	`, tenantID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var clients []store.Client
	for rows.Next() {
		c, err := scanClient(rows)
		if err != nil {
			return nil, err
		}
		if c != nil {
			clients = append(clients, *c)
		}
	}
	return clients, rows.Err()
}

func scanClient(scanner interface {
	Scan(dest ...any) error
}) (*store.Client, error) {
	var c store.Client
	var clientInfo, capabilityTags []byte
	err := scanner.Scan(&c.ID, &c.TenantID, &c.ClientID, &c.DeviceToken, &c.DeviceSecret, &c.Fingerprint, &clientInfo, &capabilityTags, &c.RiskLevel, &c.RiskScore, &c.Status, &c.ExpiresAt, &c.CreatedAt, &c.UpdatedAt, &c.LastSeenAt)
	if err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	c.ClientInfo = string(clientInfo)
	c.CapabilityTags = string(capabilityTags)
	return &c, nil
}

func scanBindRequest(scanner interface {
	Scan(dest ...any) error
}) (*store.BindRequest, error) {
	var req store.BindRequest
	err := scanner.Scan(&req.ID, &req.ClientID, &req.JoinCode, &req.TenantID, &req.Status, &req.Reason, &req.CreatedAt, &req.UpdatedAt, &req.ExpiresAt)
	if err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	return &req, nil
}
