/**
 * 离线验证 worker_fix/cf_worker.js 的双凭证闸门（integrityGate/exeCredentialCheck）。
 * 背景：2026-10-13 线上合入 Android L3 完整性墙后，/ai/*（含 /ai/jev/decisions
 * 与 /ai/v1 代理）对 Windows EXE 全量 403 integrity_signature。本测试锁定修复行为：
 *   - Android 五头客户端：行为零变化（该过过、该拦拦）
 *   - EXE 三种自有凭证（HMAC 签名 / 卡密+实例 / 计费令牌）：任一合法即放行
 *   - 无凭证/假凭证：仍落回 Android 墙 403
 * 做法：源码复制成 .mjs，预填卡密缓存，mock env + fetch，直接 import 内部函数 +
 *       走 worker.default.fetch 端到端。Node 22 自带 Request/Response/crypto.subtle。
 * 注意：Android X-Ts 是毫秒（System.currentTimeMillis，见 Integrity.kt），
 *       EXE x-weauto-ts 是秒（jev_guard.py time.time()），两边单位不同。
 */
import fs from "node:fs";
import crypto from "node:crypto";

const SRC = "worker_fix/cf_worker.js";
const TMP = "_worker_gate_test_mod.mjs";

let src = fs.readFileSync(SRC, "utf8");
src = src.replace(
  "var licenseCache = /* @__PURE__ */ new Map();",
  'var licenseCache = /* @__PURE__ */ new Map([["TESTKEY|INST01", Date.now()]]);'
);
src +=
  "\nexport { integrityGate, exeCredentialCheck, verifyClientIntegrity, signBillingToken, cf_worker_default as worker };\n";
fs.writeFileSync(TMP, src);
const M = await import("./" + TMP);

let pass = 0;
let fail = 0;
const ok = (name, cond, extra = "") => {
  if (cond) {
    pass++;
    console.log("PASS  " + name);
  } else {
    fail++;
    console.log("FAIL  " + name + (extra ? "  -> " + extra : ""));
  }
};

/* ---------- mock 环境 ---------- */
const CLIENT_SECRET = "test-client-secret";
const SIG_SECRET = "test-sign-secret";
const FP = "ab".repeat(32);
const HMAC_KEY = "test-integrity-hmac";

// Creem 在线校验 mock：仅 TESTKEY 有效（其余一律拒，模拟真实 Creem）
globalThis.fetch = async (url, init) => {
  const u = String(url);
  if (u.includes("api.creem.io")) {
    let key = "";
    try {
      key = (JSON.parse((init && init.body) || "{}") || {}).key || "";
    } catch (_) {}
    if (key === "TESTKEY") {
      return new Response(JSON.stringify({ status: "active" }), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    }
    return new Response(JSON.stringify({ message: "invalid_key" }), {
      status: 401,
      headers: { "content-type": "application/json" },
    });
  }
  return new Response("not found", { status: 404 });
};

function baseEnv(over = {}) {
  return {
    AI: {
      run: async () => ({ literal_question: true, true_intent: "casual_chat", danger_level: 1 }),
    },
    CREEM_API_KEY: "creem_test_key",
    CREEM_MODE: "test",
    WEAUATO_CLIENT_SECRET: CLIENT_SECRET,
    WEAUATO_SIGN_SECRET: SIG_SECRET,
    EXPECTED_SIG_FP: FP,
    INTEGRITY_HMAC_KEY: HMAC_KEY,
    LICENSE_KV: { get: async () => null, put: async () => {} },
    BILLING_KV: { get: async () => null, put: async () => {} },
    ...over,
  };
}

const hmacHex = (secret, msg) =>
  crypto.createHmac("sha256", secret).update(msg, "utf8").digest("hex");

function exeHeaders(path, body, ts, nonce) {
  const msg = "POST\n" + path + "\n" + ts + "\n" + nonce + "\n" + body;
  return {
    "x-weauto-ts": String(ts),
    "x-weauto-nonce": nonce,
    "x-weauto-sig": hmacHex(CLIENT_SECRET, msg),
  };
}

function androidHeaders(path, tsMs, fp = FP, pkg = "com.jev.probe", instance = "inst-1") {
  const msg = "POST\n" + path + "\n" + tsMs + "\n" + fp + "\n" + pkg + "\n" + instance;
  return {
    "x-sig-fp": fp,
    "x-pkg": pkg,
    "x-instance": instance,
    "x-ts": String(tsMs),
    "x-hmac": hmacHex(HMAC_KEY, msg),
  };
}

const nowSec = () => Math.floor(Date.now() / 1e3);
const nowMs = () => Date.now();
const JEV_BODY = JSON.stringify({
  state: { chat: { relationship: "test", messages: [{ from: "her", text: "在吗" }], latest_from: "her" } },
  questions: { literal_question: { type: "noul", instructions: "l?", criteria: { true: "y", false: "n" } } },
});
const errCode = async (r) => (r ? ((await r.clone().json()).error || {}).code || (await r.json()).error : null);

/* ================= 1. fail-open ================= */
{
  const env = baseEnv({ EXPECTED_SIG_FP: "" });
  const req = new Request("https://w.example/ai/jev/decisions", { method: "POST", body: JEV_BODY });
  ok("墙未配 EXPECTED_SIG_FP → fail-open 放行", (await M.integrityGate(req, env)) === null);
}

/* ================= 2. 无凭证 → Android 墙 ================= */
{
  const env = baseEnv();
  const req = new Request("https://w.example/ai/jev/decisions", { method: "POST", body: JEV_BODY });
  const r = await M.integrityGate(req, env);
  ok("无凭证 + 墙开 → 403 integrity_signature", r && r.status === 403);
  ok("  错误体为 integrity_signature", (await r.json()).error === "integrity_signature");
}

/* ================= 3. Android 客户端行为零变化（ts=毫秒） ================= */
{
  const env = baseEnv();
  const ts = nowMs();
  let req = new Request("https://w.example/ai/jev/decisions", {
    method: "POST",
    body: JEV_BODY,
    headers: androidHeaders("/ai/jev/decisions", ts),
  });
  ok("Android 五头合法 → 放行", (await M.integrityGate(req, env)) === null);
  req = new Request("https://w.example/ai/jev/decisions", {
    method: "POST",
    body: JEV_BODY,
    headers: androidHeaders("/ai/jev/decisions", ts, "cd".repeat(32)),
  });
  let r = await M.integrityGate(req, env);
  ok("Android 指纹错 → 403 integrity_signature", r && r.status === 403 && (await r.json()).error === "integrity_signature");
  req = new Request("https://w.example/ai/jev/decisions", {
    method: "POST",
    body: JEV_BODY,
    headers: androidHeaders("/ai/jev/decisions", ts, FP, "com.evil.app"),
  });
  r = await M.integrityGate(req, env);
  ok("Android 包名错 → 403 integrity_package", r && r.status === 403 && (await r.json()).error === "integrity_package");
  req = new Request("https://w.example/ai/jev/decisions", {
    method: "POST",
    body: JEV_BODY,
    headers: androidHeaders("/ai/jev/decisions", ts - 600000),
  });
  r = await M.integrityGate(req, env);
  ok("Android 时间戳超窗 → 403 integrity_ts", r && r.status === 403 && (await r.json()).error === "integrity_ts");
  req = new Request("https://w.example/ai/jev/decisions", {
    method: "POST",
    body: JEV_BODY,
    headers: { ...androidHeaders("/ai/jev/decisions", ts), "x-hmac": "00".repeat(32) },
  });
  r = await M.integrityGate(req, env);
  ok("Android HMAC 错 → 403 integrity_hmac", r && r.status === 403 && (await r.json()).error === "integrity_hmac");
  // 关键回归：五头合法但计费令牌过期 → 必须放行（不能因 EXE 侧令牌校验误杀 Android）
  const expired = await M.signBillingToken(env, { sub: "u_droid", tier: "basic", exp: Date.now() - 1e3 });
  req = new Request("https://w.example/ai/jev/decisions", {
    method: "POST",
    body: JEV_BODY,
    headers: { ...androidHeaders("/ai/jev/decisions", nowMs()), authorization: "Bearer " + expired },
  });
  r = await M.integrityGate(req, env);
  ok("Android 五头合法+计费令牌过期 → 仍放行（不误杀）", r === null, r ? String(r.status) : "null");
}

/* ================= 4. EXE 凭证①：HMAC 签名（ts=秒） ================= */
{
  const env = baseEnv();
  const ts = nowSec();
  const nonce = crypto.randomBytes(16).toString("hex");
  let req = new Request("https://w.example/ai/jev/decisions", {
    method: "POST",
    body: JEV_BODY,
    headers: exeHeaders("/ai/jev/decisions", JEV_BODY, ts, nonce),
  });
  ok("EXE HMAC 签名合法 → 放行", (await M.integrityGate(req, env)) === null);
  req = new Request("https://w.example/ai/jev/decisions", {
    method: "POST",
    body: JEV_BODY + " ",
    headers: exeHeaders("/ai/jev/decisions", JEV_BODY, ts, nonce),
  });
  let r = await M.integrityGate(req, env);
  ok("EXE 签名与 body 不符 → 401 bad_sig", r && r.status === 401 && (await errCode(r)) === "bad_sig", r ? String(r.status) + "/" + (await errCode(r)) : "null");
  req = new Request("https://w.example/ai/jev/decisions", {
    method: "POST",
    body: JEV_BODY,
    headers: exeHeaders("/ai/jev/decisions", JEV_BODY, ts - 120, nonce),
  });
  r = await M.integrityGate(req, env);
  ok("EXE 签名时间戳超 60s → 401 ts_expired", r && r.status === 401 && (await errCode(r)) === "ts_expired", r ? String(r.status) + "/" + (await errCode(r)) : "null");
  req = new Request("https://w.example/ai/jev/decisions", {
    method: "POST",
    body: JEV_BODY,
    headers: { "x-weauto-ts": String(ts), "x-weauto-nonce": nonce },
  });
  r = await M.integrityGate(req, env);
  ok("EXE 签名头不完整 → 401 bad_sig", r && r.status === 401 && (await errCode(r)) === "bad_sig", r ? String(r.status) + "/" + (await errCode(r)) : "null");
  const env2 = baseEnv({ WEAUATO_CLIENT_SECRET: "" });
  req = new Request("https://w.example/ai/jev/decisions", {
    method: "POST",
    body: JEV_BODY,
    headers: exeHeaders("/ai/jev/decisions", JEV_BODY, ts, nonce),
  });
  r = await M.integrityGate(req, env2);
  ok("服务端未配签名密钥 → 500 sig_unconfigured", r && r.status === 500 && (await errCode(r)) === "sig_unconfigured", r ? String(r.status) + "/" + (await errCode(r)) : "null");
}

/* ================= 5. EXE 凭证②：卡密+实例 ================= */
{
  const env = baseEnv();
  let req = new Request("https://w.example/ai/v1/chat/completions", {
    method: "POST",
    body: JSON.stringify({ model: "@cf/meta/llama-3.3-70b-instruct-fp8-fast", messages: [] }),
    headers: { authorization: "Bearer TESTKEY", "x-weauto-instance": "INST01" },
  });
  ok("卡密+实例（缓存命中）→ 放行", (await M.integrityGate(req, env)) === null);
  req = new Request("https://w.example/ai/v1/chat/completions", {
    method: "POST",
    body: "{}",
    headers: { authorization: "Bearer license-pending", "x-weauto-instance": "INST01" },
  });
  let r = await M.integrityGate(req, env);
  ok("未激活哨兵 license-pending → 401 license_required", r && r.status === 401 && (await errCode(r)) === "license_required", r ? String(r.status) + "/" + (await errCode(r)) : "null");
  const msg = r ? (await r.clone().json()).error.message : "";
  ok("  错误信息引导激活卡密", String(msg).includes("激活"), msg);
  req = new Request("https://w.example/ai/v1/chat/completions", {
    method: "POST",
    body: "{}",
    headers: { authorization: "Bearer BADKEY", "x-weauto-instance": "INST01" },
  });
  r = await M.integrityGate(req, env);
  ok("实例头在+卡密无效 → 401 license_required", r && r.status === 401 && (await errCode(r)) === "license_required", r ? String(r.status) + "/" + (await errCode(r)) : "null");
}

/* ================= 6. EXE 凭证③：计费令牌 ================= */
{
  const env = baseEnv();
  const tok = await M.signBillingToken(env, { sub: "u_test", tier: "standard", exp: Date.now() + 36e5 });
  let req = new Request("https://w.example/token/status?token=" + encodeURIComponent(tok), { method: "GET" });
  ok("/token/status 合法 query 令牌 → 放行", (await M.integrityGate(req, env)) === null);
  req = new Request("https://w.example/ai/jev/decisions", {
    method: "POST",
    body: JEV_BODY,
    headers: { authorization: "Bearer " + tok },
  });
  ok("Authorization 合法计费令牌 → 放行", (await M.integrityGate(req, env)) === null);
  req = new Request("https://w.example/token/status?token=aaaa.bbbb", { method: "GET" });
  let r = await M.integrityGate(req, env);
  ok("假令牌无痕迹头 → 墙 403", r && r.status === 403 && (await r.json()).error === "integrity_signature");
  const expired = await M.signBillingToken(env, { sub: "u_x", tier: "basic", exp: Date.now() - 1e3 });
  req = new Request("https://w.example/token/status?token=" + encodeURIComponent(expired), { method: "GET" });
  r = await M.integrityGate(req, env);
  ok("过期令牌无痕迹头 → 墙 403", r && r.status === 403 && (await r.json()).error === "integrity_signature");
}

/* ================= 7. 闸门不消耗请求体 ================= */
{
  const env = baseEnv();
  const nonce = crypto.randomBytes(16).toString("hex");
  const req = new Request("https://w.example/ai/jev/decisions", {
    method: "POST",
    body: JEV_BODY,
    headers: exeHeaders("/ai/jev/decisions", JEV_BODY, nowSec(), nonce),
  });
  await M.integrityGate(req, env);
  const bodyAfter = await req.text();
  ok("闸门 clone 读 body 后原请求体仍可读", bodyAfter === JEV_BODY, bodyAfter.slice(0, 40));
}

/* ================= 8. 端到端：worker.default.fetch ================= */
{
  const env = baseEnv();
  const nonce = crypto.randomBytes(16).toString("hex");
  let req = new Request("https://w.example/ai/jev/decisions", {
    method: "POST",
    body: JEV_BODY,
    headers: { "content-type": "application/json", ...exeHeaders("/ai/jev/decisions", JEV_BODY, nowSec(), nonce) },
  });
  let r = await M.worker.fetch(req, env, {});
  ok("e2e jev + EXE 签名 → 200", r.status === 200, String(r.status));
  let b = await r.json();
  ok("  answers.literal_question.noul == 1", b.answers && b.answers.literal_question && b.answers.literal_question.noul === 1, JSON.stringify(b).slice(0, 120));
  req = new Request("https://w.example/ai/jev/decisions", {
    method: "POST",
    body: JEV_BODY,
    headers: { "content-type": "application/json" },
  });
  r = await M.worker.fetch(req, env, {});
  ok("e2e jev 无凭证 → 403 integrity_signature", r.status === 403 && (await r.json()).error === "integrity_signature", String(r.status));
  req = new Request("https://w.example/ai/jev/decisions", {
    method: "POST",
    body: JEV_BODY,
    headers: { "content-type": "application/json", ...androidHeaders("/ai/jev/decisions", nowMs()) },
  });
  r = await M.worker.fetch(req, env, {});
  ok("e2e jev + Android 五头 → 200", r.status === 200, String(r.status));
  req = new Request("https://w.example/ai/v1/chat/completions", {
    method: "POST",
    body: JSON.stringify({ model: "@cf/meta/llama-3.3-70b-instruct-fp8-fast", messages: [{ role: "user", content: "hi" }] }),
    headers: { "content-type": "application/json", authorization: "Bearer TESTKEY", "x-weauto-instance": "INST01" },
  });
  r = await M.worker.fetch(req, env, {});
  const rb = await r.text();
  ok("e2e /ai/v1 卡密+实例 → 非墙拦截", !(r.status === 403 && rb.includes("integrity_signature")), String(r.status) + " " + rb.slice(0, 80));
  const tok = await M.signBillingToken(env, { sub: "u_e2e", tier: "pro", exp: Date.now() + 36e5 });
  req = new Request("https://w.example/token/status?token=" + encodeURIComponent(tok), { method: "GET" });
  r = await M.worker.fetch(req, env, {});
  b = await r.json();
  ok("e2e /token/status → 200 ok", r.status === 200 && b.ok === true, String(r.status) + " " + JSON.stringify(b).slice(0, 80));
  req = new Request("https://w.example/validate", {
    method: "POST",
    body: JSON.stringify({ key: "TESTKEY", instance_id: "INST01" }),
    headers: { "content-type": "application/json" },
  });
  r = await M.worker.fetch(req, env, {});
  b = await r.json();
  ok("e2e /validate（非墙上路由）→ 200 ok", r.status === 200 && b.ok === true, String(r.status) + " " + JSON.stringify(b).slice(0, 100));
}

/* ---------- 收尾 ---------- */
fs.rmSync(TMP, { force: true });
console.log("\n==== gate test: " + pass + " pass / " + fail + " fail ====");
process.exit(fail ? 1 : 0);
