/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import { describe, expect, it } from 'vitest';
import type { MarketHttpClient, MarketHttpResponse } from '@/process/plugins/network';
import {
  MarketCatalogService,
  createMemoryMarketSourceStore,
  normalizeMarketSources,
  type MarketSourceRecord,
} from '@/process/plugins/catalog';

/** A fake HTTP client keyed by URL, so the service is exercised without any network. */
function fakeHttp(responses: Record<string, MarketHttpResponse>): MarketHttpClient {
  return {
    async getJson(url: string): Promise<MarketHttpResponse> {
      const found = responses[url];
      if (!found) throw new Error(`no fixture for ${url}`);
      return found;
    },
  };
}

const MANIFEST = {
  schemaVersion: '1.0.0',
  generatedAt: '2026-01-01T00:00:00.000Z',
  items: [
    { id: 'p1', name: 'p1', displayName: 'Plugin One', summary: 'Does a thing', package: { registry: 'npm', name: 'p1' }, categories: ['utility'] },
    { id: 'p2', name: 'p2', displayName: 'Plugin Two', summary: 'Does another', repository: { url: 'https://github.com/o/r2' } },
  ],
  page: { total: 2 },
};

const V2_PAGE = {
  plugins: [
    { id: 's1', name: 's1', description: '1024 item', install: 'dsh plugin --profile main add s1', owner: 'someone', category: 'tool', pushedAt: '2026-01-01T00:00:00Z' },
    { id: 's2', name: 's2', description: 'no source', owner: 'someone' },
  ],
  page: 1,
  limit: 50,
  total: 2,
  totalPages: 1,
  categories: ['tool'],
  generatedAt: '2026-01-01T00:00:00.000Z',
};

const http = fakeHttp({
  'https://api.dshfind.com/market/manifest.json': {
    value: {
      manifestVersion: '1.0.0',
      providerId: 'com.dshfind.catalog',
      name: 'dshfind',
      transport: { kind: 'https-json', endpoint: 'https://api.dshfind.com/market/v1/plugins', method: 'GET' },
      query: { supported: ['q', 'limit'], defaultLimit: 24, maxLimit: 100, sorts: [] },
      attribution: { name: 'dshfind', url: 'https://dshfind.com' },
    },
    finalUrl: 'https://api.dshfind.com/market/manifest.json',
  },
  'https://api.dshfind.com/market/v1/plugins?limit=24': {
    value: MANIFEST,
    finalUrl: 'https://api.dshfind.com/market/v1/plugins?limit=24',
  },
  'https://deepseek1024.com/api/v2/plugins?limit=50&page=1': {
    value: V2_PAGE,
    finalUrl: 'https://deepseek1024.com/api/v2/plugins?limit=50&page=1',
  },
});

describe('normalizeMarketSources', () => {
  it('collapses multiple enabled sources to the first and renumbers order', () => {
    const records: MarketSourceRecord[] = [
      { sourceRecordId: 'a', registrationKind: 'built-in', providerId: 'a', enabled: true, order: 0 },
      { sourceRecordId: 'b', registrationKind: 'built-in', providerId: 'b', enabled: true, order: 1 },
      { sourceRecordId: 'c', registrationKind: 'built-in', providerId: 'c', enabled: false, order: 2 },
    ];
    const next = normalizeMarketSources(records);
    expect(next.filter((record) => record.enabled).map((record) => record.sourceRecordId)).toEqual(['a']);
    expect(next.map((record) => record.order)).toEqual([0, 1, 2]);
  });
});

describe('MarketCatalogService', () => {
  it('starts empty, then a built-in source can be added, selected, and browsed', async () => {
    const service = new MarketCatalogService({ store: createMemoryMarketSourceStore(), http });
    expect(await service.listSources()).toEqual([]);

    const afterAdd = await service.mutate({ action: 'add-builtin', key: 'dshfind' });
    expect(afterAdd).toHaveLength(1);
    expect(afterAdd[0]?.builtInKey).toBe('dshfind');

    const afterSelect = await service.mutate({ action: 'select', sourceRecordId: afterAdd[0]!.sourceRecordId });
    expect(afterSelect[0]?.enabled).toBe(true);

    const [page] = await service.fetch({}, new AbortController().signal);
    expect(page.items).toHaveLength(2);
    expect(page.error).toBeUndefined();
    expect(page.items[0]?.id).toBe('p1');
  });

  it('reads a DSH 1024Store v2 source and keeps only items with a name or a repository', async () => {
    const service = new MarketCatalogService({ store: createMemoryMarketSourceStore(), http });
    const added = await service.mutate({ action: 'add-builtin', key: 'dsh-1024store' });
    await service.mutate({ action: 'select', sourceRecordId: added[0]!.sourceRecordId });
    const [page] = await service.fetch({}, new AbortController().signal);
    // s2 has neither a package nor a repository, so it is dropped.
    expect(page.items.map((item) => item.id)).toEqual(['s1']);
    expect(page.items[0]?.package?.name).toBe('s1');
  });

  it('reports a source failure in its own result rather than throwing', async () => {
    const failingHttp: MarketHttpClient = {
      async getJson() {
        throw new Error('network down');
      },
    };
    const service = new MarketCatalogService({ store: createMemoryMarketSourceStore(), http: failingHttp });
    const added = await service.mutate({ action: 'add-builtin', key: 'dshfind' });
    await service.mutate({ action: 'select', sourceRecordId: added[0]!.sourceRecordId });
    const [page] = await service.fetch({}, new AbortController().signal);
    expect(page.items).toEqual([]);
    expect(page.stale).toBe(true);
    expect(page.error).toContain('network down');
  });

  it('rejects adding the same built-in source twice', async () => {
    const service = new MarketCatalogService({ store: createMemoryMarketSourceStore(), http });
    await service.mutate({ action: 'add-builtin', key: 'dshfind' });
    await expect(service.mutate({ action: 'add-builtin', key: 'dshfind' })).rejects.toThrow();
  });

  it('rejects a custom source whose manifest URL is not HTTPS', async () => {
    const service = new MarketCatalogService({ store: createMemoryMarketSourceStore(), http });
    await expect(service.mutate({ action: 'add-standard', manifestUrl: 'http://evil.test/m.json' })).rejects.toThrow();
  });

  it('moves and removes sources', async () => {
    const service = new MarketCatalogService({ store: createMemoryMarketSourceStore(), http });
    const a = await service.mutate({ action: 'add-builtin', key: 'dshfind' });
    const b = await service.mutate({ action: 'add-builtin', key: 'dsh-1024store' });
    expect((await service.listSources()).map((source) => source.providerId)).toEqual(['com.dshfind.catalog', 'com.deepseek1024.catalog']);

    // `mutate` returns the whole list, so the most recently added source is the last entry.
    const bRecordId = b[b.length - 1]!.sourceRecordId;
    await service.mutate({ action: 'move', sourceRecordId: bRecordId, direction: 'up' });
    expect((await service.listSources()).map((source) => source.providerId)).toEqual(['com.deepseek1024.catalog', 'com.dshfind.catalog']);

    await service.mutate({ action: 'remove', sourceRecordId: bRecordId });
    expect((await service.listSources()).map((source) => source.providerId)).toEqual(['com.dshfind.catalog']);
  });
});
