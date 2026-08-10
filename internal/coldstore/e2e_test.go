package coldstore_test

// End-to-end exercise of the hot/cold split with the real components: a live
// SQLite database as the hot tier, a filesystem-backed object store standing in
// for the S3-compatible one, and the tenantchat manager as the message source.

import (
	"context"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
	"time"

	"github.com/ceoadmin/CEOadmin/internal/ai"
	"github.com/ceoadmin/CEOadmin/internal/coldstore"
	"github.com/ceoadmin/CEOadmin/internal/storage"
	"github.com/ceoadmin/CEOadmin/internal/store"
	"github.com/ceoadmin/CEOadmin/internal/store/sqlite"
	"github.com/ceoadmin/CEOadmin/internal/tenantchat"
)

// fakeVector maps text to a deterministic 3-d vector so similarity is
// predictable without an embedding endpoint.
func fakeVector(text string) []float32 {
	var v [3]float32
	for i, r := range text {
		v[i%3] += float32(r%16) + 1
	}
	return v[:]
}

func setupHotTier(t *testing.T) (store.Store, *tenantchat.Manager, string) {
	t.Helper()
	dir := t.TempDir()
	db, err := sqlite.Open(filepath.Join(dir, "hot.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })

	// The platform's shared OpenAI interface must look configured.
	if err := db.SetConfig("ai.api_key", "test-key"); err != nil {
		t.Fatal(err)
	}
	if err := db.SetConfig("ai.model", "test-model"); err != nil {
		t.Fatal(err)
	}

	replies := []string{"降本要看单位成本", "先做小闭环再推广", "数据打通是前提"}
	turn := 0
	tenantchat.SetAICompletion(func(context.Context, store.AIConfig, []ai.Message, []ai.Tool) (*ai.CompletionResult, error) {
		r := replies[turn%len(replies)]
		turn++
		return &ai.CompletionResult{Content: r, Thinking: "内部推理"}, nil
	})
	tenantchat.SetAIEmbedding(func(_ context.Context, _ store.AIConfig, texts []string) ([][]float32, error) {
		out := make([][]float32, len(texts))
		for i, s := range texts {
			out[i] = fakeVector(s)
		}
		return out, nil
	})
	t.Cleanup(func() {
		tenantchat.SetAICompletion(ai.CompleteMessages)
		tenantchat.SetAIEmbedding(nil)
	})

	mgr := &tenantchat.Manager{}
	mgr.Init(db)

	conv, err := mgr.Create("userA")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := mgr.Join(conv.ID, conv.InviteCode, "userB"); err != nil {
		t.Fatal(err)
	}
	return db, mgr, conv.ID
}

func objectKeys(t *testing.T, root string) []string {
	t.Helper()
	var keys []string
	err := filepath.Walk(root, func(p string, info os.FileInfo, err error) error {
		if err != nil || info.IsDir() {
			return err
		}
		rel, _ := filepath.Rel(root, p)
		keys = append(keys, filepath.ToSlash(rel))
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	sort.Strings(keys)
	return keys
}

func TestEndToEndHotColdTiering(t *testing.T) {
	ctx := context.Background()
	db, mgr, convID := setupHotTier(t)

	cosDir := t.TempDir()
	obj, err := storage.NewFS(cosDir, "/media")
	if err != nil {
		t.Fatal(err)
	}

	exporter := coldstore.New(obj, mgr, coldstore.Options{HotRetention: time.Millisecond})
	if err := exporter.LoadState(ctx); err != nil {
		t.Fatal(err)
	}
	reader := coldstore.NewReader(obj, coldstore.ReaderOptions{})
	mgr.SetColdSearcher(reader)

	// --- two turns of real conversation land in the hot tier ---
	for i := 0; i < 2; i++ {
		if err := mgr.Step(convID); err != nil {
			t.Fatal(err)
		}
	}

	st, err := exporter.Run(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if st.Rows != 2 || st.Parts != 1 {
		t.Fatalf("first export = %d rows / %d parts, want 2/1", st.Rows, st.Parts)
	}

	keys := objectKeys(t, cosDir)
	var parts []string
	for _, k := range keys {
		if strings.Contains(k, "/part-") {
			parts = append(parts, k)
		}
	}
	if len(parts) != 1 {
		t.Fatalf("expected one part object, got %v", keys)
	}
	// The layout must be the Hive-style one external engines glob for, in the
	// default format.
	if !strings.HasPrefix(parts[0], "chat-vectors/conv="+convID+"/dt=") ||
		!strings.HasSuffix(parts[0], "."+coldstore.DefaultCodec().Ext()) {
		t.Errorf("unexpected partition layout: %s", parts[0])
	}
	// And the dataset must be self-describing.
	for _, want := range []string{"chat-vectors/_schema.json", "chat-vectors/_manifest/state.json"} {
		if !contains(keys, want) {
			t.Errorf("missing %s in %v", want, keys)
		}
	}

	// --- a third turn exports incrementally, not as a rewrite ---
	if err := mgr.Step(convID); err != nil {
		t.Fatal(err)
	}
	st, err = exporter.Run(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if st.Rows != 1 || st.Parts != 1 {
		t.Fatalf("incremental export = %d rows / %d parts, want 1/1", st.Rows, st.Parts)
	}
	if got := exporter.Watermarks()[convID]; got != 3 {
		t.Errorf("watermark = %d, want 3", got)
	}

	// --- cold tier holds every message, losslessly ---
	rows, err := reader.Scan(ctx, coldstore.Query{TenantID: "userA"})
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 3 {
		t.Fatalf("cold tier has %d rows, want 3", len(rows))
	}
	for _, r := range rows {
		if len(r.Embedding) == 0 || r.Thinking == "" || r.Content == "" {
			t.Fatalf("cold row lost data: %+v", r)
		}
	}

	// --- trim the hot tier, then prove search still finds the message ---
	time.Sleep(1100 * time.Millisecond) // let the messages age past the retention cut-off
	if _, err := exporter.Tier(ctx); err != nil {
		t.Fatal(err)
	}

	conv, ok := mgr.Get(convID)
	if !ok {
		t.Fatal("conversation vanished")
	}
	for _, m := range conv.Messages {
		if len(m.Embedding) != 0 {
			t.Errorf("seq %d still carries a vector in the hot tier", m.Seq)
		}
		if !m.Archived {
			t.Errorf("seq %d should be marked archived", m.Seq)
		}
		if m.Content == "" {
			t.Errorf("seq %d lost its text; trimming must keep history readable", m.Seq)
		}
	}

	// The stripped state is what a restart would read back from SQLite.
	restarted := &tenantchat.Manager{}
	restarted.Init(db)
	restarted.SetColdSearcher(reader)
	if reloaded, ok := restarted.Get(convID); !ok || len(reloaded.Messages) != 3 {
		t.Fatalf("hot tier lost history across restart: %+v", reloaded)
	}

	hits, err := restarted.SearchMessages("userA", "降本要看单位成本", 3)
	if err != nil {
		t.Fatal(err)
	}
	if len(hits) == 0 {
		t.Fatal("cold tier failed to serve search after the hot tier was trimmed")
	}
	if hits[0].Tier != tenantchat.TierCold {
		t.Errorf("hit tier = %q, want cold", hits[0].Tier)
	}
	if hits[0].Content != "降本要看单位成本" {
		t.Errorf("best hit = %q, want the exact match from object storage", hits[0].Content)
	}

	// --- exporting again must not resurrect the trimmed rows ---
	st, err = exporter.Run(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if st.Rows != 0 {
		t.Errorf("re-export after trim wrote %d rows; stripped rows must never overwrite good parts", st.Rows)
	}
	rows, _ = reader.Scan(ctx, coldstore.Query{})
	for _, r := range rows {
		if len(r.Embedding) == 0 {
			t.Errorf("cold part was overwritten with a trimmed row: %+v", r)
		}
	}
}

func contains(list []string, want string) bool {
	for _, s := range list {
		if s == want {
			return true
		}
	}
	return false
}
