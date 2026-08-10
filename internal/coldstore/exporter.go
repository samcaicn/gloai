package coldstore

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"sort"
	"sync"
	"time"

	"github.com/ceoadmin/CEOadmin/internal/storage"
)

// Source yields chat messages that are not yet durable in the cold tier.
// tenantchat.Manager implements it.
type Source interface {
	// MessagesSince returns every message whose Seq is greater than
	// watermarks[convID] (a conversation absent from the map is exported from
	// the start). Implementations must not return already-covered rows.
	MessagesSince(watermarks map[string]int) []Row
}

// HotTrimmer is an optional capability of a Source: shedding the heavy columns
// of messages that are already durable in the cold tier. This is what turns a
// cold *copy* into real hot/cold tiering.
type HotTrimmer interface {
	// TrimHot drops embeddings (and thinking traces) from messages whose Seq is
	// at or below durable[convID] and whose CreatedAt is older than before.
	// Message text is kept so existing UI history keeps rendering from the hot
	// tier; the vectors are served from object storage. Returns the number of messages
	// trimmed.
	TrimHot(durable map[string]int, before int64) (int, error)
}

// Options configures the Exporter.
type Options struct {
	// Prefix is the dataset root key. Defaults to DefaultPrefix.
	Prefix string
	// Codec is the wire format. Defaults to DefaultCodec (Parquet).
	Codec Codec
	// Interval is the export period used by RunLoop. Defaults to 60s.
	Interval time.Duration
	// HotRetention is how long a message stays "hot" after it is durable in the
	// cold tier. Zero disables trimming (cold copy only, nothing is ever shed).
	HotRetention time.Duration
	// MaxRowsPerPart caps the rows written into a single part object so a busy
	// conversation does not produce one huge object. Defaults to 2000.
	MaxRowsPerPart int
	// Logger receives progress; defaults to slog.Default().
	Logger *slog.Logger
}

func (o *Options) normalize() {
	if o.Prefix == "" {
		o.Prefix = DefaultPrefix
	}
	if o.Codec == nil {
		o.Codec = DefaultCodec()
	}
	if o.Interval <= 0 {
		o.Interval = time.Minute
	}
	if o.MaxRowsPerPart <= 0 {
		o.MaxRowsPerPart = 2000
	}
	if o.Logger == nil {
		o.Logger = slog.Default()
	}
}

// state is the manifest persisted on object storage. Keeping it next to the data (rather
// than only in SQLite) means the dataset stays self-contained: restore the
// bucket and the watermarks come with it.
type state struct {
	Version    int            `json:"version"`
	Codec      string         `json:"codec"`      // format new parts are written in
	Formats    []string       `json:"formats"`    // every part extension present in the dataset
	Watermarks map[string]int `json:"watermarks"` // convID -> highest durable seq
	UpdatedAt  int64          `json:"updated_at"`
}

// Exporter incrementally lands new chat messages on object storage and (optionally) trims
// the hot tier once they are durable.
type Exporter struct {
	obj storage.Store
	src Source
	lay layout
	opt Options

	mu sync.Mutex
	wm map[string]int
	// formats is the set of part extensions known to exist in the dataset. It
	// grows when the configured codec changes and is published in the manifest
	// so consumers know they must read more than one format.
	formats map[string]bool
}

// New creates an Exporter. Call LoadState before the first Run so a restart
// resumes from the existing watermark instead of re-exporting everything.
func New(obj storage.Store, src Source, opt Options) *Exporter {
	opt.normalize()
	return &Exporter{
		obj:     obj,
		src:     src,
		lay:     newLayout(opt.Prefix),
		opt:     opt,
		wm:      map[string]int{},
		formats: map[string]bool{},
	}
}

// Stats summarises one export tick.
type Stats struct {
	Rows       int
	Parts      int
	Bytes      int
	Convs      int
	TrimmedHot int
}

// LoadState restores the per-conversation watermarks.
//
// Primary source is the manifest object. If it is missing or unreadable (first
// run, or a bucket restored without it) the watermarks are rebuilt from a LIST
// of part keys — the sequence range lives in the object name, so this costs one
// listing and zero downloads.
func (e *Exporter) LoadState(ctx context.Context) error {
	if raw, err := e.obj.Get(ctx, e.lay.stateKey()); err == nil && len(raw) > 0 {
		var st state
		if json.Unmarshal(raw, &st) == nil && st.Watermarks != nil {
			e.mu.Lock()
			e.wm = st.Watermarks
			for _, ext := range st.Formats {
				e.formats[ext] = true
			}
			// Manifests written before formats were tracked still name the
			// codec that produced them.
			if len(st.Formats) == 0 && st.Codec != "" {
				if c, err := CodecByName(st.Codec); err == nil {
					e.formats[c.Ext()] = true
				}
			}
			e.mu.Unlock()
			return nil
		}
	}
	return e.RebuildState(ctx)
}

// RebuildState derives the watermarks from the objects actually present.
// It is the recovery path and also a consistency check after manual surgery on
// the bucket.
func (e *Exporter) RebuildState(ctx context.Context) error {
	lister, ok := e.obj.(storage.Lister)
	if !ok {
		// Without LIST we cannot know what is already there. Starting from an
		// empty watermark is still safe: part keys are deterministic in their
		// sequence range and Reader de-duplicates on (conv_id, seq), so at
		// worst some ranges are re-uploaded.
		e.opt.Logger.Warn("coldstore: object store cannot list, watermarks start empty")
		return nil
	}
	objs, err := lister.List(ctx, e.lay.root())
	if err != nil {
		return fmt.Errorf("coldstore: rebuild state: %w", err)
	}
	wm := map[string]int{}
	formats := map[string]bool{}
	for _, o := range objs {
		ref, ok := e.lay.parsePartKey(o.Key)
		if !ok {
			continue
		}
		if ref.Hi > wm[ref.Conv] {
			wm[ref.Conv] = ref.Hi
		}
		formats[ref.Ext] = true
	}
	e.mu.Lock()
	e.wm = wm
	e.formats = formats
	e.mu.Unlock()
	return nil
}

// Watermarks returns a copy of the current per-conversation high-water marks.
func (e *Exporter) Watermarks() map[string]int {
	e.mu.Lock()
	defer e.mu.Unlock()
	return copyWM(e.wm)
}

func copyWM(in map[string]int) map[string]int {
	out := make(map[string]int, len(in))
	for k, v := range in {
		out[k] = v
	}
	return out
}

// Run performs one incremental export: only messages beyond the watermark are
// written, each as part of a new immutable part object. Existing objects are
// never rewritten, so the cost of a tick is proportional to new messages, not
// to the size of the dataset.
func (e *Exporter) Run(ctx context.Context) (Stats, error) {
	var st Stats

	e.mu.Lock()
	before := copyWM(e.wm)
	e.mu.Unlock()

	rows := e.src.MessagesSince(before)
	if len(rows) == 0 {
		return st, nil
	}

	// Group into (conv, day) partitions.
	type pkey struct{ conv, day string }
	buckets := map[pkey][]Row{}
	for _, r := range rows {
		if r.Seq <= before[r.ConvID] {
			continue // defensive: source handed back an already-durable row
		}
		k := pkey{r.ConvID, r.Day()}
		buckets[k] = append(buckets[k], r)
	}
	if len(buckets) == 0 {
		return st, nil
	}

	keys := make([]pkey, 0, len(buckets))
	for k := range buckets {
		keys = append(keys, k)
	}
	sort.Slice(keys, func(i, j int) bool {
		if keys[i].conv != keys[j].conv {
			return keys[i].conv < keys[j].conv
		}
		return keys[i].day < keys[j].day
	})

	advanced := map[string]int{}
	convSeen := map[string]bool{}
	for _, k := range keys {
		rs := buckets[k]
		sort.Slice(rs, func(i, j int) bool { return rs[i].Seq < rs[j].Seq })
		convSeen[k.conv] = true

		for start := 0; start < len(rs); start += e.opt.MaxRowsPerPart {
			end := min(start+e.opt.MaxRowsPerPart, len(rs))
			chunk := rs[start:end]

			data, err := e.opt.Codec.Encode(chunk)
			if err != nil {
				return st, err
			}
			key := e.lay.partKey(k.conv, k.day, chunk[0].Seq, chunk[len(chunk)-1].Seq, e.opt.Codec.Ext())
			if _, err := e.obj.Put(ctx, key, e.opt.Codec.ContentType(), data); err != nil {
				return st, fmt.Errorf("coldstore: put %s: %w", key, err)
			}

			st.Rows += len(chunk)
			st.Parts++
			st.Bytes += len(data)
			if hi := chunk[len(chunk)-1].Seq; hi > advanced[k.conv] {
				advanced[k.conv] = hi
			}
		}
	}
	st.Convs = len(convSeen)

	// Advance watermarks only for what actually landed.
	e.mu.Lock()
	for conv, hi := range advanced {
		if hi > e.wm[conv] {
			e.wm[conv] = hi
		}
	}
	e.formats[e.opt.Codec.Ext()] = true
	snapshot := copyWM(e.wm)
	e.mu.Unlock()

	if err := e.saveState(ctx, snapshot); err != nil {
		// The data is durable; a lost manifest is recoverable via RebuildState.
		e.opt.Logger.Warn("coldstore: manifest save failed", "err", err)
	}
	return st, nil
}

// Formats returns the sorted set of part extensions present in the dataset.
func (e *Exporter) Formats() []string {
	e.mu.Lock()
	defer e.mu.Unlock()
	out := make([]string, 0, len(e.formats))
	for ext := range e.formats {
		out = append(out, ext)
	}
	sort.Strings(out)
	return out
}

func (e *Exporter) saveState(ctx context.Context, wm map[string]int) error {
	formats := e.Formats()
	st := state{
		Version:    SchemaVersion,
		Codec:      e.opt.Codec.Name(),
		Formats:    formats,
		Watermarks: wm,
		UpdatedAt:  time.Now().Unix(),
	}
	b, err := json.MarshalIndent(st, "", "  ")
	if err != nil {
		return err
	}
	if _, err := e.obj.Put(ctx, e.lay.stateKey(), "application/json", b); err != nil {
		return err
	}
	doc, err := json.MarshalIndent(buildSchemaDoc(e.lay.prefix, e.opt.Codec, formats), "", "  ")
	if err != nil {
		return err
	}
	_, err = e.obj.Put(ctx, e.lay.schemaKey(), "application/json", doc)
	return err
}

// Tier runs one full hot/cold cycle: export new messages, then shed the heavy
// columns of anything that is both durable in object storage and older than the retention
// window.
func (e *Exporter) Tier(ctx context.Context) (Stats, error) {
	st, err := e.Run(ctx)
	if err != nil {
		return st, err
	}
	if e.opt.HotRetention <= 0 {
		return st, nil
	}
	trimmer, ok := e.src.(HotTrimmer)
	if !ok {
		return st, nil
	}
	before := time.Now().Add(-e.opt.HotRetention).Unix()
	n, err := trimmer.TrimHot(e.Watermarks(), before)
	st.TrimmedHot = n
	if err != nil {
		return st, fmt.Errorf("coldstore: trim hot tier: %w", err)
	}
	return st, nil
}

// RunLoop exports on a ticker until ctx is cancelled, doing a final flush on
// the way out so a clean shutdown does not strand recent messages.
func (e *Exporter) RunLoop(ctx context.Context) {
	ticker := time.NewTicker(e.opt.Interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			flushCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 15*time.Second)
			if st, err := e.Run(flushCtx); err != nil {
				e.opt.Logger.Warn("coldstore: final flush failed", "err", err)
			} else if st.Rows > 0 {
				e.opt.Logger.Info("coldstore: final flush", "rows", st.Rows, "parts", st.Parts)
			}
			cancel()
			return
		case <-ticker.C:
			st, err := e.Tier(ctx)
			if err != nil {
				e.opt.Logger.Warn("coldstore: tick failed", "err", err)
				continue
			}
			if st.Rows > 0 || st.TrimmedHot > 0 {
				e.opt.Logger.Info("coldstore: incremental export",
					"rows", st.Rows, "parts", st.Parts, "bytes", st.Bytes,
					"convs", st.Convs, "trimmed_hot", st.TrimmedHot)
			}
		}
	}
}
