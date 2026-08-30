/** Agent coordination pattern. */
export type CoordinationMode = 'supervisor' | 'handoff'

/** Runtime status of a managed agent. */
export type AgentStatus = 'idle' | 'running' | 'waiting' | 'completed' | 'error'

/** A sub-agent definition within the scheduler. */
export interface SubAgentConfig {
  name: string
  description: string
  systemPrompt: string
  tools: string[]
  /** Optional: restrict which other agents this agent can hand off to. */
  canHandoffTo?: string[]
}

/** A managed agent instance (runtime view). */
export interface ManagedAgent {
  id: string
  config: SubAgentConfig
  status: AgentStatus
  threadId: string
  createdAt: number
  lastActiveAt: number
  errorMessage?: string
}

/** Task dispatch request. */
export interface DispatchRequest {
  taskId: string
  mode: CoordinationMode
  objective: string
  subAgents: SubAgentConfig[]
  /** Maximum total steps across all agents before forced termination. */
  maxSteps?: number
  /** Initial shared state passed to the graph. */
  initialState?: Record<string, unknown>
}

/** Task runtime state. */
export interface DispatchResult {
  taskId: string
  status: 'completed' | 'error' | 'max_steps_reached'
  output: string
  steps: AgentStepTrace[]
  durationMs: number
}

/** One step in the agent execution trace. */
export interface AgentStepTrace {
  agentName: string
  stepIndex: number
  action: 'llm_call' | 'tool_call' | 'handoff' | 'complete'
  summary: string
  timestamp: number
}

/** Minimal Cordis context this bundle needs. */
export interface DshPluginContext {
  tools: ToolRuntimeView
  on(event: 'tools/change', handler: () => void): () => void
  effect(callback: () => (() => void) | Promise<void>, label?: string): void
  logger: {
    info(message: string): void
    warn(message: string): void
    error(message: string): void
  }
}

/** Live DSH tool registry as this package consumes it. */
export interface ToolRuntimeView {
  schemas(): Array<{ name: string; description: string; parameters: Record<string, unknown> }>
  execute(input: {
    callId: string
    name: string
    arguments: unknown
    signal: AbortSignal
  }): Promise<ToolRuntimeResult>
}

/** Canonical DSH tool pipeline outcome. */
export interface ToolRuntimeResult {
  isError: boolean
  content: Array<Record<string, unknown>>
  value?: unknown
  error?: { message: string }
}

/** MCP tool spec. */
export interface McpToolSpec {
  name: string
  description: string
  inputSchema: Record<string, unknown>
}

/** MCP tool call output. */
export interface ToolCallOutput {
  content: Array<{ type: 'text'; text: string }>
  structuredContent?: unknown
  isError?: boolean
}

/** LLM message for LangChain. */
export interface LlmMessage {
  role: 'system' | 'user' | 'ai' | 'tool'
  content: string
  name?: string
  toolCallId?: string
  toolCalls?: Array<{ name: string; args: Record<string, unknown>; id: string }>
}

/** Checkpoint configuration for persistence. */
export interface CheckpointConfig {
  enabled: boolean
  directory: string
}
