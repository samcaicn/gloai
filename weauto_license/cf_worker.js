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
 *   CREEM_PRODUCT_ID     产品 ID（prod_xxx）
 *   CREEM_CHECKOUT_URL   后台「复制结账链接」（优先；买家点开即收银台）
 *   PRODUCT_NAME / PRODUCT_PRICE / PRODUCT_DESC / SUPPORT_EMAIL  站点文案
 *
 * 关于支付宝（两个不同概念，别混淆）：
 *   1) 买家付款方式：Creem 2.0 已新增 AliPay 作为收银台支付方式；
 *      买家在收银台选择支付宝即可（文档 FAQ 页仍有 "coming soon" 旧字样，以 2.0 为准）。
 *   2) 商家收款（payout）：中国商户可在 Balance → Payout Account 添加「支付宝」收款，
 *      单笔上限 5 万 CNY。这是你拿到钱的方式，与买家付款方式是两回事。
 */

const creemBase = (mode) =>
  mode === "prod" ? "https://api.creem.io/v1" : "https://test-api.creem.io/v1";

async function creemPost(env, path, body) {
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

// HMAC-SHA256 验签，常量时间比较
async function verifySignature(secret, rawBody, signature) {
  if (!secret || !signature) return false;
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey(
    "raw", enc.encode(secret),
    { name: "HMAC", hash: "SHA-256" }, false, ["sign"]
  );
  const buf = await crypto.subtle.sign("HMAC", key, enc.encode(rawBody));
  const computed = [...new Uint8Array(buf)]
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
  if (computed.length !== signature.length) return false;
  let diff = 0;
  for (let i = 0; i < computed.length; i++) {
    diff |= computed.charCodeAt(i) ^ signature.charCodeAt(i);
  }
  return diff === 0;
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
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

/** 购买落地页（卡密网站首页） */
function buyPage(env, origin) {
  const name = env.PRODUCT_NAME || "WeAuto 授权";
  const price = env.PRODUCT_PRICE || "见收银台";
  const desc = env.PRODUCT_DESC || "解锁 WeAuto 全部功能，一次购买，长期可用。";
  const go = origin + "/buy?go=1";
  const support = env.SUPPORT_EMAIL;

  return layout(`购买 ${name}`, `
<div class="hd">
  <h1>${esc(name)}</h1>
  <p>微信机器人 · 官方授权</p>
</div>

<div class="card">
  <div class="price">${esc(price)}<small>${esc(desc)}</small></div>
  <a class="btn" href="${esc(go)}">支付宝付款 · 立即购买</a>
  <p style="text-align:center;color:#6b7280;font-size:13px;margin:10px 0 0">
    <span class="tag">支持支付宝</span>
    收银台同时支持支付宝、信用卡等方式
  </p>
</div>

<div class="card">
  <h3 style="margin-top:0">购买后怎么用（3 步）</h3>
  <ol>
    <li>点击上方按钮，在收银台用 <b>支付宝</b> 完成付款。</li>
    <li>付款成功后，Creem 会显示 / 邮件发送你的 <b>卡密（License Key）</b>，形如
        <div class="k" style="margin-top:6px">XXXX-XXXX-XXXX-XXXX</div>
    </li>
    <li>打开 WeAuto 网页后台 →「授权管理」→ 粘贴卡密 → 点「激活 / 保存」，再点「重启机器人使授权生效」。</li>
  </ol>
</div>

<div class="card">
  <h3 style="margin-top:0">常见问题</h3>
  <p><b>一台电脑能用几次？</b><br>按产品设置的设备数（通常 1–2 台）。换机前请在授权管理里「释放本机」，否则新机会提示超限。</p>
  <p><b>断网会失效吗？</b><br>不会。首次激活后有 7 天离线宽限，断网期间仍可正常使用。</p>
  <p><b>卡密丢了怎么办？</b><br>付款邮箱里有 Creem 的收据与卡密${support ? `；也可联系 <a href="mailto:${esc(support)}">${esc(support)}</a>` : ""}。</p>
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

/** 商家还没配置好时的友好提示（替代原来的 500 裸文本）。返回 Response，状态码 503。 */
function notConfiguredPage() {
  return html(layout("暂未开放购买", `
<div class="hd"><h1>暂未开放购买</h1><p>商家正在配置支付</p></div>
<div class="card">
  <p>本授权站点尚未完成支付配置，暂时无法购买。</p>
  <p style="color:#6b7280;font-size:14px">如果你是站点管理员：请在 <code>wrangler.toml</code> 的
  <code>[vars]</code> 中填入 <code>CREEM_CHECKOUT_URL</code>（或 <code>CREEM_PRODUCT_ID</code>），
  然后重新 <code>wrangler deploy</code>。</p>
</div>`), 503);
}

/** 解析收银台地址：优先静态链接，其次动态创建，再次产品页 */
async function resolveCheckout(env, origin) {
  if (env.CREEM_CHECKOUT_URL) return env.CREEM_CHECKOUT_URL;
  if (env.CREEM_API_KEY && env.CREEM_PRODUCT_ID) {
    try {
      const { status, data } = await creemPost(env, "/checkout", {
        product_id: env.CREEM_PRODUCT_ID,
        redirect_url: origin + "/buy?done=1",
      });
      if (status >= 200 && status < 300 && data.checkout_url) return data.checkout_url;
    } catch (e) {
      /* fallthrough */
    }
  }
  if (env.CREEM_PRODUCT_ID) {
    return `https://www.creem.io/products/${env.CREEM_PRODUCT_ID}`;
  }
  return null;
}

/* ---------------- 路由 ---------------- */

export default {
  async fetch(req, env, ctx) {
    const url = new URL(req.url);
    const p = url.pathname;

    // ---- 健康检查 / 配置自检（不泄露密钥）----
    if (p === "/health") {
      return json({
        ok: true,
        service: "weauto-license",
        mode: env.CREEM_MODE || "test",
        has_api_key: !!env.CREEM_API_KEY,
        has_webhook_secret: !!env.CREEM_WEBHOOK_SECRET,
        has_checkout_url: !!env.CREEM_CHECKOUT_URL,
        has_product_id: !!env.CREEM_PRODUCT_ID,
        checkout_ready: !!(env.CREEM_CHECKOUT_URL || env.CREEM_PRODUCT_ID),
      });
    }

    // ---- 购买落地页：真正的卡密网站 ----
    if (p === "/buy" && req.method === "GET") {
      if (url.searchParams.get("done") === "1") return html(successPage(env));
      // ?go=1 → 直接跳收银台（EXE 的一键购买走这里）
      if (url.searchParams.get("go") === "1") {
        const target = await resolveCheckout(env, url.origin);
        if (!target) return notConfiguredPage();
        return Response.redirect(target, 302);
      }
      // 默认：展示购买站（买家先看清楚再付款，转化率更好）
      if (!env.CREEM_CHECKOUT_URL && !env.CREEM_PRODUCT_ID) {
        return notConfiguredPage();
      }
      return html(buyPage(env, url.origin));
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
      const { status, data } = await creemPost(env, "/licenses/activate", {
        key,
        instance_name,
      });
      if (status >= 200 && status < 300) {
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
      const { status, data } = await creemPost(env, "/licenses/validate", {
        key,
        instance_id,
      });
      if (status >= 200 && status < 300 && data && data.status === "active") {
        return json({ ok: true, expires_at: expiresToUnix(data.expires_at) });
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
      const { status } = await creemPost(env, "/licenses/deactivate", {
        key,
        instance_id,
      });
      return json({ ok: status >= 200 && status < 300 }, status);
    }

    // ---- Webhook（Creem 支付回调）----
    if (p === "/webhook" && req.method === "POST") {
      const raw = await req.text();
      const sig = req.headers.get("creem-signature") || "";
      const okSig = await verifySignature(env.CREEM_WEBHOOK_SECRET, raw, sig);
      if (!okSig) return new Response("invalid signature", { status: 401 });
      try {
        const evt = JSON.parse(raw);
        // checkout.completed / subscription.paid 等 → 卡密由 Creem 自动生成并交付买家
        // 若需自建记录（设备配额/用户映射），在此写 KV 或转发自有 DB。
        console.log("Creem webhook:", evt.eventType, evt.object && evt.object.id);
      } catch (e) {
        /* 忽略解析错误，仍回 200 避免 Creem 重投 */
      }
      return new Response("OK");
    }

    // 根路径：跳购买页，方便直接访问域名
    if (p === "/" && req.method === "GET") {
      return Response.redirect(url.origin + "/buy", 302);
    }

    return new Response("Not found", { status: 404 });
  },
};
