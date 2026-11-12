// 团员 iLink 二维码管理：复用「首页」团长登录态 → 查看 BBT-ZB 团员名册 → 给每个团员
// 生成 iLink 绑定二维码。扫码成功即完成绑定（后端直接落盘 memberqr.map 与 accounts.json），
// 名册卡片直接显示「✅ 已登录」，不再单独维护一份「已绑定账号」列表。
//
// 复用既有后端命令：
//   - bbt_call("leader/listMembers", {})   拉取团员名册
//   - ilink_begin_login(member_id)          取二维码，并把团员 id 绑定到本次扫码会话
//   - ilink_poll_login(login_id)           轮询扫码状态（confirmed 即后端完成绑定）
//   - ilink_list_accounts()                当前已登录账号及状态
//   - onIlinkEvent                         监听会话失效等事件

import { api } from "./api";
import { ilink, onIlinkEvent, autoreplyApi, onAutoreplyEvent, wxautoApi, bridgeApi, onWxautoEvent } from "./ilink";
import { channelsApi, memberCacheLabel, onRosterUpdated } from "./channels";

interface MemberInfo {
  id: string;
  name: string;
  realName: string;
  status: string;
  telephone: string;
  telephoneMask: string;
}

interface GroupInfo {
  id: string;
  name: string;
}

// ---- hub 成员映射：member_id -> bot_id（持久化在 config.json 的 memberqr.map）----
// 对齐 openilink-hub 思路：每个成员对应一个 iLink 机器人账号，hub 记录绑定关系，
// 扫码确认后落盘，过期重建后自动更新指向新 bot。
let memberMap: Record<string, string> = {};
// 团员名册本地缓存镜像：切团 / 重绘画卡片全部走内存，0 网络请求。
// 数据由 Rust roster_cache 提供（升级/重启后从 member_roster.json 恢复）。
let rosterGroups: GroupInfo[] = [];
let rosterByGroup: Record<string, any[]> = {};
let rosterCache: any = null;
// bot_id -> 已绑定账号状态快照（ilink_list_accounts）；用于判断「已登录/已过期」
let accountByBot = new Map<string, any>();
// 正在展示扫码二维码的团员 id：定时刷新不覆盖用户正在扫的二维码
const qrActive = new Set<string>();

function botOfMember(memberId: string): string {
  return memberMap[memberId] || "";
}
async function persistMap(): Promise<void> {
  try {
    await api.memberqrSetMap(memberMap);
  } catch {
    /* 落盘失败不阻塞 UI；后端在扫码成功时也会直接落盘，见 ilink_begin_login */
  }
}

// loginId -> 轮询句柄（用于停止）
const polls = new Map<string, number>();

// 当前名册总人数（用于绑定状态总览）
let rosterSize = 0;
// AI 自动回复是否已开启（用于绑定卡片的 AI 徽标）
let arEnabled = false;

let started = false;

// 配对区块临时数据（保存时复用，避免二次拉取微信会话列表）
let pairingTargets: any = null;

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
      <div class="bind-status" id="mq-bind-status">加载绑定状态…</div>
      <section class="card">
        <div class="row-between">
          <h2>BBT-ZB 团员名册</h2>
          <div>
            <span id="mq-login-state" class="hint mr8"></span>
            <button class="btn small primary hidden" id="mq-goto-login">去首页登录</button>
            <button class="btn small" id="mq-refresh-roster">刷新名册</button>
          </div>
        </div>
        <div id="mq-group-bar" class="mt8"></div>
        <div class="hint mt8" id="mq-cache-state">名册读取中…</div>
        <p class="hint">扫码成功即完成绑定，登录态与团员名册都缓存在本机；48 小时内无需重复扫码，升级 / 重启、短暂离线同样有效。「刷新名册」才在线回源。</p>
        <div id="mq-roster" class="member-grid">
          <div class="empty">尚未登录或名册为空</div>
        </div>
        <div class="btn-row">
          <button class="btn small" id="mq-gen-all">为全部团员生成二维码</button>
        </div>
      </section>

      <section class="card">
        <div class="row-between">
          <h2>团员 ↔ 微信私聊/群聊 配对</h2>
          <div>
            <button class="btn small" id="mq-pair-refresh">刷新列表</button>
            <button class="btn small primary" id="mq-pair-save">保存配对</button>
          </div>
        </div>
        <p class="hint">身份模型：每个微信私聊 / 群聊就是 1 个团员。在此把「团员名册」与「微信会话」手动配对，配对后系统能精确识别「这条微信消息来自哪个团员」，图片支付校验也会据此比对<b>该团员</b>的订单金额。</p>
        <div class="hint" id="mq-pair-note"></div>
        <div id="mq-pair-list" class="pair-list">
          <div class="empty">加载中…</div>
        </div>
        <div class="hint mt8" id="mq-pair-state"></div>
      </section>
    </div>`;

  // 加载 hub 成员映射
  try {
    memberMap = (await api.memberqrGetMap()) || {};
  } catch {
    memberMap = {};
  }

  g("mq-goto-login")?.addEventListener("click", () => {
    window.dispatchEvent(new CustomEvent("goto-tab", { detail: "params" }));
  });
  g("mq-refresh-roster")?.addEventListener("click", () => void loadRoster(true));
  g("mq-gen-all")?.addEventListener("click", genAllRosterQr);
  g("mq-pair-refresh")?.addEventListener("click", () => void loadPairing(true));
  g("mq-pair-save")?.addEventListener("click", savePairing);

  if (!started) {
    started = true;
    // 读取 AI 自动回复开关（仅用于账号卡片 AI 徽标与绑定总览）
    void loadArEnabled();
    // 监听 iLink 账号状态变化（含 session_expired）
    onIlinkEvent((ev: any) => {
      if (ev?.kind === "account") {
        // status / restored / error 都刷新一次，保证「已登录」状态及时回显
        void refreshAccounts();
        const st = ev?.status || ev?.account?.status || "";
        if (st === "session_expired") {
          toast(`团员 ${ev?.label || ev?.account?.label || ev?.bot_id || ""} 微信会话已失效，请重新扫码`, false);
        }
      }
    });
    // AI 自动回复开关变化时刷新徽标（在聊天配置页改动后同步过来）
    onAutoreplyEvent(() => {
      void loadArEnabled();
    });
    // 后台刷新拿到新名册时自动重绘（读缓存，0 网络）；只在内容真变化时推送
    void onRosterUpdated(() => { void loadRoster(); });
    // 微信通道状态变化（就绪/重启/报错）时自动补拉配对列表：只在上一帧没有任何
    // 微信会话时才刷新——没有会话时用户无可选、无未保存选择，刷新零风险；
    // 一旦有会话就停止自动刷，避免覆盖用户下拉到一半还没点保存的配对。
    void onWxautoEvent((l: any) => {
      if (pairHadWx || l?.level === "debug") return;
      if (pairReloadTimer !== null) window.clearTimeout(pairReloadTimer);
      pairReloadTimer = window.setTimeout(() => {
        pairReloadTimer = null;
        void loadPairing();
      }, 1500);
    });
    // 监听团长登录态变化：登录成功后自动拉名册/账号，无需手动点
    window.addEventListener("login-changed", (e: any) => {
      const d = e.detail || {};
      updateLoginStateUI(!!d.loggedIn, d.telephone || "");
      // 换登录账号 = 名册归属变了，强制回源（缓存按账号隔离，不会串号）
      void loadRoster(!!d.loggedIn);
      void refreshAccounts();
      void loadPairing();
    });
    // 定时刷新已绑定账号状态（捕获会话过期等）+ 绑定总览
    setInterval(() => {
      if (document.visibilityState === "visible") {
        refreshAccounts();
        refreshBindStatus();
      }
    }, 6000);
  }

  // 登录状态提示（token 就绪与否）
  void refreshLoginState();
  // 先加载已缓存的 iLink 登录态，再渲染名册卡片——否则卡片会误判成「未绑定，请扫码」
  void (async () => {
    await refreshAccounts().catch(() => { /* 账号态拉不到也要继续渲染名册 */ });
    void loadRoster();
    void loadPairing();
  })();
}

function updateLoginStateUI(loggedIn: boolean, telephone?: string) {
  const st = g("mq-login-state");
  const btn = g("mq-goto-login");
  if (loggedIn) {
    if (st) st.textContent = "✅ 已连接" + (telephone ? `（${telephone}）` : "");
    btn?.classList.add("hidden");
  } else {
    if (st) st.textContent = "未登录";
    btn?.classList.remove("hidden");
  }
}

async function refreshLoginState() {
  let token = "", tel = "";
  try {
    const cfg: any = await api.loadConfig();
    token = cfg?.connection?.token || "";
    tel = cfg?.connection?.telephone || "";
  } catch {}
  updateLoginStateUI(!!token, tel);
}

// 仅读取 AI 自动回复开关（徽标/总览用），配置维护在「聊天配置」页
async function loadArEnabled() {
  try {
    const cfg: any = await autoreplyApi.getConfig();
    arEnabled = !!cfg?.enabled;
  } catch {
    arEnabled = false;
  }
  refreshBindStatus();
  refreshAccounts();
}

function extractRoster(arr: any[]): MemberInfo[] {
  return (Array.isArray(arr) ? arr : []).map((m, i) => ({
    id: String(m.id ?? m.user_id ?? m.telephone ?? i),
    name: m.name || m.nickname || m.displayName || "",
    realName: m.realName || "",
    status: m.status || "active",
    telephone: m.telephone || "",
    telephoneMask: m.telephoneMask || m.telephoneMasked || (m.telephone ? maskTel(m.telephone) : ""),
  }));
}

function maskTel(t: string): string {
  if (t.length >= 7) return t.slice(0, 3) + "****" + t.slice(-4);
  return t;
}

function fmtTime(ts: number): string {
  if (!ts) return "";
  const d = new Date(ts);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** 取名册：默认读本地缓存（0 网络），force=true 才同步在线回源。
 *  名册、团列表、缓存状态都来自 Rust roster_cache；刷新失败时后端会自动
 *  回退旧缓存，因此这里不会因一次网络抖动变成「名册为空」。 */
async function loadRoster(force = false) {
  const box = g("mq-roster");
  if (!box) return;
  const stateEl = g("mq-cache-state");
  if (force) {
    if (stateEl) stateEl.textContent = "正在在线刷新名册…";
    box.innerHTML = `<div class="empty">正在在线刷新名册…</div>`;
  }
  try {
    const r: any = await api.memberRoster(force);
    rosterCache = r?.cache || null;
    rosterGroups = (r?.groups || []).map((x: any) => ({
      id: String(x.id),
      name: x.name || `团#${x.id}`,
    }));
    rosterByGroup = r?.by_group || {};
    if (stateEl) {
      stateEl.textContent = memberCacheLabel(rosterCache) + (r?.note ? ` · ${r.note}` : "");
    }
    if (rosterGroups.length === 0) {
      box.innerHTML = `<div class="empty">${esc(r?.note || "当前团长账号还没有管理任何团")}</div>`;
      rosterSize = 0;
      refreshBindStatus();
      return;
    }
    // 多团切换保留用户当前选择；只有第一次或被删掉的团才回到第一个
    const cur = currentGroupId();
    const defaultGid = rosterGroups.some((gp) => gp.id === cur) ? cur : rosterGroups[0].id;
    (window as any).__mqGroupId = defaultGid;
    const bar = g("mq-group-bar");
    if (bar) {
      bar.innerHTML = rosterGroups.length > 1
        ? `<select id="mq-group" class="input" style="max-width:220px">${rosterGroups
            .map((gp) => `<option value="${esc(gp.id)}"${gp.id === defaultGid ? " selected" : ""}>${esc(gp.name)}</option>`)
            .join("")}</select>`
        : `<span class="hint">团：${esc(rosterGroups[0].name)}</span>`;
      bar.querySelector("#mq-group")?.addEventListener("change", () => loadMembersOf(currentGroupId()));
    }
    await loadMembersOf(defaultGid);
  } catch (e: any) {
    const msg = e?.message || String(e);
    if (stateEl) stateEl.textContent = "名册读取失败（可点「刷新名册」重试）";
    box.innerHTML = `<div class="empty">拉取名册失败：${esc(msg)}</div>`;
  }
}

function currentGroupId(): string {
  const sel = document.getElementById("mq-group") as HTMLSelectElement | null;
  if (sel?.value) return sel.value;
  // 单团时从提示条取，兜底取 ledger 第一团
  return (window as any).__mqGroupId || "";
}

/** 切团 = 纯内存渲染：名册已按团缓存在 rosterByGroup，不再打一次线上接口。 */
async function loadMembersOf(groupId: string) {
  const box = g("mq-roster");
  if (!box) return;
  if (!groupId) {
    box.innerHTML = `<div class="empty">请先选择团</div>`;
    return;
  }
  (window as any).__mqGroupId = groupId;
  const members = extractRoster(rosterByGroup[groupId] || []);
  if (members.length === 0) {
    box.innerHTML = `<div class="empty">该团暂无成员（可点「刷新名册」在线更新）</div>`;
    rosterSize = 0;
    refreshBindStatus();
    return;
  }
  rosterSize = members.length;
  box.innerHTML = members.map((m) => rosterCard(m)).join("");
  members.forEach((m) => {
    g(`mqgen-${m.id}`)?.addEventListener("click", () => genRosterQr(m));
  });
  refreshBindStatus();
}

interface LoginView {
  badge: string; badgeCls: string; qr: string; status: string; btn: string; btnCls: string;
  expired: boolean;
}

/**
 * 团员绑定卡片的展示状态。核心规则：只要账号未过期，就一律显示「已登录」——
 * 登录态已缓存在本机 accounts.json，重启后由后端自动恢复，不要求用户重复扫码。
 */
function fmtActive(ms?: number): string {
  if (!ms) return "";
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, "0");
  return ` · 最近活跃 ${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

function loginView(bot: string, acc: any): LoginView {
  const st = String(acc?.status || "");
  const expired = !!acc?.expired || st === "session_expired";
  if (!bot) {
    return { badge: "", badgeCls: "", qr: "", status: "未生成二维码", btn: "生成 iLink 二维码", btnCls: "", expired: false };
  }
  if (expired) {
    return {
      badge: "需重新扫码",
      badgeCls: "badge-session_expired",
      qr: `<div class="mqr-state is-expired"><div class="mqr-state-title">⚠ 微信会话已失效</div><div class="mqr-state-sub">通常 48 小时无互动后失效；点下方按钮重新扫码，之后自动继续缓存</div></div>`,
      status: "微信会话已失效（48h 无互动），重新扫码即可恢复",
      btn: "重新扫码",
      btnCls: "primary",
      expired: true,
    };
  }
  const online = st === "running";
  return {
    badge: "已绑定",
    badgeCls: "badge-running",
    qr: `<div class="mqr-state is-logged">
      <div class="mqr-state-title">✅ 已绑定</div>
      <div class="mqr-state-sub">登录态已缓存在本机 · 48 小时内无需重复扫码（升级 / 重启同样有效）</div>
      <div class="mqr-state-meta">${esc(acc?.label || acc?.user_id || bot)}${online ? " · 在线" : " · 待连接"}${esc(fmtActive(acc?.last_active_ms))}</div>
    </div>`,
    status: online ? "已绑定 · 在线，无需扫码" : "已缓存登录态 · 无需扫码",
    btn: "重新扫码（更换微信）",
    btnCls: "",
    expired: false,
  };
}

/** 局部刷新名册卡片的登录态（不重建 DOM，避免打断用户正在扫的二维码） */
function syncRosterLoginStates() {
  document.querySelectorAll("#mq-roster .mcard").forEach((el) => {
    const card = el as HTMLElement;
    const mid = card.dataset.mid || "";
    if (!mid || qrActive.has(mid)) return;
    const bot = botOfMember(mid);
    const v = loginView(bot, bot ? accountByBot.get(bot) : null);
    const qrEl = card.querySelector(".mcard-qr");
    const stEl = card.querySelector(".mcard-status");
    const btn = card.querySelector("button");
    const badge = card.querySelector(".badge");
    if (qrEl) qrEl.innerHTML = v.qr;
    if (stEl) stEl.textContent = v.status;
    if (btn) { btn.textContent = v.btn; btn.className = `btn small ${v.btnCls}`.trim(); }
    if (badge && v.badge) badge.className = `badge ${v.badgeCls}`.trim();
    if (badge && v.badge) badge.textContent = v.badge;
    card.classList.toggle("expired", v.expired);
  });
}

function rosterCard(m: MemberInfo): string {
  const title = m.name || m.realName || m.telephoneMask || m.id;
  const boundBot = botOfMember(m.id);
  const v = loginView(boundBot, boundBot ? accountByBot.get(boundBot) : null);
  const stLabel: Record<string, string> = { active: "在团", disabled: "停用", pendingJoin: "审批中" };
  const stCls = m.status === "active" ? "badge-running" : "";
  const badge = v.badge
    ? `<span class="badge ${v.badgeCls}">${v.badge}</span>`
    : `<span class="badge ${stCls}">${stLabel[m.status] || m.status}</span>`;
  return `
    <div class="mcard ${v.expired ? "expired" : ""}" data-mid="${esc(m.id)}">
      <div class="row-between">
        <div class="mcard-name">${esc(title)}${m.realName && m.realName !== title ? `<span class="mcard-sub">（${esc(m.realName)}）</span>` : ""}</div>
        ${badge}
      </div>
      ${m.telephoneMask ? `<div class="mcard-sub">${esc(m.telephoneMask)}</div>` : ""}
      <div class="mcard-qr" id="mqr-${esc(m.id)}">${v.qr}</div>
      <div class="mcard-status" id="mqrs-${esc(m.id)}">${esc(v.status)}</div>
      <button class="btn small ${v.btnCls}" id="mqgen-${esc(m.id)}">${v.btn}</button>
    </div>`;
}

async function genRosterQr(m: MemberInfo) {
  const qrId = `mqr-${m.id}`;
  const st = g(`mqrs-${m.id}`);
  const btn = g(`mqgen-${m.id}`);
  if (!st || !btn) return;
  const oldBot = botOfMember(m.id);
  const oldAcc = oldBot ? accountByBot.get(oldBot) : null;
  if (oldAcc && !oldAcc.expired && oldAcc.status !== "session_expired") {
    toast("该团员已登录；继续扫码会更换绑定的微信", true);
  }
  btn.setAttribute("disabled", "true");
  qrActive.add(m.id);
  st.textContent = "正在获取二维码…";
  try {
    // member_id 随扫码会话交给后端：扫码成功由后端直接落盘「团员→bot」绑定，
    // 不再依赖前端异步写盘（前端异步写曾导致绑定丢失、订单页下拉为空）。
    const r: any = await ilink.beginLogin(m.id);
    if (!r?.svg && !r?.content) {
      throw new Error("iLink 服务端未返回二维码");
    }
    paintQr(qrId, r);
    st.textContent = "请用微信扫码（约 2 分钟有效）";
    toast(`已生成团员 ${m.telephoneMask || m.name || m.id} 的扫码二维码`);
    startPoll(r.login_id, qrId, async (status, resp) => {
      if (status === "confirmed") {
        const botId = resp?.account?.bot_id || "";
        if (botId) {
          // 更换绑定时清掉旧账号，避免旧记录残留成「绑定过期」
          if (oldBot && oldBot !== botId) {
            try { await ilink.removeAccount(oldBot); } catch { /* 忽略 */ }
          }
          memberMap[m.id] = botId;
        }
        qrActive.delete(m.id);
        // 以后端落盘结果为准，重新读取映射（避免前端写盘竞态覆盖）；
        // 若后端未写入则前端兜底再写一次，保证「扫码成功 = 已绑定」。
        let persisted = false;
        try {
          const fresh = await api.memberqrGetMap();
          if (fresh) {
            memberMap = fresh;
            persisted = !!fresh[m.id];
          }
        } catch { /* 保留内存态 */ }
        if (!persisted && botId) {
          memberMap[m.id] = botId;
          await persistMap();
        }
        // 立刻把二维码区替换成「已登录」状态卡，不等名册重渲染
        await refreshAccounts();
        syncRosterLoginStates();
        st.textContent = "✅ 已绑定 · 登录态已缓存到本机";
        toast(`团员 ${m.telephoneMask || m.name || m.id} 已绑定，登录态已缓存`);
        // 自动接通 AI 回复：确保三个开关全部打开
        try {
          await autoreplyApi.setEnabled(true);    // AI 自动回复
          await wxautoApi.setConfig({ enabled: true });  // 微信通道
          await bridgeApi.setConfig({ enabled: true });  // 团长桥
          toast("AI 自动回复已接通");
        } catch (e: any) {
          console.warn("接通AI回复失败:", e);
        }
        void loadRoster();
      } else if (status === "scaned") {
        st.textContent = "已扫码，请在手机微信确认";
      } else if (status === "wait") {
        st.textContent = "等待扫码…";
      } else if (status === "expired") {
        qrActive.delete(m.id);
        st.textContent = "二维码已过期，请点按钮重新生成";
        toast("二维码已过期，请重新生成", false);
        btn.removeAttribute("disabled");
      } else if (status === "error") {
        qrActive.delete(m.id);
        st.textContent = "轮询失败，请重新生成二维码";
        btn.removeAttribute("disabled");
      }
    });
  } catch (e: any) {
    qrActive.delete(m.id);
    const msg = e?.message || String(e);
    st.textContent = "获取失败";
    toast("团员二维码获取失败：" + msg, false);
    btn.removeAttribute("disabled");
  }
}

async function genAllRosterQr() {
  const cards = Array.from(document.querySelectorAll("#mq-roster .mcard")) as HTMLElement[];
  for (const c of cards) {
    const id = c.getAttribute("data-mid");
    if (!id) continue;
    // 已登录且未过期：登录态已缓存，跳过，不要求重复扫码
    const bot = botOfMember(id);
    const acc = bot ? accountByBot.get(bot) : null;
    if (bot && acc && !acc.expired && acc.status !== "session_expired") continue;
    const btn = c.querySelector(`#mqgen-${CSS.escape(id)}`) as HTMLButtonElement | null;
    if (btn && !btn.hasAttribute("disabled")) {
      const m: MemberInfo = {
        id,
        name: c.querySelector(".mcard-name")?.textContent || "",
        realName: "",
        status: "active",
        telephone: "",
        telephoneMask: c.querySelector(".mcard-sub")?.textContent || "",
      };
      await genRosterQr(m);
    }
  }
}

function paintQr(qrId: string, r: any) {
  const el = g(qrId);
  if (!el) return;
  if (r?.svg) el.innerHTML = r.svg;
  else if (r?.content) el.innerHTML = `<img src="${esc(r.content)}" alt="二维码"/>`;
  else el.textContent = "无法渲染二维码";
}

/**
 * 刷新已绑定账号状态。只维护内存快照 + 局部更新名册卡片，
 * 不依赖任何「已绑定账号列表」容器（该区块已移除：扫码成功即绑定，名册卡片直接显示已登录）。
 */
async function refreshAccounts() {
  let accounts: any[] = [];
  try {
    accounts = (await ilink.listAccounts()) || [];
  } catch {
    return;
  }
  accountByBot = new Map(accounts.map((a: any) => [a.bot_id, a]));
  syncRosterLoginStates();
  refreshBindStatus(accounts.filter((a) => a.status === "running").length);
}

// ---------------- 绑定状态总览 ----------------
async function refreshBindStatus(online?: number) {
  const el = g("mq-bind-status");
  if (!el) return;
  const bound = Object.keys(memberMap).length;
  let on = online;
  if (on === undefined) {
    try {
      const accounts = (await ilink.listAccounts()) || [];
      on = accounts.filter((a: any) => a.status === "running").length;
    } catch {
      on = 0;
    }
  }
  const arCls = arEnabled ? "on" : "off";
  const arTxt = arEnabled ? "已接通" : "未接通";
  el.innerHTML = `
    <span class="bs-pill">名册 <b>${rosterSize}</b> 人</span>
    <span class="bs-pill">已绑定 <b>${bound}</b></span>
    <span class="bs-pill">在线 <b>${on}</b></span>
    <span class="bs-pill ai ${arCls}">AI 回复 ${arTxt}</span>`;
}

// ---------------- AI 自动回复开关读取（徽标/总览用，配置维护在「聊天配置」页） ----------------

// 通用轮询：对 login_id 周期 poll；仅 confirmed 时停止（"expired" 表示二维码已刷新，继续轮询）。
// qrElId 每次 tick 重新解析，避免 refreshAccounts 重建 DOM 后写到游离节点。
// 回调携带完整 poll 响应（confirmed 时含 account.bot_id，供 hub 记录成员映射）。
function startPoll(loginId: string, qrElId: string, onStatus: (status: string, resp?: any) => void) {
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
        if (el) paintQr(qrElId, r);
      }
      onStatus(status, r);
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

// ---------------- 团员 ↔ 微信私聊/群聊 配对 ----------------

function pairMemberName(m: any): string {
  return m?.name || m?.realName || m?.telephoneMask || m?.id || "";
}

function pairWxLabel(s: any): string {
  const name = s?.name || s?.id || "未知会话";
  return name;
}

// 上一帧配对列表里有没有微信会话：空的时候才允许「微信通道就绪」自动补拉，
// 有会话后绝不自动刷新（避免覆盖用户下拉到一半、还没点保存的选择）。
let pairHadWx = false;
let pairReloadTimer: number | null = null;

async function loadPairing(force = false) {
  const box = g("mq-pair-list");
  const note = g("mq-pair-note");
  const stateEl = g("mq-pair-state");
  if (!box) return;
  if (note) note.textContent = "";
  if (stateEl) stateEl.textContent = force ? "正在在线刷新团员列表…" : "读取本地缓存与微信会话中…";
  try {
    const [targets, pairing] = await Promise.all([
      channelsApi.targets(force),
      api.wechatPairingGet().catch(() => ({})),
    ]);
    pairingTargets = targets;
    const members: any[] = (targets?.member || []).map((m: any) => ({
      id: String(m.id),
      name: m.name || m.realName || "",
      realName: m.realName || "",
      telephoneMask: m.telephoneMask || "",
    }));
    const wxPrivate: any[] = targets?.wx_private || [];
    const wxGroup: any[] = targets?.wx_group || [];
    const wxNote: string = targets?.wx_note || "";
    const map: Record<string, any> = pairing || {};

    if (members.length === 0) {
      box.innerHTML = `<div class="empty">团员名册为空（请先到首页登录团长账号，或刷新名册）</div>`;
      if (stateEl) stateEl.textContent = "";
      return;
    }
    const hasWx = wxPrivate.length > 0 || wxGroup.length > 0;
    pairHadWx = hasWx;
    if (!hasWx) {
      // 空列表必须说明原因（后端给了具体 wx_note：通道未开启 / sidecar 报错），不能只给一句模糊提示
      if (note) note.textContent = wxNote || "提示：微信通道暂无会话列表，通道就绪后会自动出现私聊/群聊，无需手动刷新。";
    }

    const optsPrivate = wxPrivate
      .map((s) => `<option value="${esc(s.id || "")}">${esc(pairWxLabel(s))}</option>`)
      .join("");
    const optsGroup = wxGroup
      .map((s) => `<option value="${esc(s.id || "")}">${esc(pairWxLabel(s))}</option>`)
      .join("");

    box.innerHTML = members
      .map((m) => {
        const mid = String(m.id);
        const cur = map[mid];
        const curLabel = cur?.wxname
          ? `${esc(cur.wxname)}${cur?.is_group ? "（群）" : ""}`
          : "未配对";
        return `
        <div class="pair-row" data-mid="${esc(mid)}">
          <div class="pair-name">${esc(pairMemberName(m))}${m.realName && m.realName !== pairMemberName(m) ? `<span class="pair-sub">（${esc(m.realName)}）</span>` : ""}</div>
          <select class="input pair-sel" data-mid="${esc(mid)}">
            <option value="">— 未配对 —</option>
            ${optsPrivate ? `<optgroup label="微信私聊">${optsPrivate}</optgroup>` : ""}
            ${optsGroup ? `<optgroup label="微信群聊">${optsGroup}</optgroup>` : ""}
          </select>
          <div class="pair-cur">当前：${curLabel}</div>
        </div>`;
      })
      .join("");

    // 事后逐个设置选中态（避免 optgroup 内 option 选中态错乱）
    members.forEach((m) => {
      const mid = String(m.id);
      const curWxid = map[mid]?.wxid || "";
      const sel = box.querySelector<HTMLSelectElement>(`.pair-sel[data-mid="${CSS.escape(mid)}"]`);
      if (sel && curWxid) sel.value = curWxid;
    });

    if (stateEl) {
      const n = Object.keys(map).filter((k) => map[k]?.wxid).length;
      const wxPart = hasWx ? ` · 微信私聊 ${wxPrivate.length} · 群聊 ${wxGroup.length}` : " · 微信会话暂无";
      stateEl.textContent = `已配对 ${n} / ${members.length} 名团员${wxPart} · ${memberCacheLabel(targets?.member_cache)}`;
    }
    if (targets?.member_note && note && !note.textContent) note.textContent = targets.member_note;
  } catch (e: any) {
    const msg = e?.message || String(e);
    if (box) box.innerHTML = `<div class="empty">加载失败：${esc(msg)}</div>`;
    if (stateEl) stateEl.textContent = "";
    if (note) note.textContent = /未登录|失效|token|会话/i.test(msg) ? "请先到首页登录团长账号" : "";
  }
}

async function savePairing() {
  const box = g("mq-pair-list");
  const stateEl = g("mq-pair-state");
  if (!box) return;
  if (!pairingTargets) {
    toast("请先刷新列表再保存", false);
    return;
  }
  const members: any[] = pairingTargets.member || [];
  const wxAll: any[] = [...(pairingTargets.wx_private || []), ...(pairingTargets.wx_group || [])];
  const map: Record<string, any> = {};
  let saved = 0;
  box.querySelectorAll<HTMLElement>(".pair-row").forEach((row) => {
    const mid = row.getAttribute("data-mid") || "";
    const sel = row.querySelector<HTMLSelectElement>(".pair-sel");
    const wxid = sel?.value || "";
    if (!mid || !wxid) return;
    const wx = wxAll.find((s) => String(s.id) === wxid);
    const m = members.find((x) => String(x.id) === mid);
    if (!wx || !m) return;
    map[mid] = {
      wxid,
      wxname: wx.name || wxid,
      is_group: !!wx.is_group,
      member_name: pairMemberName(m),
    };
    saved++;
  });
  try {
    await api.wechatPairingSet(map);
    if (stateEl) stateEl.textContent = `已保存 ${saved} 条配对（本机持久化，升级/重启有效）`;
    toast(`已保存 ${saved} 条团员 ↔ 微信会话配对`);
  } catch (e: any) {
    const msg = e?.message || String(e);
    if (stateEl) stateEl.textContent = "保存失败：" + msg;
    toast("配对保存失败：" + msg, false);
  }
}
