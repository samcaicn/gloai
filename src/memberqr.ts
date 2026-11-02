// 团员 iLink 二维码管理：团长身份登录 → 查看 BBT-ZB 团员名册 → 给每个团员
// 生成 iLink 绑定二维码并展示 → 绑定过期（微信会话失效）提示并支持点击重新生成。
//
// 复用既有后端命令：
//   - login(base_url, tel, pwd)             团长登录（account/login，写 conn.token）
//   - bbt_call("leader/listMembers", {})   拉取团员名册
//   - ilink_begin_login()                   取 iLink 扫码二维码（返回 login_id/content/svg）
//   - ilink_poll_login(login_id)           轮询扫码状态（confirmed 即该微信上线）
//   - ilink_list_accounts()                当前已绑定账号及状态
//   - onIlinkEvent                         监听 session_expired 等事件

import { api } from "./api";
import { ilink, onIlinkEvent } from "./ilink";

interface MemberInfo {
  id: string;
  name: string;
  telephone: string;
  telephoneMask: string;
}

// loginId -> 轮询句柄（用于停止）
const polls = new Map<string, number>();
// bot_id -> 该账号当前处于「重新生成二维码」状态（refreshAccounts 重建容器时保留 SVG）
const regenMode = new Set<string>();
const regenSvg = new Map<string, string>();

let started = false;

function esc(s: string): string {
  return (s || "").replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c] as string)
  );
}

function toast(msg: string, ok = true) {
  const box = document.getElementById("toast-box");
  if (!box) return;
  const el = document.createElement("div");
  el.className = "toast " + (ok ? "ok" : "err");
  el.textContent = msg;
  box.appendChild(el);
  setTimeout(() => el.remove(), 3200);
}

function g(id: string): HTMLElement | null {
  return document.getElementById(id);
}

export async function renderMemberQr(root: HTMLElement) {
  root.innerHTML = `
    <div class="members-wrap">
      <section class="card">
        <h2>团长身份登录</h2>
        <p class="card-desc">登录 BBT-ZB 团长账号后，可查看团员名册并为每个团员生成 iLink 绑定二维码。</p>
        <div class="grid2">
          <div class="field">
            <div class="field-label">接口地址</div>
            <input id="mq-base" class="input" placeholder="https://bbt-zb.pages.dev"/>
          </div>
          <div class="field">
            <div class="field-label">手机号</div>
            <input id="mq-tel" class="input" value="19999999999"/>
          </div>
          <div class="field">
            <div class="field-label">密码</div>
            <input id="mq-pwd" class="input" type="password" value="666666"/>
          </div>
          <div class="field">
            <div class="field-label">&nbsp;</div>
            <button class="btn primary" id="mq-login">团长身份登录</button>
            <span id="mq-login-status" class="ml8"></span>
          </div>
        </div>
      </section>

      <section class="card">
        <div class="row-between">
          <h2>BBT-ZB 团员名册</h2>
          <button class="btn small" id="mq-refresh-roster">刷新名册</button>
        </div>
        <p class="hint">每个团员可生成 1 个 iLink 二维码，团员用微信扫码即可把其微信绑定上线。点击「生成二维码」后请尽快扫码（二维码约 2 分钟有效）。</p>
        <div id="mq-roster" class="member-grid">
          <div class="empty">尚未登录或名册为空</div>
        </div>
        <div class="btn-row">
          <button class="btn small" id="mq-gen-all">为全部团员生成二维码</button>
        </div>
      </section>

      <section class="card">
        <div class="row-between">
          <h2>已绑定 iLink 账号（团员微信）</h2>
          <button class="btn small" id="mq-refresh-accounts">刷新</button>
        </div>
        <p class="hint">账号状态变为「绑定过期」表示该团员微信会话已失效（约 24h 无互动）。点击「生成新二维码」让团员重新扫码即可恢复。</p>
        <div id="mq-accounts" class="member-grid">
          <div class="empty">暂无已绑定账号</div>
        </div>
      </section>
    </div>`;

  // 预填接口地址
  try {
    const cfg: any = await api.loadConfig();
    const base = cfg?.connection?.base_url || cfg?.base_url || "";
    if (base) (g("mq-base") as HTMLInputElement).value = base;
  } catch {}

  g("mq-login")?.addEventListener("click", onLogin);
  g("mq-refresh-roster")?.addEventListener("click", loadRoster);
  g("mq-refresh-accounts")?.addEventListener("click", refreshAccounts);
  g("mq-gen-all")?.addEventListener("click", genAllRosterQr);

  if (!started) {
    started = true;
    // 监听 iLink 账号状态变化（含 session_expired）
    onIlinkEvent((ev: any) => {
      if (ev?.kind === "account" && ev?.event === "status" && ev?.status === "session_expired") {
        refreshAccounts();
      }
    });
    // 定时刷新已绑定账号状态（捕获会话过期等）
    setInterval(() => {
      if (document.visibilityState === "visible") refreshAccounts();
    }, 6000);
  }

  // 首次进入若已登录则自动拉名册
  void loadRoster();
  void refreshAccounts();
}

async function onLogin() {
  const base = (g("mq-base") as HTMLInputElement)?.value.trim();
  const tel = (g("mq-tel") as HTMLInputElement)?.value.trim();
  const pwd = (g("mq-pwd") as HTMLInputElement)?.value;
  const st = g("mq-login-status");
  if (!base || !tel || !pwd) {
    if (st) st.textContent = "请填写 接口地址 / 手机号 / 密码";
    return;
  }
  if (st) st.textContent = "登录中…";
  try {
    const res: any = await api.login(base, tel, pwd);
    if (!res || !res.token) {
      if (st) st.textContent = "登录失败：未返回 token";
      toast("登录失败：未返回 token", false);
      return;
    }
    // 确保后端连接信息已写入（含 token），后续 bbt_call 才能带鉴权
    await api.setConnection(base, res.token, tel);
    const role = res.account?.role ? `（角色：${res.account.role}）` : "";
    if (st) st.textContent = `已登录${role}`;
    toast("团长身份登录成功");
    void loadRoster();
    void refreshAccounts();
  } catch (e: any) {
    const msg = e?.message || String(e);
    if (st) st.textContent = "登录失败：" + msg;
    toast("登录失败：" + msg, false);
  }
}

function extractRoster(data: any): MemberInfo[] {
  // 兼容多种返回结构：{list:[...]} / {members:[...]} / [...] / {data:{...}}
  let arr: any[] = [];
  if (Array.isArray(data)) arr = data;
  else if (data?.list && Array.isArray(data.list)) arr = data.list;
  else if (data?.members && Array.isArray(data.members)) arr = data.members;
  return arr.map((m, i) => ({
    id: String(m.id ?? m.user_id ?? m.telephone ?? i),
    name: m.name || m.nickname || m.displayName || "",
    telephone: m.telephone || "",
    telephoneMask: m.telephoneMask || m.telephoneMasked || (m.telephone ? maskTel(m.telephone) : ""),
  }));
}

function maskTel(t: string): string {
  if (t.length >= 7) return t.slice(0, 3) + "****" + t.slice(-4);
  return t;
}

async function loadRoster() {
  const box = g("mq-roster");
  if (!box) return;
  try {
    const r: any = await api.call("leader/listMembers", {});
    const data = r?.result?.data ?? r?.data ?? r;
    const members = extractRoster(data);
    if (members.length === 0) {
      box.innerHTML = `<div class="empty">名册为空（或当前团长账号无此权限 / 需指定团）</div>`;
      return;
    }
    box.innerHTML = members.map((m) => rosterCard(m)).join("");
    members.forEach((m) => {
      g(`mqgen-${m.id}`)?.addEventListener("click", () => genRosterQr(m));
    });
  } catch (e: any) {
    const msg = e?.message || String(e);
    const friendly = /未登录|失效|未配置|token/i.test(msg)
      ? "请先在上方「团长身份登录」后再查看名册"
      : `拉取名册失败：${esc(msg)}`;
    box.innerHTML = `<div class="empty">${friendly}</div>`;
  }
}

function rosterCard(m: MemberInfo): string {
  const title = m.name || m.telephoneMask || m.telephone || m.id;
  return `
    <div class="mcard" data-mid="${esc(m.id)}">
      <div class="mcard-name">${esc(title)}</div>
      ${m.telephoneMask ? `<div class="mcard-sub">${esc(m.telephoneMask)}</div>` : ""}
      <div class="mcard-qr" id="mqr-${esc(m.id)}"></div>
      <div class="mcard-status" id="mqrs-${esc(m.id)}">未生成二维码</div>
      <button class="btn small" id="mqgen-${esc(m.id)}">生成 iLink 二维码</button>
    </div>`;
}

async function genRosterQr(m: MemberInfo) {
  const qrId = `mqr-${m.id}`;
  const st = g(`mqrs-${m.id}`);
  const btn = g(`mqgen-${m.id}`);
  if (!st || !btn) return;
  btn.setAttribute("disabled", "true");
  st.textContent = "正在获取二维码…";
  try {
    const r: any = await ilink.beginLogin();
    paintQr(qrId, r, m.id);
    st.textContent = "请用微信扫码";
    startPoll(r.login_id, qrId, (status) => {
      if (status === "confirmed") {
        st.textContent = "✅ 已绑定上线";
        toast(`团员 ${m.telephoneMask || m.name || m.id} 已绑定`);
        void refreshAccounts();
      } else if (status === "scaned") {
        st.textContent = "已扫码，请确认";
      } else if (status === "wait") {
        st.textContent = "等待扫码…";
      }
    });
  } catch (e: any) {
    st.textContent = "获取失败：" + (e?.message || e);
    btn.removeAttribute("disabled");
  }
}

async function genAllRosterQr() {
  const cards = Array.from(document.querySelectorAll("#mq-roster .mcard")) as HTMLElement[];
  for (const c of cards) {
    const id = c.getAttribute("data-mid");
    if (!id) continue;
    const btn = c.querySelector(`#mqgen-${CSS.escape(id)}`) as HTMLButtonElement | null;
    if (btn && !btn.hasAttribute("disabled")) {
      const m: MemberInfo = {
        id,
        name: c.querySelector(".mcard-name")?.textContent || "",
        telephone: "",
        telephoneMask: c.querySelector(".mcard-sub")?.textContent || "",
      };
      await genRosterQr(m);
    }
  }
}

function paintQr(qrId: string, r: any, botIdHint?: string) {
  const el = g(qrId);
  if (!el) return;
  if (r?.svg) el.innerHTML = r.svg;
  else if (r?.content) el.innerHTML = `<img src="${esc(r.content)}" alt="二维码"/>`;
  else el.textContent = "无法渲染二维码";
  // 账号重生成态：缓存 SVG 供 refreshAccounts 重建容器后回填
  if (botIdHint && regenMode.has(botIdHint)) regenSvg.set(botIdHint, el.innerHTML);
}

async function refreshAccounts() {
  const box = g("mq-accounts");
  if (!box) return;
  let accounts: any[] = [];
  try {
    accounts = (await ilink.listAccounts()) || [];
  } catch {
    return;
  }
  if (accounts.length === 0) {
    box.innerHTML = `<div class="empty">暂无已绑定账号</div>`;
    return;
  }
  box.innerHTML = accounts.map((a) => accountCard(a)).join("");
  accounts.forEach((a) => {
    const bot = a.bot_id;
    const regenBtn = g(`mqregen-${bot}`);
    if (regenBtn) regenBtn.addEventListener("click", () => regenAccountQr(bot));
    // 若处于重新生成状态，把缓存的二维码重新注入（容器刚被重建）
    if (regenMode.has(bot)) {
      const svg = regenSvg.get(bot);
      if (svg) {
        const el = g(`mqra-${bot}`);
        if (el) el.innerHTML = svg;
      }
    }
  });
}

function accountCard(a: any): string {
  const status = a.status || "offline";
  const expired = status === "session_expired";
  const label: Record<string, string> = {
    running: "在线",
    offline: "离线",
    session_expired: "绑定过期",
    error: "异常",
  };
  return `
    <div class="mcard ${expired ? "expired" : ""}" data-bot="${esc(a.bot_id)}">
      <div class="row-between">
        <div class="mcard-name">${esc(a.label || a.user_id || a.bot_id)}</div>
        <span class="badge badge-${esc(status)}">${label[status] || status}</span>
      </div>
      <div class="mcard-sub">${esc(a.user_id || "")}</div>
      <div class="mcard-qr" id="mqra-${esc(a.bot_id)}"></div>
      ${expired ? `<button class="btn small warn-btn" id="mqregen-${esc(a.bot_id)}">生成新二维码</button>` : ""}
    </div>`;
}

async function regenAccountQr(botId: string) {
  const qrId = `mqra-${botId}`;
  const qr = g(qrId);
  if (!qr) return;
  regenMode.add(botId);
  try {
    const r: any = await ilink.beginLogin();
    paintQr(qrId, r, botId);
    startPoll(r.login_id, qrId, (status) => {
      if (status === "confirmed") {
        regenMode.delete(botId);
        regenSvg.delete(botId);
        toast("账号已重新绑定上线");
        void refreshAccounts();
      }
    });
  } catch (e: any) {
    regenMode.delete(botId);
    regenSvg.delete(botId);
    toast("生成二维码失败：" + (e?.message || e), false);
  }
}

// 通用轮询：对 login_id 周期 poll；仅 confirmed 时停止（"expired" 表示二维码已刷新，继续轮询）。
// qrElId 每次 tick 重新解析，避免 refreshAccounts 重建 DOM 后写到游离节点。
function startPoll(loginId: string, qrElId: string, onStatus: (status: string) => void) {
  if (polls.has(loginId)) {
    clearInterval(polls.get(loginId)!);
    polls.delete(loginId);
  }
  const timer = window.setInterval(async () => {
    try {
      const r: any = await ilink.pollLogin(loginId);
      const status = r?.status || "wait";
      if (r?.svg || r?.content) {
        const el = g(qrElId);
        if (el) paintQr(qrElId, r, qrElId.replace(/^mqra-/, ""));
      }
      onStatus(status);
      if (status === "confirmed") {
        clearInterval(timer);
        polls.delete(loginId);
      }
    } catch {
      clearInterval(timer);
      polls.delete(loginId);
      onStatus("error");
    }
  }, 1500);
  polls.set(loginId, timer);
}
