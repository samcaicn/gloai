// 参数调整面板：按 schema 渲染表单，登录 / 连接测试 / 扫码。所有网络动作经由
// src/bbt.ts -> Rust 后端。字段改动自动保存本地；登录成功后自动拉取远程参数。
// 另含「团长聊天」页签（chat.ts）。
import { SCHEMA, CONNECTION_FIELDS, defaultParams, defaultConnection, mergeParams, mergeConnection, FieldDef } from "./config";
import { kv } from "./kv";
import { bbt } from "./bbt";
import { bridgeApi, autoreplyApi, wxautoApi } from "./ilink";
import { renderChat } from "./chat";
import { renderMemberQr } from "./memberqr";
import { renderOrdersPage } from "./orderspage";
import { renderInteractPage } from "./interact";
import { overlayHtml, openOverlay, closeOverlay, wireOverlayClose, bindOverlayEsc } from "./overlay";
import { listen } from "@tauri-apps/api/event";

interface AppState {
  connection: Record<string, any>;
  params: Record<string, any>;
}

const state: AppState = {
  connection: defaultConnection(),
  params: defaultParams(),
};

// 自动保存防抖句柄（字段改动 500ms 后落盘）
let saveTimer: number | null = null;

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

  // 连接状态条：窄条横置（替代原大卡），常驻首页顶部
  const loggedIn = !!state.connection.token;
  const connCard = `
    <section class="card status-bar span2">
      <div class="status-left">
        <span class="conn-dot ${loggedIn ? "on" : "off"}"></span>
        <div>
          <div class="status-title">${loggedIn ? "已连接 BBT-ZB" : "未连接"}</div>
          <div class="status-sub">${loggedIn ? `${state.connection.telephone || ""} · ${state.connection.base_url}` : "登录后所有页签共用此连接"}</div>
        </div>
      </div>
      <button class="btn primary" id="btn-open-login">${loggedIn ? "连接设置" : "立即登录"}</button>
    </section>`;

  const loginOverlay = overlayHtml(
    "ov-login",
    "团长登录",
    `<div class="fields" id="login-fields">${CONNECTION_FIELDS.map((f) => fieldHtml(f, state.connection[f.key], "conn")).join("")}</div>
     <div class="seg-group" role="tablist">
       <button class="seg-btn" id="seg-pwd" type="button">账号登录</button>
       <button class="seg-btn" id="seg-qr" type="button">扫码登录</button>
     </div>
     <div id="pwdc">
       <div class="btn-row"><button class="btn primary" id="btn-login">登录并连接</button></div>
     </div>
     <div id="pcqr" class="qr-pane" style="display:none">
       <div class="qr-frame">
         <div id="pcqr-code" class="qr-code">生成二维码中…</div>
         <div class="qr-scanline"></div>
       </div>
       <div id="pcqr-status" class="qr-status">请用手机端「我的」页点头像扫码</div>
       <button class="btn small hidden" id="pcqr-refresh" type="button">刷新二维码</button>
       <div class="qr-hint">手机端：我的 → 点头像 → 扫此码 → 确认</div>
     </div>`,
    "登录 BBT-ZB 团长账号后，所有页签共用此连接。"
  );

  // 运行状态：三列等宽紧凑卡（替代原 span2 总开关）
  const switchesCard = `
    <section class="card span2">
      <h2>运行状态</h2>
      <p class="card-desc">全局功能开关，改动即时生效。</p>
      <div class="sw-run">
        <div class="sw-run-item">
          <div class="sw-run-icon" style="background:var(--ok-soft)">🤖</div>
          <div class="sw-run-body">
            <div class="sw-run-name">AI 自动回复</div>
            <div class="sw-run-hint">团员/微信消息 AI 接管</div>
          </div>
          <label class="switch"><input type="checkbox" id="sw-autoreply"/><span></span></label>
        </div>
        <div class="sw-run-item">
          <div class="sw-run-icon" style="background:#e8f0ff">💬</div>
          <div class="sw-run-body">
            <div class="sw-run-name">微信通道</div>
            <div class="sw-run-hint">wxauto 自动收发</div>
          </div>
          <label class="switch"><input type="checkbox" id="sw-wxauto"/><span></span></label>
        </div>
        <div class="sw-run-item">
          <div class="sw-run-icon" style="background:var(--accent-soft)">🌉</div>
          <div class="sw-run-body">
            <div class="sw-run-name">团长桥</div>
            <div class="sw-run-hint">bbt-zb ↔ iLink 互通</div>
          </div>
          <label class="switch"><input type="checkbox" id="sw-bridge"/><span></span></label>
        </div>
      </div>
    </section>
`;
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
        <button class="tab active" id="tab-params-btn">🏠 首页</button>
        <button class="tab" id="tab-chat-btn">💬 聊天</button>
        <button class="tab" id="tab-members-btn">👥 团员</button>
        <button class="tab" id="tab-orders-btn">📦 订单</button>
        <button class="tab" id="tab-interact-btn">📢 互动</button>
      </nav>
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
      ${loginOverlay}
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
}

function wire() {
  $("btn-open-login")?.addEventListener("click", () => { openOverlay("ov-login"); setLoginMode("pwd"); });
  $("btn-login")?.addEventListener("click", onLogin);
  $("seg-pwd")?.addEventListener("click", () => setLoginMode("pwd"));
  $("seg-qr")?.addEventListener("click", () => setLoginMode("qr"));
  $("pcqr-refresh")?.addEventListener("click", () => startPcQr());
  // 关闭登录浮层时停轮询（遮罩 / ✕）
  document.querySelectorAll("#ov-login [data-close-ov]").forEach((el) =>
    el.addEventListener("click", () => stopQrPoll())
  );
  document.addEventListener("keydown", (e) => { if (e.key === "Escape") stopQrPoll(); });
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

// ---- 自动保存：表单改动 500ms 防抖后落盘（替代原「本地保存」按钮）----
async function persistNow() {
  const conn = collect("conn", CONNECTION_FIELDS);
  const params = collect("fld", SCHEMA.flatMap((g) => g.fields));
  const savedConn = {
    base_url: conn.base_url,
    telephone: conn.telephone,
    token: state.connection.token || "",
  };
  try {
    await kv.put({ connection: savedConn, ...params });
    await bbt.setConnection(savedConn.base_url, savedConn.token || undefined, savedConn.telephone || undefined);
  } catch (e: any) {
    console.warn("自动保存失败", e);
  }
}

function scheduleSave() {
  if (saveTimer !== null) clearTimeout(saveTimer);
  saveTimer = window.setTimeout(() => { saveTimer = null; void persistNow(); }, 500);
}

// 事件委托：任意表单字段（首页 + 浮层）改动即触发自动保存，render 重建 DOM 后依然有效
function bindAutoSave(root: HTMLElement) {
  root.addEventListener("change", (e) => {
    const t = e.target as HTMLElement;
    if (t.id?.startsWith("conn-") || t.id?.startsWith("fld-")) scheduleSave();
  });
}

// 登录成功后自动拉取远程参数（替代原「拉取远程」按钮）
async function syncRemote() {
  try {
    const res: any = await bbt.fetch();
    state.params = mergeParams(res);
  } catch (e: any) {
    console.warn("同步远程参数失败", e);
  }
}

async function onLogin() {
  const conn = collect("conn", CONNECTION_FIELDS);
  if (!conn.base_url || !conn.telephone || !conn.password) {
    toast("请填写 接口地址 / 手机号 / 密码", false);
    return;
  }
  const btn = $("btn-login") as HTMLButtonElement | null;
  btn?.classList.add("loading");
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
    closeOverlay("ov-login");
    stopQrPoll();
    await syncRemote();
    refreshStatusBar();
    notifyLoginChanged(true);
    const role = res.account?.role ? `（角色：${res.account.role}）` : "";
    toast(`登录成功，已建立连接${role}`);
  } catch (e: any) {
    toast("登录失败：" + (e?.message || e), false);
  } finally { btn?.classList.remove("loading"); }
}

// 局部刷新状态条（不重建整个页面，避免丢子页面轮询/状态）
function refreshStatusBar() {
  const loggedIn = !!state.connection.token;
  const dot = document.querySelector(".conn-dot");
  const title = document.querySelector(".status-title");
  const sub = document.querySelector(".status-sub");
  const btn = $("btn-open-login");
  if (dot) dot.className = "conn-dot " + (loggedIn ? "on" : "off");
  if (title) title.textContent = loggedIn ? "已连接 BBT-ZB" : "未连接";
  if (sub) sub.textContent = loggedIn
    ? [state.connection.telephone, state.connection.base_url].filter(Boolean).join(" · ")
    : "登录后所有页签共用此连接";
  if (btn) btn.textContent = loggedIn ? "连接设置" : "立即登录";
}

// 登录态变化时通知所有子页面自动刷新（无需手动点）
function notifyLoginChanged(loggedIn: boolean) {
  window.dispatchEvent(new CustomEvent("login-changed", { detail: { loggedIn } }));
}

// -------- PC 扫码登录（pc/create → 渲染二维码 → 轮询 pc/poll）--------
let qrTimer: number | null = null;

function stopQrPoll() {
  if (qrTimer !== null) { clearInterval(qrTimer); qrTimer = null; }
}

function setLoginMode(m: "pwd" | "qr") {
  const segPwd = $("seg-pwd"), segQr = $("seg-qr");
  segPwd?.classList.toggle("on", m === "pwd");
  segQr?.classList.toggle("on", m === "qr");
  const pwdc = document.getElementById("pwdc");
  const pcqr = document.getElementById("pcqr");
  if (pwdc) pwdc.style.display = m === "pwd" ? "" : "none";
  if (pcqr) pcqr.style.display = m === "qr" ? "" : "none";
  if (m === "qr") void startPcQr(); else stopQrPoll();
}

// 动态加载 qrcode-generator（与 H5 版同源，从 bbt-zb CDN 拉）
let qrLibPromise: Promise<any> | null = null;
function ensureQrcode(): Promise<any> {
  if (typeof window !== "undefined" && (window as any).qrcode) {
    return Promise.resolve((window as any).qrcode);
  }
  if (qrLibPromise) return qrLibPromise;
  qrLibPromise = new Promise((resolve, reject) => {
    const s = document.createElement("script");
    s.src = "https://bbt-zb.pages.dev/static/js/qrcode-generator.js";
    s.onload = () => ((window as any).qrcode ? resolve((window as any).qrcode) : reject(new Error("qrcode 未就绪")));
    s.onerror = () => reject(new Error("二维码组件加载失败"));
    document.head.appendChild(s);
  });
  return qrLibPromise;
}

async function startPcQr() {
  stopQrPoll();
  const codeEl = document.getElementById("pcqr-code");
  const statusEl = document.getElementById("pcqr-status");
  const pcqr = document.getElementById("pcqr");
  const refreshEl = $("pcqr-refresh") as HTMLElement | null;
  if (!codeEl || !statusEl || !pcqr) return;
  pcqr.classList.remove("expired", "scanned");
  // 从表单读 base_url（用户可能改过）
  const baseUrlEl = $("conn-base_url");
  const baseUrl = (baseUrlEl as HTMLInputElement | null)?.value?.trim() || state.connection.base_url;
  if (!baseUrl) { statusEl.textContent = "请先填写接口地址"; return; }
  statusEl.textContent = "生成二维码中…";
  if (refreshEl) refreshEl.classList.add("hidden");
  try {
    // 先把 base_url 灌进 Rust state（未带 token，pc/create 不需要）
    await bbt.setConnection(baseUrl);
    const r: any = await bbt.call("pc/create", {});
    const d = r?.data;
    if (!r?.status || !d) {
      statusEl.textContent = (r?.msg || "生成二维码失败") + "，请改用账号登录";
      if (refreshEl) refreshEl.classList.remove("hidden");
      return;
    }
    const scanId: string = d.scanId;
    const payload: string = d.payload;
    codeEl.innerHTML = "";
    codeEl.classList.add("has-img");
    const qrcode = await ensureQrcode();
    const q = qrcode(0, "M");
    q.addData(payload);
    q.make();
    const img = document.createElement("img");
    img.src = q.createDataURL(8, 4);
    img.alt = "PC 登录二维码";
    codeEl.appendChild(img);
    statusEl.textContent = "请用手机端「我的」页点头像扫码";
    pcqr.classList.add("waiting");

    qrTimer = window.setInterval(async () => {
      try {
        const pr: any = await bbt.call("pc/poll", { scanId });
        const pd = pr?.data;
        if (!pr?.status || !pd) {
          stopQrPoll();
          statusEl.textContent = (pr?.msg || "二维码已失效") + "，请刷新";
          pcqr.classList.remove("waiting", "scanned");
          pcqr.classList.add("expired");
          if (refreshEl) refreshEl.classList.remove("hidden");
          return;
        }
        if (pd.status === "pending") {
          statusEl.textContent = "等待手机端扫码…";
          pcqr.classList.add("waiting"); pcqr.classList.remove("scanned", "expired");
        } else if (pd.status === "scanned") {
          statusEl.textContent = "已扫码，请在手机端确认";
          pcqr.classList.remove("waiting"); pcqr.classList.add("scanned");
        } else if (pd.status === "confirmed") {
          stopQrPoll();
          const token: string = pd.token;
          const tel: string = pd.telephone || pd.name || "";
          state.connection.token = token;
          state.connection.base_url = baseUrl;
          state.connection.telephone = tel;
          await bbt.setConnection(baseUrl, token, tel || undefined);
          closeOverlay("ov-login");
          await syncRemote();
          refreshStatusBar();
          notifyLoginChanged(true);
          const role = pd.role ? `（角色：${pd.role}）` : "";
          toast(`扫码登录成功${role}`);
        }
      } catch (e: any) {
        // 单次轮询失败不中断，下一轮再试
        console.warn("pc/poll err", e);
      }
    }, 1500);
  } catch (e: any) {
    statusEl.textContent = "二维码启动失败：" + (e?.message || e);
    if (refreshEl) refreshEl.classList.remove("hidden");
  }
}

export async function init() {
  const appRoot = document.getElementById("app");
  if (appRoot) bindAutoSave(appRoot);
  // 静默更新事件（Rust 后台下载验签暂存完成 → 提示重启生效）
  listen("update-event", (ev: any) => {
    const d = ev.payload || {};
    toast(`🆕 新版 v${d.version} 已下载并通过验签，重启后自动升级`, true);
  }).catch(() => {});
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
