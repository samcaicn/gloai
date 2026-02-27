// 缓存 Tauri 可用性状态
let _isTauriAvailable: boolean | null = null;

// 解析 SKILL.md 文件的 frontmatter
const parseFrontmatter = (raw: string): { frontmatter: Record<string, string>; content: string } => {
  const normalized = raw.replace(/^\uFEFF/, '');
  const FRONTMATTER_RE = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?/;
  const match = normalized.match(FRONTMATTER_RE);
  if (!match) {
    return { frontmatter: {}, content: normalized };
  }

  const frontmatter: Record<string, string> = {};
  const lines = match[1].split(/\r?\n/);
  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const kv = trimmed.match(/^([A-Za-z0-9_-]+):\s*(.*)$/);
    if (!kv) continue;
    const key = kv[1];
    const value = (kv[2] ?? '').trim().replace(/^['"]|['"]$/g, '');
    frontmatter[key] = value;
  }

  const content = normalized.slice(match[0].length);
  return { frontmatter, content };
};

// 提取技能描述
const extractDescription = (content: string): string => {
  const lines = content.split(/\r?\n/);
  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    return trimmed.replace(/^#+\s*/, '');
  }
  return '';
};

// 检查字符串是否为 truthy
const isTruthy = (value?: string): boolean => {
  if (!value) return false;
  const normalized = value.trim().toLowerCase();
  return normalized === 'true' || normalized === 'yes' || normalized === '1';
};

// 浏览器模式下从 SKILLs 目录加载技能
const loadSkillsFromDirectory = async (): Promise<Skill[]> => {
  try {
    console.log('[tauriApi] Loading skills from directory in browser mode');
    
    // 技能文件夹列表（基于 skills.config.json）
    const skillIds = [
      'docx', 'web-search', 'xlsx', 'pptx', 'pdf', 'scheduled-task',
      'remotion', 'develop-web-game', 'playwright', 'create-plan',
      'canvas-design', 'frontend-design', 'local-tools', 'weather', 'skill-creator'
    ];

    // 加载 skills.config.json
    let skillsConfig: any = {};
    try {
      const configResponse = await fetch('/SKILLs/skills.config.json');
      if (configResponse.ok) {
        skillsConfig = await configResponse.json();
        console.log('[tauriApi] Loaded skills.config.json:', skillsConfig);
      }
    } catch (e) {
      console.warn('[tauriApi] Failed to load skills.config.json:', e);
    }

    const defaults = skillsConfig.defaults || {};
    const skills: Skill[] = [];

    for (const skillId of skillIds) {
      try {
        const skillMdPath = `/SKILLs/${skillId}/SKILL.md`;
        const response = await fetch(skillMdPath);
        
        if (!response.ok) {
          console.warn(`[tauriApi] Skill ${skillId} not found at ${skillMdPath}`);
          continue;
        }

        const raw = await response.text();
        const { frontmatter, content } = parseFrontmatter(raw);
        
        const name = (frontmatter.name || skillId).trim() || skillId;
        const description = (frontmatter.description || extractDescription(content) || name).trim();
        const isOfficial = isTruthy(frontmatter.official) || isTruthy(frontmatter.isOfficial);
        const defaultEnabled = defaults[skillId]?.enabled ?? true;
        
        skills.push({
          id: skillId,
          name: name,
          description: description,
          version: frontmatter.version || '1.0.0',
          author: frontmatter.author || '',
          enabled: defaultEnabled,
          path: `/SKILLs/${skillId}`,
          metadata: {
            is_official: isOfficial,
            is_built_in: true,
            updated_at: Date.now(),
            prompt: content.trim(),
            skill_path: skillMdPath,
            order: defaults[skillId]?.order ?? 999
          }
        });
      } catch (e) {
        console.warn(`[tauriApi] Failed to load skill ${skillId}:`, e);
      }
    }

    // 按 order 排序
    skills.sort((a, b) => {
      const orderA = (a.metadata?.order as number) ?? 999;
      const orderB = (b.metadata?.order as number) ?? 999;
      if (orderA !== orderB) return orderA - orderB;
      return a.name.localeCompare(b.name);
    });

    console.log('[tauriApi] Loaded', skills.length, 'skills in browser mode');
    return skills;
  } catch (error) {
    console.error('[tauriApi] Failed to load skills from directory:', error);
    return [];
  }
};

// 异步检测 Tauri 是否可用（通过实际调用验证）
export const checkTauriAvailability = async (): Promise<boolean> => {
  if (_isTauriAvailable !== null) return _isTauriAvailable;
  
  if (typeof window === 'undefined') {
    _isTauriAvailable = false;
    return false;
  }
  
  try {
    // 动态导入并尝试调用一个简单的命令
    const { invoke } = await import('@tauri-apps/api/core');
    await invoke('get_platform');
    _isTauriAvailable = true;
    console.log('[tauriApi] Tauri is available');
    return true;
  } catch (error) {
    console.log('[tauriApi] Tauri is not available:', error);
    _isTauriAvailable = false;
    return false;
  }
};

// 同步检查（用于快速判断，可能不准确）
export const isTauriReady = (): boolean => {
  if (typeof window === 'undefined') return false;
  // 优先使用缓存的可用性状态
  if (_isTauriAvailable !== null) return _isTauriAvailable;
  // 回退到检查全局变量
  return (
    !!(window as any).isTauri ||
    !!(window as any).__TAURI__ ||
    !!(window as any).__TAURI_INTERNALS__
  );
};

// 包装 invoke 函数
const invoke = async <T>(cmd: string, args?: Record<string, unknown>): Promise<T> => {
  // 先检查可用性
  const available = await checkTauriAvailability();
  if (!available) {
    throw new Error('Tauri is not available');
  }
  // 动态导入 @tauri-apps/api/core
  const { invoke: tauriInvoke } = await import('@tauri-apps/api/core');
  return tauriInvoke<T>(cmd, args);
};

const openUrl = async (url: string): Promise<void> => {
  try {
    // 复制链接到剪贴板
    if (typeof navigator !== 'undefined' && navigator.clipboard) {
      await navigator.clipboard.writeText(url);
      // 显示复制成功提示
      if (typeof window !== 'undefined') {
        const notification = document.createElement('div');
        notification.className = 'fixed top-4 right-4 bg-green-500 text-white px-4 py-2 rounded-lg shadow-lg z-50 transition-all duration-300 ease-in-out transform translate-y-0 opacity-100';
        notification.textContent = '链接已复制到剪贴板！';
        document.body.appendChild(notification);
        
        // 3秒后自动消失
        setTimeout(() => {
          notification.classList.add('translate-y-[-20px]', 'opacity-0');
          setTimeout(() => {
            if (document.body.contains(notification)) {
              document.body.removeChild(notification);
            }
          }, 300);
        }, 3000);
      }
    }
    
    // 优先使用 Tauri 命令打开链接
    try {
      await invoke('open_external', { url });
    } catch (tauriError) {
      console.warn('Tauri open_external failed, falling back to window.open:', tauriError);
      // 回退到 window.open
      if (typeof window !== 'undefined') {
        window.open(url, '_blank');
      }
    }
  } catch (error) {
    console.error('Failed to open URL:', error);
  }
};

const STORAGE_PREFIX = 'ggai_';

export const localStorageFallback = {
  get: (key: string): any => {
    try {
      const value = localStorage.getItem(STORAGE_PREFIX + key);
      return value ? JSON.parse(value) : null;
    } catch (e) {
      console.warn('localStorage get failed:', e);
      return null;
    }
  },
  set: (key: string, value: any): void => {
    try {
      localStorage.setItem(STORAGE_PREFIX + key, JSON.stringify(value));
    } catch (e) {
      console.error('localStorage set failed:', e);
    }
  },
  remove: (key: string): void => {
    try {
      localStorage.removeItem(STORAGE_PREFIX + key);
    } catch (e) {
      console.error('localStorage remove failed:', e);
    }
  },
};

// 类型定义
export interface Skill {
  id: string;
  name: string;
  description?: string;
  version?: string;
  author?: string;
  enabled: boolean;
  path?: string;
  metadata?: any;
}

export interface AppConfig {
  theme?: string;
  language?: string;
  api_configs: Record<string, any>;
}

export interface TuptupConfig {
  api_key?: string;
  api_secret?: string;
  user_id?: string;
}

export interface TuptupUserInfo {
  user_id?: string;
  username?: string;
  email?: string;
  vip_level?: number;
  plan?: TuptupPlan;
}

export interface TuptupPlan {
  level?: number;
  name?: string;
  expires_at?: string;
}

export interface TuptupTokenBalance {
  balance?: number;
  currency?: string;
}

export interface TuptupOverview {
  user_id?: string;
  username?: string;
  email?: string;
  vip_level?: number;
  level?: number;
  plan?: TuptupPlan;
  token_balance?: number;
}

export interface UserPackage {
  package_id?: string;
  package_name?: string;
  features?: string[];
  limits?: any;
  expires_at?: string;
  used_quota?: any;
  level?: number;
  is_expired?: boolean;
}

export interface PackageStatus {
  is_expired: boolean;
  level: number;
  level_name: string;
  expires_at?: string;
  days_remaining?: number;
}

// 基础 Tauri API
export const tauriApi = {
  // 存储命令 (使用 kv_* 命令名称匹配后端)
  store: {
    get: async (key: string): Promise<any> => {
      // 如果 Tauri 未初始化，直接使用 localStorage fallback
      if (!isTauriReady()) {
        return localStorageFallback.get(key);
      }
      try {
        const value = await invoke<string | null>('kv_get', { key });
        return value ? JSON.parse(value) : null;
      } catch (e) {
        console.warn('kv_get failed, using localStorage fallback:', e);
        return localStorageFallback.get(key);
      }
    },
    set: async (key: string, value: any): Promise<void> => {
      // 如果 Tauri 未初始化，直接使用 localStorage fallback
      if (!isTauriReady()) {
        localStorageFallback.set(key, value);
        return;
      }
      try {
        await invoke('kv_set', { key, value: JSON.stringify(value) });
      } catch (e) {
        console.warn('kv_set failed, using localStorage fallback:', e);
        localStorageFallback.set(key, value);
      }
    },
    remove: async (key: string): Promise<void> => {
      // 如果 Tauri 未初始化，直接使用 localStorage fallback
      if (!isTauriReady()) {
        localStorageFallback.remove(key);
        return;
      }
      try {
        await invoke('kv_remove', { key });
      } catch (e) {
        console.warn('kv_remove failed, using localStorage fallback:', e);
        localStorageFallback.remove(key);
      }
    },
  },

  // 存储初始化（保留向后兼容，现在不需要）
  initStorage: async (): Promise<void> => {
    try {
      console.log('Storage initialized automatically');
    } catch (e) {
      console.error('init_storage failed:', e);
    }
  },

  // 应用配置
  appConfig: {
    load: async (): Promise<AppConfig> => {
      if (!isTauriReady()) {
        return localStorageFallback.get('app_config') || { api_configs: {} };
      }
      try {
        return await invoke<AppConfig>('app_config_get');
      } catch (e) {
        console.warn('app_config_get failed, using localStorage fallback:', e);
        return localStorageFallback.get('app_config') || { api_configs: {} };
      }
    },
    save: async (config: AppConfig): Promise<void> => {
      if (!isTauriReady()) {
        localStorageFallback.set('app_config', config);
        return;
      }
      try {
        await invoke('app_config_set', { config });
      } catch (e) {
        console.warn('app_config_set failed, using localStorage fallback:', e);
        localStorageFallback.set('app_config', config);
      }
    },
  },

  tuptup: {
    setConfig: async (config: TuptupConfig): Promise<void> => {
      if (!isTauriReady()) {
        localStorageFallback.set('tuptup_config', config);
        return;
      }
      try {
        await invoke('tuptup_config_set', { config });
      } catch (e) {
        console.warn('tuptup_config_set failed, using localStorage fallback:', e);
        localStorageFallback.set('tuptup_config', config);
      }
    },
    getConfig: async (): Promise<TuptupConfig> => {
      if (!isTauriReady()) {
        return localStorageFallback.get('tuptup_config') || {};
      }
      try {
        return await invoke<TuptupConfig>('tuptup_config_get');
      } catch (e) {
        console.warn('tuptup_config_get failed, using localStorage fallback:', e);
        return localStorageFallback.get('tuptup_config') || {};
      }
    },
    clearConfig: async (): Promise<void> => {
      if (!isTauriReady()) {
        localStorageFallback.remove('tuptup_config');
        return;
      }
      try {
        await invoke('tuptup_config_set', { config: {} });
      } catch (e) {
        console.warn('tuptup_config_clear failed, using localStorage fallback:', e);
        localStorageFallback.remove('tuptup_config');
      }
    },
    getUserInfo: async (): Promise<TuptupUserInfo | null> => {
      if (!isTauriReady()) return null;
      try {
        return await invoke<TuptupUserInfo>('tuptup_get_user_info');
      } catch (e) {
        console.error('tuptup_get_user_info failed:', e);
        return null;
      }
    },
    getTokenBalance: async (): Promise<TuptupTokenBalance | null> => {
      if (!isTauriReady()) return null;
      try {
        return await invoke<TuptupTokenBalance>('tuptup_get_token_balance');
      } catch (e) {
        console.error('tuptup_get_token_balance failed:', e);
        return null;
      }
    },
    getPlan: async (): Promise<TuptupPlan | null> => {
      if (!isTauriReady()) return null;
      try {
        return await invoke<TuptupPlan>('tuptup_get_plan');
      } catch (e) {
        console.error('tuptup_get_plan failed:', e);
        return null;
      }
    },
    getOverview: async (): Promise<TuptupOverview | null> => {
      if (!isTauriReady()) return null;
      try {
        return await invoke<TuptupOverview>('tuptup_get_overview');
      } catch (e) {
        console.error('tuptup_get_overview failed:', e);
        return null;
      }
    },
    getSmtpConfig: async (): Promise<any | null> => {
      if (!isTauriReady()) return null;
      try {
        return await invoke<any>('tuptup_get_smtp_config');
      } catch (e) {
        console.error('tuptup_get_smtp_config failed:', e);
        return null;
      }
    },
    getUserPackage: async (): Promise<UserPackage | null> => {
      if (!isTauriReady()) return null;
      try {
        return await invoke<UserPackage>('tuptup_get_user_package');
      } catch (e) {
        console.error('tuptup_get_user_package failed:', e);
        return null;
      }
    },
    getPackageStatus: async (): Promise<PackageStatus | null> => {
      if (!isTauriReady()) return null;
      try {
        return await invoke<PackageStatus>('tuptup_get_package_status');
      } catch (e) {
        console.error('tuptup_get_package_status failed:', e);
        return null;
      }
    },
    isPackageExpired: async (): Promise<boolean> => {
      if (!isTauriReady()) return true;
      try {
        return await invoke<boolean>('tuptup_is_package_expired');
      } catch (e) {
        console.error('tuptup_is_package_expired failed:', e);
        return true;
      }
    },
    getPackageLevel: async (): Promise<number> => {
      if (!isTauriReady()) return 0;
      try {
        return await invoke<number>('tuptup_get_package_level');
      } catch (e) {
        console.error('tuptup_get_package_level failed:', e);
        return 0;
      }
    },
    sendVerificationEmail: async (email: string): Promise<any> => {
      if (!isTauriReady()) return { success: false, message: 'Tauri 未就绪' };
      try {
        const result = await invoke<any>('tuptup_send_verification_email', { email });
        console.log('sendVerificationEmail result:', result);
        return result;
      } catch (e) {
        console.error('tuptup_send_verification_email failed:', e);
        return { success: false, message: String(e) };
      }
    },
    verifyCode: async (email: string, code: string): Promise<boolean> => {
      if (!isTauriReady()) return false;
      try {
        return await invoke<boolean>('tuptup_verify_code', { email, code });
      } catch (e) {
        console.error('tuptup_verify_code failed:', e);
        return false;
      }
    },
    getUserIdByEmail: async (email: string): Promise<string | null> => {
      if (!isTauriReady()) return null;
      try {
        return await invoke<string | null>('tuptup_get_user_id_by_email', { email });
      } catch (e) {
        console.error('tuptup_get_user_id_by_email failed:', e);
        return null;
      }
    },
  },

  // 平台和应用信息
  platform: {
    get: async (): Promise<string> => {
      if (!isTauriReady()) return navigator.platform;
      try {
        return await invoke<string>('get_platform');
      } catch (e) {
        return navigator.platform;
      }
    },
    isAutoStartEnabled: async (): Promise<boolean> => {
      if (!isTauriReady()) return false;
      try {
        return await invoke<boolean>('system_is_auto_start_enabled');
      } catch (e) {
        console.error('system_is_auto_start_enabled failed:', e);
        return false;
      }
    },
    enableAutoStart: async (enable: boolean): Promise<void> => {
      if (!isTauriReady()) return;
      try {
        await invoke('system_enable_auto_start', { enable });
      } catch (e) {
        console.error('system_enable_auto_start failed:', e);
      }
    },
  },

  appInfo: {
    getVersion: async (): Promise<string> => {
      if (!isTauriReady()) return '0.1.0';
      try {
        return await invoke<string>('get_app_version');
      } catch (e) {
        return '0.1.0';
      }
    },
    getSystemLocale: async (): Promise<string> => {
      if (!isTauriReady()) return navigator.language;
      try {
        return await invoke<string>('get_system_locale');
      } catch (e) {
        return navigator.language;
      }
    },
  },

  // 窗口管理命令
  window: {
    minimize: async (): Promise<void> => {
      if (!isTauriReady()) return;
      try {
        await invoke('window_minimize');
      } catch (e) {
        console.error('window_minimize failed:', e);
      }
    },
    toggleMaximize: async (): Promise<void> => {
      if (!isTauriReady()) return;
      try {
        await invoke('window_toggle_maximize');
      } catch (e) {
        console.error('window_toggle_maximize failed:', e);
      }
    },
    close: async (): Promise<void> => {
      if (!isTauriReady()) return;
      try {
        await invoke('window_close');
      } catch (e) {
        console.error('window_close failed:', e);
      }
    },
    isMaximized: async (): Promise<boolean> => {
      if (!isTauriReady()) return false;
      try {
        return await invoke<boolean>('window_is_maximized');
      } catch (e) {
        console.error('window_is_maximized failed:', e);
        return false;
      }
    },
  },

  // 技能管理命令
  skills: {
    list: async (): Promise<Skill[]> => {
      if (!isTauriReady()) {
        // 浏览器模式下加载技能
        return await loadSkillsFromDirectory();
      }
      try {
        return await invoke<Skill[]>('skills_list');
      } catch (e) {
        console.error('skills_list failed:', e);
        return [];
      }
    },
    setEnabled: async (skillId: string, enabled: boolean): Promise<void> => {
      if (!isTauriReady()) return;
      try {
        const cmd = enabled ? 'skills_enable' : 'skills_disable';
        await invoke(cmd, { id: skillId });
      } catch (e) {
        console.error('skills_set_enabled failed:', e);
      }
    },
    delete: async (skillId: string): Promise<void> => {
      if (!isTauriReady()) return;
      try {
        await invoke('skills_delete', { id: skillId });
      } catch (e) {
        console.error('skills_delete failed:', e);
      }
    },
    getRoot: async (): Promise<string> => {
      if (!isTauriReady()) return '';
      try {
        return await invoke<string>('skills_get_root');
      } catch (e) {
        console.error('skills_get_root failed:', e);
        return '';
      }
    },
  },

  // 对话框命令 - 使用 @tauri-apps/plugin-dialog 或 Rust 后端命令
  dialog: {
    selectDirectory: async (): Promise<{ success: boolean; path: string | null }> => {
      console.log('[tauriApi] Opening directory dialog...');
      
      // 异步检查 Tauri 可用性
      const available = await checkTauriAvailability();
      console.log('[tauriApi] Tauri available:', available);
      
      if (available) {
        try {
          // 使用 @tauri-apps/plugin-dialog 的 open 函数
          const { open } = await import('@tauri-apps/plugin-dialog');
          const selected = await open({
            directory: true,
            multiple: false,
            title: '选择文件夹',
          });
          console.log('[tauriApi] Directory selected:', selected);
          if (selected === null) {
            return { success: false, path: null };
          }
          return { success: true, path: selected as string };
        } catch (e) {
          console.error('[tauriApi] Dialog plugin failed, trying backend command:', e);
          // 回退到 Rust 后端命令
          try {
            const result = await invoke<{ success: boolean; path: string | null }>('dialog_select_directory');
            console.log('[tauriApi] Backend command result:', result);
            return result;
          } catch (e2) {
            console.error('[tauriApi] Backend command also failed:', e2);
          }
        }
      }
      
      // 使用浏览器原生目录选择器作为后备
      console.log('[tauriApi] Using browser fallback for directory selection');
      return new Promise((resolve) => {
        const input = document.createElement('input');
        input.type = 'file';
        (input as any).webkitdirectory = true;
        (input as any).directory = true;
        input.style.display = 'none';
        
        input.onchange = (e) => {
          const files = (e.target as HTMLInputElement).files;
          if (files && files.length > 0) {
            const path = (files[0] as any).path || files[0].webkitRelativePath.split('/')[0];
            resolve({ success: true, path });
          } else {
            resolve({ success: false, path: null });
          }
          document.body.removeChild(input);
        };
        
        input.oncancel = () => {
          resolve({ success: false, path: null });
          document.body.removeChild(input);
        };
        
        document.body.appendChild(input);
        input.click();
      });
    },
    selectFile: async (options?: { title?: string; filters?: { name: string; extensions: string[] }[] }): Promise<{ success: boolean; path: string | null }> => {
      console.log('[tauriApi] Opening file dialog...');
      
      // 异步检查 Tauri 可用性
      const available = await checkTauriAvailability();
      console.log('[tauriApi] Tauri available:', available);
      
      if (available) {
        try {
          // 使用 @tauri-apps/plugin-dialog 的 open 函数
          const { open } = await import('@tauri-apps/plugin-dialog');
          const selected = await open({
            directory: false,
            multiple: false,
            title: options?.title || '选择文件',
            filters: options?.filters,
          });
          console.log('[tauriApi] File selected:', selected);
          if (selected === null) {
            return { success: false, path: null };
          }
          return { success: true, path: selected as string };
        } catch (e) {
          console.error('[tauriApi] Dialog plugin failed, trying backend command:', e);
          // 回退到 Rust 后端命令
          try {
            const result = await invoke<{ success: boolean; path: string | null }>('dialog_select_file', {
              title: options?.title,
              filters: options?.filters,
            });
            console.log('[tauriApi] Backend command result:', result);
            return result;
          } catch (e2) {
            console.error('[tauriApi] Backend command also failed:', e2);
          }
        }
      }
      
      // 使用浏览器原生文件选择器作为后备
      console.log('[tauriApi] Using browser fallback for file selection');
      return new Promise((resolve) => {
        const input = document.createElement('input');
        input.type = 'file';
        input.style.display = 'none';
        
        if (options?.filters && options.filters.length > 0) {
          const acceptTypes = options.filters
            .flatMap(f => f.extensions.map(ext => `.${ext}`))
            .join(',');
          input.accept = acceptTypes;
        }
        
        input.onchange = (e) => {
          const files = (e.target as HTMLInputElement).files;
          if (files && files.length > 0) {
            const path = (files[0] as any).path || files[0].name;
            resolve({ success: true, path });
          } else {
            resolve({ success: false, path: null });
          }
          document.body.removeChild(input);
        };
        
        input.oncancel = () => {
          resolve({ success: false, path: null });
          document.body.removeChild(input);
        };
        
        document.body.appendChild(input);
        input.click();
      });
    },
  },

  // Shell 命令
  shell: {
    openPath: async (filePath: string): Promise<void> => {
      try {
        await openUrl(filePath);
      } catch (e) {
        console.error('openPath failed:', e);
      }
    },
    showItemInFolder: async (_filePath: string): Promise<void> => {
      console.warn('showItemInFolder not implemented yet');
    },
    openExternal: async (url: string): Promise<void> => {
      try {
        await openUrl(url);
      } catch (e) {
        console.error('openExternal failed:', e);
      }
    },
  },

  // 事件监听
  on: async (event: string, callback: (data: any) => void): Promise<() => void> => {
    try {
      const { listen } = await import('@tauri-apps/api/event');
      const unlisten = await listen(event, (event) => {
        callback(event.payload);
      });
      return unlisten;
    } catch (e) {
      console.error('listen failed:', e);
      return () => {};
    }
  },
};

// 初始化存储
export async function initTauri() {
  await tauriApi.initStorage();
}
