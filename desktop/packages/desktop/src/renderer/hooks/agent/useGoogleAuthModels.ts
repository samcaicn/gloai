/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Google account sign-in (the `google.config` / Google Auth path) is a GFW-blocked feature removed from this build.
 * The hook stays so callers keep compiling, but it reports "not signed in" — no Google provider is ever offered.
 */

export interface GoogleAuthModelResult {
  isGoogleAuth: boolean;
  subscriptionStatus?: {
    isSubscriber: boolean;
    tier?: string;
    lastChecked: number;
    message?: string;
  };
}

export const useGoogleAuthModels = (): GoogleAuthModelResult => {
  return {
    isGoogleAuth: false,
    subscriptionStatus: undefined,
  };
};
