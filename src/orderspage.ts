// 订单消息页：左侧微信↔iLink 对应关系（渠道目标选择），右侧合成图预览 + 确认发送。
// 「一键拉取」= leader/ledger(团/轮次) + leader/orders(订单) + account/info(收款码)
// → 自动算出订单笔数与合计金额 → canvas 合成（收款码 + 笔数 + 金额）→ 原路发送。
import { api } from "./api";
import { toast } from "./ui";
import { CHANNELS, ChannelKey, channelsApi, ChannelTargets, composePayImage, fetchImageDataUrl } from "./channels";
import { onIlinkEvent } from "./ilink";

let g: (id: string) => HTMLElement | null = (id) => document.getElementById(id);
let targets: ChannelTargets | null = null;
// 当前选中渠道（下拉选择即对应）
let active: ChannelKey = "wx_private";
// 收款码缓存（account/info）
let qrCache: { wechat: string; alipay: string; payee: string } | null = null;
let lastDataUrl = "";

// ---- 一键拉取的订单数据 ----
interface GroupInfo { id: string; name: string; rounds: any[] }
let groups: GroupInfo[] = [];
let orders: any[] = [];
// 统计：{ count, amountYuan, roundNo?, source: "round"|"orders" }
let stats: { count: number; amountYuan: string; roundNo?: number } | null = null;
// 拉取状态文案
let pulled = false;
let started = false;

export function renderOrdersPage(root: HTMLElement) {
  g = (id) => root.querySelector(`#${id}`);
  root.innerHTML = `
    <div class="panel">
      <div class="row-between">
        <h1>订单消息</h1>
        <div class="btn-row">
          <select class="input" id="ord-group" style="max-width:200px;display:none"></select>
          <button class="btn small primary" id="ord-pull">一键拉取团员订单与收款码</button>
        </div>
      </div>
      <p class="hint">一键拉取 = 团轮次（leader/ledger）+ 团员订单（leader/orders）+ 团长收款码（account/info），自动算出订单笔数与合计金额并合成订单支付图。</p>
      <div class="ord-summary" id="ord-summary">尚未拉取数据。</div>
    </div>

    <div class="ord-cols">
      <div class="ord-left">
        <div class="panel">
          <div class="row-between"><h2>微信 ↔ iLink 对应关系</h2><button class="btn small" id="ord-refresh">刷新</button></div>
          <div class="ord-chans ord-chans-col">
            ${CHANNELS.map(
              (c) => `
              <div class="ord-chan" data-ch="${c.key}">
                <div class="ord-chan-label">${c.label}</div>
                <select class="input ord-select" id="ord-sel-${c.key}"><option value="">加载中…</option></select>
              </div>`
            ).join("")}
          </div>
          <div class="hint" id="ord-note"></div>
        </div>
      </div>

      <div class="ord-right">
        <div class="panel">
          <h2>支付信息</h2>
          <div class="ord-row">
            <label>合计金额 ¥</label>
            <input class="input" id="ord-amount" type="text" placeholder="一键拉取后自动填充" />
          </div>
          <div class="ord-row">
            <label>订单笔数</label>
            <input class="input" id="ord-count" type="text" placeholder="自动" />
          </div>
          <div class="ord-row">
            <label>备注（订单号等，可空）</label>
            <input class="input" id="ord-note-input" type="text" placeholder="选填" />
          </div>
          <div class="ord-row">
            <label>收款码类型</label>
            <select class="input" id="ord-qr-kind">
              <option value="wechat">微信收款码（团长）</option>
              <option value="alipay">支付宝收款码（团长）</option>
            </select>
          </div>
          <div class="hint" id="ord-qr-note">收款码来自 bbt-zb account/info（团长本人配置）。</div>
        </div>

        <div class="panel">
          <div class="row-between">
            <h2>合成预览</h2>
            <div class="btn-row">
              <button class="btn small" id="ord-compose">重新合成</button>
              <button class="btn small primary" id="ord-send">确认发送</button>
            </div>
          </div>
          <div class="ord-preview"><img id="ord-preview-img" alt="合成预览" /></div>
          <div class="ar-log" id="ord-log"></div>
        </div>
      </div>
    </div>`;

  wire();
  void refreshTargets();

  if (!started) {
    started = true;
    // 登录成功后自动刷新渠道目标（iLink/团员），不用手动点
    window.addEventListener("login-changed", () => { void refreshTargets(); });
    // iLink 账号上下线/绑定时自动刷新
    void onIlinkEvent(() => { void refreshTargets(); });
  }
}

function logLine(msg: string, cls = "") {
  const box = g("ord-log");
  if (!box) return;
  const div = document.createElement("div");
  div.className = "ar-line " + cls;
  const t = new Date();
  const p = (n: number) => String(n).padStart(2, "0");
  div.innerHTML = `<span class="ar-t">${p(t.getHours())}:${p(t.getMinutes())}:${p(t.getSeconds())}</span>${escapeHtml(msg)}`;
  box.prepend(div);
  while (box.children.length > 30) box.lastChild?.remove();
}

function escapeHtml(s: string): string {
  return (s || "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c] as string));
}

function activeTargetId(): string {
  const sel = g(`ord-sel-${active}`) as HTMLSelectElement | null;
  return sel?.value || "";
}

function activeTargetLabel(): string {
  const sel = g(`ord-sel-${active}`) as HTMLSelectElement | null;
  return sel?.selectedOptions?.[0]?.textContent || "";
}

function activeTargetRaw(): any {
  const id = activeTargetId();
  if (active === "ilink") return (targets?.ilink || []).find((a: any) => (a.bot_id || a.user_id) === id) || null;
  if (active === "member") return (targets?.member || []).find((m: any) => String(m.id) === id) || null;
  if (active === "wx_private") return (targets?.wx_private || []).find((s: any) => String(s.id) === id) || null;
  if (active === "wx_group") return (targets?.wx_group || []).find((s: any) => String(s.id) === id) || null;
  return null;
}

async function refreshTargets() {
  const note = g("ord-note");
  try {
    targets = await channelsApi.targets();
  } catch (e: any) {
    if (note) note.textContent = "拉取目标失败：" + (e?.message || e);
    return;
  }
  if (!targets) return;
  // 可观测：把各渠道实际识别到的数量与后端真实原因写进日志，便于定位「识别不到」
  logLine(`渠道目标：微信私聊 ${targets.wx_private?.length || 0} / 微信群 ${targets.wx_group?.length || 0} / iLink ${targets.ilink?.length || 0} / 团员 ${targets.member?.length || 0}`);
  if (targets.member_note) logLine("团员：" + targets.member_note, "warn");
  if (targets.ilink_note) logLine("iLink：" + targets.ilink_note, "warn");

  const fill = (key: ChannelKey, rows: any[], emptyText: string) => {
    const sel = g(`ord-sel-${key}`) as HTMLSelectElement | null;
    if (!sel) return;
    if (!rows.length) {
      sel.innerHTML = `<option value="">${emptyText}</option>`;
      return;
    }
    sel.innerHTML =
      `<option value="">— 选择（${rows.length}）—</option>` +
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
    (targets.ilink || []).map((a: any) => ({ ...a, id: a.bot_id || a.user_id, name: a.label || a.user_id || a.bot_id })),
    targets.ilink_note || "iLink：无已绑定账号"
  );
  const map = targets.member_map || {};
  fill(
    "member",
    (targets.member || []).map((m: any) => ({
      ...m,
      id: String(m.id),
      name: `${m.name || m.realName || m.id}${map[String(m.id)] ? " ✓已绑定" : ""}`,
    })),
    targets.member_note || "团员：未登录或名册为空"
  );
  if (note) {
    const parts: string[] = [];
    if (targets.wx_note) parts.push("微信：" + targets.wx_note);
    if (!targets.ilink?.length) parts.push("iLink：" + (targets.ilink_note || "无已绑定账号"));
    if (!targets.member?.length) parts.push("团员：" + (targets.member_note || "未登录或名册为空"));
    note.textContent = parts.join("；") || "四渠道目标已就绪。";
  }
}

function pickChannel(key: ChannelKey) {
  active = key;
  document.querySelectorAll(".ord-chan").forEach((el) => {
    el.classList.toggle("active", (el as HTMLElement).dataset.ch === key);
  });
  // 切换目标后，若是团员渠道则尝试按成员重算统计
  if (pulled) void applyMemberStats();
}

// ---------------- 一键拉取 ----------------

async function pullAll() {
  const btn = g("ord-pull") as HTMLButtonElement | null;
  const sum = g("ord-summary");
  const qrNote = g("ord-qr-note");
  btn?.setAttribute("disabled", "true");
  if (sum) sum.textContent = "拉取中…";
  try {
    // 1) 团列表 + 轮次（与官方前端 leader/ledger 同链路）
    const lr: any = await api.call("leader/ledger", {});
    const lgD = lr?.result?.data ?? lr?.data ?? {};
    groups = ((lgD.list || []) as any[]).map((x: any) => ({
      id: String(x.id),
      name: x.name || `团#${x.id}`,
      rounds: x.rounds || [],
    }));
    if (!groups.length) throw new Error("当前团长账号没有管理任何团");

    // 团选择器（多团显示）
    const gsel = g("ord-group") as HTMLSelectElement | null;
    if (gsel) {
      if (groups.length > 1) {
        gsel.style.display = "";
        if (!gsel.value || !groups.some((gp) => gp.id === gsel.value)) gsel.value = groups[0].id;
        gsel.innerHTML = groups.map((gp) => `<option value="${escapeHtml(gp.id)}"${gp.id === gsel.value ? " selected" : ""}>${escapeHtml(gp.name)}</option>`).join("");
      } else {
        gsel.style.display = "none";
        gsel.value = groups[0].id;
      }
    }

    // 2) 收款码（account/info，团长本人）；两码皆空时不缓存空结果，下次可重拉
    if (!qrCache || (!qrCache.wechat && !qrCache.alipay)) {
      const info = await api.call("account/info", {});
      const d = info?.result?.data || {};
      qrCache = {
        wechat: d.wechatQrcode || "",
        alipay: d.alipayQrcode || "",
        payee: d.name || d.telephoneMask || "团长",
      };
    }

    // 3) 订单 + 统计
    await loadOrdersOf(currentGroupId());
    pulled = true;

    // 4) 自动填充金额/笔数并合成预览
    if (stats) {
      const amt = g("ord-amount") as HTMLInputElement | null;
      const cnt = g("ord-count") as HTMLInputElement | null;
      if (amt && !amt.value) amt.value = stats.amountYuan;
      if (cnt && !cnt.value) cnt.value = String(stats.count);
      if (qrNote) {
        qrNote.textContent = `收款对象：${qrCache?.payee || "—"}；微信码${qrCache?.wechat ? "✓" : "✗ 未设置"}；支付宝码${qrCache?.alipay ? "✓" : "✗ 未设置"}`;
      }
    }
    await compose();
    logLine(`一键拉取完成：订单 ${stats?.count ?? 0} 笔 · 合计 ¥${stats?.amountYuan ?? "0.00"}`);
    // 拉取成功后立刻刷新渠道目标：登录态/绑定/名册刚就绪时无需再手动点「刷新」
    void refreshTargets();
  } catch (e: any) {
    const msg = e?.message || String(e);
    if (sum) sum.textContent = "拉取失败：" + msg;
    logLine("一键拉取失败：" + msg, "warn");
  } finally {
    btn?.removeAttribute("disabled");
  }
}

function currentGroupId(): string {
  const sel = g("ord-group") as HTMLSelectElement | null;
  return sel?.value || groups[0]?.id || "";
}

async function loadOrdersOf(groupId: string) {
  const sum = g("ord-summary");
  if (!groupId) throw new Error("未选择团");
  const r: any = await api.call("leader/orders", { groupId: Number(groupId) || groupId });
  const d = r?.result?.data ?? r?.data ?? {};
  // 兼容 data 直接是数组 / {list} / {orders} 三种返回形态
  orders = Array.isArray(d) ? d : (d.list || d.orders || []);
  stats = computeStats();
  logLine(`订单接口返回 ${orders.length} 笔（groupId=${groupId}），轮次统计 ${stats.count} 笔 / ¥${stats.amountYuan}`);
  renderSummary();
}

function parseYuan(v: any): number {
  const n = parseFloat(String(v ?? "").replace(/[¥,]/g, ""));
  return isFinite(n) ? n : 0;
}

function computeStats(): { count: number; amountYuan: string; roundNo?: number } {
  const grp = groups.find((gp) => gp.id === currentGroupId());
  const cur = grp?.rounds?.[0];
  if (cur && (cur.orderCnt !== undefined || cur.matched !== undefined)) {
    const matchedFen = Number(cur.matched) || 0;
    return { count: Number(cur.orderCnt) || 0, amountYuan: (matchedFen / 100).toFixed(2), roundNo: cur.roundNo };
  }
  // 兜底：从订单列表现算
  return { count: orders.length, amountYuan: orders.reduce((s, o) => s + parseYuan(o.amountYuan), 0).toFixed(2), roundNo: cur?.roundNo };
}

// 按当前选中目标（团员渠道）重算统计：匹配 buyerName
async function applyMemberStats() {
  if (active !== "member") { renderSummary(); return; }
  const raw = activeTargetRaw();
  // 收集团员所有可能的身份名（微信昵称/真实姓名/脱敏手机）
  const candidateNames: string[] = [];
  for (const k of ["name", "realName", "nickname", "displayName", "label"]) {
    const v = raw?.[k];
    if (v && typeof v === "string" && v.trim()) candidateNames.push(v.trim());
  }
  const tel = raw?.telephoneMask || raw?.telephoneMasked || "";
  if (!candidateNames.length && !tel) { renderSummary(); return; }
  const grp = groups.find((gp) => gp.id === currentGroupId());
  const curNo = grp?.rounds?.[0]?.roundNo;
  const mine = orders.filter((o) => {
    const bn = String(o.buyerName || "").trim();
    if (!bn) return false;
    if (curNo && o.roundNo && Number(o.roundNo) !== Number(curNo)) return false;
    // buyerName 可能是微信昵称，也可能是脱敏手机尾号
    return candidateNames.some((n) => bn === n || bn.includes(n) || n.includes(bn))
      || (tel && (bn.includes(tel) || tel.includes(bn)));
  });
  if (mine.length) {
    stats = { count: mine.length, amountYuan: mine.reduce((s, o) => s + parseYuan(o.amountYuan), 0).toFixed(2), roundNo: curNo };
    const amt = g("ord-amount") as HTMLInputElement | null;
    const cnt = g("ord-count") as HTMLInputElement | null;
    if (amt) amt.value = stats.amountYuan;
    if (cnt) cnt.value = String(stats.count);
    renderSummary(`已按「${candidateNames[0] || tel}」过滤订单`);
  } else {
    stats = computeStats();
    renderSummary(`未在订单中匹配到团员（试过: ${candidateNames.join("/") || tel}），显示全团统计`);
  }
}

function renderSummary(prefix = "") {
  const sum = g("ord-summary");
  if (!sum || !stats) return;
  const grp = groups.find((gp) => gp.id === currentGroupId());
  const cur = grp?.rounds?.[0];
  const bits: string[] = [];
  if (prefix) bits.push(escapeHtml(prefix));
  bits.push(`团：${escapeHtml(grp?.name || "—")}`);
  if (stats.roundNo) bits.push(`第 ${stats.roundNo} 轮`);
  bits.push(`订单 <b>${stats.count}</b> 笔`);
  bits.push(`合计 <b>¥${escapeHtml(stats.amountYuan)}</b>`);
  if (qrCache) {
    bits.push(`收款对象：${escapeHtml(qrCache.payee)}`);
    bits.push(qrCache.wechat ? `微信码 ✓` : `<span style="color:var(--err)">微信码未设置</span>`);
  }
  sum.innerHTML = bits.join(" · ");
}

// ---------------- 合成与发送 ----------------

async function loadQr(): Promise<{ dataUrl: string; kind: string; payee: string }> {
  const kind = (g("ord-qr-kind") as HTMLSelectElement)?.value || "wechat";
  if (!qrCache || (!qrCache.wechat && !qrCache.alipay)) {
    const info = await api.call("account/info", {});
    const d = info?.result?.data || {};
    qrCache = {
      wechat: d.wechatQrcode || "",
      alipay: d.alipayQrcode || "",
      payee: d.name || d.telephoneMask || "团长",
    };
  }
  const path = kind === "alipay" ? qrCache!.alipay : qrCache!.wechat;
  if (!path) throw new Error(kind === "alipay" ? "未上传支付宝收款码" : "未上传微信收款码");
  const cfg2: any = await api.loadConfig();
  const base2 = (cfg2?.connection?.base_url || "").replace(/\/$/, "");
  // 官方前端 _bbt_core.js:12 常量 FILES = BASE + 'api/files/'，少了 /api 会 404
  const url = /^https?:\/\//i.test(path)
    ? path
    : `${base2}/api/files/${String(path).replace(/^\/+/, "")}`;
  logLine(`加载收款码：${url}`);
  const dataUrl = await fetchImageDataUrl(url);
  return { dataUrl, kind: kind === "alipay" ? "支付宝" : "微信", payee: qrCache!.payee };
}

async function compose() {
  const amount = ((g("ord-amount") as HTMLInputElement)?.value || "").trim();
  if (!amount) {
    toast("请先一键拉取或手填合计金额", false);
    return;
  }
  const noteIn = ((g("ord-note-input") as HTMLInputElement)?.value || "").trim();
  const cntIn = parseInt(((g("ord-count") as HTMLInputElement)?.value || "").trim(), 10);
  const noteEl = g("ord-qr-note");
  try {
    if (noteEl) noteEl.textContent = "拉取收款码并合成中…";
    const { dataUrl, kind, payee } = await loadQr();
    lastDataUrl = await composePayImage({
      amount,
      payeeName: payee,
      qrDataUrl: dataUrl,
      kind,
      note: noteIn,
      orderCount: isFinite(cntIn) ? cntIn : undefined,
    });
    const img = g("ord-preview-img") as HTMLImageElement | null;
    if (img) img.src = lastDataUrl;
    if (noteEl) noteEl.textContent = `已合成：对象=${payee}，类型=${kind}，金额=¥${amount.replace(/^¥/, "")}${isFinite(cntIn) ? `，订单 ${cntIn} 笔` : ""}`;
    logLine(`订单图合成完成（${payee} ¥${amount}${isFinite(cntIn) ? ` · ${cntIn} 笔` : ""}）`);
  } catch (e: any) {
    if (noteEl) noteEl.textContent = "合成失败：" + (e?.message || e);
    logLine("合成失败：" + (e?.message || e), "warn");
  }
}

async function send() {
  if (!lastDataUrl) {
    await compose();
    if (!lastDataUrl) return;
  }
  const target = activeTargetId();
  if (!target) {
    toast(`请先在左侧「${CHANNELS.find((c) => c.key === active)?.label}」下拉框选择目标`, false);
    return;
  }
  const b64 = lastDataUrl.split(",")[1] || "";
  try {
    await channelsApi.sendImage(active, target, b64, "order-pay.png");
    logLine(`订单图已发送 → [${CHANNELS.find((c) => c.key === active)?.label}] ${activeTargetLabel()}`);
    toast("订单图已发送");
  } catch (e: any) {
    logLine(`发送失败：${e?.message || e}`, "warn");
    toast("发送失败：" + (e?.message || e), false);
  }
}

function wire() {
  CHANNELS.forEach((c) => {
    g(`ord-sel-${c.key}`)?.addEventListener("change", () => pickChannel(c.key));
  });
  g("ord-pull")?.addEventListener("click", () => void pullAll());
  g("ord-group")?.addEventListener("change", () => {
    void (async () => {
      try {
        await loadOrdersOf(currentGroupId());
        const amt = g("ord-amount") as HTMLInputElement | null;
        const cnt = g("ord-count") as HTMLInputElement | null;
        if (amt && stats) amt.value = stats.amountYuan;
        if (cnt && stats) cnt.value = String(stats.count);
        await compose();
      } catch (e: any) {
        logLine("切换团失败：" + (e?.message || e), "warn");
      }
    })();
  });
  g("ord-refresh")?.addEventListener("click", () => void refreshTargets());
  g("ord-compose")?.addEventListener("click", () => void compose());
  g("ord-send")?.addEventListener("click", () => void send());
  pickChannel(active);
}
