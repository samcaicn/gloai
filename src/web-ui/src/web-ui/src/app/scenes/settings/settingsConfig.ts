/**
 * settingsConfig — static shape of settings categories and tabs.
 *
 * Shared by SettingsNav (left sidebar) and SettingsScene (content renderer).
 * Labels are i18n keys resolved at render time via useTranslation('settings').
 */

export type ConfigTab =
  | 'basics'
  | 'appearance'
  | 'models'
  | 'archived-sessions'
  | 'session-personalization'
  | 'session-permissions'
  | 'quick-actions'
  | 'review'
  | 'mcp-tools'
  | 'acp-agents'
  | 'dsh'
  // | 'lsp' // temporarily hidden from config center
  | 'editor'
  | 'keyboard'
  | 'tupai'
  | 'im-wecom-bot'
  | 'im-feishu'
  | 'im-dingtalk'
  | 'im-long-conn'
  | 'im-weixin'
  | 'im-qqbot'
  | 'im-whatsapp'
  | 'im-telegram'
  | 'mesh';

export interface ConfigTabDef {
  id: ConfigTab;
  labelKey: string;
  /** i18n key under settings namespace for tab description (search + discoverability). */
  descriptionKey?: string;
  /** Language-neutral extra tokens matched by search (ASCII recommended). */
  keywords?: string[];
  /** Show a Beta pill next to the tab label in the settings nav. */
  beta?: boolean;
  /** Hide this tab in production builds (tauri build / NSIS). Visible in dev (tauri dev). */
  hiddenInRelease?: boolean;
}

export interface ConfigCategoryDef {
  id: string;
  nameKey: string;
  tabs: ConfigTabDef[];
}

export const SETTINGS_CATEGORIES: ConfigCategoryDef[] = [
  {
    id: 'general',
    nameKey: 'configCenter.categories.general',
    tabs: [
      {
        id: 'basics',
        labelKey: 'configCenter.tabs.basics',
        descriptionKey: 'configCenter.tabDescriptions.basics',
        keywords: [
          'logging',
          'log',
          'terminal',
          'shell',
          'pwsh',
          'powershell',
          'autostart',
          'login',
          'boot',
          'launch',
          'notification',
          'notifications',
          'startup tips',
        ],
      },
      {
        id: 'tupai',
        labelKey: 'configCenter.tabs.tupai',
        descriptionKey: 'configCenter.tabDescriptions.tupai',
        keywords: [
          'tupai',
          'device',
          'register',
          'join_code',
          'token',
          'theme',
          'scheme',
          'about',
          'version',
          '设备',
          '注册',
          '主题',
          '关于',
        ],
      },
      {
        id: 'mesh',
        labelKey: 'common:meshSettings.title',
        descriptionKey: 'common:meshSettings.subtitle',
        keywords: [
          'mesh',
          'p2p',
          'iroh',
          'networking',
          'peer',
          'coordinator',
          'executor',
          'ticket',
          '组网',
          '協作',
          '協調者',
        ],
      },
      {
        id: 'appearance',
        labelKey: 'configCenter.tabs.appearance',
        descriptionKey: 'configCenter.tabDescriptions.appearance',
        keywords: [
          'language',
          'locale',
          'i18n',
          'theme',
          'appearance',
          'font',
          'fonts',
          'typography',
          'size',
        ],
      },
      {
        id: 'models',
        labelKey: 'configCenter.tabs.models',
        descriptionKey: 'configCenter.tabDescriptions.models',
        keywords: [
          'api',
          'api key',
          'provider',
          'openai',
          'claude',
          'gpt',
          'base url',
          'proxy',
          'model',
          'temperature',
          'token',
        ],
        hiddenInRelease: true,
      },
      {
        id: 'archived-sessions',
        labelKey: 'configCenter.tabs.archivedSessions',
        descriptionKey: 'configCenter.tabDescriptions.archivedSessions',
        keywords: [
          'archive',
          'archived',
          'session',
          'sessions',
          'restore',
          'unarchive',
        ],
      },
      {
        id: 'keyboard',
        labelKey: 'configCenter.tabs.keyboard',
        descriptionKey: 'configCenter.tabDescriptions.keyboard',
        keywords: [
          'keyboard',
          'shortcut',
          'keybinding',
          'hotkey',
          'shortcut key',
        ],
      },
    ],
  },
  {
    id: 'smartCapabilities',
    nameKey: 'configCenter.categories.smartCapabilities',
    tabs: [
      {
        id: 'session-personalization',
        labelKey: 'configCenter.tabs.sessionPersonalization',
        descriptionKey: 'configCenter.tabDescriptions.sessionPersonalization',
        keywords: [
          'session',
          'title',
          'companion',
          'agent',
          'pixel',
          'pet',
          'partner',
        ],
        hiddenInRelease: true,
      },
      {
        id: 'session-permissions',
        labelKey: 'configCenter.tabs.sessionPermissions',
        descriptionKey: 'configCenter.tabDescriptions.sessionPermissions',
        keywords: [
          'session',
          'tool',
          'write',
          'file write',
          'timeout',
          'confirmation',
          'computer use',
          'browser',
          'cdp',
          'debug',
          'permission',
          'accessibility',
          'screen',
          'workspace',
          'search',
          'flashgrep',
          'index',
        ],
      },
      {
        id: 'quick-actions',
        labelKey: 'configCenter.tabs.quickActions',
        descriptionKey: 'configCenter.tabDescriptions.quickActions',
        keywords: [
          'quick action',
          'quick actions',
          'commit',
          'pr',
          'pull request',
          'post-coding',
          'shortcut',
        ],
      },
      {
        id: 'review',
        labelKey: 'configCenter.tabs.review',
        descriptionKey: 'configCenter.tabDescriptions.review',
        keywords: [
          'review',
          'code review',
          'deep review',
          'review team',
          'subagent',
          'readonly',
          'audit',
        ],
        hiddenInRelease: true,
      },
      {
        id: 'mcp-tools',
        labelKey: 'configCenter.tabs.mcpTools',
        descriptionKey: 'configCenter.tabDescriptions.mcpTools',
        keywords: ['mcp', 'server', 'plugin', 'stdio', 'sse', 'tools'],
      },
      {
        id: 'acp-agents',
        labelKey: 'configCenter.tabs.acpAgents',
        descriptionKey: 'configCenter.tabDescriptions.acpAgents',
        keywords: [
          'acp',
          'agent client protocol',
          'external agent',
          'opencode',
          'claude code',
          'codex',
          'stdio',
        ],
      },
      {
        id: 'dsh',
        labelKey: 'configCenter.tabs.dsh',
        descriptionKey: 'configCenter.tabDescriptions.dsh',
        keywords: [
          'dsh',
          'upstream',
          '上游',
          'runtime',
          '外部运行时',
          'endpoint',
          '端点',
          'agent',
        ],
      },
    ],
  },
  {
    id: 'devkit',
    nameKey: 'configCenter.categories.devkit',
    tabs: [
      {
        id: 'editor',
        labelKey: 'configCenter.tabs.editor',
        descriptionKey: 'configCenter.tabDescriptions.editor',
        keywords: [
          'font',
          'indent',
          'tab',
          'minimap',
          'word wrap',
          'line number',
          'format',
          'save',
        ],
      },
      // LSP / language server settings — temporarily hidden from nav
      // {
      //   id: 'lsp',
      //   labelKey: 'configCenter.tabs.lsp',
      //   descriptionKey: 'configCenter.tabDescriptions.lsp',
      //   keywords: ['lsp', 'language server', 'typescript', 'intellisense'],
      // },
    ],
  },
  {
    // IM 渠道：独立分类，每个渠道类型一个子菜单。
    id: 'im',
    nameKey: 'configCenter.categories.im',
    tabs: [
      {
        id: 'im-wecom-bot',
        labelKey: 'configCenter.tabs.im-wecom-bot',
        descriptionKey: 'configCenter.tabDescriptions.im-wecom-bot',
        keywords: ['wecom', 'wecom_bot', '企业微信', '机器人', '扫码'],
      },
      {
        id: 'im-feishu',
        labelKey: 'configCenter.tabs.im-feishu',
        descriptionKey: 'configCenter.tabDescriptions.im-feishu',
        keywords: ['feishu', '飞书', 'lark', 'app_id'],
      },
      {
        id: 'im-dingtalk',
        labelKey: 'configCenter.tabs.im-dingtalk',
        descriptionKey: 'configCenter.tabDescriptions.im-dingtalk',
        keywords: ['dingtalk', '钉钉', 'ding', 'app_key'],
      },
      {
        id: 'im-long-conn',
        labelKey: 'configCenter.tabs.im-long-conn',
        descriptionKey: 'configCenter.tabDescriptions.im-long-conn',
        keywords: ['long_conn', '通用长连接', 'websocket', 'relay', '中继'],
      },
      {
        id: 'im-weixin',
        labelKey: 'configCenter.tabs.im-weixin',
        descriptionKey: 'configCenter.tabDescriptions.im-weixin',
        keywords: ['weixin', 'wechat', '微信', '扫码'],
      },
      {
        id: 'im-qqbot',
        labelKey: 'configCenter.tabs.im-qqbot',
        descriptionKey: 'configCenter.tabDescriptions.im-qqbot',
        keywords: ['qqbot', 'qq', 'QQ Bot', '扫码'],
      },
      {
        id: 'im-whatsapp',
        labelKey: 'configCenter.tabs.im-whatsapp',
        descriptionKey: 'configCenter.tabDescriptions.im-whatsapp',
        keywords: ['whatsapp', 'WhatsApp', '扫码'],
      },
      {
        id: 'im-telegram',
        labelKey: 'configCenter.tabs.im-telegram',
        descriptionKey: 'configCenter.tabDescriptions.im-telegram',
        keywords: ['telegram', 'tg', 'Telegram', 'bot', 'BotFather'],
      },
    ],
  },
];

export const DEFAULT_SETTINGS_TAB: ConfigTab = 'basics';

/**
 * Filtered categories for the current build mode.
 * In production builds (tauri build / NSIS), tabs marked `hiddenInRelease`
 * are excluded. In dev mode (tauri dev), all tabs are shown.
 */
export const VISIBLE_SETTINGS_CATEGORIES: ConfigCategoryDef[] =
  import.meta.env.DEV
    ? SETTINGS_CATEGORIES
    : SETTINGS_CATEGORIES.map((cat) => ({
        ...cat,
        tabs: cat.tabs.filter((tab) => !tab.hiddenInRelease),
      })).filter((cat) => cat.tabs.length > 0);

const KNOWN_TABS: ConfigTab[] = SETTINGS_CATEGORIES.flatMap((c) => c.tabs.map((t) => t.id));

/** Map removed or renamed tabs; used by deep links and IDE actions. */
export function normalizeSettingsTab(section: string): ConfigTab {
  if (section === 'theme' || section === 'font' || section === 'fonts') return 'appearance';
  if (section === 'logging' || section === 'terminal') return 'basics';
  if (section === 'lsp') return DEFAULT_SETTINGS_TAB;
  if (section === 'session-config') return 'session-personalization';
  if (section === 'deep-review' || section === 'code-review' || section === 'review-team') return 'review';
  if (section === 'shortcuts' || section === 'keybindings' || section === 'hotkeys') return 'keyboard';
  if ((KNOWN_TABS as readonly string[]).includes(section)) return section as ConfigTab;
  return DEFAULT_SETTINGS_TAB;
}
