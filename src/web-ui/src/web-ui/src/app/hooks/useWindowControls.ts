import { useCallback, useRef, useState, useEffect } from 'react';
import { currentMonitor, getCurrentWindow } from '@tauri-apps/api/window';
import { getCurrentWebview } from '@tauri-apps/api/webview';
import { PhysicalPosition, PhysicalSize } from '@tauri-apps/api/dpi';
import { useWorkspaceContext } from '../../infrastructure/contexts/WorkspaceContext';
import { notificationService } from '@/shared/notification-system';
import { createLogger } from '@/shared/utils/logger';
import { sendDebugProbe } from '@/shared/utils/debugProbe';
import { nowMs } from '@/shared/utils/timing';
import { dismissibleLayerManager } from '@/infrastructure/services/DismissibleLayerManager';
import { useI18n } from '@/infrastructure/i18n';
import { isMacOSDesktopRuntime, supportsNativeWindowControls } from '@/infrastructure/runtime';
import { systemAPI } from '@/infrastructure/api/service-api/SystemAPI';
import {
  captureFocusedEditable,
  restoreWindowKeyboardFocus,
  type WindowKeyboardFocusTarget,
} from './windowKeyboardFocus';

const log = createLogger('useWindowControls');

const formatErrorMessage = (error: unknown) =>
  error instanceof Error ? error.message : String(error);

// 主窗口贴边态（半球 peek）的宽度（逻辑像素）。与后端 floating_window.rs
// 的 PEEK_WIDTH 对齐：前端在这个宽度内画一个 20×40 的小半圆。
const MAIN_DOCK_PEEK_WIDTH_LOGICAL = 20;

const createWindowKeyboardFocusTarget = (
  appWindow: ReturnType<typeof getCurrentWindow> | null
): WindowKeyboardFocusTarget => {
  if (!appWindow) return null;

  return {
    setFocus: () => appWindow.setFocus(),
    setWebviewFocus: () => getCurrentWebview().setFocus(),
  };
};

/**
 * Window controls hook.
 * Manages minimize, maximize, OS fullscreen, close, and related actions.
 *
 * Important: OS fullscreen is not maximize. Fullscreen asks the operating
 * system to put the entire Desktop window into fullscreen (`F11` on
 * Windows/Linux, `Control+Command+F` on macOS). Maximize keeps the app as a
 * normal window that fills the available work area. Keep their state and
 * handlers separate so callers do not accidentally wire panel/fullscreen
 * behavior to maximize/restore UI.
 */
export const useWindowControls = (options?: { isToolbarMode?: boolean }) => {
  const { t } = useI18n('errors');
  const isToolbarMode = options?.isToolbarMode ?? false;
  const canUseNativeWindowControls = supportsNativeWindowControls();
  const { hasWorkspace, closeWorkspace } = useWorkspaceContext();
  
  // Maximized state: ordinary OS window maximize/restore, not fullscreen.
  const [isMaximized, setIsMaximized] = useState(false);
  // OS fullscreen state: entire Desktop window fullscreen, not panel fullscreen.
  const [isFullscreen, setIsFullscreen] = useState(false);
  
  // Debounce guard to prevent rapid toggles
  const isMaximizeInProgress = useRef(false);
  const isFullscreenInProgress = useRef(false);
  
  // Skip state updates during manual operations
  const shouldSkipStateUpdate = useRef(false);

  // ── 主窗口贴边态（半球 peek）──────────────────────────────
  // minimize 按钮把主窗口缩到 MAIN_DOCK_PEEK_WIDTH_LOGICAL 宽、贴屏幕左/右
  // 边缘，前端渲染一个小半圆；点击半圆恢复原尺寸。与浮窗 dock 同思路，但主
  // 窗口是单 webview，React state 即为唯一真相，无需后端 FloatingWindowState。
  // AppLayout 在 docked 时仅 CSS 隐藏主内容（组件保持挂载），避免
  // FlowchartScene 等场景的未保存编辑态在 dock→restore 周期中丢失。
  const [mainDocked, setMainDocked] = useState(false);
  const [dockEdge, setDockEdge] = useState<'left' | 'right'>('right');
  const mainDockedRef = useRef(false);
  const preDockSizeRef = useRef<PhysicalSize | null>(null);
  const preDockPosRef = useRef<PhysicalPosition | null>(null);
  const isDockInProgress = useRef(false);

  const restoreMacOSOverlayTitlebar = useCallback(async (appWindow: any) => {
    if (!isMacOSDesktopRuntime() || isToolbarMode) return;
    try {
      if (typeof appWindow.setTitleBarStyle === 'function') {
        await appWindow.setTitleBarStyle('overlay');
      }
    } catch {
      // Ignore failures during window animation/state changes.
    }
  }, [isToolbarMode]);

  const updateWindowState = useCallback(async (appWindow: any, skipVisibilityCheck = false) => {
    if (shouldSkipStateUpdate.current) {
      return;
    }

    try {
      if (!skipVisibilityCheck) {
        const isVisible = await appWindow.isVisible();
        if (!isVisible) {
          return;
        }
      }

      const [maximized, fullscreen] = await Promise.all([
        appWindow.isMaximized(),
        appWindow.isFullscreen(),
      ]);
      setIsMaximized(maximized);
      setIsFullscreen(fullscreen);
    } catch (_error) {
      // Ignore errors to avoid noise when the window is minimized or transitioning.
    }
  }, []);

  // Listen for window state changes
  useEffect(() => {
    if (!canUseNativeWindowControls) return;

    let unlistenResized: (() => void) | undefined;
    
    // Debounce timer
    let resizeTimer: NodeJS.Timeout | null = null;

    // Update state when window regains focus.
    // Note: Tauri may not expose onFocus; use page visibility as a fallback.
    const handleVisibilityChange = async () => {
      // Skip visibility handling while a window state transition is in flight.
      if (shouldSkipStateUpdate.current) {
        return;
      }
      
      if (document.visibilityState === 'visible') {
        sendDebugProbe(
          'useWindowControls.ts:handleVisibilityChange',
          'Window became visible',
          {
            isToolbarMode,
          }
        );
        try {
          const appWindow = getCurrentWindow();
          // Delay update until window fully restores
          setTimeout(async () => {
            const startedAt = nowMs();
            try {
              await updateWindowState(appWindow);
              await restoreMacOSOverlayTitlebar(appWindow);
              sendDebugProbe(
                'useWindowControls.ts:handleVisibilityChange',
                'Window restore sync completed',
                {
                  isToolbarMode,
                },
                { startedAt }
              );
            } catch (error) {
              sendDebugProbe(
                'useWindowControls.ts:handleVisibilityChange',
                'Window restore sync failed',
                {
                  error: formatErrorMessage(error),
                  isToolbarMode,
                }
              );
            }
          }, 300);
        } catch (error) {
          sendDebugProbe(
            'useWindowControls.ts:handleVisibilityChange',
            'Window restore setup failed',
            {
              error: formatErrorMessage(error),
              isToolbarMode,
            }
          );
        }
      }
    };
    
    const setupListener = async () => {
      try {
        const appWindow = getCurrentWindow();

        // Get initial state (skip visibility check so we still sync
        // when the window is maximized before it becomes visible)
        await updateWindowState(appWindow, true);
        await restoreMacOSOverlayTitlebar(appWindow);
        
        // Listen for resize (with debounce and visibility checks)
        unlistenResized = await appWindow.onResized(async () => {
          // Skip resize handling while a window state transition is in flight.
          if (shouldSkipStateUpdate.current) {
            return;
          }
          
          // Clear previous timer
          if (resizeTimer) {
            clearTimeout(resizeTimer);
          }
          
          // Debounce: delay to avoid frequent calls (300ms covers maximize/restore/fullscreen)
          resizeTimer = setTimeout(async () => {
            await updateWindowState(appWindow);
            await restoreMacOSOverlayTitlebar(appWindow);
          }, 300); // 300ms debounce covers window change duration
        });
        
        // Add page visibility listener
        document.addEventListener('visibilitychange', handleVisibilityChange);
      } catch (error) {
        log.error('Failed to setup window state listener', error);
      }
    };
    
    setupListener();
    
    return () => {
      if (resizeTimer) {
        clearTimeout(resizeTimer);
      }
      if (unlistenResized) {
        unlistenResized();
      }
      // Remove page visibility listener
      document.removeEventListener('visibilitychange', handleVisibilityChange);
    };
  }, [canUseNativeWindowControls, isToolbarMode, restoreMacOSOverlayTitlebar, updateWindowState]);

  // Window control handlers
  const handleMinimize = useCallback(async () => {
    if (!canUseNativeWindowControls) return;

    // 工具栏模式有独立几何管理（且无 minimize 按钮），不走 dock，退化到 OS minimize。
    if (isToolbarMode) {
      try {
        await getCurrentWindow().minimize();
      } catch (error) {
        log.error('Failed to minimize window', error);
      }
      return;
    }

    // 已 dock 或正在 dock 中，忽略重复点击
    if (mainDockedRef.current || isDockInProgress.current) return;

    isDockInProgress.current = true;
    shouldSkipStateUpdate.current = true;
    try {
      const win = getCurrentWindow();
      const [size, pos, monitor] = await Promise.all([
        win.outerSize(),
        win.outerPosition(),
        currentMonitor(),
      ]);
      preDockSizeRef.current = size;
      preDockPosRef.current = pos;

      const scale = monitor?.scaleFactor ?? 1;
      // 多显示器：以当前显示器原点为基准推算贴边方向，避免负坐标误判。
      const monOriginX = monitor?.position.x ?? 0;
      const monPhysW = monitor?.size.width ?? Math.round(1280 * scale);
      const relX = pos.x - monOriginX;
      const left = relX;
      const right = monPhysW - (relX + size.width);
      const edge: 'left' | 'right' = left <= right ? 'left' : 'right';

      const peekPhysW = Math.max(1, Math.round(MAIN_DOCK_PEEK_WIDTH_LOGICAL * scale));
      const minPhysH = Math.round(120 * scale);
      const newPhysH = Math.max(size.height, minPhysH);
      const newPhysX = edge === 'left' ? monOriginX : (monOriginX + monPhysW - peekPhysW);
      const newPhysY = pos.y;

      // 先置顶：20px 半圆必须浮在其它窗口之上，用户随时点得回来。
      await win.setAlwaysOnTop(true);
      // setSize(PhysicalSize) 绕过 min_inner_size（与浮窗 dock 同理，已由
      // ToolbarModeProvider 验证），主窗口 minWidth=900 不会阻止缩到 20px。
      await win.setSize(new PhysicalSize(peekPhysW, newPhysH));
      await win.setPosition(new PhysicalPosition(newPhysX, newPhysY));

      setDockEdge(edge);
      mainDockedRef.current = true;
      setMainDocked(true);
    } catch (error) {
      log.error('Failed to dock main window to peek, falling back to OS minimize', error);
      try { await getCurrentWindow().minimize(); } catch { /* ignore */ }
    } finally {
      setTimeout(() => {
        isDockInProgress.current = false;
        shouldSkipStateUpdate.current = false;
      }, 250);
    }
  }, [canUseNativeWindowControls, isToolbarMode]);

  // 从贴边态恢复主窗口：还原 dock 前的尺寸/位置，取消置顶并聚焦。
  const handleRestoreFromDock = useCallback(async () => {
    if (!canUseNativeWindowControls) return;
    if (!mainDockedRef.current || isDockInProgress.current) return;

    isDockInProgress.current = true;
    shouldSkipStateUpdate.current = true;
    try {
      const win = getCurrentWindow();
      const size = preDockSizeRef.current;
      const pos = preDockPosRef.current;
      if (size && pos) {
        await win.setSize(new PhysicalSize(size.width, size.height));
        await win.setPosition(new PhysicalPosition(pos.x, pos.y));
      }
      await win.setAlwaysOnTop(false);
      await win.setFocus();

      mainDockedRef.current = false;
      setMainDocked(false);
      preDockSizeRef.current = null;
      preDockPosRef.current = null;
    } catch (error) {
      log.error('Failed to restore main window from peek', error);
    } finally {
      setTimeout(() => {
        isDockInProgress.current = false;
        shouldSkipStateUpdate.current = false;
      }, 250);
    }
  }, [canUseNativeWindowControls]);

  // ── 外部唤起主窗口时自动还原贴边态 ──────────────────────
  // 桌面宠物双击 / MainMiniWindow 还原 / 托盘等外部调用方通过
  // `fw_show_main_window` 唤起主窗口时，后端只做 unminimize+show+set_focus，
  // 不还原尺寸 —— 若主窗口正处在贴边 peek 态，会以 20px 半圆重新出现，用户
  // 还得再点一下半圆才能看到完整窗口，不够流畅。这里监听主窗口 focus 事件：
  // 处于 docked 态时一旦获得焦点就自动还原，让"唤起主窗口"对所有调用方都
  // 呈现完整窗口。isDockInProgress 守卫避免与 peek 点击的 handleRestoreFromDock
  // 重复触发（先到的同步置位，后到的直接返回）。
  useEffect(() => {
    if (!canUseNativeWindowControls) return;
    let unlistenFocus: (() => void) | undefined;
    const setup = async () => {
      try {
        unlistenFocus = await getCurrentWindow().onFocusChanged(({ payload: focused }) => {
          if (!focused) return;
          if (!mainDockedRef.current || isDockInProgress.current) return;
          void handleRestoreFromDock();
        });
      } catch (error) {
        log.error('Failed to setup focus listener for main-dock auto-restore', error);
      }
    };
    void setup();
    return () => { unlistenFocus?.(); };
  }, [canUseNativeWindowControls, handleRestoreFromDock]);

  const handleMaximize = useCallback(async () => {
    if (!canUseNativeWindowControls) return;

    // Debounce: ignore while in progress
    if (isMaximizeInProgress.current) {
      return;
    }
    
    // Save active element to restore focus after window change
    const focusSnapshot = captureFocusedEditable();
    let appWindow: ReturnType<typeof getCurrentWindow> | null = null;
    
    try {
      isMaximizeInProgress.current = true;
      // Skip auto updates to avoid duplicate state changes
      shouldSkipStateUpdate.current = true;
      
      appWindow = getCurrentWindow();
      
      // Optimization: skip isVisible check; query maximized directly.
      // If minimized, user restores via taskbar instead of double-clicking header.
      // Check current state to avoid duplicate toggles.
      let currentMaximized = false;
      try {
        currentMaximized = await appWindow.isMaximized();
      } catch (error) {
        log.warn('Failed to get maximized state, assuming not maximized', error);
        currentMaximized = false;
      }
      // Use requestAnimationFrame to avoid blocking UI updates
      const updateState = (newState: boolean) => {
        requestAnimationFrame(() => {
          setIsMaximized(newState);
        });
      };
      
      // Toggle maximize/restore
      if (currentMaximized) {
        await appWindow.unmaximize();
        updateState(false);
      } else {
        await appWindow.maximize();
        updateState(true);
      }
      
      // Delay DOM work to avoid blocking UI rendering
      requestAnimationFrame(() => {
        restoreWindowKeyboardFocus(
          createWindowKeyboardFocusTarget(appWindow),
          focusSnapshot,
          50
        );
      });
    } catch (error) {
      log.error('Failed to toggle maximize window', error);
      notificationService.error(t('window.maximizeFailed', { error: formatErrorMessage(error) }));
    } finally {
      // Reduce final delay: 200ms is sufficient for window updates
      setTimeout(() => {
        isMaximizeInProgress.current = false;
        shouldSkipStateUpdate.current = false;
        if (appWindow) {
          void updateWindowState(appWindow, true);
          void restoreMacOSOverlayTitlebar(appWindow);
        }
      }, 200);
    }
  }, [canUseNativeWindowControls, restoreMacOSOverlayTitlebar, t, updateWindowState]);

  const handleToggleFullscreen = useCallback(async () => {
    if (!canUseNativeWindowControls) return;

    if (isFullscreenInProgress.current) {
      return;
    }

    const focusSnapshot = captureFocusedEditable();
    let appWindow: ReturnType<typeof getCurrentWindow> | null = null;

    try {
      isFullscreenInProgress.current = true;
      shouldSkipStateUpdate.current = true;

      appWindow = getCurrentWindow();

      // OS fullscreen is intentionally separate from maximize/restore.
      // The desktop host owns the native maximize/fullscreen transition so the
      // web UI does not expose visible intermediate OS window states.
      const nextState = await systemAPI.toggleMainWindowFullscreen();

      requestAnimationFrame(() => {
        setIsFullscreen(nextState.isFullscreen);
        setIsMaximized(nextState.isMaximized);
        restoreWindowKeyboardFocus(
          createWindowKeyboardFocusTarget(appWindow),
          focusSnapshot,
          80
        );
      });

      return nextState.isFullscreen;
    } catch (error) {
      log.error('Failed to toggle fullscreen window', error);
      notificationService.error(t('window.fullscreenFailed', { error: formatErrorMessage(error) }));
      return undefined;
    } finally {
      setTimeout(() => {
        isFullscreenInProgress.current = false;
        shouldSkipStateUpdate.current = false;
        if (appWindow) {
          void updateWindowState(appWindow, true);
          void restoreMacOSOverlayTitlebar(appWindow);
        }
      }, 300);
    }
  }, [canUseNativeWindowControls, restoreMacOSOverlayTitlebar, t, updateWindowState]);

  const handleClose = useCallback(async () => {
    if (!canUseNativeWindowControls) return;

    try {
      const appWindow = getCurrentWindow();
      await appWindow.close();
    } catch (error) {
      log.error('Failed to close window', error);
      notificationService.error(t('window.closeFailed', { error: formatErrorMessage(error) }));
    }
  }, [canUseNativeWindowControls, t]);

  // Home button: reset to startup page
  const handleHomeClick = useCallback(async () => {
    try {
      // 1) Close current workspace (triggers state update)
      if (hasWorkspace) {
        await closeWorkspace();
      }
      
      // 2) Dismiss transient overlays so the reset lands on a clean surface.
      dismissibleLayerManager.dismissAll();
    } catch (error) {
      log.error('Failed to return to startup page', error);
    }
  }, [hasWorkspace, closeWorkspace]);

  return {
    handleMinimize,
    handleMaximize,
    handleToggleFullscreen,
    handleClose,
    handleHomeClick,
    handleRestoreFromDock,
    isMaximized,
    isFullscreen,
    mainDocked,
    dockEdge,
    canUseNativeWindowControls,
  };
};
