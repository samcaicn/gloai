// 配置类型定义
export interface AppConfig {
  // API 配置
  api: {
    key: string;
    baseUrl: string;
  };
  // 模型配置
  model: {
    availableModels: Array<{
      id: string;
      name: string;
      supportsImage?: boolean;
    }>;
    defaultModel: string;
    defaultModelProvider?: string;
  };
  // 多模型提供商配置
  providers?: {
    openai: {
      enabled: boolean;
      apiKey: string;
      baseUrl: string;
      // API 协议格式：anthropic 为 Anthropic 兼容，openai 为 OpenAI 兼容
      apiFormat?: 'anthropic' | 'openai';
      models?: Array<{
        id: string;
        name: string;
        supportsImage?: boolean;
      }>;
    };
    deepseek: {
      enabled: boolean;
      apiKey: string;
      baseUrl: string;
      apiFormat?: 'anthropic' | 'openai';
      models?: Array<{
        id: string;
        name: string;
        supportsImage?: boolean;
      }>;
    };
    moonshot: {
      enabled: boolean;
      apiKey: string;
      baseUrl: string;
      apiFormat?: 'anthropic' | 'openai';
      /** 是否启用 Moonshot Coding Plan 模式（使用专属 Coding API 端点） */
      codingPlanEnabled?: boolean;
      models?: Array<{
        id: string;
        name: string;
        supportsImage?: boolean;
      }>;
    };
    zhipu: {
      enabled: boolean;
      apiKey: string;
      baseUrl: string;
      apiFormat?: 'anthropic' | 'openai';
      /** 是否启用 GLM Coding Plan 模式（使用专属 Coding API 端点） */
      codingPlanEnabled?: boolean;
      models?: Array<{
        id: string;
        name: string;
        supportsImage?: boolean;
      }>;
    };
    minimax: {
      enabled: boolean;
      apiKey: string;
      baseUrl: string;
      apiFormat?: 'anthropic' | 'openai';
      models?: Array<{
        id: string;
        name: string;
        supportsImage?: boolean;
      }>;
    };
    qwen: {
      enabled: boolean;
      apiKey: string;
      baseUrl: string;
      apiFormat?: 'anthropic' | 'openai';
      /** 是否启用 Qwen Coding Plan 模式（使用专属 Coding API 端点） */
      codingPlanEnabled?: boolean;
      models?: Array<{
        id: string;
        name: string;
        supportsImage?: boolean;
      }>;
    };
    openrouter: {
      enabled: boolean;
      apiKey: string;
      baseUrl: string;
      apiFormat?: 'anthropic' | 'openai';
      models?: Array<{
        id: string;
        name: string;
        supportsImage?: boolean;
      }>;
    };
    gemini: {
      enabled: boolean;
      apiKey: string;
      baseUrl: string;
      apiFormat?: 'anthropic' | 'openai';
      models?: Array<{
        id: string;
        name: string;
        supportsImage?: boolean;
      }>;
    };
    anthropic: {
      enabled: boolean;
      apiKey: string;
      baseUrl: string;
      apiFormat?: 'anthropic' | 'openai';
      models?: Array<{
        id: string;
        name: string;
        supportsImage?: boolean;
      }>;
    };
    volcengine: {
      enabled: boolean;
      apiKey: string;
      baseUrl: string;
      apiFormat?: 'anthropic' | 'openai';
      /** 是否启用 Volcengine Coding Plan 模式（使用专属 Coding API 端点） */
      codingPlanEnabled?: boolean;
      models?: Array<{
        id: string;
        name: string;
        supportsImage?: boolean;
      }>;
    };
    xiaomi: {
      enabled: boolean;
      apiKey: string;
      baseUrl: string;
      apiFormat?: 'anthropic' | 'openai';
      models?: Array<{
        id: string;
        name: string;
        supportsImage?: boolean;
      }>;
    };
    ollama: {
      enabled: boolean;
      apiKey: string;
      baseUrl: string;
      apiFormat?: 'anthropic' | 'openai';
      models?: Array<{
        id: string;
        name: string;
        supportsImage?: boolean;
      }>;
    };
    custom: {
      enabled: boolean;
      apiKey: string;
      baseUrl: string;
      apiFormat?: 'anthropic' | 'openai';
      models?: Array<{
        id: string;
        name: string;
        supportsImage?: boolean;
      }>;
    };
    [key: string]: {
      enabled: boolean;
      apiKey: string;
      baseUrl: string;
      apiFormat?: 'anthropic' | 'openai';
      codingPlanEnabled?: boolean;
      models?: Array<{
        id: string;
        name: string;
        supportsImage?: boolean;
      }>;
    };
  };
  // 主题配置
  theme: 'light' | 'dark' | 'system';
  // 语言配置
  language: 'zh' | 'en';
  // 是否使用系统代理
  useSystemProxy: boolean;
  // 语言初始化标记 (用于判断是否是首次启动)
  language_initialized?: boolean;
  // 应用配置
  app: {
    port: number;
    isDevelopment: boolean;
  };
  // 快捷键配置
  shortcuts?: {
    newChat: string;
    search: string;
    settings: string;
    [key: string]: string | undefined;
  };
}

// 默认配置
export const defaultConfig: AppConfig = {
  api: {
    key: '',
    baseUrl: 'https://aiapi.tuptup.top',
  },
  model: {
    availableModels: [
      { id: 'tuptup', name: 'TupTup AI', supportsImage: true },
    ],
    defaultModel: 'tuptup',
    defaultModelProvider: 'tuptup',
  },
  providers: {
    tuptup: {
      enabled: true,
      apiKey: '',
      baseUrl: 'https://aiapi.tuptup.top',
      apiFormat: 'openai',
      models: [
        { id: 'tuptup', name: 'TupTup AI', supportsImage: true }
      ]
    },
    custom: {
      enabled: false,
      apiKey: '',
      baseUrl: '',
      apiFormat: 'openai',
      models: []
    }
  },
  theme: 'system',
  language: 'zh',
  useSystemProxy: false,
  app: {
    port: 3000,
    isDevelopment: process.env.NODE_ENV === 'development',
  },
  shortcuts: {
    newChat: 'Ctrl+N',
    search: 'Ctrl+F',
    settings: 'Ctrl+,',
  }
};

// 配置存储键
export const CONFIG_KEYS = {
  APP_CONFIG: 'app_config',
  AUTH: 'auth_state',
  CONVERSATIONS: 'conversations',
  PROVIDERS_EXPORT_KEY: 'providers_export_key',
  SKILLS: 'skills',
};

// 模型提供商分类
export const CHINA_PROVIDERS = ['tuptup', 'deepseek', 'moonshot', 'qwen', 'zhipu', 'minimax', 'xiaomi', 'volcengine', 'ollama', 'custom'] as const;
export const GLOBAL_PROVIDERS = ['openai', 'gemini', 'anthropic', 'openrouter'] as const;
export const EN_PRIORITY_PROVIDERS = ['tuptup', 'openai', 'anthropic', 'gemini'] as const;

/**
 * 根据语言获取可见的模型提供商
 */
export const getVisibleProviders = (language: 'zh' | 'en'): readonly string[] => {
  // 开发环境下显示所有提供商
  // if (import.meta.env.DEV) {
  //   return [...CHINA_PROVIDERS, ...GLOBAL_PROVIDERS];
  // }

  // 中文 → 中国版，英文 → 国际版
  if (language === 'zh') {
    return CHINA_PROVIDERS;
  }

  const orderedProviders = [
    ...EN_PRIORITY_PROVIDERS,
    ...CHINA_PROVIDERS,
    ...GLOBAL_PROVIDERS,
  ];
  const uniqueProviders = [...new Set(orderedProviders)];
  // Move ollama and custom to the end, with custom last
  for (const key of ['ollama', 'custom'] as const) {
    const idx = uniqueProviders.indexOf(key);
    if (idx !== -1) {
      uniqueProviders.splice(idx, 1);
      uniqueProviders.push(key);
    }
  }
  return uniqueProviders;
};
