// Computer Use 相关 Tauri 命令封装。
// 后端 CDP/UIA/OCR/LLM 能力通过 pc_automation 命令暴露：
//   - 截图/解析屏幕：parse_screen
//   - 执行步骤：execute_step（strategy = cdp/uia/ocr/llm，识别顺序 CDP→UIA→OCR→LLM）
//   - UIA 直通：uia_get_focused_window / uia_find / uia_click / uia_type
//   - Cua Driver：check_cua_driver / cua_driver_click / cua_driver_type_text / cua_driver_invoke
// 本桥接将 cu_* 命名映射到上述实际命令。
//
// 注：后端无独立的 imagePath OCR / VLM 分析命令。
//   - OCR 能力已集成在 parse_screen（include_ocr 默认开启），不接受 imagePath 入参。
//   - VLM 能力通过 execute_step(strategy='vlm') 触发，不接受 imagePath/prompt 入参。
// 因此 cuOcrImage / cuVlmAnalyze 保留导出签名以维持向后兼容，但实现为抛
// 明确错误，避免调用 invoke 触发 "command not found" 黑洞。
import { invoke } from './invoke';

export interface CuAction {
  type: string;
  params: Record<string, any>;
}

// ── Cua Driver sidecar 健康状态 ──────────────────────────────────

export interface CuaDriverHealth {
  available: boolean;
  connected: boolean;
  binaryPath: string | null;
  version: string | null;
  toolsCount: number | null;
  lastError: string | null;
}

/**
 * 检查 Cua Driver sidecar 健康状态。
 * available: 二进制是否找到
 * connected: 进程是否正在运行且已完成 MCP 握手
 * version / toolsCount: MCP initialize / tools/list 探测结果（连接后非空）
 */
export async function checkCuaDriver(): Promise<CuaDriverHealth> {
  return invoke<CuaDriverHealth>('check_cua_driver');
}

// ── Computer Use 设置页状态（SessionConfig.tsx 使用）─────────────

export interface ComputerUseStatus {
  computerUseEnabled: boolean;
  accessibilityGranted: boolean;
  screenCaptureGranted: boolean;
  platformNote: string | null;
  cuaDriver: CuaDriverHealth;
}

/**
 * 查询 Computer Use 状态：开关 + 系统权限 + Cua Driver 健康。
 * 对应后端 computer_use_get_status。
 */
export async function getComputerUseStatus(): Promise<ComputerUseStatus> {
  return invoke<ComputerUseStatus>('computer_use_get_status');
}

/**
 * 打开系统设置对应权限页（引导用户授予无障碍 / 录屏权限）。
 * pane: 'accessibility' | 'screen_capture'。
 */
export async function openComputerUseSystemSettings(pane: 'accessibility' | 'screen_capture'): Promise<void> {
  return invoke<void>('computer_use_open_system_settings', { pane });
}

/**
 * 通过 Cua Driver 执行鼠标左键点击。
 * 优先使用后台输入（PostMessage / CGEventPostToPid），
 * 不可用时降级到 enigo（前台输入）。
 */
export async function cuaDriverClick(x: number, y: number): Promise<void> {
  return invoke<void>('cua_driver_click', { x, y });
}

/**
 * 通过 Cua Driver 输入文本。
 */
export async function cuaDriverTypeText(text: string): Promise<void> {
  return invoke<void>('cua_driver_type_text', { text });
}

/**
 * 直接调用 Cua Driver 的 MCP 工具。
 * @param toolName 工具名（如 "click", "type_text", "press_key", "hotkey", "scroll", "get_accessibility_tree"）
 * @param arguments 工具参数的 JSON 对象
 */
export async function cuaDriverInvoke(
  toolName: string,
  args: Record<string, any>,
): Promise<any> {
  return invoke<any>('cua_driver_invoke', { toolName, arguments: args });
}

// 映射到 parse_screen：捕获并解析当前屏幕，返回 ScreenElement[]（原 cu_capture_screen 期望返回 string）。
export async function cuCaptureScreen(): Promise<string> {
  return invoke<string>('parse_screen', { request: null });
}

// 映射到 execute_step：调用方需传入 PcStepView 形状（id/description/strategy/primarySelector/...），
// CuAction 的 { type, params } 为遗留宽松契约，字段对齐由调用方负责。
export async function cuExecuteAction(action: CuAction): Promise<any> {
  return invoke('execute_step', { step: action });
}

// 后端无独立的 imagePath OCR 命令（OCR 已集成在 parse_screen 中）。
// 保留导出签名以维持向后兼容，但抛明确错误，避免 invoke 触发 "command not found"。
export async function cuOcrImage(_imagePath: string): Promise<any> {
  throw new Error(
    'cuOcrImage 已废弃：后端无独立 imagePath OCR 命令，请改用 cuCaptureScreen（parse_screen 内置 OCR）',
  );
}

// 后端无独立的 imagePath+prompt VLM 分析命令（VLM 通过 execute_step(strategy="vlm") 触发）。
// 保留导出签名以维持向后兼容，但抛明确错误。
export async function cuVlmAnalyze(_imagePath: string, _prompt: string): Promise<any> {
  throw new Error(
    'cuVlmAnalyze 已废弃：后端无独立 imagePath VLM 命令，请改用 cuExecuteAction（execute_step strategy="vlm"）',
  );
}
