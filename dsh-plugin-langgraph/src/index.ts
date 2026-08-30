/**
 * dsh-plugin-langgraph: LangGraph-based multi-agent scheduler for DeepSeek Harness.
 *
 * Replaces the built-in ReactLoopAgent (Rust) with a TypeScript LangGraph orchestration layer.
 * Supports Supervisor and Handoff coordination patterns.
 *
 * @module
 */

export { LangGraphScheduler } from './scheduler/core.js'
export { createSupervisorGraph } from './scheduler/supervisor/index.js'
export { createHandoffGraph } from './scheduler/handoff/index.js'
export { FileSystemCheckpointer } from './persistence/checkpointer.js'
export { buildLangChainTools } from './llm/adapter.js'
export { createSchedulerMcpServer } from './mcp/server.js'
export { resolveConfig, type AppConfig } from './config.js'
export { MultiAgentStateAnnotation, createInitialState } from './scheduler/state.js'
export type { MultiAgentState } from './scheduler/state.js'
export type {
  SubAgentConfig,
  ManagedAgent,
  DispatchRequest,
  DispatchResult,
  AgentStepTrace,
  CoordinationMode,
  AgentStatus,
} from './types.js'
