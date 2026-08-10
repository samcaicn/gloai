package store

import (
	"path/filepath"
	"testing"
)

func sampleUsage(tenant, model string, pt, ct, tt, dur int) *LLMUsageRecord {
	return &LLMUsageRecord{
		TenantID:         tenant,
		ChannelID:        "",
		Model:            model,
		ModelType:        "chat",
		PromptTokens:     pt,
		CompletionTokens: ct,
		TotalTokens:      tt,
		DurationMS:       int64(dur),
	}
}

func TestRecordAndAggregateUsage(t *testing.T) {
	for _, mode := range []string{"sqlite", "file"} {
		t.Run(mode, func(t *testing.T) {
			st, err := Open(mode, filepath.Join(t.TempDir(), "u.db"), t.TempDir())
			if err != nil {
				t.Fatalf("open %s: %v", mode, err)
			}
			defer st.Close()

			recs := []*LLMUsageRecord{
				sampleUsage("t1", "gpt-4o-mini", 10, 5, 15, 100),
				sampleUsage("t1", "gpt-4o-mini", 20, 10, 30, 200),
				sampleUsage("t1", "gpt-4o", 8, 4, 12, 50),
				sampleUsage("t2", "gpt-4o-mini", 3, 1, 4, 10),
			}
			for _, r := range recs {
				if err := st.RecordLLMUsage(r); err != nil {
					t.Fatalf("record: %v", err)
				}
			}

			all, err := st.ListLLMUsageAgg(UsageFilter{Limit: 100})
			if err != nil {
				t.Fatalf("aggregate all: %v", err)
			}
			if len(all) != 3 {
				t.Fatalf("want 3 aggregated rows, got %d: %+v", len(all), all)
			}

			// 校验 t1/gpt-4o-mini 的聚合求和
			var t1mini *LLMUsageAgg
			for i := range all {
				if all[i].TenantID == "t1" && all[i].Model == "gpt-4o-mini" {
					t1mini = &all[i]
				}
			}
			if t1mini == nil {
				t.Fatal("missing t1/gpt-4o-mini aggregate")
			}
			if t1mini.CallCount != 2 || t1mini.PromptTokens != 30 || t1mini.CompletionTokens != 15 || t1mini.TotalTokens != 45 {
				t.Fatalf("t1/gpt-4o-mini aggregate wrong: %+v", t1mini)
			}
			if t1mini.DurationMS != 300 {
				t.Fatalf("t1/gpt-4o-mini duration aggregate wrong: %+v", t1mini)
			}

			// 按租户过滤
			justT1, err := st.ListLLMUsageAgg(UsageFilter{TenantID: "t1"})
			if err != nil {
				t.Fatalf("aggregate t1: %v", err)
			}
			if len(justT1) != 2 {
				t.Fatalf("want 2 rows for t1, got %d", len(justT1))
			}
		})
	}
}
