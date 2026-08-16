/**
 * autoskillNavStore — 自动建议草稿的侧栏缓存 store。
 *
 * 参考 navSkillsStore 模式：将 pending 数量缓存到 localStorage（TTL 2 分钟），
 * 侧栏徽章可即时渲染；草稿完整列表、合并/优化候选由 AutoskillScene 按需加载。
 *
 * confirm / reject / triggerScan / triggerMerge 后会自动 loadDrafts() 刷新并
 * version++，以驱动 MainNav 徽章更新。confirmDraft 成功后还会触发
 * navSkillsStore.loadSkills(true)，让新合并/迭代的技能出现在技能列表中。
 */

import { create } from 'zustand';
import {
  listPendingDrafts,
  listMergeCandidates,
  listCandidates,
  confirmDraft as apiConfirmDraft,
  rejectDraft as apiRejectDraft,
  triggerScan as apiTriggerScan,
  triggerMerge as apiTriggerMerge,
  listSessionInsights as apiListSessionInsights,
  listSignals as apiListSignals,
  triggerSessionAnalysis as apiTriggerSessionAnalysis,
  markSignalConsumed as apiMarkSignalConsumed,
} from '@/infrastructure/api/tupai/autoskill';
import type {
  DraftRow,
  MergeCandidate,
  OptimizationCandidate,
  DraftResult,
  InsightRow,
  SignalRow,
  AnalysisRunSummary,
} from '@/infrastructure/api/tupai/autoskill';
import { useNavSkillsStore } from '../skills/navSkillsStore';
import { createLogger } from '@/shared/utils/logger';

const log = createLogger('autoskillNavStore');

const CACHE_KEY = 'tupai:autoskill:pendingCount';
const CACHE_TTL = 2 * 60 * 1000; // 2 分钟

interface CachedCount {
  count: number;
  timestamp: number;
}

function readCountCache(): CachedCount | null {
  try {
    const raw = localStorage.getItem(CACHE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as CachedCount;
    if (typeof parsed.count !== 'number') return null;
    return parsed;
  } catch {
    return null;
  }
}

function writeCountCache(count: number) {
  try {
    const payload: CachedCount = { count, timestamp: Date.now() };
    localStorage.setItem(CACHE_KEY, JSON.stringify(payload));
  } catch (err) {
    log.warn('Failed to persist autoskill pending count cache', { error: err });
  }
}

interface AutoskillNavState {
  /** 待确认草稿数量（用于侧栏徽章）。 */
  pendingCount: number;
  /** 待确认草稿完整列表（AutoskillScene 使用）。 */
  drafts: DraftRow[];
  /** 合并候选列表。 */
  mergeCandidates: MergeCandidate[];
  /** 优化候选列表。 */
  optimizationCandidates: OptimizationCandidate[];
  /** 会话洞察列表（未消费的 session_insight 信号）。 */
  sessionInsights: InsightRow[];
  /** 最近信号全量列表（不限 kind/consumed）。 */
  signals: SignalRow[];
  /** 是否正在运行会话分析（触发 triggerSessionAnalysis 时为 true）。 */
  analysisRunning: boolean;
  /** 最近一次会话分析汇总，用于在 UI 上展示扫描结果。 */
  lastAnalysis?: AnalysisRunSummary;
  loading: boolean;
  error: string | null;
  /** 自增版本号，每次数据变更 +1，驱动订阅方刷新。 */
  version: number;

  /** 只查数量（用于徽章），优先使用缓存。 */
  loadPendingCount: (force?: boolean) => Promise<void>;
  /** 加载草稿完整列表，同时刷新 pendingCount。 */
  loadDrafts: () => Promise<void>;
  /** 加载合并候选。 */
  loadMergeCandidates: () => Promise<void>;
  /** 加载优化候选。 */
  loadOptimizationCandidates: () => Promise<void>;
  /** 加载会话洞察列表。 */
  loadSessionInsights: () => Promise<void>;
  /** 加载最近信号全量列表。 */
  loadSignals: () => Promise<void>;
  /** 确认草稿：成功后刷新草稿列表 + 徽章 + 强制刷新技能列表。 */
  confirmDraft: (draftId: string) => Promise<void>;
  /** 拒绝草稿：成功后刷新草稿列表 + 徽章。 */
  rejectDraft: (draftId: string) => Promise<void>;
  /** 触发优化扫描，生成迭代草稿。返回结果并刷新列表。 */
  triggerScan: () => Promise<DraftResult[]>;
  /** 触发合并扫描，生成合并草稿。返回结果并刷新列表。 */
  triggerMerge: () => Promise<DraftResult[]>;
  /** 触发会话分析，运行结束后刷新洞察 + 信号列表。返回汇总或 null（出错时）。 */
  triggerSessionAnalysis: () => Promise<AnalysisRunSummary | null>;
  /** 标记某条会话洞察的消费状态，成功后刷新洞察列表。 */
  markInsightConsumed: (signalId: string, consumed: number) => Promise<void>;
}

export const useAutoskillNavStore = create<AutoskillNavState>((set, get) => ({
  pendingCount: readCountCache()?.count ?? 0,
  drafts: [],
  mergeCandidates: [],
  optimizationCandidates: [],
  sessionInsights: [],
  signals: [],
  analysisRunning: false,
  lastAnalysis: undefined,
  loading: false,
  error: null,
  version: 0,

  loadPendingCount: async (force?: boolean) => {
    const state = get();
    if (state.loading) return;
    // 缓存有效期内且非强制刷新，跳过网络请求
    const cached = readCountCache();
    if (!force && cached && Date.now() - cached.timestamp < CACHE_TTL) {
      set({ pendingCount: cached.count });
      return;
    }
    set({ loading: true, error: null });
    try {
      const list = await listPendingDrafts();
      const count = Array.isArray(list) ? list.length : 0;
      writeCountCache(count);
      set({
        pendingCount: count,
        loading: false,
        version: state.version + 1,
      });
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      log.error('Failed to load autoskill pending count', err);
      set({ loading: false, error: message });
    }
  },

  loadDrafts: async () => {
    const state = get();
    set({ loading: true, error: null });
    try {
      const list = await listPendingDrafts();
      const drafts = Array.isArray(list) ? list : [];
      writeCountCache(drafts.length);
      set({
        drafts,
        pendingCount: drafts.length,
        loading: false,
        version: state.version + 1,
      });
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      log.error('Failed to load autoskill drafts', err);
      set({ loading: false, error: message });
    }
  },

  loadMergeCandidates: async () => {
    const state = get();
    try {
      const list = await listMergeCandidates();
      set({
        mergeCandidates: Array.isArray(list) ? list : [],
        version: state.version + 1,
      });
    } catch (err) {
      log.error('Failed to load autoskill merge candidates', err);
    }
  },

  loadOptimizationCandidates: async () => {
    const state = get();
    try {
      const list = await listCandidates();
      set({
        optimizationCandidates: Array.isArray(list) ? list : [],
        version: state.version + 1,
      });
    } catch (err) {
      log.error('Failed to load autoskill optimization candidates', err);
    }
  },

  confirmDraft: async (draftId: string) => {
    try {
      await apiConfirmDraft(draftId);
      // 刷新草稿列表 + 徽章
      await get().loadDrafts();
      // 强制刷新技能列表，让新合并/迭代的技能出现
      try {
        await useNavSkillsStore.getState().loadSkills(true);
      } catch (err) {
        log.warn('Failed to refresh skills list after confirming draft', { error: err });
      }
      set({ version: get().version + 1 });
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      log.error('Failed to confirm autoskill draft', err);
      set({ error: message });
      throw err;
    }
  },

  rejectDraft: async (draftId: string) => {
    try {
      await apiRejectDraft(draftId);
      await get().loadDrafts();
      set({ version: get().version + 1 });
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      log.error('Failed to reject autoskill draft', err);
      set({ error: message });
      throw err;
    }
  },

  triggerScan: async () => {
    try {
      const results = await apiTriggerScan();
      // 扫描会生成新草稿，刷新草稿列表 + 徽章 + 优化候选
      await get().loadDrafts();
      void get().loadOptimizationCandidates();
      set({ version: get().version + 1 });
      return results;
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      log.error('Failed to trigger autoskill scan', err);
      set({ error: message });
      throw err;
    }
  },

  triggerMerge: async () => {
    try {
      const results = await apiTriggerMerge();
      await get().loadDrafts();
      void get().loadMergeCandidates();
      set({ version: get().version + 1 });
      return results;
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      log.error('Failed to trigger autoskill merge', err);
      set({ error: message });
      throw err;
    }
  },

  loadSessionInsights: async () => {
    const state = get();
    try {
      const list = await apiListSessionInsights();
      set({
        sessionInsights: Array.isArray(list) ? list : [],
        version: state.version + 1,
      });
    } catch (err) {
      log.error('Failed to load session insights', err);
    }
  },

  loadSignals: async () => {
    const state = get();
    try {
      const list = await apiListSignals();
      set({
        signals: Array.isArray(list) ? list : [],
        version: state.version + 1,
      });
    } catch (err) {
      log.error('Failed to load evolution signals', err);
    }
  },

  triggerSessionAnalysis: async () => {
    if (get().analysisRunning) return null;
    set({ analysisRunning: true, error: null });
    try {
      const summary = await apiTriggerSessionAnalysis();
      set({ lastAnalysis: summary });
      // 分析完成后刷新洞察 + 信号列表
      await get().loadSessionInsights();
      void get().loadSignals();
      set({ version: get().version + 1 });
      return summary;
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      log.error('Failed to trigger session analysis', err);
      set({ error: message });
      return null;
    } finally {
      set({ analysisRunning: false });
    }
  },

  markInsightConsumed: async (signalId, consumed) => {
    try {
      await apiMarkSignalConsumed(signalId, consumed);
      await get().loadSessionInsights();
      set({ version: get().version + 1 });
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      log.error('Failed to mark signal consumed', err);
      set({ error: message });
      throw err;
    }
  },
}));
