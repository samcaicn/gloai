package lifecycle

import (
	"context"
	"log/slog"

	"github.com/ceoadmin/CEOadmin/internal/coldstore"
	"github.com/ceoadmin/CEOadmin/internal/config"
	"github.com/ceoadmin/CEOadmin/internal/storage"
	"github.com/ceoadmin/CEOadmin/internal/tenantchat"
)

// ColdTierService wires the cold tier: an incremental exporter that lands new
// chat messages + vectors as partitioned open-format objects, and a reader that
// lets vector search fall through to the bucket for messages whose embeddings
// have been trimmed out of the hot tier.
//
// Everything here is best-effort: if object storage is absent or cannot list
// keys, the Hub keeps running hot-only. Mirrors the previous startColdTier().
type ColdTierService struct {
	cfg      *config.Config
	objStore storage.Store
}

// NewColdTierService builds a ColdTierService.
func NewColdTierService(cfg *config.Config, objStore storage.Store) *ColdTierService {
	return &ColdTierService{cfg: cfg, objStore: objStore}
}

// Start begins the incremental export loop (in a goroutine). It is a no-op when
// object storage is absent or cold export is disabled.
func (c *ColdTierService) Start(ctx context.Context) error {
	if c.objStore == nil || !c.cfg.ColdExportEnabled {
		return nil
	}
	codec, err := coldstore.CodecByName(c.cfg.ColdExportFormat)
	if err != nil {
		slog.Error("cold tier disabled: bad format", "err", err)
		return nil
	}

	exporter := coldstore.New(c.objStore, tenantchat.Default, coldstore.Options{
		Prefix:       c.cfg.ColdExportPrefix,
		Codec:        codec,
		Interval:     c.cfg.ColdExportInterval,
		HotRetention: c.cfg.ColdHotRetention,
	})
	if err := exporter.LoadState(ctx); err != nil {
		// Watermarks unknown: the export would redo work, but part keys are
		// deterministic and the reader de-duplicates, so it stays correct.
		slog.Warn("cold tier: watermark load failed, starting from scratch", "err", err)
	}

	reader := coldstore.NewReader(c.objStore, coldstore.ReaderOptions{
		Prefix:     c.cfg.ColdExportPrefix,
		CacheBytes: c.cfg.ColdCacheMB << 20,
	})
	if reader.Queryable() {
		tenantchat.Default.SetColdSearcher(reader)
	} else {
		slog.Warn("cold tier: storage cannot list keys, cold search disabled (export still runs)")
	}

	// A dataset that already holds parts this build cannot decode makes cold
	// search incomplete. Say so at startup rather than at query time — the
	// usual cause is a slim `-tags noparquet` binary pointed at a dataset
	// written by a full one.
	if existing := exporter.Formats(); len(existing) > 0 {
		var unreadable []string
		for _, ext := range existing {
			if _, ok := coldstore.CodecByExt(ext); !ok {
				unreadable = append(unreadable, ext)
			}
		}
		if len(unreadable) > 0 {
			slog.Error("cold tier: dataset contains parts this build cannot read; "+
				"cold search will fail until the binary supports them",
				"formats", unreadable, "dataset_formats", existing)
		}
	}

	go exporter.RunLoop(ctx)
	slog.Info("cold tier enabled (incremental export to object storage)",
		"prefix", c.cfg.ColdExportPrefix, "format", codec.Name(),
		"dataset_formats", exporter.Formats(),
		"interval", c.cfg.ColdExportInterval, "hot_retention", c.cfg.ColdHotRetention)
	return nil
}

// Stop is a no-op: the export loop ends when its context is cancelled.
func (c *ColdTierService) Stop(ctx context.Context) error { return nil }
