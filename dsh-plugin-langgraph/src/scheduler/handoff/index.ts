import { StateGraph, START, END } from '@langchain/langgraph'
import { type StructuredTool } from '@langchain/core/tools'
import type { SubAgentConfig } from '../../types.js'
import { MultiAgentStateAnnotation, type MultiAgentState } from '../state.js'
import { handoffNode } from './handoff-node.js'
import { routeHandoff } from './router.js'

/**
 * Build a Handoff-coordinated multi-agent graph.
 *
 * Architecture:
 *   START -> first_agent -> [agent_a | agent_b | ... | END]
 *               ^              |       |
 *               +--- handoff --+---+
 *
 * Each agent can decide to:
 * 1. Complete the task (route to END)
 * 2. Hand off control to another agent
 */
export function createHandoffGraph(
  agentConfigs: SubAgentConfig[],
  allTools: StructuredTool[],
  maxSteps: number,
) {
  const builder = new StateGraph(MultiAgentStateAnnotation)

  // Add a handoff-capable node per agent
  for (const config of agentConfigs) {
    const agentTools = allTools.filter(t => config.tools.includes(t.name))
    builder.addNode(config.name, handoffNode(config, agentTools, agentConfigs, maxSteps))
  }

  // Conditional routing: each agent decides next agent or END
  const routeMap: Record<string, string> = { END }
  for (const config of agentConfigs) {
    routeMap[config.name] = config.name
  }

  builder.addConditionalEdges(START as any, (state) => {
    return agentConfigs[0]?.name ?? 'END'
  }, routeMap as any)

  for (const config of agentConfigs) {
    builder.addConditionalEdges(config.name as any, routeHandoff(agentConfigs), routeMap as any)
  }

  return builder
}
