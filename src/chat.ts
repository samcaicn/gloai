// 聊天配置页（原「团长聊天」改造）：照搬 weauto 的聊天对象菜单界面 ——
//   * 聊天对象（私聊和群聊）：逐对象列表（窗口名 + 类型 + ⚙监测设置 + 删除）
//   * 回复规则（私聊+群聊）：私聊开关 / 群聊模式 / @名单 / 关键词 / 白名单 / 黑名单 /
//     禁词 / 群内频控 / 冷却 —— 后端 autoreply 管线按同规则真实过滤
//   * AI 回复日志
// 数据面走 ilink.ts -> Rust autoreply 命令。
import {
  autoreplyApi,
  aiGatewayApi,
  onAutoreplyEvent,
  onWxautoEvent,
  AutoReplyConfig,
  ListenEntry,
  AutoReplyLog,
  AutoReplyTestResult,
} from "./ilink";
import { overlayHtml, openOverlay, wireOverlayClose, bindOverlayEsc } from "./overlay";

const MON_TYPES = ["text", "voice", "image", "video", "file", "link", "quote", "merge", "emotion"];
const MON_LABELS: Record<string, string> = {
  text: "文字", voice: "语音", image: "图片", video: "视频", file: "文件",
  link: "链接", quote: "引用", merge: "合并转发", emotion: "表情",
};

interface ChatCfgState {
  cfg: AutoReplyConfig | null;
}

const state: ChatCfgState = { cfg: null };

function esc(s: string): string {
  return (s || "").replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c] as string)
  );
}

function fmtTime(ts: number): string {
  if (!ts) return "";
  const d = new Date(ts);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

function g(id: string): HTMLElement | null {
  return document.getElementById(id);
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

const splitList = (s: string) =>
  (s || "").split(/[，,]/).map((x) => x.trim()).filter(Boolean);

export function renderChat(container: HTMLElement) {
  container.innerHTML = `
    <div class="chatcfg-wrap">

      <section class="card">
        <div class="row-between">
          <h2>聊天对象（私聊和群聊）</h2>
          <button class="btn small" id="listen-add">＋添加聊天对象</button>
        </div>
        <p class="card-desc">自动回复的好友/群，每个对象可单独设置监测范围与动作。好友与<b>群聊</b>都可以加；人设/监测留空 = 用下方回复规则与平台团长人设。</p>
        <div id="listen-list"></div>
        <div class="btn-row">
          <button class="btn small primary" id="listen-save">保存聊天对象</button>
          <span class="hint ml8">类型与关键字都留空 = 监测全部内容；「不回应」可整体屏蔽某人/某群。</span>
        </div>
      </section>

      <section class="card">
        <div class="row-between">
          <h2>回复规则（私聊+群聊）</h2>
          <button class="btn small" id="ov-open-rules">打开设置</button>
        </div>
        <p class="card-desc">控制 AI 在什么情况下开口。群本身不需要加进「聊天对象」也能按本规则回复；<b>只有想给某个对象配专属监测时</b>才需要加到上方列表。规则较多，收进浮层统一编辑。</p>
      </section>

      ${overlayHtml("ov-rules", "回复规则（私聊+群聊）", `
        <div class="grid2">
          <div class="field">
            <div class="field-label">私聊自动回复</div>
            <label class="switch"><input type="checkbox" id="rl-private"/><span></span></label>
          </div>
          <div class="field">
            <div class="field-label">群聊消息处理模式</div>
            <select id="rl-gmode" class="input">
              <option value="off">不自动回复</option>
              <option value="at">仅接收满足条件的消息（@/关键词）</option>
              <option value="all">接收所有群聊消息</option>
            </select>
          </div>
          <div class="field">
            <div class="field-label">@触发名单<span class="hint">逗号分隔，空 = 任何 @ 都触发</span></div>
            <input id="rl-atnames" class="input" placeholder="如：小助手, 团长机器人"/>
          </div>
          <div class="field">
            <div class="field-label">回复触发关键词<span class="hint">多个逗号分隔</span></div>
            <input id="rl-keywords" class="input" placeholder="如：在吗, 机器人, 你好"/>
          </div>
          <div class="field">
            <div class="field-label">群白名单<span class="hint">仅这些群参与（按群名子串匹配），留空 = 不限制</span></div>
            <input id="rl-whitelist" class="input" placeholder="如：客户群, 售后群"/>
          </div>
          <div class="field">
            <div class="field-label">群黑名单<span class="hint">这些群永不回复（优先级高于白名单）</span></div>
            <input id="rl-blacklist" class="input" placeholder="如：广告群, 闲聊群"/>
          </div>
          <div class="field">
            <div class="field-label">禁词过滤（私聊+群聊）<span class="hint">包含任一禁词则忽略</span></div>
            <input id="rl-banned" class="input" placeholder="如：广告, 加微信"/>
          </div>
          <div class="field">
            <div class="field-label">群内频控（防刷屏）</div>
            <div class="ar-row">
              <input id="rl-rate-win" class="input" type="number" min="1" max="3600" style="width:76px"/> 秒内最多回复
              <input id="rl-rate-max" class="input" type="number" min="0" max="50" style="width:60px"/> 条（0=不限）
            </div>
          </div>
          <div class="field">
            <div class="field-label">每会话冷却<span class="hint">同一会话最小回复间隔</span></div>
            <div class="ar-row"><input id="rl-cd" class="input" type="number" min="1" style="width:64px"/> 秒</div>
          </div>
          <div class="field">
            <div class="field-label">语音消息也回复<span class="hint">需消息带转写文本</span></div>
            <label class="switch"><input type="checkbox" id="rl-voice"/><span></span></label>
          </div>
          <div class="field" style="grid-column: 1 / -1">
            <div class="field-label">后端故障兜底<span class="hint">服务端写接口暂不可用（如数据库当日写入配额打满）时，自动回一句中性话术，避免客户消息完全没人应；其它失败原因（配额、权限、人工接管）仍不回复</span></div>
            <div class="ar-row" style="align-items:center;flex-wrap:wrap;gap:8px">
              <label class="switch"><input type="checkbox" id="rl-fb-on"/><span></span></label>
              <input id="rl-fb-text" class="input" style="flex:1;min-width:200px" placeholder="消息已收到，稍后回复您～"/>
              <span class="hint">同一会话间隔</span>
              <input id="rl-fb-cd" class="input" type="number" min="0" style="width:76px"/>
              <span class="hint">秒</span>
            </div>
          </div>
          <div class="field" style="grid-column: 1 / -1">
            <div class="field-label">发送安全（仅微信 PC 通道）<span class="hint">只收敛行为特征，不做任何反检测；iLink 官方通道不受影响。出现「被要求重新登录」时建议保持默认</span></div>
            <div class="ar-row" style="align-items:center;flex-wrap:wrap;gap:8px">
              <label class="switch"><input type="checkbox" id="rl-sf-front"/><span></span></label>
              <span class="hint">仅前台会话发送</span>
              <span class="hint">每小时上限</span>
              <input id="rl-sf-budget" class="input" type="number" min="0" max="200" style="width:70px"/>
              <span class="hint">条（0=不限）</span>
              <span class="hint">允许时段</span>
              <input id="rl-sf-start" class="input" type="time" style="width:112px"/>
              <span class="hint">—</span>
              <input id="rl-sf-end" class="input" type="time" style="width:112px"/>
            </div>
            <div class="ar-row" style="align-items:center;flex-wrap:wrap;gap:8px;margin-top:8px">
              <span class="hint">连续回发失败</span>
              <input id="rl-sf-streak" class="input" type="number" min="0" max="20" style="width:64px"/>
              <span class="hint">次 → 急停微信通道</span>
              <input id="rl-sf-pause" class="input" type="number" min="0" max="86400" style="width:88px"/>
              <span class="hint">秒（0 次=关闭急停）</span>
            </div>
            <div class="hint" style="margin-top:6px">「仅前台会话发送」开启后：目标会话必须已经是微信当前前台会话，否则本条不回复（不抢焦点、不点侧栏切会话）。关闭会恢复每条消息自动置前 + 切换会话，行为特征明显。</div>
          </div>
          <div class="field" style="grid-column: 1 / -1">
            <div class="field-label">AI 身份<span class="hint">无需配置团员账号：多个微信私聊/群聊/iLink 渠道就是多个团员，消息都只向同一个团长发，由团长 AI 统一回复</span></div>
            <div id="gw-status" class="ar-log">正在读取网关状态…</div>
          </div>
        </div>
        <div class="btn-row">
          <button class="btn small primary" id="rl-save">保存回复规则</button>
          <button class="btn small" id="gw-refresh">刷新 AI 身份</button>
        </div>
        `)}

      <section class="card">
        <div class="row-between">
          <h2>微信发送安全</h2>
          <div class="btn-row" style="margin:0">
            <button class="btn small" id="sf-refresh">刷新</button>
            <button class="btn small" id="sf-resume">解除急停</button>
          </div>
        </div>
        <p class="card-desc">自动回复只做行为侧收敛：限制发送量、只在营业时段发、连续回发失败自动急停、只在目标会话已在前台时才发送。<b>不做任何反检测/绕过技术保护措施</b>；iLink 官方 Bot 通道不受这些限制。</p>
        <div id="sf-status" class="ar-log">正在读取发送安全状态…</div>
      </section>

      <section class="card">
        <div class="row-between">
          <h2>AI 消息网关体检</h2>
          <span class="hint">不向团长发送任何消息</span>
        </div>
        <p class="card-desc">所有渠道消息（多群聊 / 私聊 / iLink）都汇聚成「向团长发消息」，由团长 AI 统一回复后原路分发。下方<b>仅检查链路</b>只校验登录态 / AI 代聊开关 / 今日配额 / 渠道，<b>不会向团长 H5 会话发送任何消息</b>；要验证 AI 实际回复，请用另一个微信 / iLink 给本机渠道发一条真实消息即可。</p>
        <div class="ar-row">
          <button class="btn small" id="st-check">仅检查链路</button>
        </div>
        <div id="st-result" class="ar-log" style="margin-top:10px"></div>
      </section>

      <section class="card">
        <div class="row-between">
          <h2>AI 回复日志</h2>
          <button class="btn small" id="ov-open-log">打开日志</button>
        </div>
        <p class="card-desc">实时记录自动回复管线的命中/回复/失败事件（浮层查看）。</p>
      </section>

      ${overlayHtml("ov-log", "AI 回复日志", `<div class="ar-log" id="ar-log"></div>`)}
    </div>
    <div id="toast-box-chat" class="toast-box"></div>`;

  wire();
  g("ov-open-rules")?.addEventListener("click", () => openOverlay("ov-rules"));
  g("ov-open-log")?.addEventListener("click", () => openOverlay("ov-log"));
  wireOverlayClose(container);
  bindOverlayEsc();
  void loadAll();
}

async function loadAll() {
  try {
    state.cfg = await autoreplyApi.getConfig();
  } catch (e: any) {
    toast("加载聊天配置失败：" + (e?.message || e), false);
    state.cfg = null;
  }
  fillRules();
  renderListenList();
  void loadLogs();
  void loadGatewayStatus();
  void loadSafetyStatus();
}

// ---------------- 微信发送安全状态 ----------------

async function loadSafetyStatus() {
  const box = g("sf-status");
  if (!box) return;
  try {
    const s = await autoreplyApi.safetyStatus();
    const lines: string[] = [];
    if (s.paused) {
      lines.push(`<div class="ar-line warn"><span class="ar-t">!</span><b>微信通道已急停</b> · 剩余 ${s.paused_remaining_sec}s（连续 ${s.fail_streak_threshold} 次回发失败）· 先确认微信登录态，再点「解除急停」</div>`);
    } else if (!s.within_active_hours) {
      lines.push(`<div class="ar-line warn"><span class="ar-t">!</span><b>当前不在允许发送时段</b> · 本机 ${esc(s.local_time)}，允许 ${esc(s.active_hours)}，此时段微信消息不自动回复</div>`);
    } else if (s.hourly_budget > 0 && s.budget_used >= s.hourly_budget) {
      lines.push(`<div class="ar-line warn"><span class="ar-t">!</span><b>每小时发送预算已用尽</b> · ${s.budget_used}/${s.hourly_budget}，按 1 小时滑动窗口自动恢复</div>`);
    } else {
      lines.push(`<div class="ar-line info"><span class="ar-t">✓</span>微信通道可发送 · 本机 ${esc(s.local_time)}（允许 ${esc(s.active_hours)}）· 近 1 小时已发起 ${s.budget_used}/${s.hourly_budget || "不限"} 次发送 · 连败 ${s.fail_streak}/${s.fail_streak_threshold}</div>`);
    }
    lines.push(`<div class="ar-line info"><span class="ar-t">·</span>仅前台会话发送：<b>${s.require_foreground ? "开启" : "关闭"}</b> · ${s.require_foreground ? "不抢焦点、不切会话；目标会话不在前台时本条不回复" : "每条自动置前并切换会话，行为特征明显"}</div>`);
    if (s.last_reason) {
      lines.push(`<div class="ar-line warn"><span class="ar-t">i</span>最近一次拦截：${esc(s.last_reason)}</div>`);
    }
    box.innerHTML = lines.join("");
  } catch (e: any) {
    box.innerHTML = `<div class="ar-line warn"><span class="ar-t">✕</span>读取失败：${esc(String(e?.message || e))}</div>`;
  }
}

// ---------------- 回复规则 ----------------

function fillRules() {
  const c = state.cfg;
  if (!c) return;
  const val = (id: string, v: any) => {
    const el = g(id) as HTMLInputElement | HTMLSelectElement | null;
    if (el) el.value = String(v ?? "");
  };
  const chk = (id: string, v: boolean) => {
    const el = g(id) as HTMLInputElement | null;
    if (el) el.checked = !!v;
  };
  chk("rl-private", c.private_reply);
  val("rl-gmode", c.group_mode);
  val("rl-atnames", (c.at_names || []).join("，"));
  val("rl-keywords", (c.group_keywords || []).join("，"));
  val("rl-whitelist", (c.group_whitelist || []).join("，"));
  val("rl-blacklist", (c.group_blacklist || []).join("，"));
  val("rl-banned", (c.banned_words || []).join("，"));
  val("rl-rate-win", c.group_rate_window_sec ?? 60);
  val("rl-rate-max", c.group_rate_max_replies ?? 10);
  val("rl-cd", c.cooldown_sec ?? 5);
  chk("rl-voice", c.reply_voice);
  chk("rl-fb-on", c.fallback_on_fault ?? true);
  val("rl-fb-text", c.fallback_text ?? "消息已收到，稍后回复您～");
  val("rl-fb-cd", c.fallback_cooldown_sec ?? 600);
  chk("rl-sf-front", c.require_foreground ?? true);
  val("rl-sf-budget", c.hourly_send_budget ?? 30);
  val("rl-sf-start", c.active_hours_start ?? "09:00");
  val("rl-sf-end", c.active_hours_end ?? "22:00");
  val("rl-sf-streak", c.fail_streak_threshold ?? 2);
  val("rl-sf-pause", c.pause_sec ?? 1800);
}

async function saveRules() {
  const c = state.cfg || (await safeCfg());
  const gm = (g("rl-gmode") as HTMLSelectElement).value as AutoReplyConfig["group_mode"];
  const value: Partial<AutoReplyConfig> = {
    private_reply: !!(g("rl-private") as HTMLInputElement).checked,
    group_mode: gm,
    at_names: splitList((g("rl-atnames") as HTMLInputElement).value),
    group_keywords: splitList((g("rl-keywords") as HTMLInputElement).value),
    group_whitelist: splitList((g("rl-whitelist") as HTMLInputElement).value),
    group_blacklist: splitList((g("rl-blacklist") as HTMLInputElement).value),
    banned_words: splitList((g("rl-banned") as HTMLInputElement).value),
    group_rate_window_sec: Math.max(1, parseInt((g("rl-rate-win") as HTMLInputElement).value || "60", 10) || 60),
    group_rate_max_replies: Math.max(0, parseInt((g("rl-rate-max") as HTMLInputElement).value || "10", 10) || 0),
    cooldown_sec: Math.max(1, parseInt((g("rl-cd") as HTMLInputElement).value || "5", 10) || 5),
    reply_voice: !!(g("rl-voice") as HTMLInputElement).checked,
    fallback_on_fault: !!(g("rl-fb-on") as HTMLInputElement).checked,
    fallback_text: (g("rl-fb-text") as HTMLInputElement).value.trim() || "消息已收到，稍后回复您～",
    fallback_cooldown_sec: Math.max(0, parseInt((g("rl-fb-cd") as HTMLInputElement).value || "600", 10) || 0),
    require_foreground: !!(g("rl-sf-front") as HTMLInputElement).checked,
    hourly_send_budget: Math.max(0, parseInt((g("rl-sf-budget") as HTMLInputElement).value || "30", 10) || 0),
    active_hours_start: (g("rl-sf-start") as HTMLInputElement).value || "",
    active_hours_end: (g("rl-sf-end") as HTMLInputElement).value || "",
    fail_streak_threshold: Math.max(0, parseInt((g("rl-sf-streak") as HTMLInputElement).value || "2", 10) || 0),
    pause_sec: Math.max(0, parseInt((g("rl-sf-pause") as HTMLInputElement).value || "1800", 10) || 0),
  };
  try {
    state.cfg = await autoreplyApi.setConfig(value);
    void loadSafetyStatus();
    toast("回复规则已保存");
  } catch (e: any) {
    toast("保存失败：" + (e?.message || e), false);
  }
}

async function safeCfg(): Promise<AutoReplyConfig> {
  try {
    state.cfg = await autoreplyApi.getConfig();
  } catch {
    state.cfg = null;
  }
  return state.cfg as AutoReplyConfig;
}

// ---------------- 聊天对象（listen_list） ----------------

function listenRowHtml(e: ListenEntry, idx: number): string {
  const types = MON_TYPES.map((t) =>
    `<label class="chk"><input type="checkbox" class="lr-mon-type" value="${t}" ${e.mon_types?.includes(t) ? "checked" : ""}/> ${MON_LABELS[t]}</label>`
  ).join("");
  return `
    <div class="listen-row" data-idx="${idx}">
      <div class="lr-main">
        <input class="lr-name input" placeholder="微信昵称 / 群名" value="${esc(e.name || "")}"/>
        <select class="lr-kind input">
          <option value="private" ${e.kind !== "group" ? "selected" : ""}>私聊</option>
          <option value="group" ${e.kind === "group" ? "selected" : ""}>群聊</option>
        </select>
        <button class="btn small lr-gear" title="监测设置">⚙ 设置</button>
        <button class="btn small lr-del" title="删除">删除</button>
      </div>
      <div class="listen-extra hidden">
        <div class="lr-block"><b>① 监测内容</b>
          <div class="mon-types">${types}</div>
          <input class="lr-mon-kw input" placeholder="监测关键字（逗号分隔，可留空）" value="${esc((e.mon_keywords || []).join("，"))}"/>
          <small>类型与关键字都留空 = 监测全部内容；填了任一 → 只有命中的消息会处理。</small>
        </div>
        <div class="lr-block"><b>② 命中后</b>
          <select class="lr-action input">
            <option value="reply" ${e.action !== "ignore" ? "selected" : ""}>正常 AI 回复</option>
            <option value="ignore" ${e.action === "ignore" ? "selected" : ""}>不回应（整体屏蔽该对象）</option>
          </select>
        </div>
      </div>
    </div>`;
}

function renderListenList() {
  const box = g("listen-list");
  if (!box) return;
  const list = state.cfg?.listen_list || [];
  box.innerHTML = list.length
    ? list.map((e, i) => listenRowHtml(e, i)).join("")
    : `<div class="empty">暂无聊天对象，点右上角「＋添加聊天对象」</div>`;
  wireListenRows();
}

function wireListenRows() {
  const box = g("listen-list");
  if (!box) return;
  box.querySelectorAll(".lr-gear").forEach((b) =>
    b.addEventListener("click", () => {
      const extra = (b.closest(".listen-row") as HTMLElement)?.querySelector(".listen-extra");
      extra?.classList.toggle("hidden");
    })
  );
  box.querySelectorAll(".lr-del").forEach((b) =>
    b.addEventListener("click", () => {
      (b.closest(".listen-row") as HTMLElement)?.remove();
    })
  );
}

function readListenList(): ListenEntry[] {
  const box = g("listen-list");
  if (!box) return [];
  return Array.from(box.querySelectorAll(".listen-row")).map((row) => {
    const el = row as HTMLElement;
    return {
      name: (el.querySelector(".lr-name") as HTMLInputElement).value.trim(),
      kind: (el.querySelector(".lr-kind") as HTMLSelectElement).value,
      mon_types: Array.from(el.querySelectorAll(".lr-mon-type:checked")).map((c) => (c as HTMLInputElement).value),
      mon_keywords: splitList((el.querySelector(".lr-mon-kw") as HTMLInputElement).value),
      action: (el.querySelector(".lr-action") as HTMLSelectElement).value,
    };
  }).filter((e) => e.name);
}

async function saveListenList() {
  const list = readListenList();
  try {
    state.cfg = await autoreplyApi.setConfig({ listen_list: list });
    renderListenList();
    toast(`聊天对象已保存（${list.length} 个）`);
  } catch (e: any) {
    toast("保存失败：" + (e?.message || e), false);
  }
}

// ---------------- AI 消息网关状态 ----------------

async function loadGatewayStatus() {
  const box = g("gw-status");
  if (!box) return;
  try {
    const s = await aiGatewayApi.status();
    const c = s.channels || {};
    // role 只是登录账号的角色，不参与可用性判定：PC 端登录团长账号是正常态。
    const roleTxt = s.role === "leader" ? "团长账号登录" : s.role === "member" ? "团员账号登录" : s.role === "deputy" ? "团副账号登录" : "已登录";
    const leaderName = s.leader?.name || "";
    const left = s.quota?.left;
    const total = c.total ?? (c.wx_private ?? 0) + (c.wx_group ?? 0) + (c.ilink ?? 0);
    const bf = s.backend_fault;
    const faultLine = bf?.active
      ? `<div class="ar-line warn"><span class="ar-t">!</span><b>后端写入故障</b> · ${esc(bf.hint || "服务端写接口暂不可用")} <button class="btn small" id="gw-fault-reset" style="margin-left:8px">立即重试</button></div>`
      : "";
    box.innerHTML = [
      faultLine,
      `<div class="ar-line ${s.ok ? "info" : "warn"}"><span class="ar-t">${s.ok ? "✓" : "✕"}</span><b>${esc(roleTxt)}</b>${leaderName ? ` · 团长「${esc(leaderName)}」` : ""}${s.ai_enabled === false ? " · AI 代聊未开启" : ""}${left !== undefined ? (left === 0 ? " · 今日额度 0（将自动换备用团员；彻底不限额请调高后台条数）" : ` · 今日剩余 ${left} 条`) : ""}</div>`,
      `<div class="ar-line info"><span class="ar-t">·</span>渠道 = 团员：共 ${total} 个（每个微信私聊 / 群聊 / iLink 各是 1 个团员）｜私聊 ${c.wx_private ?? 0} · 群聊 ${c.wx_group ?? 0} · iLink ${c.ilink ?? 0}（已绑定映射 ${c.mapped ?? 0}）｜聊天接口 ${esc(s.chat_action || "chat/send")}</div>`,
      `<div class="ar-line ${s.ok ? "info" : "warn"}"><span class="ar-t">i</span>${esc(s.hint || "")}</div>`,
    ].join("");
  } catch (e: any) {
    box.innerHTML = `<div class="ar-line warn"><span class="ar-t">✕</span>读取失败：${esc(String(e?.message || e))}</div>`;
  }
}

// ---------------- AI 消息自测 ----------------

function renderTestResult(r: AutoReplyTestResult) {
  const box = g("st-result");
  if (!box) return;
  box.innerHTML =
    r.steps
      .map(
        (s) =>
          `<div class="ar-line ${s.ok ? "info" : "warn"}"><span class="ar-t">${s.ok ? "✓" : "✕"}</span><b>${esc(s.name)}</b> · ${esc(s.detail)}</div>`
      )
      .join("") +
    (r.reply ? `<div class="ar-line info"><span class="ar-t">AI</span>${esc(r.reply)}</div>` : "");
}

async function runSelfTest() {
  const box = g("st-result");
  if (box) box.innerHTML = `<div class="ar-line info"><span class="ar-t">…</span>正在检查链路（不向团长发送消息）…</div>`;
  try {
    const r = await autoreplyApi.selfTest("", false);
    renderTestResult(r);
    if (r.ok) toast("链路检查通过");
  } catch (e: any) {
    if (box) box.innerHTML = `<div class="ar-line warn"><span class="ar-t">✕</span>自测失败：${esc(String(e?.message || e))}</div>`;
  }
}

// ---------------- 日志 ----------------

async function loadLogs() {
  try {
    const logs = (await autoreplyApi.logs(40)) || [];
    const box = g("ar-log");
    if (box) {
      box.innerHTML = logs
        .slice()
        .reverse()
        .map((l: AutoReplyLog) => logLine(l))
        .join("");
    }
  } catch { /* 忽略 */ }
}

function logLine(l: AutoReplyLog): string {
  return `<div class="ar-line ${l.level}"><span class="ar-t">${fmtTime(l.ts)}</span>${esc(l.msg)}</div>`;
}

// ---------------- 事件 ----------------

export async function initChatEvents() {
  await onAutoreplyEvent((l: AutoReplyLog) => {
    const box = document.getElementById("ar-log");
    if (!box) return;
    box.insertAdjacentHTML("afterbegin", logLine(l));
    while (box.children.length > 60) box.lastChild?.remove();
  });
  await onWxautoEvent((l: AutoReplyLog) => {
    const box = document.getElementById("ar-log");
    if (!box) return;
    const line = { ...l, msg: "[微信] " + l.msg };
    box.insertAdjacentHTML("afterbegin", logLine(line));
    while (box.children.length > 60) box.lastChild?.remove();
  });
}

// ---------------- 接线 ----------------

function wire() {
  g("listen-add")?.addEventListener("click", () => {
    const box = g("listen-list");
    if (!box) return;
    box.insertAdjacentHTML(
      "beforeend",
      listenRowHtml({ name: "", kind: "private", mon_types: [], mon_keywords: [], action: "reply" }, box.children.length)
    );
    wireListenRows();
    box.querySelector(".listen-extra.hidden")?.classList.remove("hidden");
  });
  g("listen-save")?.addEventListener("click", saveListenList);
  g("rl-save")?.addEventListener("click", saveRules);
  g("gw-refresh")?.addEventListener("click", () => void loadGatewayStatus());
  g("sf-refresh")?.addEventListener("click", () => void loadSafetyStatus());
  g("sf-resume")?.addEventListener("click", async () => {
    try {
      await autoreplyApi.safetyResume();
      toast("已解除微信通道急停");
      await loadSafetyStatus();
    } catch (e: any) {
      toast("解除失败：" + (e?.message || e), false);
    }
  });
  g("st-check")?.addEventListener("click", () => void runSelfTest());
  // 「立即重试」按钮是状态区动态渲染的，用事件委托绑定一次
  g("gw-status")?.addEventListener("click", async (ev) => {
    if (!(ev.target as HTMLElement)?.closest("#gw-fault-reset")) return;
    try {
      await aiGatewayApi.faultReset();
      toast("已解除熔断，下一次写请求将立即发出");
      await loadGatewayStatus();
    } catch (e: any) {
      toast("解除失败：" + (e?.message || e), false);
    }
  });
}
