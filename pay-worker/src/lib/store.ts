import type { CreditsRecord, Env, Order } from '../types';

/**
 * KV 存储助手。键空间：
 *   order:<id>          支付单（含快照积分）
 *   otn:<out_trade_no>  -> orderId（webhook 回查）
 *   credits:<mid>       积分余额
 *   rl:<mid>:<minute>   创建支付单限流计数（TTL 120s）
 *   cert:<serial>       微信平台证书 PEM 缓存
 */

const ORDER_TTL_SEC = 7 * 24 * 3600; // 支付单留 7 天，够对账
const CREDITS_TTL_SEC = 365 * 24 * 3600;

export async function saveOrder(env: Env, order: Order): Promise<void> {
  await env.PAY_KV.put(`order:${order.id}`, JSON.stringify(order), { expirationTtl: ORDER_TTL_SEC });
  await env.PAY_KV.put(`otn:${order.outTradeNo}`, order.id, { expirationTtl: ORDER_TTL_SEC });
}

export async function getOrder(env: Env, id: string): Promise<Order | null> {
  const raw = await env.PAY_KV.get(`order:${id}`);
  return raw ? (JSON.parse(raw) as Order) : null;
}

export async function getOrderIdByOutTradeNo(env: Env, outTradeNo: string): Promise<string | null> {
  return env.PAY_KV.get(`otn:${outTradeNo}`);
}

export async function getCredits(env: Env, mid: string): Promise<number> {
  const raw = await env.PAY_KV.get(`credits:${mid}`);
  return raw ? (JSON.parse(raw) as CreditsRecord).credits : 0;
}

/** 加积分，返回加后的余额。 */
export async function addCredits(env: Env, mid: string, delta: number): Promise<number> {
  const key = `credits:${mid}`;
  const raw = await env.PAY_KV.get(key);
  const current = raw ? (JSON.parse(raw) as CreditsRecord).credits : 0;
  const next = current + delta;
  const record: CreditsRecord = { mid, credits: next, updatedAt: Date.now() };
  await env.PAY_KV.put(key, JSON.stringify(record), { expirationTtl: CREDITS_TTL_SEC });
  return next;
}

/**
 * 每实例每分钟限流：超限返回 false。防白嫖靠限流而非密钥保密
 * （客户端域名/共享串必然可提取，见 creem-payment-integration 技能的同类结论）。
 */
export async function rateLimit(env: Env, mid: string, limit = 20): Promise<boolean> {
  const key = `rl:${mid}:${Math.floor(Date.now() / 60000)}`;
  const raw = await env.PAY_KV.get(key);
  const used = raw ? Number.parseInt(raw, 10) : 0;
  if (used >= limit) return false;
  await env.PAY_KV.put(key, String(used + 1), { expirationTtl: 120 });
  return true;
}

// ---- 微信平台证书缓存（按序列号） ----

export async function getCachedCert(env: Env, serial: string): Promise<string | null> {
  return env.PAY_KV.get(`cert:${serial}`);
}

export async function cacheCert(env: Env, serial: string, pem: string, expireAt: number): Promise<void> {
  const ttl = Math.max(60, Math.floor((expireAt - Date.now()) / 1000));
  await env.PAY_KV.put(`cert:${serial}`, pem, { expirationTtl: ttl });
}
