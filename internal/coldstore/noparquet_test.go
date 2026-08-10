//go:build noparquet

// These tests pin the behaviour of a slim build (`go build -tags noparquet`),
// which drops the parquet-go dependency and roughly 10 MB of binary. Run them
// with: go test -tags noparquet ./internal/coldstore/
package coldstore

import (
	"context"
	"errors"
	"strings"
	"testing"
)

func TestSlimBuildFallsBackToJSONL(t *testing.T) {
	if got := DefaultCodec().Name(); got != "jsonl" {
		t.Fatalf("DefaultCodec = %q, want jsonl when parquet is compiled out", got)
	}
	var opt Options
	opt.normalize()
	if opt.Codec.Name() != "jsonl" {
		t.Errorf("Options.normalize codec = %q, want jsonl", opt.Codec.Name())
	}
	byName, err := CodecByName("")
	if err != nil || byName.Name() != "jsonl" {
		t.Errorf("CodecByName(\"\") = %v, %v; want jsonl", byName, err)
	}

	obj := newMemStore()
	exp := New(obj, &fakeSource{rows: []Row{mkRow("c1", 1, "2024-05-01", nil)}}, Options{})
	if _, err := exp.Run(context.Background()); err != nil {
		t.Fatal(err)
	}
	keys := obj.dataKeys()
	if len(keys) != 1 || !strings.HasSuffix(keys[0], ".jsonl") {
		t.Errorf("slim build should write jsonl, got %v", keys)
	}
}

// Asking for parquet here is an operator mistake worth an actionable message,
// not a bare "unknown format".
func TestSlimBuildRejectsParquetWithABuildHint(t *testing.T) {
	_, err := CodecByName("parquet")
	if err == nil {
		t.Fatal("parquet must not resolve in a noparquet build")
	}
	if !strings.Contains(err.Error(), "noparquet") {
		t.Errorf("error should name the build tag, got: %v", err)
	}
	if !strings.Contains(err.Error(), "rebuild") {
		t.Errorf("error should say how to fix it, got: %v", err)
	}
}

// The dangerous case: a dataset written by a full build, later read by a slim
// one. Skipping those parts would silently drop rows from cold search.
func TestSlimBuildRefusesToSilentlySkipParquetParts(t *testing.T) {
	ctx := context.Background()
	obj := newMemStore()
	lay := newLayout("")
	jsonl, _ := JSONL{}.Encode([]Row{mkRow("c1", 1, "2024-05-01", []float32{1, 0})})
	obj.Put(ctx, lay.partKey("c1", "2024-05-01", 1, 1, "jsonl"), "", jsonl)
	obj.Put(ctx, lay.partKey("c1", "2024-05-02", 2, 2, "parquet"), "", []byte("PAR1...PAR1"))

	r := NewReader(obj, ReaderOptions{})
	_, err := r.Scan(ctx, Query{})
	if err == nil {
		t.Fatal("slim build silently skipped parquet parts; cold search would return incomplete results")
	}
	var ufe *UnknownFormatError
	if !errors.As(err, &ufe) {
		t.Fatalf("err = %v, want *UnknownFormatError", err)
	}
	if !strings.Contains(err.Error(), "noparquet") {
		t.Errorf("error should tell the operator how to read this dataset, got: %v", err)
	}

	// Stats only lists keys, so it still works and can reveal the situation.
	st, err := r.Stats(ctx)
	if err != nil {
		t.Fatalf("Stats should not need a codec: %v", err)
	}
	if st.Parts != 2 {
		t.Errorf("Stats saw %d parts, want 2", st.Parts)
	}
}
