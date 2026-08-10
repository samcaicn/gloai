package coldstore

import (
	"context"
	"fmt"
	"log/slog"
	"math"
	"sort"
	"sync"
	"time"

	"github.com/ceoadmin/CEOadmin/internal/storage"
)

// ErrNotQueryable is returned when the configured object store cannot enumerate
// keys, which the cold tier needs in order to find partitions.
var ErrNotQueryable = fmt.Errorf("coldstore: object store does not support listing")

// UnknownFormatError reports a part this build has no codec for — most often a
// .parquet part met by a binary compiled with -tags noparquet.
//
// It deliberately fails the whole query. Skipping the part would return a
// subset of the dataset as though it were complete, and for a vector search
// that means plausible-looking but silently wrong results — far worse than an
// error the operator can act on.
type UnknownFormatError struct {
	Key string
	Ext string
}

func (e *UnknownFormatError) Error() string {
	// excludedFormats is keyed by format name, which equals the file extension
	// for every codec that ships with the project.
	if hint, ok := excludedFormats[e.Ext]; ok {
		return fmt.Sprintf("coldstore: cannot read %s: format %q is not in this build: %s", e.Key, e.Ext, hint)
	}
	return fmt.Sprintf("coldstore: cannot read %s: no codec registered for format %q", e.Key, e.Ext)
}

// Query selects a slice of the cold dataset. Everything expressible here is
// pushed down to partition pruning (a LIST plus a subset of parts), so a narrow
// query never downloads the whole dataset.
type Query struct {
	// ConvIDs restricts the scan to these conversations (first partition key).
	// Empty means every conversation.
	ConvIDs []string
	// From/To restrict the scan by day (second partition key), inclusive at
	// day granularity — passing the same date for both selects that whole day.
	// Zero means open.
	From, To time.Time
	// TenantID keeps only rows whose conversation involves this tenant. This is
	// a row-level filter, applied after partition pruning.
	TenantID string
	// Limit caps returned rows (0 = unlimited). Ignored by SearchVector, which
	// uses k instead.
	Limit int
}

// Hit is a cold-tier vector search result.
type Hit struct {
	Row        Row
	Similarity float32
}

// ReaderOptions configures a Reader.
type ReaderOptions struct {
	// Prefix is the dataset root key. Defaults to DefaultPrefix.
	Prefix string
	// CacheBytes bounds the in-process part cache. Part objects are immutable,
	// so caching them is always safe; this is what keeps repeated cold searches
	// from re-downloading from object storage every time. Defaults to 64 MiB, 0 disables.
	CacheBytes int64
	Logger     *slog.Logger
}

// Reader serves queries directly against the partitioned objects on object storage. It is
// the in-Hub counterpart of pointing DuckDB at the same bucket.
type Reader struct {
	obj storage.Store
	lay layout
	opt ReaderOptions

	mu    sync.Mutex
	cache map[string]cachedPart
	order []string // LRU, oldest first
	bytes int64
}

// cachedPart carries the size that was charged to the budget, so eviction
// credits back exactly what insertion debited.
type cachedPart struct {
	rows []Row
	size int64
}

// NewReader creates a cold-tier reader.
func NewReader(obj storage.Store, opt ReaderOptions) *Reader {
	if opt.Prefix == "" {
		opt.Prefix = DefaultPrefix
	}
	if opt.CacheBytes == 0 {
		opt.CacheBytes = 64 << 20
	}
	if opt.Logger == nil {
		opt.Logger = slog.Default()
	}
	return &Reader{
		obj:   obj,
		lay:   newLayout(opt.Prefix),
		opt:   opt,
		cache: map[string]cachedPart{},
	}
}

// Queryable reports whether the backing store supports the listing the cold
// tier needs.
func (r *Reader) Queryable() bool {
	_, ok := r.obj.(storage.Lister)
	return ok
}

// parts resolves a Query to the set of part objects that can possibly satisfy
// it. This is the partition-pruning step: only conv= prefixes that were asked
// for get listed, and dt= partitions outside the time range are dropped without
// ever being fetched.
func (r *Reader) parts(ctx context.Context, q Query) ([]partRef, error) {
	lister, ok := r.obj.(storage.Lister)
	if !ok {
		return nil, ErrNotQueryable
	}

	prefixes := []string{r.lay.root()}
	if len(q.ConvIDs) > 0 {
		prefixes = prefixes[:0]
		seen := map[string]bool{}
		for _, c := range q.ConvIDs {
			p := r.lay.convPrefix(c)
			if !seen[p] {
				seen[p] = true
				prefixes = append(prefixes, p)
			}
		}
	}

	var from, to string
	if !q.From.IsZero() {
		from = q.From.UTC().Format("2006-01-02")
	}
	if !q.To.IsZero() {
		to = q.To.UTC().Format("2006-01-02")
	}

	var out []partRef
	for _, p := range prefixes {
		objs, err := lister.List(ctx, p)
		if err != nil {
			return nil, fmt.Errorf("coldstore: list %s: %w", p, err)
		}
		for _, o := range objs {
			ref, ok := r.lay.parsePartKey(o.Key)
			if !ok {
				continue // _manifest/_schema/foreign objects
			}
			if from != "" && ref.Day < from {
				continue
			}
			if to != "" && ref.Day > to {
				continue
			}
			ref.Size = o.Size
			out = append(out, ref)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Key < out[j].Key })
	return out, nil
}

// readPart fetches and decodes one part, memoised. Parts are immutable, so a
// cache hit can never be stale.
func (r *Reader) readPart(ctx context.Context, ref partRef) ([]Row, error) {
	if r.opt.CacheBytes > 0 {
		r.mu.Lock()
		if entry, ok := r.cache[ref.Key]; ok {
			r.touch(ref.Key)
			r.mu.Unlock()
			return entry.rows, nil
		}
		r.mu.Unlock()
	}

	codec, ok := CodecByExt(ref.Ext)
	if !ok {
		return nil, &UnknownFormatError{Key: ref.Key, Ext: ref.Ext}
	}
	data, err := r.obj.Get(ctx, ref.Key)
	if err != nil {
		return nil, fmt.Errorf("coldstore: get %s: %w", ref.Key, err)
	}
	rows, err := codec.Decode(data)
	if err != nil {
		return nil, err
	}

	if r.opt.CacheBytes > 0 {
		size := int64(len(data))
		r.mu.Lock()
		if _, dup := r.cache[ref.Key]; !dup {
			r.cache[ref.Key] = cachedPart{rows: rows, size: size}
			r.order = append(r.order, ref.Key)
			r.bytes += size
			for r.bytes > r.opt.CacheBytes && len(r.order) > 1 {
				oldest := r.order[0]
				r.order = r.order[1:]
				if evicted, ok := r.cache[oldest]; ok {
					r.bytes -= evicted.size
					delete(r.cache, oldest)
				}
			}
		}
		r.mu.Unlock()
	}
	return rows, nil
}

func (r *Reader) touch(key string) {
	for i, k := range r.order {
		if k == key {
			r.order = append(r.order[:i], r.order[i+1:]...)
			r.order = append(r.order, key)
			return
		}
	}
}

// Scan returns the cold rows matching q, de-duplicated on (conv_id, seq) and
// ordered by conversation then sequence.
//
// De-duplication matters: if the manifest is ever lost and rebuilt on a store
// without listing, overlapping part ranges may exist. (conv_id, seq) is the
// dataset's primary key, so collapsing on it makes the read path immune.
func (r *Reader) Scan(ctx context.Context, q Query) ([]Row, error) {
	refs, err := r.parts(ctx, q)
	if err != nil {
		return nil, err
	}

	// Row-level date filtering uses the same day granularity as the partition
	// pruning above, so a part that survives pruning is never silently emptied
	// by a stricter row filter.
	var fromDay, toDay string
	if !q.From.IsZero() {
		fromDay = q.From.UTC().Format("2006-01-02")
	}
	if !q.To.IsZero() {
		toDay = q.To.UTC().Format("2006-01-02")
	}

	type ck struct {
		conv string
		seq  int
	}
	seen := map[ck]struct{}{}
	var out []Row
	for _, ref := range refs {
		rows, err := r.readPart(ctx, ref)
		if err != nil {
			return nil, err
		}
		for _, row := range rows {
			if d := row.Day(); (fromDay != "" && d < fromDay) || (toDay != "" && d > toDay) {
				continue
			}
			if q.TenantID != "" && !hasTenant(row, q.TenantID) {
				continue
			}
			k := ck{row.ConvID, row.Seq}
			if _, dup := seen[k]; dup {
				continue
			}
			seen[k] = struct{}{}
			out = append(out, row)
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].ConvID != out[j].ConvID {
			return out[i].ConvID < out[j].ConvID
		}
		return out[i].Seq < out[j].Seq
	})
	if q.Limit > 0 && len(out) > q.Limit {
		out = out[:q.Limit]
	}
	return out, nil
}

// SearchVector runs a cosine nearest-neighbour search over the pruned cold
// partitions and returns the best k hits.
func (r *Reader) SearchVector(ctx context.Context, vec []float32, q Query, k int) ([]Hit, error) {
	if len(vec) == 0 {
		return nil, fmt.Errorf("coldstore: empty query vector")
	}
	if k <= 0 {
		k = 5
	}
	q.Limit = 0 // never truncate before scoring
	rows, err := r.Scan(ctx, q)
	if err != nil {
		return nil, err
	}
	hits := make([]Hit, 0, len(rows))
	for _, row := range rows {
		sim, ok := Cosine(vec, row.Embedding)
		if !ok {
			continue
		}
		hits = append(hits, Hit{Row: row, Similarity: sim})
	}
	sort.Slice(hits, func(i, j int) bool { return hits[i].Similarity > hits[j].Similarity })
	if len(hits) > k {
		hits = hits[:k]
	}
	return hits, nil
}

// ColdStats describes the dataset without downloading any payload — it is
// derived from a single LIST plus the part-name sequence ranges.
type ColdStats struct {
	Convs      int            `json:"convs"`
	Partitions int            `json:"partitions"`
	Parts      int            `json:"parts"`
	Bytes      int64          `json:"bytes"`
	MaxSeq     map[string]int `json:"max_seq"`
	Format     string         `json:"format"`
}

// Stats summarises what is currently in the cold tier.
func (r *Reader) Stats(ctx context.Context) (ColdStats, error) {
	refs, err := r.parts(ctx, Query{})
	if err != nil {
		return ColdStats{}, err
	}
	st := ColdStats{MaxSeq: map[string]int{}}
	convs := map[string]bool{}
	partitions := map[string]bool{}
	for _, ref := range refs {
		convs[ref.Conv] = true
		partitions[ref.Conv+"/"+ref.Day] = true
		st.Parts++
		st.Bytes += ref.Size
		if ref.Hi > st.MaxSeq[ref.Conv] {
			st.MaxSeq[ref.Conv] = ref.Hi
		}
		st.Format = ref.Ext
	}
	st.Convs = len(convs)
	st.Partitions = len(partitions)
	return st, nil
}

func hasTenant(r Row, tenantID string) bool {
	for _, t := range r.TenantIDs {
		if t == tenantID {
			return true
		}
	}
	return false
}

// Cosine returns the cosine similarity of two equal-length vectors.
func Cosine(a, b []float32) (float32, bool) {
	if len(a) == 0 || len(b) == 0 || len(a) != len(b) {
		return 0, false
	}
	var dot, na, nb float64
	for i := range a {
		dot += float64(a[i]) * float64(b[i])
		na += float64(a[i]) * float64(a[i])
		nb += float64(b[i]) * float64(b[i])
	}
	if na == 0 || nb == 0 {
		return 0, false
	}
	return float32(dot / math.Sqrt(na*nb)), true
}
