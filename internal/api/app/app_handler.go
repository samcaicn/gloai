package appapi

import "github.com/ceoadmin/CEOadmin/internal/api/shared"

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"log/slog"
	"net/http"
	"net/url"
	"regexp"
	"sort"
	"strings"

	"github.com/ceoadmin/CEOadmin/internal/auth"
	"github.com/ceoadmin/CEOadmin/internal/store"
)

// nameToSlug converts an app name to a URL-friendly slug.
// Non-ASCII and non-alphanumeric characters are replaced with hyphens.
// If the result is too short (< 3 chars), a random suffix is appended.
func nameToSlug(name string) string {
	s := strings.ToLower(strings.TrimSpace(name))
	var b strings.Builder
	for _, r := range s {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') {
			b.WriteRune(r)
		} else {
			b.WriteByte('-')
		}
	}
	// Collapse consecutive hyphens and trim
	slug := regexp.MustCompile(`-+`).ReplaceAllString(b.String(), "-")
	slug = strings.Trim(slug, "-")
	if len(slug) > 40 {
		slug = slug[:40]
		slug = strings.TrimRight(slug, "-")
	}
	// Pad short slugs with random suffix so they pass the 3-char minimum
	if len(slug) < 3 {
		rnd := make([]byte, 4)
		rand.Read(rnd)
		suffix := hex.EncodeToString(rnd)
		if slug == "" {
			slug = "app-" + suffix
		} else {
			slug = slug + "-" + suffix
		}
	}
	return slug
}

func buildListingSnapshot(app *store.App) string {
	snap := map[string]any{
		"tools":         app.Tools,
		"events":        app.Events,
		"scopes":        app.Scopes,
		"webhook_url":   app.WebhookURL,
		"config_schema": app.ConfigSchema,
		"version":       app.Version,
	}
	b, _ := json.Marshal(snap)
	return string(b)
}

func normalizeJSON(raw json.RawMessage) json.RawMessage {
	if len(bytes.TrimSpace(raw)) == 0 {
		return nil
	}
	var v any
	if err := json.Unmarshal(raw, &v); err != nil {
		return bytes.TrimSpace(raw)
	}
	normalized, err := json.Marshal(normalizeJSONValue(v))
	if err != nil {
		return bytes.TrimSpace(raw)
	}
	return normalized
}

func jsonRawEqual(a, b json.RawMessage) bool {
	return bytes.Equal(normalizeJSON(a), normalizeJSON(b))
}

func normalizeJSONValue(v any) any {
	switch value := v.(type) {
	case map[string]any:
		out := make(map[string]any, len(value))
		for key, item := range value {
			out[key] = normalizeJSONValue(item)
		}
		return out
	case []any:
		if len(value) == 0 {
			return []any{}
		}
		if values, ok := normalizeStringSet(value); ok {
			return values
		}
		out := make([]any, len(value))
		for i, item := range value {
			out[i] = normalizeJSONValue(item)
		}
		return out
	default:
		return value
	}
}

func normalizeStringSet(items []any) ([]string, bool) {
	values := make([]string, 0, len(items))
	for _, item := range items {
		s, ok := item.(string)
		if !ok {
			return nil, false
		}
		values = append(values, s)
	}
	sort.Strings(values)
	out := values[:0]
	for _, value := range values {
		if len(out) > 0 && out[len(out)-1] == value {
			continue
		}
		out = append(out, value)
	}
	return out, true
}

func normalizeCollectionJSON(raw json.RawMessage) json.RawMessage {
	trimmed := bytes.TrimSpace(raw)
	if len(trimmed) == 0 || bytes.Equal(trimmed, []byte("null")) {
		return []byte("[]")
	}

	var v any
	if err := json.Unmarshal(trimmed, &v); err != nil {
		return trimmed
	}
	if v == nil {
		return []byte("[]")
	}
	normalized, err := json.Marshal(normalizeJSONValue(v))
	if err != nil {
		return trimmed
	}
	return normalized
}

func jsonCollectionEqual(a, b json.RawMessage) bool {
	return bytes.Equal(normalizeCollectionJSON(a), normalizeCollectionJSON(b))
}

func (s *AppHandler) transitionAppAwayFromListed(appID, nextListing string) error {
	if err := s.Store.TransitionListingWithCleanup(appID, nextListing, ""); err != nil {
		slog.Error("failed to transition app listing with cleanup", "app_id", appID, "to", nextListing, "err", err)
		return err
	}
	return nil
}

// POST /api/apps
func (s *AppHandler) HandleCreateApp(w http.ResponseWriter, r *http.Request) {
	userID := auth.UserIDFromContext(r.Context())

	var req struct {
		Name             string          `json:"name"`
		Slug             string          `json:"slug"`
		Description      string          `json:"description"`
		Icon             string          `json:"icon"`
		IconURL          string          `json:"icon_url"`
		Homepage         string          `json:"homepage"`
		OAuthSetupURL    string          `json:"oauth_setup_url"`
		OAuthRedirectURL string          `json:"oauth_redirect_url"`
		Tools            json.RawMessage `json:"tools"`
		Events           json.RawMessage `json:"events"`
		Scopes           json.RawMessage `json:"scopes"`
		Version          string          `json:"version"`
		Readme           string          `json:"readme"`
		Guide            string          `json:"guide"`
		ConfigSchema     string          `json:"config_schema"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}
	if req.Name == "" {
		shared.JSONError(w, "name required", http.StatusBadRequest)
		return
	}

	// Validate or generate slug
	slug := strings.ToLower(strings.TrimSpace(req.Slug))
	if slug == "" {
		slug = nameToSlug(req.Name)
	}
	if !shared.SlugRe.MatchString(slug) {
		shared.JSONError(w, "slug must be 3-40 chars, lowercase alphanumeric and hyphens, e.g. my-app", http.StatusBadRequest)
		return
	}

	// Check slug uniqueness among local apps (registry="")
	if existing, _ := s.Store.GetAppBySlug(slug, ""); existing != nil {
		shared.JSONError(w, "slug already taken", http.StatusConflict)
		return
	}

	if req.Homepage != "" {
		// Allow relative sub-paths on the main site (e.g. /apps/my-app),
		// which are reverse-proxied and served same-origin.
		if !strings.HasPrefix(req.Homepage, "/") {
			u, err := url.ParseRequestURI(req.Homepage)
			if err != nil || (u.Scheme != "http" && u.Scheme != "https") {
				shared.JSONError(w, "homepage must be a valid http/https URL or a relative path starting with /", http.StatusBadRequest)
				return
			}
		}
	}

	app, err := s.Store.CreateApp(&store.App{
		OwnerID:          userID,
		Name:             req.Name,
		Slug:             slug,
		Description:      req.Description,
		Icon:             req.Icon,
		IconURL:          req.IconURL,
		Homepage:         req.Homepage,
		OAuthSetupURL:    req.OAuthSetupURL,
		OAuthRedirectURL: req.OAuthRedirectURL,
		Tools:            req.Tools,
		Events:           req.Events,
		Scopes:           req.Scopes,
		Version:          req.Version,
		Readme:           req.Readme,
		Guide:            req.Guide,
		ConfigSchema:     req.ConfigSchema,
	})
	if err != nil {
		shared.JSONError(w, "create failed", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(app)
}

// GET /api/apps?listing=listed — public marketplace; otherwise my apps
func (s *AppHandler) HandleListApps(w http.ResponseWriter, r *http.Request) {
	userID := auth.UserIDFromContext(r.Context())
	var apps []store.App
	var err error

	if r.URL.Query().Get("listing") == "listed" {
		apps, err = s.Store.ListListedApps()
	} else {
		apps, err = s.Store.ListAppsByOwner(userID)
	}
	if err != nil {
		slog.Error("list apps failed", "err", err)
		shared.JSONError(w, "list failed: "+err.Error(), http.StatusInternalServerError)
		return
	}
	if apps == nil {
		apps = []store.App{}
	}

	// Annotate with installation status for the current user.
	installedIDs, err := s.Store.InstalledAppIDs(userID)
	if err != nil {
		slog.Warn("failed to load installed app IDs", "user_id", userID, "err", err)
	}

	type appEntry struct {
		store.App
		Installed bool `json:"installed"`
	}
	result := make([]appEntry, len(apps))
	for i, a := range apps {
		result[i] = appEntry{App: a, Installed: installedIDs[a.ID]}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(result)
}

// GET /api/apps/{id}
func (s *AppHandler) HandleGetApp(w http.ResponseWriter, r *http.Request) {
	userID := auth.UserIDFromContext(r.Context())
	appID := r.PathValue("id")

	app, err := s.Store.GetApp(appID)
	if err != nil {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return
	}
	// Owner can see everything; others can see listed apps or apps they have installed
	if app.OwnerID != userID {
		if app.Listing != "listed" && !s.userHasInstallation(userID, appID) {
			shared.JSONError(w, "not found", http.StatusNotFound)
			return
		}
		// Secret is already hidden via json:"-" on store.App
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(app)
		return
	}

	// Owner sees everything including secrets
	type appWithSecret struct {
		*store.App
		WebhookSecret string `json:"webhook_secret,omitempty"`
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(appWithSecret{App: app, WebhookSecret: app.WebhookSecret})
}

// PUT /api/apps/{id}
func (s *AppHandler) HandleUpdateApp(w http.ResponseWriter, r *http.Request) {
	userID := auth.UserIDFromContext(r.Context())
	appID := r.PathValue("id")

	app, err := s.Store.GetApp(appID)
	if err != nil {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return
	}
	if app.OwnerID != userID {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return
	}

	// Marketplace apps cannot be edited directly
	if app.Registry != "" {
		shared.JSONError(w, "marketplace apps cannot be edited", http.StatusForbidden)
		return
	}

	var req struct {
		Name             *string         `json:"name"`
		Description      *string         `json:"description"`
		Icon             *string         `json:"icon"`
		IconURL          *string         `json:"icon_url"`
		Homepage         *string         `json:"homepage"`
		OAuthSetupURL    *string         `json:"oauth_setup_url"`
		OAuthRedirectURL *string         `json:"oauth_redirect_url"`
		WebhookURL       *string         `json:"webhook_url"`
		Tools            json.RawMessage `json:"tools"`
		Events           json.RawMessage `json:"events"`
		Scopes           json.RawMessage `json:"scopes"`
		ConfigSchema     *string         `json:"config_schema"`
		Version          *string         `json:"version"`
		Readme           *string         `json:"readme"`
		Guide            *string         `json:"guide"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}

	// During review, only allow cosmetic updates (readme, description, icon).
	// Core changes require withdrawing the listing request first.
	if app.Listing == "pending" {
		if req.WebhookURL != nil || req.Tools != nil || req.Events != nil || req.Scopes != nil || req.ConfigSchema != nil || req.Version != nil {
			shared.JSONError(w, "cannot modify core fields while listing is pending review. Withdraw the request first or wait for review.", http.StatusForbidden)
			return
		}
	}

	name := app.Name
	if req.Name != nil {
		name = *req.Name
	}
	description := app.Description
	if req.Description != nil {
		description = *req.Description
	}
	icon := app.Icon
	if req.Icon != nil {
		icon = *req.Icon
	}
	iconURL := app.IconURL
	if req.IconURL != nil {
		iconURL = *req.IconURL
	}
	homepage := app.Homepage
	if req.Homepage != nil {
		if *req.Homepage != "" && !strings.HasPrefix(*req.Homepage, "/") {
			u, err := url.ParseRequestURI(*req.Homepage)
			if err != nil || (u.Scheme != "http" && u.Scheme != "https") {
				shared.JSONError(w, "homepage must be a valid http/https URL or a relative path starting with /", http.StatusBadRequest)
				return
			}
		}
		homepage = *req.Homepage
	}
	oauthSetupURL := app.OAuthSetupURL
	if req.OAuthSetupURL != nil {
		oauthSetupURL = *req.OAuthSetupURL
	}
	oauthRedirectURL := app.OAuthRedirectURL
	if req.OAuthRedirectURL != nil {
		oauthRedirectURL = *req.OAuthRedirectURL
	}
	configSchema := app.ConfigSchema
	if req.ConfigSchema != nil {
		configSchema = *req.ConfigSchema
	}
	version := app.Version
	if req.Version != nil {
		version = *req.Version
	}
	readme := app.Readme
	if req.Readme != nil {
		readme = *req.Readme
	}
	guide := app.Guide
	if req.Guide != nil {
		guide = *req.Guide
	}
	tools := app.Tools
	if req.Tools != nil {
		tools = req.Tools
	}
	events := app.Events
	if req.Events != nil {
		events = req.Events
	}
	scopes := app.Scopes
	if req.Scopes != nil {
		scopes = req.Scopes
	}

	webhookURL := app.WebhookURL
	if req.WebhookURL != nil {
		webhookURL = *req.WebhookURL
	}

	coreChanged := false
	if req.WebhookURL != nil && *req.WebhookURL != app.WebhookURL {
		coreChanged = true
	}
	if req.Tools != nil && !jsonCollectionEqual(req.Tools, app.Tools) {
		coreChanged = true
	}
	if req.Events != nil && !jsonCollectionEqual(req.Events, app.Events) {
		coreChanged = true
	}
	if req.Scopes != nil && !jsonCollectionEqual(req.Scopes, app.Scopes) {
		coreChanged = true
	}
	if req.ConfigSchema != nil && !jsonRawEqual(json.RawMessage(*req.ConfigSchema), json.RawMessage(app.ConfigSchema)) {
		coreChanged = true
	}
	if req.Version != nil && *req.Version != app.Version {
		coreChanged = true
	}

	nextListing := ""
	if app.Listing == "listed" && coreChanged {
		nextListing = "pending"
	}

	result, err := s.Store.UpdateAppWithTransition(appID, store.AppUpdate{
		Name:             name,
		Description:      description,
		Icon:             icon,
		IconURL:          iconURL,
		Homepage:         homepage,
		OAuthSetupURL:    oauthSetupURL,
		OAuthRedirectURL: oauthRedirectURL,
		WebhookURL:       webhookURL,
		ConfigSchema:     configSchema,
		Version:          version,
		Readme:           readme,
		Guide:            guide,
		Tools:            tools,
		Events:           events,
		Scopes:           scopes,
	}, nextListing)
	if err != nil {
		shared.JSONError(w, "update failed", http.StatusInternalServerError)
		return
	}

	if result.Transitioned {
		slog.Info("listed app core fields changed, reverted to pending", "app", appID)
		updatedApp, _ := s.Store.GetApp(appID)
		if updatedApp != nil {
			if err := s.Store.CreateAppReview(&store.AppReview{
				AppID:    appID,
				Action:   "auto_revert",
				ActorID:  "system",
				Version:  updatedApp.Version,
				Snapshot: buildListingSnapshot(updatedApp),
			}); err != nil {
				slog.Warn("failed to create app review record", "app", appID, "action", "auto_revert", "err", err)
			}
		}
	}

	shared.JSONOK(w)
}

// DELETE /api/apps/{id}
func (s *AppHandler) HandleDeleteApp(w http.ResponseWriter, r *http.Request) {
	userID := auth.UserIDFromContext(r.Context())
	appID := r.PathValue("id")

	app, err := s.Store.GetApp(appID)
	if err != nil {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return
	}
	if app.OwnerID != userID {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return
	}

	if err := s.Store.DeleteApp(appID); err != nil {
		shared.JSONError(w, "delete failed", http.StatusInternalServerError)
		return
	}
	shared.JSONOK(w)
}

// POST /api/apps/{id}/request-listing — owner requests to list their app
func (s *AppHandler) HandleRequestListing(w http.ResponseWriter, r *http.Request) {
	app := s.requireApp(w, r)
	if app == nil {
		return
	}
	if app.Listing == "listed" {
		shared.JSONError(w, "already listed", http.StatusBadRequest)
		return
	}
	if app.Listing == "pending" {
		shared.JSONError(w, "already pending review", http.StatusBadRequest)
		return
	}

	// Validate required fields before submitting for review.
	var errors []string
	if app.Name == "" {
		errors = append(errors, "name is required")
	}
	if app.Description == "" {
		errors = append(errors, "description is required")
	}
	if app.Readme == "" {
		errors = append(errors, "readme is required")
	}
	if app.Version == "" {
		errors = append(errors, "version is required")
	}
	// Apps need at least one way to receive events: webhook_url or OAuth (setup_url for WS/PKCE)
	if app.WebhookURL == "" && app.OAuthSetupURL == "" && app.Registry != "builtin" {
		errors = append(errors, "webhook_url or oauth_setup_url is required")
	}
	// Must have events or tools.
	hasEvents := len(app.Events) > 0 && string(app.Events) != "[]" && string(app.Events) != "null"
	hasTools := len(app.Tools) > 0 && string(app.Tools) != "[]" && string(app.Tools) != "null"
	if !hasEvents && !hasTools {
		errors = append(errors, "at least one event subscription or tool is required")
	}
	// Must have scopes.
	hasScopes := len(app.Scopes) > 0 && string(app.Scopes) != "[]" && string(app.Scopes) != "null"
	if !hasScopes {
		errors = append(errors, "at least one scope is required")
	}

	if len(errors) > 0 {
		shared.JSONError(w, "cannot submit for listing: "+strings.Join(errors, "; "), http.StatusBadRequest)
		return
	}

	if err := s.Store.RequestListing(app.ID); err != nil {
		shared.JSONError(w, "request failed", http.StatusInternalServerError)
		return
	}
	if err := s.Store.CreateAppReview(&store.AppReview{
		AppID:    app.ID,
		Action:   "request",
		ActorID:  auth.UserIDFromContext(r.Context()),
		Version:  app.Version,
		Snapshot: buildListingSnapshot(app),
	}); err != nil {
		slog.Warn("failed to create app review record", "app", app.ID, "action", "request", "err", err)
	}
	shared.JSONOK(w)
}

// POST /api/apps/{id}/withdraw-listing — owner withdraws a pending listing request
func (s *AppHandler) HandleWithdrawListing(w http.ResponseWriter, r *http.Request) {
	app := s.requireApp(w, r)
	if app == nil {
		return
	}
	if app.Listing != "pending" {
		shared.JSONError(w, "not pending", http.StatusBadRequest)
		return
	}
	if err := s.Store.WithdrawListing(app.ID); err != nil {
		shared.JSONError(w, "withdraw failed", http.StatusInternalServerError)
		return
	}
	if err := s.Store.CreateAppReview(&store.AppReview{
		AppID:    app.ID,
		Action:   "withdraw",
		ActorID:  auth.UserIDFromContext(r.Context()),
		Version:  app.Version,
		Snapshot: buildListingSnapshot(app),
	}); err != nil {
		slog.Warn("failed to create app review record", "app", app.ID, "action", "withdraw", "err", err)
	}
	shared.JSONOK(w)
}

// PUT /api/admin/apps/{id}/review-listing — admin approves/rejects listing
func (s *AppHandler) HandleReviewListing(w http.ResponseWriter, r *http.Request) {
	appID := r.PathValue("id")
	var req struct {
		Approve bool   `json:"approve"`
		Reason  string `json:"reason"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}
	if !req.Approve && req.Reason == "" {
		shared.JSONError(w, "reason required for rejection", http.StatusBadRequest)
		return
	}
	app, err := s.Store.GetApp(appID)
	if err != nil {
		shared.JSONError(w, "app not found", http.StatusNotFound)
		return
	}
	if req.Approve {
		err = s.Store.ReviewListing(appID, true, "")
	} else {
		err = s.Store.TransitionListingWithCleanup(appID, "rejected", req.Reason)
	}
	if err != nil {
		shared.JSONError(w, "review failed", http.StatusInternalServerError)
		return
	}
	app, _ = s.Store.GetApp(appID)
	actorID := auth.UserIDFromContext(r.Context())
	action := "approve"
	if !req.Approve {
		action = "reject"
	}
	if app != nil {
		if err := s.Store.CreateAppReview(&store.AppReview{
			AppID:    appID,
			Action:   action,
			ActorID:  actorID,
			Reason:   req.Reason,
			Version:  app.Version,
			Snapshot: buildListingSnapshot(app),
		}); err != nil {
			slog.Warn("failed to create app review record", "app", appID, "action", action, "err", err)
		}
	}
	shared.JSONOK(w)
}

// GET /api/admin/apps — list all apps (admin only)
func (s *AppHandler) HandleAdminListApps(w http.ResponseWriter, r *http.Request) {
	apps, err := s.Store.ListAllApps()
	if err != nil {
		shared.JSONError(w, "list failed", http.StatusInternalServerError)
		return
	}
	if apps == nil {
		apps = []store.App{}
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(apps)
}

// userHasInstallation checks if the user owns any bot that has this app installed.
func (s *AppHandler) userHasInstallation(userID, appID string) bool {
	installations, err := s.Store.ListInstallationsByApp(appID)
	if err != nil {
		return false
	}
	for _, inst := range installations {
		bot, err := s.Store.GetBot(inst.BotID)
		if err == nil && bot.UserID == userID {
			return true
		}
	}
	return false
}

// requireApp loads an app by path ID and verifies ownership.
// Returns the app or nil (with error already written to w).
func (s *AppHandler) requireApp(w http.ResponseWriter, r *http.Request) *store.App {
	userID := auth.UserIDFromContext(r.Context())
	appID := r.PathValue("id")

	app, err := s.Store.GetApp(appID)
	if err != nil {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return nil
	}
	if app.OwnerID != userID {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return nil
	}
	return app
}

// requireAppForInstall loads an app that the user can install:
// either they own it, or it's publicly listed.
func (s *AppHandler) requireAppForInstall(w http.ResponseWriter, r *http.Request) *store.App {
	userID := auth.UserIDFromContext(r.Context())
	appID := r.PathValue("id")

	app, err := s.Store.GetApp(appID)
	if err != nil {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return nil
	}
	// Admin can access all apps; otherwise must be owner or listed
	user, _ := s.Store.GetUserByID(userID)
	isAdmin := user != nil && store.IsAdmin(user.Role)
	if !isAdmin && app.OwnerID != userID && app.Listing != "listed" {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return nil
	}
	return app
}

// PUT /api/admin/apps/{id}/listing — admin directly sets listing status
// PUT /api/admin/apps/{id}/listing — admin directly sets listing status (listed/unlisted).
// This is an admin privilege bypass of the normal review flow, intended for
// moderation actions (e.g. emergency takedown, re-listing without re-review).
func (s *AppHandler) HandleAdminSetListing(w http.ResponseWriter, r *http.Request) {
	appID := r.PathValue("id")
	var req struct {
		Listing  string   `json:"listing"`
		Price    *float64 `json:"price"`
		Currency string   `json:"currency"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}
	app, err := s.Store.GetApp(appID)
	if err != nil {
		shared.JSONError(w, "app not found", http.StatusNotFound)
		return
	}
	// Admin may configure price/currency for paid apps.
	if req.Price != nil {
		if err := s.Store.UpdateAppPrice(appID, *req.Price, req.Currency); err != nil {
			slog.Error("set app price failed", "err", err)
			shared.JSONError(w, "set price failed", http.StatusInternalServerError)
			return
		}
		slog.Info("admin set app price", "app_id", appID, "price", *req.Price, "currency", req.Currency)
	}
	if req.Listing != "" {
		validListings := map[string]bool{"listed": true, "unlisted": true}
		if !validListings[req.Listing] {
			shared.JSONError(w, "listing must be 'listed' or 'unlisted'", http.StatusBadRequest)
			return
		}
		if err := s.transitionAppAwayFromListed(appID, req.Listing); err != nil {
		slog.Error("set listing failed", "err", err)
		shared.JSONError(w, "set listing failed", http.StatusInternalServerError)
		return
	}
		slog.Info("admin set listing", "app_id", appID, "listing", req.Listing)
	}
	app, _ = s.Store.GetApp(appID)
	if app != nil {
		if err := s.Store.CreateAppReview(&store.AppReview{
			AppID:    appID,
			Action:   "admin_set",
			ActorID:  auth.UserIDFromContext(r.Context()),
			Reason:   req.Listing,
			Version:  app.Version,
			Snapshot: buildListingSnapshot(app),
		}); err != nil {
			slog.Warn("failed to create app review record", "app", appID, "action", "admin_set", "err", err)
		}
	}
	shared.JSONOK(w)
}

// GET /api/apps/{id}/reviews
func (s *AppHandler) HandleListAppReviews(w http.ResponseWriter, r *http.Request) {
	userID := auth.UserIDFromContext(r.Context())
	appID := r.PathValue("id")

	app, err := s.Store.GetApp(appID)
	if err != nil {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return
	}

	// Only owner or admin can view review history
	user, _ := s.Store.GetUserByID(userID)
	isAdmin := user != nil && store.IsAdmin(user.Role)
	if app.OwnerID != userID && !isAdmin {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return
	}

	reviews, err := s.Store.ListAppReviews(appID)
	if err != nil {
		shared.JSONError(w, "list reviews failed", http.StatusInternalServerError)
		return
	}
	if reviews == nil {
		reviews = []store.AppReview{}
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(reviews)
}

// requireInstallation loads an installation by path IID and verifies it belongs to the app
// and the current user owns the bot.
func (s *AppHandler) requireInstallation(w http.ResponseWriter, r *http.Request, appID string) *store.AppInstallation {
	userID := auth.UserIDFromContext(r.Context())
	iid := r.PathValue("iid")

	inst, err := s.Store.GetInstallation(iid)
	if err != nil {
		shared.JSONError(w, "installation not found", http.StatusNotFound)
		return nil
	}
	if inst.AppID != appID {
		shared.JSONError(w, "installation not found", http.StatusNotFound)
		return nil
	}

	// Verify: admin, app owner, or bot owner
	user, _ := s.Store.GetUserByID(userID)
	isAdmin := user != nil && store.IsAdmin(user.Role)
	app, _ := s.Store.GetApp(appID)
	isAppOwner := app != nil && app.OwnerID == userID
	if !isAdmin && !isAppOwner {
		bot, err := s.Store.GetBot(inst.BotID)
		if err != nil || bot.UserID != userID {
			shared.JSONError(w, "installation not found", http.StatusNotFound)
			return nil
		}
	}
	return inst
}

// POST /api/apps/{id}/purchase — record a purchase/entitlement for the current
// user. This is the stand-in for real payment: when a payment gateway is wired
// later, this endpoint is where the confirmed order creates the entitlement.
// Paid apps require this record before installation (see HandleInstallApp).
func (s *AppHandler) HandlePurchaseApp(w http.ResponseWriter, r *http.Request) {
	userID := auth.UserIDFromContext(r.Context())
	appID := r.PathValue("id")
	app, err := s.Store.GetApp(appID)
	if err != nil || app == nil {
		shared.JSONError(w, "app not found", http.StatusNotFound)
		return
	}
	p, err := s.Store.CreateAppPurchase(appID, userID)
	if err != nil {
		slog.Error("purchase app failed", "app", appID, "user", userID, "err", err)
		shared.JSONError(w, "purchase failed", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(p)
}
