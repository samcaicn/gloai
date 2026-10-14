/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import React, { useEffect } from 'react';
import { useLocation } from 'react-router-dom';
import { MU_DISPLAY_NAME } from '@/common/kyrn/displayName';

/**
 * Single owner of `document.title`.
 *
 * The window title is the product name on every route. Kept as a component so
 * nothing else writes `document.title` behind the app's back.
 */
export function titleForPath(_pathname: string): string {
  return MU_DISPLAY_NAME;
}

const DocumentTitle: React.FC = () => {
  const { pathname } = useLocation();

  useEffect(() => {
    document.title = titleForPath(pathname);
  }, [pathname]);

  return null;
};

export default DocumentTitle;
