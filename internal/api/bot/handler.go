package botapi

import (
	"github.com/ceoadmin/CEOadmin/internal/app"
	"github.com/ceoadmin/CEOadmin/internal/bot"
	"github.com/ceoadmin/CEOadmin/internal/relay"
	"github.com/ceoadmin/CEOadmin/internal/storage"
	"github.com/ceoadmin/CEOadmin/internal/store"
)

// BotHandler groups the bot-management and bot-API handlers. It receives only
// the dependencies those handlers use.
type BotHandler struct {
	AppWSHub    *app.WSHub
	BotManager  *bot.Manager
	Hub         *relay.Hub
	ObjectStore storage.Store
	Store       store.Store
	Version     string
}

// NewBotHandler constructs a BotHandler.
func NewBotHandler(appWSHub *app.WSHub, botMgr *bot.Manager, hub *relay.Hub, objStore storage.Store, store store.Store, version string) *BotHandler {
	return &BotHandler{
		AppWSHub:    appWSHub,
		BotManager:  botMgr,
		Hub:         hub,
		ObjectStore: objStore,
		Store:       store,
		Version:     version,
	}
}
