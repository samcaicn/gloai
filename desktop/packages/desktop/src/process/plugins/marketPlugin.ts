/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The market, as a plugin.
 *
 * It is loaded the same way anything else is — `kernel.plugin(...)` with a name, an `apply`, and nothing else — which
 * is the whole claim behind "everything is a plugin": the market is not built into the app, it is a feature the app
 * composes. Its service goes into the kernel under one name, so whatever needs it asks for the name and never imports
 * this file.
 */

import type { PluginDefinition } from '../../common/plugins/kernel';
import { MarketCatalogService, createFileMarketSourceStore, marketSourcesPath } from './catalog';

/** The name the market's service is provided under. */
export const MARKET_SERVICE = 'market';

export const marketCatalogPlugin: PluginDefinition<{ dataPath: string }> = {
  name: 'market-catalog',
  apply(ctx, config) {
    const dataPath = config?.dataPath;
    if (!dataPath) throw new Error('The market needs a data path to keep its sources in');
    const service = new MarketCatalogService({ store: createFileMarketSourceStore(marketSourcesPath(dataPath)) });
    ctx.provide(MARKET_SERVICE, service);
  },
};
