/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The renderer's plugin kernel: the same container the main process uses (`common/plugins/kernel`), one instance for
 * the window. A plugin here is the same shape as one there — `{ name, inject, apply }` — and what it adds to the UI it
 * adds through a **slot**, so a screen does not have to know its extensions: the sidebar draws whatever is registered
 * in `sider.nav`, and a plugin that is never loaded simply draws nothing.
 *
 * The kernel is pure TypeScript and holds no state of the app's own, so sharing `common/plugins/kernel` between the two
 * processes costs nothing and keeps one idea of what a plugin is.
 */

import { useEffect, useState } from 'react';
import type { Logger, PluginDefinition, SlotEntry } from '@/common/plugins/kernel';
import { PluginKernel } from '@/common/plugins/kernel';

/**
 * Where a plugin puts a row in the sidebar. Named, not wired: the sidebar reads the slot, no screen imports a plugin.
 */
export const SIDER_NAV_SLOT = 'sider.nav';

/**
 * Defined here instead of imported: an imported binding the bundler cannot prove is used at module scope gets
 * tree-shaken out of the production bundle, and the app then throws "is not defined" at startup. A logger is only ever
 * reached from inside a function, which is exactly the case that gets dropped — so it lives where it is used.
 */
const rendererLogger: Logger = {
  info: (message) => console.info(`[plugins] ${message}`),
  warn: (message) => console.warn(`[plugins] ${message}`),
  error: (message) => console.error(`[plugins] ${message}`),
};

export const rendererKernel = new PluginKernel({ logger: rendererLogger });

const installed = new Set<string>();

/**
 * Loads a plugin once. Loading is fire-and-forget on purpose: `apply` registers in a slot synchronously, so the
 * sidebar already has the row on its first render, and a plugin that awaits a service it never gets stays unloaded
 * rather than half-drawn.
 */
export const installRendererPlugin = <Config>(definition: PluginDefinition<Config>, config?: Config): void => {
  if (installed.has(definition.name)) return;
  installed.add(definition.name);
  void rendererKernel.plugin(definition, config);
};

/**
 * What is registered in a slot, kept in state so a plugin loaded later still reaches the screen that shows it.
 * `SlotRegistry.entries` hands out a fresh array when a slot is empty, so the value is read on change rather than
 * compared by identity.
 */
export const useSlotEntries = <T>(slot: string): readonly SlotEntry<T>[] => {
  const [entries, setEntries] = useState<readonly SlotEntry<T>[]>(() => rendererKernel.slots.entries(slot) as readonly SlotEntry<T>[]);

  useEffect(() => {
    const sync = (): void => {
      setEntries(rendererKernel.slots.entries(slot) as readonly SlotEntry<T>[]);
    };
    sync();
    return rendererKernel.slots.subscribe((changed) => {
      if (changed === slot) sync();
    });
  }, [slot]);

  return entries;
};
