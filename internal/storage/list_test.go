package storage

import (
	"context"
	"testing"
)

func seedFS(t *testing.T) *FSStore {
	t.Helper()
	s, err := NewFS(t.TempDir(), "/media")
	if err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()
	for _, k := range []string{
		"chat-vectors/conv=a/dt=2024-05-01/part-00000001-00000002.jsonl",
		"chat-vectors/conv=a/dt=2024-05-02/part-00000003-00000003.jsonl",
		"chat-vectors/conv=b/dt=2024-05-01/part-00000001-00000001.jsonl",
		"chat-vectors/_manifest/state.json",
		"media/photo.png",
	} {
		if _, err := s.Put(ctx, k, "application/octet-stream", []byte(k)); err != nil {
			t.Fatal(err)
		}
	}
	return s
}

func TestFSListPrefix(t *testing.T) {
	s := seedFS(t)
	ctx := context.Background()

	all, err := s.List(ctx, "chat-vectors/")
	if err != nil {
		t.Fatal(err)
	}
	if len(all) != 4 {
		t.Fatalf("list chat-vectors/ = %d objects, want 4", len(all))
	}
	if all[0].Key != "chat-vectors/_manifest/state.json" {
		t.Errorf("results should be key-sorted, got %s first", all[0].Key)
	}
	if all[0].Size == 0 {
		t.Error("ObjectInfo.Size should be populated")
	}

	// A partition prefix must not leak neighbouring partitions.
	one, err := s.List(ctx, "chat-vectors/conv=a/")
	if err != nil {
		t.Fatal(err)
	}
	if len(one) != 2 {
		t.Fatalf("list conv=a = %d, want 2: %+v", len(one), one)
	}
	for _, o := range one {
		if o.Key == "media/photo.png" {
			t.Error("prefix listing leaked an unrelated object")
		}
	}
}

func TestFSListPartialSegmentPrefix(t *testing.T) {
	s := seedFS(t)
	// A prefix that stops mid-filename must still match, like S3 does.
	got, err := s.List(context.Background(), "chat-vectors/conv=a/dt=2024-05-01/part-")
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 1 {
		t.Fatalf("partial prefix = %+v, want 1", got)
	}
}

func TestFSListMissingPrefixIsEmpty(t *testing.T) {
	s := seedFS(t)
	got, err := s.List(context.Background(), "nothing-here/")
	if err != nil {
		t.Fatalf("missing prefix should not error: %v", err)
	}
	if len(got) != 0 {
		t.Errorf("missing prefix = %+v, want empty", got)
	}
}

func TestFSListRejectsTraversal(t *testing.T) {
	s := seedFS(t)
	if _, err := s.List(context.Background(), "../../etc/"); err == nil {
		t.Error("path traversal in a list prefix must be rejected")
	}
}

func TestFSDelete(t *testing.T) {
	s := seedFS(t)
	ctx := context.Background()
	key := "chat-vectors/conv=b/dt=2024-05-01/part-00000001-00000001.jsonl"

	if err := s.Delete(ctx, key); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Get(ctx, key); err == nil {
		t.Error("object should be gone after Delete")
	}
	// Deleting twice is a no-op, matching S3 semantics.
	if err := s.Delete(ctx, key); err != nil {
		t.Errorf("deleting a missing key should be a no-op, got %v", err)
	}
	if err := s.Delete(ctx, "../escape"); err == nil {
		t.Error("path traversal in Delete must be rejected")
	}
}

// The optional capabilities must actually be satisfied by the concrete stores,
// since callers discover them with a type assertion.
func TestFSImplementsOptionalCapabilities(t *testing.T) {
	var s any = &FSStore{}
	if _, ok := s.(Lister); !ok {
		t.Error("FSStore must implement Lister")
	}
	if _, ok := s.(Deleter); !ok {
		t.Error("FSStore must implement Deleter")
	}
}

func TestS3ImplementsOptionalCapabilities(t *testing.T) {
	var s any = &S3Store{}
	if _, ok := s.(Lister); !ok {
		t.Error("S3Store must implement Lister")
	}
	if _, ok := s.(Deleter); !ok {
		t.Error("S3Store must implement Deleter")
	}
}
