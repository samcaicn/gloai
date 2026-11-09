// 统一渠道路由前端封装 + 订单支付图 canvas 合成
// 渠道：wx_private 微信私聊 / wx_group 微信群聊 / ilink iLink 账号 / member 团员(经绑定 bot)
import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";

export interface WxSession {
  id: string;
  name: string;
  is_group: boolean;
  summary: string;
  last_time: number;
}

/** 团员名册缓存状态（Rust roster_cache 透传，用于向用户说明数据来源与新鲜度） */
export interface MemberCache {
  source: "memory" | "disk" | "stale" | "network" | "error";
  age_ms: number;
  cached_at: number;
  count: number;
  stale: boolean;
  refreshing: boolean;
  ttl_ms: number;
  retry_ms: number;
  last_attempt_ms: number;
  last_success_ms: number;
  path: string;
  backup: string;
  error?: string;
}

/** 后端名册刷新完成（内容确有变化）时推送，前端据此 0 网络重绘 */
export interface RosterUpdatedEvent {
  source: "network" | "cleared";
  changed: boolean;
  count: number;
  cache?: MemberCache;
}

export function onRosterUpdated(cb: (e: RosterUpdatedEvent) => void): Promise<UnlistenFn> {
  return listen<RosterUpdatedEvent>("roster-updated", (ev) => cb(ev.payload));
}

export interface ChannelTargets {
  wx_private: WxSession[];
  wx_group: WxSession[];
  wx_note: string;
  ilink: any[];
  ilink_note: string;
  member: any[];
  member_note: string;
  member_cache?: MemberCache;
  member_map: Record<string, string>;
}

/** 人可读的缓存来源说明，不只用颜色传达状态 */
export function memberCacheLabel(c?: MemberCache): string {
  if (!c || !c.count) return c?.error ? `名册异常：${c.error}` : "名册暂无本地缓存";
  const mins = Math.max(0, Math.floor(c.age_ms / 60000));
  const age = mins < 1 ? "刚刚" : mins < 60 ? `${mins} 分钟前` : `${Math.floor(mins / 60)} 小时前`;
  const src: Record<string, string> = {
    memory: "本地缓存",
    disk: "本地缓存（重启恢复）",
    stale: "本地缓存",
    network: "本次在线更新",
    error: "本地缓存",
  };
  let label = `${src[c.source] || "本地缓存"} · ${age}更新 · ${c.count} 名团员`;
  // 「缓存过期但正在后台更新」≠「刷新失败离线可用」，之前两者都写成离线可用会误导排障
  if (c.error) label += ` · 在线更新失败，显示离线缓存（${c.error}）`;
  else if (c.refreshing) label += " · 后台更新中";
  else if (c.source === "stale") label += " · 待更新";
  return label;
}

export const CHANNELS = [
  { key: "wx_private", label: "微信私聊" },
  { key: "wx_group", label: "微信群聊" },
  { key: "ilink", label: "iLink" },
  { key: "member", label: "团员" },
] as const;

export type ChannelKey = (typeof CHANNELS)[number]["key"];

export const channelsApi = {
  /** force=true 才同步回源；默认走本地缓存（过期先给旧数据 + 后台刷新） */
  targets: (force = false) => invoke<ChannelTargets>("channel_targets", { force }),
  sendText: (channel: ChannelKey | string, target: string, text: string) =>
    invoke<any>("channel_send_text", { channel, target, text }),
  sendImage: (channel: ChannelKey | string, target: string, dataB64: string, filename?: string) =>
    invoke<any>("channel_send_image", { channel, target, dataB64, filename: filename || undefined }),
  fetchImageB64: (url: string) => invoke<string>("fetch_image_b64", { url }),
};

/** 远程图片 → dataURL（经 Rust 拉取，绕开 canvas 跨域污染） */
export async function fetchImageDataUrl(url: string): Promise<string> {
  const b64 = await channelsApi.fetchImageB64(url);
  const mime = /^data|\.png(\?|$)/.test(url) ? "image/png" : "image/jpeg";
  return `data:${mime};base64,${b64}`;
}

export interface PayImageOpts {
  amount: string;          // 「88.00」或「¥88.00」
  payeeName: string;       // 收款对象名称
  qrDataUrl: string;       // 收款码图片 dataURL
  kind?: string;           // 微信 / 支付宝
  note?: string;           // 备注（订单号等）
  orderCount?: number;     // 订单笔数（一键拉取自动填充）
}

/**
 * canvas 合成订单支付图（640×920）：
 * 头部「抱抱团 · 订单收款」+ 收款对象 + 大字金额 + 订单笔数 + 收款码 + 底部扫码提示。
 */
export function composePayImage(opts: PayImageOpts): Promise<string> {
  return new Promise((resolve, reject) => {
    const W = 640, H = 920;
    const cv = document.createElement("canvas");
    cv.width = W;
    cv.height = H;
    const ctx = cv.getContext("2d");
    if (!ctx) return reject(new Error("canvas 不可用"));

    const img = new Image();
    img.onload = () => {
      // 背景
      ctx.fillStyle = "#ffffff";
      ctx.fillRect(0, 0, W, H);
      // 顶部色带
      const grad = ctx.createLinearGradient(0, 0, 0, 150);
      grad.addColorStop(0, "#E8A33D");
      grad.addColorStop(1, "#f5c46a");
      ctx.fillStyle = grad;
      ctx.fillRect(0, 0, W, 150);
      // 标题
      ctx.fillStyle = "#ffffff";
      ctx.font = "bold 40px 'Microsoft YaHei', sans-serif";
      ctx.textAlign = "center";
      ctx.fillText("抱抱团 · 订单收款", W / 2, 92);

      // 收款对象
      ctx.fillStyle = "#333333";
      ctx.font = "28px 'Microsoft YaHei', sans-serif";
      ctx.fillText(`收款对象：${opts.payeeName || "—"}`, W / 2, 210);

      // 金额大字
      const amt = opts.amount.replace(/^¥/, "").trim();
      ctx.fillStyle = "#e34949";
      ctx.font = "bold 72px 'Microsoft YaHei', sans-serif";
      ctx.fillText(`¥${amt}`, W / 2, 296);

      // 订单笔数（一键拉取时自动填充）
      let y = 352;
      if (opts.orderCount !== undefined && opts.orderCount >= 0) {
        ctx.fillStyle = "#333333";
        ctx.font = "30px 'Microsoft YaHei', sans-serif";
        ctx.fillText(`订单 ${opts.orderCount} 笔`, W / 2, y);
        y += 46;
      }

      // 备注
      if (opts.note && opts.note.trim()) {
        ctx.fillStyle = "#888888";
        ctx.font = "22px 'Microsoft YaHei', sans-serif";
        ctx.fillText(`备注：${opts.note.trim()}`, W / 2, y);
        y += 40;
      }

      // 收款码白底卡片 + 描边
      const qrSize = 340;
      const qx = (W - qrSize) / 2;
      const qy = Math.max(y, 440);
      ctx.strokeStyle = "#eeeeee";
      ctx.lineWidth = 2;
      ctx.strokeRect(qx - 14, qy - 14, qrSize + 28, qrSize + 28);
      // 收款码按最长边等比缩放居中
      const scale = Math.min(qrSize / img.width, qrSize / img.height);
      const dw = img.width * scale, dh = img.height * scale;
      ctx.drawImage(img, qx + (qrSize - dw) / 2, qy + (qrSize - dh) / 2, dw, dh);

      // 底部提示
      const kind = opts.kind || "微信";
      ctx.fillStyle = "#555555";
      ctx.font = "26px 'Microsoft YaHei', sans-serif";
      ctx.fillText(`请使用${kind}扫一扫，向上方金额付款`, W / 2, qy + qrSize + 60);
      ctx.fillStyle = "#bbbbbb";
      ctx.font = "20px 'Microsoft YaHei', sans-serif";
      ctx.fillText("付款后请在订单页上传支付凭证", W / 2, qy + qrSize + 98);

      resolve(cv.toDataURL("image/png"));
    };
    img.onerror = () => reject(new Error("收款码图片加载失败"));
    img.src = opts.qrDataUrl;
  });
}
