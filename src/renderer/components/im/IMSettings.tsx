/**
 * IM Settings Component
 * Configuration UI for DingTalk, Feishu and Telegram IM bots
 */

import React, { useState, useEffect, useMemo } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { SignalIcon, XMarkIcon, CheckCircleIcon, XCircleIcon, ExclamationTriangleIcon, ArrowPathIcon } from '@heroicons/react/24/outline';
import { RootState } from '../../store';
import { imService } from '../../services/im';
import { setDingTalkConfig, setFeishuConfig, setTelegramConfig, setDiscordConfig, setWeWorkConfig, setWhatsAppConfig, clearError } from '../../store/slices/imSlice';
import { i18nService } from '../../services/i18n';
// import { tuptupService } from '../../services/tuptup';
import { configService } from '../../services/config';
import type { IMPlatform, IMConnectivityCheck, IMConnectivityTestResult, IMGatewayConfig, WeWorkConfig } from '../../types/im';
import { getVisibleIMPlatforms } from '../../utils/regionFilter';

// Platform metadata
const platformMeta: Record<IMPlatform, { label: string; logo: string }> = {
  dingtalk: { label: '钉钉', logo: 'dingding.png' },
  feishu: { label: '飞书', logo: 'feishu.png' },
  telegram: { label: 'Telegram', logo: 'telegram.svg' },
  discord: { label: 'Discord', logo: 'discord.svg' },
  wework: { label: '企业微信', logo: 'wework.svg' },
  whatsapp: { label: 'WhatsApp', logo: 'whatsapp.svg' },
};

const verdictColorClass: Record<IMConnectivityTestResult['verdict'], string> = {
  pass: 'bg-green-500/15 text-green-600 dark:text-green-400',
  warn: 'bg-yellow-500/15 text-yellow-700 dark:text-yellow-300',
  fail: 'bg-red-500/15 text-red-600 dark:text-red-400',
};

const checkLevelColorClass: Record<IMConnectivityCheck['level'], string> = {
  pass: 'text-green-600 dark:text-green-400',
  info: 'text-sky-600 dark:text-sky-400',
  warn: 'text-yellow-700 dark:text-yellow-300',
  fail: 'text-red-600 dark:text-red-400',
};

const IMSettings: React.FC = () => {
  const dispatch = useDispatch();
  const { config, status, isLoading: globalIsLoading } = useSelector((state: RootState) => state.im);
  const [activePlatform, setActivePlatform] = useState<IMPlatform>('dingtalk');
  const [testingPlatform, setTestingPlatform] = useState<IMPlatform | null>(null);
  const [connectivityResults, setConnectivityResults] = useState<Partial<Record<IMPlatform, IMConnectivityTestResult>>>({});
  const [connectivityModalPlatform, setConnectivityModalPlatform] = useState<IMPlatform | null>(null);
  const [language, setLanguage] = useState<'zh' | 'en'>(i18nService.getLanguage());
  const [error, setError] = useState<string | null>(null);
  const [isInitialized, setIsInitialized] = useState(false);

  // Subscribe to language changes
  useEffect(() => {
    const unsubscribe = i18nService.subscribe(() => {
      setLanguage(i18nService.getLanguage());
    });
    return unsubscribe;
  }, []);

  // Initialize IM service and subscribe status updates - 异步初始化不阻塞 UI
  useEffect(() => {
    const init = async () => {
      try {
        await imService.init();
      } catch (err) {
        console.error('Failed to initialize IM service:', err);
      } finally {
        setIsInitialized(true);
      }
    };
    void init();
    return () => {
      imService.destroy();
    };
  }, []);

  // Handle DingTalk config change
  const handleDingTalkChange = (field: 'clientId' | 'clientSecret' | 'agentId' | 'robotCode', value: string) => {
    dispatch(setDingTalkConfig({ [field]: value }));
  };

  // Handle Feishu config change
  const handleFeishuChange = (field: 'appId' | 'appSecret', value: string) => {
    dispatch(setFeishuConfig({ [field]: value }));
  };

  // Handle Telegram config change
  const handleTelegramChange = (field: 'botToken', value: string) => {
    dispatch(setTelegramConfig({ [field]: value }));
  };

  // Handle Discord config change
  const handleDiscordChange = (field: 'botToken', value: string) => {
    dispatch(setDiscordConfig({ [field]: value }));
  };

  // Handle WeWork config change
  const handleWeWorkChange = (field: keyof WeWorkConfig, value: string | boolean) => {
    dispatch(setWeWorkConfig({ [field]: value } as Partial<WeWorkConfig>));
  };

  // Handle WhatsApp config change
  const handleWhatsAppChange = (field: 'phoneNumberId' | 'accessToken', value: string) => {
    dispatch(setWhatsAppConfig({ [field]: value }));
  };

  // Save config on blur
  const handleSaveConfig = async () => {
    await imService.updateConfig(config);
  };

  const getCheckTitle = (code: IMConnectivityCheck['code']): string => {
    return i18nService.t(`imConnectivityCheckTitle_${code}`);
  };

  const getCheckSuggestion = (check: IMConnectivityCheck): string | undefined => {
    if (check.suggestion) {
      return check.suggestion;
    }
    if (check.code === 'gateway_running' && check.level === 'pass') {
      return undefined;
    }
    const suggestion = i18nService.t(`imConnectivityCheckSuggestion_${check.code}`);
    if (suggestion.startsWith('imConnectivityCheckSuggestion_')) {
      return undefined;
    }
    return suggestion;
  };

  const formatTestTime = (timestamp: number): string => {
    try {
      return new Date(timestamp).toLocaleString();
    } catch {
      return String(timestamp);
    }
  };

  const runConnectivityTest = async (
    platform: IMPlatform,
    configOverride?: Partial<IMGatewayConfig>
  ) => {
    setTestingPlatform(platform);
    const result = await imService.testGateway(platform, configOverride);
    if (result) {
      setConnectivityResults((prev) => ({ ...prev, [platform]: result }));
    }
    setTestingPlatform(null);
  };

  // 需要 VIP1 级别以上套餐的平台
  const vipRequiredPlatforms = new Set<IMPlatform>(['telegram', 'discord', 'wework', 'whatsapp']);

  // Toggle gateway on/off and persist enabled state
  const toggleGateway = async (platform: IMPlatform) => {
    const isEnabled = config[platform].enabled;
    const newEnabled = !isEnabled;

    // Map platform to its Redux action
    const setConfigAction = {
      dingtalk: setDingTalkConfig,
      feishu: setFeishuConfig,
      telegram: setTelegramConfig,
      discord: setDiscordConfig,
      wework: setWeWorkConfig,
      whatsapp: setWhatsAppConfig,
    }[platform];

    // 如果是启用操作，且该平台需要 VIP 权限
    if (newEnabled && vipRequiredPlatforms.has(platform)) {
      setError(null);
      
      // 检查是否已登录
      const appConfig = configService.getConfig();
      if (!appConfig.tuptup?.apiKey || !appConfig.tuptup?.apiSecret || !appConfig.tuptup?.userId) {
        setError('请先登录我的账户');
        return;
      }

      // 使用新的套餐状态 API 获取 VIP 等级
      try {
        const { tauriApi } = await import('../../services/tauriApi');
        const vipLevel = await tauriApi.tuptup.getPackageLevel();
        
        if (vipLevel < 1) {
          setError('需要 VIP1 及以上等级才能启用该平台');
          return;
        }
      } catch (err) {
        setError(err instanceof Error ? err.message : '验证 VIP 等级失败');
        return;
      }
    }

    // Update Redux state
    dispatch(setConfigAction({ enabled: newEnabled }));

    // Persist the updated config (construct manually since Redux state hasn't re-rendered yet)
    await imService.updateConfig({ [platform]: { ...config[platform], enabled: newEnabled } });

    if (newEnabled) {
      dispatch(clearError());
      const success = await imService.startGateway(platform);
      if (!success) {
        // Rollback enabled state on failure
        dispatch(setConfigAction({ enabled: false }));
        await imService.updateConfig({ [platform]: { ...config[platform], enabled: false } });
      } else {
        await runConnectivityTest(platform, {
          [platform]: { ...config[platform], enabled: true },
        } as Partial<IMGatewayConfig>);
      }
    } else {
      await imService.stopGateway(platform);
    }
  };

  const dingtalkConnected = status.dingtalk.connected;
  const feishuConnected = status.feishu.connected;
  const telegramConnected = status.telegram.connected;
  const discordConnected = status.discord.connected;
  const weworkConnected = status.wework.connected;
  const whatsappConnected = status.whatsapp.connected;

  // Compute visible platforms based on language
  const platforms = useMemo<IMPlatform[]>(() => {
    return getVisibleIMPlatforms(language) as IMPlatform[];
  }, [language]);

  // Ensure activePlatform is always in visible platforms when language changes
  useEffect(() => {
    if (platforms.length > 0 && !platforms.includes(activePlatform)) {
      // If current activePlatform is not visible, switch to first visible platform
      setActivePlatform(platforms[0]);
    }
  }, [platforms, activePlatform]);

  // Check if platform can be started
  const canStart = (platform: IMPlatform): boolean => {
    if (platform === 'dingtalk') {
      return !!(config.dingtalk.clientId && config.dingtalk.clientSecret);
    }
    if (platform === 'telegram') {
      return !!config.telegram.botToken;
    }
    if (platform === 'discord') {
      return !!config.discord.botToken;
    }
    if (platform === 'wework') {
      return !!(config.wework.corpId && config.wework.agentId && config.wework.secret);
    }
    if (platform === 'whatsapp') {
      return !!(config.whatsapp.phoneNumberId && config.whatsapp.accessToken);
    }
    return !!(config.feishu.appId && config.feishu.appSecret);
  };

  // Get platform enabled state (persisted toggle state)
  const isPlatformEnabled = (platform: IMPlatform): boolean => {
    return config[platform].enabled;
  };

  // Get platform connection status (runtime state)
  const getPlatformConnected = (platform: IMPlatform): boolean => {
    if (platform === 'dingtalk') return dingtalkConnected;
    if (platform === 'telegram') return telegramConnected;
    if (platform === 'discord') return discordConnected;
    if (platform === 'wework') return weworkConnected;
    if (platform === 'whatsapp') return whatsappConnected;
    return feishuConnected;
  };

  // Get platform transient starting status
  const getPlatformStarting = (platform: IMPlatform): boolean => {
    if (platform === 'discord') return status.discord.starting;
    return false;
  };

  const handleConnectivityTest = async (platform: IMPlatform) => {
    setConnectivityModalPlatform(platform);
    await runConnectivityTest(platform, {
      [platform]: config[platform],
    } as Partial<IMGatewayConfig>);
  };

  // Handle platform toggle
  const handlePlatformToggle = (platform: IMPlatform) => {
    const isEnabled = isPlatformEnabled(platform);
    // Can toggle ON if credentials are present, can always toggle OFF
    const canToggle = isEnabled || canStart(platform);
    if (canToggle && !globalIsLoading) {
      setActivePlatform(platform);
      toggleGateway(platform);
    }
  };

  const renderConnectivityTestButton = (platform: IMPlatform) => (
    <button
      type="button"
      onClick={() => handleConnectivityTest(platform)}
      disabled={globalIsLoading || testingPlatform === platform}
      className="inline-flex items-center px-3 py-1.5 text-xs font-medium rounded-xl border dark:border-claude-darkBorder border-claude-border dark:text-claude-darkText text-claude-text dark:hover:bg-claude-darkSurfaceHover hover:bg-claude-surfaceHover disabled:opacity-50 disabled:cursor-not-allowed transition-colors active:scale-[0.98]"
    >
      <SignalIcon className="h-3.5 w-3.5 mr-1.5" />
      {testingPlatform === platform
        ? i18nService.t('imConnectivityTesting')
        : connectivityResults[platform]
          ? i18nService.t('imConnectivityRetest')
          : i18nService.t('imConnectivityTest')}
    </button>
  );

  useEffect(() => {
    if (!connectivityModalPlatform) {
      return;
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setConnectivityModalPlatform(null);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [connectivityModalPlatform]);

  return (
    <div className="flex h-full gap-4 flex-col">
      {/* Error display */}
      {error && (
        <div className="text-xs text-red-500 bg-red-500/10 px-3 py-2 rounded-lg mb-2">
          {error}
        </div>
      )}
      
      {/* 加载指示器 - 顶部小图标 */}
      {(globalIsLoading || !isInitialized) && (
        <div className="flex items-center justify-center py-2">
          <div className="flex items-center gap-2 text-xs text-claude-textSecondary dark:text-claude-darkTextSecondary">
            <ArrowPathIcon className="h-4 w-4 animate-spin text-claude-accent" />
            <span>{i18nService.t('loading') || '加载中...'}</span>
          </div>
        </div>
      )}
      
      <div className="flex flex-1 gap-4">
        {/* Platform List - Left Side */}
        <div className="w-48 flex-shrink-0 border-r dark:border-claude-darkBorder border-claude-border pr-3 space-y-2 overflow-y-auto">
        {platforms.map((platform) => {
          const meta = platformMeta[platform];
          const isEnabled = isPlatformEnabled(platform);
          const isConnected = getPlatformConnected(platform) || getPlatformStarting(platform);
          const canToggle = isEnabled || canStart(platform);
          return (
            <div
              key={platform}
              onClick={() => setActivePlatform(platform)}
              className={`flex items-center p-2 rounded-xl cursor-pointer transition-colors ${
                activePlatform === platform
                  ? 'bg-claude-accent/10 dark:bg-claude-accent/20 border border-claude-accent/30'
                  : 'bg-claude-surfaceHover/80 dark:bg-claude-darkSurface/55 dark:bg-gradient-to-br dark:from-claude-darkSurface/70 dark:to-claude-darkSurfaceHover/70 hover:bg-claude-surface dark:hover:from-claude-darkSurface/80 dark:hover:to-claude-darkSurfaceHover/80 dark:border-claude-darkBorder/70 border-claude-border/80 border'
              }`}
            >
              <div className="flex flex-1 items-center">
                <div className="mr-2 flex h-7 w-7 items-center justify-center">
                  <img
                    src={meta.logo}
                    alt={meta.label}
                    className="w-6 h-6 object-contain"
                  />
                </div>
                <span className={`text-sm font-medium truncate ${
                  activePlatform === platform
                    ? 'text-claude-accent'
                    : 'dark:text-claude-darkText text-claude-text'
                }`}>
                  {i18nService.t(platform)}
                </span>
              </div>
              <div className="flex items-center ml-2">
                <div
                  className={`w-7 h-4 rounded-full flex items-center transition-colors ${
                    isEnabled
                      ? (isConnected ? 'bg-green-500' : 'bg-yellow-500')
                      : 'dark:bg-claude-darkBorder bg-claude-border'
                  } ${!canToggle ? 'opacity-50 cursor-not-allowed' : 'cursor-pointer'}`}
                  onClick={(e) => {
                    e.stopPropagation();
                    handlePlatformToggle(platform);
                  }}
                >
                  <div
                    className={`w-3 h-3 rounded-full bg-white shadow-md transform transition-transform ${
                      isEnabled ? 'translate-x-3.5' : 'translate-x-0.5'
                    }`}
                  />
                </div>
              </div>
            </div>
          );
        })}
      </div>

      {/* Platform Settings - Right Side */}
      <div className="flex-1 min-w-0 space-y-4 overflow-y-auto">
        {/* Header with status */}
        <div className="flex items-center gap-3 pb-3 border-b dark:border-claude-darkBorder/60 border-claude-border/60">
          <div className="flex items-center gap-2">
            <div className="flex h-7 w-7 items-center justify-center rounded-md bg-white dark:bg-claude-darkBorder/30 p-1">
              <img
                src={platformMeta[activePlatform].logo}
                alt={platformMeta[activePlatform].label}
                className="w-4 h-4 object-contain"
              />
            </div>
            <h3 className="text-sm font-medium dark:text-claude-darkText text-claude-text">
              {`${i18nService.t(activePlatform)}${i18nService.t('settings')}`}
            </h3>
          </div>
          <div className={`px-2 py-0.5 rounded-full text-xs font-medium ${
            getPlatformConnected(activePlatform) || getPlatformStarting(activePlatform)
              ? 'bg-green-500/15 text-green-600 dark:text-green-400'
              : 'bg-gray-500/15 text-gray-500 dark:text-gray-400'
          }`}>
            {getPlatformConnected(activePlatform)
              ? i18nService.t('connected')
              : getPlatformStarting(activePlatform)
                ? (i18nService.t('starting') || '启动中')
                : i18nService.t('disconnected')}
          </div>
        </div>

        {/* DingTalk Settings */}
        {activePlatform === 'dingtalk' && (
          <div className="space-y-3">
            {/* Usage Instructions */}
            <div className="bg-green-500/10 border border-green-500/20 rounded-lg px-3 py-2">
              <p className="text-xs text-green-700 dark:text-green-400">
                <span className="font-medium">💬 双向通信模式</span> - 支持接收和发送消息。配置后点击"测试连通性"，然后在钉钉中发送消息给机器人即可实时接收。
              </p>
            </div>

            {/* Help Link */}
            <div className="flex items-center gap-2 text-xs">
              <a
                href="https://open.dingtalk.com/document/isv/app-types-intro"
                target="_blank"
                rel="noopener noreferrer"
                className="text-claude-accent hover:text-claude-accent/80 dark:text-claude-accent dark:hover:text-claude-accent/80 flex items-center gap-1"
              >
                <span>📖 如何获取 Client ID 和 Secret</span>
              </a>
            </div>

            {/* Client ID */}
            <div className="space-y-1.5">
              <label className="block text-xs font-medium dark:text-claude-darkTextSecondary text-claude-textSecondary">
                Client ID (AppKey)
              </label>
              <input
                type="text"
                value={config.dingtalk.clientId}
                onChange={(e) => handleDingTalkChange('clientId', e.target.value)}
                onBlur={handleSaveConfig}
                className="block w-full rounded-lg dark:bg-claude-darkSurface/80 bg-claude-surface/80 dark:border-claude-darkBorder/60 border-claude-border/60 border focus:border-claude-accent focus:ring-1 focus:ring-claude-accent/30 dark:text-claude-darkText text-claude-text px-3 py-2 text-sm transition-colors"
                placeholder="dingxxxxxx"
              />
            </div>

            {/* Client Secret */}
            <div className="space-y-1.5">
              <label className="block text-xs font-medium dark:text-claude-darkTextSecondary text-claude-textSecondary">
                Client Secret (AppSecret)
              </label>
              <input
                type="password"
                value={config.dingtalk.clientSecret}
                onChange={(e) => handleDingTalkChange('clientSecret', e.target.value)}
                onBlur={handleSaveConfig}
                className="block w-full rounded-lg dark:bg-claude-darkSurface/80 bg-claude-surface/80 dark:border-claude-darkBorder/60 border-claude-border/60 border focus:border-claude-accent focus:ring-1 focus:ring-claude-accent/30 dark:text-claude-darkText text-claude-text px-3 py-2 text-sm transition-colors"
                placeholder="••••••••••••"
              />
            </div>

            {/* Agent ID */}
            <div className="space-y-1.5">
              <label className="block text-xs font-medium dark:text-claude-darkTextSecondary text-claude-textSecondary">
                Agent ID (可选)
              </label>
              <input
                type="text"
                value={config.dingtalk.agentId || ''}
                onChange={(e) => handleDingTalkChange('agentId', e.target.value)}
                onBlur={handleSaveConfig}
                className="block w-full rounded-lg dark:bg-claude-darkSurface/80 bg-claude-surface/80 dark:border-claude-darkBorder/60 border-claude-border/60 border focus:border-claude-accent focus:ring-1 focus:ring-claude-accent/30 dark:text-claude-darkText text-claude-text px-3 py-2 text-sm transition-colors"
                placeholder="123456789"
              />
            </div>

            {/* Robot Code */}
            <div className="space-y-1.5">
              <label className="block text-xs font-medium dark:text-claude-darkTextSecondary text-claude-textSecondary">
                Robot Code (可选)
              </label>
              <input
                type="text"
                value={config.dingtalk.robotCode || ''}
                onChange={(e) => handleDingTalkChange('robotCode', e.target.value)}
                onBlur={handleSaveConfig}
                className="block w-full rounded-lg dark:bg-claude-darkSurface/80 bg-claude-surface/80 dark:border-claude-darkBorder/60 border-claude-border/60 border focus:border-claude-accent focus:ring-1 focus:ring-claude-accent/30 dark:text-claude-darkText text-claude-text px-3 py-2 text-sm transition-colors"
                placeholder="robot_code"
              />
            </div>

            <div className="pt-1">
              {renderConnectivityTestButton('dingtalk')}
            </div>

            {/* Error display */}
            {status.dingtalk.lastError && (
              <div className="text-xs text-red-500 bg-red-500/10 px-3 py-2 rounded-lg">
                {status.dingtalk.lastError}
              </div>
            )}
          </div>
        )}

        {/* Feishu Settings */}
        {activePlatform === 'feishu' && (
          <div className="space-y-3">
            {/* Usage Instructions */}
            <div className="bg-green-500/10 border border-green-500/20 rounded-lg px-3 py-2">
              <p className="text-xs text-green-700 dark:text-green-400">
                <span className="font-medium">💬 双向通信模式</span> - 支持接收和发送消息。配置后点击"测试连通性"，然后在飞书中发送消息给机器人即可实时接收。
              </p>
            </div>

            {/* Help Link */}
            <div className="flex items-center gap-2 text-xs">
              <a
                href="#"
                onClick={async (e) => {
                  e.preventDefault();
                  const url = 'https://open.feishu.cn/app/';
                  try {
                    const { tauriApi } = await import('../../services/tauriApi');
                    // Open in browser and copy to clipboard
                    await tauriApi.shell.openExternal(url);
                  } catch (error) {
                    console.error('Failed to copy and open link:', error);
                  }
                }}
                className="text-claude-accent hover:text-claude-accent/80 dark:text-claude-accent dark:hover:text-claude-accent/80 flex items-center gap-1 cursor-pointer"
              >
                <span>📖 如何创建飞书自定义应用</span>
              </a>
            </div>

            {/* App ID */}
            <div className="space-y-1.5">
              <label className="block text-xs font-medium dark:text-claude-darkTextSecondary text-claude-textSecondary">
                App ID
              </label>
              <input
                type="text"
                value={config.feishu.appId}
                onChange={(e) => handleFeishuChange('appId', e.target.value)}
                onBlur={handleSaveConfig}
                className="block w-full rounded-lg dark:bg-claude-darkSurface/80 bg-claude-surface/80 dark:border-claude-darkBorder/60 border-claude-border/60 border focus:border-claude-accent focus:ring-1 focus:ring-claude-accent/30 dark:text-claude-darkText text-claude-text px-3 py-2 text-sm transition-colors"
                placeholder="cli_xxxxx"
              />
            </div>

            {/* App Secret */}
            <div className="space-y-1.5">
              <label className="block text-xs font-medium dark:text-claude-darkTextSecondary text-claude-textSecondary">
                App Secret
              </label>
              <input
                type="password"
                value={config.feishu.appSecret}
                onChange={(e) => handleFeishuChange('appSecret', e.target.value)}
                onBlur={handleSaveConfig}
                className="block w-full rounded-lg dark:bg-claude-darkSurface/80 bg-claude-surface/80 dark:border-claude-darkBorder/60 border-claude-border/60 border focus:border-claude-accent focus:ring-1 focus:ring-claude-accent/30 dark:text-claude-darkText text-claude-text px-3 py-2 text-sm transition-colors"
                placeholder="••••••••••••"
              />
            </div>

            {/* Domain Selection */}
            <div className="space-y-1.5">
              <label className="block text-xs font-medium dark:text-claude-darkTextSecondary text-claude-textSecondary">
                域名
              </label>
              <select
                value={config.feishu.domain || 'feishu'}
                onChange={(e) => dispatch(setFeishuConfig({ domain: e.target.value as 'feishu' | 'lark' }))}
                onBlur={handleSaveConfig}
                className="block w-full rounded-lg dark:bg-claude-darkSurface/80 bg-claude-surface/80 dark:border-claude-darkBorder/60 border-claude-border/60 border focus:border-claude-accent focus:ring-1 focus:ring-claude-accent/30 dark:text-claude-darkText text-claude-text px-3 py-2 text-sm transition-colors"
              >
                <option value="feishu">飞书 (open.feishu.cn)</option>
                <option value="lark">Lark (open.larkoffice.com)</option>
              </select>
            </div>

            <div className="pt-1">
              {renderConnectivityTestButton('feishu')}
            </div>

            {/* Error display */}
            {status.feishu.error && (
              <div className="text-xs text-red-500 bg-red-500/10 px-3 py-2 rounded-lg">
                {status.feishu.error}
              </div>
            )}
          </div>
        )}

        {/* Telegram Settings */}
        {activePlatform === 'telegram' && (
          <div className="space-y-3">
            {/* Usage Instructions */}
            <div className="bg-green-500/10 border border-green-500/20 rounded-lg px-3 py-2">
              <p className="text-xs text-green-700 dark:text-green-400">
                <span className="font-medium">💬 双向通信模式</span> - 支持接收和发送消息。配置后点击"测试连通性"，然后在 Telegram 中发送消息给 Bot 即可实时接收。
              </p>
            </div>

            {/* Help Link */}
            <div className="flex items-center gap-2 text-xs">
              <a
                href="https://core.telegram.org/bots/tutorial"
                target="_blank"
                rel="noopener noreferrer"
                className="text-claude-accent hover:text-claude-accent/80 dark:text-claude-accent dark:hover:text-claude-accent/80 flex items-center gap-1"
              >
                <span>📖 如何通过 @BotFather 创建 Bot</span>
              </a>
            </div>

            {/* Bot Token */}
            <div className="space-y-1.5">
              <label className="block text-xs font-medium dark:text-claude-darkTextSecondary text-claude-textSecondary">
                Bot Token
              </label>
              <input
                type="password"
                value={config.telegram.botToken}
                onChange={(e) => handleTelegramChange('botToken', e.target.value)}
                onBlur={handleSaveConfig}
                className="block w-full rounded-lg dark:bg-claude-darkSurface/80 bg-claude-surface/80 dark:border-claude-darkBorder/60 border-claude-border/60 border focus:border-claude-accent focus:ring-1 focus:ring-claude-accent/30 dark:text-claude-darkText text-claude-text px-3 py-2 text-sm transition-colors"
                placeholder="123456789:ABCdefGHIjklMNOpqrsTUVwxyz"
              />
              <p className="text-xs text-claude-textSecondary dark:text-claude-darkTextSecondary">
                {i18nService.t('telegramTokenHint') || '从 @BotFather 获取 Bot Token'}
              </p>
            </div>

            <div className="pt-1">
              {renderConnectivityTestButton('telegram')}
            </div>

            {/* Bot username display */}
            {status.telegram.botUsername && (
              <div className="text-xs text-green-600 dark:text-green-400 bg-green-500/10 px-3 py-2 rounded-lg">
                Bot: @{status.telegram.botUsername}
              </div>
            )}

            {/* Error display */}
            {status.telegram.lastError && (
              <div className="text-xs text-red-500 bg-red-500/10 px-3 py-2 rounded-lg">
                {status.telegram.lastError}
              </div>
            )}
          </div>
        )}

        {/* Discord Settings */}
        {activePlatform === 'discord' && (
          <div className="space-y-3">
            {/* Usage Instructions */}
            <div className="bg-green-500/10 border border-green-500/20 rounded-lg px-3 py-2">
              <p className="text-xs text-green-700 dark:text-green-400">
                <span className="font-medium">💬 双向通信模式</span> - 支持接收和发送消息。配置后点击"测试连通性"，然后在 Discord 中发送消息给 Bot 即可实时接收。
              </p>
            </div>

            {/* Help Link */}
            <div className="flex items-center gap-2 text-xs">
              <a
                href="https://discord.com/developers/applications"
                target="_blank"
                rel="noopener noreferrer"
                className="text-claude-accent hover:text-claude-accent/80 dark:text-claude-accent dark:hover:text-claude-accent/80 flex items-center gap-1"
              >
                <span>📖 如何在 Discord Developer Portal 创建 Bot</span>
              </a>
            </div>

            {/* Bot Token */}
            <div className="space-y-1.5">
              <label className="block text-xs font-medium dark:text-claude-darkTextSecondary text-claude-textSecondary">
                Bot Token
              </label>
              <input
                type="password"
                value={config.discord.botToken}
                onChange={(e) => handleDiscordChange('botToken', e.target.value)}
                onBlur={handleSaveConfig}
                className="block w-full rounded-lg dark:bg-claude-darkSurface/80 bg-claude-surface/80 dark:border-claude-darkBorder/60 border-claude-border/60 border focus:border-claude-accent focus:ring-1 focus:ring-claude-accent/30 dark:text-claude-darkText text-claude-text px-3 py-2 text-sm transition-colors"
                placeholder="MTIzNDU2Nzg5MDEyMzQ1Njc4OQ..."
              />
              <p className="text-xs text-claude-textSecondary dark:text-claude-darkTextSecondary">
                从 Discord Developer Portal 获取 Bot Token
              </p>
            </div>

            <div className="pt-1">
              {renderConnectivityTestButton('discord')}
            </div>

            {/* Bot username display */}
            {status.discord.botUsername && (
              <div className="text-xs text-green-600 dark:text-green-400 bg-green-500/10 px-3 py-2 rounded-lg">
                Bot: {status.discord.botUsername}
              </div>
            )}

            {/* Error display */}
            {status.discord.lastError && (
              <div className="text-xs text-red-500 bg-red-500/10 px-3 py-2 rounded-lg">
                {status.discord.lastError}
              </div>
            )}
          </div>
        )}

        {/* WeWork Settings */}
        {activePlatform === 'wework' && (
          <div className="space-y-3">
            {/* Usage Instructions */}
            <div className="bg-blue-500/10 border border-blue-500/20 rounded-lg px-3 py-2">
              <p className="text-xs text-blue-700 dark:text-blue-400">
                <span className="font-medium">📤 Webhook 模式</span> - 使用群机器人 Webhook 发送消息。在企业微信群中添加机器人，复制 Webhook 地址即可。
              </p>
            </div>

            {/* Help Link */}
            <div className="flex items-center gap-2 text-xs">
              <a
                href="#"
                onClick={(e) => {
                  e.preventDefault();
                  window.open('https://developer.work.weixin.qq.com/document/path/91770', '_blank');
                }}
                className="text-claude-accent hover:text-claude-accent/80 dark:text-claude-accent dark:hover:text-claude-accent/80 flex items-center gap-1"
              >
                <span>📖 如何获取群机器人 Webhook</span>
              </a>
            </div>

            {/* Webhook URL */}
            <div className="space-y-1.5">
              <label className="block text-xs font-medium dark:text-claude-darkTextSecondary text-claude-textSecondary">
                Webhook URL
              </label>
              <input
                type="password"
                value={config.wework.webhookUrl || ''}
                onChange={(e) => handleWeWorkChange('webhookUrl', e.target.value)}
                onBlur={handleSaveConfig}
                className="block w-full rounded-lg dark:bg-claude-darkSurface/80 bg-claude-surface/80 dark:border-claude-darkBorder/60 border-claude-border/60 border focus:border-claude-accent focus:ring-1 focus:ring-claude-accent/30 dark:text-claude-darkText text-claude-text px-3 py-2 text-sm transition-colors"
                placeholder="https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxxxx"
              />
              <p className="text-xs text-claude-textSecondary dark:text-claude-darkTextSecondary">
                在企业微信群中添加机器人后获取的 Webhook 地址
              </p>
            </div>

            <div className="pt-1">
              {renderConnectivityTestButton('wework')}
            </div>

            {/* Error display */}
            {status.wework.lastError && (
              <div className="text-xs text-red-500 bg-red-500/10 px-3 py-2 rounded-lg">
                {status.wework.lastError}
              </div>
            )}
          </div>
        )}

        {/* WhatsApp Settings */}
        {activePlatform === 'whatsapp' && (
          <div className="space-y-3">
            {/* Mode Notice */}
            <div className="bg-yellow-500/10 border border-yellow-500/20 rounded-lg px-3 py-2">
              <p className="text-xs text-yellow-600 dark:text-yellow-400">
                <span className="font-medium">📤 仅发送消息模式</span> - WhatsApp 不支持无公网IP接收消息，此模式仅支持发送消息。如需接收消息，请使用钉钉、飞书或 Telegram。
              </p>
            </div>

            {/* Help Link */}
            <div className="flex items-center gap-2 text-xs">
              <a
                href="https://developers.facebook.com/docs/whatsapp/cloud-api/get-started"
                target="_blank"
                rel="noopener noreferrer"
                className="text-claude-accent hover:text-claude-accent/80 dark:text-claude-accent dark:hover:text-claude-accent/80 flex items-center gap-1"
              >
                <span>📖 如何获取 WhatsApp Business API</span>
              </a>
            </div>

            {/* Phone Number ID */}
            <div className="space-y-1.5">
              <label className="block text-xs font-medium dark:text-claude-darkTextSecondary text-claude-textSecondary">
                Phone Number ID (电话号码 ID)
              </label>
              <input
                type="text"
                value={config.whatsapp.phoneNumberId || ''}
                onChange={(e) => handleWhatsAppChange('phoneNumberId', e.target.value)}
                onBlur={handleSaveConfig}
                className="block w-full rounded-lg dark:bg-claude-darkSurface/80 bg-claude-surface/80 dark:border-claude-darkBorder/60 border-claude-border/60 border focus:border-claude-accent focus:ring-1 focus:ring-claude-accent/30 dark:text-claude-darkText text-claude-text px-3 py-2 text-sm transition-colors"
                placeholder="123456789012345"
              />
            </div>

            {/* Access Token */}
            <div className="space-y-1.5">
              <label className="block text-xs font-medium dark:text-claude-darkTextSecondary text-claude-textSecondary">
                Access Token (访问令牌)
              </label>
              <input
                type="password"
                value={config.whatsapp.accessToken || ''}
                onChange={(e) => handleWhatsAppChange('accessToken', e.target.value)}
                onBlur={handleSaveConfig}
                className="block w-full rounded-lg dark:bg-claude-darkSurface/80 bg-claude-surface/80 dark:border-claude-darkBorder/60 border-claude-border/60 border focus:border-claude-accent focus:ring-1 focus:ring-claude-accent/30 dark:text-claude-darkText text-claude-text px-3 py-2 text-sm transition-colors"
                placeholder="EAACck..."
              />
              <p className="text-xs text-claude-textSecondary dark:text-claude-darkTextSecondary">
                从 WhatsApp Business Platform 获取访问令牌
              </p>
            </div>

            <div className="pt-1">
              {renderConnectivityTestButton('whatsapp')}
            </div>

            {/* Error display */}
            {status.whatsapp.lastError && (
              <div className="text-xs text-red-500 bg-red-500/10 px-3 py-2 rounded-lg">
                {status.whatsapp.lastError}
              </div>
            )}
          </div>
        )}

        {connectivityModalPlatform && (
          <div
            className="fixed inset-0 z-50 bg-black/40 flex items-center justify-center p-4"
            onClick={() => setConnectivityModalPlatform(null)}
          >
            <div
              className="w-full max-w-2xl dark:bg-claude-darkSurface bg-claude-surface rounded-2xl shadow-modal border dark:border-claude-darkBorder border-claude-border overflow-hidden"
              onClick={(e) => e.stopPropagation()}
            >
              <div className="px-4 py-3 border-b dark:border-claude-darkBorder border-claude-border flex items-center justify-between">
                <div className="text-sm font-semibold dark:text-claude-darkText text-claude-text">
                  {`${i18nService.t(connectivityModalPlatform)} ${i18nService.t('imConnectivitySectionTitle')}`}
                </div>
                <button
                  type="button"
                  aria-label={i18nService.t('close')}
                  onClick={() => setConnectivityModalPlatform(null)}
                  className="p-1 rounded-md dark:hover:bg-claude-darkSurfaceHover hover:bg-claude-surfaceHover dark:text-claude-darkTextSecondary text-claude-textSecondary"
                >
                  <XMarkIcon className="h-4 w-4" />
                </button>
              </div>

              <div className="p-4 max-h-[65vh] overflow-y-auto">
                {testingPlatform === connectivityModalPlatform ? (
                  <div className="text-sm dark:text-claude-darkTextSecondary text-claude-textSecondary">
                    {i18nService.t('imConnectivityTesting')}
                  </div>
                ) : connectivityResults[connectivityModalPlatform] ? (
                  <div className="space-y-3">
                    <div className="flex items-center justify-between gap-2">
                      <div className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium ${verdictColorClass[connectivityResults[connectivityModalPlatform]!.verdict]}`}>
                        {connectivityResults[connectivityModalPlatform]!.verdict === 'pass' ? (
                          <CheckCircleIcon className="h-3.5 w-3.5" />
                        ) : connectivityResults[connectivityModalPlatform]!.verdict === 'warn' ? (
                          <ExclamationTriangleIcon className="h-3.5 w-3.5" />
                        ) : (
                          <XCircleIcon className="h-3.5 w-3.5" />
                        )}
                        {i18nService.t(`imConnectivityVerdict_${connectivityResults[connectivityModalPlatform]!.verdict}`)}
                      </div>
                      <div className="text-[11px] dark:text-claude-darkTextSecondary text-claude-textSecondary">
                        {`${i18nService.t('imConnectivityLastChecked')}: ${formatTestTime(connectivityResults[connectivityModalPlatform]!.testedAt)}`}
                      </div>
                    </div>

                    <div className="space-y-2">
                      {connectivityResults[connectivityModalPlatform]!.checks.map((check, index) => (
                        <div
                          key={`${check.code}-${index}`}
                          className="rounded-lg border dark:border-claude-darkBorder/60 border-claude-border/60 px-2.5 py-2 dark:bg-claude-darkSurface/25 bg-white/70"
                        >
                          <div className={`text-xs font-medium ${checkLevelColorClass[check.level]}`}>
                            {getCheckTitle(check.code)}
                          </div>
                          <div className="mt-1 text-xs dark:text-claude-darkTextSecondary text-claude-textSecondary">
                            {check.message}
                          </div>
                          {getCheckSuggestion(check) && (
                            <div className="mt-1 text-[11px] dark:text-claude-darkTextSecondary text-claude-textSecondary">
                              {`${i18nService.t('imConnectivitySuggestion')}: ${getCheckSuggestion(check)}`}
                            </div>
                          )}
                        </div>
                      ))}
                    </div>
                  </div>
                ) : (
                  <div className="text-sm dark:text-claude-darkTextSecondary text-claude-textSecondary">
                    {i18nService.t('imConnectivityNoResult')}
                  </div>
                )}
              </div>

              <div className="px-4 py-3 border-t dark:border-claude-darkBorder border-claude-border flex items-center justify-end">
                {renderConnectivityTestButton(connectivityModalPlatform)}
              </div>
            </div>
          </div>
        )}
      </div>
      </div>
    </div>
  );
};

export default IMSettings;
