// 团长聊天工作台：iLink 多账号扫码 + 会话收发（文本/图片/语音）+ 1对多桥开关。
// 数据面全部走 ilink.ts -> Rust 后端；Rust 经 "ilink-event" 事件实时推送。
import { ilink, bridgeApi, autoreplyApi, onIlinkEvent, onAutoreplyEvent, IlinkEvent, AccountInfo, ChatRecord, BridgeConfig, AutoReplyConfig, AutoReplyLog, mediaDataUrl, fileToB64 } from "./ilink";

interface ChatState {
  accounts: AccountInfo[];
  activeBot: string;
  // bot_id -> peer -> records
  history: Map<string, Map<string, ChatRecord[]>>;
  activePeer: string;
  bridge: BridgeConfig | null;
  autoreply: AutoReplyConfig | null;
}

const state: ChatState = {
  accounts: [],
  activeBot: "",
  history: new Map(),
  activePeer: "",
  bridge: null,
  autoreply: null,
};

let chatRoot: HTMLElement | null = null;
let qrTimer: number | null = null;

function esc(s: string): string {
  return s.replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c] as string)
  );
}

function fmtTime(ts: number): string {
  if (!ts) return "";
  const d = new Date(ts);
  return `${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")} ${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}

export function renderChat(container: HTMLElement) {
  chatRoot = container;
  container.innerHTML = `
    <div class="chat-layout">
      <aside class="chat-accounts">
        <div class="acc-head">
          <span>iLink 账号</span>
          <button class="btn small" id="ilink-add">＋扫码登录</button>
        </div>
        <div class="acc-list" id="acc-list"></div>
        <div class="bridge-panel">
          <div class="acc-head"><span>团长桥（bbt-zb ↔ iLink）</span></div>
          <label class="switch"><input type="checkbox" id="bridge-toggle"/><span></span></label>
          <div class="bridge-hint">开启后：团长在 bbt-zb 发出的新消息自动转发到下方路由表的所有 iLink 账号；iLink 收到的消息按路由回传团长会话。</div>
          <textarea id="bridge-targets" rows="6" placeholder='[{"bot_id":"…","wechat_user_id":"…","bbt_session_id":"…"}]'></textarea>
          <div class="btn-row">
            <button class="btn small" id="bridge-save">保存路由</button>
          </div>
        </div>
        <div class="bridge-panel" id="ar-panel">
          <div class="acc-head"><span>AI 自动回复（bbt-zb 生成）</span></div>
          <label class="switch"><input type="checkbox" id="ar-toggle"/><span></span></label>
          <div class="bridge-hint">开启后：iLink 收到的<b>私聊/群聊</b>消息自动调 bbt-zb（团员身份 chat/send）由服务端 AI 生成回复并回发微信。人设/FAQ/话术在「参数调整」页维护，配额与接管状态以平台为准。</div>
          <input id="ar-tel" class="input" placeholder="团员账号手机号（触发服务端 AI）" />
          <input id="ar-pwd" class="input" type="password" placeholder="团员账号密码（留空 = 不修改）" />
          <label class="ar-check"><input type="checkbox" id="ar-private"/> 私聊自动回复</label>
          <div class="ar-row">群聊：
            <select id="ar-gmode">
              <option value="off">不回复</option>
              <option value="at">@触发</option>
              <option value="all">全部回复</option>
            </select>
          </div>
          <input id="ar-atnames" class="input" placeholder="@触发名单（逗号分隔，空=任意@都触发）" />
          <input id="ar-kw" class="input" placeholder="群聊关键词触发（逗号分隔，可空）" />
          <div class="ar-row">冷却：<input id="ar-cd" class="input" type="number" min="1" style="width:64px"/> 秒／会话</div>
          <div class="btn-row">
            <button class="btn small" id="ar-save">保存自动回复</button>
          </div>
          <div class="ar-log" id="ar-log"></div>
        </div>
      </aside>
      <div class="chat-main">
        <div class="conv-head" id="conv-head">选择左侧账号查看会话</div>
        <div class="chat-body">
          <div class="conv-list" id="conv-list"></div>
          <div class="msg-pane">
            <div class="msg-list" id="msg-list"></div>
            <div class="composer">
              <input type="file" id="file-image" accept="image/*" hidden/>
              <input type="file" id="file-voice" accept=".silk,.mp3" hidden/>
              <button class="btn small" id="btn-image" title="发图片">🖼</button>
              <button class="btn small" id="btn-voice" title="发语音（silk/mp3）">🎤</button>
              <input type="text" id="msg-input" placeholder="输入消息，回车发送…"/>
              <button class="btn primary small" id="btn-send">发送</button>
            </div>
          </div>
        </div>
      </div>
    </div>
    <div id="qr-modal" class="modal hidden">
      <div class="modal-card">
        <h3>微信扫码登录 iLink</h3>
        <div class="qr-box" id="qr-box"></div>
        <div class="qr-status" id="qr-status">正在获取二维码…</div>
        <div class="btn-row center"><button class="btn" id="qr-cancel">取消</button></div>
      </div>
    </div>
    <div id="toast-box-chat" class="toast-box"></div>`;
  wire();
  refreshAccounts();
  refreshBridge();
  refreshAutoreply();
}

function toast(msg: string, ok = true) {
  const box = document.getElementById("toast-box-chat");
  if (!box) return;
  const el = document.createElement("div");
  el.className = "toast " + (ok ? "ok" : "err");
  el.textContent = msg;
  box.appendChild(el);
  setTimeout(() => el.remove(), 3200);
}

function wire() {
  document.getElementById("ilink-add")?.addEventListener("click", startLogin);
  document.getElementById("qr-cancel")?.addEventListener("click", cancelLogin);
  document.getElementById("bridge-toggle")?.addEventListener("change", onBridgeToggle);
  document.getElementById("bridge-save")?.addEventListener("click", saveBridgeTargets);
  document.getElementById("ar-toggle")?.addEventListener("change", onArToggle);
  document.getElementById("ar-save")?.addEventListener("click", saveAutoreply);
  document.getElementById("btn-send")?.addEventListener("click", sendCurrent);
  document.getElementById("msg-input")?.addEventListener("keydown", (e) => {
    if ((e as KeyboardEvent).key === "Enter") sendCurrent();
  });
  document.getElementById("btn-image")?.addEventListener("click", () => (document.getElementById("file-image") as HTMLInputElement)?.click());
  document.getElementById("btn-voice")?.addEventListener("click", () => (document.getElementById("file-voice") as HTMLInputElement)?.click());
  document.getElementById("file-image")?.addEventListener("change", onPickImage);
  document.getElementById("file-voice")?.addEventListener("change", onPickVoice);
}

// ---------------- 账号 ----------------

async function refreshAccounts() {
  try {
    state.accounts = (await ilink.listAccounts()) || [];
  } catch (e: any) {
    toast("账号列表获取失败：" + (e?.message || e), false);
    return;
  }
  const list = document.getElementById("acc-list");
  if (!list) return;
  list.innerHTML = state.accounts
    .map(
      (a) => `
      <div class="acc-item ${a.bot_id === state.activeBot ? "active" : ""}" data-bot="${esc(a.bot_id)}">
        <span class="dot ${statusClass(a.status)}"></span>
        <div class="acc-meta">
          <div class="acc-label">${esc(a.label || a.bot_id)}</div>
          <div class="acc-sub">${statusText(a.status)}</div>
        </div>
        <button class="icon-btn acc-del" data-bot="${esc(a.bot_id)}" title="移除账号">✕</button>
      </div>`
    )
    .join("");
  list.querySelectorAll(".acc-item").forEach((el) =>
    el.addEventListener("click", (e) => {
      if ((e.target as HTMLElement).classList.contains("acc-del")) return;
      selectBot((el as HTMLElement).dataset.bot || "");
    })
  );
  list.querySelectorAll(".acc-del").forEach((el) =>
    el.addEventListener("click", async () => {
      const bot = (el as HTMLElement).dataset.bot || "";
      if (!confirm("移除该账号？本地消息会保留。")) return;
      await ilink.removeAccount(bot);
      if (state.activeBot === bot) { state.activeBot = ""; state.activePeer = ""; }
      refreshAccounts();
    })
  );
}

function statusClass(s: string) {
  return s === "running" ? "ok" : s === "session_expired" ? "warn" : "off";
}
function statusText(s: string) {
  return s === "running" ? "在线" : s === "session_expired" ? "会话过期（需重新扫码）" : s === "offline" ? "离线" : s.startsWith("error") ? s : "离线";
}

async function selectBot(bot: string) {
  state.activeBot = bot;
  if (!state.history.has(bot)) {
    try {
      const recs = (await ilink.localHistory(bot, 500)) || [];
      const byPeer = new Map<string, ChatRecord[]>();
      for (const r of recs) {
        if (!byPeer.has(r.peer)) byPeer.set(r.peer, []);
        byPeer.get(r.peer)!.push(r);
      }
      state.history.set(bot, byPeer);
    } catch {
      state.history.set(bot, new Map());
    }
  }
  renderConvList();
  renderAccountsOnly();
  renderMsgs();
}

function renderAccountsOnly() {
  const list = document.getElementById("acc-list");
  if (!list) return;
  list.querySelectorAll(".acc-item").forEach((el) => {
    el.classList.toggle("active", (el as HTMLElement).dataset.bot === state.activeBot);
  });
}

// ---------------- 会话列表 / 消息 ----------------

function peersOf(bot: string): string[] {
  const m = state.history.get(bot);
  if (!m) return [];
  return [...m.keys()].sort((a, b) => {
    const la = m.get(a) || [];
    const lb = m.get(b) || [];
    return (lb[lb.length - 1]?.ts || 0) - (la[la.length - 1]?.ts || 0);
  });
}

function renderConvList() {
  const el = document.getElementById("conv-list");
  if (!el) return;
  const peers = peersOf(state.activeBot);
  el.innerHTML = peers.length
    ? peers
        .map((p) => {
          const recs = state.history.get(state.activeBot)!.get(p) || [];
          const last = recs[recs.length - 1];
          const preview = last?.kind === "text" ? last.text : last?.kind === "image" ? "[图片]" : last?.kind === "voice" ? `[语音]${last.text ? " " + last.text : ""}` : `[${last?.kind || "?"}]`;
          return `
          <div class="conv-item ${p === state.activePeer ? "active" : ""}" data-peer="${esc(p)}">
            <div class="conv-name">${esc(p)}</div>
            <div class="conv-preview">${esc((preview || "").slice(0, 40))}</div>
            <div class="conv-time">${fmtTime(last?.ts || 0)}</div>
          </div>`;
        })
        .join("")
    : `<div class="empty">暂无会话<br/><small>对方在微信里给该账号发一条消息后出现</small></div>`;
  el.querySelectorAll(".conv-item").forEach((n) =>
    n.addEventListener("click", () => {
      state.activePeer = (n as HTMLElement).dataset.peer || "";
      renderConvList();
      renderMsgs();
    })
  );
  const head = document.getElementById("conv-head");
  if (head) {
    const acc = state.accounts.find((a) => a.bot_id === state.activeBot);
    head.textContent = acc ? `${acc.label} · ${state.activePeer || "选择会话"}` : "选择左侧账号查看会话";
  }
}

function renderMsgs() {
  const el = document.getElementById("msg-list");
  if (!el) return;
  const recs = state.history.get(state.activeBot)?.get(state.activePeer) || [];
  el.innerHTML = recs
    .map((r) => bubbleHtml(r))
    .join("");
  el.querySelectorAll("[data-media-idx]").forEach((n) => loadMedia(n as HTMLElement));
  el.scrollTop = el.scrollHeight;
}

function bubbleHtml(r: ChatRecord): string {
  const side = r.dir === "out" ? "out" : "in";
  let body = "";
  if (r.kind === "text") {
    body = `<div class="bubble-text">${esc(r.text).replace(/\n/g, "<br/>")}</div>`;
  } else if (r.kind === "image") {
    body = `<div class="bubble-media" data-media-idx="1"><span class="media-ph">图片加载中…</span></div>`;
  } else if (r.kind === "voice") {
    const secs = Math.max(1, Math.round((r.playtime_ms || 0) / 1000));
    body = `<div class="bubble-voice">🎤 语音 ${secs}″${r.text ? `<div class="voice-text">${esc(r.text)}</div>` : ""}</div>`;
  } else {
    body = `<div class="bubble-text">[${r.kind}] ${esc(r.text || "")}</div>`;
  }
  const status = r.status === "failed" ? `<span class="msg-fail">发送失败</span>` : "";
  return `
    <div class="msg-row ${side}" data-id="${esc(r.id)}">
      <div class="bubble ${side}">
        ${body}
        <div class="bubble-time">${fmtTime(r.ts)} ${status}</div>
      </div>
    </div>`;
}

async function loadMedia(el: HTMLElement) {
  const row = el.closest(".msg-row") as HTMLElement | null;
  const id = row?.dataset.id;
  if (!id) return;
  const recs = state.history.get(state.activeBot)?.get(state.activePeer) || [];
  const rec = recs.find((r) => r.id === id);
  if (!rec || !rec.media) return;
  try {
    const url = await mediaDataUrl(rec);
    if (rec.kind === "image") {
      el.innerHTML = `<img class="msg-img" src="${url}"/>`;
    } else {
      el.innerHTML = `<audio controls src="${url}"></audio>`;
    }
  } catch (e: any) {
    el.innerHTML = `<span class="media-ph">媒体加载失败：${esc(String(e?.message || e)).slice(0, 60)}</span>`;
  }
}

// ---------------- 发送 ----------------

async function sendCurrent() {
  const input = document.getElementById("msg-input") as HTMLInputElement | null;
  const text = input?.value.trim() || "";
  if (!text) return;
  if (!state.activeBot || !state.activePeer) return toast("先选择账号与会话", false);
  input!.value = "";
  try {
    await ilink.sendText(state.activeBot, state.activePeer, text);
    pushLocal({
      id: `local-${Date.now()}`, bot_id: state.activeBot, dir: "out", peer: state.activePeer,
      kind: "text", text, ts: Date.now(), status: "sent",
    });
  } catch (e: any) {
    toast("发送失败：" + (e?.message || e), false);
  }
}

async function onPickImage(e: Event) {
  const inp = e.target as HTMLInputElement;
  const f = inp.files?.[0];
  inp.value = "";
  if (!f || !state.activeBot || !state.activePeer) return;
  try {
    const b64 = await fileToB64(f);
    await ilink.sendImage(state.activeBot, state.activePeer, b64, f.name);
    pushLocal({
      id: `local-${Date.now()}`, bot_id: state.activeBot, dir: "out", peer: state.activePeer,
      kind: "image", text: "", media: { data_b64: b64, mime: f.type || "image/jpeg" }, ts: Date.now(), status: "sent",
    });
  } catch (err: any) {
    toast("图片发送失败：" + (err?.message || err), false);
  }
}

async function onPickVoice(e: Event) {
  const inp = e.target as HTMLInputElement;
  const f = inp.files?.[0];
  inp.value = "";
  if (!f || !state.activeBot || !state.activePeer) return;
  try {
    const b64 = await fileToB64(f);
    const lower = f.name.toLowerCase();
    const encodeType = lower.endsWith(".mp3") ? 2 : 4; // 4=SILK 2=MP3
    const secs = Math.max(1, Math.round(f.size / 3000)); // 粗估时长
    await ilink.sendVoice(state.activeBot, state.activePeer, b64, encodeType, 24000, secs * 1000);
    pushLocal({
      id: `local-${Date.now()}`, bot_id: state.activeBot, dir: "out", peer: state.activePeer,
      kind: "voice", text: "", ts: Date.now(), status: "sent", playtime_ms: secs * 1000,
    });
  } catch (err: any) {
    toast("语音发送失败：" + (err?.message || err), false);
  }
}

function pushLocal(rec: ChatRecord) {
  if (!state.history.has(rec.bot_id)) state.history.set(rec.bot_id, new Map());
  const m = state.history.get(rec.bot_id)!;
  if (!m.has(rec.peer)) m.set(rec.peer, []);
  m.get(rec.peer)!.push(rec);
  if (rec.bot_id === state.activeBot) {
    renderConvList();
    if (rec.peer === state.activePeer) renderMsgs();
  }
}

// ---------------- 扫码登录 ----------------

async function startLogin() {
  const modal = document.getElementById("qr-modal");
  const box = document.getElementById("qr-box");
  const st = document.getElementById("qr-status");
  if (!modal || !box || !st) return;
  modal.classList.remove("hidden");
  st.textContent = "正在获取二维码…";
  box.innerHTML = "";
  try {
    const r = await ilink.beginLogin();
    showQr(r.content, r.svg);
    st.textContent = "请用微信扫码并确认";
    pollLoop(r.login_id);
  } catch (e: any) {
    st.textContent = "获取二维码失败：" + (e?.message || e);
  }
}

function showQr(content: string, svg: string) {
  const box = document.getElementById("qr-box");
  if (!box) return;
  if (/^(https?:\/\/|data:image)/.test(content)) {
    box.innerHTML = `<img src="${esc(content)}" alt="二维码"/>`;
  } else if (svg) {
    box.innerHTML = svg;
  } else {
    box.textContent = "无法渲染二维码";
  }
}

function pollLoop(loginId: string) {
  cancelQrTimer();
  qrTimer = window.setInterval(async () => {
    try {
      const r = await ilink.pollLogin(loginId);
      const st = document.getElementById("qr-status");
      if (r.svg || r.content) showQr(r.content, r.svg);
      if (st) {
        if (r.status === "scaned") st.textContent = "已扫码，请在手机上确认";
        else if (r.status === "confirmed") st.textContent = "登录成功！";
        else if (r.status === "wait") st.textContent = "等待扫码…";
      }
      if (r.status === "confirmed") {
        cancelQrTimer();
        setTimeout(() => {
          document.getElementById("qr-modal")?.classList.add("hidden");
          refreshAccounts();
          toast("iLink 账号已上线");
        }, 800);
      }
    } catch (e: any) {
      const st = document.getElementById("qr-status");
      if (st) st.textContent = "轮询失败：" + (e?.message || e);
    }
  }, 1500);
}

function cancelQrTimer() {
  if (qrTimer !== null) {
    clearInterval(qrTimer);
    qrTimer = null;
  }
}

function cancelLogin() {
  cancelQrTimer();
  document.getElementById("qr-modal")?.classList.add("hidden");
}

// ---------------- 桥 ----------------

async function refreshBridge() {
  try {
    state.bridge = await bridgeApi.getConfig();
  } catch {
    return;
  }
  const t = document.getElementById("bridge-toggle") as HTMLInputElement | null;
  const ta = document.getElementById("bridge-targets") as HTMLTextAreaElement | null;
  if (t && state.bridge) t.checked = state.bridge.enabled;
  if (ta && state.bridge) ta.value = JSON.stringify(state.bridge.targets || [], null, 1);
}

async function onBridgeToggle(e: Event) {
  const on = (e.target as HTMLInputElement).checked;
  try {
    state.bridge = await bridgeApi.setEnabled(on);
    toast(on ? "团长桥已开启" : "团长桥已关闭");
  } catch (err: any) {
    toast("切换失败：" + (err?.message || err), false);
    (e.target as HTMLInputElement).checked = !on;
  }
}

async function saveBridgeTargets() {
  const ta = document.getElementById("bridge-targets") as HTMLTextAreaElement | null;
  if (!ta) return;
  let targets: any[];
  try {
    targets = JSON.parse(ta.value || "[]");
  } catch {
    return toast("路由表 JSON 格式错误", false);
  }
  try {
    state.bridge = await bridgeApi.setConfig({ targets });
    toast("路由表已保存");
  } catch (err: any) {
    toast("保存失败：" + (err?.message || err), false);
  }
}

// ---------------- AI 自动回复 ----------------

async function refreshAutoreply() {
  try {
    state.autoreply = await autoreplyApi.getConfig();
  } catch {
    return;
  }
  const c = state.autoreply;
  if (!c) return;
  const t = document.getElementById("ar-toggle") as HTMLInputElement | null;
  const tel = document.getElementById("ar-tel") as HTMLInputElement | null;
  const pwd = document.getElementById("ar-pwd") as HTMLInputElement | null;
  const priv = document.getElementById("ar-private") as HTMLInputElement | null;
  const gm = document.getElementById("ar-gmode") as HTMLSelectElement | null;
  const atn = document.getElementById("ar-atnames") as HTMLInputElement | null;
  const kw = document.getElementById("ar-kw") as HTMLInputElement | null;
  const cd = document.getElementById("ar-cd") as HTMLInputElement | null;
  if (t) t.checked = c.enabled;
  if (tel) tel.value = c.member_telephone || "";
  if (priv) priv.checked = !!c.private_reply;
  if (gm) gm.value = c.group_mode || "at";
  if (atn) atn.value = (c.at_names || []).join("，");
  if (kw) kw.value = (c.group_keywords || []).join("，");
  if (cd) cd.value = String(c.cooldown_sec || 5);
  // 密码不回显；占位提示已设置与否由后端忽略空值决定
  if (pwd) pwd.placeholder = c.member_telephone ? "密码已保存（留空 = 不修改）" : "团员账号密码";
  // 拉最近日志
  try {
    const logs = (await autoreplyApi.logs(30)) || [];
    const box = document.getElementById("ar-log");
    if (box) {
      box.innerHTML = logs
        .slice()
        .reverse()
        .map((l) => `<div class="ar-line ${l.level}"><span class="ar-t">${fmtTime(l.ts)}</span>${esc(l.msg)}</div>`)
        .join("");
    }
  } catch {}
}

async function onArToggle(e: Event) {
  const on = (e.target as HTMLInputElement).checked;
  try {
    state.autoreply = await autoreplyApi.setEnabled(on);
    toast(on ? "AI 自动回复已开启" : "AI 自动回复已关闭");
  } catch (err: any) {
    toast("切换失败：" + (err?.message || err), false);
    (e.target as HTMLInputElement).checked = !on;
  }
}

async function saveAutoreply() {
  const g = (id: string) => document.getElementById(id) as HTMLInputElement | null;
  const gm = document.getElementById("ar-gmode") as HTMLSelectElement | null;
  const split = (s: string) => (s || "").split(/[，,]/).map((x) => x.trim()).filter(Boolean);
  const value: Partial<AutoReplyConfig> = {
    member_telephone: g("ar-tel")?.value.trim() || "",
    member_password: g("ar-pwd")?.value || "", // 空值后端视为「不修改」
    private_reply: !!g("ar-private")?.checked,
    group_mode: (gm?.value as AutoReplyConfig["group_mode"]) || "at",
    at_names: split(g("ar-atnames")?.value || ""),
    group_keywords: split(g("ar-kw")?.value || ""),
    cooldown_sec: Math.max(1, parseInt(g("ar-cd")?.value || "5", 10) || 5),
  };
  try {
    state.autoreply = await autoreplyApi.setConfig(value);
    toast("自动回复配置已保存");
    refreshAutoreply();
  } catch (err: any) {
    toast("保存失败：" + (err?.message || err), false);
  }
}

// ---------------- 事件 ----------------

export async function initChatEvents() {
  await onIlinkEvent((ev: IlinkEvent) => {
    if (ev.kind === "message" && ev.record) {
      const rec = ev.record as ChatRecord;
      if (!state.history.has(rec.bot_id)) state.history.set(rec.bot_id, new Map());
      const m = state.history.get(rec.bot_id)!;
      if (!m.has(rec.peer)) m.set(rec.peer, []);
      m.get(rec.peer)!.push(rec);
      if (rec.bot_id === state.activeBot) {
        renderConvList();
        if (rec.peer === state.activePeer) renderMsgs();
      }
    } else if (ev.kind === "account" && ev.event === "status") {
      refreshAccounts();
    }
  });
  await onAutoreplyEvent((l: AutoReplyLog) => {
    const box = document.getElementById("ar-log");
    if (!box) return;
    const div = document.createElement("div");
    div.className = "ar-line " + l.level;
    div.innerHTML = `<span class="ar-t">${fmtTime(l.ts)}</span>${esc(l.msg)}`;
    box.prepend(div);
    while (box.children.length > 40) box.lastChild?.remove();
  });
}
