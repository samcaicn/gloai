import ReactDOM from "react-dom/client";
import { lazy, Suspense } from "react";
import App from "./app/App";
import AgentCompanionDesktopPet from "./app/components/AgentCompanionDesktopPet/AgentCompanionDesktopPet";
import AppErrorBoundary from "./app/components/AppErrorBoundary";
import { STARTUP_OVERLAY_HIDDEN_EVENT } from "./app/startup/startupSignals";
import { hideStartupOverlay } from "./app/startup/startupOverlay";
import { WorkspaceProvider } from "./infrastructure/contexts/WorkspaceProvider";
import "./app/styles/index.scss";

// Font: Noto Sans SC is loaded via a <link> tag in index.html.
// File path: public/fonts/fonts.css, served as /fonts/fonts.css.

import { bootstrapLogger, createLogger, initLogger } from './shared/utils/logger';
import { elapsedMs, logElapsed, measureAsyncAndLog, nowMs } from './shared/utils/timing';
import { startupTrace } from './shared/utils/startupTrace';
import { scheduleAfterStartupSignal } from './shared/utils/startupTaskScheduling';
import {
  buildReactCrashLogPayload,
  isMinifiedReactErrorMessage,
} from './shared/utils/reactProductionError';

// tupai 主题早期应用：从 localStorage 恢复用户选择，默认 cyberpunk-cyan。
// 必须在 React 渲染前执行，避免主题闪烁。
(function applyTupaiScheme() {
  const TUPAI_SCHEME_KEY = 'tupai-scheme';
  const DEFAULT_SCHEME = 'cyberpunk-cyan';
  const VALID_SCHEMES = [
    'cyberpunk-cyan', 'cyberpunk-magenta', 'cyberpunk-green',
    'cyberpunk-yellow', 'cyberpunk-red', 'bitfun-dark', 'bitfun-light',
  ];
  try {
    const stored = localStorage.getItem(TUPAI_SCHEME_KEY);
    const scheme = stored && VALID_SCHEMES.includes(stored) ? stored : DEFAULT_SCHEME;
    document.documentElement.setAttribute('data-scheme', scheme);
  } catch {
    document.documentElement.setAttribute('data-scheme', DEFAULT_SCHEME);
  }
})();

// Install console forwarding before app startup so early console output is persisted too.
bootstrapLogger();

const log = createLogger('App');
startupTrace.markPhase('first_script_eval', {
  viteMode: import.meta.env.MODE,
  isDev: import.meta.env.DEV,
});

async function traceStartupStep<T>(
  phase: string,
  step: string,
  run: () => Promise<T>
): Promise<T> {
  const startedAt = nowMs();
  startupTrace.markPhase(`${phase}_start`, { step });
  try {
    const value = await run();
    startupTrace.markPhase(`${phase}_end`, {
      step,
      durationMs: elapsedMs(startedAt),
    });
    return value;
  } catch (error) {
    startupTrace.markPhase(`${phase}_failed`, {
      step,
      durationMs: elapsedMs(startedAt),
    });
    throw error;
  }
}

/** Dedupe only for white-screen heuristic (empty #root), not for Error Boundary logs. */
const WHITE_SCREEN_LOGGED_FLAG = '__bitfun_white_screen_crash_logged__';
function hasLoggedWhiteScreenCrash(): boolean {
  return Boolean((window as any)[WHITE_SCREEN_LOGGED_FLAG]);
}
function markWhiteScreenCrashLogged(): void {
  (window as any)[WHITE_SCREEN_LOGGED_FLAG] = true;
}

function serializeError(err: unknown): Record<string, unknown> {
  if (err instanceof Error) {
    return {
      name: err.name,
      message: err.message,
      stack: err.stack,
    };
  }
  return { value: String(err) };
}

function isRootEmpty(): boolean {
  const root = document.getElementById('root');
  if (!root) {
    return true;
  }
  return root.childElementCount === 0;
}

function registerGlobalErrorHandlers() {
  const flag = '__bitfun_global_error_handlers_registered__';
  const w = window as any;
  if (w[flag]) {
    return;
  }
  w[flag] = true;

  const scheduleCrashLog = (payload: { location: string; message: string; data?: Record<string, unknown> }) => {
    // Always persist uncaught errors so they appear in webview.log for diagnostics.
    // Mark white-screen crashes separately to allow callers to deduplicate.
    queueMicrotask(() => {
      requestAnimationFrame(() => {
        requestAnimationFrame(() => {
          const isWhiteScreen = isRootEmpty();
          const crashType = isWhiteScreen ? 'white-screen' : 'page-error';
          // Deduplicate only white-screen crashes to avoid duplicate startup logs.
          if (isWhiteScreen && hasLoggedWhiteScreenCrash()) {
            return;
          }
          if (isWhiteScreen) {
            markWhiteScreenCrashLogged();
          }
          log.error(`[CRASH:${crashType}] Uncaught error`, {
            location: payload.location,
            message: payload.message,
            ...payload.data,
          });
        });
      });
    });
  };

  window.addEventListener(
    'error',
    (event: Event) => {
      if (event instanceof ErrorEvent) {
        const msg = event.message || '';
        // Minified React errors often reach window.error even when #root is not empty;
        // always persist so production builds get react.dev/errors/{code} in webview.log.
        if (isMinifiedReactErrorMessage(msg)) {
          const err =
            event.error instanceof Error ? event.error : new Error(msg);
          log.error('[CRASH] window:error (minified React)', {
            location: 'window:error',
            ...buildReactCrashLogPayload(err),
            filename: event.filename,
            lineno: event.lineno,
            colno: event.colno,
          });
        }
        scheduleCrashLog({
          location: 'window:error',
          message: msg || 'window error',
          data: {
            filename: event.filename,
            lineno: event.lineno,
            colno: event.colno,
            error: serializeError(event.error),
          },
        });
        return;
      }

    // Resource load errors rarely cause a white screen; log only if root is empty.
      const target = event.target as any;
      scheduleCrashLog({
        location: 'window:resource-error',
        message: 'resource load error',
        data: {
          tagName: target?.tagName,
          src: target?.src,
          href: target?.href,
        },
      });
    },
    true
  );

  window.addEventListener('unhandledrejection', (event: PromiseRejectionEvent) => {
    const reason = event.reason;
    const msg =
      reason instanceof Error
        ? reason.message
        : typeof reason === 'string'
          ? reason
          : '';
    if (isMinifiedReactErrorMessage(msg)) {
      const err = reason instanceof Error ? reason : new Error(msg);
      log.error('[CRASH] unhandledrejection (minified React)', {
        location: 'window:unhandledrejection',
        ...buildReactCrashLogPayload(err),
      });
    }
    scheduleCrashLog({
      location: 'window:unhandledrejection',
      message: 'unhandled rejection',
      data: {
        reason: serializeError(event.reason),
      },
    });
  });
}

registerGlobalErrorHandlers();

// Disable Tab-key focus traversal globally.
// Tab still works inside Monaco Editor and xterm terminal where it has semantic meaning.
document.addEventListener(
  'keydown',
  (e: KeyboardEvent) => {
    if (e.key !== 'Tab') return;
    const target = e.target as Element | null;
    if (target?.closest('.monaco-editor, .xterm')) return;
    e.preventDefault();
  },
  true
);

/**
 * Race a promise against a timeout. If the timeout wins, reject with a
 * TimeoutError so the caller can catch and proceed with a fallback.
 * This prevents the entire startup from hanging indefinitely when a
 * Tauri IPC call (e.g. configAPI.getConfig for theme) doesn't respond
 * on some Windows machines where the backend is slow to initialise.
 */
function withTimeout<T>(promise: Promise<T>, ms: number, label: string): Promise<T> {
  return Promise.race([
    promise,
    new Promise<T>((_, reject) =>
      window.setTimeout(() => reject(new Error(`Timeout: ${label} (${ms}ms)`)), ms)
    ),
  ]);
}

/** Logger, theme, and minimal deps — must finish before first React paint (F5 / webview reload does not re-run Tauri init script). */
async function initializeBeforeRender(): Promise<void> {
  const phaseStartedAt = nowMs();
  startupTrace.markPhase('before_render_start');
  await traceStartupStep('before_render_step', 'init_logger', async () => {
    await measureAsyncAndLog(log, 'Startup step completed', () => initLogger(), {
      data: { step: 'initLogger' },
    });
  });

  log.info('Initializing BitFun');

  // Wrap theme initialization in a timeout so a hanging Tauri IPC call
  // (configAPI.getConfig) doesn't block the entire startup. If the theme
  // service can't initialise within 10s, we proceed without it — the app
  // will still render with the default inline CSS from index.html.
  await traceStartupStep('before_render_step', 'theme_service_initialize', async () => {
    await measureAsyncAndLog(log, 'Startup step completed', async () => {
      const { themeService } = await import('./infrastructure/theme');
      await withTimeout(
        themeService.initialize(),
        10_000,
        'themeService.initialize',
      );
    }, {
      data: { step: 'themeService.initialize' },
    });
  }).catch(error => {
    log.error('Theme initialization timed out or failed, proceeding with default theme', error);
    startupTrace.markPhase('before_render_step_theme_timeout', { error: String(error) });
  });
  log.info('Theme system initialized');
  logElapsed(log, 'Startup phase completed', phaseStartedAt, {
    data: { phase: 'initializeBeforeRender' },
  });
  startupTrace.markPhase('before_render_end', {
    durationMs: elapsedMs(phaseStartedAt),
  });
}

/** Rest of startup runs after the shell is interactive so first-screen latency stays reasonable. */
async function initializeAfterRender(): Promise<void> {
  const phaseStartedAt = nowMs();
  startupTrace.markPhase('after_render_start');
  const { fontPreferenceService } = await import('./infrastructure/font-preference');
  await fontPreferenceService.initialize();
  log.info('Font preference initialized at startup');

  const initResults = await Promise.allSettled([
    (async () => {
      const { backgroundTaskScheduler } = await import('./shared/utils/backgroundTaskScheduler');
      backgroundTaskScheduler.schedule(async () => {
        const { configManager } = await import('./infrastructure/config/services/ConfigManager');
        await configManager.getConfig('editor');
        log.info('Editor configuration preloaded');
      }, {
        idle: true,
        inFlightKey: 'startup:editor-config-preload',
        priority: 'low',
      });
    })(),
    (async () => {
      const {
        initializeFrontendLogLevelSync,
        installFrontendLogLevelConfigWatcher,
      } = await import('./infrastructure/config/services/FrontendLogLevelSync');
      await initializeFrontendLogLevelSync();
      await installFrontendLogLevelConfigWatcher();
    })(),
    (async () => {
      const { themeService } = await import('./infrastructure/theme');
      await themeService.ensureUserThemesLoaded();
    })(),
    (async () => {
      const { registerDefaultContextTypes } = await import('./shared/context-system/core/registerDefaultTypes');
      registerDefaultContextTypes();
    })(),
    (async () => {
      const { initRecommendationProviders } = await import('./flow_chat/components/smart-recommendations');
      initRecommendationProviders();
    })(),
    (async () => {
      const { initializeAllTools } = await import('./tools/initializeTools');
      await initializeAllTools();
    })(),
    (async () => {
      const { initContextMenuSystem } = await import('./shared/context-menu-system');
      initContextMenuSystem({
        registerBuiltinCommands: true,
        registerBuiltinProviders: true,
        debug: false,
      });

      const { registerNotificationContextMenu } = await import('./shared/notification-system');
      registerNotificationContextMenu();
    })(),
  ]);

  initResults.forEach((result, index) => {
    const names = [
      'EditorConfigPreload',
      'LogLevelConfigWatcher',
      'UserThemes',
      'DefaultContextTypes',
      'RecommendationProviders',
      'Tools',
      'ContextMenu',
    ];
    if (result.status === 'rejected') {
      log.warn('Initialization failed', { module: names[index], error: result.reason });
    }
  });

  log.info('BitFun core systems initialized successfully');
  logElapsed(log, 'Startup phase completed', phaseStartedAt, {
    data: { phase: 'initializeAfterRender' },
  });
  startupTrace.markPhase('after_render_end', {
    durationMs: elapsedMs(phaseStartedAt),
  });
}

async function startApplication(): Promise<void> {
  const appStartedAt = nowMs();
  startupTrace.markPhase('start_application_start');

  // Hash-based routing for floating windows / standalone scenes.
  // Tauri webview URLs use the form `index.html#/floating-window?id=xxx`.
  // These branches render a minimal UI and return early, skipping the full
  // BitFun App startup (initializeBeforeRender / WorkspaceProvider / etc.)
  // so floating windows start fast and don't load the heavy App bundle path.
  const hash = window.location.hash || '';
  const isFloatingWindow = hash.startsWith('#/floating-window');
  const isScreenMarker = hash.startsWith('#/screen-marker');
  const isStandaloneSettings =
    hash.startsWith('#/settings') && !hash.startsWith('#/settings/');

  if (isFloatingWindow) {
    let entryId: string | undefined;
    const hashQueryIndex = hash.indexOf('?');
    if (hashQueryIndex >= 0) {
      const params = new URLSearchParams(hash.slice(hashQueryIndex + 1));
      entryId = params.get('id') ?? undefined;
    }
    const [{ I18nProvider }, FloatingWindowModule] = await Promise.all([
      import('./infrastructure/i18n'),
      import('./app/components/FloatingWindow/FloatingWindow'),
    ]);
    const FloatingWindow = FloatingWindowModule.default;
    ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
      <AppErrorBoundary>
        <I18nProvider>
          <FloatingWindow id={entryId} />
        </I18nProvider>
      </AppErrorBoundary>
    );
    startupTrace.markPhase('floating_window_render_scheduled', {
      entryId,
      sinceStartupMs: elapsedMs(appStartedAt),
    });
    startupTrace.flushSummary('floating_window_render_scheduled');
    logElapsed(log, 'Startup step completed', appStartedAt, {
      data: { step: 'scheduleFloatingWindowRender', entryId },
    });
    // Floating window webview loads the same index.html (with splash overlay).
    // The main window branch calls hideStartupOverlay() from App.tsx, but this
    // branch returns early — we must hide the overlay here, otherwise the
    // floating window stays stuck on "正在启动..." forever.
    void hideStartupOverlay().then(() => {
      window.dispatchEvent(new CustomEvent(STARTUP_OVERLAY_HIDDEN_EVENT));
    });
    return;
  }

  if (isScreenMarker) {
    const { I18nProvider } = await import('./infrastructure/i18n');
    ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
      <AppErrorBoundary>
        <I18nProvider>
          <div
            style={{
              width: '100vw',
              height: '100vh',
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              justifyContent: 'center',
              gap: '16px',
              background: '#0a0a12',
              color: '#00ffe0',
              fontFamily: "'Orbitron', 'Rajdhani', sans-serif",
              userSelect: 'none',
            }}
          >
            <div style={{ fontSize: '20px', letterSpacing: '2px' }}>
              Screen Marker
            </div>
            <button
              type="button"
              onClick={() => window.close()}
              style={{
                padding: '6px 14px',
                background: 'transparent',
                color: '#00ffe0',
                border: '1px solid rgba(0, 255, 224, 0.55)',
                borderRadius: '4px',
                cursor: 'pointer',
                fontFamily: "'Rajdhani', sans-serif",
                fontSize: '14px',
                letterSpacing: '1px',
              }}
            >
              关闭
            </button>
          </div>
        </I18nProvider>
      </AppErrorBoundary>
    );
    startupTrace.markPhase('screen_marker_render_scheduled', {
      sinceStartupMs: elapsedMs(appStartedAt),
    });
    startupTrace.flushSummary('screen_marker_render_scheduled');
    logElapsed(log, 'Startup step completed', appStartedAt, {
      data: { step: 'scheduleScreenMarkerRender' },
    });
    // Hide splash overlay for standalone webview windows (same reason as
    // floating window branch above).
    void hideStartupOverlay().then(() => {
      window.dispatchEvent(new CustomEvent(STARTUP_OVERLAY_HIDDEN_EVENT));
    });
    return;
  }

  if (isStandaloneSettings) {
    const { I18nProvider } = await import('./infrastructure/i18n');
    const SettingsScene = lazy(() => import('./app/scenes/settings/SettingsScene'));
    ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
      <AppErrorBoundary>
        <I18nProvider>
          <Suspense
            fallback={
              <div
                style={{
                  padding: '24px',
                  color: '#00ffe0',
                  fontFamily: "'Rajdhani', sans-serif",
                  background: '#0a0a12',
                  minHeight: '100vh',
                }}
              >
                Loading settings…
              </div>
            }
          >
            <SettingsScene />
          </Suspense>
        </I18nProvider>
      </AppErrorBoundary>
    );
    startupTrace.markPhase('standalone_settings_render_scheduled', {
      sinceStartupMs: elapsedMs(appStartedAt),
    });
    startupTrace.flushSummary('standalone_settings_render_scheduled');
    logElapsed(log, 'Startup step completed', appStartedAt, {
      data: { step: 'scheduleStandaloneSettingsRender' },
    });
    // Hide splash overlay for standalone webview windows (same reason as
    // floating window branch above).
    void hideStartupOverlay().then(() => {
      window.dispatchEvent(new CustomEvent(STARTUP_OVERLAY_HIDDEN_EVENT));
    });
    return;
  }

  try {
    await initializeBeforeRender();
  } catch (error) {
    log.error('Failed to initialize (pre-render)', error);
  }

  // I18n Provider — wrapped in try-catch so a failed dynamic import
  // (e.g. a corrupted chunk on disk, or a network error in web mode)
  // doesn't prevent React from mounting. If the import fails we fall
  // back to a no-op wrapper that renders children directly.
  let I18nProvider: (props: { children: React.ReactNode }) => JSX.Element;
  try {
    const i18nProviderImportResult = await traceStartupStep(
      'startup_step',
      'load_i18n_provider',
      () => measureAsyncAndLog(
        log,
        'Startup step completed',
        () => import('./infrastructure/i18n'),
        { data: { step: 'loadI18nProvider' } }
      )
    );
    I18nProvider = i18nProviderImportResult.value.I18nProvider as (props: { children: React.ReactNode }) => JSX.Element;
  } catch (error) {
    log.error('Failed to load I18n provider, using fallback wrapper', error);
    startupTrace.markPhase('i18n_provider_load_failed', { error: String(error) });
    // Minimal fallback: render children without i18n context.
    I18nProvider = ({ children }: { children: React.ReactNode }) => <>{children}</>;
  }
  const isAgentCompanionWindow = new URLSearchParams(window.location.search)
    .get('bitfunWindow') === 'agent-companion';

  const renderStartedAt = nowMs();
  if (isAgentCompanionWindow) {
    ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
      <AppErrorBoundary>
        <I18nProvider>
          <AgentCompanionDesktopPet />
        </I18nProvider>
      </AppErrorBoundary>
    );
    logElapsed(log, 'Startup step completed', renderStartedAt, {
      data: {
        step: 'scheduleAgentCompanionRender',
        sinceStartupMs: elapsedMs(appStartedAt),
      },
    });
    startupTrace.markPhase('agent_companion_render_scheduled', {
      sinceStartupMs: elapsedMs(appStartedAt),
    });
    startupTrace.flushSummary('agent_companion_render_scheduled');
    return;
  }

  ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
    <AppErrorBoundary>
      <I18nProvider>
        <WorkspaceProvider>
          <App />
        </WorkspaceProvider>
      </I18nProvider>
    </AppErrorBoundary>
  );
  logElapsed(log, 'Startup step completed', renderStartedAt, {
    data: {
      step: 'scheduleInitialRender',
      sinceStartupMs: elapsedMs(appStartedAt),
    },
  });
  startupTrace.markPhase('react_render_scheduled', {
    sinceStartupMs: elapsedMs(appStartedAt),
  });

  startupTrace.markPhase('non_critical_init_scheduled', {
    signalName: STARTUP_OVERLAY_HIDDEN_EVENT,
    fallbackTimeoutMs: 10000,
    frameCount: 1,
  });
  scheduleAfterStartupSignal(async () => {
    const nonCriticalStartedAt = nowMs();
    try {
      await initializeAfterRender();
      startupTrace.markPhase('non_critical_init_done', {
        durationMs: elapsedMs(nonCriticalStartedAt),
      });
      startupTrace.flushSummary('non_critical_init_completed');
    } catch (error) {
      log.error('Failed to complete post-render initialization', error);
      startupTrace.markPhase('non_critical_init_failed', {
        durationMs: elapsedMs(nonCriticalStartedAt),
      });
    }
  }, {
    signalName: STARTUP_OVERLAY_HIDDEN_EVENT,
    fallbackTimeoutMs: 10000,
    frameCount: 1,
    onError: error => {
      log.error('Failed to schedule post-render initialization', error);
    },
  });

  logElapsed(log, 'Startup phase completed', appStartedAt, {
    data: { phase: 'startApplication' },
  });
  startupTrace.markPhase('start_application_end', {
    durationMs: elapsedMs(appStartedAt),
  });
  startupTrace.flushSummary('start_application_completed');
}

// Wrap the top-level call in a try-catch so an unhandled exception in
// startApplication() doesn't leave the user with a white screen. If
// the app fails to start, we show a minimal error in #root and hide
// the splash overlay so the user can see the error and reload.
try {
  void startApplication();
} catch (error) {
  // This catch handles synchronous errors thrown before the first
  // `await` in startApplication. Async errors are caught by the
  // unhandledrejection listener registered above.
  const rootEl = document.getElementById('root');
  const overlay = document.getElementById('bitfun-startup-overlay');
  const message = error instanceof Error ? error.message : String(error);

  if (overlay) overlay.remove();
  if (rootEl) {
    rootEl.innerHTML =
      '<div style="width:100%;height:100%;display:flex;align-items:center;justify-content:center;background:#0a0a12;color:#e5e7eb;font-family:system-ui,sans-serif;padding:24px;box-sizing:border-box">' +
        '<div style="max-width:560px;text-align:center">' +
          '<h2 style="margin:0 0 12px;font-size:18px;font-weight:600">应用启动失败</h2>' +
          '<p style="margin:0 0 8px;opacity:0.8;font-size:14px;line-height:1.6">应用在初始化过程中遇到错误。</p>' +
          '<p style="margin:0 0 20px;font-size:12px;font-family:monospace;opacity:0.6;word-break:break-all">' + message + '</p>' +
          '<button onclick="window.location.reload()" style="padding:8px 20px;background:#2563eb;color:#fff;border:none;border-radius:8px;cursor:pointer;font-size:14px">重新加载</button>' +
        '</div>' +
      '</div>';
  }
}
