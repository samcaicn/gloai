// Autoskill 自动建议相关 Tauri 命令封装。
// 命令名已对齐后端 invoke_handler 注册：
//   autoskill_list_pending_drafts(scene) -> Vec<DraftRow>
//   autoskill_list_merge_candidates(scene) -> Vec<MergeCandidate>
//   autoskill_list_candidates(scene) -> Vec<OptimizationCandidate>
//   autoskill_confirm_draft(draft_id) -> ()
//   autoskill_reject_draft(draft_id) -> ()
//   autoskill_trigger_scan(scene) -> Vec<DraftResult>
//   autoskill_trigger_merge(scene) -> Vec<DraftResult>
import { invoke } from './invoke';

// 待确认草稿行
export interface DraftRow {
  id: string;
  scene: string;
  skill_id: string;
  draft_version: string;
  source: string;
  status: string;
  content?: string;
  old_score?: number;
  new_score?: number;
  optimization_points?: string; // JSON 字符串
  watch_started_at?: string;
  watch_score_drop?: number;
  created_at?: string;
  // ── Hermes 自进化扩展字段（Track C 在 Rust 侧补齐，TS 接口由 Track E 维护） ──
  /** 草稿来源技能类型，与后端 SkillKind 枚举对齐。 */
  skillKind?: SkillKind;
  /** 信号来源（telemetry / session_insight / memory_linked / merge）。 */
  sourceKind?: SignalSource;
  /** 关联 EvolutionSignal 的 JSON 字符串，前端解析后用于展示 evidence。 */
  evidenceJson?: string;
  /** 关联的 signalId（可追溯到触发草稿的信号）。 */
  signalRef?: string;
}

// 合并候选
export interface MergeCandidate {
  scene: string;
  skill_ids: string[];
  similarity: number;
  action_signature: string;
  total_runs: number;
}

// 优化候选
export interface OptimizationCandidate {
  scene: string;
  skill_id: string;
  current_version: string;
  current_score: number;
  run_count: number;
  failure_rate: number;
  reason: string;
}

// 草稿生成结果（trigger_scan / trigger_merge 返回）
export interface DraftResult {
  draft_id: string;
  scene: string;
  skill_id: string;
  draft_version: string;
  content: string;
  new_score: number;
  old_score: number;
  optimization_points: string[];
  qualified: boolean;
}

// 查询待确认草稿列表
export async function listPendingDrafts(scene: string = 'default'): Promise<DraftRow[]> {
  return invoke<DraftRow[]>('autoskill_list_pending_drafts', { scene });
}

// 查询合并候选列表
export async function listMergeCandidates(scene: string = 'default'): Promise<MergeCandidate[]> {
  return invoke<MergeCandidate[]>('autoskill_list_merge_candidates', { scene });
}

// 查询优化候选列表
export async function listCandidates(scene: string = 'default'): Promise<OptimizationCandidate[]> {
  return invoke<OptimizationCandidate[]>('autoskill_list_candidates', { scene });
}

// 确认草稿（采纳）
export async function confirmDraft(draftId: string): Promise<void> {
  return invoke<void>('autoskill_confirm_draft', { draftId });
}

// 拒绝草稿
export async function rejectDraft(draftId: string): Promise<void> {
  return invoke<void>('autoskill_reject_draft', { draftId });
}

// 触发优化扫描，生成迭代草稿
export async function triggerScan(scene: string = 'default'): Promise<DraftResult[]> {
  return invoke<DraftResult[]>('autoskill_trigger_scan', { scene });
}

// 触发合并扫描，生成合并草稿
export async function triggerMerge(scene: string = 'default'): Promise<DraftResult[]> {
  return invoke<DraftResult[]>('autoskill_trigger_merge', { scene });
}

// ════════════════════════════════════════════════════════════════════
// Hermes 自进化：会话分析 / 信号 / 洞察（Track E 前端封装）
//
// 命令名对齐后端 invoke_handler 注册：
//   evolution_trigger_session_analysis(sinceMs?: number)  -> AnalysisRunSummary
//   evolution_list_signals(limit?: number)                -> SignalRow[]
//   evolution_list_session_insights(scene?: string)       -> InsightRow[]
//   evolution_mark_signal_consumed(signalId, consumed)    -> ()
// ════════════════════════════════════════════════════════════════════

/** 技能类型（对齐后端 SkillKind Rust 枚举）。 */
export type SkillKind = 'mcp' | 'automation' | 'builtin';

/** 会话信号类型（对齐后端 SessionSignalType Rust 枚举）。 */
export type SessionSignalType =
  | 'missing_skill'
  | 'frequent_correction'
  | 'negative_rating'
  | 'repetitive_action';

/** 信号来源（对齐后端 SignalSource Rust 枚举）。 */
export type SignalSource = 'telemetry' | 'session_insight' | 'memory_linked' | 'merge';

/**
 * 信号行（对齐后端 SignalRow）。
 *
 * 注意：
 * - `signalKind` 字段使用后端 Rust 枚举的 tag 字符串
 *   ('Telemetry' | 'SessionInsight' | 'MemoryLinked' | 'MergeCandidate')，
 *   这里以 `string` 表达以兼容未来新增枚举值。
 * - `evidenceJson` 是完整 EvolutionSignal 的 JSON 字符串，前端可解析后
 *   取出 `evidence: string[]` 数组用于展示。
 */
export interface SignalRow {
  signalId: string;
  signalKind: string;
  sourceKind: SignalSource;
  sessionId?: string;
  skillId?: string;
  skillKind: SkillKind;
  signalType?: SessionSignalType;
  evidenceJson?: string;
  suggestedAction?: string;
  confidence: number;
  consumed: number;
  createdAt: string;
}

/** 会话洞察行：与 SignalRow 同形状，后端按 kind=sessionInsight 且 consumed=0 过滤。 */
export interface InsightRow extends SignalRow {}

/** 一次会话分析运行的汇总信息。 */
export interface AnalysisRunSummary {
  runId: string;
  sessionsScanned: number;
  signalsEmitted: number;
  degraded: boolean;
  llmTokensUsed?: number;
}

/**
 * 触发会话分析。`sinceMs` 缺省时后端使用默认时间窗口。
 * 后端命令：evolution_trigger_session_analysis
 */
export async function triggerSessionAnalysis(sinceMs?: number): Promise<AnalysisRunSummary> {
  return invoke<AnalysisRunSummary>('evolution_trigger_session_analysis', {
    sinceMs: sinceMs ?? null,
  });
}

/**
 * 拉取最近的信号列表。后端命令：evolution_list_signals
 */
export async function listSignals(limit = 50): Promise<SignalRow[]> {
  return invoke<SignalRow[]>('evolution_list_signals', { limit });
}

/**
 * 拉取未消费的会话洞察（kind=sessionInsight, consumed=0）。
 * 后端命令：evolution_list_session_insights
 */
export async function listSessionInsights(scene = 'default'): Promise<InsightRow[]> {
  return invoke<InsightRow[]>('evolution_list_session_insights', { scene });
}

/**
 * 标记信号消费状态。
 * - consumed=0 未处理
 * - consumed=1 已采纳
 * - consumed=2 已忽略
 * - consumed=3 PassThrough（交给 AutoSkillEngine）
 * 后端命令：evolution_mark_signal_consumed
 */
export async function markSignalConsumed(signalId: string, consumed: number): Promise<void> {
  return invoke<void>('evolution_mark_signal_consumed', { signalId, consumed });
}
