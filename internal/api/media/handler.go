package mediaapi

import (
	"github.com/ceoadmin/CEOadmin/internal/bot"
	"github.com/ceoadmin/CEOadmin/internal/storage"
	"github.com/ceoadmin/CEOadmin/internal/store"
)

// MediaHandler groups the media-proxy / channel-media handlers. It holds only the
// dependencies those handlers use.
type MediaHandler struct {
	BotManager  *bot.Manager
	ObjectStore storage.Store
	Store       store.Store
}

// NewMediaHandler constructs a MediaHandler.
func NewMediaHandler(botMgr *bot.Manager, objStore storage.Store, store store.Store) *MediaHandler {
	return &MediaHandler{
		BotManager:  botMgr,
		ObjectStore: objStore,
		Store:       store,
	}
}
