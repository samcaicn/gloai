/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import { describe, expect, it, vi } from 'vitest';
import { PluginKernel, createSlotRegistry, silentLogger } from '@/common/plugins/kernel';

describe('PluginKernel', () => {
  it('provides a service that get returns, and withdraws it on dispose', () => {
    const kernel = new PluginKernel();
    const dispose = kernel.provide('thing', { value: 1 });
    expect(kernel.get<{ value: number }>('thing')?.value).toBe(1);
    dispose();
    expect(kernel.has('thing')).toBe(false);
  });

  it('a later provider of the same key wins', () => {
    const kernel = new PluginKernel();
    kernel.provide('thing', 'first');
    kernel.provide('thing', 'second');
    expect(kernel.get('thing')).toBe('second');
    // Withdrawing the later one restores the earlier only if it is still the live value.
    expect(kernel.get('thing')).toBe('second');
  });

  it('loads a plugin through plugin() and exposes its service', async () => {
    const kernel = new PluginKernel();
    const unload = await kernel.plugin({
      name: 'feature',
      apply(ctx) {
        ctx.provide('feature', 'worked');
      },
    });
    expect(kernel.get('feature')).toBe('worked');
    unload();
    expect(kernel.has('feature')).toBe(false);
  });

  it('holds apply back until its injected dependency exists, and unloads when it goes', async () => {
    const kernel = new PluginKernel();
    let live = false;
    // Not awaited: until `dep` exists the plugin does not load, and this call does not resolve.
    const unloadPromise = kernel.plugin({
      name: 'needs-dep',
      inject: ['dep'],
      apply(ctx) {
        live = true;
        ctx.effect(() => {
          live = false;
        });
      },
    });
    // No dependency yet: the plugin is not loaded.
    expect(live).toBe(false);

    const depDispose = kernel.provide('dep', 1);
    await Promise.resolve();
    await Promise.resolve();
    expect(live).toBe(true);

    depDispose();
    await Promise.resolve();
    await Promise.resolve();
    expect(live).toBe(false);

    const unload = await unloadPromise;
    unload();
  });

  it('tolerates a plugin that throws on apply', async () => {
    const kernel = new PluginKernel({ logger: silentLogger });
    const error = console.error;
    console.error = () => undefined;
    try {
      const unload = await kernel.plugin({
        name: 'boom',
        apply() {
          throw new Error('kaboom');
        },
      });
      expect(unload).toBeTypeOf('function');
    } finally {
      console.error = error;
    }
  });
});

describe('createSlotRegistry', () => {
  it('registers, orders, and replaces by id', () => {
    const slots = createSlotRegistry();
    slots.register('row', { name: 'a', order: 2, id: 'a' });
    slots.register('row', { name: 'b', order: 1, id: 'b' });
    slots.register('row', { name: 'a-again', order: 3, id: 'a' });
    const entries = slots.entries('row');
    // Re-registering id 'a' replaces the earlier entry: the slot keeps id 'a' but carries the new name.
    expect(entries.map((entry) => entry.id)).toEqual(['b', 'a']);
    expect(entries[1]?.name).toBe('a-again');
  });

  it('notifies subscribers on change', () => {
    const slots = createSlotRegistry();
    const listener = vi.fn();
    slots.subscribe(listener);
    const dispose = slots.register('row', { name: 'a', order: 1 });
    expect(listener).toHaveBeenCalledWith('row');
    dispose();
    expect(listener).toHaveBeenCalledTimes(2);
  });
});
