/**
 * AutoskillScene — 技能自进化面板。
 *
 * 四个 tab：
 *   1. 待确认草稿：列出 DraftRow，可确认 / 拒绝（确认后从列表移除）
 *   2. 合并候选：列出 MergeCandidate，可生成合并草稿（调用 triggerMerge）
 *   3. 优化候选：列出 OptimizationCandidate，可生成迭代草稿（调用 triggerScan）
 *   4. 会话洞察：列出 InsightRow，可触发会话分析（triggerSessionAnalysis），
 *      展示置信度/证据；采纳生成草稿待 Track B 接入，忽略调用 markInsightConsumed(2)
 *
 * 数据通过 useAutoskillNavStore 统一管理，确认/拒绝/触发后自动刷新。
 * 内联状态提示（无项目级 toast 机制）。
 */

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Sparkles, Check, X, RefreshCw, GitMerge, TrendingUp, AlertTriangle, FileText, Brain, Lightbulb, Eye, ListTodo } from 'lucide-react';
import { useI18n } from '@/infrastructure/i18n/hooks/useI18n';
import { useAutoskillNavStore } from '@/app/components/NavPanel/sections/autoskill/autoskillNavStore';
import type { DraftRow, MergeCandidate, OptimizationCandidate, InsightRow, AnalysisRunSummary } from '@/infrastructure/api/tupai/autoskill';
import * as pipelineApi from '@/infrastructure/api/tupai/pipeline';
import { useSceneStore } from '@/app/stores/sceneStore';
import { subscribe } from '@/infrastructure/api/tupai/events';
import './AutoskillScene.scss';

type TabKey = 'drafts' | 'merge' | 'optimize' | 'insights';

type TFunc = (key: string, options?: Record<string, unknown>) => string;

// 解析 optimization_points JSON 字符串为字符串数组
function parseOptimizationPoints(raw?: string): string[] {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed)) return parsed.map(x => String(x));
    if (typeof parsed === 'string') return [parsed];
    return [];
  } catch {
    return [];
  }
}

// 解析 evidenceJson（完整 EvolutionSignal 序列化字符串）中的 evidence[] 数组。
// 后端约定 EvolutionSignal 含 evidence: Vec<String> 字段。
function parseEvidence(raw?: string): string[] {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw) as { evidence?: unknown };
    const evidence = parsed.evidence;
    if (Array.isArray(evidence)) return evidence.map(x => String(x));
    if (typeof evidence === 'string') return [evidence];
    return [];
  } catch {
    return [];
  }
}

// 将 0..1 的置信度归一化为百分比字符串
function formatConfidence(confidence: number): string {
  const clamped = Math.max(0, Math.min(1, confidence));
  return `${(clamped * 100).toFixed(0)}%`;
}

const AutoskillScene: React.FC = () => {
  const { t } = useI18n('common');

  const drafts = useAutoskillNavStore(s => s.drafts);
  const mergeCandidates = useAutoskillNavStore(s => s.mergeCandidates);
  const optimizationCandidates = useAutoskillNavStore(s => s.optimizationCandidates);
  const sessionInsights = useAutoskillNavStore(s => s.sessionInsights);
  const analysisRunning = useAutoskillNavStore(s => s.analysisRunning);
  const lastAnalysis = useAutoskillNavStore(s => s.lastAnalysis);
  const loading = useAutoskillNavStore(s => s.loading);
  const loadDrafts = useAutoskillNavStore(s => s.loadDrafts);
  const loadMergeCandidates = useAutoskillNavStore(s => s.loadMergeCandidates);
  const loadOptimizationCandidates = useAutoskillNavStore(s => s.loadOptimizationCandidates);
  const loadSessionInsights = useAutoskillNavStore(s => s.loadSessionInsights);
  const confirmDraft = useAutoskillNavStore(s => s.confirmDraft);
  const rejectDraft = useAutoskillNavStore(s => s.rejectDraft);
  const triggerScan = useAutoskillNavStore(s => s.triggerScan);
  const triggerMerge = useAutoskillNavStore(s => s.triggerMerge);
  const triggerSessionAnalysis = useAutoskillNavStore(s => s.triggerSessionAnalysis);
  const markInsightConsumed = useAutoskillNavStore(s => s.markInsightConsumed);

  const [activeTab, setActiveTab] = useState<TabKey>('drafts');
  const [busyId, setBusyId] = useState<string | null>(null);
  const [busyAction, setBusyAction] = useState<string | null>(null);
  const [hint, setHint] = useState<{ type: 'success' | 'error' | 'warning'; msg: string } | null>(null);
  const [previewDraft, setPreviewDraft] = useState<DraftRow | null>(null);

  // showHint 自动清除定时器引用，避免连续提示时旧定时器覆盖新提示
  const hintTimerRef = useRef<number | null>(null);

  // 显示内联提示，3 秒后自动清除
  const showHint = useCallback((type: 'success' | 'error' | 'warning', msg: string) => {
    if (hintTimerRef.current !== null) window.clearTimeout(hintTimerRef.current);
    setHint({ type, msg });
    hintTimerRef.current = window.setTimeout(() => setHint(null), 3000);
  }, []);

  // 组件卸载时清理 hint 定时器，避免在已卸载组件上 setState
  useEffect(() => {
    return () => {
      if (hintTimerRef.current !== null) window.clearTimeout(hintTimerRef.current);
    };
  }, []);

  // 首次进入加载四个 tab 的数据
  useEffect(() => {
    void loadDrafts();
    void loadMergeCandidates();
    void loadOptimizationCandidates();
    void loadSessionInsights();
  }, [loadDrafts, loadMergeCandidates, loadOptimizationCandidates, loadSessionInsights]);

  // 监听后端 evolution://analysis-done 事件 (周期 5min / session_end 触发后 emit),
  // 自动刷新 "会话洞察" tab 数据。后端 try_trigger_analysis 用 AtomicBool 保证
  // 同一时刻只有一个分析在跑, 所以事件不会高频触发。
  useEffect(() => {
    const unsub = subscribe<{ sessionsScanned: number; signalsEmitted: number; reason: string }>(
      'evolution://analysis-done',
      () => {
        void loadSessionInsights();
      },
    );
    return unsub;
  }, [loadSessionInsights]);

  // 监听后端 autoskill://drafts-updated 事件 (30 分钟后台扫描生成新草稿后 emit),
  // 自动刷新所有 tab 数据，确保用户无需手动刷新即可看到最新草稿。
  useEffect(() => {
    const unsub = subscribe<void>('autoskill://drafts-updated', () => {
      void loadDrafts();
      void loadMergeCandidates();
      void loadOptimizationCandidates();
      showHint('success', t('autoskillScene.newDraftsAvailable'));
    });
    return unsub;
  }, [loadDrafts, loadMergeCandidates, loadOptimizationCandidates, showHint, t]);

  // 监听 mesh://skill-received 事件 (Phase 3: 对端确认技能升级后广播,
  // 本机收到后已由后端 UpgradeWriter 落盘, 此处刷新草稿列表并提示用户)。
  useEffect(() => {
    const unsub = subscribe<{
      skillId: string;
      skillKind: string;
      sourceClientId: string;
      applied: boolean;
    }>('mesh://skill-received', (data) => {
      void loadDrafts();
      showHint(
        data.applied ? 'success' : 'error',
        data.applied
          ? t('autoskillScene.skillSyncReceived', { skillId: data.skillId })
          : t('autoskillScene.skillSyncFailed'),
      );
    });
    return unsub;
  }, [loadDrafts, showHint, t]);

  // 确认草稿
  const handleConfirm = useCallback(async (draft: DraftRow) => {
    // 流程图类技能 (automation) 的升级尚未支持：拦截确认，显示友好提示，不调后端。
    // i18n key upgradeNotReady / upgradeNotReadyTooltip 已在三语言 common.json 中就绪。
    if (draft.skillKind === 'automation') {
      showHint('warning', t('autoskillScene.upgradeNotReady'));
      return;
    }
    setBusyId(draft.id);
    setBusyAction('confirm');
    try {
      await confirmDraft(draft.id);
      showHint('success', t('autoskillScene.draftConfirmed', { id: draft.skill_id }));
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      showHint('error', t('autoskillScene.confirmFailed', { error: msg }));
    } finally {
      setBusyId(null);
      setBusyAction(null);
    }
  }, [confirmDraft, showHint, t]);

  // 拒绝草稿
  const handleReject = useCallback(async (draft: DraftRow) => {
    setBusyId(draft.id);
    setBusyAction('reject');
    try {
      await rejectDraft(draft.id);
      showHint('success', t('autoskillScene.draftRejected', { id: draft.skill_id }));
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      showHint('error', t('autoskillScene.rejectFailed', { error: msg }));
    } finally {
      setBusyId(null);
      setBusyAction(null);
    }
  }, [rejectDraft, showHint, t]);

  // 生成合并草稿
  const handleTriggerMerge = useCallback(async () => {
    setBusyAction('merge');
    try {
      const results = await triggerMerge();
      const n = Array.isArray(results) ? results.length : 0;
      showHint('success', t('autoskillScene.mergeGenerated', { count: n }));
      setActiveTab('drafts');
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      showHint('error', t('autoskillScene.mergeFailed', { error: msg }));
    } finally {
      setBusyAction(null);
    }
  }, [triggerMerge, showHint, t]);

  // 生成迭代草稿
  const handleTriggerScan = useCallback(async () => {
    setBusyAction('scan');
    try {
      const results = await triggerScan();
      const n = Array.isArray(results) ? results.length : 0;
      showHint('success', t('autoskillScene.optimizationGenerated', { count: n }));
      setActiveTab('drafts');
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      showHint('error', t('autoskillScene.scanFailed', { error: msg }));
    } finally {
      setBusyAction(null);
    }
  }, [triggerScan, showHint, t]);

  // 触发会话分析
  const handleAnalyzeSessions = useCallback(async () => {
    setBusyAction('analyze');
    try {
      const summary = await triggerSessionAnalysis();
      if (summary) {
        showHint(
          'success',
          t('autoskillScene.analysisDone', {
            sessions: summary.sessionsScanned,
            signals: summary.signalsEmitted,
          }),
        );
      } else {
        // triggerSessionAnalysis 在出错时返回 null（store 已记录 error）
        showHint('error', t('autoskillScene.analysisFailed'));
      }
    } catch (err) {
      // store 吞错, 此 catch 防御性 — triggerSessionAnalysis 出错时返回 null,
      // 正常不会抛到这里; 保留以防 store 实现变更后漏接异常。
      const msg = err instanceof Error ? err.message : String(err);
      showHint('error', t('autoskillScene.analysisFailed', { error: msg }));
    } finally {
      setBusyAction(null);
    }
  }, [triggerSessionAnalysis, showHint, t]);

  // 忽略某条会话洞察（consumed=2）
  const handleDismissInsight = useCallback(async (insight: InsightRow) => {
    setBusyId(insight.signalId);
    setBusyAction('dismiss');
    try {
      await markInsightConsumed(insight.signalId, 2);
      showHint('success', t('autoskillScene.insightDismissed'));
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      showHint('error', t('autoskillScene.dismissFailed', { error: msg }));
    } finally {
      setBusyId(null);
      setBusyAction(null);
    }
  }, [markInsightConsumed, showHint, t]);

  // 从草稿创建流水线
  const handleCreatePipeline = useCallback(async (draft: DraftRow) => {
    setBusyId(draft.id);
    setBusyAction('pipeline');
    try {
      await pipelineApi.pipelineCreate({
        name: `${draft.skill_id} 流水线`,
        scene: 'work',
        steps: [{ skillId: draft.skill_id, skillName: draft.skill_id, params: {}, order: 0 }],
        rounds: 1,
      });
      showHint('success', '流水线已创建，正在跳转…');
      useSceneStore.getState().openScene('pipelines', draft.skill_id);
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      showHint('error', `创建流水线失败: ${msg}`);
    } finally {
      setBusyId(null);
      setBusyAction(null);
    }
  }, [showHint]);

  // 打开预览 modal — 查看技能文本修改详情
  const handlePreview = useCallback((draft: DraftRow) => {
    setPreviewDraft(draft);
  }, []);

  // 关闭预览 modal
  const closePreview = useCallback(() => {
    setPreviewDraft(null);
  }, []);

  // 在预览 modal 里确认升级 — 先 await 后关闭, 失败时保持 modal 打开可重试
  // (handleConfirm 内部失败只 showHint 不抛错, 故 modal 会在 await 后关闭;
  //  busy 状态在 modal 上由 busyId/busyAction 驱动显示)
  const handleConfirmFromPreview = useCallback(async (draft: DraftRow) => {
    await handleConfirm(draft);
    setPreviewDraft(null);
  }, [handleConfirm]);

  // 查看建议 (insight 采纳): 标记已采纳 + 刷新升级建议列表 + 跳到升级建议 tab
  const handleAdoptInsight = useCallback(async (insight: InsightRow) => {
    setBusyId(insight.signalId);
    setBusyAction('adopt');
    try {
      await markInsightConsumed(insight.signalId, 1);
      await loadDrafts();
      setActiveTab('drafts');
      showHint('success', t('autoskillScene.adopt'));
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      showHint('error', t('autoskillScene.adoptFailed', { error: msg }));
    } finally {
      setBusyId(null);
      setBusyAction(null);
    }
  }, [markInsightConsumed, loadDrafts, showHint, t]);

  const tabs = useMemo(() => [
    { key: 'drafts' as TabKey, label: t('autoskillScene.tabDrafts'), count: drafts.length },
    { key: 'merge' as TabKey, label: t('autoskillScene.tabMerge'), count: mergeCandidates.length },
    { key: 'optimize' as TabKey, label: t('autoskillScene.tabOptimize'), count: optimizationCandidates.length },
    { key: 'insights' as TabKey, label: t('autoskillScene.tabInsights'), count: sessionInsights.length },
  ], [drafts.length, mergeCandidates.length, optimizationCandidates.length, sessionInsights.length, t]);

  const handleRefreshAll = useCallback(() => {
    void loadDrafts();
    void loadMergeCandidates();
    void loadOptimizationCandidates();
    void loadSessionInsights();
  }, [loadDrafts, loadMergeCandidates, loadOptimizationCandidates, loadSessionInsights]);

  return (
    <div className="autoskill-scene">
      {/* 标题栏 */}
      <div className="autoskill-scene__header">
        <span className="autoskill-scene__header-icon" aria-hidden="true">
          <Sparkles size={16} />
        </span>
        <h2 className="autoskill-scene__title">
          {t('autoskillScene.title')}
        </h2>
        <button
          className="autoskill-scene__refresh-btn"
          onClick={handleRefreshAll}
          disabled={loading || !!busyAction || analysisRunning}
          title={t('actions.refresh')}
        >
          <RefreshCw size={13} className={loading ? 'is-spinning' : ''} />
        </button>
      </div>

      {/* Tab 栏 */}
      <div className="autoskill-scene__tabs">
        {tabs.map(tab => (
          <button
            key={tab.key}
            type="button"
            className={`autoskill-scene__tab${activeTab === tab.key ? ' is-active' : ''}`}
            onClick={() => setActiveTab(tab.key)}
          >
            <span>{tab.label}</span>
            {tab.count > 0 && (
              <span className="autoskill-scene__tab-count">{tab.count}</span>
            )}
          </button>
        ))}
      </div>

      {/* 内联提示 */}
      {hint && (
        <div className={`autoskill-scene__hint autoskill-scene__hint--${hint.type}`}>
          {hint.type === 'success' ? <Check size={13} /> : <AlertTriangle size={13} />}
          <span>{hint.msg}</span>
        </div>
      )}

      {/* 内容区 */}
      <div className="autoskill-scene__content">
        {activeTab === 'drafts' && (
          <DraftsTab
            drafts={drafts}
            loading={loading}
            busyId={busyId}
            busyAction={busyAction}
            t={t}
            onConfirm={handleConfirm}
            onReject={handleReject}
            onPreview={handlePreview}
            onCreatePipeline={handleCreatePipeline}
          />
        )}
        {activeTab === 'merge' && (
          <MergeTab
            candidates={mergeCandidates}
            loading={loading}
            busyAction={busyAction}
            t={t}
            onTrigger={handleTriggerMerge}
          />
        )}
        {activeTab === 'optimize' && (
          <OptimizeTab
            candidates={optimizationCandidates}
            loading={loading}
            busyAction={busyAction}
            t={t}
            onTrigger={handleTriggerScan}
          />
        )}
        {activeTab === 'insights' && (
          <InsightsTab
            insights={sessionInsights}
            loading={loading}
            analysisRunning={analysisRunning}
            lastAnalysis={lastAnalysis}
            busyId={busyId}
            busyAction={busyAction}
            t={t}
            onAnalyze={handleAnalyzeSessions}
            onDismiss={handleDismissInsight}
            onAdopt={handleAdoptInsight}
          />
        )}
      </div>

      {/* 预览 modal — 查看技能文本修改 + 确认升级 */}
      {previewDraft && (
        <DraftPreviewModal
          draft={previewDraft}
          t={t}
          busy={busyId === previewDraft.id && (busyAction === 'confirm' || busyAction === 'reject' || busyAction === 'pipeline')}
          onCreatePipeline={handleCreatePipeline}
          onConfirm={handleConfirmFromPreview}
          onClose={closePreview}
        />
      )}
    </div>
  );
};

// ── 待确认草稿 tab ──
interface DraftsTabProps {
  drafts: DraftRow[];
  loading: boolean;
  busyId: string | null;
  busyAction: string | null;
  t: TFunc;
  onConfirm: (draft: DraftRow) => void;
  onReject: (draft: DraftRow) => void;
  onPreview: (draft: DraftRow) => void;
  onCreatePipeline?: (draft: DraftRow) => void;
}

function DraftsTab({ drafts, loading, busyId, busyAction, t, onConfirm, onReject, onPreview, onCreatePipeline }: DraftsTabProps) {
  if (loading && drafts.length === 0) {
    return <div className="autoskill-scene__empty">{t('status.loading')}</div>;
  }
  if (drafts.length === 0) {
    return <div className="autoskill-scene__empty">{t('autoskillScene.noSuggestions')}</div>;
  }
  return (
    <div className="autoskill-scene__list">
      {drafts.map(draft => {
        const points = parseOptimizationPoints(draft.optimization_points);
        const evidence = parseEvidence(draft.evidenceJson);
        const scoreDelta = draft.new_score != null && draft.old_score != null
          ? draft.new_score - draft.old_score
          : null;
        const isBusy = busyId === draft.id;
        return (
          <div key={draft.id} className="autoskill-scene__card">
            <div className="autoskill-scene__card-head">
              <span className="autoskill-scene__card-icon"><FileText size={14} /></span>
              <span className="autoskill-scene__card-title">{draft.skill_id}</span>
              <span className="autoskill-scene__card-version">v{draft.draft_version}</span>
            </div>
            <div className="autoskill-scene__card-meta">
              {draft.old_score != null && draft.new_score != null && (
                <span className="autoskill-scene__score">
                  <span className="autoskill-scene__score-old">{draft.old_score.toFixed(2)}</span>
                  <span className="autoskill-scene__score-arrow">→</span>
                  <span className="autoskill-scene__score-new">{draft.new_score.toFixed(2)}</span>
                  {scoreDelta != null && (
                    <span className={`autoskill-scene__score-delta${scoreDelta >= 0 ? ' is-up' : ' is-down'}`}>
                      {scoreDelta >= 0 ? '+' : ''}{scoreDelta.toFixed(2)}
                    </span>
                  )}
                </span>
              )}
              {draft.source && (
                <span className="autoskill-scene__chip">{draft.source}</span>
              )}
              {draft.sourceKind && (
                <span className="autoskill-scene__chip autoskill-scene__chip--source">{draft.sourceKind}</span>
              )}
              {draft.status && (
                <span className="autoskill-scene__chip">{draft.status}</span>
              )}
            </div>
            {points.length > 0 && (
              <ul className="autoskill-scene__points">
                {points.map((p, i) => (
                  <li key={i}>{p}</li>
                ))}
              </ul>
            )}
            {evidence.length > 0 && (
              <details className="autoskill-scene__evidence">
                <summary className="autoskill-scene__evidence-summary">
                  <Lightbulb size={12} />
                  <span>{t('autoskillScene.evidence')}</span>
                  <span className="autoskill-scene__evidence-count">{evidence.length}</span>
                </summary>
                <div className="autoskill-scene__evidence-body">
                  {evidence.map((e, i) => (
                    <blockquote key={i} className="autoskill-scene__evidence-item">{e}</blockquote>
                  ))}
                </div>
              </details>
            )}
            <div className="autoskill-scene__card-actions">
              <button
                type="button"
                className="autoskill-scene__btn autoskill-scene__btn--ghost"
                onClick={() => onPreview(draft)}
                disabled={isBusy}
              >
                <Eye size={12} />
                {t('autoskillScene.previewChanges')}
              </button>
              {onCreatePipeline && draft.status === 'pending_confirm' && (
                <button
                  type="button"
                  className="autoskill-scene__btn autoskill-scene__btn--secondary"
                  onClick={() => onCreatePipeline(draft)}
                  disabled={isBusy}
                >
                  <ListTodo size={12} />
                  {isBusy && busyAction === 'pipeline' ? '…' : '创建流水线'}
                </button>
              )}
              <button
                type="button"
                className="autoskill-scene__btn autoskill-scene__btn--primary"
                onClick={() => onConfirm(draft)}
                disabled={isBusy}
              >
                <Check size={12} />
                {isBusy && busyAction === 'confirm' ? '…' : t('autoskillScene.confirm')}
              </button>
              <button
                type="button"
                className="autoskill-scene__btn autoskill-scene__btn--danger"
                onClick={() => onReject(draft)}
                disabled={isBusy}
              >
                <X size={12} />
                {isBusy && busyAction === 'reject' ? '…' : t('autoskillScene.reject')}
              </button>
            </div>
          </div>
        );
      })}
    </div>
  );
}

// ── 合并候选 tab ──
interface MergeTabProps {
  candidates: MergeCandidate[];
  loading: boolean;
  busyAction: string | null;
  t: TFunc;
  onTrigger: () => void;
}

function MergeTab({ candidates, loading, busyAction, t, onTrigger }: MergeTabProps) {
  if (loading && candidates.length === 0) {
    return <div className="autoskill-scene__empty">{t('status.loading')}</div>;
  }
  if (candidates.length === 0) {
    return <div className="autoskill-scene__empty">{t('autoskillScene.noSuggestions')}</div>;
  }
  const isBusy = busyAction === 'merge';
  return (
    <div className="autoskill-scene__list">
      <div className="autoskill-scene__trigger-bar">
        <button
          type="button"
          className="autoskill-scene__btn autoskill-scene__btn--primary"
          onClick={onTrigger}
          disabled={isBusy}
        >
          <GitMerge size={12} />
          {isBusy ? '…' : t('autoskillScene.generateMergeDraft')}
        </button>
      </div>
      {candidates.map((c, i) => (
        <div key={`${c.action_signature}-${i}`} className="autoskill-scene__card">
          <div className="autoskill-scene__card-head">
            <span className="autoskill-scene__card-icon"><GitMerge size={14} /></span>
            <span className="autoskill-scene__card-title">
              {c.skill_ids.join(' + ')}
            </span>
          </div>
          <div className="autoskill-scene__card-meta">
            <span className="autoskill-scene__chip">
              {t('autoskillScene.similarity')}: {(c.similarity * 100).toFixed(1)}%
            </span>
            <span className="autoskill-scene__chip">
              {t('autoskillScene.runs')}: {c.total_runs}
            </span>
          </div>
          {c.action_signature && (
            <div className="autoskill-scene__signature" title={c.action_signature}>
              {c.action_signature}
            </div>
          )}
        </div>
      ))}
    </div>
  );
}

// ── 优化候选 tab ──
interface OptimizeTabProps {
  candidates: OptimizationCandidate[];
  loading: boolean;
  busyAction: string | null;
  t: TFunc;
  onTrigger: () => void;
}

function OptimizeTab({ candidates, loading, busyAction, t, onTrigger }: OptimizeTabProps) {
  if (loading && candidates.length === 0) {
    return <div className="autoskill-scene__empty">{t('status.loading')}</div>;
  }
  if (candidates.length === 0) {
    return <div className="autoskill-scene__empty">{t('autoskillScene.noSuggestions')}</div>;
  }
  const isBusy = busyAction === 'scan';
  return (
    <div className="autoskill-scene__list">
      <div className="autoskill-scene__trigger-bar">
        <button
          type="button"
          className="autoskill-scene__btn autoskill-scene__btn--primary"
          onClick={onTrigger}
          disabled={isBusy}
        >
          <TrendingUp size={12} />
          {isBusy ? '…' : t('autoskillScene.generateOptimizationDraft')}
        </button>
      </div>
      {candidates.map((c, i) => (
        <div key={`${c.skill_id}-${i}`} className="autoskill-scene__card">
          <div className="autoskill-scene__card-head">
            <span className="autoskill-scene__card-icon"><TrendingUp size={14} /></span>
            <span className="autoskill-scene__card-title">{c.skill_id}</span>
            <span className="autoskill-scene__card-version">v{c.current_version}</span>
          </div>
          <div className="autoskill-scene__card-meta">
            <span className="autoskill-scene__chip">
              {t('autoskillScene.score')}: {c.current_score.toFixed(2)}
            </span>
            <span className="autoskill-scene__chip">
              {t('autoskillScene.runs')}: {c.run_count}
            </span>
            <span className={`autoskill-scene__chip${c.failure_rate > 0.3 ? ' is-warn' : ''}`}>
              {t('autoskillScene.failure')}: {(c.failure_rate * 100).toFixed(1)}%
            </span>
          </div>
          {c.reason && (
            <div className="autoskill-scene__reason" title={c.reason}>{c.reason}</div>
          )}
        </div>
      ))}
    </div>
  );
}

// ── 会话洞察 tab ──
interface InsightsTabProps {
  insights: InsightRow[];
  loading: boolean;
  analysisRunning: boolean;
  lastAnalysis?: AnalysisRunSummary;
  busyId: string | null;
  busyAction: string | null;
  t: TFunc;
  onAnalyze: () => void;
  onDismiss: (insight: InsightRow) => void;
  onAdopt: (insight: InsightRow) => void;
}

function InsightsTab({
  insights,
  loading,
  analysisRunning,
  lastAnalysis,
  busyId,
  busyAction,
  t,
  onAnalyze,
  onDismiss,
  onAdopt,
}: InsightsTabProps) {
  const isAnalyzing = analysisRunning || busyAction === 'analyze';
  return (
    <div className="autoskill-scene__list">
      <div className="autoskill-scene__trigger-bar autoskill-scene__trigger-bar--insights">
        <button
          type="button"
          className="autoskill-scene__btn autoskill-scene__btn--primary"
          onClick={onAnalyze}
          disabled={isAnalyzing}
        >
          <Brain size={12} className={isAnalyzing ? 'is-spinning' : ''} />
          {isAnalyzing ? '…' : t('autoskillScene.analyzeSessions')}
        </button>
        {lastAnalysis && !isAnalyzing && (
          <span className="autoskill-scene__analysis-summary">
            <span className="autoskill-scene__chip">
              {t('autoskillScene.sessionsScanned')}: {lastAnalysis.sessionsScanned}
            </span>
            <span className="autoskill-scene__chip">
              {t('autoskillScene.signalsEmitted')}: {lastAnalysis.signalsEmitted}
            </span>
            {lastAnalysis.degraded && (
              <span className="autoskill-scene__chip is-warn">{t('autoskillScene.degraded')}</span>
            )}
          </span>
        )}
      </div>
      {loading && insights.length === 0 ? (
        <div className="autoskill-scene__empty">{t('status.loading')}</div>
      ) : insights.length === 0 ? (
        <div className="autoskill-scene__empty">{t('autoskillScene.noInsights')}</div>
      ) : (
        insights.map(insight => {
          const evidence = parseEvidence(insight.evidenceJson);
          const isBusy = busyId === insight.signalId;
          const confidencePct = formatConfidence(insight.confidence);
          const confidenceWidth = `${Math.max(0, Math.min(1, insight.confidence)) * 100}%`;
          return (
            <div key={insight.signalId} className="autoskill-scene__card autoskill-scene__card--insight">
              <div className="autoskill-scene__card-head">
                <span className="autoskill-scene__card-icon"><Lightbulb size={14} /></span>
                <span className="autoskill-scene__card-title">
                  {insight.skillId || t('autoskillScene.newSkill')}
                </span>
                {insight.signalType && (
                  <span className="autoskill-scene__chip autoskill-scene__chip--signal-type">
                    {insight.signalType}
                  </span>
                )}
              </div>
              <div className="autoskill-scene__card-meta">
                <span className="autoskill-scene__confidence">
                  <span className="autoskill-scene__confidence-label">
                    {t('autoskillScene.confidence')}
                  </span>
                  <span className="autoskill-scene__confidence-bar">
                    <span
                      className="autoskill-scene__confidence-fill"
                      style={{ width: confidenceWidth }}
                    />
                  </span>
                  <span className="autoskill-scene__confidence-value">{confidencePct}</span>
                </span>
                {insight.sourceKind && (
                  <span className="autoskill-scene__chip autoskill-scene__chip--source">
                    {insight.sourceKind}
                  </span>
                )}
                {insight.skillKind && (
                  <span className="autoskill-scene__chip">{insight.skillKind}</span>
                )}
              </div>
              {insight.suggestedAction && (
                <div className="autoskill-scene__suggested-action">
                  {insight.suggestedAction}
                </div>
              )}
              {evidence.length > 0 && (
                <details className="autoskill-scene__evidence">
                  <summary className="autoskill-scene__evidence-summary">
                    <Lightbulb size={12} />
                    <span>{t('autoskillScene.evidence')}</span>
                    <span className="autoskill-scene__evidence-count">{evidence.length}</span>
                  </summary>
                  <div className="autoskill-scene__evidence-body">
                    {evidence.map((e, i) => (
                      <blockquote key={i} className="autoskill-scene__evidence-item">{e}</blockquote>
                    ))}
                  </div>
                </details>
              )}
              <div className="autoskill-scene__card-actions">
                <button
                  type="button"
                  className="autoskill-scene__btn autoskill-scene__btn--primary"
                  onClick={() => onAdopt(insight)}
                  disabled={isBusy}
                  title={t('autoskillScene.adoptDisabledTooltip')}
                >
                  <Check size={12} />
                  {isBusy && busyAction === 'adopt' ? '…' : t('autoskillScene.adopt')}
                </button>
                <button
                  type="button"
                  className="autoskill-scene__btn autoskill-scene__btn--danger"
                  onClick={() => onDismiss(insight)}
                  disabled={isBusy}
                >
                  <X size={12} />
                  {isBusy && busyAction === 'dismiss' ? '…' : t('autoskillScene.dismiss')}
                </button>
              </div>
            </div>
          );
        })
      )}
    </div>
  );
}

// ── 预览 modal — 查看技能文本修改 + 确认升级 ──
interface DraftPreviewModalProps {
  draft: DraftRow;
  t: TFunc;
  busy: boolean;
  onConfirm: (draft: DraftRow) => void;
  onClose: () => void;
  onCreatePipeline?: (draft: DraftRow) => void;
}

function DraftPreviewModal({ draft, t, busy, onConfirm, onClose, onCreatePipeline }: DraftPreviewModalProps) {
  const points = parseOptimizationPoints(draft.optimization_points);
  const evidence = parseEvidence(draft.evidenceJson);
  const scoreDelta = draft.new_score != null && draft.old_score != null
    ? draft.new_score - draft.old_score
    : null;
  // 流程图类技能升级尚未支持：置灰确认按钮 + 黄色通知横幅。
  // handleConfirm 内部也会拦截 automation 并显示友好提示，这里禁用按钮避免误触。
  const isAutomation = draft.skillKind === 'automation';
  return (
    <div className="autoskill-scene__modal-overlay" onClick={onClose}>
      <div className="autoskill-scene__modal" onClick={e => e.stopPropagation()}>
        <div className="autoskill-scene__modal-head">
          <span className="autoskill-scene__modal-title">
            <FileText size={14} />
            {draft.skill_id}
          </span>
          <span className="autoskill-scene__card-version">v{draft.draft_version}</span>
          <button type="button" className="autoskill-scene__modal-close" onClick={onClose}>
            <X size={14} />
          </button>
        </div>
        <div className="autoskill-scene__modal-meta">
          {draft.old_score != null && draft.new_score != null && (
            <span className="autoskill-scene__score">
              <span className="autoskill-scene__score-old">{draft.old_score.toFixed(2)}</span>
              <span className="autoskill-scene__score-arrow">→</span>
              <span className="autoskill-scene__score-new">{draft.new_score.toFixed(2)}</span>
              {scoreDelta != null && (
                <span className={`autoskill-scene__score-delta${scoreDelta >= 0 ? ' is-up' : ' is-down'}`}>
                  {scoreDelta >= 0 ? '+' : ''}{scoreDelta.toFixed(2)}
                </span>
              )}
            </span>
          )}
          {draft.source && (
            <span className="autoskill-scene__chip">{draft.source}</span>
          )}
        </div>
        {isAutomation && (
          <div className="autoskill-scene__modal-notice" title={t('autoskillScene.upgradeNotReadyTooltip')}>
            <AlertTriangle size={13} />
            {t('autoskillScene.upgradeNotReady')}
          </div>
        )}
        {points.length > 0 && (
          <ul className="autoskill-scene__points">
            {points.map((p, i) => (
              <li key={i}>{p}</li>
            ))}
          </ul>
        )}
        <div className="autoskill-scene__modal-body">
          <div className="autoskill-scene__modal-body-label">
            <FileText size={12} />
            {t('autoskillScene.skillContent')}
          </div>
          {draft.content ? (
            <pre className="autoskill-scene__modal-content">{draft.content}</pre>
          ) : (
            <div className="autoskill-scene__empty">{t('autoskillScene.noContent')}</div>
          )}
        </div>
        {evidence.length > 0 && (
          <details className="autoskill-scene__evidence">
            <summary>{t('autoskillScene.evidence')}</summary>
            <ul>
              {evidence.map((ev, i) => (
                <li key={i}>{ev}</li>
              ))}
            </ul>
          </details>
        )}
        <div className="autoskill-scene__modal-actions">
          <button
            type="button"
            className="autoskill-scene__btn autoskill-scene__btn--danger"
            onClick={onClose}
            disabled={busy}
          >
            <X size={12} />
            {t('autoskillScene.reject')}
          </button>
          {onCreatePipeline && draft.status === 'pending_confirm' && (
            <button
              type="button"
              className="autoskill-scene__btn autoskill-scene__btn--secondary"
              onClick={() => onCreatePipeline(draft)}
              disabled={busy}
            >
              <ListTodo size={12} />
              创建流水线
            </button>
          )}
          <button
            type="button"
            className="autoskill-scene__btn autoskill-scene__btn--primary"
            onClick={() => onConfirm(draft)}
            disabled={busy || isAutomation}
            title={isAutomation ? t('autoskillScene.upgradeNotReadyTooltip') : undefined}
          >
            <Check size={12} />
            {busy ? '…' : t('autoskillScene.confirm')}
          </button>
        </div>
      </div>
    </div>
  );
}

export default AutoskillScene;
