import { type BaseMessage, AIMessage, HumanMessage, ToolMessage } from '@langchain/core/messages'
import { type StructuredTool } from '@langchain/core/tools'
import { type RunnableConfig } from '@langchain/core/runnables'
import type { SubAgentConfig, AgentStepTrace } from '../../types.js'
import { type MultiAgentState } from '../state.js'

/**
 * Creates a worker node function for a specific agent configuration.
 * Each worker executes its assigned task using the tools available to it,
 * then returns its result to the supervisor.
 */
export function workerNode(
  agentConfig: SubAgentConfig,
  tools: StructuredTool[],
  maxSteps: number,
) {
  return async (state: MultiAgentState, _config?: RunnableConfig): Promise<Partial<MultiAgentState>> => {
    const stepCount = state.stepCount + 1

    // Build the task context for this worker
    const priorResults = Object.entries(state.results)
      .map(([name, result]) => `<result agent="${name}">\n${result}\n</result>`)
      .join('\n') || '(no prior results)'

    const taskContent = `You are ${agentConfig.name}: ${agentConfig.description}

${agentConfig.systemPrompt}

## Overall Objective
${state.objective}

## Prior Agent Results
${priorResults}

## Your Task
Execute your role and produce a complete result. Be thorough and specific.
When you are done, respond with "FINAL:" followed by your complete output.`

    const taskMessage = new HumanMessage(taskContent)

    // Execute: call LLM (placeholder for real LLM integration)
    const result = await executeAgentTurn(taskMessage, tools, agentConfig)

    const trace = addTrace(state, agentConfig.name, stepCount, 'llm_call', result.slice(0, 200))

    return {
      activeAgent: agentConfig.name,
      stepCount,
      results: { ...state.results, [agentConfig.name]: result },
      messages: [taskMessage, new AIMessage(result)],
      trace,
    }
  }
}

/**
 * Execute one turn of agent reasoning with tool use.
 * In production, this would integrate with a real LLM via LangChain.
 */
async function executeAgentTurn(
  message: HumanMessage,
  tools: StructuredTool[],
  config: SubAgentConfig,
): Promise<string> {
  // Placeholder: In real implementation, this would:
  // 1. Bind tools to an LLM via ChatOpenAI or similar
  // 2. Create an agent executor
  // 3. Invoke with the message and return the final output
  const toolNames = tools.map(t => t.name).join(', ') || 'none'
  return `[${config.name} completed its task using tools: ${toolNames}]

FINAL: ${config.name} has analyzed the objective and produced a result based on "${config.description}".
Available tools used: ${toolNames}
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
