-- +goose Up
-- Client/device registration for MCP access.

CREATE TABLE IF NOT EXISTS clients (
    id              TEXT PRIMARY KEY,
    tenant_id       TEXT NOT NULL,
    client_id       TEXT NOT NULL UNIQUE,
    device_token    TEXT NOT NULL UNIQUE,
    device_secret   TEXT NOT NULL,
    fingerprint     TEXT NOT NULL,
    client_info     TEXT NOT NULL DEFAULT '{}',
    capability_tags TEXT NOT NULL DEFAULT '[]',
    risk_level      TEXT NOT NULL DEFAULT 'trust',
    risk_score      INTEGER NOT NULL DEFAULT 0,
    status          TEXT NOT NULL DEFAULT 'unbound',  -- unbound, active, revoked
    expires_at      INTEGER NOT NULL,
    created_at      INTEGER NOT NULL DEFAULT (unixepoch()),
    updated_at      INTEGER NOT NULL DEFAULT (unixepoch()),
    last_seen_at    INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_clients_tenant ON clients(tenant_id);
CREATE INDEX IF NOT EXISTS idx_clients_device_token ON clients(device_token);
CREATE INDEX IF NOT EXISTS idx_clients_client_id ON clients(client_id);
CREATE INDEX IF NOT EXISTS idx_clients_fingerprint ON clients(fingerprint);
CREATE INDEX IF NOT EXISTS idx_clients_status ON clients(status);

CREATE TABLE IF NOT EXISTS bind_requests (
    id           TEXT PRIMARY KEY,
    client_id    TEXT NOT NULL,
    join_code    TEXT NOT NULL,
    tenant_id    TEXT NOT NULL,
    status       TEXT NOT NULL DEFAULT 'pending',  -- pending, approved, rejected
    reason       TEXT NOT NULL DEFAULT '',
    created_at   INTEGER NOT NULL DEFAULT (unixepoch()),
    updated_at   INTEGER NOT NULL DEFAULT (unixepoch()),
    expires_at   INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_bind_requests_client ON bind_requests(client_id);
CREATE INDEX IF NOT EXISTS idx_bind_requests_status ON bind_requests(status);
CREATE INDEX IF NOT EXISTS idx_bind_requests_join_code ON bind_requests(join_code);

-- +goose Down
DROP TABLE IF EXISTS bind_requests;
DROP TABLE IF EXISTS clients;