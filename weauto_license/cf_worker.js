/**
 * WeAuto License Worker — 部署到 Cloudflare Workers
 * --------------------------------------------------------------------------
 * 角色：Creem 与客户端 EXE 之间的唯一服务端代理 + 买家「卡密网站」。
 *   - 客户端 EXE 只与本 Worker 通信，绝不直接接触 api.creem.io。
 *   - 本 Worker 持有 Creem 密钥（CF Secrets 注入），代理激活/校验/释放。
 *   - Creem Webhook 落在这里，验签后记录事件。
 *   - /buy 是一个真正的中文购买站（卡密网站），不是裸跳转。
 *
 * 为什么必须放服务端：EXE 二进制里的密钥等于明文（可被逆向抠出），
 * 拿到 key 就能白嫖激活。Creem 官方也明确 SDK 不可放客户端。
 *
 * 部署（一次性）：
 *   npm i -g wrangler
 *   wrangler login
 *   wrangler secret put CREEM_API_KEY        # Creem > Developers > API Keys
 *   wrangler secret put CREEM_WEBHOOK_SECRET # Creem > Developers > Webhooks
 *   wrangler deploy                          # 终端会打印真实地址
 *
 * 注入变量（wrangler.toml [vars]）：
 *   CREEM_MODE           "test" | "prod"
 *   CREEM_PRODUCTS       JSON 数组，多档套餐（每档含 tier/label/price_text/product_id/billing/features）
 *   SITE_TITLE / SUPPORT_EMAIL   站点文案
 *   AI 双供应商（彻底去掉 vg.v1api.cc）：
 *     - Workers AI：env.AI binding（wrangler [ai]），免密钥、有免费额度，/ai 默认走它
 *     - 火山方舟：VOLCANO_BASE_URL + VOLCANO_API_KEY（OpenAI 兼容，failover 备用）
 *   路由规则见 cf_worker.js 的 resolveProvider；模型以 @cf/ 开头即 Workers AI。
 *
 *   AI_DEFAULT_PROVIDER  workers | volcano（模型未显式标明供应商时的默认；默认 workers）
 *   AI_CHAT_MODEL        Workers AI 聊天模型（默认 @cf/meta/llama-3.3-70b-instruct-fp8-fast）
 *   VOLCANO_BASE_URL     火山方舟 API 基址（默认 https://ark.cn-beijing.volces.com/api/v3）
 *   VOLCANO_MODEL        火山默认模型（planagent；如 doubao-seed-1.6-250615 或 ep-xxxx 接入点）
 *
 * 额外 Secrets（wrangler secret put）：
 *   VOLCANO_API_KEY      火山方舟 API Key（商家持有，客户端永不接触）；
 *                        未配置时火山不可用，/ai 回落到 Workers AI
 * 兼容保留（旧的 vg.v1api.cc secret 仍可被当作火山 key 复用，不推荐）：
 *   AI_UPSTREAM_URL / AI_UPSTREAM_KEY —— 仅当未配 VOLCANO_* 时作为火山配置回退
 *
 * 关于支付宝（两个不同概念，别混淆）：
 *   1) 买家付款方式：Creem 2.0 已上线 AliPay（官方 Changelog: "Alipay Is Live at Checkout,
 *      available on one-time checkouts"）。**只在一次性付款（onetime）收银台出现**——
 *      订阅制产品没有支付宝入口。买家在收银台自己选，无需任何代码改动。
 *   2) 商家收款（payout）：中国商户可在 Balance → Payout Account 添加「支付宝」收款，
 *      单笔上限 5 万 CNY。这是你拿到钱的方式，与买家付款方式是两回事。
 *
 * API 路径实测（2026-09-25，无 key 时以 401/404 区分):
 *   GET  /v1/products  -> 401（存在）      GET /v1/checkouts -> 401（存在）
 *   GET  /v1/checkout  -> 404（不存在！）  GET /v1/zzz-不存在 -> 404
 *   → 创建结账会话必须用复数 /v1/checkouts，写成单数会静默失败。
 *   另：直连 api.creem.io 需带浏览器 UA，否则被 CF WAF 挡成 403 error code 1010。
 */

const creemBase = (mode) =>
  mode === "prod" ? "https://api.creem.io/v1" : "https://test-api.creem.io/v1";

// ============ 一次性运维 nonce（改完线上价格后应清空并重部署）============
// 明文口令只存在于部署者本地，代码里只留 SHA-256 哈希 -> 拿到源码也推不出明文。
// 用法：echo -n "$NONCE" | openssl dgst -sha256 -hex  填到下面。
const ONETIME_NONCE_SHA256 = "";  // 已用完并烧毁：重新启用需重跑 create_creem_product.py 生成新 nonce


/** sha256 十六进制（用 Workers 原生 crypto.subtle —— 自己手写 SHA-256 实测算错，
 *  这类密码学原语千万别手搓）。 */
async function sha256hex(str) {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(String(str)));
  return Array.from(new Uint8Array(buf)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function creemPost(env, path, body) {
  try {
    const r = await fetch(creemBase(env.CREEM_MODE) + path, {
      method: "POST",
      headers: {
        "x-api-key": env.CREEM_API_KEY,
        "Content-Type": "application/json",
        accept: "application/json",
      },
      body: JSON.stringify(body),
    });
    const txt = await r.text();
    let data = {};
    try {
      data = JSON.parse(txt);
    } catch (e) {
      /* 非 JSON 响应（如错误页）忽略正文 */
    }
    return { status: r.status, data };
  } catch (e) {
    // 网络层异常（DNS 失败 / 连接重置 / 超时 / 上游被 WAF 挡）统一归一化，
    // 不让异常冒泡成 Worker 500；调用方按 status:0 + neterr 返回 503。
    console.error("creem upstream unreachable:", path, e && e.message);
    return { status: 0, data: { message: "upstream_unreachable" }, neterr: true };
  }
}

/** 上游不可用判定：网络层异常 或 Creem 自身 5xx（临时故障，不是买家的错）。 */
function upstreamError(res) {
  return !res || res.neterr === true || (res.status >= 500 && res.status <= 599);
}

/** Creem 通用请求（GET/PATCH 都用这个）。Creem 的 key 只存在于 Worker secret，
 *  本机拿不到，所以「改线上实收价」必须由 Worker 自己发请求。 */
async function creemReq(env, method, path, body) {
  try {
    const r = await fetch(creemBase(env.CREEM_MODE) + path, {
      method: method,
      headers: {
        "x-api-key": env.CREEM_API_KEY,
        "Content-Type": "application/json",
        accept: "application/json",
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const txt = await r.text();
    let data = {};
    try { data = JSON.parse(txt); } catch (e) { /* 非 JSON 响应忽略 */ }
    return { status: r.status, data: data };
  } catch (e) {
    console.error("creem upstream unreachable:", method, path, e && e.message);
    return { status: 0, data: { message: "upstream_unreachable" }, neterr: true };
  }
}

/** 上游不可用时的统一 503 响应（替代原来的裸 500）。 */
function upstreamUnavailable(res) {
  const reason = (res && res.data && res.data.message) || "upstream_unreachable";
  return json({ ok: false, reason, upstream_status: (res && res.status) || 0 }, 503);
}

/** 判定 Creem 响应是否表示「已激活/有效」，供 /activate 做业务层校验。 */
function isActiveLike(data) {
  if (!data) return false;
  if (typeof data.status === "string") return data.status.toLowerCase() === "active";
  if (data.active === true) return true;
  const lic = data.license || data.license_key_object;
  if (lic && typeof lic === "object") {
    if (typeof lic.status === "string") return lic.status.toLowerCase() === "active";
    if (lic.active === true) return true;
  }
  // 响应里没有 status 字段（部分 Creem 版本）：有实例/id 即视为激活成功
  return !!(data.instance || data.id);
}

function extractInstanceId(data) {
  const inst = data && data.instance;
  if (Array.isArray(inst)) return inst[0] && inst[0].id;
  if (inst && typeof inst === "object") return inst.id;
  return data && data.id;
}

function expiresToUnix(expiresAt) {
  if (!expiresAt) return null;
  const t = Date.parse(expiresAt);
  return Number.isNaN(t) ? null : Math.floor(t / 1000);
}

/**
 * 解析 creem-signature 请求头。
 * 实际格式是 `t=<时间戳>,s=<hex摘要>`（也可能是纯 hex），
 * 不能把整串当 hex 直接比较（长度必不相等 → 合法回调一律被拒）。
 */
function parseSig(header) {
  if (!header) return { sig: "", ts: null };
  if (header.includes("s=")) {
    const parts = header.split(",").map((s) => s.trim());
    let sig = "", ts = null;
    for (const p of parts) {
      if (p.startsWith("s=")) sig = p.slice(2);
      else if (p.startsWith("t=")) ts = Number(p.slice(2));
    }
    return { sig, ts };
  }
  return { sig: header.trim(), ts: null }; // 纯 hex 兜底
}

// 重放窗口：超过 300s 的旧回调一律拒绝（时间戳单位兼容秒/毫秒）
const REPLAY_WINDOW_SEC = 300;

// HMAC-SHA256 验签，常量时间比较
async function verifySignature(secret, rawBody, signature) {
  if (!secret || !signature) return false;
  const { sig, ts } = parseSig(signature);
  if (!sig) return false;
  // 重放防护：有 t= 且已超过窗口 → 拒绝
  if (ts !== null && Number.isFinite(ts)) {
    const tsSec = ts > 1e12 ? Math.floor(ts / 1000) : Math.floor(ts);
    const nowSec = Math.floor(Date.now() / 1000);
    if (nowSec - tsSec > REPLAY_WINDOW_SEC) {
      console.warn("webhook signature too old, rejected:", nowSec - tsSec, "s");
      return false;
    }
  }
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey(
    "raw", enc.encode(secret),
    { name: "HMAC", hash: "SHA-256" }, false, ["sign"]
  );
  const buf = await crypto.subtle.sign("HMAC", key, enc.encode(rawBody));
  const computed = [...new Uint8Array(buf)]
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
  const given = sig.trim().toLowerCase(); // 大小写无关
  if (computed.length !== given.length) return false;
  let diff = 0;
  for (let i = 0; i < computed.length; i++) {
    diff |= computed.charCodeAt(i) ^ given.charCodeAt(i);
  }
  return diff === 0;
}

function json(obj, status = 200, extraHeaders = null) {
  const h = { "content-type": "application/json; charset=utf-8" };
  if (extraHeaders && typeof extraHeaders.entries === "function") {
    for (const [k, v] of extraHeaders.entries()) h[k] = v;
  }
  return new Response(JSON.stringify(obj), { status, headers: h });
}

/** 解析套餐列表：优先 CREEM_PRODUCTS（JSON 数组，多档）；兼容旧的单产品变量。 */
function parseProducts(env) {
  const raw = env.CREEM_PRODUCTS;
  if (raw) {
    try {
      const arr = typeof raw === "string" ? JSON.parse(raw) : raw;
      if (Array.isArray(arr) && arr.length) {
        // 归一化：补齐 valid_days（默认 30 天）与 voluntary 字段，供「每月提示续费」计算用。
        // 现在三档都是一次性（onetime）套餐，购一次给 valid_days 天授权，到期前软件提示续费。
        return arr.map((x) => ({
          tier: x.tier,
          label: x.label || x.tier,
          price_text: x.price_text || "",
          product_id: x.product_id,
          billing: x.billing || "once",
          features: x.features || "",
          valid_days: x.valid_days ? Number(x.valid_days) : 30,
          voluntary: !!x.voluntary,
          // usd_cents = 该档在 Creem 侧的**实收价**（美分），与 price_text 展示价零联动。
          // 只被运维接口 /creem/price 用来校准线上价格；购买页不使用。
          usd_cents: Number.isInteger(x.usd_cents) ? x.usd_cents : null,
        }));
      }
    } catch (e) { /* 解析失败走兜底 */ }
  }
  if (env.CREEM_PRODUCT_ID) {
    return [{
      tier: "default",
      label: env.PRODUCT_NAME || "WeAuto 授权",
      price_text: env.PRODUCT_PRICE || "见收银台",
      product_id: env.CREEM_PRODUCT_ID,
      billing: "once",
      features: env.PRODUCT_DESC || "",
    }];
  }
  return [];
}

/** 从 Creem license 校验响应里尽力取 product -> tier（响应结构不确定，做多重兼容）。 */
function mapTier(env, data) {
  const products = parseProducts(env);
  if (!products.length || !data) return null;
  const obj = data.order || data.subscription || data.product || data.license || data;
  // 候选 product id：优先 product_id 字段，其次嵌套对象的 id（Creem 两种都有）
  const cands = [obj && obj.product_id, obj && obj.id, data.product_id].filter(Boolean);
  for (const c of cands) {
    const hit = products.find((x) => x.product_id === c);
    if (hit) return hit.tier; // 只在能精确匹配到已知套餐时才返回，避免误映射
  }
  return null;
}

/* ---------------- AI 代理（LLM 唯一出口，反破解核心） ----------------
 * 客户端把 OpenAI base_url 指到 <worker>/ai/v1，api_key 用 license key，
 * 并带 X-WeAuto-Instance 头。Worker 验完卡密后把请求路由到 Workers AI（env.AI）
 * 或火山方舟（VOLCANO_*，OpenAI 兼容）并注入商家持有的 VOLCANO_API_KEY ——
 * 客户端永远拿不到真实 key：反编译/patch 掉客户端门禁也没用，没有有效卡密这里直接 401。
 */

// isolate 级内存缓存：key|instance -> 校验通过时间戳(ms)。TTL 内不再打 Creem，
// 避免每次 LLM 调用都加 200-400ms 延迟；isolate 回收后重新验一次，代价可接受。
const licenseCache = new Map();
const LICENSE_TTL = 10 * 60 * 1000;

async function licenseOk(env, key, instanceId, requireUpstream = true) {
  // fail-close：Worker 自身没配好（无 Creem key）一律拒绝。
  // requireUpstream=false 供 Jev 用：Jev 走 Workers AI（env.AI），不依赖 LLM upstream，
  // 不能因为商家没配火山 key / Workers AI 就把 Jev 也一起封死（Jev 走 env.AI，不依赖 LLM upstream）。
  if (!key || !instanceId) return false;
  if (!env.CREEM_API_KEY) return false;
  if (requireUpstream && !aiAvailable(env)) return false;
  const now = Date.now();
  const ck = key + "|" + instanceId;
  const hit = licenseCache.get(ck);
  if (hit && now - hit < LICENSE_TTL) return true;
  try {
    const { status, data } = await creemPost(env, "/licenses/validate", {
      key,
      instance_id: instanceId,
    });
    const ok = status >= 200 && status < 300 && data && data.status === "active";
    if (ok) licenseCache.set(ck, now);
    else licenseCache.delete(ck);
    return ok;
  } catch (e) {
    licenseCache.delete(ck);
    return false;
  }
}

/* ---------------- AI 双供应商路由（彻底去掉 vg.v1api.cc） ----------------
 * 客户端把 OpenAI base_url 指到 <worker>/ai/v1，api_key 用 license key（或 APK 计费令牌）。
 * Worker 在这里把请求路由到两家供应商之一：
 *   1) Workers AI（Cloudflare 自家的 env.AI binding，免密钥、有免费额度，延迟低）
 *   2) 火山方舟（Volcano Engine / 火山，OpenAI 兼容，需 VOLCANO_API_KEY）
 * 路由规则（按优先级）：
 *   - 请求头 X-WeAuto-Provider: workers | volcano 显式指定；
 *   - 模型名以 @cf/ 开头 → Workers AI；
 *   - 模型名以 volcano:/ark:/doubao: 开头 → 火山（并剥掉前缀）；
 *   - 其余 → 由 AI_DEFAULT_PROVIDER（默认 workers）决定。
 * 任一家主供应商失败时，若另一家已配置则自动 failover，最大化在线率。
 */

/** 火山方舟配置：优先 VOLCANO_*，未配则回退兼容旧的 AI_UPSTREAM_*（旧 secret 仍可用）。 */
function volcanoConfig(env) {
  const base = env.VOLCANO_BASE_URL || env.AI_UPSTREAM_URL || "https://ark.cn-beijing.volces.com/api/v3";
  const key = env.VOLCANO_API_KEY || env.AI_UPSTREAM_KEY || "";
  return { base: String(base).replace(/\/+$/, ""), key: String(key) };
}
function volcanoConfigured(env) { return !!volcanoConfig(env).key; }
/** 任一 AI 供应商可用：Workers AI binding 绑定 或 火山 key 已配。 */
function aiAvailable(env) { return !!env.AI || volcanoConfigured(env); }

/** 解析最终供应商与模型名。返回 { provider, realModel } 或 { error }。 */
function resolveProvider(model, hint, env) {
  const workersReady = !!env.AI;
  const volcanoReady = volcanoConfigured(env);
  const def = (env.AI_DEFAULT_PROVIDER || "workers").toLowerCase();
  let provider;
  if (hint === "workers") provider = "workers";
  else if (hint === "volcano") provider = "volcano";
  else if (typeof model === "string" && model.startsWith("@cf/")) provider = "workers";
  else if (typeof model === "string" && /^(volcano|ark|doubao):/i.test(model)) provider = "volcano";
  else {
    // 未被前缀/header 显式指定：模型名本身像火山方舟（doubao / ep- / ark- 接入点）时优先走火山
    if (typeof model === "string" && /(doubao|^ep-|^ark-)/i.test(model) && volcanoReady) {
      provider = "volcano";
    } else {
      provider = def;
    }
  }
  let realModel = typeof model === "string" ? model : "";
  if (provider === "workers") {
    if (!workersReady) {
      if (volcanoReady) provider = "volcano";
      else return { error: "no_provider" };
    }
    if (!realModel.startsWith("@cf/")) {
      realModel = env.AI_CHAT_MODEL || env.AI_DEFAULT_MODEL || JEV_MODEL_DEFAULT;
    }
  } else {
    if (!volcanoReady) {
      if (workersReady) {
        provider = "workers";
        if (!realModel.startsWith("@cf/")) realModel = env.AI_CHAT_MODEL || env.AI_DEFAULT_MODEL || JEV_MODEL_DEFAULT;
      } else return { error: "no_provider" };
    }
    realModel = realModel.replace(/^(volcano|ark|doubao):/i, "");
    if (!realModel) realModel = env.VOLCANO_MODEL || env.AI_DEFAULT_MODEL || "";
  }
  return { provider, realModel };
}

/** 从 Workers AI 各种返回形态里抠出文本（兼容 {response}、choices、content、text）。 */
function workersAiText(res) {
  if (!res) return null;
  if (typeof res === "string") return res;
  if (res.response !== undefined) return typeof res.response === "string" ? res.response : JSON.stringify(res.response);
  if (res.choices && res.choices[0] && res.choices[0].message && res.choices[0].message.content !== undefined) return res.choices[0].message.content;
  if (res.content !== undefined) return res.content;
  if (res.text !== undefined) return res.text;
  return null;
}

/** 包成标准 OpenAI chat.completion 结构，让 OpenAI SDK 无需改动即可解析。 */
function openAiCompletion(model, text, usage) {
  return {
    id: "chatcmpl-weauto-" + Date.now().toString(36),
    object: "chat.completion",
    created: Math.floor(Date.now() / 1000),
    model: model,
    choices: [{ index: 0, message: { role: "assistant", content: text }, finish_reason: "stop" }],
    usage: {
      prompt_tokens: usage.prompt,
      completion_tokens: usage.completion,
      total_tokens: usage.prompt + usage.completion,
    },
  };
}

/** 字符数兜底估算 token（Workers AI 不返回 usage，计费/统计用）。 */
function estimateUsage(obj, text) {
  const msgs = (obj && Array.isArray(obj.messages)) ? JSON.stringify(obj.messages) : "";
  const promptChars = msgs ? msgs.length : 0;
  const completionChars = text ? String(text).length : 0;
  return { prompt: Math.ceil(promptChars / 3), completion: Math.ceil(completionChars / 3) };
}

/** Workers AI 直连：env.AI.run() -> OpenAI 结构（非流式，EXE 侧 stream=False）。 */
async function callWorkersAi(env, model, obj) {
  if (!env.AI) throw new Error("workers_ai_binding_missing");
  const messages = Array.isArray(obj.messages) ? obj.messages : [];
  const opts = { messages, stream: false };
  if (typeof obj.temperature === "number") opts.temperature = obj.temperature;
  if (typeof obj.max_tokens === "number") opts.max_tokens = obj.max_tokens;
  if (typeof obj.top_p === "number") opts.top_p = obj.top_p;
  if (obj.stop !== undefined) opts.stop = obj.stop;
  let res;
  try { res = await env.AI.run(model, opts); }
  catch (e) { throw e; }
  const text = workersAiText(res);
  if (text == null) throw new Error("workers_ai_empty_response");
  return json(openAiCompletion(model, text, estimateUsage(obj, text)), 200);
}

/** 火山方舟直连：OpenAI 兼容，注入真实 key，原样透传（含 SSE 流式）。 */
async function callVolcano(env, model, obj, url, req, bodyStr) {
  const c = volcanoConfig(env);
  if (!c.key) throw new Error("volcano_key_missing");
  const rel = url.pathname.slice("/ai/".length).replace(/^v1\//, "");
  const target = c.base + "/" + rel + url.search;
  let finalBody = bodyStr || "";
  if (model) {
    try {
      const o = finalBody ? JSON.parse(finalBody) : {};
      if (o.model !== model) { o.model = model; finalBody = JSON.stringify(o); }
    } catch (_) { /* 非 JSON 则原样发，让上游报错 */ }
  }
  const init = {
    method: req.method,
    headers: {
      "content-type": req.headers.get("content-type") || "application/json",
      "accept": req.headers.get("accept") || "application/json, text/event-stream",
      "authorization": "Bearer " + c.key,
      "user-agent": req.headers.get("user-agent") || "weauto-worker",
    },
    body: (req.method === "GET" || req.method === "HEAD") ? undefined : finalBody,
  };
  return fetch(target, init);
}

/** 按解析结果派发到对应供应商。 */
async function dispatch(env, provider, model, obj, url, req, bodyStr) {
  if (provider === "workers") return callWorkersAi(env, model, obj);
  return callVolcano(env, model, obj, url, req, bodyStr);
}

/** /ai/v1 统一入口：解析模型/供应商 -> 调用 -> 失败 failover。 */
async function routeAi(req, env, url) {
  let bodyStr = "";
  try { bodyStr = await req.clone().text(); } catch (_) {}
  let obj = {};
  if (bodyStr) { try { obj = JSON.parse(bodyStr); } catch (_) {} }
  const model = (obj && obj.model) || "";
  const hint = (req.headers && req.headers.get && req.headers.get("x-weauto-provider")) || "";
  const resolved = resolveProvider(model, hint, env);
  if (resolved.error) {
    return json({
      error: {
        message: "云端尚未配置 AI 供应商（Workers AI 或火山方舟），请联系管理员",
        type: "server_misconfigured",
        code: "ai_upstream_missing",
      },
    }, 503);
  }
  try {
    return await dispatch(env, resolved.provider, resolved.realModel, obj, url, req, bodyStr);
  } catch (e1) {
    const other = resolved.provider === "workers" ? "volcano" : "workers";
    const otherReady = other === "volcano" ? volcanoConfigured(env) : !!env.AI;
    if (otherReady) {
      try { return await dispatch(env, other, resolved.realModel, obj, url, req, bodyStr); }
      catch (_) { /* fall through to 502 */ }
    }
    return json({
      error: {
        message: "AI 调用失败（双供应商均不可用）：" + String((e1 && e1.message) || e1),
        type: "upstream_error",
        code: "ai_run_failed",
      },
    }, 502);
  }
}

/** /ai/v1/* 路由入口：先鉴权（计费令牌 或 Creem 卡密），再交给 routeAi 派发。 */
async function proxyAi(req, env, url) {
  const authHdr = (req.headers.get("authorization") || "").trim();
  const token = authHdr.startsWith("Bearer ") ? authHdr.slice(7).trim() : authHdr;
  // ---- token 计费路径（APK）：自校验签名 + 云端记账，不依赖 Creem ----
  if (token && looksLikeBillingToken(token) && env.WEAUATO_SIGN_SECRET) {
    return proxyAiBilled(req, env, url, token);
  }
  // ---- 旧 Creem 卡密路径（EXE，保持不变）----
  const key = token;
  const instanceId = req.headers.get("x-weauto-instance") || "";
  if (!(await licenseOk(env, key, instanceId))) {
    return json({
      error: {
        message: "无效授权：请先在 WeAuto 后台「授权管理」激活卡密后再使用 AI 功能",
        type: "weauto_license_error",
        code: "license_required",
      },
    }, 401);
  }
  return routeAi(req, env, url);
}

/* ===================== Token 计费（APK 专用，云端强约束） =====================
 * 设计要点：
 *  - 账户令牌 = base64url(payload).base64url(HMAC)，由 Worker 自己签发/校验，
 *    不依赖 Creem（每次请求不再打 Creem，延迟低、且 Creem 故障不影响已付费用户）。
 *  - 计费在云端强制：客户端只发令牌，用量由 Worker 读 upstream 响应里的
 *    usage.total_tokens 累加写入 KV，超额直接 402，客户端无法绕过。
 *  - 3 档月套餐：1 算力 = 10,000 token。档位在 TIERS 定义。
 *  - 与旧 Creem 卡密路径（EXE）并存：APK 发 billing token（含 "."），
 *    EXE 发 Creem key（含 "-"），按格式分流，互不干扰。
 */

const DEFAULT_TIER = "standard";
const JEV_COST = 1500; // Jev 一次判断估算消耗（Workers AI 不返回 usage）
const TIERS = {
  basic:    { name: "基础版", compute: 100,  tokens: 100 * 10000,  price_cny: 19.9,  price_text: "¥19.9/月" },
  standard: { name: "标准版", compute: 500,  tokens: 500 * 10000,  price_cny: 49.9,  price_text: "¥49.9/月" },
  pro:      { name: "旗舰版", compute: 2000, tokens: 2000 * 10000, price_cny: 129.9, price_text: "¥129.9/月" },
};

// 注意：Workers 运行时没有 Node 的 Buffer，必须用 btoa/atob + TextEncoder 纯 Web API
function b64u(s) {
  const bytes = new TextEncoder().encode(String(s));
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
function b64uDecode(s) {
  s = String(s).replace(/-/g, "+").replace(/_/g, "/");
  const bin = atob(s + "===".slice((s.length + 3) % 4));
  const bytes = Uint8Array.from(bin, (c) => c.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}
function looksLikeBillingToken(t) {
  return /^[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}$/.test(t || "");
}
function randHex(n) {
  const c = "abcdef0123456789";
  let s = "";
  for (let i = 0; i < n; i++) s += c[Math.floor(Math.random() * 16)];
  return s;
}
async function signBillingToken(env, payload) {
  const pb = b64u(JSON.stringify(payload));
  const sig = await hmacHex(env.WEAUATO_SIGN_SECRET, new TextEncoder().encode("weauto-billing-v1|" + pb));
  return pb + "." + sig;
}
async function verifyBillingToken(env, token) {
  if (!token || !looksLikeBillingToken(token)) return null;
  const parts = token.split(".");
  if (parts.length !== 2) return null;
  const expect = await hmacHex(env.WEAUATO_SIGN_SECRET, new TextEncoder().encode("weauto-billing-v1|" + parts[0]));
  if (expect.length !== parts[1].length || !timingSafeEqual(expect, parts[1])) return null;
  try {
    const payload = JSON.parse(b64uDecode(parts[0]));
    if (!payload.sub || !payload.tier) return null;
    if (payload.exp && Date.now() > payload.exp) return null;
    return payload;
  } catch (_) {
    return null;
  }
}
function monthKey(d) {
  d = d || new Date();
  return d.getUTCFullYear() + "-" + String(d.getUTCMonth() + 1).padStart(2, "0");
}
function nextMonthStart() {
  const d = new Date();
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 1, 0, 0, 0, 0);
}
async function readUsage(env, sub) {
  if (!env.BILLING_KV) return 0;
  const raw = await env.BILLING_KV.get("usage:" + sub);
  if (!raw) return 0;
  try {
    const o = JSON.parse(raw);
    if (o.month !== monthKey()) return 0; // 跨月自动归零
    return o.tokens || 0;
  } catch (_) {
    return 0;
  }
}
async function addUsage(env, sub, n) {
  if (!env.BILLING_KV) return;
  const m = monthKey();
  const raw = await env.BILLING_KV.get("usage:" + sub);
  let tokens = 0;
  if (raw) {
    try {
      const o = JSON.parse(raw);
      if (o.month === m) tokens = o.tokens || 0;
    } catch (_) {}
  }
  tokens += n;
  await env.BILLING_KV.put("usage:" + sub, JSON.stringify({ month: m, tokens }), { expirationTtl: 33 * 24 * 3600 });
}
async function getAcct(env, sub) {
  if (!env.BILLING_KV) return null;
  const raw = await env.BILLING_KV.get("acct:" + sub);
  if (!raw) return null;
  try { return JSON.parse(raw); } catch (_) { return null; }
}
async function setAcct(env, sub, tier) {
  if (!env.BILLING_KV) return;
  await env.BILLING_KV.put("acct:" + sub, JSON.stringify({ tier, createdAt: Date.now() }), { expirationTtl: 400 * 24 * 3600 });
}
function billingHeaders(b) {
  const h = new Headers();
  h.set("X-Billing-Used", String(b.used));
  h.set("X-Billing-Quota", String(b.quota));
  h.set("X-Billing-Remain", String(Math.max(0, b.quota - b.used)));
  h.set("X-Billing-Tier", b.tier);
  return h;
}
function estimateTokens(text) {
  return Math.ceil((text ? String(text).length : 0) / 4);
}

/* ---------------- 设备档案 / 套餐 / 人格云同步（/device/*） ----------------
 * 目标：
 *   1) 设备 ID、套餐（tier）、AI 分身人格档案存到 CF —— 软件卸载重装后能拉回来。
 *   2) 与 Android 端（jev-chat-jarvis）互通：Android 每个请求本来就会带
 *      X-WeAuto-Instance（设备 ID）+ Authorization: Bearer <账户令牌>，
 *      这套接口直接复用这两个头，**Android 端零改动即可接入**。
 *   3) 跨端人格互通：以「微信身份键 wxKey」为归属键。同一个微信号在
 *      Windows(EXE) 与 Android(APK) 上登录，读到的是同一份人格档案。
 *
 * 归属键优先级：wxKey（微信身份）> deviceId（设备）。
 *   两者都是单向哈希（客户端算好再上报），服务器拿不到 wxid/MAC 等原始值。
 *
 * 凭证（三选一，任一通过即可读写「自己那份」档案）：
 *   ① Authorization: Bearer <billing token>              —— Android 现成的账户令牌
 *   ② X-WeAuto-Key: <卡密> + X-WeAuto-Instance: <设备ID>  —— EXE 已激活用户
 *   ③ HMAC 签名头 X-WeAuto-Ts/Nonce/Sig（客户端共享密钥）  —— 兜底
 *   三个 secret 都没配时退化为「公开模式」（向后兼容未配置环境），靠 ownerKey 不可猜做隔离。
 *
 * 存储：复用 BILLING_KV（前缀 prof:），不新增 namespace，免改 wrangler.toml。
 * 注意：Workers 运行时没有 Buffer，只能用 btoa/atob + TextEncoder（同上）。
 */

const PROF_PREFIX = "prof:";
const PROF_TTL = 400 * 24 * 3600;
const DEVICE_ID_RE = /^[A-Za-z0-9_-]{4,64}$/;
const WXKEY_RE = /^wx_[a-f0-9]{16,64}$/;

const PERSONA_IDENTITY_FIELDS = [
  "nameCN", "title", "company", "city", "about", "companyDescription", "productDescription",
];

function ownerKeyOf(wxKey, deviceId) {
  const w = String(wxKey || "").trim();
  if (WXKEY_RE.test(w)) return "wx:" + w;
  const d = String(deviceId || "").trim();
  if (DEVICE_ID_RE.test(d)) return "dev:" + d;
  return "";
}

/** 归一不同套餐体系的展示信息（APK 令牌三档 / Creem 卡密三档）。 */
function planInfoFor(tier) {
  const t = String(tier || "").trim();
  if (!t) return null;
  if (TIERS[t]) {
    return { tier: t, name: TIERS[t].name, source: "apk_token", tokens: TIERS[t].tokens, price_text: TIERS[t].price_text };
  }
  const CREEM_LABEL = { normal: "初级套餐", premium: "中级套餐", lifetime: "高级套餐" };
  if (CREEM_LABEL[t]) return { tier: t, name: CREEM_LABEL[t], source: "creem_license" };
  return null;
}

async function readProfile(env, owner) {
  if (!env.BILLING_KV || !owner) return null;
  const raw = await env.BILLING_KV.get(PROF_PREFIX + owner);
  if (!raw) return null;
  try { return JSON.parse(raw); } catch (_) { return null; }
}

async function writeProfile(env, owner, prof) {
  if (!env.BILLING_KV || !owner) return false;
  prof.owner = owner;
  prof.updatedAt = Date.now();
  await env.BILLING_KV.put(PROF_PREFIX + owner, JSON.stringify(prof), { expirationTtl: PROF_TTL });
  return true;
}

function blankProfile(owner, wxKey) {
  return {
    owner, wxKey: wxKey || "", tier: "", plan: null,
    persona: null, personaRev: 0, devices: [],
    createdAt: Date.now(), updatedAt: Date.now(),
  };
}

function touchDevice(prof, deviceId, platform, appVer) {
  const id = String(deviceId || "").trim();
  if (!DEVICE_ID_RE.test(id)) return;
  const now = Date.now();
  prof.devices = Array.isArray(prof.devices) ? prof.devices : [];
  const hit = prof.devices.find((d) => d && d.id === id);
  if (hit) {
    hit.lastSeen = now;
    if (platform) hit.platform = String(platform).slice(0, 32);
    if (appVer) hit.appVer = String(appVer).slice(0, 32);
  } else {
    prof.devices.push({ id, platform: String(platform || "").slice(0, 32), appVer: String(appVer || "").slice(0, 32), firstSeen: now, lastSeen: now });
  }
  if (prof.devices.length > 10) {
    prof.devices = prof.devices.slice().sort((a, b) => (b.lastSeen || 0) - (a.lastSeen || 0)).slice(0, 10);
  }
}

/** 只保留白名单字段并限长，防止把整个硬盘塞进 KV。 */
function sanitizePersona(p) {
  if (!p || typeof p !== "object") return null;
  const srcId = (p.identity && typeof p.identity === "object") ? p.identity : {};
  const srcSt = (p.settings && typeof p.settings === "object") ? p.settings : {};
  const identity = {};
  for (const k of PERSONA_IDENTITY_FIELDS) identity[k] = String(srcId[k] ?? "").slice(0, 500);
  return {
    identity,
    settings: {
      enabled: !!srcSt.enabled,
      personaPrompt: String(srcSt.personaPrompt ?? "").slice(0, 4000),
      chatPurpose: String(srcSt.chatPurpose ?? "").slice(0, 500),
    },
    styleText: String(p.styleText ?? "").slice(0, 4000),
  };
}

function profState(prof) {
  const persona = prof.persona || null;
  return {
    ok: true,
    owner: prof.owner || "",
    wxKey: prof.wxKey || "",
    tier: prof.tier || "",
    plan: prof.plan || planInfoFor(prof.tier) || null,
    persona,
    personaRev: Number(prof.personaRev) || 0,
    personaUpdatedAt: Number((persona && persona.updatedAt) || prof.personaRev || 0),
    personaSource: (persona && persona.sourcePlatform) || "",
    devices: prof.devices || [],
    updatedAt: prof.updatedAt || 0,
  };
}

/** /device/* 的凭证校验：三选一通过即可。 */
async function deviceAuth(req, env, rawBody) {
  // ① 账户令牌（Android 现成的 Bearer token）
  const auth = String(req.headers.get("authorization") || "").trim();
  const bm = /^bearer\s+(.+)$/i.exec(auth);
  if (bm && env.WEAUATO_SIGN_SECRET) {
    const payload = await verifyBillingToken(env, bm[1].trim());
    if (payload) return { ok: true, kind: "token", sub: payload.sub || "", tier: payload.tier || "" };
  }
  // ② 卡密 + 设备 ID（EXE 已激活用户）
  const key = String(req.headers.get("x-weauto-key") || "").trim();
  const inst = String(req.headers.get("x-weauto-instance") || "").trim();
  if (key && inst && env.CREEM_API_KEY) {
    if (await licenseOk(env, key, inst, false)) return { ok: true, kind: "license", sub: "", tier: "" };
  }
  // ③ HMAC 签名（与 /ai/jev/decisions 同一套：X-WeAuto-Ts/Nonce/Sig）
  const secret = env.WEAUATO_CLIENT_SECRET || env.WEAUATO_SIGN_SECRET || "";
  if (secret && rawBody != null) {
    const ts = String(req.headers.get("x-weauto-ts") || "").trim();
    const nonce = String(req.headers.get("x-weauto-nonce") || "").trim();
    const sig = String(req.headers.get("x-weauto-sig") || "").trim();
    if (ts && nonce && sig) {
      const msg = [req.method, new URL(req.url).pathname, ts, nonce, rawBody].join("\n");
      if (await verifySignature(secret, msg, sig)) return { ok: true, kind: "hmac", sub: "", tier: "" };
    }
  }
  // ④ 公开模式：三个 secret 都没配（未配置环境）才放行，保持向后兼容
  if (!env.WEAUATO_SIGN_SECRET && !env.CREEM_API_KEY && !env.WEAUATO_CLIENT_SECRET) {
    return { ok: true, kind: "open", sub: "", tier: "" };
  }
  return { ok: false, kind: "none" };
}

async function deviceUnauthorized() {
  return json({ ok: false, error: "unauthorized", hint: "需要 Bearer 账户令牌 / 卡密 / HMAC 签名之一" }, 401);
}

/** 读设备档案（GET /device/state?device=&wx=）。 */
async function deviceState(req, env, url) {
  const owner = ownerKeyOf(url.searchParams.get("wx"), url.searchParams.get("device"));
  if (!owner) return json({ ok: false, error: "bad_identity" }, 400);
  // GET 没有 body，但 HMAC 签名仍要对「空 body」签名，故传 ""（null 会被当成未签名跳过校验）
  const auth = await deviceAuth(req, env, "");
  if (!auth.ok) return deviceUnauthorized();
  let prof = await readProfile(env, owner);
  if (!prof) prof = blankProfile(owner, url.searchParams.get("wx") || "");
  return json(profState(prof));
}

/** 注册/心跳 + 拉回档案（POST /device/hello）。 */
async function deviceHello(req, env, url) {
  const raw = await req.text();
  let body;
  try { body = JSON.parse(raw || "{}"); } catch (_) { return json({ ok: false, error: "bad_json" }, 400); }
  const wxKey = String(body.wxKey || url.searchParams.get("wx") || "").trim();
  const deviceId = String(body.deviceId || req.headers.get("x-weauto-instance") || "").trim();
  const owner = ownerKeyOf(wxKey, deviceId);
  if (!owner) return json({ ok: false, error: "bad_identity" }, 400);
  const auth = await deviceAuth(req, env, raw);
  if (!auth.ok) return deviceUnauthorized();

  let prof = (await readProfile(env, owner)) || blankProfile(owner, wxKey);
  if (wxKey && WXKEY_RE.test(wxKey)) prof.wxKey = wxKey;
  touchDevice(prof, deviceId, body.platform, body.appVer);

  // 云端套餐：令牌/卡密校验通过才有资格写入（不会被客户端随意篡改）
  const acct = await getAcct(env, auth.sub);
  let tier = auth.tier || (acct && acct.tier) || "";
  if (!tier && auth.kind === "license") {
    const lk = String(body.licenseKey || req.headers.get("x-weauto-key") || "").trim();
    const li = String(body.licenseInstanceId || deviceId).trim();
    if (lk && li && env.CREEM_API_KEY) {
      try {
        const { status, data } = await creemPost(env, "/licenses/validate", { key: lk, instance_id: li });
        if (status >= 200 && status < 300 && data && data.status === "active") tier = mapTier(env, data) || "";
      } catch (_) { /* 保持原 tier */ }
    }
  }
  if (tier) { prof.tier = tier; prof.plan = planInfoFor(tier); }

  await writeProfile(env, owner, prof);
  return json(Object.assign(profState(prof), { auth_kind: auth.kind, deviceId }));
}

/** 上传人格（PUT /device/persona）：rev 小于服务端则拒绝（防旧版本覆盖新版本）。 */
async function devicePutPersona(req, env, url) {
  const raw = await req.text();
  let body;
  try { body = JSON.parse(raw || "{}"); } catch (_) { return json({ ok: false, error: "bad_json" }, 400); }
  const wxKey = String(body.wxKey || url.searchParams.get("wx") || "").trim();
  const deviceId = String(body.deviceId || req.headers.get("x-weauto-instance") || "").trim();
  const owner = ownerKeyOf(wxKey, deviceId);
  if (!owner) return json({ ok: false, error: "bad_identity" }, 400);
  const auth = await deviceAuth(req, env, raw);
  if (!auth.ok) return deviceUnauthorized();

  const persona = sanitizePersona(body.persona);
  if (!persona) return json({ ok: false, error: "bad_persona" }, 400);
  // rev 用客户端毫秒时间戳；无脑四舍五入，非数字按 0 处理
  const rev = Number.isFinite(Number(body.rev)) ? Number(body.rev) : Date.now();

  let prof = (await readProfile(env, owner)) || blankProfile(owner, wxKey);
  if (wxKey && WXKEY_RE.test(wxKey)) prof.wxKey = wxKey;
  touchDevice(prof, deviceId, body.platform, body.appVer);
  if (rev < (Number(prof.personaRev) || 0)) {
    return json({ ok: false, error: "stale_rev", serverRev: Number(prof.personaRev) || 0 }, 409);
  }
  persona.updatedAt = rev;
  persona.sourcePlatform = String(body.platform || "").slice(0, 32);
  persona.sourceDevice = String(deviceId || "").slice(0, 64);
  prof.persona = persona;
  prof.personaRev = rev;
  await writeProfile(env, owner, prof);
  return json({ ok: true, personaRev: rev, owner });
}

/** 只拉人格（GET /device/persona?wx=&device=），跨端互通的读口。 */
async function deviceGetPersona(req, env, url) {
  const owner = ownerKeyOf(url.searchParams.get("wx"), url.searchParams.get("device"));
  if (!owner) return json({ ok: false, error: "bad_identity" }, 400);
  // GET 没有 body，但 HMAC 签名仍要对「空 body」签名，故传 ""（null 会被当成未签名跳过校验）
  const auth = await deviceAuth(req, env, "");
  if (!auth.ok) return deviceUnauthorized();
  const prof = await readProfile(env, owner);
  return json({
    ok: true,
    owner,
    persona: (prof && prof.persona) || null,
    personaRev: Number((prof && prof.personaRev) || 0),
  });
}

/** 写入/刷新套餐（PUT /device/plan）：必须拿卡密到 Creem 现验，客户端说了不算。 */
async function devicePutPlan(req, env, url) {
  const raw = await req.text();
  let body;
  try { body = JSON.parse(raw || "{}"); } catch (_) { return json({ ok: false, error: "bad_json" }, 400); }
  const wxKey = String(body.wxKey || url.searchParams.get("wx") || "").trim();
  const deviceId = String(body.deviceId || req.headers.get("x-weauto-instance") || "").trim();
  const owner = ownerKeyOf(wxKey, deviceId);
  if (!owner) return json({ ok: false, error: "bad_identity" }, 400);
  const auth = await deviceAuth(req, env, raw);
  if (!auth.ok) return deviceUnauthorized();
  const lk = String(body.licenseKey || req.headers.get("x-weauto-key") || "").trim();
  const li = String(body.licenseInstanceId || deviceId).trim();
  // 先判请求本身是否合法（400），再判服务端是否配好（503）
  if (!lk || !li) return json({ ok: false, error: "missing_license" }, 400);
  if (!env.CREEM_API_KEY) return json({ ok: false, error: "creem_not_configured" }, 503);
  const { status, data } = await creemPost(env, "/licenses/validate", { key: lk, instance_id: li });
  if (!(status >= 200 && status < 300) || !data || data.status !== "active") {
    return json({ ok: false, error: "license_not_active" }, 403);
  }
  const tier = mapTier(env, data) || "";
  let prof = (await readProfile(env, owner)) || blankProfile(owner, wxKey);
  if (wxKey && WXKEY_RE.test(wxKey)) prof.wxKey = wxKey;
  touchDevice(prof, deviceId, body.platform, body.appVer);
  prof.tier = tier;
  prof.plan = planInfoFor(tier);
  prof.license = { expiresAt: expiresToUnix(data.expires_at) || 0, updatedAt: Date.now() };
  await writeProfile(env, owner, prof);
  return json({ ok: true, tier, plan: prof.plan, expiresAt: prof.license.expiresAt });
}

/** /ai/v1 的计费拦截：校验令牌 -> 查额度 -> 转发 upstream -> 扣减 -> 回写计费头。 */
async function proxyAiBilled(req, env, url, token) {
  const payload = await verifyBillingToken(env, token);
  if (!payload) {
    return json({ error: { message: "账户令牌无效或已过期", type: "auth_error", code: "bad_token" } }, 401);
  }
  const tierInfo = TIERS[payload.tier] || TIERS[DEFAULT_TIER];
  const quota = tierInfo.tokens;
  const used = await readUsage(env, payload.sub);
  if (used >= quota) {
    return json({
      error: { message: "本月算力已用尽，请升级套餐或等待下月重置", type: "quota_error", code: "quota_exceeded", used, quota },
    }, 402);
  }
  const resp = await routeAi(req, env, url);

  // 计量：优先取响应的 usage.total_tokens；否则按字符数兜底估算
  let cost = 0;
  const ct = resp.headers.get("content-type") || "";
  if (resp.status === 200 && ct.includes("application/json") && !ct.includes("text/event-stream")) {
    try {
      const j = await resp.clone().json();
      cost = (j && j.usage && (j.usage.total_tokens || j.usage.totalTokens)) || 0;
    } catch (_) {}
  }
  if (!cost) {
    let ub = "";
    try { ub = await resp.clone().text(); } catch (_) {}
    cost = estimateTokens(ub);
  }
  cost = Math.max(1, Math.round(cost));
  await addUsage(env, payload.sub, cost);

  const h = billingHeaders({ used: used + cost, quota, tier: payload.tier });
  return new Response(resp.body, { status: resp.status, headers: h });
}

/* ---------------- 卡密网站（HTML） ---------------- */

function esc(s) {
  return String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  }[c]));
}

function html(body, status = 200) {
  return new Response(body, {
    status,
    headers: {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

function layout(title, inner) {
  return `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(title)}</title>
<style>
*{box-sizing:border-box}
body{margin:0;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;
background:#f5f7fa;color:#1f2937;line-height:1.65}
.wrap{max-width:680px;margin:0 auto;padding:28px 18px 48px}
.hd{background:linear-gradient(135deg,#1677ff,#4096ff);color:#fff;border-radius:14px;padding:26px 22px;text-align:center}
.hd h1{margin:0 0 6px;font-size:24px;font-weight:700}
.hd p{margin:0;opacity:.92;font-size:14px}
.card{background:#fff;border-radius:14px;padding:22px;margin-top:18px;box-shadow:0 2px 12px rgba(0,0,0,.06)}
.price{text-align:center;font-size:34px;font-weight:800;color:#1677ff;margin:6px 0 2px}
.price small{display:block;font-size:13px;color:#6b7280;font-weight:400;margin-top:4px}
.btn{display:block;width:100%;text-align:center;background:#1677ff;color:#fff;text-decoration:none;
font-size:17px;font-weight:700;padding:15px;border-radius:10px;margin-top:16px;border:none;cursor:pointer}
.btn:hover{background:#0958d9}
.btn.alt{background:#fff;color:#1677ff;border:2px solid #1677ff}
ol{padding-left:20px;margin:10px 0}
ol li{margin:8px 0}
.k{background:#f3f4f6;border:1px dashed #9ca3af;border-radius:8px;padding:10px 12px;
font-family:ui-monospace,Menlo,Consolas,monospace;font-size:13px;word-break:break-all}
.tag{display:inline-block;background:#e6f4ff;color:#0958d9;border-radius:20px;padding:3px 12px;font-size:12px;font-weight:600}
.tier .price{margin:8px 0 4px}
.feats{list-style:none;padding:0;margin:6px 0 14px;color:#374151;font-size:14px}
.feats li{padding:3px 0 3px 18px;position:relative}
.feats li:before{content:"✓";position:absolute;left:0;color:#1677ff;font-weight:700}
.warn{background:#fff7e6;border:1px solid #ffd591;border-radius:10px;padding:14px;margin-top:16px;font-size:14px;color:#874d00}
.foot{text-align:center;color:#9ca3af;font-size:12px;margin-top:26px;display:none}
code{background:#f3f4f6;padding:2px 6px;border-radius:4px;font-size:13px}
</style>
</head>
<body>
<div class="wrap">${inner}</div>
<div class="foot" style="margin-top:26px">
  <a href="/privacy" style="color:#374151;text-decoration:underline">Privacy Policy</a> ·
  <a href="/terms" style="color:#374151;text-decoration:underline">Terms of Service</a> ·
  Payments &amp; refunds handled by Creem (Merchant of Record)<br>
  <span style="color:#9ca3af">隐私政策 · 服务条款 · 支付与退款由 Creem 处理</span>
</div>
</body>
</html>`;
}

/** 隐私政策页。Creem 商户合规审查（account review）硬性要求。
 *  英文为主 + 中文对照（审核员为英文环境）。 */
function privacyPage(env) {
  const support = env.SUPPORT_EMAIL;
  const contact = support
    ? `<a href="mailto:${esc(support)}">${esc(support)}</a>`
    : "our support email";
  return html(layout("Privacy Policy · 隐私政策", `
<div class="hd"><h1>Privacy Policy <span style="color:#6b7280;font-size:18px">/ 隐私政策</span></h1>
<p>WeAuto · wetech.jukuai.net · Last updated: 2026-10-02</p></div>

<div class="card">
  <h3 style="margin-top:0">1. What We Collect（我们收集什么）</h3>
  <p>WeAuto is desktop software that runs <b>on your own computer</b>. To provide licensing and
  payment services, we collect:</p>
  <ul>
    <li><b>License key &amp; device instance ID</b> — to validate your license and enforce device limits.</li>
    <li><b>Email address</b> — provided by our payment processor Creem, used only for receipts and license delivery.</li>
    <li><b>AI usage counters</b> — token consumption numbers only. <b>We never record chat content.</b></li>
  </ul>
</div>
<div class="card">
  <h3 style="margin-top:0">2. What We Do NOT Collect（我们不收集什么）</h3>
  <p>This website does <b>not</b> store or upload your WeChat chat history, contacts, images, or voice
  messages. AI reply requests go directly from your local client to the model gateway; this site only
  performs license validation and usage quota counting.</p>
</div>
<div class="card">
  <h3 style="margin-top:0">3. Payment Data &amp; Third Parties（支付数据与第三方）</h3>
  <p>Payments and refunds are handled by <b>Creem (Merchant of Record)</b>. Card/payment details are
  processed by Creem and never touch our servers — see
  <a href="https://www.creem.io/privacy" rel="noopener">creem.io/privacy</a>.
  We do not sell your data to anyone.</p>
</div>
<div class="card">
  <h3 style="margin-top:0">4. Data Retention &amp; Deletion（数据保留与删除）</h3>
  <p>License and device records are kept while your license is active. To delete your data
  (license key / device ID / email), contact ${contact} and we will remove it within 7 days.</p>
</div>
<div class="card">
  <h3 style="margin-top:0">隐私政策（中文摘要）</h3>
  <p style="color:#374151;font-size:14px">WeAuto 运行于你自己的电脑。为提供授权与付费服务，我们仅收集：
  卡密与设备实例 ID、由 Creem 提供的邮箱、AI 用量计数（不含聊天内容）。本站不存储/不上传微信聊天记录、
  联系人、图片或语音。支付与退款由 Creem（Merchant of Record）处理，卡片信息不经我方服务器。
  我们不出售你的数据。如需删除授权数据，请联系客服邮箱，7 天内处理。</p>
</div>`));
}

/** 服务条款页。Creem 审查点名三要素：subscription terms / refund policy / license usage rules。
 *  英文为主 + 中文对照。 */
function termsPage(env) {
  return html(layout("Terms of Service · 服务条款", `
<div class="hd"><h1>Terms of Service <span style="color:#6b7280;font-size:18px">/ 服务条款</span></h1>
<p>WeAuto · Effective date: 2026-10-02</p></div>

<div class="card">
  <h3 style="margin-top:0">1. The Service（服务内容）</h3>
  <p>WeAuto is desktop automation software for WeChat, licensed per device. After purchase you
  activate it with a license key on the number of devices stated on the product page.</p>
</div>
<div class="card">
  <h3 style="margin-top:0">2. Subscription Terms（订阅条款）</h3>
  <p>Monthly plans are <b>auto-renewing subscriptions</b>, billed each month until cancelled.
  You may cancel anytime from the Creem receipt email or the Creem customer portal; access continues
  until the end of the current billing period. One-time (lifetime) plans never renew.</p>
</div>
<div class="card">
  <h3 style="margin-top:0">3. Refund Policy（退款政策）</h3>
  <ul>
    <li><b>One-time purchases</b>: full refund within 7 days if activation fails and the issue
    cannot be resolved by our support.</li>
    <li><b>Monthly subscriptions</b>: the current period is generally non-refundable; duplicate or
    erroneous charges are refunded in full after verification.</li>
    <li>Refunds are issued via Creem (Merchant of Record) to the original payment method.</li>
  </ul>
</div>
<div class="card">
  <h3 style="margin-top:0">4. License Usage Rules（许可使用规则）</h3>
  <p>The license is personal to you. You may <b>not</b> resell, share your license key, reverse
  engineer, circumvent license validation, or use the software for any unlawful purpose (including
  harassment, fraud, or spam). Violation allows us to terminate the license without refund.</p>
</div>
<div class="card">
  <h3 style="margin-top:0">5. Disclaimer &amp; Acceptable Use（免责声明）</h3>
  <p>The software is provided "as is". You are responsible for complying with the WeChat Terms of
  Service when using automation features; account restrictions caused by misuse are your own risk.</p>
</div>
<div class="card">
  <h3 style="margin-top:0">服务条款（中文摘要）</h3>
  <p style="color:#374151;font-size:14px">WeAuto 为按设备授权的本机软件。三档（初级 / 中级 / 高级 套餐费）均为
  <b>一次性购买</b>，购一次获得对应档位 <b>valid_days 天</b>授权，到期前软件会在授权面板与微信里提示续费，
  续费需再次购买。可随时在 Creem 收据邮件 / 客户门户查看订单；一次性购买原则上不自动续订、不重复扣款。
  退款：购买后 7 天内且激活遇无法解决的技术问题可全额退款；重复 / 误扣全额退。
  许可规则：禁止转售、共享卡密、逆向工程、绕过校验、用于违法违规用途，违者终止授权不予退款。
  使用自动化功能请遵守微信用户协议。</p>
</div>`));
}

/** 购买落地页（多档套餐）
 *  @param {string} mid  本机能力令牌（高熵随机），用于把付款回调的卡密关联到这台机器，
 *                        实现「付款后自动同步卡密、无需手动复制粘贴」。
 */
function buyPage(env, origin, mid, email) {
  const products = parseProducts(env);
  const title = env.SITE_TITLE || "WeAuto 授权中心";
  const support = env.SUPPORT_EMAIL;
  // mid/email 都要往每张卡的「立即购买」链接上带：买家可能先看列表再点某一档，
  // 参数在这一跳丢掉就白带了（收银台邮箱又变成空的）。
  const carry = (mid ? "&mid=" + encodeURIComponent(mid) : "") +
    (looksLikeEmail(email) ? "&email=" + encodeURIComponent(email.trim()) : "");

  const cards = products.map((p) => {
    const badge = p.billing === "monthly" ? "月租订阅" : "一次性套餐";
    const go = origin + "/buy?go=1&tier=" + encodeURIComponent(p.tier) + carry;
    const feats = (p.features || "").split(/[+；;]/).map((f) => f.trim()).filter(Boolean);
    const featsHtml = feats.length
      ? '<ul class="feats">' + feats.map((f) => `<li>${esc(f)}</li>`).join("") + "</ul>"
      : "";
    return `<div class="card tier">
      <span class="tag">${esc(badge)}</span>
      <h3 style="margin:14px 0 0">${esc(p.label)}</h3>
      <div class="price">${esc(p.price_text)}</div>
      ${featsHtml}
      <a class="btn" href="${esc(go)}">支付宝付款 · 立即购买</a>
    </div>`;
  }).join("");

  return layout(title, `
<div class="hd">
  <h1>${esc(title)}</h1>
  <p>微信机器人 · 官方授权</p>
</div>
${cards}
<div class="card">
  <h3 style="margin-top:0">购买后怎么用</h3>
  <ol>
    <li>选上方档位点「立即购买」，在收银台用 <b>支付宝</b>或银行卡完成付款。</li>
    <li><b>什么都不用做</b>：付款成功后本机会自动获取授权并立即生效。</li>
  </ol>
</div>
<div class="card">
  <h3 style="margin-top:0">常见问题</h3>
  <p><b>一台电脑能用几次？</b><br>按产品设置的设备数（通常 1–2 台）。换机前请在授权管理里「释放本机」。</p>
  <p><b>套餐费是订阅吗？会自动扣费吗？</b><br>不会。三档（初级/中级/高级 套餐费）均为<b>一次性购买</b>，付款即得对应天数授权（30 天 / 30 天 / 365 天），<b>不自动续费、不二次扣款</b>。到期前本软件会在授权面板与微信里提示续费，续费需再次购买。</p>
  <p><b>断网会失效吗？</b><br>不会。首次激活后有 7 天离线宽限。</p>
  <p><b>卡密丢了？</b><br>不用找卡密：本机自动核销。若要凭据（如换机），付款邮箱里有 Creem 收据与卡密${support ? `；也可联系 <a href="mailto:${esc(support)}">${esc(support)}</a>` : ""}。</p>
</div>
<div class="foot">本页由 WeAuto License Worker 提供 · 支付由 Creem（Merchant of Record）处理</div>`);
}

/** 付款完成后的引导页 */
function successPage(env) {
  const name = env.PRODUCT_NAME || "WeAuto 授权";
  const support = env.SUPPORT_EMAIL;
  return layout("付款成功", `
<div class="hd"><h1>付款成功</h1><p>感谢购买 ${esc(name)}</p></div>
<div class="card">
  <h3 style="margin-top:0">下一步</h3>
  <ol>
    <li><b>什么都不用做</b>：本机会自动获取授权并立即生效，无需复制粘贴卡密。</li>
    <li>若几秒后仍未生效，回到软件点一下「刷新授权状态」即可。</li>
  </ol>
  ${support ? `<p style="color:#6b7280;font-size:13px">没收到卡密？联系 <a href="mailto:${esc(support)}">${esc(support)}</a></p>` : ""}
</div>
<div class="foot">支付与开票由 Creem 处理</div>`);
}

/** 商家还没配置好 / 上游不可用时的友好提示（替代原来的 500 裸文本）。返回 Response，状态码 503。
 *  @param {string} note  可选的额外说明（如上游临时不可达），展示给买家看。
 */
function notConfiguredPage(note) {
  const extra = note
    ? `<p style="color:#874d00">${esc(note)}</p>`
    : "";
  return html(layout("暂未开放购买", `
<div class="hd"><h1>暂未开放购买</h1><p>商家正在配置支付</p></div>
<div class="card">
  <p>本授权站点尚未完成支付配置，暂时无法购买。</p>
  ${extra}
  <p style="color:#6b7280;font-size:14px">如果你是站点管理员：请在 <code>wrangler.toml</code> 的
  <code>[vars]</code> 中配置 <code>CREEM_PRODUCTS</code>（JSON 数组，含各档 product_id），
  并用 <code>wrangler secret put CREEM_API_KEY</code> 注入 API key，
  然后重新 <code>wrangler deploy</code>。</p>
</div>`), 503);
}

/** 极简邮箱校验：只放行形状正确的地址，避免把任意字符串塞进 Creem 的 customer.email。
 *  宽松到足以容纳 a.b+tag@x.co.uk /  punycode 等合法形态。 */
function looksLikeEmail(s) {
  if (!s || typeof s !== "string") return false;
  if (s.length > 254 || /\s/.test(s)) return false;
  return /^[^@]+@[^@.]+(\.[^@.]+)+$/.test(s.trim());
}

/** 按档位解析收银台地址（动态创建 checkout 会话，用 Creem 原生页）。
 *  @param {string} mid  本机能力令牌，写入 checkout metadata，使付款回调能关联到这台机器。
 *  @param {string} email 买家邮箱：**预填并锁定**收银台邮箱框，付款零输入。
 *      Creem 是 MoR（Merchant of Record），邮箱是收据/税务的法定必填项，
 *      官方没有「隐藏邮箱字段」的开关——能做的只有提前塞进去让买家不用手打。
 *      缺失/不合法时静默跳过，退回原行为（收银台留空由买家自己填）。
 *
 *  注意：**不做跨请求缓存**。checkout 会话是「一次性的买家私有资源」，
 *  早先的 60s 全局缓存会让第二个买家拿到第一个人的 checkout（提示已使用/串号）。
 *  每次请求现建（成本只是一次 API 调用）；若商家配了静态 CREEM_CHECKOUT_URL 则直接复用它。
 */
async function resolveCheckout(env, tier, mid, email) {
  // 静态收银台链接：无需 API、无需新建，直接跳转
  if (env.CREEM_CHECKOUT_URL) return env.CREEM_CHECKOUT_URL;
  const products = parseProducts(env);
  const p = products.find((x) => x.tier === tier) || products[0];
  if (!p || !p.product_id || !env.CREEM_API_KEY) return null;
  // 不传 success_url：用 Creem 原始链接/原生成功页（用户要求，不做自定义跳转）。
  // 通过 metadata.mid 把「这台机器」带进支付流，付款后 webhook 回带 mid → 自动核销。
  const metadata = { source: "weauto-license-worker", tier: p.tier };
  if (mid) metadata.mid = mid;
  const payload = { product_id: p.product_id, metadata };
  const mail = looksLikeEmail(email) ? email.trim() : "";
  if (mail) payload.customer = { email: mail };
  const res = await creemPost(env, "/checkouts", payload);
  if (upstreamError(res)) return null; // 路由层兜底成 503
  if (res.status >= 200 && res.status < 300 && res.data && res.data.checkout_url) {
    return res.data.checkout_url;
  }
  console.error("create checkout failed:", res.status, JSON.stringify(res.data).slice(0, 200));
  return null;
}

/* ---------------- Jev 判断式 AI（Workers AI JSON Mode，不走 OpenRouter） ----------------
 *
 * 技术来源：TypeSafe Jev（jev-chat-jarvis 的 tools/jev）。Jev 不是聊天模型，
 * 而是「判断模型」：喂 state + questions，返回结构化判断，题型只有三种：
 *   noul   是非    -> { noul: 0..1 }
 *   choice 单选    -> { choice: "<criteria 里的 key>" }
 *   score  打分    -> { score: 0..bins-1 }
 * 原实现走 OpenRouter 的 /alpha/decisions；本项目改为在 Worker 内部用
 * Cloudflare Workers AI 的 JSON Mode（response_format.json_schema）跑同一套题目，
 * 返回结构维持 { answers: {...} } 兼容，客户端解析逻辑零改动。
 * 好处：不出 Cloudflare、不需要 OpenRouter 账号与美元结算、延迟低。
 *
 * 端点：POST /ai/jev/decisions
 *   鉴权（防白嫖）：HMAC-SHA256 客户端签名，密钥 = env.WEAUATO_CLIENT_SECRET。
 *        —— 这是「自己的客户端 ↔ 自己的 CF 后台」之间的通信鉴权，与 Creem 卡密无关。
 *           卡密（CREEM_LICENSE_KEY）只服务 Creem 支付授权与 /ai/v1 代理。
 *       规则：Worker 端若配置了 WEAUATO_CLIENT_SECRET，则所有请求必须带
 *         X-WeAuto-Ts（unix 秒，±60s 内有效，防重放）
 *         X-WeAuto-Nonce（随机 hex）
 *         X-WeAuto-Sig = HMAC_SHA256(密钥, "POST\n<path>\n<ts>\n<nonce>\n<body>")
 *       否则 401。未配置该 secret 时端点保持公开（开发/向后兼容）。
 *       客户端（EXE）端同源：填了 WEAUATO_CLIENT_SECRET 才带签名。
 *   body：{ model?, state:{chat:{relationship,messages:[{from,text}],latest_from}},
 *           questions:{ <key>: {type,instructions,criteria} } }
 *   响应：{ answers:{...}, model, provider:"cloudflare-workers-ai" }
 */

const JEV_MODEL_DEFAULT = "@cf/meta/llama-3.1-8b-instruct";

/** Jev questions -> JSON Schema（Workers AI JSON Mode 要求合法 schema） */
function jevJsonSchema(questions) {
  const properties = {};
  const required = [];
  for (const key of Object.keys(questions || {})) {
    const q = questions[key] || {};
    const desc = String(q.instructions || key).slice(0, 900);
    if (q.type === "noul") {
      properties[key] = { type: "boolean", description: desc };
    } else if (q.type === "choice") {
      const opts = Object.keys(q.criteria || {});
      if (!opts.length) continue;
      properties[key] = { type: "string", enum: opts, description: desc };
    } else if (q.type === "score") {
      const bins = Array.isArray(q.criteria) ? q.criteria.length : 10;
      properties[key] = {
        type: "integer",
        minimum: 0,
        maximum: Math.max(1, bins - 1),
        description: desc,
      };
    } else {
      continue; // 未知题型直接跳过，绝不因此让整批判断全部失败
    }
    required.push(key);
  }
  return { type: "object", properties, required };
}

/** system 提示：题型 + 判定标准（criteria 原文照抄；聊天正文保持中文原样） */
function jevSystemPrompt(questions, state) {
  const rel = (state && state.chat && state.chat.relationship) || "unknown";
  const lines = [
    "You are a judgement model. You NEVER write replies or chat text.",
    "Answer every question strictly from the conversation, using the criteria given.",
    "Instructions and criteria are authoritative; even when uncertain, answer with the closest option.",
    "",
    "Relationship: " + rel,
    "",
    "Questions (answer every one):",
  ];
  for (const key of Object.keys(questions || {})) {
    const q = questions[key] || {};
    lines.push("", `[${key}] type=${q.type}`, "instructions: " + String(q.instructions || ""));
    const c = q.criteria;
    if (q.type === "score" && Array.isArray(c)) {
      lines.push("bins (index -> meaning):");
      c.forEach((b, i) => lines.push(`  ${i}: ${b}`));
    } else if (c && typeof c === "object") {
      lines.push("options (value -> meaning):");
      for (const k of Object.keys(c)) lines.push(`  ${k}: ${c[k]}`);
    }
  }
  return lines.join("\n");
}

function jevUserPrompt(state) {
  const chat = (state && state.chat) || {};
  const msgs = Array.isArray(chat.messages) ? chat.messages.slice(-20) : [];
  const out = msgs
    .map((m) => `${m && m.from === "me" ? "ME" : "THEM"}: ${String((m && m.text) || "")}`)
    .join("\n");
  return (
    "Conversation (oldest first):\n" +
    out +
    "\n\nLatest message is from: " +
    String(chat.latest_from || "them")
  );
}

/** Workers AI 的返回形态不止一种，实测见到过三种，必须都能吃下：
 *   a) { response: {...} }                              —— JSON Mode 典型返回
 *   b) { choices: [{ message: { content: "<json>" } }] } —— chat completions 风格
 *      （部分模型/路由即使传了 response_format 也走这个形态，实测 8B 如此）
 *   c) 直接就是答案对象
 * 返回解析后的对象；实在解析不出来返回 null。
 */
function jevExtractAnswers(res) {
  if (!res || typeof res !== "object") return null;
  let raw = res;
  if (res.response !== undefined) raw = res.response;
  else if (res.choices && res.choices[0] && res.choices[0].message) {
    raw = res.choices[0].message.content;
  }
  if (typeof raw !== "string") return raw && typeof raw === "object" ? raw : null;
  try {
    return JSON.parse(raw);
  } catch (_) {
    const m = raw.match(/\{[\s\S]*\}/); // 少数模型会裹一层 ```json 代码块
    if (!m) return null;
    try {
      return JSON.parse(m[0]);
    } catch (_2) {
      return null;
    }
  }
}

/** HMAC-SHA256，返回十六进制串（Workers AI 同源的 Web Crypto 实现） */
async function hmacHex(secret, msgBytes) {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const sig = await crypto.subtle.sign("HMAC", key, msgBytes);
  return Array.from(new Uint8Array(sig))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

/** 常量时间字符串比较，避免时序侧信道 */
function timingSafeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let r = 0;
  for (let i = 0; i < a.length; i++) r |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return r === 0;
}

async function jevDecisions(req, env) {
  if (req.method !== "POST") {
    return json({ error: { message: "POST required", type: "invalid_request", code: "method_not_allowed" } }, 405);
  }
  if (!env.AI) {
    return json(
      {
        error: {
          message: "Worker 未绑定 Workers AI：wrangler.toml 需加 [ai] binding = \"AI\" 后重新 deploy",
          type: "server_misconfigured",
          code: "ai_binding_missing",
        },
      },
      503
    );
  }

  // ---- 鉴权 / 计费分流 ----
  // 1) APK 账户令牌（token 计费）：自校验签名 + 云端记账；与 EXE 客户端签名并存
  let billed = null;
  const authHdr = (req.headers.get("authorization") || "").trim();
  const jt = authHdr.startsWith("Bearer ") ? authHdr.slice(7).trim() : authHdr;
  if (jt && looksLikeBillingToken(jt) && env.WEAUATO_SIGN_SECRET) {
    const p = await verifyBillingToken(env, jt);
    if (!p) return json({ error: { message: "账户令牌无效或已过期", type: "auth_error", code: "bad_token" } }, 401);
    const ti = TIERS[p.tier] || TIERS[DEFAULT_TIER];
    const used = await readUsage(env, p.sub);
    if (used >= ti.tokens) return json({ error: { message: "本月算力已用尽，请升级套餐或等待下月重置", type: "quota_error", code: "quota_exceeded", used, quota: ti.tokens } }, 402);
    await addUsage(env, p.sub, JEV_COST);
    billed = { used: used + JEV_COST, quota: ti.tokens, tier: p.tier };
  }

  // 2) 读取 body（计费路径已校验通过；其余走 EXE 客户端签名 / 公开逻辑）
  let body = null;
  const secret = env.WEAUATO_CLIENT_SECRET;
  if (billed === null && secret) {
    const ts = req.headers.get("x-weauto-ts");
    const nonce = req.headers.get("x-weauto-nonce");
    const sig = req.headers.get("x-weauto-sig");
    if (!ts || !nonce || !sig) {
      return json(
        { error: { message: "缺少客户端签名头（X-WeAuto-Ts / X-WeAuto-Nonce / X-WeAuto-Sig）", type: "auth_error", code: "missing_sig" } },
        401
      );
    }
    const tn = parseInt(ts, 10);
    const now = Math.floor(Date.now() / 1000);
    if (!Number.isFinite(tn) || Math.abs(now - tn) > 60) {
      return json({ error: { message: "签名时间戳过期（±60s 有效）", type: "auth_error", code: "ts_expired" } }, 401);
    }
    let raw;
    try {
      raw = await req.text();
    } catch (_) {
      return json({ error: { message: "无法读取请求体", type: "invalid_request", code: "bad_body" } }, 400);
    }
    const enc = new TextEncoder();
    const head = enc.encode("POST\n" + new URL(req.url).pathname + "\n" + ts + "\n" + nonce + "\n");
    const tail = enc.encode(raw);
    const full = new Uint8Array(head.length + tail.length);
    full.set(head, 0);
    full.set(tail, head.length);
    const expect = await hmacHex(secret, full);
    if (expect.length !== sig.length || !timingSafeEqual(expect, sig)) {
      return json({ error: { message: "客户端签名校验失败", type: "auth_error", code: "bad_sig" } }, 401);
    }
    try {
      body = JSON.parse(raw);
    } catch (_) {
      return json({ error: { message: "body 不是合法 JSON", type: "invalid_request", code: "bad_json" } }, 400);
    }
  } else {
    try {
      body = await req.json();
    } catch (_) {
      return json({ error: { message: "body 不是合法 JSON", type: "invalid_request", code: "bad_json" } }, 400);
    }
  }
  const questions = (body && body.questions) || {};
  const state = (body && body.state) || {};
  const schema = jevJsonSchema(questions);
  if (!schema.required.length) {
    return json(
      { error: { message: "questions 为空或题型不支持（只支持 noul/choice/score）", type: "invalid_request", code: "bad_questions" } },
      400
    );
  }

  const model = (body && body.model) || env.JEV_MODEL || JEV_MODEL_DEFAULT;
  let out = null;
  try {
    const res = await env.AI.run(model, {
      messages: [
        { role: "system", content: jevSystemPrompt(questions, state) },
        { role: "user", content: jevUserPrompt(state) },
      ],
      response_format: { type: "json_schema", json_schema: schema },
    });
    out = jevExtractAnswers(res);
  } catch (e) {
    return json(
      { error: { message: "Workers AI 调用失败：" + String((e && e.message) || e), type: "upstream_error", code: "ai_run_failed" } },
      502
    );
  }
  if (!out || typeof out !== "object") {
    return json(
      { error: { message: "Workers AI 未返回可解析的 JSON（JSON Mode 未满足）", type: "upstream_error", code: "unparsable" } },
      502
    );
  }

  // 归一成 Jev 原版的 answers 结构，客户端解析逻辑无需区分后端
  const answers = {};
  for (const k of Object.keys(questions)) {
    const q = questions[k] || {};
    const v = out[k];
    if (v === undefined || v === null) continue;
    if (q.type === "noul") answers[k] = { noul: v === true || v === "true" ? 1 : 0 };
    else if (q.type === "choice") answers[k] = { choice: String(v) };
    else if (q.type === "score") {
      const n = Number(v);
      if (Number.isFinite(n)) answers[k] = { score: n };
    }
  }
  if (billed) {
    return json({ answers, model, provider: "cloudflare-workers-ai" }, 200, billingHeaders(billed));
  }
  return json({ answers, model, provider: "cloudflare-workers-ai" });
}

/* ---------------- 路由 ---------------- */

export default {
  async fetch(req, env, ctx) {
    const url = new URL(req.url);
    const p = url.pathname;

    // ---- 健康检查 / 配置自检（不泄露密钥）----
    if (p === "/health") {
      const products = parseProducts(env);
      const ready = products.some((x) => x.product_id);
      return json({
        ok: true,
        service: "weauto-license",
        mode: env.CREEM_MODE || "test",
        has_api_key: !!env.CREEM_API_KEY,
        has_webhook_secret: !!env.CREEM_WEBHOOK_SECRET,
        creem_products: products.map((x) => ({ tier: x.tier, billing: x.billing, product_id: x.product_id, valid_days: x.valid_days, voluntary: x.voluntary })),
        checkout_ready: ready,
        has_ai_upstream: aiAvailable(env),
        ai_ready: !!(env.CREEM_API_KEY && aiAvailable(env)),
        workers_ai_ready: !!env.AI,
        volcano_ready: volcanoConfigured(env),
        // Jev 判断：Workers AI binding + 模型（binding 由 wrangler 注入，不在 vars 里）
        jev_ready: !!env.AI,
        jev_model: env.JEV_MODEL || JEV_MODEL_DEFAULT,
        // 是否启用了客户端签名防白嫖：配置了 WEAUATO_CLIENT_SECRET 即为 true
        jev_require_auth: !!env.WEAUATO_CLIENT_SECRET,
        // token 计费
        billing_enabled: !!env.WEAUATO_SIGN_SECRET,
        billing_kv: !!env.BILLING_KV,
        // 设备档案 / 套餐 / 人格云同步（/device/*）
        device_sync_ready: !!env.BILLING_KV,
        device_auth_mode: (env.WEAUATO_SIGN_SECRET || env.CREEM_API_KEY || env.WEAUATO_CLIENT_SECRET) ? "strict" : "open",
        default_tier: DEFAULT_TIER,
        tiers: Object.keys(TIERS),
      });
    }

    // ---- EXE 不受信任服务商黑名单：/black（自建，替代原 vg 的 /black）----
    // 返回需要被 EXE 阻断的 API 网关域名/关键字列表（小写子串匹配）。
    // 当前为空：自有 Worker 网关始终可信；若发现泄露/恶意网关域名可在此下发。
    if (p === "/black") {
      return json({ data: [] });
    }

    // ---- Android APK 自动更新代理：/api/update ----
    // 本端点用 secret 里的 GITHUB_TOKEN 查最新 CI 构件，返回一个「免 GitHub 鉴权」
    // 的直链（GitHub 重定向到的 azure SAS URL）。客户端零密钥即可升级，
    // token 仅在服务端，可随时轮换、不需重发客户端。
    // Android artifact 名带 run 序号（如 jev-chat-jarvis-apk-1294），故用前缀匹配。
    // 默认前缀 jev-chat-jarvis-apk*，可用 env UPDATE_APK_ARTIFACT_PREFIX 覆盖。
    // 注意：这里用独立的 env（不看 UPDATE_ARTIFACT_NAME），避免被 Windows 的配置带偏。
    if (p === "/api/update") {
      return updateProxy(req, env, env.UPDATE_APK_ARTIFACT_PREFIX || "jev-chat-jarvis-apk*");
    }

    // ---- /api/update-android：/api/update 的显式别名，语义更清楚 ----
    if (p === "/api/update-android") {
      return updateProxy(req, env, env.UPDATE_APK_ARTIFACT_PREFIX || "jev-chat-jarvis-apk*");
    }

    // ---- Windows EXE 更新：/api/update-windows ----
    // Windows 客户端由他人维护。/api/update 已改为返回 Android APK，
    // 为避免 Windows 端升级断供，保留此专属端点：Windows 侧请改用本端点
    // （仍可用 env UPDATE_ARTIFACT_NAME 覆盖构件名，默认 weauto-windows-exe）。
    if (p === "/api/update-windows") {
      return updateProxy(req, env, env.UPDATE_ARTIFACT_NAME || "weauto-windows-exe");
    }

    // ---- Jev 判断式 AI：/ai/jev/decisions（Workers AI JSON Mode，必须排在 /ai/ 通配之前）----
    if (p === "/ai/jev/decisions") {
      return jevDecisions(req, env);
    }

    // ---- AI 代理：/ai/v1/* -> upstream（卡密即凭证，反破解核心）----
    if (p === "/ai" || p.startsWith("/ai/")) {
      return proxyAi(req, env, url);
    }

    // ---- 套餐与账户令牌（token 计费，APK 专用）----
    if (p === "/plans" && req.method === "GET") {
      return json({
        plans: Object.entries(TIERS).map(([k, v]) => ({
          tier: k, name: v.name, compute: v.compute, tokens: v.tokens,
          price_cny: v.price_cny, price_text: v.price_text,
        })),
      });
    }

    if (p === "/token/status" && req.method === "GET") {
      const tok = url.searchParams.get("token") || "";
      const p2 = (env.WEAUATO_SIGN_SECRET) ? await verifyBillingToken(env, tok) : null;
      if (!p2) return json({ ok: false, error: "invalid_token" }, 401);
      const ti = TIERS[p2.tier] || TIERS[DEFAULT_TIER];
      const used = await readUsage(env, p2.sub);
      return json({
        ok: true, sub: p2.sub, tier: p2.tier, name: ti.name,
        quota: ti.tokens, used, remain: Math.max(0, ti.tokens - used),
        reset_at: nextMonthStart(),
      });
    }

    if (p === "/token/issue" && req.method === "POST") {
      // fail-close：必须配置 ADMIN_KEY 且请求头匹配，否则一律拒绝（否则谁都能发令牌白嫖）
      if (!env.ADMIN_KEY || req.headers.get("x-admin-key") !== env.ADMIN_KEY) {
        return json({ ok: false, error: "forbidden" }, 403);
      }
      if (!env.WEAUATO_SIGN_SECRET) return json({ ok: false, error: "billing_not_configured" }, 503);
      if (!env.BILLING_KV) return json({ ok: false, error: "billing_kv_missing" }, 503);
      let body;
      try { body = await req.json(); } catch (_) { return json({ ok: false, reason: "bad_json" }, 400); }
      const tier = TIERS[body.tier] ? body.tier : DEFAULT_TIER;
      const sub = (body.sub && /^[A-Za-z0-9_-]{3,}$/.test(body.sub)) ? body.sub : ("u_" + randHex(12));
      const months = Math.max(1, Math.min(12, Number(body.months) || 1));
      await setAcct(env, sub, tier);
      const exp = Date.now() + months * 30 * 24 * 3600 * 1000;
      const token = await signBillingToken(env, { sub, tier, exp });
      const ti = TIERS[tier];
      return json({ ok: true, token, sub, tier, name: ti.name, quota: ti.tokens, months });
    }

    /* ---- Creem 产品价格查询 / 校准（一次性运维接口）----
     *
     * 为什么需要它：Creem 的 API key 只存在于 Worker secret（`wrangler secret put
     * CREEM_API_KEY`），**本机没有副本**。而 wrangler.toml 的 `price_text`
     * （购买页展示价）与 Creem 实际 `price`（收银台收费）**毫无联动** ——
     * 2026-10-03 建档时算错，导致页面写 ¥29.9、线上实收 $1.01，每单少收 ¥23。
     * 所以「改实收价」必须由持有 key 的 Worker 来发。
     *
     * 安全：同 /token/issue 的 fail-close 守卫 —— 必须配了 ADMIN_KEY 且
     * `x-admin-key` 头匹配才放行，否则任何人改了价格。GET 只读，POST 动钱。
     * 用法（改完价后可以把这个分支删掉重部署）：
     *   curl -H "x-admin-key: $ADMIN_KEY" https://wetech.jukuai.net/creem/price
     *   curl -X POST -H "x-admin-key: $ADMIN_KEY" -H "content-type: application/json" \
     *        -d '{"confirm":true}' https://wetech.jukuai.net/creem/price
     */
    if (p === "/creem/price") {
      // 守卫：ADMIN_KEY（长期运维钥匙，本机无副本）**或** 一次性 nonce（哈希比对）。
      // nonce 明文只在部署者的本地，用完即焚；哈希留在代码里也拿不到明文。
      const key = req.headers.get("x-admin-key") || "";
      const nonce = req.headers.get("x-onetime-nonce") || "";
      const nonceOk = !!ONETIME_NONCE_SHA256 && (await sha256hex(nonce)) === ONETIME_NONCE_SHA256;
      if (!((env.ADMIN_KEY && key === env.ADMIN_KEY) || nonceOk)) {
        return json({ ok: false, error: "forbidden" }, 403);
      }
      if (!env.CREEM_API_KEY) return json({ ok: false, error: "creem_not_configured" }, 503);

      // 目标价来自 CREEM_PRODUCTS 里新增的 usd_cents 字段（唯一权威，改 wrangler.toml 即可）
      const targets = parseProducts(env).map((x) => ({
        tier: x.tier,
        product_id: x.product_id,
        price_text: x.price_text || "",
        target_cents: Number.isInteger(x.usd_cents) ? x.usd_cents : null,
      })).filter((x) => x.product_id);

      if (req.method === "GET") {
        const out = [];
        for (const t of targets) {
          const r = await creemReq(env, "GET", "/products/" + t.product_id);
          const cur = (r.status === 200 && Number.isInteger(r.data.price)) ? r.data.price : null;
          out.push({
            tier: t.tier,
            product_id: t.product_id,
            price_text: t.price_text,
            target_cents: t.target_cents,
            live_cents: cur,
            live_usd: cur === null ? null : cur / 100,
            status: r.status,
            match: cur !== null && cur === t.target_cents,
          });
        }
        return json({ ok: true, mode: env.CREEM_MODE, products: out });
      }

      if (req.method === "POST") {
        let body = {};
        try { body = await req.json(); } catch (_) { /* 空 body 也允许，但下面会拦 */ }
        if (body.confirm !== true) {
          return json({
            ok: false,
            error: "need_confirm",
            hint: "改价涉及真实收款，必须带 {\"confirm\":true}",
            targets: targets,
          }, 400);
        }
        const results = [];
        for (const t of targets) {
          if (t.target_cents === null) {
            results.push({ tier: t.tier, skipped: "wrangler.toml 里没有 usd_cents" });
            continue;
          }
          // 只 PATCH 已有产品，绝不创建/重建（重建会丢销量、评价、统计，且旧卡密指向旧产品）
          const patch = await creemReq(env, "PATCH", "/products/" + t.product_id,
                                       { price: t.target_cents });
          // 回读确认：Creem 的 PATCH 会返回 200 但不保证落盘，必须再 GET 一次
          const back = await creemReq(env, "GET", "/products/" + t.product_id);
          const live = (back.status === 200 && Number.isInteger(back.data.price)) ? back.data.price : null;
          results.push({
            tier: t.tier,
            product_id: t.product_id,
            target_cents: t.target_cents,
            patch_status: patch.status,
            live_cents: live,
            ok: live === t.target_cents,
          });
        }
        const allOk = results.every((r) => r.ok === true);
        return json({ ok: allOk, mode: env.CREEM_MODE, results: results });
      }
      return json({ ok: false, error: "method_not_allowed" }, 405);
    }

    // ---- 设备档案 / 套餐 / 人格云同步（EXE 与 Android 共用，见上方注释块）----
    if (p === "/device/hello" && req.method === "POST") {
      if (!env.BILLING_KV) return json({ ok: false, error: "billing_kv_missing" }, 503);
      return deviceHello(req, env, url);
    }
    if (p === "/device/state" && req.method === "GET") {
      if (!env.BILLING_KV) return json({ ok: false, error: "billing_kv_missing" }, 503);
      return deviceState(req, env, url);
    }
    if (p === "/device/persona" && req.method === "GET") {
      if (!env.BILLING_KV) return json({ ok: false, error: "billing_kv_missing" }, 503);
      return deviceGetPersona(req, env, url);
    }
    if (p === "/device/persona" && req.method === "PUT") {
      if (!env.BILLING_KV) return json({ ok: false, error: "billing_kv_missing" }, 503);
      return devicePutPersona(req, env, url);
    }
    if (p === "/device/plan" && req.method === "PUT") {
      if (!env.BILLING_KV) return json({ ok: false, error: "billing_kv_missing" }, 503);
      return devicePutPlan(req, env, url);
    }

    // ---- 购买落地页：真正的卡密网站 ----
    if (p === "/buy" && req.method === "GET") {
      // mid：本机能力令牌，贯穿「购买页 → 收银台 metadata → webhook 回带 → 自动核销」
      const mid = url.searchParams.get("mid") || "";
      // email：EXE 本机记住的买家邮箱。透传给 Creem 预填并锁定收银台邮箱框，
      // 买家点开就能直接付款，不用手打。缺失时收银台仍会自己要（MoR 法定必填）。
      const email = url.searchParams.get("email") || "";
      if (url.searchParams.get("done") === "1") return html(successPage(env));
      // ?go=1&tier=x → 直接跳对应档位收银台（EXE 的一键购买走这里）
      if (url.searchParams.get("go") === "1") {
        const target = await resolveCheckout(env, url.searchParams.get("tier") || "", mid, email);
        if (!target) return notConfiguredPage();
        return Response.redirect(target, 302);
      }
      // 默认：展示多档购买站（买家先看清楚再付款，转化率更好）。
      // 这里必须与 ?go=1 用同一套「能不能真的付款」判定：光有产品没 API key，
      // 列表页会显示「立即购买」而点进去才提示未开放，体验不一致。
      const products = parseProducts(env);
      const payable = !!env.CREEM_API_KEY || !!env.CREEM_CHECKOUT_URL;
      if (!products.length || !payable) {
        return notConfiguredPage();
      }
      return html(buyPage(env, url.origin, mid, email));
    }

    // ---- 合规页面（Creem 商户审查硬性要求：footer 可见的 Privacy Policy 与 Terms）----
    if (p === "/privacy" && req.method === "GET") {
      return privacyPage(env);
    }
    if (p === "/terms" && req.method === "GET") {
      return termsPage(env);
    }

    // ---- 激活（首次）----
    if (p === "/activate" && req.method === "POST") {
      let body;
      try {
        body = await req.json();
      } catch (e) {
        return json({ ok: false, reason: "bad_json" }, 400);
      }
      const { key, instance_name } = body;
      if (!key || !instance_name) {
        return json({ ok: false, reason: "missing_fields" }, 400);
      }
      const res = await creemPost(env, "/licenses/activate", {
        key,
        instance_name,
      });
      if (upstreamError(res)) return upstreamUnavailable(res);
      const { status, data } = res;
      if (status >= 200 && status < 300) {
        // 不能只看 HTTP 状态码：Creem 可能 200 返回 status!="active"（已停用/已退订/设备超限）
        if (!isActiveLike(data)) {
          return json({
            ok: false,
            reason: (data && data.message) || (data && data.status) || "license_not_active",
          }, 403);
        }
        return json({
          ok: true,
          instance_id: extractInstanceId(data),
          expires_at: expiresToUnix(data.expires_at),
          valid_days: 30,
        });
      }
      return json({ ok: false, reason: (data && data.message) || "http_" + status }, status);
    }

    // ---- 校验（启动/付费功能前）----
    if (p === "/validate" && req.method === "POST") {
      let body;
      try {
        body = await req.json();
      } catch (e) {
        return json({ ok: false, reason: "bad_json" }, 400);
      }
      const { key, instance_id } = body;
      if (!key || !instance_id) {
        return json({ ok: false, reason: "missing_fields" }, 400);
      }
      const res = await creemPost(env, "/licenses/validate", {
        key,
        instance_id,
      });
      if (upstreamError(res)) return upstreamUnavailable(res);
      const { status, data } = res;
      if (status >= 200 && status < 300 && data && data.status === "active") {
        return json({ ok: true, expires_at: expiresToUnix(data.expires_at), tier: mapTier(env, data) });
      }
      return json({ ok: false, reason: (data && data.status) || "http_" + status }, status);
    }

    // ---- 释放（换机/退订）----
    if (p === "/deactivate" && req.method === "POST") {
      let body;
      try {
        body = await req.json();
      } catch (e) {
        return json({ ok: false, reason: "bad_json" }, 400);
      }
      const { key, instance_id } = body;
      // 与 /activate、/validate 一致：缺必填字段直接 400，不发出站请求
      if (!key || !instance_id) {
        return json({ ok: false, reason: "missing_fields" }, 400);
      }
      const res = await creemPost(env, "/licenses/deactivate", {
        key,
        instance_id,
      });
      if (upstreamError(res)) return upstreamUnavailable(res);
      return json({ ok: res.status >= 200 && res.status < 300 }, res.status);
    }

    // ---- Webhook（Creem 支付回调）----
    if (p === "/webhook" && req.method === "POST") {
      // secret 缺失 = 部署漏配，必须明确报错（500 + 日志），
      // 否则会静默 401，支付事件悄悄全丢、账单对不上却无人知晓。
      if (!env.CREEM_WEBHOOK_SECRET) {
        console.error("CREEM_WEBHOOK_SECRET 未配置：/webhook 无法验签，支付事件将全部丢失。" +
          "请执行 wrangler secret put CREEM_WEBHOOK_SECRET 后重新部署。");
        return json({ ok: false, error: "webhook_secret_missing" }, 500);
      }
      const raw = await req.text();
      const sig = req.headers.get("creem-signature") || req.headers.get("x-creem-signature") || "";
      const okSig = await verifySignature(env.CREEM_WEBHOOK_SECRET, raw, sig);
      if (!okSig) return new Response("invalid signature", { status: 401 });
      try {
        const evt = JSON.parse(raw);
        // 兼容两种载荷结构：event.object（新版） / event.data.object（旧版/SDK）
        const obj = evt.object || (evt.data && evt.data.object) || {};
        const et = evt.eventType || evt.type || "";
        // 付款完成 / 订阅续费：这两种会生成或续期卡密，需要把卡密回传给对应机器
        if (et === "checkout.completed" || et === "subscription.paid" || et === "subscription.active") {
          // license_key 可能出现的位置有多处，尽力取（Creem 不同事件字段名不完全一致）
          const lk = obj.license_key || (obj.license && obj.license.key) || obj.key || "";
          const meta = obj.metadata || {};
          const mid = meta.mid || "";
          if (lk && mid && env.LICENSE_KV) {
            // EXE 套餐名（normal/premium/lifetime）映射到 APK 三档（basic/standard/pro）；
            // meta.tier 若本身就是 APK 档名则直接用
            const APK_TIER_MAP = { normal: "basic", premium: "standard", lifetime: "pro" };
            const tier = (meta.tier && TIERS[meta.tier]) ? meta.tier
              : (meta.tier && APK_TIER_MAP[meta.tier]) ? APK_TIER_MAP[meta.tier]
              : (mapTier(env, obj) || DEFAULT_TIER);
            let token = "";
            // 同步为 APK 签发一枚账户令牌（token 计费用），与 Creem 卡密一一对应
            if (env.WEAUATO_SIGN_SECRET && env.BILLING_KV) {
              const sub = "creem_" + (mid || lk);
              await setAcct(env, sub, tier);
              token = await signBillingToken(env, { sub, tier, exp: Date.now() + 32 * 24 * 3600 * 1000 });
            }
            // 存 N 天自动过期（N = 该档 valid_days）：记录存活时长与授权有效期一致，
            // 到期即失效；同时写 lickey:<卡密> -> mid 索引，供 /validate、/activate 反查有效期。
            const products = parseProducts(env);
            const prod = products.find((x) => x.tier === tier);
            const vdays = (prod && prod.valid_days) || 30;
            // 记下买家邮箱：后续续费时由 EXE 取回并预填进 Creem 收银台，
            // 买家不必在付款页再手打一次邮箱（Creem 是 MoR，邮箱无法免填，只能预填）。
            const email = (obj.customer && obj.customer.email) || obj.customer_email || "";
            const rec = { key: lk, tier, token, ts: Date.now(), valid_days: vdays };
            if (looksLikeEmail(email)) rec.email = email.trim();
            await env.LICENSE_KV.put("lic:" + mid, JSON.stringify(rec),
              { expirationTtl: vdays * 24 * 3600 });
            try {
              await env.LICENSE_KV.put("lickey:" + lk, mid, { expirationTtl: vdays * 24 * 3600 });
            } catch (e) { /* 索引非关键，失败不影响主流程 */ }
            console.log("license stored for mid", mid, "tier", tier, "valid_days", vdays);
          }
        }
        console.log("Creem webhook:", et, obj && obj.id);
      } catch (e) {
        /* 忽略解析错误，仍回 200 避免 Creem 重投 */
      }
      return new Response("OK");
    }

    // ---- 取卡密（本机轮询用）：付款后由 webhook 写入，前端据此自动核销 ----
    if (p === "/license" && req.method === "GET") {
      const mid = url.searchParams.get("mid") || "";
      if (!mid || !env.LICENSE_KV) return json({ pending: true }, 404);
      try {
        const val = await env.LICENSE_KV.get("lic:" + mid);
        if (!val) return json({ pending: true }, 404);
        const d = JSON.parse(val);
        const vdays = d.valid_days || 30;
        const ts = d.ts || 0;
        // 有效期 = 购买时刻 + 该档 valid_days；一次性套餐无 Creem 续期，由我们自己定义周期。
        const expires_at = ts ? Math.floor(ts / 1000) + vdays * 24 * 3600 : 0;
        // email 一并回传：EXE 存进本地，下次续费直接预填进收银台（买家零输入）。
        return json({ key: d.key, tier: d.tier || "", token: d.token || "", ts,
                      valid_days: vdays, expires_at, email: d.email || "" });
      } catch (e) {
        return json({ pending: true }, 404);
      }
    }

    // ---- 设备备份同步：App 的云端快照，用于卸载重装后恢复数据 ----
    // GET  ?mid=<deviceId>  取回快照（404 = 该设备还没备份过）
    // PUT  ?mid=<deviceId>  body 为明文 JSON 快照
    // 注意：路由分发放在 handleSync 定义之后 —— 函数声明会提升，但
    // DEVICE_ID_RE/SYNC_MAX_BYTES 是 const（TDZ），若在声明前调用
    // handleSync 会 ReferenceError → Worker 1101（/sync 永远 500 的根因）。

    // 根路径：跳购买页，方便直接访问域名
    if (p === "/" && req.method === "GET") {
      return Response.redirect(url.origin + "/buy", 302);
    }

    // ---- /sync 的实现 ----
    //
    // deviceId 是 App 自己生成的随机 UUID v4 —— 不是 IMEI、不是序列号、
    // 也不是 ANDROID_ID（后者部分国产 ROM 会对所有 App 返回同一个固定值）。
    // 它既是数据地址也是凭证，因此校验形态是唯一必要的访问控制：猜到别人
    // slot 的概率约等于 2^-122。
    //
    // 没有 deviceId 就查不到数据，而 deviceId 本身必须在卸载后还能拿回来 ——
    // App 侧为此在公共 Download 目录留了一份几十字节的 MediaStore 锚点。
    const DEVICE_ID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    const SYNC_MAX_BYTES = 8 * 1024 * 1024;

    async function handleSync(req, env, url) {
      const CORS = {
        "Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Methods": "GET, PUT, OPTIONS",
        "Access-Control-Allow-Headers": "Content-Type",
      };

      if (req.method === "OPTIONS") return new Response("", { status: 204, headers: CORS });

      const mid = (url.searchParams.get("mid") || "").trim();
      if (!DEVICE_ID_RE.test(mid)) {
        return json({ ok: false, error: "bad_device_id" }, 400, CORS);
      }
      if (!env.BILLING_KV) {
        return json({ ok: false, error: "kv_not_bound" }, 500, CORS);
      }

      const key = "bak:" + mid;

      if (req.method === "GET") {
        const raw = await env.BILLING_KV.get(key);
        if (!raw) return new Response("Not found", { status: 404, headers: CORS });
        return new Response(raw, {
          status: 200,
          headers: { ...CORS, "Content-Type": "application/json; charset=utf-8" },
        });
      }

      if (req.method === "PUT") {
        const body = await req.text();
        if (!body) return json({ ok: false, error: "empty_body" }, 400, CORS);
        if (body.length > SYNC_MAX_BYTES) {
          return json({ ok: false, error: "snapshot_too_large" }, 413, CORS);
        }
        try {
          const obj = JSON.parse(body);
          if (!obj || typeof obj !== "object" || Array.isArray(obj)) {
            return json({ ok: false, error: "not_an_object" }, 400, CORS);
          }
          if (typeof obj.v !== "number") {
            return json({ ok: false, error: "missing_version" }, 400, CORS);
          }
          obj.saved_at = Date.now();
          await env.BILLING_KV.put(key, JSON.stringify(obj));
          return json({ ok: true, bytes: body.length, saved_at: obj.saved_at }, 200, CORS);
        } catch (e) {
          return json({ ok: false, error: "invalid_json" }, 400, CORS);
        }
      }

      return json({ ok: false, error: "method_not_allowed" }, 405, CORS);
    }

    // /sync 路由分发（必须在 const 声明与 handleSync 定义之后，见上方 TDZ 注释）
    if (p === "/sync") {
      return handleSync(req, env, url);
    }

    // ---- EXE 自动更新代理实现 ----
    // 查询 repo 的最新 CI 构件（actions/artifacts），解析出无需 GitHub 鉴权的 azure SAS 直链。
    async function updateProxy(req, env, artifactNameOverride) {
      const token = env.GITHUB_TOKEN;
      if (!token) return json({ ok: false, error: "github_token_missing" }, 503);
      const owner = env.UPDATE_REPO_OWNER || "samcaicn";
      const repo = env.UPDATE_REPO_NAME || "gloai";
      const artifactName = env.UPDATE_ARTIFACT_NAME || "weauto-windows-exe";
      const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0 Safari/537.36";
      try {
        const api = `https://api.github.com/repos/${owner}/${repo}/actions/artifacts?per_page=100`;
        const r = await fetch(api, {
          headers: { Authorization: `Bearer ${token}`, Accept: "application/vnd.github+json", "user-agent": UA },
        });
        if (!r.ok) return json({ ok: false, error: "github_api_error", status: r.status }, 502);
        const data = await r.json();
      const matchName = artifactNameOverride || env.UPDATE_ARTIFACT_NAME || "weauto-windows-exe";
      const arts = (data.artifacts || []).filter((a) => {
        if (a.expired) return false;
        if (matchName.endsWith("*")) return a.name.startsWith(matchName.slice(0, -1));
        return a.name === matchName;
      });
        if (!arts.length) return json({ ok: false, error: "no_artifact" }, 404);
        arts.sort((a, b) => (a.updated_at < b.updated_at ? 1 : -1));
        const target = arts[0];
        // GitHub 的 archive_download_url 会 302 到 azure SAS URL（公开可读，无需 GitHub 鉴权）
        const dl = await fetch(target.archive_download_url, {
          headers: { Authorization: `Bearer ${token}`, "user-agent": UA },
          redirect: "manual",
        });
        let directUrl = target.archive_download_url;
        if (dl.status === 301 || dl.status === 302) {
          const loc = dl.headers.get("location");
          if (loc) directUrl = loc;
        }
        return json({
          ok: true,
          version: target.updated_at,
          download_url: directUrl,
          artifact_id: target.id,
          size_in_bytes: target.size_in_bytes,
          source: "WeAuto Worker Update Proxy",
        });
      } catch (e) {
        return json({ ok: false, error: "proxy_failed", detail: String((e && e.message) || e) }, 502);
      }
    }

    return new Response("Not found", { status: 404 });
  },
};
