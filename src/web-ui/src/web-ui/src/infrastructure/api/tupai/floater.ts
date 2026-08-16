// 浮窗相关 Tauri 命令封装。
// 命令名已对齐后端 lib.rs 的 invoke_handler 注册（commands/floating_window.rs）：
//   fwOpen          → fw_open              (input: OpenWindowInput，必填 id)
//   fwGetState      → fw_get_state
//   fwClose         → fw_close             (id)
//   fwShow          → fw_focus             (id) —— 后端无 fw_show，fw_focus 会 unminimize+show+set_focus
//   fwShowMainWindow → fw_show_main_window
//   fwHideMainWindow → fw_hide_main_window
import { invoke } from './invoke';
import type { FwOpenInput, FwState } from './types';

// 后端 fw_open 期望 OpenWindowInput（id 必填），返回 FloatingEntry。
// 桥接层保持 fwOpen(input) 契约：调用方传 FwOpenInput（id 必填），
// 后端返回 FloatingEntry。这里把返回值简化为 id 字符串（取 entry.id）。
export async function fwOpen(input: FwOpenInput): Promise<string> {
  const entry = await invoke<FwState>('fw_open', { input });
  // 非 Tauri 环境下 invoke 返回 undefined，需兜底避免 TypeError
  return entry?.id ?? '';
}

export async function fwGetState(): Promise<FwState[]> {
  return invoke<FwState[]>('fw_get_state');
}

export async function fwClose(id: string): Promise<void> {
  return invoke<void>('fw_close', { id });
}

// 后端无 fw_show 命令；fw_focus(id) 会 unminimize + show + set_focus，语义等价于「显示并聚焦浮窗」。
export async function fwShow(id: string): Promise<void> {
  return invoke<void>('fw_focus', { id });
}

export async function fwShowMainWindow(): Promise<void> {
  return invoke<void>('fw_show_main_window');
}

export async function fwHideMainWindow(): Promise<void> {
  return invoke<void>('fw_hide_main_window');
}

// 录制/执行浮窗关闭时调用：拉起主窗口 + 通知主窗口加载该软件流程图节点。
export async function fwFinishSession(appName: string): Promise<void> {
  return invoke<void>('fw_finish_session', { appName });
}

// 最小化浮窗（后端实际走 dock 逻辑，隐藏窗口）。
export async function fwMinimize(id: string): Promise<void> {
  return invoke<void>('fw_minimize', { id });
}

// 恢复浮窗（从最小化/docked 状态恢复到正常显示）。
export async function fwRestore(id: string): Promise<void> {
  return invoke<void>('fw_restore', { id });
}

// 悬浮聊天窗 → 主窗口：拉起主窗口并通知其创建新会话、发送消息。
export async function fwChatToMain(message: string): Promise<void> {
  return invoke<void>('fw_chat_to_main', { message });
}

// 悬浮聊天窗 → 主窗口：把整段会话历史带回到主窗口（用户点击最大化时调用）。
// 主窗口订阅 `chat-floater:transfer-history` 事件后创建新 FlowChat 会话并回放历史。
export interface FwTransferMessage {
  role: string;
  content: string;
}

export async function fwChatTransferToMain(messages: FwTransferMessage[]): Promise<void> {
  return invoke<void>('fw_chat_transfer_to_main', { messages });
}
