package memstore

import (
	"context"
	"encoding/json"
	"sync"
	"time"

	"github.com/ceoadmin/CEOadmin/internal/store"
)

// Store is an in-memory implementation of store.Store for testing.
type Store struct {
	mu       sync.Mutex
	bots     map[string]*store.Bot
	apps     map[string]*store.App
	installs map[string]*store.AppInstallation
	contacts []store.RecentContact
	sentMsgs []store.Message
}

func New() *Store {
	return &Store{
		bots:     make(map[string]*store.Bot),
		apps:     make(map[string]*store.App),
		installs: make(map[string]*store.AppInstallation),
	}
}

func (s *Store) AddBot(b *store.Bot) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.bots[b.ID] = b
}

func (s *Store) AddApp(a *store.App) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.apps[a.ID] = a
}

func (s *Store) AddInstallation(inst *store.AppInstallation) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.installs[inst.ID] = inst
}

func (s *Store) AddContact(c store.RecentContact) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.contacts = append(s.contacts, c)
}

func (s *Store) GetSentMessages() []store.Message {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.sentMsgs
}

func (s *Store) Reset() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.bots = make(map[string]*store.Bot)
	s.apps = make(map[string]*store.App)
	s.installs = make(map[string]*store.AppInstallation)
	s.contacts = nil
	s.sentMsgs = nil
}

// Implement store.Store interface with no-op methods
func (s *Store) CreateUser(username, displayName string) (*store.User, error) { return nil, nil }
func (s *Store) CreateUserFull(username, email, displayName, passwordHash, role string) (*store.User, error) {
	return nil, nil
}
func (s *Store) GetUserByID(id string) (*store.User, error)              { return nil, nil }
func (s *Store) GetUserByUsername(username string) (*store.User, error)  { return nil, nil }
func (s *Store) GetUserByEmail(email string) (*store.User, error)        { return nil, nil }
func (s *Store) UserCount() (int, error)                                 { return 0, nil }
func (s *Store) UpdateUser(user *store.User) error                       { return nil }
func (s *Store) UpdateUserProfile(id, displayName, email string) error   { return nil }
func (s *Store) UpdateUserPassword(id, passwordHash string) error        { return nil }
func (s *Store) UpdateUserUsername(id, username string) error            { return nil }
func (s *Store) UpdateUserRole(id, role string) error                    { return nil }
func (s *Store) UpdateUserStatus(id, status string) error                { return nil }
func (s *Store) ListUsers() ([]*store.User, error)                       { return nil, nil }
func (s *Store) DeleteUser(id string) error                              { return nil }
func (s *Store) FindTenantByJoinCode(code string) (*store.Tenant, error) { return nil, nil }

func (s *Store) CreateBot(userID, name, provider, providerID string, credentials json.RawMessage) (*store.Bot, error) {
	return nil, nil
}
func (s *Store) GetBot(id string) (*store.Bot, error)               { return nil, nil }
func (s *Store) GetAllBots() ([]store.Bot, error)                   { return nil, nil }
func (s *Store) GetBotStats(userID string) (*store.BotStats, error) { return nil, nil }
func (s *Store) GetBotsNeedingReminder() ([]store.Bot, error)       { return nil, nil }
func (s *Store) GetAdminStats() (*store.AdminStats, error)          { return nil, nil }
func (s *Store) ListBotsByUser(userID string) ([]store.Bot, error)  { return nil, nil }
func (s *Store) ListRecentContacts(botID string, limit int) ([]store.RecentContact, error) {
	return nil, nil
}
func (s *Store) IncrBotMsgCount(id string) error                                     { return nil }
func (s *Store) UpdateBotAIEnabled(id string, enabled bool) error                    { return nil }
func (s *Store) UpdateBotAIModel(id, model string) error                             { return nil }
func (s *Store) UpdateBotDisplayName(id, displayName string) error                   { return nil }
func (s *Store) MarkBotReminded(id string) error                                     { return nil }
func (s *Store) UpdateBotReminder(id string, hours int) error                        { return nil }
func (s *Store) FindBotByProviderID(provider, providerID string) (*store.Bot, error) { return nil, nil }
func (s *Store) FindBotByCredential(key, value string) (*store.Bot, error)           { return nil, nil }
func (s *Store) UpdateBotCredentials(id, providerID string, credentials json.RawMessage) error {
	return nil
}
func (s *Store) UpdateBotName(id, name string) error                           { return nil }
func (s *Store) UpdateBotStatus(id, status string) error                       { return nil }
func (s *Store) UpdateBotSyncState(id string, syncState json.RawMessage) error { return nil }
func (s *Store) CountBotsByUser(userID string) (int, error)                    { return 0, nil }
func (s *Store) LastActivityAt(userID string) *time.Time                       { return nil }
func (s *Store) BatchHasFreshContextToken(botIDs []string, maxAge time.Duration) map[string]bool {
	return nil
}
func (s *Store) HasFreshContextToken(botID string, maxAge time.Duration) bool { return false }

func (s *Store) CreateChannel(botID, name, handle string, filter *store.FilterRule, ai *store.AIConfig) (*store.Channel, error) {
	return nil, nil
}
func (s *Store) GetChannel(id string) (*store.Channel, error)                  { return nil, nil }
func (s *Store) GetChannelByAPIKey(apiKey string) (*store.Channel, error)      { return nil, nil }
func (s *Store) ListChannelsByBot(botID string) ([]store.Channel, error)       { return nil, nil }
func (s *Store) ListChannelsByBotIDs(botIDs []string) ([]store.Channel, error) { return nil, nil }
func (s *Store) UpdateChannel(id, name, handle string, filter *store.FilterRule, ai *store.AIConfig, webhook *store.WebhookConfig, enabled bool) error {
	return nil
}
func (s *Store) UpdateChannelLastSeq(channelID string, seq int64) error { return nil }
func (s *Store) DeleteBot(id string) error                              { return nil }
func (s *Store) DeleteChannel(id string) error                          { return nil }
func (s *Store) RotateChannelKey(id string) (string, error)             { return "", nil }
func (s *Store) CountChannelsByBot(botID string) (int, error)           { return 0, nil }

func (s *Store) SaveMessage(m *store.Message) (store.SaveResult, error) {
	return store.SaveResult{}, nil
}
func (s *Store) GetMessage(id int64) (*store.Message, error) { return nil, nil }
func (s *Store) ListMessages(botID string, limit int, beforeID int64) ([]store.Message, error) {
	return nil, nil
}
func (s *Store) ListChannelMessages(channelID, sender string, limit int) ([]store.Message, error) {
	return nil, nil
}
func (s *Store) ListMessagesBySender(botID, sender string, limit int) ([]store.Message, error) {
	return nil, nil
}
func (s *Store) GetMessagesSince(botID string, afterSeq int64, limit int) ([]store.Message, error) {
	return nil, nil
}
func (s *Store) GetLatestContextToken(botID string) string                    { return "" }
func (s *Store) UpdateMessagePayload(id int64, payload json.RawMessage) error { return nil }
func (s *Store) GetUnprocessedMessages(botID string, limit int) ([]store.Message, error) {
	return nil, nil
}
func (s *Store) MarkProcessed(id int64) error                                       { return nil }
func (s *Store) PruneMessages(maxAgeDays int) (int64, error)                        { return 0, nil }
func (s *Store) UpdateMediaStatus(botID, status string, keys json.RawMessage) error { return nil }
func (s *Store) UpdateMediaStatusByID(id int64, status string, keys json.RawMessage) error {
	return nil
}
func (s *Store) UpdateMediaPayloads(botID, eqp string, newPayload json.RawMessage) error { return nil }

func (s *Store) GetApp(id string) (*store.App, error)                   { return nil, nil }
func (s *Store) GetAppBySlug(slug, registry string) (*store.App, error) { return nil, nil }
func (s *Store) ListAllApps() ([]store.App, error)                      { return nil, nil }
func (s *Store) ListAppsByOwner(ownerID string) ([]store.App, error)    { return nil, nil }
func (s *Store) ListListedApps() ([]store.App, error)                   { return nil, nil }
func (s *Store) ListMarketplaceApps() ([]store.App, error)              { return nil, nil }
func (s *Store) UpdateApp(id string, name, description, icon, iconURL, homepage, oauthSetupURL, oauthRedirectURL, configSchema, version, readme, guide string, tools, events, scopes json.RawMessage) error {
	return nil
}
func (s *Store) UpdateMarketplaceApp(id, name, description, iconURL, homepage, webhookURL, oauthSetupURL, oauthRedirectURL, version, readme, guide string, tools, events, scopes json.RawMessage) error {
	return nil
}
func (s *Store) DeleteApp(id string) error                                           { return nil }
func (s *Store) UpdateAppTools(id string, tools json.RawMessage) error               { return nil }
func (s *Store) InstallApp(appID, botID string) (*store.AppInstallation, error)      { return nil, nil }
func (s *Store) GetInstallation(id string) (*store.AppInstallation, error)           { return nil, nil }
func (s *Store) GetInstallationByToken(token string) (*store.AppInstallation, error) { return nil, nil }
func (s *Store) GetInstallationByHandle(botID, handle string) (*store.AppInstallation, error) {
	return nil, nil
}
func (s *Store) InstalledAppIDs(userID string) (map[string]bool, error) { return nil, nil }
func (s *Store) ListInstallationsByApp(appID string) ([]store.AppInstallation, error) {
	return nil, nil
}
func (s *Store) ListInstallationsByBot(botID string) ([]store.AppInstallation, error) {
	return nil, nil
}
func (s *Store) UpdateInstallation(id, handle string, config, scopes json.RawMessage, enabled bool) error {
	return nil
}
func (s *Store) UpdateInstallationTools(id string, tools json.RawMessage) error          { return nil }
func (s *Store) SetAppWebhookVerified(id string, verified bool) error                    { return nil }
func (s *Store) UpdateAppWebhookURL(id, webhookURL string) error                         { return nil }
func (s *Store) RegenerateInstallationToken(id string) (string, error)                   { return "", nil }
func (s *Store) DeleteInstallation(id string) error                                      { return nil }
func (s *Store) DeleteInstallationsByAppID(appID string) error                           { return nil }
func (s *Store) RequestListing(id string) error                                          { return nil }
func (s *Store) ReviewListing(id string, approve bool, reason string) error              { return nil }
func (s *Store) WithdrawListing(id string) error                                         { return nil }
func (s *Store) SetListing(id, listing string) error                                     { return nil }
func (s *Store) TransitionListingWithCleanup(id, nextListing, rejectReason string) error { return nil }
func (s *Store) UpdateAppWithTransition(id string, update store.AppUpdate, nextListing string) (store.AppUpdateResult, error) {
	return store.AppUpdateResult{}, nil
}
func (s *Store) CreateApp(app *store.App) (*store.App, error)           { return nil, nil }
func (s *Store) CreateAppReview(review *store.AppReview) error          { return nil }
func (s *Store) ListAppReviews(appID string) ([]store.AppReview, error) { return nil, nil }

func (s *Store) ListRegistries() ([]store.Registry, error)           { return nil, nil }
func (s *Store) CreateRegistry(r *store.Registry) error              { return nil }
func (s *Store) UpdateRegistryEnabled(id string, enabled bool) error { return nil }
func (s *Store) DeleteRegistry(id string) error                      { return nil }

func (s *Store) SaveCredential(c *store.Credential) error                         { return nil }
func (s *Store) GetCredentialsByUserID(userID string) ([]store.Credential, error) { return nil, nil }
func (s *Store) UpdateCredentialName(id, userID, name string) (bool, error)       { return false, nil }
func (s *Store) UpdateCredentialSignCount(id string, signCount uint32) error      { return nil }
func (s *Store) DeleteCredential(id, userID string) error                         { return nil }

func (s *Store) RecordMediaUsage(rec *store.MediaUsageRecord) error { return nil }
func (s *Store) ListMediaUsageAgg(filter store.MediaUsageFilter) ([]store.MediaUsageAggregate, error) {
	return nil, nil
}

func (s *Store) RecordLLMUsage(rec *store.LLMUsageRecord) error { return nil }
func (s *Store) ListLLMUsageAgg(filter store.UsageFilter) ([]store.UsageAggregate, error) {
	return nil, nil
}

func (s *Store) ListSkills(q store.SkillQuery) ([]store.Skill, error)           { return nil, nil }
func (s *Store) GetSkill(id string) (*store.Skill, error)                       { return nil, nil }
func (s *Store) GetSkillBySlug(slug string) (*store.Skill, error)               { return nil, nil }
func (s *Store) GetSkillVersion(versionID string) (*store.SkillVersion, error)  { return nil, nil }
func (s *Store) UpdateSkillMeta(id string, s2 *store.Skill) error               { return nil }
func (s *Store) SetSkillListing(id, listing, reason string) error               { return nil }
func (s *Store) DeleteSkill(id string) error                                    { return nil }
func (s *Store) DeleteSkillRating(skillID, userID string) error                 { return nil }
func (s *Store) ListSkillVersions(skillID string) ([]store.SkillVersion, error) { return nil, nil }
func (s *Store) ListSkillRatings(skillID string, limit int) ([]store.SkillRating, error) {
	return nil, nil
}
func (s *Store) CreateSkill(sk *store.Skill) (*store.Skill, error) { return nil, nil }
func (s *Store) CreateSkillVersion(v *store.SkillVersion) (*store.SkillVersion, error) {
	return nil, nil
}
func (s *Store) ListPendingSkillVersions() ([]store.SkillVersion, error) { return nil, nil }
func (s *Store) SupersedePendingSkillVersions(skillID string) error      { return nil }
func (s *Store) ReviewSkillVersion(versionID, status, reviewerID, reason string) error {
	return nil
}
func (s *Store) CancelSkillVersion(versionID string) error                           { return nil }
func (s *Store) IncrementSkillDownload(versionID string) error                       { return nil }
func (s *Store) GetSkillRating(skillID, userID string) (*store.SkillRating, error)   { return nil, nil }
func (s *Store) UpsertSkillRating(r *store.SkillRating) error                        { return nil }
func (s *Store) RecordSkillInstall(skillID, versionID, userID, agentID string) error { return nil }
func (s *Store) ListSkillInstalls(userID string) ([]store.SkillInstall, error)       { return nil, nil }
func (s *Store) DeleteSkillInstall(skillID, userID, agentID string) error            { return nil }
func (s *Store) CreateSkillReviewLog(l *store.SkillReviewLog) error                  { return nil }
func (s *Store) ListSkillReviewLogs(skillID string) ([]store.SkillReviewLog, error)  { return nil, nil }

func (s *Store) CreateWebhookLog(log *store.WebhookLog) (int64, error)                    { return 0, nil }
func (s *Store) UpdateWebhookLogRequest(id int64, status, url, method, body string) error { return nil }
func (s *Store) UpdateWebhookLogResponse(id int64, status string, respStatus int, respBody string, durationMs int) error {
	return nil
}
func (s *Store) UpdateWebhookLogResult(id int64, status, scriptError string, replies []string) error {
	return nil
}
func (s *Store) UpdateWebhookLogPluginVersion(id int64, version string) error { return nil }
func (s *Store) ListWebhookLogs(botID, channelID string, limit int) ([]store.WebhookLog, error) {
	return nil, nil
}
func (s *Store) CleanOldWebhookLogs(days int) error { return nil }

func (s *Store) CreateEventLog(log *store.AppEventLog) (int64, error) { return 0, nil }
func (s *Store) UpdateEventLogDelivered(id int64, respStatus int, respBody string, durationMs int) error {
	return nil
}
func (s *Store) UpdateEventLogFailed(id int64, errMsg string, retryCount int, durationMs int) error {
	return nil
}
func (s *Store) ListEventLogs(installationID string, limit int) ([]store.AppEventLog, error) {
	return nil, nil
}
func (s *Store) CleanOldAppLogs(days int) error { return nil }

func (s *Store) CreateAPILog(log *store.AppAPILog) error { return nil }
func (s *Store) ListAPILogs(installationID string, limit int) ([]store.AppAPILog, error) {
	return nil, nil
}

func (s *Store) CreateClient(ctx context.Context, c *store.Client) (*store.Client, error) {
	return nil, nil
}
func (s *Store) GetClientByID(ctx context.Context, id string) (*store.Client, error) { return nil, nil }
func (s *Store) GetClient(ctx context.Context, clientID, tenantID string) (*store.Client, error) {
	return nil, nil
}
func (s *Store) GetClientByClientID(ctx context.Context, clientID string) (*store.Client, error) {
	return nil, nil
}
func (s *Store) GetClientByFingerprint(ctx context.Context, fingerprint string) (*store.Client, error) {
	return nil, nil
}
func (s *Store) FindFingerprint(ctx context.Context, fingerprint string) (*store.Client, error) {
	return s.GetClientByFingerprint(ctx, fingerprint)
}
func (s *Store) GetClientByDeviceToken(ctx context.Context, token string) (*store.Client, error) {
	return nil, nil
}
func (s *Store) UpdateClient(ctx context.Context, c *store.Client) error   { return nil }
func (s *Store) UpdateClientLastSeen(ctx context.Context, id string) error { return nil }
func (s *Store) ListClientsByTenant(ctx context.Context, tenantID string) ([]store.Client, error) {
	return nil, nil
}
func (s *Store) RevokeClient(ctx context.Context, id string) error    { return nil }
func (s *Store) CleanExpiredClients(ctx context.Context) (int, error) { return 0, nil }

func (s *Store) CreateBindRequest(ctx context.Context, req *store.BindRequest) (*store.BindRequest, error) {
	return nil, nil
}
func (s *Store) GetBindRequest(ctx context.Context, id string) (*store.BindRequest, error) {
	return nil, nil
}
func (s *Store) UpdateBindRequest(ctx context.Context, req *store.BindRequest) error { return nil }
func (s *Store) ListBindRequestsByTenant(ctx context.Context, tenantID, status string) ([]store.BindRequest, error) {
	return nil, nil
}
func (s *Store) ListBindRequestsByClient(ctx context.Context, clientID string) ([]store.BindRequest, error) {
	return nil, nil
}

func (s *Store) CreateSession(token, userID string, expiresAt time.Time) error { return nil }
func (s *Store) GetSession(token string) (string, time.Time, error)            { return "", time.Time{}, nil }
func (s *Store) DeleteSession(token string) error                              { return nil }
func (s *Store) DeleteExpiredSessions() error                                  { return nil }
func (s *Store) DeleteSessionsByUserID(userID string) error                    { return nil }

func (s *Store) CreateOAuthAccount(a *store.OAuthAccount) error { return nil }
func (s *Store) GetOAuthAccount(provider, providerID string) (*store.OAuthAccount, error) {
	return nil, nil
}
func (s *Store) ListOAuthAccountsByUser(userID string) ([]store.OAuthAccount, error)   { return nil, nil }
func (s *Store) DeleteOAuthAccount(provider, providerID string) error                  { return nil }
func (s *Store) CreateOAuthCode(code, appID, botID, state, codeChallenge string) error { return nil }
func (s *Store) ExchangeOAuthCode(code string) (appID, botID, codeChallenge string, err error) {
	return "", "", "", nil
}
func (s *Store) CleanExpiredOAuthCodes() {}

func (s *Store) CreateScanLoginSession(sessionID, userID string) error { return nil }
func (s *Store) GetScanLoginSession(sessionID string) (*store.ScanLoginSession, error) {
	return nil, nil
}
func (s *Store) UpdateScanLoginSession(session *store.ScanLoginSession) error { return nil }
func (s *Store) DeleteScanLoginSession(sessionID string) error                { return nil }

func (s *Store) CreatePasskey(passkey *store.Passkey) error          { return nil }
func (s *Store) GetPasskey(id string) (*store.Passkey, error)        { return nil, nil }
func (s *Store) ListPasskeys(userID string) ([]store.Passkey, error) { return nil, nil }
func (s *Store) DeletePasskey(id string) error                       { return nil }

func (s *Store) CreatePlugin(p *store.Plugin) (*store.Plugin, error)                 { return nil, nil }
func (s *Store) GetPlugin(id string) (*store.Plugin, error)                          { return nil, nil }
func (s *Store) GetPluginByName(name string) (*store.Plugin, error)                  { return nil, nil }
func (s *Store) ListPlugins() ([]store.PluginWithLatest, error)                      { return nil, nil }
func (s *Store) ListPluginsByOwner(ownerID string) ([]store.PluginWithLatest, error) { return nil, nil }
func (s *Store) UpdatePluginMeta(id string, p *store.Plugin) error                   { return nil }
func (s *Store) DeletePlugin(id string) error                                        { return nil }
func (s *Store) CreatePluginVersion(v *store.PluginVersion) (*store.PluginVersion, error) {
	return nil, nil
}
func (s *Store) GetPluginVersion(id string) (*store.PluginVersion, error)          { return nil, nil }
func (s *Store) ListPluginVersions(pluginID string) ([]store.PluginVersion, error) { return nil, nil }
func (s *Store) ListPendingVersions() ([]store.PluginVersion, error)               { return nil, nil }
func (s *Store) ReviewPluginVersion(id, status, reviewedBy, reason string) error   { return nil }
func (s *Store) CancelPluginVersion(id string) error                               { return nil }
func (s *Store) DeletePluginVersion(id string) error                               { return nil }
func (s *Store) SupersedeNonApprovedVersions(pluginID string)                      {}
func (s *Store) FindPendingVersion(pluginID string) (*store.PluginVersion, error)  { return nil, nil }
func (s *Store) UpdatePluginVersion(id string, v *store.PluginVersion) error       { return nil }
func (s *Store) RecordPluginInstall(pluginID, userID string) error                 { return nil }
func (s *Store) FindPluginOwner(name string) (string, error)                       { return "", nil }
func (s *Store) ResolvePluginScript(versionID string) (script, version string, timeoutSec int, err error) {
	return "", "", 0, nil
}

func (s *Store) AppendSpan(traceID, botID, name, kind, statusCode, statusMessage string, attrs map[string]any) error {
	return nil
}
func (s *Store) InsertSpan(traceID, spanID, parentSpanID, name, kind, statusCode, statusMessage string, startTime, endTime int64, attrsJSON, eventsJSON []byte, botID string) error {
	return nil
}
func (s *Store) ListRootSpans(botID string, limit int) ([]store.TraceSpan, error) { return nil, nil }
func (s *Store) ListSpansByTrace(traceID string) ([]store.TraceSpan, error)       { return nil, nil }

func (s *Store) CreateTask(ctx context.Context, task *store.Task) error      { return nil }
func (s *Store) GetTask(ctx context.Context, id string) (*store.Task, error) { return nil, nil }
func (s *Store) GetTenantTasks(ctx context.Context, tenantID string, status *store.TaskStatus, limit int) ([]*store.Task, error) {
	return nil, nil
}
func (s *Store) GetPendingTasksForClient(ctx context.Context, clientID string, limit int, sinceTaskID string) ([]*store.Task, error) {
	return nil, nil
}
func (s *Store) MarkTaskDelivered(ctx context.Context, id string) bool                   { return true }
func (s *Store) AcknowledgeTask(ctx context.Context, id string) bool                     { return true }
func (s *Store) CompleteTask(ctx context.Context, id string, result map[string]any) bool { return true }
func (s *Store) FailTask(ctx context.Context, id string, errorMessage string) bool       { return true }
func (s *Store) CancelTask(ctx context.Context, id string) bool                          { return true }

func (s *Store) TouchHeartbeat(ctx context.Context, clientID, tenantID string) error { return nil }

func (s *Store) CreateSkillUploadTicket(ctx context.Context, req *store.SkillUploadTicketRequest) (*store.SkillUploadTicket, error) {
	return nil, nil
}
func (s *Store) ConfirmSkillInstall(ctx context.Context, clientID string, confirm *store.SkillInstallConfirm) (bool, error) {
	return true, nil
}
func (s *Store) ReportSkillExecution(ctx context.Context, record *store.SkillExecutionReport) error {
	return nil
}
func (s *Store) GetSkillEvaluation(ctx context.Context, skillID string) (*store.SkillEvaluation, error) {
	return nil, nil
}

func (s *Store) CreateUploadTicket(ctx context.Context, skillID string, ttl int) (*store.UploadTicket, error) {
	return nil, nil
}
func (s *Store) ConfirmUpload(ctx context.Context, ticketID string, success bool, sha256 string, size int64) (bool, error) {
	return true, nil
}
func (s *Store) GetBillingConfig(ctx context.Context) (*store.BillingConfig, error) { return nil, nil }
func (s *Store) GetGarlicLedger(ctx context.Context, clientID string) (*store.GarlicLedger, error) {
	return nil, nil
}
func (s *Store) ReportSearchSignals(ctx context.Context, record *store.SearchSignalsReport) error {
	return nil
}

func (s *Store) SetClock(c store.Clock) {}

func (s *Store) GetConfig(key string) (string, error)                        { return "", nil }
func (s *Store) SetConfig(key, value string) error                           { return nil }
func (s *Store) DeleteConfig(key string) error                               { return nil }
func (s *Store) ListConfigByPrefix(prefix string) (map[string]string, error) { return nil, nil }

func (s *Store) Close() error { return nil }
