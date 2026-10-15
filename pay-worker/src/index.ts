import type { Env, Order } from './types';
import { findTier, listTiers } from './lib/tiers';
import { addCredits, getCredits, getOrder, getOrderIdByOutTradeNo, rateLimit, saveOrder } from './lib/store';
import {
  closeOrder,
  decryptNotify,
  isPartnerMode,
  nativeOrder,
  notifyMerchantMatch,
  queryOrder,
  readNotifyHeaders,
  readWxConfig,
  rfc3339Cst,
  verifyNotify,
} from './lib/wxpay';

/**
 * 智创协同 AI 桌面端支付 Worker（微信支付服务商模式 Native 扫码）。
 *
 * 唯一持有微信支付商户密钥的一层；桌面客户端只跟本 Worker 通信（配置里只存
 * Worker 域名），不持有任何商户凭证。积分余额以本 Worker 的 KV 为准。
 *
 * 收款形态：服务商（sp_mchid）代子商户（sub_mchid）下单，钱进子商户账户。
 * 设 WXPAY_SP_MODE=merchant 可退回直连商户模式，见 README。
 *
 * 路由：
 *   GET  /health                  探活（返回模式 / 接入形态 / 凭证是否配齐）
 *   GET  /api/pay/tiers           三档套餐（含实时积分）
 *   POST /api/pay/create          {tier, mid} -> {orderId, codeUrl, expiresAt, mock}
 *   GET  /api/pay/status?orderId= 轮询支付结果（未支付时会向微信主动查单兜底）
 *   GET  /api/pay/credits?mid=    积分余额
 *   POST /api/pay/notify          微信支付回调（验签 + 解密 + 入账）
 *   POST /api/pay/mock/confirm    {orderId} 仅 PAY_MODE=mock：模拟支付成功
 */

const JSON_HEADERS = { 'Content-Type': 'application/json; charset=utf-8' };

/** 主动查单最小间隔：客户端 2s 轮询，这里按 5s 打微信，避免触发频率限制 */
const QUERY_MIN_INTERVAL_MS = 5_000;
/** 关单最短延迟：微信建议下单后至少 5 分钟再关单，否则状态未同步会关单失败 */
const CLOSE_MIN_DELAY_MS = 5 * 60_000;

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: JSON_HEADERS });
}

function bad(message: string, status = 400): Response {
  return json({ error: message }, status);
}

function payMode(env: Env): 'mock' | 'wechat' {
  return env.PAY_MODE === 'wechat' ? 'wechat' : 'mock';
}

function orderExpireMinutes(env: Env): number {
  const n = Number.parseInt(env.ORDER_EXPIRE_MINUTES ?? '', 10);
  return Number.isFinite(n) && n > 0 ? n : 30;
}

/** /health 用：告诉运维当前接入形态与还缺哪些 secret（不泄露值）。 */
function wxMissingSecrets(env: Env): string[] {
  const missing: string[] = [];
  if (!env.WXPAY_SERIAL?.trim()) missing.push('WXPAY_SERIAL');
  if (!env.WXPAY_PRIVATE_KEY?.trim()) missing.push('WXPAY_PRIVATE_KEY');
  if (!env.WXPAY_V3KEY?.trim()) missing.push('WXPAY_V3KEY');
  if (!env.WXPAY_NOTIFY_URL?.trim()) missing.push('WXPAY_NOTIFY_URL');
  if (isPartnerMode(env)) {
    if (!env.WXPAY_SP_MCHID?.trim()) missing.push('WXPAY_SP_MCHID');
    if (!env.WXPAY_SP_APPID?.trim()) missing.push('WXPAY_SP_APPID');
    if (!env.WXPAY_SUB_MCHID?.trim()) missing.push('WXPAY_SUB_MCHID');
  } else {
    if (!env.WXPAY_MCHID?.trim()) missing.push('WXPAY_MCHID');
    if (!env.WXPAY_APPID?.trim()) missing.push('WXPAY_APPID');
  }
  return missing;
}

function genId(): string {
  // out_trade_no 要求 ≤32 位且唯一；同值即订单 id，webhook 回查零歧义
  return `mu${Date.now().toString(36)}${crypto.randomUUID().replace(/-/g, '').slice(0, 12)}`;
}

/** 到期懒置 expired：轮询时才写，避免无谓 KV 操作。 */
async function refreshExpiry(env: Env, order: Order): Promise<Order> {
  if (order.status === 'created' && Date.now() > order.expireAt) {
    order.status = 'expired';
    await saveOrder(env, order);
  }
  return order;
}

/** 从 attach（下单时写入的 {mid,tierId}）取 mid；缺失用订单快照。 */
function midFromAttach(attach: string | undefined, fallback: string): string {
  if (!attach) return fallback;
  try {
    return (JSON.parse(attach) as { mid?: string }).mid || fallback;
  } catch {
    return fallback;
  }
}

/** 置为已付并入账，返回入账后的积分余额。只改对象，落盘交给调用方。 */
async function markPaid(env: Env, order: Order, mid: string, transactionId?: string): Promise<number> {
  order.status = 'paid';
  order.paidAt = Date.now();
  if (transactionId) order.wxTransactionId = transactionId;
  await saveOrder(env, order);
  return addCredits(env, mid, order.credits);
}

/**
 * 回调兜底：微信回调可能因为网络/域名问题丢失，客户端轮询时顺手向微信查一次真单。
 * 命中 SUCCESS 直接入账；命中终态（关闭/撤销/失败）把本地单置 closed。
 */
async function reconcileWithWxpay(env: Env, order: Order): Promise<void> {
  if (order.status !== 'created' || order.mock) return;
  const cfg = readWxConfig(env);
  if (!cfg) return;

  const now = Date.now();
  if (order.lastQueryAt && now - order.lastQueryAt < QUERY_MIN_INTERVAL_MS) return;
  order.lastQueryAt = now;

  try {
    const result = await queryOrder(env, cfg, order.outTradeNo);
    if (!result) return; // 微信侧还没有这笔单（下单请求可能失败过），保持 created
    if (result.trade_state === 'SUCCESS') {
      const paidCents = result.amount?.total ?? result.amount?.payer_total ?? 0;
      if (paidCents === order.amountCents) {
        await markPaid(env, order, midFromAttach(result.attach, order.mid), result.transaction_id);
        return;
      }
    } else if (['CLOSED', 'REVOKED', 'PAYERROR'].includes(result.trade_state)) {
      order.status = 'closed';
    }
  } catch {
    // 查单失败不影响本次轮询响应，下个周期重试
  }
  await saveOrder(env, order);
}

/** 本地过期后向微信关一次单，避免微信侧订单一直挂着可支付状态。 */
async function closeExpiredOrder(env: Env, order: Order): Promise<void> {
  if (order.status !== 'expired' || order.mock || order.closeTriedAt) return;
  if (Date.now() - order.createdAt < CLOSE_MIN_DELAY_MS) return;
  const cfg = readWxConfig(env);
  if (!cfg) return;
  order.closeTriedAt = Date.now();
  await saveOrder(env, order);
  try {
    await closeOrder(env, cfg, order.outTradeNo);
  } catch {
    // 关单失败不重试：最坏结果是微信侧自然过期
  }
}

async function handleCreate(env: Env, req: Request): Promise<Response> {
  let body: { tier?: string; mid?: string };
  try {
    body = (await req.json()) as { tier?: string; mid?: string };
  } catch {
    return bad('invalid json body');
  }
  const tierId = (body.tier ?? '').trim();
  const mid = (body.mid ?? '').trim();
  if (!tierId) return bad('missing tier');
  if (!mid || mid.length < 8 || mid.length > 64) return bad('invalid mid');

  const tier = findTier(env, tierId);
  if (!tier) return bad(`unknown tier: ${tierId}`, 404);

  if (!(await rateLimit(env, mid))) return bad('too many requests, retry later', 429);

  const mode = payMode(env);
  const now = Date.now();
  const order: Order = {
    id: genId(),
    outTradeNo: genId(),
    tierId: tier.id,
    tierName: tier.name,
    mid,
    amountCents: tier.priceCents,
    credits: tier.credits,
    status: 'created',
    codeUrl: null,
    mock: mode === 'mock',
    createdAt: now,
    expireAt: now + orderExpireMinutes(env) * 60_000,
  };

  if (mode === 'mock') {
    // 开发自测：二维码内容为占位串（扫不出东西），客户端配「模拟支付成功」按钮走 mock/confirm
    order.codeUrl = `mock://pay/${order.id}`;
  } else {
    const cfg = readWxConfig(env);
    if (!cfg) return bad('PAY_MODE=wechat but WXPAY_* not configured', 500);
    try {
      order.codeUrl = await nativeOrder(env, cfg, {
        outTradeNo: order.outTradeNo,
        description: `智创协同AI·${tier.name}`,
        amountCents: tier.priceCents,
        // 让微信侧的支付截止时间与本地 expireAt 一致，到点后用户扫旧码会被微信拒绝
        timeExpire: rfc3339Cst(order.expireAt),
        attach: JSON.stringify({ mid, tierId: tier.id }).slice(0, 128),
      });
    } catch (error) {
      return bad(`wxpay order failed: ${error instanceof Error ? error.message : String(error)}`, 502);
    }
  }

  await saveOrder(env, order);
  return json({
    orderId: order.id,
    codeUrl: order.codeUrl,
    expiresAt: order.expireAt,
    mock: order.mock,
    tier: { id: tier.id, name: tier.name, priceCents: tier.priceCents, credits: tier.credits },
  });
}

async function handleStatus(env: Env, url: URL): Promise<Response> {
  const orderId = url.searchParams.get('orderId') ?? '';
  const order = orderId ? await getOrder(env, orderId) : null;
  if (!order) return bad('order not found', 404);
  await refreshExpiry(env, order);
  // 真实收款模式下，回调若没打到本 Worker，这里主动查微信兜底
  if (payMode(env) === 'wechat') {
    await reconcileWithWxpay(env, order);
    await closeExpiredOrder(env, order);
  }
  return json({
    orderId: order.id,
    status: order.status,
    tierId: order.tierId,
    tierName: order.tierName,
    amountCents: order.amountCents,
    credits: order.status === 'paid' ? order.credits : undefined,
    paidAt: order.paidAt,
  });
}

async function handleCredits(env: Env, url: URL): Promise<Response> {
  const mid = url.searchParams.get('mid') ?? '';
  if (!mid) return bad('missing mid');
  return json({ mid, credits: await getCredits(env, mid) });
}

async function handleNotify(env: Env, req: Request): Promise<Response> {
  // 微信支付要求：成功 = 200 + {"code":"SUCCESS"}；失败 = 4xx/5xx（会按策略重推）
  const ok = (message = '成功') => json({ code: 'SUCCESS', message });
  const fail = (message: string, status = 400) => json({ code: 'FAIL', message }, status);

  const rawBody = await req.text();
  let payload: { resource?: { ciphertext: string; nonce: string; associated_data?: string }; id?: string; event_type?: string };
  try {
    payload = JSON.parse(rawBody) as typeof payload;
  } catch {
    return fail('invalid json body');
  }
  if (!payload.resource) return fail('missing resource');

  const cfg = readWxConfig(env);
  if (!cfg) return fail('wechat pay not configured', 500);

  const headers = readNotifyHeaders(req.headers);
  if (!headers) return fail('missing wechatpay-* headers');
  if (!(await verifyNotify(env, cfg, headers, rawBody))) return fail('signature verify failed', 401);

  let notify;
  try {
    notify = await decryptNotify(cfg, payload.resource);
  } catch {
    return fail('decrypt failed');
  }
  if (notify.trade_state !== 'SUCCESS') {
    // 非成功态（如关单）不处理，直接确认收悉
    return ok();
  }
  // 服务商模式校验 sp_mchid + sub_mchid；直连模式校验 mchid
  if (!notifyMerchantMatch(cfg, notify)) return fail('merchant mismatch');

  const orderId = await getOrderIdByOutTradeNo(env, notify.out_trade_no);
  if (!orderId) return fail('unknown out_trade_no', 404);
  const order = await getOrder(env, orderId);
  if (!order) return fail('order not found', 404);
  if (order.status === 'paid') return ok(); // 幂等：重复通知直接确认

  // 金额校验（分）：与下单快照不一致 = 异常单，拒绝入账
  const paidCents = notify.amount?.total ?? notify.amount?.payer_total ?? 0;
  if (paidCents !== order.amountCents) return fail('amount mismatch');

  // attach 里的 mid 优先（下单时写入），缺失退回订单快照
  await markPaid(env, order, midFromAttach(notify.attach, order.mid), notify.transaction_id);
  return ok();
}

async function handleMockConfirm(env: Env, req: Request): Promise<Response> {
  if (payMode(env) !== 'mock') return bad('mock confirm disabled in wechat mode', 403);
  let body: { orderId?: string };
  try {
    body = (await req.json()) as { orderId?: string };
  } catch {
    return bad('invalid json body');
  }
  const order = body.orderId ? await getOrder(env, body.orderId) : null;
  if (!order) return bad('order not found', 404);
  if (order.status === 'paid') return json({ orderId: order.id, status: 'paid', credits: order.credits });
  if (order.status !== 'created') return bad(`order is ${order.status}`);
  const balance = await markPaid(env, order, order.mid);
  return json({ orderId: order.id, status: 'paid', credits: order.credits, balance });
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    // 桌面客户端直连；放开 CORS 只是顺带，不承担浏览器鉴权
    const cors = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Methods': 'GET,POST,OPTIONS', 'Access-Control-Allow-Headers': 'Content-Type' };
    if (req.method === 'OPTIONS') return new Response(null, { status: 204, headers: cors });

    try {
      let resp: Response;
      if (url.pathname === '/health') {
        const partner = isPartnerMode(env);
        const missing = wxMissingSecrets(env);
        resp = json({
          ok: true,
          mode: payMode(env),
          wxpay: {
            access: partner ? 'partner' : 'merchant',
            partner,
            configured: missing.length === 0,
            missing,
          },
        });
      } else if (url.pathname === '/api/pay/tiers' && req.method === 'GET') {
        resp = json({ tiers: listTiers(env) });
      } else if (url.pathname === '/api/pay/create' && req.method === 'POST') {
        resp = await handleCreate(env, req);
      } else if (url.pathname === '/api/pay/status' && req.method === 'GET') {
        resp = await handleStatus(env, url);
      } else if (url.pathname === '/api/pay/credits' && req.method === 'GET') {
        resp = await handleCredits(env, url);
      } else if (url.pathname === '/api/pay/notify' && req.method === 'POST') {
        resp = await handleNotify(env, req);
      } else if (url.pathname === '/api/pay/mock/confirm' && req.method === 'POST') {
        resp = await handleMockConfirm(env, req);
      } else {
        resp = bad('not found', 404);
      }
      // 通知回调的响应头不带 CORS 也无妨；统一附加
      for (const [k, v] of Object.entries(cors)) resp.headers.set(k, v);
      return resp;
    } catch (error) {
      return json({ error: error instanceof Error ? error.message : String(error) }, 500);
    }
  },
};
