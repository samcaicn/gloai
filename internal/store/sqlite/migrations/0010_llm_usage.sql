-- +goose Up
-- LLM token-usage accounting for per-tenant billing.
-- Every chat-completion / embedding call that flows through the platform's
-- OpenAI-compatible interface is recorded here, summed later by (tenant, model,
-- model_type) for billing.
CREATE TABLE IF NOT EXISTS llm_usage (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    tenant_id         TEXT    NOT NULL,
    channel_id        TEXT    NOT NULL DEFAULT '',
    model             TEXT    NOT NULL,
    model_type        TEXT    NOT NULL DEFAULT 'chat',
    prompt_tokens     INTEGER NOT NULL DEFAULT 0,
    completion_tokens INTEGER NOT NULL DEFAULT 0,
    total_tokens      INTEGER NOT NULL DEFAULT 0,
    cached_tokens     INTEGER NOT NULL DEFAULT 0,
    reasoning_tokens  INTEGER NOT NULL DEFAULT 0,
    created_at        INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_llm_usage_tenant      ON llm_usage (tenant_id);
CREATE INDEX IF NOT EXISTS idx_llm_usage_created     ON llm_usage (created_at);
CREATE INDEX IF NOT EXISTS idx_llm_usage_tenant_model ON llm_usage (tenant_id, model, model_type);

-- +goose Down
DROP TABLE IF EXISTS llm_usage;
