package actions

import (
	 

	"github.com/ceoadmin/CEOadmin/internal/mcp/shared"
	"github.com/ceoadmin/CEOadmin/internal/store"
)

// BillingManager handles billing operations
type BillingManager struct {
	store store.Store
}

func NewBillingManager(s store.Store) *BillingManager {
	return &BillingManager{store: s}
}

func (m *BillingManager) Config(ctx *shared.Context, params map[string]any) (any, error) {
	config, err := m.store.GetBillingConfig(ctx)
	if err != nil {
		return nil, err
	}
	return config, nil
}

func (m *BillingManager) Ledger(ctx *shared.Context, params map[string]any) (any, error) {
	ledger, err := m.store.GetGarlicLedger(ctx, ctx.ClientID)
	if err != nil {
		return nil, err
	}
	return ledger, nil
}

func (m *BillingManager) UploadTicket(ctx *shared.Context, params map[string]any) (any, error) {
	skillID, _ := params["skill_id"].(string)
	if skillID == "" {
		return nil, shared.MissingParam("skill_id")
	}

	ttl := 600
	if t, ok := params["ttl_seconds"].(float64); ok {
		ttl = int(t)
	}

	ticket, err := m.store.CreateUploadTicket(ctx, skillID, ttl)
	if err != nil {
		return nil, err
	}

	return ticket, nil
}

func (m *BillingManager) ConfirmUpload(ctx *shared.Context, params map[string]any) (any, error) {
	ticketID, _ := params["ticket_id"].(string)
	if ticketID == "" {
		return nil, shared.MissingParam("ticket_id")
	}

	success, _ := params["success"].(bool)
	sha256, _ := params["sha256"].(string)
	size, _ := params["size"].(float64)

	used, err := m.store.ConfirmUpload(ctx, ticketID, success, sha256, int64(size))
	if err != nil {
		return nil, err
	}

	return map[string]any{"used": used}, nil
}