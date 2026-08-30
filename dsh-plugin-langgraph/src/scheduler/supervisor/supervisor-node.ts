import { type BaseMessage, AIMessage, HumanMessage } from '@langchain/core/messages'
import { type StructuredTool } from '@langchain/core/tools'
import { type RunnableConfig } from '@langchain/core/runnables'
import type { SubAgentConfig, AgentStepTrace } from '../../types.js'
import { type MultiAgentState } from '../state.js'

/**
 * The supervisor node function.
 * Analyzes current state and decides which worker agent to activate next,
 * or whether the task is complete.
 */
export function supervisorNode(
  agentConfigs: SubAgentConfig[],
  maxSteps: number,
) {
  return async (state: MultiAgentState, _config?: RunnableConfig): Promise<Partial<MultiAgentState>> => {
    const stepCount = state.stepCount + 1

    // Force termination if max steps reached
    if (stepCount >= maxSteps) {
      const trace = addTrace(state, 'supervisor', stepCount, 'complete', 'Max steps reached, terminating')
      return {
        stepCount,
        shouldEnd: true,
        finalOutput: state.finalOutput || summarizeResults(state),
        trace,
      }
    }

    // Heuristic: pick the next agent that hasn't produced results yet
    const pendingAgents = agentConfigs.filter(a => !state.results[a.name])
    let nextAgent = 'END'

    if (pendingAgents.length > 0 && !state.shouldEnd) {
      nextAgent = pendingAgents[0]!.name
    } else if (Object.keys(state.results).length >= agentConfigs.length) {
      nextAgent = 'END'
    }

    const trace = addTrace(state, 'supervisor', stepCount, 'llm_call', `Dispatching to: ${nextAgent}`)

    return {
      activeAgent: nextAgent,
      stepCount,
      nextAgent,
      shouldEnd: nextAgent === 'END',
      trace,
    }
  }
}

function addTrace(
  state: MultiAgentState,
  agentName: string,
  stepIndex: number,
  action: AgentStepTrace['action'],
  summary: string,
): AgentStepTrace[] {
  return [...state.trace, { agentName, stepIndex, action, summary, timestamp: Date.now() }]
}

function summarizeResults(state: MultiAgentState): string {
  return Object.entries(state.results)
    .map(([name, result]) => `## ${name}\n${result}`)
    .join('\n\n')
}
