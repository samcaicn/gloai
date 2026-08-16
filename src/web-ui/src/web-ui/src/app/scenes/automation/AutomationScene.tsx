/**
 * AutomationScene — 自动化页面（master-detail 布局）。
 *
 * 功能：
 *   1. 扫描本地已安装软件 → 左侧卡片网格
 *   2. 点击软件卡片 → 右侧操作面板
 *   3. 右侧操作：
 *      - 「录制」→ 隐藏主窗口 + 拉起目标软件 + 打开 recorder 浮窗（fwOpen id=recorder-…）
 *      - 「执行」→ 拉起目标软件 + 打开 steps 浮窗（fwOpen id=steps-…）— 浮窗内逐节点单步按钮
 *      - 「查看流程图」→ 跳转 FlowchartScene 加载/编辑节点（去重持久化）
 *   4. 关闭浮窗 → 后端 fw_finish_session 触发主窗口恢复 + 加载该软件流程图
 */

import React, { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import { RefreshCw, Search, AlertTriangle, Activity, ListTree, Layers, Clock } from 'lucide-react';
import {
  recordingLoad, getAppStats, recordingStart, recordingStop,
  scanInstalledSoftware, launchSoftware,
  fwOpen, fwHideMainWindow, fwShowMainWindow,
  analyzeRecording, getAnalysisStatus, refineAnalysis, publishAnalyzedSkill,
  type AnalysisResult,
} from '@/infrastructure/api/tupai';
import { useI18n } from '@/infrastructure/i18n/hooks/useI18n';
import { useSceneManager } from '../../hooks/useSceneManager';
import { updateSceneTabLabel } from '@/app/stores/sceneStore';
import { createLogger } from '@/shared/utils/logger';
import './AutomationScene.scss';

const log = createLogger('AutomationScene');

// ── 类型定义 ──
interface SoftwareItem {
  name: string;
  exePath?: string;
  installLocation?: string;
}

interface LogEntry {
  ts: string;
  msg: string;
  level: 'info' | 'error' | 'warn';
}

interface AppStats {
  appName: string;
  totalBatches: number;
  totalActions: number;
  firstRecordDate: string | null;
  lastRecordDate: string | null;
}

// ── 辅助函数 ──
function fallbackEmoji(name: string): string {
  const n = (name || '').toLowerCase();
  if (/chrome|edge|firefox|brave|browser|safari|opera/.test(n)) return '🌐';
  if (/wechat|weixin|微信/.test(n)) return '💬';
  if (/dingtalk|钉钉/.test(n)) return '📌';
  if (/feishu|lark|飞书/.test(n)) return '🐦';
  if (/qq/.test(n)) return '🐧';
  if (/code|vscode|ide|studio|eclipse|jetbrains|intellij/.test(n)) return '🛠';
  if (/office|word|excel|powerpoint|wps/.test(n)) return '📊';
  if (/notepad|text|editor/.test(n)) return '📝';
  if (/player|video|music|spotify|vlc|potplayer/.test(n)) return '🎬';
  if (/photoshop|illustrator|design|cad|drawing/.test(n)) return '🎨';
  if (/terminal|cmd|powershell|shell/.test(n)) return '⚡';
  if (/vmware|virtualbox|docker|container/.test(n)) return '📦';
  if (/python|java|node|ruby|golang|rust/.test(n)) return '🗜';
  if (/game|steam|epic|battle/.test(n)) return '🎮';
  if (/cloud|drive|dropbox|onedrive|baidu/.test(n)) return '☁️';
  if (/security|antivirus|firewall|360/.test(n)) return '🛡';
  if (/mail|outlook|foxmail/.test(n)) return '📧';
  if (/pdf|reader|acrobat/.test(n)) return '📕';
  if (/compress|zip|rar|7-?zip|bandizip/.test(n)) return '🗜';
  if (/meeting|zoom|teams|webex/.test(n)) return '🎥';
  if (/translate|dictionary|bing.*dict/.test(n)) return '🌐';
  if (/screenshot|snip|capture/.test(n)) return '📷';
  if (/download|thunder|迅雷|motrix/.test(n)) return '⬇️';
  if (/input|sogou|pinyin|ime/.test(n)) return '⌨️';
  return '📦';
}

const LS_USAGE_KEY = 'trae_software_usage';
const LS_CACHE_KEY = 'trae_software_cache';

const AutomationScene: React.FC = () => {
  const { t } = useI18n('common');
  const { openScene } = useSceneManager();

  // tRef for callbacks that should not re-run on language change
  const tRef = useRef(t);
  tRef.current = t;

  // 软件列表
  const [software, setSoftware] = useState<SoftwareItem[]>(() => {
    try {
      const raw = localStorage.getItem(LS_CACHE_KEY);
      return raw ? JSON.parse(raw) : [];
    } catch { return []; }
  });
  const [loading, setLoading] = useState(true);
  const [loadCount, setLoadCount] = useState(0);
  const [error, setError] = useState('');
  const [search, setSearch] = useState('');

  // 右侧选中软件
  const [selected, setSelected] = useState<SoftwareItem | null>(null);
  const [selectedStats, setSelectedStats] = useState<AppStats | null>(null);
  const [selectedFlowchart, setSelectedFlowchart] = useState<any | null>(null);
  const [goal, setGoal] = useState('');

  // 运行/会话状态
  const [sessionBusy, setSessionBusy] = useState(false);
  const [logs, setLogs] = useState<LogEntry[]>([]);

  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;
    return () => { mountedRef.current = false; };
  }, []);

  // 使用次数
  const [usage, setUsage] = useState<Record<string, number>>(() => {
    try { return JSON.parse(localStorage.getItem(LS_USAGE_KEY) || '{}'); } catch { return {}; }
  });

  const addLog = useCallback((msg: string, level: 'info' | 'error' | 'warn' = 'info') => {
    const ts = new Date().toLocaleTimeString('zh-CN', { hour12: false });
    setLogs(prev => [...prev.slice(-60), { ts, msg, level }]);
  }, []);

  // 加载本地软件
  const loadSoftware = useCallback(async () => {
    setLoading(true);
    setError('');
    setLoadCount(0);
    if (typeof window === 'undefined' || !(window as any).__TAURI_INTERNALS__) {
      setError(tRef.current('automationScene.tauriNotReady'));
      setLoading(false);
      setSoftware([]);
      return;
    }
    try {
      const list = await scanInstalledSoftware();
      if (!mountedRef.current) return;
      const fresh = Array.isArray(list) ? list : [];
      const merged: SoftwareItem[] = [];
      let count = 0;
      const BATCH_SIZE = 10;
      for (const sw of fresh) {
        merged.push(sw);
        count++;
        if (!mountedRef.current) break;
        if (count % BATCH_SIZE === 0 || count === fresh.length) {
          setSoftware([...merged]);
          setLoadCount(count);
          await new Promise(r => setTimeout(r, 20));
        }
      }
      try { localStorage.setItem(LS_CACHE_KEY, JSON.stringify(fresh)); } catch { /* empty */ }
    } catch (e: any) {
      if (mountedRef.current) {
        const msg = e?.message || String(e);
        if (msg.includes('Cannot read properties of undefined') && msg.includes('invoke')) {
          setError(tRef.current('automationScene.tauriNotReady'));
        } else {
          setError(msg || tRef.current('automationScene.getSoftwareFailed'));
        }
        setSoftware([]);
      }
    } finally {
      if (mountedRef.current) setLoading(false);
    }
  }, []);

  useEffect(() => { void loadSoftware(); }, [loadSoftware]);

  const bumpUsage = useCallback((swName: string) => {
    setUsage(prev => {
      const next = { ...prev, [swName]: (prev[swName] || 0) + 1 };
      try { localStorage.setItem(LS_USAGE_KEY, JSON.stringify(next)); } catch { /* empty */ }
      return next;
    });
  }, []);

  // 点击软件卡片 → 选中并加载统计
  const handleSoftwareClick = useCallback(async (sw: SoftwareItem) => {
    bumpUsage(sw.name);
    setSelected(sw);
    setSelectedStats(null);
    setSelectedFlowchart(null);
    setGoal('');
    // 动态更新场景标签：显示具体软件名称
    updateSceneTabLabel('automation', sw.name);

    try {
      const stats = await getAppStats(sw.name);
      let flowchart = null;
      if (stats && stats.totalActions > 0) {
        const fc = await recordingLoad(sw.name);
        if (fc && fc.nodes && fc.nodes.length > 2) {
          flowchart = fc;
        }
      }
      if (!mountedRef.current) return;
      setSelectedStats(stats);
      setSelectedFlowchart(flowchart);
    } catch {
      if (!mountedRef.current) return;
      setSelectedStats(null);
    }
  }, [bumpUsage]);

  const closeDetail = useCallback(() => {
    if (sessionBusy) return;
    setSelected(null);
    setSelectedStats(null);
    setSelectedFlowchart(null);
    setGoal('');
    // 关闭详情面板 → 重置标签为通用 i18n 标题
    updateSceneTabLabel('automation', t('scenes.automation'));
  }, [sessionBusy, t]);

  // 推送录制历史到 localStorage，供 MainNav 动态生成"最近录制"入口。
  // 事件 detail.appName 携带刚录制的软件名,订阅方（AutomationScene 自身、
  // 其他可能关心录制完成的组件）可据此判断要不要重拉数据。
  const pushRecordingHistory = useCallback((appName: string) => {
    try {
      const raw = localStorage.getItem('tupai:recording_history');
      const history: Array<{ appName: string; ts: number }> = raw ? JSON.parse(raw) : [];
      const filtered = history.filter(h => h?.appName !== appName);
      filtered.unshift({ appName, ts: Date.now() });
      const trimmed = filtered.slice(0, 10);
      localStorage.setItem('tupai:recording_history', JSON.stringify(trimmed));
      window.dispatchEvent(new CustomEvent('tupai:recording-history-updated', { detail: { appName } }));
    } catch { /* ignore */ }
  }, []);

  // 录制完成后立即重拉当前选中软件的 stats + flowchart，让右侧详情面板
  // 反映出新的步骤数（场景已被 hide 期间数据写盘，回到前台时缓存已过期）。
  // 监听「录制历史更新」事件: AutomationScene 自身 pushRecordingHistory 时
  // 会发, MainNav pushRecordingHistory 时也会发 —— 任何录制完成都会触发。
  // 用 selectedRef 闭包最新值避免 useEffect 反复重注册。
  const selectedRef = useRef<SoftwareItem | null>(null);
  selectedRef.current = selected;
  useEffect(() => {
    const refreshIfMatch = (appName?: string | null) => {
      if (!appName) return;
      const cur = selectedRef.current;
      if (!cur || cur.name !== appName) return;
      // 重新拉 stats + flowchart 覆盖旧数据。
      void (async () => {
        try {
          const stats = await getAppStats(appName);
          let flowchart: any = null;
          if (stats && stats.totalActions > 0) {
            const fc = await recordingLoad(appName);
            if (fc && fc.nodes && fc.nodes.length > 2) {
              flowchart = fc;
            }
          }
          if (!mountedRef.current) return;
          setSelectedStats(stats);
          setSelectedFlowchart(flowchart);
        } catch (e: any) {
          log.warn('refresh after recording failed', e);
        }
      })();
    };
    const onHistory = (e: Event) => {
      const detail = (e as CustomEvent<{ appName?: string }>).detail;
      refreshIfMatch(detail?.appName);
    };
    window.addEventListener('tupai:recording-history-updated', onHistory as EventListener);
    return () => {
      window.removeEventListener('tupai:recording-history-updated', onHistory as EventListener);
    };
  }, []);

  // 跳转流程图场景（会话区入口页）
  const openFlowchartScene = useCallback((appName: string) => {
    try {
      sessionStorage.setItem('tupai:flowchart:selectedApp', appName);
    } catch { /* ignore */ }
    window.dispatchEvent(new CustomEvent('tupai:flowchart:selectApp', { detail: { appName } }));
    openScene('flowchart');
  }, [openScene]);

  // 启动录制：拉起目标软件 + 隐藏主窗口 + 调用 start_recording 让录制立即开始 +
  // 打开 recorder 浮窗（id 以 recorder- 开头，FloatingWindow 解析为 recorder kind）。
  // 关闭浮窗时由 fw_finish_session 通知主窗口恢复 + 加载节点。
  //
  // 修复：之前只打开浮窗、浮窗内仍 idle，用户不得不在小窗口里再点一下
  // Start 才能真正开始录制——这与点击「录制」按钮的直觉完全相反，体验割裂。
  // 现在 AutomationScene 先调 start_recording 启全局教学录制器，再开浮窗；
  // 浮窗 mount 后 polling 会读到 state=recording，直接进入录制态。
  const startRecord = useCallback(async () => {
    if (!selected || sessionBusy) return;
    setSessionBusy(true);
    addLog(t('automationScene.logRecord', { name: selected.name }));

    try {
      // 1) 拉起目标软件
      try {
        await launchSoftware(selected.name);
      } catch (e: any) {
        addLog(t('automationScene.logStartFailed', { error: e?.message || String(e) }), 'warn');
      }

      // 2) 隐藏主窗口
      try { await fwHideMainWindow(); } catch { /* 忽略：部分环境无主窗口 */ }

      // 3) 立即开始录制（失败不阻塞打开浮窗 —— 浮窗会让用户看到 idle 状态并可手动重试）
      try {
        await recordingStart(selected.name);
        addLog(t('automationScene.logRecordingStarted', { name: selected.name }));
      } catch (e: any) {
        addLog(t('automationScene.logRecordingStartFailed', { error: e?.message || String(e) }), 'warn');
      }

      // 4) 打开 recorder 浮窗（id 以 recorder- 开头，FloatingWindow 解析为 recorder kind）
      await fwOpen({
        id: `recorder-${selected.name}`,
        title: t('floatingWindow.recorderTitle', { name: selected.name }),
        width: 280,
        height: 180,
        minWidth: 240,
        minHeight: 140,
        payload: { appName: selected.name },
      });

      pushRecordingHistory(selected.name);
    } catch (e: any) {
      addLog(t('automationScene.logStartFailed', { error: e?.message || String(e) }), 'error');
      // 出错时停止可能已启动的录制,避免后台泄漏
      try { await recordingStop(selected.name); } catch { /* ignore */ }
      // 出错时确保主窗口恢复
      try { await fwShowMainWindow(); } catch { /* ignore */ }
    } finally {
      if (mountedRef.current) setSessionBusy(false);
    }
  }, [selected, sessionBusy, addLog, t, pushRecordingHistory]);

  // AI 分析：对已完成的录制进行后处理分析。
  // 借鉴 understudy teach 模式的录制后处理流程，但基于已有的
  // CDP/UIA 事件录制产物（不含视频录制），进行：
  //   1. 意图提取 — AI 从事件流中识别任务标题、目标、参数
  //   2. 路由优化 — 为每个步骤分配 preferred/fallback 路由
  //   3. 澄清对话 — 用户可通过对话精炼分析结果
  //   4. 技能发布 — 发布为三层抽象 SKILL.md（意图 + 路由 + GUI 提示）
  const [analysisResult, setAnalysisResult] = useState<AnalysisResult | null>(null);
  const [analyzing, setAnalyzing] = useState(false);
  const [clarifyInput, setClarifyInput] = useState('');
  const [clarifyReply, setClarifyReply] = useState<string | null>(null);

  const handleAnalyze = useCallback(async () => {
    if (!selected || analyzing) return;
    setAnalyzing(true);
    setAnalysisResult(null);
    setClarifyReply(null);
    addLog(`AI analyzing recording for ${selected.name}...`);
    try {
      await analyzeRecording(selected.name);
      const result = await getAnalysisStatus(selected.name);
      if (result.analysis) {
        setAnalysisResult(result.analysis);
        addLog(`Analysis complete: ${result.analysis.steps.length} steps identified`);
      } else if (result.status.state === 'failed') {
        addLog(`Analysis failed: ${result.status.message ?? 'unknown'}`, 'error');
      }
    } catch (e: any) {
      addLog(`Analysis error: ${e?.message || String(e)}`, 'error');
    } finally {
      if (mountedRef.current) setAnalyzing(false);
    }
  }, [selected, analyzing, addLog]);

  const handleClarify = useCallback(async () => {
    if (!selected || !clarifyInput.trim() || analyzing) return;
    setAnalyzing(true);
    setClarifyReply(null);
    try {
      const result = await refineAnalysis(selected.name, clarifyInput.trim());
      setClarifyReply(result.reply);
      setAnalysisResult(result.analysis);
      setClarifyInput('');
    } catch (e: any) {
      addLog(`Clarify error: ${e?.message || String(e)}`, 'error');
    } finally {
      if (mountedRef.current) setAnalyzing(false);
    }
  }, [selected, clarifyInput, analyzing, addLog]);

  const handlePublishSkill = useCallback(async () => {
    if (!selected || analyzing) return;
    setAnalyzing(true);
    try {
      const result = await publishAnalyzedSkill(selected.name);
      addLog(`Skill published: ${result.skillId} (${result.skillMd.length} chars)`);
      setAnalysisResult(null);
    } catch (e: any) {
      addLog(`Publish error: ${e?.message || String(e)}`, 'error');
    } finally {
      if (mountedRef.current) setAnalyzing(false);
    }
  }, [selected, analyzing, addLog]);

  // 执行：拉起目标软件 + 打开 steps 浮窗（id 以 steps- 开头）。
  // 浮窗内的 StepExecutionWindow 会调用 execute_flowchart_step 单步执行。
  // 关闭浮窗时由 fw_finish_session 恢复主窗口 + 加载节点。
  const startExecute = useCallback(async () => {
    if (!selected || sessionBusy) return;
    setSessionBusy(true);
    const swName = selected.name;
    const goalStr = goal ? ` · ${goal}` : '';
    addLog(t('automationScene.logExecute', { name: swName, goal: goalStr }));

    try {
      // 1) 拉起目标软件
      try {
        await launchSoftware(swName);
        addLog(t('automationScene.logStarted', { name: swName }));
      } catch (e: any) {
        addLog(t('automationScene.logStartFailed', { error: e?.message || String(e) }), 'warn');
      }

      // 2) 隐藏主窗口
      try { await fwHideMainWindow(); } catch { /* 忽略：部分环境无主窗口 */ }

      // 3) steps 浮窗：用户可点逐节点按钮单步执行
      await fwOpen({
        id: `steps-${swName}`,
        title: t('floatingWindow.stepsTitle', { name: swName }),
        width: 300,
        height: 420,
        minWidth: 240,
        minHeight: 300,
        payload: { appName: swName },
      });
    } catch (e: any) {
      addLog(t('automationScene.logStartFailed', { error: e?.message || String(e) }), 'error');
      // 出错时确保主窗口恢复
      try { await fwShowMainWindow(); } catch { /* ignore */ }
    } finally {
      if (mountedRef.current) setSessionBusy(false);
    }
  }, [selected, sessionBusy, goal, addLog, t]);

  // 过滤 + 排序
  const filtered = useMemo(() => {
    const filteredRaw = search.trim()
      ? software.filter(s => (s.name || '').toLowerCase().includes(search.trim().toLowerCase()))
      : software;
    return filteredRaw
      .map((sw, idx) => ({ sw, idx, count: usage[sw.name] || 0 }))
      .sort((a, b) => b.count - a.count || a.idx - b.idx)
      .map(x => x.sw);
  }, [software, search, usage]);

  const lastDateLabel = selectedStats?.lastRecordDate
    ? selectedStats.lastRecordDate
    : (selectedStats ? t('automationScene.noRecordYet') : '—');

  const busy = sessionBusy;

  return (
    <div className="automation-scene">
      <div className="automation-scene__main">
        <div className="automation-scene__header">
          <h2 className="automation-scene__title">{t('automationScene.title')}</h2>
          <span className="automation-scene__subtitle">{t('automationScene.subtitle')}</span>
        </div>

        <div className="automation-scene__toolbar">
          <div className="automation-scene__search">
            <Search size={14} />
            <input
              value={search}
              onChange={e => setSearch(e.target.value)}
              placeholder={t('automationScene.searchPlaceholder')}
              disabled={busy}
            />
          </div>
          <button
            className="automation-scene__refresh-btn"
            onClick={() => void loadSoftware()}
            disabled={loading || busy}
          >
            <RefreshCw size={13} className={loading ? 'spinning' : ''} />
            <span>{t('automationScene.refresh')}</span>
          </button>
        </div>

        <div className="automation-scene__count">
          {loading ? (
            <span>{loadCount > 0 ? t('automationScene.loadedCount', { count: loadCount }) : t('automationScene.loading')}</span>
          ) : error ? (
            <span style={{ color: '#ef4444' }}>{error}</span>
          ) : (
            <span>{filtered.length} / {software.length} {t('automationScene.software')}</span>
          )}
        </div>

        <div className="automation-scene__grid">
          {loading && software.length === 0 ? (
            <div className="automation-scene__empty">
              <div className="spinner" />
              <span>{t('automationScene.scanningSoftware')}</span>
            </div>
          ) : error && software.length === 0 ? (
            <div className="automation-scene__error">
              <AlertTriangle size={20} />
              <span>{error}</span>
              <button className="automation-scene__refresh-btn" onClick={() => void loadSoftware()}>
                {t('automationScene.retry')}
              </button>
            </div>
          ) : filtered.length === 0 ? (
            <div className="automation-scene__empty">
              <span>{t('automationScene.noSoftwareFound')}</span>
            </div>
          ) : (
            filtered.map((sw, i) => {
              const useCount = usage[sw.name] || 0;
              const isActive = selected?.name === sw.name;
              return (
                <button
                  key={`${sw.name}-${i}`}
                  className={`automation-scene__card${isActive ? ' is-active' : ''}`}
                  onClick={() => void handleSoftwareClick(sw)}
                  title={sw.name}
                  disabled={busy}
                >
                  <span className="automation-scene__card-icon">{fallbackEmoji(sw.name)}</span>
                  <span className="automation-scene__card-name">{sw.name}</span>
                  {useCount > 0 && (
                    <span className="automation-scene__card-badge">{useCount}</span>
                  )}
                </button>
              );
            })
          )}
        </div>
      </div>

      <aside className="automation-scene__detail" aria-label={t('automationScene.detailAria')}>
        {!selected ? (
          <div className="automation-scene__detail-empty">
            <ListTree size={28} />
            <span>{t('automationScene.detailEmpty')}</span>
          </div>
        ) : (
          <>
            <div className="automation-scene__detail-header">
              <span className="automation-scene__detail-icon">{fallbackEmoji(selected.name)}</span>
              <span className="automation-scene__detail-name">{selected.name}</span>
              <button
                className="automation-scene__detail-close"
                onClick={closeDetail}
                disabled={busy}
                aria-label={t('automationScene.closeDetail')}
              >×</button>
            </div>

            <div className="automation-scene__stats">
              <div className="automation-scene__stat">
                <ListTree size={16} className="automation-scene__stat-icon" />
                <div className="automation-scene__stat-body">
                  <span className="automation-scene__stat-value">{selectedStats?.totalActions ?? 0}</span>
                  <span className="automation-scene__stat-label">{t('automationScene.statSteps')}</span>
                </div>
              </div>
              <div className="automation-scene__stat">
                <Layers size={16} className="automation-scene__stat-icon" />
                <div className="automation-scene__stat-body">
                  <span className="automation-scene__stat-value">{selectedStats?.totalBatches ?? 0}</span>
                  <span className="automation-scene__stat-label">{t('automationScene.statBatches')}</span>
                </div>
              </div>
              <div className="automation-scene__stat">
                <Activity size={16} className="automation-scene__stat-icon" />
                <div className="automation-scene__stat-body">
                  <span className="automation-scene__stat-value">{usage[selected.name] ?? 0}</span>
                  <span className="automation-scene__stat-label">{t('automationScene.statUsage')}</span>
                </div>
              </div>
              <div className="automation-scene__stat">
                <Clock size={16} className="automation-scene__stat-icon" />
                <div className="automation-scene__stat-body">
                  <span className="automation-scene__stat-value automation-scene__stat-value--sm">{lastDateLabel}</span>
                  <span className="automation-scene__stat-label">{t('automationScene.statLast')}</span>
                </div>
              </div>
            </div>

            <input
              className="automation-scene__goal-input"
              value={goal}
              onChange={e => setGoal(e.target.value)}
              placeholder={t('automationScene.goalPlaceholder')}
              disabled={busy}
            />

            <div className="automation-scene__actions">
              <button
                className="automation-scene__btn automation-scene__btn--primary"
                onClick={() => void startRecord()}
                disabled={busy}
              >
                {busy ? '…' : `● ${t('floatingWindow.startRecording')}`}
              </button>
              <button
                className="automation-scene__btn"
                onClick={() => void startExecute()}
                disabled={busy}
              >
                {busy ? '…' : `▶ ${t('automationScene.start')}`}
              </button>
              <button
                className="automation-scene__btn"
                onClick={() => openFlowchartScene(selected.name)}
                disabled={busy}
              >
                {t('automationScene.viewFlowchart')}
              </button>
            </div>

            {/* AI 分析按钮 — 录制完成后可对录制产物进行 AI 分析 */}
            {selectedStats && selectedStats.totalActions > 0 && (
              <button
                className="automation-scene__btn automation-scene__btn--analyze"
                onClick={() => void handleAnalyze()}
                disabled={busy || analyzing}
              >
                {analyzing ? '⏳ Analyzing...' : '✦ AI Analyze'}
              </button>
            )}

            {/* AI 分析结果 */}
            {analysisResult && (
              <div className="automation-scene__analysis-panel">
                <div className="automation-scene__analysis-header">
                  <strong>{analysisResult.title}</strong>
                  <p>{analysisResult.objective}</p>
                </div>
                {analysisResult.steps.length > 0 && (
                  <div className="automation-scene__analysis-steps">
                    {analysisResult.steps.map((step, i) => (
                      <div key={i} className="automation-scene__analysis-step">
                        <span className={`automation-scene__route-badge automation-scene__route--${step.route}`}>
                          {step.route}
                        </span>
                        <span>{step.summary ?? step.instruction}</span>
                      </div>
                    ))}
                  </div>
                )}
                {analysisResult.openQuestions.length > 0 && (
                  <div className="automation-scene__analysis-questions">
                    {analysisResult.openQuestions.length} open question(s)
                  </div>
                )}
                {clarifyReply && (
                  <div className="automation-scene__clarify-reply">{clarifyReply}</div>
                )}
                <div className="automation-scene__clarify-input">
                  <input
                    type="text"
                    value={clarifyInput}
                    onChange={e => setClarifyInput(e.target.value)}
                    onKeyDown={e => { if (e.key === 'Enter') void handleClarify(); }}
                    placeholder="Refine the task..."
                    disabled={analyzing}
                  />
                  <button
                    onClick={() => void handleClarify()}
                    disabled={analyzing || !clarifyInput.trim()}
                  >Send</button>
                </div>
                <button
                  className="automation-scene__btn automation-scene__btn--primary"
                  onClick={() => void handlePublishSkill()}
                  disabled={analyzing}
                >
                  Publish Skill
                </button>
              </div>
            )}

            {/* 已有录制可快速查看流程图 */}
            {selectedFlowchart && (
              <button
                className="automation-scene__flow-link"
                onClick={() => openFlowchartScene(selected.name)}
              >
                {t('automationScene.recordedSteps', { count: selectedStats?.totalActions ?? 0 })} · {t('automationScene.viewFlowchart')} →
              </button>
            )}

            {/* 日志 */}
            {logs.length > 0 && (
              <div className="automation-scene__logs">
                <h4>{t('automationScene.logs')}</h4>
                {logs.map((l, i) => (
                  <div key={i} className={`automation-scene__log-line log-${l.level}`}>
                    <span className="automation-scene__log-ts">{l.ts}</span>
                    <span>{l.msg}</span>
                  </div>
                ))}
              </div>
            )}
          </>
        )}
      </aside>
    </div>
  );
};

export default AutomationScene;
