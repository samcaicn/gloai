// 与 Rust 后端对齐的 TypeScript 类型定义（集中存放各子模块共享的类型）
export interface LlmMessage {
  role: 'system' | 'user' | 'assistant' | 'tool';
  content: string;
  /** 工具调用时的函数名（role=tool 时） */
  name?: string;
  /** 工具调用的唯一 ID（role=tool 时对应 tool_call_id） */
  tool_call_id?: string;
}

export interface ToolSchemaFunction {
  name: string;
  description: string;
  parameters: Record<string, any>;
}

export interface ToolSchema {
  type: 'function';
  function: ToolSchemaFunction;
}

export interface LlmStreamChunk {
  type: 'content' | 'tool_call' | 'error' | 'done';
  data: any;
}

/** 与 Rust 后端 ChatToolEvent 对齐 — 前端监听 chattoolevent 事件 */
export interface ChatToolEvent {
  requestId?: string;
  phase: 'started' | 'completed';
  name?: string;
  callId?: string;
  arguments?: string;
  output?: string;
  status?: string;
}

export interface SkillMeta {
  skill_id: string;
  title: string;
  description: string;
  category: string;
  version: string;
  source: string;
  /** 技能标签（如 "platform" 表示平台级技能） */
  tags?: string[];
  /** Optional fields returned by some API endpoints (tenant skills, etc.) */
  id?: string;
  skill_name?: string;
  name?: string;
}

export interface Skill {
  skill_id: string;
  title: string;
  description: string;
  content: string;  // SKILL.md 内容
  version: string;
  category: string;
}

export interface SkillOutput {
  success: boolean;
  output: string;
  error?: string;
}

/**
 * 与 Rust 后端 `TeachingStopResult` 对齐（teaching.rs::stop_recording 返回值）。
 * 录制结束后前端拿到全部录制产物：原始 SKILL.md 文本、即时编译的 MCP 二进制
 * （base64，可直接喂给 `execute_skill` 当 skill_id 走"立即执行"路径）、
 * 步骤数、以及实时转出的可视化流程图。
 *
 * 字段命名：Rust 端用 snake_case，Tauri 默认按 camelCase 序列化到前端。
 */
export interface TeachingStopResult {
  skillMd: string;
  mcpBlobBase64: string;
  stepCount: number;
  flowchart: {
    nodes: Array<{ id: string; type?: string; label?: string; data?: any }>;
    connections?: Array<{ from: string; to: string; label?: string }>;
  };
}

export interface ImMessage {
  text: string;
  markdown?: string;
}

export interface ImEvent {
  channel_id: string;
  event_type: 'message' | 'status' | 'error';
  data: any;
}

export interface FwOpenInput {
  // 后端 OpenWindowInput 必填 id；桥接层用 page 作为 id 的语义化别名
  // （fwOpen 内部会同时填充 id 和 page，后端按字段名匹配）。
  id: string;          // main-mini / recorder / automation
  title?: string;
  width?: number;
  height?: number;
  minWidth?: number;
  minHeight?: number;
  position?: { x: number; y: number };
  anchor?: string;
  payload?: any;
}

export interface FwState {
  id: string;
  title: string;
  width: number;
  height: number;
  position: { x: number; y: number };
  minimized: boolean;
  docked: boolean;
  dockEdge?: string | null;
  z_index: number;
  opened_at: number;
}
