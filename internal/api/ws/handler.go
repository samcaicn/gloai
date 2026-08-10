package wsapi

import (
	"github.com/ceoadmin/CEOadmin/internal/bot"
	"github.com/ceoadmin/CEOadmin/internal/config"
	"github.com/ceoadmin/CEOadmin/internal/push"
	"github.com/ceoadmin/CEOadmin/internal/relay"
	"github.com/ceoadmin/CEOadmin/internal/store"
)

// WSHandler groups the WebSocket / push handlers. It holds only the dependencies
// those handlers use.
type WSHandler struct {
	BotManager *bot.Manager
	Config     *config.Config
	Hub        *relay.Hub
	PushHub    *push.Hub
	Store      store.Store
}

// NewWSHandler constructs a WSHandler.
func NewWSHandler(botMgr *bot.Manager, cfg *config.Config, hub *relay.Hub, pushHub *push.Hub, store store.Store) *WSHandler {
	return &WSHandler{
		BotManager: botMgr,
		Config:     cfg,
		Hub:        hub,
		PushHub:    pushHub,
		Store:      store,
	}
}
