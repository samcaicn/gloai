// 互动消息页：4 渠道下拉框（微信私聊/群聊/iLink/团员）→ 事件驱动的 AI 互动控制台
// 入站消息（ilink-event / wxauto-event）驱动统一管线调 bbt-zb 接口生成 AI 回复（autoreply-event），
// 本页实时展示所选目标的事件流，并可手动向所选目标发文本。
// 0.3.0：新增「AI 消息网关」——多渠道消息都只向同一个团长发，由团长 AI 统一回复。
import { toast } from "./ui";
import { api } from "./api";
import {
  autoreplyApi,
  aiGatewayApi,
  AiGatewayStatus,
  AutoReplyConfig,
  AutoReplyLog,
  onAutoreplyEvent,
  onAiGatewayEvent,
  onIlinkEvent,
  onWxautoEvent,
  IlinkEvent,
} from "./ilink";
import { CHANNELS, ChannelKey, channelsApi, ChannelTargets } from "./channels";

let g: (id: string) => HTMLElement | null = (id) => document.getElementById(id);
let targets: ChannelTargets | null = null;
let active: ChannelKey = "wx_private";
let unlistenFns: Array<() => void> = [];
/** 本机缓存的渠道选择（config.json → channel_settings），升级/重启后自动恢复 */
let savedSettings: {
  active?: ChannelKey;
  selected?: Record<string, string>;
  auto_send?: boolean;
} | null = null;
let cfgSaveTimer: number | null = null;

const CHANNEL_SETTINGS_HINT =
  "设置自动保存到本机（config.json + 备份），升级 / 重启后继续有效；iLink 登录态缓存 48 小时内无需重复扫码。";

export function renderInteractPage(root: HTMLElement) {
  g = (id) => root.querySelector(`#${id}`);
  root.innerHTML = `
    <div class="panel">
      <h1>互动消息</h1>
      <p class="hint">事件驱动：所选目标的新消息自动发往团长，由团长 AI 生成回复后原路回发；本页实时展示事件流。</p>
    </div>

    <div class="panel">
      <div class="row-between"><h2>① 选择互动目标</h2><button class="btn small" id="ia-refresh">刷新目标</button></div>
      <div class="ord-chans">
        ${CHANNELS.map(
          (c) => `
          <div class="ord-chan" data-ch="${c.key}">
            <div class="ord-chan-label">${c.label}</div>
            <select class="input ord-select" id="ia-sel-${c.key}"><option value="">加载中…</option></select>
          </div>`
        ).join("")}
      </div>
      <div class="hint" id="ia-note"></div>
    </div>

    <div class="panel">
      <div class="row-between">
        <h2>② 渠道配置 · 交互优化</h2>
        <button class="btn small primary" id="ia-cfg-save">保存设置</button>
      </div>
      <p class="hint">${CHANNEL_SETTINGS_HINT}</p>
      <div class="ar-row" style="flex-wrap:wrap;gap:10px 18px">
        <label class="hint" style="display:inline-flex;align-items:center;gap:6px"><input type="checkbox" id="ia-cfg-enabled"/> AI 互动（自动回复）</label>
        <label class="hint" style="display:inline-flex;align-items:center;gap:6px">每会话冷却 <input class="input" id="ia-cfg-cooldown" type="number" min="1" style="width:68px;padding:4px 6px"/> 秒</label>
        <label class="hint" style="display:inline-flex;align-items:center;gap:6px">群内频控 <input class="input" id="ia-cfg-rate-win" type="number" min="1" style="width:68px;padding:4px 6px"/> 秒最多 <input class="input" id="ia-cfg-rate-max" type="number" min="0" style="width:60px;padding:4px 6px"/> 条</label>
      </div>
      <div class="ar-row mt8" style="flex-wrap:wrap;gap:10px 18px">
        <label class="hint" style="display:inline-flex;align-items:center;gap:6px">私聊思考延迟 <input class="input" id="ia-cfg-tmin" type="number" min="0" step="0.5" style="width:64px;padding:4px 6px"/> ~ <input class="input" id="ia-cfg-tmax" type="number" min="0" step="0.5" style="width:64px;padding:4px 6px"/> 秒</label>
        <label class="hint" style="display:inline-flex;align-items:center;gap:6px">群聊思考延迟 <input class="input" id="ia-cfg-gtmin" type="number" min="0" step="0.5" style="width:64px;padding:4px 6px"/> ~ <input class="input" id="ia-cfg-gtmax" type="number" min="0" step="0.5" style="width:64px;padding:4px 6px"/> 秒</label>
      </div>
      <div class="hint mt8" id="ia-cfg-state">读取中…</div>
      <div class="ar-log mt8" id="ia-cache-status">读取本地缓存…</div>
    </div>

    <div class="panel">
      <div class="row-between"><h2>③ AI 消息网关</h2><button class="btn small" id="ia-gw-refresh">刷新状态</button></div>
      <p class="hint">多个群聊 / 私聊 / iLink 渠道就是多个团员，消息都只向同一个团长发，由团长 AI 统一回复。下方按「① 选择互动目标」所选渠道真发一条测试问题（消耗 1 条当日配额）。</p>
      <div class="ar-log" id="ia-gw-status">正在读取登录身份…</div>
      <div class="ord-row mt8">
        <input class="input" id="ia-gw-text" type="text" placeholder="测试问题，如：你好，在吗？" />
        <button class="btn small primary" id="ia-gw-send">向团长发消息</button>
        <label class="hint" style="display:inline-flex;align-items:center;gap:4px"><input type="checkbox" id="ia-gw-autosend" /> 同时回发到所选目标</label>
      </div>
      <div class="ar-log mt8" id="ia-gw-reply"></div>
    </div>

    <div class="panel">
      <h2>④ 手动发一条</h2>
      <div class="ord-row">
        <input class="input" id="ia-text" type="text" placeholder="输入要发给所选目标的内容…" />
        <button class="btn small primary" id="ia-send">发送</button>
      </div>
    </div>

    <div class="panel">
      <div class="row-between"><h2>⑤ 事件流（实时）</h2><button class="btn small" id="ia-clear">清空</button></div>
      <div class="ar-log" id="ia-log"></div>
    </div>`;

  wire();
  void refreshTargets();
  void loadChannelSettings();
  void refreshGatewayStatus();
  void subscribeEvents();
}

function escapeHtml(s: string): string {
  return (s || "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c] as string));
}

function fmtTime(ts?: number): string {
  const t = ts ? new Date(ts) : new Date();
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(t.getHours())}:${p(t.getMinutes())}:${p(t.getSeconds())}`;
}

function logLine(msg: string, cls = "") {
  const box = g("ia-log");
  if (!box) return;
  const div = document.createElement("div");
  div.className = "ar-line " + cls;
  div.innerHTML = `<span class="ar-t">${fmtTime()}</span>${escapeHtml(msg)}`;
  box.prepend(div);
  while (box.children.length > 60) box.lastChild?.remove();
}

/** 判断事件是否属于当前选中目标（定向过滤；空选择=全收） */
function matchActive(peer: string, botId: string, channel: string): boolean {
  const target = (g(`ia-sel-${active}`) as HTMLSelectElement)?.value || "";
  if (!target) return true; // 未选具体目标 → 全收
  if (active === channel) return peer === target || botId === target;
  if (active === "member" && (channel === "ilink" || channel === "member")) {
    // member 选择：iLink 消息按绑定映射反查团员 id
    const map = targets?.member_map || {};
    const memberId = Object.entries(map).find(([, b]) => b === botId)?.[0];
    return memberId ? memberId === target : false;
  }
  return false;
}

async function refreshTargets() {
  const note = g("ia-note");
  try {
    targets = await channelsApi.targets();
  } catch (e: any) {
    if (note) note.textContent = "拉取目标失败：" + (e?.message || e);
    return;
  }
  if (!targets) return;
  const fill = (key: ChannelKey, rows: any[], emptyText: string) => {
    const sel = g(`ia-sel-${key}`) as HTMLSelectElement | null;
    if (!sel) return;
    if (!rows.length) {
      sel.innerHTML = `<option value="">${emptyText}</option>`;
      return;
    }
    sel.innerHTML =
      `<option value="">— 全部（${rows.length}）—</option>` +
      rows
        .map((r) => {
          const id = String(r.id ?? r.bot_id ?? r.user_id ?? "");
          const label = r.name || r.label || r.realName || r.nickname || id;
          const extra = r.telephoneMask ? `（${r.telephoneMask}）` : "";
          return `<option value="${escapeHtml(id)}">${escapeHtml(label + extra)}</option>`;
        })
        .join("");
  };
  fill("wx_private", targets.wx_private, targets.wx_note || "微信私聊：无");
  fill("wx_group", targets.wx_group, targets.wx_note || "微信群聊：无");
  fill(
    "ilink",
    (targets.ilink || []).map((a: any) => ({
      ...a,
      id: a.id || a.bot_id || a.user_id,
      name: `${a.name || a.label || a.user_id || a.bot_id}${a.expired ? " · 需重新扫码（48h 无互动）" : a.online ? " · 在线（48h 内免扫码）" : " · 本机缓存有效"}`,
    })),
    targets.ilink_note || "iLink：无已绑定团员（先在「团员二维码」页扫码）"
  );
  const map = targets.member_map || {};
  fill(
    "member",
    (targets.member || []).map((m: any) => ({
      ...m,
      id: String(m.id),
      name: `${m.name || m.realName || m.id}${map[String(m.id)] ? " ✓已绑定" : ""}`,
    })),
    "团员：未登录或名册为空"
  );
  if (note) {
    const parts: string[] = [];
    if (targets.wx_note) parts.push("微信：" + targets.wx_note);
    if (!targets.ilink?.length) {
      parts.push("iLink：无已绑定账号" + (targets.ilink_note ? `（${targets.ilink_note}）` : ""));
    }
    if (!targets.member?.length) {
      parts.push("团员：" + (targets.member_note || "未登录或名册为空"));
    }
    note.textContent = parts.join("；") || "目标就绪；下拉选「全部」可看全渠道事件。";
  }
  // 目标列表重建后，恢复本机缓存的上次选择（升级/重启不丢）
  applySavedSelections();
}

async function refreshGatewayStatus() {
  const box = g("ia-gw-status");
  if (!box) return;
  try {
    const s: AiGatewayStatus = await aiGatewayApi.status();
    const c = s.channels || {};
    const role = s.role === "member" ? "团员（已绑定团长）" : s.role === "leader" ? "团长" : s.role || "未知";
    const leader = s.leader?.name || "—";
    box.innerHTML = [
      `<div class="ar-line ${s.ok ? "info" : "warn"}"><span class="ar-t">${s.ok ? "✓" : "✕"}</span><b>${escapeHtml(role)}</b> · 团长「${escapeHtml(leader)}」${s.quota?.left !== undefined ? ` · 今日剩余 ${s.quota.left} 条` : ""}</div>`,
      `<div class="ar-line info"><span class="ar-t">·</span>对应关系：私聊 ${c.wx_private ?? 0} · 群聊 ${c.wx_group ?? 0} · iLink ${c.ilink ?? 0} · 团员 ${c.member ?? 0}（已绑定 ${c.mapped ?? 0}）</div>`,
      `<div class="ar-line ${s.ok ? "info" : "warn"}"><span class="ar-t">i</span>${escapeHtml(s.hint || "")}</div>`,
    ].join("");
  } catch (e: any) {
    box.innerHTML = `<div class="ar-line warn"><span class="ar-t">✕</span>状态读取失败：${escapeHtml(String(e?.message || e))}</div>`;
  }
}

async function runGatewaySend() {
  const input = g("ia-gw-text") as HTMLInputElement | null;
  const text = (input?.value || "").trim() || "你好，在吗？";
  const box = g("ia-gw-reply");
  const target = (g(`ia-sel-${active}`) as HTMLSelectElement)?.value || "";
  const autoSend = !!(g("ia-gw-autosend") as HTMLInputElement | null)?.checked;
  if (autoSend && !target) {
    toast("勾选了回发，请先在①里选择具体目标", false);
    return;
  }
  if (box) box.innerHTML = `<div class="ar-line info"><span class="ar-t">…</span>正在向团长发消息（${escapeHtml(active)}${target ? " → " + escapeHtml(target) : ""}），等待 AI 回复…</div>`;
  try {
    const r = await aiGatewayApi.send(text, active, target, autoSend);
    if (box) {
      box.innerHTML =
        `<div class="ar-line info"><span class="ar-t">✓</span>团长 AI 回复（${r.reply.length} 字${r.quota_left !== undefined && r.quota_left !== null ? `，剩余 ${r.quota_left} 条` : ""}${r.sent ? "，已原路回发" : ""}）：</div>` +
        `<div class="ar-line info"><span class="ar-t">AI</span>${escapeHtml(r.reply)}</div>` +
        (r.send_error ? `<div class="ar-line warn"><span class="ar-t">✕</span>回发失败：${escapeHtml(r.send_error)}</div>` : "");
    }
    void refreshGatewayStatus();
  } catch (e: any) {
    if (box) box.innerHTML = `<div class="ar-line warn"><span class="ar-t">✕</span>失败：${escapeHtml(String(e?.message || e))}</div>`;
  }
}

// ---------------- 渠道配置 / 交互优化（本机缓存） ----------------

function fmtDateTime(ms?: number): string {
  if (!ms) return "—";
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

function numOf(id: string, def: number): number {
  const v = parseFloat((g(id) as HTMLInputElement | null)?.value ?? "");
  return Number.isFinite(v) ? v : def;
}
function setVal(id: string, v: any) {
  const el = g(id) as HTMLInputElement | null;
  if (el) el.value = String(v ?? "");
}
function setChk(id: string, v: any) {
  const el = g(id) as HTMLInputElement | null;
  if (el) el.checked = !!v;
}
function setCfgState(text: string, bad = false) {
  const el = g("ia-cfg-state");
  if (el) {
    el.textContent = text;
    el.style.color = bad ? "var(--danger, #d64545)" : "";
  }
}

/** 读取本机缓存的渠道选择 + 交互优化参数，恢复界面；随后展示缓存体检结果 */
async function loadChannelSettings() {
  try {
    const cfg: any = await api.loadConfig();
    savedSettings = cfg?.channel_settings || {};
    applySavedSelections();
  } catch (e: any) {
    setCfgState("渠道配置读取失败：" + (e?.message || e), true);
  }
  try {
    const c: AutoReplyConfig = await autoreplyApi.getConfig();
    setChk("ia-cfg-enabled", c.enabled);
    setVal("ia-cfg-cooldown", c.cooldown_sec ?? 15);
    setVal("ia-cfg-rate-win", c.group_rate_window_sec ?? 60);
    setVal("ia-cfg-rate-max", c.group_rate_max_replies ?? 10);
    setVal("ia-cfg-tmin", c.think_min_sec ?? 1.5);
    setVal("ia-cfg-tmax", c.think_max_sec ?? 4);
    setVal("ia-cfg-gtmin", c.group_think_min_sec ?? 2.5);
    setVal("ia-cfg-gtmax", c.group_think_max_sec ?? 6);
    setCfgState("已载入本机设置 · 修改后自动保存（升级/重启仍有效）");
  } catch (e: any) {
    setCfgState("交互参数读取失败：" + (e?.message || e), true);
  }
  void refreshCacheStatus();
}

/** 把缓存的渠道选择套回下拉框（幂等，目标列表刷新后也会调用一次） */
function applySavedSelections() {
  if (!savedSettings) return;
  CHANNELS.forEach((c) => {
    const v = savedSettings?.selected?.[c.key] || "";
    const sel = g(`ia-sel-${c.key}`) as HTMLSelectElement | null;
    if (sel && v && Array.from(sel.options).some((o) => o.value === v)) sel.value = v;
  });
  const a = savedSettings.active;
  if (a && CHANNELS.some((c) => c.key === a)) {
    active = a;
    document.querySelectorAll(".ord-chan").forEach((el) => {
      el.classList.toggle("active", (el as HTMLElement).dataset.ch === a);
    });
  }
  if (savedSettings.auto_send !== undefined) setChk("ia-gw-autosend", savedSettings.auto_send);
}

/** 渠道选择变化即落盘（局部合并，不覆盖其它配置节点） */
async function persistChannelSelection() {
  const selected: Record<string, string> = {};
  CHANNELS.forEach((c) => {
    const sel = g(`ia-sel-${c.key}`) as HTMLSelectElement | null;
    if (sel) selected[c.key] = sel.value;
  });
  const patch = {
    active,
    selected,
    auto_send: !!(g("ia-gw-autosend") as HTMLInputElement | null)?.checked,
    selection_saved_at: Date.now(),
  };
  savedSettings = { ...(savedSettings || {}), ...patch };
  try {
    await api.patchConfig({ channel_settings: patch });
  } catch (e: any) {
    setCfgState("渠道选择保存失败：" + (e?.message || e), true);
  }
}

/** 保存交互优化参数：autoreply 配置走专用命令，同时记录保存时间到本机配置 */
async function saveInteractionSettings(showToast = false) {
  const tMin = Math.max(0, numOf("ia-cfg-tmin", 1.5));
  const tMax = Math.max(tMin, numOf("ia-cfg-tmax", 4));
  const gMin = Math.max(0, numOf("ia-cfg-gtmin", 2.5));
  const gMax = Math.max(gMin, numOf("ia-cfg-gtmax", 6));
  const value: Partial<AutoReplyConfig> = {
    enabled: !!(g("ia-cfg-enabled") as HTMLInputElement | null)?.checked,
    cooldown_sec: Math.max(1, Math.round(numOf("ia-cfg-cooldown", 15))),
    group_rate_window_sec: Math.max(1, Math.round(numOf("ia-cfg-rate-win", 60))),
    group_rate_max_replies: Math.max(0, Math.round(numOf("ia-cfg-rate-max", 10))),
    think_min_sec: tMin,
    think_max_sec: tMax,
    group_think_min_sec: gMin,
    group_think_max_sec: gMax,
  };
  try {
    await autoreplyApi.setConfig(value);
    await api.patchConfig({ channel_settings: { interaction_saved_at: Date.now() } });
    setCfgState(`✅ 已保存到本机 · ${fmtDateTime(Date.now())}（升级 / 重启后仍有效）`);
    if (showToast) toast("已保存到本机，升级后仍有效");
    void refreshCacheStatus();
  } catch (e: any) {
    const msg = "保存失败：" + (e?.message || e);
    setCfgState(msg, true);
    if (showToast) toast(msg, false);
  }
}

function scheduleCfgSave() {
  if (cfgSaveTimer !== null) clearTimeout(cfgSaveTimer);
  cfgSaveTimer = window.setTimeout(() => {
    cfgSaveTimer = null;
    void saveInteractionSettings(false);
  }, 500);
}

/** 本地缓存体检：配置备份、绑定关系、iLink 48h 登录态 */
async function refreshCacheStatus() {
  const box = g("ia-cache-status");
  if (!box) return;
  try {
    const c = await api.localCacheStatus();
    const last = c.ilink_last_active_ms
      ? `最近活跃 ${fmtDateTime(c.ilink_last_active_ms)}`
      : "暂无互动记录";
    box.innerHTML = [
      `<div class="ar-line info"><span class="ar-t">${c.config_exists ? "✓" : "✕"}</span>配置已缓存到本机${c.backup_exists ? " · 备份已就绪" : ""} · 最近保存 ${fmtDateTime(c.saved_at)}</div>`,
      `<div class="ar-line info"><span class="ar-t">·</span>渠道：iLink 缓存 ${c.ilink_active}/${c.ilink_total} 个有效 · 团员绑定 ${c.memberqr_bindings} 条${c.autoreply_saved ? " · 回复规则已缓存" : ""}</div>`,
      `<div class="ar-line ${c.ilink_expired ? "warn" : "info"}"><span class="ar-t">·</span>iLink 登录态 ${c.ilink_ttl_hours}h 内免扫码（${last}）${c.ilink_expired ? ` · ${c.ilink_expired} 个会话已失效，需重新扫码` : ""}</div>`,
    ].join("");
  } catch (e: any) {
    box.innerHTML = `<div class="ar-line warn"><span class="ar-t">✕</span>缓存状态读取失败：${escapeHtml(String(e?.message || e))}</div>`;
  }
}

async function subscribeEvents() {
  unlistenFns.forEach((f) => f());
  unlistenFns = [];

  // iLink 入站/出站消息事件
  const un1 = await onIlinkEvent((ev: IlinkEvent) => {
    if (ev.kind === "message" && ev.event === "in") {
      const rec: any = (ev as any).record || {};
      if (matchActive(rec.peer || "", rec.bot_id || "", "ilink")) {
        logLine(`[iLink] ${rec.peer || ""} 收到 ${rec.kind === "text" ? "文本" : rec.kind}：${(rec.text || "").slice(0, 60)}`);
      }
    }
  });
  unlistenFns.push(un1);

  // wxauto 通道日志（收发）
  const un2 = await onWxautoEvent((l: AutoReplyLog) => {
    const peer = l?.extra?.peer || "";
    if (matchActive(peer, "", "wx")) {
      logLine(`[微信] ${l.msg}`, l.level === "warn" ? "warn" : "");
    }
  });
  unlistenFns.push(un2);

  // AI 回复管线日志（chat/send → autoReply → 回发）
  const un3 = await onAutoreplyEvent((l: AutoReplyLog) => {
    const peer = l?.extra?.to || "";
    const chanHit = matchActive(peer, l?.extra?.bot_id || "", "wx") || matchActive(peer, l?.extra?.bot_id || "", "ilink");
    if (chanHit || !peer) {
      logLine(`[AI] ${l.msg}`, l.level === "warn" ? "warn" : l.level === "debug" ? "debug" : "");
    }
  });
  unlistenFns.push(un3);

  // 统一 AI 消息网关日志（多渠道 → 团长 → AI 回复）
  const un4 = await onAiGatewayEvent((l: AutoReplyLog) => {
    logLine(`[网关] ${l.msg}`, l.level === "warn" ? "warn" : "");
  });
  unlistenFns.push(un4);
}

async function sendText() {
  const input = g("ia-text") as HTMLInputElement | null;
  const text = (input?.value || "").trim();
  if (!text) {
    toast("请输入内容", false);
    return;
  }
  const target = (g(`ia-sel-${active}`) as HTMLSelectElement)?.value || "";
  if (!target) {
    toast(`请先在「${CHANNELS.find((c) => c.key === active)?.label}」下拉框选择目标`, false);
    return;
  }
  try {
    await channelsApi.sendText(active, target, text);
    logLine(`已发送 → [${CHANNELS.find((c) => c.key === active)?.label}] ${target}：${text}`);
    if (input) input.value = "";
  } catch (e: any) {
    logLine(`发送失败：${e?.message || e}`, "warn");
    toast("发送失败：" + (e?.message || e), false);
  }
}

function wire() {
  CHANNELS.forEach((c) => {
    g(`ia-sel-${c.key}`)?.addEventListener("change", () => {
      active = c.key;
      document.querySelectorAll(".ord-chan").forEach((el) => {
        el.classList.toggle("active", (el as HTMLElement).dataset.ch === c.key);
      });
      void persistChannelSelection();
    });
  });
  g("ia-refresh")?.addEventListener("click", () => {
    void refreshTargets();
    void refreshCacheStatus();
  });
  g("ia-gw-refresh")?.addEventListener("click", () => void refreshGatewayStatus());
  g("ia-gw-send")?.addEventListener("click", () => void runGatewaySend());
  g("ia-gw-autosend")?.addEventListener("change", () => void persistChannelSelection());
  g("ia-cfg-save")?.addEventListener("click", () => void saveInteractionSettings(true));
  ["ia-cfg-enabled", "ia-cfg-cooldown", "ia-cfg-rate-win", "ia-cfg-rate-max",
    "ia-cfg-tmin", "ia-cfg-tmax", "ia-cfg-gtmin", "ia-cfg-gtmax"].forEach((id) => {
    g(id)?.addEventListener("change", scheduleCfgSave);
    g(id)?.addEventListener("input", scheduleCfgSave);
  });
  g("ia-send")?.addEventListener("click", () => void sendText());
  g("ia-clear")?.addEventListener("click", () => {
    const box = g("ia-log");
    if (box) box.innerHTML = "";
  });
}
