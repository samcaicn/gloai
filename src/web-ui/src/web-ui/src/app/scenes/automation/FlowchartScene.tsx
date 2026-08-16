/**
 * FlowchartScene — 独立流程图查看场景。
 *
 * 功能：
 *   1. 选择已录制软件 → 加载并展示流程图
 *   2. 支持只读 / 可编辑模式切换
 *   3. 执行流程图
 */

import React, { useState, useEffect, useCallback, useRef } from 'react';
import { Play, Edit, Eye, AlertTriangle, Circle, CircleDot, Square, GitMerge, X } from 'lucide-react';
import { createLogger } from '@/shared/utils/logger';
import {
  recordingLoad, saveFlowchart,
  executeFlowchartStep, recordingStart, recordingGetStatus, recordingStop, recordingPause, recordingResume,
  launchSoftware, fwOpen, fwHideMainWindow, fwShowMainWindow,
} from '@/infrastructure/api/tupai';
import { useI18n } from '@/infrastructure/i18n/hooks/useI18n';
import { notificationService } from '@/shared/notification-system';
import { isTauriRuntime } from '@/infrastructure/runtime';
import { FlowchartView, EditableFlowchartView } from './flowchart/FlowchartViews';
import { useFlowchartAppStore } from '@/app/stores/flowchartAppStore';
import { detectSmallLoops, mergeSmallLoops, type LoopCandidate } from './canvas/canvasUtils';
import './AutomationScene.scss';

const log = createLogger('FlowchartScene');

const FlowchartScene: React.FC = () => {
  const { t, currentLanguage } = useI18n('common');
  const locale = currentLanguage === 'zh-CN' ? 'zh' : 'en';
  const setFlowchartAppName = useFlowchartAppStore((s) => s.setSelectedAppName);

  const [selectedApp, setSelectedApp] = useState('');
  const [flowchart, setFlowchart] = useState<any>(null);
  const [editedFlowchart, setEditedFlowchart] = useState<any>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [editMode, setEditMode] = useState(false);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // 「从此步骤开始录制」按下后到浮窗真正起来之间的中间态
  const [startingRecord, setStartingRecord] = useState(false);
  // 录制状态徽章：与后端 get_recording_status 同步，显示录制中/暂停。
  const [recordingState, setRecordingState] = useState<string>('idle');
  const [recordingActionCount, setRecordingActionCount] = useState<number>(0);
  // 「从此步骤开始录制」的基线节点 id:非 null 表示当前正以"接续"模式录制,
  // 详情面板渲染录制控制台而非普通操作按钮。录制开始时设置,recording:stopped 时清空。
  const [, setBaselineNodeId] = useState<string | null>(null);

  // 小循环检测：加载流程图后检测相邻重复子序列，提示用户确认是否合并。
  // dismissedLoopApps 记录用户已忽略的 app，避免反复弹窗打扰。
  const [loopCandidates, setLoopCandidates] = useState<LoopCandidate[]>([]);
  const [dismissedLoopApps, setDismissedLoopApps] = useState<Set<string>>(() => new Set());
  // ref 镜像：避免 handleLoadFlowchart 闭包过期读取旧值
  const dismissedLoopAppsRef = useRef(dismissedLoopApps);
  dismissedLoopAppsRef.current = dismissedLoopApps;

  // 每次切换软件(load/selectApp 事件)递增,作为 fitViewKey 透传给
  // FlowchartView/EditableFlowchartView,让视图层主动 fitView 到标准缩放级别。
  // 不递增的话用户切到 app B 时仍停留在 app A 的缩放级别上,体验割裂。
  const [fitViewNonce, setFitViewNonce] = useState(0);

  // 同步 selectedApp 到 flowchartAppStore（SceneBar 读取此 store 显示流程图标签页的 app 名称）
  useEffect(() => {
    setFlowchartAppName(selectedApp);
  }, [selectedApp, setFlowchartAppName]);

  // ref 解决 useEffect 在 handleLoadFlowchart 定义前调用的 TDZ 问题
  const handleLoadFlowchartRef = useRef<((appName: string) => Promise<void>) | null>(null);
  // ref 跟踪最新 selectedApp,供 recording:stopped 监听器使用,避免闭包过期。
  const selectedAppRef = useRef(selectedApp);
  selectedAppRef.current = selectedApp;

  // 监听侧栏入口派发的「tupai:flowchart:selectApp」事件 + sessionStorage 兜底,
  // 这两个入口由「录制历史」快捷入口 / 详情面板「查看流程图」按钮写入。
  // 不再需要 dropdown —— 入口页面由侧栏的录制历史承载,直接定位到具体 app。
  useEffect(() => {
    const applyApp = (appName: string) => {
      if (!appName) return;
      setSelectedApp(appName);
      void handleLoadFlowchartRef.current?.(appName);
    };
    const onSelect = (e: Event) => {
      const detail = (e as CustomEvent<{ appName?: string }>).detail;
      if (detail?.appName) applyApp(detail.appName);
    };
    window.addEventListener('tupai:flowchart:selectApp', onSelect as EventListener);
    // sessionStorage 兜底: 侧栏跳转时 setItem 即可(无论 flowchart tab 是否已挂载)。
    try {
      const app = sessionStorage.getItem('tupai:flowchart:selectedApp');
      if (app) {
        sessionStorage.removeItem('tupai:flowchart:selectedApp');
        applyApp(app);
      }
    } catch { /* ignore */ }
    return () => {
      window.removeEventListener('tupai:flowchart:selectApp', onSelect as EventListener);
    };
  }, []);

  // 监听后端 emit 的 `recording:stopped` 事件:
  // 当 FlowchartScene 已挂载时,若当前选中的 app 与录制完成的 app 一致,自动刷新流程图;
  // 若 selectedApp 为空(从未选中),则自动选中刚录制的 app 并加载。
  // 这是 session:finish-recording → MainNav → dispatchEvent 链路之外的安全网,
  // 确保用户在 FlowchartScene 上"从步骤重新录制"后回到主窗口能立即看到最新节点,
  // 也避免 dispatchEvent/openScene 时序问题导致流程图不刷新。
  useEffect(() => {
    if (!isTauriRuntime()) {
      return;
    }
    let disposed = false;
    let unlisten: (() => void) | null = null;
    void import('@tauri-apps/api/event')
      .then(({ listen }) =>
        listen<{ appName?: string | null }>('recording:stopped', (event) => {
          if (disposed) return;
          const appName = event.payload?.appName;
          if (!appName) return;
          const current = selectedAppRef.current;
          log.info('recording:stopped received', { appName, currentSelected: current });
          if (current === appName) {
            // 当前已选中该 app,直接刷新(录制完成后 flowchart.json 已落库)
            void handleLoadFlowchartRef.current?.(appName);
          } else if (!current) {
            // 从未选中 app,自动选中刚录制的 app 并加载
            setSelectedApp(appName);
            void handleLoadFlowchartRef.current?.(appName);
          }
          // 录制结束 → 详情面板回退为普通模式(选中的锚点节点已不适用于录制场景)
          setBaselineNodeId(null);
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
  }, []);

  // 轮询后端录制状态，驱动 header 上的录制徽章。
  // 与 FloatingWindow 的轮询独立：两边各自显示，互不依赖。
  useEffect(() => {
    if (!isTauriRuntime()) return;
    let cancelled = false;
    const tick = async () => {
      try {
        const next = await recordingGetStatus();
        if (!cancelled) {
          setRecordingState(next.state || 'idle');
          setRecordingActionCount(next.action_count ?? 0);
        }
      } catch { /* 忽略：非 Tauri 或后端未就绪 */ }
    };
    void tick();
    const timer = window.setInterval(() => void tick(), 1000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, []);

  // 加载选定软件的流程图
  const handleLoadFlowchart = useCallback(async (appName: string) => {
    if (!appName) {
      setFlowchart(null);
      setEditedFlowchart(null);
      return;
    }
    // 切换 app 时必须重置编辑表单 + 清除选中节点/基线节点,避免上一个 app 的
    // 编辑态污染新 app 的 hint(项目硬约束)。
    setSelectedNodeId(null);
    setBaselineNodeId(null);
    setEditMode(false);
    log.info('handleLoadFlowchart start', { appName });
    setLoading(true);
    setError(null);
    // 切换 app 前递增 fitViewNonce,确保流程图加载完后视图层会主动 fitView 到标准级别。
    // 关键: 即便用户从 appA 切回 appA(同 app 但需要刷新数据,如录制完成后),也要 fitView,
    // 因为 React 在 flowchart 引用变化时不会自动重置缩放,容易让用户看到意外的视角。
    setFitViewNonce((n) => n + 1);
    try {
      const fc = await recordingLoad(appName);
      const nodeCount = fc?.nodes?.length ?? 0;
      log.info('handleLoadFlowchart loaded', { appName, nodeCount, hasFlowchart: !!fc });
      if (fc && fc.nodes && fc.nodes.length > 0) {
        setFlowchart(fc);
        setEditedFlowchart(null);
        // 检测小循环：仅对未忽略的 app 弹窗确认
        if (!dismissedLoopAppsRef.current.has(appName)) {
          const loops = detectSmallLoops(fc);
          setLoopCandidates(loops);
        } else {
          setLoopCandidates([]);
        }
      } else {
        setFlowchart(null);
        setEditedFlowchart(null);
        setError(t('flowchartScene.noFlowchartData'));
      }
    } catch (e: any) {
      const msg = e?.message || String(e);
      log.error('Load flowchart failed', e);
      setError(msg);
      setFlowchart(null);
    } finally {
      setLoading(false);
    }
  }, [t]);

  // 同步 ref 供 useEffect 调用（避免 TDZ）
  handleLoadFlowchartRef.current = handleLoadFlowchart;

  // 执行流程图：与 AutomationScene 的「执行」对齐 —— 拉起目标软件 + 隐藏主界面 +
  // 打开 steps 浮窗（id 以 steps- 开头，FloatingWindow 解析为 steps kind）。浮窗内
  // 可「依次执行全部」/单步/暂停/停止，关闭浮窗时 fw_finish_session 恢复主窗口。
  //
  // 编辑模式下先保存：浮窗通过 recordingLoad(appName) 从后端读流程图，不保存的话
  // 浮窗跑的是旧版本而非用户刚编辑的内容。
  const handleExecute = useCallback(async () => {
    if (!selectedApp) return;
    const fc = editedFlowchart || flowchart;
    if (!fc) return;
    setBusy(true);
    setError(null);
    try {
      // 编辑模式下先保存，确保浮窗加载到最新流程图
      if (editedFlowchart) {
        try {
          await saveFlowchart(selectedApp, fc.title || selectedApp, fc);
        } catch (e: any) {
          log.warn('auto-save before execute failed', e);
        }
      }
      // 1) 拉起目标软件
      try {
        await launchSoftware(selectedApp);
      } catch (e: any) {
        log.warn('launch software failed', e);
      }
      // 2) 隐藏主窗口
      try { await fwHideMainWindow(); } catch { /* 忽略：部分环境无主窗口 */ }
      // 3) 打开 steps 浮窗（id 以 steps- 开头，FloatingWindow 解析为 steps kind）
      await fwOpen({
        id: `steps-${selectedApp}`,
        title: t('floatingWindow.stepsTitle', { name: selectedApp }),
        width: 300,
        height: 420,
        minWidth: 240,
        minHeight: 300,
        payload: { appName: selectedApp },
      });
    } catch (e: any) {
      const msg = e?.message || String(e);
      log.error('Execute flowchart failed', e);
      setError(msg);
      // 出错时确保主窗口恢复
      try { await fwShowMainWindow(); } catch { /* ignore */ }
    } finally {
      setBusy(false);
    }
  }, [selectedApp, editedFlowchart, flowchart, t]);

  // 确认合并小循环：合并后替换当前流程图并清空候选
  const handleMergeLoops = useCallback(() => {
    if (!flowchart || loopCandidates.length === 0) return;
    const merged = mergeSmallLoops(flowchart, loopCandidates);
    setFlowchart(merged);
    setEditedFlowchart(null);
    setLoopCandidates([]);
    setFitViewNonce((n) => n + 1);
    log.info('Small loops merged', { app: selectedApp, count: loopCandidates.length });
    notificationService.success(t('flowchartScene.loopsMerged', { count: loopCandidates.length }));
  }, [flowchart, loopCandidates, selectedApp, t]);

  // 忽略小循环提示：记录到 dismissed 集合，当前 app 不再弹窗
  const handleDismissLoops = useCallback(() => {
    setDismissedLoopApps((prev) => new Set(prev).add(selectedApp));
    setLoopCandidates([]);
  }, [selectedApp]);

  // 保存（编辑后的）流程图到 recording::store（自动去重合并），并持久化
  const handleSave = useCallback(async () => {
    if (!selectedApp) return;
    const fc = editedFlowchart || flowchart;
    if (!fc) return;
    setBusy(true);
    setError(null);
    try {
      await saveFlowchart(selectedApp, fc.title || selectedApp, fc);
      log.info('Flowchart saved', { app: selectedApp });
      notificationService.success(t('flowchartScene.saved'));
    } catch (e: any) {
      const msg = e?.message || String(e);
      log.error('Save flowchart failed', e);
      setError(msg);
      notificationService.error(msg);
    } finally {
      setBusy(false);
    }
  }, [selectedApp, editedFlowchart, flowchart, t]);



  // 在流程图任意节点上单步执行（点击节点详情面板的"执行此步骤"）。
  // 只执行该节点代表的动作,不触发完整流程图执行。
  // @ts-expect-error TS6133 — reserved for future node detail panel
  const handleExecuteStep = useCallback(async (node: any) => {
    if (!node || busy) return;
    setBusy(true);
    try {
      const res = await executeFlowchartStep(node);
      if (res?.ok) {
        notificationService.success(t('flowchartScene.nodePanel.execute'));
      } else {
        notificationService.error(res?.error || 'step failed');
      }
    } catch (e: any) {
      notificationService.error(e?.message || String(e));
    } finally {
      setBusy(false);
    }
  }, [busy, t]);

  // 从选中的步骤开始录制：保留 flowchart 中该节点及之前的内容作为锚点,
  // 启动新录制捕获后续操作,新动作会通过 store::merge_flowcharts 去重追加
  // 到 flowchart.json（不覆盖前序节点）。语义上"从此步骤往后重新录"。
  //
  // 使用录制悬浮窗基础能力：隐藏主窗口 + 拉起目标软件 + 打开 recorder 浮窗。
  // 与 AutomationScene 的「录制」按钮和工具栏的「录制」按钮行为一致。
  // @ts-expect-error TS6133 — reserved for future node detail panel
  const handleRecordFromStep = useCallback(async (node: any) => {
    if (!node || !selectedApp || startingRecord) return;
    setStartingRecord(true);
    try {
      // 1) 拉起目标软件(用户基线操作目标),失败不阻塞
      try {
        await launchSoftware(selectedApp).catch(() => {});
      } catch { /* ignore */ }
      // 2) 隐藏主窗口
      try { await fwHideMainWindow(); } catch { /* 忽略：部分环境无主窗口 */ }
      // 3) 启动录制
      try {
        await recordingStart(selectedApp);
      } catch (e: any) {
        notificationService.error(e?.message || String(e));
        // 启动录制失败时恢复主窗口
        try { await fwShowMainWindow(); } catch { /* ignore */ }
        return;
      }
      // 4) 设置基线节点 → 详情面板自动切到录制控制台模式
      setBaselineNodeId(node.id);
      setSelectedNodeId(node.id);
      // 5) 打开 recorder 浮窗（id 以 recorder- 开头，FloatingWindow 解析为 recorder kind）
      try {
        await fwOpen({
          id: `recorder-${selectedApp}`,
          title: t('floatingWindow.recorderTitle', { name: selectedApp }),
          width: 280,
          height: 180,
          minWidth: 240,
          minHeight: 140,
          payload: { appName: selectedApp },
        });
      } catch (e: any) {
        log.warn('Failed to open recorder floating window', e);
        // 浮窗打开失败不阻塞录制，但恢复主窗口让用户能看到录制状态
        try { await fwShowMainWindow(); } catch { /* ignore */ }
      }
    } finally {
      setStartingRecord(false);
    }
  }, [selectedApp, startingRecord, t]);

  // 停止录制 → 回退详情面板为普通模式
  const handleStopRecordingFromPanel = useCallback(async () => {
    if (!selectedApp) return;
    setBusy(true);
    try {
      await recordingStop(selectedApp);
      // 后端会发 recording:stopped 事件,那边会清 baselineNodeId 并刷新流程图
    } catch (e: any) {
      notificationService.error(e?.message || String(e));
    } finally {
      setBusy(false);
    }
  }, [selectedApp]);

  // 暂停 / 恢复录制（直接在面板里控制，无需浮窗）
  // @ts-expect-error TS6133 — reserved for future node detail panel
  const handleTogglePauseFromPanel = useCallback(async () => {
    try {
      if (recordingState === 'paused') {
        await recordingResume();
      } else {
        await recordingPause();
      }
    } catch (e: any) {
      notificationService.error(e?.message || String(e));
    }
  }, [recordingState]);

  // 工具栏「录制」按钮：直接从当前选中 app 开始新录制，无需切回 AutomationScene。
  // 使用录制悬浮窗基础能力：隐藏主窗口 + 拉起目标软件 + 打开 recorder 浮窗。
  // 区别是 baseline 节点为流程图最后一个节点（接续录制），或 null（全新录制）。
  const handleStartRecord = useCallback(async () => {
    if (!selectedApp || startingRecord || recordingState !== 'idle') return;
    setStartingRecord(true);
    try {
      // 1) 拉起目标软件
      try {
        await launchSoftware(selectedApp).catch(() => {});
      } catch { /* ignore */ }
      // 2) 隐藏主窗口
      try { await fwHideMainWindow(); } catch { /* 忽略：部分环境无主窗口 */ }
      // 3) 启动录制
      try {
        await recordingStart(selectedApp);
      } catch (e: any) {
        notificationService.error(e?.message || String(e));
        try { await fwShowMainWindow(); } catch { /* ignore */ }
        return;
      }
      // 4) 设置基线节点为当前流程图最后一个节点（接续录制）
      //    或 null（全新录制）。
      const currentFlow = editedFlowchart || flowchart;
      const lastNode = currentFlow?.nodes?.[currentFlow.nodes.length - 1];
      if (lastNode?.id) {
        setBaselineNodeId(lastNode.id);
        setSelectedNodeId(lastNode.id);
      } else {
        setBaselineNodeId(null);
      }
      // 4) 通知侧栏更新录制历史
      try {
        const raw = localStorage.getItem('tupai:recording_history');
        const arr = raw ? JSON.parse(raw) : [];
        if (!arr.find((x: any) => x.appName === selectedApp)) {
          arr.unshift({ appName: selectedApp, ts: Date.now() });
          localStorage.setItem('tupai:recording_history', JSON.stringify(arr.slice(0, 20)));
          window.dispatchEvent(new CustomEvent('tupai:recording-history-updated', { detail: { appName: selectedApp } }));
        }
      } catch { /* ignore */ }
      // 5) 打开 recorder 浮窗（id 以 recorder- 开头，FloatingWindow 解析为 recorder kind）
      try {
        await fwOpen({
          id: `recorder-${selectedApp}`,
          title: t('floatingWindow.recorderTitle', { name: selectedApp }),
          width: 280,
          height: 180,
          minWidth: 240,
          minHeight: 140,
          payload: { appName: selectedApp },
        });
      } catch (e: any) {
        log.warn('Failed to open recorder floating window', e);
        try { await fwShowMainWindow(); } catch { /* ignore */ }
      }
    } finally {
      setStartingRecord(false);
    }
  }, [selectedApp, startingRecord, recordingState, editedFlowchart, flowchart, t]);

  // UI text is now driven by i18n t() function

  const isRecording = recordingState === 'recording';
  const isPaused = recordingState === 'paused';
  const nodeCount = (editedFlowchart || flowchart)?.nodes?.length ?? 0;
  const stepCount = Math.max(0, nodeCount - 2); // 减去 start/end 骨架节点

  return (
    <div className="flowchart-scene">
      <div className="flowchart-scene__header">
        <h2 className="flowchart-scene__title">
          {selectedApp ? selectedApp : t('flowchartScene.title')}
        </h2>
        <div className="flowchart-scene__toolbar">
          {/* 录制状态徽章：录制中/暂停时显示 */}
          {(isRecording || isPaused) && (
            <span className={`flowchart-scene__recording-badge${isRecording ? ' is-recording' : ' is-paused'}`}>
              <span className="flowchart-scene__recording-dot" aria-hidden="true" />
              {isRecording
                ? t('floatingWindow.recording', { count: recordingActionCount })
                : t('floatingWindow.recordingPaused', { count: recordingActionCount })}
            </span>
          )}
          {/* 录制中显示 Stop 按钮(全新录制时 baselineNodeId 可能 null,NodeDetailsPanel 不渲染,无 Stop 入口会卡死录制) */}
          {(isRecording || isPaused) && (
            <button
              className="flowchart-scene__btn flowchart-scene__btn--stop"
              onClick={() => void handleStopRecordingFromPanel()}
              disabled={busy}
              title={t('floatingWindow.stopRecording')}
            >
              <Square size={11} />
              {t('floatingWindow.stopRecording')}
            </button>
          )}
          {/* 「录制」按钮：选中 app 后即可直接开始录制，无需切回 AutomationScene */}
          {selectedApp && !isRecording && !isPaused && (
            <button
              className="flowchart-scene__btn flowchart-scene__btn--record"
              onClick={() => void handleStartRecord()}
              disabled={busy || startingRecord}
              title={t('floatingWindow.startRecording')}
            >
              <Circle size={10} style={{ color: '#ff5d5d' }} />
              {startingRecord ? '…' : t('floatingWindow.startRecording')}
            </button>
          )}
          {flowchart && !loading && (
            <>
              <button
                className="flowchart-scene__btn"
                onClick={() => setEditMode(!editMode)}
                disabled={busy}
              >
                {editMode ? <Eye size={13} /> : <Edit size={13} />}
                {editMode ? t('flowchartScene.view') : t('flowchartScene.edit')}
              </button>
              <button
                className="flowchart-scene__btn"
                onClick={() => void handleSave()}
                disabled={busy}
                title={t('flowchartScene.save')}
              >
                {t('flowchartScene.save')}
              </button>
              <button
                className="flowchart-scene__btn flowchart-scene__btn--primary"
                onClick={() => void handleExecute()}
                disabled={busy}
              >
                <Play size={13} />
                {t('flowchartScene.execute')}
              </button>
            </>
          )}
        </div>
      </div>

      {/* 统计信息栏：展示节点数/步数 */}
      {flowchart && !loading && stepCount > 0 && (
        <div className="flowchart-scene__stats">
          <span className="flowchart-scene__stat-item">
            <CircleDot size={12} />
            {t('flowchartScene.stepCount', { count: stepCount })}
          </span>
        </div>
      )}

      {error && (
        <div style={{ padding: '4px 16px', color: '#ef4444', fontSize: 12, display: 'flex', alignItems: 'center', gap: 4 }}>
          <AlertTriangle size={12} />
          {error}
        </div>
      )}

      {/* 小循环合并确认条：检测到重复子序列时提示用户确认是否合并 */}
      {loopCandidates.length > 0 && !editMode && (
        <div className="flowchart-scene__loop-banner">
          <GitMerge size={14} />
          <span className="flowchart-scene__loop-banner-text">
            {t('flowchartScene.loopDetected', { count: loopCandidates.length, repeats: loopCandidates[0].repeats })}
          </span>
          <button
            className="flowchart-scene__btn flowchart-scene__btn--primary flowchart-scene__loop-banner-merge"
            onClick={handleMergeLoops}
          >
            {t('flowchartScene.mergeLoops')}
          </button>
          <button
            className="flowchart-scene__loop-banner-dismiss"
            onClick={handleDismissLoops}
            title={t('flowchartScene.dismissLoops')}
          >
            <X size={13} />
          </button>
        </div>
      )}

      <div className="flowchart-scene__body">
        <div className="flowchart-scene__canvas">
        {!selectedApp ? (
          <div className="flowchart-scene__empty">
            <div className="icon">◌</div>
            <span>{t('flowchartScene.chooseFromSidebar')}</span>
          </div>
        ) : loading ? (
          <div className="flowchart-scene__empty">
            <div className="spinner" style={{ width: 24, height: 24, border: '2px solid rgba(255,255,255,0.1)', borderTopColor: '#3b82f6', borderRadius: '50%', animation: 'automation-spin 0.8s linear infinite' }} />
            <span>{t('flowchartScene.loading')}</span>
          </div>
        ) : flowchart ? (
          editMode ? (
            <EditableFlowchartView
              flowchart={editedFlowchart || flowchart}
              onChange={(fc) => setEditedFlowchart(fc)}
              locale={locale}
              fitViewKey={fitViewNonce}
            />
          ) : (
            <FlowchartView
              flowchart={flowchart}
              selectedNodeId={selectedNodeId}
              onSelectNode={setSelectedNodeId}
              locale={locale}
              fitViewKey={fitViewNonce}
            />
          )
        ) : error ? null : (
          // 选中了 app 但没有流程图数据：引导用户开始录制
          <div className="flowchart-scene__empty">
            <div className="icon">◌</div>
            <span>{t('flowchartScene.noData')}</span>
            <button
              className="flowchart-scene__btn flowchart-scene__btn--record"
              onClick={() => void handleStartRecord()}
              disabled={busy || startingRecord}
              style={{ marginTop: 12 }}
            >
              <Circle size={10} style={{ color: '#ff5d5d' }} />
              {startingRecord ? '…' : t('floatingWindow.startRecording')}
            </button>
          </div>
        )}
        </div>
      </div>
    </div>
  );
};

export default FlowchartScene;
