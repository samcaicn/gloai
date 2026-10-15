/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The plugin market's contracts, kept compatible with DSH Desktop's published catalog schema on purpose.
 *
 * DSH ships the schema as four JSON Schema files (`catalog-source`, `catalog-provider-page`, `catalog-snapshot`,
 * `catalog-query`) and validates with Ajv. Adding Ajv here would mean a new runtime dependency for a handful of
 * checks, so the same rules are written out by hand below — same field names, same limits, same rejections. A data
 * source written for DSH is accepted here, and one written for this app is accepted there.
 *
 * The rules that matter are the ones about trust, not about shape:
 *
 * - a source is HTTPS on port 443, with no credentials, no query and no fragment, and its catalog path ends in
 *   `/v1/plugins` — checked before a request goes out;
 * - the manifest the person registered, the endpoint it names, and the catalog it serves must all sit on one origin.
 *   A source may not hand the app a different host halfway through;
 * - the catalog is untrusted input. Nothing in it is a command to run: an item says *what* it is, and this app
 *   decides how, or whether, it would be installed.
 *
 * Pure TypeScript, no dependencies: the shared layer, imported by both processes.
 */

/** A data source as its publisher describes it: what it is called, and where its catalog lives. */
export type MarketSourceManifest = {
  manifestVersion: '1.0.0';
  providerId: string;
  name: string;
  description?: string;
  homepage?: string;
  attribution: { name: string; url: string; notice?: string };
  transport: { kind: 'https-json'; endpoint: string; method: 'GET' };
  query: {
    supported: MarketQueryFeature[];
    defaultLimit: number;
    maxLimit: number;
    sorts: MarketSort[];
  };
};

export type MarketQueryFeature = 'q' | 'category' | 'capability' | 'cursor' | 'limit' | 'sort' | 'locale';
export type MarketSort = 'relevance' | 'updated' | 'name' | 'downloads';

/** One plugin in a catalog. Only `id`, `name`, `displayName` and `summary` are required. */
export type MarketItem = {
  id: string;
  name: string;
  displayName: string;
  summary: string;
  description?: string;
  homepage?: string;
  latestVersion?: string;
  license?: string;
  categories?: string[];
  keywords?: string[];
  repository?: { url: string; subdirectory?: string };
  /** A release pinned to one commit: the only way an item may name code to fetch. */
  installSource?: { kind: 'github'; commit: string };
  package?: { registry: 'npm'; name: string };
  publisher?: { name: string; url?: string };
  capabilities?: { required?: string[]; optional?: string[] };
  compatibility?: { apiVersion?: string; hosts?: string[] };
  updatedAt?: string;
};

/** What `GET /v1/plugins` answers: one page of items, and how to ask for the next. */
export type MarketProviderPage = {
  schemaVersion: '1.0.0';
  generatedAt?: string;
  revision?: string;
  items: MarketItem[];
  page: { nextCursor?: string; total?: number };
};

export type MarketQuery = {
  q?: string;
  category?: string[];
  capability?: string[];
  cursor?: string;
  limit?: number;
  sort?: MarketSort;
  locale?: string;
};

/** A source as this app keeps it: the manifest, plus bookkeeping that is ours, never the publisher's. */
export type MarketSourceRecord = {
  sourceRecordId: string;
  registrationKind: 'built-in' | 'user-added';
  providerId: string;
  /** Set for a user-added source: the manifest URL the person gave. */
  manifestUrl?: string;
  /** Set for a built-in source: which of `BUILT_IN_MARKET_SOURCES` it is. */
  builtInKey?: string;
  manifest?: MarketSourceManifest;
  enabled: boolean;
  order: number;
};

/** What a screen shows about a source: its record, plus the display fields pulled out of the manifest. */
export type MarketSourceView = MarketSourceRecord & {
  name: string;
  description?: string;
  endpoint: string;
  homepage?: string;
  attribution?: { name: string; url: string; notice?: string };
};

/** One source's answer to a query. `error` is set instead of `items` when it could not answer. */
export type MarketCatalogResult = {
  sourceRecordId: string;
  providerId: string;
  name: string;
  items: MarketItem[];
  nextCursor?: string;
  total?: number;
  categories: string[];
  fetchedAt: string;
  /** Served from the cache because the live fetch failed or has not run. */
  stale: boolean;
  error?: string;
};

export type MarketSourceMutation =
  | { action: 'add-builtin'; key: string }
  | { action: 'add-standard'; manifestUrl: string }
  | { action: 'select'; sourceRecordId: string }
  | { action: 'move'; sourceRecordId: string; direction: 'up' | 'down' }
  | { action: 'remove'; sourceRecordId: string };

export type MarketErrorCode =
  | 'invalid-url'
  | 'blocked-address'
  | 'timeout'
  | 'redirect'
  | 'http'
  | 'response'
  | 'contract';

export class MarketError extends Error {
  readonly code: MarketErrorCode;

  constructor(code: MarketErrorCode, message: string) {
    super(message);
    this.name = 'MarketError';
    this.code = code;
  }
}

const PROVIDER_ID = /^[a-z0-9]+(?:[.-][a-z0-9]+)+$/;
const ITEM_ID = /^[A-Za-z0-9][A-Za-z0-9._:/@+-]*$/;
const CATEGORY_ID = /^[a-z0-9][a-z0-9._:-]*$/;
/** Control and formatting characters, and the bidi overrides that let a name read as something it is not. */
const UNSAFE_TEXT = /[\p{Cc}\p{Cf}‪-‮⁦-⁩]/u;
const QUERY_FEATURES: readonly MarketQueryFeature[] = ['q', 'category', 'capability', 'cursor', 'limit', 'sort', 'locale'];
const SORTS: readonly MarketSort[] = ['relevance', 'updated', 'name', 'downloads'];
const LOCALE = /^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$/;

// A function declaration, not a const arrow: TypeScript only treats a call as terminating control flow -- and so
// narrows the value that was just checked -- when the callee is declared this way with an explicit `never`.
function fail(what: string): never {
  throw new MarketError('contract', what);
}

const asRecord = (value: unknown): Record<string, unknown> | undefined =>
  typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : undefined;

const text = (value: unknown, what: string, max: number, min = 1): string => {
  if (typeof value !== 'string') fail(`${what} must be text`);
  if (value.length < min || value.length > max) fail(`${what} is the wrong length`);
  if (UNSAFE_TEXT.test(value)) fail(`${what} contains characters it may not contain`);
  return value;
};

const optionalText = (value: unknown, what: string, max: number): string | undefined =>
  value === undefined || value === null ? undefined : text(value, what, max);

/** A URL this app will dial: HTTPS on port 443, no credentials, no fragment. Checked before a socket opens. */
export function safeMarketUrl(value: string, what: string): URL {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new MarketError('invalid-url', `${what} is not a URL`);
  }
  if (url.protocol !== 'https:') throw new MarketError('invalid-url', `${what} must use HTTPS`);
  if (url.username || url.password) throw new MarketError('invalid-url', `${what} must not carry credentials`);
  if (url.port && url.port !== '443') throw new MarketError('invalid-url', `${what} must use port 443`);
  if (url.hash) throw new MarketError('invalid-url', `${what} must not have a fragment`);
  return url;
}

/** A URL safe both to fetch and to show, and one this app will not follow to another origin. */
export function safeMarketHref(value: string, what: string): URL {
  const url = safeMarketUrl(value, what);
  if (url.search) throw new MarketError('invalid-url', `${what} must not have a query string`);
  return url;
}

const textList = (value: unknown, what: string, max: number, pattern?: RegExp): string[] | undefined => {
  if (value === undefined || value === null) return undefined;
  if (!Array.isArray(value) || value.length > max) fail(`${what} is not a valid list`);
  const seen = new Set<string>();
  for (const entry of value) {
    const item = text(entry, `${what} entry`, 64);
    if (pattern && !pattern.test(item)) fail(`${what} entry "${item}" is not allowed`);
    if (seen.has(item)) fail(`${what} repeats "${item}"`);
    seen.add(item);
  }
  return [...seen];
};

const bounded = (value: unknown, what: string): number => {
  const number = Number(value);
  if (!Number.isInteger(number) || number < 1 || number > 200) fail(`${what} must be between 1 and 200`);
  return number;
};

/**
 * Checks a manifest against the published `catalog-source` schema, plus the rules a schema cannot say: that the
 * endpoint is a shape this app will dial, and that the limits and the sort list agree with each other.
 */
export function parseMarketSourceManifest(value: unknown): MarketSourceManifest {
  const root = asRecord(value) ?? fail('The source manifest is not an object');
  if (root.manifestVersion !== '1.0.0') fail('The source manifest version is not 1.0.0');

  const providerId = text(root.providerId, 'providerId', 128, 3);
  if (!PROVIDER_ID.test(providerId)) fail('providerId must look like a reverse domain name');

  const transport = asRecord(root.transport) ?? fail('The source manifest has no transport');
  if (transport.kind !== 'https-json') fail('The source transport must be https-json');
  if (transport.method !== 'GET') fail('The source transport must use GET');
  const endpoint = safeMarketUrl(String(transport.endpoint ?? ''), 'The catalog endpoint');
  if (!endpoint.pathname.endsWith('/v1/plugins')) fail('The catalog endpoint must end in /v1/plugins');

  const query = asRecord(root.query) ?? fail('The source manifest has no query');
  const supported = (Array.isArray(query.supported) ? query.supported : []).map((entry) => {
    const feature = String(entry);
    if (!QUERY_FEATURES.includes(feature as MarketQueryFeature))
      fail(`The source declares an unknown query feature "${feature}"`);
    return feature as MarketQueryFeature;
  });
  if (supported.length > 7) fail('The source declares too many query features');
  if (new Set(supported).size !== supported.length) fail('The source repeats a query feature');

  const defaultLimit = bounded(query.defaultLimit ?? 50, 'defaultLimit');
  const maxLimit = bounded(query.maxLimit ?? 50, 'maxLimit');
  if (defaultLimit > maxLimit) fail('defaultLimit is above maxLimit');

  const sorts = (Array.isArray(query.sorts) ? query.sorts : []).map((entry) => {
    const sort = String(entry);
    if (!SORTS.includes(sort as MarketSort)) fail(`The source declares an unknown sort "${sort}"`);
    return sort as MarketSort;
  });
  if (sorts.length > 4) fail('The source declares too many sorts');
  // Declaring `sort` with nothing to sort by — or sorts with no `sort` — is a contradiction, not a default.
  if (supported.includes('sort') && sorts.length === 0) fail('The source supports sort but offers no sorts');
  if (!supported.includes('sort') && sorts.length > 0) fail('The source offers sorts without supporting sort');

  const attribution = asRecord(root.attribution) ?? fail('The source manifest has no attribution');

  return {
    manifestVersion: '1.0.0',
    providerId,
    name: text(root.name, 'name', 120),
    description: optionalText(root.description, 'description', 500),
    homepage: root.homepage === undefined ? undefined : safeMarketHref(String(root.homepage), 'homepage').href,
    attribution: {
      name: text(attribution.name, 'attribution.name', 120),
      url: safeMarketHref(String(attribution.url ?? ''), 'attribution.url').href,
      notice: optionalText(attribution.notice, 'attribution.notice', 500),
    },
    transport: { kind: 'https-json', endpoint: endpoint.href, method: 'GET' },
    query: { supported, defaultLimit, maxLimit, sorts },
  };
}

/**
 * Checks one page of a catalog. `limit` is what this app asked for: a source that answers with more than it was
 * asked for, or with items it cannot identify, is not answering the same protocol.
 */
export function parseMarketProviderPage(value: unknown, limit: number): MarketProviderPage {
  const root = asRecord(value) ?? fail('The catalog page is not an object');
  if (root.schemaVersion !== '1.0.0') fail('The catalog page version is not 1.0.0');
  if (!Array.isArray(root.items)) fail('The catalog page has no items');
  if (root.items.length > 200) fail('The catalog page holds more than 200 items');
  if (root.items.length > limit) fail('The catalog page holds more than was asked for');

  const seen = new Set<string>();
  const items = root.items.map((entry: unknown, index: number) => {
    const raw = asRecord(entry) ?? fail(`Catalog item ${index} is not an object`);
    const id = text(raw.id, `item ${index} id`, 160);
    if (!ITEM_ID.test(id)) fail(`item ${index} id is not allowed`);
    if (seen.has(id)) fail(`The catalog page repeats the item id "${id}"`);
    seen.add(id);

    const repository = asRecord(raw.repository);
    const installSource = asRecord(raw.installSource);
    const packageInfo = asRecord(raw.package);
    // An item must say where code comes from: a repository, or a package.
    if (!repository && !packageInfo) fail(`item ${index} names no repository or package`);

    const item: MarketItem = {
      id,
      name: text(raw.name, `item ${index} name`, 160),
      displayName: text(raw.displayName, `item ${index} displayName`, 120),
      summary: text(raw.summary, `item ${index} summary`, 1000),
    };
    const description = optionalText(raw.description, `item ${index} description`, 5000);
    if (description !== undefined) item.description = description;
    if (raw.homepage !== undefined) item.homepage = safeMarketHref(String(raw.homepage), `item ${index} homepage`).href;
    const version = optionalText(raw.latestVersion, `item ${index} latestVersion`, 64);
    if (version !== undefined) item.latestVersion = version;
    const license = optionalText(raw.license, `item ${index} license`, 80);
    if (license !== undefined) item.license = license;
    const categories = textList(raw.categories, `item ${index} categories`, 32, CATEGORY_ID);
    if (categories !== undefined) item.categories = categories;
    const keywords = textList(raw.keywords, `item ${index} keywords`, 64);
    if (keywords !== undefined) item.keywords = keywords;
    if (repository) {
      const subdirectory = optionalText(repository.subdirectory, `item ${index} repository.subdirectory`, 240);
      item.repository = {
        url: safeMarketHref(String(repository.url ?? ''), `item ${index} repository.url`).href,
        ...(subdirectory === undefined ? {} : { subdirectory }),
      };
    }
    // A GitHub source must be pinned to one commit: a branch or a tag moves, and "latest" is not an identity.
    if (installSource?.kind === 'github') {
      const commit = text(installSource.commit, `item ${index} installSource.commit`, 40, 40).toLowerCase();
      if (!/^[0-9a-f]{40}$/.test(commit)) fail(`item ${index} installSource.commit is not a full commit hash`);
      item.installSource = { kind: 'github', commit };
    }
    if (packageInfo?.registry === 'npm') {
      item.package = { registry: 'npm', name: text(packageInfo.name, `item ${index} package.name`, 214) };
    }
    const publisher = asRecord(raw.publisher);
    if (publisher) item.publisher = { name: text(publisher.name, `item ${index} publisher.name`, 120) };
    const updatedAt = optionalText(raw.updatedAt, `item ${index} updatedAt`, 40);
    if (updatedAt !== undefined) item.updatedAt = updatedAt;
    return item;
  });

  const page = asRecord(root.page);
  return {
    schemaVersion: '1.0.0',
    generatedAt: optionalText(root.generatedAt, 'generatedAt', 40),
    revision: optionalText(root.revision, 'revision', 160),
    items,
    page: {
      nextCursor: optionalText(page?.nextCursor, 'page.nextCursor', 2048),
      total: typeof page?.total === 'number' && page.total >= 0 ? page.total : undefined,
    },
  };
}

/** A query as this app sends it: unknown sorts dropped, limit held under the source's own maximum. */
export function normalizeMarketQuery(value: MarketQuery, manifest: MarketSourceManifest): MarketQuery {
  const supports = (feature: MarketQueryFeature): boolean => manifest.query.supported.includes(feature);
  const asked = Math.trunc(value.limit ?? manifest.query.defaultLimit);
  const limit = Math.min(Math.max(asked, 1), manifest.query.maxLimit);
  const query: MarketQuery = {};
  if (supports('q') && value.q) query.q = value.q.slice(0, 200);
  if (supports('category') && value.category?.length) query.category = value.category.slice(0, 20);
  if (supports('capability') && value.capability?.length) query.capability = value.capability.slice(0, 32);
  if (supports('cursor') && value.cursor) query.cursor = value.cursor.slice(0, 2048);
  if (supports('limit')) query.limit = limit;
  if (supports('sort') && value.sort && manifest.query.sorts.includes(value.sort)) query.sort = value.sort;
  if (supports('locale') && value.locale && LOCALE.test(value.locale)) query.locale = value.locale;
  return query;
}

/** Builds the catalog URL. Only the parameters the source declared go on the wire. */
export function marketCatalogUrl(manifest: MarketSourceManifest, query: MarketQuery): URL {
  const url = new URL(manifest.transport.endpoint);
  if (query.q !== undefined) url.searchParams.set('q', query.q);
  for (const category of query.category ?? []) url.searchParams.append('category', category);
  for (const capability of query.capability ?? []) url.searchParams.append('capability', capability);
  if (query.cursor !== undefined) url.searchParams.set('cursor', query.cursor);
  if (query.limit !== undefined) url.searchParams.set('limit', String(query.limit));
  if (query.sort !== undefined) url.searchParams.set('sort', query.sort);
  if (query.locale !== undefined) url.searchParams.set('locale', query.locale);
  return url;
}

/**
 * The trust root: the origin the person registered, the origin the manifest was served from, and the origin the
 * catalog is served from must be one and the same. A source that answers from somewhere else is not the source that
 * was added.
 */
export function marketTrustRoot(manifestUrl: string, manifestFinalUrl: string, endpoint: string): string {
  const registered = safeMarketHref(manifestUrl, 'The source manifest URL').origin;
  if (new URL(manifestFinalUrl).origin !== registered)
    throw new MarketError('redirect', 'The source manifest was served from another origin than the one registered');
  if (new URL(endpoint).origin !== registered)
    throw new MarketError('contract', 'The catalog endpoint is on another origin than the registered manifest');
  return registered;
}
