-- +goose Up
-- Record the wall-clock duration (milliseconds) of each system-LLM call so the
-- platform can report latency / 时长 alongside token usage and call count.
ALTER TABLE llm_usage ADD COLUMN duration_ms INTEGER NOT NULL DEFAULT 0;

-- +goose Down
ALTER TABLE llm_usage DROP COLUMN duration_ms;
