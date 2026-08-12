package api

import (
	"log/slog"
	"net/http"
	"net/http/httputil"
	"net/url"

	"github.com/ceoadmin/CEOadmin/internal/api/app"
	"github.com/ceoadmin/CEOadmin/internal/api/auth"
	"github.com/ceoadmin/CEOadmin/internal/api/bot"
	"github.com/ceoadmin/CEOadmin/internal/api/config"
	"github.com/ceoadmin/CEOadmin/internal/api/media"
	"github.com/ceoadmin/CEOadmin/internal/api/message"
	"github.com/ceoadmin/CEOadmin/internal/api/skill"
	tenantapi "github.com/ceoadmin/CEOadmin/internal/api/tenant"
	"github.com/ceoadmin/CEOadmin/internal/api/tenantchat"
	"github.com/ceoadmin/CEOadmin/internal/api/ws"
	"github.com/ceoadmin/CEOadmin/internal/app"
	"github.com/ceoadmin/CEOadmin/internal/auth"
	"github.com/ceoadmin/CEOadmin/internal/bot"
	"github.com/ceoadmin/CEOadmin/internal/config"
	"github.com/ceoadmin/CEOadmin/internal/mcp"
	"github.com/ceoadmin/CEOadmin/internal/push"
	"github.com/ceoadmin/CEOadmin/internal/registry"
	"github.com/ceoadmin/CEOadmin/internal/relay"
	"github.com/ceoadmin/CEOadmin/internal/storage"
	"github.com/ceoadmin/CEOadmin/internal/store"
	"github.com/ceoadmin/CEOadmin/internal/web"
	"github.com/go-webauthn/webauthn/webauthn"
)

// Server is the dependency container for the HTTP API. It holds the shared
// dependencies (store, config, registry, etc.) and delegates request handling to
// the per-domain handler groups in internal/api/*, which receive only the
// dependencies they actually use.
type Server struct {
	Store        store.Store
	WebAuthn     *webauthn.WebAuthn
	SessionStore *auth.SessionStore
	BotManager   *bot.Manager
	Hub          *relay.Hub
	Config       *config.Config
	OAuthStates  *authapi.OAuthStateStore
	ObjectStore  storage.Store // optional
	SkillStorage storage.Store // optional; falls back to ObjectStore
	Registry     *registry.Client
	AppWSHub     *app.WSHub
	PushHub      *push.Hub
	Version      string
}

func cors(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		origin := r.Header.Get("Origin")
		if origin != "" {
			w.Header().Set("Access-Control-Allow-Origin", origin)
			w.Header().Set("Access-Control-Allow-Credentials", "true")
			w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS")
			w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")
		}
		if r.Method == "OPTIONS" {
			w.WriteHeader(204)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()

	// Per-domain handler groups, each constructed with only its own dependencies.
	authH := authapi.NewAuthHandler(s.Store, s.BotManager, s.Config, s.OAuthStates, s.SessionStore, s.WebAuthn)
	botH := botapi.NewBotHandler(s.AppWSHub, s.BotManager, s.Hub, s.ObjectStore, s.Store, s.Version)
	appH := appapi.NewAppHandler(s.Config, s.Registry, s.Store, s.Version)
	msgH := messageapi.NewMessageHandler(s.BotManager, s.ObjectStore, s.Store)
	skillH := skillapi.NewSkillHandler(s.ObjectStore, s.SkillStorage, s.Store)
	cfgH := configapi.NewConfigHandler(s.Config, s.Store, s.Version)
	wsH := wsapi.NewWSHandler(s.BotManager, s.Config, s.Hub, s.PushHub, s.Store)
	tcH := tenantchatapi.NewTenantChatHandler(s.Store)
	mediaH := mediaapi.NewMediaHandler(s.BotManager, s.ObjectStore, s.Store)
	tenantBindH := tenantapi.NewTenantBindHandler(s.Store)

	// --- Public auth ---
	mux.HandleFunc("POST /api/auth/register", authH.HandlePasswordRegister)
	mux.HandleFunc("POST /api/auth/login", authH.HandlePasswordLogin)
	mux.HandleFunc("POST /api/auth/passkey/register/begin", authH.HandleRegisterBegin)
	mux.HandleFunc("POST /api/auth/passkey/register/finish", authH.HandleRegisterFinish)
	mux.HandleFunc("POST /api/auth/passkey/login/begin", authH.HandleLoginBegin)
	mux.HandleFunc("POST /api/auth/passkey/login/finish", authH.HandleLoginFinish)
	mux.HandleFunc("POST /api/auth/logout", authH.HandleLogout)

	// --- OAuth ---
	mux.HandleFunc("GET /api/auth/oauth/providers", authH.HandleOAuthProviders)
	mux.HandleFunc("GET /api/auth/oauth/{provider}", authH.HandleOAuthRedirect)
	mux.HandleFunc("GET /api/auth/oauth/{provider}/callback", authH.HandleOAuthCallback)

	// --- OIDC (independent routes for custom identity providers) ---
	mux.HandleFunc("GET /api/auth/oidc/{slug}", authH.HandleOIDCLogin)
	mux.HandleFunc("GET /api/auth/oidc/{slug}/callback", authH.HandleOIDCCallback)

	// --- iLink scan login: scan QR to register + login + bind bot ---
	mux.HandleFunc("POST /api/auth/scan/start", authH.HandleScanLoginStart)
	mux.HandleFunc("GET /api/auth/scan/status/{sessionID}", authH.HandleScanLoginStatus)

	// --- Public info ---
	mux.HandleFunc("GET /api/info", cfgH.HandleInfo)

	// --- Webhook plugins (public: list approved) ---
	mux.HandleFunc("GET /api/webhook-plugins", appH.HandleListPlugins)
	mux.HandleFunc("GET /api/webhook-plugins/{id}", appH.HandleGetPlugin)
	mux.HandleFunc("GET /api/webhook-plugins/{id}/versions", appH.HandlePluginVersions)

	// --- OAuth complete (popup callback page, no auth needed) ---
	mux.HandleFunc("GET /oauth/complete", appH.HandleOAuthComplete)

	// --- Media proxy (serves MinIO files through Hub) ---
	mux.HandleFunc("GET /api/v1/media/", mediaH.HandleMediaProxy)

	// --- Channel API (api_key auth) ---
	mux.HandleFunc("GET /api/v1/channels/connect", wsH.HandleWebSocket)
	mux.HandleFunc("GET /api/v1/channels/messages", msgH.HandleChannelMessages)
	mux.HandleFunc("POST /api/v1/channels/send", msgH.HandleChannelSend)
	mux.HandleFunc("POST /api/v1/channels/typing", msgH.HandleChannelTyping)
	mux.HandleFunc("POST /api/v1/channels/config", msgH.HandleChannelConfig)
	mux.HandleFunc("GET /api/v1/channels/status", msgH.HandleChannelStatus)
	mux.HandleFunc("GET /api/v1/channels/media", mediaH.HandleChannelMedia)

	// --- github webhook (public, token-authenticated) ---
	mux.HandleFunc("POST /api/hooks/github", msgH.HandleWebhook)

	// --- Registry public endpoints ---
	mux.HandleFunc("GET /api/registry/v1/apps.json", appH.HandleRegistryApps)
	mux.HandleFunc("GET /api/registry/v1/skills.json", skillH.HandleRegistrySkills)
	mux.HandleFunc("GET /api/registry/v1/skills/{slug}/download", skillH.HandleRegistrySkillDownload)

	// --- Protected routes ---
	protected := http.NewServeMux()

	// Push WebSocket (browser real-time events)
	protected.HandleFunc("GET /api/ws", wsH.HandlePushWebSocket)

	// Profile
	protected.HandleFunc("GET /api/me", authH.HandleMe)
	protected.HandleFunc("PUT /api/me/profile", authH.HandleUpdateProfile)
	protected.HandleFunc("PUT /api/me/username", authH.HandleUpdateUsername)
	protected.HandleFunc("PUT /api/me/password", authH.HandleChangePassword)

	// My plugins
	protected.HandleFunc("GET /api/me/plugins", appH.HandleMyPlugins)

	// Passkey binding (authenticated)
	protected.HandleFunc("GET /api/me/passkeys", authH.HandleListPasskeys)
	protected.HandleFunc("POST /api/me/passkeys/register/begin", authH.HandlePasskeyBindBegin)
	protected.HandleFunc("POST /api/me/passkeys/register/finish", authH.HandlePasskeyBindFinish)
	protected.HandleFunc("DELETE /api/me/passkeys/{id}", authH.HandleDeletePasskey)
	protected.HandleFunc("PATCH /api/me/passkeys/{id}", authH.HandleRenamePasskey)

	// OAuth account binding (authenticated)
	protected.HandleFunc("GET /api/me/linked-accounts", authH.HandleOAuthAccounts)
	protected.HandleFunc("GET /api/me/linked-accounts/{provider}/bind", authH.HandleOAuthBind)
	protected.HandleFunc("DELETE /api/me/linked-accounts/{provider}", authH.HandleOAuthUnbind)
	protected.HandleFunc("GET /api/me/oidc/{slug}/bind", authH.HandleOIDCBind)

	// Bots
	protected.HandleFunc("GET /api/bots", botH.HandleListBots)
	protected.HandleFunc("POST /api/bots/bind/start", botH.HandleBindStart)
	protected.HandleFunc("GET /api/bots/bind/status/{sessionID}", botH.HandleBindStatus)
	protected.HandleFunc("POST /api/bots/{id}/reconnect", botH.HandleReconnect)
	protected.HandleFunc("DELETE /api/bots/{id}", botH.HandleDeleteBot)

	// Webhook logs
	protected.HandleFunc("GET /api/bots/{id}/webhook-logs", msgH.HandleWebhookLogs)
	protected.HandleFunc("GET /api/bots/{id}/traces", msgH.HandleListTraces)
	protected.HandleFunc("GET /api/bots/{id}/traces/{traceId}", msgH.HandleGetTrace)

	// Bot app installations
	protected.HandleFunc("GET /api/bots/{id}/apps", appH.HandleListBotApps)

	// Channels (under bots)
	protected.HandleFunc("GET /api/bots/{id}/channels", msgH.HandleListChannels)
	protected.HandleFunc("POST /api/bots/{id}/channels", msgH.HandleCreateChannel)
	protected.HandleFunc("PUT /api/bots/{id}/channels/{cid}", msgH.HandleUpdateChannel)
	protected.HandleFunc("DELETE /api/bots/{id}/channels/{cid}", msgH.HandleDeleteChannel)
	protected.HandleFunc("POST /api/bots/{id}/channels/{cid}/rotate_key", msgH.HandleRotateKey)

	// Bot operations
	protected.HandleFunc("PUT /api/bots/{id}", botH.HandleUpdateBot)
	protected.HandleFunc("PUT /api/bots/{id}/ai", botH.HandleSetBotAI)
	protected.HandleFunc("PUT /api/bots/{id}/ai_model", botH.HandleSetBotAIModel)
	protected.HandleFunc("POST /api/bots/{id}/send", botH.HandleBotSend)
	protected.HandleFunc("GET /api/bots/{id}/contacts", botH.HandleBotContacts)
	protected.HandleFunc("GET /api/bots/stats", botH.HandleStats)

	// Tenant device bind approval
	protected.HandleFunc("GET /api/tenant/client-binds", tenantBindH.HandleListBinds)
	protected.HandleFunc("PUT /api/tenant/client-binds/{id}/approve", tenantBindH.HandleApproveBind)
	protected.HandleFunc("PUT /api/tenant/client-binds/{id}/reject", tenantBindH.HandleRejectBind)

	// Messages (under bots)
	protected.HandleFunc("GET /api/bots/{id}/messages", msgH.HandleListMessages)
	protected.HandleFunc("POST /api/bots/{id}/messages/{msgId}/retry_media", msgH.HandleRetryMedia)

	// --- Admin: user management ---
	protected.HandleFunc("GET /api/admin/users", cfgH.RequireAdmin(cfgH.HandleListUsers))
	protected.HandleFunc("POST /api/admin/users", cfgH.RequireAdmin(cfgH.HandleCreateUser))
	protected.HandleFunc("PUT /api/admin/users/{id}/role", cfgH.RequireAdmin(cfgH.HandleUpdateUserRole))
	protected.HandleFunc("PUT /api/admin/users/{id}/status", cfgH.RequireAdmin(cfgH.HandleUpdateUserStatus))
	protected.HandleFunc("PUT /api/admin/users/{id}/password", cfgH.RequireAdmin(cfgH.HandleResetUserPassword))
	protected.HandleFunc("DELETE /api/admin/users/{id}", cfgH.RequireAdmin(cfgH.HandleDeleteUser))

	// --- Apps ---
	protected.HandleFunc("POST /api/apps/import-mcp", appH.HandleImportMCP)
	protected.HandleFunc("POST /api/apps", appH.HandleCreateApp)
	protected.HandleFunc("GET /api/apps", appH.HandleListApps)
	protected.HandleFunc("GET /api/apps/{id}", appH.HandleGetApp)
	protected.HandleFunc("PUT /api/apps/{id}", appH.HandleUpdateApp)
	protected.HandleFunc("DELETE /api/apps/{id}", appH.HandleDeleteApp)
	protected.HandleFunc("POST /api/apps/{id}/install", appH.HandleInstallApp)
	protected.HandleFunc("POST /api/apps/{id}/request-listing", appH.HandleRequestListing)
	protected.HandleFunc("POST /api/apps/{id}/withdraw-listing", appH.HandleWithdrawListing)
	protected.HandleFunc("GET /api/apps/{id}/installations", appH.HandleListInstallations)
	protected.HandleFunc("GET /api/apps/{id}/installations/{iid}", appH.HandleGetInstallation)
	protected.HandleFunc("PUT /api/apps/{id}/installations/{iid}", appH.HandleUpdateInstallation)
	protected.HandleFunc("DELETE /api/apps/{id}/installations/{iid}", appH.HandleDeleteInstallation)
	protected.HandleFunc("POST /api/apps/{id}/installations/{iid}/regenerate-token", appH.HandleRegenerateToken)
	protected.HandleFunc("POST /api/apps/{id}/installations/{iid}/reauthorize", appH.HandleReauthorize)
	protected.HandleFunc("GET /api/apps/{id}/reviews", appH.HandleListAppReviews)
	protected.HandleFunc("POST /api/apps/{id}/verify-url", appH.HandleVerifyURL)
	protected.HandleFunc("GET /api/apps/{id}/installations/{iid}/event-logs", appH.HandleAppEventLogs)
	protected.HandleFunc("GET /api/apps/{id}/installations/{iid}/api-logs", appH.HandleAppAPILogs)

	// --- App OAuth ---
	protected.HandleFunc("GET /api/apps/{id}/oauth/setup", appH.HandleAppOAuthSetupRedirect)
	protected.HandleFunc("GET /api/apps/{id}/oauth/authorize", appH.HandleAppOAuthAuthorize)

	// --- 甲乙方 AI 对聊 (builtin tenant-chat app): 甲/乙 = 两个真实扫码 iLink 用户 ---
	protected.HandleFunc("GET /api/tenant-chat/passive", tcH.HandleTenantChatPassiveGet)
	protected.HandleFunc("PUT /api/tenant-chat/passive", tcH.HandleTenantChatPassiveSet)
	protected.HandleFunc("GET /api/tenant-chat/passive/users", tcH.HandleTenantChatPassiveUsers)
	protected.HandleFunc("GET /api/tenant-chat/conversations/mine", tcH.HandleTenantChatMine)
	protected.HandleFunc("GET /api/tenant-chat/conversations/{id}", tcH.HandleTenantChatGet)
	protected.HandleFunc("POST /api/tenant-chat/conversations", tcH.HandleTenantChatCreate)
	protected.HandleFunc("POST /api/tenant-chat/conversations/join", tcH.HandleTenantChatJoin)
	protected.HandleFunc("POST /api/tenant-chat/conversations/start-passive", tcH.HandleTenantChatStartPassive)
	protected.HandleFunc("PUT /api/tenant-chat/conversations/{id}/persona", tcH.HandleTenantChatPersona)
	protected.HandleFunc("PUT /api/tenant-chat/conversations/{id}/config", tcH.HandleTenantChatConfig)
	protected.HandleFunc("POST /api/tenant-chat/conversations/{id}/control", tcH.HandleTenantChatControl)
	protected.HandleFunc("GET /api/tenant-chat/memory", tcH.HandleTenantChatMemoryList)
	protected.HandleFunc("POST /api/tenant-chat/memory", tcH.HandleTenantChatMemoryAdd)
	protected.HandleFunc("GET /api/tenant-chat/memory/profile", tcH.HandleTenantChatMemoryProfileGet)
	protected.HandleFunc("PUT /api/tenant-chat/memory/profile", tcH.HandleTenantChatMemoryProfileSet)
	protected.HandleFunc("DELETE /api/tenant-chat/memory/{mid}", tcH.HandleTenantChatMemoryDelete)

	// --- Skill marketplace ---
	protected.HandleFunc("GET /api/skills", skillH.HandleListSkills)
	protected.HandleFunc("POST /api/skills/submit", skillH.HandleSubmitSkill)
	protected.HandleFunc("GET /api/skills/{id}", skillH.HandleGetSkill)
	protected.HandleFunc("DELETE /api/skills/{id}", skillH.HandleDeleteSkill)
	protected.HandleFunc("GET /api/skills/{id}/versions", skillH.HandleListSkillVersions)
	protected.HandleFunc("GET /api/skills/{id}/versions/{vid}/download", skillH.HandleDownloadSkillBundle)
	protected.HandleFunc("POST /api/skills/{id}/versions/{vid}/cancel", skillH.HandleCancelSkillVersion)
	protected.HandleFunc("GET /api/skills/{id}/reviews", skillH.HandleListSkillReviewLogs)
	protected.HandleFunc("GET /api/skills/{id}/ratings", skillH.HandleListSkillRatings)
	protected.HandleFunc("PUT /api/skills/{id}/rating", skillH.HandleRateSkill)
	protected.HandleFunc("DELETE /api/skills/{id}/rating", skillH.HandleDeleteSkillRating)
	protected.HandleFunc("POST /api/skills/{id}/install", skillH.HandleInstallSkill)
	protected.HandleFunc("DELETE /api/skills/{id}/install", skillH.HandleUninstallSkill)
	protected.HandleFunc("GET /api/me/skill-installs", skillH.HandleMySkillInstalls)

	// --- 供采市场 (builtin supply-market app) ---
	protected.HandleFunc("GET /api/supply-market/categories", s.handleSupplyMarketCategories)
	protected.HandleFunc("GET /api/supply-market/items", s.handleSupplyMarketMyItems)
	protected.HandleFunc("POST /api/supply-market/items", s.handleSupplyMarketPublish)
	protected.HandleFunc("GET /api/supply-market/items/{id}", s.handleSupplyMarketGet)
	protected.HandleFunc("POST /api/supply-market/items/{id}/clarify", s.handleSupplyMarketClarify)
	protected.HandleFunc("POST /api/supply-market/items/{id}/close", s.handleSupplyMarketClose)
	protected.HandleFunc("DELETE /api/supply-market/items/{id}", s.handleSupplyMarketDelete)
	protected.HandleFunc("GET /api/supply-market/marketplace", s.handleSupplyMarketList)
	protected.HandleFunc("GET /api/supply-market/match", s.handleSupplyMarketMatch)
	protected.HandleFunc("GET /api/supply-market/chats/mine", s.handleSupplyMarketChatsMine)
	protected.HandleFunc("POST /api/supply-market/chats", s.handleSupplyMarketChatStart)
	protected.HandleFunc("GET /api/supply-market/chats/{id}", s.handleSupplyMarketChatGet)
	protected.HandleFunc("POST /api/supply-market/chats/{id}/messages", s.handleSupplyMarketChatSend)

	// --- Marketplace ---
	protected.HandleFunc("GET /api/marketplace", appH.HandleMarketplace)
	protected.HandleFunc("GET /api/marketplace/builtin", appH.HandleBuiltinApps)
	protected.HandleFunc("POST /api/marketplace/sync/{slug}", appH.HandleMarketplaceSync)

	// --- Webhook plugins (authenticated actions) ---
	protected.HandleFunc("POST /api/webhook-plugins/submit", appH.HandleSubmitPlugin)
	protected.HandleFunc("POST /api/webhook-plugins/{id}/versions/{vid}/cancel", appH.HandleCancelVersion)
	protected.HandleFunc("POST /api/webhook-plugins/debug/request", appH.HandleDebugRequest)
	protected.HandleFunc("POST /api/webhook-plugins/debug/response", appH.HandleDebugResponse)
	protected.HandleFunc("POST /api/webhook-plugins/{id}/install", appH.HandleInstallPlugin)
	protected.HandleFunc("POST /api/webhook-plugins/{id}/install-to-channel", appH.HandleInstallPluginToChannel)

	// --- Admin: dashboard ---
	protected.HandleFunc("GET /api/admin/stats", cfgH.RequireAdmin(botH.HandleAdminStats))

	// --- Admin: webhook plugins ---
	protected.HandleFunc("PUT /api/admin/webhook-plugins/{id}/review", cfgH.RequireAdmin(appH.HandleReviewPlugin))
	protected.HandleFunc("DELETE /api/admin/webhook-plugins/{id}", cfgH.RequireAdmin(appH.HandleDeletePlugin))

	// --- Admin: apps ---
	protected.HandleFunc("GET /api/admin/apps", cfgH.RequireAdmin(appH.HandleAdminListApps))
	protected.HandleFunc("PUT /api/admin/apps/{id}/review-listing", cfgH.RequireAdmin(appH.HandleReviewListing))
	protected.HandleFunc("PUT /api/admin/apps/{id}/listing", cfgH.RequireAdmin(appH.HandleAdminSetListing))

	// --- Admin: skill marketplace ---
	protected.HandleFunc("GET /api/admin/skills", cfgH.RequireAdmin(skillH.HandleAdminListSkills))
	protected.HandleFunc("GET /api/admin/skills/pending", cfgH.RequireAdmin(skillH.HandleAdminPendingSkills))
	protected.HandleFunc("PUT /api/admin/skills/versions/{vid}/review", cfgH.RequireAdmin(skillH.HandleReviewSkillVersion))
	protected.HandleFunc("PUT /api/admin/skills/{id}/listing", cfgH.RequireAdmin(skillH.HandleAdminSetSkillListing))
	protected.HandleFunc("DELETE /api/admin/skills/{id}", cfgH.RequireAdmin(skillH.HandleAdminDeleteSkill))

	// --- Admin: registries ---
	protected.HandleFunc("GET /api/admin/registries", cfgH.RequireAdmin(appH.HandleListRegistries))
	protected.HandleFunc("POST /api/admin/registries", cfgH.RequireAdmin(appH.HandleCreateRegistry))
	protected.HandleFunc("PUT /api/admin/registries/{id}", cfgH.RequireAdmin(appH.HandleUpdateRegistry))
	protected.HandleFunc("DELETE /api/admin/registries/{id}", cfgH.RequireAdmin(appH.HandleDeleteRegistry))

	// --- Admin: system config ---
	protected.HandleFunc("GET /api/admin/config/oauth", cfgH.RequireAdmin(cfgH.HandleGetOAuthConfig))
	protected.HandleFunc("PUT /api/admin/config/oauth/{provider}", cfgH.RequireAdmin(cfgH.HandleSetOAuthConfig))
	protected.HandleFunc("DELETE /api/admin/config/oauth/{provider}", cfgH.RequireAdmin(cfgH.HandleDeleteOAuthConfig))
	protected.HandleFunc("GET /api/admin/config/oidc", cfgH.RequireAdmin(authH.HandleGetOIDCConfig))
	protected.HandleFunc("PUT /api/admin/config/oidc/{slug}", cfgH.RequireAdmin(authH.HandleSetOIDCConfig))
	protected.HandleFunc("DELETE /api/admin/config/oidc/{slug}", cfgH.RequireAdmin(authH.HandleDeleteOIDCConfig))
	protected.HandleFunc("GET /api/config/ai/available_models", cfgH.HandleGetAvailableModels)
	protected.HandleFunc("GET /api/admin/config/ai", cfgH.RequireAdmin(cfgH.HandleGetAIConfig))
	protected.HandleFunc("PUT /api/admin/config/ai", cfgH.RequireAdmin(cfgH.HandleSetAIConfig))
	protected.HandleFunc("DELETE /api/admin/config/ai", cfgH.RequireAdmin(cfgH.HandleDeleteAIConfig))
	protected.HandleFunc("POST /api/admin/config/ai/fetch-models", cfgH.RequireAdmin(cfgH.HandleFetchAIModels))
	protected.HandleFunc("GET /api/admin/llm-usage", cfgH.RequireAdmin(cfgH.HandleListLLMUsage))
	protected.HandleFunc("GET /api/admin/media-usage", cfgH.RequireAdmin(cfgH.HandleListMediaUsage))
	protected.HandleFunc("GET /api/admin/config/registry", cfgH.RequireAdmin(cfgH.HandleGetRegistryConfig))
	protected.HandleFunc("PUT /api/admin/config/registry", cfgH.RequireAdmin(cfgH.HandleSetRegistryConfig))
	protected.HandleFunc("GET /api/admin/config/registration", cfgH.RequireAdmin(cfgH.HandleGetRegistrationConfig))
	protected.HandleFunc("PUT /api/admin/config/registration", cfgH.RequireAdmin(cfgH.HandleSetRegistrationConfig))
	protected.HandleFunc("GET /api/admin/config/scan_login_role", cfgH.RequireAdmin(cfgH.HandleGetScanLoginRoleConfig))
	protected.HandleFunc("PUT /api/admin/config/scan_login_role", cfgH.RequireAdmin(cfgH.HandleSetScanLoginRoleConfig))

	// App OAuth exchange (no user auth — uses PKCE or single-use code)
	mux.HandleFunc("POST /api/apps/{id}/oauth/exchange", appH.HandleAppOAuthExchange)

	mux.Handle("/api/", auth.Middleware(s.Store)(protected))

	// --- Bot API (app_token auth) ---
	botAPI := http.NewServeMux()
	// New paths
	botAPI.HandleFunc("POST /bot/v1/message/send", botH.HandleBotAPISend)
	botAPI.HandleFunc("GET /bot/v1/contact", botH.HandleBotAPIContacts)
	botAPI.HandleFunc("GET /bot/v1/info", botH.HandleBotAPIBotInfo)
	// Keep old paths for backward compatibility
	botAPI.HandleFunc("POST /bot/v1/messages/send", botH.HandleBotAPISend)
	botAPI.HandleFunc("GET /bot/v1/contacts", botH.HandleBotAPIContacts)
	botAPI.HandleFunc("GET /bot/v1/bot", botH.HandleBotAPIBotInfo)
	botAPI.HandleFunc("PUT /bot/v1/app/tools", botH.HandleBotAPIUpdateTools)
	botAPI.HandleFunc("PUT /bot/v1/installation/tools", botH.HandleBotAPIUpdateInstallationTools)
	botAPI.HandleFunc("/bot/", botH.HandleBotAPINotFound)
	mux.Handle("/bot/", botH.AppTokenAuth(botAPI))

	// WebSocket endpoints (auth via query param, outside appTokenAuth)
	mux.HandleFunc("GET /bot/v1/ws", botH.HandleBotAPIWebSocket)       // per-installation
	mux.HandleFunc("GET /bot/v1/app/ws", botH.HandleAppLevelWebSocket) // per-app (all installations)

	// MCP endpoint (installation app_token auth, server-side MCP capabilities)
	mux.Handle("/api/v2/mcp", mcp.MCPHandler(s.Store))

	// MCP endpoint (app_token auth, stateless streamable HTTP)
	mux.Handle("/mcp", botH.SetupMCP())

	// Serve embedded frontend (production) or skip (dev mode uses vite)
	if handler := web.Handler(); handler != nil {
		mux.Handle("/", handler)
	}

	// --- App reverse-proxy: serve app frontends as sub-paths of the main
	// site (/apps/{slug}) instead of independent ports. ---
	s.mountAppProxies(mux)

	return recovery(requestLogger(cors(mux)))
}

// mountAppProxies exposes each configured app upstream at /apps/{slug} on the
// main site. Requests are reverse-proxied to the upstream with the /apps/{slug}
// prefix stripped, so the app can be deployed under that base path.
func (s *Server) mountAppProxies(mux *http.ServeMux) {
	for slug, upstream := range s.Config.AppProxies {
		target, err := url.Parse(upstream)
		if err != nil || target.Host == "" {
			slog.Warn("app proxy skipped: invalid upstream", "upstream", upstream, "slug", slug, "err", err)
			continue
		}
		prefix := "/apps/" + slug
		rp := &httputil.ReverseProxy{
			Rewrite: func(pr *httputil.ProxyRequest) {
				// Forward the path unchanged. The upstream app is expected
				// to be served under its own base path (/apps/{slug}), e.g.
				// via Next.js basePath, so we must NOT strip the prefix.
				pr.SetURL(target)
				pr.Out.URL.Path = pr.In.URL.Path
				pr.Out.URL.RawPath = pr.In.URL.RawPath
				pr.Out.URL.RawQuery = pr.In.URL.RawQuery
			},
		}
		mux.Handle(prefix+"/", rp)
		mux.HandleFunc(prefix, func(w http.ResponseWriter, r *http.Request) {
			rp.ServeHTTP(w, r)
		})
	}
}
