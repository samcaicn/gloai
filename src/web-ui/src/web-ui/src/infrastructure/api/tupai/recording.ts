// 录制相关 Tauri 命令封装。
// 命令名已对齐后端 lib.rs 的 invoke_handler 注册：
//   recordingStart       → start_recording             (teaching.rs: start_recording() —— 无参数)
//   recordingStop        → stop_recording              (teaching.rs: stop_recording())
//   recordingLoad        → get_recorded_flowchart_cmd  (recording_cmds.rs: get_recorded_flowchart_cmd(app_name, limit?))
//   recordingGetStatus   → get_recording_status        (teaching.rs: get_recording_status())
//   recordingPause       → pause_recording             (teaching.rs: pause_recording())
//   recordingResume      → resume_recording            (teaching.rs: resume_recording())
import { invoke } from './invoke';
import type { TeachingStopResult } from './types';

// 后端 start_recording 不接受 appName（教学录制为全局录制；后台录制始终自动开启）。appName 保留在 invoke 对象中以维持函数签名，后端 serde 忽略未知字段。
export async function recordingStart(appName: string): Promise<void> {
  return invoke<void>('start_recording', { appName });
}

// 最近一次录制产物的本地缓存（供「立即执行」通知按钮延后调用）。
// sessionStorage 而非内存：避免主窗口刷新/重新挂载后丢失。
const LAST_RESULT_KEY = 'tupai:recording:lastResult';

export interface CachedRecordingResult {
  appName: string | null;
  result: TeachingStopResult;
  capturedAt: number;
}

export function cacheLastRecordingResult(appName: string | null, result: TeachingStopResult): void {
  try {
    const payload: CachedRecordingResult = {
      appName,
      result,
      capturedAt: Date.now(),
    };
    sessionStorage.setItem(LAST_RESULT_KEY, JSON.stringify(payload));
  } catch {
    /* sessionStorage 不可用时静默忽略 —— 通知中的 Run now 按钮降级为 disabled。 */
  }
}

export function getCachedRecordingResult(): CachedRecordingResult | null {
  try {
    const raw = sessionStorage.getItem(LAST_RESULT_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as CachedRecordingResult;
    if (!parsed?.result?.mcpBlobBase64) return null;
    return parsed;
  } catch {
    return null;
  }
}

export function clearCachedRecordingResult(): void {
  try {
    sessionStorage.removeItem(LAST_RESULT_KEY);
  } catch { /* ignore */ }
}

// 后端 stop_recording 接收 app_name: Option<String>，Tauri 会把前端的
// camelCase 字段名自动转换为 snake_case，故此处必须用 appName 才能映射到 app_name。
// 返回 TeachingStopResult（skill_md + flowchart + mcp_blob + step_count），
// 让前端能：(1) 展示步骤数给用户，(2) 把 mcp_blob_base64 直接喂给 execute_skill
// 走"立即执行"路径（避免再次编译浪费），(3) 拿到最新流程图同步刷新 UI。
// appName 透传给后端，用于把录制结果落库到 recording::store 的
// <app_dir>/flowchart.json，使加载路径能读到（多次录制自动去重合并）。
// 此外后端 stop_recording 内部会 emit `recording:stopped` 全局事件，
// 主窗口订阅后弹出"录制完成"通知，调用方无需自行处理通知。
export async function recordingStop(appName?: string): Promise<TeachingStopResult> {
  const result = await invoke<TeachingStopResult>('stop_recording', { appName: appName ?? null });
  // 同步缓存：保证即便后端事件因时序问题未到达主窗口，通知按钮仍能工作。
  cacheLastRecordingResult(appName ?? null, result);
  return result;
}

// 后端 get_recorded_flowchart_cmd 期望 (app_name, limit?)，返回 Flowchart。
export async function recordingLoad(appName: string): Promise<any> {
  return invoke('get_recorded_flowchart_cmd', { appName, limit: 50 });
}

// 后端 get_recording_status 返回 RecordingStatus（idle / recording / paused）。
export async function recordingGetStatus(): Promise<{ state: string; event_count?: number; action_count?: number; elapsed_ms?: number }> {
  return invoke('get_recording_status');
}

// 后端 pause_recording: rdev 监视线程继续运行，但事件不再推入 buffer。
export async function recordingPause(): Promise<void> {
  return invoke<void>('pause_recording');
}

// 后端 resume_recording: 从暂停状态继续。
export async function recordingResume(): Promise<void> {
  return invoke<void>('resume_recording');
}

// 后端 get_app_stats_cmd(app_name: String) -> Result<AppRecordingStats, String>。
export async function getAppStats(appName: string): Promise<any> {
  return invoke('get_app_stats_cmd', { appName });
}

// 保存（编辑后的）流程图。后端 save_flowchart(app_name, title, flowchart_json)
// 把 JSON 落到 recording::store 的 flowchart.json（合并去重），并写一条
// source=Manual 的 SkillProposal。后端返回 SkillProposal 对象(含 proposal_id/skill_md/lineage)。
export async function saveFlowchart(appName: string, title: string, flowchart: any): Promise<any> {
  return invoke<any>('save_flowchart', { appName, title, flowchartJson: JSON.stringify(flowchart) });
}
