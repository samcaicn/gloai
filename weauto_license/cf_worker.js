/**
 * WeAuto License Worker — 部署到 Cloudflare Workers
 * --------------------------------------------------------------------------
 * 角色：作为 Creem 与客户端 EXE 之间的唯一服务端代理。
 *   - 客户端 EXE 只与本 Worker 通信，绝不直接接触 api.creem.io。
 *   - 本 Worker 持有 Creem 密钥（通过 CF Secrets/Env 注入），代理 license 激活/校验/释放。
 *   - Creem Webhook 也落在这里，验签后做记录/发卡。
 *
 * 为什么必须放服务端：EXE 二进制里的密钥等于明文（PyInstaller 字符串可读），
 * 逆向即可抠出 key 白嫖激活。Creem 官方也明确 SDK 不可放客户端。
 *
 * 部署：
 *   npm i -g wrangler
 *   wrangler login
 *   wrangler secret put CREEM_API_KEY        # 从 Creem Dashboard > Settings > API Keys
 *   wrangler secret put CREEM_WEBHOOK_SECRET # 从 Creem > Developers > Webhooks
 *   # 在 wrangler.toml 写：
 *   #   name = "weauto-license"
 *   #   main = "weauto_license/cf_worker.js"
 *   #   compatibility_date = "2024-09-23"
 *   #   [vars]
 *   #   CREEM_MODE = "test"          # 上线切 "prod"
 *   #   CREEM_PRODUCT_ID = "prod_xxx"
 *   #   CREEM_CHECKOUT_URL = "https://www.creem.io/checkout/xxxx"  # 后台「复制结账链接」粘贴这里
 *   # routes（可绑自定义域名；不绑则用默认 https://<worker>.<子域>.workers.dev）：
 *   #   [[routes]]
 *   #   custom_domain = "buy.你的域名.com"
 *   wrangler deploy
 *
 * 需注入的变量/密钥：
 *   CREEM_API_KEY        Creem API key（secret）
 *   CREEM_WEBHOOK_SECRET Creem webhook secret（secret）
 *   CREEM_MODE           "test" | "prod"
 *   CREEM_PRODUCT_ID     License 产品 ID（兜底 /buy 用）
 *   CREEM_CHECKOUT_URL   后台复制的结账链接（优先，买家点开即收银台；支持支付宝）
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

export default {
  async fetch(req, env, ctx) {
    const url = new URL(req.url);
    const p = url.pathname;

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
      return json({ ok: false, reason: data && data.message ? data.message : "http_" + status }, status);
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
      const reason = (data && data.status) || ("http_" + status);
      return json({ ok: false, reason }, status);
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

    // ---- 购买落地页：302 跳转到 Creem 收银台（买家点开即付款，支持支付宝）----
    // 买家入口的唯一来源就是本端点：客户端未激活时打印的 (WORKER_URL)/buy 即指向这里。
    // 部署时必须让 WORKER 绑定自定义域名（.workers.dev 在中国大陆不可达）。
    if (p === "/buy" && req.method === "GET") {
      // 1) 最稳：开发者在 Creem 后台「复制结账链接」后填入 wrangler.toml 的 CREEM_CHECKOUT_URL
      if (env.CREEM_CHECKOUT_URL) {
        return Response.redirect(env.CREEM_CHECKOUT_URL, 302);
      }
      // 2) 次选：动态创建 checkout session（带 redirect 回本页激活引导），失败自动降级
      if (env.CREEM_API_KEY && env.CREEM_PRODUCT_ID) {
        try {
          const { status, data } = await creemPost(env, "/checkout", {
            product_id: env.CREEM_PRODUCT_ID,
            redirect_url: url.origin + "/buy?done=1",
          });
          if (status >= 200 && status < 300 && data.checkout_url) {
            return Response.redirect(data.checkout_url, 302);
          }
        } catch (e) {
          /* 字段不符则 fallthrough 到兜底 */
        }
      }
      // 3) 兜底：Creem 产品页（买家还需再点一次 Checkout）
      if (env.CREEM_PRODUCT_ID) {
        return Response.redirect(
          `https://www.creem.io/products/${env.CREEM_PRODUCT_ID}`, 302);
      }
      return new Response(
        "未配置 CREEM_CHECKOUT_URL 或 CREEM_PRODUCT_ID", { status: 500 });
    }

    // ---- Webhook（Creem 支付成功回调）----
    if (p === "/webhook" && req.method === "POST") {
      const raw = await req.text();
      const sig = req.headers.get("creem-signature") || "";
      const ok = await verifySignature(env.CREEM_WEBHOOK_SECRET, raw, sig);
      if (!ok) return new Response("invalid signature", { status: 401 });
      try {
        const evt = JSON.parse(raw);
        // checkout.completed / subscription.paid / subscription.active → 发卡（Creem 自动生成 key）
        // 如需自建记录（设备配额/用户映射），在此写 env.KV 或转发到自有 DB。
        console.log("Creem webhook:", evt.eventType, evt.object && evt.object.id);
      } catch (e) {
        /* 忽略解析错误，仍回 200 避免 Creem 重投 */
      }
      return new Response("OK");
    }

    return new Response("Not found", { status: 404 });
  },
};
