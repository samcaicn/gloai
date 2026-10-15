import type { Env } from '../types';
import { cacheCert, getCachedCert } from './store';

/**
 * 微信支付 APIv3 客户端（Worker 内实现），同时支持两种接入模式：
 *
 *   partner（服务商模式，默认，本次接入形态）：
 *     下单  POST /v3/pay/partner/transactions/native    body 用 sp_appid/sp_mchid/sub_mchid/sub_appid
 *     查单  GET  /v3/pay/partner/transactions/out-trade-no/{no}?sp_mchid=&sub_mchid=
 *     关单  POST /v3/pay/partner/transactions/out-trade-no/{no}/close  body {sp_mchid,sub_mchid}
 *     签名与证书一律用「服务商商户」的凭证（mchid 字段 = sp_mchid，serial_no = 服务商 API 证书序列号），
 *     钱进子商户（sub_mchid）账户。
 *
 *   merchant（普通直连商户）：走 /v3/pay/* 那一套，保留兼容。
 *
 * 两种模式共用：RSA-SHA256 请求签名、平台证书验签（/v3/certificates 下载 + AES-256-GCM 解密 + KV 缓存）、
 * 通知 AES-256-GCM 解密。
 */

const WXPAY_BASE = 'https://api.mch.weixin.qq.com';

export interface WxPayConfig {
  /** true = 服务商模式（partner）；false = 直连商户 */
  partner: boolean;
  /** 签名 Authorization 里的 mchid：partner 下 = sp_mchid，直连下 = mchid */
  mchid: string;
  /** 下单用的 appid：partner 下 = sp_appid，直连下 = appid */
  appid: string;
  spMchid?: string;
  spAppid?: string;
  subMchid?: string;
  subAppid?: string;
  serial: string;
  privateKeyPem: string;
  v3key: string;
  notifyUrl: string;
}

export function isPartnerMode(env: Env): boolean {
  return (env.WXPAY_SP_MODE ?? 'partner').trim().toLowerCase() !== 'merchant';
}

export function readWxConfig(env: Env): WxPayConfig | null {
  const serial = env.WXPAY_SERIAL?.trim();
  const privateKeyPem = env.WXPAY_PRIVATE_KEY?.trim();
  const v3key = env.WXPAY_V3KEY?.trim();
  const notifyUrl = env.WXPAY_NOTIFY_URL?.trim();
  if (!serial || !privateKeyPem || !v3key || !notifyUrl) return null;

  if (isPartnerMode(env)) {
    const spMchid = env.WXPAY_SP_MCHID?.trim();
    const spAppid = env.WXPAY_SP_APPID?.trim();
    const subMchid = env.WXPAY_SUB_MCHID?.trim();
    const subAppid = env.WXPAY_SUB_APPID?.trim();
    if (!spMchid || !spAppid || !subMchid) return null;
    return {
      partner: true,
      mchid: spMchid,
      appid: spAppid,
      spMchid,
      spAppid,
      subMchid,
      subAppid: subAppid || undefined,
      serial,
      privateKeyPem,
      v3key,
      notifyUrl,
    };
  }

  const mchid = env.WXPAY_MCHID?.trim();
  const appid = env.WXPAY_APPID?.trim();
  if (!mchid || !appid) return null;
  return { partner: false, mchid, appid, serial, privateKeyPem, v3key, notifyUrl };
}

// ---------------- base64 / PEM ----------------

function b64stdEncode(bytes: Uint8Array): string {
  let s = '';
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s);
}
function b64stdDecode(s: string): Uint8Array {
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}
function pemToBytes(pem: string): Uint8Array {
  const b64 = pem.replace(/-----(BEGIN|END)[^-]+-----/g, '').replace(/\s+/g, '');
  return b64stdDecode(b64);
}

/** RFC3339 带时区（东八区），用于 time_expire。 */
export function rfc3339Cst(ms: number): string {
  const d = new Date(ms + 8 * 3600_000);
  const p = (n: number) => String(n).padStart(2, '0');
  return (
    `${d.getUTCFullYear()}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())}` +
    `T${p(d.getUTCHours())}:${p(d.getUTCMinutes())}:${p(d.getUTCSeconds())}+08:00`
  );
}

// ---------------- 签名 ----------------

async function rsaSign(privateKeyPem: string, message: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    'pkcs8',
    pemToBytes(privateKeyPem),
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
    false,
    ['sign'],
  );
  const sig = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(message));
  return b64stdEncode(new Uint8Array(sig));
}

async function rsaVerify(publicKeyPem: string, message: string, signatureB64: string): Promise<boolean> {
  try {
    const key = await crypto.subtle.importKey(
      'spki',
      pemToBytes(publicKeyPem),
      { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
      false,
      ['verify'],
    );
    return crypto.subtle.verify(
      'RSASSA-PKCS1-v1_5',
      key,
      b64stdDecode(signatureB64),
      new TextEncoder().encode(message),
    );
  } catch {
    return false;
  }
}

/**
 * 构造 WECHATPAY2-SHA256-RSA2048 Authorization 头。
 * 注意：签名的 path 不含 query string（查单的 ?sp_mchid=... 不参与签名）。
 */
async function authHeader(cfg: WxPayConfig, method: string, path: string, body = ''): Promise<string> {
  const ts = Math.floor(Date.now() / 1000).toString();
  const nonce = crypto.randomUUID().replace(/-/g, '');
  const signMsg = `${method}\n${path}\n${ts}\n${nonce}\n${body}\n`;
  const signature = await rsaSign(cfg.privateKeyPem, signMsg);
  return `WECHATPAY2-SHA256-RSA2048 mchid="${cfg.mchid}",nonce_str="${nonce}",signature="${signature}",timestamp="${ts}",serial_no="${cfg.serial}"`;
}

// ---------------- 平台证书 ----------------

interface CertItem {
  serial_no: string;
  effective_time: string;
  expire_time: string;
  encrypt_certificate: { algorithm: string; nonce: string; associated_data: string; ciphertext: string };
}

async function aesGcmDecrypt(v3key: string, nonceB64: string, aad: string, ciphertextB64: string): Promise<Uint8Array> {
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(v3key), { name: 'AES-GCM' }, false, ['decrypt']);
  const iv = b64stdDecode(nonceB64);
  const aadBytes = new TextEncoder().encode(aad);
  const ct = b64stdDecode(ciphertextB64);
  return new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv, additionalData: aadBytes }, key, ct));
}

/** 下载（带 KV 缓存）微信支付平台证书，返回 serial -> PEM。服务商身份下载，两种模式通用。 */
async function platformCerts(env: Env, cfg: WxPayConfig): Promise<Map<string, string>> {
  const path = '/v3/certificates';
  const auth = await authHeader(cfg, 'GET', path);
  const resp = await fetch(`${WXPAY_BASE}${path}`, {
    headers: { Authorization: auth, Accept: 'application/json' },
  });
  if (!resp.ok) throw new Error(`wxpay get certificates failed: ${resp.status} ${await resp.text()}`);
  const data = (await resp.json()) as { data: CertItem[] };
  const out = new Map<string, string>();
  for (const item of data.data ?? []) {
    const pem = new TextDecoder().decode(
      await aesGcmDecrypt(cfg.v3key, item.encrypt_certificate.nonce, item.encrypt_certificate.associated_data, item.encrypt_certificate.ciphertext),
    );
    out.set(item.serial_no, pem);
    await cacheCert(env, item.serial_no, pem, new Date(item.expire_time).getTime());
  }
  return out;
}

/** 取验签用公钥：先查 KV 缓存，未命中下载全量。 */
async function certBySerial(env: Env, cfg: WxPayConfig, serial: string): Promise<string | null> {
  const cached = await getCachedCert(env, serial);
  if (cached) return cached;
  const certs = await platformCerts(env, cfg);
  return certs.get(serial) ?? null;
}

// ---------------- Native 下单 ----------------

export interface NativeOrderInput {
  outTradeNo: string;
  description: string;
  amountCents: number;
  /** RFC3339 支付截止时间；不传则由微信默认（2 小时） */
  timeExpire?: string;
  /** attach 原样带回通知里，放 {mid,tierId} JSON（≤128 字节） */
  attach: string;
}

/** Native 下单，返回 code_url（桌面端画成二维码，用户用微信扫）。 */
export async function nativeOrder(env: Env, cfg: WxPayConfig, input: NativeOrderInput): Promise<string> {
  const amount = { total: input.amountCents, currency: 'CNY' };
  const path = cfg.partner ? '/v3/pay/partner/transactions/native' : '/v3/pay/transactions/native';
  const body = JSON.stringify(
    cfg.partner
      ? {
          sp_appid: cfg.spAppid,
          sp_mchid: cfg.spMchid,
          sub_mchid: cfg.subMchid,
          ...(cfg.subAppid ? { sub_appid: cfg.subAppid } : {}),
          description: input.description,
          out_trade_no: input.outTradeNo,
          ...(input.timeExpire ? { time_expire: input.timeExpire } : {}),
          attach: input.attach,
          notify_url: cfg.notifyUrl,
          amount,
        }
      : {
          appid: cfg.appid,
          mchid: cfg.mchid,
          description: input.description,
          out_trade_no: input.outTradeNo,
          ...(input.timeExpire ? { time_expire: input.timeExpire } : {}),
          attach: input.attach,
          notify_url: cfg.notifyUrl,
          amount,
        },
  );
  const auth = await authHeader(cfg, 'POST', path, body);
  const resp = await fetch(`${WXPAY_BASE}${path}`, {
    method: 'POST',
    headers: { Authorization: auth, 'Content-Type': 'application/json', Accept: 'application/json' },
    body,
  });
  if (!resp.ok) throw new Error(`wxpay native order failed: ${resp.status} ${await resp.text()}`);
  const data = (await resp.json()) as { code_url?: string };
  if (!data.code_url) throw new Error('wxpay native order: empty code_url');
  return data.code_url;
}

// ---------------- 查单 / 关单（回调丢失时的兜底） ----------------

export interface QueryResult {
  out_trade_no: string;
  transaction_id?: string;
  trade_state: string;
  trade_state_desc?: string;
  attach?: string;
  success_time?: string;
  amount?: { total?: number; payer_total?: number; currency?: string };
  sp_mchid?: string;
  sub_mchid?: string;
}

/** 按商户订单号查单。订单不存在返回 null；网络/签名异常抛错。 */
export async function queryOrder(env: Env, cfg: WxPayConfig, outTradeNo: string): Promise<QueryResult | null> {
  const prefix = cfg.partner ? '/v3/pay/partner/transactions/out-trade-no/' : '/v3/pay/transactions/out-trade-no/';
  const path = `${prefix}${encodeURIComponent(outTradeNo)}`;
  const query = cfg.partner
    ? `?sp_mchid=${encodeURIComponent(cfg.spMchid ?? '')}&sub_mchid=${encodeURIComponent(cfg.subMchid ?? '')}`
    : `?mchid=${encodeURIComponent(cfg.mchid)}`;
  const auth = await authHeader(cfg, 'GET', path); // 签名只算 path，不含 query
  const resp = await fetch(`${WXPAY_BASE}${path}${query}`, {
    headers: { Authorization: auth, Accept: 'application/json' },
  });
  if (resp.status === 404) return null;
  if (!resp.ok) throw new Error(`wxpay query order failed: ${resp.status} ${await resp.text()}`);
  return (await resp.json()) as QueryResult;
}

/** 关单。成功（含已关闭）返回 true，订单不存在返回 false。 */
export async function closeOrder(env: Env, cfg: WxPayConfig, outTradeNo: string): Promise<boolean> {
  const prefix = cfg.partner ? '/v3/pay/partner/transactions/out-trade-no/' : '/v3/pay/transactions/out-trade-no/';
  const path = `${prefix}${encodeURIComponent(outTradeNo)}/close`;
  const body = JSON.stringify(cfg.partner ? { sp_mchid: cfg.spMchid, sub_mchid: cfg.subMchid } : { mchid: cfg.mchid });
  const auth = await authHeader(cfg, 'POST', path, body);
  const resp = await fetch(`${WXPAY_BASE}${path}`, {
    method: 'POST',
    headers: { Authorization: auth, 'Content-Type': 'application/json', Accept: 'application/json' },
    body,
  });
  if (resp.status === 404) return false;
  return resp.status === 204 || resp.ok;
}

// ---------------- 通知验签 + 解密 ----------------

export interface NotifyHeaders {
  signature: string;
  serial: string;
  timestamp: string;
  nonce: string;
}

export function readNotifyHeaders(headers: Headers): NotifyHeaders | null {
  const signature = headers.get('wechatpay-signature');
  const serial = headers.get('wechatpay-serial');
  const timestamp = headers.get('wechatpay-timestamp');
  const nonce = headers.get('wechatpay-nonce');
  if (!signature || !serial || !timestamp || !nonce) return null;
  return { signature, serial, timestamp, nonce };
}

/** 验签 + 时效（5 分钟）双重校验，防重放。 */
export async function verifyNotify(env: Env, cfg: WxPayConfig, h: NotifyHeaders, rawBody: string): Promise<boolean> {
  const nowSec = Math.floor(Date.now() / 1000);
  const ts = Number.parseInt(h.timestamp, 10);
  if (!Number.isFinite(ts) || Math.abs(nowSec - ts) > 300) return false;
  const cert = await certBySerial(env, cfg, h.serial);
  if (!cert) return false;
  return rsaVerify(cert, `${h.timestamp}\n${h.nonce}\n${rawBody}\n`, h.signature);
}

export interface DecryptedNotify {
  out_trade_no: string;
  transaction_id: string;
  trade_state: string;
  /** 服务商模式下为服务商商户号 */
  mchid?: string;
  sp_mchid?: string;
  sub_mchid?: string;
  sp_appid?: string;
  sub_appid?: string;
  trade_type?: string;
  bank_type?: string;
  success_time?: string;
  amount?: { total?: number; payer_total?: number };
  attach?: string;
}

/** 解密通知体（AES-256-GCM，APIv3 key）。 */
export async function decryptNotify(
  cfg: WxPayConfig,
  resource: { ciphertext: string; nonce: string; associated_data?: string },
): Promise<DecryptedNotify> {
  const plain = await aesGcmDecrypt(cfg.v3key, resource.nonce, resource.associated_data ?? '', resource.ciphertext);
  return JSON.parse(new TextDecoder().decode(plain)) as DecryptedNotify;
}

/**
 * 通知里的商户号是否与配置一致（防「别人的回调打进来」）。
 * 服务商模式校验 sp_mchid + sub_mchid，直连模式校验 mchid；通知缺失该字段时放行。
 */
export function notifyMerchantMatch(cfg: WxPayConfig, n: DecryptedNotify): boolean {
  if (cfg.partner) {
    if (n.sp_mchid && cfg.spMchid && n.sp_mchid !== cfg.spMchid) return false;
    if (n.sub_mchid && cfg.subMchid && n.sub_mchid !== cfg.subMchid) return false;
    return true;
  }
  return !n.mchid || n.mchid === cfg.mchid;
}
