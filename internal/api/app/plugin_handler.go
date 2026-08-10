package appapi

import "github.com/ceoadmin/CEOadmin/internal/api/shared"

import (
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"regexp"
	"strings"
	"time"

	"github.com/ceoadmin/CEOadmin/internal/auth"
	"github.com/ceoadmin/CEOadmin/internal/sink"
	"github.com/ceoadmin/CEOadmin/internal/store"
)

// POST /api/webhook-plugins/submit
func (s *AppHandler) HandleSubmitPlugin(w http.ResponseWriter, r *http.Request) {
	userID := auth.UserIDFromContext(r.Context())

	var req struct {
		GithubURL string `json:"github_url"`
		Script    string `json:"script"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}

	var script, githubURL, commitHash string
	if req.Script != "" {
		script = req.Script
	} else if req.GithubURL != "" {
		rawURL, owner, repo, path, err := parseGithubBlobURL(req.GithubURL)
		if err != nil {
			shared.JSONError(w, err.Error(), http.StatusBadRequest)
			return
		}
		var fetchErr error
		script, fetchErr = fetchURL(rawURL)
		if fetchErr != nil {
			shared.JSONError(w, "failed to fetch script: "+fetchErr.Error(), http.StatusBadGateway)
			return
		}
		githubURL = req.GithubURL
		commitHash, _ = fetchGithubCommitHash(owner, repo, path)
	} else {
		shared.JSONError(w, "github_url or script required", http.StatusBadRequest)
		return
	}

	meta := parsePluginMeta(script)
	if meta.Name == "" {
		shared.JSONError(w, "plugin must have @name in comments", http.StatusBadRequest)
		return
	}

	// Find or create plugin
	plugin, err := s.Store.GetPluginByName(meta.Name)
	if err != nil {
		// New plugin — check name not taken by someone else
		if owner, _ := s.Store.FindPluginOwner(meta.Name); owner != "" && owner != userID {
			shared.JSONError(w, "plugin name already taken by another user", http.StatusConflict)
			return
		}
		plugin, err = s.Store.CreatePlugin(&store.Plugin{
			Name: meta.Name, Namespace: meta.Namespace, Description: meta.Description,
			Author: meta.Author, Icon: meta.Icon, License: meta.License, Homepage: meta.Homepage,
			OwnerID: userID,
		})
		if err != nil {
			shared.JSONError(w, "create plugin failed", http.StatusInternalServerError)
			return
		}
	} else if plugin.OwnerID != userID {
		shared.JSONError(w, "plugin name already taken by another user", http.StatusConflict)
		return
	}

	// Update plugin metadata
	plugin.Description = meta.Description
	plugin.Author = meta.Author
	plugin.Icon = meta.Icon
	plugin.License = meta.License
	plugin.Homepage = meta.Homepage
	plugin.Namespace = meta.Namespace
	s.Store.UpdatePluginMeta(plugin.ID, plugin)

	configSchema, _ := json.Marshal(meta.Config)

	// Cancel all non-approved versions (pending/rejected → superseded)
	s.Store.SupersedeNonApprovedVersions(plugin.ID)

	// Create new version
	ver, err := s.Store.CreatePluginVersion(&store.PluginVersion{
		PluginID: plugin.ID, Version: meta.Version, Changelog: meta.Changelog,
		Script: script, ConfigSchema: configSchema,
		GithubURL: githubURL, CommitHash: commitHash,
		MatchTypes: meta.Match, ConnectDomains: meta.Connect, GrantPerms: strings.Join(meta.Grant, ","),
		TimeoutSec: meta.TimeoutSec,
	})
	if err != nil {
		slog.Error("create version failed", "plugin", plugin.ID, "err", err)
		shared.JSONError(w, "create version failed: "+err.Error(), http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{"plugin_id": plugin.ID, "version_id": ver.ID, "status": "pending"})
}

// POST /api/webhook-plugins/{id}/versions/{vid}/cancel
func (s *AppHandler) HandleCancelVersion(w http.ResponseWriter, r *http.Request) {
	pluginID := r.PathValue("id")
	versionID := r.PathValue("vid")
	userID := auth.UserIDFromContext(r.Context())

	plugin, err := s.Store.GetPlugin(pluginID)
	if err != nil || plugin.OwnerID != userID {
		shared.JSONError(w, "not found or not owner", http.StatusNotFound)
		return
	}

	ver, err := s.Store.GetPluginVersion(versionID)
	if err != nil || ver.PluginID != pluginID {
		shared.JSONError(w, "version not found", http.StatusNotFound)
		return
	}
	if ver.Status != "pending" {
		shared.JSONError(w, "only pending versions can be cancelled", http.StatusBadRequest)
		return
	}

	if err := s.Store.CancelPluginVersion(versionID); err != nil {
		shared.JSONError(w, "cancel failed", http.StatusInternalServerError)
		return
	}
	shared.JSONOK(w)
}

// GET /api/webhook-plugins — list plugins with latest approved version
func (s *AppHandler) HandleListPlugins(w http.ResponseWriter, r *http.Request) {
	status := r.URL.Query().Get("status")

	if status == "pending" {
		// Admin only
		user := s.optionalUser(r)
		if user == nil || !store.IsAdmin(user.Role) {
			shared.JSONError(w, "admin required", http.StatusForbidden)
			return
		}
		versions, err := s.Store.ListPendingVersions()
		if err != nil {
			shared.JSONError(w, "list failed", http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(versions)
		return
	}

	// Default: list plugins with latest approved version
	plugins, err := s.Store.ListPlugins()
	if err != nil {
		slog.Error("list plugins failed", "err", err)
		shared.JSONError(w, "list failed", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(plugins)
}

// GET /api/webhook-plugins/{id} — get plugin detail
func (s *AppHandler) HandleGetPlugin(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	plugin, err := s.Store.GetPlugin(id)
	if err != nil {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return
	}

	// Get latest version info
	var latestVersion *store.PluginVersion
	if plugin.LatestVersionID != "" {
		latestVersion, _ = s.Store.GetPluginVersion(plugin.LatestVersionID)
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"plugin":         plugin,
		"latest_version": latestVersion,
	})
}

// GET /api/webhook-plugins/{id}/versions
func (s *AppHandler) HandlePluginVersions(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	versions, err := s.Store.ListPluginVersions(id)
	if err != nil {
		shared.JSONError(w, "query failed", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(versions)
}

// PUT /api/admin/webhook-plugins/{id}/review — review a version
func (s *AppHandler) HandleReviewPlugin(w http.ResponseWriter, r *http.Request) {
	versionID := r.PathValue("id")
	userID := auth.UserIDFromContext(r.Context())

	var req struct {
		Status string `json:"status"`
		Reason string `json:"reason"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}
	if req.Status != "approved" && req.Status != "rejected" {
		shared.JSONError(w, "status must be approved or rejected", http.StatusBadRequest)
		return
	}

	if err := s.Store.ReviewPluginVersion(versionID, req.Status, userID, req.Reason); err != nil {
		shared.JSONError(w, "update failed", http.StatusInternalServerError)
		return
	}
	shared.JSONOK(w)
}

// DELETE /api/admin/webhook-plugins/{id}
func (s *AppHandler) HandleDeletePlugin(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if err := s.Store.DeletePlugin(id); err != nil {
		shared.JSONError(w, "delete failed", http.StatusInternalServerError)
		return
	}
	shared.JSONOK(w)
}

// POST /api/webhook-plugins/{id}/install — get script for a version
func (s *AppHandler) HandleInstallPlugin(w http.ResponseWriter, r *http.Request) {
	pluginID := r.PathValue("id")
	plugin, err := s.Store.GetPlugin(pluginID)
	if err != nil || plugin.LatestVersionID == "" {
		shared.JSONError(w, "plugin not found or no approved version", http.StatusNotFound)
		return
	}

	ver, err := s.Store.GetPluginVersion(plugin.LatestVersionID)
	if err != nil || ver.Status != "approved" {
		shared.JSONError(w, "no approved version", http.StatusNotFound)
		return
	}

	userID := auth.UserIDFromContext(r.Context())
	s.Store.RecordPluginInstall(pluginID, userID)

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"plugin_id":  pluginID,
		"version_id": ver.ID,
		"version":    ver.Version,
		"script":     ver.Script,
	})
}

// POST /api/webhook-plugins/{id}/install-to-channel
func (s *AppHandler) HandleInstallPluginToChannel(w http.ResponseWriter, r *http.Request) {
	pluginID := r.PathValue("id")
	userID := auth.UserIDFromContext(r.Context())

	plugin, err := s.Store.GetPlugin(pluginID)
	if err != nil || plugin.LatestVersionID == "" {
		shared.JSONError(w, "plugin not found or no approved version", http.StatusNotFound)
		return
	}

	var req struct {
		BotID     string `json:"bot_id"`
		ChannelID string `json:"channel_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.BotID == "" || req.ChannelID == "" {
		shared.JSONError(w, "bot_id and channel_id required", http.StatusBadRequest)
		return
	}

	bot, err := s.Store.GetBot(req.BotID)
	if err != nil || bot.UserID != userID {
		shared.JSONError(w, "bot not found", http.StatusNotFound)
		return
	}
	ch, err := s.Store.GetChannel(req.ChannelID)
	if err != nil || ch.BotID != req.BotID {
		shared.JSONError(w, "channel not found", http.StatusNotFound)
		return
	}

	// Set version ID as plugin_id in webhook config
	ch.WebhookConfig.PluginID = pluginID
	ch.WebhookConfig.VersionID = plugin.LatestVersionID
	ch.WebhookConfig.Script = ""
	if err := s.Store.UpdateChannel(ch.ID, ch.Name, ch.Handle, &ch.FilterRule, &ch.AIConfig, &ch.WebhookConfig, ch.Enabled); err != nil {
		shared.JSONError(w, "update channel failed", http.StatusInternalServerError)
		return
	}

	s.Store.RecordPluginInstall(pluginID, userID)

	ver, _ := s.Store.GetPluginVersion(plugin.LatestVersionID)
	versionStr := ""
	if ver != nil {
		versionStr = ver.Version
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"ok": true, "plugin_id": pluginID, "version_id": plugin.LatestVersionID, "plugin_version": versionStr,
	})
}

// GET /api/me/plugins
func (s *AppHandler) HandleMyPlugins(w http.ResponseWriter, r *http.Request) {
	userID := auth.UserIDFromContext(r.Context())
	plugins, err := s.Store.ListPluginsByOwner(userID)
	if err != nil {
		shared.JSONError(w, "list failed", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(plugins)
}

// POST /api/webhook-plugins/debug/request
func (s *AppHandler) HandleDebugRequest(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Script      string `json:"script"`
		WebhookURL  string `json:"webhook_url"`
		MockMessage struct {
			Sender  string `json:"sender"`
			Content string `json:"content"`
			MsgType string `json:"msg_type"`
		} `json:"mock_message"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Script == "" {
		shared.JSONError(w, "script required", http.StatusBadRequest)
		return
	}
	msg := sink.MockPayload(req.MockMessage.Sender, req.MockMessage.Content, req.MockMessage.MsgType)
	result := sink.DebugRequest(req.Script, msg, req.WebhookURL)
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(result)
}

// POST /api/webhook-plugins/debug/response
func (s *AppHandler) HandleDebugResponse(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Script      string `json:"script"`
		MockMessage struct {
			Sender  string `json:"sender"`
			Content string `json:"content"`
			MsgType string `json:"msg_type"`
		} `json:"mock_message"`
		Response struct {
			Status  int               `json:"status"`
			Headers map[string]string `json:"headers"`
			Body    string            `json:"body"`
		} `json:"response"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Script == "" {
		shared.JSONError(w, "script required", http.StatusBadRequest)
		return
	}
	msg := sink.MockPayload(req.MockMessage.Sender, req.MockMessage.Content, req.MockMessage.MsgType)
	result := sink.DebugResponse(req.Script, msg, &sink.ResData{
		Status: req.Response.Status, Headers: req.Response.Headers, Body: req.Response.Body,
	})
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(result)
}

// optionalUser tries to extract the current user from session cookie.
func (s *AppHandler) optionalUser(r *http.Request) *store.User {
	cookie, err := r.Cookie("session")
	if err != nil {
		return nil
	}
	userID, err := auth.ValidateSession(s.Store, cookie.Value)
	if err != nil {
		return nil
	}
	user, _ := s.Store.GetUserByID(userID)
	return user
}

// --- Helpers ---

var githubBlobRe = regexp.MustCompile(`^https?://github\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.+)$`)

func parseGithubBlobURL(url string) (rawURL, owner, repo, path string, err error) {
	m := githubBlobRe.FindStringSubmatch(url)
	if m == nil {
		return "", "", "", "", fmt.Errorf("invalid  URL")
	}
	owner, repo, branch, path := m[1], m[2], m[3], m[4]
	rawURL = fmt.Sprintf("https://raw.githubusercontent.com/%s/%s/%s/%s", owner, repo, branch, path)
	return rawURL, owner, repo, path, nil
}

func fetchURL(url string) (string, error) {
	client := &http.Client{Timeout: 15 * time.Second}
	resp, err := client.Get(url)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return "", fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	body, err := io.ReadAll(resp.Body)
	return string(body), err
}

func fetchGithubCommitHash(owner, repo, path string) (string, error) {
	url := fmt.Sprintf("https://api.github.com/repos/%s/%s/commits?path=%s&per_page=1", owner, repo, path)
	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Get(url)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	var commits []struct {
		SHA string `json:"sha"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&commits); err != nil || len(commits) == 0 {
		return "", fmt.Errorf("no commits found")
	}
	return commits[0].SHA, nil
}

type pluginMeta struct {
	Name, Description, Author, Version, Namespace, Icon, License, Homepage string
	Match, Connect, Changelog                                              string
	TimeoutSec                                                             int
	Grant                                                                  []string
	Config                                                                 []store.ConfigField
}

var metaRe = regexp.MustCompile(`//\s*@(\w+)\s+(.+)`)

func parsePluginMeta(script string) pluginMeta {
	var meta pluginMeta
	lines := strings.Split(script, "\n")
	inBlock := false
	hasBlock := strings.Contains(script, "==WebhookPlugin==")
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if hasBlock {
			if strings.Contains(trimmed, "// ==WebhookPlugin==") || strings.Contains(trimmed, "// ==/WebhookPlugin==") {
				inBlock = !inBlock
				continue
			}
			if !inBlock {
				continue
			}
		}
		m := metaRe.FindStringSubmatch(trimmed)
		if m == nil {
			continue
		}
		key, val := m[1], strings.TrimSpace(m[2])
		switch key {
		case "name":
			meta.Name = val
		case "description":
			meta.Description = val
		case "author":
			meta.Author = val
		case "version":
			meta.Version = val
		case "namespace":
			meta.Namespace = val
		case "icon":
			meta.Icon = val
		case "license":
			meta.License = val
		case "homepage":
			meta.Homepage = val
		case "match":
			meta.Match = val
		case "connect":
			meta.Connect = val
		case "changelog":
			meta.Changelog = val
		case "timeout":
			fmt.Sscanf(val, "%d", &meta.TimeoutSec)
		case "grant":
			for _, g := range strings.Split(val, ",") {
				if g = strings.TrimSpace(g); g != "" {
					meta.Grant = append(meta.Grant, g)
				}
			}
		case "config":
			parts := strings.SplitN(val, " ", 3)
			if len(parts) >= 2 {
				desc := ""
				if len(parts) == 3 {
					desc = strings.Trim(parts[2], `"`)
				}
				meta.Config = append(meta.Config, store.ConfigField{Name: parts[0], Type: parts[1], Description: desc})
			}
		}
	}
	if meta.Version == "" {
		meta.Version = "1.0.0"
	}
	if meta.Match == "" {
		meta.Match = "*"
	}
	if meta.Connect == "" {
		meta.Connect = "*"
	}
	return meta
}
