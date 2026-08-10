-- +goose Up
DROP TABLE IF EXISTS cron_jobs;

-- +goose Down
-- cron_jobs table removed; will be reimplemented as a third-party app.
