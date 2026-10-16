/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import { describe, expect, it } from 'vitest';
import { renderHook } from '@testing-library/react';

import { useGoogleAuthModels } from '@/renderer/hooks/agent/useGoogleAuthModels';

/**
 * Google account sign-in (the `google.config` / Google Auth path) is a GFW-blocked feature removed from this build.
 * The hook stays so callers keep compiling, but reports "not signed in" — no Google provider is ever offered and no
 * backend is queried.
 */
describe('useGoogleAuthModels', () => {
  it('reports no Google sign-in in this build (feature removed)', () => {
    const { result } = renderHook(() => useGoogleAuthModels());
    expect(result.current.isGoogleAuth).toBe(false);
    expect(result.current.subscriptionStatus).toBeUndefined();
  });
});
