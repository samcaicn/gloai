-- +goose Up
-- Add join_code column to users table for tenant device bind flow.

ALTER TABLE users ADD COLUMN join_code TEXT NOT NULL DEFAULT '';
CREATE INDEX IF NOT EXISTS idx_users_join_code ON users(join_code) WHERE join_code != '';

-- +goose Down
-- SQLite doesn't support DROP COLUMN easily, so we leave it.