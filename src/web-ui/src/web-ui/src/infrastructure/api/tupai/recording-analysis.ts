/**
 * 录制后分析 API — 借鉴 Understudy teach 模式的录制后处理流程。
 *
 * 与后端 recording_analysis.rs 对齐（Tauri 命令）：
 *   analyzeRecording      → analyze_recording        (触发录制后 AI 分析)
 *   getAnalysisStatus     → get_analysis_status       (查询分析进度/结果)
 *   refineAnalysis        → refine_analysis           (澄清对话精炼分析)
 *   publishAnalyzedSkill  → publish_analyzed_skill    (发布三层抽象技能)
 *
 * 设计区别（vs 旧的 enhanced-recording.ts）：
 *   - 无视频录制、无双轨概念、无证据包关键帧
 *   - 基于已有的 CDP/UIA 事件录制产物（flowchart + events）
 *   - 分析是录制完成后的可选增强步骤
 */
import { invoke } from './invoke';

// ── 分析结果类型 ──────────────────────────────────────────────────

/**
 * AI 分析结果 — 适配自 Understudy 的 VideoTeachAnalysis，
 * 移除了视频相关字段（keyframes, episodes），保留语义分析层。
 */
export interface AnalysisResult {
  title: string;
  objective: string;
  taskKind: string;
  parameterSlots: ParameterSlot[];
  successCriteria: string[];
  openQuestions: string[];
  steps: AnalyzedStep[];
  routeOptions: RouteOption[];
  preferredRoutes: string[];
  provider: string;
  model: string;
  eventCount: number;
  summary: string;
}

/**
 * 参数槽位 — AI 从录制中识别的可变参数。
 */
export interface ParameterSlot {
  name: string;
  label: string;
  sampleValue?: string;
  required: boolean;
  notes?: string;
}

/**
 * 分析步骤 — AI 分析后的结构化步骤，包含路由信息。
 */
export interface AnalyzedStep {
  route: 'skill' | 'browser' | 'shell' | 'gui';
  toolName: string;
  instruction: string;
  summary?: string;
  target?: string;
  app?: string;
}

/**
 * 路由选项 — 每个步骤的路由偏好。
 */
export interface RouteOption {
  id: string;
  stepIndex: number;
  route: string;
  preference: 'preferred' | 'fallback' | 'observed';
  instruction: string;
}

/**
 * 分析状态。
 */
export type AnalysisState = 'pending' | 'analyzing' | 'completed' | 'failed';

export interface AnalysisStatus {
  state: AnalysisState;
  message?: string;
}

// ── API 函数 ──────────────────────────────────────────────────────

/**
 * 触发录制后 AI 分析。
 *
 * 读取指定应用的已存储录制数据（flowchart + events），
 * 进行意图提取、路由优化、参数识别。
 *
 * 分析是同步的（Phase 1 stub），Phase 2 将接入 LLM 异步分析。
 */
export async function analyzeRecording(
  appName: string,
): Promise<{ appName: string; state: string }> {
  return invoke('analyze_recording', { appName });
}

/**
 * 查询分析进度和结果。
 */
export async function getAnalysisStatus(
  appName: string,
): Promise<{
  status: AnalysisStatus;
  analysis?: AnalysisResult | null;
}> {
  return invoke('get_analysis_status', { appName });
}

/**
 * 澄清对话 — 通过自然语言精炼 AI 分析结果。
 *
 * 用户可以：
 *   - 修改任务标题/目标
 *   - 调整参数槽位
 *   - 回答待解决问题
 */
export async function refineAnalysis(
  appName: string,
  message: string,
): Promise<{
  analysis: AnalysisResult;
  reply: string;
  hasOpenQuestions: boolean;
}> {
  return invoke('refine_analysis', { appName, message });
}

/**
 * 发布分析结果为三层抽象 SKILL.md。
 *
 * 三层结构（适配自 Understudy，非照抄）：
 *   1. 意图流程 — 自然语言步骤
 *   2. 路由选项 — preferred/fallback/observed
 *   3. GUI 回放提示 — 坐标 + 元素信息
 */
export async function publishAnalyzedSkill(
  appName: string,
  skillName?: string,
): Promise<{
  skillId: string;
  skillMd: string;
  mcpBlobBase64: string;
  published: boolean;
}> {
  return invoke('publish_analyzed_skill', {
    appName,
    skillName: skillName ?? null,
  });
}
