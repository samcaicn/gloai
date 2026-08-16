// 模型列表相关 Tauri 命令封装。
// 通过后端 mcp_call_v2 命令调用 MCP action 'model.list'，获取云端可用模型列表。
// MCP 响应格式 { ok, data, error }，需解包 data 层。
//
// 模型分类：
//   - text：文本对话模型，前端默认使用 'default'（不传 model 字段，云端走默认路由）
//   - image / video / audio / embedding：多模态模型，通过 MCP 获取供用户选择
import { invoke } from './invoke';
import { mcpCallWithRefresh } from './device';

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

/** 单个模型信息。 */
export interface ModelInfo {
  id: string;
  name: string;
  /** 模型类型：text / image / video / audio / embedding */
  type?: string;
  /** 模型能力标签数组。 */
  capabilities?: string[];
  /** 提供商。 */
  provider?: string;
  /** 上下文窗口大小。 */
  contextWindow?: number;
}

/** 从 MCP 响应中归一化模型列表。兼容多种字段名。 */
function normalizeModels(data: any): ModelInfo[] {
  const raw = Array.isArray(data)
    ? data
    : data?.models || data?.items || data?.list || [];
  if (!Array.isArray(raw)) return [];
  return raw.map((m: any): ModelInfo => ({
    id: String(m?.id || m?.model_id || m?.model || ''),
    name: String(m?.name || m?.label || m?.model_name || m?.id || ''),
    type: m?.type || m?.modality || m?.category || '',
    capabilities: Array.isArray(m?.capabilities) ? m.capabilities : undefined,
    provider: m?.provider || m?.provider_id || undefined,
    contextWindow: typeof m?.context_window === 'number' ? m.context_window : undefined,
  })).filter((m: ModelInfo) => m.id);
}

/**
 * 通过 MCP action 'model.list' 获取云端可用模型列表。
 * 失败时返回空数组（调用方降级为仅显示 'default'）。
 */
export async function listModelsViaMcp(): Promise<ModelInfo[]> {
  try {
    // mcpCallWithRefresh 内部读 localStorage token，auth 失败时自动 fingerprint 刷新 + 重试，
    // 覆盖会话中途 12h token 过期场景（直接 invoke 会在过期时静默返回空列表）。
    const r = await mcpCallWithRefresh('model.list', {});
    const data = unwrapMcpResponse(r);
    return normalizeModels(data);
  } catch {
    // MCP action 不存在或网络失败时降级为空列表
    return [];
  }
}

/**
 * Dashboard 主模型配置 (后端 get_dashboard_primary_model_config)。
 * 用于本地 cron trigger 时把 provider/base_url/api_key/model 一起传给后端,
 * 让 LLMService 用对应 provider 跑 prompt。失败返回 null (前端再降级)。
 */
export interface DashboardPrimaryModel {
  provider: string;
  baseUrl: string;
  apiKey: string;
  model: string;
}

export async function getDashboardPrimaryModel(): Promise<DashboardPrimaryModel | null> {
  try {
    const cfg = await invoke<any>('get_dashboard_primary_model_config');
    if (!cfg || typeof cfg !== 'object') return null;
    const provider = String(cfg.provider || '').trim();
    const baseUrl = String(cfg.baseUrl || cfg.base_url || '').trim();
    const apiKey = String(cfg.apiKey || cfg.api_key || '').trim();
    const model = String(cfg.model || '').trim();
    if (!provider || !baseUrl || !apiKey || !model) return null;
    return { provider, baseUrl, apiKey, model };
  } catch {
    return null;
  }
}
