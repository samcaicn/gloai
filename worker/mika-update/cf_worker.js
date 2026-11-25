/**
 * Mika 热更新代理(照 WeAuto 的 update-android 通道做的独立 Worker)。
 *
 * 职责只有两件事,不带 WeAuto Worker 的 AI/计费等业务:
 *   GET /api/update-android          查最新构建元数据(只读 GITHUB_TOKEN 拉 Actions artifact 列表)
 *   GET /api/update-android/download 把最新 artifact zip 流式中转给设备
 *
 * 部署(wrangler):
 *   wrangler secret put GITHUB_TOKEN      # 只读 repo:read 的 PAT,读取 samcaicn/gloai 的 artifact
 *   wrangler deploy
 *   可选 vars:UPDATE_REPO_OWNER / UPDATE_REPO_NAME / UPDATE_APK_ARTIFACT_PREFIX(默认 mika-apk*)
 *
 * 版本口径:artifact 名形如 mika-apk-<versionCode>(CI 侧 52+run_number),尾部数字
 * 就是客户端 versionCode,单调递增;客户端拿它与自身 versionCode 比大小判新旧。
 */

const ARTIFACT_PREFIX_DEFAULT = "mika-apk*";
const REPO_OWNER_DEFAULT = "samcaicn";
const REPO_NAME_DEFAULT = "gloai";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0 Safari/537.36";

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

function matchPrefix(name, pattern) {
  if (pattern.endsWith("*")) return name.startsWith(pattern.slice(0, -1));
  return name === pattern;
}

/** artifact 列表里挑最新的、未过期的、名字匹配 mika-apk-<run> 的那个。 */
async function latestArtifact(env) {
  const token = env.GITHUB_TOKEN;
  if (!token) return { error: json({ ok: false, error: "github_token_missing" }, 503) };
  const owner = env.UPDATE_REPO_OWNER || REPO_OWNER_DEFAULT;
  const repo = env.UPDATE_REPO_NAME || REPO_NAME_DEFAULT;
  const pattern = env.UPDATE_APK_ARTIFACT_PREFIX || ARTIFACT_PREFIX_DEFAULT;
  const api = `https://api.github.com/repos/${owner}/${repo}/actions/artifacts?per_page=100`;
  const r = await fetch(api, {
    headers: { Authorization: `Bearer ${token}`, Accept: "application/vnd.github+json", "user-agent": UA },
  });
  if (!r.ok) return { error: json({ ok: false, error: "github_api_error", status: r.status }, 502) };
  const data = await r.json();
  const arts = (data.artifacts || []).filter((a) => !a.expired && matchPrefix(a.name || "", pattern));
  if (!arts.length) return { error: json({ ok: false, error: "no_artifact", pattern }, 404) };
  arts.sort((a, b) => (a.updated_at < b.updated_at ? 1 : -1));
  return { target: arts[0], token };
}

async function updateProxy(req, env) {
  try {
    const found = await latestArtifact(env);
    if (found.error) return found.error;
    const target = found.target;
    // artifact 下载地址 302 到 Azure 签名 URL;查元数据时直接把跳转后的地址回给调用方
    const dl = await fetch(target.archive_download_url, {
      headers: { Authorization: `Bearer ${found.token}`, "user-agent": UA },
      redirect: "manual",
    });
    let directUrl = target.archive_download_url;
    if (dl.status === 301 || dl.status === 302) {
      const loc = dl.headers.get("location");
      if (loc) directUrl = loc;
    }
    // 名形如 mika-apk-<versionCode>:尾部数字即客户端 versionCode,直接比大小判新旧
    const m = (target.name || "").match(/(\d+)\s*$/);
    const versionCode = m ? parseInt(m[1], 10) : 0;
    return json({
      ok: true,
      version: target.updated_at,
      version_code: versionCode,
      artifact: target.name,
      download_url: directUrl,
      download_proxy: new URL(req.url).origin + "/api/update-android/download",
      artifact_id: target.id,
      size_in_bytes: target.size_in_bytes,
      source: "Mika Worker Update Proxy",
    });
  } catch (e) {
    return json({ ok: false, error: "proxy_failed", detail: String((e && e.message) || e) }, 502);
  }
}

async function updateDownloadProxy(req, env) {
  try {
    const found = await latestArtifact(env);
    if (found.error) return found.error;
    const target = found.target;
    // 必须 redirect:"manual":Workers fetch 跟随跨域 302 时会带上 Authorization,
    // Azure SAS 拒绝「签名 URL + 无效 Bearer」的组合(WeAuto 实测 401)。
    // 先拿签名地址,再不带鉴权取流。
    const dl0 = await fetch(target.archive_download_url, {
      headers: { Authorization: `Bearer ${found.token}`, "user-agent": UA },
      redirect: "manual",
    });
    let signed = target.archive_download_url;
    if (dl0.status === 301 || dl0.status === 302) {
      const loc = dl0.headers.get("location");
      if (loc) signed = loc;
    }
    const dl = await fetch(signed, { "user-agent": UA });
    if (!dl.ok || !dl.body) {
      return json({
        ok: false,
        error: "artifact_download_failed",
        status: dl.status,
        hop1: dl0.status,
        host: new URL(signed).host,
      }, 502);
    }
    const h = new Headers();
    h.set("content-type", "application/zip");
    h.set("content-disposition", `attachment; filename="${target.name}.zip"`);
    return new Response(dl.body, { status: 200, headers: h });
  } catch (e) {
    return json({ ok: false, error: "proxy_failed", detail: String((e && e.message) || e) }, 502);
  }
}

export default {
  async fetch(req, env) {
    const p = new URL(req.url).pathname;
    if (p === "/health") return json({ ok: true, service: "mika-update" });
    if (p === "/api/update-android") return updateProxy(req, env);
    if (p === "/api/update-android/download") return updateDownloadProxy(req, env);
    return new Response("Not found", { status: 404 });
  },
};
