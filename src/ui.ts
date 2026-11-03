// 参数调整面板：按 schema 渲染表单，提供 本地保存 / 登录 / 连接测试 /
// 拉取远程 / 推送到 BBT-ZB / 重置。所有网络动作经由 src/bbt.ts -> Rust 后端。
// 另含「团长聊天」页签（chat.ts）。
import { SCHEMA, CONNECTION_FIELDS, defaultParams, defaultConnection, mergeParams, mergeConnection, FieldDef } from "./config";
import { kv } from "./kv";
import { bbt } from "./bbt";
import { bridgeApi, autoreplyApi, wxautoApi } from "./ilink";
import { renderChat } from "./chat";
import { renderMemberQr } from "./memberqr";
import { renderOrdersPage } from "./orderspage";
import { renderInteractPage } from "./interact";
import { overlayHtml, openOverlay, wireOverlayClose, bindOverlayEsc } from "./overlay";

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

export function toast(msg: string, ok = true) {
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
      <h2>团长登录</h2>
      <p class="card-desc">登录 BBT-ZB 团长账号后，所有页签共用此连接（其余页不再单独登录）。</p>
      <div class="grid2">
        ${CONNECTION_FIELDS.map((f) => fieldHtml(f, state.connection[f.key], "conn")).join("")}
      </div>
      <div class="btn-row">
        <button class="btn primary" id="btn-login">登录并连接</button>
      </div>
    </section>`;

  // 总开关卡：三个全局开关，状态从各自后端拉取
  const switchesCard = `
    <section class="card span2">
      <h2>总开关</h2>
      <p class="card-desc">全局功能开关，改动即时生效（各页签共用）。</p>
      <div class="grid2 switches-grid">
        <div class="field switch-field">
          <div class="field-label">AI 自动回复<span class="hint">团员/微信消息由 AI 接管回复</span></div>
          <label class="switch"><input type="checkbox" id="sw-autoreply"/><span></span></label>
        </div>
        <div class="field switch-field">
          <div class="field-label">微信通道<span class="hint">wxauto 自动启动 Python 收发</span></div>
          <label class="switch"><input type="checkbox" id="sw-wxauto"/><span></span></label>
        </div>
        <div class="field switch-field">
          <div class="field-label">团长桥<span class="hint">bbt-zb ↔ iLink 消息互通</span></div>
          <label class="switch"><input type="checkbox" id="sw-bridge"/><span></span></label>
        </div>
      </div>
    </section>`;

  // 次要配置块：浮层启动按钮 + 浮层本体（字段仍在 DOM 中，collect 正常读取）
  const moreLaunchers = SCHEMA.map(
    (g) => `<button class="btn" id="btn-ov-${g.id}">${g.title}</button>`
  ).join("");
  const moreOverlays = SCHEMA.map(
    (g) =>
      overlayHtml(
        `ov-${g.id}`,
        g.title,
        `<div class="fields">${g.fields.map((f) => fieldHtml(f, state.params[f.key], "fld")).join("")}</div>`,
        g.desc
      )
  ).join("");

  app.innerHTML = `
    <header class="topbar">
      <div class="brand">BBT-ZB 控制台</div>
      <nav class="tabs">
        <button class="tab active" id="tab-params-btn">首页</button>
        <button class="tab" id="tab-chat-btn">聊天配置</button>
        <button class="tab" id="tab-members-btn">团员二维码</button>
        <button class="tab" id="tab-orders-btn">订单消息</button>
        <button class="tab" id="tab-interact-btn">互动消息</button>
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
      ${switchesCard}
      <section class="card span2">
        <h2>更多设置</h2>
        <p class="card-desc">次要配置以浮层打开，保持首页简洁。</p>
        <div class="btn-row">${moreLaunchers}</div>
      </section>
      ${moreOverlays}
    </main>
    <main class="content chat-page hidden" id="page-chat"></main>
    <main class="content members-page hidden" id="page-members"></main>
    <main class="content orders-page hidden" id="page-orders"></main>
    <main class="content interact-page hidden" id="page-interact"></main>
    <div id="toast-box" class="toast-box"></div>`;

  wire();
  wireOverlayClose(app);
  bindOverlayEsc();
  SCHEMA.forEach((g) => {
    $(`btn-ov-${g.id}`)?.addEventListener("click", () => openOverlay(`ov-${g.id}`));
  });
  renderChat(document.getElementById("page-chat") as HTMLElement);
  renderMemberQr(document.getElementById("page-members") as HTMLElement);
  renderOrdersPage(document.getElementById("page-orders") as HTMLElement);
  renderInteractPage(document.getElementById("page-interact") as HTMLElement);
  void loadSwitches();

  const pBtn = $("tab-params-btn"), cBtn = $("tab-chat-btn"), mBtn = $("tab-members-btn");
  const oBtn = $("tab-orders-btn"), iBtn = $("tab-interact-btn");
  pBtn?.addEventListener("click", () => setActiveTab("params"));
  cBtn?.addEventListener("click", () => setActiveTab("chat"));
  mBtn?.addEventListener("click", () => setActiveTab("members"));
  oBtn?.addEventListener("click", () => setActiveTab("orders"));
  iBtn?.addEventListener("click", () => setActiveTab("interact"));
}

// ---- 总开关：拉取三个全局开关的当前状态 ----
async function loadSwitches() {
  const ar = $("sw-autoreply") as HTMLInputElement | null;
  const wx = $("sw-wxauto") as HTMLInputElement | null;
  const br = $("sw-bridge") as HTMLInputElement | null;
  const jobs: Promise<void>[] = [];
  if (ar) jobs.push(autoreplyApi.getConfig().then((c: any) => { ar.checked = !!c?.enabled; }).catch(() => {}));
  if (wx) jobs.push(wxautoApi.getConfig().then((c: any) => { wx.checked = !!c?.enabled; }).catch(() => {}));
  if (br) jobs.push(bridgeApi.getConfig().then((c: any) => { br.checked = !!c?.enabled; }).catch(() => {}));
  await Promise.allSettled(jobs);
}

function setActiveTab(tab: "params" | "chat" | "members" | "orders" | "interact") {
  const tabs: Record<string, string> = {
    params: "tab-params-btn",
    chat: "tab-chat-btn",
    members: "tab-members-btn",
    orders: "tab-orders-btn",
    interact: "tab-interact-btn",
  };
  const pages: Record<string, string> = {
    params: "page-params",
    chat: "page-chat",
    members: "page-members",
    orders: "page-orders",
    interact: "page-interact",
  };
  Object.entries(tabs).forEach(([name, btnId]) => {
    $(btnId)?.classList.toggle("active", name === tab);
  });
  Object.entries(pages).forEach(([name, pageId]) => {
    $(pageId)?.classList.toggle("hidden", name !== tab);
  });
  const btnRow = document.querySelector(".topbar .params-only");
  btnRow?.classList.toggle("hidden", tab !== "params");
}

function wire() {
  $("btn-save")?.addEventListener("click", onSaveLocal);
  $("btn-login")?.addEventListener("click", onLogin);
  $("btn-fetch")?.addEventListener("click", onFetch);
  $("btn-push")?.addEventListener("click", onPush);
  $("btn-reset")?.addEventListener("click", onReset);
  // 总开关
  $("sw-autoreply")?.addEventListener("change", async (e) => {
    const on = (e.target as HTMLInputElement).checked;
    try {
      await autoreplyApi.setEnabled(on);
      toast(on ? "AI 自动回复已开启" : "AI 自动回复已关闭");
    } catch (err: any) {
      toast("切换失败：" + (err?.message || err), false);
      (e.target as HTMLInputElement).checked = !on;
    }
  });
  $("sw-wxauto")?.addEventListener("change", async (e) => {
    const on = (e.target as HTMLInputElement).checked;
    try {
      await wxautoApi.setConfig({ enabled: on });
      toast(on ? "微信通道已开启（自动启动 Python）" : "微信通道已关闭");
    } catch (err: any) {
      toast("切换失败：" + (err?.message || err), false);
      (e.target as HTMLInputElement).checked = !on;
    }
  });
  $("sw-bridge")?.addEventListener("change", async (e) => {
    const on = (e.target as HTMLInputElement).checked;
    try {
      await bridgeApi.setEnabled(on);
      toast(on ? "团长桥已开启" : "团长桥已关闭");
    } catch (err: any) {
      toast("切换失败：" + (err?.message || err), false);
      (e.target as HTMLInputElement).checked = !on;
    }
  });
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
    const role = res.account?.role ? `（角色：${res.account.role}）` : "";
    toast(`登录成功，已建立连接${role}`);
  } catch (e: any) {
    toast("登录失败：" + (e?.message || e), false);
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
  } catch (e: any) {
    toast("重置落盘失败：" + (e?.message || e), false);
  }
  render();
  toast("已重置为默认值");
}

export async function init() {
  // 其他页签可派发 window 事件跳转页签（如 团员二维码 → 去首页登录）
  window.addEventListener("goto-tab", (e) => {
    const tab = (e as CustomEvent).detail;
    if (typeof tab === "string" && ["params", "chat", "members", "orders", "interact"].includes(tab)) {
      setActiveTab(tab as any);
    }
  });
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
