/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The market's channels, in the same shape the rest of the app's bridges use.
 *
 * Answers are wrapped rather than thrown: a source that cannot be reached is a thing the panel shows, not an
 * exception that loses the screen. `ok: false` carries a code a screen can translate and a message for the log.
 */

import { bridge } from '../platform/bridge';
import type {
  MarketCatalogResult,
  MarketErrorCode,
  MarketQuery,
  MarketSourceMutation,
  MarketSourceView,
} from './market';

export type MarketResult<T> = { ok: true; data: T } | { ok: false; code: MarketErrorCode | 'unknown'; message: string };

/** A built-in source as a screen lists it, before it is added. */
export type MarketBuiltInView = {
  key: string;
  name: string;
  description?: string;
  endpoint: string;
  homepage?: string;
};

export type MarketState = {
  sources: MarketSourceView[];
  builtIns: MarketBuiltInView[];
};

export const pluginBridge = {
  /** Which sources are configured, and which are offered built in. */
  marketState: bridge.buildProvider<MarketResult<MarketState>, void>('plugins.market.state'),
  /** One page from the selected source. Empty when none is selected. */
  marketCatalog: bridge.buildProvider<MarketResult<MarketCatalogResult[]>, MarketQuery>('plugins.market.catalog'),
  /** Add, remove, reorder or select a source; answers with the whole list. */
  marketSources: bridge.buildProvider<MarketResult<MarketSourceView[]>, MarketSourceMutation>(
    'plugins.market.sources',
  ),
};

/** Turns a wrapped answer back into a value, or throws what the main process threw. */
export function unwrapMarket<T>(result: MarketResult<T>): T {
  // `=== false`, not `!result.ok`: without strictNullChecks TypeScript narrows a union only on an equality check.
  if (result.ok === false) throw new Error(result.message);
  return result.data;
}
