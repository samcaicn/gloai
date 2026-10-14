/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The plugins this build ships. One place registers them; nothing else in the app changes — the screens that show
 * them read slots, not this file.
 *
 * A plugin registered here is not force-loaded: `bootstrapRendererPlugins` installs the enabled set (persisted from
 * the last run, or the default-enabled ones on the first run), and the Plugins settings page can install or uninstall
 * any of them at runtime. To add a plugin, register it here and the manager does the rest.
 */

import { registerBuiltInPlugin, bootstrapRendererPlugins } from './manager';
import { zhichuangStudioPlugin } from './zhichuang';

registerBuiltInPlugin(zhichuangStudioPlugin, {
  description: '智创协同 AI · 行业专家工作台',
  defaultEnabled: true,
});

/** Called once before the first render, in place of the old force-install. */
export const installBuiltInRendererPlugins = (): void => {
  bootstrapRendererPlugins();
};

export {
  registerBuiltInPlugin,
  bootstrapRendererPlugins,
  listPlugins,
  enablePlugin,
  disablePlugin,
  isPluginEnabled,
  isPluginRegistered,
  onPluginToggle,
} from './manager';
