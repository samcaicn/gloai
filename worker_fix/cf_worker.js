var __defProp = Object.defineProperty;
var __name = (target, value) => __defProp(target, "name", { value, configurable: true });

// weauto_license/cf_worker.js
var __defProp2 = Object.defineProperty;
var __name2 = /* @__PURE__ */ __name((target, value) => __defProp2(target, "name", { value, configurable: true }), "__name");
var creemBase = /* @__PURE__ */ __name2((mode) => mode === "prod" ? "https://api.creem.io/v1" : "https://test-api.creem.io/v1", "creemBase");
var ONETIME_NONCE_SHA256 = "";
async function sha256hex(str) {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(String(str)));
  return Array.from(new Uint8Array(buf)).map((b) => b.toString(16).padStart(2, "0")).join("");
}
__name(sha256hex, "sha256hex");
__name2(sha256hex, "sha256hex");
async function hmacSha256Hex(secret, raw) {
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const buf = await crypto.subtle.sign("HMAC", key, enc.encode(raw));
  return Array.from(new Uint8Array(buf)).map((b) => b.toString(16).padStart(2, "0")).join("");
}
__name(hmacSha256Hex, "hmacSha256Hex");
__name2(hmacSha256Hex, "hmacSha256Hex");
async function creemPost(env, path, body) {
  try {
    const r = await fetch(creemBase(env.CREEM_MODE) + path, {
      method: "POST",
      headers: {
        "x-api-key": env.CREEM_API_KEY,
        "Content-Type": "application/json",
        accept: "application/json"
      },
      body: JSON.stringify(body)
    });
    const txt = await r.text();
    let data = {};
    try {
      data = JSON.parse(txt);
    } catch (e) {
    }
    return { status: r.status, data };
  } catch (e) {
    console.error("creem upstream unreachable:", path, e && e.message);
    return { status: 0, data: { message: "upstream_unreachable" }, neterr: true };
  }
}
__name(creemPost, "creemPost");
__name2(creemPost, "creemPost");
function upstreamError(res) {
  return !res || res.neterr === true || res.status >= 500 && res.status <= 599;
}
__name(upstreamError, "upstreamError");
__name2(upstreamError, "upstreamError");
async function creemReq(env, method, path, body) {
  try {
    const r = await fetch(creemBase(env.CREEM_MODE) + path, {
      method,
      headers: {
        "x-api-key": env.CREEM_API_KEY,
        "Content-Type": "application/json",
        accept: "application/json"
      },
      body: body === void 0 ? void 0 : JSON.stringify(body)
    });
    const txt = await r.text();
    let data = {};
    try {
      data = JSON.parse(txt);
    } catch (e) {
    }
    return { status: r.status, data };
  } catch (e) {
    console.error("creem upstream unreachable:", method, path, e && e.message);
    return { status: 0, data: { message: "upstream_unreachable" }, neterr: true };
  }
}
__name(creemReq, "creemReq");
__name2(creemReq, "creemReq");
function upstreamUnavailable(res) {
  const reason = res && res.data && res.data.message || "upstream_unreachable";
  return json({ ok: false, reason, upstream_status: res && res.status || 0 }, 503);
}
__name(upstreamUnavailable, "upstreamUnavailable");
__name2(upstreamUnavailable, "upstreamUnavailable");
function isActiveLike(data) {
  if (!data) return false;
  if (typeof data.status === "string") return data.status.toLowerCase() === "active";
  if (data.active === true) return true;
  const lic = data.license || data.license_key_object;
  if (lic && typeof lic === "object") {
    if (typeof lic.status === "string") return lic.status.toLowerCase() === "active";
    if (lic.active === true) return true;
  }
  return !!(data.instance || data.id);
}
__name(isActiveLike, "isActiveLike");
__name2(isActiveLike, "isActiveLike");
function extractInstanceId(data) {
  const inst = data && data.instance;
  if (Array.isArray(inst)) return inst[0] && inst[0].id;
  if (inst && typeof inst === "object") return inst.id;
  return data && data.id;
}
__name(extractInstanceId, "extractInstanceId");
__name2(extractInstanceId, "extractInstanceId");
function expiresToUnix(expiresAt) {
  if (!expiresAt) return null;
  const t = Date.parse(expiresAt);
  return Number.isNaN(t) ? null : Math.floor(t / 1e3);
}
__name(expiresToUnix, "expiresToUnix");
__name2(expiresToUnix, "expiresToUnix");
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
  return { sig: header.trim(), ts: null };
}
__name(parseSig, "parseSig");
__name2(parseSig, "parseSig");
var REPLAY_WINDOW_SEC = 300;
async function verifySignature(secret, rawBody, signature) {
  if (!secret || !signature) return false;
  const { sig, ts } = parseSig(signature);
  if (!sig) return false;
  if (ts !== null && Number.isFinite(ts)) {
    const tsSec = ts > 1e12 ? Math.floor(ts / 1e3) : Math.floor(ts);
    const nowSec = Math.floor(Date.now() / 1e3);
    if (nowSec - tsSec > REPLAY_WINDOW_SEC) {
      console.warn("webhook signature too old, rejected:", nowSec - tsSec, "s");
      return false;
    }
  }
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey(
    "raw",
    enc.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const buf = await crypto.subtle.sign("HMAC", key, enc.encode(rawBody));
  const computed = [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
  const given = sig.trim().toLowerCase();
  if (computed.length !== given.length) return false;
  let diff = 0;
  for (let i = 0; i < computed.length; i++) {
    diff |= computed.charCodeAt(i) ^ given.charCodeAt(i);
  }
  return diff === 0;
}
__name(verifySignature, "verifySignature");
__name2(verifySignature, "verifySignature");
function json(obj, status = 200, extraHeaders = null) {
  const h = { "content-type": "application/json; charset=utf-8" };
  if (extraHeaders && typeof extraHeaders.entries === "function") {
    for (const [k, v] of extraHeaders.entries()) h[k] = v;
  }
  return new Response(JSON.stringify(obj), { status, headers: h });
}
__name(json, "json");
__name2(json, "json");
function parseProducts(env) {
  const raw = env.CREEM_PRODUCTS;
  if (raw) {
    try {
      const arr = typeof raw === "string" ? JSON.parse(raw) : raw;
      if (Array.isArray(arr) && arr.length) {
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
          usd_cents: Number.isInteger(x.usd_cents) ? x.usd_cents : null
        }));
      }
    } catch (e) {
    }
  }
  if (env.CREEM_PRODUCT_ID) {
    return [{
      tier: "default",
      label: env.PRODUCT_NAME || "WeAuto \u6388\u6743",
      price_text: env.PRODUCT_PRICE || "\u89C1\u6536\u94F6\u53F0",
      product_id: env.CREEM_PRODUCT_ID,
      billing: "once",
      features: env.PRODUCT_DESC || ""
    }];
  }
  return [];
}
__name(parseProducts, "parseProducts");
__name2(parseProducts, "parseProducts");
function mapTier(env, data) {
  const products = parseProducts(env);
  if (!products.length || !data) return null;
  const obj = data.order || data.subscription || data.product || data.license || data;
  const cands = [obj && obj.product_id, obj && obj.id, data.product_id].filter(Boolean);
  for (const c of cands) {
    const hit = products.find((x) => x.product_id === c);
    if (hit) return hit.tier;
  }
  return null;
}
__name(mapTier, "mapTier");
__name2(mapTier, "mapTier");
var licenseCache = /* @__PURE__ */ new Map();
var LICENSE_TTL = 10 * 60 * 1e3;
async function licenseOk(env, key, instanceId, requireUpstream = true) {
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
      instance_id: instanceId
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
__name(licenseOk, "licenseOk");
__name2(licenseOk, "licenseOk");
function volcanoConfig(env) {
  const base = env.VOLCANO_BASE_URL || env.AI_UPSTREAM_URL || "https://ark.cn-beijing.volces.com/api/v3";
  const key = env.VOLCANO_API_KEY || env.AI_UPSTREAM_KEY || "";
  return { base: String(base).replace(/\/+$/, ""), key: String(key) };
}
__name(volcanoConfig, "volcanoConfig");
__name2(volcanoConfig, "volcanoConfig");
function volcanoConfigured(env) {
  return !!volcanoConfig(env).key;
}
__name(volcanoConfigured, "volcanoConfigured");
__name2(volcanoConfigured, "volcanoConfigured");
function aiAvailable(env) {
  return !!env.AI || volcanoConfigured(env);
}
__name(aiAvailable, "aiAvailable");
__name2(aiAvailable, "aiAvailable");
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
__name(resolveProvider, "resolveProvider");
__name2(resolveProvider, "resolveProvider");
function workersAiText(res) {
  if (!res) return null;
  if (typeof res === "string") return res;
  if (res.response !== void 0) return typeof res.response === "string" ? res.response : JSON.stringify(res.response);
  if (res.choices && res.choices[0] && res.choices[0].message && res.choices[0].message.content !== void 0) return res.choices[0].message.content;
  if (res.content !== void 0) return res.content;
  if (res.text !== void 0) return res.text;
  return null;
}
__name(workersAiText, "workersAiText");
__name2(workersAiText, "workersAiText");
function openAiCompletion(model, text, usage) {
  return {
    id: "chatcmpl-weauto-" + Date.now().toString(36),
    object: "chat.completion",
    created: Math.floor(Date.now() / 1e3),
    model,
    choices: [{ index: 0, message: { role: "assistant", content: text }, finish_reason: "stop" }],
    usage: {
      prompt_tokens: usage.prompt,
      completion_tokens: usage.completion,
      total_tokens: usage.prompt + usage.completion
    }
  };
}
__name(openAiCompletion, "openAiCompletion");
__name2(openAiCompletion, "openAiCompletion");
function estimateUsage(obj, text) {
  const msgs = obj && Array.isArray(obj.messages) ? JSON.stringify(obj.messages) : "";
  const promptChars = msgs ? msgs.length : 0;
  const completionChars = text ? String(text).length : 0;
  return { prompt: Math.ceil(promptChars / 3), completion: Math.ceil(completionChars / 3) };
}
__name(estimateUsage, "estimateUsage");
__name2(estimateUsage, "estimateUsage");
async function callWorkersAi(env, model, obj) {
  if (!env.AI) throw new Error("workers_ai_binding_missing");
  const messages = Array.isArray(obj.messages) ? obj.messages : [];
  const opts = { messages, stream: false };
  if (typeof obj.temperature === "number") opts.temperature = obj.temperature;
  if (typeof obj.max_tokens === "number") opts.max_tokens = obj.max_tokens;
  if (typeof obj.top_p === "number") opts.top_p = obj.top_p;
  if (obj.stop !== void 0) opts.stop = obj.stop;
  let res;
  try {
    res = await env.AI.run(model, opts);
  } catch (e) {
    throw e;
  }
  const text = workersAiText(res);
  if (text == null) throw new Error("workers_ai_empty_response");
  return json(openAiCompletion(model, text, estimateUsage(obj, text)), 200);
}
__name(callWorkersAi, "callWorkersAi");
__name2(callWorkersAi, "callWorkersAi");
async function callVolcano(env, model, obj, url, req, bodyStr) {
  const c = volcanoConfig(env);
  if (!c.key) throw new Error("volcano_key_missing");
  const rel = url.pathname.slice("/ai/".length).replace(/^v1\//, "");
  const target = c.base + "/" + rel + url.search;
  let finalBody = bodyStr || "";
  if (model) {
    try {
      const o = finalBody ? JSON.parse(finalBody) : {};
      if (o.model !== model) {
        o.model = model;
        finalBody = JSON.stringify(o);
      }
    } catch (_) {
    }
  }
  const init = {
    method: req.method,
    headers: {
      "content-type": req.headers.get("content-type") || "application/json",
      "accept": req.headers.get("accept") || "application/json, text/event-stream",
      "authorization": "Bearer " + c.key,
      "user-agent": req.headers.get("user-agent") || "weauto-worker"
    },
    body: req.method === "GET" || req.method === "HEAD" ? void 0 : finalBody
  };
  return fetch(target, init);
}
__name(callVolcano, "callVolcano");
__name2(callVolcano, "callVolcano");
async function dispatch(env, provider, model, obj, url, req, bodyStr) {
  if (provider === "workers") return callWorkersAi(env, model, obj);
  return callVolcano(env, model, obj, url, req, bodyStr);
}
__name(dispatch, "dispatch");
__name2(dispatch, "dispatch");
async function routeAi(req, env, url) {
  let bodyStr = "";
  try {
    bodyStr = await req.clone().text();
  } catch (_) {
  }
  let obj = {};
  if (bodyStr) {
    try {
      obj = JSON.parse(bodyStr);
    } catch (_) {
    }
  }
  const model = obj && obj.model || "";
  const hint = req.headers && req.headers.get && req.headers.get("x-weauto-provider") || "";
  const resolved = resolveProvider(model, hint, env);
  if (resolved.error) {
    return json({
      error: {
        message: "\u4E91\u7AEF\u5C1A\u672A\u914D\u7F6E AI \u4F9B\u5E94\u5546\uFF08Workers AI \u6216\u706B\u5C71\u65B9\u821F\uFF09\uFF0C\u8BF7\u8054\u7CFB\u7BA1\u7406\u5458",
        type: "server_misconfigured",
        code: "ai_upstream_missing"
      }
    }, 503);
  }
  try {
    return await dispatch(env, resolved.provider, resolved.realModel, obj, url, req, bodyStr);
  } catch (e1) {
    const other = resolved.provider === "workers" ? "volcano" : "workers";
    const otherReady = other === "volcano" ? volcanoConfigured(env) : !!env.AI;
    if (otherReady) {
      try {
        return await dispatch(env, other, resolved.realModel, obj, url, req, bodyStr);
      } catch (_) {
      }
    }
    return json({
      error: {
        message: "AI \u8C03\u7528\u5931\u8D25\uFF08\u53CC\u4F9B\u5E94\u5546\u5747\u4E0D\u53EF\u7528\uFF09\uFF1A" + String(e1 && e1.message || e1),
        type: "upstream_error",
        code: "ai_run_failed"
      }
    }, 502);
  }
}
__name(routeAi, "routeAi");
__name2(routeAi, "routeAi");
async function proxyAi(req, env, url) {
  const authHdr = (req.headers.get("authorization") || "").trim();
  const token = authHdr.startsWith("Bearer ") ? authHdr.slice(7).trim() : authHdr;
  // 客户端机器指纹签名校验（2026-10-15 防配额盗用/伪造）：
  // 开启 VERIFY_CLIENT_SIG=on 后，凡带 X-WA-Machine 的请求必须附合法
  // X-WA-Ts + X-WA-Sig（HMAC-SHA256(WEAUATO_CLIENT_SECRET, machine\n+ts)），
  // 时间窗 ±24h（防伪造为主，重放属更高阶威胁）；未配置该开关时 fail-open
  // 保持兼容旧客户端。签名无效 → 403，不消耗配额、不转发。
  if ((env.VERIFY_CLIENT_SIG || "").toLowerCase() === "on") {
    const machineHdr = req.headers.get("x-wa-machine");
    if (machineHdr) {
      const ts = req.headers.get("x-wa-ts") || "";
      const sig = (req.headers.get("x-wa-sig") || "").toLowerCase();
      const sec = env.WEAUATO_CLIENT_SECRET || "";
      const now = Date.now() / 1e3;
      const ok = sec && /^\d{10}$/.test(ts)
        && Math.abs(now - Number(ts)) < 24 * 3600
        && sig === await hmacSha256Hex(sec, machineHdr + "\n" + ts);
      if (!ok) {
        return json({ ok: false, error: "weauto_sig_invalid" }, 403);
      }
    }
  }
  if (token && looksLikeBillingToken(token) && env.WEAUATO_SIGN_SECRET) {
    return proxyAiBilled(req, env, url, token);
  }
  // 卡密路径：有有效卡密 + 实例 → 直通（不受每日配额限制）
  const key = token;
  const instanceId = req.headers.get("x-weauto-instance") || "";
  if (key && instanceId && (await licenseOk(env, key, instanceId))) {
    return routeAi(req, env, url);
  }
  // 免费配额路径（2026-10-14 商业模式：所有软件默认激活，无卡密/卡密无效
  // 也放行，但按机器指纹限制每日回复条数，超限 429，激活卡密可解除）。
  // 2026-10-15 离线授权：导入管理员签发的「话术包+回复次数」授权文件并激活后，
  // 本机按授权总次数计费（不再受每日免费条数限制）。
  const machine = quotaMachine(req);
  const off = await getOfflineLic(env, machine);
  if (off) {
    const used = Number(off.used || 0);
    const total = Number(off.total || 0);
    if (used >= total) {
      return json({
        error: {
          message: "回复次数已用完（离线授权），请联系管理员续期。",
          type: "weauto_quota_error",
          code: "offline_quota_exceeded",
          quota_remaining: 0,
          quota_limit: total,
        },
      }, 429, new Headers({ "X-Quota-Remaining": "0", "X-Quota-Limit": String(total) }));
    }
    const resp = await routeAi(req, env, url);
    const h = new Headers(resp ? resp.headers : {});
    if (resp && resp.status >= 200 && resp.status < 300) {
      await incrOfflineUsed(env, machine);
      h.set("X-Quota-Remaining", String(Math.max(0, total - used - 1)));
    } else {
      h.set("X-Quota-Remaining", String(Math.max(0, total - used)));
    }
    h.set("X-Quota-Limit", String(total));
    h.set("X-Quota-Source", "offline");
    if (resp) return new Response(resp.body, { status: resp.status, headers: h });
    return new Response("{}", { status: 502, headers: h });
  }
  const q = await checkDailyQuota(env, machine);
  if (!q.allowed) {
    const buy = env.CREEM_CHECKOUT_URL || (env.WORKER_URL ? env.WORKER_URL + "/buy" : "");
    return json({
      error: {
        message: "今日免费回复已达上限（" + q.limit + " 条），明天再来吧。"
          + (buy ? " 激活卡密可立即解除限制：" + buy : ""),
        type: "weauto_quota_error",
        code: "daily_quota_exceeded",
        quota_remaining: 0,
        quota_limit: q.limit,
      },
    }, 429, new Headers({ "X-Quota-Remaining": "0", "X-Quota-Limit": String(q.limit) }));
  }
  const resp = await routeAi(req, env, url);
  if (resp && resp.status >= 200 && resp.status < 300) {
    await incrDailyQuota(env, machine);
  }
  const h = new Headers(resp.headers);
  h.set("X-Quota-Remaining", String(Math.max(0, q.remaining - 1)));
  h.set("X-Quota-Limit", String(q.limit));
  return new Response(resp.body, { status: resp.status, headers: h });
}
__name(proxyAi, "proxyAi");
__name2(proxyAi, "proxyAi");
/* ===================== 免费每日回复配额（默认激活商业模式） =====================
 * 无卡密用户每日最多回复 WEAUTO_FREE_DAILY_QUOTA 条（默认 30），按机器指纹
 * （X-WA-Machine → X-WeAuto-Instance → 请求 IP）在 BILLING_KV 计数，超限 429。
 * 有有效卡密的用户不受此限制。AI 调用成功（2xx）后才 +1，失败不耗额度。
 */
var FREE_QUOTA_DEFAULT = 30;
function quotaMachine(req) {
  return (req.headers.get("x-wa-machine")
    || req.headers.get("x-weauto-instance")
    || req.headers.get("cf-connecting-ip")
    || "anon").slice(0, 64);
}
__name(quotaMachine, "quotaMachine");
__name2(quotaMachine, "quotaMachine");
function quotaDate() {
  return new Date().toISOString().slice(0, 10);
}
__name(quotaDate, "quotaDate");
__name2(quotaDate, "quotaDate");
async function checkDailyQuota(env, machine) {
  const limit = Number(env.WEAUTO_FREE_DAILY_QUOTA) > 0
    ? Number(env.WEAUTO_FREE_DAILY_QUOTA) : FREE_QUOTA_DEFAULT;
  if (!env.BILLING_KV) return { allowed: true, limit, remaining: limit, key: "" };
  const key = "daily:" + quotaDate() + ":" + machine;
  const raw = await env.BILLING_KV.get(key);
  const count = raw ? Number(raw) : 0;
  return { allowed: count < limit, limit, remaining: Math.max(0, limit - count), key };
}
__name(checkDailyQuota, "checkDailyQuota");
__name2(checkDailyQuota, "checkDailyQuota");
async function incrDailyQuota(env, machine) {
  if (!env.BILLING_KV || !machine) return;
  const limit = Number(env.WEAUTO_FREE_DAILY_QUOTA) > 0
    ? Number(env.WEAUTO_FREE_DAILY_QUOTA) : FREE_QUOTA_DEFAULT;
  const key = "daily:" + quotaDate() + ":" + machine;
  const cur = Number(await env.BILLING_KV.get(key)) || 0;
  await env.BILLING_KV.put(key, String(Math.min(cur + 1, limit + 1)),
    { expirationTtl: 48 * 3600 });
}
__name(incrDailyQuota, "incrDailyQuota");
__name2(incrDailyQuota, "incrDailyQuota");

/* ===================== 离线授权（话术包 + 回复次数） =====================
 * 2026-10 商业模式：Creem 在线收款下线，改为「管理员按硬件识别码签发 .wpk
 * （话术包 + 回复次数），离线分发，客户端导入后上报本端点激活」。
 * - 激活校验：.wpk ECDSA P-256 验签（公钥 PACK_VERIFY_PUB env）→ 设备绑定
 *   （header.device_hashes 含本机 device_hash）→ reply_quota>0 → 未到期；
 * - 生效后 BILLING_KV 记 offline:lic:{machine}，/ai/v1 免费路径优先按授权
 *   总次数计费（不再受每日免费条数限制），X-Quota 校准客户端本地记账。
 */
async function getOfflineLic(env, machine) {
  if (!env.BILLING_KV || !machine) return null;
  const raw = await env.BILLING_KV.get("offline:lic:" + machine);
  if (!raw) return null;
  try {
    const o = JSON.parse(raw);
    if (!o || !o.pack_id || !(o.total > 0)) return null;
    if (o.expires_at && o.expires_at * 1000 < Date.now()) return null;
    return o;
  } catch (_) {
    return null;
  }
}
__name(getOfflineLic, "getOfflineLic");
__name2(getOfflineLic, "getOfflineLic");
async function setOfflineLic(env, machine, packId, total, expiresAt) {
  if (!env.BILLING_KV || !machine) return;
  await env.BILLING_KV.put("offline:lic:" + machine,
    JSON.stringify({ pack_id: packId, total: Number(total), used: 0, expires_at: Number(expiresAt || 0), activated_at: Date.now() }),
    { expirationTtl: 400 * 24 * 3600 });
}
__name(setOfflineLic, "setOfflineLic");
__name2(setOfflineLic, "setOfflineLic");
async function incrOfflineUsed(env, machine) {
  if (!env.BILLING_KV || !machine) return 0;
  const lic = await getOfflineLic(env, machine);
  if (!lic) return 0;
  lic.used = (lic.used || 0) + 1;
  await env.BILLING_KV.put("offline:lic:" + machine, JSON.stringify(lic),
    { expirationTtl: 400 * 24 * 3600 });
  return lic.used;
}
__name(incrOfflineUsed, "incrOfflineUsed");
__name2(incrOfflineUsed, "incrOfflineUsed");
function b64Decode(s) {
  const b = String(s).replace(/-/g, "+").replace(/_/g, "/");
  const pad = b.length % 4;
  const bin = atob(pad ? b + "=".repeat(4 - pad) : b);
  const u8 = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
  return u8;
}
__name(b64Decode, "b64Decode");
__name2(b64Decode, "b64Decode");
function derToRawEcdsa(der) {
  // DER SEQUENCE -> IEEE P1363 raw r||s（WebCrypto ECDSA 输入格式）
  try {
    if (der.length < 8 || der[0] !== 0x30) return null;
    let off = 2;
    if (der[1] !== der.length - 2) return null;
    if (der[off] !== 0x02) return null;
    const rlen = der[off + 1];
    const r = der.slice(off + 2, off + 2 + rlen);
    off += 2 + rlen;
    if (der[off] !== 0x02) return null;
    const slen = der[off + 1];
    const s = der.slice(off + 2, off + 2 + slen);
    const ri = BigInt("0x" + Buffer.from(r).toString("hex"));
    const si = BigInt("0x" + Buffer.from(s).toString("hex"));
    const to32 = (v) => {
      const h = v.toString(16).padStart(64, "0");
      return Uint8Array.from(Buffer.from(h, "hex"));
    };
    const raw = new Uint8Array(64);
    raw.set(to32(ri), 0);
    raw.set(to32(si), 32);
    return raw;
  } catch (_) {
    return null;
  }
}
__name(derToRawEcdsa, "derToRawEcdsa");
__name2(derToRawEcdsa, "derToRawEcdsa");
async function wpkVerifySignature(env, aadCt, sigDer) {
  const pubB64 = env.PACK_VERIFY_PUB;
  if (!pubB64) return false;
  try {
    const raw = derToRawEcdsa(sigDer);
    if (!raw) return false;
    const key = await crypto.subtle.importKey("spki", b64Decode(pubB64),
      { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
    return await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, key, raw, aadCt);
  } catch (_) {
    return false;
  }
}
__name(wpkVerifySignature, "wpkVerifySignature");
__name2(wpkVerifySignature, "wpkVerifySignature");
function parseWpkHeader(data) {
  // .wpk 明文头：WPK1 + ver(1) + flags(1) + hlen(2 LE) + header_json + payload_len(4) + ct_tag + sig_len(2) + sig
  try {
    if (data.length < 8) return null;
    const magic = String.fromCharCode(data[0], data[1], data[2], data[3]);
    if (magic !== "WPK1") return null;
    const hlen = (data[7] << 8 | data[6]) >>> 0;
    if (data.length < 8 + hlen + 4) return null;
    const header = JSON.parse(new TextDecoder().decode(data.slice(8, 8 + hlen)));
    let off = 8 + hlen;
    const payloadLen = (data[off] | (data[off + 1] << 8) | (data[off + 2] << 16) | (data[off + 3] << 24)) >>> 0;
    off += 4;
    off += payloadLen; // 跳过 ct_tag 密文区
    if (off + 2 > data.length) return null;
    const sigLen = data[off] | (data[off + 1] << 8);
    off += 2;
    if (off + sigLen > data.length) return null;
    const sig = data.slice(off, off + sigLen);
    const aadCt = data.slice(0, 8 + hlen + 4 + payloadLen);
    return { header, sig, aadCt };
  } catch (_) {
    return null;
  }
}
__name(parseWpkHeader, "parseWpkHeader");
__name2(parseWpkHeader, "parseWpkHeader");
async function activateOffline(req, env) {
  if (req.method !== "POST") return json({ ok: false, error: "method" }, 405);
  let body;
  try {
    body = await req.json();
  } catch (_) {
    return json({ ok: false, error: "bad_json" }, 400);
  }
  const fileB64 = String(body.file_b64 || "");
  const deviceId = String(body.device_id || "").trim();
  if (!fileB64 || !deviceId) return json({ ok: false, error: "missing_fields" }, 400);
  if (!env.PACK_VERIFY_PUB) return json({ ok: false, error: "pack_verify_not_configured" }, 503);
  let data;
  try {
    data = b64Decode(fileB64);
  } catch (_) {
    return json({ ok: false, error: "bad_b64" }, 400);
  }
  const parsed = parseWpkHeader(data);
  if (!parsed) return json({ ok: false, error: "bad_wpk_format" }, 400);
  const h = parsed.header;
  const quota = Number(h.reply_quota || 0);
  if (!(quota > 0)) return json({ ok: false, error: "no_reply_quota" }, 400);
  const packId = String(h.pack_id || "");
  if (!packId) return json({ ok: false, error: "no_pack_id" }, 400);
  const okSig = await wpkVerifySignature(env, parsed.aadCt, parsed.sig);
  if (!okSig) return json({ ok: false, error: "bad_signature" }, 403);
  // 设备绑定：device_hashes 非空时必须含本机 device_hash（sha256(device_id) 前 16B）
  const dh = (await crypto.subtle.digest("SHA-256", new TextEncoder().encode(deviceId))).slice(0, 16);
  const dhHex = Array.from(new Uint8Array(dh)).map((b) => b.toString(16).padStart(2, "0")).join("");
  const bound = Array.isArray(h.device_hashes) && h.device_hashes.length > 0;
  if (bound && !h.device_hashes.includes(dhHex)) {
    return json({ ok: false, error: "device_mismatch" }, 403);
  }
  const expiresAt = Number(h.expires_at || 0);
  if (expiresAt && expiresAt * 1000 < Date.now()) return json({ ok: false, error: "expired" }, 403);
  await setOfflineLic(env, dhHex, packId, quota, expiresAt);
  return json({ ok: true, pack_id: packId, reply_quota: quota, device_hash: dhHex });
}
__name(activateOffline, "activateOffline");
__name2(activateOffline, "activateOffline");
var DEFAULT_TIER = "standard";
var JEV_COST = 1500;
var TIERS = {
  basic: { name: "\u57FA\u7840\u7248", compute: 100, tokens: 100 * 1e4, price_cny: 19.9, price_text: "\xA519.9/\u6708" },
  standard: { name: "\u6807\u51C6\u7248", compute: 500, tokens: 500 * 1e4, price_cny: 49.9, price_text: "\xA549.9/\u6708" },
  pro: { name: "\u65D7\u8230\u7248", compute: 2e3, tokens: 2e3 * 1e4, price_cny: 129.9, price_text: "\xA5129.9/\u6708" }
};
function b64u(s) {
  const bytes = new TextEncoder().encode(String(s));
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
__name(b64u, "b64u");
__name2(b64u, "b64u");
function b64uDecode(s) {
  s = String(s).replace(/-/g, "+").replace(/_/g, "/");
  const bin = atob(s + "===".slice((s.length + 3) % 4));
  const bytes = Uint8Array.from(bin, (c) => c.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}
__name(b64uDecode, "b64uDecode");
__name2(b64uDecode, "b64uDecode");
function looksLikeBillingToken(t) {
  return /^[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}$/.test(t || "");
}
__name(looksLikeBillingToken, "looksLikeBillingToken");
__name2(looksLikeBillingToken, "looksLikeBillingToken");
function randHex(n) {
  const c = "abcdef0123456789";
  let s = "";
  for (let i = 0; i < n; i++) s += c[Math.floor(Math.random() * 16)];
  return s;
}
__name(randHex, "randHex");
__name2(randHex, "randHex");
async function signBillingToken(env, payload) {
  const pb = b64u(JSON.stringify(payload));
  const sig = await hmacHex(env.WEAUATO_SIGN_SECRET, new TextEncoder().encode("weauto-billing-v1|" + pb));
  return pb + "." + sig;
}
__name(signBillingToken, "signBillingToken");
__name2(signBillingToken, "signBillingToken");
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
__name(verifyBillingToken, "verifyBillingToken");
__name2(verifyBillingToken, "verifyBillingToken");
function monthKey(d) {
  d = d || /* @__PURE__ */ new Date();
  return d.getUTCFullYear() + "-" + String(d.getUTCMonth() + 1).padStart(2, "0");
}
__name(monthKey, "monthKey");
__name2(monthKey, "monthKey");
function nextMonthStart() {
  const d = /* @__PURE__ */ new Date();
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 1, 0, 0, 0, 0);
}
__name(nextMonthStart, "nextMonthStart");
__name2(nextMonthStart, "nextMonthStart");
async function readUsage(env, sub) {
  if (!env.BILLING_KV) return 0;
  const raw = await env.BILLING_KV.get("usage:" + sub);
  if (!raw) return 0;
  try {
    const o = JSON.parse(raw);
    if (o.month !== monthKey()) return 0;
    return o.tokens || 0;
  } catch (_) {
    return 0;
  }
}
__name(readUsage, "readUsage");
__name2(readUsage, "readUsage");
async function addUsage(env, sub, n) {
  if (!env.BILLING_KV) return;
  const m = monthKey();
  const raw = await env.BILLING_KV.get("usage:" + sub);
  let tokens = 0;
  if (raw) {
    try {
      const o = JSON.parse(raw);
      if (o.month === m) tokens = o.tokens || 0;
    } catch (_) {
    }
  }
  tokens += n;
  await env.BILLING_KV.put("usage:" + sub, JSON.stringify({ month: m, tokens }), { expirationTtl: 33 * 24 * 3600 });
}
__name(addUsage, "addUsage");
__name2(addUsage, "addUsage");
async function getAcct(env, sub) {
  if (!env.BILLING_KV) return null;
  const raw = await env.BILLING_KV.get("acct:" + sub);
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch (_) {
    return null;
  }
}
__name(getAcct, "getAcct");
__name2(getAcct, "getAcct");
async function setAcct(env, sub, tier) {
  if (!env.BILLING_KV) return;
  await env.BILLING_KV.put("acct:" + sub, JSON.stringify({ tier, createdAt: Date.now() }), { expirationTtl: 400 * 24 * 3600 });
}
__name(setAcct, "setAcct");
__name2(setAcct, "setAcct");
function billingHeaders(b) {
  const h = new Headers();
  h.set("X-Billing-Used", String(b.used));
  h.set("X-Billing-Quota", String(b.quota));
  h.set("X-Billing-Remain", String(Math.max(0, b.quota - b.used)));
  h.set("X-Billing-Tier", b.tier);
  return h;
}
__name(billingHeaders, "billingHeaders");
__name2(billingHeaders, "billingHeaders");
function estimateTokens(text) {
  return Math.ceil((text ? String(text).length : 0) / 4);
}
__name(estimateTokens, "estimateTokens");
__name2(estimateTokens, "estimateTokens");
var PROF_PREFIX = "prof:";
var PROF_TTL = 400 * 24 * 3600;
var DEVICE_ID_RE = /^[A-Za-z0-9_-]{4,64}$/;
var WXKEY_RE = /^wx_[a-f0-9]{16,64}$/;
var PERSONA_IDENTITY_FIELDS = [
  "nameCN",
  "title",
  "company",
  "city",
  "about",
  "companyDescription",
  "productDescription"
];
function ownerKeyOf(wxKey, deviceId) {
  const w = String(wxKey || "").trim();
  if (WXKEY_RE.test(w)) return "wx:" + w;
  const d = String(deviceId || "").trim();
  if (DEVICE_ID_RE.test(d)) return "dev:" + d;
  return "";
}
__name(ownerKeyOf, "ownerKeyOf");
__name2(ownerKeyOf, "ownerKeyOf");
function planInfoFor(tier) {
  const t = String(tier || "").trim();
  if (!t) return null;
  if (TIERS[t]) {
    return { tier: t, name: TIERS[t].name, source: "apk_token", tokens: TIERS[t].tokens, price_text: TIERS[t].price_text };
  }
  const CREEM_LABEL = { normal: "\u521D\u7EA7\u5957\u9910", premium: "\u4E2D\u7EA7\u5957\u9910", lifetime: "\u9AD8\u7EA7\u5957\u9910" };
  if (CREEM_LABEL[t]) return { tier: t, name: CREEM_LABEL[t], source: "creem_license" };
  return null;
}
__name(planInfoFor, "planInfoFor");
__name2(planInfoFor, "planInfoFor");
async function readProfile(env, owner) {
  if (!env.BILLING_KV || !owner) return null;
  const raw = await env.BILLING_KV.get(PROF_PREFIX + owner);
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch (_) {
    return null;
  }
}
__name(readProfile, "readProfile");
__name2(readProfile, "readProfile");
async function writeProfile(env, owner, prof) {
  if (!env.BILLING_KV || !owner) return false;
  prof.owner = owner;
  prof.updatedAt = Date.now();
  await env.BILLING_KV.put(PROF_PREFIX + owner, JSON.stringify(prof), { expirationTtl: PROF_TTL });
  return true;
}
__name(writeProfile, "writeProfile");
__name2(writeProfile, "writeProfile");
function blankProfile(owner, wxKey) {
  return {
    owner,
    wxKey: wxKey || "",
    tier: "",
    plan: null,
    persona: null,
    personaRev: 0,
    devices: [],
    createdAt: Date.now(),
    updatedAt: Date.now()
  };
}
__name(blankProfile, "blankProfile");
__name2(blankProfile, "blankProfile");
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
__name(touchDevice, "touchDevice");
__name2(touchDevice, "touchDevice");
function sanitizePersona(p) {
  if (!p || typeof p !== "object") return null;
  const srcId = p.identity && typeof p.identity === "object" ? p.identity : {};
  const srcSt = p.settings && typeof p.settings === "object" ? p.settings : {};
  const identity = {};
  for (const k of PERSONA_IDENTITY_FIELDS) identity[k] = String(srcId[k] ?? "").slice(0, 500);
  return {
    identity,
    settings: {
      enabled: !!srcSt.enabled,
      personaPrompt: String(srcSt.personaPrompt ?? "").slice(0, 4e3),
      chatPurpose: String(srcSt.chatPurpose ?? "").slice(0, 500)
    },
    styleText: String(p.styleText ?? "").slice(0, 4e3)
  };
}
__name(sanitizePersona, "sanitizePersona");
__name2(sanitizePersona, "sanitizePersona");
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
    personaUpdatedAt: Number(persona && persona.updatedAt || prof.personaRev || 0),
    personaSource: persona && persona.sourcePlatform || "",
    devices: prof.devices || [],
    updatedAt: prof.updatedAt || 0
  };
}
__name(profState, "profState");
__name2(profState, "profState");
async function deviceAuth(req, env, rawBody) {
  const auth = String(req.headers.get("authorization") || "").trim();
  const bm = /^bearer\s+(.+)$/i.exec(auth);
  if (bm && env.WEAUATO_SIGN_SECRET) {
    const payload = await verifyBillingToken(env, bm[1].trim());
    if (payload) return { ok: true, kind: "token", sub: payload.sub || "", tier: payload.tier || "" };
  }
  const key = String(req.headers.get("x-weauto-key") || "").trim();
  const inst = String(req.headers.get("x-weauto-instance") || "").trim();
  if (key && inst && env.CREEM_API_KEY) {
    if (await licenseOk(env, key, inst, false)) return { ok: true, kind: "license", sub: "", tier: "" };
  }
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
  if (!env.WEAUATO_SIGN_SECRET && !env.CREEM_API_KEY && !env.WEAUATO_CLIENT_SECRET) {
    return { ok: true, kind: "open", sub: "", tier: "" };
  }
  return { ok: false, kind: "none" };
}
__name(deviceAuth, "deviceAuth");
__name2(deviceAuth, "deviceAuth");
async function deviceUnauthorized() {
  return json({ ok: false, error: "unauthorized", hint: "\u9700\u8981 Bearer \u8D26\u6237\u4EE4\u724C / \u5361\u5BC6 / HMAC \u7B7E\u540D\u4E4B\u4E00" }, 401);
}
__name(deviceUnauthorized, "deviceUnauthorized");
__name2(deviceUnauthorized, "deviceUnauthorized");
async function deviceState(req, env, url) {
  const owner = ownerKeyOf(url.searchParams.get("wx"), url.searchParams.get("device"));
  if (!owner) return json({ ok: false, error: "bad_identity" }, 400);
  const auth = await deviceAuth(req, env, "");
  if (!auth.ok) return deviceUnauthorized();
  let prof = await readProfile(env, owner);
  if (!prof) prof = blankProfile(owner, url.searchParams.get("wx") || "");
  return json(profState(prof));
}
__name(deviceState, "deviceState");
__name2(deviceState, "deviceState");
async function deviceHello(req, env, url) {
  const raw = await req.text();
  let body;
  try {
    body = JSON.parse(raw || "{}");
  } catch (_) {
    return json({ ok: false, error: "bad_json" }, 400);
  }
  const wxKey = String(body.wxKey || url.searchParams.get("wx") || "").trim();
  const deviceId = String(body.deviceId || req.headers.get("x-weauto-instance") || "").trim();
  const owner = ownerKeyOf(wxKey, deviceId);
  if (!owner) return json({ ok: false, error: "bad_identity" }, 400);
  const auth = await deviceAuth(req, env, raw);
  if (!auth.ok) return deviceUnauthorized();
  let prof = await readProfile(env, owner) || blankProfile(owner, wxKey);
  if (wxKey && WXKEY_RE.test(wxKey)) prof.wxKey = wxKey;
  touchDevice(prof, deviceId, body.platform, body.appVer);
  const acct = await getAcct(env, auth.sub);
  let tier = auth.tier || acct && acct.tier || "";
  if (!tier && auth.kind === "license") {
    const lk = String(body.licenseKey || req.headers.get("x-weauto-key") || "").trim();
    const li = String(body.licenseInstanceId || deviceId).trim();
    if (lk && li && env.CREEM_API_KEY) {
      try {
        const { status, data } = await creemPost(env, "/licenses/validate", { key: lk, instance_id: li });
        if (status >= 200 && status < 300 && data && data.status === "active") tier = mapTier(env, data) || "";
      } catch (_) {
      }
    }
  }
  if (tier) {
    prof.tier = tier;
    prof.plan = planInfoFor(tier);
  }
  await writeProfile(env, owner, prof);
  return json(Object.assign(profState(prof), { auth_kind: auth.kind, deviceId }));
}
__name(deviceHello, "deviceHello");
__name2(deviceHello, "deviceHello");
async function devicePutPersona(req, env, url) {
  const raw = await req.text();
  let body;
  try {
    body = JSON.parse(raw || "{}");
  } catch (_) {
    return json({ ok: false, error: "bad_json" }, 400);
  }
  const wxKey = String(body.wxKey || url.searchParams.get("wx") || "").trim();
  const deviceId = String(body.deviceId || req.headers.get("x-weauto-instance") || "").trim();
  const owner = ownerKeyOf(wxKey, deviceId);
  if (!owner) return json({ ok: false, error: "bad_identity" }, 400);
  const auth = await deviceAuth(req, env, raw);
  if (!auth.ok) return deviceUnauthorized();
  const persona = sanitizePersona(body.persona);
  if (!persona) return json({ ok: false, error: "bad_persona" }, 400);
  const rev = Number.isFinite(Number(body.rev)) ? Number(body.rev) : Date.now();
  let prof = await readProfile(env, owner) || blankProfile(owner, wxKey);
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
__name(devicePutPersona, "devicePutPersona");
__name2(devicePutPersona, "devicePutPersona");
async function deviceGetPersona(req, env, url) {
  const owner = ownerKeyOf(url.searchParams.get("wx"), url.searchParams.get("device"));
  if (!owner) return json({ ok: false, error: "bad_identity" }, 400);
  const auth = await deviceAuth(req, env, "");
  if (!auth.ok) return deviceUnauthorized();
  const prof = await readProfile(env, owner);
  return json({
    ok: true,
    owner,
    persona: prof && prof.persona || null,
    personaRev: Number(prof && prof.personaRev || 0)
  });
}
__name(deviceGetPersona, "deviceGetPersona");
__name2(deviceGetPersona, "deviceGetPersona");
async function devicePutPlan(req, env, url) {
  const raw = await req.text();
  let body;
  try {
    body = JSON.parse(raw || "{}");
  } catch (_) {
    return json({ ok: false, error: "bad_json" }, 400);
  }
  const wxKey = String(body.wxKey || url.searchParams.get("wx") || "").trim();
  const deviceId = String(body.deviceId || req.headers.get("x-weauto-instance") || "").trim();
  const owner = ownerKeyOf(wxKey, deviceId);
  if (!owner) return json({ ok: false, error: "bad_identity" }, 400);
  const auth = await deviceAuth(req, env, raw);
  if (!auth.ok) return deviceUnauthorized();
  const lk = String(body.licenseKey || req.headers.get("x-weauto-key") || "").trim();
  const li = String(body.licenseInstanceId || deviceId).trim();
  if (!lk || !li) return json({ ok: false, error: "missing_license" }, 400);
  if (!env.CREEM_API_KEY) return json({ ok: false, error: "creem_not_configured" }, 503);
  const { status, data } = await creemPost(env, "/licenses/validate", { key: lk, instance_id: li });
  if (!(status >= 200 && status < 300) || !data || data.status !== "active") {
    return json({ ok: false, error: "license_not_active" }, 403);
  }
  const tier = mapTier(env, data) || "";
  let prof = await readProfile(env, owner) || blankProfile(owner, wxKey);
  if (wxKey && WXKEY_RE.test(wxKey)) prof.wxKey = wxKey;
  touchDevice(prof, deviceId, body.platform, body.appVer);
  prof.tier = tier;
  prof.plan = planInfoFor(tier);
  prof.license = { expiresAt: expiresToUnix(data.expires_at) || 0, updatedAt: Date.now() };
  await writeProfile(env, owner, prof);
  return json({ ok: true, tier, plan: prof.plan, expiresAt: prof.license.expiresAt });
}
__name(devicePutPlan, "devicePutPlan");
__name2(devicePutPlan, "devicePutPlan");
async function proxyAiBilled(req, env, url, token) {
  const payload = await verifyBillingToken(env, token);
  if (!payload) {
    return json({ error: { message: "\u8D26\u6237\u4EE4\u724C\u65E0\u6548\u6216\u5DF2\u8FC7\u671F", type: "auth_error", code: "bad_token" } }, 401);
  }
  const tierInfo = TIERS[payload.tier] || TIERS[DEFAULT_TIER];
  const quota = tierInfo.tokens;
  const used = await readUsage(env, payload.sub);
  if (used >= quota) {
    return json({
      error: { message: "\u672C\u6708\u7B97\u529B\u5DF2\u7528\u5C3D\uFF0C\u8BF7\u5347\u7EA7\u5957\u9910\u6216\u7B49\u5F85\u4E0B\u6708\u91CD\u7F6E", type: "quota_error", code: "quota_exceeded", used, quota }
    }, 402);
  }
  const resp = await routeAi(req, env, url);
  let cost = 0;
  const ct = resp.headers.get("content-type") || "";
  if (resp.status === 200 && ct.includes("application/json") && !ct.includes("text/event-stream")) {
    try {
      const j = await resp.clone().json();
      cost = j && j.usage && (j.usage.total_tokens || j.usage.totalTokens) || 0;
    } catch (_) {
    }
  }
  if (!cost) {
    let ub = "";
    try {
      ub = await resp.clone().text();
    } catch (_) {
    }
    cost = estimateTokens(ub);
  }
  cost = Math.max(1, Math.round(cost));
  await addUsage(env, payload.sub, cost);
  const h = billingHeaders({ used: used + cost, quota, tier: payload.tier });
  return new Response(resp.body, { status: resp.status, headers: h });
}
__name(proxyAiBilled, "proxyAiBilled");
__name2(proxyAiBilled, "proxyAiBilled");
function esc(s) {
  return String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#39;"
  })[c]);
}
__name(esc, "esc");
__name2(esc, "esc");
function html(body, status = 200) {
  return new Response(body, {
    status,
    headers: {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "no-store"
    }
  });
}
__name(html, "html");
__name2(html, "html");
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
.feats li:before{content:"\u2713";position:absolute;left:0;color:#1677ff;font-weight:700}
.warn{background:#fff7e6;border:1px solid #ffd591;border-radius:10px;padding:14px;margin-top:16px;font-size:14px;color:#874d00}
.foot{text-align:center;color:#9ca3af;font-size:12px;margin-top:26px;display:none}
code{background:#f3f4f6;padding:2px 6px;border-radius:4px;font-size:13px}
</style>
</head>
<body>
<div class="wrap">${inner}</div>
<div class="foot" style="margin-top:26px">
  <a href="/privacy" style="color:#374151;text-decoration:underline">Privacy Policy</a> \xB7
  <a href="/terms" style="color:#374151;text-decoration:underline">Terms of Service</a> \xB7
  Payments &amp; refunds handled by Creem (Merchant of Record)<br>
  <span style="color:#9ca3af">\u9690\u79C1\u653F\u7B56 \xB7 \u670D\u52A1\u6761\u6B3E \xB7 \u652F\u4ED8\u4E0E\u9000\u6B3E\u7531 Creem \u5904\u7406</span>
</div>
</body>
</html>`;
}
__name(layout, "layout");
__name2(layout, "layout");
function privacyPage(env) {
  const support = env.SUPPORT_EMAIL;
  const contact = support ? `<a href="mailto:${esc(support)}">${esc(support)}</a>` : "our support email";
  return html(layout("Privacy Policy \xB7 \u9690\u79C1\u653F\u7B56", `
<div class="hd"><h1>Privacy Policy <span style="color:#6b7280;font-size:18px">/ \u9690\u79C1\u653F\u7B56</span></h1>
<p>WeAuto \xB7 weauto-app.pages.dev \xB7 Last updated: 2026-10-14</p></div>

<div class="card">
  <h3 style="margin-top:0">1. What We Collect\uFF08\u6211\u4EEC\u6536\u96C6\u4EC0\u4E48\uFF09</h3>
  <p>WeAuto is desktop software that runs <b>on your own computer</b>. To provide licensing and
  payment services, we collect:</p>
  <ul>
    <li><b>License key &amp; device instance ID</b> \u2014 to validate your license and enforce device limits.</li>
    <li><b>Email address</b> \u2014 provided by our payment processor Creem, used only for receipts and license delivery.</li>
    <li><b>AI usage counters</b> \u2014 token consumption numbers only. <b>We never record chat content.</b></li>
  </ul>
</div>
<div class="card">
  <h3 style="margin-top:0">2. What We Do NOT Collect\uFF08\u6211\u4EEC\u4E0D\u6536\u96C6\u4EC0\u4E48\uFF09</h3>
  <p>This website does <b>not</b> store or upload your WeChat chat history, contacts, images, or voice
  messages. AI reply requests go directly from your local client to the model gateway; this site only
  performs license validation and usage quota counting.</p>
</div>
<div class="card">
  <h3 style="margin-top:0">3. Payment Data &amp; Third Parties\uFF08\u652F\u4ED8\u6570\u636E\u4E0E\u7B2C\u4E09\u65B9\uFF09</h3>
  <p>Payments and refunds are handled by <b>Creem (Merchant of Record)</b>. Card/payment details are
  processed by Creem and never touch our servers \u2014 see
  <a href="https://www.creem.io/privacy" rel="noopener">creem.io/privacy</a>.
  We do not sell your data to anyone.</p>
</div>
<div class="card">
  <h3 style="margin-top:0">4. Data Retention &amp; Deletion\uFF08\u6570\u636E\u4FDD\u7559\u4E0E\u5220\u9664\uFF09</h3>
  <p>License and device records are kept while your license is active. To delete your data
  (license key / device ID / email), contact ${contact} and we will remove it within 7 days.</p>
</div>
<div class="card">
  <h3 style="margin-top:0">\u9690\u79C1\u653F\u7B56\uFF08\u4E2D\u6587\u6458\u8981\uFF09</h3>
  <p style="color:#374151;font-size:14px">WeAuto \u8FD0\u884C\u4E8E\u4F60\u81EA\u5DF1\u7684\u7535\u8111\u3002\u4E3A\u63D0\u4F9B\u6388\u6743\u4E0E\u4ED8\u8D39\u670D\u52A1\uFF0C\u6211\u4EEC\u4EC5\u6536\u96C6\uFF1A
  \u5361\u5BC6\u4E0E\u8BBE\u5907\u5B9E\u4F8B ID\u3001\u7531 Creem \u63D0\u4F9B\u7684\u90AE\u7BB1\u3001AI \u7528\u91CF\u8BA1\u6570\uFF08\u4E0D\u542B\u804A\u5929\u5185\u5BB9\uFF09\u3002\u672C\u7AD9\u4E0D\u5B58\u50A8/\u4E0D\u4E0A\u4F20\u5FAE\u4FE1\u804A\u5929\u8BB0\u5F55\u3001
  \u8054\u7CFB\u4EBA\u3001\u56FE\u7247\u6216\u8BED\u97F3\u3002\u652F\u4ED8\u4E0E\u9000\u6B3E\u7531 Creem\uFF08Merchant of Record\uFF09\u5904\u7406\uFF0C\u5361\u7247\u4FE1\u606F\u4E0D\u7ECF\u6211\u65B9\u670D\u52A1\u5668\u3002
  \u6211\u4EEC\u4E0D\u51FA\u552E\u4F60\u7684\u6570\u636E\u3002\u5982\u9700\u5220\u9664\u6388\u6743\u6570\u636E\uFF0C\u8BF7\u8054\u7CFB\u5BA2\u670D\u90AE\u7BB1\uFF0C7 \u5929\u5185\u5904\u7406\u3002</p>
</div>`));
}
__name(privacyPage, "privacyPage");
__name2(privacyPage, "privacyPage");
function termsPage(env) {
  return html(layout("Terms of Service \xB7 \u670D\u52A1\u6761\u6B3E", `
<div class="hd"><h1>Terms of Service <span style="color:#6b7280;font-size:18px">/ \u670D\u52A1\u6761\u6B3E</span></h1>
<p>WeAuto \xB7 Effective date: 2026-10-02</p></div>

<div class="card">
  <h3 style="margin-top:0">1. The Service\uFF08\u670D\u52A1\u5185\u5BB9\uFF09</h3>
  <p>WeAuto is desktop automation software for WeChat, licensed per device. After purchase you
  activate it with a license key on the number of devices stated on the product page.</p>
</div>
<div class="card">
  <h3 style="margin-top:0">2. Subscription Terms\uFF08\u8BA2\u9605\u6761\u6B3E\uFF09</h3>
  <p>Monthly plans are <b>auto-renewing subscriptions</b>, billed each month until cancelled.
  You may cancel anytime from the Creem receipt email or the Creem customer portal; access continues
  until the end of the current billing period. One-time (lifetime) plans never renew.</p>
</div>
<div class="card">
  <h3 style="margin-top:0">3. Refund Policy\uFF08\u9000\u6B3E\u653F\u7B56\uFF09</h3>
  <ul>
    <li><b>One-time purchases</b>: full refund within 7 days if activation fails and the issue
    cannot be resolved by our support.</li>
    <li><b>Monthly subscriptions</b>: the current period is generally non-refundable; duplicate or
    erroneous charges are refunded in full after verification.</li>
    <li>Refunds are issued via Creem (Merchant of Record) to the original payment method.</li>
  </ul>
</div>
<div class="card">
  <h3 style="margin-top:0">4. License Usage Rules\uFF08\u8BB8\u53EF\u4F7F\u7528\u89C4\u5219\uFF09</h3>
  <p>The license is personal to you. You may <b>not</b> resell, share your license key, reverse
  engineer, circumvent license validation, or use the software for any unlawful purpose (including
  harassment, fraud, or spam). Violation allows us to terminate the license without refund.</p>
</div>
<div class="card">
  <h3 style="margin-top:0">5. Disclaimer &amp; Acceptable Use\uFF08\u514D\u8D23\u58F0\u660E\uFF09</h3>
  <p>The software is provided "as is". You are responsible for complying with the WeChat Terms of
  Service when using automation features; account restrictions caused by misuse are your own risk.</p>
</div>
<div class="card">
  <h3 style="margin-top:0">\u670D\u52A1\u6761\u6B3E\uFF08\u4E2D\u6587\u6458\u8981\uFF09</h3>
  <p style="color:#374151;font-size:14px">WeAuto \u4E3A\u6309\u8BBE\u5907\u6388\u6743\u7684\u672C\u673A\u8F6F\u4EF6\u3002\u4E09\u6863\uFF08\u521D\u7EA7 / \u4E2D\u7EA7 / \u9AD8\u7EA7 \u5957\u9910\u8D39\uFF09\u5747\u4E3A
  <b>\u4E00\u6B21\u6027\u8D2D\u4E70</b>\uFF0C\u8D2D\u4E00\u6B21\u83B7\u5F97\u5BF9\u5E94\u6863\u4F4D <b>valid_days \u5929</b>\u6388\u6743\uFF0C\u5230\u671F\u524D\u8F6F\u4EF6\u4F1A\u5728\u6388\u6743\u9762\u677F\u4E0E\u5FAE\u4FE1\u91CC\u63D0\u793A\u7EED\u8D39\uFF0C
  \u7EED\u8D39\u9700\u518D\u6B21\u8D2D\u4E70\u3002\u53EF\u968F\u65F6\u5728 Creem \u6536\u636E\u90AE\u4EF6 / \u5BA2\u6237\u95E8\u6237\u67E5\u770B\u8BA2\u5355\uFF1B\u4E00\u6B21\u6027\u8D2D\u4E70\u539F\u5219\u4E0A\u4E0D\u81EA\u52A8\u7EED\u8BA2\u3001\u4E0D\u91CD\u590D\u6263\u6B3E\u3002
  \u9000\u6B3E\uFF1A\u8D2D\u4E70\u540E 7 \u5929\u5185\u4E14\u6FC0\u6D3B\u9047\u65E0\u6CD5\u89E3\u51B3\u7684\u6280\u672F\u95EE\u9898\u53EF\u5168\u989D\u9000\u6B3E\uFF1B\u91CD\u590D / \u8BEF\u6263\u5168\u989D\u9000\u3002
  \u8BB8\u53EF\u89C4\u5219\uFF1A\u7981\u6B62\u8F6C\u552E\u3001\u5171\u4EAB\u5361\u5BC6\u3001\u9006\u5411\u5DE5\u7A0B\u3001\u7ED5\u8FC7\u6821\u9A8C\u3001\u7528\u4E8E\u8FDD\u6CD5\u8FDD\u89C4\u7528\u9014\uFF0C\u8FDD\u8005\u7EC8\u6B62\u6388\u6743\u4E0D\u4E88\u9000\u6B3E\u3002
  \u4F7F\u7528\u81EA\u52A8\u5316\u529F\u80FD\u8BF7\u9075\u5B88\u5FAE\u4FE1\u7528\u6237\u534F\u8BAE\u3002</p>
</div>`));
}
__name(termsPage, "termsPage");
__name2(termsPage, "termsPage");
function buyPage(env, origin, mid, email) {
  const products = parseProducts(env);
  const title = env.SITE_TITLE || "WeAuto \u6388\u6743\u4E2D\u5FC3";
  const support = env.SUPPORT_EMAIL;
  const carry = (mid ? "&mid=" + encodeURIComponent(mid) : "") + (looksLikeEmail(email) ? "&email=" + encodeURIComponent(email.trim()) : "");
  const cards = products.map((p) => {
    const badge = p.billing === "monthly" ? "\u6708\u79DF\u8BA2\u9605" : "\u4E00\u6B21\u6027\u5957\u9910";
    const go = origin + "/buy?go=1&tier=" + encodeURIComponent(p.tier) + carry;
    const feats = (p.features || "").split(/[+；;]/).map((f) => f.trim()).filter(Boolean);
    const featsHtml = feats.length ? '<ul class="feats">' + feats.map((f) => `<li>${esc(f)}</li>`).join("") + "</ul>" : "";
    return `<div class="card tier">
      <span class="tag">${esc(badge)}</span>
      <h3 style="margin:14px 0 0">${esc(p.label)}</h3>
      <div class="price">${esc(p.price_text)}</div>
      ${featsHtml}
      <a class="btn" href="${esc(go)}">\u652F\u4ED8\u5B9D\u4ED8\u6B3E \xB7 \u7ACB\u5373\u8D2D\u4E70</a>
    </div>`;
  }).join("");
  return layout(title, `
<div class="hd">
  <h1>${esc(title)}</h1>
  <p>\u5FAE\u4FE1\u673A\u5668\u4EBA \xB7 \u5B98\u65B9\u6388\u6743</p>
</div>
${cards}
<div class="card">
  <h3 style="margin-top:0">\u8D2D\u4E70\u540E\u600E\u4E48\u7528</h3>
  <ol>
    <li>\u9009\u4E0A\u65B9\u6863\u4F4D\u70B9\u300C\u7ACB\u5373\u8D2D\u4E70\u300D\uFF0C\u5728\u6536\u94F6\u53F0\u7528 <b>\u652F\u4ED8\u5B9D</b>\u6216\u94F6\u884C\u5361\u5B8C\u6210\u4ED8\u6B3E\u3002</li>
    <li><b>\u4EC0\u4E48\u90FD\u4E0D\u7528\u505A</b>\uFF1A\u4ED8\u6B3E\u6210\u529F\u540E\u672C\u673A\u4F1A\u81EA\u52A8\u83B7\u53D6\u6388\u6743\u5E76\u7ACB\u5373\u751F\u6548\u3002</li>
  </ol>
</div>
<div class="card">
  <h3 style="margin-top:0">\u5E38\u89C1\u95EE\u9898</h3>
  <p><b>\u4E00\u53F0\u7535\u8111\u80FD\u7528\u51E0\u6B21\uFF1F</b><br>\u6309\u4EA7\u54C1\u8BBE\u7F6E\u7684\u8BBE\u5907\u6570\uFF08\u901A\u5E38 1\u20132 \u53F0\uFF09\u3002\u6362\u673A\u524D\u8BF7\u5728\u6388\u6743\u7BA1\u7406\u91CC\u300C\u91CA\u653E\u672C\u673A\u300D\u3002</p>
  <p><b>\u5957\u9910\u8D39\u662F\u8BA2\u9605\u5417\uFF1F\u4F1A\u81EA\u52A8\u6263\u8D39\u5417\uFF1F</b><br>\u4E0D\u4F1A\u3002\u4E09\u6863\uFF08\u521D\u7EA7/\u4E2D\u7EA7/\u9AD8\u7EA7 \u5957\u9910\u8D39\uFF09\u5747\u4E3A<b>\u4E00\u6B21\u6027\u8D2D\u4E70</b>\uFF0C\u4ED8\u6B3E\u5373\u5F97\u5BF9\u5E94\u5929\u6570\u6388\u6743\uFF0830 \u5929 / 30 \u5929 / 365 \u5929\uFF09\uFF0C<b>\u4E0D\u81EA\u52A8\u7EED\u8D39\u3001\u4E0D\u4E8C\u6B21\u6263\u6B3E</b>\u3002\u5230\u671F\u524D\u672C\u8F6F\u4EF6\u4F1A\u5728\u6388\u6743\u9762\u677F\u4E0E\u5FAE\u4FE1\u91CC\u63D0\u793A\u7EED\u8D39\uFF0C\u7EED\u8D39\u9700\u518D\u6B21\u8D2D\u4E70\u3002</p>
  <p><b>\u65AD\u7F51\u4F1A\u5931\u6548\u5417\uFF1F</b><br>\u4E0D\u4F1A\u3002\u9996\u6B21\u6FC0\u6D3B\u540E\u6709 7 \u5929\u79BB\u7EBF\u5BBD\u9650\u3002</p>
  <p><b>\u5361\u5BC6\u4E22\u4E86\uFF1F</b><br>\u4E0D\u7528\u627E\u5361\u5BC6\uFF1A\u672C\u673A\u81EA\u52A8\u6838\u9500\u3002\u82E5\u8981\u51ED\u636E\uFF08\u5982\u6362\u673A\uFF09\uFF0C\u4ED8\u6B3E\u90AE\u7BB1\u91CC\u6709 Creem \u6536\u636E\u4E0E\u5361\u5BC6${support ? `\uFF1B\u4E5F\u53EF\u8054\u7CFB <a href="mailto:${esc(support)}">${esc(support)}</a>` : ""}\u3002</p>
</div>
<div class="foot">\u672C\u9875\u7531 WeAuto License Worker \u63D0\u4F9B \xB7 \u652F\u4ED8\u7531 Creem\uFF08Merchant of Record\uFF09\u5904\u7406</div>`);
}
__name(buyPage, "buyPage");
__name2(buyPage, "buyPage");
function successPage(env) {
  const name = env.PRODUCT_NAME || "WeAuto \u6388\u6743";
  const support = env.SUPPORT_EMAIL;
  return layout("\u4ED8\u6B3E\u6210\u529F", `
<div class="hd"><h1>\u4ED8\u6B3E\u6210\u529F</h1><p>\u611F\u8C22\u8D2D\u4E70 ${esc(name)}</p></div>
<div class="card">
  <h3 style="margin-top:0">\u4E0B\u4E00\u6B65</h3>
  <ol>
    <li><b>\u4EC0\u4E48\u90FD\u4E0D\u7528\u505A</b>\uFF1A\u672C\u673A\u4F1A\u81EA\u52A8\u83B7\u53D6\u6388\u6743\u5E76\u7ACB\u5373\u751F\u6548\uFF0C\u65E0\u9700\u590D\u5236\u7C98\u8D34\u5361\u5BC6\u3002</li>
    <li>\u82E5\u51E0\u79D2\u540E\u4ECD\u672A\u751F\u6548\uFF0C\u56DE\u5230\u8F6F\u4EF6\u70B9\u4E00\u4E0B\u300C\u5237\u65B0\u6388\u6743\u72B6\u6001\u300D\u5373\u53EF\u3002</li>
  </ol>
  ${support ? `<p style="color:#6b7280;font-size:13px">\u6CA1\u6536\u5230\u5361\u5BC6\uFF1F\u8054\u7CFB <a href="mailto:${esc(support)}">${esc(support)}</a></p>` : ""}
</div>
<div class="foot">\u652F\u4ED8\u4E0E\u5F00\u7968\u7531 Creem \u5904\u7406</div>`);
}
__name(successPage, "successPage");
__name2(successPage, "successPage");
function notConfiguredPage(note) {
  const extra = note ? `<p style="color:#874d00">${esc(note)}</p>` : "";
  return html(layout("\u6682\u672A\u5F00\u653E\u8D2D\u4E70", `
<div class="hd"><h1>\u6682\u672A\u5F00\u653E\u8D2D\u4E70</h1><p>\u5546\u5BB6\u6B63\u5728\u914D\u7F6E\u652F\u4ED8</p></div>
<div class="card">
  <p>\u672C\u6388\u6743\u7AD9\u70B9\u5C1A\u672A\u5B8C\u6210\u652F\u4ED8\u914D\u7F6E\uFF0C\u6682\u65F6\u65E0\u6CD5\u8D2D\u4E70\u3002</p>
  ${extra}
  <p style="color:#6b7280;font-size:14px">\u5982\u679C\u4F60\u662F\u7AD9\u70B9\u7BA1\u7406\u5458\uFF1A\u8BF7\u5728 <code>wrangler.toml</code> \u7684
  <code>[vars]</code> \u4E2D\u914D\u7F6E <code>CREEM_PRODUCTS</code>\uFF08JSON \u6570\u7EC4\uFF0C\u542B\u5404\u6863 product_id\uFF09\uFF0C
  \u5E76\u7528 <code>wrangler secret put CREEM_API_KEY</code> \u6CE8\u5165 API key\uFF0C
  \u7136\u540E\u91CD\u65B0 <code>wrangler deploy</code>\u3002</p>
</div>`), 503);
}
__name(notConfiguredPage, "notConfiguredPage");
__name2(notConfiguredPage, "notConfiguredPage");
function looksLikeEmail(s) {
  if (!s || typeof s !== "string") return false;
  if (s.length > 254 || /\s/.test(s)) return false;
  return /^[^@]+@[^@.]+(\.[^@.]+)+$/.test(s.trim());
}
__name(looksLikeEmail, "looksLikeEmail");
__name2(looksLikeEmail, "looksLikeEmail");
async function resolveCheckout(env, tier, mid, email, origin) {
  if (env.CREEM_CHECKOUT_URL) return env.CREEM_CHECKOUT_URL;
  const products = parseProducts(env);
  const p = products.find((x) => x.tier === tier) || products[0];
  if (!p || !p.product_id || !env.CREEM_API_KEY) return null;
  const metadata = { source: "weauto-license-worker", tier: p.tier };
  if (mid) metadata.mid = mid;
  const payload = { product_id: p.product_id, metadata };
  if (typeof origin === "string" && origin.startsWith("http")) {
    payload.success_url = origin + "/buy?done=1";
  }
  const mail = looksLikeEmail(email) ? email.trim() : "";
  if (mail) payload.customer = { email: mail };
  const res = await creemPost(env, "/checkouts", payload);
  if (upstreamError(res)) return null;
  if (res.status >= 200 && res.status < 300 && res.data && res.data.checkout_url) {
    return res.data.checkout_url;
  }
  console.error("create checkout failed:", res.status, JSON.stringify(res.data).slice(0, 200));
  return null;
}
__name(resolveCheckout, "resolveCheckout");
__name2(resolveCheckout, "resolveCheckout");
var JEV_MODEL_DEFAULT = "@cf/meta/llama-4-scout-17b-16e-instruct";
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
        description: desc
      };
    } else {
      continue;
    }
    required.push(key);
  }
  return { type: "object", properties, required };
}
__name(jevJsonSchema, "jevJsonSchema");
__name2(jevJsonSchema, "jevJsonSchema");
function jevSystemPrompt(questions, state) {
  const rel = state && state.chat && state.chat.relationship || "unknown";
  const lines = [
    "You are a judgement model. You NEVER write replies or chat text.",
    "Answer every question strictly from the conversation, using the criteria given.",
    "Instructions and criteria are authoritative; even when uncertain, answer with the closest option.",
    "",
    "Relationship: " + rel,
    "",
    "Questions (answer every one):"
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
__name(jevSystemPrompt, "jevSystemPrompt");
__name2(jevSystemPrompt, "jevSystemPrompt");
function jevUserPrompt(state) {
  const chat = state && state.chat || {};
  const msgs = Array.isArray(chat.messages) ? chat.messages.slice(-20) : [];
  const out = msgs.map((m) => `${m && m.from === "me" ? "ME" : "THEM"}: ${String(m && m.text || "")}`).join("\n");
  return "Conversation (oldest first):\n" + out + "\n\nLatest message is from: " + String(chat.latest_from || "them");
}
__name(jevUserPrompt, "jevUserPrompt");
__name2(jevUserPrompt, "jevUserPrompt");
function jevExtractAnswers(res) {
  if (!res || typeof res !== "object") return null;
  let raw = res;
  if (res.response !== void 0) raw = res.response;
  else if (res.choices && res.choices[0] && res.choices[0].message) {
    raw = res.choices[0].message.content;
  }
  if (typeof raw !== "string") return raw && typeof raw === "object" ? raw : null;
  try {
    return JSON.parse(raw);
  } catch (_) {
    const m = raw.match(/\{[\s\S]*\}/);
    if (!m) return null;
    try {
      return JSON.parse(m[0]);
    } catch (_2) {
      return null;
    }
  }
}
__name(jevExtractAnswers, "jevExtractAnswers");
__name2(jevExtractAnswers, "jevExtractAnswers");
async function hmacHex(secret, msgBytes) {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const sig = await crypto.subtle.sign("HMAC", key, msgBytes);
  return Array.from(new Uint8Array(sig)).map((b) => b.toString(16).padStart(2, "0")).join("");
}
__name(hmacHex, "hmacHex");
__name2(hmacHex, "hmacHex");
function timingSafeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let r = 0;
  for (let i = 0; i < a.length; i++) r |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return r === 0;
}
__name(timingSafeEqual, "timingSafeEqual");
__name2(timingSafeEqual, "timingSafeEqual");
async function verifyClientIntegrity(req, env) {
  const expectedFp = (env.EXPECTED_SIG_FP || "").toLowerCase();
  if (!expectedFp) return null;
  const fp = (req.headers.get("x-sig-fp") || "").toLowerCase();
  const pkg = req.headers.get("x-pkg") || "";
  const instance = req.headers.get("x-instance") || "";
  const ts = req.headers.get("x-ts") || "";
  const hmac = (req.headers.get("x-hmac") || "").toLowerCase();
  if (fp !== expectedFp) {
    return json({ ok: false, error: "integrity_signature" }, 403);
  }
  if (pkg !== "com.jev.probe") {
    return json({ ok: false, error: "integrity_package" }, 403);
  }
  const tn = parseInt(ts, 10);
  if (!Number.isFinite(tn) || Math.abs(Date.now() - tn) > 3e5) {
    return json({ ok: false, error: "integrity_ts" }, 403);
  }
  const hmacKey = env.INTEGRITY_HMAC_KEY || "";
  if (hmacKey) {
    const url = new URL(req.url);
    const msg = req.method + "\n" + url.pathname + "\n" + ts + "\n" + fp + "\n" + pkg + "\n" + instance;
    const expect = await hmacHex(hmacKey, new TextEncoder().encode(msg));
    if (expect.length !== hmac.length || !timingSafeEqual(expect, hmac)) {
      return json({ ok: false, error: "integrity_hmac" }, 403);
    }
  }
  return null;
}
__name(verifyClientIntegrity, "verifyClientIntegrity");
__name2(verifyClientIntegrity, "verifyClientIntegrity");
/**
 * 双凭证闸门（2026-10-14 修复 Windows EXE 被 Android 完整性墙误伤）。
 * 背景：10-13 合入 Android L3 墙后，墙上路由（/license、/ai、/ai/*、/token/status）
 * 对 Windows EXE 全量 403 integrity_signature——EXE 发不出 Android 五头
 * （x-sig-fp 是 APK 证书指纹、x-pkg 硬编码 com.jev.probe），导致
 * /ai/jev/decisions（Jev 判断）与 /ai/v1（云 AI 代理）双双不可用。
 * 现在：EXPECTED_SIG_FP 未配 → fail-open（与原墙一致）；配了则先试 EXE 自有凭证，
 * 任一合法即放行（各路由内部还有二次校验：jevDecisions 验 HMAC、proxyAi 验卡密），
 * 全不合法才落回 Android 墙。Android 客户端行为零变化。
 * EXE 凭证三种（与客户端实现逐字节对齐）：
 *   ① HMAC 签名 x-weauto-ts/nonce/sig，密钥 WEAUATO_CLIENT_SECRET
 *      （jev_guard.py _sign：HMAC(secret,"POST\n<path>\n<ts>\n<nonce>\n"+body)，±60s）
 *   ② 卡密+实例：Authorization Bearer 卡密 + X-WeAuto-Instance
 *      （guard.py worker_ai_gate；licenseOk 内部复检，含 Creem 在线校验+缓存）
 *   ③ 计费令牌：Authorization Bearer <b64>.<sig> 或 ?token=（WEAUATO_SIGN_SECRET 签发）
 */
async function exeCredentialCheck(req, env) {
  const url = new URL(req.url);
  const authHdr = (req.headers.get("authorization") || "").trim();
  const token = authHdr.startsWith("Bearer ") ? authHdr.slice(7).trim() : authHdr;
  const instanceId = req.headers.get("x-weauto-instance") || "";
  const ts = req.headers.get("x-weauto-ts");
  const nonce = req.headers.get("x-weauto-nonce");
  const sig = req.headers.get("x-weauto-sig");
  if (ts && nonce && sig) {
    if (!env.WEAUATO_CLIENT_SECRET) return { ok: false, reason: "sig_unconfigured" };
    const tn = parseInt(ts, 10);
    const now = Math.floor(Date.now() / 1e3);
    if (!Number.isFinite(tn) || Math.abs(now - tn) > 60) return { ok: false, reason: "ts_expired" };
    let raw = "";
    try {
      raw = await req.clone().text();
    } catch (_) {
      raw = "";
    }
    const enc = new TextEncoder();
    const head = enc.encode("POST\n" + url.pathname + "\n" + ts + "\n" + nonce + "\n");
    const tail = enc.encode(raw);
    const full = new Uint8Array(head.length + tail.length);
    full.set(head, 0);
    full.set(tail, head.length);
    const expect = await hmacHex(env.WEAUATO_CLIENT_SECRET, full);
    if (expect.length !== sig.length || !timingSafeEqual(expect, sig)) return { ok: false, reason: "bad_sig" };
    return { ok: true };
  }
  if (ts || nonce || sig) return { ok: false, reason: "sig_incomplete" };
  const qTok = url.searchParams.get("token") || "";
  const billTok = token && looksLikeBillingToken(token) ? token : qTok && looksLikeBillingToken(qTok) ? qTok : "";
  if (billTok && env.WEAUATO_SIGN_SECRET) {
    if (await verifyBillingToken(env, billTok) != null) return { ok: true };
    if (instanceId) return { ok: false, reason: "license_required" };
    return { ok: false, reason: "no_credential" };
  }
  if (instanceId) {
    if (token === "license-pending") return { ok: false, reason: "license_pending" };
    if (token && env.CREEM_API_KEY && !looksLikeBillingToken(token)) {
      if (await licenseOk(env, token, instanceId)) return { ok: true };
    }
    return { ok: false, reason: "license_required" };
  }
  return { ok: false, reason: "no_credential" };
}
async function integrityGate(req, env) {
  const expectedFp = (env.EXPECTED_SIG_FP || "").toLowerCase();
  if (!expectedFp) return null;
  const chk = await exeCredentialCheck(req, env);
  if (chk.ok) return null;
  if (chk.reason === "license_pending" || chk.reason === "license_required"
      || chk.reason === "no_credential") {
    // 免费模式（2026-10-14 商业模式）：所有软件默认激活。仅「AI 代理回复
    // （/ai/v1）」在无凭证/卡密无效时放行到 proxyAi 的免费配额（每日条数云端限制）。
    // Jev（/ai/jev/decisions）与 /license 等路由保持原墙，防外部白嫖烧 AI 额度。
    // 带 Android 完整性头（x-sig-fp / x-pkg / x-instance / x-ts / x-hmac）的请求
    // 仍走原 Android 墙，防马甲包。
    const androidLike = req.headers.get("x-sig-fp") || req.headers.get("x-pkg")
      || req.headers.get("x-instance") || req.headers.get("x-ts") || req.headers.get("x-hmac");
    if (!androidLike && new URL(req.url).pathname.startsWith("/ai/v1")) return null;
    return verifyClientIntegrity(req, env);
  }
  if (chk.reason === "ts_expired") {
    return json({ error: { message: "客户端签名时间戳过期（±60s 有效），请检查本机系统时间", type: "auth_error", code: "ts_expired" } }, 401);
  }
  if (chk.reason === "bad_sig" || chk.reason === "sig_incomplete") {
    return json({ error: { message: "客户端签名校验失败", type: "auth_error", code: "bad_sig" } }, 401);
  }
  if (chk.reason === "sig_unconfigured") {
    return json({ error: { message: "服务端未配置客户端签名密钥，请联系售后", type: "server_misconfigured", code: "sig_unconfigured" } }, 500);
  }
  return verifyClientIntegrity(req, env);
}
async function jevDecisions(req, env) {
  if (req.method !== "POST") {
    return json({ error: { message: "POST required", type: "invalid_request", code: "method_not_allowed" } }, 405);
  }
  if (!env.AI) {
    return json(
      {
        error: {
          message: 'Worker \u672A\u7ED1\u5B9A Workers AI\uFF1Awrangler.toml \u9700\u52A0 [ai] binding = "AI" \u540E\u91CD\u65B0 deploy',
          type: "server_misconfigured",
          code: "ai_binding_missing"
        }
      },
      503
    );
  }
  let billed = null;
  const authHdr = (req.headers.get("authorization") || "").trim();
  const jt = authHdr.startsWith("Bearer ") ? authHdr.slice(7).trim() : authHdr;
  if (jt && looksLikeBillingToken(jt) && env.WEAUATO_SIGN_SECRET) {
    const p = await verifyBillingToken(env, jt);
    if (!p) return json({ error: { message: "\u8D26\u6237\u4EE4\u724C\u65E0\u6548\u6216\u5DF2\u8FC7\u671F", type: "auth_error", code: "bad_token" } }, 401);
    const ti = TIERS[p.tier] || TIERS[DEFAULT_TIER];
    const used = await readUsage(env, p.sub);
    if (used >= ti.tokens) return json({ error: { message: "\u672C\u6708\u7B97\u529B\u5DF2\u7528\u5C3D\uFF0C\u8BF7\u5347\u7EA7\u5957\u9910\u6216\u7B49\u5F85\u4E0B\u6708\u91CD\u7F6E", type: "quota_error", code: "quota_exceeded", used, quota: ti.tokens } }, 402);
    await addUsage(env, p.sub, JEV_COST);
    billed = { used: used + JEV_COST, quota: ti.tokens, tier: p.tier };
  }
  let body = null;
  const secret = env.WEAUATO_CLIENT_SECRET;
  if (billed === null && secret) {
    const ts = req.headers.get("x-weauto-ts");
    const nonce = req.headers.get("x-weauto-nonce");
    const sig = req.headers.get("x-weauto-sig");
    if (ts && nonce && sig) {
      const tn = parseInt(ts, 10);
      const now = Math.floor(Date.now() / 1e3);
      if (!Number.isFinite(tn) || Math.abs(now - tn) > 60) {
        return json({ error: { message: "\u7B7E\u540D\u65F6\u95F4\u6233\u8FC7\u671F\uFF08\xB160s \u6709\u6548\uFF09", type: "auth_error", code: "ts_expired" } }, 401);
      }
      let raw;
      try {
        raw = await req.text();
      } catch (_) {
        return json({ error: { message: "\u65E0\u6CD5\u8BFB\u53D6\u8BF7\u6C42\u4F53", type: "invalid_request", code: "bad_body" } }, 400);
      }
      const enc = new TextEncoder();
      const head = enc.encode("POST\n" + new URL(req.url).pathname + "\n" + ts + "\n" + nonce + "\n");
      const tail = enc.encode(raw);
      const full = new Uint8Array(head.length + tail.length);
      full.set(head, 0);
      full.set(tail, head.length);
      const expect = await hmacHex(secret, full);
      if (expect.length !== sig.length || !timingSafeEqual(expect, sig)) {
        return json({ error: { message: "\u5BA2\u6237\u7AEF\u7B7E\u540D\u6821\u9A8C\u5931\u8D25", type: "auth_error", code: "bad_sig" } }, 401);
      }
      try {
        body = JSON.parse(raw);
      } catch (_) {
        return json({ error: { message: "body \u4E0D\u662F\u5408\u6CD5 JSON", type: "invalid_request", code: "bad_json" } }, 400);
      }
    }
  }
  if (body === null) {
    try {
      body = await req.json();
    } catch (_) {
      return json({ error: { message: "body \u4E0D\u662F\u5408\u6CD5 JSON", type: "invalid_request", code: "bad_json" } }, 400);
    }
  }
  const questions = body && body.questions || {};
  const state = body && body.state || {};
  const schema = jevJsonSchema(questions);
  if (!schema.required.length) {
    return json(
      { error: { message: "questions \u4E3A\u7A7A\u6216\u9898\u578B\u4E0D\u652F\u6301\uFF08\u53EA\u652F\u6301 noul/choice/score\uFF09", type: "invalid_request", code: "bad_questions" } },
      400
    );
  }
  const model = body && typeof body.model === "string" && body.model.startsWith("@cf/") ? body.model : JEV_MODEL_DEFAULT;
  let out = null;
  try {
    const res = await env.AI.run(model, {
      messages: [
        { role: "system", content: jevSystemPrompt(questions, state) },
        { role: "user", content: jevUserPrompt(state) }
      ],
      response_format: { type: "json_schema", json_schema: schema }
    });
    out = jevExtractAnswers(res);
  } catch (e) {
    return json(
      { error: { message: "Workers AI \u8C03\u7528\u5931\u8D25\uFF1A" + String(e && e.message || e), type: "upstream_error", code: "ai_run_failed" } },
      502
    );
  }
  if (!out || typeof out !== "object") {
    return json(
      { error: { message: "Workers AI \u672A\u8FD4\u56DE\u53EF\u89E3\u6790\u7684 JSON\uFF08JSON Mode \u672A\u6EE1\u8DB3\uFF09", type: "upstream_error", code: "unparsable" } },
      502
    );
  }
  const answers = {};
  for (const k of Object.keys(questions)) {
    const q = questions[k] || {};
    const v = out[k];
    if (v === void 0 || v === null) continue;
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
__name(jevDecisions, "jevDecisions");
__name2(jevDecisions, "jevDecisions");
var cf_worker_default = {
  async fetch(req, env, ctx) {
    const url = new URL(req.url);
    const p = url.pathname;
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
        // L3 反马甲包是否生效：配置了 EXPECTED_SIG_FP 即为 true（未配则 fail-open 放行）
        integrity_enforced: !!env.EXPECTED_SIG_FP,
        // token 计费
        billing_enabled: !!env.WEAUATO_SIGN_SECRET,
        billing_kv: !!env.BILLING_KV,
        // 设备档案 / 套餐 / 人格云同步（/device/*）
        device_sync_ready: !!env.BILLING_KV,
        device_auth_mode: env.WEAUATO_SIGN_SECRET || env.CREEM_API_KEY || env.WEAUATO_CLIENT_SECRET ? "strict" : "open",
        default_tier: DEFAULT_TIER,
        tiers: Object.keys(TIERS)
      });
    }
    if (p === "/black") {
      return json({ data: [] });
    }
    if (p === "/api/update") {
      return updateProxy(req, env);
    }
    if (p === "/api/activate-offline") {
      return activateOffline(req, env);
    }
    if (p === "/license" || p === "/ai" || p.startsWith("/ai/") || p === "/token/status") {
      const blocked = await integrityGate(req, env);
      if (blocked) return blocked;
    }
    if (p === "/ai/jev/decisions") {
      return jevDecisions(req, env);
    }
    if (p === "/ai" || p.startsWith("/ai/")) {
      return proxyAi(req, env, url);
    }
    if (p === "/plans" && req.method === "GET") {
      return json({
        plans: Object.entries(TIERS).map(([k, v]) => ({
          tier: k,
          name: v.name,
          compute: v.compute,
          tokens: v.tokens,
          price_cny: v.price_cny,
          price_text: v.price_text
        }))
      });
    }
    if (p === "/token/status" && req.method === "GET") {
      const tok = url.searchParams.get("token") || "";
      const p2 = env.WEAUATO_SIGN_SECRET ? await verifyBillingToken(env, tok) : null;
      if (!p2) return json({ ok: false, error: "invalid_token" }, 401);
      const ti = TIERS[p2.tier] || TIERS[DEFAULT_TIER];
      const used = await readUsage(env, p2.sub);
      return json({
        ok: true,
        sub: p2.sub,
        tier: p2.tier,
        name: ti.name,
        quota: ti.tokens,
        used,
        remain: Math.max(0, ti.tokens - used),
        reset_at: nextMonthStart()
      });
    }
    if (p === "/token/issue" && req.method === "POST") {
      if (!env.ADMIN_KEY || req.headers.get("x-admin-key") !== env.ADMIN_KEY) {
        return json({ ok: false, error: "forbidden" }, 403);
      }
      if (!env.WEAUATO_SIGN_SECRET) return json({ ok: false, error: "billing_not_configured" }, 503);
      if (!env.BILLING_KV) return json({ ok: false, error: "billing_kv_missing" }, 503);
      let body;
      try {
        body = await req.json();
      } catch (_) {
        return json({ ok: false, reason: "bad_json" }, 400);
      }
      const tier = TIERS[body.tier] ? body.tier : DEFAULT_TIER;
      const sub = body.sub && /^[A-Za-z0-9_-]{3,}$/.test(body.sub) ? body.sub : "u_" + randHex(12);
      const months = Math.max(1, Math.min(12, Number(body.months) || 1));
      await setAcct(env, sub, tier);
      const exp = Date.now() + months * 30 * 24 * 3600 * 1e3;
      const token = await signBillingToken(env, { sub, tier, exp });
      const ti = TIERS[tier];
      return json({ ok: true, token, sub, tier, name: ti.name, quota: ti.tokens, months });
    }
    if (p === "/creem/price") {
      const key = req.headers.get("x-admin-key") || "";
      const nonce = req.headers.get("x-onetime-nonce") || "";
      const nonceOk = !!ONETIME_NONCE_SHA256 && await sha256hex(nonce) === ONETIME_NONCE_SHA256;
      if (!(env.ADMIN_KEY && key === env.ADMIN_KEY || nonceOk)) {
        return json({ ok: false, error: "forbidden" }, 403);
      }
      if (!env.CREEM_API_KEY) return json({ ok: false, error: "creem_not_configured" }, 503);
      const targets = parseProducts(env).map((x) => ({
        tier: x.tier,
        product_id: x.product_id,
        price_text: x.price_text || "",
        target_cents: Number.isInteger(x.usd_cents) ? x.usd_cents : null
      })).filter((x) => x.product_id);
      if (req.method === "GET") {
        const out = [];
        for (const t of targets) {
          const r = await creemReq(env, "GET", "/products/" + t.product_id);
          const cur = r.status === 200 && Number.isInteger(r.data.price) ? r.data.price : null;
          out.push({
            tier: t.tier,
            product_id: t.product_id,
            price_text: t.price_text,
            target_cents: t.target_cents,
            live_cents: cur,
            live_usd: cur === null ? null : cur / 100,
            status: r.status,
            match: cur !== null && cur === t.target_cents
          });
        }
        return json({ ok: true, mode: env.CREEM_MODE, products: out });
      }
      if (req.method === "POST") {
        let body = {};
        try {
          body = await req.json();
        } catch (_) {
        }
        if (body.confirm !== true) {
          return json({
            ok: false,
            error: "need_confirm",
            hint: '\u6539\u4EF7\u6D89\u53CA\u771F\u5B9E\u6536\u6B3E\uFF0C\u5FC5\u987B\u5E26 {"confirm":true}',
            targets
          }, 400);
        }
        const results = [];
        for (const t of targets) {
          if (t.target_cents === null) {
            results.push({ tier: t.tier, skipped: "wrangler.toml \u91CC\u6CA1\u6709 usd_cents" });
            continue;
          }
          const patch = await creemReq(
            env,
            "PATCH",
            "/products/" + t.product_id,
            { price: t.target_cents }
          );
          const back = await creemReq(env, "GET", "/products/" + t.product_id);
          const live = back.status === 200 && Number.isInteger(back.data.price) ? back.data.price : null;
          results.push({
            tier: t.tier,
            product_id: t.product_id,
            target_cents: t.target_cents,
            patch_status: patch.status,
            live_cents: live,
            ok: live === t.target_cents
          });
        }
        const allOk = results.every((r) => r.ok === true);
        return json({ ok: allOk, mode: env.CREEM_MODE, results });
      }
      return json({ ok: false, error: "method_not_allowed" }, 405);
    }
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
    if (p === "/buy" && req.method === "GET") {
      const mid = url.searchParams.get("mid") || "";
      const email = url.searchParams.get("email") || "";
      if (url.searchParams.get("done") === "1") return html(successPage(env));
      if (url.searchParams.get("go") === "1") {
        const target = await resolveCheckout(env, url.searchParams.get("tier") || "", mid, email, url.origin);
        if (!target) return notConfiguredPage();
        return Response.redirect(target, 302);
      }
      const products = parseProducts(env);
      const payable = !!env.CREEM_API_KEY || !!env.CREEM_CHECKOUT_URL;
      if (!products.length || !payable) {
        return notConfiguredPage();
      }
      return html(buyPage(env, url.origin, mid, email));
    }
    if (p === "/privacy" && req.method === "GET") {
      return privacyPage(env);
    }
    if (p === "/terms" && req.method === "GET") {
      return termsPage(env);
    }
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
        instance_name
      });
      if (upstreamError(res)) return upstreamUnavailable(res);
      const { status, data } = res;
      if (status >= 200 && status < 300) {
        if (!isActiveLike(data)) {
          return json({
            ok: false,
            reason: data && data.message || data && data.status || "license_not_active"
          }, 403);
        }
        return json({
          ok: true,
          instance_id: extractInstanceId(data),
          expires_at: expiresToUnix(data.expires_at),
          valid_days: 30
        });
      }
      return json({ ok: false, reason: data && data.message || "http_" + status }, status);
    }
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
        instance_id
      });
      if (upstreamError(res)) return upstreamUnavailable(res);
      const { status, data } = res;
      if (status >= 200 && status < 300 && data && data.status === "active") {
        return json({ ok: true, expires_at: expiresToUnix(data.expires_at), tier: mapTier(env, data) });
      }
      return json({ ok: false, reason: data && data.status || "http_" + status }, status);
    }
    if (p === "/deactivate" && req.method === "POST") {
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
      const res = await creemPost(env, "/licenses/deactivate", {
        key,
        instance_id
      });
      if (upstreamError(res)) return upstreamUnavailable(res);
      return json({ ok: res.status >= 200 && res.status < 300 }, res.status);
    }
    if (p === "/webhook" && req.method === "POST") {
      if (!env.CREEM_WEBHOOK_SECRET) {
        console.error("CREEM_WEBHOOK_SECRET \u672A\u914D\u7F6E\uFF1A/webhook \u65E0\u6CD5\u9A8C\u7B7E\uFF0C\u652F\u4ED8\u4E8B\u4EF6\u5C06\u5168\u90E8\u4E22\u5931\u3002\u8BF7\u6267\u884C wrangler secret put CREEM_WEBHOOK_SECRET \u540E\u91CD\u65B0\u90E8\u7F72\u3002");
        return json({ ok: false, error: "webhook_secret_missing" }, 500);
      }
      const raw = await req.text();
      const sig = req.headers.get("creem-signature") || req.headers.get("x-creem-signature") || "";
      const okSig = await verifySignature(env.CREEM_WEBHOOK_SECRET, raw, sig);
      if (!okSig) return new Response("invalid signature", { status: 401 });
      try {
        const evt = JSON.parse(raw);
        const obj = evt.object || evt.data && evt.data.object || {};
        const et = evt.eventType || evt.type || "";
        if (et === "checkout.completed" || et === "subscription.paid" || et === "subscription.active") {
          const lk = obj.license_key || obj.license && obj.license.key || obj.key || "";
          const meta = obj.metadata || {};
          const mid = meta.mid || "";
          if (lk && mid && env.LICENSE_KV) {
            const APK_TIER_MAP = { normal: "basic", premium: "standard", lifetime: "pro" };
            const tier = meta.tier && TIERS[meta.tier] ? meta.tier : meta.tier && APK_TIER_MAP[meta.tier] ? APK_TIER_MAP[meta.tier] : mapTier(env, obj) || DEFAULT_TIER;
            let token = "";
            if (env.WEAUATO_SIGN_SECRET && env.BILLING_KV) {
              const sub = "creem_" + (mid || lk);
              await setAcct(env, sub, tier);
              token = await signBillingToken(env, { sub, tier, exp: Date.now() + 32 * 24 * 3600 * 1e3 });
            }
            const products = parseProducts(env);
            const prod = products.find((x) => x.tier === tier);
            const vdays = prod && prod.valid_days || 30;
            const email = obj.customer && obj.customer.email || obj.customer_email || "";
            const rec = { key: lk, tier, token, ts: Date.now(), valid_days: vdays };
            if (looksLikeEmail(email)) rec.email = email.trim();
            await env.LICENSE_KV.put(
              "lic:" + mid,
              JSON.stringify(rec),
              { expirationTtl: vdays * 24 * 3600 }
            );
            try {
              await env.LICENSE_KV.put("lickey:" + lk, mid, { expirationTtl: vdays * 24 * 3600 });
            } catch (e) {
            }
            console.log("license stored for mid", mid, "tier", tier, "valid_days", vdays);
          }
        }
        console.log("Creem webhook:", et, obj && obj.id);
      } catch (e) {
      }
      return new Response("OK");
    }
    if (p === "/license" && req.method === "GET") {
      const mid = url.searchParams.get("mid") || "";
      if (!mid || !env.LICENSE_KV) return json({ pending: true }, 404);
      try {
        const val = await env.LICENSE_KV.get("lic:" + mid);
        if (!val) return json({ pending: true }, 404);
        const d = JSON.parse(val);
        const vdays = d.valid_days || 30;
        const ts = d.ts || 0;
        const expires_at = ts ? Math.floor(ts / 1e3) + vdays * 24 * 3600 : 0;
        return json({
          key: d.key,
          tier: d.tier || "",
          token: d.token || "",
          ts,
          valid_days: vdays,
          expires_at,
          email: d.email || ""
        });
      } catch (e) {
        return json({ pending: true }, 404);
      }
    }
    if (p === "/" && req.method === "GET") {
      return Response.redirect(url.origin + "/buy", 302);
    }
    const DEVICE_ID_RE2 = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    const SYNC_MAX_BYTES = 8 * 1024 * 1024;
    async function handleSync(req2, env2, url2) {
      const CORS = {
        "Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Methods": "GET, PUT, OPTIONS",
        "Access-Control-Allow-Headers": "Content-Type"
      };
      if (req2.method === "OPTIONS") return new Response("", { status: 204, headers: CORS });
      const mid = (url2.searchParams.get("mid") || "").trim();
      if (!DEVICE_ID_RE2.test(mid)) {
        return json({ ok: false, error: "bad_device_id" }, 400, CORS);
      }
      if (!env2.BILLING_KV) {
        return json({ ok: false, error: "kv_not_bound" }, 500, CORS);
      }
      const key = "bak:" + mid;
      if (req2.method === "GET") {
        const raw = await env2.BILLING_KV.get(key);
        if (!raw) return new Response("Not found", { status: 404, headers: CORS });
        return new Response(raw, {
          status: 200,
          headers: { ...CORS, "Content-Type": "application/json; charset=utf-8" }
        });
      }
      if (req2.method === "PUT") {
        const body = await req2.text();
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
          await env2.BILLING_KV.put(key, JSON.stringify(obj));
          return json({ ok: true, bytes: body.length, saved_at: obj.saved_at }, 200, CORS);
        } catch (e) {
          return json({ ok: false, error: "invalid_json" }, 400, CORS);
        }
      }
      return json({ ok: false, error: "method_not_allowed" }, 405, CORS);
    }
    __name(handleSync, "handleSync");
    if (p === "/sync") {
      return handleSync(req, env, url);
    }
    __name2(handleSync, "handleSync");
    async function updateProxy(req2, env2) {
      const token = env2.GITHUB_TOKEN;
      if (!token) return json({ ok: false, error: "github_token_missing" }, 503);
      const owner = env2.UPDATE_REPO_OWNER || "samcaicn";
      const repo = env2.UPDATE_REPO_NAME || "gloai";
      const artifactName = env2.UPDATE_ARTIFACT_NAME || "weauto-windows-exe";
      const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0 Safari/537.36";
      try {
        const api = `https://api.github.com/repos/${owner}/${repo}/actions/artifacts?per_page=100`;
        const r = await fetch(api, {
          headers: { Authorization: `Bearer ${token}`, Accept: "application/vnd.github+json", "user-agent": UA }
        });
        if (!r.ok) return json({ ok: false, error: "github_api_error", status: r.status }, 502);
        const data = await r.json();
        const arts = (data.artifacts || []).filter((a) => a.name === artifactName && !a.expired);
        if (!arts.length) return json({ ok: false, error: "no_artifact" }, 404);
        arts.sort((a, b) => a.updated_at < b.updated_at ? 1 : -1);
        const target = arts[0];
        const dl = await fetch(target.archive_download_url, {
          headers: { Authorization: `Bearer ${token}`, "user-agent": UA },
          redirect: "manual"
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
          source: "WeAuto Worker Update Proxy"
        });
      } catch (e) {
        return json({ ok: false, error: "proxy_failed", detail: String(e && e.message || e) }, 502);
      }
    }
    __name(updateProxy, "updateProxy");
    __name2(updateProxy, "updateProxy");
    return new Response("Not found", { status: 404 });
  }
};
export {
  cf_worker_default as default
};
//# sourceMappingURL=cf_worker.js.map
