package tenantchatapi

import "github.com/ceoadmin/CEOadmin/internal/store"

// TenantChatHandler groups the 甲乙方 AI 对聊 handlers. It holds only the
// dependency those handlers use.
type TenantChatHandler struct {
	Store store.Store
}

// NewTenantChatHandler constructs a TenantChatHandler.
func NewTenantChatHandler(store store.Store) *TenantChatHandler {
	return &TenantChatHandler{Store: store}
}
