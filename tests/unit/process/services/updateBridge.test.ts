/**
 * @license
 * Copyright 2026 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

/** The renderer reaches the update service through two IPC providers: the state, and the step the person takes. */

import { describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => {
  const providers = new Map<string, (params?: unknown) => unknown>();
  const provider = (name: string) => ({
    provider: vi.fn((handler: (params?: unknown) => unknown) => {
      providers.set(name, handler);
      return vi.fn();
    }),
  });
  const service = {
    getState: vi.fn(() => ({ phase: 'idle' })),
    run: vi.fn(async (action: string) => ({ phase: 'upToDate', action })),
  };
  return { providers, provider, service };
});

vi.mock('@/common', () => ({
  ipcBridge: { update: { getState: mocks.provider('getState'), run: mocks.provider('run') } },
}));
vi.mock('@/process/services/update', () => ({ getUpdateService: () => mocks.service }));

import { initUpdateBridge } from '@/process/bridge/updateBridge';

describe('initUpdateBridge', () => {
  it('answers with the state and runs the step the renderer asks for', async () => {
    initUpdateBridge();
    expect(mocks.providers.get('getState')?.()).toEqual({ phase: 'idle' });
    await expect(mocks.providers.get('run')?.({ action: 'check' })).resolves.toEqual({
      phase: 'upToDate',
      action: 'check',
    });
    expect(mocks.service.run).toHaveBeenCalledWith('check');
  });
});
