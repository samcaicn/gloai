package ai

import (
	"encoding/json"
	"os"
	"strconv"

	"github.com/ceoadmin/CEOadmin/internal/store"
)

// platformLLMV2 is the JSON shape of the platform-injected unified system LLM
// interface (ACC_PRODUCT_CONFIG_V2). Both the Hub (tenants) and the
// tenant-memory sidecar (applications) consume it, so they reach the *same*
// system LLM. It mirrors tenant-memory/internal/config.applySystemLLM.
type platformLLMV2 struct {
	Endpoint       string `json:"endpoint"`
	Authentication struct {
		Attributes struct {
			Token string `json:"token"`
		} `json:"attributes"`
	} `json:"authentication"`
}

// ResolveSystemAIConfig returns the effective system OpenAI-compatible interface
// used by the Hub's tenants and apps.
//
// Preference order:
//  1. The operator-configured global `ai.*` settings (base URL, API key, model,
//     custom headers, embedding model) — the traditional source of truth set via
//     the admin UI.
//  2. When `ai.*` is not configured (no api_key), the platform's unified system
//     LLM interface injected via the ACC_PRODUCT_CONFIG_V2 environment variable,
//     so the Hub's tenants reach the *same* system LLM that the
//     tenant-memory sidecar application uses.
//
// get returns the raw key→value config map for a prefix (e.g.
// store.ListConfigByPrefix). The boolean reports whether a usable config was
// resolved (i.e. a non-empty api key is available via either path).
func ResolveSystemAIConfig(get func(prefix string) (map[string]string, error)) (store.AIConfig, bool) {
	global, _ := get("ai.")
	if global["ai.api_key"] != "" {
		return aiConfigFromDB(global), true
	}
	if cfg := SystemLLMFromEnv(); cfg.APIKey != "" {
		return cfg, true
	}
	return store.AIConfig{}, false
}

// aiConfigFromDB maps the `ai.*` key→value map to a store.AIConfig.
func aiConfigFromDB(global map[string]string) store.AIConfig {
	cfg := store.AIConfig{
		Source:         "builtin",
		BaseURL:        global["ai.base_url"],
		APIKey:         global["ai.api_key"],
		Model:          global["ai.model"],
		SystemPrompt:   global["ai.system_prompt"],
		HideThinking:   global["ai.hide_thinking"] == "true",
		StripMarkdown:  global["ai.strip_markdown"] == "true",
		EmbeddingModel: global["ai.embedding_model"],
	}
	if v := global["ai.max_history"]; v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			cfg.MaxHistory = n
		}
	}
	if v := global["ai.custom_headers"]; v != "" {
		cfg.CustomHeaders = ParseCustomHeaders(v)
	}
	return cfg
}

// SystemLLMFromEnv parses the platform-injected unified system LLM interface
// (ACC_PRODUCT_CONFIG_V2). It is the Hub-side mirror of the tenant-memory
// sidecar's applySystemLLM, so applications and tenants share one system LLM.
// Returns an empty config when the env var is unset or malformed.
func SystemLLMFromEnv() store.AIConfig {
	raw := os.Getenv("ACC_PRODUCT_CONFIG_V2")
	if raw == "" {
		return store.AIConfig{}
	}
	var sys platformLLMV2
	if err := json.Unmarshal([]byte(raw), &sys); err != nil {
		return store.AIConfig{}
	}
	if sys.Endpoint == "" {
		return store.AIConfig{}
	}
	return store.AIConfig{
		Source:  "platform",
		BaseURL: sys.Endpoint,
		APIKey:  sys.Authentication.Attributes.Token,
	}
}

// ParseCustomHeaders parses custom headers from JSON. Supports both array
// format [["key","value"],...] (from frontend) and object format {"key":"value"}.
// It lives in this package so the Hub's `ai.*` resolver and the sink share one
// implementation.
func ParseCustomHeaders(raw string) map[string]string {
	var arr [][2]string
	if json.Unmarshal([]byte(raw), &arr) == nil {
		m := make(map[string]string, len(arr))
		for _, kv := range arr {
			if kv[0] != "" {
				m[kv[0]] = kv[1]
			}
		}
		if len(m) > 0 {
			return m
		}
		return nil
	}
	var m map[string]string
	if json.Unmarshal([]byte(raw), &m) == nil && len(m) > 0 {
		return m
	}
	return nil
}
