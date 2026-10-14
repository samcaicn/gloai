/**
 * @license
 * Copyright 2026 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import type { KeyboardEvent } from 'react';

/**
 * What a clickable row needs to act as a button without being a `<button>` (the app's rule keeps raw interactive
 * elements out of the UI): a place in the Tab order, the order the rows have on screen (never a positive
 * `tabIndex`), and Enter or Space to press it, as a click would. The theme draws the focus ring of every
 * `[role='button']`. Space would otherwise scroll the list the row sits in, so it is taken.
 */
export const rowButtonProps = (onActivate: () => void) => ({
  role: 'button' as const,
  tabIndex: 0,
  onClick: onActivate,
  onKeyDown: (event: KeyboardEvent<HTMLElement>) => {
    if (event.key !== 'Enter' && event.key !== ' ') return;
    event.preventDefault();
    onActivate();
  },
});
