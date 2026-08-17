// Plugin Market API — "everything is a plugin" bridge.
//
// Mirrors the Rust `commands::plugin_market` surface (camelCase wire shape).
// Three axes unified:
//   • searchDshPlugins  — network-wide DSH plugin search (GitHub topic:dsh-plugin)
//   • DSH plugin CRUD   — install / remove / enable tracked in the active profile
//   • built-in plugins  — toggle cdp/mcp/memory/pc_automation/skill/system

import { invoke } from '@tauri-apps/api/core';

export interface DshPluginSearchItem {
  id: string;
  repo: string;
  name: string;
  description?: string | null;
  stars: number;
  url: string;
  language?: string | null;
  license?: string | null;
  updatedAt?: string | null;
  installRef: string;
}

export interface DshPluginRef {
  id: string;
  repo: string;
  displayName?: string | null;
  description?: string | null;
  stars?: number | null;
  enabled: boolean;
}

export interface BuiltinPluginInfo {
  name: string;
  description: string;
  category: string;
  enabled: boolean;
}

export interface DshPluginInstallRequest {
  repo: string;
  displayName?: string | null;
  description?: string | null;
  stars?: number | null;
}

export async function searchDshPlugins(query?: string): Promise<DshPluginSearchItem[]> {
  return invoke<DshPluginSearchItem[]>('search_dsh_plugins', { query: query ?? null });
}

export async function listDshPlugins(): Promise<DshPluginRef[]> {
  return invoke<DshPluginRef[]>('list_dsh_plugins');
}

export async function installDshPlugin(req: DshPluginInstallRequest): Promise<DshPluginRef[]> {
  return invoke<DshPluginRef[]>('install_dsh_plugin', { request: req });
}

export async function removeDshPlugin(id: string): Promise<DshPluginRef[]> {
  return invoke<DshPluginRef[]>('remove_dsh_plugin', { id });
}

export async function setDshPluginEnabled(id: string, enabled: boolean): Promise<DshPluginRef[]> {
  return invoke<DshPluginRef[]>('set_dsh_plugin_enabled', { id, enabled });
}

export async function listBuiltinPlugins(): Promise<BuiltinPluginInfo[]> {
  return invoke<BuiltinPluginInfo[]>('list_builtin_plugins');
}

export async function setBuiltinPluginEnabled(
  name: string,
  enabled: boolean,
): Promise<BuiltinPluginInfo[]> {
  return invoke<BuiltinPluginInfo[]>('set_builtin_plugin_enabled', { name, enabled });
}
