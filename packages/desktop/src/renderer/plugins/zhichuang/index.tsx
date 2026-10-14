/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * 智创协同 AI · 行业专家工作台, as a plugin.
 *
 * Two rows in the sidebar — the workbench and the expert roster — and the page behind them. Nothing else in the app
 * imports this file: the rows travel through the `sider.nav` slot, and the router maps the paths to the page, which is
 * the whole point of a plugin. Unload it and both rows go away with it.
 *
 * The workbench itself is the designed prototype, kept whole: it is a self-contained document (its own styles, its own
 * script) shown in a sandboxed frame, so the design the plugin ships is the design that was handed over. When it grows
 * past a prototype, the frame is the one thing to replace — `StudioPage` is its only seam with the rest of the app.
 */

import React from 'react';
import { AllApplication, Peoples } from '@icon-park/react';
import type { PluginDefinition } from '@/common/plugins/kernel';
import { SIDER_NAV_SLOT } from '../kernel';
import type { SiderNavEntry } from '../types';

export const ZHICHUANG_ID = 'zhichuang-studio';

/** The workbench: pick a scene, describe a task, follow it through to its deliverables. */
export const STUDIO_HOME_PATH = '/studio';
/** The roster of industry experts behind the workbench. */
export const STUDIO_EXPERTS_PATH = '/studio/experts';

export const zhichuangStudioPlugin: PluginDefinition = {
  name: ZHICHUANG_ID,
  apply(ctx) {
    const entries: Array<{ id: string; name: string; order: number; payload: SiderNavEntry }> = [
      {
        id: 'zhichuang-workbench',
        name: 'AI 工作台',
        order: 40,
        payload: {
          path: STUDIO_HOME_PATH,
          label: 'AI 工作台',
          icon: <AllApplication theme='outline' size='16' fill='currentColor' />,
        },
      },
      {
        id: 'zhichuang-experts',
        name: '我的专家团',
        order: 41,
        payload: {
          path: STUDIO_EXPERTS_PATH,
          label: '我的专家团',
          icon: <Peoples theme='outline' size='16' fill='currentColor' />,
        },
      },
    ];

    // Every registration hands back its disposer; `effect` makes unloading the plugin take both rows with it.
    for (const entry of entries) {
      ctx.effect(ctx.slots.register(SIDER_NAV_SLOT, entry));
    }
  },
};
