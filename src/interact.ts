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
import { CHANNELS, ChannelKey, channelsApi, ChannelTargets, memberCacheLabel, onRosterUpdated } from "./channels";

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
      <p class="hint">多个群聊 / 私聊 / iLink 渠道就是多个团员，消息都只向同一个团长发，由团长 AI 统一回复后原路分发。<b>本面板不向团长发送任何自测消息</b>；验证 AI 实际回复请用另一个微信 / iLink 给本机渠道发一条真实消息。</p>
      <div class="ar-log" id="ia-gw-status">正在读取网关状态…</div>
      <div class="ord-row mt8">
        <button class="btn small" id="ia-gw-ocr">图片 OCR 自测</button>
        <button class="btn small" id="ia-sf-resume">解除微信急停</button>
      </div>
      <div class="ar-log mt8" id="ia-sf-status">正在读取微信发送安全状态…</div>
      <div class="ar-log mt8" id="ia-gw-ocr-result">从微信本地库取最近一张图片，离线识别文字（不调用团长 AI、不向团长发送消息）。</div>
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

async function refreshTargets(force = false) {
  const note = g("ia-note");
  try {
    targets = await channelsApi.targets(force);
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
    if (targets.member_note) parts.push(targets.member_note);
    const cacheTxt = targets.member_cache?.count
      ? `团员名册：${memberCacheLabel(targets.member_cache)}`
      : "";
    const base = parts.join("；") || "目标就绪；下拉选「全部」可看全渠道事件。";
    note.textContent = cacheTxt ? `${base} · ${cacheTxt}` : base;
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
    // role 仅描述登录账号，不参与可用性判定：PC 端登录团长账号是正常态。
    const role = s.role === "leader" ? "团长账号登录" : s.role === "member" ? "团员账号登录" : s.role === "deputy" ? "团副账号登录" : "已登录";
    const leader = s.leader?.name || "";
    const total = c.total ?? (c.wx_private ?? 0) + (c.wx_group ?? 0) + (c.ilink ?? 0);
    const bf = s.backend_fault;
    const faultLine = bf?.active
      ? `<div class="ar-line warn"><span class="ar-t">!</span><b>后端写入故障</b> · ${escapeHtml(bf.hint || "服务端写接口暂不可用")} <button class="btn small" id="ia-gw-fault-reset" style="margin-left:8px">立即重试</button></div>`
      : "";
    box.innerHTML = [
      faultLine,
      `<div class="ar-line ${s.ok ? "info" : "warn"}"><span class="ar-t">${s.ok ? "✓" : "✕"}</span><b>${escapeHtml(role)}</b>${leader ? ` · 团长「${escapeHtml(leader)}」` : ""}${s.quota?.left !== undefined ? (s.quota.left === 0 ? " · 今日额度 0（将自动换备用团员；彻底不限额请调高后台条数）" : ` · 今日剩余 ${s.quota.left} 条`) : ""}</div>`,
      `<div class="ar-line info"><span class="ar-t">·</span>渠道 = 团员：共 ${total} 个（每个私聊 / 群聊 / iLink 各是 1 个团员）｜私聊 ${c.wx_private ?? 0} · 群聊 ${c.wx_group ?? 0} · iLink ${c.ilink ?? 0}（已绑定映射 ${c.mapped ?? 0}）｜聊天接口 ${escapeHtml(s.chat_action || "chat/send")}</div>`,
      `<div class="ar-line ${s.ok ? "info" : "warn"}"><span class="ar-t">i</span>${escapeHtml(s.hint || "")}</div>`,
    ].join("");
  } catch (e: any) {
    box.innerHTML = `<div class="ar-line warn"><span class="ar-t">✕</span>状态读取失败：${escapeHtml(String(e?.message || e))}</div>`;
  }
  void refreshSafetyStatus();
}

/** 微信发送安全：急停 / 预算 / 时段 / 仅前台会话，与网关状态一起刷新。 */
async function refreshSafetyStatus() {
  const box = g("ia-sf-status");
  if (!box) return;
  try {
    const s = await autoreplyApi.safetyStatus();
    const head = s.paused
      ? `<div class="ar-line warn"><span class="ar-t">!</span><b>微信通道已急停</b> · 剩余 ${s.paused_remaining_sec}s（连续回发失败）· 确认微信登录态后点「解除微信急停」</div>`
      : !s.within_active_hours
        ? `<div class="ar-line warn"><span class="ar-t">!</span><b>当前不在允许发送时段</b> · 本机 ${escapeHtml(s.local_time)} / 允许 ${escapeHtml(s.active_hours)}</div>`
        : `<div class="ar-line info"><span class="ar-t">✓</span>发送安全 · 近 1 小时发起 ${s.budget_used}/${s.hourly_budget || "不限"} 次发送 · 连败 ${s.fail_streak}/${s.fail_streak_threshold} · 本机 ${escapeHtml(s.local_time)}</div>`;
    box.innerHTML = head +
      `<div class="ar-line info"><span class="ar-t">·</span>仅前台会话发送：${s.require_foreground ? "开启（不抢焦点/不切会话，目标会话不在前台时本条不回复）" : "关闭（每条自动置前+切会话，行为特征明显）"}</div>` +
      (s.last_reason ? `<div class="ar-line warn"><span class="ar-t">i</span>最近拦截：${escapeHtml(s.last_reason)}</div>` : "");
  } catch (e: any) {
    box.innerHTML = `<div class="ar-line warn"><span class="ar-t">✕</span>发送安全状态读取失败：${escapeHtml(String(e?.message || e))}</div>`;
  }
}


/** 图片 OCR 自测：脱离 Listener 水位线，直接取本地库最近一张图片做离线识别（不调用团长 AI、不写团长会话）。 */
async function runOcrTest() {
  const box = g("ia-gw-ocr-result");
  if (!box) return;
  box.innerHTML = `<div class="ar-line info"><span class="ar-t">…</span>正在取最近一张图片并离线识别…</div>`;
  try {
    const r = await aiGatewayApi.ocrTest("", false);
    const text = String(r?.text || "");
    box.innerHTML = r?.ok
      ? `<div class="ar-line info"><span class="ar-t">✓</span>OCR 成功 · 会话 ${escapeHtml(String(r?.peer || ""))} · local_id=${escapeHtml(String(r?.local_id ?? ""))} · 识别 ${text.length} 字</div>` +
        `<div class="ar-line info"><span class="ar-t">TXT</span>${escapeHtml(text || "（图中未识别到文字）")}</div>`
      : `<div class="ar-line warn"><span class="ar-t">✕</span>OCR 失败：${escapeHtml(String(r?.msg || "未知原因"))}</div>`;
  } catch (e: any) {
    box.innerHTML = `<div class="ar-line warn"><span class="ar-t">✕</span>OCR 失败：${escapeHtml(String(e?.message || e))}</div>`;
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
    // 通道级日志（启动/失败/告警）大多没有 peer，选中某个目标时也必须显示，
    // 否则「微信有消息没反应」时事件流一片空白，无法定位。
    if (!peer || l.level === "warn" || matchActive(peer, "", "wx")) {
      logLine(`[微信] ${l.msg}`, l.level === "warn" ? "warn" : "");
    }
  });
  unlistenFns.push(un2);

  // AI 回复管线日志（chat/send → autoReply → 回发）
  const un3 = await onAutoreplyEvent((l: AutoReplyLog) => {
    // 管线日志里静默忽略用 peer、AI 网关/回发用 to，两个都要认；
    // warn（失败/未回复）即使没有会话信息也要显示。
    const peer = l?.extra?.to || l?.extra?.peer || "";
    const chanHit = matchActive(peer, l?.extra?.bot_id || "", "wx") || matchActive(peer, l?.extra?.bot_id || "", "ilink");
    if (chanHit || !peer || l.level === "warn") {
      logLine(`[AI] ${l.msg}`, l.level === "warn" ? "warn" : l.level === "debug" ? "debug" : "");
    }
  });
  unlistenFns.push(un3);

  // 统一 AI 消息网关日志（多渠道 → 团长 → AI 回复）
  const un4 = await onAiGatewayEvent((l: AutoReplyLog) => {
    logLine(`[网关] ${l.msg}`, l.level === "warn" ? "warn" : "");
  });
  unlistenFns.push(un4);

  // 名册后台更新完成（内容真变化）→ 读缓存重填下拉，不等用户手动点刷新
  const un5 = await onRosterUpdated(() => { void refreshTargets(); });
  unlistenFns.push(un5);
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
    void refreshTargets(true);
    void refreshCacheStatus();
  });
  g("ia-gw-refresh")?.addEventListener("click", () => void refreshGatewayStatus());
  g("ia-gw-status")?.addEventListener("click", async (ev) => {
    if (!(ev.target as HTMLElement)?.closest("#ia-gw-fault-reset")) return;
    try {
      await aiGatewayApi.faultReset();
      toast("已解除熔断，下一次写请求将立即发出");
      await refreshGatewayStatus();
    } catch (e: any) {
      toast("解除失败：" + (e?.message || e), false);
    }
  });
  g("ia-gw-ocr")?.addEventListener("click", () => void runOcrTest());
  g("ia-sf-resume")?.addEventListener("click", async () => {
    try {
      await autoreplyApi.safetyResume();
      toast("已解除微信通道急停");
      await refreshSafetyStatus();
    } catch (e: any) {
      toast("解除失败：" + (e?.message || e), false);
    }
  });
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
