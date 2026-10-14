/**
 * @license
 * Copyright 2026 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';

type Provider = (params: Record<string, unknown>) => Promise<unknown>;

const mocks = vi.hoisted(() => ({
  providers: new Map<string, Provider>(),
  applyAppLanguage: vi.fn(async (language: string) => language),
}));

vi.mock('@/common', () => ({
  ipcBridge: {
    // Every systemSettings channel records the handler the bridge registers on it.
    systemSettings: new Proxy(
      {},
      {
        get: (_target, channel) => ({
          provider: (handler: Provider) => mocks.providers.set(String(channel), handler),
        }),
      }
    ),
  },
}));
vi.mock('@process/services/i18n', () => ({ applyAppLanguage: mocks.applyAppLanguage }));
vi.mock('@process/utils/initStorage', () => ({ ProcessConfig: { get: vi.fn(), set: vi.fn() } }));
vi.mock('@process/utils/tray', () => ({
  createOrUpdateTray: vi.fn(),
  destroyTray: vi.fn(),
  setCloseToTrayEnabled: vi.fn(),
}));
vi.mock('@process/utils/closeToTraySetting', () => ({
  readCloseToTraySetting: vi.fn(),
  writeCloseToTraySetting: vi.fn(),
}));

import { initSystemSettingsBridge } from '@process/bridge/systemSettingsBridge';

describe('system settings bridge: app language', () => {
  beforeEach(() => {
    mocks.providers.clear();
    mocks.applyAppLanguage.mockClear();
    initSystemSettingsBridge();
  });

  it('makes the main process follow the language the renderer switched to', async () => {
    const sync = mocks.providers.get('syncMainLanguage');
    expect(sync).toBeDefined();
    await sync?.({ language: 'zh-TW' });
    expect(mocks.applyAppLanguage).toHaveBeenCalledWith('zh-TW');
  });

  it('answers the renderer even when the switch fails', async () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    mocks.applyAppLanguage.mockRejectedValueOnce(new Error('boom'));
    await expect(mocks.providers.get('syncMainLanguage')?.({ language: 'ja-JP' })).resolves.toBeUndefined();
    expect(error).toHaveBeenCalled();
    error.mockRestore();
  });

  it('no longer registers the HTTP-backed changeLanguage channel, whose provider never runs', () => {
    expect(mocks.providers.has('changeLanguage')).toBe(false);
  });
});
