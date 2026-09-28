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
 *   AI_UPSTREAM_URL      LLM upstream（如 https://vg.v1api.cc/v1），/ai 代理透传目标
 *
 * 额外 Secrets（wrangler secret put）：
 *   AI_UPSTREAM_KEY      LLM upstream 的真实 key（商家持有，客户端永不接触）；
 *                        未配置时 /ai 一律 401（fail-close），LLM 功能不可用
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

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

/** 解析套餐列表：优先 CREEM_PRODUCTS（JSON 数组，多档）；兼容旧的单产品变量。 */
function parseProducts(env) {
  const raw = env.CREEM_PRODUCTS;
  if (raw) {
    try {
      const arr = typeof raw === "string" ? JSON.parse(raw) : raw;
      if (Array.isArray(arr) && arr.length) return arr;
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
 * 并带 X-WeAuto-Instance 头。Worker 验完卡密后把请求透传给真正的 upstream
 * （AI_UPSTREAM_URL）并注入商家持有的 AI_UPSTREAM_KEY —— 客户端永远拿不到
 * 真实 key：反编译/patch 掉客户端门禁也没用，没有有效卡密这里直接 401。
 */

// isolate 级内存缓存：key|instance -> 校验通过时间戳(ms)。TTL 内不再打 Creem，
// 避免每次 LLM 调用都加 200-400ms 延迟；isolate 回收后重新验一次，代价可接受。
const licenseCache = new Map();
const LICENSE_TTL = 10 * 60 * 1000;

async function licenseOk(env, key, instanceId, requireUpstream = true) {
  // fail-close：Worker 自身没配好（无 Creem key）一律拒绝。
  // requireUpstream=false 供 Jev 用：Jev 走 Workers AI（env.AI），不依赖 LLM upstream，
  // 不能因为商家没配 AI_UPSTREAM_KEY 就把 Jev 也一起封死。
  if (!key || !instanceId) return false;
  if (!env.CREEM_API_KEY) return false;
  if (requireUpstream && (!env.AI_UPSTREAM_URL || !env.AI_UPSTREAM_KEY)) return false;
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

/** /ai/v1/* -> AI_UPSTREAM_URL/<原样路径>，注入真实 upstream key，原样透传（含 SSE 流式） */
async function proxyAi(req, env, url) {
  const auth = req.headers.get("authorization") || "";
  const key = auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
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
  // <worker>/ai/v1/chat/completions -> AI_UPSTREAM_URL/chat/completions
  // AI_UPSTREAM_URL 需带 /v1 后缀（如 https://vg.v1api.cc/v1，与 config.py 的 BASE_URL 同义）；
  // 客户端路径里的 "v1/" 前缀剥掉，避免拼出 /v1/v1/ 双重路径。
  const rel = url.pathname.slice("/ai/".length).replace(/^v1\//, "");
  const target = String(env.AI_UPSTREAM_URL).replace(/\/+$/, "") + "/" + rel + url.search;
  const init = {
    method: req.method,
    headers: {
      "content-type": req.headers.get("content-type") || "application/json",
      "accept": req.headers.get("accept") || "application/json",
      "authorization": "Bearer " + env.AI_UPSTREAM_KEY,
      "user-agent": req.headers.get("user-agent") || "weauto-worker",
    },
    // POST body 原样透传（支持流式）；GET/HEAD 无 body
    body: (req.method === "GET" || req.method === "HEAD") ? undefined : req.body,
  };
  // 直接 return fetch 的 Response：upstream 的 SSE 流式响应原样透传给客户端
  return fetch(target, init);
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
.foot{text-align:center;color:#9ca3af;font-size:12px;margin-top:26px}
code{background:#f3f4f6;padding:2px 6px;border-radius:4px;font-size:13px}
</style>
</head>
<body>
<div class="wrap">${inner}</div>
</body>
</html>`;
}

/** 购买落地页（多档套餐）
 *  @param {string} mid  本机能力令牌（高熵随机），用于把付款回调的卡密关联到这台机器，
 *                        实现「付款后自动同步卡密、无需手动复制粘贴」。
 */
function buyPage(env, origin, mid) {
  const products = parseProducts(env);
  const title = env.SITE_TITLE || "WeAuto 授权中心";
  const support = env.SUPPORT_EMAIL;

  const cards = products.map((p) => {
    const badge = p.billing === "monthly" ? "月租订阅" : "永久买断";
    const go = origin + "/buy?go=1&tier=" + encodeURIComponent(p.tier) +
      (mid ? "&mid=" + encodeURIComponent(mid) : "");
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
  <h3 style="margin-top:0">购买后怎么用（3 步）</h3>
  <ol>
    <li>选择上方档位，点「立即购买」，在收银台用 <b>支付宝</b>（一次性档）或信用卡完成付款。</li>
  <li>付款成功后，Creem 会生成你的 <b>卡密（License Key）</b>（页面/邮件可见）：
        <div class="k" style="margin-top:6px">XXXX-XXXX-XXXX-XXXX</div></li>
  <li>新版 WeAuto 后台「授权管理」会<b>自动同步卡密并一键激活 + 重启</b>，无需手动复制粘贴。
        若因网络未自动同步，再手动把卡密粘贴进去点「激活 / 保存」即可。</li>
  </ol>
</div>
<div class="card">
  <h3 style="margin-top:0">常见问题</h3>
  <p><b>一台电脑能用几次？</b><br>按产品设置的设备数（通常 1–2 台）。换机前请在授权管理里「释放本机」。</p>
  <p><b>月租会断吗？</b><br>订阅到期前 Creem 自动续费并延长卡密有效期；若退订，宽限期后停用。</p>
  <p><b>断网会失效吗？</b><br>不会。首次激活后有 7 天离线宽限。</p>
  <p><b>卡密丢了？</b><br>付款邮箱里有 Creem 收据与卡密${support ? `；也可联系 <a href="mailto:${esc(support)}">${esc(support)}</a>` : ""}。</p>
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
  <h3 style="margin-top:0">下一步：拿到卡密并激活</h3>
  <ol>
    <li>Creem 已把 <b>卡密（License Key）</b> 显示在本页/发送到你的付款邮箱，复制它。</li>
    <li>打开 WeAuto 网页后台 →「授权管理」→ 粘贴卡密 →「激活 / 保存」。</li>
    <li>点「重启机器人使授权生效」，即可正常使用。</li>
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

/** 按档位解析收银台地址（动态创建 checkout 会话，用 Creem 原生页）。
 *  @param {string} mid  本机能力令牌，写入 checkout metadata，使付款回调能关联到这台机器。
 *
 *  注意：**不做跨请求缓存**。checkout 会话是「一次性的买家私有资源」，
 *  早先的 60s 全局缓存会让第二个买家拿到第一个人的 checkout（提示已使用/串号）。
 *  每次请求现建（成本只是一次 API 调用）；若商家配了静态 CREEM_CHECKOUT_URL 则直接复用它。
 */
async function resolveCheckout(env, tier, mid) {
  // 静态收银台链接：无需 API、无需新建，直接跳转
  if (env.CREEM_CHECKOUT_URL) return env.CREEM_CHECKOUT_URL;
  const products = parseProducts(env);
  const p = products.find((x) => x.tier === tier) || products[0];
  if (!p || !p.product_id || !env.CREEM_API_KEY) return null;
  // 不传 success_url：用 Creem 原始链接/原生成功页（用户要求，不做自定义跳转）。
  // 通过 metadata.mid 把「这台机器」带进支付流，付款后 webhook 回带 mid → 自动核销。
  const metadata = { source: "weauto-license-worker", tier: p.tier };
  if (mid) metadata.mid = mid;
  const res = await creemPost(env, "/checkouts", {
    product_id: p.product_id,
    metadata,
  });
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
 *   鉴权：无。Jev 判断是 Workers AI 推理能力，公开可用，不需要卡密。
 *        卡密（CREEM_LICENSE_KEY）仅用于 Creem 支付授权与 /ai/v1 代理。
 *        若需防止他人白嫖 Workers AI 额度，后续可加 Jev 专用令牌，但不应是支付卡密。
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

async function jevDecisions(req, env) {
  if (req.method !== "POST") {
    return json({ error: { message: "POST required", type: "invalid_request", code: "method_not_allowed" } }, 405);
  }
  // Jev 判断走 Workers AI，公开可用，不需要卡密（卡密仅服务 Creem 支付授权）。
  // 任何人都可直接 POST；若需限制他人白嫖 Workers AI 额度，后续可加 Jev 专用令牌。
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

  let body = null;
  try {
    body = await req.json();
  } catch (_) {
    return json({ error: { message: "body 不是合法 JSON", type: "invalid_request", code: "bad_json" } }, 400);
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
        tiers: products.map((x) => ({ tier: x.tier, billing: x.billing, product_id: x.product_id })),
        checkout_ready: ready,
        has_ai_upstream: !!(env.AI_UPSTREAM_URL && env.AI_UPSTREAM_KEY),
        ai_ready: !!(env.CREEM_API_KEY && env.AI_UPSTREAM_URL && env.AI_UPSTREAM_KEY),
        // Jev 判断：Workers AI binding + 模型（binding 由 wrangler 注入，不在 vars 里）
        jev_ready: !!env.AI,
        jev_model: env.JEV_MODEL || JEV_MODEL_DEFAULT,
      });
    }

    // ---- Jev 判断式 AI：/ai/jev/decisions（Workers AI JSON Mode，必须排在 /ai/ 通配之前）----
    if (p === "/ai/jev/decisions") {
      return jevDecisions(req, env);
    }

    // ---- AI 代理：/ai/v1/* -> upstream（卡密即凭证，反破解核心）----
    if (p === "/ai" || p.startsWith("/ai/")) {
      return proxyAi(req, env, url);
    }

    // ---- 购买落地页：真正的卡密网站 ----
    if (p === "/buy" && req.method === "GET") {
      // mid：本机能力令牌，贯穿「购买页 → 收银台 metadata → webhook 回带 → 自动核销」
      const mid = url.searchParams.get("mid") || "";
      if (url.searchParams.get("done") === "1") return html(successPage(env));
      // ?go=1&tier=x → 直接跳对应档位收银台（EXE 的一键购买走这里）
      if (url.searchParams.get("go") === "1") {
        const target = await resolveCheckout(env, url.searchParams.get("tier") || "", mid);
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
      return html(buyPage(env, url.origin, mid));
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
            const tier = meta.tier || (mapTier(env, obj) || "");
            // 存 7 天自动过期：卡密同步到本机后即无用，避免 KV 无限堆积 / mid 被猜泄露
            await env.LICENSE_KV.put("lic:" + mid, JSON.stringify({ key: lk, tier, ts: Date.now() }),
              { expirationTtl: 7 * 24 * 3600 });
            console.log("license stored for mid", mid, "tier", tier);
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
        return json({ key: d.key, tier: d.tier || "", ts: d.ts || 0 });
      } catch (e) {
        return json({ pending: true }, 404);
      }
    }

    // 根路径：跳购买页，方便直接访问域名
    if (p === "/" && req.method === "GET") {
      return Response.redirect(url.origin + "/buy", 302);
    }

    return new Response("Not found", { status: 404 });
  },
};
