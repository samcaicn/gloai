-- +goose Up
-- Media-generation usage accounting (image / video / audio) for per-tenant
-- billing. Each row is one generation request; we sum count (items produced) and
-- duration_seconds (0 for images) by (tenant, model, media type).
CREATE TABLE IF NOT EXISTS media_usage (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    tenant_id         TEXT    NOT NULL,
    channel_id        TEXT    NOT NULL DEFAULT '',
    model             TEXT    NOT NULL,
    media_type        TEXT    NOT NULL DEFAULT 'image',
    count             INTEGER NOT NULL DEFAULT 0,
    duration_seconds  INTEGER NOT NULL DEFAULT 0,
    created_at        INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_media_usage_tenant       ON media_usage (tenant_id);
CREATE INDEX IF NOT EXISTS idx_media_usage_created      ON media_usage (created_at);
CREATE INDEX IF NOT EXISTS idx_media_usage_tenant_model ON media_usage (tenant_id, model, media_type);

-- +goose Down
DROP TABLE IF EXISTS media_usage;
