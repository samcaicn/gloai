// 离线验证 token 计费全链路（Node 22，内存 mock，不打网络）
// 运行：node _billing_test.mjs（需先有 cf_worker.js 的副本 _cf_worker_under_test.mjs）
import worker from "./_cf_worker_under_test.mjs";

let pass = 0, fail = 0;
function ok(name, cond, extra = "") {
  if (cond) { pass++; console.log("  ok  " + name); }
  else { fail++; console.log("FAIL  " + name + (extra ? "  " + extra : "")); }
}

// ---- 内存 KV ----
function memKV() {
  const m = new Map();
  return {
    async get(k) { return m.has(k) ? m.get(k) : null; },
    async put(k, v) { m.set(k, String(v)); },
    m,
  };
}
const BILLING_KV = memKV();
const LICENSE_KV = memKV();

// ---- mock upstream LLM：返回 usage.total_tokens=1234 ----
let upstreamCalls = 0, upstreamAuthHdr = "";
globalThis.fetch = async (target, init = {}) => {
  const t = String(target);
  if (t.startsWith("https://upstream.test/v1/")) {
    upstreamCalls++;
    upstreamAuthHdr = (init.headers && (init.headers.authorization || init.headers["authorization"])) || "";
    return new Response(JSON.stringify({
      choices: [{ message: { content: "hi" } }],
      usage: { total_tokens: 1234 },
    }), { status: 200, headers: { "content-type": "application/json" } });
  }
  return new Response(JSON.stringify({ error: "unexpected fetch " + t }), { status: 599 });
};

// ---- mock Workers AI ----
const env = {
  BILLING_KV, LICENSE_KV,
  WEAUATO_SIGN_SECRET: "test_sign_secret_0123456789abcdef",
  AI_UPSTREAM_URL: "https://upstream.test/v1",
  AI_UPSTREAM_KEY: "sk-upstream-real-key",
  AI_DEFAULT_MODEL: "gpt-test",
  JEV_MODEL: "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
  CREEM_PRODUCTS: "[]",
  CREEM_MODE: "prod",
  CREEM_WEBHOOK_SECRET: "whsec_test",
  ADMIN_KEY: "test_admin_key",
  AI: {
    async run(model, opts) {
      return { choices: [{ message: { content: JSON.stringify({ mood: "vent_anger" }) } }] };
    },
  },
};

async function call(path, opts = {}) {
  const req = new Request("https://weauto.safeopc.cn" + path, opts);
  const res = await worker.fetch(req, env, {});
  let body = null;
  try { body = await res.json(); } catch (_) {}
  return { status: res.status, body, headers: res.headers, res };
}

const enc = new TextEncoder();
async function hmacHex(secret, data) {
  const key = await crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("HMAC", key, typeof data === "string" ? enc.encode(data) : data);
  return [...new Uint8Array(sig)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

// ============ 1. health / plans ============
{
  const h = await call("/health");
  ok("health billing_enabled", h.body.billing_enabled === true);
  ok("health billing_kv", h.body.billing_kv === true);
  ok("health tiers=3", Array.isArray(h.body.tiers) && h.body.tiers.length === 3);
  const pl = await call("/plans");
  ok("plans 3档", pl.body.plans.length === 3);
  const basic = pl.body.plans.find((x) => x.tier === "basic");
  ok("basic=100算力/100万token/¥19.9", basic.compute === 100 && basic.tokens === 1000000 && basic.price_cny === 19.9);
  const pro = pl.body.plans.find((x) => x.tier === "pro");
  ok("pro=2000算力/2000万token", pro.tokens === 20000000);
}

// ============ 2. 签发 + 状态 ============
let tok;
{
  // 无 admin key -> 403（fail-close）
  const rNo = await call("/token/issue", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ tier: "standard" }),
  });
  ok("无 ADMIN_KEY 签发被拒 403", rNo.status === 403);

  const r = await call("/token/issue", {
    method: "POST",
    headers: { "content-type": "application/json", "x-admin-key": "test_admin_key" },
    body: JSON.stringify({ tier: "standard", sub: "user_abc" }),
  });
  ok("issue ok", r.body && r.body.ok === true, JSON.stringify(r.body));
  ok("standard quota=500万", r.body.quota === 5000000);
  tok = r.body.token;
  ok("token 形如 xxx.yyy", /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(tok));

  const s = await call("/token/status?token=" + encodeURIComponent(tok));
  ok("status used=0", s.body.ok === true && s.body.used === 0 && s.body.remain === 5000000);
}

// ============ 3. /ai/v1 计费扣减 ============
{
  const r = await call("/ai/v1/chat/completions", {
    method: "POST",
    headers: { "content-type": "application/json", "authorization": "Bearer " + tok },
    body: JSON.stringify({ messages: [{ role: "user", content: "hello" }] }),
  });
  ok("ai/v1 200", r.status === 200, "status=" + r.status + " body=" + JSON.stringify(r.body));
  ok("upstream 被调用且注入真实key", upstreamCalls === 1 && upstreamAuthHdr === "Bearer sk-upstream-real-key");
  ok("X-Billing-Used=1234", r.headers.get("x-billing-used") === "1234", r.headers.get("x-billing-used"));
  ok("X-Billing-Quota=500万", r.headers.get("x-billing-quota") === "5000000");
  ok("X-Billing-Tier=standard", r.headers.get("x-billing-tier") === "standard");

  await call("/ai/v1/chat/completions", {
    method: "POST",
    headers: { "content-type": "application/json", "authorization": "Bearer " + tok },
    body: JSON.stringify({ messages: [{ role: "user", content: "hello2" }] }),
  });
  const s = await call("/token/status?token=" + encodeURIComponent(tok));
  ok("两次后 used=2468", s.body.used === 2468, "used=" + s.body.used);

  // 不带 body.model 时自动补 AI_DEFAULT_MODEL
  // （在 upstreamCall 里没法直接断言 body，跳过 —— 逻辑与旧 proxyAi 一致）

  // 篡改签名
  const bad = tok.slice(0, -4) + "AAAA";
  const rb = await call("/ai/v1/chat/completions", {
    method: "POST",
    headers: { "content-type": "application/json", "authorization": "Bearer " + bad },
    body: "{}",
  });
  ok("篡改令牌 401", rb.status === 401);

  // 随便造的假令牌
  const rf = await call("/ai/v1/chat/completions", {
    method: "POST",
    headers: { "content-type": "application/json", "authorization": "Bearer sk-abc12345678" },
    body: "{}",
  });
  ok("非令牌格式回落 Creem 卡密路径（无 CREEM_API_KEY => fail-close 401）", rf.status === 401);
}

// ============ 4. Jev 计费 ============
{
  const before = (await call("/token/status?token=" + encodeURIComponent(tok))).body.used;
  const r = await call("/ai/jev/decisions", {
    method: "POST",
    headers: { "content-type": "application/json", "authorization": "Bearer " + tok },
    body: JSON.stringify({
      questions: { mood: { type: "choice", criteria: { vent_anger: "在发泄愤怒", casual_chat: "闲聊" } } },
      state: {},
    }),
  });
  ok("jev 200 + answers", r.status === 200 && r.body.answers && r.body.answers.mood.choice === "vent_anger",
    JSON.stringify(r.body));
  ok("jev 回带计费头", !!r.headers.get("x-billing-used"));
  const after = (await call("/token/status?token=" + encodeURIComponent(tok))).body.used;
  ok("jev 记账 +1500", after - before === 1500, (after - before) + "");

  // jev 坏令牌（每段≥8字符、形似令牌但签名错）
  const rb = await call("/ai/jev/decisions", {
    method: "POST",
    headers: { "content-type": "application/json", "authorization": "Bearer aaaaaaaaaaaaaaaa.bbbbbbbbbbbbbbbb" },
    body: JSON.stringify({ questions: { mood: { type: "choice", criteria: { a: "a" } } }, state: {} }),
  });
  ok("jev 坏令牌 401", rb.status === 401);
}

// ============ 5. 超额 402 ============
{
  const r = await call("/token/issue", {
    method: "POST",
    headers: { "content-type": "application/json", "x-admin-key": "test_admin_key" },
    body: JSON.stringify({ tier: "basic", sub: "user_poor" }),
  });
  const bt = r.body.token;
  // 手动把用量写满
  const m = new Date().getUTCFullYear() + "-" + String(new Date().getUTCMonth() + 1).padStart(2, "0");
  await BILLING_KV.put("usage:user_poor", JSON.stringify({ month: m, tokens: 1000000 }));
  const r2 = await call("/ai/v1/chat/completions", {
    method: "POST",
    headers: { "content-type": "application/json", "authorization": "Bearer " + bt },
    body: "{}",
  });
  ok("超额 402", r2.status === 402, "status=" + r2.status);
  ok("402 code=quota_exceeded", r2.body && r2.body.error && r2.body.error.code === "quota_exceeded");

  // 跨月归零：改 month 字段
  await BILLING_KV.put("usage:user_poor", JSON.stringify({ month: "2000-01", tokens: 999999999 }));
  const r3 = await call("/token/status?token=" + encodeURIComponent(bt));
  ok("跨月用量归零", r3.body.used === 0);
}

// ============ 6. webhook 自动签发 ============
{
  const raw = JSON.stringify({
    eventType: "checkout.completed",
    object: {
      id: "ch_1", license_key: "LK-1234-ABCD",
      metadata: { mid: "mid_001", tier: "premium" },
    },
  });
  const sig = await hmacHex("whsec_test", raw);
  const r = await call("/webhook", {
    method: "POST",
    headers: { "content-type": "application/json", "creem-signature": sig },
    body: raw,
  });
  ok("webhook 200", r.status === 200);
  const stored = JSON.parse(await LICENSE_KV.get("lic:mid_001"));
  ok("webhook 存了卡密+token", stored.key === "LK-1234-ABCD" && /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(stored.token || ""));
  // 该 token 直接可用，且按 premium 计费
  const s = await call("/token/status?token=" + encodeURIComponent(stored.token));
  ok("webhook token 有效且 premium 映射到 standard 档", s.body.ok === true && s.body.tier === "standard" && s.body.quota === 5000000,
    JSON.stringify(s.body));
  // 错签拒绝
  const r2 = await call("/webhook", {
    method: "POST",
    headers: { "content-type": "application/json", "creem-signature": "deadbeef" },
    body: raw,
  });
  ok("webhook 错签 401", r2.status === 401);
}

console.log("\n" + pass + " passed, " + fail + " failed");
process.exit(fail ? 1 : 0);
