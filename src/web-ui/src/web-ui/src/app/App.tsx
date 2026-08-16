import { lazy, Suspense, useEffect, useCallback, useLayoutEffect, useState, useRef } from 'react';
import { useShortcut } from '@/infrastructure/hooks/useShortcut';
import { useHasDismissibleLayer } from '@/infrastructure/hooks/useDismissibleLayer';
import { dismissibleLayerManager } from '@/infrastructure/services/DismissibleLayerManager';
import { ChatProvider } from '../infrastructure/contexts/ChatProvider';
import { SSHRemoteProvider } from '../features/ssh-remote';
import { ToolbarModeProvider } from '../flow_chat/components/toolbar-mode';
import { ContextMenuRenderer } from '../shared/context-menu-system/components/ContextMenuRenderer';
import { NotificationContainer, NotificationCenter } from '../shared/notification-system';
import { AnnouncementProvider } from '../shared/announcement-system';
import { ConfirmDialogRenderer } from '../component-library';
import { createLogger } from '@/shared/utils/logger';
import { startupTrace } from '@/shared/utils/startupTrace';
import { isTauriRuntime } from '@/infrastructure/runtime';
import { useWorkspaceContext } from '../infrastructure/contexts/WorkspaceContext';
import { useGlobalSceneShortcuts } from './hooks/useGlobalSceneShortcuts';
import { useDebugInspector } from '@/infrastructure/debug/useDebugInspector';
import { scheduleDeferredStartupSystems } from './startup/deferredStartupSystems';
import { shouldScheduleDeferredStartupSystems } from './startup/deferredStartupGate';
import { STARTUP_OVERLAY_HIDDEN_EVENT } from './startup/startupSignals';
import {
  getStartupOverlayElapsedMs,
  hideStartupOverlay,
  isStartupOverlayPresent,
} from './startup/startupOverlay';
import { ensureDeviceToken, registerDevice, getDeviceApprovalStatus } from '@/infrastructure/api/tupai/device';
import { useSystemHealthEvents } from '@/infrastructure/system/useSystemHealthEvents';
import { SkillStarRating } from '@/app/components/SkillStarRating/SkillStarRating';


const log = createLogger('App');

function isBackgroundTaskCancelledError(error: unknown): boolean {
  return error instanceof Error && error.name === 'BackgroundTaskCancelledError';
}

interface AppLayoutStartupGateProps {
  onReady: () => void;
}

const LazyAppLayout = lazy(async () => {
  startupTrace.markPhase('app_layout_import_start');
  try {
    const module = await import('./layout/AppLayout');
    startupTrace.markPhase('app_layout_import_end');
    return {
      default: function AppLayoutStartupGate({ onReady }: AppLayoutStartupGateProps) {
        useLayoutEffect(() => {
          startupTrace.markPhase('app_layout_ready');
          onReady();
        }, [onReady]);

        return <module.default />;
      },
    };
  } catch (error) {
    startupTrace.markPhase('app_layout_import_failed');
    throw error;
  }
});

/**
 * BitFun main application component.
 *
 * Unified architecture:
 * - Use a single AppLayout component
 * - AppLayout switches content based on workspace presence
 * - Without a workspace: show startup content (branding + actions)
 * - With a workspace: show workspace panels
 * - Header is always present; elements toggle by state
 */
// Minimum time (ms) the splash is shown, so the animation is never a flash.
const MIN_SPLASH_MS = 900;

function App() {
  // Workspace loading state — drives splash exit timing
  const { loading: workspaceLoading } = useWorkspaceContext();

  const [startupOverlayVisible, setStartupOverlayVisible] = useState(isStartupOverlayPresent);
  const hasAppDismissibleLayer = useHasDismissibleLayer('app');
  const mainWindowShownRef = useRef(false);
  const userCloseRequestedRef = useRef(false);
  const interactiveShellReadyRef = useRef(false);
  const interactiveShellReadyFrameRef = useRef<number | null>(null);
  const workspaceLoadingRef = useRef(workspaceLoading);
  const appLayoutReadyRef = useRef(false);
  const [interactiveShellReady, setInteractiveShellReady] = useState(false);
  const [appLayoutReady, setAppLayoutReady] = useState(false);

  workspaceLoadingRef.current = workspaceLoading;

  const releaseInteractiveShellReadyIfReady = useCallback((reason: string) => {
    const latestWorkspaceLoading = workspaceLoadingRef.current;
    const latestAppLayoutReady = appLayoutReadyRef.current;
    startupTrace.markPhase('interactive_shell_ready_gate_check', {
      workspaceLoading: latestWorkspaceLoading,
      appLayoutReady: latestAppLayoutReady,
      alreadyReady: interactiveShellReadyRef.current,
      reason,
      afterPaint: true,
    });
    if (latestWorkspaceLoading || !latestAppLayoutReady || interactiveShellReadyRef.current) {
      return;
    }
    interactiveShellReadyRef.current = true;
    startupTrace.markPhase('interactive_shell_ready', { reason });
    window.dispatchEvent(new CustomEvent('bitfun:interactive-shell-ready', {
      detail: { reason },
    }));
    setInteractiveShellReady(true);
  }, []);

  const markInteractiveShellReadyIfReady = useCallback((reason: string) => {
    const latestWorkspaceLoading = workspaceLoadingRef.current;
    const latestAppLayoutReady = appLayoutReadyRef.current;
    startupTrace.markPhase('interactive_shell_ready_gate_check', {
      workspaceLoading: latestWorkspaceLoading,
      appLayoutReady: latestAppLayoutReady,
      alreadyReady: interactiveShellReadyRef.current,
      alreadyScheduled: interactiveShellReadyFrameRef.current !== null,
      reason,
    });
    if (
      latestWorkspaceLoading ||
      !latestAppLayoutReady ||
      interactiveShellReadyRef.current ||
      interactiveShellReadyFrameRef.current !== null
    ) {
      return;
    }

    startupTrace.markPhase('interactive_shell_ready_after_paint_scheduled', { reason });
    interactiveShellReadyFrameRef.current = window.requestAnimationFrame(() => {
      interactiveShellReadyFrameRef.current = null;
      releaseInteractiveShellReadyIfReady(`${reason}-after-paint`);
    });
  }, [releaseInteractiveShellReadyIfReady]);

  const handleAppLayoutReady = useCallback(() => {
    startupTrace.markPhase('app_layout_ready_state_update_requested');
    appLayoutReadyRef.current = true;
    setAppLayoutReady(true);
    markInteractiveShellReadyIfReady('app-layout-ready');
  }, [markInteractiveShellReadyIfReady]);

  useEffect(() => {
    return () => {
      if (interactiveShellReadyFrameRef.current !== null) {
        window.cancelAnimationFrame(interactiveShellReadyFrameRef.current);
        interactiveShellReadyFrameRef.current = null;
      }
    };
  }, []);

  // Once the workspace finishes loading, wait for the remaining min-display
  // time and then begin the exit animation.
  useEffect(() => {
    if (workspaceLoading || !appLayoutReady) return;
    const elapsed = getStartupOverlayElapsedMs();
    const remaining = Math.max(0, MIN_SPLASH_MS - elapsed);
    let cancelled = false;
    const timer = window.setTimeout(() => {
      void hideStartupOverlay().then(() => {
        if (!cancelled) {
          setStartupOverlayVisible(false);
          startupTrace.markPhase('startup_overlay_hidden');
          window.dispatchEvent(new CustomEvent(STARTUP_OVERLAY_HIDDEN_EVENT));
        }
      });
    }, remaining);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [workspaceLoading, appLayoutReady]);

  useEffect(() => {
    if (!isTauriRuntime()) {
      return;
    }

    let unlisten: (() => void) | null = null;
    let disposed = false;

    void import('@tauri-apps/api/event')
      .then(({ listen }) => listen('bitfun_main_window_close_requested', () => {
        userCloseRequestedRef.current = true;
        startupTrace.markPhase('main_window_user_close_requested', { reason: 'user-close-requested' });
      }))
      .then(removeListener => {
        if (disposed) {
          removeListener();
          return;
        }
        unlisten = removeListener;
      })
      .catch(error => {
        if (!disposed) {
          log.warn('Failed to listen for main window close request in startup visibility guard', error);
        }
      });

    return () => {
      disposed = true;
      unlisten?.();
    };
  }, []);

  const showMainWindow = useCallback(async (reason: string) => {
    if (mainWindowShownRef.current) {
      return;
    }
    mainWindowShownRef.current = true;

    // 非 Tauri 环境（pnpm dev / web preview）下无需显示桌面主窗口，直接跳过
    if (!isTauriRuntime()) {
      return;
    }

    try {
      const { invoke } = await import('@tauri-apps/api/core');
      await invoke('fw_show_main_window');
      log.debug('Main window shown', { reason });
      startupTrace.markPhase('main_window_shown', { reason });
      window.dispatchEvent(new CustomEvent('bitfun:main-window-shown', { detail: { reason } }));
    } catch (error: any) {
      log.error('Failed to show main window', error);

      try {
        const { getCurrentWindow } = await import('@tauri-apps/api/window');
        const mainWindow = getCurrentWindow();
        await mainWindow.show();
        await mainWindow.setFocus();
        log.debug('Main window shown via fallback', { reason });
        startupTrace.markPhase('main_window_shown_fallback', { reason });
        window.dispatchEvent(new CustomEvent('bitfun:main-window-shown', { detail: { reason } }));
      } catch (fallbackError) {
        log.error('Fallback window show failed', fallbackError);
        mainWindowShownRef.current = false;
      }
    }
  }, []);

  const verifyMainWindowVisible = useCallback(async (reason: string) => {
    if (userCloseRequestedRef.current) {
      log.debug('Skipping main window startup visibility retry after user close request', {
        reason,
        closeReason: 'user-close-requested',
      });
      return;
    }

    if (!isTauriRuntime()) {
      void showMainWindow(reason);
      return;
    }

    try {
      const { getCurrentWindow } = await import('@tauri-apps/api/window');
      const mainWindow = getCurrentWindow();
      if (await mainWindow.isVisible()) {
        return;
      }

      log.warn('Main window is not visible after native startup show, retrying', { reason });
      mainWindowShownRef.current = false;
      await showMainWindow(reason);
    } catch (error) {
      log.warn('Failed to verify main window visibility after native startup show', { reason, error });
    }
  }, [showMainWindow]);

  // Desktop shows the startup splash from the native window creation path.
  // Mark it here so deferred work can wait until the first visible shell exists.
  useEffect(() => {
    startupTrace.markPhase('app_effect_mounted');
    if (isTauriRuntime()) {
      mainWindowShownRef.current = true;
      startupTrace.markPhase('main_window_shown', { reason: 'startup-native' });
      window.dispatchEvent(new CustomEvent('bitfun:main-window-shown', {
        detail: { reason: 'startup-native' },
      }));
      return;
    }
    void showMainWindow('startup-overlay');
  }, [showMainWindow]);

  useEffect(() => {
    if (appLayoutReady) {
      appLayoutReadyRef.current = true;
    }
    markInteractiveShellReadyIfReady('workspace-or-layout-state');
  }, [workspaceLoading, appLayoutReady, markInteractiveShellReadyIfReady]);

  // If the early reveal path fails, keep the old post-splash show as a retry.
  useEffect(() => {
    if (startupOverlayVisible) {
      return;
    }

    const timer = window.setTimeout(() => {
      void verifyMainWindowVisible('startup-complete');
    }, 50);

    return () => window.clearTimeout(timer);
  }, [startupOverlayVisible, verifyMainWindowVisible]);

  // Safety net: if startup gets stuck, reveal the window so the user can see errors.
  useEffect(() => {
    const timer = window.setTimeout(() => {
      void verifyMainWindowVisible('startup-watchdog');
    }, 10000);

    return () => window.clearTimeout(timer);
  }, [verifyMainWindowVisible]);

  // Non-critical systems are delayed until the shell is interactive and the
  // startup overlay has fully handed off to the app surface.
  useEffect(() => {
    if (!shouldScheduleDeferredStartupSystems({ interactiveShellReady, startupOverlayVisible })) {
      return;
    }

    log.info('Application visible and interactive, scheduling deferred systems');
    const startupSystemsHandle = scheduleDeferredStartupSystems();
    startupSystemsHandle.promise.catch(error => {
      if (!isBackgroundTaskCancelledError(error)) {
        log.warn('Deferred startup systems task failed', error);
      }
    });

    return () => startupSystemsHandle.cancel();
  }, [interactiveShellReady, startupOverlayVisible]);

  // 启动时自动获取 token 并验证 MCP 请求成功（退出重启 / 重装 / 升级 后均适用）：
  // - localStorage 无 token（重装/升级后）: 自动 fingerprint + MCP client.renew 验证
  //   · 服务器识别已审批设备 → 自动通过 + MCP 验证成功 → 写 localStorage，无需再注册设备
  //   · 服务器不识别或 MCP 拒绝 → 标记 pending，让用户手动注册
  // - localStorage 有 token（退出重启后）: renewDeviceToken 走 MCP client.renew 验证
  //   · 服务器判 valid=false → 清空 localStorage，触发自动 fingerprint + MCP 验证重注册
  //   · 服务器判 valid=true + 返回新 token → 写新 token
  //   · 服务器判 valid=true + token 没变 → 不动 localStorage
  //   · 网络/5xx → 后端保守判 valid=true，前端不写不删，保留旧 token
  // 设计上永不抛错，不阻塞启动。
  useEffect(() => {
    if (!isTauriRuntime()) {
      return;
    }
    if (!interactiveShellReady || startupOverlayVisible) {
      return;
    }

    let disposed = false;
    void ensureDeviceToken()
      .then(async result => {
        if (disposed) return;
        const currentStatus = getDeviceApprovalStatus();
        if (!result.valid || currentStatus === 'pending_approval' || currentStatus === 'unknown') {
          log.warn('ensureDeviceToken: device not fully approved, attempting auto-bind with joincode 39201145', { valid: result.valid, status: currentStatus });
          // Auto-bind: call registerDevice with joincode to bind the device
          try {
            const regResult = await registerDevice('39201145');
            log.info('Auto-bind result:', { approvalStatus: regResult.approvalStatus, hasToken: !!regResult.token });
            if (regResult.token) {
              try { localStorage.setItem('trae_device_token', regResult.token); } catch {}
              window.dispatchEvent(new Event('tupai:device-token-changed'));
            }
          } catch (e) {
            log.warn('Auto-bind with joincode 39201145 failed:', e);
          }
        } else if (result.changed) {
          log.info('ensureDeviceToken: token verified via MCP, written to localStorage');
        } else {
          log.debug('ensureDeviceToken: token still valid (MCP verified)');
        }
        // token 有效 → 60s 后静默检查并下载升级 (下次启动时安装)
        if (result.valid && result.token) {
          const token = result.token;
          window.setTimeout(() => {
            if (disposed) return;
            import('@tauri-apps/api/core')
              .then(({ invoke }) => invoke('silent_download_upgrade', { deviceToken: token }))
              .catch(err => log.warn('silent download upgrade failed', err));
          }, 60_000);
        }
      })
      .catch(error => {
        if (!disposed) {
          log.warn('ensureDeviceToken IPC layer failed (kept existing token):', error);
        }
      });

    return () => { disposed = true; };
  }, [interactiveShellReady, startupOverlayVisible]);

  useEffect(() => {
    if (!interactiveShellReady || startupOverlayVisible) {
      return;
    }

    let disposed = false;
    let editorWarmupHandle: { promise: Promise<void>; cancel: () => void } | null = null;

    void import('@/tools/editor/services/MonacoStartupWarmup')
      .then(({ scheduleMonacoStartupWarmup }) => {
        if (disposed) {
          return;
        }
        editorWarmupHandle = scheduleMonacoStartupWarmup();
        editorWarmupHandle.promise.catch(error => {
          if (!disposed && !isBackgroundTaskCancelledError(error)) {
            log.warn('Editor startup warmup task failed', error);
          }
        });
      })
      .catch(error => {
        if (!disposed) {
          log.warn('Failed to schedule editor startup warmup', error);
        }
      });

    return () => {
      disposed = true;
      editorWarmupHandle?.cancel();
    };
  }, [interactiveShellReady, startupOverlayVisible]);

  // Block browser-native Ctrl+F (find bar) and Ctrl+R (hard reload).
  // On macOS the equivalent modifiers are Cmd+F / Cmd+R.
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      const primary = e.ctrlKey || e.metaKey;
      if (!primary) return;
      const key = e.key.toLowerCase();
      if (key === 'f' || key === 'r') {
        e.preventDefault();
        e.stopPropagation();
      }
    };
    window.addEventListener('keydown', handleKeyDown, { capture: true });
    return () => window.removeEventListener('keydown', handleKeyDown, { capture: true });
  }, []);

  // Escape closes preview overlay (registered via ShortcutManager)
  useShortcut(
    'app.closePreview',
    { key: 'Escape', scope: 'app', allowInInput: true },
    () => {
      dismissibleLayerManager.dismissTop('app');
    },
    {
      enabled: hasAppDismissibleLayer,
      priority: 1,
      description: 'keyboard.shortcuts.app.closePreview',
    }
  );

  // Top SceneBar: Mod+Alt+1..9 / Mod+Alt+PageUp/PageDown
  useGlobalSceneShortcuts();

  // Debug inspector shortcuts (desktop devtools only)
  useDebugInspector();

  // 系统健康事件订阅: 托盘初始化失败 / mesh 防火墙 / 启动降级 / 二次启动 等。
  // 瞬态事件转 toast, 不阻塞用户; 持久权限横幅由 <OsCompatibilityBanner> 处理。
  useSystemHealthEvents();

  // Unified layout via a single AppLayout
  return (
    <ChatProvider>
      <SSHRemoteProvider>
        <ToolbarModeProvider>
          {/* Unified app layout with startup/workspace modes */}
          <Suspense fallback={null}>
            <LazyAppLayout onReady={handleAppLayoutReady} />
          </Suspense>

          {/* Context menu renderer */}
          <ContextMenuRenderer />

          {/* Notification system */}
          <NotificationContainer />
          <NotificationCenter />

          {/* Confirm dialog */}
          <ConfirmDialogRenderer />

          {/* Announcement / feature-demo / tips system */}
          <AnnouncementProvider />

          {/* Skill execution star rating overlay */}
          <SkillStarRating />
        </ToolbarModeProvider>
      </SSHRemoteProvider>
    </ChatProvider>
  );
}

export default App;
