/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The payloads a slot carries. Kept next to the kernel rather than with any one plugin: the screen that draws the slot
 * and the plugin that fills it agree on this shape and on nothing else.
 */

import type { ReactNode } from 'react';

/** One row a plugin adds to the sidebar. */
export type SiderNavEntry = {
  /** The route the row opens. Owned by the plugin, mapped to its page by the router. */
  path: string;
  /** Shown beside the icon when the sidebar is open, and as the rail's tooltip when it is not. */
  label: string;
  /** Drawn at 16px in the row's own colour. */
  icon: ReactNode;
};
