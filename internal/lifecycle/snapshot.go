package lifecycle

import (
	"context"
	"log/slog"
	"strings"
	"time"

	"github.com/ceoadmin/CEOadmin/internal/auth"
	"github.com/ceoadmin/CEOadmin/internal/backup"
	"github.com/ceoadmin/CEOadmin/internal/config"
	"github.com/ceoadmin/CEOadmin/internal/storage"
	"github.com/ceoadmin/CEOadmin/internal/store"
)

// SnapshotService keeps whole-database snapshots as a disaster-recovery net for
// the non-chat state (accounts, apps, bots, config). Chat data durability is
// the cold tier's job, so this runs slowly and prunes old generations. Skipped
// for PostgreSQL backends, whose durability the server handles itself. Mirrors
// the previous startSnapshots().
type SnapshotService struct {
	cfg      *config.Config
	objStore storage.Store
}

// NewSnapshotService builds a SnapshotService.
func NewSnapshotService(cfg *config.Config, objStore storage.Store) *SnapshotService {
	return &SnapshotService{cfg: cfg, objStore: objStore}
}

// Start launches the periodic snapshot+prune loop (in a goroutine). No-op when
// object storage is absent, the backend is PostgreSQL, or the interval is <= 0.
func (s *SnapshotService) Start(ctx context.Context) error {
	if s.objStore == nil || strings.HasPrefix(s.cfg.DBPath, "postgres") || s.cfg.SnapshotInterval <= 0 {
		return nil
	}
	go func() {
		ticker := time.NewTicker(s.cfg.SnapshotInterval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				if err := backup.Backup(ctx, s.objStore, s.cfg.DBPath); err != nil {
					slog.Warn("db snapshot failed", "err", err)
					continue
				}
				deleted, err := backup.Prune(ctx, s.objStore, s.cfg.SnapshotKeep)
				if err != nil {
					slog.Warn("db snapshot prune failed", "err", err)
				}
				slog.Info("db snapshot done", "db", s.cfg.DBPath, "pruned_objects", deleted)
			}
		}
	}()
	return nil
}

// Stop is a no-op: the loop ends when its context is cancelled.
func (s *SnapshotService) Stop(ctx context.Context) error { return nil }

// SessionCleanupService periodically removes expired auth sessions. Mirrors the
// previous inline periodic cleanup in main().
type SessionCleanupService struct{ store store.Store }

// NewSessionCleanupService builds a SessionCleanupService.
func NewSessionCleanupService(st store.Store) *SessionCleanupService {
	return &SessionCleanupService{store: st}
}

// Start launches the hourly cleanup loop (in a goroutine).
func (s *SessionCleanupService) Start(ctx context.Context) error {
	go func() {
		ticker := time.NewTicker(1 * time.Hour)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				auth.CleanExpiredSessions(s.store)
			}
		}
	}()
	return nil
}

// Stop is a no-op: the loop ends when its context is cancelled.
func (s *SessionCleanupService) Stop(ctx context.Context) error { return nil }
