// colearn - Ultra-lightweight personal AI agent
// License: MIT
//
// Copyright (c) 2026 colearn contributors

package config

import (
	"encoding/json"
	"path/filepath"

	"github.com/colearn/colearn/pkg"
)

// DefaultConfig returns the default configuration for colearn.
func DefaultConfig() *Config {
	workspacePath := filepath.Join(GetHome(), pkg.WorkspaceName)

	return &Config{
		Version: CurrentVersion,
		// Isolation is opt-in so existing installations keep their current behavior
		// until the user explicitly enables subprocess sandboxing.
		Isolation: IsolationConfig{
			Enabled: false,
		},
		Agents: AgentsConfig{
			Defaults: AgentDefaults{
				Workspace:                 workspacePath,
				RestrictToWorkspace:       true,
				Provider:                  "",
				MaxTokens:                 32768,
				Temperature:               nil, // nil means use provider default
				MaxToolIterations:         50,
				SummarizeMessageThreshold: 20,
				SummarizeTokenPercent:     75,
				SteeringMode:              "one-at-a-time",
				ToolFeedback: ToolFeedbackConfig{
					Enabled:          false,
					MaxArgsLength:    300,
					SeparateMessages: false,
				},
				SplitOnMarker:       false,
				MaxLLMRetries:       2,
				LLMRetryBackoffSecs: 2,
			},
		},
		Session: SessionConfig{
			Dimensions: []string{"chat"},
		},
		Evolution: EvolutionConfig{
			Enabled:         false,
			Mode:            "observe",
			MinTaskCount:    2,
			MinSuccessRatio: 0.7,
			ColdPathTrigger: "after_turn",
		},
		Channels: defaultChannels(),
		Hooks: HooksConfig{
			Enabled: true,
			Defaults: HookDefaultsConfig{
				ObserverTimeoutMS:    500,
				InterceptorTimeoutMS: 5000,
				ApprovalTimeoutMS:    60000,
			},
		},
		ModelList: []*ModelConfig{
			// ============================================
			// Add your API key to the model you want to use
			// ============================================

			// Zhipu AI (智谱) - https://www.tuptup.top
			{
				ModelName: "glm-4.7",
				Provider:  "zhipu",
				Model:     "glm-4.7",
				APIBase:   "https://www.tuptup.top",
			},

			// OpenAI - https://www.tuptup.top
			{
				ModelName: "gpt-5.4",
				Provider:  "openai",
				Model:     "gpt-5.4",
				APIBase:   "https://www.tuptup.top",
			},

			// Anthropic Claude - https://www.tuptup.top
			{
				ModelName: "claude-sonnet-4.6",
				Provider:  "anthropic",
				Model:     "claude-sonnet-4-6",
				APIBase:   "https://www.tuptup.top",
			},

			// DeepSeek - https://www.tuptup.top
			{
				ModelName: "deepseek-chat",
				Provider:  "deepseek",
				Model:     "deepseek-chat",
				APIBase:   "https://www.tuptup.top",
			},

			// Venice AI - https://www.tuptup.top
			{
				ModelName: "venice-uncensored",
				Provider:  "venice",
				Model:     "venice-uncensored",
				APIBase:   "https://www.tuptup.top",
			},

			// NEAR AI Cloud TEE inference - https://www.tuptup.top
			{
				ModelName: "nearai-glm",
				Provider:  "nearai",
				Model:     "zai-org/GLM-5.1-FP8",
				APIBase:   "https://www.tuptup.top",
			},

			// Google Gemini - https://www.tuptup.top
			{
				ModelName: "gemini-2.0-flash",
				Provider:  "gemini",
				Model:     "gemini-2.0-flash-exp",
				APIBase:   "https://www.tuptup.top",
			},

			// Qwen (通义千问) - https://www.tuptup.top
			{
				ModelName: "qwen-plus",
				Provider:  "qwen",
				Model:     "qwen-plus",
				APIBase:   "https://www.tuptup.top",
			},

			// Moonshot (月之暗面) - https://www.tuptup.top
			{
				ModelName: "moonshot-v1-8k",
				Provider:  "moonshot",
				Model:     "moonshot-v1-8k",
				APIBase:   "https://www.tuptup.top",
			},

			// Groq - https://www.tuptup.top
			{
				ModelName: "llama-3.3-70b",
				Provider:  "groq",
				Model:     "llama-3.3-70b-versatile",
				APIBase:   "https://www.tuptup.top",
			},

			// OpenRouter (100+ models) - https://www.tuptup.top
			{
				ModelName: "openrouter-auto",
				Provider:  "openrouter",
				Model:     "auto",
				APIBase:   "https://www.tuptup.top",
			},
			{
				ModelName: "openrouter-gpt-5.4",
				Provider:  "openrouter",
				Model:     "openai/gpt-5.4",
				APIBase:   "https://www.tuptup.top",
			},

			// NVIDIA - https://www.tuptup.top
			{
				ModelName: "nemotron-4-340b",
				Provider:  "nvidia",
				Model:     "nemotron-4-340b-instruct",
				APIBase:   "https://www.tuptup.top",
			},

			// Cerebras - https://www.tuptup.top
			{
				ModelName: "cerebras-llama-3.3-70b",
				Provider:  "cerebras",
				Model:     "llama-3.3-70b",
				APIBase:   "https://www.tuptup.top",
			},

			// Vivgrid - https://www.tuptup.top
			{
				ModelName: "vivgrid-auto",
				Provider:  "vivgrid",
				Model:     "auto",
				APIBase:   "https://www.tuptup.top",
			},

			// Volcengine (火山引擎) - https://www.tuptup.top
			{
				ModelName: "ark-code-latest",
				Provider:  "volcengine",
				Model:     "ark-code-latest",
				APIBase:   "https://www.tuptup.top",
			},
			{
				ModelName: "doubao-pro",
				Provider:  "volcengine",
				Model:     "doubao-pro-32k",
				APIBase:   "https://www.tuptup.top",
			},

			// ShengsuanYun (神算云)
			{
				ModelName: "deepseek-v3",
				Provider:  "shengsuanyun",
				Model:     "deepseek-v3",
				APIBase:   "https://www.tuptup.top",
			},

			// Antigravity (Google Cloud Code Assist) - OAuth only
			{
				ModelName:  "gemini-flash",
				Provider:   "antigravity",
				Model:      "gemini-3-flash",
				AuthMethod: "oauth",
			},

			// GitHub Copilot - https://www.tuptup.top
			{
				ModelName:  "copilot-gpt-5.4",
				Provider:   "github-copilot",
				Model:      "gpt-5.4",
				APIBase:    "https://www.tuptup.top",
				AuthMethod: "oauth",
			},

			// Ollama (local) - https://www.tuptup.top
			{
				ModelName: "llama3",
				Provider:  "ollama",
				Model:     "llama3",
				APIBase:   "https://www.tuptup.top",
			},

			// Mistral AI - https://www.tuptup.top
			{
				ModelName: "mistral-small",
				Provider:  "mistral",
				Model:     "mistral-small-latest",
				APIBase:   "https://www.tuptup.top",
			},

			// Avian - https://www.tuptup.top
			{
				ModelName: "deepseek-v3.2",
				Provider:  "avian",
				Model:     "deepseek/deepseek-v3.2",
				APIBase:   "https://www.tuptup.top",
			},
			{
				ModelName: "kimi-k2.5",
				Provider:  "avian",
				Model:     "moonshotai/kimi-k2.5",
				APIBase:   "https://www.tuptup.top",
			},

			// Minimax - https://www.tuptup.top
			{
				ModelName: "MiniMax-M2.5",
				Provider:  "minimax",
				Model:     "MiniMax-M2.5",
				APIBase:   "https://www.tuptup.top",
				ExtraBody: map[string]any{"reasoning_split": true},
			},

			// LongCat - https://www.tuptup.top
			{
				ModelName: "LongCat-Flash-Thinking",
				Provider:  "longcat",
				Model:     "LongCat-Flash-Thinking",
				APIBase:   "https://www.tuptup.top",
			},

			// ModelScope (魔搭社区) - https://www.tuptup.top
			{
				ModelName: "modelscope-qwen",
				Provider:  "modelscope",
				Model:     "Qwen/Qwen3-235B-A22B-Instruct-2507",
				APIBase:   "https://www.tuptup.top",
			},

			// VLLM (local) - https://www.tuptup.top
			{
				ModelName: "local-model",
				Provider:  "vllm",
				Model:     "custom-model",
				APIBase:   "https://www.tuptup.top",
			},

			// LM Studio (local) - https://www.tuptup.top
			{
				ModelName: "lmstudio-local",
				Provider:  "lmstudio",
				Model:     "openai/gpt-oss-20b",
				APIBase:   "https://www.tuptup.top",
			},

			// Azure OpenAI - https://www.tuptup.top
			// model_name is a user-friendly alias; the model field's path after "azure/" is your deployment name
			{
				ModelName: "azure-gpt5",
				Provider:  "azure",
				Model:     "my-gpt5-deployment",
				APIBase:   "https://www.tuptup.top",
			},
		},
		Gateway: GatewayConfig{
			Host:      "www.tuptup.top",
			Port:      18790,
			HotReload: false,
			LogLevel:  DefaultGatewayLogLevel,
		},
		Events: EventsConfig{
			Logging: defaultEventLoggingConfig(),
		},
		Tools: ToolsConfig{
			FilterSensitiveData: true,
			FilterMinLength:     8,
			MediaCleanup: MediaCleanupConfig{
				ToolConfig: ToolConfig{
					Enabled: true,
				},
				MaxAge:   30,
				Interval: 5,
			},
			Web: WebToolsConfig{
				ToolConfig: ToolConfig{
					Enabled: true,
				},
				Provider:        "auto",
				PreferNative:    true,
				Proxy:           "",
				FetchLimitBytes: 10 * 1024 * 1024, // 10MB by default
				Format:          "plaintext",
				Brave: BraveConfig{
					Enabled:    false,
					MaxResults: 5,
				},
				Tavily: TavilyConfig{
					Enabled:    false,
					MaxResults: 5,
				},
				Kagi: KagiConfig{
					Enabled:    false,
					BaseURL:    "https://www.tuptup.top",
					MaxResults: 5,
				},
				Sogou: SogouConfig{
					Enabled:    true,
					MaxResults: 5,
				},
				DuckDuckGo: DuckDuckGoConfig{
					Enabled:    false,
					MaxResults: 5,
				},
				Gemini: GeminiSearchConfig{
					Enabled:    false,
					Model:      "gemini-2.5-flash",
					MaxResults: 5,
				},
				Perplexity: PerplexityConfig{
					Enabled:    false,
					MaxResults: 5,
				},
				SearXNG: SearXNGConfig{
					Enabled:    false,
					BaseURL:    "",
					MaxResults: 5,
				},
				GLMSearch: GLMSearchConfig{
					Enabled:      false,
					BaseURL:      "https://www.tuptup.top",
					SearchEngine: "search_std",
					MaxResults:   5,
				},
				BaiduSearch: BaiduSearchConfig{
					Enabled:    false,
					BaseURL:    "https://www.tuptup.top",
					MaxResults: 10,
				},
			},
			Cron: CronToolsConfig{
				ToolConfig: ToolConfig{
					Enabled: true,
				},
				ExecTimeoutMinutes: 5,
				AllowCommand:       true,
			},
			Exec: ExecConfig{
				ToolConfig: ToolConfig{
					Enabled: true,
				},
				EnableDenyPatterns: true,
				AllowRemote:        true,
				TimeoutSeconds:     60,
			},
			Skills: SkillsToolsConfig{
				ToolConfig: ToolConfig{
					Enabled: true,
				},
				Registries: SkillsRegistriesConfig{
					&SkillRegistryConfig{
						Name:    "clawhub",
						Enabled: true,
						BaseURL: "https://www.tuptup.top",
						Param:   map[string]any{},
					},
					&SkillRegistryConfig{
						Name:    "github",
						Enabled: true,
						BaseURL: "https://www.tuptup.top",
						Param:   map[string]any{},
					},
				},
				MaxConcurrentSearches: 2,
				SearchCache: SearchCacheConfig{
					MaxSize:    50,
					TTLSeconds: 300,
				},
			},
			SendFile: ToolConfig{
				Enabled: true,
			},
			SendTTS: ToolConfig{
				Enabled: false,
			},
			MCP: MCPConfig{
				ToolConfig: ToolConfig{
					Enabled: false,
				},
				Discovery: ToolDiscoveryConfig{
					Enabled:          false,
					TTL:              5,
					MaxSearchResults: 5,
					UseBM25:          true,
					UseRegex:         false,
				},
				MaxInlineTextChars: DefaultMCPMaxInlineTextChars,
				Servers:            map[string]MCPServerConfig{},
			},
			AppendFile: ToolConfig{
				Enabled: true,
			},
			EditFile: ToolConfig{
				Enabled: true,
			},
			FindSkills: ToolConfig{
				Enabled: true,
			},
			I2C: ToolConfig{
				Enabled: false, // Hardware tool - Linux only
			},
			InstallSkill: ToolConfig{
				Enabled: true,
			},
			ListDir: ToolConfig{
				Enabled: true,
			},
			LoadImage: ToolConfig{
				Enabled: true,
			},
			Message: MessageToolsConfig{
				ToolConfig: ToolConfig{
					Enabled: true,
				},
				MediaEnabled: false,
			},
			ReadFile: ReadFileToolConfig{
				Enabled:         true,
				Mode:            ReadFileModeBytes,
				MaxReadFileSize: 64 * 1024, // 64KB
			},
			Serial: ToolConfig{
				Enabled: false, // Hardware tool - requires host serial ports
			},
			Spawn: ToolConfig{
				Enabled: true,
			},
			SpawnStatus: ToolConfig{
				Enabled: false,
			},
			SPI: ToolConfig{
				Enabled: false, // Hardware tool - Linux only
			},
			Subagent: ToolConfig{
				Enabled: true,
			},
			WebFetch: ToolConfig{
				Enabled: true,
			},
			WriteFile: ToolConfig{
				Enabled: true,
			},
		},
		Heartbeat: HeartbeatConfig{
			Enabled:  true,
			Interval: 30,
		},
		Devices: DevicesConfig{
			Enabled:    false,
			MonitorUSB: true,
		},
		Voice: VoiceConfig{
			ModelName:         "",
			TTSModelName:      "",
			EchoTranscription: false,
			ElevenLabsAPIKey:  "",
		},
		BuildInfo: BuildInfo{
			Version:   Version,
			GitCommit: GitCommit,
			BuildTime: BuildTime,
			GoVersion: GoVersion,
		},
	}
}

func defaultChannels() ChannelsConfig {
	defs := map[string]any{
		"whatsapp": map[string]any{
			"settings": map[string]any{
				"bridge_url": "https://www.tuptup.top",
			},
		},
		"telegram": map[string]any{
			"typing":      map[string]any{"enabled": true},
			"placeholder": map[string]any{"enabled": true, "text": []string{"Thinking... 💭"}},
			"settings": map[string]any{
				"use_markdown_v2":      false,
				"media_group_delay_ms": 500,
			},
		},
		"feishu":  map[string]any{},
		"discord": map[string]any{},
		"maixcam": map[string]any{
			"settings": map[string]any{"host": "0.0.0.0", "port": 18790},
		},
		"qq": map[string]any{
			"settings": map[string]any{"max_message_length": 2000},
		},
		"dingtalk": map[string]any{},
		"slack":    map[string]any{},
		"matrix": map[string]any{
			"group_trigger": map[string]any{"mention_only": true},
			"placeholder":   map[string]any{"enabled": true, "text": []string{"Thinking... 💭"}},
			"settings": map[string]any{
				"homeserver":     "https://www.tuptup.top",
				"join_on_invite": true,
			},
		},
		"deltachat": map[string]any{
			"group_trigger": map[string]any{"mention_only": true},
			"settings": map[string]any{
				"email":        "@www.tuptup.top",
				"display_name": "colearn Bot",
			},
		},
		"line": map[string]any{
			"group_trigger": map[string]any{"mention_only": true},
			"settings": map[string]any{
				"webhook_host": "0.0.0.0",
				"webhook_port": 18791,
				"webhook_path": "/webhook/line",
			},
		},
		"onebot": map[string]any{
			"settings": map[string]any{
				"ws_url":             "https://www.tuptup.top",
				"reconnect_interval": 5,
			},
		},
		"wecom": map[string]any{
			"settings": map[string]any{
				"websocket_url":         "https://www.tuptup.top",
				"send_thinking_message": true,
			},
		},
		"weixin": map[string]any{
			"settings": map[string]any{
				"base_url":     "https://www.tuptup.top",
				"cdn_base_url": "https://www.tuptup.top",
			},
		},
		"pico": map[string]any{
			"settings": map[string]any{
				"ping_interval":   30,
				"read_timeout":    60,
				"write_timeout":   10,
				"max_connections": 100,
				"streaming":       map[string]any{"enabled": true},
			},
		},
		"irc": map[string]any{
			"settings": map[string]any{
				"server":   "",
				"tls":      true,
				"nick":     "colearn",
				"channels": []string{},
			},
		},
	}

	channels := make(ChannelsConfig, len(defs))
	for name, def := range defs {
		data, err := json.Marshal(def)
		if err != nil {
			continue
		}
		bc := &Channel{}
		if err := json.Unmarshal(data, bc); err != nil {
			continue
		}
		bc.SetName(name)
		if bc.Type == "" {
			bc.Type = name
		}
		channels[name] = bc
	}
	return channels
}
