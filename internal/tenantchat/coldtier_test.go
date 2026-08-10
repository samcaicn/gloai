package tenantchat

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"

	"github.com/ceoadmin/CEOadmin/internal/coldstore"
	"github.com/ceoadmin/CEOadmin/internal/store"
)

// ---- helpers ----

// seedPair builds a paired 甲乙 conversation and installs the given messages.
func seedPair(t *testing.T, m *Manager, msgs ...Message) string {
	t.Helper()
	conv, err := m.Create("userA")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := m.Join(conv.ID, conv.InviteCode, "userB"); err != nil {
		t.Fatal(err)
	}
	m.mu.Lock()
	rt := m.convs[conv.ID]
	m.mu.Unlock()
	rt.mu.Lock()
	rt.conv.Messages = msgs
	rt.conv.Participants[SideA].Tags = []string{"制造业"}
	rt.mu.Unlock()
	if err := m.persist(rt); err != nil {
		t.Fatal(err)
	}
	return conv.ID
}

func msg(seq int, content string, emb []float32, at time.Time) Message {
	return Message{
		Seq: seq, Side: SideA, Content: content, Thinking: "思考" + content,
		Embedding: emb, CreatedAt: at.Unix(),
	}
}

func withFakeEmbedding(vec []float32) func() {
	SetAIEmbedding(func(context.Context, store.AIConfig, []string) ([][]float32, error) {
		return [][]float32{vec}, nil
	})
	return func() { SetAIEmbedding(nil) }
}

// configuredManager returns a manager whose global AI interface looks usable,
// which SearchMessages requires in order to embed the query.
func configuredManager(t *testing.T) *Manager {
	t.Helper()
	cfg := newMemConfig()
	cfg.m["ai.api_key"] = "test-key"
	cfg.m["ai.model"] = "test-model"
	return newManager(cfg)
}

type fakeCold struct {
	hits []coldstore.Hit
	err  error
	got  coldstore.Query
	k    int
}

func (f *fakeCold) SearchVector(_ context.Context, _ []float32, q coldstore.Query, k int) ([]coldstore.Hit, error) {
	f.got, f.k = q, k
	return f.hits, f.err
}

// ---- incremental export source ----

func TestMessagesSinceOnlyReturnsNewMessages(t *testing.T) {
	m := newManager(newMemConfig())
	now := time.Now()
	id := seedPair(t, m,
		msg(1, "one", []float32{1, 0}, now),
		msg(2, "two", []float32{0, 1}, now),
		msg(3, "three", nil, now),
	)

	if all := m.MessagesSince(nil); len(all) != 3 {
		t.Fatalf("no watermark should export everything, got %d", len(all))
	}
	got := m.MessagesSince(map[string]int{id: 2})
	if len(got) != 1 || got[0].Seq != 3 {
		t.Fatalf("MessagesSince(2) = %+v, want only seq 3", got)
	}
	if len(m.MessagesSince(map[string]int{id: 3})) != 0 {
		t.Error("a caught-up watermark must export nothing")
	}
}

func TestMessagesSinceCarriesTenantAttributionAndHeavyColumns(t *testing.T) {
	m := newManager(newMemConfig())
	seedPair(t, m, msg(1, "hello", []float32{1, 2, 3}, time.Now()))

	rows := m.MessagesSince(nil)
	if len(rows) != 1 {
		t.Fatalf("rows = %d", len(rows))
	}
	r := rows[0]
	if len(r.TenantIDs) != 2 || r.TenantIDs[0] != "userA" || r.TenantIDs[1] != "userB" {
		t.Errorf("tenant ids = %v, want [userA userB] in seat order", r.TenantIDs)
	}
	if len(r.TenantTags) != 1 || r.TenantTags[0] != "制造业" {
		t.Errorf("tenant tags = %v", r.TenantTags)
	}
	if len(r.Embedding) != 3 || r.Thinking == "" || r.Content != "hello" {
		t.Errorf("cold row must be lossless, got %+v", r)
	}
}

func TestMessagesSinceSkipsAlreadyTrimmedMessages(t *testing.T) {
	m := newManager(newMemConfig())
	old := time.Now().Add(-72 * time.Hour)
	id := seedPair(t, m, msg(1, "archived", nil, old), msg(2, "live", []float32{1}, time.Now()))

	m.mu.Lock()
	m.convs[id].conv.Messages[0].Archived = true
	m.mu.Unlock()

	// A watermark reset must not re-export a stripped row over a good part.
	rows := m.MessagesSince(nil)
	if len(rows) != 1 || rows[0].Seq != 2 {
		t.Fatalf("archived rows must not be re-exported, got %+v", rows)
	}
}

// ---- hot tier trimming ----

func TestTrimHotShedsHeavyColumnsButKeepsText(t *testing.T) {
	m := newManager(newMemConfig())
	old := time.Now().Add(-72 * time.Hour)
	fresh := time.Now()
	id := seedPair(t, m,
		msg(1, "durable-and-old", []float32{1, 0}, old),
		msg(2, "durable-but-fresh", []float32{0, 1}, fresh),
		msg(3, "not-yet-durable", []float32{1, 1}, old),
	)

	cutoff := time.Now().Add(-24 * time.Hour).Unix()
	n, err := m.TrimHot(map[string]int{id: 2}, cutoff)
	if err != nil {
		t.Fatal(err)
	}
	if n != 1 {
		t.Fatalf("trimmed %d messages, want 1 (only durable AND old)", n)
	}

	conv, _ := m.Get(id)
	if got := conv.Messages[0]; len(got.Embedding) != 0 || got.Thinking != "" || !got.Archived {
		t.Errorf("msg 1 should be stripped and marked archived, got %+v", got)
	}
	if conv.Messages[0].Content != "durable-and-old" {
		t.Error("trimming must never drop the message text")
	}
	if len(conv.Messages[1].Embedding) == 0 || conv.Messages[1].Archived {
		t.Error("msg 2 is inside the retention window and must stay hot")
	}
	if len(conv.Messages[2].Embedding) == 0 || conv.Messages[2].Archived {
		t.Error("msg 3 is not durable in object storage yet and must never be trimmed")
	}
}

func TestTrimHotPersistsAndIsIdempotent(t *testing.T) {
	cfg := newMemConfig()
	m := newManager(cfg)
	old := time.Now().Add(-72 * time.Hour)
	id := seedPair(t, m, msg(1, "old", []float32{1, 0}, old))
	cutoff := time.Now().Add(-24 * time.Hour).Unix()

	if n, err := m.TrimHot(map[string]int{id: 1}, cutoff); err != nil || n != 1 {
		t.Fatalf("first trim = %d, %v", n, err)
	}
	// Second pass has nothing left to shed.
	if n, err := m.TrimHot(map[string]int{id: 1}, cutoff); err != nil || n != 0 {
		t.Fatalf("second trim = %d, %v; want 0 (idempotent)", n, err)
	}

	// The stripped state must survive a restart, i.e. it really left SQLite.
	var persisted Conversation
	if err := json.Unmarshal([]byte(cfg.m[convKey(id)]), &persisted); err != nil {
		t.Fatal(err)
	}
	if len(persisted.Messages[0].Embedding) != 0 || !persisted.Messages[0].Archived {
		t.Errorf("hot tier still carries the vector after trim: %+v", persisted.Messages[0])
	}

	reloaded := newManager(cfg)
	conv, ok := reloaded.Get(id)
	if !ok || !conv.Messages[0].Archived {
		t.Error("archived flag lost across restart")
	}
}

func TestTrimHotIgnoresConversationsWithoutWatermark(t *testing.T) {
	m := newManager(newMemConfig())
	old := time.Now().Add(-72 * time.Hour)
	id := seedPair(t, m, msg(1, "old", []float32{1, 0}, old))

	n, err := m.TrimHot(map[string]int{}, time.Now().Unix())
	if err != nil || n != 0 {
		t.Fatalf("trim without a watermark = %d, %v; nothing is durable yet", n, err)
	}
	conv, _ := m.Get(id)
	if len(conv.Messages[0].Embedding) == 0 {
		t.Error("data was destroyed without a cold copy")
	}
}

// ---- merged hot + cold search ----

func TestSearchMergesHotAndColdTiers(t *testing.T) {
	defer withFakeEmbedding([]float32{1, 0})()
	m := configuredManager(t)
	id := seedPair(t, m,
		msg(1, "hot hit", []float32{1, 0}, time.Now()),
		msg(2, "trimmed", nil, time.Now().Add(-72*time.Hour)),
	)
	cold := &fakeCold{hits: []coldstore.Hit{{
		Row:        coldstore.Row{ConvID: id, Seq: 2, Side: "A", Content: "trimmed"},
		Similarity: 0.8,
	}}}
	m.SetColdSearcher(cold)

	hits, err := m.SearchMessages("userA", "问题", 5)
	if err != nil {
		t.Fatal(err)
	}
	if len(hits) != 2 {
		t.Fatalf("hits = %+v, want one from each tier", hits)
	}
	if hits[0].Tier != TierHot || hits[0].Seq != 1 {
		t.Errorf("best hit should be the hot one, got %+v", hits[0])
	}
	if hits[1].Tier != TierCold || hits[1].Seq != 2 {
		t.Errorf("second hit should come from object storage, got %+v", hits[1])
	}
	if len(cold.got.ConvIDs) != 1 || cold.got.ConvIDs[0] != id {
		t.Errorf("cold query must be pruned to the tenant's conversations, got %+v", cold.got)
	}
	if cold.got.TenantID != "userA" {
		t.Errorf("cold query must carry the tenant filter, got %q", cold.got.TenantID)
	}
}

func TestSearchDeduplicatesAcrossTiers(t *testing.T) {
	defer withFakeEmbedding([]float32{1, 0})()
	m := configuredManager(t)
	id := seedPair(t, m, msg(1, "same message", []float32{1, 0}, time.Now()))
	m.SetColdSearcher(&fakeCold{hits: []coldstore.Hit{{
		Row:        coldstore.Row{ConvID: id, Seq: 1, Side: "A", Content: "same message"},
		Similarity: 0.5,
	}}})

	hits, err := m.SearchMessages("userA", "问题", 5)
	if err != nil {
		t.Fatal(err)
	}
	if len(hits) != 1 {
		t.Fatalf("overlap during the export window produced %d hits, want 1", len(hits))
	}
	if hits[0].Tier != TierHot {
		t.Errorf("the hot copy should win a duplicate, got %s", hits[0].Tier)
	}
}

func TestSearchSurvivesColdTierFailure(t *testing.T) {
	defer withFakeEmbedding([]float32{1, 0})()
	m := configuredManager(t)
	seedPair(t, m, msg(1, "hot", []float32{1, 0}, time.Now()))
	m.SetColdSearcher(&fakeCold{err: errors.New("object storage unreachable")})

	hits, err := m.SearchMessages("userA", "问题", 5)
	if err != nil {
		t.Fatalf("a cold tier outage must not fail search: %v", err)
	}
	if len(hits) != 1 || hits[0].Tier != TierHot {
		t.Errorf("hot results should still be served, got %+v", hits)
	}
}

func TestSearchStaysHotOnlyWithoutColdSearcher(t *testing.T) {
	defer withFakeEmbedding([]float32{1, 0})()
	m := configuredManager(t)
	seedPair(t, m, msg(1, "hot", []float32{1, 0}, time.Now()))

	hits, err := m.SearchMessages("userA", "问题", 5)
	if err != nil {
		t.Fatal(err)
	}
	if len(hits) != 1 || hits[0].Tier != TierHot {
		t.Errorf("hits = %+v", hits)
	}
}

func TestSearchDoesNotLeakOtherTenantsConversations(t *testing.T) {
	defer withFakeEmbedding([]float32{1, 0})()
	m := configuredManager(t)
	seedPair(t, m, msg(1, "private", []float32{1, 0}, time.Now()))
	cold := &fakeCold{}
	m.SetColdSearcher(cold)

	hits, err := m.SearchMessages("stranger", "问题", 5)
	if err != nil {
		t.Fatal(err)
	}
	if len(hits) != 0 {
		t.Errorf("non-participant got %+v", hits)
	}
	if len(cold.got.ConvIDs) != 0 {
		t.Errorf("cold tier should not be queried for a non-participant: %+v", cold.got)
	}
}

// The manager must satisfy the interfaces the exporter relies on.
func TestManagerImplementsExporterContracts(t *testing.T) {
	var m any = &Manager{}
	if _, ok := m.(coldstore.Source); !ok {
		t.Error("Manager must implement coldstore.Source")
	}
	if _, ok := m.(coldstore.HotTrimmer); !ok {
		t.Error("Manager must implement coldstore.HotTrimmer")
	}
}
