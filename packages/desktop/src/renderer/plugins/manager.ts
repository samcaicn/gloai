/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The renderer's runtime plugin manager.
 *
 * The kernel already supports unloading — `plugin()` hands back a disposer that takes everything a plugin added
 * with it — so "install" and "uninstall" are just `enable` and `disable` here. A plugin is a plain `{ name, apply }`
 * definition the bundle ships (a built-in); enabling it loads it through the kernel, disabling disposes it and the
 * sidebar row, route and every registration go away with it. Which plugins are enabled survives restarts in
 * `localStorage`, so the AI workbench can be uninstalled and reinstalled at any time without a rebuild.
 *
 * Loading an external plugin (a `.mjs` dropped into the plugins folder) is a later step; the manager is written to
 * grow into it without changing this surface.
 */

import { rendererKernel } from './kernel';
import type { Dispose, PluginDefinition } from '@/common/plugins/kernel';

/** Where the enabled set lives between runs. */
const STORAGE_KEY = 'mu.plugins.v1';

type BuiltInMeta = {
  description?: string;
  /** Enabled on first run, before anything is persisted. */
  defaultEnabled?: boolean;
};

type BuiltIn = { definition: PluginDefinition; meta: BuiltInMeta };

const builtins = new Map<string, BuiltIn>();
const loaded = new Map<string, Dispose>();
const listeners = new Set<(name: string) => void>();

function readEnabled(): Set<string> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) return new Set<string>(JSON.parse(raw) as string[]);
  } catch {
    /* a corrupt value is treated as "nothing enabled yet" */
  }
  return new Set<string>();
}

function writeEnabled(set: Set<string>): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify([...set]));
  } catch {
    /* storage may be unavailable (private mode); the in-memory state still works for the session */
  }
}

// Live enabled set, read once. Toggles update it and persist.
let enabled = readEnabled();

function notify(name: string): void {
  for (const listener of Array.from(listeners)) listener(name);
}

/** Records a built-in plugin the build ships, with how it should first appear. Call once at startup. */
export function registerBuiltInPlugin(definition: PluginDefinition, meta: BuiltInMeta = {}): void {
  builtins.set(definition.name, { definition, meta });
}

/** Whether a plugin is currently loaded (its rows are in the sidebar). */
export function isPluginEnabled(name: string): boolean {
  return loaded.has(name);
}

/** Whether a plugin is known to the manager (built-in or otherwise). */
export function isPluginRegistered(name: string): boolean {
  return builtins.has(name);
}

/** Every known plugin and whether it is loaded right now. */
export function listPlugins(): Array<{ name: string; description?: string; enabled: boolean; source: 'builtin' }> {
  return [...builtins.entries()].map(([name, { meta }]) => ({
    name,
    description: meta.description,
    enabled: loaded.has(name),
    source: 'builtin' as const,
  }));
}

/** Loads a plugin. Idempotent: a second call for an already-loaded plugin is a no-op that resolves true. */
export async function enablePlugin(name: string): Promise<boolean> {
  if (loaded.has(name)) return true;
  const entry = builtins.get(name);
  if (!entry) return false;
  try {
    const dispose = await rendererKernel.plugin(entry.definition);
    loaded.set(name, dispose);
    enabled.add(name);
    writeEnabled(enabled);
    notify(name);
    return true;
  } catch (error) {
    console.error(`[plugins] failed to enable "${name}"`, error);
    return false;
  }
}

/** Unloads a plugin: disposes it through the kernel, so every registration it made is withdrawn. */
export function disablePlugin(name: string): void {
  const dispose = loaded.get(name);
  if (!dispose) return;
  try {
    dispose();
  } catch (error) {
    console.error(`[plugins] failed to disable "${name}"`, error);
  }
  loaded.delete(name);
  enabled.delete(name);
  writeEnabled(enabled);
  notify(name);
}

/** Subscribe to load/unload of any plugin; returns an unsubscribe. */
export function onPluginToggle(listener: (name: string) => void): Dispose {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/**
 * Installs the enabled set at startup: whatever was persisted last run, or the default-enabled built-ins on the
 * first run (before anything is persisted). Called once, before the first render, so a plugin's rows are in the
 * slot by the time the sidebar draws.
 */
export function bootstrapRendererPlugins(): void {
  const persisted = readEnabled();
  const hasPersisted = persisted.size > 0;
  for (const [name, { meta }] of builtins) {
    const shouldEnable = hasPersisted ? persisted.has(name) : Boolean(meta.defaultEnabled);
    if (shouldEnable) void enablePlugin(name);
  }
}
