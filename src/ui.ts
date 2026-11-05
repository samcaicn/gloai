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

// 页签单一事实源：顺序 = Alt+1~5 / 方向键顺序 / 顶栏渲染顺序 / 恢复顺序
export type TabKey = "params" | "chat" | "members" | "orders" | "interact";
const TAB_ORDER: TabKey[] = ["params", "chat", "members", "orders", "interact"];
const TAB_META: Record<TabKey, { label: string; emoji: string; title: string }> = {
  params: { label: "首页", emoji: "🏠", title: "首页" },
  chat: { label: "聊天", emoji: "💬", title: "聊天" },
  members: { label: "团员", emoji: "👥", title: "团员" },
  orders: { label: "订单", emoji: "📦", title: "订单" },
  interact: { label: "互动", emoji: "📢", title: "互动" },
};
const LAST_TAB_KEY = "bbt-pc-last-tab";

function $(id: string) {
  return document.getElementById(id) as HTMLInputElement | null;
}

export function toast(msg: string, ok = true) {
  const box = $("toast-box");
  if (!box) return;
  const el = document.createElement("div");
  el.className = "toast " + (ok ? "ok" : "err");
  el.setAttribute("role", "status");
  el.setAttribute("aria-live", ok ? "polite" : "assertive");
  el.textContent = msg;
  box.appendChild(el);
  // 同时最多留 3 条，避免连续操作把右下角堆满挡住内容
  while (box.children.length > 3) box.firstElementChild?.remove();
  setTimeout(() => el.remove(), 3200);
}

// ---- 渲染单个字段 ----
function fieldHtml(f: FieldDef, value: any, prefix: string): string {
  const id = `${prefix}-${f.key}`;
  const val = value === undefined || value === null ? f.default : value;
  let control = "";
  // 桌面客户端表单：让系统/密码管理器能正确识别并填充，登录少打一遍
  const AUTOCOMPLETE: Record<string, string> = {
    base_url: "url", telephone: "username", password: "current-password",
  };
  const autocomplete = AUTOCOMPLETE[f.key] ? ` autocomplete="${AUTOCOMPLETE[f.key]}"` : "";
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
    control = `<input type="${t}" id="${id}" value="${escapeHtml(String(val))}"${autocomplete}/>`;
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

/** 顶栏常驻连接态：任意页签都能一眼看到是否已连接，点击直接进连接设置。 */
function updateConnBadge() {
  const el = document.getElementById("conn-badge");
  if (!el) return;
  const on = !!state.connection.token;
  const tel = state.connection.telephone || "";
  el.className = `conn-badge ${on ? "on" : "off"}`;
  el.innerHTML = `<span class="conn-dot ${on ? "on" : "off"}"></span>${on ? escapeHtml(tel || "已连接") : "未连接"}`;
  el.title = on
    ? `已连接 ${state.connection.base_url || ""} · 点击管理连接`
    : "未连接 · 点击登录并连接";
  el.setAttribute("aria-label", el.title);
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
  const loginOverlay = overlayHtml(
    "ov-login",
    "团长登录",
    `<div class="seg-group" role="tablist">
       <button class="seg-btn on" id="seg-pwd" type="button">账号登录</button>
       <button class="seg-btn" id="seg-qr" type="button">扫码登录</button>
     </div>
     <div id="pwdc">
       <div class="fields" id="login-fields">${CONNECTION_FIELDS.map((f) => fieldHtml(f, state.connection[f.key], "conn")).join("")}</div>
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

  // 状态条：已登录显示"连接设置 + 登出"，未登录显示"立即登录"
  const loggedIn = !!state.connection.token;
  const connActions = loggedIn
    ? `<button class="btn" id="btn-logout">登出</button><button class="btn primary" id="btn-open-login">连接设置</button>`
    : `<button class="btn primary" id="btn-open-login">立即登录</button>`;
  const connCard = `
    <section class="card status-bar span2">
      <div class="status-left">
        <span class="conn-dot ${loggedIn ? "on" : "off"}"></span>
        <div>
          <div class="status-title">${loggedIn ? "已连接 BBT-ZB" : "未连接"}</div>
          <div class="status-sub">${loggedIn ? [state.connection.telephone, state.connection.base_url].filter(Boolean).join(" · ") : "登录后所有页签共用此连接"}</div>
        </div>
      </div>
      <div class="btn-row" style="gap:8px">${connActions}</div>
    </section>`;

  // 运行状态：三列等宽紧凑卡（替代原 span2 总开关）
  const switchesCard = `
    <section class="card span2">
      <h2>运行状态</h2>
      <p class="card-desc">全局功能开关，改动即时生效。</p>
      <div class="sw-run">
        <div class="sw-run-item" data-sw="autoreply">
          <div class="sw-run-icon" style="background:var(--ok-soft)">🤖</div>
          <div class="sw-run-body">
            <div class="sw-run-name">AI 自动回复</div>
            <div class="sw-run-hint">团员/微信消息 AI 接管</div>
          </div>
          <span class="sw-run-state" id="sw-state-autoreply">读取中…</span>
          <label class="switch"><input type="checkbox" id="sw-autoreply" aria-label="AI 自动回复开关"/><span></span></label>
        </div>
        <div class="sw-run-item" data-sw="wxauto">
          <div class="sw-run-icon" style="background:#e8f0ff">💬</div>
          <div class="sw-run-body">
            <div class="sw-run-name">微信通道</div>
            <div class="sw-run-hint">wxauto 自动收发</div>
          </div>
          <span class="sw-run-state" id="sw-state-wxauto">读取中…</span>
          <label class="switch"><input type="checkbox" id="sw-wxauto" aria-label="微信通道开关"/><span></span></label>
        </div>
        <div class="sw-run-item" data-sw="bridge">
          <div class="sw-run-icon" style="background:var(--accent-soft)">🌉</div>
          <div class="sw-run-body">
            <div class="sw-run-name">团长桥</div>
            <div class="sw-run-hint">bbt-zb ↔ iLink 互通</div>
          </div>
          <span class="sw-run-state" id="sw-state-bridge">读取中…</span>
          <label class="switch"><input type="checkbox" id="sw-bridge" aria-label="团长桥开关"/><span></span></label>
        </div>
      </div>
    </section>
`;
  // 次要配置块：带说明/字段数的可点设置卡（原为一排无说明裸按钮，看不出里面是什么）
  const moreLaunchers = SCHEMA.map(
    (g) => `<button class="set-item" id="btn-ov-${g.id}" type="button">
       <span class="set-emoji">${g.id === "readonly" ? "📊" : "⚙️"}</span>
       <span class="set-body"><b>${g.title}</b><small>${g.desc || "点击查看与修改"}</small></span>
       <span class="set-count">${g.fields.length} 项</span><span class="set-go">›</span>
     </button>`
  ).join("");
  // 首页快捷入口：桌面端最常用的是直接跳到某个工作页，避免每次都先读一遍首页
  const QUICK = [
    { tab: "chat", emoji: "💬", name: "聊天", sub: "回复规则 · 自测" },
    { tab: "members", emoji: "👥", name: "团员", sub: "名册 · 绑定码" },
    { tab: "orders", emoji: "📦", name: "订单", sub: "收款图 · 发送" },
    { tab: "interact", emoji: "📢", name: "互动", sub: "渠道 · 事件流" },
  ];
  const quickCard = `
    <section class="card span2">
      <h2>快捷入口</h2>
      <p class="card-desc">常用工作页一键直达，也可用 Alt + 2~5 切换。</p>
      <div class="quick-grid">
        ${QUICK.map((q) => `<button class="quick-item" type="button" data-goto="${q.tab}">
          <span class="quick-emoji">${q.emoji}</span><span>${q.name}</span><small>${q.sub}</small>
        </button>`).join("")}
      </div>
    </section>`;
  const moreOverlays = SCHEMA.map(
    (g) =>
      overlayHtml(
        `ov-${g.id}`,
        g.title,
        `<div class="fields">${g.fields.map((f) => fieldHtml(f, state.params[f.key], "fld")).join("")}</div>`,
        g.desc
      )
  ).join("");

  const tabButtons = TAB_ORDER.map((k, i) => `
    <button class="tab${i === 0 ? " active" : ""}" id="tab-${k}-btn" type="button"
      role="tab" aria-selected="${i === 0}" aria-controls="page-${k}"
      tabindex="${i === 0 ? "0" : "-1"}" title="Alt+${i + 1} 切换到${TAB_META[k].title}"
      data-tab="${k}">${TAB_META[k].emoji} ${TAB_META[k].label}<kbd>${i + 1}</kbd></button>`).join("");

  app.innerHTML = `
    <header class="topbar">
      <div class="brand">BBT-ZB 控制台</div>
      <nav class="tabs" role="tablist" aria-label="主导航">${tabButtons}</nav>
      <div class="topbar-right">
        <span class="kbd-hint">Alt + 1~5 切换</span>
        <button class="conn-badge off" id="conn-badge" type="button" title="点击登录并连接">
          <span class="conn-dot off"></span>未连接
        </button>
      </div>
    </header>
    <main class="content" id="page-params" role="tabpanel" aria-labelledby="tab-params-btn" tabindex="-1">
      ${connCard}
      ${switchesCard}
      ${quickCard}
      <section class="card span2">
        <h2>更多设置</h2>
        <p class="card-desc">次要配置以浮层打开，保持首页简洁；改动自动保存到本机。</p>
        <div class="set-grid">${moreLaunchers}</div>
      </section>
    </main>
    <main class="content chat-page hidden" id="page-chat" role="tabpanel" aria-labelledby="tab-chat-btn" tabindex="-1"></main>
    <main class="content members-page hidden" id="page-members" role="tabpanel" aria-labelledby="tab-members-btn" tabindex="-1"></main>
    <main class="content orders-page hidden" id="page-orders" role="tabpanel" aria-labelledby="tab-orders-btn" tabindex="-1"></main>
    <main class="content interact-page hidden" id="page-interact" role="tabpanel" aria-labelledby="tab-interact-btn" tabindex="-1"></main>
    <div id="toast-box" class="toast-box" aria-live="polite"></div>
    ${moreOverlays}
    ${loginOverlay}`;
  // 浮层挂在 #app 根层而非 #page-params：页签切换时 page-params 会 display:none，
  // 若登录浮层是其子节点，在「聊天/订单」页触发 session-expired 就会「弹了但看不见」。
  updateConnBadge();

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

  app.querySelectorAll<HTMLElement>("[data-tab]").forEach((btn) =>
    btn.addEventListener("click", () => setActiveTab(btn.dataset.tab as TabKey))
  );
  app.querySelectorAll<HTMLElement>("[data-goto]").forEach((btn) =>
    btn.addEventListener("click", () => setActiveTab(btn.dataset.goto as TabKey))
  );
  wireTabKeys(app);
}

// 页签键盘：Alt+1~5 全局直达；tablist 内 ←/→/Home/End 移动并激活。
// 浮层打开时不响应（否则焦点会留在浮层、页面却切走了）。
function wireTabKeys(app: HTMLElement) {
  document.addEventListener("keydown", (e) => {
    if (e.ctrlKey || e.metaKey || e.shiftKey) return;
    if (document.querySelector(".overlay:not(.hidden)")) return;
    if (e.altKey && /^[1-5]$/.test(e.key)) {
      e.preventDefault();
      setActiveTab(TAB_ORDER[Number(e.key) - 1]);
    }
  });
  const list = app.querySelector<HTMLElement>('.tabs[role="tablist"]');
  if (!list) return;
  list.addEventListener("keydown", (e) => {
    const cur = TAB_ORDER.indexOf((document.activeElement as HTMLElement)?.dataset?.tab as TabKey);
    if (cur < 0) return;
    let next = -1;
    if (e.key === "ArrowRight") next = (cur + 1) % TAB_ORDER.length;
    else if (e.key === "ArrowLeft") next = (cur - 1 + TAB_ORDER.length) % TAB_ORDER.length;
    else if (e.key === "Home") next = 0;
    else if (e.key === "End") next = TAB_ORDER.length - 1;
    if (next < 0) return;
    e.preventDefault();
    setActiveTab(TAB_ORDER[next]);
    $(`tab-${TAB_ORDER[next]}-btn`)?.focus();
  });
}

// ---- 总开关：拉取三个全局开关的当前状态 ----
// 桌面端必须有文字状态（「已开启/已关闭/切换中…」），不能只靠一个绿色滑块：
// 颜色 + 位置是单通道信息，且开关切换是异步 Rust 调用，没有进行中反馈会被反复点。
type SwKey = "autoreply" | "wxauto" | "bridge";

function showSwitchState(key: SwKey, on: boolean | null, mode: "" | "pending" | "error" = "") {
  const item = document.querySelector<HTMLElement>(`.sw-run-item[data-sw="${key}"]`);
  const label = document.getElementById(`sw-state-${key}`);
  if (!item || !label) return;
  item.classList.toggle("on", on === true);
  label.className = "sw-run-state" + (mode ? ` ${mode}` : "");
  label.textContent =
    on === null ? "读取中…" :
    mode === "pending" ? "切换中…" :
    mode === "error" ? "切换失败" :
    on ? "已开启" : "已关闭";
}

function switchInput(key: SwKey): HTMLInputElement | null {
  return $(`sw-${key}`) as HTMLInputElement | null;
}

async function loadSwitches() {
  const readers: Array<[SwKey, Promise<any>]> = [
    ["autoreply", autoreplyApi.getConfig()],
    ["wxauto", wxautoApi.getConfig()],
    ["bridge", bridgeApi.getConfig()],
  ];
  await Promise.all(readers.map(async ([key, p]) => {
    try {
      const c: any = await p;
      const on = !!c?.enabled;
      const input = switchInput(key);
      if (input) input.checked = on;
      showSwitchState(key, on);
    } catch {
      showSwitchState(key, null, "error");
    }
  }));
}

/** 统一的开关切换封装：禁用 → 进行中 → 成功/失败回滚，杜绝异步期间的重复点击。 */
async function toggleSwitch(key: SwKey, apply: (on: boolean) => Promise<any>, onLabel: string, offLabel: string) {
  const input = switchInput(key);
  if (!input) return;
  const on = input.checked;
  input.disabled = true;
  showSwitchState(key, on, "pending");
  try {
    await apply(on);
    showSwitchState(key, on);
    toast(on ? onLabel : offLabel);
  } catch (err: any) {
    input.checked = !on;                       // 回滚到服务端真实状态
    showSwitchState(key, !on, "error");
    toast("切换失败：" + (err?.message || err), false);
    window.setTimeout(() => showSwitchState(key, !on), 2000);
  } finally {
    input.disabled = false;
  }
}

function setActiveTab(tab: TabKey) {
  if (!TAB_ORDER.includes(tab)) return;
  TAB_ORDER.forEach((name) => {
    const btn = $(`tab-${name}-btn`);
    const on = name === tab;
    btn?.classList.toggle("active", on);
    btn?.setAttribute("aria-selected", String(on));
    btn?.setAttribute("tabindex", on ? "0" : "-1");
    $(`page-${name}`)?.classList.toggle("hidden", !on);
  });
  document.title = `抱抱团 · ${TAB_META[tab].title}`;
  try { localStorage.setItem(LAST_TAB_KEY, tab); } catch { /* 隐私模式下忽略 */ }
  // 键盘用户切页后把焦点送进新页面（不滚动、不画焦点框），读屏能接续朗读
  const page = $(`page-${tab}`);
  if (page && document.activeElement?.closest(".tabs, [data-goto]")) {
    page.focus({ preventScroll: true });
  }
}

function wire() {
  $("btn-open-login")?.addEventListener("click", () => { openOverlay("ov-login"); setLoginMode("pwd"); });
  $("btn-logout")?.addEventListener("click", onLogout);
  $("btn-login")?.addEventListener("click", onLogin);
  $("seg-pwd")?.addEventListener("click", () => setLoginMode("pwd"));
  $("seg-qr")?.addEventListener("click", () => setLoginMode("qr"));
  $("pcqr-refresh")?.addEventListener("click", () => startPcQr());
  // 关闭登录浮层时停轮询（遮罩 / ✕）
  document.querySelectorAll("#ov-login [data-close-ov]").forEach((el) =>
    el.addEventListener("click", () => stopQrPoll())
  );
  document.addEventListener("keydown", (e) => { if (e.key === "Escape") stopQrPoll(); });
  // 总开关（统一走 toggleSwitch，含进行中/失败回滚）
  $("sw-autoreply")?.addEventListener("change", () =>
    void toggleSwitch("autoreply", (on) => autoreplyApi.setEnabled(on), "AI 自动回复已开启", "AI 自动回复已关闭"));
  $("sw-wxauto")?.addEventListener("change", () =>
    void toggleSwitch("wxauto", (on) => wxautoApi.setConfig({ enabled: on }), "微信通道已开启（自动启动 Python）", "微信通道已关闭"));
  $("sw-bridge")?.addEventListener("change", () =>
    void toggleSwitch("bridge", (on) => bridgeApi.setEnabled(on), "团长桥已开启", "团长桥已关闭"));

  // 顶栏连接徽标：任意页签点击都能进连接设置
  $("conn-badge")?.addEventListener("click", () => { openOverlay("ov-login"); setLoginMode("pwd"); });
  // 登录表单 Enter 直接提交（桌面端登录最高频的操作，原实现只能鼠标点按钮）
  $("login-fields")?.addEventListener("keydown", (e) => {
    if ((e as KeyboardEvent).key === "Enter" && (e.target as HTMLElement).tagName !== "TEXTAREA") {
      e.preventDefault();
      void onLogin();
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
    // 表单 password 输入框不回显（type=password），初始为空：若用户只是改动某个
    // 参数触发防抖保存，不能把磁盘上已落盘的密码覆盖成空，否则 token 过期后
    // 无法自动重登。表单有值用表单值，否则继承已加载的 state.connection.password。
    password: conn.password || state.connection.password || "",
    token: state.connection.token || "",
  };
  try {
    // 局部合并保存：绝不能全量覆盖，否则会抹掉 autoreply / memberqr 绑定 /
    // channel_settings 等由其它页面维护的节点（升级后「配置失效」的根因）。
    await kv.patch({ connection: savedConn, ...params });
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
    const missing = ["base_url", "telephone", "password"].find((k) => !conn[k]);
    if (missing) $(`conn-${missing}`)?.focus();
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
    toast("登录成功，正在后台加载数据…");
    refreshStatusBar();
    notifyLoginChanged(true);
    // 并行加载，不阻塞 UI
    Promise.all([syncRemote(), loadSwitches()]).then(() => {
      const role = res.account?.role ? `（角色：${res.account.role}）` : "";
      toast(`登录成功${role}，所有页面已就绪`);
    }).catch(() => {
      toast("数据加载部分失败，可手动刷新", false);
    });
  } catch (e: any) {
    toast("登录失败：" + (e?.message || e), false);
  } finally { btn?.classList.remove("loading"); }
}

// 局部刷新状态条（不重建整个页面，避免丢子页面轮询/状态）
function refreshStatusBar() {
  const loggedIn = !!state.connection.token;
  updateConnBadge();                 // 顶栏徽标与状态条同源同步
  const bar = document.querySelector(".status-bar");
  if (!bar) return;
  const tel = state.connection.telephone || "";
  const url = state.connection.base_url || "";
  const sub = loggedIn ? [tel, url].filter(Boolean).join(" · ") : "登录后所有页签共用此连接";
  bar.innerHTML = `
    <div class="status-left">
      <span class="conn-dot ${loggedIn ? "on" : "off"}"></span>
      <div>
        <div class="status-title">${loggedIn ? "已连接 BBT-ZB" : "未连接"}</div>
        <div class="status-sub">${sub}</div>
      </div>
    </div>
    <div class="btn-row" style="gap:8px">
      ${loggedIn ? `<button class="btn" id="btn-logout">登出</button><button class="btn primary" id="btn-open-login">连接设置</button>` : `<button class="btn primary" id="btn-open-login">立即登录</button>`}
    </div>`;
  $("btn-open-login")?.addEventListener("click", () => { openOverlay("ov-login"); setLoginMode("pwd"); });
  $("btn-logout")?.addEventListener("click", onLogout);
}

// 登出：清 token + 本地持久化 + 局部刷新
async function onLogout() {
  state.connection.token = "";
  state.connection.telephone = "";
  try {
    await bbt.setConnection(state.connection.base_url, "", undefined);
    const savedConn = { base_url: state.connection.base_url, telephone: "", token: "" };
    await kv.patch({ connection: savedConn, ...state.params });
  } catch {}
  refreshStatusBar();
  notifyLoginChanged(false);
  toast("已登出");
}

// token 失效时自动弹登录浮层（全局监听错误事件）
function onSessionExpired(msg: string) {
  toast("登录已失效，请重新登录", false);
  state.connection.token = "";
  refreshStatusBar();
  notifyLoginChanged(false);
  openOverlay("ov-login");
  setLoginMode("pwd");
}

// 登录态变化时通知所有子页面自动刷新（无需手动点）
function notifyLoginChanged(loggedIn: boolean) {
  window.dispatchEvent(new CustomEvent("login-changed", {
    detail: { loggedIn, telephone: state.connection.telephone || "", token: loggedIn ? "1" : "" }
  }));
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
    // 先把 base_url 灌进 Rust state（保留当前 token，不要清掉）
    await bbt.setConnection(baseUrl, state.connection.token || undefined, state.connection.telephone || undefined);
    const r: any = await bbt.call("pc/create", {});
    const d = r?.data;
    if (!r?.status || !d) {
      const msg = r?.msg || "生成二维码失败";
      statusEl.textContent = msg + "，请改用账号登录";
      if (refreshEl) refreshEl.classList.remove("hidden");
      toast(msg, false);
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
          toast("扫码登录成功，正在后台加载数据…");
          refreshStatusBar();
          notifyLoginChanged(true);
          // 并行加载，不阻塞 UI
          Promise.all([syncRemote(), loadSwitches()]).then(() => {
            const role = pd.role ? `（角色：${pd.role}）` : "";
            toast(`扫码登录成功${role}，所有页面已就绪`);
          }).catch(() => {
            toast("数据加载部分失败，可手动刷新", false);
          });
        }
      } catch (e: any) {
        // 单次轮询失败不中断，下一轮再试
        console.warn("pc/poll err", e);
      }
    }, 1500);
  } catch (e: any) {
    const msg = e?.message || String(e);
    statusEl.textContent = "二维码启动失败：" + msg;
    if (refreshEl) refreshEl.classList.remove("hidden");
    toast("二维码生成失败：" + msg, false);
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
    if (typeof tab === "string" && TAB_ORDER.includes(tab as TabKey)) {
      setActiveTab(tab as TabKey);
    }
  });
  // token 失效自动弹登录
  window.addEventListener("session-expired", () => {
    onSessionExpired("会话已失效");
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
  // 恢复上次工作页：桌面客户端重启/升级后回到原岗位，不用每次从首页点进去
  try {
    const last = localStorage.getItem(LAST_TAB_KEY) as TabKey | null;
    if (last && TAB_ORDER.includes(last)) setActiveTab(last);
  } catch { /* 隐私模式下忽略 */ }
}
