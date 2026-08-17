// Copyright (c) 2026 tupAI
//
// Live fetch+parse test for the "接通 DSH 插件服务" path. Mirrors the exact
// Rust logic in src-tauri/src/commands/plugin_market.rs (join_dsh_plugin_url,
// dsh_plugin_array, normalize_dsh_plugin, sort+dedup). Talks to the local mock
// service (scripts/mock_dsh_plugin_service.py) so the whole contract is proven
// without building the Tauri binary.
//
// Run:
//   python scripts/mock_dsh_plugin_service.py 8787 &
//   node scripts/test_dsh_plugin_fetch.mjs
//   (or just: DSH_MOCK_URL=http://127.0.0.1:8787 node scripts/test_dsh_plugin_fetch.mjs)

const BASE = process.env.DSH_MOCK_URL || "http://127.0.0.1:8787";
const PATHS = ["/plugins", "/plugins-wrapped"];

function joinUrl(endpoint, path) {
  const e = endpoint.replace(/\/+$/, "");
  let p = (path || "").trim();
  if (!p) p = "/plugins";
  else if (!p.startsWith("/")) p = "/" + p;
  return e + p;
}

function pluginArray(payload) {
  if (Array.isArray(payload)) return payload;
  if (payload && typeof payload === "object") {
    for (const k of ["plugins", "data", "items", "result", "results"]) {
      if (Array.isArray(payload[k])) return payload[k];
    }
  }
  return null;
}

function normalize(raw, upstreamId, endpoint) {
  let pid = raw.id || raw.pluginId || raw.name;
  if (!pid || !String(pid).trim()) return null;
  pid = String(pid);
  const name = raw.name || raw.title || pid;
  const description = raw.description || raw.summary || raw.desc || null;
  const stars = raw.stars ?? raw.downloads ?? 0;
  const url =
    raw.homepage || raw.url || raw.link ||
    `${endpoint.replace(/\/+$/, "")}/plugins/${pid}`;
  const language = raw.language || null;
  const license = raw.license || null;
  const updatedAt = raw.updatedAt || raw.updated_at || raw.version || null;
  const repo = `${upstreamId}/${pid}`;
  return {
    id: repo.replace(/\//g, "-"),
    repo,
    name,
    description,
    stars,
    url,
    language,
    license,
    updatedAt,
    installRef: `dsh:${upstreamId}/${pid}`,
  };
}

async function fetchUpstream(endpoint, path, upstreamId) {
  const url = joinUrl(endpoint, path);
  const res = await fetch(url, {
    headers: { "User-Agent": "safeopcAPP", Accept: "application/json" },
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const body = await res.json();
  const arr = pluginArray(body);
  if (!arr) throw new Error("no plugin array in response");
  return arr.map((it) => normalize(it, upstreamId, endpoint)).filter(Boolean);
}

async function main() {
  let failures = 0;
  const assert = (cond, msg) => {
    if (cond) console.log("  \u2713", msg);
    else {
      console.error("  \u2717", msg);
      failures++;
    }
  };

  console.log(`\n[DSH plugin fetch test] mock=${BASE}\n`);

  for (const p of PATHS) {
    console.log(`\u2022 upstream 'local' @ ${p}`);
    const items = await fetchUpstream(BASE, p, "local");
    assert(items.length === 3, `pulled ${items.length} plugins`);
    const first = items[0];
    assert(first.id === "local-translator", `id scoped to upstream (${first.id})`);
    assert(
      first.installRef === "dsh:local/translator",
      `install_ref = ${first.installRef}`,
    );
    assert(first.stars === 42, `stars parsed (${first.stars})`);
    assert(
      first.url === "https://dsh.local/plugins/translator",
      `url honored (${first.url})`,
    );
    const ocr = items.find((x) => x.id === "local-ocr");
    assert(ocr && ocr.language === "Rust", `optional language parsed (${ocr && ocr.language})`);
  }

  // Sort by stars desc + dedup (mirrors backend).
  const all = (await fetchUpstream(BASE, "/plugins", "local"))
    .sort((a, b) => b.stars - a.stars)
    .filter((v, i, a) => a.findIndex((x) => x.id === v.id) === i);
  assert(all[0].id === "local-translator", `sorted by stars desc (top=${all[0].id})`);

  // Simulate the frontend client-side query filter.
  const q = "ocr";
  const filtered = all.filter(
    (x) =>
      (x.name || "").toLowerCase().includes(q) ||
      (x.description || "").toLowerCase().includes(q),
  );
  assert(filtered.length === 1 && filtered[0].id === "local-ocr", `client filter "${q}" -> 1 match`);

  console.log(
    `\n${failures === 0 ? "PASS \u2705 拉取+解析链路通" : `FAIL \u274c ${failures} 项失败`}\n`,
  );
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error("FATAL", e);
  process.exit(1);
});
