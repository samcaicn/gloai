/**
 * SceneBar — horizontal scene-level tab bar (32px).
 *
 * Delegates state to useSceneManager.
 * AI Agent tab shows the current session title as a subtitle.
 */

import React, { useCallback, useRef, useState, useEffect } from 'react';
import { Settings as SettingsIcon, PictureInPicture2, Sun, Moon, MessageSquare, X } from 'lucide-react';
import { WindowControls } from '@/component-library';
import { useI18n } from '@/infrastructure/i18n/hooks/useI18n';
import { useTheme } from '@/infrastructure/theme';
import { getDeviceApprovalStatus } from '@/infrastructure/api/tupai/device';
import { supportsNativeWindowDragging, isTauriRuntime } from '@/infrastructure/runtime';
import { fwOpen, fwGetState, fwMinimize, fwRestore, fwHideMainWindow } from '@/infrastructure/api/tupai/floater';
import { useSettingsOverlayStore } from '../../scenes/settings/settingsOverlayStore';
import { createLogger } from '@/shared/utils/logger';
import './SceneBar.scss';

const log = createLogger('SceneBar');

const INTERACTIVE_SELECTOR =
  'button, input, textarea, select, a, [role="button"], [contenteditable="true"], .window-controls';

interface SceneBarProps {
  className?: string;
  onMinimize?: () => void;
  onMaximize?: () => void;
  onClose?: () => void;
  isMaximized?: boolean;
}

const SceneBar: React.FC<SceneBarProps> = ({
  className = '',
  onMinimize,
  onMaximize,
  onClose,
  isMaximized = false,
}) => {
  const { t, currentLanguage, changeLanguage } = useI18n('common');
  const hasWindowControls = !!(onMinimize && onMaximize && onClose);
  const openSettingsOverlay = useSettingsOverlayStore((s) => s.open);

  // ── Floating chat button state (moved from ChatFloaterButton) ──
  const FLOATER_ID = 'chat-floater';
  const [floaterSnapshot, setFloaterSnapshot] = useState<{ exists: boolean; docked: boolean }>({ exists: false, docked: false });

  // ── Theme toggle (using real ThemeService) ──
  const { isDark, setTheme } = useTheme();

  const handleToggleTheme = useCallback(() => {
    const next = isDark ? 'bitfun-light' : 'bitfun-dark';
    void setTheme(next);
    document.documentElement.removeAttribute('data-scheme');
    log.debug('Theme toggled', { next });
  }, [isDark, setTheme]);

  // ── Language toggle (EN / 中 one-click) ──
  const handleToggleLanguage = useCallback(() => {
    const next = currentLanguage === 'zh-CN' ? 'en-US' : 'zh-CN';
    void changeLanguage(next).catch((error) => {
      log.error('Failed to change language', { next, error });
    });
  }, [currentLanguage, changeLanguage]);

  const langLabel = currentLanguage === 'zh-CN' ? 'EN' : '中';

  // ── Mini window ──
  const handleOpenMini = useCallback(async () => {
    try {
      await fwOpen({
        id: 'main-mini-' + Date.now(),
        title: t('footer.miniMode'),
        width: 320,
        height: 480,
      });
    } catch (err) {
      log.error('Failed to open mini window', err);
    }
  }, [t]);

  // ── Device status indicator: green/yellow/red ──
  const [deviceIndicator, setDeviceIndicator] = useState<'ok' | 'warn' | 'error'>(() => {
    try {
      const status = getDeviceApprovalStatus();
      if (status === 'unknown') return 'error';
      if (status === 'active') return 'ok';
      return 'warn';
    } catch {
      return 'error';
    }
  });

  useEffect(() => {
    const refresh = () => {
      try {
        const status = getDeviceApprovalStatus();
        if (status === 'unknown') { setDeviceIndicator('error'); return; }
        if (status === 'active') { setDeviceIndicator('ok'); return; }
        setDeviceIndicator('warn');
      } catch { setDeviceIndicator('error'); }
    };
    refresh();
    window.addEventListener('storage', refresh);
    window.addEventListener('tupai:device-registered', refresh);
    window.addEventListener('tupai:device-token-changed', refresh);
    return () => {
      window.removeEventListener('storage', refresh);
      window.removeEventListener('tupai:device-registered', refresh);
      window.removeEventListener('tupai:device-token-changed', refresh);
    };
  }, []);

  const handleOpenSettings = useCallback(() => { openSettingsOverlay(); }, [openSettingsOverlay]);
  const handleOpenDevice = useCallback(() => { openSettingsOverlay('tupai'); }, [openSettingsOverlay]);

  const indicatorTooltip =
    deviceIndicator === 'ok'
      ? t('settingsOverlay.indicatorOk')
      : deviceIndicator === 'warn'
        ? t('settingsOverlay.indicatorWarn')
        : t('settingsOverlay.indicatorError');

  // ── Floating chat button logic ──
  const refreshFloaterSnapshot = useCallback(async () => {
    if (!isTauriRuntime()) return;
    try {
      const entries = await fwGetState();
      const entry = entries.find((e) => e.id === FLOATER_ID);
      const next = entry
        ? { exists: true, docked: Boolean(entry.docked) || Boolean(entry.minimized) }
        : { exists: false, docked: false };
      setFloaterSnapshot((prev) =>
        prev.exists === next.exists && prev.docked === next.docked ? prev : next
      );
    } catch (err) {
      log.warn('fw_get_state failed', err);
    }
  }, []);

  useEffect(() => {
    if (!isTauriRuntime()) return;
    void refreshFloaterSnapshot();
    let disposed = false;
    let unlisten: (() => void) | null = null;
    void import('@tauri-apps/api/event')
      .then(({ listen }) =>
        listen('floating_window:state-changed', () => {
          if (disposed) return;
          void refreshFloaterSnapshot();
        })
      )
      .then((removeListener) => {
        if (disposed) { removeListener(); return; }
        unlisten = removeListener;
      })
      .catch(() => {});
    return () => { disposed = true; unlisten?.(); };
  }, [refreshFloaterSnapshot]);

  const handleFloaterClick = useCallback(async () => {
    if (!isTauriRuntime()) return;
    let latest = floaterSnapshot;
    try {
      const entries = await fwGetState();
      const entry = entries.find((e) => e.id === FLOATER_ID);
      latest = entry
        ? { exists: true, docked: Boolean(entry.docked) || Boolean(entry.minimized) }
        : { exists: false, docked: false };
    } catch (err) { log.warn('fw_get_state failed during toggle', err); }

    try {
      if (!latest.exists) {
        await fwOpen({
          id: FLOATER_ID,
          title: t('toolCards.toolbar.startNewChat'),
          width: 240,
          height: 400,
        });
        await fwHideMainWindow();
      } else if (latest.docked) {
        await fwRestore(FLOATER_ID);
        await fwHideMainWindow();
      } else {
        await fwMinimize(FLOATER_ID);
      }
    } catch (err) { log.error('Chat floater toggle failed', err); }
  }, [floaterSnapshot, t]);

  const isFloaterOpen = floaterSnapshot.exists && !floaterSnapshot.docked;

  const sceneBarClassName = `bitfun-scene-bar ${!hasWindowControls ? 'bitfun-scene-bar--no-controls' : ''} ${className}`.trim();
  const canDragWindow = supportsNativeWindowDragging();
  const lastMouseDownTimeRef = useRef<number>(0);

  const handleBarMouseDown = useCallback((e: React.MouseEvent) => {
    if (!canDragWindow) return;

    const now = Date.now();
    const timeSinceLastMouseDown = now - lastMouseDownTimeRef.current;
    lastMouseDownTimeRef.current = now;

    if (e.button !== 0) return;
    const target = e.target as HTMLElement | null;
    if (!target) return;
    if (target.closest(INTERACTIVE_SELECTOR)) return;
    if (timeSinceLastMouseDown < 500 && timeSinceLastMouseDown > 50) return;

    void (async () => {
      try {
        const { getCurrentWindow } = await import('@tauri-apps/api/window');
        await getCurrentWindow().startDragging();
      } catch (error) {
        log.debug('startDragging failed', error);
      }
    })();
  }, [canDragWindow]);

  const handleBarDoubleClick = useCallback((e: React.MouseEvent) => {
    const target = e.target as HTMLElement | null;
    if (!target) return;
    if (target.closest(INTERACTIVE_SELECTOR)) return;
    onMaximize?.();
  }, [onMaximize]);

  return (
    <div
      className={sceneBarClassName}
      onMouseDown={handleBarMouseDown}
      onDoubleClick={handleBarDoubleClick}
    >
      <div className="bitfun-scene-bar__controls">
        {/* 语言切换 EN / 中 */}
        <button
          type="button"
          className="bitfun-scene-bar__lang-btn"
          onClick={handleToggleLanguage}
          aria-label={t('navBar.language')}
          title={t('navBar.language')}
        >
          {langLabel}
        </button>
        {/* 设备状态指示灯：三色恒定渲染，但仅当前态着色。
            黄/绿/红 任一灯点击 → 拉起设置浮层并定位到设备 tab (tupai)。 */}
        <div
          className="bitfun-scene-bar__indicator-group"
          role="group"
          aria-label={t('settingsOverlay.indicatorGroupLabel')}
        >
          <button
            type="button"
            className={`bitfun-scene-bar__indicator bitfun-scene-bar__indicator--green ${deviceIndicator === 'ok' ? 'is-on' : ''}`}
            onClick={handleOpenDevice}
            aria-label={t('settingsOverlay.indicatorOk')}
            title={`${indicatorTooltip} · ${t('scenes.settings')}`}
            tabIndex={deviceIndicator === 'ok' ? 0 : -1}
          />
          <button
            type="button"
            className={`bitfun-scene-bar__indicator bitfun-scene-bar__indicator--yellow ${deviceIndicator === 'warn' ? 'is-on' : ''}`}
            onClick={handleOpenDevice}
            aria-label={t('settingsOverlay.indicatorWarn')}
            title={`${indicatorTooltip} · ${t('scenes.settings')}`}
            tabIndex={deviceIndicator === 'warn' ? 0 : -1}
          />
          <button
            type="button"
            className={`bitfun-scene-bar__indicator bitfun-scene-bar__indicator--red ${deviceIndicator === 'error' ? 'is-on' : ''}`}
            onClick={handleOpenDevice}
            aria-label={t('settingsOverlay.indicatorError')}
            title={`${indicatorTooltip} · ${t('scenes.settings')}`}
            tabIndex={deviceIndicator === 'error' ? 0 : -1}
          />
        </div>
        {/* 深色/浅色主题切换 */}
        <button
          type="button"
          className="bitfun-scene-bar__icon-btn"
          onClick={handleToggleTheme}
          aria-label={isDark ? t('footer.lightMode') : t('footer.darkMode')}
          title={isDark ? t('footer.lightMode') : t('footer.darkMode')}
        >
          {isDark ? <Sun size={14} /> : <Moon size={14} />}
        </button>
        {/* 迷你窗口 */}
        <button
          type="button"
          className="bitfun-scene-bar__icon-btn"
          onClick={handleOpenMini}
          aria-label={t('footer.miniMode')}
          title={t('footer.miniMode')}
        >
          <PictureInPicture2 size={14} />
        </button>
        <button
          type="button"
          className="bitfun-scene-bar__icon-btn"
          onClick={handleOpenSettings}
          aria-label={t('scenes.settings')}
          title={t('scenes.settings')}
        >
          <SettingsIcon size={14} />
        </button>
        {/* Floating chat button — moved from bottom-right to top-right, left of WindowControls */}
        {isTauriRuntime() && (
          <button
            type="button"
            className={`bitfun-scene-bar__icon-btn bitfun-scene-bar__icon-btn--chat ${isFloaterOpen ? 'is-open' : ''}`}
            onClick={handleFloaterClick}
            aria-label={isFloaterOpen ? t('toolCards.toolbar.closeChat') : t('toolCards.toolbar.startNewChat')}
            title={isFloaterOpen ? t('toolCards.toolbar.closeChat') : t('toolCards.toolbar.startNewChat')}
          >
            {isFloaterOpen ? <X size={14} /> : <MessageSquare size={14} />}
          </button>
        )}
        {hasWindowControls && (
          <WindowControls
            onMinimize={onMinimize!}
            onMaximize={onMaximize!}
            onClose={onClose!}
            isMaximized={isMaximized}
          />
        )}
      </div>
    </div>
  );
};

export default SceneBar;
