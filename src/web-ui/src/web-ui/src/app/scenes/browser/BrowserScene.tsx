import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { AlertTriangle, ChevronDown, ChevronLeft, ChevronRight, Circle, Globe, RefreshCw } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { IconButton } from '@/component-library';
import { createLogger } from '@/shared/utils/logger';
import { useSceneStore } from '@/app/stores/sceneStore';
import { automationExecute, recordingLoad, recordingStart, recordingStop } from '@/infrastructure/api/tupai';
import { isTauriRuntime } from '@/infrastructure/runtime';
import { BLANK_TARGET_INTERCEPT_SCRIPT } from './browserInspectorScript';
import { validateUrl, checkConnectivity } from './browserUrlCheck';
import './BrowserScene.scss';

const log = createLogger('BrowserScene');
const DEFAULT_URL = 'https://www.google.com/';
const BROWSER_HOLDER_WINDOW_LABEL = 'embedded-browser-holder-window';

function isTauriEnvironment(): boolean {
  return typeof window !== 'undefined' && '__TAURI__' in window;
}

type BrowserWebviewHandle = {
  close: () => Promise<void>;
  hide: () => Promise<void>;
  label: string;
  once: (event: string, handler: (event?: unknown) => void) => Promise<() => void>;
  reparent: (window: string | unknown) => Promise<void>;
  setFocus: () => Promise<void>;
  setPosition: (position: unknown) => Promise<void>;
  setSize: (size: unknown) => Promise<void>;
  show: () => Promise<void>;
};

type BrowserHolderWindowHandle = {
  close: () => Promise<void>;
  hide: () => Promise<void>;
  once: (event: string, handler: (event?: unknown) => void) => Promise<() => void>;
};

function formatUnknownError(error: unknown): string {
  if (error instanceof Error) {
    return error.message;
  }

  if (typeof error === 'string') {
    return error;
  }

  if (error && typeof error === 'object') {
    const record = error as Record<string, unknown>;
    const payload = 'payload' in record ? record.payload : undefined;
    const message =
      (typeof record.message === 'string' && record.message) ||
      (payload && typeof payload === 'object' && typeof (payload as Record<string, unknown>).message === 'string'
        ? String((payload as Record<string, unknown>).message)
        : null);

    if (message) {
      return message;
    }

    try {
      return JSON.stringify(error);
    } catch {
      return String(error);
    }
  }

  return String(error);
}

function isWebviewNotFoundError(error: unknown): boolean {
  const message = formatUnknownError(error);
  return message.toLowerCase().includes('webview not found');
}

async function evalWebview(label: string, script: string): Promise<void> {
  const { invoke } = await import('@tauri-apps/api/core');
  await invoke('browser_webview_eval', { request: { label, script } });
}

async function waitForWebviewCreated(handle: BrowserWebviewHandle): Promise<void> {
  await new Promise<void>((resolve, reject) => {
    let settled = false;

    const finish = (callback: () => void) => {
      if (settled) {
        return;
      }
      settled = true;
      callback();
    };

    void handle.once('tauri://created', () => {
      finish(resolve);
    });

    void handle.once('tauri://error', (event) => {
      finish(() => reject(new Error(formatUnknownError(event))));
    });
  });
}

async function waitForWindowCreated(handle: BrowserHolderWindowHandle): Promise<void> {
  await new Promise<void>((resolve, reject) => {
    let settled = false;

    const finish = (callback: () => void) => {
      if (settled) {
        return;
      }
      settled = true;
      callback();
    };

    void handle.once('tauri://created', () => {
      finish(resolve);
    });

    void handle.once('tauri://error', (event) => {
      finish(() => reject(new Error(formatUnknownError(event))));
    });
  });
}

function normalizeUrl(raw: string): string {
  const value = raw.trim();
  if (!value) {
    return DEFAULT_URL;
  }

  if (/^[a-zA-Z][a-zA-Z\d+\-.]*:/.test(value)) {
    return value;
  }

  return `https://${value}`;
}

const BrowserScene: React.FC = () => {
  const { t } = useTranslation('common');
  const activeTabId = useSceneStore((state) => state.activeTabId);
  const isActive = activeTabId === 'browser';
  const isTauri = useMemo(() => isTauriEnvironment(), []);

  const viewportRef = useRef<HTMLDivElement>(null);
  const webviewRef = useRef<BrowserWebviewHandle | null>(null);
  const holderWindowRef = useRef<BrowserHolderWindowHandle | null>(null);
  const webviewSequenceRef = useRef(0);
  const currentUrlRef = useRef<string>(DEFAULT_URL);
  const resizeFrameRef = useRef<number | null>(null);
  const webviewLabelRef = useRef<string>('');
  const urlPollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const [inputValue, setInputValue] = useState(DEFAULT_URL);
  const [currentUrl, setCurrentUrl] = useState(DEFAULT_URL);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 录制与自动化流程图状态
  const [isRecording, setIsRecording] = useState<boolean>(false);
  const [showFlowchartPanel, setShowFlowchartPanel] = useState<boolean>(false);
  const [flowchartJson, setFlowchartJson] = useState<string>('');
  const [flowchartResult, setFlowchartResult] = useState<string>('');
  const [flowchartError, setFlowchartError] = useState<string | null>(null);
  const [flowchartBusy, setFlowchartBusy] = useState<boolean>(false);

  const syncWebviewBounds = useCallback(async (handle?: BrowserWebviewHandle | null) => {
    const target = handle ?? webviewRef.current;
    if (!isTauri || !isActive || !viewportRef.current || !target) {
      return;
    }

    const rect = viewportRef.current.getBoundingClientRect();
    if (rect.width <= 1 || rect.height <= 1) {
      return;
    }

    const [{ LogicalPosition, LogicalSize }] = await Promise.all([
      import('@tauri-apps/api/dpi'),
    ]);

    await Promise.all([
      target.setPosition(new LogicalPosition(rect.left, rect.top)),
      target.setSize(new LogicalSize(rect.width, rect.height)),
    ]);
  }, [isActive, isTauri]);

  const closeWebview = useCallback(async (handle?: BrowserWebviewHandle | null) => {
    const target = handle ?? webviewRef.current;
    if (!target) {
      return;
    }

    try {
      await target.close();
    } catch (closeError) {
      if (!isWebviewNotFoundError(closeError)) {
        log.warn('Close browser webview failed', closeError);
      }
    } finally {
      if (!handle || target === webviewRef.current) {
        webviewRef.current = null;
      }
    }
  }, []);

  const ensureHolderWindow = useCallback(async (): Promise<BrowserHolderWindowHandle> => {
    if (holderWindowRef.current) {
      return holderWindowRef.current;
    }

    const { Window } = await import('@tauri-apps/api/window');
    const existing = (await Window.getByLabel(BROWSER_HOLDER_WINDOW_LABEL)) as BrowserHolderWindowHandle | null;
    if (existing) {
      holderWindowRef.current = existing;
      return existing;
    }

    const holder = new Window(BROWSER_HOLDER_WINDOW_LABEL, {
      visible: false,
      decorations: false,
      skipTaskbar: true,
      shadow: false,
      width: 1,
      height: 1,
      x: -10000,
      y: -10000,
      title: 'Browser Holder',
    }) as BrowserHolderWindowHandle;

    await waitForWindowCreated(holder);
    await holder.hide().catch(() => {});
    holderWindowRef.current = holder;
    return holder;
  }, []);

  const recreateWebview = useCallback(async (url: string) => {
    const previous = webviewRef.current;
    if (previous) {
      await closeWebview(previous);
    }

    const [{ Webview }, { getCurrentWindow }] = await Promise.all([
      import('@tauri-apps/api/webview'),
      import('@tauri-apps/api/window'),
    ]);
    const nextLabel = `embedded-browser-view-${webviewSequenceRef.current++}`;
    webviewLabelRef.current = nextLabel;
    const handle = new Webview(getCurrentWindow(), nextLabel, {
      url,
      x: 0,
      y: 0,
      width: 960,
      height: 640,
    }) as unknown as BrowserWebviewHandle;

    await waitForWebviewCreated(handle);
    webviewRef.current = handle;
    return handle;
  }, [closeWebview]);

  const loadUrl = useCallback(async (rawUrl: string) => {
    const nextUrl = normalizeUrl(rawUrl);
    setInputValue(nextUrl);
    setCurrentUrl(nextUrl);
    currentUrlRef.current = nextUrl;
    setError(null);
    setIsLoading(true);

    if (!isTauri) {
      setIsLoading(false);
      return;
    }

    try {
      validateUrl(nextUrl);
      await checkConnectivity(nextUrl);

      if (urlPollTimerRef.current) {
        clearInterval(urlPollTimerRef.current);
        urlPollTimerRef.current = null;
      }

      const handle = await recreateWebview(nextUrl);
      await syncWebviewBounds(handle);
      if (isActive) {
        await handle.show();
        await handle.setFocus();
      }

      const label = webviewLabelRef.current;
      await evalWebview(label, BLANK_TARGET_INTERCEPT_SCRIPT);

      const { invoke } = await import('@tauri-apps/api/core');
      urlPollTimerRef.current = setInterval(() => {
        invoke<string>('browser_get_url', { request: { label } })
          .then((url) => {
            if (url && url !== currentUrlRef.current) {
              currentUrlRef.current = url;
              setInputValue(url);
              setCurrentUrl(url);
              setError(null);
              evalWebview(label, BLANK_TARGET_INTERCEPT_SCRIPT).catch(() => {});
            }
          })
          .catch(() => {});
      }, 500);
    } catch (loadError) {
      const message = formatUnknownError(loadError);
      log.error('Load browser url failed', loadError);
      setError(message);
    } finally {
      setIsLoading(false);
    }
  }, [isActive, isTauri, recreateWebview, syncWebviewBounds]);

  const queueSync = useCallback(() => {
    if (resizeFrameRef.current !== null) {
      window.cancelAnimationFrame(resizeFrameRef.current);
    }
    resizeFrameRef.current = window.requestAnimationFrame(() => {
      resizeFrameRef.current = null;
      void syncWebviewBounds().catch((syncError) => {
        log.warn('Sync browser webview bounds failed', syncError);
      });
    });
  }, [syncWebviewBounds]);

  useEffect(() => {
    if (!isTauri) {
      return;
    }

    if (isActive) {
      if (!webviewRef.current) {
        void loadUrl(currentUrlRef.current).catch((loadError) => {
          log.warn('Restore browser webview failed', loadError);
        });
        return;
      }

      void (async () => {
        const { getCurrentWindow } = await import('@tauri-apps/api/window');
        await webviewRef.current?.reparent(getCurrentWindow());
        await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
        await syncWebviewBounds();
      })()
        .then(() => webviewRef.current?.show())
        .then(() => webviewRef.current?.setFocus())
        .catch((syncError) => {
          log.warn('Activate browser webview failed', syncError);
        });
      return;
    }

    if (webviewRef.current) {
      void ensureHolderWindow()
        .then((holderWindow) => webviewRef.current?.reparent(holderWindow))
        .then(() => holderWindowRef.current?.hide())
        .catch((reparentError) => {
          log.warn('Reparent browser webview to holder window failed', reparentError);
          return closeWebview();
        })
        .catch((closeError) => {
          log.warn('Close browser webview on tab switch failed', closeError);
        });
    }
  }, [closeWebview, ensureHolderWindow, isActive, isTauri, loadUrl, syncWebviewBounds]);

  useEffect(() => () => {
    if (holderWindowRef.current) {
      void holderWindowRef.current.close().catch((closeError) => {
        log.warn('Close browser holder window failed', closeError);
      });
    }
  }, []);

  useEffect(() => {
    if (!isTauri) {
      return;
    }

    const observer = new ResizeObserver(() => {
      if (isActive) {
        queueSync();
      }
    });

    if (viewportRef.current) {
      observer.observe(viewportRef.current);
    }

    const handleResize = () => {
      if (isActive) {
        queueSync();
      }
    };

    window.addEventListener('resize', handleResize);
    return () => {
      observer.disconnect();
      window.removeEventListener('resize', handleResize);
      if (resizeFrameRef.current !== null) {
        window.cancelAnimationFrame(resizeFrameRef.current);
        resizeFrameRef.current = null;
      }
    };
  }, [isActive, isTauri, queueSync]);

  useEffect(() => () => {
    if (urlPollTimerRef.current) {
      clearInterval(urlPollTimerRef.current);
      urlPollTimerRef.current = null;
    }
    void closeWebview();
  }, [closeWebview]);

  useEffect(() => {
    if (!isTauri) return;

    const OVERLAY_SELECTOR = '.modal-overlay, .canvas-mission-control';
    let hiddenByOverlay = false;

    const checkOverlays = () => {
      const hasOverlay = document.querySelector(OVERLAY_SELECTOR) !== null;
      if (hasOverlay && !hiddenByOverlay) {
        hiddenByOverlay = true;
        void webviewRef.current?.hide().catch(() => {});
      } else if (!hasOverlay && hiddenByOverlay) {
        hiddenByOverlay = false;
        if (isActive) {
          void syncWebviewBounds()
            .then(() => webviewRef.current?.show())
            .catch(() => {});
        }
      }
    };

    const observer = new MutationObserver(checkOverlays);
    observer.observe(document.body, { childList: true, subtree: true });

    const handleToolbarActivating = () => {
      void webviewRef.current?.hide().catch(() => {});
    };
    window.addEventListener('toolbar-mode-activating', handleToolbarActivating);

    return () => {
      observer.disconnect();
      window.removeEventListener('toolbar-mode-activating', handleToolbarActivating);
    };
  }, [isActive, isTauri, syncWebviewBounds]);

  const handleSubmit = useCallback((event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    void loadUrl(inputValue);
  }, [inputValue, loadUrl]);

  const handleGoBack = useCallback(() => {
    if (!isTauri || !webviewLabelRef.current) return;
    void evalWebview(webviewLabelRef.current, 'history.back()').catch(() => {});
  }, [isTauri]);

  const handleGoForward = useCallback(() => {
    if (!isTauri || !webviewLabelRef.current) return;
    void evalWebview(webviewLabelRef.current, 'history.forward()').catch(() => {});
  }, [isTauri]);

  const handleRefresh = useCallback(() => {
    if (!isTauri || !webviewLabelRef.current) return;
    void evalWebview(webviewLabelRef.current, 'location.reload()').catch(() => {});
  }, [isTauri]);

  // 切换录制状态：开始 / 停止
  // 使用 flowchartBusy 锁定，防止异步切换期间重复点击导致状态错乱
  // recordingStop 的 TeachingStopResult 会被自动缓存 + 后端 emit `recording:stopped`，
  // 主窗口订阅该事件后弹"录制完成"通知，提供「立即执行」入口 —— 这里无需手动 push 通知。
  // 修复 1：开始录制时只在 isRecording=false 路径启用 flowchartBusy，避免无谓 disable 1s+。
  // 修复 2：失败重置：开始失败时把 isRecording 显式置回 false，避免 UI 与后端漂移。
  const handleToggleRecording = useCallback(() => {
    if (flowchartBusy) return;
    setFlowchartError(null);
    setFlowchartBusy(true);
    if (isRecording) {
      void recordingStop('browser')
        .then(() => setIsRecording(false))
        .catch((recErr) => {
          const message = formatUnknownError(recErr);
          log.error('Stop recording failed', recErr);
          setFlowchartError(message);
        })
        .finally(() => setFlowchartBusy(false));
    } else {
      // 先把 isRecording 翻为 true，给用户即时反馈（后端拒绝则回滚）。
      setIsRecording(true);
      void recordingStart('browser')
        .catch((recErr) => {
          const message = formatUnknownError(recErr);
          log.error('Start recording failed', recErr);
          setFlowchartError(message);
          setIsRecording(false);
        })
        .finally(() => setFlowchartBusy(false));
    }
  }, [flowchartBusy, isRecording]);

  // 监听后端 emit 的 `recording:stopped` 事件，appName 匹配 'browser' 时
  //   * 把 isRecording 置回 false（用户可能从其他地方停止录制，比如关闭浮窗）
  //   * 自动把刚录制的流程图加载到 BrowserScene 自带的流程图面板里（无需手动点 Load）
  // 之前完全依赖 onClick 同步翻 isRecording，用户通过浮窗 / 全局通知停止录制时，
  // 浏览器面板上的录制状态和流程图都不会更新。
  useEffect(() => {
    if (!isTauri || !isTauriRuntime()) {
      return;
    }
    let disposed = false;
    let unlisten: (() => void) | null = null;
    void import('@tauri-apps/api/event')
      .then(({ listen }) =>
        listen<{ appName?: string | null }>('recording:stopped', (event) => {
          if (disposed) return;
          if (event.payload?.appName !== 'browser') return;
          setIsRecording(false);
          log.info('BrowserScene: recording stopped, auto-loading flowchart');
          // 自动加载最新流程图到面板。失败不提示：用户可以手动点 Load。
          setFlowchartBusy(true);
          recordingLoad('browser')
            .then((fc) => {
              if (disposed) return;
              setFlowchartJson(JSON.stringify(fc, null, 2));
              setShowFlowchartPanel(true);
            })
            .catch((loadErr) => {
              if (disposed) return;
              log.warn('Auto-load recording after stop failed', loadErr);
            })
            .finally(() => {
              if (!disposed) setFlowchartBusy(false);
            });
        }),
      )
      .then((removeListener) => {
        if (disposed) {
          removeListener();
          return;
        }
        unlisten = removeListener;
      })
      .catch((err) => {
        if (!disposed) {
          log.warn('Failed to listen for recording:stopped', err);
        }
      });
    return () => {
      disposed = true;
      unlisten?.();
    };
  }, [isTauri]);

  // 卸载时若仍在录制则强制停止，避免 BrowserScene 切走/关闭后留下幽灵录制
  // （录制是全局的，不在此处停就只有关 app 才能停掉，违反直觉）。
  // 用 ref 跟踪最新 isRecording，避免 useEffect 闭包过期。
  const isRecordingRef = useRef(isRecording);
  isRecordingRef.current = isRecording;
  useEffect(() => {
    return () => {
      if (isRecordingRef.current) {
        recordingStop('browser').catch((err) => {
          log.warn('Stop recording on BrowserScene unmount failed', err);
        });
      }
    };
  }, []);

  // 加载已录制的流程图 JSON
  const handleLoadRecording = useCallback(() => {
    setFlowchartBusy(true);
    setFlowchartError(null);
    void recordingLoad('browser')
      .then((flowchart) => {
        setFlowchartJson(JSON.stringify(flowchart, null, 2));
      })
      .catch((loadErr) => {
        const message = formatUnknownError(loadErr);
        log.error('Load recording failed', loadErr);
        setFlowchartError(message);
      })
      .finally(() => setFlowchartBusy(false));
  }, []);

  // 执行当前流程图 JSON
  const handleExecuteFlowchart = useCallback(() => {
    if (!flowchartJson) {
      setFlowchartError(t('browserScene.loadRecordingFirst'));
      return;
    }
    let parsed: unknown;
    try {
      parsed = JSON.parse(flowchartJson);
    } catch (parseErr) {
      const message = formatUnknownError(parseErr);
      setFlowchartError(t('browserScene.flowchartJsonParseError', { error: message }));
      return;
    }
    setFlowchartBusy(true);
    setFlowchartError(null);
    void automationExecute(parsed)
      .then((result) => {
        setFlowchartResult(JSON.stringify(result, null, 2));
      })
      .catch((execErr) => {
        const message = formatUnknownError(execErr);
        log.error('Execute flowchart failed', execErr);
        setFlowchartError(message);
      })
      .finally(() => setFlowchartBusy(false));
  }, [flowchartJson, t]);

  return (
    <div className="browser-scene">
      <form className="browser-scene__toolbar" onSubmit={handleSubmit}>
        <IconButton
          type="button"
          variant="ghost"
          size="small"
          onClick={handleGoBack}
          aria-label={t('nav.back')}
        >
          <ChevronLeft size={14} />
        </IconButton>
        <IconButton
          type="button"
          variant="ghost"
          size="small"
          onClick={handleGoForward}
          aria-label={t('nav.forward')}
        >
          <ChevronRight size={14} />
        </IconButton>
        <IconButton
          type="button"
          variant="ghost"
          size="small"
          onClick={handleRefresh}
          disabled={isLoading}
          aria-label={t('actions.refresh')}
        >
          <RefreshCw size={14} className={isLoading ? 'browser-scene__spinning' : undefined} />
        </IconButton>
        <IconButton
          type="button"
          variant="ghost"
          size="small"
          onClick={handleToggleRecording}
          disabled={flowchartBusy}
          aria-label={isRecording ? t('browserScene.stopRecording') : t('browserScene.startRecording')}
          title={isRecording ? t('browserScene.stopRecording') : t('browserScene.startRecording')}
        >
          <Circle
            size={14}
            className={isRecording ? 'browser-scene__recording-dot' : undefined}
          />
        </IconButton>
        <div className="browser-scene__address">
          <Globe size={16} />
          <input
            type="text"
            value={inputValue}
            onChange={(event) => setInputValue(event.target.value)}
            placeholder={t('browserView.addressPlaceholder', { exampleUrl: 'https://example.com' })}
            spellCheck={false}
          />
        </div>
      </form>

      <div className="browser-scene__flowchart">
        <button
          type="button"
          className="browser-scene__flowchart-header"
          onClick={() => setShowFlowchartPanel((v) => !v)}
          aria-expanded={showFlowchartPanel}
        >
          <ChevronDown
            size={14}
            className={`browser-scene__flowchart-chevron${showFlowchartPanel ? '' : ' browser-scene__flowchart-chevron--collapsed'}`}
          />
          <span>{t('browserScene.automationFlowchart')}</span>
        </button>
        {showFlowchartPanel ? (
          <div className="browser-scene__flowchart-body">
            <div className="browser-scene__flowchart-actions">
              <button
                type="button"
                className="browser-scene__flowchart-btn"
                onClick={handleLoadRecording}
                disabled={flowchartBusy || isRecording}
              >
                {t('browserScene.loadRecording')}
              </button>
              <button
                type="button"
                className="browser-scene__flowchart-btn"
                onClick={handleExecuteFlowchart}
                disabled={flowchartBusy || isRecording}
              >
                {t('browserScene.executeFlowchart')}
              </button>
            </div>
            {flowchartError ? (
              <div className="browser-scene__flowchart-error">
                <AlertTriangle size={14} />
                <span>{flowchartError}</span>
              </div>
            ) : null}
            {flowchartJson ? (
              <div className="browser-scene__flowchart-block">
                <div className="browser-scene__flowchart-label">{t('browserScene.flowchartJson')}</div>
                <pre className="browser-scene__pre">{flowchartJson}</pre>
              </div>
            ) : null}
            {flowchartResult ? (
              <div className="browser-scene__flowchart-block">
                <div className="browser-scene__flowchart-label">{t('browserScene.executionResult')}</div>
                <pre className="browser-scene__pre">{flowchartResult}</pre>
              </div>
            ) : null}
          </div>
        ) : null}
      </div>

      {error ? (
        <div className="browser-scene__error">
          <AlertTriangle size={16} />
          <span>{error}</span>
        </div>
      ) : null}

      <div className="browser-scene__content">
        {!isTauri ? (
          <iframe
            className="browser-scene__iframe"
            src={currentUrl}
            title="Embedded Browser"
            sandbox="allow-scripts allow-same-origin allow-forms allow-popups allow-downloads"
          />
        ) : (
          <div ref={viewportRef} className="browser-scene__webview-host">
            <div className="browser-scene__webview-placeholder">
              <Globe size={20} />
              <span>{currentUrl}</span>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default BrowserScene;
