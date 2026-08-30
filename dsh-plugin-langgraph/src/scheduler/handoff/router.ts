import type { SubAgentConfig } from '../../types.js'
import { MultiAgentState } from '../state.js'

/**
 * Routes the handoff decision from an agent to the next agent or END.
 * The agent sets `nextAgent` to either another agent's name or 'END'.
 */
export function routeHandoff(agentConfigs: SubAgentConfig[]) {
  const validNames = new Set(['END', ...agentConfigs.map(a => a.name)])

  return (state: MultiAgentState): string => {
    const target = state.nextAgent || 'END'
    return validNames.has(target) ? target : 'END'
  }
}
