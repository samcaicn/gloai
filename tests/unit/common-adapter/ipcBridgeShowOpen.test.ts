/**
 * @vitest-environment node
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';

const nativeInvoke = vi.hoisted(() => vi.fn(async () => ['/native/path']));

vi.mock('@/common/platform/bridge', () => ({
  bridge: {
    buildProvider: vi.fn(() => ({
      provider: vi.fn(),
      invoke: nativeInvoke,
    })),
    buildEmitter: vi.fn(() => ({
      on: vi.fn(() => vi.fn()),
      emit: vi.fn(),
    })),
  },
}));

vi.mock('@/common/adapter/httpBridge', () => {
  const provider = () => () => ({ provider: vi.fn(), invoke: vi.fn() });
  const emitter = () => ({ on: vi.fn(() => vi.fn()), emit: vi.fn() });
  return {
    httpGet: provider(),
    httpPost: provider(),
    httpPut: provider(),
    httpPatch: provider(),
    httpDelete: provider(),
    httpRequest: vi.fn(),
    getBaseUrl: vi.fn(() => ''),
    stubProvider: vi.fn(() => ({ provider: vi.fn(), invoke: vi.fn() })),
    withResponseMap: vi.fn((inner: unknown) => inner),
    wsEmitter: vi.fn(emitter),
    wsMappedEmitter: vi.fn(emitter),
    stubEmitter: vi.fn(emitter),
  };
});

describe('ipcBridge dialog.showOpen', () => {
  beforeEach(() => {
    nativeInvoke.mockClear();
  });

  it('goes through the native Electron IPC channel', async () => {
    const { dialog } = await import('@/common/adapter/ipcBridge');

    await expect(dialog.showOpen.invoke({ properties: ['openDirectory'] })).resolves.toEqual(['/native/path']);
    expect(nativeInvoke).toHaveBeenCalledTimes(1);
  });

  it('propagates a cancelled picker as undefined', async () => {
    const { dialog } = await import('@/common/adapter/ipcBridge');
    nativeInvoke.mockResolvedValueOnce(undefined as unknown as string[]);

    await expect(dialog.showOpen.invoke({ properties: ['openDirectory'] })).resolves.toBeUndefined();
  });
});
