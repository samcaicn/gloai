// 聊天配置页（原「团长聊天」改造）：照搬 weauto 的聊天对象菜单界面 ——
//   * 聊天对象（私聊和群聊）：逐对象列表（窗口名 + 类型 + ⚙监测设置 + 删除）
//   * 回复规则（私聊+群聊）：私聊开关 / 群聊模式 / @名单 / 关键词 / 白名单 / 黑名单 /
//     禁词 / 群内频控 / 冷却 —— 后端 autoreply 管线按同规则真实过滤
//   * AI 回复日志
// 数据面走 ilink.ts -> Rust autoreply 命令。
import {
  autoreplyApi,
  onAutoreplyEvent,
  onWxautoEvent,
  AutoReplyConfig,
  ListenEntry,
  AutoReplyLog,
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
            <div class="field-label">团员账号（触发 AI 的身份）<span class="hint">关键：留空则自动回复 fallback 到团长连接，而团长没有 AI 端点 → 回复必失败；填团员手机号+密码，AI 才生效</span></div>
            <div class="ar-row">
              <input id="rl-member-tel" class="input" placeholder="团员手机号"/>
              <input id="rl-member-pwd" class="input" type="password" placeholder="团员登录密码（改时再填，留空保留原值）"/>
            </div>
          </div>
        </div>
        <div class="btn-row">
          <button class="btn small primary" id="rl-save">保存回复规则</button>
        </div>
        `)}

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
  val("rl-member-tel", c.member_telephone || "");
  // 密码不回显（type=password 且属敏感信息），仅作占位；保存时留空则保留原值
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
    member_telephone: (g("rl-member-tel") as HTMLInputElement).value.trim(),
  };
  const member_pwd = (g("rl-member-pwd") as HTMLInputElement).value;
  if (member_pwd) {
    value.member_password = member_pwd;
  }
  try {
    state.cfg = await autoreplyApi.setConfig(value);
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
}
