package appapi

import "github.com/ceoadmin/CEOadmin/internal/api/shared"

import (
	"encoding/json"
	"log/slog"
	"net/http"
	"net/url"
	"strings"

	"github.com/ceoadmin/CEOadmin/internal/store"
)

// GET /api/admin/registries — list all registry sources
func (s *AppHandler) HandleListRegistries(w http.ResponseWriter, r *http.Request) {
	registries, err := s.Store.ListRegistries()
	if err != nil {
		shared.JSONError(w, "list failed", http.StatusInternalServerError)
		return
	}
	if registries == nil {
		registries = []store.Registry{}
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(registries)
}

// POST /api/admin/registries — add a new registry source
func (s *AppHandler) HandleCreateRegistry(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Name string `json:"name"`
		URL  string `json:"url"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}
	if req.Name == "" || req.URL == "" {
		shared.JSONError(w, "name and url required", http.StatusBadRequest)
		return
	}

	// Normalize URL: parse structurally, strip the well-known registry path
	// suffix, and discard query/fragment so the stored base URL is always clean.
	parsed, err := url.Parse(strings.TrimSpace(req.URL))
	if err != nil || parsed.Host == "" || (parsed.Scheme != "http" && parsed.Scheme != "https") {
		shared.JSONError(w, "invalid url: must be an absolute http(s) URL", http.StatusBadRequest)
		return
	}
	parsed.RawQuery = ""
	parsed.Fragment = ""
	parsed.Path = strings.TrimSuffix(strings.TrimRight(parsed.Path, "/"), "/api/registry/v1/apps.json")
	u := strings.TrimRight(parsed.String(), "/")

	// Re-validate after normalization to catch URLs that become invalid
	// after stripping query params, fragments, and well-known path suffix.
	normalized, err := url.Parse(u)
	if err != nil || normalized.Host == "" || (normalized.Scheme != "http" && normalized.Scheme != "https") {
		shared.JSONError(w, "invalid url after normalization", http.StatusBadRequest)
		return
	}

	reg := &store.Registry{
		Name:    req.Name,
		URL:     u,
		Enabled: true,
	}
	if err := s.Store.CreateRegistry(reg); err != nil {
		shared.JSONError(w, "create failed", http.StatusInternalServerError)
		return
	}

	// Refresh registry client sources
	if err := s.refreshRegistrySources(); err != nil {
		slog.Warn("registry created but failed to refresh sources", "err", err)
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(reg)
}

// PUT /api/admin/registries/{id} — update a registry source (enable/disable)
func (s *AppHandler) HandleUpdateRegistry(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")

	var req struct {
		Enabled *bool `json:"enabled"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}
	if req.Enabled == nil {
		shared.JSONError(w, "enabled field required", http.StatusBadRequest)
		return
	}

	if err := s.Store.UpdateRegistryEnabled(id, *req.Enabled); err != nil {
		shared.JSONError(w, "update failed", http.StatusInternalServerError)
		return
	}

	// Refresh registry client sources
	if err := s.refreshRegistrySources(); err != nil {
		slog.Warn("registry updated but failed to refresh sources", "err", err)
	}

	shared.JSONOK(w)
}

// DELETE /api/admin/registries/{id} — remove a registry source
func (s *AppHandler) HandleDeleteRegistry(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")

	if err := s.Store.DeleteRegistry(id); err != nil {
		shared.JSONError(w, "delete failed", http.StatusInternalServerError)
		return
	}

	// Refresh registry client sources
	if err := s.refreshRegistrySources(); err != nil {
		slog.Warn("registry updated but failed to refresh sources", "err", err)
	}

	shared.JSONOK(w)
}

// refreshRegistrySources reloads registry sources from DB into the registry client.
// Returns an error if the reload fails so callers can decide how to handle it.
func (s *AppHandler) refreshRegistrySources() error {
	if s.Registry == nil {
		return nil
	}
	registries, err := s.Store.ListRegistries()
	if err != nil {
		slog.Error("refreshRegistrySources: failed to list registries", "err", err)
		return err
	}
	var sources []struct{ Name, URL string }
	for _, reg := range registries {
		if reg.Enabled {
			sources = append(sources, struct{ Name, URL string }{Name: reg.Name, URL: reg.URL})
		}
	}
	s.Registry.SetSources(sources)
	return nil
}
