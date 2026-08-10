package messageapi

import (
	"github.com/ceoadmin/CEOadmin/internal/bot"
	"github.com/ceoadmin/CEOadmin/internal/storage"
	"github.com/ceoadmin/CEOadmin/internal/store"
)

// MessageHandler groups the message / channel / github-webhook handlers. It holds
// only the dependencies those handlers use.
type MessageHandler struct {
	BotManager  *bot.Manager
	ObjectStore storage.Store
	Store       store.Store
}

// NewMessageHandler constructs a MessageHandler.
func NewMessageHandler(botMgr *bot.Manager, objStore storage.Store, store store.Store) *MessageHandler {
	return &MessageHandler{
		BotManager:  botMgr,
		ObjectStore: objStore,
		Store:       store,
	}
}
