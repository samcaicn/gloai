//go:build !noparquet

package coldstore

import (
	"bytes"
	"context"
	"encoding/json"
	"math/rand"
	"strings"
	"testing"

	"github.com/parquet-go/parquet-go"
)

func sampleRows() []Row {
	return []Row{
		{
			ConvID: "c1", TenantIDs: []string{"userA", "userB"}, TenantTags: []string{"制造业", "试点"},
			Seq: 1, Side: "A", Content: "降本要看单位成本，不是看总账",
			Thinking: "先框定口径", Embedding: []float32{1.5, -2.25, 0, 0.125}, CreatedAt: 1714521600,
		},
		{
			// Nothing optional set: no tags, no thinking, no vector.
			ConvID: "c1", Seq: 2, Side: "B", Content: "同意，先做小闭环", CreatedAt: 1714521660,
		},
	}
}

// The default format is a deliberate policy decision, declared in exactly one
// place. Pin it so it cannot drift silently.
func TestParquetIsTheDefaultFormat(t *testing.T) {
	if got := DefaultCodec().Name(); got != "parquet" {
		t.Fatalf("DefaultCodec = %q, want parquet", got)
	}
	// Every path that resolves a format must agree with that one decision.
	byName, err := CodecByName("")
	if err != nil {
		t.Fatal(err)
	}
	if byName.Name() != DefaultCodec().Name() {
		t.Errorf("CodecByName(\"\") = %q, want %q", byName.Name(), DefaultCodec().Name())
	}
	var opt Options
	opt.normalize()
	if opt.Codec.Name() != DefaultCodec().Name() {
		t.Errorf("Options.normalize codec = %q, want %q", opt.Codec.Name(), DefaultCodec().Name())
	}

	obj := newMemStore()
	exp := New(obj, &fakeSource{rows: []Row{mkRow("c1", 1, "2024-05-01", nil)}}, Options{})
	if _, err := exp.Run(context.Background()); err != nil {
		t.Fatal(err)
	}
	keys := obj.dataKeys()
	if len(keys) != 1 || !strings.HasSuffix(keys[0], ".parquet") {
		t.Errorf("an unconfigured exporter should write parquet, got %v", keys)
	}
}

func TestParquetRoundTrip(t *testing.T) {
	rows := sampleRows()
	data, err := Parquet{}.Encode(rows)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.HasPrefix(data, []byte("PAR1")) || !bytes.HasSuffix(data, []byte("PAR1")) {
		t.Fatal("output is not a parquet file (missing PAR1 magic)")
	}

	back, err := Parquet{}.Decode(data)
	if err != nil {
		t.Fatal(err)
	}
	if len(back) != 2 {
		t.Fatalf("decoded %d rows, want 2", len(back))
	}

	got, want := back[0], rows[0]
	if got.ConvID != want.ConvID || got.Seq != want.Seq || got.Side != want.Side ||
		got.Content != want.Content || got.Thinking != want.Thinking || got.CreatedAt != want.CreatedAt {
		t.Errorf("scalar columns changed:\n got %+v\nwant %+v", got, want)
	}
	if len(got.TenantIDs) != 2 || got.TenantIDs[1] != "userB" {
		t.Errorf("tenant_ids = %v", got.TenantIDs)
	}
	if len(got.TenantTags) != 2 || got.TenantTags[0] != "制造业" {
		t.Errorf("tenant_tags = %v", got.TenantTags)
	}
	if len(got.Embedding) != 4 {
		t.Fatalf("embedding = %v", got.Embedding)
	}
	for i, v := range want.Embedding {
		if got.Embedding[i] != v {
			t.Errorf("embedding[%d] = %v, want %v (float32 must be exact)", i, got.Embedding[i], v)
		}
	}
}

// Parquet has no nil list, so absent slices must be normalised back to nil —
// otherwise the two codecs would not be interchangeable.
func TestParquetNormalisesEmptyListsToNil(t *testing.T) {
	data, err := Parquet{}.Encode(sampleRows())
	if err != nil {
		t.Fatal(err)
	}
	back, err := Parquet{}.Decode(data)
	if err != nil {
		t.Fatal(err)
	}
	sparse := back[1]
	if sparse.Embedding != nil || sparse.TenantIDs != nil || sparse.TenantTags != nil {
		t.Errorf("absent lists should decode as nil, got %+v", sparse)
	}
	if sparse.Thinking != "" {
		t.Errorf("thinking = %q, want empty", sparse.Thinking)
	}
}

// The whole point of the seam is that a Row survives a trip through either
// codec unchanged, so parts of both formats can coexist in one dataset.
func TestCodecsAreInterchangeable(t *testing.T) {
	rows := sampleRows()

	viaJSONL, err := JSONL{}.Encode(rows)
	if err != nil {
		t.Fatal(err)
	}
	decodedJSONL, err := JSONL{}.Decode(viaJSONL)
	if err != nil {
		t.Fatal(err)
	}
	viaParquet, err := Parquet{}.Encode(decodedJSONL)
	if err != nil {
		t.Fatal(err)
	}
	decodedParquet, err := Parquet{}.Decode(viaParquet)
	if err != nil {
		t.Fatal(err)
	}

	if len(decodedParquet) != len(decodedJSONL) {
		t.Fatalf("row count drifted: %d vs %d", len(decodedParquet), len(decodedJSONL))
	}
	for i := range decodedJSONL {
		a, b := decodedJSONL[i], decodedParquet[i]
		if a.ConvID != b.ConvID || a.Seq != b.Seq || a.Side != b.Side || a.Content != b.Content ||
			a.Thinking != b.Thinking || a.CreatedAt != b.CreatedAt {
			t.Errorf("row %d scalars diverged:\n jsonl %+v\nparquet %+v", i, a, b)
		}
		if len(a.Embedding) != len(b.Embedding) || len(a.TenantIDs) != len(b.TenantIDs) {
			t.Errorf("row %d lists diverged:\n jsonl %+v\nparquet %+v", i, a, b)
		}
	}
}

func TestParquetEmptyPartIsStillValid(t *testing.T) {
	data, err := Parquet{}.Encode(nil)
	if err != nil {
		t.Fatal(err)
	}
	rows, err := Parquet{}.Decode(data)
	if err != nil {
		t.Fatalf("an empty part must still be a readable parquet file: %v", err)
	}
	if len(rows) != 0 {
		t.Errorf("rows = %d, want 0", len(rows))
	}
}

// External engines address columns by name. They must match the JSON names
// exactly, or a migration would silently break every existing query.
func TestParquetSchemaMatchesTheOpenContract(t *testing.T) {
	schema := parquet.SchemaOf(Row{}).String()
	for _, col := range []string{
		"conv_id", "tenant_ids", "tenant_tags", "seq",
		"side", "content", "thinking", "embedding", "created_at",
	} {
		if !strings.Contains(schema, col) {
			t.Errorf("column %q missing from parquet schema:\n%s", col, schema)
		}
	}
	// Go field names must never leak into the physical schema.
	for _, leaked := range []string{"ConvID", "TenantIDs", "CreatedAt"} {
		if strings.Contains(schema, leaked) {
			t.Errorf("Go field name %q leaked into the parquet schema:\n%s", leaked, schema)
		}
	}
	// Slices must use the 3-level LIST logical type, not a legacy repeated
	// field, or pyarrow/Spark read them differently from DuckDB.
	if strings.Count(schema, "(LIST)") != 3 {
		t.Errorf("expected 3 LIST columns (tenant_ids, tenant_tags, embedding):\n%s", schema)
	}
	if !strings.Contains(schema, "required float element") {
		t.Errorf("embedding elements should be FLOAT:\n%s", schema)
	}
}

func TestParquetIsSmallerThanJSONLForVectors(t *testing.T) {
	// A realistic-ish part: many rows, each with a 256-d embedding. The floats
	// come from a PRNG so they have the entropy of real embeddings — a
	// low-cardinality synthetic vector would flatter the compression ratio.
	rng := rand.New(rand.NewSource(1))
	var rows []Row
	for i := 1; i <= 200; i++ {
		emb := make([]float32, 256)
		for j := range emb {
			emb[j] = float32(rng.NormFloat64())
		}
		rows = append(rows, Row{
			ConvID: "c1", TenantIDs: []string{"userA", "userB"}, Seq: i, Side: "A",
			Content: "这是一条用于压缩率对比的会话消息", Embedding: emb, CreatedAt: 1714521600 + int64(i),
		})
	}

	jsonl, err := JSONL{}.Encode(rows)
	if err != nil {
		t.Fatal(err)
	}
	pq, err := Parquet{}.Encode(rows)
	if err != nil {
		t.Fatal(err)
	}
	if len(pq) >= len(jsonl) {
		t.Errorf("parquet (%d B) should beat jsonl (%d B) on vector-heavy data", len(pq), len(jsonl))
	}
	t.Logf("jsonl=%d B parquet=%d B (%.1f%% of jsonl)",
		len(jsonl), len(pq), 100*float64(len(pq))/float64(len(jsonl)))
}

// ---- integration with the exporter/reader ----

func TestExportAndQueryInParquet(t *testing.T) {
	ctx := context.Background()
	obj := newMemStore()
	codec, err := CodecByName("parquet")
	if err != nil {
		t.Fatal(err)
	}
	src := &fakeSource{rows: []Row{
		mkRow("c1", 1, "2024-05-01", []float32{1, 0}),
		mkRow("c1", 2, "2024-05-01", []float32{0, 1}),
	}}

	exp := New(obj, src, Options{Codec: codec})
	if _, err := exp.Run(ctx); err != nil {
		t.Fatal(err)
	}
	keys := obj.dataKeys()
	if len(keys) != 1 || !strings.HasSuffix(keys[0], ".parquet") {
		t.Fatalf("expected a .parquet part, got %v", keys)
	}

	// Incremental export and watermark recovery must work identically.
	src.rows = append(src.rows, mkRow("c1", 3, "2024-05-02", []float32{1, 1}))
	restored := New(obj, src, Options{Codec: codec})
	if err := restored.LoadState(ctx); err != nil {
		t.Fatal(err)
	}
	st, err := restored.Run(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if st.Rows != 1 {
		t.Errorf("incremental parquet export = %d rows, want 1", st.Rows)
	}

	// And the cold read path works over parquet parts.
	hits, err := NewReader(obj, ReaderOptions{}).SearchVector(ctx, []float32{1, 0}, Query{TenantID: "userA"}, 1)
	if err != nil {
		t.Fatal(err)
	}
	if len(hits) != 1 || hits[0].Row.Seq != 1 {
		t.Fatalf("cold vector search over parquet = %+v", hits)
	}
}

func TestSchemaDocAdvertisesTheRightReader(t *testing.T) {
	ctx := context.Background()
	for _, tc := range []struct {
		format, want string
	}{
		{"jsonl", "read_json_auto("},
		{"parquet", "read_parquet("},
	} {
		obj := newMemStore()
		codec, err := CodecByName(tc.format)
		if err != nil {
			t.Fatal(err)
		}
		exp := New(obj, &fakeSource{rows: []Row{mkRow("c1", 1, "2024-05-01", nil)}}, Options{Codec: codec})
		if _, err := exp.Run(ctx); err != nil {
			t.Fatal(err)
		}
		raw, err := obj.Get(ctx, newLayout("").schemaKey())
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(string(raw), tc.want) {
			t.Errorf("%s schema doc should advertise %s:\n%s", tc.format, tc.want, raw)
		}
		if !strings.Contains(string(raw), "*."+codec.Ext()) {
			t.Errorf("%s schema doc should glob *.%s:\n%s", tc.format, codec.Ext(), raw)
		}
	}
}

// A dataset that was migrated mid-life must stay fully readable, with no
// backfill of the old parts.
func TestMigrationJSONLToParquetNeedsNoBackfill(t *testing.T) {
	ctx := context.Background()
	obj := newMemStore()
	src := &fakeSource{rows: []Row{mkRow("c1", 1, "2024-05-01", []float32{1, 0})}}

	// A dataset that predates the parquet default.
	if _, err := New(obj, src, Options{Codec: JSONL{}}).Run(ctx); err != nil {
		t.Fatal(err)
	}

	pq, _ := CodecByName("parquet")
	migrated := New(obj, src, Options{Codec: pq})
	if err := migrated.LoadState(ctx); err != nil {
		t.Fatal(err)
	}
	src.rows = append(src.rows, mkRow("c1", 2, "2024-05-02", []float32{0, 1}))
	if _, err := migrated.Run(ctx); err != nil {
		t.Fatal(err)
	}

	keys := obj.dataKeys()
	if len(keys) != 2 {
		t.Fatalf("expected the old part to be left alone, got %v", keys)
	}
	if !strings.HasSuffix(keys[0], ".jsonl") || !strings.HasSuffix(keys[1], ".parquet") {
		t.Fatalf("expected one part per format, got %v", keys)
	}

	rows, err := NewReader(obj, ReaderOptions{}).Scan(ctx, Query{})
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 2 || rows[0].Seq != 1 || rows[1].Seq != 2 {
		t.Fatalf("mixed-format scan = %+v, want both rows", rows)
	}
	if len(rows[0].Embedding) != 2 || len(rows[1].Embedding) != 2 {
		t.Error("vectors must survive the format boundary")
	}
}

// Switching the default silently turns every existing dataset into a mixed one.
// An external consumer that reads only the current format would lose the older
// rows, so the manifest has to name every format present.
func TestManifestAdvertisesEveryFormatPresent(t *testing.T) {
	ctx := context.Background()
	obj := newMemStore()
	src := &fakeSource{rows: []Row{mkRow("c1", 1, "2024-05-01", nil)}}

	if _, err := New(obj, src, Options{Codec: JSONL{}}).Run(ctx); err != nil {
		t.Fatal(err)
	}
	// Before the switch the dataset is single-format and needs no warning.
	if doc := readSchemaDoc(t, obj); doc.Warning != "" || len(doc.Formats) != 1 {
		t.Fatalf("single-format dataset should not warn: %+v", doc)
	}

	migrated := New(obj, src, Options{}) // default = parquet
	if err := migrated.LoadState(ctx); err != nil {
		t.Fatal(err)
	}
	src.rows = append(src.rows, mkRow("c1", 2, "2024-05-02", nil))
	if _, err := migrated.Run(ctx); err != nil {
		t.Fatal(err)
	}

	if got := migrated.Formats(); len(got) != 2 || got[0] != "jsonl" || got[1] != "parquet" {
		t.Fatalf("Formats() = %v, want [jsonl parquet]", got)
	}

	doc := readSchemaDoc(t, obj)
	if doc.Codec != "parquet" {
		t.Errorf("codec = %q, want the format new parts use", doc.Codec)
	}
	if len(doc.Formats) != 2 {
		t.Fatalf("formats = %v, want both", doc.Formats)
	}
	if _, ok := doc.ReadHints["jsonl"]; !ok {
		t.Error("manifest dropped the read hint for the pre-migration format")
	}
	if _, ok := doc.ReadHints["parquet"]; !ok {
		t.Error("manifest is missing the read hint for the current format")
	}
	if doc.Warning == "" {
		t.Error("a mixed-format dataset must warn that reading one format loses rows")
	}
}

// The format set must survive a restart that reads the manifest instead of
// re-listing the bucket.
func TestFormatSetSurvivesManifestRestore(t *testing.T) {
	ctx := context.Background()
	obj := newMemStore()
	src := &fakeSource{rows: []Row{mkRow("c1", 1, "2024-05-01", nil)}}

	if _, err := New(obj, src, Options{Codec: JSONL{}}).Run(ctx); err != nil {
		t.Fatal(err)
	}
	restarted := New(obj, src, Options{}) // parquet, but nothing new to write
	if err := restarted.LoadState(ctx); err != nil {
		t.Fatal(err)
	}
	if got := restarted.Formats(); len(got) != 1 || got[0] != "jsonl" {
		t.Fatalf("Formats() after manifest restore = %v, want [jsonl]", got)
	}
}

func readSchemaDoc(t *testing.T, obj *memStore) schemaDoc {
	t.Helper()
	raw, err := obj.Get(context.Background(), newLayout("").schemaKey())
	if err != nil {
		t.Fatal(err)
	}
	var doc schemaDoc
	if err := json.Unmarshal(raw, &doc); err != nil {
		t.Fatal(err)
	}
	return doc
}
