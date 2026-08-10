package modelrank

import (
	"testing"
)

func TestComputeRanking(t *testing.T) {
	m := &Manager{
		stats: map[string]*modelStat{
			// healthy, fast
			"fast-good": {Calls: 10, Failures: 0, LatencyMs: 500},
			// healthy, slow
			"slow-good": {Calls: 10, Failures: 1, LatencyMs: 2000},
			// unhealthy (high failure rate)
			"broken": {Calls: 10, Failures: 9, LatencyMs: 800},
			// unused-but-probed: healthy (rate 0) but high latency -> sinks last
			"unused": {Calls: 1, Failures: 0, LatencyMs: 999999},
			// always-kept alias should be ignored in ranking
			"default": {Calls: 100, Failures: 0, LatencyMs: 1},
		},
	}

	ordered, healthy, chosen := m.computeRanking()

	// best healthy first
	if chosen != "fast-good" {
		t.Fatalf("chosen = %q, want fast-good", chosen)
	}
	if len(healthy) != 3 {
		t.Fatalf("healthy count = %d, want 3", len(healthy))
	}
	if ordered[0] != "fast-good" {
		t.Fatalf("ordered[0] = %q, want fast-good", ordered[0])
	}
	// slow-good (healthy) before broken (unhealthy)
	idxSlow, idxBroken := indexOf(ordered, "slow-good"), indexOf(ordered, "broken")
	if idxSlow < 0 || idxBroken < 0 || idxSlow > idxBroken {
		t.Fatalf("healthy should precede unhealthy: %v", ordered)
	}
	// default/auto aliases are appended after the ranked concrete models.
	idxDefault := indexOf(ordered, "default")
	idxAuto := indexOf(ordered, "auto")
	if idxDefault < 0 || idxAuto < 0 {
		t.Fatalf("aliases must be present: %v", ordered)
	}
	if idxDefault <= idxBroken {
		t.Fatalf("default alias must come after concrete models: %v", ordered)
	}
	if idxAuto != len(ordered)-1 {
		t.Fatalf("auto alias should be last, got %v", ordered)
	}
	// unused must not outrank the known-good model
	idxUnused := indexOf(ordered, "unused")
	if idxUnused >= 0 && idxUnused < idxSlow {
		t.Fatalf("unused should not outrank healthy models: %v", ordered)
	}
}

func TestComputeRankingAllUnhealthy(t *testing.T) {
	m := &Manager{
		stats: map[string]*modelStat{
			"a": {Calls: 10, Failures: 10, LatencyMs: 100},
			"b": {Calls: 5, Failures: 5, LatencyMs: 200},
		},
	}
	ordered, _, chosen := m.computeRanking()
	// No healthy model -> fall back to "default" alias.
	if chosen != "default" {
		t.Fatalf("chosen = %q, want default fallback", chosen)
	}
	if len(ordered) < 2 {
		t.Fatalf("expected concrete models preserved: %v", ordered)
	}
}

func TestIsHealthy(t *testing.T) {
	m := &Manager{
		stats: map[string]*modelStat{
			"good": {Calls: 5, Failures: 0},
			"bad":  {Calls: 5, Failures: 5},
		},
	}
	if !m.IsHealthy("good") {
		t.Fatal("good should be healthy")
	}
	if m.IsHealthy("bad") {
		t.Fatal("bad (100% failures) should be unhealthy")
	}
	if !m.IsHealthy("default") {
		t.Fatal("default alias must be healthy")
	}
	if !m.IsHealthy("auto") {
		t.Fatal("auto alias must be healthy")
	}
	if m.IsHealthy("unknown") {
		t.Fatal("unknown model must not be healthy")
	}
}

func indexOf(s []string, v string) int {
	for i, x := range s {
		if x == v {
			return i
		}
	}
	return -1
}
