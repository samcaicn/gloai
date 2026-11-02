// 订单消息页：4 渠道下拉框（微信私聊/群聊/iLink/团员）→ 选定渠道接收订单支付图
// 支付金额（输入）+ 支付对象收款码（account/info 接口获取）→ canvas 合成 → channel_send_image
import { api } from "./api";
import { toast } from "./ui";
import { CHANNELS, ChannelKey, channelsApi, ChannelTargets, composePayImage, fetchImageDataUrl } from "./channels";

let g: (id: string) => HTMLElement | null = (id) => document.getElementById(id);
let targets: ChannelTargets | null = null;
// 当前选中渠道（下拉选择即对应）
let active: ChannelKey = "wx_private";
// 收款码缓存（account/info）
let qrCache: { wechat: string; alipay: string; payee: string } | null = null;
let lastDataUrl = "";

export function renderOrdersPage(root: HTMLElement) {
  g = (id) => root.querySelector(`#${id}`);
  root.innerHTML = `
    <div class="panel">
      <h1>订单消息</h1>
      <p class="hint">选定接收渠道 → 填金额 → 自动从平台取收款码合成订单支付图 → 原路发送（iLink/团员经该账号最近会话对象）。</p>
    </div>

    <div class="panel">
      <div class="row-between"><h2>① 选择接收渠道</h2><button class="btn small" id="ord-refresh">刷新目标</button></div>
      <div class="ord-chans">
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

    <div class="panel">
      <h2>② 支付信息</h2>
      <div class="ord-row">
        <label>支付金额 ¥</label>
        <input class="input" id="ord-amount" type="text" placeholder="如 88.00" />
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
        <button class="btn small" id="ord-load-qr">拉取收款码</button>
      </div>
      <div class="hint" id="ord-qr-note">收款对象与码图来自 bbt-zb account/info（团长本人收款码）。</div>
    </div>

    <div class="panel">
      <div class="row-between">
        <h2>③ 合成预览</h2>
        <div class="btn-row">
          <button class="btn small" id="ord-compose">重新合成</button>
          <button class="btn small primary" id="ord-send">发送订单图</button>
        </div>
      </div>
      <div class="ord-preview"><img id="ord-preview-img" alt="合成预览" /></div>
      <div class="ar-log" id="ord-log"></div>
    </div>`;

  wire();
  void refreshTargets();
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

async function refreshTargets() {
  const note = g("ord-note");
  try {
    targets = await channelsApi.targets();
  } catch (e: any) {
    if (note) note.textContent = "拉取目标失败：" + (e?.message || e);
    return;
  }
  if (!targets) return;

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
    "iLink：无已绑定账号"
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
    if (!targets.ilink?.length) parts.push("iLink：无已绑定账号");
    if (!targets.member?.length) parts.push("团员：未登录或名册为空");
    note.textContent = parts.join("；") || "四渠道目标已就绪。";
  }
}

function pickChannel(key: ChannelKey) {
  active = key;
  document.querySelectorAll(".ord-chan").forEach((el) => {
    el.classList.toggle("active", (el as HTMLElement).dataset.ch === key);
  });
}

async function loadQr(): Promise<{ dataUrl: string; kind: string; payee: string }> {
  const kind = (g("ord-qr-kind") as HTMLSelectElement)?.value || "wechat";
  if (!qrCache) {
    // account/info：团长本人收款码（qrcodes/xxx.jpg → {base}/files/...）
    const info = await api.call("account/info", {});
    const d = info?.result?.data || {};
    qrCache = {
      wechat: d.wechatQrcode || "",
      alipay: d.alipayQrcode || "",
      payee: d.name || d.telephoneMask || "团长",
    };
    if (!qrCache.wechat && !qrCache.alipay) throw new Error("团长账号未上传收款码");
  }
  const path = kind === "alipay" ? qrCache!.alipay : qrCache!.wechat;
  if (!path) throw new Error(kind === "alipay" ? "未上传支付宝收款码" : "未上传微信收款码");
  const cfg2: any = await api.loadConfig();
  const base2 = (cfg2?.connection?.base_url || "").replace(/\/$/, "");
  const url = `${base2}/files/${path}`;
  const dataUrl = await fetchImageDataUrl(url);
  return { dataUrl, kind: kind === "alipay" ? "支付宝" : "微信", payee: qrCache!.payee };
}

async function compose() {
  const amount = ((g("ord-amount") as HTMLInputElement)?.value || "").trim();
  if (!amount) {
    toast("请先填写支付金额", false);
    return;
  }
  const noteIn = ((g("ord-note-input") as HTMLInputElement)?.value || "").trim();
  const noteEl = g("ord-qr-note");
  try {
    if (noteEl) noteEl.textContent = "拉取收款码并合成中…";
    const { dataUrl, kind, payee } = await loadQr();
    lastDataUrl = await composePayImage({ amount, payeeName: payee, qrDataUrl: dataUrl, kind, note: noteIn });
    const img = g("ord-preview-img") as HTMLImageElement | null;
    if (img) img.src = lastDataUrl;
    if (noteEl) noteEl.textContent = `已合成：对象=${payee}，类型=${kind}，金额=¥${amount.replace(/^¥/, "")}`;
    logLine(`订单图合成完成（${payee} ¥${amount}）`);
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
    toast(`请先在「${CHANNELS.find((c) => c.key === active)?.label}」下拉框选择目标`, false);
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
  g("ord-refresh")?.addEventListener("click", () => void refreshTargets());
  g("ord-load-qr")?.addEventListener("click", () => void compose());
  g("ord-compose")?.addEventListener("click", () => void compose());
  g("ord-send")?.addEventListener("click", () => void send());
  pickChannel(active);
}
