package postgres

import (
	"context"
	"database/sql"
	"fmt"
	"log/slog"
	"time"

	"github.com/ceoadmin/CEOadmin/internal/store"
	_ "github.com/jackc/pgx/v5/stdlib"
)

// DB implements store.Store for PostgreSQL.
type DB struct {
	*sql.DB
	clock store.Clock
}

// Verify interface compliance at compile time.
// var _ store.Store = (*DB)(nil)

// Open connects to PostgreSQL and runs pending migrations.
func Open(dsn string) (*DB, error) {
	db, err := sql.Open("pgx", dsn)
	if err != nil {
		return nil, fmt.Errorf("open database: %w", err)
	}
	db.SetMaxOpenConns(20)
	db.SetMaxIdleConns(5)

	if err := db.Ping(); err != nil {
		db.Close()
		return nil, fmt.Errorf("ping database: %w", err)
	}

	if err := runMigrations(db); err != nil {
		db.Close()
		return nil, err
	}

	slog.Info("PostgreSQL connected")
	return &DB{DB: db, clock: store.RealClock{}}, nil
}

// SetClock replaces the clock used by time-sensitive queries (e.g. reminders).
func (db *DB) SetClock(c store.Clock) { db.clock = c }

// now returns the current time from the configured clock.
func (db *DB) now() time.Time { return db.clock.Now() }

// AcknowledgeTask is a no-op for PostgreSQL (single-process mode).
func (db *DB) AcknowledgeTask(ctx context.Context, id string) bool {
	return false
}

// CancelTask is a no-op for PostgreSQL.
func (db *DB) CancelTask(ctx context.Context, id string) bool {
	return false
}

// CompleteTask is a no-op for PostgreSQL.
func (db *DB) CompleteTask(ctx context.Context, id string, result map[string]any) bool {
	return false
}

// FailTask is a no-op for PostgreSQL.
func (db *DB) FailTask(ctx context.Context, id string, errorMessage string) bool {
	return false
}

// CreateTask is a no-op for PostgreSQL.
func (db *DB) CreateTask(ctx context.Context, task *store.Task) error {
	return nil
}

// GetTask is a no-op for PostgreSQL.
func (db *DB) GetTask(ctx context.Context, id string) (*store.Task, error) {
	return nil, nil
}

// GetTenantTasks is a no-op for PostgreSQL.
func (db *DB) GetTenantTasks(ctx context.Context, tenantID string, status *store.TaskStatus, limit int) ([]*store.Task, error) {
	return nil, nil
}

// GetPendingTasksForClient is a no-op for PostgreSQL.
func (db *DB) GetPendingTasksForClient(ctx context.Context, clientID string, limit int, sinceTaskID string) ([]*store.Task, error) {
	return nil, nil
}

// MarkTaskDelivered is a no-op for PostgreSQL.
func (db *DB) MarkTaskDelivered(ctx context.Context, id string) bool {
	return false
}

// ConfirmSkillInstall is a no-op for PostgreSQL.
func (db *DB) ConfirmSkillInstall(ctx context.Context, clientID string, confirm *store.SkillInstallConfirm) (bool, error) {
	return false, nil
}

// ConfirmUpload is a no-op for PostgreSQL.
func (db *DB) ConfirmUpload(ctx context.Context, ticketID string, success bool, sha256 string, size int64) (bool, error) {
	return false, nil
}
