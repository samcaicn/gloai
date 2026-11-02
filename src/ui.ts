// 参数调整面板：按 schema 渲染表单，提供 本地保存 / 登录 / 连接测试 /
// 拉取远程 / 推送到 BBT-ZB / 重置。所有网络动作经由 src/bbt.ts -> Rust 后端。
// 另含「团长聊天」页签（chat.ts）。
import { SCHEMA, CONNECTION_FIELDS, defaultParams, defaultConnection, mergeParams, mergeConnection, FieldDef } from "./config";
import { kv } from "./kv";
import { bbt } from "./bbt";
import { renderChat } from "./chat";

interface AppState {
  connection: Record<string, any>;
  params: Record<string, any>;
}

const state: AppState = {
  connection: defaultConnection(),
  params: defaultParams(),
};

const fieldById = new Map<string, FieldDef>();
[...SCHEMA.flatMap((g) => g.fields), ...CONNECTION_FIELDS].forEach((f) => fieldById.set(f.key, f));

function $(id: string) {
  return document.getElementById(id) as HTMLInputElement | null;
}

function toast(msg: string, ok = true) {
  const box = $("toast-box");
  if (!box) return;
  const el = document.createElement("div");
  el.className = "toast " + (ok ? "ok" : "err");
  el.textContent = msg;
  box.appendChild(el);
  setTimeout(() => el.remove(), 3200);
}

// ---- 渲染单个字段 ----
function fieldHtml(f: FieldDef, value: any, prefix: string): string {
  const id = `${prefix}-${f.key}`;
  const val = value === undefined || value === null ? f.default : value;
  let control = "";
  if (f.type === "toggle") {
    control = `<label class="switch"><input type="checkbox" id="${id}" ${val ? "checked" : ""}/><span></span></label>`;
  } else if (f.type === "select") {
    const opts = (f.options || [])
      .map((o) => `<option value="${o.value}" ${o.value === String(val) ? "selected" : ""}>${o.label}</option>`)
      .join("");
    control = `<select id="${id}">${opts}</select>`;
  } else if (f.type === "textarea") {
    control = `<textarea id="${id}" rows="3">${escapeHtml(String(val))}</textarea>`;
  } else if (f.type === "number") {
    control = `<input type="number" id="${id}" value="${val}" step="${f.step || "any"}"/>`;
  } else {
    const t = f.type === "password" ? "password" : "text";
    control = `<input type="${t}" id="${id}" value="${escapeHtml(String(val))}"/>`;
  }
  return `
    <div class="field">
      <div class="field-label">${f.label}${f.help ? `<span class="hint">${f.help}</span>` : ""}</div>
      ${control}
    </div>`;
}

function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c] as string)
  );
}

// ---- 收集值 ----
function collect(prefix: string, fields: FieldDef[]): Record<string, any> {
  const out: Record<string, any> = {};
  for (const f of fields) {
    const el = $(`${prefix}-${f.key}`);
    if (!el) {
      out[f.key] = f.default;
      continue;
    }
    if (f.type === "toggle") out[f.key] = (el as HTMLInputElement).checked;
    else if (f.type === "number") {
      const n = parseFloat((el as HTMLInputElement).value);
      out[f.key] = isNaN(n) ? f.default : n;
    } else out[f.key] = (el as HTMLInputElement).value;
  }
  return out;
}

// ---- 渲染整个面板 ----
function render() {
  const app = $("app");
  if (!app) return;

  const connCard = `
    <section class="card span2">
      <h2>BBT-ZB 连接</h2>
      <div class="grid2">
        ${CONNECTION_FIELDS.map((f) => fieldHtml(f, state.connection[f.key], "conn")).join("")}
      </div>
      <div class="btn-row">
        <button class="btn primary" id="btn-login">登录并连接</button>
        <button class="btn" id="btn-test">连接测试</button>
        <button class="btn" id="btn-update-check">检查更新</button>
        <button class="btn" id="btn-update-apply">下载更新</button>
      </div>
    </section>`;

  const groupCards = SCHEMA.map(
    (g) => `
    <section class="card">
      <h2>${g.title}</h2>
      ${g.desc ? `<p class="card-desc">${g.desc}</p>` : ""}
      <div class="fields">
        ${g.fields.map((f) => fieldHtml(f, state.params[f.key], "fld")).join("")}
      </div>
    </section>`
  ).join("");

  app.innerHTML = `
    <header class="topbar">
      <div class="brand">BBT-ZB 控制台</div>
      <nav class="tabs">
        <button class="tab active" id="tab-params-btn">参数调整</button>
        <button class="tab" id="tab-chat-btn">团长聊天</button>
      </nav>
      <div class="btn-row right params-only">
        <button class="btn" id="btn-fetch">拉取远程</button>
        <button class="btn" id="btn-push">推送到 BBT-ZB</button>
        <button class="btn" id="btn-reset">重置</button>
        <button class="btn primary" id="btn-save">本地保存</button>
      </div>
    </header>
    <main class="content" id="page-params">
      ${connCard}
      ${groupCards}
    </main>
    <main class="content chat-page hidden" id="page-chat"></main>
    <div id="toast-box" class="toast-box"></div>`;

  wire();
  renderChat(document.getElementById("page-chat") as HTMLElement);

  const pBtn = $("tab-params-btn"), cBtn = $("tab-chat-btn");
  pBtn?.addEventListener("click", () => switchTab(true));
  cBtn?.addEventListener("click", () => switchTab(false));
}

function switchTab(params: boolean) {
  const pBtn = $("tab-params-btn"), cBtn = $("tab-chat-btn");
  const p = $("page-params"), c = $("page-chat");
  const btnRow = document.querySelector(".topbar .params-only");
  if (!pBtn || !cBtn || !p || !c) return;
  pBtn.classList.toggle("active", params);
  cBtn.classList.toggle("active", !params);
  p.classList.toggle("hidden", !params);
  c.classList.toggle("hidden", params);
  btnRow?.classList.toggle("hidden", !params);
}

function wire() {
  $("btn-save")?.addEventListener("click", onSaveLocal);
  $("btn-login")?.addEventListener("click", onLogin);
  $("btn-test")?.addEventListener("click", onTest);
  $("btn-fetch")?.addEventListener("click", onFetch);
  $("btn-push")?.addEventListener("click", onPush);
  $("btn-reset")?.addEventListener("click", onReset);
  $("btn-update-check")?.addEventListener("click", onUpdateCheck);
  $("btn-update-apply")?.addEventListener("click", onUpdateApply);
}

async function onSaveLocal() {
  const conn = collect("conn", CONNECTION_FIELDS);
  const params = collect("fld", SCHEMA.flatMap((g) => g.fields));
  // 不持久化密码；token 单独保留
  const savedConn = {
    base_url: conn.base_url,
    telephone: conn.telephone,
    token: state.connection.token || "",
  };
  const payload = { connection: savedConn, ...params };
  await kv.put(payload);
  await bbt.setConnection(savedConn.base_url, savedConn.token || undefined, savedConn.telephone || undefined);
  toast("已保存到本地");
}

async function onLogin() {
  const conn = collect("conn", CONNECTION_FIELDS);
  if (!conn.base_url || !conn.telephone || !conn.password) {
    toast("请填写 接口地址 / 手机号 / 密码", false);
    return;
  }
  try {
    const res: any = await bbt.login(conn.base_url, conn.telephone, conn.password);
    if (!res || !res.token) {
      toast("登录失败：未返回 token", false);
      return;
    }
    state.connection.token = res.token;
    state.connection.base_url = conn.base_url;
    state.connection.telephone = conn.telephone;
    await bbt.setConnection(conn.base_url, res.token, conn.telephone);
    render();
    const t = $("conn-token");
    if (t) t.value = res.token;
    const role = res.account?.role ? `（角色：${res.account.role}）` : "";
    toast(`登录成功，已建立连接${role}`);
  } catch (e: any) {
    toast("登录失败：" + (e?.message || e), false);
  }
}

async function onTest() {
  const conn = collect("conn", CONNECTION_FIELDS);
  if (conn.base_url) await bbt.setConnection(conn.base_url);
  try {
    const res: any = await bbt.test();
    toast(res?.message || (res?.ok ? "连接成功" : "连接失败"), !!res?.ok);
  } catch (e: any) {
    toast("测试失败：" + (e?.message || e), false);
  }
}

async function onFetch() {
  try {
    const res: any = await bbt.fetch();
    state.params = mergeParams(res);
    render();
    toast("已从 BBT-ZB 拉取参数");
  } catch (e: any) {
    toast("拉取失败：" + (e?.message || e), false);
  }
}

async function onPush() {
  // 只推送 chat/configSave 接受的字段（排除只读组）
  const all = collect("fld", SCHEMA.flatMap((g) => g.fields));
  const params: Record<string, any> = {};
  for (const g of SCHEMA) {
    if (g.id === "readonly") continue;
    for (const f of g.fields) params[f.key] = all[f.key];
  }
  try {
    await bbt.push(params);
    toast("已推送到 BBT-ZB");
  } catch (e: any) {
    toast("推送失败：" + (e?.message || e), false);
  }
}

async function onReset() {
  state.params = defaultParams();
  state.connection = defaultConnection();
  try {
    await kv.put({ connection: defaultConnection(), ...defaultParams() });
  } catch {}
  render();
  toast("已重置为默认值");
}

// ---- 自动更新（weauto 模式：检查 → 下载验签暂存 → 重启自动替换）----
async function onUpdateCheck() {
  const base = ($("conn-update_base_url") as HTMLInputElement)?.value?.trim();
  if (!base) return toast("请先填写升级源地址", false);
  try {
    const r: any = await bbt.updateCheck(base);
    if (r?.update_available) {
      toast(`发现新版本 ${r.latest}（当前 ${r.current}），可点「下载更新」`, true);
    } else if (r) {
      toast(`已是最新版本 ${r.current}`, true);
    } else {
      toast("检查更新失败", false);
    }
  } catch (e: any) {
    toast("检查更新失败：" + (e?.message || e), false);
  }
}

async function onUpdateApply() {
  const base = ($("conn-update_base_url") as HTMLInputElement)?.value?.trim();
  if (!base) return toast("请先填写升级源地址", false);
  try {
    const r: any = await bbt.updatePrepare(base);
    if (r?.success) {
      toast(r.message || "新版本已就绪，重启后自动升级", true);
    } else {
      toast(r?.message || "下载更新失败", false);
    }
  } catch (e: any) {
    toast("下载更新失败：" + (e?.message || e), false);
  }
}

export async function init() {
  try {
    const loaded = await kv.get();
    if (loaded && typeof loaded === "object") {
      state.params = mergeParams(loaded);
      state.connection = mergeConnection(loaded);
    }
  } catch {
    // 首次运行无配置，使用默认值
  }
  render();
}
