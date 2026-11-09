// iLink 多账号聊天：Tauri command 封装 + 事件监听
import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";

export interface AccountInfo {
  bot_id: string;
  label: string;
  user_id: string;
  base_url?: string;
  status: string; // running / offline / session_expired / error
}

export interface ChatRecord {
  id: string;
  bot_id: string;
  dir: "in" | "out";
  peer: string;
  kind: "text" | "image" | "voice" | "file" | "video" | "other";
  text: string;
  media?: any;
  ts: number;
  status?: string;
  playtime_ms?: number;
}

export interface BridgeTarget {
  bot_id: string;
  wechat_user_id: string;
  bbt_session_id: string;
}

export interface BridgeConfig {
  enabled: boolean;
  poll_interval_sec: number;
  session_list_action: string;
  history_action: string;
  send_action: string;
  forward_leader_to_bots: boolean;
  forward_inbound_to_bbt: boolean;
  targets: BridgeTarget[];
}

export const ilink = {
  // member_id：本次二维码对应的团员，扫码成功由后端直接落盘绑定（扫码成功 = 已绑定）
  beginLogin: (member_id?: string) => invoke<any>("ilink_begin_login", { memberId: member_id || null }),
  pollLogin: (login_id: string) => invoke<any>("ilink_poll_login", { loginId: login_id }),
  cancelLogin: (login_id: string) => invoke("ilink_cancel_login", { loginId: login_id }),
  listAccounts: () => invoke<AccountInfo[]>("ilink_list_accounts"),
  removeAccount: (bot_id: string) => invoke("ilink_remove_account", { botId: bot_id }),
  sendText: (bot_id: string, to: string, text: string) =>
    invoke<any>("ilink_send_text", { botId: bot_id, to, text }),
  sendImage: (bot_id: string, to: string, dataB64: string, filename: string) =>
    invoke<any>("ilink_send_image", { botId: bot_id, to, dataB64, filename }),
  sendVoice: (bot_id: string, to: string, dataB64: string, encodeType: number, sampleRate: number, playtimeMs: number) =>
    invoke<any>("ilink_send_voice", { botId: bot_id, to, dataB64, encodeType, sampleRate, playtimeMs }),
  broadcastText: (bot_ids: string[], to: string, text: string) =>
    invoke<any>("ilink_broadcast_text", { botIds: bot_ids, to, text }),
  localHistory: (bot_id: string, limit = 300) =>
    invoke<ChatRecord[]>("ilink_local_history", { botId: bot_id, limit }),
  readMedia: (media: any) => invoke<any>("ilink_read_media", { media }),
};

export interface IlinkEvent {
  kind: "login" | "account" | "message";
  event: string;
  [k: string]: any;
}

export function onIlinkEvent(cb: (e: IlinkEvent) => void): Promise<UnlistenFn> {
  return listen<IlinkEvent>("ilink-event", (ev) => cb(ev.payload));
}

export const bridgeApi = {
  getConfig: () => invoke<BridgeConfig>("bridge_get_config"),
  setConfig: (value: Partial<BridgeConfig>) => invoke<BridgeConfig>("bridge_set_config", { value }),
  setEnabled: (enabled: boolean) => invoke<BridgeConfig>("bridge_set_enabled", { enabled }),
};

/** 聊天对象（weauto「用户与监听」风格的单个私聊/群聊配置） */
export interface ListenEntry {
  name: string; // 窗口名（微信昵称/群名，子串匹配）
  kind: string; // "private" | "group"
  mon_types: string[]; // 监测内容类型，空=全部
  mon_keywords: string[]; // 监测关键字
  action: string; // "reply" | "ignore"（不回应）
}

export interface AutoReplyConfig {
  enabled: boolean;
  member_telephone: string;
  member_password: string;
  private_reply: boolean;
  group_mode: "off" | "at" | "all";
  at_names: string[];
  group_keywords: string[];
  cooldown_sec: number;
  strip_at_prefix: boolean;
  reply_voice: boolean;
  listen_list: ListenEntry[];
  group_whitelist: string[];
  group_blacklist: string[];
  banned_words: string[];
  group_rate_window_sec: number;
  group_rate_max_replies: number;
  /** 交互优化：真人思考随机延迟（秒），私聊/群聊各自可配 */
  think_min_sec: number;
  think_max_sec: number;
  group_think_min_sec: number;
  group_think_max_sec: number;
  /** 服务端写故障时是否回中性兜底话术（其它失败原因不回复） */
  fallback_on_fault: boolean;
  fallback_text: string;
  /** 同一会话兜底回复最小间隔（秒） */
  fallback_cooldown_sec: number;
  /** 发送安全（仅微信 PC 通道）：每小时最多发起的发送次数，0=不限 */
  hourly_send_budget: number;
  /** 允许自动发送的本地时段 "HH:MM"，留空=不限 */
  active_hours_start: string;
  active_hours_end: string;
  /** 连续回发失败达到该次数后急停微信通道，0=关闭 */
  fail_streak_threshold: number;
  /** 急停时长（秒） */
  pause_sec: number;
  /** 仅当目标会话已在微信前台时发送（不抢焦点、不切会话） */
  require_foreground: boolean;
}

/** 微信通道发送安全的实时状态（状态条展示） */
export interface AutoReplySafetyStatus {
  paused: boolean;
  paused_remaining_sec: number;
  hourly_budget: number;
  budget_used: number;
  local_time: string;
  active_hours: string;
  within_active_hours: boolean;
  fail_streak: number;
  fail_streak_threshold: number;
  pause_sec: number;
  require_foreground: boolean;
  last_reason: string;
}

export interface AutoReplyLog {
  ts: number;
  level: "info" | "warn" | "debug";
  msg: string;
  extra?: any;
}

export interface AutoReplyTestStep {
  name: string;
  ok: boolean;
  detail: string;
}

export interface AutoReplyTestResult {
  ok: boolean;
  steps: AutoReplyTestStep[];
  reply: string;
}

/** PC 端统一 AI 消息网关：多渠道消息都只向同一个团长发，由团长 AI 回复 */
export interface AiGatewayStatus {
  ok: boolean;
  logged_in: boolean;
  role: string;
  leader: any;
  ai_enabled: boolean | null;
  session_id: any;
  quota: { limit?: number; used?: number; left?: number } | null;
  channels: {
    wx_private?: number;
    wx_group?: number;
    ilink?: number;
    member?: number;
    mapped?: number;
    /** 渠道总数 = 团员总数（每个私聊 / 群聊 / iLink 渠道各代表 1 个团员） */
    total?: number;
  };
  /** 当前生效的「向团长发消息」action；后端目前只有 chat/send，PC 专用接口是预留开关、未部署 */
  chat_action?: string;
  /** 备用团员账号数（额度用尽时自动换团员 id 继续请求） */
  member_pool?: number;
  /** 本机「渠道 ↔ 团员」映射里的团员 id */
  member_ids?: any[];
  /** 服务端写故障熔断状态（读正常、写全挂，如 D1 当日写入配额打满） */
  backend_fault?: {
    active: boolean;
    remaining_sec?: number;
    reason?: string;
    hint?: string;
  };
  hint: string;
}

export interface AiGatewaySendResult {
  ok: boolean;
  reply: string;
  session_id?: any;
  quota_left?: number | null;
  auto?: boolean;
  channel: string;
  target: string;
  sent: boolean;
  send_error: string;
}

export const aiGatewayApi = {
  /** 登录态 / 角色(仅展示) / AI 开关 / 今日配额 / 渠道=团员 / 后端写故障 */
  status: () => invoke<AiGatewayStatus>("ai_gateway_status"),
  /** 后端恢复后手动解除写故障熔断，立即放行下一次写请求 */
  faultReset: () => invoke<any>("ai_gateway_fault_reset"),
  /** 统一入口：向团长发一条消息并拿 AI 回复；autoSend=true 按渠道原路回发 */
  send: (text: string, channel?: string, target?: string, autoSend = false, memberId?: any) =>
    invoke<AiGatewaySendResult>("ai_gateway_send", {
      text,
      channel: channel ?? null,
      target: target ?? null,
      auto_send: autoSend,
      member_id: memberId ?? null,
    }),
  /** 取微信本地库最近一张图片做离线 OCR；inject=true 时把结果按图片消息注入自动回复管线 */
  ocrTest: (peer?: string, inject = false) =>
    invoke<any>("wxauto_ocr_test", { peer: peer ?? null, inject }),
  logs: (limit = 50) => invoke<AutoReplyLog[]>("ai_gateway_logs", { limit }),
};

export function onAiGatewayEvent(cb: (e: AutoReplyLog) => void): Promise<UnlistenFn> {
  return listen<AutoReplyLog>("ai-gateway-event", (ev) => cb(ev.payload));
}

export const autoreplyApi = {
  getConfig: () => invoke<AutoReplyConfig>("autoreply_get_config"),
  setConfig: (value: Partial<AutoReplyConfig>) =>
    invoke<AutoReplyConfig>("autoreply_set_config", { value }),
  setEnabled: (enabled: boolean) =>
    invoke<AutoReplyConfig>("autoreply_set_enabled", { enabled }),
  logs: (limit = 50) => invoke<AutoReplyLog[]>("autoreply_logs", { limit }),
  /** 微信发送安全实时状态：急停 / 每小时预算 / 允许时段 / 最近拦截原因 */
  safetyStatus: () => invoke<AutoReplySafetyStatus>("autoreply_safety_status"),
  /** 人工确认微信恢复后解除急停并清零连败计数 */
  safetyResume: () => invoke<AutoReplySafetyStatus>("autoreply_safety_resume"),
  /** AI 消息自测：send=false 仅检查链路（不消耗平台配额），send=true 实发一条测试问题 */
  selfTest: (question: string, send: boolean) =>
    invoke<AutoReplyTestResult>("autoreply_self_test", { question, send }),
  /** 注入一条模拟客户消息走完整管线（AI 回复只记日志、不外发） */
  inject: (text: string, peer?: string, group?: boolean) =>
    invoke<{ ok: boolean; reply: string }>("autoreply_inject", { text, peer: peer ?? null, group: !!group }),
};

export function onAutoreplyEvent(cb: (e: AutoReplyLog) => void): Promise<UnlistenFn> {
  return listen<AutoReplyLog>("autoreply-event", (ev) => cb(ev.payload));
}

// ---------------- 微信 wxauto 通道（wechatauto sidecar） ----------------

export interface WxAutoConfig {
  enabled: boolean;
  python_path: string;
  vendor_path: string;
  running?: boolean;
}

export const wxautoApi = {
  getConfig: () => invoke<WxAutoConfig>("wxauto_get_config"),
  setConfig: (value: Partial<WxAutoConfig>) => invoke<WxAutoConfig>("wxauto_set_config", { value }),
  start: () => invoke<{ ok: boolean }>("wxauto_start"),
  stop: () => invoke<{ ok: boolean }>("wxauto_stop"),
  logs: (limit = 50) => invoke<AutoReplyLog[]>("wxauto_logs", { limit }),
};

export function onWxautoEvent(cb: (e: AutoReplyLog) => void): Promise<UnlistenFn> {
  return listen<AutoReplyLog>("wxauto-event", (ev) => cb(ev.payload));
}

// 媒体 data-URL 缓存（避免重复下载）
const mediaCache = new Map<string, string>();

export async function mediaDataUrl(record: ChatRecord): Promise<string> {
  const key = `${record.bot_id}|${record.id}`;
  const hit = mediaCache.get(key);
  if (hit) return hit;
  // 刚发送的本地媒体：直接用内存 base64，不请求 CDN
  if (record.media && record.media.data_b64) {
    const url = `data:${record.media.mime || "application/octet-stream"};base64,${record.media.data_b64}`;
    mediaCache.set(key, url);
    return url;
  }
  const r: any = await ilink.readMedia(record.media);
  const url = `data:${r.mime};base64,${r.data_b64}`;
  mediaCache.set(key, url);
  return url;
}

export function fileToB64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const s = String(reader.result || "");
      const i = s.indexOf(",");
      resolve(i >= 0 ? s.slice(i + 1) : s);
    };
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(file);
  });
}
