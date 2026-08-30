import { type RunnableConfig } from '@langchain/core/runnables'
import { type StructuredTool } from '@langchain/core/tools'
import { nanoid } from 'nanoid'
import type { SubAgentConfig, DispatchRequest, DispatchResult, AgentStepTrace, ToolRuntimeView } from '../types.js'
import { buildLangChainTools } from '../llm/adapter.js'
import { FileSystemCheckpointer } from '../persistence/checkpointer.js'
import { MultiAgentStateAnnotation, type MultiAgentState, createInitialState } from './state.js'
import { createSupervisorGraph } from './supervisor/index.js'
import { createHandoffGraph } from './handoff/index.js'

/**
 * LangGraph-based multi-agent scheduler.
 * Replaces the built-in ReactLoopAgent with a graph-driven orchestration model.
 */
export class LangGraphScheduler {
  private readonly runtime: ToolRuntimeView
  private readonly checkpointer: FileSystemCheckpointer | null
  private readonly maxAgents: number
  private readonly extraTools: StructuredTool[]
  private readonly activeTasks = new Map<string, DispatchResult>()

  constructor(runtime: ToolRuntimeView, checkpointDir: string | null, maxAgents: number, extraTools: StructuredTool[] = []) {
    this.runtime = runtime
    this.checkpointer = checkpointDir ? new FileSystemCheckpointer(checkpointDir) : null
    this.maxAgents = maxAgents
    this.extraTools = extraTools
  }

  /**
   * Dispatch a multi-agent task.
   */
  async dispatch(request: DispatchRequest): Promise<DispatchResult> {
    if (request.subAgents.length === 0) {
      throw new Error('At least one sub-agent is required')
    }
    if (request.subAgents.length > this.maxAgents) {
      throw new Error(`Too many agents: ${request.subAgents.length} > max ${this.maxAgents}`)
    }

    const startTime = Date.now()
    const tools = [...buildLangChainTools(this.runtime), ...this.extraTools]

    const graph = request.mode === 'supervisor'
      ? createSupervisorGraph(request.subAgents, tools, request.maxSteps ?? 50)
      : createHandoffGraph(request.subAgents, tools, request.maxSteps ?? 50)

    const compiled = graph.compile({ checkpointer: this.checkpointer ?? undefined })

    const threadId = `task-${request.taskId}-${nanoid(8)}`
    const config: RunnableConfig = { configurable: { thread_id: threadId } }

    const initialState = createInitialState(request.objective, request.initialState)

    try {
      const result = await compiled.invoke(initialState, config)
      const durationMs = Date.now() - startTime

      const dispatchResult: DispatchResult = {
        taskId: request.taskId,
        status: result.shouldEnd && result.finalOutput ? 'completed' : 'max_steps_reached',
        output: result.finalOutput || '',
        steps: result.trace,
        durationMs,
      }
      this.activeTasks.set(request.taskId, dispatchResult)
      return dispatchResult
    } catch (error) {
      const durationMs = Date.now() - startTime
      const dispatchResult: DispatchResult = {
        taskId: request.taskId,
        status: 'error',
        output: error instanceof Error ? error.message : String(error),
        steps: [],
        durationMs,
      }
      this.activeTasks.set(request.taskId, dispatchResult)
      return dispatchResult
    }
  }

  /**
   * Stream a multi-agent task execution, yielding intermediate state updates.
   */
  async *stream(request: DispatchRequest): AsyncGenerator<AgentStepTrace> {
    if (request.subAgents.length === 0) {
      throw new Error('At least one sub-agent is required')
    }

    const tools = [...buildLangChainTools(this.runtime), ...this.extraTools]
    const graph = request.mode === 'supervisor'
      ? createSupervisorGraph(request.subAgents, tools, request.maxSteps ?? 50)
      : createHandoffGraph(request.subAgents, tools, request.maxSteps ?? 50)

    const compiled = graph.compile({ checkpointer: this.checkpointer ?? undefined })
    const threadId = `task-${request.taskId}-${nanoid(8)}`
    const config: RunnableConfig = { configurable: { thread_id: threadId } }
    const initialState = createInitialState(request.objective, request.initialState)

    const stream = await compiled.stream(initialState, config)
    const reader = stream.getReader()
    try {
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        const state = value as unknown as MultiAgentState
        if (state.trace.length > 0) {
          const lastStep = state.trace[state.trace.length - 1]!
          yield lastStep
        }
      }
    } finally {
      reader.releaseLock()
    }
  }

  /**
   * Get the result of a completed task.
   */
  getResult(taskId: string): DispatchResult | undefined {
    return this.activeTasks.get(taskId)
  }

  /**
   * List all tracked task results.
   */
  listResults(): DispatchResult[] {
    return [...this.activeTasks.values()]
  }

  /**
   * Delete a task checkpoint data.
   */
  async deleteTask(threadId: string): Promise<void> {
    if (this.checkpointer) {
      await this.checkpointer.deleteThread(threadId)
    }
  }
}
