import { StateGraph, START, END } from '@langchain/langgraph'
import { type StructuredTool } from '@langchain/core/tools'
import type { SubAgentConfig } from '../../types.js'
import { MultiAgentStateAnnotation, type MultiAgentState } from '../state.js'
import { supervisorNode } from './supervisor-node.js'
import { workerNode } from './worker-node.js'
import { routeSupervisorDecision } from './router.js'

/**
 * Build a Supervisor-coordinated multi-agent graph.
 *
 * Architecture:
 *   START -> supervisor -> [worker_a | worker_b | ... | END]
 *                 ^                           |
 *                 +---------- routeback ------+
 *
 * The supervisor LLM decides which worker to activate next (or to finish).
 * Each worker executes its assigned task, then returns control to the supervisor.
 */
export function createSupervisorGraph(
  agentConfigs: SubAgentConfig[],
  allTools: StructuredTool[],
  maxSteps: number,
) {
  const builder = new StateGraph(MultiAgentStateAnnotation)

  // Add the supervisor node
  builder.addNode('supervisor', supervisorNode(agentConfigs, maxSteps))

  // Add a worker node per agent config
  for (const config of agentConfigs) {
    const agentTools = allTools.filter(t => config.tools.includes(t.name))
    builder.addNode(config.name, workerNode(config, agentTools, maxSteps))
  }

  // Routing: supervisor decides which worker to activate next
  const routeMap: Record<string, string> = { END }
  for (const config of agentConfigs) {
    routeMap[config.name] = config.name
  }

  builder.addConditionalEdges('supervisor' as any, routeSupervisorDecision(agentConfigs), routeMap as any)

  // Every worker returns to supervisor
  for (const config of agentConfigs) {
    builder.addEdge(config.name as any, 'supervisor' as any)
  }

  return builder
}
