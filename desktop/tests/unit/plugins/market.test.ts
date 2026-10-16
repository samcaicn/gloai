/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import { describe, expect, it } from 'vitest';
import {
  MarketError,
  normalizeMarketQuery,
  parseMarketProviderPage,
  parseMarketSourceManifest,
  safeMarketHref,
  safeMarketUrl,
} from '@/common/plugins/market';

describe('safeMarketUrl / safeMarketHref', () => {
  it('accepts a clean HTTPS URL on port 443', () => {
    expect(safeMarketUrl('https://api.dshfind.com/market/v1/plugins').href).toBe(
      'https://api.dshfind.com/market/v1/plugins'
    );
  });

  it('rejects non-HTTPS, a custom port, credentials and a fragment', () => {
    expect(() => safeMarketUrl('http://api.dshfind.com/x')).toThrow(MarketError);
    expect(() => safeMarketUrl('https://api.dshfind.com:8080/x')).toThrow(MarketError);
    expect(() => safeMarketUrl('https://user@api.dshfind.com/x')).toThrow(MarketError);
    expect(() => safeMarketUrl('https://api.dshfind.com/x#frag')).toThrow(MarketError);
  });

  it('safeMarketHref also rejects a query string', () => {
    expect(() => safeMarketHref('https://api.dshfind.com/x?q=1')).toThrow(MarketError);
  });
});

describe('parseMarketSourceManifest', () => {
  const base = {
    manifestVersion: '1.0.0',
    providerId: 'com.example.catalog',
    name: 'Example',
    transport: { kind: 'https-json', endpoint: 'https://api.example.com/market/v1/plugins', method: 'GET' },
    query: { supported: ['q', 'limit'], defaultLimit: 24, maxLimit: 100, sorts: [] },
    attribution: { name: 'Example', url: 'https://example.com' },
  };

  it('accepts a well-formed manifest', () => {
    const manifest = parseMarketSourceManifest(base);
    expect(manifest.providerId).toBe('com.example.catalog');
    expect(manifest.transport.endpoint).toBe('https://api.example.com/market/v1/plugins');
  });

  it('rejects an endpoint that is not HTTPS, not on 443, or not ending in /v1/plugins', () => {
    expect(() => parseMarketSourceManifest({ ...base, transport: { kind: 'https-json', endpoint: 'http://api.example.com/market/v1/plugins', method: 'GET' } })).toThrow(MarketError);
    expect(() => parseMarketSourceManifest({ ...base, transport: { kind: 'https-json', endpoint: 'https://api.example.com:8443/market/v1/plugins', method: 'GET' } })).toThrow(MarketError);
    expect(() => parseMarketSourceManifest({ ...base, transport: { kind: 'https-json', endpoint: 'https://api.example.com/market/plugins', method: 'GET' } })).toThrow(MarketError);
  });

  it('rejects an unknown query feature and a default limit above the max', () => {
    expect(() => parseMarketSourceManifest({ ...base, query: { supported: ['bogus'], defaultLimit: 10, maxLimit: 50, sorts: [] } })).toThrow(MarketError);
    expect(() => parseMarketSourceManifest({ ...base, query: { supported: ['q'], defaultLimit: 200, maxLimit: 50, sorts: [] } })).toThrow(MarketError);
  });
});

describe('parseMarketProviderPage', () => {
  it('rejects a page with no items, an item with no source, or a duplicate id', () => {
    expect(() => parseMarketProviderPage({ schemaVersion: '1.0.0' }, 24)).toThrow(MarketError);
    expect(() =>
      parseMarketProviderPage({ schemaVersion: '1.0.0', items: [{ id: 'a', name: 'a', displayName: 'a', summary: 's' }] }, 24)
    ).toThrow(MarketError);
    expect(() =>
      parseMarketProviderPage(
        {
          schemaVersion: '1.0.0',
          items: [
            { id: 'a', name: 'a', displayName: 'a', summary: 's', repository: { url: 'https://github.com/o/r' } },
            { id: 'a', name: 'a', displayName: 'a', summary: 's', repository: { url: 'https://github.com/o/r' } },
          ],
        },
        24
      )
    ).toThrow(MarketError);
  });

  it('rejects a github install source whose commit is not a full 40-char hash', () => {
    expect(() =>
      parseMarketProviderPage(
        {
          schemaVersion: '1.0.0',
          items: [
            {
              id: 'a',
              name: 'a',
              displayName: 'a',
              summary: 's',
              repository: { url: 'https://github.com/o/r' },
              installSource: { kind: 'github', commit: 'abc123' },
            },
          ],
        },
        24
      )
    ).toThrow(MarketError);
  });

  it('rejects more items than were asked for', () => {
    expect(() =>
      parseMarketProviderPage(
        { schemaVersion: '1.0.0', items: [{ id: 'a', name: 'a', displayName: 'a', summary: 's', package: { registry: 'npm', name: 'a' } }] },
        0
      )
    ).toThrow(MarketError);
  });
});

describe('normalizeMarketQuery', () => {
  const manifest = parseMarketSourceManifest({
    manifestVersion: '1.0.0',
    providerId: 'com.example.catalog',
    name: 'Example',
    transport: { kind: 'https-json', endpoint: 'https://api.example.com/market/v1/plugins', method: 'GET' },
    query: { supported: ['q', 'limit'], defaultLimit: 24, maxLimit: 50, sorts: [] },
    attribution: { name: 'Example', url: 'https://example.com' },
  });

  it('drops unsupported features and clamps the limit to the source maximum', () => {
    const query = normalizeMarketQuery({ q: 'x', category: ['y'], limit: 999 }, manifest);
    expect(query.q).toBe('x');
    expect(query.category).toBeUndefined();
    expect(query.limit).toBe(50);
  });
});
