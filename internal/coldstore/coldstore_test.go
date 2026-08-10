package coldstore

import (
	"context"
	"errors"
	"fmt"
	"sort"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/ceoadmin/CEOadmin/internal/storage"
)

// ---- test doubles ----

// memStore is an in-memory storage.Store that also implements Lister and
// Deleter, and counts operations so tests can assert that the export is really
// incremental and that reads really prune partitions.
type memStore struct {
	mu      sync.Mutex
	data    map[string][]byte
	puts    []string
	gets    []string
	deletes []string
}

func newMemStore() *memStore { return &memStore{data: map[string][]byte{}} }

func (m *memStore) Put(_ context.Context, key, _ string, data []byte) (string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.data[key] = append([]byte(nil), data...)
	m.puts = append(m.puts, key)
	return "/" + key, nil
}

func (m *memStore) Get(_ context.Context, key string) ([]byte, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.gets = append(m.gets, key)
	b, ok := m.data[key]
	if !ok {
		return nil, fmt.Errorf("not found: %s", key)
	}
	return b, nil
}

func (m *memStore) URL(key string) string { return "/" + key }

func (m *memStore) List(_ context.Context, prefix string) ([]storage.ObjectInfo, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	var out []storage.ObjectInfo
	for k, v := range m.data {
		if strings.HasPrefix(k, prefix) {
			out = append(out, storage.ObjectInfo{Key: k, Size: int64(len(v))})
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Key < out[j].Key })
	return out, nil
}

func (m *memStore) Delete(_ context.Context, key string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.data, key)
	m.deletes = append(m.deletes, key)
	return nil
}

func (m *memStore) dataKeys() []string {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]string, 0, len(m.data))
	for k := range m.data {
		if strings.Contains(k, "/part-") {
			out = append(out, k)
		}
	}
	sort.Strings(out)
	return out
}

func (m *memStore) resetCounters() {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.puts, m.gets, m.deletes = nil, nil, nil
}

func (m *memStore) partPuts() []string {
	m.mu.Lock()
	defer m.mu.Unlock()
	var out []string
	for _, k := range m.puts {
		if strings.Contains(k, "/part-") {
			out = append(out, k)
		}
	}
	return out
}

func (m *memStore) partGets() []string {
	m.mu.Lock()
	defer m.mu.Unlock()
	var out []string
	for _, k := range m.gets {
		if strings.Contains(k, "/part-") {
			out = append(out, k)
		}
	}
	return out
}

// memStoreNoList is a Store WITHOUT the optional Lister/Deleter capabilities.
// It wraps rather than embeds, so List is not promoted onto it.
type memStoreNoList struct{ inner *memStore }

func (m memStoreNoList) Put(ctx context.Context, key, ct string, data []byte) (string, error) {
	return m.inner.Put(ctx, key, ct, data)
}
func (m memStoreNoList) Get(ctx context.Context, key string) ([]byte, error) {
	return m.inner.Get(ctx, key)
}
func (m memStoreNoList) URL(key string) string { return m.inner.URL(key) }

// fakeSource mimics tenantchat.Manager for the exporter.
type fakeSource struct {
	rows []Row
	// trimmed records the arguments of the last TrimHot call.
	trimCalled bool
	trimMark   map[string]int
	trimBefore int64
	trimCount  int
}

func (f *fakeSource) MessagesSince(wm map[string]int) []Row {
	var out []Row
	for _, r := range f.rows {
		if r.Seq > wm[r.ConvID] {
			out = append(out, r)
		}
	}
	return out
}

func (f *fakeSource) TrimHot(durable map[string]int, before int64) (int, error) {
	f.trimCalled = true
	f.trimMark = durable
	f.trimBefore = before
	return f.trimCount, nil
}

func day(s string) time.Time {
	t, err := time.Parse("2006-01-02", s)
	if err != nil {
		panic(err)
	}
	return t
}

func mkRow(conv string, seq int, d string, emb []float32) Row {
	return Row{
		ConvID:    conv,
		TenantIDs: []string{"userA", "userB"},
		Seq:       seq,
		Side:      "A",
		Content:   fmt.Sprintf("%s#%d", conv, seq),
		Embedding: emb,
		CreatedAt: day(d).Add(12 * time.Hour).Unix(),
	}
}

// ---- codec ----

func TestJSONLRoundTrip(t *testing.T) {
	rows := []Row{
		mkRow("c1", 1, "2024-05-01", []float32{1, 0, 0}),
		{ConvID: "c1", Seq: 2, Side: "B", Content: "带\n换行 和 \"引号\"", Thinking: "思考", CreatedAt: 42},
	}
	data, err := JSONL{}.Encode(rows)
	if err != nil {
		t.Fatal(err)
	}
	if got := strings.Count(string(data), "\n"); got != 2 {
		t.Errorf("jsonl should be one line per row, got %d newlines", got)
	}
	back, err := JSONL{}.Decode(data)
	if err != nil {
		t.Fatal(err)
	}
	if len(back) != 2 || back[1].Content != rows[1].Content || back[1].Thinking != "思考" {
		t.Fatalf("round trip mismatch: %+v", back)
	}
	if len(back[0].Embedding) != 3 || back[0].Embedding[0] != 1 {
		t.Errorf("embedding lost: %+v", back[0].Embedding)
	}
}

func TestCodecByName(t *testing.T) {
	c, err := CodecByName("")
	if err != nil || c.Name() != DefaultCodec().Name() {
		t.Errorf("empty format should resolve to DefaultCodec, got %v %v", c, err)
	}
	// JSONL has no dependencies and is present in every build.
	for _, name := range []string{"jsonl", "JSONL"} {
		if _, err := CodecByName(name); err != nil {
			t.Errorf("CodecByName(%q) = %v, want the built-in codec", name, err)
		}
	}

	_, err = CodecByName("avro")
	if err == nil {
		t.Fatal("unregistered format should error")
	}
	if !strings.Contains(err.Error(), "RegisterCodec") {
		t.Errorf("error should point at the extension seam, got: %v", err)
	}
	if !strings.Contains(err.Error(), "jsonl") {
		t.Errorf("error should list the available formats, got: %v", err)
	}
}

// A part in a format this build cannot decode must fail the query rather than
// quietly shrink the result set.
func TestUnknownFormatFailsLoudly(t *testing.T) {
	ctx := context.Background()
	obj := newMemStore()
	lay := newLayout("")
	good, _ := JSONL{}.Encode([]Row{mkRow("c1", 1, "2024-05-01", []float32{1, 0})})
	obj.Put(ctx, lay.partKey("c1", "2024-05-01", 1, 1, "jsonl"), "", good)
	obj.Put(ctx, lay.partKey("c1", "2024-05-02", 2, 2, "madeupfmt"), "", []byte("opaque"))

	_, err := NewReader(obj, ReaderOptions{}).Scan(ctx, Query{})
	if err == nil {
		t.Fatal("scan silently skipped an undecodable part; results would be wrong but look complete")
	}
	var ufe *UnknownFormatError
	if !errors.As(err, &ufe) {
		t.Fatalf("err = %v, want *UnknownFormatError", err)
	}
	if ufe.Ext != "madeupfmt" {
		t.Errorf("ext = %q, want madeupfmt", ufe.Ext)
	}
	if !strings.Contains(err.Error(), ufe.Key) {
		t.Errorf("error should name the offending object, got: %v", err)
	}
}

// ---- incremental export ----

func TestExportIsIncremental(t *testing.T) {
	ctx := context.Background()
	obj := newMemStore()
	src := &fakeSource{rows: []Row{
		mkRow("c1", 1, "2024-05-01", []float32{1, 0}),
		mkRow("c1", 2, "2024-05-01", []float32{0, 1}),
	}}
	exp := New(obj, src, Options{})

	st, err := exp.Run(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if st.Rows != 2 || st.Parts != 1 {
		t.Fatalf("first run: rows=%d parts=%d, want 2/1", st.Rows, st.Parts)
	}
	if wm := exp.Watermarks()["c1"]; wm != 2 {
		t.Fatalf("watermark = %d, want 2", wm)
	}

	// A tick with nothing new must not touch object storage at all.
	obj.resetCounters()
	if st, err = exp.Run(ctx); err != nil {
		t.Fatal(err)
	}
	if st.Rows != 0 || len(obj.partPuts()) != 0 {
		t.Fatalf("idle tick wrote %d parts (rows=%d), want 0", len(obj.partPuts()), st.Rows)
	}

	// New messages produce only a new part; the old one is left alone.
	src.rows = append(src.rows, mkRow("c1", 3, "2024-05-02", []float32{1, 1}))
	obj.resetCounters()
	if st, err = exp.Run(ctx); err != nil {
		t.Fatal(err)
	}
	if st.Rows != 1 {
		t.Fatalf("incremental run exported %d rows, want 1", st.Rows)
	}
	puts := obj.partPuts()
	if len(puts) != 1 {
		t.Fatalf("incremental run wrote %v, want exactly 1 new part", puts)
	}
	if !strings.Contains(puts[0], "dt=2024-05-02") {
		t.Errorf("new part should land in the new day partition, got %s", puts[0])
	}
	if keys := obj.dataKeys(); len(keys) != 2 {
		t.Errorf("dataset should have 2 parts total, got %v", keys)
	}
}

func TestPartitionLayoutAndDeterministicKeys(t *testing.T) {
	ctx := context.Background()
	obj := newMemStore()
	src := &fakeSource{rows: []Row{
		mkRow("c1", 1, "2024-05-01", nil),
		mkRow("c2", 7, "2024-05-01", nil),
	}}
	exp := New(obj, src, Options{Prefix: "chat-vectors"})
	if _, err := exp.Run(ctx); err != nil {
		t.Fatal(err)
	}

	ext := DefaultCodec().Ext()
	want := []string{
		"chat-vectors/conv=c1/dt=2024-05-01/part-00000001-00000001." + ext,
		"chat-vectors/conv=c2/dt=2024-05-01/part-00000007-00000007." + ext,
	}
	got := obj.dataKeys()
	if len(got) != 2 || got[0] != want[0] || got[1] != want[1] {
		t.Fatalf("layout mismatch:\n got %v\nwant %v", got, want)
	}

	// Re-exporting the same range must be idempotent (same key, not a dupe).
	exp2 := New(obj, src, Options{Prefix: "chat-vectors"})
	if _, err := exp2.Run(ctx); err != nil {
		t.Fatal(err)
	}
	if got := obj.dataKeys(); len(got) != 2 {
		t.Errorf("re-export created duplicates: %v", got)
	}
}

func TestMaxRowsPerPartSplits(t *testing.T) {
	ctx := context.Background()
	obj := newMemStore()
	var rows []Row
	for i := 1; i <= 5; i++ {
		rows = append(rows, mkRow("c1", i, "2024-05-01", nil))
	}
	exp := New(obj, &fakeSource{rows: rows}, Options{MaxRowsPerPart: 2})
	st, err := exp.Run(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if st.Parts != 3 {
		t.Fatalf("parts = %d, want 3 (2+2+1)", st.Parts)
	}
	ext := DefaultCodec().Ext()
	keys := obj.dataKeys()
	if !strings.HasSuffix(keys[0], "part-00000001-00000002."+ext) ||
		!strings.HasSuffix(keys[2], "part-00000005-00000005."+ext) {
		t.Errorf("unexpected chunk boundaries: %v", keys)
	}
	if exp.Watermarks()["c1"] != 5 {
		t.Errorf("watermark should reach the last written seq")
	}
}

func TestLoadStateFromManifestAndRebuildFromList(t *testing.T) {
	ctx := context.Background()
	obj := newMemStore()
	src := &fakeSource{rows: []Row{
		mkRow("c1", 1, "2024-05-01", nil),
		mkRow("c1", 2, "2024-05-01", nil),
		mkRow("c2", 9, "2024-05-03", nil),
	}}
	exp := New(obj, src, Options{})
	if _, err := exp.Run(ctx); err != nil {
		t.Fatal(err)
	}

	// Fresh process: manifest restores the watermark, so nothing re-exports.
	restored := New(obj, src, Options{})
	if err := restored.LoadState(ctx); err != nil {
		t.Fatal(err)
	}
	if wm := restored.Watermarks(); wm["c1"] != 2 || wm["c2"] != 9 {
		t.Fatalf("manifest restore = %v, want c1:2 c2:9", wm)
	}
	obj.resetCounters()
	if st, _ := restored.Run(ctx); st.Rows != 0 {
		t.Errorf("restored exporter re-exported %d rows", st.Rows)
	}

	// Manifest lost: the watermark is rebuilt from part names alone.
	obj.Delete(ctx, newLayout("").stateKey())
	rebuilt := New(obj, src, Options{})
	obj.resetCounters()
	if err := rebuilt.LoadState(ctx); err != nil {
		t.Fatal(err)
	}
	if wm := rebuilt.Watermarks(); wm["c1"] != 2 || wm["c2"] != 9 {
		t.Fatalf("list rebuild = %v, want c1:2 c2:9", wm)
	}
	if gets := obj.partGets(); len(gets) != 0 {
		t.Errorf("rebuild must not download parts, fetched %v", gets)
	}
}

func TestExportWritesSelfDescribingSchema(t *testing.T) {
	ctx := context.Background()
	obj := newMemStore()
	exp := New(obj, &fakeSource{rows: []Row{mkRow("c1", 1, "2024-05-01", nil)}}, Options{})
	if _, err := exp.Run(ctx); err != nil {
		t.Fatal(err)
	}
	raw, err := obj.Get(ctx, newLayout("").schemaKey())
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"conv_id", "embedding", "partition_keys", "read_hint", DefaultCodec().Name()} {
		if !strings.Contains(string(raw), want) {
			t.Errorf("schema doc missing %q:\n%s", want, raw)
		}
	}
}

// ---- hot/cold tiering ----

func TestTierTrimsOnlyDurableAndOldMessages(t *testing.T) {
	ctx := context.Background()
	obj := newMemStore()
	src := &fakeSource{rows: []Row{mkRow("c1", 1, "2024-05-01", nil)}, trimCount: 1}
	exp := New(obj, src, Options{HotRetention: 24 * time.Hour})

	st, err := exp.Tier(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if !src.trimCalled {
		t.Fatal("Tier should trim the hot tier after a successful export")
	}
	if st.TrimmedHot != 1 {
		t.Errorf("TrimmedHot = %d, want 1", st.TrimmedHot)
	}
	if src.trimMark["c1"] != 1 {
		t.Errorf("trim watermark = %v, want c1:1 (only what is durable)", src.trimMark)
	}
	cutoff := time.Now().Add(-24 * time.Hour).Unix()
	if src.trimBefore > cutoff+5 || src.trimBefore < cutoff-5 {
		t.Errorf("trim cutoff = %d, want ~%d", src.trimBefore, cutoff)
	}
}

func TestTierWithoutRetentionKeepsHotIntact(t *testing.T) {
	src := &fakeSource{rows: []Row{mkRow("c1", 1, "2024-05-01", nil)}}
	exp := New(newMemStore(), src, Options{HotRetention: 0})
	if _, err := exp.Tier(context.Background()); err != nil {
		t.Fatal(err)
	}
	if src.trimCalled {
		t.Error("HotRetention=0 must never trim the hot tier")
	}
}

// ---- cold reads ----

func seedReader(t *testing.T) (*memStore, *Reader) {
	t.Helper()
	obj := newMemStore()
	src := &fakeSource{rows: []Row{
		mkRow("c1", 1, "2024-05-01", []float32{1, 0}),
		mkRow("c1", 2, "2024-05-02", []float32{0, 1}),
		mkRow("c2", 1, "2024-05-03", []float32{1, 1}),
	}}
	exp := New(obj, src, Options{})
	// One tick per day so each partition gets its own part object.
	if _, err := exp.Run(context.Background()); err != nil {
		t.Fatal(err)
	}
	return obj, NewReader(obj, ReaderOptions{})
}

func TestReaderPrunesPartitions(t *testing.T) {
	ctx := context.Background()
	obj, r := seedReader(t)

	obj.resetCounters()
	rows, err := r.Scan(ctx, Query{ConvIDs: []string{"c1"}, From: day("2024-05-02"), To: day("2024-05-02")})
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 || rows[0].Seq != 2 {
		t.Fatalf("pruned scan = %+v, want just c1#2", rows)
	}
	gets := obj.partGets()
	if len(gets) != 1 {
		t.Errorf("partition pruning failed: downloaded %v, want 1 part", gets)
	}
	if strings.Contains(strings.Join(gets, ","), "conv=c2") {
		t.Errorf("unrelated conversation was downloaded: %v", gets)
	}
}

func TestReaderFiltersByTenant(t *testing.T) {
	ctx := context.Background()
	obj := newMemStore()
	rows := []Row{mkRow("c1", 1, "2024-05-01", nil)}
	rows = append(rows, Row{
		ConvID: "c9", TenantIDs: []string{"someoneElse"}, Seq: 1, Side: "A",
		Content: "other tenant", CreatedAt: day("2024-05-01").Unix(),
	})
	if _, err := New(obj, &fakeSource{rows: rows}, Options{}).Run(ctx); err != nil {
		t.Fatal(err)
	}
	got, err := NewReader(obj, ReaderOptions{}).Scan(ctx, Query{TenantID: "userA"})
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 1 || got[0].ConvID != "c1" {
		t.Fatalf("tenant filter = %+v, want only c1", got)
	}
}

func TestReaderDeduplicatesOverlappingParts(t *testing.T) {
	ctx := context.Background()
	obj := newMemStore()
	lay := newLayout("")
	rowA := mkRow("c1", 1, "2024-05-01", nil)
	rowB := mkRow("c1", 2, "2024-05-01", nil)

	// Simulate a lost manifest on a store without listing: one part covers 1-1,
	// a later full re-export covers 1-2. Both objects exist and overlap.
	one, _ := JSONL{}.Encode([]Row{rowA})
	both, _ := JSONL{}.Encode([]Row{rowA, rowB})
	obj.Put(ctx, lay.partKey("c1", "2024-05-01", 1, 1, "jsonl"), "", one)
	obj.Put(ctx, lay.partKey("c1", "2024-05-01", 1, 2, "jsonl"), "", both)

	rows, err := NewReader(obj, ReaderOptions{}).Scan(ctx, Query{})
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 2 {
		t.Fatalf("overlapping parts produced %d rows, want 2 de-duplicated", len(rows))
	}
	if rows[0].Seq != 1 || rows[1].Seq != 2 {
		t.Errorf("rows should be ordered by seq: %+v", rows)
	}
}

func TestReaderIgnoresForeignObjects(t *testing.T) {
	ctx := context.Background()
	obj, r := seedReader(t)
	obj.Put(ctx, "chat-vectors/README.txt", "text/plain", []byte("not a part"))
	obj.Put(ctx, "chat-vectors/conv=c1/dt=nope/part-1-2.jsonl", "", []byte("bad day"))

	rows, err := r.Scan(ctx, Query{})
	if err != nil {
		t.Fatalf("foreign objects must not break the scan: %v", err)
	}
	if len(rows) != 3 {
		t.Errorf("scan = %d rows, want 3", len(rows))
	}
}

func TestColdVectorSearchRanks(t *testing.T) {
	ctx := context.Background()
	_, r := seedReader(t)
	hits, err := r.SearchVector(ctx, []float32{1, 0}, Query{TenantID: "userA"}, 2)
	if err != nil {
		t.Fatal(err)
	}
	if len(hits) != 2 {
		t.Fatalf("hits = %d, want 2", len(hits))
	}
	if hits[0].Row.ConvID != "c1" || hits[0].Row.Seq != 1 {
		t.Errorf("best hit = %s#%d, want c1#1", hits[0].Row.ConvID, hits[0].Row.Seq)
	}
	if hits[0].Similarity < hits[1].Similarity {
		t.Error("hits must be sorted by descending similarity")
	}
}

func TestReaderCachesImmutableParts(t *testing.T) {
	ctx := context.Background()
	obj, r := seedReader(t)
	if _, err := r.Scan(ctx, Query{}); err != nil {
		t.Fatal(err)
	}
	obj.resetCounters()
	if _, err := r.Scan(ctx, Query{}); err != nil {
		t.Fatal(err)
	}
	if gets := obj.partGets(); len(gets) != 0 {
		t.Errorf("second scan re-downloaded %v; parts are immutable and should be cached", gets)
	}
}

func TestReaderStatsIsListOnly(t *testing.T) {
	ctx := context.Background()
	obj, r := seedReader(t)
	obj.resetCounters()
	st, err := r.Stats(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if st.Convs != 2 || st.Parts != 3 || st.Partitions != 3 {
		t.Errorf("stats = %+v, want 2 convs / 3 parts / 3 partitions", st)
	}
	if st.MaxSeq["c1"] != 2 {
		t.Errorf("stats max seq = %v", st.MaxSeq)
	}
	if gets := obj.partGets(); len(gets) != 0 {
		t.Errorf("Stats must not download payloads, fetched %v", gets)
	}
}

func TestReaderNotQueryableWithoutListing(t *testing.T) {
	r := NewReader(memStoreNoList{newMemStore()}, ReaderOptions{})
	if r.Queryable() {
		t.Fatal("a store without List must not report itself queryable")
	}
	if _, err := r.Scan(context.Background(), Query{}); err != ErrNotQueryable {
		t.Errorf("Scan err = %v, want ErrNotQueryable", err)
	}
}

func TestExportWorksWithoutListing(t *testing.T) {
	// Degrade, don't fail: the incremental write path only needs Put/Get.
	obj := memStoreNoList{newMemStore()}
	exp := New(obj, &fakeSource{rows: []Row{mkRow("c1", 1, "2024-05-01", nil)}}, Options{})
	if err := exp.LoadState(context.Background()); err != nil {
		t.Fatalf("LoadState should degrade gracefully: %v", err)
	}
	if st, err := exp.Run(context.Background()); err != nil || st.Rows != 1 {
		t.Fatalf("export = %+v, err %v", st, err)
	}
}

// ---- the "swap JSONL for Parquet" seam ----

// fakeColumnar stands in for a real Parquet codec: a different name, extension
// and encoding, registered from outside the package.
type fakeColumnar struct{}

func (fakeColumnar) Name() string        { return "fakecolumnar" }
func (fakeColumnar) Ext() string         { return "fcol" }
func (fakeColumnar) ContentType() string { return "application/x-fakecolumnar" }
func (fakeColumnar) Encode(rows []Row) ([]byte, error) {
	b, err := JSONL{}.Encode(rows)
	return append([]byte("FCOL\n"), b...), err
}
func (fakeColumnar) Decode(data []byte) ([]Row, error) {
	body, ok := strings.CutPrefix(string(data), "FCOL\n")
	if !ok {
		return nil, fmt.Errorf("bad magic")
	}
	return JSONL{}.Decode([]byte(body))
}

func TestFormatIsSwappableAndMixedPartsStayReadable(t *testing.T) {
	ctx := context.Background()
	RegisterCodec(fakeColumnar{})

	obj := newMemStore()
	src := &fakeSource{rows: []Row{mkRow("c1", 1, "2024-05-01", []float32{1, 0})}}

	// Phase 1: JSONL.
	exp := New(obj, src, Options{Codec: JSONL{}})
	if _, err := exp.Run(ctx); err != nil {
		t.Fatal(err)
	}

	// Phase 2: migrate the writer to the columnar format, keeping the watermark.
	codec, err := CodecByName("fakecolumnar")
	if err != nil {
		t.Fatal(err)
	}
	exp2 := New(obj, src, Options{Codec: codec})
	if err := exp2.LoadState(ctx); err != nil {
		t.Fatal(err)
	}
	src.rows = append(src.rows, mkRow("c1", 2, "2024-05-02", []float32{0, 1}))
	if _, err := exp2.Run(ctx); err != nil {
		t.Fatal(err)
	}

	keys := obj.dataKeys()
	if len(keys) != 2 || !strings.HasSuffix(keys[0], ".jsonl") || !strings.HasSuffix(keys[1], ".fcol") {
		t.Fatalf("expected one part per format, got %v", keys)
	}

	// Phase 3: reads span both formats transparently.
	rows, err := NewReader(obj, ReaderOptions{}).Scan(ctx, Query{})
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 2 || rows[0].Seq != 1 || rows[1].Seq != 2 {
		t.Fatalf("mixed-format scan = %+v, want both rows", rows)
	}
}
