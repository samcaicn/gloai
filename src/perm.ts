// 统一权限状态中心（2026-11-20 权限交互重构）
// 目标：登录态 / 角色 / 配额 / 渠道 / 后端健康只有一份事实来源，多个页面共享，
// 避免 chat.ts / interact.ts / 首页各自 ai_gateway_status() 造成重复网络往返。
//
// 设计要点：
//   1. TTL 缓存 + in-flight 合并：TTL 内二次调用直接返回缓存；并发调用共享同一个 Promise。
//   2. 主动失效：登录 / 登出 / 写回限额 / 事件推送后 clearGateway()，下次读取强制回源。
//   3. 订阅广播：权限态刷新后通知所有视图，局部更新而非各自轮询。
//   4. 团长手机号「记住但不记密码」：仅手机号存 localStorage（方便下次写回少打一项），
//      密码永不落盘、永不进 state；这是用户在 0.4.3 定的「凭证不落盘」铁律的折中。

import { aiGatewayApi, type AiGatewayStatus } from "./ilink";

const TTL_MS = 8000; // 8 秒内复用，足以挡住同一次交互里 2~3 处重复读取

let cache: { at: number; value: AiGatewayStatus } | null = null;
let inflight: Promise<AiGatewayStatus> | null = null;

type Listener = (s: AiGatewayStatus | null) => void;
const listeners = new Set<Listener>();

/** 读取统一权限态。force=true 强制回源（登录/写回后的主动刷新用）。 */
export async function gatewayStatus(force = false): Promise<AiGatewayStatus> {
  const now = Date.now();
  if (!force && cache && now - cache.at < TTL_MS) return cache.value;
  if (inflight) return inflight;
  inflight = (async () => {
    const value = await aiGatewayApi.status();
    cache = { at: Date.now(), value };
    return value;
  })();
  try {
    return await inflight;
  } finally {
    inflight = null;
  }
}

/** 清缓存并广播失效（不主动请求，避免登录流程里打无用请求）。 */
export function invalidateGateway(): void {
  cache = null;
  // 广播 null：告诉订阅方「状态已过期」；订阅方可选择立即 gatewayStatus(true) 刷新
  listeners.forEach((fn) => {
    try { fn(null); } catch { /* 单个订阅方异常不影响其它 */ }
  });
}

/** 强制回源并广播新值。 */
export async function refreshGateway(): Promise<AiGatewayStatus> {
  const value = await gatewayStatus(true);
  listeners.forEach((fn) => {
    try { fn(value); } catch { /* noop */ }
  });
  return value;
}

export function onGatewayChange(fn: Listener): () => void {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

/** 统一角色文案：避免每页各写一套 leader/member/deputy 判断。 */
export function roleText(role?: string): string {
  switch (role) {
    case "leader": return "团长账号";
    case "member": return "团员账号";
    case "deputy": return "团副账号";
    default: return "已登录";
  }
}

const LEADER_TEL_KEY = "bbt-leader-tel";

/** 记住团长手机号（明文、仅本机浏览器存储；密码永不存）。 */
export function rememberLeaderTel(tel: string): void {
  try {
    if (tel) localStorage.setItem(LEADER_TEL_KEY, tel);
    else localStorage.removeItem(LEADER_TEL_KEY);
  } catch { /* 隐私模式忽略 */ }
}

export function rememberedLeaderTel(): string {
  try { return localStorage.getItem(LEADER_TEL_KEY) || ""; } catch { return ""; }
}
