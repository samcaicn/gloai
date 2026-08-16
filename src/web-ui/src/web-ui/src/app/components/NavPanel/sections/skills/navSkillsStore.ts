/**
 * navSkillsStore — local cache for the sidebar Skills section.
 *
 * Caches the tupai skill list so the nav panel can render instantly
 * without re-fetching on every mount. The cache is persisted to
 * localStorage and refreshed in the background on app start.
 *
 * v2: 同时缓存服务器市场技能（通过 MCP skill.search 拉取 normal + automation
 * 分类的标题和描述），以及用户上次搜索结果。默认视图展示三者的合并：
 *   本地已安装 + 服务器市场技能 + 上次搜索结果
 */

import { create } from 'zustand';
import {
  skillList,
  getBuiltinSkills,
} from '@/infrastructure/api/tupai';
import { mcpCallWithRefresh } from '@/infrastructure/api/tupai/device';
import type { SkillMeta } from '@/infrastructure/api/tupai';
import { createLogger } from '@/shared/utils/logger';

const log = createLogger('navSkillsStore');

const CACHE_KEY = 'bitfun:nav:skillsCache';
const MARKET_CACHE_KEY = 'bitfun:nav:marketSkillsCache';
const LAST_SEARCH_KEY = 'bitfun:nav:lastSearchResults';
const CACHE_TTL = 5 * 60 * 1000; // 5 minutes
const MARKET_CACHE_TTL = 10 * 60 * 1000; // 10 minutes for market skills

// localStorage key for device token (与 device.ts / skill.ts 保持一致)
const DEVICE_TOKEN_KEY = 'trae_device_token';

function readDeviceToken(): string | null {
  try {
    return typeof localStorage !== 'undefined' ? localStorage.getItem(DEVICE_TOKEN_KEY) : null;
  } catch {
    return null;
  }
}

interface CachedPayload {
  skills: SkillMeta[];
  timestamp: number;
}

interface MarketCachedPayload {
  skills: SkillMeta[];
  timestamp: number;
}

interface LastSearchPayload {
  query: string;
  results: any[];
  timestamp: number;
}

function readCache(): CachedPayload | null {
  try {
    const raw = localStorage.getItem(CACHE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as CachedPayload;
    if (!Array.isArray(parsed.skills)) return null;
    return parsed;
  } catch {
    return null;
  }
}

function writeCache(skills: SkillMeta[]) {
  try {
    const payload: CachedPayload = { skills, timestamp: Date.now() };
    localStorage.setItem(CACHE_KEY, JSON.stringify(payload));
  } catch (err) {
    log.warn('Failed to persist skills cache', { error: err });
  }
}

function readMarketCache(): MarketCachedPayload | null {
  try {
    const raw = localStorage.getItem(MARKET_CACHE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as MarketCachedPayload;
    if (!Array.isArray(parsed.skills)) return null;
    return parsed;
  } catch {
    return null;
  }
}

function writeMarketCache(skills: SkillMeta[]) {
  try {
    const payload: MarketCachedPayload = { skills, timestamp: Date.now() };
    localStorage.setItem(MARKET_CACHE_KEY, JSON.stringify(payload));
  } catch (err) {
    log.warn('Failed to persist market skills cache', { error: err });
  }
}

function readLastSearch(): LastSearchPayload | null {
  try {
    const raw = localStorage.getItem(LAST_SEARCH_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as LastSearchPayload;
    if (!Array.isArray(parsed.results)) return null;
    return parsed;
  } catch {
    return null;
  }
}

export function writeLastSearch(query: string, results: any[]) {
  try {
    const payload: LastSearchPayload = { query, results, timestamp: Date.now() };
    localStorage.setItem(LAST_SEARCH_KEY, JSON.stringify(payload));
  } catch (err) {
    log.warn('Failed to persist last search results', { error: err });
  }
}

export function clearLastSearch() {
  try {
    localStorage.removeItem(LAST_SEARCH_KEY);
  } catch { /* ignore */ }
}

// 解包 MCP 标准响应 { ok, data, error } → data
function unwrapMcpResponse(r: any): any {
  if (r && typeof r === 'object' && 'ok' in r) {
    if (r.ok === false) {
      throw new Error('MCP call failed');
    }
    return r?.data ?? r;
  }
  return r;
}

/**
 * 从服务器 MCP 拉取市场技能列表（normal + automation 分类）。
 * 使用 device_token 鉴权。失败时静默返回空数组，不阻塞 UI。
 */
async function fetchMarketSkills(): Promise<SkillMeta[]> {
  const token = readDeviceToken();
  if (!token) {
    log.debug('fetchMarketSkills: no device token, skipping');
    return [];
  }
  try {
    // 用空 query 搜索，服务器返回全部市场技能（含 normal + automation 分类）
    // mcpCallWithRefresh 内部读 localStorage token，auth 失败时自动 fingerprint 刷新 + 重试，
    // 覆盖会话中途 12h token 过期场景（直接 invoke 会在过期时静默返回空列表）。
    const r = await mcpCallWithRefresh('skill.search', { query: '', limit: 100 });
    const data = unwrapMcpResponse(r);
    const items = data?.items || data?.skills || data?.results || (Array.isArray(data) ? data : []);
    if (!Array.isArray(items)) return [];
    // 归一化为 SkillMeta 格式
    return items.map((s: any): SkillMeta => ({
      skill_id: s.skill_id || s.id || '',
      title: s.title || s.skill_name || s.name || s.skill_id || '',
      description: s.description || s.skill_md || '',
      category: s.category || s.kind || '市场技能',
      version: s.version || '',
      source: s.source || 'market',
      tags: Array.isArray(s.tags) ? s.tags : (Array.isArray(s.skill_tags) ? s.skill_tags : []),
      id: s.id,
      skill_name: s.skill_name || s.name,
      name: s.name,
    }));
  } catch (err) {
    log.warn('fetchMarketSkills: MCP call failed', { error: err });
    return [];
  }
}

interface NavSkillsState {
  /** 内置自带技能（编译进二进制） */
  builtinSkills: SkillMeta[];
  /** 本地已安装技能 */
  skills: SkillMeta[];
  /** 服务器市场技能（normal + automation） */
  marketSkills: SkillMeta[];
  /** 上次搜索结果 */
  lastSearchResults: any[];
  lastSearchQuery: string;
  /** 合并后的展示列表（内置 + 本地 + 市场 + 上次搜索去重） */
  displaySkills: SkillMeta[];
  loading: boolean;
  error: string | null;
  lastFetch: number;
  /** Incremented when skills change, so subscribers can react. */
  version: number;

  loadSkills: (force?: boolean) => Promise<void>;
  /** 强制刷新市场技能缓存 */
  refreshMarketSkills: () => Promise<void>;
}

function hasPlatformTag(tags: unknown): boolean {
  if (!Array.isArray(tags)) return false;
  return tags.some((t) => typeof t === 'string' && t.toLowerCase() === 'platform');
}

/** 合并内置 + 市场 + 本地 + 上次搜索，按 skill_id 去重。
 *  优先级（用户搜索前展示）：
 *    1. 内置自带技能 (source === 'builtin') — 最高优先级
 *    2. 租户自有技能 (source === 'tenant')
 *    3. 平台标签技能 (tags 包含 'platform'，大小写不敏感)
 *    4. 本地已安装技能
 *    5. 其他市场技能（非 tenant / 非 platform 的市场技能）
 *    6. 上次搜索结果 — 最低优先级 */
function mergeSkills(builtin: SkillMeta[], local: SkillMeta[], market: SkillMeta[], lastSearch: any[]): SkillMeta[] {
  const tenantSkills: SkillMeta[] = [];
  const platformSkills: SkillMeta[] = [];
  const otherMarketSkills: SkillMeta[] = [];

  for (const s of market) {
    if (s.source === 'tenant') {
      tenantSkills.push(s);
    } else if (hasPlatformTag(s.tags)) {
      platformSkills.push(s);
    } else {
      otherMarketSkills.push(s);
    }
  }

  const seen = new Set<string>();
  const out: SkillMeta[] = [];

  // 1. 内置自带技能（最高优先级，含 platform 标签会自动置顶展示）
  for (const s of builtin) {
    const id = s.skill_id || s.id || '';
    if (id && seen.has(id)) continue;
    if (id) seen.add(id);
    out.push(s);
  }
  // 2. 租户自有技能
  for (const s of tenantSkills) {
    const id = s.skill_id || s.id || '';
    if (id && seen.has(id)) continue;
    if (id) seen.add(id);
    out.push(s);
  }
  // 3. 平台标签技能
  for (const s of platformSkills) {
    const id = s.skill_id || s.id || '';
    if (id && seen.has(id)) continue;
    if (id) seen.add(id);
    out.push(s);
  }
  // 4. 本地已安装技能
  for (const s of local) {
    const id = s.skill_id || s.id || '';
    if (id && seen.has(id)) continue;
    if (id) seen.add(id);
    out.push(s);
  }
  // 5. 其他市场技能
  for (const s of otherMarketSkills) {
    const id = s.skill_id || s.id || '';
    if (id && seen.has(id)) continue;
    if (id) seen.add(id);
    out.push(s);
  }
  // 6. 上次搜索结果（归一化为 SkillMeta）— 最低优先级
  for (const raw of lastSearch) {
    const id = raw?.skill_id || raw?.id || '';
    if (id && seen.has(id)) continue;
    if (id) seen.add(id);
    out.push({
      skill_id: id,
      title: raw?.title || raw?.skill_name || raw?.name || id,
      description: raw?.description || '',
      category: raw?.category || '搜索结果',
      version: raw?.version || '',
      source: raw?.source || 'search',
    });
  }
  return out;
}

const initialCache = readCache();
const initialMarketCache = readMarketCache();
const initialLastSearch = readLastSearch();

export const useNavSkillsStore = create<NavSkillsState>((set, get) => ({
  builtinSkills: [],
  skills: initialCache?.skills ?? [],
  marketSkills: initialMarketCache?.skills ?? [],
  lastSearchResults: initialLastSearch?.results ?? [],
  lastSearchQuery: initialLastSearch?.query ?? '',
  displaySkills: mergeSkills(
    [],
    initialCache?.skills ?? [],
    initialMarketCache?.skills ?? [],
    initialLastSearch?.results ?? [],
  ),
  loading: false,
  error: null,
  lastFetch: initialCache?.timestamp ?? 0,
  version: 0,

  loadSkills: async (force?: boolean) => {
    const state = get();
    if (state.loading) return;
    // 本地缓存 TTL 检查
    const localExpired = force || Date.now() - state.lastFetch >= CACHE_TTL || state.skills.length === 0;
    // builtin 技能每次都重新拉取（开销小，确保最新）
    const builtinExpired = true;
    // 市场缓存 TTL 检查
    const marketCache = readMarketCache();
    const marketExpired = !marketCache || Date.now() - marketCache.timestamp >= MARKET_CACHE_TTL;

    if (!localExpired && !builtinExpired && !marketExpired) {
      // 都未过期，直接合并已有数据
      const lastSearch = readLastSearch();
      const merged = mergeSkills(state.builtinSkills, state.skills, marketCache?.skills ?? [], lastSearch?.results ?? []);
      set({ displaySkills: merged });
      return;
    }

    set({ loading: true, error: null });
    try {
      // 并行拉取 builtin + 本地 + 市场技能
      const [builtinList, localList, marketList] = await Promise.all([
        builtinExpired ? getBuiltinSkills().catch(e => {
          log.warn('getBuiltinSkills failed', { error: e });
          return [] as any[];
        }) : Promise.resolve(state.builtinSkills),
        localExpired ? skillList().catch(e => {
          log.warn('skillList failed', { error: e });
          return [] as SkillMeta[];
        }) : Promise.resolve(state.skills),
        marketExpired ? fetchMarketSkills() : Promise.resolve(marketCache?.skills ?? []),
      ]);

      // builtin 技能归一化为 SkillMeta 格式
      const safeBuiltin: SkillMeta[] = (Array.isArray(builtinList) ? builtinList : []).map((s: any) => ({
        skill_id: s.skill_id || s.id || '',
        title: s.skill_name || s.name || s.skill_id || '',
        description: s.description || '',
        category: s.category || '平台技能',
        version: s.version || '',
        source: 'builtin',
        tags: Array.isArray(s.tags) ? s.tags : [],
      }));
      const safeLocal = Array.isArray(localList) ? localList : [];
      const safeMarket = Array.isArray(marketList) ? marketList : [];

      // 持久化缓存
      if (localExpired) writeCache(safeLocal);
      if (marketExpired) writeMarketCache(safeMarket);

      const lastSearch = readLastSearch();
      const merged = mergeSkills(safeBuiltin, safeLocal, safeMarket, lastSearch?.results ?? []);

      set({
        builtinSkills: safeBuiltin,
        skills: safeLocal,
        marketSkills: safeMarket,
        lastSearchResults: lastSearch?.results ?? [],
        lastSearchQuery: lastSearch?.query ?? '',
        displaySkills: merged,
        loading: false,
        lastFetch: Date.now(),
        version: state.version + 1,
      });
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      log.error('Failed to load skills for nav cache', err);
      set({ loading: false, error: message });
    }
  },

  refreshMarketSkills: async () => {
    try {
      const marketList = await fetchMarketSkills();
      writeMarketCache(marketList);
      const state = get();
      const lastSearch = readLastSearch();
      const merged = mergeSkills(state.builtinSkills, state.skills, marketList, lastSearch?.results ?? []);
      set({
        marketSkills: marketList,
        displaySkills: merged,
        version: state.version + 1,
      });
    } catch (err) {
      log.warn('refreshMarketSkills failed', { error: err });
    }
  },
}));

// 监听 device token 变化，自动刷新市场技能
if (typeof window !== 'undefined') {
  window.addEventListener('tupai:device-token-changed', () => {
    window.setTimeout(() => {
      void useNavSkillsStore.getState().refreshMarketSkills();
    }, 500);
  });
}

/**
 * Classify a skill source into a group.
 * - 'self': user-generated skills (source is empty, 'manual', 'user', or 'local')
 * - 'tenant': skills pushed from the tenant / organisation
 */
export function classifySkillSource(source: string | undefined): 'self' | 'tenant' {
  if (!source) return 'self';
  const s = source.toLowerCase();
  if (s === 'manual' || s === 'user' || s === 'local' || s === 'self') return 'self';
  return 'tenant';
}
