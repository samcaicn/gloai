// Package compose is the composition root for the CEOadmin Hub. It builds every
// long-lived component (store, API server, bot manager, AI sink, relay hub, the
// per-tenant memory sidecar and the cold/snapshot jobs) and wires them
// together. Keeping this in one place — instead of inline in main() — makes the
// dependency graph explicit, surfaces cycles at compile time, and lets main()
// stay a thin bootstrap.
package compose

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"path/filepath"
	"strings"

	"github.com/ceoadmin/CEOadmin/internal/ai"
	"github.com/ceoadmin/CEOadmin/internal/api"
	"github.com/ceoadmin/CEOadmin/internal/api/auth"
	appdelivery "github.com/ceoadmin/CEOadmin/internal/app"
	"github.com/ceoadmin/CEOadmin/internal/auth"
	"github.com/ceoadmin/CEOadmin/internal/bot"
	"github.com/ceoadmin/CEOadmin/internal/builtin"
	"github.com/ceoadmin/CEOadmin/internal/config"
	"github.com/ceoadmin/CEOadmin/internal/lifecycle"
	"github.com/ceoadmin/CEOadmin/internal/media"
	"github.com/ceoadmin/CEOadmin/internal/push"
	"github.com/ceoadmin/CEOadmin/internal/registry"
	"github.com/ceoadmin/CEOadmin/internal/relay"
	"github.com/ceoadmin/CEOadmin/internal/sink"
	"github.com/ceoadmin/CEOadmin/internal/storage"
	"github.com/ceoadmin/CEOadmin/internal/store"
	"github.com/ceoadmin/CEOadmin/internal/store/postgres"
	"github.com/ceoadmin/CEOadmin/internal/store/sqlite"
	"github.com/ceoadmin/CEOadmin/internal/supplymarket"
	"github.com/ceoadmin/CEOadmin/internal/tenantchat"
	"github.com/go-webauthn/webauthn/webauthn"
)

// Hub is the fully-wired application: the HTTP server plus the lifecycle
// supervisor that owns every long-running component.
type Hub struct {
	Server    *api.Server
	HTTPSrv   *http.Server
	Lifecycle *lifecycle.Supervisor
}

// Build constructs the whole Hub from configuration. It mirrors the previous
// main() wiring exactly; the only behavioural change is that sidecar/periodic
// components are owned by the lifecycle Supervisor instead of ad-hoc goroutines.
func Build(ctx context.Context, cfg *config.Config, version string) (*Hub, error) {
	// Ensure data directory exists for SQLite
	if !strings.HasPrefix(cfg.DBPath, "postgres") {
		if err := config.EnsureDataDir(); err != nil {
			return nil, fmt.Errorf("create data directory: %w", err)
		}
	}

	// Database
	s, err := openStore(cfg.DBPath)
	if err != nil {
		return nil, fmt.Errorf("database open: %w", err)
	}

	// Seed builtin apps
	if err := builtin.SeedApps(s); err != nil {
		slog.Error("seed builtin apps failed", "err", err)
	}

	// Load persisted 甲乙方 AI 对聊 conversations (created on demand by tenants).
	tenantchat.Default.Init(s)

	// Wire 供采市场 storage backend.
	supplymarket.Default.Init(s)

	// Auto-install builtin apps for all existing tenants (bots) so they are
	// available by default. New bots also get builtin apps on creation.
	if err := builtin.BackfillAllBots(s); err != nil {
		slog.Warn("backfill builtin apps failed", "err", err)
	}

	// Seed default admin (admin / A@666666) so the platform is usable
	// without first registering, and without binding iLink.
	if err := seedDefaultAdmin(s); err != nil {
		slog.Warn("seed default admin failed", "err", err)
	}

	// WebAuthn
	wa, err := webauthn.New(&webauthn.Config{
		RPDisplayName: cfg.RPName,
		RPID:          cfg.RPID,
		RPOrigins:     []string{cfg.RPOrigin},
	})
	if err != nil {
		s.Close()
		return nil, fmt.Errorf("webauthn init: %w", err)
	}

	// Registry client
	regClient := registry.NewClient(0)
	registries, err := s.ListRegistries()
	if err != nil {
		slog.Warn("failed to load registry sources", "err", err)
	} else {
		enabledCount := 0
		for _, reg := range registries {
			if reg.Enabled {
				regClient.AddSource(reg.Name, reg.URL)
				enabledCount++
			}
		}
		if enabledCount > 0 {
			slog.Info("registry sources loaded", "count", enabledCount)
		}
	}

	// Server components
	srv := &api.Server{
		Store:        s,
		WebAuthn:     wa,
		SessionStore: auth.NewSessionStore(),
		Config:       cfg,
		OAuthStates:  authapi.SetupOAuth(cfg),
		Registry:     regClient,
		Version:      version,
	}

	// Record every LLM call's token usage for per-tenant billing.
	ai.SetUsageRecorder(func(ctx context.Context, r ai.UsageRecord) {
		rec := &store.LLMUsageRecord{
			TenantID:         r.TenantID,
			ChannelID:        r.ChannelID,
			Model:            r.Model,
			ModelType:        r.ModelType,
			PromptTokens:     r.PromptTokens,
			CompletionTokens: r.CompletionTokens,
			TotalTokens:      r.TotalTokens,
			CachedTokens:     r.CachedTokens,
			ReasoningTokens:  r.ReasoningTokens,
		}
		if err := s.RecordLLMUsage(rec); err != nil {
			slog.Warn("record llm usage failed", "err", err)
		}
	})

	// Record media-generation usage for per-tenant billing.
	media.SetRecorder(func(ctx context.Context, r media.UsageRecord) {
		rec := &store.MediaUsageRecord{
			TenantID:        r.TenantID,
			ChannelID:       r.ChannelID,
			Model:           r.Model,
			MediaType:       r.MediaType,
			Count:           r.Count,
			DurationSeconds: r.DurationSeconds,
		}
		if err := s.RecordMediaUsage(rec); err != nil {
			slog.Warn("record media usage failed", "err", err)
		}
	})

	// Storage (optional): S3 > local FS > proxy fallback
	var objStore storage.Store
	if cfg.StorageEndpoint != "" {
		publicURL := cfg.StoragePublicURL
		if publicURL == "" {
			publicURL = cfg.RPOrigin + "/api/v1/media"
		}
		var err error
		objStore, err = storage.NewS3(storage.S3Config{
			Endpoint:  cfg.StorageEndpoint,
			AccessKey: cfg.StorageAccessKey,
			SecretKey: cfg.StorageSecretKey,
			Bucket:    cfg.StorageBucket,
			UseSSL:    cfg.StorageSSL,
			PublicURL: publicURL,
		})
		if err != nil {
			s.Close()
			return nil, fmt.Errorf("storage init (s3): %w", err)
		}
		slog.Info("storage connected (s3)", "endpoint", cfg.StorageEndpoint, "bucket", cfg.StorageBucket)
	} else if cfg.StoragePath != "" {
		var err error
		objStore, err = storage.NewFS(cfg.StoragePath, cfg.RPOrigin+"/api/v1/media")
		if err != nil {
			s.Close()
			return nil, fmt.Errorf("storage init (fs): %w", err)
		}
		slog.Info("storage connected (fs)", "dir", cfg.StoragePath)
	} else {
		slog.Info("storage not configured, media will use CDN proxy")
	}
	if objStore != nil {
		srv.ObjectStore = objStore
	}

	// Skill bundles need durable storage even when media storage is disabled
	// (the default): fall back to a dedicated local directory so the skill
	// marketplace works out of the box.
	srv.SkillStorage = objStore
	if srv.SkillStorage == nil {
		skillDir := skillBundleDir(cfg.DBPath)
		if fsStore, err := storage.NewFS(skillDir, cfg.RPOrigin+"/api/skills"); err != nil {
			slog.Warn("skill bundle storage unavailable", "err", err)
		} else {
			srv.SkillStorage = fsStore
			slog.Info("skill bundle storage (fs)", "dir", skillDir)
		}
	}

	hub := relay.NewHub(srv.SetupUpstreamHandler())
	appDisp := appdelivery.NewDispatcher(s)
	aiSink := &sink.AI{Store: s, AppDisp: appDisp, Storage: objStore}
	mgr := bot.NewManager(s, hub, aiSink, objStore, cfg.RPOrigin)
	aiSink.BotManager = mgr
	srv.BotManager = mgr
	srv.Hub = hub
	srv.AppWSHub = api.NewAppWSHub()
	srv.PushHub = push.NewHub()
	mgr.SetAppWSHub(srv.AppWSHub)
	mgr.SetPushHub(srv.PushHub)

	// HTTP server
	httpSrv := &http.Server{
		Addr:    cfg.ListenAddr,
		Handler: srv.Handler(),
	}

	// Lifecycle: every long-running component is owned by the Supervisor so
	// start/stop is deterministic. Start order: bots first, HTTP last.
	sup := lifecycle.New()
	sup.Add("bots", &lifecycle.BotsService{Mgr: mgr})
	sup.Add("modelrank", &lifecycle.ModelRankService{Store: s})
	sup.Add("memory", lifecycle.NewMemoryService(s))
	sup.Add("coldtier", lifecycle.NewColdTierService(cfg, objStore))
	sup.Add("snapshot", lifecycle.NewSnapshotService(cfg, objStore))
	sup.Add("session-cleanup", lifecycle.NewSessionCleanupService(s))
	sup.Add("http", &lifecycle.HTTPServerService{Srv: httpSrv})

	return &Hub{Server: srv, HTTPSrv: httpSrv, Lifecycle: sup}, nil
}

// openStore opens the store for the given DSN (SQLite or PostgreSQL).
func openStore(dsn string) (store.Store, error) {
	if strings.HasPrefix(dsn, "postgres://") || strings.HasPrefix(dsn, "postgresql://") {
		return postgres.Open(dsn)
	}
	return sqlite.Open(dsn)
}

// seedDefaultAdmin creates the built-in admin account (admin / A@666666) if it
// does not already exist. Admins see all menus and do not need to bind iLink.
func seedDefaultAdmin(s store.Store) error {
	const username = "admin"
	if _, err := s.GetUserByUsername(username); err == nil {
		return nil // already exists
	}
	hash := auth.HashPassword("A@666666")
	_, err := s.CreateUserFull(username, "", "管理员", hash, store.RoleAdmin)
	return err
}

// skillBundleDir picks a local directory for skill bundles when no object
// storage is configured: next to the SQLite database, or ./data otherwise.
func skillBundleDir(dbPath string) string {
	if dbPath != "" && !strings.HasPrefix(dbPath, "postgres://") && !strings.HasPrefix(dbPath, "postgresql://") {
		if dir := filepath.Dir(dbPath); dir != "" && dir != "." {
			return filepath.Join(dir, "skill-bundles")
		}
	}
	return filepath.Join("data", "skill-bundles")
}
