// 自动化相关 Tauri 命令封装。
// 命令名已对齐后端 lib.rs 的 invoke_handler 注册：
//   automationExecute     → automation_execute  (ext_streams.rs: automation_execute(flowchart) —— 遍历流程图节点整体执行)
//   automationExecuteStep → execute_step        (pc_automation.rs: execute_step(step) —— 执行单步)
//   automationHeal        → attempt_heal        (teaching.rs: attempt_heal(skill_id, failure?) —— step 作为 skillId 传入)
import { invoke } from './invoke';

// 后端 automation_execute 期望 flowchart: 流程图 JSON（{ nodes, connections }），
// 返回 { success, stepsExecuted, errors }。
export async function automationExecute(flowchart: any): Promise<any> {
  return invoke('automation_execute', { flowchart });
}

// 后端 execute_step 期望 PcStepView，返回 StepResult。
export async function automationExecuteStep(step: any): Promise<any> {
  return invoke('execute_step', { step });
}

// 后端 execute_flowchart_step 期望单个流程图节点（serde_json::Value），
// 返回 { ok, stepId, error? }。执行浮窗「逐节点单步执行」按钮调用本函数。
export async function executeFlowchartStep(node: any): Promise<{ ok: boolean; stepId?: string; error?: string }> {
  return invoke('execute_flowchart_step', { node });
}

// 本地已安装软件条目（commands::automation::LocalSoftwareEntry，serde camelCase）。
export interface LocalSoftwareEntry {
  name: string;
  exePath?: string;
  installLocation?: string;
}

// 后端 scan_installed_software 无参数，返回 Vec<LocalSoftwareEntry>。
export async function scanInstalledSoftware(): Promise<LocalSoftwareEntry[]> {
  return invoke<LocalSoftwareEntry[]>('scan_installed_software');
}

// 后端 launch_software_cmd(software_name: String) -> Result<(), String>。
export async function launchSoftware(softwareName: string): Promise<void> {
  return invoke<void>('launch_software_cmd', { softwareName });
}

// 后端 attempt_heal 期望 (skill_id: String, failure: Option<FailureContext>)；此处将 step 作为 skillId 传入，failure 置空。
export async function automationHeal(step: any): Promise<any> {
  return invoke('attempt_heal', { skillId: step, failure: null });
}

// ── CDP 浏览器按需启动 ──────────────────────────────────────────

/**
 * 检查 CDP 浏览器是否已连接。
 * 后端 check_cdp() 返回 Result<bool, String>。
 */
export async function checkCdp(): Promise<boolean> {
  return invoke<boolean>('check_cdp');
}

/**
 * 启动 CDP 浏览器（Chrome/Edge/Brave），自动检测端口 9222-9230。
 * 后端 launch_cdp_browser(browser_type: Option<String>) 返回 Result<String, String>。
 */
export async function launchCdpBrowser(browserType?: string): Promise<string> {
  return invoke<string>('launch_cdp_browser', { browserType: browserType ?? null });
}
