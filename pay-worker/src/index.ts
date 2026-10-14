import type { Env, Order } from './types';
import { findTier, listTiers } from './lib/tiers';
import { addCredits, getCredits, getOrder, getOrderIdByOutTradeNo, rateLimit, saveOrder } from './lib/store';
import { decryptNotify, nativeOrder, readNotifyHeaders, readWxConfig, verifyNotify } from './lib/wxpay';

/**
 * 智创协同 AI 桌面端支付 Worker。
 *
 * 唯一持有微信支付商户密钥的一层；桌面客户端只跟本 Worker 通信（配置里只存
 * Worker 域名），不持有任何商户凭证。积分余额以本 Worker 的 KV 为准。
 *
 * 路由：
 *   GET  /health                  探活
 *   GET  /api/pay/tiers           三档套餐（含实时积分）
 *   POST /api/pay/create          {tier, mid} -> {orderId, codeUrl, expiresAt, mock}
 *   GET  /api/pay/status?orderId= 轮询支付结果
 *   GET  /api/pay/credits?mid=    积分余额
 *   POST /api/pay/notify          微信支付回调（验签 + 解密 + 入账）
 *   POST /api/pay/mock/confirm    {orderId} 仅 PAY_MODE=mock：模拟支付成功
 */

const JSON_HEADERS = { 'Content-Type': 'application/json; charset=utf-8' };

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
  if (notify.mchid && notify.mchid !== cfg.mchid) return fail('mchid mismatch');

  const orderId = await getOrderIdByOutTradeNo(env, notify.out_trade_no);
  if (!orderId) return fail('unknown out_trade_no', 404);
  const order = await getOrder(env, orderId);
  if (!order) return fail('order not found', 404);
  if (order.status === 'paid') return ok(); // 幂等：重复通知直接确认

  // 金额校验（分）：与下单快照不一致 = 异常单，拒绝入账
  const paidCents = notify.amount?.total ?? notify.amount?.payer_total ?? 0;
  if (paidCents !== order.amountCents) return fail('amount mismatch');

  // attach 里的 mid 优先（下单时写入），缺失退回订单快照
  let mid = order.mid;
  try {
    const attach = notify.attach ? (JSON.parse(notify.attach) as { mid?: string }) : {};
    if (attach.mid) mid = attach.mid;
  } catch {
    /* attach 损坏则用订单快照的 mid */
  }

  order.status = 'paid';
  order.paidAt = Date.now();
  order.wxTransactionId = notify.transaction_id;
  await saveOrder(env, order);
  await addCredits(env, mid, order.credits);
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
  order.status = 'paid';
  order.paidAt = Date.now();
  await saveOrder(env, order);
  const balance = await addCredits(env, order.mid, order.credits);
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
        resp = json({ ok: true, mode: payMode(env) });
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
