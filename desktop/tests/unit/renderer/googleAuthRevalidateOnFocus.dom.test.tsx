/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Tripwire for the SWR focus-revalidation opt-in. When Google auth + subscription status were queried here, they
 * opted those queries back into `revalidateOnFocus` (the sign-in happens in an external browser, so a focus had to
 * re-check). Google account sign-in is a GFW-blocked feature removed from this build: the hook is now a stub that
 * makes no SWR calls at all, so there is nothing to opt back in.
 */

import { render, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import React from 'react';

const { swrCalls } = vi.hoisted(() => ({ swrCalls: [] as Array<{ key: unknown }> }));

// Wrap the real useSWR to record (key) per call, then delegate.
vi.mock('swr', async (importOriginal) => {
  const actual = await importOriginal<typeof import('swr')>();
  const wrapped = (key: unknown, fetcher: unknown, options?: Record<string, unknown>) => {
    swrCalls.push({ key });
    return (actual.default as unknown as (...a: unknown[]) => unknown)(key, fetcher, options);
  };
  return { ...actual, default: wrapped };
});

import { useGoogleAuthModels } from '@/renderer/hooks/agent/useGoogleAuthModels';

const Probe: React.FC = () => {
  useGoogleAuthModels();
  return null;
};

const optionFor = (keyMatch: string): { key: unknown } | undefined =>
  swrCalls.find((c) => typeof c.key === 'string' && c.key.startsWith(keyMatch));

afterEach(() => {
  cleanup();
  swrCalls.length = 0;
});

describe('useGoogleAuthModels focus-revalidation opt-in', () => {
  it('makes no Google auth or subscription SWR queries (feature removed from this build)', () => {
    render(<Probe />);
    // The Google sign-in + subscription status are GFW-blocked and removed; the stub hook hits no backend.
    expect(optionFor('google.auth.status')).toBeUndefined();
    expect(optionFor('google.subscription.status')).toBeUndefined();
  });
});
