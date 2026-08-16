import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Pause,
  Play,
  Square,
  SkipForward,
  Minus,
  Maximize2,
} from 'lucide-react';
import { MainMiniWindow } from '../MainMiniWindow/MainMiniWindow';
import {
  automationExecute,
  automationExecuteStep,
  executeFlowchartStep,
  fwChatTransferToMain,
  fwClose,
  fwFinishSession,
  fwGetState,
  fwMinimize,
  fwRestore,
  fwShowMainWindow,
  recordingGetStatus,
  recordingLoad,
  recordingPause,
  recordingResume,
  recordingStart,
  recordingStop,
} from '@/infrastructure/api/tupai';
import { llmStreamChat } from '@/infrastructure/api/tupai/llm';
import type { LlmMessage } from '@/infrastructure/api/tupai/types';
import { useI18n } from '@/infrastructure/i18n/hooks/useI18n';
import { createLogger } from '@/shared/utils/logger';
import './FloatingWindow.scss';

const log = createLogger('FloatingWindow');

export interface FloatingWindowProps {
  /** 从 URL hash 解析得到的 entry id（`index.html#/floating-window?id=xxx`）。 */
  id?: string;
}

// 速记本地持久化 key。
const QUICK_NOTES_KEY = 'tupai-quick-notes';

// ──────────────────────────────────────────────
// 统一关闭流程：先恢复主窗口，再通过 fwClose 让后端 hide + destroy 窗口。
//
// 黑屏根因（彻底解决）：
//   window.close() 触发 OS 级别窗口关闭，过程中 webview 渲染表面被撕裂，
//   暴露 background_color(10,10,18) 深色背景 → 用户看到黑屏闪烁。
//   即使在 CloseRequested 中调 hide() 也是竞态 —— OS 可能先开始撕裂。
//
// 正确方案：不调 window.close()，改用 fwClose(id) 让后端 close() 方法执行
//   hide() → destroy()。hide() 让窗口瞬间不可见（用户看不到撕裂帧），
//   destroy() 随后清理 webview。fire-and-forget + catch 防止 IPC 未送达
//   时的未处理 rejection（窗口被 destroy 后 IPC response 不会到达）。
//
// 恢复主窗口：ChatFloaterButton / AutomationScene 打开浮窗时会隐藏主窗口，
//   关闭浮窗时必须恢复，否则用户看不到任何窗口。
// ──────────────────────────────────────────────
function closeFloatingWindow(id?: string): void {
  void fwShowMainWindow().catch(() => { /* 主窗口可能本来就不存在 */ });
  if (id) {
    // 后端 hide() + destroy()，从根源避免 webview 撕裂黑屏。
    // 不调 window.close() —— 那会触发 OS 关闭流程导致撕裂。
    void fwClose(id).catch(() => { /* 窗口可能已被 destroy，忽略 */ });
  } else {
    // 无 id 时兜底用 window.close()
    window.close();
  }
}

// ──────────────────────────────────────────────
// 通用浮窗外壳
// ──────────────────────────────────────────────
interface FloatingWindowShellProps {
  title: string;
  onClose: () => void;
  closeLabel: string;
  onMinimize?: () => void;
  onMaximize?: () => void;
  minimizeLabel?: string;
  maximizeLabel?: string;
  children: React.ReactNode;
}

const FloatingWindowShell: React.FC<FloatingWindowShellProps> = ({
  title,
  onClose,
  closeLabel,
  onMinimize,
  onMaximize,
  minimizeLabel,
  maximizeLabel,
  children,
}) => {
  return (
    <div className="tupai-floating-window" role="dialog">
      <div className="tupai-floating-window__titlebar" data-tauri-drag-region>
        <span className="tupai-floating-window__title" data-tauri-drag-region>
          {title}
        </span>
        <div className="tupai-floating-window__controls">
          {onMinimize && (
            <button
              type="button"
              className="tupai-floating-window__ctrl-btn tupai-floating-window__ctrl-btn--minimize"
              onClick={onMinimize}
              aria-label={minimizeLabel || 'Minimize'}
              title={minimizeLabel || 'Minimize'}
            >
              <Minus size={13} />
            </button>
          )}
          {onMaximize && (
            <button
              type="button"
              className="tupai-floating-window__ctrl-btn tupai-floating-window__ctrl-btn--maximize"
              onClick={onMaximize}
              aria-label={maximizeLabel || 'Maximize'}
              title={maximizeLabel || 'Maximize'}
            >
              <Maximize2 size={12} />
            </button>
          )}
          <button
            type="button"
            className="tupai-floating-window__close-btn"
            onClick={onClose}
            aria-label={closeLabel}
          >
            ×
          </button>
        </div>
      </div>
      <div className="tupai-floating-window__body">{children}</div>
    </div>
  );
};

// ──────────────────────────────────────────────
// 录制悬浮窗 — 三个按钮：录制 / 暂停 / 停止
//
// 状态机：idle → recording ⇄ paused
//   idle:       显示 [● 录制]
//   recording:  显示 [⏸ 暂停] [⏹ 停止]
//   paused:     显示 [▶ 继续] [⏹ 停止]
//
// 轮询后端 get_recording_status 保持状态一致。
// ──────────────────────────────────────────────

const RECORDER_ID_PREFIX = 'recorder-';
const STATUS_POLL_MS = 1000;

interface RecordingStatus {
  state: string;
  event_count?: number;
  action_count?: number;
  elapsed_ms?: number;
}

function resolveAppName(id?: string, prefix = RECORDER_ID_PREFIX): string {
  if (!id) return 'floating';
  if (id.startsWith(prefix)) return id.slice(prefix.length) || 'floating';
  return 'floating';
}

function formatElapsed(ms: number): string {
  const sec = Math.floor(ms / 1000);
  return `${String(Math.floor(sec / 60)).padStart(2, '0')}:${String(sec % 60).padStart(2, '0')}`;
}

const RecorderFloatingWindow: React.FC<{ id?: string }> = ({ id }) => {
  const { t } = useI18n('common');
  const appName = resolveAppName(id);
  const [status, setStatus] = useState<RecordingStatus>({ state: 'idle' });
  const [busy, setBusy] = useState(false);
  const [closing, setClosing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 轮询后端状态
  useEffect(() => {
    let cancelled = false;
    const tick = async () => {
      try {
        const s = await recordingGetStatus();
        if (!cancelled) setStatus(s);
      } catch { /* ignore */ }
    };
    void tick();
    const timer = setInterval(() => void tick(), STATUS_POLL_MS);
    return () => { cancelled = true; clearInterval(timer); };
  }, []);

  // 立即刷新状态
  const refresh = useCallback(async () => {
    try { setStatus(await recordingGetStatus()); } catch { /* ignore */ }
  }, []);

  // 录制 / 暂停 / 继续
  const handlePrimary = useCallback(async () => {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      if (status.state === 'recording') {
        await recordingPause();
      } else if (status.state === 'paused') {
        await recordingResume();
      } else {
        await recordingStart(appName);
      }
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }, [appName, status.state, busy, refresh]);

  // 停止
  const handleStop = useCallback(async () => {
    if (busy || status.state === 'idle') return;
    setBusy(true);
    setError(null);
    try {
      await recordingStop(appName);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }, [appName, status.state, busy, refresh]);

  // 关闭浮窗：先停止录制 → 恢复主窗口 → 关闭
  const handleClose = useCallback(async () => {
    if (closing) return;
    setClosing(true);
    try {
      if (status.state !== 'idle') {
        try { await recordingStop(appName); } catch (err) {
          log.error('Auto-stop on close failed', err);
          setError(t('floatingWindow.stopFailedOnClose'));
          setClosing(false);
          return;
        }
      }
      try { await fwFinishSession(appName); } catch {
        try { await fwShowMainWindow(); } catch { /* ignore */ }
      }
      closeFloatingWindow(id);
    } finally {
      setClosing(false);
    }
  }, [id, appName, status.state, closing, t]);

  const isRecording = status.state === 'recording';
  const isPaused = status.state === 'paused';
  const elapsed = formatElapsed(status.elapsed_ms ?? 0);
  const steps = status.action_count ?? 0;

  return (
    <FloatingWindowShell title={t('floatingWindow.recorderTitle')} onClose={handleClose} closeLabel={t('floatingWindow.close')}>
      <div className="tupai-floating-window__recorder">
        {/* 状态行 */}
        <div className="tupai-floating-window__recorder-status">
          <span
            className={`tupai-floating-window__recorder-dot${isRecording ? ' is-recording' : isPaused ? ' is-paused' : ''}`}
            aria-hidden="true"
          />
          <span className="tupai-floating-window__recorder-text">
            {isRecording
              ? t('floatingWindow.recording', { count: steps })
              : isPaused
                ? t('floatingWindow.recordingPaused', { count: steps })
                : t('floatingWindow.recordingIdle')}
          </span>
          {(isRecording || isPaused) && (status.elapsed_ms ?? 0) > 0 && (
            <span className="tupai-floating-window__recorder-elapsed">{elapsed}</span>
          )}
        </div>

        {/* 按钮行 */}
        <div className="tupai-floating-window__recorder-actions">
          {status.state === 'idle' ? (
            <button
              type="button"
              className="tupai-floating-window__action-btn tupai-floating-window__action-btn--primary"
              onClick={handlePrimary}
              disabled={busy || closing}
              title={t('floatingWindow.startRecording')}
            >
              <Play size={13} aria-hidden="true" />
              <span>{t('floatingWindow.start')}</span>
            </button>
          ) : (
            <>
              <button
                type="button"
                className="tupai-floating-window__action-btn tupai-floating-window__action-btn--primary"
                onClick={handlePrimary}
                disabled={busy || closing}
                title={isRecording ? t('floatingWindow.pause') : t('floatingWindow.resume')}
              >
                {isRecording ? <Pause size={13} aria-hidden="true" /> : <Play size={13} aria-hidden="true" />}
                <span>{isRecording ? t('floatingWindow.pause') : t('floatingWindow.resume')}</span>
              </button>
              <button
                type="button"
                className="tupai-floating-window__action-btn tupai-floating-window__action-btn--stop"
                onClick={handleStop}
                disabled={busy || closing}
                title={t('floatingWindow.stopAndSave')}
              >
                <Square size={11} aria-hidden="true" />
                <span>{t('floatingWindow.stop')}</span>
              </button>
            </>
          )}
        </div>

        {/* 错误提示 */}
        {error && (
          <div className="tupai-floating-window__feedback tupai-floating-window__feedback--err" role="alert">
            {error}
          </div>
        )}
      </div>
    </FloatingWindowShell>
  );
};

// ──────────────────────────────────────────────
// 执行悬浮窗 — 三个按钮：单步 / 暂停 / 停止
//
// 加载已录制的流程图节点，逐节点单步执行。
// "暂停"暂停自动连续执行（已执行的不回退）。
// "停止"结束执行并关闭浮窗。
// ──────────────────────────────────────────────

const STEPS_ID_PREFIX = 'steps-';
const ACTION_TYPES = new Set(['click', 'type', 'hotkey']);

const ExecutionFloatingWindow: React.FC<{ id?: string }> = ({ id }) => {
  const { t } = useI18n('common');
  const appName = resolveAppName(id, STEPS_ID_PREFIX);
  const [nodes, setNodes] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [cursor, setCursor] = useState(-1); // -1 = 未开始
  const [isRunning, setIsRunning] = useState(false); // 自动连续执行中
  const [isStepping, setIsStepping] = useState(false); // 单步执行中
  const [paused, setPaused] = useState(false);
  const [results, setResults] = useState<Record<string, { ok: boolean; error?: string }>>({});
  const [closing, setClosing] = useState(false);
  const abortRef = useRef(false);
  // runAll 的"代际"计数器：暂停后恢复时递增，让正在 sleep 的旧循环
  // 通过 gen !== runGenRef.current 自我作废，避免新旧循环并发把同一步
  // 骤执行两次（abortRef 是布尔值，恢复时会被重置为 false，无法区分新旧循环）。
  const runGenRef = useRef(0);

  // 加载流程图节点
  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    recordingLoad(appName)
      .then((fc: any) => {
        if (cancelled) return;
        const list: any[] = Array.isArray(fc?.nodes) ? fc.nodes : [];
        setNodes(list.filter((n: any) => ACTION_TYPES.has(n?.action) || ACTION_TYPES.has(n?.type)));
      })
      .catch((err) => {
        log.error('load flowchart failed', err);
        if (!cancelled) setNodes([]);
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [appName]);

  // 执行单步
  const stepOnce = useCallback(async () => {
    if (isStepping || isRunning) return;
    const next = cursor + 1;
    if (next >= nodes.length) return;
    const node = nodes[next];
    const nodeId = node?.id ?? JSON.stringify(node);
    setIsStepping(true);
    setCursor(next);
    try {
      const res = await executeFlowchartStep(node);
      setResults((prev) => ({ ...prev, [nodeId]: { ok: !!res?.ok, error: res?.error } }));
    } catch (err: any) {
      setResults((prev) => ({ ...prev, [nodeId]: { ok: false, error: err?.message ?? String(err) } }));
    } finally {
      setIsStepping(false);
    }
  }, [cursor, nodes, isStepping, isRunning]);

  // 自动连续执行（可被暂停/停止中断）
  // 使用 runGenRef 代际计数：暂停后恢复会递增 gen，让正在 sleep 的旧循环
  // 自动作废；只有当前代际的循环结束时才清 isRunning，避免旧循环提前清掉
  // 新循环的运行态。
  const runAll = useCallback(async () => {
    if (isRunning) return;
    const gen = ++runGenRef.current;
    setIsRunning(true);
    setPaused(false);
    abortRef.current = false;
    for (let i = cursor + 1; i < nodes.length; i++) {
      if (abortRef.current || gen !== runGenRef.current) break;
      const node = nodes[i];
      const nodeId = node?.id ?? JSON.stringify(node);
      setCursor(i);
      try {
        const res = await executeFlowchartStep(node);
        setResults((prev) => ({ ...prev, [nodeId]: { ok: !!res?.ok, error: res?.error } }));
      } catch (err: any) {
        setResults((prev) => ({ ...prev, [nodeId]: { ok: false, error: err?.message ?? String(err) } }));
      }
      await new Promise((r) => setTimeout(r, 400));
    }
    // 仅当前代际的循环才能清运行态；被暂停/作废的旧循环不清，避免误清新循环。
    if (gen === runGenRef.current) setIsRunning(false);
  }, [cursor, nodes, isRunning]);

  // 暂停自动执行
  const handlePause = useCallback(() => {
    abortRef.current = true;
    // 递增 gen 让正在 sleep 的旧循环失效（abortRef 在恢复时会被重置，
    // 单靠它无法阻止旧循环 sleep 醒来后继续跑）。
    runGenRef.current++;
    setPaused(true);
    setIsRunning(false);
  }, []);

  // 停止：关闭浮窗
  // closing guard 防止连点导致 fwFinishSession 重复 emit（主窗口会重复
  // 跳转流程图场景）；递增 runGenRef 让正在 sleep 的 runAll 循环自我作废，
  // 避免窗口 destroy 后还在跑 setState。
  const handleClose = useCallback(async () => {
    if (closing) return;
    setClosing(true);
    abortRef.current = true;
    runGenRef.current++;
    try {
      try { await fwFinishSession(appName); } catch {
        try { await fwShowMainWindow(); } catch { /* ignore */ }
      }
      closeFloatingWindow(id);
    } finally {
      setClosing(false);
    }
  }, [id, appName, closing]);

  const progress = nodes.length > 0 ? `${Math.min(cursor + 1, nodes.length)}/${nodes.length}` : '';
  const allDone = cursor >= nodes.length - 1 && nodes.length > 0;
  // key 必须与 stepOnce / runAll 存储时一致 (node?.id ?? JSON.stringify(node))，
  // 否则当节点无 id 时存的是 JSON 字符串、这里却查 ''，最近一步结果永远不显示。
  const lastResult = cursor >= 0 && nodes[cursor]
    ? results[nodes[cursor]?.id ?? JSON.stringify(nodes[cursor] ?? '')]
    : null;

  return (
    <FloatingWindowShell title={t('floatingWindow.stepsTitle')} onClose={handleClose} closeLabel={t('floatingWindow.close')}>
      <div className="tupai-floating-window__recorder">
        {/* 状态行 */}
        <div className="tupai-floating-window__recorder-status">
          <span
            className={`tupai-floating-window__recorder-dot${isRunning ? ' is-recording' : paused ? ' is-paused' : ''}`}
            aria-hidden="true"
          />
          <span className="tupai-floating-window__recorder-text">
            {loading
              ? t('floatingWindow.stepsLoading')
              : nodes.length === 0
                ? t('floatingWindow.stepsEmpty')
                : isRunning
                  ? t('floatingWindow.stepsRunningAll')
                  : allDone
                    ? t('automationScene.logExecuteComplete')
                    : paused
                      ? t('floatingWindow.recordingPaused', { count: cursor + 1 })
                      : t('floatingWindow.stepsTitle')}
          </span>
          {progress && <span className="tupai-floating-window__recorder-elapsed">{progress}</span>}
        </div>

        {/* 最近一步结果 */}
        {lastResult && (
          <div className={`tupai-floating-window__recorder-step-result ${lastResult.ok ? 'is-ok' : 'is-err'}`}>
            {lastResult.ok
              ? `✓ ${nodes[cursor]?.label ?? 'step'}`
              : `✗ ${lastResult.error ?? 'failed'}`}
          </div>
        )}

        {/* 第一行：依次执行全部（主操作）— 自动连续执行所有节点，暂停后变"继续" */}
        {!loading && nodes.length > 0 && (
          <div className="tupai-floating-window__recorder-actions">
            <button
              type="button"
              className="tupai-floating-window__action-btn tupai-floating-window__action-btn--primary"
              onClick={() => void runAll()}
              disabled={isRunning || isStepping || allDone || closing}
              title={paused ? t('floatingWindow.resume') : t('floatingWindow.stepsRunAll')}
            >
              <Play size={13} aria-hidden="true" />
              <span>
                {isRunning
                  ? t('floatingWindow.stepsRunningAll')
                  : paused
                    ? t('floatingWindow.resume')
                    : t('floatingWindow.stepsRunAll')}
              </span>
            </button>
          </div>
        )}

        {/* 第二行：单步 / 暂停 / 停止 — 辅助操作，按状态禁用 */}
        {!loading && nodes.length > 0 && (
          <div className="tupai-floating-window__recorder-actions">
            {/* 单步：未运行且未完成时可点 */}
            <button
              type="button"
              className="tupai-floating-window__action-btn tupai-floating-window__action-btn--primary"
              onClick={stepOnce}
              disabled={isStepping || isRunning || allDone || closing}
              title={t('floatingWindow.step')}
            >
              <SkipForward size={13} aria-hidden="true" />
              <span>{t('floatingWindow.step')}</span>
            </button>
            {/* 暂停：运行中可点，否则禁用 */}
            <button
              type="button"
              className="tupai-floating-window__action-btn tupai-floating-window__action-btn--primary"
              onClick={handlePause}
              disabled={!isRunning || closing}
              title={t('floatingWindow.pause')}
            >
              <Pause size={13} aria-hidden="true" />
              <span>{t('floatingWindow.pause')}</span>
            </button>
            {/* 停止：关闭浮窗 */}
            <button
              type="button"
              className="tupai-floating-window__action-btn tupai-floating-window__action-btn--stop"
              onClick={handleClose}
              disabled={closing}
              title={t('floatingWindow.stop')}
            >
              <Square size={11} aria-hidden="true" />
              <span>{t('floatingWindow.stop')}</span>
            </button>
          </div>
        )}

        {/* 空节点提示：显示关闭按钮让用户能退出 */}
        {!loading && nodes.length === 0 && (
          <div className="tupai-floating-window__recorder-actions">
            <button
              type="button"
              className="tupai-floating-window__action-btn tupai-floating-window__action-btn--stop"
              onClick={handleClose}
              disabled={closing}
              title={t('floatingWindow.close')}
            >
              <Square size={11} aria-hidden="true" />
              <span>{t('floatingWindow.close')}</span>
            </button>
          </div>
        )}
      </div>
    </FloatingWindowShell>
  );
};

// ──────────────────────────────────────────────
// 自动化控制：粘贴流程图 JSON 并执行
// ──────────────────────────────────────────────

const AutomationControlWindow: React.FC<{ id?: string }> = ({ id }) => {
  const { t } = useI18n('common');
  const [jsonText, setJsonText] = useState('');
  const [result, setResult] = useState('');
  const [isRunning, setIsRunning] = useState(false);
  const [isStepping, setIsStepping] = useState(false);

  const runExecute = useCallback(async (mode: 'full' | 'step') => {
    let parsed: any;
    try {
      parsed = jsonText.trim() ? JSON.parse(jsonText) : {};
    } catch (err) {
      setResult(t('floatingWindow.jsonParseError', { error: err instanceof Error ? err.message : String(err) }));
      return;
    }
    if (mode === 'full') setIsRunning(true); else setIsStepping(true);
    try {
      const res = mode === 'step' ? await automationExecuteStep(parsed) : await automationExecute(parsed);
      setResult(`[${mode}] ` + JSON.stringify(res, null, 2));
    } catch (err) {
      setResult(t('floatingWindow.executeFailed', { error: err instanceof Error ? err.message : String(err) }));
    } finally {
      if (mode === 'full') setIsRunning(false); else setIsStepping(false);
    }
  }, [jsonText, t]);

  return (
    <FloatingWindowShell title={t('floatingWindow.automationTitle')} onClose={() => closeFloatingWindow(id)} closeLabel={t('floatingWindow.close')}>
      <div className="tupai-floating-window__automation">
        <textarea
          className="tupai-floating-window__textarea"
          value={jsonText}
          onChange={(e) => setJsonText(e.target.value)}
          placeholder={t('floatingWindow.pasteFlowchart')}
          spellCheck={false}
        />
        <div className="tupai-floating-window__action-row">
          <button type="button" className="tupai-floating-window__action-btn tupai-floating-window__action-btn--primary" onClick={() => void runExecute('full')} disabled={isRunning || isStepping}>
            {isRunning ? t('floatingWindow.executing') : t('floatingWindow.execute')}
          </button>
          <button type="button" className="tupai-floating-window__action-btn" onClick={() => void runExecute('step')} disabled={isRunning || isStepping}>
            {isStepping ? t('floatingWindow.stepping') : t('floatingWindow.step')}
          </button>
        </div>
        {result ? <pre className="tupai-floating-window__result">{result}</pre> : null}
      </div>
    </FloatingWindowShell>
  );
};

// ──────────────────────────────────────────────
// 速记
// ──────────────────────────────────────────────

const QuickNotesWindow: React.FC<{ id?: string }> = ({ id }) => {
  const { t } = useI18n('common');
  const [text, setText] = useState(() => {
    try { return localStorage.getItem(QUICK_NOTES_KEY) ?? ''; } catch { return ''; }
  });
  const saveTimer = useRef<number | undefined>(undefined);

  const handleChange = useCallback((e: React.ChangeEvent<HTMLTextAreaElement>) => {
    const value = e.target.value;
    setText(value);
    if (saveTimer.current !== undefined) clearTimeout(saveTimer.current);
    saveTimer.current = window.setTimeout(() => {
      saveTimer.current = undefined;
      try { localStorage.setItem(QUICK_NOTES_KEY, value); } catch { /* ignore */ }
    }, 400);
  }, []);

  useEffect(() => () => { if (saveTimer.current !== undefined) clearTimeout(saveTimer.current); }, []);

  const handleClose = useCallback(() => {
    if (saveTimer.current !== undefined) { clearTimeout(saveTimer.current); saveTimer.current = undefined; }
    try { localStorage.setItem(QUICK_NOTES_KEY, text); } catch { /* ignore */ }
    closeFloatingWindow(id);
  }, [id, text]);

  return (
    <FloatingWindowShell title={t('floatingWindow.notesTitle')} onClose={handleClose} closeLabel={t('floatingWindow.close')}>
      <textarea
        className="tupai-floating-window__textarea tupai-floating-window__textarea--notes"
        value={text}
        onChange={handleChange}
        placeholder={t('floatingWindow.notesPlaceholder')}
        spellCheck={false}
      />
    </FloatingWindowShell>
  );
};

// ──────────────────────────────────────────────
// 快捷对话（本地 echo 占位）
// ──────────────────────────────────────────────

interface ChatMessage { text: string; fromUser: boolean; }

const QuickChatWindow: React.FC<{ id?: string }> = ({ id }) => {
  const { t } = useI18n('common');
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');

  const handleSend = useCallback(() => {
    const value = input.trim();
    if (!value) return;
    setMessages((prev) => [...prev, { text: value, fromUser: true }, { text: value, fromUser: false }]);
    setInput('');
  }, [input]);

  return (
    <FloatingWindowShell title={t('floatingWindow.chatTitle')} onClose={() => closeFloatingWindow(id)} closeLabel={t('floatingWindow.close')}>
      <div className="tupai-floating-window__chat">
        <div className="tupai-floating-window__chat-messages">
          {messages.length === 0
            ? <div className="tupai-floating-window__chat-empty">{t('floatingWindow.chatEmpty')}</div>
            : messages.map((msg, idx) => (
              <div key={idx} className={`tupai-floating-window__chat-msg${msg.fromUser ? ' is-user' : ' is-echo'}`}>{msg.text}</div>
            ))}
        </div>
        <div className="tupai-floating-window__chat-input-row">
          <input type="text" className="tupai-floating-window__chat-input" value={input} onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); handleSend(); } }} placeholder={t('floatingWindow.chatInputPlaceholder')} />
          <button type="button" className="tupai-floating-window__action-btn" onClick={handleSend}>{t('floatingWindow.chatSend')}</button>
        </div>
      </div>
    </FloatingWindowShell>
  );
};

// ──────────────────────────────────────────────
// 悬浮聊天窗：独立 Tauri webview，MCP LLM 流式对话
// ──────────────────────────────────────────────

interface FloaterMessage { role: 'user' | 'assistant'; content: string; }

const ChatFloaterWindow: React.FC<{ id?: string }> = ({ id }) => {
  const { t } = useI18n('common');
  const [input, setInput] = useState('');
  const [messages, setMessages] = useState<FloaterMessage[]>([]);
  const [isStreaming, setIsStreaming] = useState(false);
  const [feedback, setFeedback] = useState<string | null>(null);
  const currentRequestIdRef = useRef<string | null>(null);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const [docked, setDocked] = useState(false);
  const [dockEdge, setDockEdge] = useState<string | null | undefined>(undefined);

  // 订阅 state-changed 更新 docked + dockEdge
  useEffect(() => {
    if (!id) return;
    let disposed = false;
    const refresh = () => {
      void fwGetState().then((entries) => {
        if (disposed) return;
        const entry = entries.find((e) => e.id === id);
        setDocked(Boolean(entry?.docked));
        setDockEdge(entry?.dockEdge ?? null);
      }).catch(() => {});
    };
    refresh();
    let unlisten: (() => void) | null = null;
    void import('@tauri-apps/api/event').then(({ listen }) =>
      listen('floating_window:state-changed', () => { if (!disposed) refresh(); }),
    ).then((remove) => { if (disposed) { remove(); return; } unlisten = remove; }).catch(() => {});
    return () => { disposed = true; unlisten?.(); };
  }, [id]);

  // 自动滚动到底部
  useEffect(() => { messagesEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' }); }, [messages]);

  const handleRestore = useCallback(() => {
    if (!id) return;
    void fwRestore(id).catch((err) => { log.error('fwRestore failed', err); });
  }, [id]);

  const handleSend = useCallback(async () => {
    const message = input.trim();
    if (!message || isStreaming) return;
    const userMsg: FloaterMessage = { role: 'user', content: message };
    const placeholder: FloaterMessage = { role: 'assistant', content: '' };
    setMessages((prev) => [...prev, userMsg, placeholder]);
    setInput('');
    setIsStreaming(true);
    setFeedback(null);
    const sessionId = `chat-floater-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    currentRequestIdRef.current = sessionId;
    try {
      const llmMessages: LlmMessage[] = [...messages, userMsg].map((m) => ({ role: m.role, content: m.content }));
      const stream = llmStreamChat({ sessionId, messages: llmMessages });
      for await (const chunk of stream) {
        if (chunk.type === 'content') {
          const delta = typeof chunk.data === 'string' ? chunk.data : '';
          if (delta) {
            setMessages((prev) => {
              if (prev.length === 0) return prev;
              const last = prev[prev.length - 1];
              if (last.role !== 'assistant') return prev;
              return [...prev.slice(0, -1), { ...last, content: last.content + delta }];
            });
          }
        } else if (chunk.type === 'error') {
          setIsStreaming(false);
          currentRequestIdRef.current = null;
          setMessages((prev) => { const last = prev[prev.length - 1]; return last.role === 'assistant' && !last.content.trim() ? prev.slice(0, -1) : prev; });
          setFeedback(typeof chunk.data === 'string' ? chunk.data : t('floatingWindow.chatSendFailed'));
          setTimeout(() => setFeedback(null), 4000);
          return;
        } else if (chunk.type === 'done') {
          setIsStreaming(false);
          currentRequestIdRef.current = null;
          return;
        }
      }
      setIsStreaming(false);
      currentRequestIdRef.current = null;
    } catch (err) {
      setIsStreaming(false);
      currentRequestIdRef.current = null;
      setMessages((prev) => { const last = prev[prev.length - 1]; return last.role === 'assistant' && !last.content.trim() ? prev.slice(0, -1) : prev; });
      setFeedback(err instanceof Error ? err.message : t('floatingWindow.chatSendFailed'));
      setTimeout(() => setFeedback(null), 4000);
    }
  }, [input, isStreaming, messages, t]);

  const handleMinimize = useCallback(() => {
    if (!id) return;
    void fwMinimize(id).catch((err) => { log.error('fwMinimize failed', err); });
  }, [id]);

  const handleMaximize = useCallback(async () => {
    try {
      const pendingInput = input.trim();
      const history: FloaterMessage[] = pendingInput ? [...messages, { role: 'user', content: pendingInput }] : messages;
      if (history.length > 0) {
        await fwChatTransferToMain(history.map((m) => ({ role: m.role, content: m.content })));
        setInput('');
        closeFloatingWindow(id);
      } else {
        await fwShowMainWindow();
      }
    } catch (err) {
      log.error('maximize transfer failed', err);
      setFeedback(t('floatingWindow.chatSendFailed'));
    }
  }, [input, messages, id, t]);

  // 贴边态 UI —— 小半圆。dockEdge 决定半圆弧朝哪边：
  // 贴右边时弧面朝左（凸入屏幕），贴左边时弧面朝右。
  if (docked) {
    const peekClass = dockEdge === 'left'
      ? 'tupai-floating-window__peek tupai-floating-window__peek--left'
      : 'tupai-floating-window__peek';
    return (
      <div className={peekClass} role="button" aria-label={t('floatingWindow.restore') || 'Restore'}
        title={t('floatingWindow.restore') || 'Restore'} onClick={handleRestore}
        onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); handleRestore(); } }} tabIndex={0} />
    );
  }

  return (
    <FloatingWindowShell title={t('floatingWindow.chatTitle')} onClose={() => closeFloatingWindow(id)} closeLabel={t('floatingWindow.close')}
      onMinimize={handleMinimize} onMaximize={handleMaximize} minimizeLabel={t('floatingWindow.minimize')} maximizeLabel={t('floatingWindow.maximize')}>
      <div className="tupai-floating-window__chat-floater">
        <div className="tupai-floating-window__chat-floater-messages">
          {messages.length === 0
            ? <div className="tupai-floating-window__chat-floater-empty">{t('floatingWindow.chatFloaterHint')}</div>
            : messages.map((msg, idx) => (
              <div key={idx} className={`tupai-floating-window__chat-floater-msg${msg.role === 'user' ? ' is-user' : ' is-assistant'}`}>
                {msg.content || (msg.role === 'assistant' && isStreaming && idx === messages.length - 1 ? '…' : '')}
              </div>
            ))}
          <div ref={messagesEndRef} />
        </div>
        <div className="tupai-floating-window__chat-floater-input-row">
          <textarea className="tupai-floating-window__chat-floater-textarea" value={input} onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); void handleSend(); } }}
            placeholder={t('floatingWindow.chatInputPlaceholder')} disabled={isStreaming} autoFocus rows={3} />
          <button type="button" className="tupai-floating-window__action-btn tupai-floating-window__action-btn--primary"
            onClick={handleSend} disabled={!input.trim() || isStreaming}>
            {isStreaming ? t('floatingWindow.sending') : t('floatingWindow.chatSend')}
          </button>
        </div>
        {feedback && <div className="tupai-floating-window__feedback tupai-floating-window__feedback--err" role="status">{feedback}</div>}
      </div>
    </FloatingWindowShell>
  );
};

// ──────────────────────────────────────────────
// 路由：根据 entry id 前缀分流到不同子视图
// ──────────────────────────────────────────────

type FloatingWindowKind = 'main-mini' | 'recorder' | 'automation' | 'steps' | 'quick-notes' | 'quick-chat' | 'chat-floater';

function resolveKind(id?: string): FloatingWindowKind {
  if (!id) return 'main-mini';
  if (id.startsWith('recorder')) return 'recorder';
  if (id.startsWith('steps')) return 'steps';
  if (id.startsWith('automation')) return 'automation';
  if (id.startsWith('quick-notes')) return 'quick-notes';
  if (id.startsWith('quick-chat')) return 'quick-chat';
  if (id.startsWith('chat-floater')) return 'chat-floater';
  return 'main-mini';
}

export const FloatingWindow: React.FC<FloatingWindowProps> = ({ id }) => {
  const kind = resolveKind(id);

  useEffect(() => {
    log.info('FloatingWindow mounted', { entryId: id, kind });
  }, [id, kind]);

  switch (kind) {
    case 'recorder': return <RecorderFloatingWindow id={id} />;
    case 'steps': return <ExecutionFloatingWindow id={id} />;
    case 'automation': return <AutomationControlWindow id={id} />;
    case 'quick-notes': return <QuickNotesWindow id={id} />;
    case 'quick-chat': return <QuickChatWindow id={id} />;
    case 'chat-floater': return <ChatFloaterWindow id={id} />;
    case 'main-mini':
    default: return <MainMiniWindow id={id} />;
  }
};

export default FloatingWindow;
