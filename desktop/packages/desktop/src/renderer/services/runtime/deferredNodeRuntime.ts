/**
 * @license
 * Copyright 2026 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import type { IRuntimeStatusScope } from '@/common/adapter/ipcBridge';
import { isStartupRuntimeScope } from '@/common/adapter/nodeRuntimeDeferral';

/**
 * Where the Node.js runtime was needed while its download is put off (see common/adapter/nodeRuntimeDeferral.ts), once
 * per place. A conversation says so in one line above its composer; the first place anywhere else gets one toast.
 */
export type DeferredRuntimeNeeds = {
  /** Records the place. True when it is the first one outside a conversation: the caller announces that one. */
  record: (scope: IRuntimeStatusScope) => boolean;
  neededBy: (conversationId: string) => boolean;
  subscribe: (listener: () => void) => () => void;
};

export function createDeferredRuntimeNeeds(): DeferredRuntimeNeeds {
  const places = new Set<string>();
  const listeners = new Set<() => void>();
  let announcedElsewhere = false;
  return {
    record: (scope) => {
      if (isStartupRuntimeScope(scope)) return false;
      const place = `${scope.kind}:${scope.id}`;
      if (places.has(place)) return false;
      places.add(place);
      for (const listener of listeners) listener();
      if (scope.kind === 'conversation' || announcedElsewhere) return false;
      announcedElsewhere = true;
      return true;
    },
    neededBy: (conversationId) => places.has(`conversation:${conversationId}`),
    subscribe: (listener) => {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
  };
}

export const deferredRuntimeNeeds = createDeferredRuntimeNeeds();
