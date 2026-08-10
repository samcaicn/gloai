package configapi

import (
	"github.com/ceoadmin/CEOadmin/internal/config"
	"github.com/ceoadmin/CEOadmin/internal/store"
)

// ConfigHandler groups the admin/system-config and public info handlers. It holds
// only the dependencies those handlers use.
type ConfigHandler struct {
	Config  *config.Config
	Store   store.Store
	Version string
}

// NewConfigHandler constructs a ConfigHandler.
func NewConfigHandler(cfg *config.Config, store store.Store, version string) *ConfigHandler {
	return &ConfigHandler{
		Config:  cfg,
		Store:   store,
		Version: version,
	}
}
