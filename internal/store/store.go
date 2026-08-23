package store

import (
	"context"
	"encoding/json"
	"time"
)

// Store is the main database interface implemented by SQLite and Postgres backends.
// The concrete type (*sqlite.DB or *postgres.DB) must satisfy this interface.
type Store interface {
	// Config
	GetConfig(key string) (string, error)
	SetConfig(key, value string) error
	DeleteConfig(key string) error
	ListConfigByPrefix(prefix string) (map[string]string, error)

	// Users (not yet implemented in sqlite/postgres — stubs will be added)
	CreateUser(username, displayName string) (*User, error)
	CreateUserFull(username, email, displayName, passwordHash, role string) (*User, error)
	GetUserByID(id string) (*User, error)
	GetUserByUsername(username string) (*User, error)
	GetUserByEmail(email string) (*User, error)
	UserCount() (int, error)
	UpdateUser(user *User) error
	UpdateUserProfile(id, displayName, email string) error
	UpdateUserPassword(id, passwordHash string) error
	UpdateUserUsername(id, username string) error
	UpdateUserRole(id, role string) error
	UpdateUserStatus(id, status string) error
	ListUsers() ([]*User, error)
	DeleteUser(id string) error

	// Tenants
	FindTenantByJoinCode(code string) (*Tenant, error)

	// Bots
	CreateBot(userID, name, provider, providerID string, credentials json.RawMessage) (*Bot, error)
	GetBot(id string) (*Bot, error)
	GetAllBots() ([]Bot, error)
	GetBotStats(userID string) (*BotStats, error)
	GetBotsNeedingReminder() ([]Bot, error)
	GetAdminStats() (*AdminStats, error)
	ListBotsByUser(userID string) ([]Bot, error)
	ListRecentContacts(botID string, limit int) ([]RecentContact, error)
	IncrBotMsgCount(id string) error
	UpdateBotAIEnabled(id string, enabled bool) error
	UpdateBotAIModel(id, model string) error
	UpdateBotDisplayName(id, displayName string) error
	MarkBotReminded(id string) error
	UpdateBotReminder(id string, hours int) error
	FindBotByProviderID(provider, providerID string) (*Bot, error)
	FindBotByCredential(key, value string) (*Bot, error)
	UpdateBotCredentials(id, providerID string, credentials json.RawMessage) error
	UpdateBotName(id, name string) error
	UpdateBotStatus(id, status string) error
	UpdateBotSyncState(id string, syncState json.RawMessage) error
	CountBotsByUser(userID string) (int, error)
	DeleteBot(id string) error
	LastActivityAt(userID string) *time.Time
	BatchHasFreshContextToken(botIDs []string, maxAge time.Duration) map[string]bool
	HasFreshContextToken(botID string, maxAge time.Duration) bool

	// Channels
	CreateChannel(botID, name, handle string, filter *FilterRule, ai *AIConfig) (*Channel, error)
	GetChannel(id string) (*Channel, error)
	GetChannelByAPIKey(apiKey string) (*Channel, error)
	ListChannelsByBot(botID string) ([]Channel, error)
	ListChannelsByBotIDs(botIDs []string) ([]Channel, error)
	UpdateChannel(id, name, handle string, filter *FilterRule, ai *AIConfig, webhook *WebhookConfig, enabled bool) error
	UpdateChannelLastSeq(channelID string, seq int64) error
	DeleteChannel(id string) error
	RotateChannelKey(id string) (string, error)
	CountChannelsByBot(botID string) (int, error)

	// Messages
	SaveMessage(m *Message) (SaveResult, error)
	GetMessage(id int64) (*Message, error)
	ListMessages(botID string, limit int, beforeID int64) ([]Message, error)
	ListChannelMessages(channelID, sender string, limit int) ([]Message, error)
	ListMessagesBySender(botID, sender string, limit int) ([]Message, error)
	GetMessagesSince(botID string, afterSeq int64, limit int) ([]Message, error)
	GetLatestContextToken(botID string) string
	UpdateMessagePayload(id int64, payload json.RawMessage) error
	GetUnprocessedMessages(botID string, limit int) ([]Message, error)
	MarkProcessed(id int64) error
	PruneMessages(maxAgeDays int) (int64, error)
	UpdateMediaStatus(botID, status string, keys json.RawMessage) error
	UpdateMediaStatusByID(id int64, status string, keys json.RawMessage) error
	UpdateMediaPayloads(botID, eqp string, newPayload json.RawMessage) error

	// Apps
	GetApp(id string) (*App, error)
	GetAppBySlug(slug, registry string) (*App, error)
	ListAllApps() ([]App, error)
	ListAppsByOwner(ownerID string) ([]App, error)
	ListListedApps() ([]App, error)
	ListMarketplaceApps() ([]App, error)
	UpdateApp(id string, name, description, icon, iconURL, homepage, oauthSetupURL, oauthRedirectURL, configSchema, version, readme, guide string, tools, events, scopes json.RawMessage) error
	UpdateMarketplaceApp(id, name, description, iconURL, homepage, webhookURL, oauthSetupURL, oauthRedirectURL, version, readme, guide string, tools, events, scopes json.RawMessage) error
	DeleteApp(id string) error
	UpdateAppTools(id string, tools json.RawMessage) error
	InstallApp(appID, botID string) (*AppInstallation, error)
	GetInstallation(id string) (*AppInstallation, error)
	GetInstallationByToken(token string) (*AppInstallation, error)
	GetInstallationByHandle(botID, handle string) (*AppInstallation, error)
	InstalledAppIDs(userID string) (map[string]bool, error)
	ListInstallationsByApp(appID string) ([]AppInstallation, error)
	ListInstallationsByBot(botID string) ([]AppInstallation, error)
	UpdateInstallation(id, handle string, config json.RawMessage, scopes json.RawMessage, enabled bool) error
	UpdateInstallationTools(id string, tools json.RawMessage) error
	SetAppWebhookVerified(id string, verified bool) error
	UpdateAppWebhookURL(id, webhookURL string) error
	RegenerateInstallationToken(id string) (string, error)
	DeleteInstallation(id string) error
	DeleteInstallationsByAppID(appID string) error
	RequestListing(id string) error
	ReviewListing(id string, approve bool, reason string) error
	WithdrawListing(id string) error
	SetListing(id, listing string) error
	TransitionListingWithCleanup(id, nextListing, rejectReason string) error
	UpdateAppWithTransition(id string, update AppUpdate, nextListing string) (AppUpdateResult, error)
	CreateApp(app *App) (*App, error)
	CreateAppReview(review *AppReview) error
	ListAppReviews(appID string) ([]AppReview, error)
	UpdateAppPrice(appID string, price float64, currency string) error
	CreateAppPurchase(appID, userID string) (*AppPurchase, error)
	GetAppPurchase(appID, userID string) (*AppPurchase, error)
	ListAppPurchasesByUser(userID string) ([]AppPurchase, error)

	// Registry
	ListRegistries() ([]Registry, error)
	CreateRegistry(r *Registry) error
	UpdateRegistryEnabled(id string, enabled bool) error
	DeleteRegistry(id string) error

	// Credentials
	SaveCredential(c *Credential) error
	GetCredentialsByUserID(userID string) ([]Credential, error)
	UpdateCredentialName(id, userID, name string) (bool, error)
	UpdateCredentialSignCount(id string, signCount uint32) error
	DeleteCredential(id, userID string) error

	// Media
	RecordMediaUsage(rec *MediaUsageRecord) error
	ListMediaUsageAgg(filter MediaUsageFilter) ([]MediaUsageAggregate, error)

	// LLM Usage
	RecordLLMUsage(rec *LLMUsageRecord) error
	ListLLMUsageAgg(filter UsageFilter) ([]UsageAggregate, error)

	// Skills
	ListSkills(q SkillQuery) ([]Skill, error)
	GetSkill(id string) (*Skill, error)
	GetSkillBySlug(slug string) (*Skill, error)
	GetSkillVersion(versionID string) (*SkillVersion, error)
	UpdateSkillMeta(id string, s *Skill) error
	SetSkillListing(id, listing, reason string) error
	DeleteSkill(id string) error
	DeleteSkillRating(skillID, userID string) error
	ListSkillVersions(skillID string) ([]SkillVersion, error)
	ListSkillRatings(skillID string, limit int) ([]SkillRating, error)
	CreateSkill(s *Skill) (*Skill, error)
	CreateSkillVersion(v *SkillVersion) (*SkillVersion, error)

	ListPendingSkillVersions() ([]SkillVersion, error)
	SupersedePendingSkillVersions(skillID string) error
	ReviewSkillVersion(versionID, status, reviewerID, reason string) error
	CancelSkillVersion(versionID string) error
	IncrementSkillDownload(versionID string) error
	GetSkillRating(skillID, userID string) (*SkillRating, error)
	UpsertSkillRating(r *SkillRating) error
	RecordSkillInstall(skillID, versionID, userID, agentID string) error
	ListSkillInstalls(userID string) ([]SkillInstall, error)
	DeleteSkillInstall(skillID, userID, agentID string) error
	CreateSkillReviewLog(l *SkillReviewLog) error
	ListSkillReviewLogs(skillID string) ([]SkillReviewLog, error)

	// Webhook logs
	CreateWebhookLog(log *WebhookLog) (int64, error)
	UpdateWebhookLogRequest(id int64, status, url, method, body string) error
	UpdateWebhookLogResponse(id int64, status string, respStatus int, respBody string, durationMs int) error
	UpdateWebhookLogResult(id int64, status, scriptError string, replies []string) error
	UpdateWebhookLogPluginVersion(id int64, version string) error
	ListWebhookLogs(botID, channelID string, limit int) ([]WebhookLog, error)
	CleanOldWebhookLogs(days int) error

	// App logs
	CreateEventLog(log *AppEventLog) (int64, error)
	UpdateEventLogDelivered(id int64, respStatus int, respBody string, durationMs int) error
	UpdateEventLogFailed(id int64, errMsg string, retryCount int, durationMs int) error
	ListEventLogs(installationID string, limit int) ([]AppEventLog, error)
	CleanOldAppLogs(days int) error

	// API logs
	CreateAPILog(log *AppAPILog) error
	ListAPILogs(installationID string, limit int) ([]AppAPILog, error)

	// Client/device management
	CreateClient(ctx context.Context, c *Client) (*Client, error)
	GetClientByID(ctx context.Context, id string) (*Client, error)
	GetClientByClientID(ctx context.Context, clientID string) (*Client, error)
	GetClientByFingerprint(ctx context.Context, fingerprint string) (*Client, error)
	GetClientByDeviceToken(ctx context.Context, token string) (*Client, error)
	UpdateClient(ctx context.Context, c *Client) error
	UpdateClientLastSeen(ctx context.Context, id string) error
	ListClientsByTenant(ctx context.Context, tenantID string) ([]Client, error)
	RevokeClient(ctx context.Context, id string) error
	CleanExpiredClients(ctx context.Context) (int, error)
	TouchHeartbeat(ctx context.Context, clientID, tenantID string) error

	// Bind requests
	CreateBindRequest(ctx context.Context, req *BindRequest) (*BindRequest, error)
	GetBindRequest(ctx context.Context, id string) (*BindRequest, error)
	UpdateBindRequest(ctx context.Context, req *BindRequest) error
	ListBindRequestsByTenant(ctx context.Context, tenantID, status string) ([]BindRequest, error)
	ListBindRequestsByClient(ctx context.Context, clientID string) ([]BindRequest, error)

	// Sessions
	CreateSession(token, userID string, expiresAt time.Time) error
	GetSession(token string) (string, time.Time, error)
	DeleteSession(token string) error
	DeleteExpiredSessions() error
	DeleteSessionsByUserID(userID string) error

	// OAuth
	CreateOAuthAccount(a *OAuthAccount) error
	GetOAuthAccount(provider, providerID string) (*OAuthAccount, error)
	ListOAuthAccountsByUser(userID string) ([]OAuthAccount, error)
	DeleteOAuthAccount(provider, providerID string) error
	CreateOAuthCode(code, appID, botID, state, codeChallenge string) error
	ExchangeOAuthCode(code string) (appID, botID, codeChallenge string, err error)
	CleanExpiredOAuthCodes()

	// Auth scan
	CreateScanLoginSession(sessionID, userID string) error
	GetScanLoginSession(sessionID string) (*ScanLoginSession, error)
	UpdateScanLoginSession(session *ScanLoginSession) error
	DeleteScanLoginSession(sessionID string) error

	// Passkeys
	CreatePasskey(passkey *Passkey) error
	GetPasskey(id string) (*Passkey, error)
	ListPasskeys(userID string) ([]Passkey, error)
	DeletePasskey(id string) error

	// Plugins
	CreatePlugin(p *Plugin) (*Plugin, error)
	GetPlugin(id string) (*Plugin, error)
	GetPluginByName(name string) (*Plugin, error)
	ListPlugins() ([]PluginWithLatest, error)
	ListPluginsByOwner(ownerID string) ([]PluginWithLatest, error)
	UpdatePluginMeta(id string, p *Plugin) error
	DeletePlugin(id string) error
	CreatePluginVersion(v *PluginVersion) (*PluginVersion, error)
	GetPluginVersion(id string) (*PluginVersion, error)
	ListPluginVersions(pluginID string) ([]PluginVersion, error)
	ListPendingVersions() ([]PluginVersion, error)
	ReviewPluginVersion(id, status, reviewedBy, reason string) error
	CancelPluginVersion(id string) error
	DeletePluginVersion(id string) error
	SupersedeNonApprovedVersions(pluginID string)
	FindPendingVersion(pluginID string) (*PluginVersion, error)
	UpdatePluginVersion(id string, v *PluginVersion) error
	RecordPluginInstall(pluginID, userID string) error
	FindPluginOwner(name string) (string, error)
	ResolvePluginScript(versionID string) (script, version string, timeoutSec int, err error)

	// Tasks
	CreateTask(ctx context.Context, task *Task) error
	GetTask(ctx context.Context, id string) (*Task, error)
	GetTenantTasks(ctx context.Context, tenantID string, status *TaskStatus, limit int) ([]*Task, error)
	GetPendingTasksForClient(ctx context.Context, clientID string, limit int, sinceTaskID string) ([]*Task, error)
	MarkTaskDelivered(ctx context.Context, id string) bool
	AcknowledgeTask(ctx context.Context, id string) bool
	CompleteTask(ctx context.Context, id string, result map[string]any) bool
	FailTask(ctx context.Context, id string, errorMessage string) bool
	CancelTask(ctx context.Context, id string) bool

	// Client/Device
	FindFingerprint(ctx context.Context, fingerprint string) (*Client, error)
	GetClient(ctx context.Context, clientID, tenantID string) (*Client, error)

	// Skills (Marketplace & Execution)
	CreateSkillUploadTicket(ctx context.Context, req *SkillUploadTicketRequest) (*SkillUploadTicket, error)
	ConfirmSkillInstall(ctx context.Context, clientID string, confirm *SkillInstallConfirm) (bool, error)
	ReportSkillExecution(ctx context.Context, record *SkillExecutionReport) error
	GetSkillEvaluation(ctx context.Context, skillID string) (*SkillEvaluation, error)

	// Billing / COS
	CreateUploadTicket(ctx context.Context, skillID string, ttl int) (*UploadTicket, error)
	ConfirmUpload(ctx context.Context, ticketID string, success bool, sha256 string, size int64) (bool, error)
	GetBillingConfig(ctx context.Context) (*BillingConfig, error)
	GetGarlicLedger(ctx context.Context, clientID string) (*GarlicLedger, error)

	// Search
	ReportSearchSignals(ctx context.Context, record *SearchSignalsReport) error

	// Trace
	AppendSpan(traceID, botID, name, kind, statusCode, statusMessage string, attrs map[string]any) error
	InsertSpan(traceID, spanID, parentSpanID, name, kind, statusCode, statusMessage string,
		startTime, endTime int64, attrsJSON, eventsJSON []byte, botID string) error
	ListRootSpans(botID string, limit int) ([]TraceSpan, error)
	ListSpansByTrace(traceID string) ([]TraceSpan, error)

	// Clock
	SetClock(c Clock)

	// Close
	Close() error
}
