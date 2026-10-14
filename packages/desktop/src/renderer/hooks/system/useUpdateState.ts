/**
 * @license
 * Copyright 2026 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import { ipcBridge } from '@/common';
import type { UpdateAction, UpdateState } from '@/common/update/updateTypes';
import { isElectronDesktop } from '@/renderer/utils/platform';
import { useCallback, useEffect, useState } from 'react';

/**
 * The update state of the main process (process/services/update), kept current, and the steps the person can take.
 * Null outside the desktop app, where nothing updates itself.
 */
export const useUpdateState = (): {
  state: UpdateState | null;
  run: (action: UpdateAction) => Promise<void>;
} => {
  const [state, setState] = useState<UpdateState | null>(null);

  useEffect(() => {
    if (!isElectronDesktop()) return;
    let active = true;
    const unsubscribe = ipcBridge.update.state.on((next) => {
      if (active) setState(next);
    });
    void ipcBridge.update.getState.invoke().then((current) => {
      if (active) setState(current);
    });
    return () => {
      active = false;
      unsubscribe();
    };
  }, []);

  const run = useCallback(async (action: UpdateAction) => {
    if (!isElectronDesktop()) return;
    setState(await ipcBridge.update.run.invoke({ action }));
  }, []);

  return { state, run };
};
