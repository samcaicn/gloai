/**
 * WelcomeScene — landing page shown on app start inside SceneViewport.
 *
 * Two modes:
 *  - Has workspace: welcome header + new-session shortcuts + workspace switching.
 *  - No workspace: branding + open/create project.
 */

import React, { useState, useCallback, useMemo, useEffect, lazy, Suspense } from 'react';
import {
  FolderOpen, Clock, FolderPlus, Trash2, ListChecks, ExternalLink, Tag,
} from 'lucide-react';
import { useWorkspaceContext } from '@/infrastructure/contexts/WorkspaceContext';
import { useSceneStore } from '@/app/stores/sceneStore';
import { useI18n } from '@/infrastructure/i18n';
import { Tooltip } from '@/component-library';
import { createLogger } from '@/shared/utils/logger';
import type { SceneTabId } from '@/app/components/SceneBar/types';
import type { WorkspaceInfo } from '@/shared/types';
import { getRecentWorkspaceLineParts } from '@/shared/utils/recentWorkspaceDisplay';
import { getBrandInfo, type BrandInfo } from '@/infrastructure/api/tupai/brand';
import { tenantGet, tenantInfo, type TenantInfo } from '@/infrastructure/api/tupai/tenant';
import { openExternalUrl } from '@/infrastructure/runtime';
import './WelcomeScene.scss';

const log = createLogger('WelcomeScene');

/** 兜底官网地址（MCP 未返回 website 时使用）。 */
const FALLBACK_BRAND_WEBSITE = 'https://safeopc.cn';

const WorkspacePickerDialog = lazy(() =>
  import('@/app/components/WorkspacePickerDialog').then(module => ({ default: module.WorkspacePickerDialog }))
);

const WelcomeScene: React.FC = () => {
  const { t, formatDate: formatLocaleDate } = useI18n('common');
  const {
    hasWorkspace, currentWorkspace, recentWorkspaces,
    openWorkspace, switchWorkspace, removeWorkspaceFromRecent,
  } = useWorkspaceContext();
  const openScene = useSceneStore(s => s.openScene);
  const [isSelecting, setIsSelecting] = useState(false);
  const [showWorkspacePicker, setShowWorkspacePicker] = useState(false);

  // ── 品牌信息（首页左上角）──
  // 优先级：MCP tenant.logoText / tags[0] → 本地 BrandInfo.publisher → 'tupai'
  // 网站优先级：MCP tenant.websiteUrl / website → BrandInfo.homepage → FALLBACK_BRAND_WEBSITE
  const [brandName, setBrandName] = useState<string>('');
  const [brandWebsite, setBrandWebsite] = useState<string>('');

  useEffect(() => {
    let disposed = false;
    void (async () => {
      // 1. 同步读 BrandInfo（本地编译期注入的品牌名 + homepage）
      let localBrand: BrandInfo | null = null;
      try {
        localBrand = await getBrandInfo();
      } catch (err) {
        log.warn('getBrandInfo failed', err);
      }
      if (disposed) return;

      const localName = localBrand?.publisher || '';
      const localSite = localBrand?.homepage || '';

      // 2. 异步拉 MCP tenant 信息（服务器配置的 logoText / websiteUrl）
      let mcpName = '';
      let mcpSite = '';
      try {
        const localInfo = await tenantGet();
        if (localInfo?.id) {
          let deviceToken: string | null = null;
          try {
            deviceToken = localStorage.getItem('trae_device_token');
          } catch { /* ignore */ }
          const mcpInfo: TenantInfo = await tenantInfo(deviceToken ?? undefined);
          const logoText = typeof mcpInfo?.logoText === 'string' ? mcpInfo.logoText.trim() : '';
          mcpName = logoText || mcpInfo?.tags?.[0] || '';
          mcpSite = (typeof mcpInfo?.websiteUrl === 'string' ? mcpInfo.websiteUrl.trim() : '')
            || (typeof mcpInfo?.website === 'string' ? mcpInfo.website.trim() : '');
        }
      } catch (err) {
        log.warn('tenantInfo MCP fetch failed', err);
      }
      if (disposed) return;

      // 3. 合并：MCP 优先，本地兜底
      setBrandName(mcpName || localName || 'tupai');
      setBrandWebsite(mcpSite || localSite || FALLBACK_BRAND_WEBSITE);
    })();
    return () => { disposed = true; };
  }, []);

  const handleBrandClick = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    void openExternalUrl(brandWebsite || FALLBACK_BRAND_WEBSITE);
  }, [brandWebsite]);
  const [welcomeMessageIndex] = useState(
    () => Math.floor(Math.random() * 4),
  );
  const welcomeMessages = useMemo(
    () => [
      t('welcomeScene.messages.message1'),
      t('welcomeScene.messages.message2'),
      t('welcomeScene.messages.message3'),
      t('welcomeScene.messages.message4'),
    ],
    [t],
  );
  const welcomeMessage = welcomeMessages[welcomeMessageIndex % welcomeMessages.length];

  const displayRecentWorkspaces = useMemo(
    () => (hasWorkspace
      ? recentWorkspaces.filter(ws => ws.id !== currentWorkspace?.id)
      : recentWorkspaces
    ).slice(0, 5),
    [hasWorkspace, recentWorkspaces, currentWorkspace?.id],
  );

  const handleOpenFolder = useCallback(async () => {
    try {
      setIsSelecting(true);
      const { open } = await import('@tauri-apps/plugin-dialog');
      const selected = await open({
        directory: true,
        multiple: false,
        title: t('startup.selectWorkspaceDirectory'),
      });
      if (selected && typeof selected === 'string') {
        await openWorkspace(selected);
        openScene('session' as SceneTabId);
      }
    } catch (e) {
      log.error('Failed to open folder', e);
    } finally {
      setIsSelecting(false);
    }
  }, [openWorkspace, openScene, t]);

  const handleNewProject = useCallback(() => {
    window.dispatchEvent(new Event('nav:new-project'));
  }, []);

  const handleOpenPicker = useCallback(() => {
    setShowWorkspacePicker(true);
  }, []);

  const handlePickerSelected = useCallback(() => {
    openScene('session' as SceneTabId);
  }, [openScene]);

  const handleSwitchWorkspace = useCallback(async (workspace: WorkspaceInfo) => {
    try {
      await switchWorkspace(workspace);
      openScene('session' as SceneTabId);
    } catch (e) {
      log.error('Failed to switch workspace', e);
    }
  }, [switchWorkspace, openScene]);

  const handleRemoveFromRecent = useCallback(async (workspaceId: string) => {
    try {
      await removeWorkspaceFromRecent(workspaceId);
    } catch (e) {
      log.error('Failed to remove workspace from recent', e);
    }
  }, [removeWorkspaceFromRecent]);

  const formatDate = useCallback((dateString: string) => {
    try {
      const date = new Date(dateString);
      const now = new Date();
      const diffMs = Math.abs(now.getTime() - date.getTime());
      const diffDays = Math.ceil(diffMs / (1000 * 60 * 60 * 24));
      if (diffDays <= 1) return t('time.yesterday');
      if (diffDays < 7) return t('startup.daysAgo', { count: diffDays });
      if (diffDays < 30) return t('startup.weeksAgo', { count: Math.ceil(diffDays / 7) });
      return formatLocaleDate(date);
    } catch {
      return '';
    }
  }, [formatLocaleDate, t]);

  return (
    <div className="welcome-scene">
      {/* 首页左上角：品牌名 + website 链接
          优先用 MCP tenant.logoText / tags[0]，兜底用本地 BrandInfo.publisher。
          点击调用 open_external 在系统浏览器打开（避免 WebView 内打开）。 */}
      {brandName && (
        <a
          className="welcome-scene__brand"
          href={brandWebsite || FALLBACK_BRAND_WEBSITE}
          target="_blank"
          rel="noopener noreferrer"
          title={brandWebsite || FALLBACK_BRAND_WEBSITE}
          onClick={handleBrandClick}
        >
          <Tag size={11} aria-hidden="true" />
          <span className="welcome-scene__brand-name">{brandName}</span>
          <ExternalLink size={9} className="welcome-scene__brand-ext" aria-hidden="true" />
        </a>
      )}
      <div className="welcome-scene__content">
        <div className="welcome-scene__greeting">
          <h1 className="welcome-scene__title">{t('welcomeScene.firstTime.title')}</h1>
          <p className="welcome-scene__greeting-label">{welcomeMessage}</p>
        </div>

        <div className="welcome-scene__divider" />

        <section className="welcome-scene__switch">
          <div className="welcome-scene__switch-header">
            <span className="welcome-scene__section-label">
              <Clock size={12} />
              {t('welcomeScene.recentWorkspaces')}
            </span>
            <div className="welcome-scene__switch-actions">
              <button
                className="welcome-scene__link-btn"
                onClick={handleOpenPicker}
                title={t('workspacePicker.title')}
              >
                <ListChecks size={12} />
                {t('welcomeScene.selectWorkspace')}
              </button>
              <button
                className="welcome-scene__link-btn"
                onClick={() => void handleOpenFolder()}
                disabled={isSelecting}
              >
                <FolderOpen size={12} />
                {t('welcomeScene.openOtherProject')}
              </button>
              <button className="welcome-scene__link-btn" onClick={handleNewProject}>
                <FolderPlus size={12} />
                {t('welcomeScene.newProject')}
              </button>
            </div>
          </div>

          {displayRecentWorkspaces.length > 0 ? (
            <div className="welcome-scene__recent-list">
              {displayRecentWorkspaces.map(ws => {
                const { hostPrefix, folderLabel, tooltip } = getRecentWorkspaceLineParts(ws);
                return (
                <div key={ws.id} className="welcome-scene__recent-row">
                  <Tooltip content={tooltip} placement="right" followCursor>
                    <button
                      type="button"
                      className="welcome-scene__recent-item"
                      onClick={() => { void handleSwitchWorkspace(ws); }}
                    >
                      <FolderOpen size={13} />
                      <span className="welcome-scene__recent-name">
                        {hostPrefix ? (
                          <>
                            <span className="welcome-scene__recent-host">{hostPrefix}</span>
                            <span className="welcome-scene__recent-host-sep" aria-hidden>
                              {' · '}
                            </span>
                          </>
                        ) : null}
                        {folderLabel}
                      </span>
                    </button>
                  </Tooltip>
                  <button
                    type="button"
                    className="welcome-scene__recent-time-btn"
                    title={t('welcomeScene.removeFromRecent')}
                    aria-label={t('welcomeScene.removeFromRecent')}
                    onClick={() => { void handleRemoveFromRecent(ws.id); }}
                  >
                    <span className="welcome-scene__recent-time-btn__label">
                      {formatDate(ws.lastAccessed)}
                    </span>
                    <span className="welcome-scene__recent-time-btn__icon" aria-hidden>
                      <Trash2 size={15} strokeWidth={2} />
                    </span>
                  </button>
                </div>
                );
              })}
            </div>
          ) : (
            <p className="welcome-scene__no-recent">{t('welcomeScene.noRecentWorkspaces')}</p>
          )}
        </section>

      </div>

      {showWorkspacePicker && (
        <Suspense fallback={null}>
          <WorkspacePickerDialog
            isOpen={showWorkspacePicker}
            onClose={() => setShowWorkspacePicker(false)}
            onSelected={handlePickerSelected}
          />
        </Suspense>
      )}
    </div>
  );
};

export default WelcomeScene;
