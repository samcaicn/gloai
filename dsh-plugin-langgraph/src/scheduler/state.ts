import { type BaseMessage } from '@langchain/core/messages'
import { Annotation } from '@langchain/langgraph'
import type { AgentStepTrace } from '../types.js'

/**
 * Shared state for multi-agent LangGraph workflows.
 * Uses Annotation.Root to define channels with proper reducers.
 */
export const MultiAgentStateAnnotation = Annotation.Root({
  messages: Annotation<BaseMessage[]>({
    reducer: (left: BaseMessage[], right: BaseMessage | BaseMessage[]) => {
      if (Array.isArray(right)) return left.concat(right)
      return left.concat([right])
    },
    default: () => [],
  }),
  objective: Annotation<string>(),
  activeAgent: Annotation<string>(),
  results: Annotation<Record<string, string>>({
    reducer: (left: Record<string, string>, right: Record<string, string>) => ({ ...left, ...right }),
    default: () => ({}),
  }),
  trace: Annotation<AgentStepTrace[]>({
    reducer: (left: AgentStepTrace[], right: AgentStepTrace[]) => left.concat(right),
    default: () => [],
  }),
  stepCount: Annotation<number>(),
  finalOutput: Annotation<string>(),
  shouldEnd: Annotation<boolean>(),
  nextAgent: Annotation<string>(),
})

/** Derived state type from the annotation. */
export type MultiAgentState = typeof MultiAgentStateAnnotation.State

/**
 * State factory - creates initial state for a new dispatch.
 */
export function createInitialState(objective: string, initialData?: Record<string, unknown>): MultiAgentState {
  return {
    messages: [],
    objective,
    activeAgent: '',
    results: { ...(initialData as Record<string, string> ?? {}) },
    trace: [],
    stepCount: 0,
    finalOutput: '',
    shouldEnd: false,
    nextAgent: '',
  }
}

/**
 * Convert state messages to a serializable format.
 */
export function messagesToSerializable(messages: BaseMessage[]): Array<{ role: string; content: string }> {
  return messages.map((msg) => ({
    role: msg._getType(),
    content: typeof msg.content === 'string' ? msg.content : JSON.stringify(msg.content),
  }))
}
