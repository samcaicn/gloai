// Cron 任务调度相关 Tauri 命令封装。
// 命令名已对齐后端 lib.rs 的 invoke_handler 注册（commands::legacy::*）：
//   cronList       → get_cron_jobs        (无参数，返回 Vec<CronJob>)
//   cronCreate     → create_cron_job      (input: CreateCronJobInput)
//   cronPause      → pause_cron_job       (id: String)
//   cronResume     → resume_cron_job      (id: String)
//   cronTrigger    → trigger_cron_job     (id: String)
//   cronDelete     → delete_cron_job      (id: String)
//
// 后端通过 hermes_dashboard_api_request 代理到本地 Hermes Dashboard
// （/api/cron/jobs 系列 REST 端点），前端不直连 dashboard。
import { invoke } from './invoke';

/** 后端 CronScheduleInfo（serde rename_all = "camelCase"）。 */
export interface CronScheduleInfo {
  kind: string;
  expr: string;
  display: string;
}

/** 后端 CronJob（serde rename_all = "camelCase"）。 */
export interface CronJob {
  id: string;
  name: string | null;
  prompt: string;
  schedule: CronScheduleInfo;
  scheduleDisplay: string;
  enabled: boolean;
  state: string;
  deliver: string | null;
  lastRunAt: string | null;
  nextRunAt: string | null;
  lastError: string | null;
}

/** 后端 CreateCronJobInput。 */
export interface CreateCronJobInput {
  prompt: string;
  schedule: string;
  name: string | null;
  deliver: string | null;
}

/** 后端 CronActionResult（pause/resume/trigger/delete 共用）。 */
export interface CronActionResult {
  ok: boolean;
}

/** 列出所有 cron 任务。 */
export async function cronList(): Promise<CronJob[]> {
  return invoke<CronJob[]>('get_cron_jobs');
}

/** 创建 cron 任务。 */
export async function cronCreate(input: CreateCronJobInput): Promise<CronJob> {
  return invoke<CronJob>('create_cron_job', { input });
}

/** 暂停 cron 任务。 */
export async function cronPause(id: string): Promise<CronActionResult> {
  return invoke<CronActionResult>('pause_cron_job', { id });
}

/** 恢复 cron 任务。 */
export async function cronResume(id: string): Promise<CronActionResult> {
  return invoke<CronActionResult>('resume_cron_job', { id });
}

/** 立即触发 cron 任务。 */
export async function cronTrigger(id: string): Promise<CronActionResult> {
  return invoke<CronActionResult>('trigger_cron_job', { id });
}

/** 删除 cron 任务。 */
export async function cronDelete(id: string): Promise<CronActionResult> {
  return invoke<CronActionResult>('delete_cron_job', { id });
}

// ============================================================================
// 本地 cron (应用进程内自管，jobs.json 落盘 + 30s tick 调度 + runs 历史)
// 全部走 `hermes::cron_local::*` Tauri 命令，与 Dashboard REST 端点解耦。
// ============================================================================

/** 后端 CronSchedule (camelCase)。 */
export interface CronSchedule {
  kind: string;
  expr: string;
  display: string;
}

/** 后端 CronJob (camelCase). 多了 total/successful/failedRuns 累计。 */
export interface CronLocalJob {
  id: string;
  name: string | null;
  prompt: string;
  schedule: CronSchedule;
  scheduleDisplay: string;
  enabled: boolean;
  /** idle | running | error | paused | completed */
  state: string;
  deliver: string | null;
  lastRunAt: string | null;
  nextRunAt: string | null;
  lastError: string | null;
  totalRuns: number;
  successfulRuns: number;
  failedRuns: number;
}

/** 后端 CronRun (camelCase). 单次执行记录。 */
export interface CronRun {
  id: string;
  jobId: string;
  startedAt: string;
  finishedAt: string | null;
  /** running | completed | error */
  state: string;
  output: string | null;
  error: string | null;
  /** manual | schedule */
  trigger: string;
  delivery: string | null;
  durationMs: number | null;
}

/** 后端 CreateCronJobInput (camelCase). */
export interface CreateCronLocalJobInput {
  prompt: string;
  schedule: string;
  name: string | null;
  deliver: string | null;
  /** device_token，经 MCP llm.stream_request 调 LLM 时做 Bearer 鉴权。 */
  token?: string | null;
}

/** Trigger 入参。 */
export interface TriggerCronLocalInput {
  id: string;
  /** device_token，优先于后端缓存的 token。 */
  token?: string | null;
}

// localStorage key 与 llm.ts / skill.ts / model.ts / device.ts 保持一致
// （后端 mcp_call_v2 / cron_local 用作 Bearer token）。
const DEVICE_TOKEN_KEY = 'trae_device_token';

/** 读取设备 token（用于定时任务经 MCP 调 LLM 的鉴权）。 */
export function readDeviceToken(): string | null {
  try {
    return typeof localStorage !== 'undefined' ? localStorage.getItem(DEVICE_TOKEN_KEY) : null;
  } catch {
    return null;
  }
}

export async function cronLocalList(): Promise<CronLocalJob[]> {
  return invoke<CronLocalJob[]>('cron_local_list');
}

export async function cronLocalCreate(input: CreateCronLocalJobInput): Promise<CronLocalJob> {
  return invoke<CronLocalJob>('cron_local_create', { input });
}

export async function cronLocalPause(id: string): Promise<CronActionResult> {
  return invoke<CronActionResult>('cron_local_pause', { id });
}

export async function cronLocalResume(id: string): Promise<CronActionResult> {
  return invoke<CronActionResult>('cron_local_resume', { id });
}

export async function cronLocalTrigger(input: TriggerCronLocalInput): Promise<CronActionResult> {
  return invoke<CronActionResult>('cron_local_trigger', { input });
}

export async function cronLocalDelete(id: string): Promise<CronActionResult> {
  return invoke<CronActionResult>('cron_local_delete', { id });
}

export async function cronLocalGetRuns(id: string, limit = 200): Promise<CronRun[]> {
  return invoke<CronRun[]>('cron_local_get_runs', { id, limit });
}

export async function cronLocalClearRuns(id: string): Promise<CronActionResult> {
  return invoke<CronActionResult>('cron_local_clear_runs', { id });
}

/**
 * 把当前 device_token 透传给后端定时任务调度器。
 * 后端用它经 MCP 调 LLM（服务器自动匹配模型）；不持久化、
 * 仅存进程内存，故需在进入面板 / 触发 / 窗口聚焦时刷新。
 *
 * 同时透传给 Hermes AgentLoop 的 ToolRegistry2，使 mcp_call 等
 * 工具 handler 能用同一 token 做 Bearer 鉴权。
 */
export async function cronLocalSetToken(token: string | null): Promise<CronActionResult> {
  // 同步刷新 Hermes device_token，使 agent_loop 工具调用可鉴权
  try {
    await invoke('hermes_set_device_token', { token: token ?? null });
  } catch {
    // 静默失败——Hermes tool handlers 降级为无 token 调用
  }
  return invoke<CronActionResult>('cron_local_set_token', { token: token ?? '' });
}
