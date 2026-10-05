/**
 * /device/* 离线回归测试（不联网、不起 Worker，直接 import 模块 + mock KV）。
 * 重点验证：设备档案归属、套餐上云、**人格跨端互通（Windows ↔ Android 同一 wxKey）**。
 *
 * 跑法：node _device_worker_test.mjs
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const workerSrc = path.join(here, "weauto_license", "cf_worker.js");
const tmp = path.join(here, "_worker_under_test.mjs");
fs.writeFileSync(tmp, fs.readFileSync(workerSrc, "utf8"));
const mod = await import("file://" + tmp.replace(/\\/g, "/"));
const worker = mod.default;

let pass = 0, fail = 0;
function check(name, cond, extra = "") {
  if (cond) { pass++; console.log("  ok   " + name); }
  else { fail++; console.log("  FAIL " + name + (extra ? "  -> " + extra : "")); }
}

/* ---------------- mock ---------------- */
class KV {
  constructor() { this.m = new Map(); }
  async get(k) { return this.m.has(k) ? this.m.get(k) : null; }
  async put(k, v) { this.m.set(k, String(v)); }
}
const SECRET = "test_client_secret_0123456789";
const SIGN_SECRET = "test_sign_secret_0123456789";
function makeEnv(over = {}) {
  return Object.assign({
    BILLING_KV: new KV(),
    WEAUATO_CLIENT_SECRET: SECRET,
    WEAUATO_SIGN_SECRET: SIGN_SECRET,
  }, over);
}
async function hmacHex(secret, msg) {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const b = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(msg));
  return [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, "0")).join("");
}
function b64u(s) {
  const bytes = new TextEncoder().encode(String(s));
  let bin = ""; for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
/** 生成与 Worker 一致的签名头 */
async function signHeaders(method, urlPath, bodyStr, secret = SECRET) {
  const ts = String(Math.floor(Date.now() / 1000));
  const nonce = "n" + Math.random().toString(16).slice(2);
  const sig = await hmacHex(secret, [method, urlPath, ts, nonce, bodyStr].join("\n"));
  return { "X-WeAuto-Ts": ts, "X-WeAuto-Nonce": nonce, "X-WeAuto-Sig": sig };
}
/** 造一个 Android 端账户令牌（与 signBillingToken 同算法） */
async function makeToken(env, sub, tier) {
  const payload = { sub, tier, exp: Date.now() + 86400000 };
  const pb = b64u(JSON.stringify(payload));
  const sig = await hmacHex(env.WEAUATO_SIGN_SECRET, "weauto-billing-v1|" + pb);
  return pb + "." + sig;
}
async function call(env, method, urlPath, bodyObj, headers = {}) {
  const bodyStr = bodyObj == null ? "" : JSON.stringify(bodyObj);
  const req = new Request("https://wetech.jukuai.net" + urlPath, {
    method,
    headers: Object.assign({ "Content-Type": "application/json" }, headers),
    body: method === "GET" ? undefined : bodyStr,
  });
  const res = await worker.fetch(req, env, {});
  let data = null;
  try { data = await res.json(); } catch (_) { data = null; }
  return { status: res.status, data };
}
async function signed(env, method, urlPath, bodyObj, extraHeaders = {}) {
  const bodyStr = bodyObj == null ? "" : JSON.stringify(bodyObj);
  // Worker 侧只签 pathname（不含 query），客户端必须一致
  const h = Object.assign(await signHeaders(method, urlPath.split("?")[0], bodyStr, SECRET), extraHeaders);
  return call(env, method, urlPath, bodyObj, h);
}

const DEV_WIN = "dev_1111111111111111111111111111aa";
const DEV_ANDROID = "a1b2c3d4e5f60718"; // Android ANDROID_ID 形态（16 hex）
const WXKEY = "wx_" + "ab".repeat(16);

const PERSONA = {
  identity: { nameCN: "张三", title: "品牌总监", company: "某酒业", city: "成都", about: "说话直接" },
  settings: { enabled: true, personaPrompt: "别用客服腔", chatPurpose: "商务对接" },
  styleText: "短句为主，爱用句号。",
};

console.log("=== /device/* 设备档案 / 套餐 / 人格云同步 ===");

/* 1. 凭证 */
{
  const env = makeEnv();
  const r = await call(env, "POST", "/device/hello", { deviceId: DEV_WIN });
  check("无凭证 -> 401", r.status === 401, "status=" + r.status);
}
{
  const env = makeEnv();
  const r = await signed(env, "POST", "/device/hello", { deviceId: DEV_WIN, platform: "windows-exe" });
  check("HMAC 签名 -> 200", r.status === 200 && r.data.ok === true, JSON.stringify(r.data));
  check("归属键回显 deviceId", r.data.deviceId === DEV_WIN);
}

/* 2. 归属键优先级：wxKey > deviceId */
{
  const env = makeEnv();
  await signed(env, "POST", "/device/hello", { deviceId: DEV_WIN, wxKey: WXKEY, platform: "windows-exe" });
  const raw = await env.BILLING_KV.get("prof:wx:" + WXKEY);
  check("有 wxKey 时按微信身份归档", raw !== null);
  check("不另建设备档案", (await env.BILLING_KV.get("prof:dev:" + DEV_WIN)) === null);
}
{
  const env = makeEnv();
  await signed(env, "POST", "/device/hello", { deviceId: DEV_WIN });
  check("无 wxKey 时退化为设备归档", (await env.BILLING_KV.get("prof:dev:" + DEV_WIN)) !== null);
}
{
  const env = makeEnv();
  const r = await signed(env, "POST", "/device/hello", { deviceId: "x", wxKey: "not_a_wxkey" });
  check("非法身份 -> 400", r.status === 400, "status=" + r.status);
}

/* 3. 人格上传 + 跨端互通（核心） */
{
  const env = makeEnv();
  await signed(env, "POST", "/device/hello", { deviceId: DEV_WIN, wxKey: WXKEY, platform: "windows-exe" });
  const up = await signed(env, "PUT", "/device/persona",
    { deviceId: DEV_WIN, wxKey: WXKEY, platform: "windows-exe", persona: PERSONA, rev: 1700000000000 });
  check("Windows 上传人格 -> 200", up.status === 200 && up.data.ok === true, JSON.stringify(up.data));
  check("人格版本号已写入", up.data.personaRev === 1700000000000);

  // 换一台设备（Android，带 Android 的 Instance 头）用同一个 wxKey 读
  const get = await signed(env, "GET", "/device/persona?wx=" + WXKEY + "&device=" + DEV_ANDROID,
    null, { "X-WeAuto-Instance": DEV_ANDROID });
  check("Android 同 wxKey 读到同一份人格（跨端互通）",
    get.status === 200 && get.data.persona && get.data.persona.identity.nameCN === "张三",
    JSON.stringify(get.data));
  check("人格来源标记为 windows-exe", get.data.persona.sourcePlatform === "windows-exe");
}

/* 4. rev 保护：旧版本不能覆盖新版本 */
{
  const env = makeEnv();
  await signed(env, "PUT", "/device/persona",
    { deviceId: DEV_WIN, wxKey: WXKEY, persona: PERSONA, rev: 1700000000000, platform: "windows-exe" });
  const stale = await signed(env, "PUT", "/device/persona",
    { deviceId: DEV_ANDROID, wxKey: WXKEY, persona: PERSONA, rev: 1600000000000, platform: "android-apk" });
  check("旧 rev 上传 -> 409 stale_rev", stale.status === 409, "status=" + stale.status);
  const fresh = await signed(env, "PUT", "/device/persona",
    { deviceId: DEV_ANDROID, wxKey: WXKEY, persona: PERSONA, rev: 1800000000000, platform: "android-apk" });
  check("新 rev 上传 -> 覆盖成功", fresh.status === 200 && fresh.data.personaRev === 1800000000000);
  const back = await signed(env, "GET", "/device/persona?wx=" + WXKEY, null);
  check("覆盖后来源变为 android-apk", back.data.persona.sourcePlatform === "android-apk");
}

/* 5. 多设备登记 */
{
  const env = makeEnv();
  await signed(env, "POST", "/device/hello", { deviceId: DEV_WIN, wxKey: WXKEY, platform: "windows-exe" });
  await signed(env, "POST", "/device/hello", { deviceId: DEV_ANDROID, wxKey: WXKEY, platform: "android-apk" });
  const st = await signed(env, "GET", "/device/state?wx=" + WXKEY, null);
  check("同一身份登记了 2 台设备", (st.data.devices || []).length === 2, JSON.stringify(st.data.devices));
  check("设备平台都记下来了",
    st.data.devices.map((d) => d.platform).sort().join(",") === "android-apk,windows-exe");
}

/* 6. 套餐：账户令牌（Android 路径） */
{
  const env = makeEnv();
  const tok = await makeToken(env, "u_android_1", "pro");
  const r = await call(env, "POST", "/device/hello",
    { deviceId: DEV_ANDROID, wxKey: WXKEY, platform: "android-apk" },
    { Authorization: "Bearer " + tok, "X-WeAuto-Instance": DEV_ANDROID });
  check("Bearer 账户令牌 -> 200", r.status === 200 && r.data.ok === true, JSON.stringify(r.data));
  check("套餐档位写入 pro", r.data.tier === "pro", "tier=" + r.data.tier);
  check("套餐展示信息完整", r.data.plan && r.data.plan.name === "旗舰版", JSON.stringify(r.data.plan));
  const st = await call(env, "GET", "/device/state?wx=" + WXKEY, null, { Authorization: "Bearer " + tok });
  check("重装后从云端读回套餐（卸载不丢）", st.data.tier === "pro");
}
{
  const env = makeEnv();
  const bad = await call(env, "GET", "/device/state?wx=" + WXKEY, null, { Authorization: "Bearer garbage.token" });
  check("无效令牌 -> 401", bad.status === 401, "status=" + bad.status);
}

/* 7. 公开模式（未配任何 secret） */
{
  const env = { BILLING_KV: new KV() };
  const r = await call(env, "POST", "/device/hello", { deviceId: DEV_WIN, wxKey: WXKEY });
  check("未配 secret -> 公开模式放行", r.status === 200 && r.data.auth_kind === "open", JSON.stringify(r.data));
}

/* 8. person a 字段净化与限长 */
{
  const env = makeEnv();
  const big = {
    identity: { nameCN: "李四", evil: "x".repeat(9999) },
    settings: { enabled: true, personaPrompt: "p".repeat(9000) },
    styleText: "s".repeat(9000),
  };
  await signed(env, "PUT", "/device/persona",
    { deviceId: DEV_WIN, wxKey: WXKEY, persona: big, rev: 1700000000000, platform: "windows-exe" });
  const g = await signed(env, "GET", "/device/persona?wx=" + WXKEY, null);
  check("非白名单字段被丢弃", !("evil" in g.data.persona.identity));
  check("personaPrompt 限长 4000", g.data.persona.settings.personaPrompt.length === 4000);
  check("styleText 限长 4000", g.data.persona.styleText.length === 4000);
  const bad = await signed(env, "PUT", "/device/persona",
    { deviceId: DEV_WIN, wxKey: WXKEY, persona: null, rev: 1 });
  check("persona 非法 -> 400", bad.status === 400);
}

/* 9. /device/plan 需要 Creem 配置 */
{
  const env = makeEnv();
  const r = await signed(env, "PUT", "/device/plan", { deviceId: DEV_WIN, wxKey: WXKEY, licenseKey: "k", licenseInstanceId: "i" });
  check("未配 CREEM_API_KEY -> 503", r.status === 503, "status=" + r.status);
  const noLic = await signed(env, "PUT", "/device/plan", { deviceId: DEV_WIN, wxKey: WXKEY });
  check("缺卡密 -> 400", noLic.status === 400, "status=" + noLic.status);
}

/* 10. KV 缺失时优雅降级 */
{
  const env = makeEnv({ BILLING_KV: undefined });
  const r = await signed(env, "POST", "/device/hello", { deviceId: DEV_WIN, wxKey: WXKEY });
  check("无 KV -> 503 而非崩溃", r.status === 503, "status=" + r.status);
}

/* 11. hello 心跳不破坏已有的人格 */
{
  const env = makeEnv();
  await signed(env, "PUT", "/device/persona",
    { deviceId: DEV_WIN, wxKey: WXKEY, persona: PERSONA, rev: 1700000000000, platform: "windows-exe" });
  await signed(env, "POST", "/device/hello", { deviceId: DEV_WIN, wxKey: WXKEY });
  const g = await signed(env, "GET", "/device/persona?wx=" + WXKEY, null);
  check("心跳后人格仍在", g.data.persona && g.data.persona.identity.nameCN === "张三");
  check("心跳后 rev 不变", g.data.personaRev === 1700000000000);
}

/* 12. 健康检查暴露新字段 */
{
  const env = makeEnv();
  const r = await call(env, "GET", "/health", null);
  check("/health 暴露 device_sync_ready", r.data.device_sync_ready === true);
  check("/health 暴露 device_auth_mode=strict", r.data.device_auth_mode === "strict", r.data.device_auth_mode);
}

try { fs.unlinkSync(tmp); } catch (_) {}
console.log(`\n结果： ${pass} 通过 / ${fail} 失败`);
process.exit(fail ? 1 : 0);
