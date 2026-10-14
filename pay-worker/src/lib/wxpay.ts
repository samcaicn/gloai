import type { Env } from '../types';
import { cacheCert, getCachedCert } from './store';

/**
 * 微信支付 APIv3 客户端（Worker 内实现）。
 *
 * 复用自已上线项目（柜递 cabinet-cf）的实测代码：同一套 RSA-SHA256 签名与
 * AES-256-GCM 回解密，差异只有两点：
 *   1. 下单端点用 /v3/pay/transactions/native（返回 code_url 供桌面端画二维码），
 *      而不是 JSAPI（那个要 openid，只适用于微信内/H5/小程序）；
 *   2. 通知验签改用「微信支付平台证书」（RSA 公钥），证书经 /v3/certificates
 *      下载后 AES-256-GCM 解密，按 serial_no 缓存进 KV。
 */

const WXPAY_BASE = 'https://api.mch.weixin.qq.com';

interface WxPayConfig {
  mchid: string;
  appid: string;
  serial: string;
  privateKeyPem: string;
  v3key: string;
  notifyUrl: string;
}

export function readWxConfig(env: Env): WxPayConfig | null {
  const mchid = env.WXPAY_MCHID?.trim();
  const appid = env.WXPAY_APPID?.trim();
  const serial = env.WXPAY_SERIAL?.trim();
  const privateKeyPem = env.WXPAY_PRIVATE_KEY?.trim();
  const v3key = env.WXPAY_V3KEY?.trim();
  const notifyUrl = env.WXPAY_NOTIFY_URL?.trim();
  if (!mchid || !appid || !serial || !privateKeyPem || !v3key || !notifyUrl) return null;
  return { mchid, appid, serial, privateKeyPem, v3key, notifyUrl };
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

/** 构造 WECHATPAY2-SHA256-RSA2048  Authorization 头。 */
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

/** 下载（带 KV 缓存）微信支付平台证书，返回 serial -> PEM。 */
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
  /** attach 原样带回通知里，放 {mid,tierId} JSON（≤128 字节） */
  attach: string;
}

/** Native 下单，返回 code_url（桌面端画成二维码，用户用微信扫）。 */
export async function nativeOrder(env: Env, cfg: WxPayConfig, input: NativeOrderInput): Promise<string> {
  const path = '/v3/pay/transactions/native';
  const body = JSON.stringify({
    mchid: cfg.mchid,
    appid: cfg.appid,
    description: input.description,
    out_trade_no: input.outTradeNo,
    notify_url: cfg.notifyUrl,
    amount: { total: input.amountCents, currency: 'CNY' },
    attach: input.attach,
  });
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
  mchid?: string;
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
