import type { SubAgentConfig } from '../../types.js'
import { MultiAgentState } from '../state.js'

/**
 * Routes the supervisor decision to the appropriate worker node or END.
 * The supervisor node sets `nextAgent` to either a worker name or 'END'.
 */
export function routeSupervisorDecision(agentConfigs: SubAgentConfig[]) {
  const validNames = new Set(['END', ...agentConfigs.map(a => a.name)])

  return (state: MultiAgentState): string => {
    const target = state.nextAgent || 'END'
    return validNames.has(target) ? target : 'END'
  }
}
