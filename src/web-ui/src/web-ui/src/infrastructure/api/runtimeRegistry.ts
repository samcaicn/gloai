// Runtime Registry frontend client (skeleton).
//
// Bridges the new Rust `runtime_registry` Tauri commands to the chat UI.
// Integrates with AgentService.getAvailableAgents() so detected CLIs
// (claude1 / opencode1 / ...) show up as selectable sub-agents.

import { invoke } from '@tauri-apps/api/core';

export type RuntimeKind = 'acp' | 'cliRun' | 'customApi' | 'upstream';
export type SubAgentStatus = 'available' | 'busy' | 'offline';

export interface RuntimeInstance {
  id: string;
  providerId: string;
  kind: RuntimeKind;
  displayName: string;
  endpoint: string;
  installed: boolean;
  version?: string | null;
  model?: string | null;
  hasApiKey?: boolean;
  /** CliRun / Upstream subprocess argv template ({prompt}/{cwd}). The "task". */
  cliArgsTemplate?: string[] | null;
  /** ACP only: preset client id that drives this provider (e.g. "claude-code"). */
  acpClientId?: string | null;
  /** ACP only: model ids reported by the CLI at session/new. */
  availableModels?: string[];
}

export interface SubAgent {
  id: string;
  displayName: string;
  instanceId: string;
  providerId: string;
  kind: RuntimeKind;
  status: SubAgentStatus;
  /** ACP only: model id discovered via session/new (how the exe gets it). */
  model?: string | null;
  /** ACP only: candidate model ids reported by the CLI. */
  availableModels?: string[];
}

export interface RuntimeRegistrySnapshot {
  instances: RuntimeInstance[];
  subagents: SubAgent[];
}

export interface AddCustomAgentRequest {
  name: string;
  endpoint: string;
  model?: string;
  apiKey?: string;
}

export interface InvokeSubagentRequest {
  subagentId: string;
  prompt: string;
  workspacePath?: string;
  model?: string;
  timeoutSeconds?: number;
  /** Optional API-key override for CustomApi agents (env fallback otherwise). */
  apiKey?: string;
}

export interface InvokeSubagentResponse {
  subagentId: string;
  output: string;
  exitStatus?: number;
  error?: string;
}

export const runtimeRegistryAPI = {
  async scan(): Promise<void> {
    await invoke('rr_scan_runtimes');
  },
  async listRuntimes(): Promise<RuntimeRegistrySnapshot> {
    return invoke('rr_list_runtimes');
  },
  async listSubagents(): Promise<SubAgent[]> {
    return invoke('rr_list_subagents');
  },
  async spawnInstance(providerId: string): Promise<SubAgent | null> {
    return invoke('rr_spawn_instance', { providerId });
  },
  async addCustomAgent(req: AddCustomAgentRequest): Promise<SubAgent> {
    return invoke('rr_add_custom_agent', { request: req });
  },
  async removeAgent(subagentId: string): Promise<boolean> {
    return invoke('rr_remove_agent', { subagentId });
  },
  async invokeSubagent(
    req: InvokeSubagentRequest,
  ): Promise<InvokeSubagentResponse> {
    return invoke('rr_invoke_subagent', { request: req });
  },
  /**
   * Discover model id + available models for an ACP provider the way the exe
   * does (open a real ACP session, read `models`). Returns the refreshed
   * snapshot so the caller can re-render immediately.
   */
  async discoverModels(
    providerId: string,
  ): Promise<RuntimeRegistrySnapshot> {
    return invoke('rr_discover_models', { providerId });
  },
};
