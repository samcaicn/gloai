/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The HTTP client the market is allowed to use — the part of DSH Desktop's `restricted-http` worth carrying over
 * whole, because it is where a catalog stops being data and starts being an attack.
 *
 * A catalog URL is a third party's choice of address, so it is treated as hostile:
 *
 * - **private addresses are refused**: loopback, RFC 1918, link-local, CGNAT, multicast and the rest. A market must
 *   not be able to make this app read its own machine's services;
 * - **DNS is pinned**: the name is resolved once, every answer is checked, and that address is what the socket
 *   connects to. A name that resolves to a safe address and then to a private one between the check and the
 *   connection — DNS rebinding — cannot get through;
 * - **redirects are bounded and origin-locked**: at most three, and a jump to another origin is refused before the
 *   request goes out when the caller named the origin it expects;
 * - **answers must be JSON**, uncompressed, and under a size cap. A catalog that answers with something else is not
 *   asked again;
 * - **every stage has a deadline**: connect, first byte, and the whole exchange. A slow source stalls one panel, not
 *   the app.
 *
 * One deliberate exception: a local proxy in fake-IP mode answers with benchmark addresses (198.18.0.0/15) instead of
 * real ones, which would otherwise look like an attack. That is allowed — but only for hostnames named in advance
 * (`syntheticProxyHostnames`), never for a source the person added. DSH draws the same line.
 */

import { lookup as dnsLookup } from 'node:dns/promises';
import { request as httpsRequest } from 'node:https';
import type { IncomingMessage } from 'node:http';
import { BlockList, isIP } from 'node:net';
import { MarketError } from '../../common/plugins/market';

const CONNECT_TIMEOUT_MS = 8_000;
const FIRST_BYTE_TIMEOUT_MS = 12_000;
const TOTAL_TIMEOUT_MS = 30_000;
const MAX_REDIRECTS = 3;
const DEFAULT_MAX_BODY_BYTES = 2 * 1024 * 1024;

/** Every address a market must never reach: this machine, the LAN, and the ranges that are not public. */
const BLOCKED = new BlockList();
for (const [address, prefix] of [
  ['0.0.0.0', 8],
  ['10.0.0.0', 8],
  ['100.64.0.0', 10],
  ['127.0.0.0', 8],
  ['169.254.0.0', 16],
  ['172.16.0.0', 12],
  ['192.0.0.0', 24],
  ['192.168.0.0', 16],
  ['198.18.0.0', 15],
  ['224.0.0.0', 3],
] as const) {
  BLOCKED.addSubnet(address, prefix, 'ipv4');
}
for (const [address, prefix] of [
  ['::', 128],
  ['::1', 128],
  ['fc00::', 7],
  ['fe80::', 10],
  ['ff00::', 8],
] as const) {
  BLOCKED.addSubnet(address, prefix, 'ipv6');
}

export type MarketHttpPolicy = {
  /** When set, a redirect to any other origin is refused before the request goes out. */
  allowedOrigin?: string;
  maxBodyBytes?: number;
};

export type MarketHttpResponse = { value: unknown; finalUrl: string };

export type MarketHttpClient = {
  getJson: (url: string, signal: AbortSignal, policy?: MarketHttpPolicy) => Promise<MarketHttpResponse>;
};

export type RestrictedHttpOptions = {
  /** Hostnames allowed to resolve to a local proxy's fake addresses. Never a source the person added. */
  syntheticProxyHostnames?: readonly string[];
  maxBodyBytes?: number;
  /** Injectable in tests, so a catalog can be served from a local server without touching real DNS. */
  resolve?: (hostname: string) => Promise<readonly { address: string; family: number }[]>;
};

const familyOf = (address: string): number => (isIP(address) === 6 ? 6 : 4);

function assertSafeAddress(address: string, allowSyntheticProxy: boolean): number {
  const family = familyOf(address);
  if (BLOCKED.check(address, family === 6 ? 'ipv6' : 'ipv4')) {
    // 198.18.0.0/15 is the only blocked range a local proxy legitimately answers with.
    const synthetic = family === 4 && address.startsWith('198.18.');
    if (!(synthetic && allowSyntheticProxy)) throw new MarketError('blocked-address', `Refused address ${address}`);
  }
  return family;
}

type Pinned = { address: string; family: number };

/**
 * Resolves a name once and checks every answer. One unsafe answer among several is enough to refuse: a name with a
 * safe address and a private one is exactly how rebinding works.
 */
async function pinAddress(
  hostname: string,
  syntheticProxyHostnames: ReadonlySet<string>,
  resolve: RestrictedHttpOptions['resolve']
): Promise<Pinned> {
  const literal = hostname.replace(/^\[|\]$/g, '');
  if (isIP(literal)) return { address: literal, family: assertSafeAddress(literal, false) };

  const allowed = syntheticProxyHostnames.has(hostname.toLowerCase());
  const answers = resolve ? await resolve(hostname) : await dnsLookup(hostname, { all: true, verbatim: true });
  if (answers.length === 0) throw new MarketError('blocked-address', `${hostname} resolved to nothing`);

  const first = answers[0];
  if (!first) throw new MarketError('blocked-address', `${hostname} resolved to nothing`);
  for (const answer of answers) assertSafeAddress(answer.address, allowed);
  return { address: first.address, family: assertSafeAddress(first.address, allowed) };
}

type RawResponse = { statusCode: number; headers: IncomingMessage['headers']; body: Buffer };

function get(url: URL, signal: AbortSignal, pinned: Pinned, maxBodyBytes: number): Promise<RawResponse> {
  return new Promise((resolve, reject) => {
    let settled = false;
    const finish = (fn: () => void): void => {
      if (settled) return;
      settled = true;
      fn();
    };

    const request = httpsRequest(
      {
        protocol: url.protocol,
        hostname: url.hostname,
        port: url.port || 443,
        path: `${url.pathname}${url.search}`,
        method: 'GET',
        // The resolved address is what gets connected to: the name is not looked up a second time.
        lookup: (_hostname, options, callback) => {
          if (options.all) callback(null, [{ address: pinned.address, family: pinned.family }]);
          else callback(null, pinned.address, pinned.family);
        },
        servername: url.hostname,
        headers: {
          accept: 'application/json',
          'accept-encoding': 'identity',
          'user-agent': 'aibuddy-market/0.1',
        },
        timeout: CONNECT_TIMEOUT_MS,
      },
      (response) => {
        const chunks: Buffer[] = [];
        let size = 0;
        response.on('data', (chunk: Buffer) => {
          size += chunk.length;
          if (size > maxBodyBytes) {
            finish(() => {
              response.destroy();
              request.destroy();
              reject(new MarketError('response', 'The answer was larger than a catalog may be'));
            });
            return;
          }
          chunks.push(chunk);
        });
        response.on('end', () =>
          finish(() =>
            resolve({ statusCode: response.statusCode ?? 0, headers: response.headers, body: Buffer.concat(chunks) })
          )
        );
        response.on('error', (error: NodeJS.ErrnoException) => finish(() => reject(error)));
      }
    );

    const abort = (): void => {
      finish(() => {
        request.destroy();
        reject(new MarketError('timeout', 'The source took too long'));
      });
    };
    const firstByte = setTimeout(abort, FIRST_BYTE_TIMEOUT_MS);
    request.on('response', () => clearTimeout(firstByte));
    request.on('timeout', abort);
    request.on('error', (error: NodeJS.ErrnoException) => finish(() => reject(error)));
    signal.addEventListener('abort', abort, { once: true });
    request.end();
  });
}

/** A URL this client will dial. Rejected before any socket opens. */
function assertFetchable(url: URL): void {
  if (url.protocol !== 'https:') throw new MarketError('invalid-url', 'A market source must use HTTPS');
  if (url.username || url.password) throw new MarketError('invalid-url', 'A market URL must not carry credentials');
  if (url.port && url.port !== '443') throw new MarketError('invalid-url', 'A market URL must use port 443');
}

/** Runs `work` under one overall deadline, so no combination of stages can hang the panel. */
async function withDeadline<T>(work: Promise<T>, signal: AbortSignal): Promise<T> {
  let timer: NodeJS.Timeout | undefined;
  const deadline = new Promise<never>((_resolve, reject) => {
    timer = setTimeout(() => reject(new MarketError('timeout', 'The source took too long')), TOTAL_TIMEOUT_MS);
  });
  const aborted = new Promise<never>((_resolve, reject) => {
    signal.addEventListener('abort', () => reject(new MarketError('timeout', 'The request was cancelled')), {
      once: true,
    });
  });
  try {
    return await Promise.race([work, deadline, aborted]);
  } finally {
    if (timer) clearTimeout(timer);
  }
}

export function createRestrictedMarketHttp(options: RestrictedHttpOptions = {}): MarketHttpClient {
  const synthetic = new Set((options.syntheticProxyHostnames ?? []).map((host) => host.toLowerCase()));
  const defaultMaxBodyBytes = options.maxBodyBytes ?? DEFAULT_MAX_BODY_BYTES;

  const getJson = async (
    start: string,
    signal: AbortSignal,
    policy: MarketHttpPolicy = {},
    redirects = 0
  ): Promise<MarketHttpResponse> => {
    const url = new URL(start);
    assertFetchable(url);
    if (policy.allowedOrigin !== undefined && url.origin !== policy.allowedOrigin)
      throw new MarketError('redirect', 'The source redirected to another origin');
    if (redirects > MAX_REDIRECTS) throw new MarketError('redirect', 'The source redirected too many times');

    const pinned = await pinAddress(url.hostname, synthetic, options.resolve);
    const response = await withDeadline(get(url, signal, pinned, policy.maxBodyBytes ?? defaultMaxBodyBytes), signal);

    if (response.statusCode >= 300 && response.statusCode < 400) {
      const location = response.headers.location;
      if (typeof location !== 'string') throw new MarketError('redirect', 'The source redirected with no location');
      return await getJson(new URL(location, url).href, signal, policy, redirects + 1);
    }
    if (response.statusCode < 200 || response.statusCode >= 300)
      throw new MarketError('http', `The source answered ${response.statusCode}`);

    const contentType = String(response.headers['content-type'] ?? '');
    const encoding = response.headers['content-encoding'];
    if (!/^(?:application\/json|application\/[^;]+\+json)(?:;|$)/i.test(contentType))
      throw new MarketError('response', 'The source did not answer with JSON');
    // A compressed answer is refused rather than decoded: it is the usual way to hide how big a body really is.
    if (encoding !== undefined && encoding !== 'identity')
      throw new MarketError('response', 'The source answered with an unsupported encoding');

    try {
      return { value: JSON.parse(response.body.toString('utf8')) as unknown, finalUrl: url.href };
    } catch {
      throw new MarketError('response', 'The source answered with JSON that does not parse');
    }
  };

  return { getJson };
}

export type CachedHttpOptions = { ttlMs?: number; now?: () => number };

/**
 * Caches answers for a short while and merges concurrent requests for the same URL into one. A market panel re-renders
 * often; without this, one keystroke in the search box is one request per keystroke per source.
 */
export function createCachedMarketHttp(delegate: MarketHttpClient, options: CachedHttpOptions = {}): MarketHttpClient {
  const ttlMs = options.ttlMs ?? 5 * 60 * 1000;
  const now = options.now ?? (() => Date.now());
  const cache = new Map<string, { value: MarketHttpResponse; savedAt: number }>();
  const inFlight = new Map<string, Promise<MarketHttpResponse>>();

  return {
    async getJson(url, signal, policy = {}) {
      const key = `${policy.allowedOrigin ?? ''}\0${url}`;
      if (policy.maxBodyBytes === undefined) {
        const cached = cache.get(key);
        if (cached && now() - cached.savedAt < ttlMs) return cached.value;
      }
      const pending = inFlight.get(key);
      if (pending) return await pending;

      const request = delegate
        .getJson(url, signal, policy)
        .then((value) => {
          cache.set(key, { value, savedAt: now() });
          return value;
        })
        .finally(() => inFlight.delete(key));
      inFlight.set(key, request);
      return await request;
    },
  };
}
