// Package modelrank maintains a live health ranking of the chat models
// listed in ai.available_models and automatically steers the platform toward
// the best-performing one.
//
// Behaviour (driven by the operator's requirements):
//   - On startup it PROBES each concrete model exactly once (default/auto
//     aliases are skipped — they are always kept available but never ranked).
//   - Every day it RE-RANKS using the real call records accumulated since the
//     last reset (average latency + failure rate) and rewrites both
//     ai.available_models (best first) and ai.model (best healthy model).
//     Before committing ai.model it runs a lightweight liveness pre-check so a
//     model that went down since the last probe is never auto-selected.
//   - Every OTHER day (48h window) it RESETS the call records and re-probes,
//     then re-ranks from scratch.
//
// Call records are accumulated in memory from every LLM completion (see
// sink.AI.reply) and flushed to system_config only when they change, so they
// survive restarts. No dashboard is provided; the numeric records are the
// source of truth.
package modelrank

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"sort"
	"sync"
	"sync/atomic"
	"time"

	"github.com/ceoadmin/CEOadmin/internal/ai"
	"github.com/ceoadmin/CEOadmin/internal/store"
)

const (
	probePrompt    = "Reply with exactly one word: ok"
	probeTimeout   = 15 * time.Second // per-model timeout for the startup/reset probe
	verifyTimeout  = 12 * time.Second // per-model timeout for liveness pre-checks
	probeWorkers   = 8
	dailyInterval  = 24 * time.Hour
	resetDays      = 2 // reset + re-probe every other day
	flushInterval  = 5 * time.Minute
	statsKey       = "modelrank.stats"
	metaKey        = "modelrank.meta"
	healthFailRate = 0.5 // failure rate at/above this => unhealthy
	latencyPenalty = 1_000_000
	verifyCap      = 4 // max models verified when committing ai.model
)

// alwaysKeep are special gateway aliases that are never probed or ranked; they
// are always present in ai.available_models as safe fallbacks and always pass a
// pre-check.
var alwaysKeep = []string{"default", "auto"}

// modelStat is the accumulated call record for one model since the last reset.
type modelStat struct {
	Calls     int64 `json:"calls"`
	Failures  int64 `json:"failures"`
	LatencyMs int64 `json:"latency_ms"`
	LastUsed  int64 `json:"last_used"`
}

type meta struct {
	WindowStart int64 `json:"window_start"`
	Day         int   `json:"day"`
}

type probeResult struct {
	name string
	ok   bool
	ms   int64
}

// Manager owns the stats and the scheduling loop.
type Manager struct {
	store store.Store

	mu          sync.Mutex
	stats       map[string]*modelStat
	windowStart int64
	day         int

	probing atomic.Bool
	dirty   atomic.Bool

	probeMessages []ai.Message
}

// Default is the process-wide manager, initialised by Init.
var Default *Manager

// Init creates the process-wide manager, resumes any persisted records, runs
// an initial probe if needed, and starts the daily scheduler. Safe to call
// once at startup.
func Init(ctx context.Context, s store.Store) {
	if Default != nil {
		return
	}
	m := &Manager{
		store: s,
		stats: map[string]*modelStat{},
		probeMessages: []ai.Message{
			{Role: "user", Content: probePrompt},
		},
	}
	Default = m
	m.load()

	expired := m.windowStart != 0 && time.Now().Unix()-m.windowStart >= int64(resetDays)*int64(dailyInterval.Seconds())
	if m.windowStart == 0 || expired || len(m.stats) == 0 {
		// No usable records (first run, expired window, or empty) -> probe now.
		m.windowStart = time.Now().Unix()
		m.day = 0
		m.stats = map[string]*modelStat{}
		m.dirty.Store(true)
		go m.probeAndRank(ctx)
	} else {
		// Resume with persisted records; verify the chosen model is still live.
		m.rankAndApply(true)
	}
	go m.run(ctx)
}

// Available reports whether the manager is initialised (pre-checks are live).
func Available() bool { return Default != nil }

// RecordCall is called by the AI sink after every completion to feed the
// ranking. It is a no-op if the manager is not initialised.
func RecordCall(model string, latencyMs int64, ok bool) {
	if Default != nil {
		Default.RecordCall(model, latencyMs, ok)
	}
}

// RecordCall accumulates one completion outcome for model.
func (m *Manager) RecordCall(model string, latencyMs int64, ok bool) {
	if m == nil || model == "" {
		return
	}
	m.mu.Lock()
	st := m.stats[model]
	if st == nil {
		st = &modelStat{}
		m.stats[model] = st
	}
	st.Calls++
	if !ok {
		st.Failures++
	}
	st.LatencyMs += latencyMs
	st.LastUsed = time.Now().Unix()
	m.mu.Unlock()
	m.dirty.Store(true)
}

// IsHealthy reports whether model is currently considered healthy from records
// (aliases are always healthy; unknown models are not).
func IsHealthy(model string) bool {
	if Default == nil {
		return false
	}
	return Default.IsHealthy(model)
}

// IsHealthy reports health from in-memory records.
func (m *Manager) IsHealthy(model string) bool {
	if contains(alwaysKeep, model) {
		return true
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	st := m.stats[model]
	if st == nil || st.Calls == 0 {
		return false
	}
	return float64(st.Failures)/float64(st.Calls) < healthFailRate
}

// Precheck performs a single live availability probe of model. Aliases always
// pass. The result is recorded into the stats so it also feeds the ranking.
func Precheck(ctx context.Context, model string) (ok bool, ms int64, err error) {
	if Default == nil {
		return false, 0, fmt.Errorf("modelrank not initialised")
	}
	return Default.Precheck(ctx, model)
}

// Precheck performs a single live probe (records the result).
func (m *Manager) Precheck(ctx context.Context, model string) (bool, int64, error) {
	if contains(alwaysKeep, model) {
		return true, 0, nil
	}
	cfg := m.readAIConfig()
	if cfg.APIKey == "" {
		return false, 0, fmt.Errorf("no ai.api_key configured")
	}
	ok, ms := m.probeOne(ctx, cfg, model, true, probeTimeout)
	if !ok {
		return false, ms, fmt.Errorf("model %q probe failed", model)
	}
	return true, ms, nil
}

// run is the scheduler loop: daily re-rank (with pre-check), every other day
// reset+re-probe, periodic flush of records.
func (m *Manager) run(ctx context.Context) {
	ticker := time.NewTicker(dailyInterval)
	defer ticker.Stop()
	flush := time.NewTicker(flushInterval)
	defer flush.Stop()
	for {
		select {
		case <-ctx.Done():
			m.forcePersist()
			return
		case <-ticker.C:
			m.day++
			if m.day%resetDays == 0 {
				m.reset(ctx)
			} else {
				m.rankAndApply(true)
			}
			m.persist()
		case <-flush.C:
			m.persist()
		}
	}
}

// reset clears call records, opens a new window, and re-probes.
func (m *Manager) reset(ctx context.Context) {
	slog.Info("modelrank: reset window, clearing records and re-probing")
	m.mu.Lock()
	m.stats = map[string]*modelStat{}
	m.windowStart = time.Now().Unix()
	m.mu.Unlock()
	m.dirty.Store(true)
	m.probeAndRank(ctx)
}

// probeAndRank probes every concrete model once, stores the results, then
// applies the ranking. Skipped if a probe is already in flight.
func (m *Manager) probeAndRank(ctx context.Context) {
	if !m.probing.CompareAndSwap(false, true) {
		return
	}
	defer m.probing.Store(false)

	cfg := m.readAIConfig()
	if cfg.APIKey == "" {
		slog.Warn("modelrank: no ai.api_key configured, skipping probe")
		return
	}
	models := m.concreteModels()
	if len(models) == 0 {
		slog.Warn("modelrank: no concrete models to probe")
		return
	}
	slog.Info("modelrank: probing models", "count", len(models))
	results := m.probe(ctx, cfg, models)

	m.mu.Lock()
	for name, r := range results {
		m.stats[name] = &modelStat{
			Calls:     1,
			Failures:  b2i(!r.ok),
			LatencyMs: r.ms,
		}
	}
	m.mu.Unlock()
	m.dirty.Store(true)

	m.rankAndApply(false)
}

// probe calls each model once with bounded concurrency.
func (m *Manager) probe(ctx context.Context, cfg store.AIConfig, models []string) map[string]probeResult {
	jobs := make(chan string, len(models))
	out := make(chan probeResult, len(models))
	var wg sync.WaitGroup
	for i := 0; i < probeWorkers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for name := range jobs {
				// Pure probe: the result is written once by probeAndRank,
				// avoiding a double-write / concurrent clobber of live stats.
				ok, ms := m.probeOne(ctx, cfg, name, false, probeTimeout)
				out <- probeResult{name: name, ok: ok, ms: ms}
			}
		}()
	}
	go func() {
		for _, n := range models {
			jobs <- n
		}
		close(jobs)
	}()
	go func() {
		wg.Wait()
		close(out)
	}()
	res := make(map[string]probeResult, len(models))
	for r := range out {
		res[r.name] = r
		if !r.ok {
			slog.Warn("modelrank: probe failed", "model", r.name, "ms", r.ms)
		}
	}
	return res
}

// probeOne performs a single live completion for model. When record is true the
// outcome feeds the ranking stats. Aliases always pass without a network call.
// cfg is read by the caller once per batch to avoid repeated DB lookups.
func (m *Manager) probeOne(ctx context.Context, cfg store.AIConfig, name string, record bool, timeout time.Duration) (ok bool, ms int64) {
	if cfg.APIKey == "" {
		return false, 0
	}
	if contains(alwaysKeep, name) {
		return true, 0
	}
	c := cfg
	c.Model = name
	pctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	// Health probes are not tenant traffic — exclude from billing accounting.
	pctx = ai.ContextSystem(pctx)
	start := time.Now()
	_, err := ai.CompleteMessages(pctx, c, m.probeMessages, nil)
	ms = time.Since(start).Milliseconds()
	ok = err == nil
	if record {
		m.RecordCall(name, ms, ok)
	}
	return
}

// rankAndApply computes the ordering and rewrites ai.available_models +
// ai.model in the store. When verify is true the chosen concrete model is
// liveness-checked first (pure check, does not record) and falls back if dead.
func (m *Manager) rankAndApply(verify bool) {
	ordered, healthy, chosen := m.computeRanking()
	if verify && chosen != "default" && len(healthy) > 0 {
		chosen = m.verifyChosen(context.Background(), healthy, chosen)
	}
	if err := m.store.SetConfig("ai.available_models", mustJSON(ordered)); err != nil {
		slog.Error("modelrank: write available_models failed", "err", err)
	}
	if err := m.store.SetConfig("ai.model", chosen); err != nil {
		slog.Error("modelrank: write model failed", "err", err)
	}
	slog.Info("modelrank: applied ranking",
		"model", chosen, "models", len(ordered), "verify", verify)
}

// verifyChosen liveness-checks preferred first, then other healthy models up to
// verifyCap, returning the first that responds. Falls back to "default".
func (m *Manager) verifyChosen(ctx context.Context, healthy []string, preferred string) string {
	cfg := m.readAIConfig()
	seen := map[string]bool{}
	order := append([]string{preferred}, healthy...)
	tried := 0
	for _, name := range order {
		if name == "default" || seen[name] {
			continue
		}
		seen[name] = true
		if tried >= verifyCap {
			break
		}
		tried++
		if ok, _ := m.probeOne(ctx, cfg, name, false, verifyTimeout); ok {
			return name
		}
		slog.Warn("modelrank: chosen model failed liveness pre-check, skipping", "model", name)
	}
	return "default"
}

// computeRanking returns the concrete models ordered best-first (healthy by
// latency/failure rate, then unhealthy), with the always-kept aliases appended,
// plus the healthy model names and the model that should become ai.model (best
// healthy, else "default").
func (m *Manager) computeRanking() (ordered []string, healthy []string, chosen string) {
	m.mu.Lock()
	type entry struct {
		name string
		st   modelStat
	}
	var h, u []entry
	for name, st := range m.stats {
		if contains(alwaysKeep, name) {
			continue
		}
		r := 0.0
		if st.Calls > 0 {
			r = float64(st.Failures) / float64(st.Calls)
		}
		if r < healthFailRate {
			h = append(h, entry{name, *st})
		} else {
			u = append(u, entry{name, *st})
		}
	}
	sort.Slice(h, func(i, j int) bool {
		return score(h[i].st) < score(h[j].st)
	})
	sort.Slice(u, func(i, j int) bool {
		// Worst (highest failure rate, then highest latency) last.
		if rate(u[i].st) != rate(u[j].st) {
			return rate(u[i].st) > rate(u[j].st)
		}
		return u[i].st.LatencyMs > u[j].st.LatencyMs
	})
	ordered = make([]string, 0, len(h)+len(u))
	for _, e := range h {
		ordered = append(ordered, e.name)
	}
	for _, e := range u {
		ordered = append(ordered, e.name)
	}
	healthyNames := make([]string, 0, len(h))
	for _, e := range h {
		healthyNames = append(healthyNames, e.name)
	}
	m.mu.Unlock()

	// Always keep the special aliases, appended after ranked concrete models.
	final := append([]string{}, ordered...)
	for _, k := range alwaysKeep {
		if !contains(final, k) {
			final = append(final, k)
		}
	}

	chosen = "default"
	if len(healthyNames) > 0 {
		chosen = healthyNames[0]
	}
	return final, healthyNames, chosen
}

// score lower is better: penalise failures, penalise unused models.
func score(st modelStat) float64 {
	avg := float64(latencyPenalty)
	if st.Calls > 0 {
		avg = float64(st.LatencyMs) / float64(st.Calls)
	}
	return avg * (1 + 5*rate(st))
}

func rate(st modelStat) float64 {
	if st.Calls > 0 {
		return float64(st.Failures) / float64(st.Calls)
	}
	return 0
}

// concreteModels returns the models in ai.available_models minus the special
// aliases we never probe/rank.
func (m *Manager) concreteModels() []string {
	raw, _ := m.store.GetConfig("ai.available_models")
	var all []string
	if err := json.Unmarshal([]byte(raw), &all); err != nil || len(all) == 0 {
		return nil
	}
	var out []string
	for _, n := range all {
		if n == "" || contains(alwaysKeep, n) {
			continue
		}
		out = append(out, n)
	}
	return out
}

// readAIConfig reads the base_url/api_key used to call the gateway.
func (m *Manager) readAIConfig() store.AIConfig {
	global, _ := m.store.ListConfigByPrefix("ai.")
	var cfg store.AIConfig
	cfg.BaseURL = global["ai.base_url"]
	cfg.APIKey = global["ai.api_key"]
	cfg.Model = global["ai.model"]
	return cfg
}

// load restores persisted records and meta.
func (m *Manager) load() {
	if raw, _ := m.store.GetConfig(metaKey); raw != "" {
		var mt meta
		if json.Unmarshal([]byte(raw), &mt) == nil {
			m.windowStart = mt.WindowStart
			m.day = mt.Day
		}
	}
	if raw, _ := m.store.GetConfig(statsKey); raw != "" {
		var sm map[string]modelStat
		if json.Unmarshal([]byte(raw), &sm) == nil {
			m.stats = make(map[string]*modelStat, len(sm))
			for k, v := range sm {
				st := v
				m.stats[k] = &st
			}
		}
	}
}

// persist writes records + meta back to system_config, but only when they have
// changed since the last flush (avoids needless SQLite writes).
func (m *Manager) persist() {
	if !m.dirty.Load() {
		return
	}
	m.forcePersist()
}

// forcePersist writes records + meta unconditionally.
func (m *Manager) forcePersist() {
	m.mu.Lock()
	sm := make(map[string]modelStat, len(m.stats))
	for k, v := range m.stats {
		sm[k] = *v
	}
	mt := meta{WindowStart: m.windowStart, Day: m.day}
	m.mu.Unlock()
	if b, err := json.Marshal(sm); err == nil {
		m.store.SetConfig(statsKey, string(b))
	}
	if b, err := json.Marshal(mt); err == nil {
		m.store.SetConfig(metaKey, string(b))
	}
	m.dirty.Store(false)
}

func b2i(b bool) int64 {
	if b {
		return 1
	}
	return 0
}

func contains(s []string, v string) bool {
	for _, x := range s {
		if x == v {
			return true
		}
	}
	return false
}

func mustJSON(v any) string {
	b, err := json.Marshal(v)
	if err != nil {
		return "[]"
	}
	return string(b)
}
