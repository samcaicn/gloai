/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The plugin market, wired to the rest of the app through the same bridge every other feature uses.
 *
 * The market is not special-cased here: it is loaded as a plugin through the kernel, exactly as any later feature
 * would be. The bridge answers are the wrapped `MarketResult` shape so a source that cannot be reached is a line in
 * the panel, not a thrown IPC error that loses the screen.
 */

import { getDataPath } from '../utils/utils';
import { PluginKernel } from '../../common/plugins/kernel';
import { type MarketCatalogService, BUILT_IN_MARKET_SOURCES } from './catalog';
import { MARKET_SERVICE, marketCatalogPlugin } from './marketPlugin';
import { MarketError } from '../../common/plugins/market';
import { pluginBridge, type MarketResult } from '../../common/plugins/bridge';

// Defined locally (not imported from kernel) so the production bundle never tree-shakes it away:
// the kernel re-exports it, but Rollup drops an imported binding it cannot prove is used, and the
// packaged app then throws `silentLogger is not defined` at startup.
const silentLogger = { info: () => undefined, warn: () => undefined, error: () => undefined };

/** Wraps a market call so the renderer always gets `{ ok, data }` or `{ ok, code, message }`. */
async function answer<T>(work: () => Promise<T>): Promise<MarketResult<T>> {
  try {
    return { ok: true, data: await work() };
  } catch (error) {
    if (error instanceof MarketError) return { ok: false, code: error.code, message: error.message };
    return { ok: false, code: 'unknown', message: error instanceof Error ? error.message : String(error) };
  }
}

export function initPluginBridge(): void {
  // The kernel is the app's plugin container. Loading the market through it — rather than constructing it here — is
  // the concrete claim that "everything is a plugin": a later feature adds itself the same way and asks for this
  // service by name, never importing this file.
  const kernel = new PluginKernel({ logger: silentLogger });
  void kernel.plugin(marketCatalogPlugin, { dataPath: getDataPath() });

  // `apply` ran synchronously (the plugin has no `inject` to wait on), so the service is already provided by now.
  const service = (): MarketCatalogService => {
    const found = kernel.get<MarketCatalogService>(MARKET_SERVICE);
    if (!found) throw new MarketError('contract', 'The plugin market is still starting up');
    return found;
  };

  pluginBridge.marketState.provider(() =>
    answer(async () => ({
      sources: await service().listSources(),
      builtIns: BUILT_IN_MARKET_SOURCES.map((source) => ({
        key: source.key,
        name: source.name,
        description: source.description,
        endpoint: source.endpoint,
        homepage: source.homepage,
      })),
    }))
  );

  pluginBridge.marketCatalog.provider((query) =>
    answer(() => service().fetch(query ?? {}, new AbortController().signal))
  );

  pluginBridge.marketSources.provider((mutation) => answer(() => service().mutate(mutation)));
}
