// IM 相关 Tauri 命令封装。
// 命令名已对齐后端 lib.rs 的 invoke_handler 注册：
//   imSend       → im_send        (im_config.rs: channel_id, target, content)
//   imConfigGet  → im_config_get  (无参数，返回 ImConfigSnapshot)
//   imConfigSet  → im_config_set  (entry: ImChannelEntry)
//   imConfigList → im_channels    (返回 Vec<IMBinding>)
//   imConnect    → im_connect     (ext_streams.rs: channel_id —— 手动连接指定渠道)
//   imStatus     → im_status      (ext_streams.rs: 无参数，返回 Vec<ImChannelStatus>)
//
// 事件订阅：后端 im_config.rs::spawn_inbound_forwarder 通过 app.emit("im_adapter_event", ev)
// 推送 IMAdapterEvent（{ binding_id, kind, payload, ts }）。本桥接 subscribe 监听该事件名。
//
// im_send 必须包含 target 参数（项目 memory：LongConnAdapter 需 target 用于 relay gateway 路由）
import { invoke } from './invoke';
import type { ImMessage } from './types';
import { subscribe } from './events';

/** 后端 IMAdapterEvent（serde rename_all 默认 camelCase 未启用，字段名原样）。 */
export interface ImAdapterEvent {
  binding_id: string;
  kind: string;
  payload: any;
  ts: number;
}

/** 后端 auto_reply 循环 emit 的 backend_reply 事件 payload（前端据此渲染后端生成的回复）。 */
export interface ImBackendReplyPayload {
  channelId: string;
  target: string;
  content: string;
}

// 后端 im_connect 期望 (channel_id)；经 ChannelRegistry 解析 binding 后 AdapterPool.get_or_connect。
export async function imConnect(channelId: string): Promise<void> {
  return invoke<void>('im_connect', { channelId });
}

// 后端 im_send 期望 (channel_id, target, content)；msg.text 作为 content。
export async function imSend(channelId: string, msg: ImMessage, target: string): Promise<void> {
  return invoke<void>('im_send', { channelId, target, content: msg.text });
}

// 后端 im_set_bridged 期望 (channels: string[])。前端在桥接渠道集合变化时
// 全量上报，后端 inbound auto_reply 循环对桥接渠道跳过回复（由前端带技能
// 上下文驱动），避免双回复。窗口卸载/会话切换传空数组清除。
export async function imSetBridged(channels: string[]): Promise<void> {
  return invoke<void>('im_set_bridged', { channels });
}

// 后端 im_status 无参数，返回 Vec<ImChannelStatus>（{ channelId, connected, lastError?, cooldownUntil?, backendAutoReply? }）。
export async function imStatus(): Promise<ImChannelStatus[]> {
  return invoke<ImChannelStatus[]>('im_status');
}

// 后端 im_config_get 无参数，返回完整 ImConfigSnapshot。key 保留在 invoke 对象中以维持函数签名，后端 serde 忽略未知字段。
export async function imConfigGet(key: string): Promise<any> {
  return invoke('im_config_get', { key });
}

// 后端 im_config_set 期望 entry: ImChannelEntry（完整渠道对象）；value 作为 entry，key 保留以维持函数签名（后端忽略）。
export async function imConfigSet(key: string, value: any): Promise<void> {
  return invoke<void>('im_config_set', { key, entry: value });
}

// ── 类型化便捷封装（新组件优先使用这两个）──
export interface ImChannelEntry {
  id: string;
  name: string;
  provider: { type: string; endpoint?: string; secret?: string; url?: string };
  metadata?: Record<string, unknown>;
  enabled: boolean;
  /** 后端自动回复开关（默认 true）。开启时入站消息由 Rust 后端直接调 LLM 回发。 */
  autoReply?: boolean;
}

/** im_status 返回的单渠道状态。 */
export interface ImChannelStatus {
  channelId: string;
  connected: boolean;
  lastError?: string | null;
  cooldownUntil?: number | null;
  /** 后端自动回复开关：true 时前端应跳过自己的 LLM 自动回复，避免双回复。 */
  backendAutoReply?: boolean;
}
export interface ImConfigSnapshot {
  channels: ImChannelEntry[];
}

/** 获取完整 IM 配置快照（后端无参数）。 */
export async function imConfigGetSnapshot(): Promise<ImConfigSnapshot> {
  return invoke<ImConfigSnapshot>('im_config_get');
}

/** 保存/更新单个渠道，返回更新后的配置快照。 */
export async function imConfigSetEntry(entry: ImChannelEntry): Promise<ImConfigSnapshot> {
  return invoke<ImConfigSnapshot>('im_config_set', { entry });
}

// 后端 im_channels 返回当前已绑定的运行时渠道列表（Vec<IMBinding>）。
export async function imConfigList(): Promise<any> {
  return invoke('im_channels');
}

// 后端 im_config_remove 期望 (id)；返回更新后的 ImConfigSnapshot。
export async function imConfigRemove(id: string): Promise<any> {
  return invoke('im_config_remove', { id });
}

// 后端 im_send 期望 (channel_id, target, content)；直接传三个字符串参数。
export async function imSendRaw(channelId: string, target: string, content: string): Promise<string> {
  return invoke<string>('im_send', { channelId, target, content });
}

export interface SkillParamFieldInfo {
  name: string;
  type: 'string' | 'number' | 'boolean';
  description?: string;
  enumValues?: string[];
  currentValue?: unknown;
}

/** 发送技能参数确认消息到 IM 渠道。返回发送结果。 */
export async function imSendSkillParams(
  channelId: string,
  target: string,
  skillName: string,
  skillDescription: string,
  fields: SkillParamFieldInfo[],
  correlationId: string,
): Promise<string> {
  return invoke<string>('im_send_skill_params', {
    channelId,
    target,
    skillName,
    skillDescription,
    fields,
    correlationId,
  });
}

// ── im_bridge MCP server 命令封装 ──
export async function imBridgeListPending(): Promise<any[]> {
  return invoke('im_bridge_list_pending');
}
export async function imBridgeConfirm(channelId: string): Promise<any> {
  return invoke('im_bridge_confirm', { channelId });
}
export async function imBridgeRevoke(channelId: string): Promise<any> {
  return invoke('im_bridge_revoke', { channelId });
}
export async function imBridgeAudit(): Promise<any[]> {
  return invoke('im_bridge_audit');
}

/**
 * 订阅 IM 适配器入站事件流，返回取消订阅函数。
 *
 * 后端 im_config.rs::spawn_inbound_forwarder 通过 app.emit("im_adapter_event", ev)
 * 推送 IMAdapterEvent（{ binding_id, kind, payload, ts }）。
 * kind 常见取值由各 IMAdapter 实现决定（如 "message" / "status" / "error"）。
 */
export function imSubscribe(handler: (event: ImAdapterEvent) => void): () => void {
  return subscribe<ImAdapterEvent>('im_adapter_event', handler);
}

// ── IM 扫码 OAuth（飞书 / Lark）───────────────────────────────
// 后端命令实现在 src-tauri/src/commands/im_oauth.rs。URL 全部从
// im_endpoints::ImChannelKind::feishu_oauth_url() 硬编码而来，前端不
// 也不允许传 URL。
export type FeishuOAuthDomain = 'feishu' | 'feishu_lark';

export interface FeishuOAuthBeginResult {
  flowId: string;
  platform: string;
  status: string;            // 'pending' | 'scanned' | 'completed' | 'expired'
  qrUrl?: string | null;
  scanData?: string | null;
  userCode?: string | null;
  intervalSeconds: number;
  expiresAtMs: number;
  message?: string | null;
}

export interface FeishuOAuthPollResult {
  flowId: string;
  platform: string;
  /** OAuth device flow 状态机:pending → scanned → pending_admin_approval → completed(或 expired/denied/slow_down/error) */
  status: string;
  appId?: string | null;
  appSecret?: string | null;
  openId?: string | null;
  /** completed 时返回的随机 nonce,用于前端反劫持二次确认(与本机标识比对)。 */
  initiatorAnchor?: string | null;
  error?: string | null;
  message?: string | null;
}

/** 开始飞书 / Lark OAuth 扫码。返回 QR URL 给前端渲染。 */
export async function imOAuthBeginFeishu(domain: FeishuOAuthDomain): Promise<FeishuOAuthBeginResult> {
  return invoke<FeishuOAuthBeginResult>('im_oauth_begin_feishu', { domain });
}

/** 轮询飞书 / Lark OAuth 扫码状态。completed 时返回 appId + appSecret。 */
export async function imOAuthPollFeishu(flowId: string): Promise<FeishuOAuthPollResult> {
  return invoke<FeishuOAuthPollResult>('im_oauth_poll_feishu', { flowId });
}

/** 取消正在进行的 OAuth 流程。 */
export async function imOAuthCancelFeishu(flowId: string): Promise<boolean> {
  return invoke<boolean>('im_oauth_cancel_feishu', { flowId });
}

// ── IM 通用扫码登录（微信 iLink / QQ Bot / 企微）─────────────────
// 后端命令实现在 src-tauri/src/commands/im_qr_login.rs。URL 全部从
// im_endpoints.rs 硬编码，前端不也不允许传 URL。

export type QrLoginPlatform = 'weixin' | 'qqbot' | 'wecom';

export interface QrBeginResult {
  flowId: string;
  platform: string;
  status: string;            // 'pending'
  /** QR 图片 URL 或 base64 data URL（前端直接渲染） */
  qrImage: string;
  /** 扫码内容字符串（可选，用于 QRCodeSVG 生成） */
  qrData?: string | null;
  expiresAtMs: number;
  message?: string | null;
}

export interface QrPollResult {
  flowId: string;
  platform: string;
  /** 'pending' | 'scanned' | 'completed' | 'expired' | 'error' */
  status: string;
  /** completed 时返回的凭据 */
  token?: string | null;
  botId?: string | null;
  baseUrl?: string | null;
  /** QR 刷新后的新图片（expired 时可能返回） */
  qrImage?: string | null;
  error?: string | null;
  message?: string | null;
  /** completed 时返回的随机 nonce，用于前端反劫持二次确认（与 Feishu OAuth 对齐）。 */
  initiatorAnchor?: string | null;
}

/** 开始 IM 扫码登录。platform: 'weixin' | 'qqbot' | 'wecom' */
export async function imQrBegin(platform: QrLoginPlatform): Promise<QrBeginResult> {
  return invoke<QrBeginResult>('im_qr_begin', { platform });
}

/** 轮询 IM 扫码状态。completed 时返回 token/botId。 */
export async function imQrPoll(flowId: string): Promise<QrPollResult> {
  return invoke<QrPollResult>('im_qr_poll', { flowId });
}

/** 取消正在进行的扫码流程。 */
export async function imQrCancel(flowId: string): Promise<boolean> {
  return invoke<boolean>('im_qr_cancel', { flowId });
}

// ── IM 对象选择（好友/群组/文档列表）────────────────────────
// 后端命令实现在 src-tauri/src/commands/im_targets.rs。

export type ImTargetType = 'friend' | 'chat' | 'group' | 'doc';

export interface ImTargetItem {
  id: string;
  name: string;
  /** 'friend' | 'chat' | 'group' | 'doc' */
  type: string;
  avatar?: string | null;
  description?: string | null;
  memberCount?: number | null;
}

export interface ImTargetList {
  items: ImTargetItem[];
  /** 'ok' | 'needs_auth' | 'not_connected' | 'error' | 'unsupported' */
  status: string;
  message?: string | null;
}

/**
 * 列出指定渠道下可发送的目标对象（好友/群组/文档）。
 *
 * - 返回 status === 'needs_auth' 时前端应展示授权按钮，调用对应渠道的 OAuth/扫码授权流程。
 * - 返回 status === 'not_connected' 时前端引导用户先连接渠道。
 * - 企微群聊不支持 API 列举，返回空列表 + 提示手动输入 chatid。
 */
export async function imListTargets(
  channelId: string,
  targetType: ImTargetType = 'chat',
  query?: string,
): Promise<ImTargetList> {
  return invoke<ImTargetList>('im_list_targets', { channelId, targetType, query });
}
