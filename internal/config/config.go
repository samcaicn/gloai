package config

import (
	"os"
	"strconv"
	"strings"
	"time"
)

// Config holds the application configuration loaded from environment variables.
type Config struct {
	// Server
	ListenAddr string

	// Database
	DBPath  string
	DataDir string

	// Authentication
	RPID     string
	RPName   string
	RPOrigin string
	Secret   string

	// Storage
	StorageEndpoint  string
	StorageAccessKey string
	StorageSecretKey string
	StorageBucket    string
	StorageSSL       bool
	StoragePath      string
	StoragePublicURL string

	// Cold tier
	ColdExportEnabled  bool
	ColdExportFormat   string
	ColdExportPrefix   string
	ColdExportInterval time.Duration
	ColdHotRetention   time.Duration
	ColdCacheMB        int64

	// Snapshot
	SnapshotInterval time.Duration
	SnapshotKeep     int

	// AI defaults (overridden by provider-specific configs)
	EmbeddingModel string

	// OpenID Connect providers
	OIDCProviders []OIDCProviderConfig

	// OAuth client credentials (env fallback)
	ClientID            string
	ClientSecret        string
	LinuxDoClientID     string
	LinuxDoClientSecret string

	// AppProxies maps app slug to upstream URL for reverse proxy mount
	AppProxies map[string]string
}

// OIDCProviderConfig holds configuration for an OIDC/OAuth login provider.
type OIDCProviderConfig struct {
	Slug         string
	DisplayName  string
	IssuerURL    string
	ClientID     string
	ClientSecret string
	Scopes       string
	AuthURL      string
	TokenURL     string
	UserInfoURL  string
}

// Load reads configuration from environment variables with sensible defaults.
func Load() *Config {
	cfg := &Config{
		ListenAddr: getenv("HUB_LISTEN", "0.0.0.0:9800"),
		DBPath:     getenv("DATABASE_URL", ""),
		DataDir:    getenv("DATA_DIR", ""),
		RPID:       getenv("RP_ID", "localhost"),
		RPName:     getenv("RP_NAME", "CEOadmin"),
		RPOrigin:   getenv("RP_ORIGIN", "http://localhost:9800"),
		Secret:     getenv("SECRET", "ceoadmin"),
	}

	// Storage
	cfg.StorageEndpoint = getenv("STORAGE_ENDPOINT", "")
	cfg.StorageAccessKey = getenv("STORAGE_ACCESS_KEY", "")
	cfg.StorageSecretKey = getenv("STORAGE_SECRET_KEY", "")
	cfg.StorageBucket = getenv("STORAGE_BUCKET", "ceoadmin")
	cfg.StorageSSL = getenv("STORAGE_SSL", "false") == "true"
	cfg.StoragePath = getenv("STORAGE_PATH", "")
	cfg.StoragePublicURL = getenv("STORAGE_PUBLIC_URL", "")

	// Cold tier
	cfg.ColdExportEnabled = getenv("COLD_EXPORT_ENABLED", "false") == "true"
	cfg.ColdExportFormat = getenv("COLD_EXPORT_FORMAT", "parquet")
	cfg.ColdExportPrefix = getenv("COLD_EXPORT_PREFIX", "cold/")
	if v, _ := strconv.Atoi(getenv("COLD_CACHE_MB", "512")); v > 0 {
		cfg.ColdCacheMB = int64(v)
	}
	if v, _ := time.ParseDuration(getenv("COLD_EXPORT_INTERVAL", "0")); v > 0 {
		cfg.ColdExportInterval = v
	}
	if v, _ := time.ParseDuration(getenv("COLD_HOT_RETENTION", "0")); v > 0 {
		cfg.ColdHotRetention = v
	}

	// Snapshot
	if v, _ := time.ParseDuration(getenv("SNAPSHOT_INTERVAL", "0")); v > 0 {
		cfg.SnapshotInterval = v
	}
	if v, _ := strconv.Atoi(getenv("SNAPSHOT_KEEP", "7")); v > 0 {
		cfg.SnapshotKeep = v
	}

	// Embedding model default
	cfg.EmbeddingModel = getenv("EMBEDDING_MODEL", "text-embedding-3-small")

	// OAuth env fallback
	cfg.ClientID = getenv("OAUTH_CLIENT_ID", "")
	cfg.ClientSecret = getenv("OAUTH_CLIENT_SECRET", "")
	cfg.LinuxDoClientID = getenv("LINUXDO_CLIENT_ID", "")
	cfg.LinuxDoClientSecret = getenv("LINUXDO_CLIENT_SECRET", "")

	// App proxies
	cfg.AppProxies = ParseCustomHeaders(getenv("APP_PROXIES", ""))

	if cfg.DataDir == "" {
		cfg.DataDir = DataDir()
	}
	if cfg.DBPath == "" {
		cfg.DBPath = DefaultDBPath()
	}

	return cfg
}

func getenv(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func ParseCustomHeaders(v string) map[string]string {
	m := make(map[string]string)
	for _, pair := range strings.Split(v, ",") {
		parts := strings.SplitN(pair, "=", 2)
		if len(parts) == 2 {
			m[strings.TrimSpace(parts[0])] = strings.TrimSpace(parts[1])
		}
	}
	return m
}
