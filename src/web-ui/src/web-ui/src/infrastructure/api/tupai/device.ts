// 设备注册相关 Tauri 命令封装。
// 命令名已对齐后端 lib.rs 的 invoke_handler 注册（commands/device_register.rs）：
//   registerDevice    → register_device      (join_code: String) —— 注意参数名是 joinCode
//   renewDeviceToken  → renew_device_token   (existing_token: String)
//
// 设计原则（2026-07-18 重构）：
//   客户端不持久化绑定信息（approval_status / bind_request_id）。
//   启动时通过 renew 校验 token 是否有效——服务器是唯一真相源。
//   已审批设备 token 保持不变，服务器只是放行 MCP 权限，不需要重复注册。
//   只有 token 真失效（expired/revoked）时才需要用户重新注册。
import { invoke } from './invoke';
import { createLogger } from '@/shared/utils/logger';
import { useDeviceStatusStore } from '@/shared/stores/deviceStatusStore';

const log = createLogger('device');

// localStorage 只保留 device_token（MCP 鉴权必需）。
// 审批状态 / bind request_id 不再持久化——启动时从服务器实时查询。
const DEVICE_TOKEN_KEY = 'trae_device_token';

/// 白名单 action：pending_approval / rejected 设备也可调用的 MCP action。
/// Flow 1 未审批设备只能调这些 action（绑定 / 查状态 / 验证 token / 续期）。
/// 用于 mcpCallWithRefresh / llmStreamChat 入口门控。
const WHITELIST_ACTIONS = new Set([
  'client.bind',
  'client.bind.status',
  'tenant.get',
  'client.renew',
]);

/** 设备审批状态（运行时推断，不持久化）
 *  词汇对齐服务器 4-flow：active=已审批, pending_approval=待审批/未绑定, rejected=已拒绝, unknown=未知 */
export type DeviceApprovalStatus = 'active' | 'pending_approval' | 'rejected' | 'unknown';

/** register_device 命令返回结果（camelCase,与后端 serde rename_all 对齐） */
export interface RegisterResult {
  token: string;
  deviceId: string;
  tenantId: string;
  isNewDevice: boolean;
  approvalStatus: string;
  nextStep: string | null;
  /** bind 请求 ID,用于轮询审批状态（pending 时非空） */
  requestId: string | null;
}

/** register_device 命令错误响应 */
export interface RegisterError {
  code: string;
  message: string;
}

/** renew_device_token 命令返回结果 */
export interface RenewResult {
  token: string | null;
  valid: boolean;
}

/** ensureDeviceToken 内部汇总，给启动 hook 用 */
export interface EnsureResult {
  /** 续期后生效的 token（可能等于旧值，也可能被清空） */
  token: string | null;
  valid: boolean;
  /** localStorage 是否发生变化（写入了新 token 或清空了旧 token） */
  changed: boolean;
  /** 静默续期是否被跳过（无 token 直接返回 true） */
  skipped: boolean;
}

/** check_bind_status 命令返回结果 */
export interface BindStatusResult {
  status: string;
  raw: unknown;
}

function readDeviceToken(): string | null {
  try {
    return typeof localStorage !== 'undefined' ? localStorage.getItem(DEVICE_TOKEN_KEY) : null;
  } catch {
    return null;
  }
}

/**
 * 把新 token 写到 localStorage 并 emit 事件。
 * 调用方负责确保新 token 非空且与旧的不同。
 */
function writeDeviceToken(token: string): void {
  try {
    if (typeof localStorage === 'undefined') return;
    localStorage.setItem(DEVICE_TOKEN_KEY, token);
    notifyDeviceStatusChanged('silent-renew', token);
  } catch {
    // localStorage 不可写（隐私模式 / 配额满）—— 忽略，不影响续期逻辑
  }
}

/** 清空 localStorage 中的 device_token 并 emit 事件。
 * 用于服务器判 valid=false（token 真过期/被吊销）时，让 UI 立刻反映"未注册"状态。
 */
export function clearDeviceToken(): void {
  try {
    if (typeof localStorage === 'undefined') return;
    localStorage.removeItem(DEVICE_TOKEN_KEY);
    notifyDeviceStatusChanged('silent-renew-cleared', null);
  } catch {}
}

/**
 * 通知设备状态可能已变化，让 UI（SceneBar 指示灯、设置页等）刷新。
 *
 * 与 writeDeviceToken/clearDeviceToken 不同，此函数仅 dispatch 事件，
 * 不修改 localStorage——用于 ensureDeviceToken 更新 store 后通知 UI。
 *
 * 关键场景：重启时旧 token 仍有效（MCP tenant.get 验证成功且 token 未变），
 * 此时 localStorage 不变、store 状态从 'pending_approval' 变为 'active'，
 * 但若不 emit 事件，SceneBar 指示灯会一直停在黄色（pending_approval），
 * 用户误以为需要重新填注册码。
 */
function notifyDeviceStatusChanged(source: string, token: string | null): void {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(
    new CustomEvent('tupai:device-token-changed', { detail: { token, source } }),
  );
}

/**
 * 运行时获取设备审批状态。
 *
 * 优先读取共享 store（由 ensureDeviceToken / registerDevice 更新）；
 * 无 store 值时按以下规则推断：
 *   - 无 token → unknown
 *   - 有 token 但无显式状态 → pending_approval（避免误判为 active）
 *   - 有显式状态 → 用该状态
 */
export function getDeviceApprovalStatus(): DeviceApprovalStatus {
  const { approvalStatus } = useDeviceStatusStore.getState();
  if (approvalStatus !== 'unknown') return approvalStatus;

  // store 还没被 ensureDeviceToken 更新时，返回 unknown 而非 pending，
  // 避免在 MCP 验证成功前误显示"审核中"。
  // SceneBar 指示灯在 unknown 时显示红灯，ensureDeviceToken 成功后变绿。
  return 'unknown';
}

// ── 以下函数保留导出以兼容旧调用方，但不再做 localStorage 持久化 ──

/**
 * @deprecated 审批状态不再持久化，改用 getDeviceApprovalStatus() 运行时推断。
 * 保留此函数仅为兼容旧代码，返回值与 getDeviceApprovalStatus() 一致。
 */
export function readDeviceApprovalStatus(): DeviceApprovalStatus {
  return getDeviceApprovalStatus();
}

/**
 * @deprecated 审批状态不再持久化，此函数变为 no-op。
 * 保留导出仅为兼容旧代码的调用，不再写 localStorage。
 */
export function writeDeviceApprovalStatus(_status: DeviceApprovalStatus): void {
  // no-op: 审批状态不持久化，由服务器实时查询
}

/**
 * @deprecated bind request_id 不再持久化。返回 null。
 * pending 审批场景重启后需要重新 register，不恢复旧的轮询。
 */
export function readBindRequestId(): string | null {
  return null;
}

/**
 * @deprecated bind request_id 不再持久化。此函数变为 no-op。
 */
export function writeBindRequestId(_requestId: string | null): void {
  // no-op: bind request_id 不持久化
}

/** 将后端返回的 approvalStatus 字符串规范化为 DeviceApprovalStatus。
 *  对齐服务器 4-flow 词汇：active / pending_approval / unknown。
 *
 *  服务器设备生命周期**没有** "rejected" 状态（CLAUDE.md 服务器 API 流程规则核实）：
 *  审批只有待审批/通过两态。bind 请求失败是「未绑定/可重试」，未绑定设备是
 *  `device_not_bound`，二者都应呈现为 `pending_approval`，绝不能显示 "被拒绝"。
 *  因此任何被误读为 reject 的值（grant 主动拒绝 / 未绑定 / 绑定失败）一律收敛到
 *  `pending_approval`，避免用户看到误导性的「设备已被拒绝」。 */
export function normalizeApprovalStatus(raw: string): DeviceApprovalStatus {
  const lower = (raw || '').toLowerCase();
  if (lower === 'active' || lower === 'approved') return 'active';
  // 未绑定 / 绑定失败 / 服务器拒绝 一律归 pending_approval（服务器不返回 rejected）
  if (
    lower === 'pending_approval' ||
    lower === 'pending' ||
    lower === 'unbound' ||
    lower === 'device_not_bound' ||
    lower === 'not_bound' ||
    lower === 'rejected' ||
    lower === 'reject' ||
    lower === 'declined' ||
    lower === 'denied' ||
    lower === 'deny' ||
    lower === 'disabled' ||
    lower === 'revoked'
  ) {
    return 'pending_approval';
  }
  return 'unknown';
}

// 后端 register_device 期望 (join_code: String)。
// join_code 可为空字符串——已审批设备服务器在 fingerprint 阶段自动通过（跳过 bind）。
export async function registerDevice(joinCode: string): Promise<RegisterResult> {
  const result = await invoke<RegisterResult>('register_device', { joinCode });
  const status = normalizeApprovalStatus(result.approvalStatus);
  useDeviceStatusStore.getState().setStatus({ approvalStatus: status, token: result.token });
  return result;
}

// 后端 renew_device_token 期望 (existing_token: String)。
// 不传 token 时自动从 localStorage 读取，避免调用方忘记透传。
//
// @deprecated 服务器 `client.renew` action 会轮换 token 并吊销旧 token，导致并发
// 触发的 tenant.get / skill.search / llm.stream_request 仍读旧 token 而全部
// `auth.token_invalid`。启动校验已改用 `verifyTokenViaTenantGet`（tenant.get 只读
// 不轮换）。保留此函数仅为向后兼容，无内部调用方。
export async function renewDeviceToken(token?: string): Promise<RenewResult> {
  const existingToken = token ?? readDeviceToken() ?? '';
  const result = await invoke<RenewResult>('renew_device_token', { existingToken });
  return { token: result?.token ?? null, valid: result?.valid === true };
}

/**
 * 用 MCP `tenant.get` 验证 device_token 是否被服务器 auth 层放行。
 *
 * 关键：用 `tenant.get` 而非 `client.renew` 验证 —— `client.renew` 会轮换 token
 * （返回新 device_token 同时吊销旧 token），导致并发触发的 tenant.get /
 * skill.search / llm.stream_request 仍读旧 token 而全部 `auth.token_invalid`。
 * `tenant.get` 是只读查询，不轮换 token，验证通过后原 token 仍有效，
 * 后续业务调用直接复用同一 token。
 *
 * 判定规则（已通过 live 服务器诊断证实，见
 * .trae/documents/fix-device-token-renew-rotation.md）：
 *   - `ok: true` → valid（设备已绑，token 有效）
 *   - `error.code` 含 auth/token_invalid/unauthorized/invalid token → invalid（token 真失效）
 *   - 其它错误（如 `device_not_bound`）→ valid（auth 已通过，token 有效，只是设备未绑）
 *   - invoke 抛错且 message 含 HTTP 401/403 → invalid
 *   - invoke 抛错（网络/5xx/超时，非 401/403）→ 保守 valid（不登出用户，下次启动再试）
 *
 * 后端 mcp_call_v2 命令（mcp_proxy.rs）代理到 /api/v2/mcp，HTTP 非 2xx 返回 Err JSON
 * `{"code":"upstream_http_error","message":"MCP tenant.get returned HTTP 401",...}`；
 * HTTP 2xx 时返回 Ok(MCP 信封 { ok, data, error })。
 */
async function verifyTokenViaTenantGet(
  token: string,
): Promise<{ valid: boolean; reason: string; tenantId?: string }> {
  if (!token) return { valid: false, reason: 'empty token' };
  try {
    const r = await invoke<any>('mcp_call_v2', {
      action: 'tenant.get',
      params: {},
      token,
    });
    // MCP 信封: { ok, data, error: { code, message } | null }
    if (r?.ok === true) {
      // 从 tenant.get 响应提取 tenant_id —— 用于区分 active(非空) vs pending_approval(空)
      const tenantId = r?.data?.tenant_id ? String(r.data.tenant_id) : undefined;
      return { valid: true, reason: 'tenant.get ok (device bound)', tenantId };
    }
    const errCode = String(r?.error?.code ?? '');
    const errMsg = String(r?.error?.message ?? '');
    const authRejected = /\bauth\.|token_invalid|unauthorized|invalid token/i.test(
      `${errCode} ${errMsg}`,
    );
    if (authRejected) {
      return { valid: false, reason: `tenant.get auth-rejected: ${errCode || errMsg}` };
    }
    // 非 auth 错误（如 device_not_bound）→ auth 已通过，token 有效
    return {
      valid: true,
      reason: `tenant.get auth-passed (non-auth err: ${errCode || 'unknown'})`,
    };
  } catch (e: any) {
    const msg = e?.message ?? String(e);
    // HTTP 401/403 → token 无效
    if (/HTTP 40[13]|\bauth\.|token_invalid|unauthorized|invalid token/i.test(msg)) {
      return { valid: false, reason: `tenant.get http-auth error: ${msg}` };
    }
    // 网络/5xx/超时 → 保守 valid=true，避免误登出用户
    return { valid: true, reason: `tenant.get inconclusive (kept token): ${msg}` };
  }
}

/**
 * 统一检测 auth token 失效错误。兼容两种形态：
 *   - 字符串 / Error（来自 catch 块，如后端 mcp_call_v2 HTTP 401 返回的 JSON 错误串）
 *   - MCP 信封对象 { ok: false, error: { code, message } }（来自 invoke resolve）
 *
 * 用于 mcpCallWithRefresh / llmStreamChat 判断是否需要刷新 token。
 */
export function isAuthTokenInvalid(value: unknown): boolean {
  if (value == null) return false;

  // 字符串 / Error → 检查 message（含 HTTP 401/403，后端 mcp_call_v2 在
  // 上游 HTTP 非 2xx 时 reject，message 含 "MCP ... returned HTTP 401"）
  const msg =
    typeof value === 'string'
      ? value
      : value instanceof Error
        ? value.message
        : '';
  if (msg) {
    return /\bauth\.|token_invalid|unauthorized|invalid token|HTTP 40[13]/i.test(msg);
  }

  // 对象 → 检查 MCP 信封 error.code / error.message
  //（信封级 auth 错误不附 HTTP 状态码，故不匹配 HTTP 401）
  if (typeof value === 'object') {
    const r = value as Record<string, any>;
    if (r.ok === true) return false;
    const errCode = String(r?.error?.code ?? '');
    const errMsg = String(r?.error?.message ?? '');
    if (errCode || errMsg) {
      return /\bauth\.|token_invalid|unauthorized|invalid token/i.test(`${errCode} ${errMsg}`);
    }
  }

  return false;
}

/**
 * 通过 fingerprint 获取新 token，然后用 MCP `tenant.get` 验证 token 可用。
 *
 * 流程（实现"启动后自动检测 MCP 能否成功，成功就置绿，无需 join_code"）：
 *   1. registerDevice('') → fingerprint 端点签发 token
 *      · 服务器识别该指纹（已审批设备）→ 返回 active + token
 *      · 服务器不识别 → 返回 pending_approval + token（后端不再报错，直接给 token）
 *   2. 只要有 token → 调 verifyTokenViaTenantGet(token) 走 MCP `tenant.get` 验证
 *      · 验证 valid=true → token 真正可用，置绿写入 localStorage（无需 join_code）
 *      · 验证 valid=false → token 被签发但 auth 层拒绝，不写入（用户需输 join_code）
 *      · 网络/IPC 错误 → 保守认为失败，下次启动再试
 *
 * 关键：用 `tenant.get`（只读、幂等、不轮换 token）验证，而非 `client.renew`。
 * `client.renew` 会轮换 token 并吊销旧 token，导致并发触发的 tenant.get /
 * skill.search / llm.stream_request 仍读旧 token 而全部 `auth.token_invalid`
 * （live 诊断证实，见 .trae/documents/fix-device-token-renew-rotation.md）。
 * 用 tenant.get 验证后，fingerprint 签发的原始 token 保持有效，后续业务调用直接复用。
 *
 * 不再以 fingerprint 的 approval_status 作为门控。只要服务器签发了 token，
 * 就尝试 MCP 验证——服务器通常会在 fingerprint 阶段直接签发可用 token，
 * MCP 成功即可置绿，无需用户输入 join_code。
 *
 * 用于两种场景：
 *   - localStorage 无 token（重装/升级后）→ 自动 fingerprint + MCP 验证
 *   - 旧 token 被服务器判 invalid → 清空后自动 fingerprint + MCP 验证
 *
 * 返回 success=true 表示 token 已通过 MCP 验证，调用方可直接写入 localStorage。
 */
async function fingerprintAndVerifyMcp(): Promise<{
  success: boolean;
  token: string | null;
  status: DeviceApprovalStatus;
  reason: string;
}> {
  // Step 1: fingerprint 获取 token
  let regResult: RegisterResult;
  try {
    regResult = await registerDevice('');
  } catch (e: any) {
    return {
      success: false,
      token: null,
      status: 'pending_approval',
      reason: `fingerprint failed: ${e?.message || String(e)}`,
    };
  }

  // 审批状态由 fingerprint 响应的 activation 字段决定（而非 tenant.get 成功）。
  // tenant.get 是白名单 action，pending 设备也能调通，不能作为"已审批"信号。
  const status = normalizeApprovalStatus(regResult.approvalStatus);

  // 无 token → 无法进行 MCP 验证，需要用户输 join_code
  if (!regResult.token) {
    return {
      success: false,
      token: null,
      status,
      reason: `no token from fingerprint (status=${status})`,
    };
  }

  // Step 2: 有 token → 调 MCP tenant.get 验证 token 真正可用（不轮换 token）
  // tenant.get 仅作 token 有效性探针（auth 层是否放行），不决定审批状态。
  // 审批状态已由上方 activation 字段派生（status 变量）。
  // verifyTokenViaTenantGet 内部已捕获所有异常并返回 {valid, reason}，不抛错。
  const verify = await verifyTokenViaTenantGet(regResult.token);
  if (!verify.valid) {
    // token 签发了但 auth 层拒绝放行 → 设备未真正通过审批。
    // 服务器无 rejected 状态，统一收敛为 pending（未绑定/待审批），不能显示 active。
    const failStatus: DeviceApprovalStatus = 'pending_approval';
    return {
      success: false,
      token: regResult.token,
      status: failStatus,
      reason: `MCP tenant.get rejected token: ${verify.reason}`,
    };
  }
  // MCP 验证通过 → 直接用 fingerprint 原始 token（tenant.get 不轮换，原 token 仍有效）。
  // 关键：status 沿用 activation 派生值（active 或 pending_approval），不再硬编码 'active'。
  // pending 设备也需要 token 调 client.bind，所以 success=true + status=pending_approval 是合法组合。
  return {
    success: true,
    token: regResult.token,
    status,
    reason: `fingerprint + MCP verified: ${verify.reason}`,
  };
}

// ── 会话中途 token 过期自动刷新 ──────────────────────────
//
// 服务器签发的 device_token 12h 过期。app 运行 > 12h 后，skill.search /
// llm.stream_request 等业务调用会开始返回 auth.token_invalid。ensureDeviceToken
// 只在启动时运行，无法覆盖会话中途的 token 过期。
//
// refreshDeviceToken 是会话中途的轻量刷新：调 fingerprintAndVerifyMcp 获取
// 新 token，写 localStorage + dispatch tupai:device-token-changed 事件，
// 让所有消费者（MainNav tenantInfo、SceneBar 指示灯、skill/llm 后续调用）自动刷新。
//
// 并发安全：多个 API 调用同时遇到 auth.token_invalid 时，共用同一个
// refreshPromise singleton，只发一次 fingerprint 请求。

let refreshPromise: Promise<{ success: boolean; token: string | null }> | null = null;

/**
 * 会话中途 token 过期时，自动 fingerprint 获取新 token。
 *
 * 调 fingerprintAndVerifyMcp()（fingerprint + tenant.get 验证），
 * 成功后写 localStorage + dispatch tupai:device-token-changed 事件，
 * 让所有消费者（MainNav tenantInfo、SceneBar 指示灯等）自动刷新。
 *
 * 并发安全：模块级 refreshPromise singleton，多个调用方同时触发
 * auth.token_invalid 时共用同一个 Promise，只发一次 fingerprint 请求。
 * finally 中清空 refreshPromise，下次过期时可再次刷新。
 */
export async function refreshDeviceToken(): Promise<{
  success: boolean;
  token: string | null;
}> {
  if (refreshPromise) return refreshPromise;

  refreshPromise = (async () => {
    try {
      const r = await fingerprintAndVerifyMcp();
      if (r.success && r.token) {
        writeDeviceToken(r.token);
        // 用 fingerprintAndVerifyMcp 返回的 status（由 activation 派生），不再硬编码 'active'
        useDeviceStatusStore.getState().setStatus({ approvalStatus: r.status, token: r.token });
        log.info(`refreshDeviceToken: refreshed via fingerprint + MCP verified (${r.reason}, status=${r.status})`);
        return { success: true, token: r.token };
      }
      const finalStatus: DeviceApprovalStatus = r.status === 'unknown' ? 'pending_approval' : r.status;
      useDeviceStatusStore.getState().setStatus({ approvalStatus: finalStatus, token: r.token });
      notifyDeviceStatusChanged('refresh-failed', r.token);
      log.warn(`refreshDeviceToken: refresh failed: ${r.reason}`);
      return { success: false, token: r.token };
    } finally {
      refreshPromise = null;
    }
  })();

  return refreshPromise;
}

/**
 * 带 token 自动刷新的非流式 MCP 调用包装器。
 *
 * 读 token → invoke('mcp_call_v2', ...) → 如果返回 auth 错误（MCP 信封
 * { ok: false, error: { code: 'auth.token_invalid' } } 或 invoke reject
 * 含 HTTP 401）→ refreshDeviceToken() 获取新 token → 重试一次。
 *
 * 非 auth 错误（如 device_not_bound、网络超时）→ 不重试，直接返回/抛出。
 * refresh 失败 → 返回/抛出原始错误，让调用方处理。
 *
 * 返回原始 MCP 信封 { ok, data, error }，由调用方 unwrapMcpResponse 解包。
 *
 * 用于 skill.ts 的 skillLoad / skillSceneTags / skillTopByTags / searchSkillsRemote。
 * llm.ts 的流式调用在 llmStreamChat 内部单独处理（async generator 重试逻辑不同）。
 */
export async function mcpCallWithRefresh(
  action: string,
  params: Record<string, any>,
): Promise<any> {
  // 白名单门控：pending_approval/rejected 设备调非白名单 action 时提前失败，
  // 避免无意义 refresh+重试循环（tenant.get 白名单成功 → 误判 token 有效 → 重试非白名单 action → 再失败）。
  // unknown 放行（启动竞态/网络不确定时由服务器兜底，避免误阻塞）。
  if (!WHITELIST_ACTIONS.has(action)) {
    const st = getDeviceApprovalStatus();
    if (st === 'pending_approval' || st === 'rejected') {
      // 抛 Error（带 code）而非裸对象：调用方 catch 后 instanceof Error / .message /
      // String(e) 均可拿到可读消息，避免 searchAllSkills 等处显示 "[object Object]"。
      const err = new Error('设备未审批通过，此功能暂不可用');
      (err as any).code = 'device.not_approved';
      throw err;
    }
  }

  const token = readDeviceToken() ?? '';
  const doCall = (tok: string) => invoke<any>('mcp_call_v2', { action, params, token: tok });

  try {
    let r = await doCall(token);
    // MCP 信封 auth 错误 → 刷新 + 重试
    if (isAuthTokenInvalid(r)) {
      const refresh = await refreshDeviceToken();
      if (refresh.success && refresh.token) {
        r = await doCall(refresh.token);
      }
    }
    return r;
  } catch (e) {
    // invoke reject（HTTP 401/403 from backend）→ 检测 auth → 刷新 + 重试
    if (isAuthTokenInvalid(e)) {
      const refresh = await refreshDeviceToken();
      if (refresh.success && refresh.token) {
        return doCall(refresh.token);
      }
    }
    throw e;
  }
}

/**
 * 启动时静默验证 / 自动注册设备指纹 + 验证 MCP 请求成功。
 *
 * 行为（2026-07-22 重构：用 tenant.get 验证，避免 client.renew 轮换 token）：
 *   - localStorage 无 token（重装/升级后）→ fingerprintAndVerifyMcp()
 *     · fingerprint 拿 token + MCP tenant.get 验证 → MCP 成功才写 localStorage
 *     · 服务器签发 token 后直接尝试 MCP 验证，不依赖 approval_status
 *     · 验证 valid=true → 置绿，无需用户输 join_code
 *     · 验证 valid=false → 标记 pending_approval，让用户手动注册
 *     · 网络错误 → 标记 pending_approval，下次启动再试
 *   - localStorage 有 token（退出重启后）→ verifyTokenViaTenantGet 校验
 *     · 验证 valid=false（auth.token_invalid / HTTP 401）→ 清空 localStorage，
 *       触发 fingerprintAndVerifyMcp 重注册
 *     · 验证 valid=true → 保留原 token（不轮换、不写 localStorage）
 *     · 网络/5xx/超时 → 保守判 valid=true，保留旧 token
 *
 * 关键：用 `tenant.get`（只读、不轮换 token）验证，而非 `client.renew`。
 * `client.renew` 会轮换 token 并吊销旧 token，导致并发触发的 tenant.get /
 * skill.search / llm.stream_request 仍读旧 token 而全部 `auth.token_invalid`
 * （live 诊断证实，见 .trae/documents/fix-device-token-renew-rotation.md）。
 * 用 tenant.get 验证后 token 不被轮换，后续业务调用直接复用同一 token。
 *
 * 故意设计成"绝不抛错"：启动期任何网络问题都不应阻塞 UI。
 */
export async function ensureDeviceToken(): Promise<EnsureResult> {
  const existing = readDeviceToken();

  // ── 无 token：自动 fingerprint + MCP 验证 ──────────────
  if (!existing) {
    useDeviceStatusStore.getState().setStatus({ approvalStatus: 'unknown', token: null });
    const r = await fingerprintAndVerifyMcp();
    if (r.success && r.token) {
      // fingerprint + MCP 验证双通过 → 写 localStorage，无需再注册设备。
      // status 由 activation 派生（active 或 pending_approval），不再硬编码 'active'。
      writeDeviceToken(r.token);
      useDeviceStatusStore.getState().setStatus({ approvalStatus: r.status, token: r.token });
      log.info(`ensureDeviceToken: auto-registered via fingerprint + MCP verified (${r.reason}, status=${r.status})`);
      return { token: r.token, valid: true, changed: true, skipped: false };
    }
    // 未通过 → 不写 localStorage，让用户手动注册
    const finalStatus: DeviceApprovalStatus = r.status === 'unknown' ? 'pending_approval' : r.status;
    useDeviceStatusStore.getState().setStatus({ approvalStatus: finalStatus, token: r.token });
    // 通知 UI 刷新指示灯（即使 localStorage 没变，store 状态已变）
    notifyDeviceStatusChanged('fingerprint-failed', r.token);
    log.warn(`ensureDeviceToken: auto-fingerprint + MCP verify failed: ${r.reason}`);
    return { token: r.token, valid: false, changed: false, skipped: false };
  }

  // ── 有 token：用 MCP tenant.get 验证（不轮换 token）──
  // verifyTokenViaTenantGet 内部已捕获所有异常并返回 {valid, reason}，不抛错。
  // 外层 try/catch 仅作兜底防御（store / 事件分发等同步操作理论上不抛）。
  try {
    const verify = await verifyTokenViaTenantGet(existing);

    if (!verify.valid) {
      // 服务器显式判 token 无效（auth.token_invalid / HTTP 401）→ 清空 localStorage，
      // 尝试 fingerprint + MCP 验证重注册
      clearDeviceToken();
      // 立即重置 store 为 unknown（红灯），避免在 fingerprint 网络往返期间
      // SceneBar 仍读旧 store 显示绿灯/黄灯，误导用户以为仍处于已注册状态。
      useDeviceStatusStore.getState().setStatus({ approvalStatus: 'unknown', token: null });
      log.info(`ensureDeviceToken: token rejected by MCP (${verify.reason}), attempting auto-fingerprint re-register`);
      const r = await fingerprintAndVerifyMcp();
      if (r.success && r.token) {
        writeDeviceToken(r.token);
        useDeviceStatusStore.getState().setStatus({ approvalStatus: r.status, token: r.token });
        log.info(`ensureDeviceToken: token rejected but auto-fingerprint + MCP verified (${r.reason}, status=${r.status})`);
        return { token: r.token, valid: true, changed: true, skipped: false };
      }
      const finalStatus: DeviceApprovalStatus = r.status === 'unknown' ? 'pending_approval' : r.status;
      useDeviceStatusStore.getState().setStatus({ approvalStatus: finalStatus, token: r.token });
      // 通知 UI 刷新指示灯（重注册失败，状态变为 pending/rejected）
      notifyDeviceStatusChanged('re-register-failed', r.token);
      return { token: r.token, valid: false, changed: true, skipped: false };
    }

    // token 验证通过 → 保留原 token（tenant.get 不轮换，无需写 localStorage）。
    // 审批状态用 tenant.get 响应的 tenant_id 判定：
    //   - tenant_id 非空 → active（设备已绑定业务租户，全量 MCP 放行）
    //   - tenant_id 空/缺失 → pending_approval（token 有效但设备未绑定，只能调白名单）
    // 这是修复"pending 设备被误判为 active"的关键——tenant.get 是白名单 action，
    // pending 设备也能调通，但 pending 设备的 tenant_id 为空。
    const tokenStatus: DeviceApprovalStatus = verify.tenantId ? 'active' : 'pending_approval';
    useDeviceStatusStore.getState().setStatus({ approvalStatus: tokenStatus, token: existing });
    notifyDeviceStatusChanged('mcp-verified-unchanged', existing);
    log.info(`ensureDeviceToken: token verified via tenant.get, unchanged (${verify.reason}, status=${tokenStatus})`);
    return { token: existing, valid: true, changed: false, skipped: false };
  } catch (e) {
    // 兜底防御：verifyTokenViaTenantGet 已捕获所有异常，正常不会走到这里。
    // 保守保留旧 token，不抛错（启动期不应阻塞 UI）。
    // token 存在说明之前通过过审批，保守判 active。
    log.warn('ensureDeviceToken unexpected error, keeping existing token:', { error: e });
    useDeviceStatusStore.getState().setStatus({ approvalStatus: 'active', token: existing });
    notifyDeviceStatusChanged('ipc-fallback-active', existing);
    return { token: existing, valid: true, changed: false, skipped: false };
  }
}

// 后端 check_bind_status 期望 (request_id: String, device_token: String)。
// 用于轮询 client.bind 审批状态。
export async function checkBindStatus(
  requestId: string,
  deviceToken?: string,
): Promise<BindStatusResult> {
  const token = deviceToken ?? readDeviceToken() ?? '';
  return invoke('check_bind_status', { requestId, deviceToken: token });
}

// 注：unregisterDevice / unregister_device 已移除。
// 用户决策：客户端不再有解绑能力与入口（解绑由服务器管理员操作）。
// 客户端通过现有 token 被拒路径（ensureDeviceToken has-token 分支 tenant.get 失败 →
// 清 token → 重走 fingerprint 流程 1）自动恢复。
