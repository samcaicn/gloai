-- App pricing + purchase/entitlement records (for paid apps).
ALTER TABLE apps ADD COLUMN IF NOT EXISTS price DOUBLE PRECISION NOT NULL DEFAULT 0;
ALTER TABLE apps ADD COLUMN IF NOT EXISTS currency TEXT NOT NULL DEFAULT 'CNY';

CREATE TABLE IF NOT EXISTS app_purchases (
    id          TEXT PRIMARY KEY,
    app_id      TEXT NOT NULL,
    user_id     TEXT NOT NULL,
    created_at  BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()),
    UNIQUE(app_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_app_purchases_user ON app_purchases(user_id);
CREATE INDEX IF NOT EXISTS idx_app_purchases_app ON app_purchases(app_id);
