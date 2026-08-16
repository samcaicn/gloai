// DSH upstream management API (profile-backed runtime-registry Upstream).
//
// Frontend bridge for the `dsh_*` Tauri commands. DSH upstreams are stored in
// the active profile (`profile.json`) and seeded into the runtime-registry,
// so they appear as usable sub-agents and follow the active profile.

import { invoke } from '@tauri-apps/api/core';

export interface DshUpstreamConfig {
  id: string;
  displayName: string;
  /** http(s) URL (OpenAI-compatible /chat/completions) or a local binary path. */
  endpoint: string;
  /** Subprocess argv template ({prompt}/{cwd}) — binary endpoints only. */
  cliArgsTemplate?: string[] | null;
  model?: string | null;
  /** Present in the stored profile but intentionally NOT echoed back by the
   *  commands after write (secrecy); UI treats it as "set / unset". */
  apiKey?: string | null;
  enabled: boolean;
}

export interface DshUpsertRequest {
  id: string;
  displayName: string;
  endpoint: string;
  cliArgsTemplate?: string[] | null;
  model?: string | null;
  apiKey?: string | null;
  enabled?: boolean;
}

export const dshAPI = {
  async listUpstreams(): Promise<DshUpstreamConfig[]> {
    return invoke('dsh_list_upstreams');
  },
  async upsertUpstream(
    request: DshUpsertRequest,
  ): Promise<DshUpstreamConfig[]> {
    return invoke('dsh_upsert_upstream', { request });
  },
  async removeUpstream(id: string): Promise<DshUpstreamConfig[]> {
    return invoke('dsh_remove_upstream', { id });
  },
  async setUpstreamEnabled(
    id: string,
    enabled: boolean,
  ): Promise<DshUpstreamConfig[]> {
    return invoke('dsh_set_upstream_enabled', { id, enabled });
  },
};
