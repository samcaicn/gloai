-- +goose Up
CREATE TABLE IF NOT EXISTS media_usage (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         TEXT    NOT NULL,
    channel_id        TEXT    NOT NULL DEFAULT '',
    model             TEXT    NOT NULL,
    media_type        TEXT    NOT NULL DEFAULT 'image',
    count             INTEGER NOT NULL DEFAULT 0,
    duration_seconds  INTEGER NOT NULL DEFAULT 0,
    created_at        BIGINT  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_media_usage_tenant       ON media_usage (tenant_id);
CREATE INDEX IF NOT EXISTS idx_media_usage_created      ON media_usage (created_at);
CREATE INDEX IF NOT EXISTS idx_media_usage_tenant_model ON media_usage (tenant_id, model, media_type);

-- +goose Down
DROP TABLE IF EXISTS media_usage;
