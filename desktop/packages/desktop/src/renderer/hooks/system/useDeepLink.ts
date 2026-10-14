/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import { useCallback, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { ipcBridge } from '@/common';

/**
 * Deep link event payload from main process
 */
export type DeepLinkPayload = {
  action: string;
  params: Record<string, string>;
};

export type DeepLinkAddProviderDetail = {
  base_url?: string;
  api_key?: string;
  name?: string;
  platform?: string;
};

/**
 * Pending deep link data for the add-provider action. Read-once: the providers page takes it when it opens, or at once
 * when it is already open (it listens through {@link subscribePendingDeepLink}).
 */
let pendingDeepLinkData: DeepLinkAddProviderDetail | null = null;
const pendingListeners = new Set<() => void>();

/**
 * Consume (read and clear) pending deep link data.
 * Returns the data if present, or null. Subsequent calls return null until new data arrives.
 */
export const consumePendingDeepLink = (): DeepLinkAddProviderDetail | null => {
  const data = pendingDeepLinkData;
  pendingDeepLinkData = null;
  return data;
};

/** Keeps an add-provider link's details for the providers page and tells a page that is already open. */
export const offerAddProviderLink = (detail: DeepLinkAddProviderDetail): void => {
  pendingDeepLinkData = detail;
  for (const listener of pendingListeners) listener();
};

/** Calls `listener` whenever an add-provider link arrives. Returns the unsubscribe function. */
export const subscribePendingDeepLink = (listener: () => void): (() => void) => {
  pendingListeners.add(listener);
  return () => {
    pendingListeners.delete(listener);
  };
};

/**
 * Allowed route patterns for the navigate deep link action.
 * Only routes matching these patterns are permitted.
 */
const ALLOWED_NAVIGATE_PATTERNS = [/^\/team\/[^/]+$/, /^\/conversation\/[^/]+$/];

/**
 * Hook to listen for mu:// deep link events from main process.
 * Routes 'add-provider' action to the providers settings page, which opens a new provider filled from the link.
 * Routes 'navigate' action to the specified route (whitelist-validated).
 * The pre-fill data is stored in a module-level variable and taken by the providers page via consumePendingDeepLink().
 */
export const useDeepLink = () => {
  const navigate = useNavigate();

  const handler = useCallback(
    (payload: DeepLinkPayload) => {
      // Support both formats: "add-provider" and "provider/add" (one-api style)
      if (payload.action === 'add-provider' || payload.action === 'provider/add') {
        offerAddProviderLink({
          base_url: payload.params.base_url,
          api_key: payload.params.api_key || payload.params.key,
          name: payload.params.name,
          platform: payload.params.platform,
        });

        // The providers page is where a provider is added. (`/settings/model` is retired: it only redirected here.)
        void navigate('/settings/providers');
        return;
      }

      if (payload.action === 'navigate') {
        const route = payload.params.route;
        if (!route) {
          console.warn('[DeepLink] navigate action missing route param');
          return;
        }

        const isAllowed = ALLOWED_NAVIGATE_PATTERNS.some((pattern) => pattern.test(route));
        if (!isAllowed) {
          console.warn(`[DeepLink] navigate blocked: route "${route}" not in whitelist`);
          return;
        }

        void navigate(route);
      }
    },
    [navigate]
  );

  useEffect(() => {
    return ipcBridge.deepLink.received.on(handler);
  }, [handler]);
};
