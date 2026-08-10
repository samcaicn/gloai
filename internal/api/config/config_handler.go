package configapi

import "github.com/ceoadmin/CEOadmin/internal/api/shared"

import (
	"encoding/json"
	"log/slog"
	"net/http"
	"strconv"

	"github.com/ceoadmin/CEOadmin/internal/ai"
	"github.com/ceoadmin/CEOadmin/internal/store"
)

// Supported OAuth provider names for validation.

// GET /api/admin/config/oauth — get OAuth config (secrets masked)
func (s *ConfigHandler) HandleGetOAuthConfig(w http.ResponseWriter, r *http.Request) {
	dbConf, err := s.Store.ListConfigByPrefix("oauth.")
	if err != nil {
		shared.JSONError(w, "query failed", http.StatusInternalServerError)
		return
	}

	type providerConfig struct {
		ClientID     string `json:"client_id"`
		ClientSecret string `json:"client_secret"`
		Enabled      bool   `json:"enabled"`
		Source       string `json:"source"` // "db" or "env"
	}

	result := map[string]*providerConfig{}
	for name := range shared.OAuthProviderDefs {
		pc := &providerConfig{}

		// Check DB first
		if id := dbConf["oauth."+name+".client_id"]; id != "" {
			pc.ClientID = id
			pc.ClientSecret = shared.MaskSecret(dbConf["oauth."+name+".client_secret"])
			pc.Enabled = true
			pc.Source = "db"
		} else {
			// Check env fallback
			var envID, envSecret string
			switch name {
			case "github":
				envID = s.Config.ClientID
				envSecret = s.Config.ClientSecret
			case "linuxdo":
				envID = s.Config.LinuxDoClientID
				envSecret = s.Config.LinuxDoClientSecret
			}
			if envID != "" {
				pc.ClientID = envID
				pc.ClientSecret = shared.MaskSecret(envSecret)
				pc.Enabled = true
				pc.Source = "env"
			}
		}

		result[name] = pc
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(result)
}

// PUT /api/admin/config/oauth/{provider} — set OAuth config for a provider
func (s *ConfigHandler) HandleSetOAuthConfig(w http.ResponseWriter, r *http.Request) {
	name := r.PathValue("provider")
	if !shared.KnownOAuthProviders[name] {
		shared.JSONError(w, "unknown provider", http.StatusBadRequest)
		return
	}

	var req struct {
		ClientID     string `json:"client_id"`
		ClientSecret string `json:"client_secret"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}

	if req.ClientID == "" {
		shared.JSONError(w, "client_id required", http.StatusBadRequest)
		return
	}

	if err := s.Store.SetConfig("oauth."+name+".client_id", req.ClientID); err != nil {
		shared.JSONError(w, "save failed", http.StatusInternalServerError)
		return
	}
	if req.ClientSecret != "" {
		current, _ := s.Store.GetConfig("oauth." + name + ".client_secret")
		if req.ClientSecret != shared.MaskSecret(current) {
			if err := s.Store.SetConfig("oauth."+name+".client_secret", req.ClientSecret); err != nil {
				shared.JSONError(w, "save failed", http.StatusInternalServerError)
				return
			}
		}
	}
	shared.JSONOK(w)
}

// DELETE /api/admin/config/oauth/{provider} — remove OAuth config (revert to env)
func (s *ConfigHandler) HandleDeleteOAuthConfig(w http.ResponseWriter, r *http.Request) {
	name := r.PathValue("provider")
	if !shared.KnownOAuthProviders[name] {
		shared.JSONError(w, "unknown provider", http.StatusBadRequest)
		return
	}

	s.Store.DeleteConfig("oauth." + name + ".client_id")
	s.Store.DeleteConfig("oauth." + name + ".client_secret")
	shared.JSONOK(w)
}

// GET /api/config/ai/available_models — public endpoint returning the configured model list
func (s *ConfigHandler) HandleGetAvailableModels(w http.ResponseWriter, r *http.Request) {
	dbConf, _ := s.Store.ListConfigByPrefix("ai.")
	raw := dbConf["ai.available_models"]
	if raw == "" || !json.Valid([]byte(raw)) {
		raw = "[]"
	}
	w.Header().Set("Content-Type", "application/json")
	w.Write([]byte(raw))
}

// GET /api/admin/config/ai — get global AI config
func (s *ConfigHandler) HandleGetAIConfig(w http.ResponseWriter, r *http.Request) {
	dbConf, err := s.Store.ListConfigByPrefix("ai.")
	if err != nil {
		shared.JSONError(w, "query failed", http.StatusInternalServerError)
		return
	}
	result := map[string]string{
		"base_url":         dbConf["ai.base_url"],
		"api_key":          shared.MaskSecret(dbConf["ai.api_key"]),
		"model":            dbConf["ai.model"],
		"system_prompt":    dbConf["ai.system_prompt"],
		"max_history":      dbConf["ai.max_history"],
		"hide_thinking":    dbConf["ai.hide_thinking"],
		"strip_markdown":   dbConf["ai.strip_markdown"],
		"available_models": dbConf["ai.available_models"],
		"custom_headers":   dbConf["ai.custom_headers"],
		"image_model":      dbConf["ai.image_model"],
		"video_model":      dbConf["ai.video_model"],
		"audio_model":      dbConf["ai.audio_model"],
	}
	if dbConf["ai.api_key"] != "" {
		result["enabled"] = "true"
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(result)
}

// PUT /api/admin/config/ai — set global AI config
func (s *ConfigHandler) HandleSetAIConfig(w http.ResponseWriter, r *http.Request) {
	var req struct {
		BaseURL         string  `json:"base_url"`
		APIKey          string  `json:"api_key"`
		Model           string  `json:"model"`
		SystemPrompt    string  `json:"system_prompt"`
		MaxHistory      string  `json:"max_history"`
		HideThinking    string  `json:"hide_thinking"`
		StripMarkdown   string  `json:"strip_markdown"`
		AvailableModels string  `json:"available_models"`
		CustomHeaders   *string `json:"custom_headers"`
		ImageModel      string  `json:"image_model"`
		VideoModel      string  `json:"video_model"`
		AudioModel      string  `json:"audio_model"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}

	if req.BaseURL != "" {
		s.Store.SetConfig("ai.base_url", req.BaseURL)
	}
	if req.APIKey != "" {
		current, _ := s.Store.GetConfig("ai.api_key")
		if req.APIKey != shared.MaskSecret(current) {
			s.Store.SetConfig("ai.api_key", req.APIKey)
		}
	}
	if req.Model != "" {
		s.Store.SetConfig("ai.model", req.Model)
	}
	// These can be set to empty to clear
	s.Store.SetConfig("ai.system_prompt", req.SystemPrompt)
	if req.MaxHistory != "" {
		s.Store.SetConfig("ai.max_history", req.MaxHistory)
	}
	if req.HideThinking != "" {
		s.Store.SetConfig("ai.hide_thinking", req.HideThinking)
	}
	if req.StripMarkdown != "" {
		s.Store.SetConfig("ai.strip_markdown", req.StripMarkdown)
	}
	if req.AvailableModels != "" {
		s.Store.SetConfig("ai.available_models", req.AvailableModels)
	}
	if req.CustomHeaders != nil {
		if *req.CustomHeaders == "" {
			s.Store.DeleteConfig("ai.custom_headers")
		} else if !json.Valid([]byte(*req.CustomHeaders)) {
			shared.JSONError(w, "custom_headers must be valid JSON", http.StatusBadRequest)
			return
		} else {
			s.Store.SetConfig("ai.custom_headers", *req.CustomHeaders)
		}
	}
	// Media-generation model identifiers (reuse the global OpenAI-compatible
	// base_url / api_key; only the per-type model name is stored here).
	if req.ImageModel != "" {
		s.Store.SetConfig("ai.image_model", req.ImageModel)
	}
	if req.VideoModel != "" {
		s.Store.SetConfig("ai.video_model", req.VideoModel)
	}
	if req.AudioModel != "" {
		s.Store.SetConfig("ai.audio_model", req.AudioModel)
	}
	shared.JSONOK(w)
}

// DELETE /api/admin/config/ai — remove global AI config
func (s *ConfigHandler) HandleDeleteAIConfig(w http.ResponseWriter, r *http.Request) {
	s.Store.DeleteConfig("ai.base_url")
	s.Store.DeleteConfig("ai.api_key")
	s.Store.DeleteConfig("ai.model")
	s.Store.DeleteConfig("ai.system_prompt")
	s.Store.DeleteConfig("ai.max_history")
	s.Store.DeleteConfig("ai.hide_thinking")
	s.Store.DeleteConfig("ai.strip_markdown")
	s.Store.DeleteConfig("ai.available_models")
	s.Store.DeleteConfig("ai.custom_headers")
	s.Store.DeleteConfig("ai.image_model")
	s.Store.DeleteConfig("ai.video_model")
	s.Store.DeleteConfig("ai.audio_model")
	shared.JSONOK(w)
}

// POST /api/admin/config/ai/fetch-models — fetch the model list from the
// provider's OpenAI-compatible /models endpoint. The caller may supply
// base_url / api_key / custom_headers; when omitted the saved global AI config
// is used instead.
func (s *ConfigHandler) HandleFetchAIModels(w http.ResponseWriter, r *http.Request) {
	var req struct {
		BaseURL       string            `json:"base_url"`
		APIKey        string            `json:"api_key"`
		CustomHeaders map[string]string `json:"custom_headers"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}

	if req.BaseURL == "" || req.APIKey == "" {
		dbConf, _ := s.Store.ListConfigByPrefix("ai.")
		if req.BaseURL == "" {
			req.BaseURL = dbConf["ai.base_url"]
		}
		if req.APIKey == "" {
			req.APIKey = dbConf["ai.api_key"]
		}
		if len(req.CustomHeaders) == 0 {
			if raw := dbConf["ai.custom_headers"]; raw != "" {
				var stored map[string]string
				if json.Unmarshal([]byte(raw), &stored) == nil {
					req.CustomHeaders = stored
				}
			}
		}
	}
	if req.BaseURL == "" || req.APIKey == "" {
		shared.JSONError(w, "base_url 和 api_key 不能为空", http.StatusBadRequest)
		return
	}

	models, err := ai.ListModels(r.Context(), req.BaseURL, req.APIKey, req.CustomHeaders)
	if err != nil {
		shared.JSONError(w, "获取模型失败："+err.Error(), http.StatusBadGateway)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{"models": models})
}

// GET /api/info — public endpoint to check which features are available
func (s *ConfigHandler) HandleInfo(w http.ResponseWriter, r *http.Request) {
	globalAI, _ := s.Store.ListConfigByPrefix("ai.")
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"ai":                   globalAI["ai.api_key"] != "",
		"storage":              s.Config.StorageEndpoint != "",
		"registration_enabled": shared.RegistrationEnabled(s.Store),
		"version":              s.Version,
	})
}

// registrationEnabled returns true if public registration is allowed.
// Default is enabled (key absent or != "false").

// scanLoginRole returns the role assigned to a newly created user via iLink
// scan login. Defaults to member (普通租户). Invalid/missing values fall back
// to member. The first user is always superadmin (bootstrap), handled at the
// call site.

// GET /api/admin/config/registration — get registration config
func (s *ConfigHandler) HandleGetRegistrationConfig(w http.ResponseWriter, r *http.Request) {
	enabled, err := s.Store.GetConfig("registration.enabled")
	if err != nil {
		slog.Error("failed to get registration config", "err", err)
	}
	// Default to "true" when key is absent
	if enabled == "" {
		enabled = "true"
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{
		"enabled": enabled,
	})
}

// PUT /api/admin/config/registration — set registration config
func (s *ConfigHandler) HandleSetRegistrationConfig(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Enabled string `json:"enabled"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}
	if req.Enabled != "true" && req.Enabled != "false" {
		shared.JSONError(w, "enabled must be 'true' or 'false'", http.StatusBadRequest)
		return
	}
	if err := s.Store.SetConfig("registration.enabled", req.Enabled); err != nil {
		shared.JSONError(w, "save failed", http.StatusInternalServerError)
		return
	}
	shared.JSONOK(w)
}

// GET /api/admin/config/scan_login_role — get the default role for iLink scan-login users
func (s *ConfigHandler) HandleGetScanLoginRoleConfig(w http.ResponseWriter, r *http.Request) {
	role, err := s.Store.GetConfig("scan_login.role")
	if err != nil {
		slog.Error("failed to get scan_login role config", "err", err)
	}
	if role == "" {
		role = store.RoleMember
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{
		"role": role,
	})
}

// PUT /api/admin/config/scan_login_role — set the default role for iLink scan-login users
func (s *ConfigHandler) HandleSetScanLoginRoleConfig(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Role string `json:"role"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}
	if !store.IsValidRole(req.Role) {
		shared.JSONError(w, "role must be one of: superadmin, admin, developer, member", http.StatusBadRequest)
		return
	}
	if err := s.Store.SetConfig("scan_login.role", req.Role); err != nil {
		shared.JSONError(w, "save failed", http.StatusInternalServerError)
		return
	}
	shared.JSONOK(w)
}

// GET /api/admin/config/registry — get registry config
func (s *ConfigHandler) HandleGetRegistryConfig(w http.ResponseWriter, r *http.Request) {
	enabled, err := s.Store.GetConfig("registry.enabled")
	if err != nil {
		slog.Error("failed to get registry config", "err", err)
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{
		"enabled": enabled,
	})
}

// PUT /api/admin/config/registry — set registry config
func (s *ConfigHandler) HandleSetRegistryConfig(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Enabled string `json:"enabled"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}
	if req.Enabled != "true" && req.Enabled != "false" {
		shared.JSONError(w, "enabled must be 'true' or 'false'", http.StatusBadRequest)
		return
	}
	if err := s.Store.SetConfig("registry.enabled", req.Enabled); err != nil {
		shared.JSONError(w, "save failed", http.StatusInternalServerError)
		return
	}
	shared.JSONOK(w)
}

// GET /api/admin/llm-usage — aggregated LLM token usage for per-tenant billing.
// Query params: tenant, model, model_type, from, to (unix seconds), limit.
func (s *ConfigHandler) HandleListLLMUsage(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	filter := store.UsageFilter{
		TenantID:  q.Get("tenant"),
		Model:     q.Get("model"),
		ModelType: q.Get("model_type"),
	}
	if v := q.Get("from"); v != "" {
		if n, err := strconv.ParseInt(v, 10, 64); err == nil {
			filter.From = n
		}
	}
	if v := q.Get("to"); v != "" {
		if n, err := strconv.ParseInt(v, 10, 64); err == nil {
			filter.To = n
		}
	}
	if v := q.Get("limit"); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			filter.Limit = n
		}
	}

	agg, err := s.Store.ListLLMUsageAgg(filter)
	if err != nil {
		slog.Error("list llm usage failed", "err", err)
		shared.JSONError(w, "query failed", http.StatusInternalServerError)
		return
	}
	if agg == nil {
		agg = []store.UsageAggregate{}
	}

	// Grand totals across the (filtered) result set.
	var totals store.UsageAggregate
	for _, a := range agg {
		totals.PromptTokens += a.PromptTokens
		totals.CompletionTokens += a.CompletionTokens
		totals.TotalTokens += a.TotalTokens
		totals.CachedTokens += a.CachedTokens
		totals.ReasoningTokens += a.ReasoningTokens
		totals.CallCount += a.CallCount
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"rows":   agg,
		"totals": totals,
	})
}

// GET /api/admin/media-usage — aggregated media-generation usage (image / video /
// audio) for per-tenant billing. Query params: tenant, model, media_type, from,
// to (unix seconds), limit.
func (s *ConfigHandler) HandleListMediaUsage(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	filter := store.MediaUsageFilter{
		TenantID:  q.Get("tenant"),
		Model:     q.Get("model"),
		MediaType: store.MediaType(q.Get("media_type")),
	}
	if v := q.Get("from"); v != "" {
		if n, err := strconv.ParseInt(v, 10, 64); err == nil {
			filter.From = n
		}
	}
	if v := q.Get("to"); v != "" {
		if n, err := strconv.ParseInt(v, 10, 64); err == nil {
			filter.To = n
		}
	}
	if v := q.Get("limit"); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			filter.Limit = n
		}
	}

	agg, err := s.Store.ListMediaUsageAgg(filter)
	if err != nil {
		slog.Error("list media usage failed", "err", err)
		shared.JSONError(w, "query failed", http.StatusInternalServerError)
		return
	}
	if agg == nil {
		agg = []store.MediaUsageAggregate{}
	}

	var totals store.MediaUsageAggregate
	for _, a := range agg {
		totals.Count += a.Count
		totals.DurationSeconds += a.DurationSeconds
		totals.CallCount += a.CallCount
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"rows":   agg,
		"totals": totals,
	})
}
