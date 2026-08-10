// Package lifecycle gives the Hub a single, uniform model for long-running
// components (the HTTP server, bot manager, the per-tenant memory sidecar, the
// cold tier exporter, the DB snapshot job and the session-cleanup ticker).
//
// Previously these were fire-and-forget goroutines launched directly inside
// main(); there was no shared start/stop contract and no health visibility.
// A Supervisor starts every Service in registration order and stops them in
// reverse order when the context is cancelled, so shutdown is deterministic.
package lifecycle

import (
	"context"
	"log/slog"
	"net/http"
	"time"

	"github.com/ceoadmin/CEOadmin/internal/bot"
	"github.com/ceoadmin/CEOadmin/internal/modelrank"
	"github.com/ceoadmin/CEOadmin/internal/store"
)

// Service is a long-running component managed by a Supervisor.
type Service interface {
	// Start begins the component. It must return quickly; blocking work
	// (e.g. Accept loops) should run in a goroutine.
	Start(ctx context.Context) error
	// Stop tears the component down. ctx carries a short grace timeout.
	Stop(ctx context.Context) error
}

type namedService struct {
	name string
	svc  Service
}

// Supervisor owns a group of Services and starts/stops them together.
type Supervisor struct {
	services []namedService
}

// New returns an empty Supervisor.
func New() *Supervisor { return &Supervisor{} }

// Add registers a named Service. Registration order is the start order; stop
// runs in reverse. Returns the Supervisor for chaining.
func (s *Supervisor) Add(name string, svc Service) *Supervisor {
	s.services = append(s.services, namedService{name: name, svc: svc})
	return s
}

// Run starts every Service, then blocks until ctx is cancelled. On cancel it
// stops all Services in reverse start order, each with a 5s grace timeout.
func (s *Supervisor) Run(ctx context.Context) error {
	for _, ns := range s.services {
		if err := ns.svc.Start(ctx); err != nil {
			slog.Error("lifecycle service failed to start", "name", ns.name, "err", err)
			return err
		}
		slog.Info("lifecycle service started", "name", ns.name)
	}
	<-ctx.Done()
	slog.Info("shutting down lifecycle services")
	for i := len(s.services) - 1; i >= 0; i-- {
		ns := s.services[i]
		stopCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		if err := ns.svc.Stop(stopCtx); err != nil {
			slog.Warn("lifecycle service stop error", "name", ns.name, "err", err)
		}
		cancel()
	}
	return nil
}

// HTTPServerService runs an *http.Server and shuts it down gracefully.
type HTTPServerService struct{ Srv *http.Server }

// Start launches ListenAndServe in a goroutine (non-blocking).
func (h *HTTPServerService) Start(ctx context.Context) error {
	go func() {
		if err := h.Srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			slog.Error("server error", "err", err)
		}
	}()
	return nil
}

// Stop gracefully shuts the server down.
func (h *HTTPServerService) Stop(ctx context.Context) error { return h.Srv.Shutdown(ctx) }

// BotsService starts and stops every bot instance.
type BotsService struct{ Mgr *bot.Manager }

func (b *BotsService) Start(ctx context.Context) error { b.Mgr.StartAll(ctx); return nil }
func (b *BotsService) Stop(ctx context.Context) error  { b.Mgr.StopAll(); return nil }

// ModelRankService probes models and periodically re-ranks them.
type ModelRankService struct{ Store store.Store }

func (m *ModelRankService) Start(ctx context.Context) error { modelrank.Init(ctx, m.Store); return nil }
func (m *ModelRankService) Stop(ctx context.Context) error  { return nil }
