import { type BaseMessage, AIMessage, HumanMessage, ToolMessage } from '@langchain/core/messages'
import { type StructuredTool } from '@langchain/core/tools'
import { type RunnableConfig } from '@langchain/core/runnables'
import type { SubAgentConfig, AgentStepTrace } from '../../types.js'
import { type MultiAgentState } from '../state.js'

/**
 * Creates a handoff-capable node for a specific agent.
 * The agent can decide to hand off to another agent or complete the task.
 */
export function handoffNode(
  agentConfig: SubAgentConfig,
  tools: StructuredTool[],
  allAgents: SubAgentConfig[],
  maxSteps: number,
) {
  return async (state: MultiAgentState, _config?: RunnableConfig): Promise<Partial<MultiAgentState>> => {
    const stepCount = state.stepCount + 1

    if (stepCount >= maxSteps) {
      const trace = addTrace(state, agentConfig.name, stepCount, 'complete', 'Max steps reached')
      return {
        stepCount,
        shouldEnd: true,
        finalOutput: state.finalOutput || summarizeResults(state),
        trace,
      }
    }

    // Build context
    const priorResults = Object.entries(state.results)
      .map(([name, result]) => `<result agent="${name}">\n${result}\n</result>`)
      .join('\n') || '(no prior results)'

    const availableHandoffs = allAgents
      .filter(a => a.name !== agentConfig.name && (!agentConfig.canHandoffTo || agentConfig.canHandoffTo.includes(a.name)))
      .map(a => `- ${a.name}: ${a.description}`)
      .join('\n') || '(none)'

    const taskContent = `You are ${agentConfig.name}: ${agentConfig.description}

${agentConfig.systemPrompt}

## Overall Objective
${state.objective}

## Prior Agent Results
${priorResults}

## Available Agents to Hand Off To
${availableHandoffs}

## Your Task
Execute your role. When done, either:
1. Respond with "FINAL:" followed by your complete output if the task is done
2. Respond with "HANDOFF: <agent_name>" if another agent should continue`

    const taskMessage = new HumanMessage(taskContent)

    const result = await executeHandoffTurn(taskMessage, tools, agentConfig)

    // Parse the result to determine if it's a handoff or final output
    const handoffMatch = result.match(/^HANDOFF:\s*(\S+)/i)
    const finalMatch = result.match(/^FINAL:\s*([\s\S]+)/i)

    let nextAgent = 'END'
    let finalOutput = state.finalOutput
    let shouldEnd = false

    if (handoffMatch) {
      nextAgent = handoffMatch[1]!
    } else if (finalMatch) {
      shouldEnd = true
      finalOutput = finalMatch[1]!.trim()
    } else {
      // Default: treat as final output
      shouldEnd = true
      finalOutput = result
    }

    const trace = addTrace(
      state,
      agentConfig.name,
      stepCount,
      nextAgent !== 'END' ? 'handoff' : 'complete',
      nextAgent !== 'END' ? `Handing off to: ${nextAgent}` : 'Task complete',
    )

    return {
      activeAgent: agentConfig.name,
      stepCount,
      nextAgent,
      shouldEnd,
      finalOutput,
      results: { ...state.results, [agentConfig.name]: result },
      messages: [taskMessage, new AIMessage(result)],
      trace,
    }
  }
}

async function executeHandoffTurn(
  message: HumanMessage,
  tools: StructuredTool[],
  config: SubAgentConfig,
): Promise<string> {
  // Placeholder: In production, integrate with real LLM
  const toolNames = tools.map(t => t.name).join(', ') || 'none'
  return `[${config.name} executed its role using tools: ${toolNames}]

FINAL: ${config.name} completed analysis based on "${config.description}".
Tools available: ${toolNames}
Timestamp: ${new Date().toISOString()}`
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
