/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import { describe, expect, it } from 'vitest';
import { MarketError } from '@/common/plugins/market';
import { createCachedMarketHttp, createRestrictedMarketHttp } from '@/process/plugins/network';

describe('createRestrictedMarketHttp — address and URL gate', () => {
  const client = createRestrictedMarketHttp();

  it('refuses non-HTTPS and a custom port before any connection', async () => {
    await expect(client.getJson('http://api.example.com/x', new AbortController().signal)).rejects.toThrow(MarketError);
    await expect(client.getJson('https://api.example.com:8443/x', new AbortController().signal)).rejects.toThrow(
      MarketError
    );
  });

  it('refuses private and loopback addresses (IP literals never reach a socket)', async () => {
    await expect(client.getJson('https://127.0.0.1/x', new AbortController().signal)).rejects.toThrow(MarketError);
    await expect(client.getJson('https://10.0.0.5/x', new AbortController().signal)).rejects.toThrow(MarketError);
    await expect(client.getJson('https://192.168.1.1/x', new AbortController().signal)).rejects.toThrow(MarketError);
    await expect(client.getJson('https://[::1]/x', new AbortController().signal)).rejects.toThrow(MarketError);
  });
});

describe('createRestrictedMarketHttp — DNS pinning with an injected resolver', () => {
  it('blocks a name that resolves to a private address', async () => {
    const client = createRestrictedMarketHttp({
      resolve: async () => [{ address: '10.0.0.9', family: 4 }],
    });
    await expect(client.getJson('https://evil.test/x', new AbortController().signal)).rejects.toThrow(MarketError);
  });

  it('blocks a name that resolves to a mix of safe and private addresses', async () => {
    const client = createRestrictedMarketHttp({
      resolve: async () => [
        { address: '93.184.216.34', family: 4 },
        { address: '192.168.0.9', family: 4 },
      ],
    });
    await expect(client.getJson('https://rebinding.test/x', new AbortController().signal)).rejects.toThrow(MarketError);
  });

  it('allows a name that resolves only to public addresses (the gate does not reject it)', async () => {
    const client = createRestrictedMarketHttp({
      // 203.0.113.0/24 is TEST-NET-3 (RFC 5737): a public-range address that routes nowhere, so it passes
      // the gate and the socket then fails downstream — which is not this gate refusing the address.
      resolve: async () => [{ address: '203.0.113.1', family: 4 }],
    });
    let caught: unknown;
    try {
      // A short self-aborting signal ends the test fast; whatever it rejected with, the gate must not have blocked it.
      await client.getJson('https://example.test/x', AbortSignal.timeout(300));
    } catch (error) {
      caught = error;
    }
    // Whatever the downstream connection did, the address gate must not have blocked it.
    if (caught instanceof MarketError) {
      expect(['blocked-address', 'invalid-url']).not.toContain(caught.code);
    }
  });
});

describe('createCachedMarketHttp', () => {
  it('serves a cached answer on the second call and dedupes concurrent requests', async () => {
    let calls = 0;
    const delegate = {
      async getJson(url: string) {
        calls += 1;
        return { value: { url }, finalUrl: url };
      },
    };
    const client = createCachedMarketHttp(delegate as never, { ttlMs: 10_000 });

    const first = await client.getJson('https://a.test/x', new AbortController().signal);
    const second = await client.getJson('https://a.test/x', new AbortController().signal);
    expect(first).toBe(second);
    expect(calls).toBe(1);

    const [c1, c2] = await Promise.all([
      client.getJson('https://b.test/x', new AbortController().signal),
      client.getJson('https://b.test/x', new AbortController().signal),
    ]);
    expect(c1).toBe(c2);
    expect(calls).toBe(2);
  });
});
