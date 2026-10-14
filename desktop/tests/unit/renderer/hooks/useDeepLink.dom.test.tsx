/**
 * @license
 * Copyright 2026 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * A mu:// link that adds a provider lands on the providers page. It used to go to `/settings/model`, a page that no
 * longer exists and only redirected there. The page takes the link's details when it opens, or at once when it is
 * already open.
 */

import { renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { navigateMock, listeners } = vi.hoisted(() => ({
  navigateMock: vi.fn(),
  listeners: [] as Array<(payload: { action: string; params: Record<string, string> }) => void>,
}));

vi.mock('react-router-dom', () => ({
  useNavigate: () => navigateMock,
}));

vi.mock('@/common', () => ({
  ipcBridge: {
    deepLink: {
      received: {
        on: (listener: (payload: { action: string; params: Record<string, string> }) => void) => {
          listeners.push(listener);
          return () => undefined;
        },
      },
    },
  },
}));

import { consumePendingDeepLink, subscribePendingDeepLink, useDeepLink } from '@/renderer/hooks/system/useDeepLink';

describe('useDeepLink', () => {
  beforeEach(() => {
    navigateMock.mockClear();
    listeners.length = 0;
    consumePendingDeepLink();
  });

  it.each(['add-provider', 'provider/add'])('opens the providers page for a %s link', (action) => {
    renderHook(() => useDeepLink());

    listeners.at(-1)?.({ action, params: { base_url: 'https://api.example.com', key: 'k-1', name: 'Example' } });

    expect(navigateMock).toHaveBeenCalledWith('/settings/providers');
    expect(consumePendingDeepLink()).toMatchObject({
      base_url: 'https://api.example.com',
      api_key: 'k-1',
      name: 'Example',
    });
  });

  it('tells a providers page that is already open, until it stops listening', () => {
    renderHook(() => useDeepLink());
    const heard = vi.fn(() => consumePendingDeepLink());
    const stop = subscribePendingDeepLink(heard);

    listeners.at(-1)?.({
      action: 'add-provider',
      params: { base_url: 'https://api.example.com', platform: 'anthropic' },
    });
    expect(heard).toHaveBeenCalledTimes(1);
    expect(heard.mock.results[0].value).toMatchObject({ base_url: 'https://api.example.com', platform: 'anthropic' });

    stop();
    listeners.at(-1)?.({ action: 'add-provider', params: { base_url: 'https://other.example.com' } });
    expect(heard).toHaveBeenCalledTimes(1);
    expect(consumePendingDeepLink()).toMatchObject({ base_url: 'https://other.example.com' });
  });
});
