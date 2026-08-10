package store

import (
	"context"
	"time"
)

// Store is the main database interface.
type Store interface {
	// Config
	GetConfig(key string) (string, error)
	SetConfig(key, value string) error
	DeleteConfig(key string) error
	ListConfigByPrefix(prefix string) (map[string]string, error)

	// Users
	CreateUserFull(username, email, displayName, passwordHash, role string) (*User, error)
	GetUserByID(id string) (*User, error)
	GetUserByUsername(username string) (*User, error)
	GetUserByEmail(email string) (*User, error)
	UserCount() (int, error)
	UpdateUser(user *User) error
	GetUsers(limit, offset int) ([]*User, error)

	// Bots
	CreateBot(userID, name, provider, providerID, credentials string) (*Bot, error)
	GetBotByID(id string) (*Bot, error)
	GetBotsByUser(userID string) ([]*Bot, error)
	UpdateBot(bot *Bot) error
	DeleteBot(id string) error
	FindBotByProviderID(provider, providerID string) (*Bot, error)
	FindBotByCredential(key, value string) (*Bot, error)
	UpdateBotCredentials(botID, providerID, credentials string) error
	GetBots() ([]*Bot, error)

	// Channels
	CreateChannel(botID, name, channelType string, config, credentials string) (*Channel, error)
	GetChannelByID(id string) (*Channel, error)
	GetChannelsByBot(botID string) ([]*Channel, error)
	UpdateChannel(channel *Channel) error
	DeleteChannel(id string) error
	RotateChannelKey(ctx context.Context, channelID string) (string, error)

	// Messages
	CreateMessage(msg *Message) error
	GetMessage(id string) (*Message, error)
	ListMessages(botID, channelID string, limit, offset int) ([]*Message, error)
	UpdateMessage(msg *Message) error

	// Apps
	SeedApps(s Store) error
	BackfillAllBots(s Store) error
	GetApp(slug string) (*App, error)
	ListApps(listing string) ([]*App, error)
	CreateAppInstallation(inst *AppInstallation) error
	GetAppInstallation(id string) (*AppInstallation, error)
	ListInstallationsByBot(botID string) ([]*AppInstallation, error)
	ListInstallationsByApp(appID string) ([]*AppInstallation, error)
	UpdateAppInstallation(inst *AppInstallation) error
	DeleteAppInstallation(id string) error

	// Registry
	ListRegistries() ([]*Registry, error)
	UpsertRegistry(reg *Registry) error

	// Media
	RecordMediaUsage(rec *MediaUsageRecord) error

	// LLM Usage
	RecordLLMUsage(rec *LLMUsageRecord) error

	// Skills
	RecordSkillUsage(rec *SkillUsageRecord) error

	// Webhook logs
	CreateWebhookLog(log *WebhookLog) error
	GetWebhookLogs(appID string, limit int) ([]*WebhookLog, error)

	// Client/device management
	CreateClient(ctx context.Context, client *Client) error
	GetClientByID(ctx context.Context, id string) (*Client, error)
	GetClientByClientID(ctx context.Context, clientID string) (*Client, error)
	GetClientByFingerprint(ctx context.Context, fingerprint string) (*Client, error)
	GetClientByDeviceToken(ctx context.Context, token string) (*Client, error)
	UpdateClient(ctx context.Context, client *Client) error
	UpdateClientLastSeen(ctx context.Context, id string) error
	ListClientsByTenant(ctx context.Context, tenantID string) ([]*Client, error)

	// Bind requests
	CreateBindRequest(ctx context.Context, req *BindRequest) error
	GetBindRequest(ctx context.Context, id string) (*BindRequest, error)
	UpdateBindRequest(ctx context.Context, req *BindRequest) error
	ListBindRequestsByTenant(ctx context.Context, tenantID, status string) ([]*BindRequest, error)
	ListBindRequestsByClient(ctx context.Context, clientID string) ([]*BindRequest, error)

	// Skills marketplace
	ListSkills(params SkillListParams) ([]*Skill, error)
	GetSkill(id string) (*Skill, error)
	ListSkillVersions(skillID string) ([]*SkillVersion, error)
	ListSkillRatings(skillID string) ([]*SkillRating, error)
	CreateSkill(skill *Skill) error
	CreateSkillVersion(version *SkillVersion) error
	UpdateSkillRating(skillID string, rating float64, comment string) error

	// Sessions
	CreateSession(userID string) (string, error)
	ValidateSession(token string) (string, error)
	DeleteSession(token string) error
	GetSession(token string) (*Session, error)
	DeleteExpiredSessions() error

	// Passwords
	UpdatePassword(userID, newHash string) error

	// OAuth
	CreateOAuthState(state string, bindUID string) error
	ValidateOAuthState(state string) (string, error)
	DeleteOAuthState(state string) error

	// Auth scan
	CreateScanLoginSession(sessionID, userID string) error
	GetScanLoginSession(sessionID string) (*ScanLoginSession, error)
	UpdateScanLoginSession(session *ScanLoginSession) error
	DeleteScanLoginSession(sessionID string) error

	// Passkeys
	CreatePasskey(passkey *Passkey) error
	GetPasskey(id string) (*Passkey, error)
	ListPasskeys(userID string) ([]*Passkey, error)
	DeletePasskey(id string) error

	// LLM Usage
	RecordLLMUsageRecord(rec *LLMUsageRecord) error

	// Media Usage
	RecordMediaUsageRecord(rec *MediaUsageRecord) error

	// Skills Usage
	RecordSkillUsageRecord(rec *SkillUsageRecord) error

	// Close
	Close() error
}

// Clock provides time abstraction for testing.
type Clock interface {
	Now() time.Time
}

type RealClock struct{}

func (RealClock) Now() time.Time { return time.Now() }

func Now() int64 { return time.Now().Unix() }
