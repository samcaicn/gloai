/**
 * NavBar — tupai 阶段 3 顶部栏。
 *
 * 顶部独立栏：左侧租户标签，右侧两个图标按钮：
 *   - 设置（⚙）：切换到 settings scene
 *   - 中英文切换：在 zh-CN / en-US 之间切换
 *
 * 租户标签（UI-3-2）：
 *   - 挂载时调用 tenantGet() 加载当前租户
 *   - 已注册：显示租户名 + plan 徽章
 *   - 未注册：显示「注册租户」按钮，点击弹出输入框，调用 tenantRegister()
 *
 * 与 app/components/NavBar（侧栏导航历史栏）是两个不同组件，
 * 这里渲染在 AppLayout 顶部，不影响现有 workspace/scene 渲染逻辑。
 */

import React, { useCallback, useEffect, useState } from 'react';
import { Settings, UserPlus, RefreshCw, PictureInPicture2, Box, Tag, ExternalLink } from 'lucide-react';
import { Button, Tooltip } from '@/component-library';
import { useI18n } from '@/infrastructure/i18n';
import { useSceneManager } from '../hooks/useSceneManager';
import { tenantGet, tenantRegister, tenantInfo, fwOpen } from '@/infrastructure/api/tupai';
import { openExternalUrl } from '@/infrastructure/runtime';
import type { TenantInfo } from '@/infrastructure/api/tupai';
import { createLogger } from '@/shared/utils/logger';
import { useSettingsOverlayStore } from '../scenes/settings/settingsOverlayStore';
import './NavBar.scss';

const log = createLogger('layout/NavBar');

/** 租户 tag 跳转链接的兜底地址。
 *  当 MCP `tenant.get` 未返回 website / 解析失败 / 非 http(s) 协议时，
 *  用 safeopc.cn 作 fallback。 */
const FALLBACK_TENANT_WEBSITE = 'https://safeopc.cn';

const NavBar: React.FC = () => {
  const { t, currentLanguage, changeLanguage } = useI18n('common');
  const { openScene } = useSceneManager();
  const openSettingsOverlay = useSettingsOverlayStore((s) => s.open);

  // ---- 租户状态 ----
  const [tenant, setTenant] = useState<TenantInfo | null>(null);
  // MCP 拉取的租户 logo 文字和 website
  // 优先用 logoText（服务器配置的品牌名），回退到 tags[0]
  const [mcpTag, setMcpTag] = useState<string | null>(null);
  const [mcpWebsite, setMcpWebsite] = useState<string | null>(null);
  const [tenantLoading, setTenantLoading] = useState<boolean>(false);
  const [tenantError, setTenantError] = useState<string | null>(null);
  const [showRegister, setShowRegister] = useState<boolean>(false);
  const [regName, setRegName] = useState<string>('');
  const [registering, setRegistering] = useState<boolean>(false);

  const loadTenant = useCallback(async () => {
    setTenantLoading(true);
    setTenantError(null);
    try {
      // 先读本地租户信息（快速渲染）
      const localInfo = await tenantGet();
      setTenant(localInfo);
      // 再异步拉取 MCP tags + website（logo 文字和跳转链接）
      if (localInfo.id) {
        let deviceToken: string | null = null;
        try {
          deviceToken = typeof localStorage !== 'undefined' ? localStorage.getItem('trae_device_token') : null;
        } catch { /* ignore */ }
        try {
          const mcpInfo = await tenantInfo(deviceToken ?? undefined);
          // 优先用 logoText（服务器配置的品牌名），回退到 tags[0]
          const logoText = typeof mcpInfo?.logoText === 'string' ? mcpInfo.logoText.trim() : '';
          const tag = logoText || mcpInfo?.tags?.[0];
          setMcpTag(typeof tag === 'string' && tag.trim() ? tag.trim() : null);
          // 优先用 websiteUrl（新格式），回退到 website（旧格式）
          const site = (typeof mcpInfo?.websiteUrl === 'string' ? mcpInfo.websiteUrl.trim() : '')
            || (typeof mcpInfo?.website === 'string' ? mcpInfo.website.trim() : '');
          setMcpWebsite(site || null);
        } catch (err) {
          log.warn('tenantInfo MCP fetch failed', err);
          setMcpTag(null);
          setMcpWebsite(null);
        }
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      log.error('Failed to load tenant', err);
      setTenantError(message);
    } finally {
      setTenantLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadTenant();
  }, [loadTenant]);

  const handleRegister = useCallback(async () => {
    const name = regName.trim();
    if (!name || registering) return;
    setRegistering(true);
    try {
      const info = await tenantRegister({ name });
      setTenant(info);
      setShowRegister(false);
      setRegName('');
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      log.error('Failed to register tenant', err);
      setTenantError(message);
    } finally {
      setRegistering(false);
    }
  }, [regName, registering]);

  const handleOpenSettings = useCallback(() => {
    // 设置改为浮层展示：不再走 scene tab，直接拉起 overlay。
    // 跨平台一致（Modal portal），无需 Tauri 浮窗。
    try {
      openSettingsOverlay();
    } catch (error) {
      log.error('Failed to open settings overlay', error);
    }
  }, [openSettingsOverlay]);

  const handleToggleLanguage = useCallback(() => {
    const next = currentLanguage === 'zh-CN' ? 'en-US' : 'zh-CN';
    void changeLanguage(next).catch((error) => {
      log.error('Failed to change language', { next, error });
    });
  }, [currentLanguage, changeLanguage]);

  const handleMiniMode = useCallback(async () => {
    try {
      await fwOpen({
        id: 'main-mini-' + Date.now(),
        title: 'tupai Mini',
        width: 320,
        height: 240,
      });
    } catch (err) {
      log.error('Failed to open mini mode floating window', err);
    }
  }, []);

  // 显示可切换到的目标语言：当前中文 → 显示 "EN"；当前英文 → 显示 "中"
  const langLabel = currentLanguage === 'zh-CN' ? 'EN' : '中';

  // 租户注册表单（内联，不依赖外部 Modal 组件）
  const registerForm = showRegister ? (
    <div className="bitfun-top-nav-bar__tenant-form">
      <input
        className="bitfun-top-nav-bar__tenant-input"
        type="text"
        placeholder={t('navBar.tenantNamePlaceholder')}
        value={regName}
        onChange={(e) => setRegName(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') void handleRegister();
          if (e.key === 'Escape') {
            setShowRegister(false);
            setRegName('');
          }
        }}
        autoFocus
        disabled={registering}
      />
      <button
        type="button"
        className="bitfun-top-nav-bar__tenant-btn bitfun-top-nav-bar__tenant-btn--primary"
        onClick={() => void handleRegister()}
        disabled={!regName.trim() || registering}
      >
        {registering ? t('navBar.registering') : t('actions.confirm')}
      </button>
      <button
        type="button"
        className="bitfun-top-nav-bar__tenant-btn"
        onClick={() => {
          setShowRegister(false);
          setRegName('');
        }}
        disabled={registering}
      >
        {t('actions.cancel')}
      </button>
    </div>
  ) : null;

  return (
    <div className="bitfun-top-nav-bar" role="toolbar" aria-label={t('navBar.language')}>
      <div className="bitfun-top-nav-bar__left">
        {/* 租户标签：优先展示 MCP logo 文字（tags[0]），
            未配置时展示本地租户名，都为空时展示注册按钮。
            logo 文字是外链：MCP website ?? tuptup.top 兜底。 */}
        <div className="bitfun-top-nav-bar__tenant">
          {tenantLoading ? (
            <span className="bitfun-top-nav-bar__tenant-loading">
              <RefreshCw size={12} className="bitfun-top-nav-bar__spinning" />
              <span>{t('navBar.tenantLoading')}</span>
            </span>
          ) : mcpTag ? (
            <a
              className="bitfun-top-nav-bar__tenant-logo"
              href={mcpWebsite ?? FALLBACK_TENANT_WEBSITE}
              target="_blank"
              rel="noopener noreferrer"
              title={mcpWebsite ?? FALLBACK_TENANT_WEBSITE}
              onClick={(e) => {
                e.preventDefault();
                e.stopPropagation();
                void openExternalUrl(mcpWebsite ?? FALLBACK_TENANT_WEBSITE);
              }}
            >
              <Tag size={11} aria-hidden="true" />
              <span className="bitfun-top-nav-bar__tenant-logo-text">{mcpTag}</span>
              <ExternalLink size={9} className="bitfun-top-nav-bar__tenant-logo-ext" aria-hidden="true" />
            </a>
          ) : tenant && tenant.id ? (
            <span className="bitfun-top-nav-bar__tenant-badge" title={tenant.id}>
              <span className="bitfun-top-nav-bar__tenant-dot" />
              <span className="bitfun-top-nav-bar__tenant-name">{tenant.name}</span>
              {tenant.plan && (
                <span className="bitfun-top-nav-bar__tenant-plan">{tenant.plan}</span>
              )}
            </span>
          ) : showRegister ? (
            registerForm
          ) : (
            <button
              type="button"
              className="bitfun-top-nav-bar__tenant-btn bitfun-top-nav-bar__tenant-btn--register"
              onClick={() => setShowRegister(true)}
              title={t('navBar.registerTenant')}
            >
              <UserPlus size={12} />
              <span>{t('navBar.registerTenant')}</span>
            </button>
          )}
          {tenantError && !showRegister && (
            <span className="bitfun-top-nav-bar__tenant-error" title={tenantError}>
              !
            </span>
          )}
        </div>
      </div>
      <div className="bitfun-top-nav-bar__right">
        <Tooltip content={t('footer.miniMode')} placement="bottom">
          <Button
            type="button"
            variant="ghost"
            size="small"
            iconOnly
            className="bitfun-top-nav-bar__btn"
            aria-label={t('footer.miniMode')}
            onClick={handleMiniMode}
          >
            <PictureInPicture2 size={16} />
          </Button>
        </Tooltip>
        <Tooltip content={t('scenes.miniApps')} placement="bottom">
          <Button
            type="button"
            variant="ghost"
            size="small"
            iconOnly
            className="bitfun-top-nav-bar__btn"
            aria-label={t('scenes.miniApps')}
            onClick={() => openScene('miniapps')}
          >
            <Box size={16} />
          </Button>
        </Tooltip>
        <Tooltip content={t('navBar.settings')} placement="bottom">
          <Button
            type="button"
            variant="ghost"
            size="small"
            iconOnly
            className="bitfun-top-nav-bar__btn"
            aria-label={t('navBar.settings')}
            onClick={handleOpenSettings}
          >
            <Settings size={16} />
          </Button>
        </Tooltip>
        <Tooltip content={t('navBar.language')} placement="bottom">
          <Button
            type="button"
            variant="ghost"
            size="small"
            className="bitfun-top-nav-bar__btn bitfun-top-nav-bar__btn--lang"
            aria-label={t('navBar.language')}
            onClick={handleToggleLanguage}
          >
            {langLabel}
          </Button>
        </Tooltip>
      </div>
    </div>
  );
};

export default NavBar;
