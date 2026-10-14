/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The market's catalogue: which sources exist, what they say, and how a page is asked for and checked.
 *
 * Two kinds of source, because that is what the ecosystem actually has:
 *
 * - **standard**: speaks DSH's published schema — a manifest at a registered HTTPS URL naming a `/v1/plugins`
 *   endpoint, answering with `catalog-provider-page`. Anyone can publish one, and one written for DSH works here;
 * - **1024store-v2**: DSH's partner catalogue, which answers in its own older shape. It is carried over so the app
 *   reaches the catalogue people already use, and it is read through the same narrow opening: only an npm package
 *   name is taken from it, never the install command it prints.
 *
 * Browsing is always available. Nothing here installs anything: an item describes itself, and whether this app can
 * act on it is decided elsewhere, by capabilities this service does not assume exist.
 */

import { randomUUID } from 'node:crypto';
import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import {
  type MarketCatalogResult,
  type MarketItem,
  type MarketProviderPage,
  type MarketQuery,
  type MarketSourceManifest,
  type MarketSourceMutation,
  type MarketSourceRecord,
  type MarketSourceView,
  MarketError,
  marketCatalogUrl,
  marketTrustRoot,
  normalizeMarketQuery,
  parseMarketProviderPage,
  parseMarketSourceManifest,
  safeMarketHref,
} from '../../common/plugins/market';
import { type MarketHttpClient, createCachedMarketHttp, createRestrictedMarketHttp } from './network';

export type BuiltInMarketSource = {
  key: string;
  name: string;
  description?: string;
  providerId: string;
  /** How to read it: the published schema, or DSH's partner catalogue's own v2 shape. */
  kind: 'standard' | '1024store-v2';
  endpoint: string;
  /** Where the manifest lives, for a standard source that publishes one. */
  manifestUrl?: string;
  /** Kept in the app instead, for a source that has no manifest to fetch. */
  manifest?: MarketSourceManifest;
  homepage?: string;
  attribution: { name: string; url: string; notice?: string };
  /**
   * A hostname allowed to resolve to a local proxy's fake addresses. Only ever a host this app chose in advance:
   * a source the person adds must not inherit it.
   */
  syntheticProxyHostname?: string;
};

/**
 * The sources built into the app. Being listed here is not a recommendation and not a review: it says these speak a
 * shape the app can read, and nothing about what is in them. None is enabled until the person picks one.
 */
export const BUILT_IN_MARKET_SOURCES: readonly BuiltInMarketSource[] = [
  {
    key: 'dshfind',
    name: 'dshfind',
    description: 'DSH community catalogue. Speaks the published catalog schema.',
    providerId: 'com.dshfind.catalog',
    kind: 'standard',
    endpoint: 'https://api.dshfind.com/market/v1/plugins',
    manifestUrl: 'https://api.dshfind.com/market/manifest.json',
    homepage: 'https://dshfind.com',
    attribution: {
      name: 'dshfind',
      url: 'https://dshfind.com',
      notice: 'Catalogue data provided by a cooperating provider.',
    },
    syntheticProxyHostname: 'api.dshfind.com',
  },
  {
    key: 'dsh-1024store',
    name: 'DSH 1024Store',
    description: 'DSH partner catalogue, read through its own v2 endpoint.',
    providerId: 'com.deepseek1024.catalog',
    kind: '1024store-v2',
    endpoint: 'https://deepseek1024.com/api/v2/plugins',
    homepage: 'https://deepseek1024.com',
    attribution: {
      name: 'DSH 1024Store',
      url: 'https://deepseek1024.com',
      notice: 'Catalogue data provided by a cooperating provider.',
    },
    syntheticProxyHostname: 'deepseek1024.com',
  },
];

export const builtInMarketSource = (key: string): BuiltInMarketSource | undefined =>
  BUILT_IN_MARKET_SOURCES.find((source) => source.key === key);

/** How the list of sources is kept between runs. */
export type MarketSourceStore = {
  load: () => Promise<MarketSourceRecord[]>;
  save: (records: readonly MarketSourceRecord[]) => Promise<void>;
};

/** Sources in a JSON file next to the app's data, written whole so a crash cannot leave half a list. */
export function createFileMarketSourceStore(filePath: string): MarketSourceStore {
  return {
    async load() {
      try {
        const parsed: unknown = JSON.parse(readFileSync(filePath, 'utf8'));
        return Array.isArray(parsed) ? (parsed as MarketSourceRecord[]) : [];
      } catch {
        return [];
      }
    },
    async save(records) {
      mkdirSync(dirname(filePath), { recursive: true });
      // Write aside, then move: a reader never sees a half-written file.
      const temporary = `${filePath}.${process.pid}.tmp`;
      writeFileSync(temporary, `${JSON.stringify(records, undefined, 2)}\n`, { encoding: 'utf8', flag: 'w' });
      renameSync(temporary, filePath);
    },
  };
}

/** A store kept in memory: the tests drive this, and it keeps them off the disk. */
export function createMemoryMarketSourceStore(initial: readonly MarketSourceRecord[] = []): MarketSourceStore {
  let records = [...initial];
  return {
    async load() {
      return [...records];
    },
    async save(next) {
      records = [...next];
    },
  };
}

/** One enabled source at most: the one the market shows. Older lists that enabled several collapse to the first. */
export function normalizeMarketSources(records: readonly MarketSourceRecord[]): MarketSourceRecord[] {
  const ordered = [...records].sort((left, right) => left.order - right.order);
  const active = ordered.find((record) => record.enabled)?.sourceRecordId;
  return ordered.map((record, index) => ({
    ...record,
    enabled: record.sourceRecordId === active,
    order: index,
  }));
}

const viewOf = (record: MarketSourceRecord): MarketSourceView => {
  const builtIn = record.builtInKey ? builtInMarketSource(record.builtInKey) : undefined;
  const manifest = record.manifest;
  return {
    ...record,
    name: manifest?.name ?? builtIn?.name ?? record.providerId,
    description: manifest?.description ?? builtIn?.description,
    endpoint: manifest?.transport.endpoint ?? builtIn?.endpoint ?? '',
    homepage: manifest?.homepage ?? builtIn?.homepage,
    attribution: manifest?.attribution ?? builtIn?.attribution,
  };
};

/** DSH 1024Store's v2 page: its own envelope around a list of plugins. */
type Store1024Page = {
  plugins: Array<Record<string, unknown>>;
  page: number;
  limit: number;
  total: number;
  totalPages: number;
  categories: string[];
  generatedAt: string;
};

const NPM_PACKAGE = /^(?:@[a-z0-9][a-z0-9._-]*\/)?[a-z0-9][a-z0-9._-]*$/;
const COMMAND_TOKEN = /^[A-Za-z0-9@:/._#+=-]+$/;
const GITHUB_OWNER = /^[A-Za-z0-9][A-Za-z0-9-]{0,99}$/;
const GITHUB_REPOSITORY = /^[A-Za-z0-9._-]{1,100}$/;

/**
 * Takes an npm package name out of the install command the catalogue displays, and throws the command away.
 *
 * The command is a string a third party wrote; running it would be running their code on their terms. Only the name it
 * names survives, and only if every token in it is one a package spec can be made of.
 */
function store1024PackageName(install: unknown): string | undefined {
  if (typeof install !== 'string' || install.length === 0 || install.length > 1024) return undefined;
  const tokens = install.trim().split(/\s+/);
  if (tokens.length !== 6) return undefined;
  if (tokens[0] !== 'dsh' || tokens[1] !== 'plugin' || tokens[2] !== '--profile' || tokens[4] !== 'add') return undefined;
  const profile = tokens[3];
  const name = tokens[5];
  if (!profile || !name || !COMMAND_TOKEN.test(profile) || !/^[A-Za-z0-9_-]+$/.test(profile)) return undefined;
  if (!COMMAND_TOKEN.test(name)) return undefined;
  return NPM_PACKAGE.test(name) ? name : undefined;
}

/** The repository behind an item, when the catalogue names one that looks like a GitHub project. */
function store1024Repository(url: unknown): { url: string } | undefined {
  if (typeof url !== 'string' || url.length === 0 || url.length > 1024) return undefined;
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return undefined;
  }
  if (parsed.protocol !== 'https:' || parsed.hostname !== 'github.com') return undefined;
  const [owner, repo] = parsed.pathname.split('/').filter(Boolean);
  if (!owner || !repo) return undefined;
  if (!GITHUB_OWNER.test(owner) || !GITHUB_REPOSITORY.test(repo.replace(/\.git$/, ''))) return undefined;
  return { url: `https://github.com/${owner}/${repo}` };
}

const store1024Text = (value: unknown, max: number): string | undefined => {
  if (typeof value !== 'string' || value.length === 0 || value.length > max) return undefined;
  // The same ban as the shared contract: control characters, and the bidi overrides that disguise a name.
  return /[\p{Cc}\p{Cf}‪-‮⁦-⁩]/u.test(value) ? undefined : value;
};

function parseStore1024Page(value: unknown): Store1024Page {
  const root = (typeof value === 'object' && value !== null ? value : {}) as Record<string, unknown>;
  if (!Array.isArray(root.plugins)) throw new MarketError('contract', 'The 1024Store page has no plugins');
  const number = (raw: unknown, fallback: number): number =>
    typeof raw === 'number' && Number.isSafeInteger(raw) && raw >= 0 ? raw : fallback;
  const limit = number(root.limit, 50);
  const totalPages = Math.max(number(root.totalPages, 1), 1);
  const page = Math.min(Math.max(number(root.page, 1), 1), totalPages);
  return {
    plugins: root.plugins.filter((entry): entry is Record<string, unknown> => typeof entry === 'object' && entry !== null),
    page,
    limit,
    total: number(root.total, root.plugins.length),
    totalPages,
    categories: Array.isArray(root.categories)
      ? root.categories.filter((entry): entry is string => typeof entry === 'string' && /^[a-z0-9][a-z0-9._:-]*$/.test(entry))
      : [],
    generatedAt: typeof root.generatedAt === 'string' ? root.generatedAt : new Date().toISOString(),
  };
}

function store1024ToProviderPage(page: Store1024Page): MarketProviderPage {
  const items: MarketItem[] = [];
  for (const raw of page.plugins) {
    const id = store1024Text(raw.id, 160);
    const name = store1024Text(raw.name, 160);
    if (!id || !name) continue;
    const summary = store1024Text(raw.description, 1000) ?? '';
    const packageName = store1024PackageName(raw.install);
    const repository = store1024Repository(raw.url);
    // An item that names neither a package nor a repository cannot be installed, and is not shown as if it could be.
    if (!packageName && !repository) continue;
    const item: MarketItem = { id, name, displayName: name, summary };
    if (packageName) item.package = { registry: 'npm', name: packageName };
    if (repository) item.repository = repository;
    const owner = store1024Text(raw.owner, 120);
    if (owner) item.publisher = { name: owner };
    const category = store1024Text(raw.category, 64);
    if (category) item.categories = [category];
    const pushedAt = store1024Text(raw.pushedAt, 40);
    if (pushedAt && !Number.isNaN(Date.parse(pushedAt))) item.updatedAt = new Date(pushedAt).toISOString();
    items.push(item);
  }
  const next = page.page < page.totalPages ? String(page.page + 1) : undefined;
  return {
    schemaVersion: '1.0.0',
    generatedAt: page.generatedAt,
    items,
    page: { total: page.total, ...(next === undefined ? {} : { nextCursor: next }) },
  };
}

export type MarketCatalogServiceOptions = {
  store: MarketSourceStore;
  http?: MarketHttpClient;
  now?: () => number;
};

/**
 * Holds the source list, answers queries against the enabled source, and never throws at a screen: a source that
 * fails reports itself in its own result, and one source's failure says nothing about another's.
 */
export class MarketCatalogService {
  private readonly store: MarketSourceStore;
  private readonly http: MarketHttpClient;
  private records: MarketSourceRecord[] = [];
  private loaded = false;

  constructor(options: MarketCatalogServiceOptions) {
    this.store = options.store;
    this.http =
      options.http ??
      createCachedMarketHttp(
        createRestrictedMarketHttp({
          syntheticProxyHostnames: BUILT_IN_MARKET_SOURCES.map((source) => source.syntheticProxyHostname).filter(
            (host): host is string => typeof host === 'string',
          ),
        }),
      );
  }

  private async sync(): Promise<MarketSourceRecord[]> {
    if (!this.loaded) {
      this.records = normalizeMarketSources(await this.store.load());
      this.loaded = true;
    }
    return this.records;
  }

  async listSources(): Promise<MarketSourceView[]> {
    return (await this.sync()).map(viewOf);
  }

  /** Adds, removes, reorders or selects a source. Returns the whole list, so a screen can redraw from one answer. */
  async mutate(mutation: MarketSourceMutation): Promise<MarketSourceView[]> {
    const records = await this.sync();
    let next = [...records];

    if (mutation.action === 'add-builtin') {
      const builtIn = builtInMarketSource(mutation.key);
      if (!builtIn) throw new MarketError('contract', 'Unknown built-in source');
      if (next.some((record) => record.builtInKey === mutation.key))
        throw new MarketError('contract', 'That source is already in the list');
      next.push({
        sourceRecordId: randomUUID(),
        registrationKind: 'built-in',
        providerId: builtIn.providerId,
        builtInKey: builtIn.key,
        ...(builtIn.manifest === undefined ? {} : { manifest: builtIn.manifest }),
        ...(builtIn.manifestUrl === undefined ? {} : { manifestUrl: builtIn.manifestUrl }),
        enabled: false,
        order: next.length,
      });
    } else if (mutation.action === 'add-standard') {
      // Checked before anything is fetched: an address this app would refuse to dial is never dialled.
      safeMarketHref(mutation.manifestUrl, 'The source manifest URL');
      if (next.some((record) => record.manifestUrl === mutation.manifestUrl))
        throw new MarketError('contract', 'That source is already in the list');
      next.push({
        sourceRecordId: randomUUID(),
        registrationKind: 'user-added',
        providerId: '',
        manifestUrl: mutation.manifestUrl,
        enabled: false,
        order: next.length,
      });
    } else if (mutation.action === 'select') {
      next = next.map((record) => ({ ...record, enabled: record.sourceRecordId === mutation.sourceRecordId }));
    } else if (mutation.action === 'move') {
      const ordered = [...next].sort((left, right) => left.order - right.order);
      const at = ordered.findIndex((record) => record.sourceRecordId === mutation.sourceRecordId);
      const to = mutation.direction === 'up' ? at - 1 : at + 1;
      if (at >= 0 && to >= 0 && to < ordered.length) {
        [ordered[at], ordered[to]] = [ordered[to] as MarketSourceRecord, ordered[at] as MarketSourceRecord];
        next = ordered.map((record, index) => ({ ...record, order: index }));
      }
    } else {
      next = next.filter((record) => record.sourceRecordId !== mutation.sourceRecordId);
    }

    next = normalizeMarketSources(next);
    this.records = next;
    await this.store.save(next);
    return next.map(viewOf);
  }

  /** The manifest of one source: the built-in copy when there is one, otherwise fetched from where it was registered. */
  private async manifestOf(record: MarketSourceRecord, signal: AbortSignal): Promise<MarketSourceManifest> {
    if (record.manifest) return record.manifest;
    const builtIn = record.builtInKey ? builtInMarketSource(record.builtInKey) : undefined;
    if (builtIn?.manifest) return builtIn.manifest;
    if (!record.manifestUrl) throw new MarketError('contract', 'The source has no manifest');

    const response = await this.http.getJson(record.manifestUrl, signal, {
      allowedOrigin: new URL(record.manifestUrl).origin,
    });
    const manifest = parseMarketSourceManifest(response.value);
    // The origin the person registered has to be the one the manifest came from and the one the catalog lives on.
    marketTrustRoot(record.manifestUrl, response.finalUrl, manifest.transport.endpoint);
    if (record.providerId && record.providerId !== manifest.providerId)
      throw new MarketError('contract', 'The source changed its provider identity since it was added');
    return manifest;
  }

  /** One page from one source, checked and narrowed to what this app will show. */
  private async fetchPage(record: MarketSourceRecord, query: MarketQuery, signal: AbortSignal) {
    const builtIn = record.builtInKey ? builtInMarketSource(record.builtInKey) : undefined;

    if (builtIn?.kind === '1024store-v2') {
      const origin = new URL(builtIn.endpoint).origin;
      const url = new URL(builtIn.endpoint);
      const asked = Math.min(Math.max(Math.trunc(query.limit ?? 50), 1), 200);
      url.searchParams.set('limit', String(asked));
      // The v2 endpoint numbers its pages; the cursor carries the number, and nothing else.
      const page = query.cursor ? Number.parseInt(query.cursor, 10) : 1;
      url.searchParams.set('page', String(Number.isSafeInteger(page) && page > 0 ? page : 1));
      if (query.q) url.searchParams.set('q', query.q);
      if (query.category?.length) url.searchParams.set('category', query.category[0] ?? '');

      const response = await this.http.getJson(url.href, signal, { allowedOrigin: origin });
      const parsed = parseStore1024Page(response.value);
      return { page: store1024ToProviderPage(parsed), categories: parsed.categories };
    }

    const manifest = await this.manifestOf(record, signal);
    const narrowed = normalizeMarketQuery(query, manifest);
    const url = marketCatalogUrl(manifest, narrowed);
    const response = await this.http.getJson(url.href, signal, { allowedOrigin: new URL(manifest.transport.endpoint).origin });
    if (new URL(response.finalUrl).origin !== new URL(manifest.transport.endpoint).origin)
      throw new MarketError('redirect', 'The catalog was served from another origin than the registered one');
    const limit = narrowed.limit ?? manifest.query.defaultLimit;
    return { page: parseMarketProviderPage(response.value, limit), categories: [] as string[] };
  }

  /**
   * Asks the enabled source. With no source selected there is nothing to ask, and no request goes out: an empty
   * market is an empty list, not an error.
   */
  async fetch(query: MarketQuery, signal: AbortSignal): Promise<MarketCatalogResult[]> {
    const records = await this.sync();
    const active = records.find((record) => record.enabled);
    if (!active) return [];

    const view = viewOf(active);
    const fetchedAt = new Date().toISOString();
    try {
      const { page, categories } = await this.fetchPage(active, query, signal);
      const all = new Set<string>(categories);
      for (const item of page.items) for (const category of item.categories ?? []) all.add(category);
      return [
        {
          sourceRecordId: active.sourceRecordId,
          providerId: active.providerId,
          name: view.name,
          items: page.items,
          nextCursor: page.page.nextCursor,
          total: page.page.total,
          categories: [...all],
          fetchedAt,
          stale: false,
        },
      ];
    } catch (error) {
      // A source that cannot answer says so in its own result. The panel keeps its previous page and marks it stale.
      return [
        {
          sourceRecordId: active.sourceRecordId,
          providerId: active.providerId,
          name: view.name,
          items: [],
          categories: [],
          fetchedAt,
          stale: true,
          error: error instanceof Error ? error.message : String(error),
        },
      ];
    }
  }

  /** Forgets a source's cached answers after it is changed, so the next read is not the old source's page. */
  invalidate(): void {
    this.loaded = false;
  }
}

export const marketSourcesPath = (dataPath: string): string => join(dataPath, 'plugins', 'market-sources.json');
