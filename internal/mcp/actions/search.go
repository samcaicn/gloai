package actions

import (
	 
	"time"

	"github.com/ceoadmin/CEOadmin/internal/mcp/shared"
	"github.com/ceoadmin/CEOadmin/internal/store"
)

// SearchManager handles search signals
type SearchManager struct {
	store store.Store
}

func NewSearchManager(s store.Store) *SearchManager {
	return &SearchManager{store: s}
}

func (m *SearchManager) ReportSignals(ctx *shared.Context, params map[string]any) (any, error) {
	clientID := ctx.ClientID
	tenantID := ctx.TenantID

	query, _ := params["query"].(string)
	results, _ := params["results"].([]any)
	clickedResult, _ := params["clicked_result"].(map[string]any)
	dwellTimeMs, _ := params["dwell_time_ms"].(float64)

	record := &store.SearchSignalsReport{
		ClientID:      clientID,
		TenantID:      tenantID,
		Query:         query,
		Results:       results,
		ClickedResult: clickedResult,
		DwellTimeMs:   int64(dwellTimeMs),
		Timestamp:     time.Now().Unix(),
	}

	if err := m.store.ReportSearchSignals(ctx, record); err != nil {
		return nil, err
	}

	return map[string]any{"success": true}, nil
}