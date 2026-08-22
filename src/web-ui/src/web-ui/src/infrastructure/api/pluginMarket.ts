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
  /** Whether the plugin source has been downloaded locally (a real install). */
  installed: boolean;
  /** Local path where the plugin source was extracted (real installs only). */
  localPath?: string | null;
  /** ISO-8601 timestamp of the last successful local install. */
  installedAt?: string | null;
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

/// Pull the live plugin catalog from every configured DSH upstream (Settings →
/// DSH). This is the real "接通 DSH 插件服务" path; the catalog is served by
/// the DSH runtime itself, not GitHub.
export async function dshListPlugins(): Promise<DshPluginSearchItem[]> {
  return invoke<DshPluginSearchItem[]>('dsh_list_plugins');
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

/** Open a filesystem path in the OS file manager (cross-platform). */
export async function openPath(path: string): Promise<void> {
  return invoke<void>('open_path', { path });
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
