/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import { ipcBridge } from '@/common';
import { getUpdateService } from '../services/update';

/** The renderer's way to the update service (process/services/update): the state, and the steps the person takes. */
export function initUpdateBridge(): void {
  ipcBridge.update.getState.provider(() => getUpdateService().getState());
  ipcBridge.update.run.provider(({ action }) => getUpdateService().run(action));
}
