package appapi

import (
	"github.com/ceoadmin/CEOadmin/internal/config"
	"github.com/ceoadmin/CEOadmin/internal/registry"
	"github.com/ceoadmin/CEOadmin/internal/store"
)

// AppHandler groups the App marketplace, plugin, registry, marketplace and
// app-OAuth handlers. It holds only the dependencies those handlers use.
type AppHandler struct {
	Config   *config.Config
	Registry *registry.Client
	Store    store.Store
	Version  string
}

// NewAppHandler constructs an AppHandler.
func NewAppHandler(cfg *config.Config, reg *registry.Client, store store.Store, version string) *AppHandler {
	return &AppHandler{
		Config:   cfg,
		Registry: reg,
		Store:    store,
		Version:  version,
	}
}
