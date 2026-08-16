// Skill 执行相关 Tauri 命令封装。
// 命令名已对齐后端 lib.rs 的 invoke_handler 注册：
//   skillExecute → execute_skill         (skill.rs: execute_skill(skill_id))
//   skillList    → get_skills            (agent.rs: get_skills())
//   skillLoad    → get_skill_detail      (agent.rs: get_skill_detail(name))
//   skillSave    → save_optimized_skill  (skill.rs: save_optimized_skill(skill_id, skill_md, source?))
//
// 技能加载策略（参考 Hermes 开源项目的技能挂载模式）：
//   技能必须成功挂载为 system prompt 才能执行——SKILL.md 内容必须落盘到本地
//   MD 文件，确保：(1) 跨重启可复用；(2) execute_skill 能从本地读取执行；
//   (3) 不会退化为无 prompt 的普通对话。
//
//   六级加载 + 多级重试：
//     LB. builtin 技能（get_builtin_skills_command，直接从二进制内嵌获取）
//     L0. localStorage 内存缓存（秒加载，用于会话内切换）
//     L1. 本地 get_skill_detail（已安装/builtin 技能，读磁盘 SKILL.md）
//     L2. MCP skill.detail（服务器市场技能"解密"——解包 MCP 信封拿明文 SKILL.md）
//     L3. install_skill 下载到本地后重试 L1（确保技能文件真正落盘）
//     L4. builtin 降级（非 builtin- 前缀的 ID 尝试匹配内置技能）
//   L2/L3 成功后自动持久化到本地（save_optimized_skill + localStorage 缓存），
//   确保技能文件真正落盘。
import { invoke } from './invoke';
import { mcpCallWithRefresh } from './device';
import type { SkillMeta, Skill, SkillOutput } from './types';
import { createLogger } from '@/shared/utils/logger';

const skillLog = createLogger('skillLoad');

// 后端 execute_skill 期望 (skill_id)，不接受 params。params 保留在 invoke 对象中以维持函数签名，后端 serde 忽略未知字段。
export async function skillExecute(skillId: string, params: any): Promise<SkillOutput> {
  return invoke<SkillOutput>('execute_skill', { skillId, params });
}

// 后端 get_skills 无参数，返回 Vec<SkillInfo>。
export async function skillList(): Promise<SkillMeta[]> {
  return invoke<SkillMeta[]>('get_skills');
}

// ═══════════════════════════════════════════════════════════════════
// localStorage 缓存层（L0）
// 会话内切换技能时秒加载，避免每次都走网络/磁盘。
// ═══════════════════════════════════════════════════════════════════

const SKILL_CACHE_PREFIX = 'tupai:skillContent:';
const SKILL_CACHE_INDEX_KEY = 'tupai:skillContentIndex';
// 缓存有效期 24 小时（技能内容变更频率低，超时后自动走完整加载刷新）
const SKILL_CACHE_TTL = 24 * 60 * 60 * 1000;

interface CachedSkillContent {
  skillId: string;
  content: string;
  title: string;
  description: string;
  version: string;
  category: string;
  cachedAt: number;
}

function readSkillCache(skillId: string): CachedSkillContent | null {
  try {
    const raw = localStorage.getItem(SKILL_CACHE_PREFIX + skillId);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as CachedSkillContent;
    if (typeof parsed.content !== 'string' || !parsed.content) return null;
    // 超时检查
    if (Date.now() - parsed.cachedAt > SKILL_CACHE_TTL) return null;
    return parsed;
  } catch {
    return null;
  }
}

function writeSkillCache(skill: Skill): void {
  try {
    const entry: CachedSkillContent = {
      skillId: skill.skill_id,
      content: skill.content,
      title: skill.title,
      description: skill.description,
      version: skill.version,
      category: skill.category,
      cachedAt: Date.now(),
    };
    localStorage.setItem(SKILL_CACHE_PREFIX + skill.skill_id, JSON.stringify(entry));
    // 维护索引列表
    const idxRaw = localStorage.getItem(SKILL_CACHE_INDEX_KEY);
    const idx: string[] = idxRaw ? (JSON.parse(idxRaw) as string[]) : [];
    if (!idx.includes(skill.skill_id)) {
      idx.push(skill.skill_id);
      localStorage.setItem(SKILL_CACHE_INDEX_KEY, JSON.stringify(idx));
    }
  } catch (err) {
    skillLog.warn('writeSkillCache failed (non-fatal)', { skillId: skill.skill_id, error: err });
  }
}

/** 读取已缓存的技能内容（不触发网络/磁盘），用于秒加载。 */
export function getCachedSkill(skillId: string): Skill | null {
  const cached = readSkillCache(skillId);
  if (!cached) return null;
  return {
    skill_id: cached.skillId,
    title: cached.title,
    description: cached.description,
    content: cached.content,
    version: cached.version,
    category: cached.category,
  };
}

// ═══════════════════════════════════════════════════════════════════
// 持久化到本地磁盘（落盘 MD 文件）
// 调用后端 save_optimized_skill 将 SKILL.md 写入 <app_data>/skills_optimized/
// 这样 execute_skill 可以从本地读取执行，不会退化为无 prompt 的普通对话。
// ═══════════════════════════════════════════════════════════════════

async function persistSkillToDisk(skillId: string, content: string, source?: string): Promise<boolean> {
  if (!content || content.trim().length === 0) return false;
  // builtin- 前缀的技能已在二进制内嵌入，无需落盘
  if (skillId.toLowerCase().startsWith('builtin-')) return false;
  try {
    await invoke('save_optimized_skill', {
      skillId,
      skillMd: content,
      source: source || 'auto-download',
    });
    skillLog.info('skill persisted to local disk', { skillId, contentLen: content.length });
    return true;
  } catch (err) {
    // save_optimized_skill 会校验 manifest 合法性，市场技能的 SKILL.md 格式
    // 可能不完全匹配后端 SkillManifest::from_skill_md 的要求，落盘失败不阻断
    // 主流程——内容仍会写入 localStorage 缓存，会话内可用。
    skillLog.warn('persistSkillToDisk failed (non-fatal, skill_md may not match manifest schema)', {
      skillId,
      error: err,
    });
    return false;
  }
}

// ═══════════════════════════════════════════════════════════════════
// 重试工具
// ═══════════════════════════════════════════════════════════════════

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function backoffDelay(attempt: number): number {
  // 指数退避: 1s → 2s → 4s + 抖动
  const base = Math.pow(2, attempt) * 1000;
  const jitter = Math.random() * 500;
  return base + jitter;
}

/**
 * 带重试的异步操作执行器。
 * @param fn 要重试的异步函数，返回 T | null（null 表示需要重试）
 * @param maxRetries 最大重试次数
 * @param label 日志标签
 * @returns fn 的返回值，或 null（全部重试失败）
 */
async function withRetry<T>(
  fn: () => Promise<T | null>,
  maxRetries: number,
  label: string,
): Promise<T | null> {
  for (let attempt = 0; attempt < maxRetries; attempt++) {
    try {
      const result = await fn();
      if (result) return result;
      skillLog.debug(`${label} attempt ${attempt + 1} returned null, will retry`, { attempt });
    } catch (err) {
      skillLog.warn(`${label} attempt ${attempt + 1} failed`, { attempt, error: err });
    }
    if (attempt < maxRetries - 1) {
      await sleep(backoffDelay(attempt));
    }
  }
  return null;
}

/** 技能加载结果，包含挂载状态和来源信息。 */
export interface SkillLoadResult extends Skill {
  /** 挂载状态：success=已成功获取 SKILL.md 内容；empty=加载失败，内容为空 */
  mountStatus: 'success' | 'empty';
  /** 内容来源：builtin/cache/local/mcp/install */
  source: 'builtin' | 'cache' | 'local' | 'mcp' | 'install';
}

// 后端 get_skill_detail 期望 (name: String)，注意参数名是 name 而非 skillId。
// 返回 SkillDetail { skill: SkillInfo, contentPreview, content }，此处映射为前端 Skill。
//
// 六级加载 + 多级重试策略（参考 Hermes 开源项目技能挂载模式）：
//   LB. builtin 技能（get_builtin_skills_command，直接从二进制内嵌获取）
//   L0. localStorage 内存缓存（秒加载，用于会话内切换）
//   L1. 本地 get_skill_detail（已安装/builtin 技能，读磁盘 SKILL.md）
//   L2. MCP skill.detail（服务器市场技能"解密"——解包 MCP 信封拿明文 SKILL.md）
//   L3. install_skill 下载到本地后重试 L1（确保技能文件真正落盘）
//   L4. builtin 降级（非 builtin- 前缀的 ID 尝试匹配内置技能）
//   L4. 持久化：L2/L3 成功后将内容写入 localStorage 缓存 + 落盘到本地 MD 文件
//
// 每个 remote 级别（L2/L3）最多重试 3 次（指数退避），确保网络波动下仍能挂载成功。
export async function skillLoad(skillId: string): Promise<Skill> {
  const result = await skillLoadDetailed(skillId);
  return result;
}

/**
 * 详细的技能加载函数，返回挂载状态和来源。
 * 调用方（TupaiChatScene）可用 mountStatus 判断是否挂载成功，
 * empty 时应阻断普通对话回退，提示用户重试。
 */
export async function skillLoadDetailed(skillId: string): Promise<SkillLoadResult> {
  const emptyResult = (source: SkillLoadResult['source']): SkillLoadResult => ({
    skill_id: skillId,
    title: skillId,
    description: '',
    content: '',
    version: '',
    category: '',
    mountStatus: 'empty',
    source,
  });

  // ── LB. builtin 技能不走磁盘——直接从后端拉取 info 作为 prompt ──
  if (skillId.startsWith('builtin-')) {
    try {
      const list = await invoke<any[]>('get_builtin_skills_command');
      const skill = (list || []).find((s: any) => s.id === skillId || s.skill_id === skillId);
      if (skill) {
        const content = skill.description || skill.name || skillId;
        const result: Skill = {
          skill_id: skillId,
          title: skill.name || skillId,
          description: skill.description || '',
          content,
          version: skill.version || '',
          category: skill.category || '平台技能',
        };
        writeSkillCache(result);
        return { ...result, mountStatus: 'success', source: 'builtin' };
      }
    } catch { /* fall through to L0-L4 */ }
  }

  // ── L0. localStorage 缓存（秒加载） ──
  const cached = readSkillCache(skillId);
  if (cached) {
    skillLog.info('L0 cache hit', { skillId, contentLen: cached.content.length });
    return {
      skill_id: cached.skillId,
      title: cached.title,
      description: cached.description,
      content: cached.content,
      version: cached.version,
      category: cached.category,
      mountStatus: 'success',
      source: 'cache',
    };
  }

  // ── L1. 本地已安装技能（get_skill_detail，读磁盘 SKILL.md） ──
  // 本地读取无网络开销，重试 2 次即可（主要防 IO 抖动）
  const localResult = await withRetry(async () => {
    try {
      const detail = await invoke<any>('get_skill_detail', { name: skillId });
      if (detail) {
        const content = detail.content || detail.contentPreview || '';
        if (content) {
          return {
            skill_id: detail.skill?.name || skillId,
            title: detail.skill?.name || skillId,
            description: detail.skill?.description || '',
            content,
            version: detail.skill?.version || '',
            category: detail.skill?.category || '',
          } as Skill;
        }
      }
    } catch (_err) {
      // 本地未找到（市场技能未安装），降级到远程拉取
    }
    return null;
  }, 2, 'L1 get_skill_detail');

  if (localResult) {
    skillLog.info('L1 local skill loaded', { skillId, contentLen: localResult.content.length });
    // 写入缓存，下次秒加载
    writeSkillCache(localResult);
    return { ...localResult, mountStatus: 'success', source: 'local' };
  }

  // ── L2. 服务器市场技能（MCP skill.detail "解密"） ──
  // MCP 调用有网络开销，重试 3 次（指数退避 1s→2s→4s）
  let mcpContent = '';
  let mcpMeta: any = null;
  const mcpResult = await withRetry(async () => {
    try {
      const r = await mcpCallWithRefresh('skill.detail', { skill_id: skillId });
      const data = unwrapMcpResponse(r);
      const content = data?.skill_md || data?.content || data?.skillMd || '';
      if (content) {
        mcpMeta = data;
        mcpContent = content;
        return content;
      }
    } catch (err) {
      skillLog.warn('L2 MCP skill.detail failed', { skillId, error: err });
    }
    return null;
  }, 3, 'L2 MCP skill.detail');

  if (mcpResult && mcpContent) {
    const skill: Skill = {
      skill_id: skillId,
      title: mcpMeta?.name || mcpMeta?.skill_name || mcpMeta?.title || skillId,
      description: mcpMeta?.description || '',
      content: mcpContent,
      version: mcpMeta?.version || '',
      category: mcpMeta?.category || mcpMeta?.kind || '',
    };
    skillLog.info('L2 MCP skill loaded', { skillId, contentLen: mcpContent.length });
    // 持久化：写入 localStorage 缓存 + 落盘到本地 MD 文件
    writeSkillCache(skill);
    void persistSkillToDisk(skillId, mcpContent, 'mcp-download');
    return { ...skill, mountStatus: 'success', source: 'mcp' };
  }

  // ── L3. install_skill 下载到本地后重试 L1 ──
  // install_skill 执行 `hermes skills install <identifier> --yes`，
  // 将技能文件下载到本地 skills 目录。重试 3 次。
  const installResult = await withRetry(async () => {
    try {
      const result = await invoke<any>('install_skill', { identifier: skillId });
      if (result?.success) {
        // 下载成功后重试本地读取
        try {
          const detail = await invoke<any>('get_skill_detail', { name: skillId });
          if (detail) {
            const content = detail.content || detail.contentPreview || '';
            if (content) {
              return {
                skill_id: detail.skill?.name || skillId,
                title: detail.skill?.name || skillId,
                description: detail.skill?.description || '',
                content,
                version: detail.skill?.version || '',
                category: detail.skill?.category || '',
              } as Skill;
            }
          }
        } catch {
          // install 后仍读不到
        }
      } else {
        skillLog.warn('L3 install_skill returned success:false', {
          skillId,
          stderr: result?.stderr || result?.stdout,
        });
      }
    } catch (err) {
      skillLog.warn('L3 install_skill failed', { skillId, error: err });
    }
    return null;
  }, 3, 'L3 install_skill');

  if (installResult) {
    skillLog.info('L3 install + local read success', { skillId, contentLen: installResult.content.length });
    // 持久化：写入缓存
    writeSkillCache(installResult);
    return { ...installResult, mountStatus: 'success', source: 'install' };
  }

  // ── L4. Builtin 降级：非 builtin- 前缀的 ID 在 L1-L3 全失败后，
  //      尝试用 builtin-{skillId} 匹配已编译进二进制的内置技能。
  //      这覆盖了场景：市场市场返回的技能 ID 不带 builtin- 前缀，
  //      但实际该技能以内置形式存在于二进制中。
  if (!skillId.startsWith('builtin-')) {
    try {
      const builtinList = await invoke<any[]>('get_builtin_skills_command');
      const builtinSkill = (builtinList || []).find(
        (s: any) =>
          s.id === `builtin-${skillId}` ||
          s.skill_id === `builtin-${skillId}` ||
          s.name === skillId ||
          s.skill_name === skillId,
      );
      if (builtinSkill) {
        const content = builtinSkill.description || builtinSkill.name || skillId;
        const result: Skill = {
          skill_id: `builtin-${skillId}`,
          title: builtinSkill.name || skillId,
          description: builtinSkill.description || '',
          content,
          version: builtinSkill.version || '',
          category: builtinSkill.category || '平台技能',
        };
        skillLog.info('L4 builtin fallback matched', { skillId, builtinId: result.skill_id });
        writeSkillCache(result);
        return { ...result, mountStatus: 'success', source: 'builtin' };
      }
    } catch (err) {
      skillLog.warn('L4 builtin fallback failed (non-fatal)', { skillId, error: err });
    }
  }

  // ── 全部失败：返回空内容 ──
  // 调用方应根据 mountStatus==='empty' 阻断普通对话回退，提示用户重试。
  skillLog.error('all skill loading levels failed', { skillId });
  return emptyResult('install');
}

// 后端 save_optimized_skill 期望 (skill_id, skill_md, source?)。
export async function skillSave(skill: Skill): Promise<void> {
  return invoke<void>('save_optimized_skill', {
    skillId: skill.skill_id,
    skillMd: skill.content,
    source: 'manual',
  });
}

// ---------------------------------------------------------------------------
// 技能市场搜索相关（场景标签 / TOP N 推荐 / 多源搜索）
// 以下函数对接后端 mcp_call_v2 / get_builtin_skills_command /
// get_market_skills 命令，实现远程技能市场 + 本地（builtin + installed）技能的
// 聚合搜索。远程搜索统一走 MCP action（skill.search / skill.scene_tags /
// skill.top_by_tags），禁止用 mcp_api_get 调用 /api/v1/skills/* REST 接口
//（服务器只有 MCP 端点 /api/v2/mcp）。MCP 响应格式 { ok, data, error }，
// 需解包 data 层。
// ---------------------------------------------------------------------------

// 解包 MCP 标准响应 { ok, data, error } → data；非标准格式直接返回。
function unwrapMcpResponse(r: any): any {
  if (r && typeof r === 'object' && 'ok' in r) {
    if (r.ok === false) {
      const msg = r?.error?.message || r?.error || 'MCP call failed';
      throw new Error(typeof msg === 'string' ? msg : JSON.stringify(msg));
    }
    return r?.data ?? r;
  }
  return r;
}

// 场景标签：MCP action 'skill.scene_tags'
export async function skillSceneTags(): Promise<any> {
  const r = await mcpCallWithRefresh('skill.scene_tags', {});
  return unwrapMcpResponse(r);
}

// TOP N 推荐技能：MCP action 'skill.top_by_tags'
export async function skillTopByTags(limit: number = 5): Promise<any> {
  const r = await mcpCallWithRefresh('skill.top_by_tags', { limit });
  return unwrapMcpResponse(r);
}

// searchAllSkills 的查询选项。
interface SkillSearchOpts {
  sceneTags?: string[];
  kind?: string;
  offset?: number;
  limit?: number;
  skipLocal?: boolean;
}

// 本地技能归一化后的结构（builtin / installed 统一映射到此形状）。
export interface ParamField {
  name: string;
  type: 'string' | 'number' | 'boolean';
  description?: string;
  enum?: string[];
  required?: boolean;
  defaultValue?: unknown;
}

interface LocalSkillItem {
  skill_id: string;
  skill_name: string;
  description: string;
  version: string;
  source: 'builtin' | 'installed';
  category?: string;
  tags?: string[];
  params?: ParamField[];
}

// searchAllSkills 的返回结构。
interface SkillSearchResult {
  results: any[];
  total: number;
  executable: boolean;
  sources: { remote: number; local: number };
}

// 远程技能市场搜索：经后端 mcp_call_v2 命令调用 MCP action 'skill.search'。
// 后端 mcp_call_v2(action, params, timeout_secs?, token?) 代理到 ai.tuptup.top/api/v2/mcp。
// 服务器返回标准 MCP 响应 { ok: true, data: { items/skills: [...], total: number } }。
async function searchSkillsRemote(query: string, opts: SkillSearchOpts = {}): Promise<any> {
  const params: Record<string, any> = { query };
  if (Array.isArray(opts.sceneTags) && opts.sceneTags.length) {
    params.scene_tags = opts.sceneTags;
  }
  if (opts.kind) params.kind = opts.kind;
  if (opts.offset !== undefined) params.offset = opts.offset;
  if (opts.limit !== undefined) params.limit = opts.limit;
  const r = await mcpCallWithRefresh('skill.search', params);
  const data = unwrapMcpResponse(r);
  // 兼容服务器多种返回字段名：items / skills / results / 裸数组
  const items = data?.items || data?.skills || data?.results || (Array.isArray(data) ? data : []);
  const total = data?.total ?? (Array.isArray(items) ? items.length : 0);
  return { results: items, total };
}

// 本地内置技能：后端 get_builtin_skills_command 返回 Vec<EmbeddedSkill>，
// 此处归一化为 LocalSkillItem。
// 注意：code 字段（含 capabilities.js + skillRuntime.js + 技能 index.js）
// 不在此提取，由 runBuiltinSkill 单独拉取后执行。
function parseParams(raw: any): ParamField[] | undefined {
  if (!raw || typeof raw !== 'object') return undefined;
  const entries = Object.entries(raw) as [string, any][];
  if (entries.length === 0) return undefined;
  return entries.map(([name, spec]) => ({
    name,
    type: (spec?.type === 'number' || spec?.type === 'boolean') ? spec.type : 'string',
    description: spec?.description || undefined,
    enum: Array.isArray(spec?.enum) && spec.enum.length > 0 ? spec.enum : undefined,
    required: spec?.required === true,
    defaultValue: spec?.default !== undefined ? spec.default : undefined,
  }));
}

export async function getBuiltinSkills(): Promise<LocalSkillItem[]> {
  try {
    const list = await invoke<any[]>('get_builtin_skills_command');
    if (!Array.isArray(list)) return [];
    return list.map((s: any) => ({
      skill_id: s.id || s.skill_id || '',
      skill_name: s.name || s.skill_name || s.id || '',
      description: s.description || '',
      version: s.version || '',
      source: 'builtin' as const,
      category: s.category || '平台技能',
      tags: Array.isArray(s.tags) ? s.tags : [],
      params: parseParams(s.params),
    }));
  } catch {
    return [];
  }
}

/** 根据 skillId 获取技能参数 schema。builtin 技能从二进制返回中提取，其他技能返回 null。 */
export async function fetchSkillParams(skillId: string): Promise<ParamField[] | null> {
  if (!skillId.startsWith('builtin-')) return null;
  try {
    const list = await invoke<any[]>('get_builtin_skills_command');
    if (!Array.isArray(list)) return null;
    const skill = list.find((s: any) => (s.id || s.skill_id) === skillId);
    if (!skill) return null;
    return parseParams(skill.params) ?? null;
  } catch {
    return null;
  }
}

// ── 技能执行器 ──────────────────────────────────────────
// 从后端拉取技能 JS 代码（含 capabilities.js + skillRuntime.js + index.js），
// 在页面上下文 eval 执行后调用 execute(params, complete) 返回结果。

/**
 * 执行内置 JS 技能。
 *
 * 流程:
 *   1. 调 get_builtin_skills_command 拉取技能列表
 *   2. 找到 skillId 对应的 skill.code（含能力层 + 运行时 + 技能代码）
 *   3. 用 new Function() 在页面全局上下文 eval 执行
 *   4. 调用暴露的 execute(params, complete) 函数
 *   5. 返回执行结果
 *
 * @param skillId 技能 ID（如 "builtin-auto-product-comm"）
 * @param params  传给 execute() 的参数
 * @returns 技能 execute() 的返回值
 */
export async function runBuiltinSkill(
  skillId: string,
  params: Record<string, any> = {},
): Promise<any> {
  // 1. 拉取技能代码
  const list = await invoke<any[]>('get_builtin_skills_command');
  const skill = (list || []).find(
    (s) => s.id === skillId || s.skill_id === skillId,
  );
  if (!skill || !skill.code) {
    throw new Error(`技能 ${skillId} 未找到或无代码`);
  }

  // 1b. 语义映射：调用方传通用 { action: 'execute' } 或不传 action 表示"启动技能"，
  // 但各技能入口 action 不同（auto-product-comm=execute / trace-auto=start /
  // publisher=monitor）。若技能声明了 entry_action，满足以下条件之一时映射到
  // entry_action 让技能走正确启动分支：
  //   a) 调用方未传 action
  //   b) 调用方传的 action 不在技能声明的有效 action 枚举中（如 'execute' 不在
  //      trace-auto 的 enum 中）
  // 显式传入技能已知 action（status/stop 等）时原样透传，不干预。
  const entryAction = skill.entry_action || skill.entryAction
  const validActions: string[] = skill.params?.action?.enum || []
  const shouldMapAction = entryAction && (
    !params.action ||
    (params.action !== entryAction && validActions.length > 0 && !validActions.includes(params.action))
  )
  const finalParams = shouldMapAction
    ? { ...params, action: entryAction }
    : params

  // 2. 清理 ES module 语法（new Function 不支持 export/import）
  //    去掉 `export default ...` / `export const ...` / `export { ... }`
  //    去掉 `import ... from ...`
  let code = skill.code
    .replace(/export\s+default\s+/g, 'var __default_export__ = ')
    .replace(/export\s+const\s+/g, 'const ')
    .replace(/export\s+\{/g, '{')
    .replace(/^\s*import\s.+$/gm, '');

  // 3. 在页面全局上下文 eval 执行
  //    代码末尾返回 handler 函数（技能主入口，按 action 分发）
  code += '\nreturn typeof handler === "function" ? handler : (typeof execute === "function" ? execute : null);';

  const fn = new Function(code);
  const handlerFn = fn();
  if (typeof handlerFn !== 'function') {
    throw new Error(`技能 ${skillId} 未暴露 handler/execute 函数`);
  }

  // 4. 执行技能
  let status = 'ok';
  let result: any;
  try {
    result = await handlerFn(finalParams, null);
  } catch (execErr: any) {
    status = 'error';
    throw execErr;
  } finally {
    // 5. Best-effort 上报 builtin skill coverage
    try {
      await invoke('record_builtin_skill_run_command', {
        skillId,
        action: finalParams.action || '',
        status,
      });
    } catch { /* coverage 上报失败不影响技能执行 */ }
  }
  return result;
}

// 本地已安装技能：后端 get_market_skills 返回 Vec<MarketSkillInfo>，
// 此处归一化为 LocalSkillItem，并过滤掉无 skill_id 的条目。
async function getInstalledSkills(): Promise<LocalSkillItem[]> {
  try {
    const list = await invoke<any[]>('get_market_skills');
    if (!Array.isArray(list)) return [];
    return list
      .map((s: any) => ({
        skill_id: s.skill_id || s.id || s.identifier || s.name || '',
        skill_name: s.skill_name || s.name || s.id || '',
        description: s.description || s.skill_md || '',
        version: s.version || '',
        source: 'installed' as const,
      }))
      .filter((s: LocalSkillItem) => s.skill_id);
  } catch {
    return [];
  }
}

// 合并本地 builtin + installed，按 skill_id 去重。
async function getLocalSkills(): Promise<LocalSkillItem[]> {
  const [builtin, installed] = await Promise.all([getBuiltinSkills(), getInstalledSkills()]);
  const seen = new Set<string>();
  const out: LocalSkillItem[] = [];
  for (const s of [...builtin, ...installed]) {
    const id = s.skill_id;
    if (!id || seen.has(id)) continue;
    seen.add(id);
    out.push(s);
  }
  return out;
}

// 按 query 过滤本地技能（匹配 skill_id / skill_name / description，大小写不敏感）。
function filterLocalSkills(localSkills: LocalSkillItem[], query: string): LocalSkillItem[] {
  if (!query) return localSkills;
  const lower = query.toLowerCase();
  return localSkills.filter(
    (s) =>
      (s.skill_id || '').toLowerCase().includes(lower) ||
      (s.skill_name || '').toLowerCase().includes(lower) ||
      (s.description || '').toLowerCase().includes(lower),
  );
}

// 合并远程 + 本地结果，按 skill_id 去重（远程优先）。
function mergeSkills(remote: any[], local: LocalSkillItem[]): any[] {
  const seen = new Set<string>();
  const out: any[] = [];
  for (const s of remote) {
    const id = s.skill_id || s.id || '';
    if (id && seen.has(id)) continue;
    if (id) seen.add(id);
    out.push(s);
  }
  for (const s of local) {
    const id = s.skill_id || '';
    if (id && seen.has(id)) continue;
    if (id) seen.add(id);
    out.push(s);
  }
  return out;
}

/**
 * 多源技能搜索：远程技能市场（REST）+ 本地（builtin + installed）聚合，
 * 按 skill_id 去重。远程失败时降级为仅本地结果；仅当远程失败且本地也为空时
 * 才抛出远程错误。skipLocal=true 时跳过本地搜索（纯远程）。
 */
export async function searchAllSkills(query: string, opts: SkillSearchOpts = {}): Promise<SkillSearchResult> {
  const remotePromise = searchSkillsRemote(query, opts).catch((e: unknown) => ({ __error: e }));
  const localPromise: Promise<LocalSkillItem[]> = opts.skipLocal
    ? Promise.resolve([])
    : getLocalSkills().catch(() => []);

  const [remoteResult, localAll] = await Promise.all([remotePromise, localPromise]);

  let remoteList: any[] = [];
  let remoteTotal = 0;
  let remoteOk = true;
  if (remoteResult && (remoteResult as any).__error) {
    remoteOk = false;
  } else {
    const r = remoteResult as any;
    remoteList = r?.results || r?.skills || r || [];
    if (!Array.isArray(remoteList)) remoteList = [];
    remoteTotal = r?.total || remoteList.length;
  }

  const localList = opts.skipLocal ? [] : filterLocalSkills(localAll, query);
  const merged = mergeSkills(remoteList, localList);

  // 远程失败且本地为空时，解析后端 JSON 错误串，抛出更具体的错误消息
  if (!remoteOk && localList.length === 0) {
    const rawErr = (remoteResult as any).__error;
    // 后端 mcp_call_v2 返回 JSON 错误串：{"code":"upstream_http_error","message":"..."}
    // Tauri invoke 拒绝时 e 可能是 string，也可能是 Error
    let msg: string;
    if (rawErr instanceof Error) {
      msg = rawErr.message;
    } else if (typeof rawErr === 'string') {
      // 尝试解析 JSON 错误串
      try {
        const parsed = JSON.parse(rawErr);
        msg = parsed?.message || rawErr;
      } catch {
        msg = rawErr;
      }
    } else {
      msg = String(rawErr);
    }
    const err = new Error(msg);
    (err as any).raw = rawErr;
    throw err;
  }

  const total = opts.skipLocal
    ? remoteTotal
    : remoteOk
      ? remoteTotal + merged.length - remoteList.length
      : merged.length;

  return {
    results: merged,
    total,
    executable: true,
    sources: { remote: remoteList.length, local: localList.length },
  };
}
