package config

import (
	"encoding/json"
	"os"
	"strconv"
)

// Config 来自环境变量，零配置文件。
type Config struct {
	Port         int
	Store        string // "sqlite" | "file"
	DBPath       string // sqlite 用
	DataDir      string // file 模式用（与 edict 的 data/ 对齐）
	LLMBaseURL   string
	LLMAPIKey    string
	LLMModel     string
	EmbedBaseURL string
	EmbedAPIKey  string
	EmbedModel   string
	RetrieveK    int // 每次召回 top-K 条记忆
}

func getenv(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

// Load 读取环境变量，给出合理默认值，并尝试自动接入系统级 LLM 接口。
func Load() *Config {
	port, _ := strconv.Atoi(getenv("PORT", "8080"))
	mode := getenv("STORE", "sqlite")
	if mode != "sqlite" && mode != "file" {
		mode = "sqlite"
	}
	k, _ := strconv.Atoi(getenv("RETRIEVE_K", "5"))
	cfg := &Config{
		Port:         port,
		Store:        mode,
		DBPath:       getenv("DB_PATH", "tenant-memory.db"),
		DataDir:      getenv("DATA_DIR", "data"),
		LLMBaseURL:   getenv("LLM_BASE_URL", ""),
		LLMAPIKey:    getenv("LLM_API_KEY", ""),
		LLMModel:     getenv("LLM_MODEL", "gpt-4o-mini"),
		EmbedBaseURL: getenv("EMBED_BASE_URL", ""),
		EmbedAPIKey:  getenv("EMBED_API_KEY", ""),
		EmbedModel:   getenv("EMBED_MODEL", "text-embedding-3-small"),
		RetrieveK:    k,
	}
	cfg.applySystemLLM()
	return cfg
}

// applySystemLLM 若用户未显式配置 LLM（既没给 BASE_URL 也没给 API_KEY），
// 则尝试从 ACC_PRODUCT_CONFIG_V2（平台统一 LLM 接口）读取端点与令牌。
// 注意：显式设置 LLM_API_KEY=mock 等时不会触发，mock 模式优先。
func (c *Config) applySystemLLM() {
	if c.LLMBaseURL != "" || c.LLMAPIKey != "" {
		return
	}
	raw := os.Getenv("ACC_PRODUCT_CONFIG_V2")
	if raw == "" {
		return
	}
	var sys struct {
		Endpoint       string `json:"endpoint"`
		Authentication struct {
			Attributes struct {
				Token string `json:"token"`
			} `json:"attributes"`
		} `json:"authentication"`
	}
	if err := json.Unmarshal([]byte(raw), &sys); err != nil {
		return
	}
	if sys.Endpoint != "" {
		c.LLMBaseURL = sys.Endpoint
		c.LLMAPIKey = sys.Authentication.Attributes.Token
	}
}
