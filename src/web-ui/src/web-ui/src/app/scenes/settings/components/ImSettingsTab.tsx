/**
 * ImSettingsTab — IM 渠道配置面板（纯客户端，无中转）。
 *
 * 【铁律】tupAI 是独立的 IM 长连接客户端：
 *   1. WS 长连接：客户端 ↔ IM 服务器，零中转、零代理、零后端服务器
 *   2. 配置：用户在表单里填 endpoint (wss://) + secret/token，本地保存
 *   3. 扫码：不需要后端 — 客户端自己生成/解析二维码（依赖 @im/sdk 提供的
 *      本地扫码能力；如果用户配置了第三方长连接网关，网关会自己处理扫码）
 *
 * 删除历史（2026-07-12）：
 *   - 删除 im_bridge 面板（待确认队列 + 审计日志）
 *   - 删除测试发送输入框
 *   - 删除 CHANNEL_FIELDS 平台特定参数
 *   - 删除后端 get_*_qrcode / check_*_qrcode_status 7 个命令（1057 行 channels.rs）
 *     扫码不再走后端
 *   - 删除前端对应的 7 个 API 封装
 *   - 全部渠道改为统一表单：endpoint + secret
 */

import { useCallback, useEffect, useRef, useState, type FC } from 'react';
import { useTranslation } from 'react-i18next';
import { QRCodeSVG } from 'qrcode.react';
import { Button, Input } from '@/component-library';
import {
  ConfigPageContent,
  ConfigPageHeader,
  ConfigPageLayout,
  ConfigPageRow,
  ConfigPageSection,
} from '@/infrastructure/config/components/common';
import {
  imConfigGetSnapshot,
  imConfigSetEntry,
  imConfigRemove,
  imConnect,
  imOAuthBeginFeishu,
  imOAuthPollFeishu,
  imOAuthCancelFeishu,
  imQrBegin,
  imQrPoll,
  imQrCancel,
  type FeishuOAuthBeginResult,
  type QrBeginResult,
  type QrLoginPlatform,
  type ImChannelEntry,
} from '@/infrastructure/api/tupai';
import { notificationService } from '@/shared/notification-system';
import { confirmDanger } from '@/component-library/components/ConfirmDialog/confirmService';
import { useSettingsStore } from '../settingsStore';
import { useSceneStore } from '@/app/stores/sceneStore';
import './ImSettingsTab.scss';

/** sessionStorage key：扫码成功后标记"刚连接"的渠道 id，用于列表高亮。 */
const JUST_CONNECTED_KEY = 'tupai:im:justConnected';
/** sessionStorage key：跳转到对话时预选的渠道 id。 */
const SELECTED_CHANNEL_KEY = 'tupai:im:selectedChannel';

// ==================== tab → 渠道类型映射 ====================

const IM_TAB_TO_TYPE: Record<string, string> = {
  'im-wecom-bot': 'wecom',
  'im-feishu': 'feishu',
  'im-dingtalk': 'dingtalk',
  'im-long-conn': 'long_conn',
  'im-weixin': 'weixin',
  'im-qqbot': 'qqbot',
  'im-whatsapp': 'whatsapp',
  'im-telegram': 'telegram',
};

function channelMatchesTab(ch: ImChannelEntry, tabId: string): boolean {
  const { type } = parseProvider(ch.provider);
  switch (tabId) {
    case 'im-wecom-bot': return type === 'wecom';
    case 'im-long-conn': return type === 'long_conn' || type === 'web_socket' || type === 'websocket';
    case 'im-feishu': return type === 'feishu' || type === 'feishu_lark' || type === 'lark';
    case 'im-dingtalk': return type === 'dingtalk';
    case 'im-weixin': return type === 'weixin';
    case 'im-qqbot': return type === 'qqbot';
    case 'im-whatsapp': return type === 'whatsapp';
    case 'im-telegram': return type === 'telegram';
    default: return false;
  }
}

// ==================== 渠道类型定义 ====================

interface ChannelTypeDef {
  id: string;
  icon: string;
  /** 硬编码的官方直连 WSS URL（仅 feishu/feishu_lark/dingtalk 有，其他为 undefined）。 */
  hardcodedEndpoint?: string;
  /** 表单 hint，告知用户 endpoint 是什么格式的 URL（仅在没有 hardcodedEndpoint 时显示）。 */
  endpointHint: string;
  /** secret 字段说明，告诉用户填什么。 */
  credentialHint: string;
}

// 【铁律】抄自 openclaw 官方直连 URL：
//   - 飞书官方事件订阅：https://open.feishu.cn/open-apis/im/v1
//   - 钉钉 Stream 模式：https://wss-open-connection.dingtalk.com/connect
// 全部 IM 直连地址写死在代码里，**不允许用户填、也不允许前端拼**。
const CHANNEL_TYPES: ChannelTypeDef[] = [
  {
    id: 'feishu',
    icon: '🐦',
    hardcodedEndpoint: 'wss://open.feishu.cn/open-apis/im/v1',
    endpointHint: '',
    credentialHint: '飞书 App ID (cli_xxx) + App Secret',
  },
  {
    id: 'feishu_lark',
    icon: '🌐',
    hardcodedEndpoint: 'wss://open.larksuite.com/open-apis/im/v1',
    endpointHint: '',
    credentialHint: 'Lark App ID + App Secret',
  },
  {
    id: 'dingtalk',
    icon: '📌',
    hardcodedEndpoint: 'wss://wss-open-connection.dingtalk.com/connect',
    endpointHint: '',
    credentialHint: '钉钉 ClientID (dingXXX) + ClientSecret',
  },
  {
    id: 'wecom',
    icon: '🤖',
    // 企业微信智能机器人扫码绑定（aibot_subscribe 协议）。
    // 【铁律】endpoint 写死为 wss://openws.work.weixin.qq.com，与后端 im_endpoints.rs 对齐。
    // BotID+Secret 由扫码自动获取（抄自 @wecom/wecom-openclaw-cli），无需手填。
    hardcodedEndpoint: 'wss://openws.work.weixin.qq.com',
    endpointHint: '企微扫码绑定后自动连接（aibot_subscribe 协议）',
    credentialHint: '扫码后自动获取 BotID + Secret',
  },
  {
    id: 'long_conn',
    icon: '🔗',
    endpointHint: 'wss:// 用户自建的 IM 长连接网关（必须自己部署）',
    credentialHint: '网关鉴权 Token',
  },
  {
    id: 'weixin',
    icon: '💚',
    endpointHint: 'wss:// 用户自建的微信协议网关（兼容 openclaw iLink）',
    credentialHint: 'iLink / ClawBot 协议凭据',
  },
  {
    id: 'qqbot',
    icon: '🐧',
    endpointHint: 'wss:// 用户自建的 QQ Bot 网关（兼容 openclaw）',
    credentialHint: 'QQ Bot AppID + ClientSecret',
  },
  {
    id: 'whatsapp',
    icon: '📱',
    endpointHint: 'wss:// 用户自建的 WhatsApp Business 网关',
    credentialHint: 'WhatsApp Business API Token',
  },
  {
    id: 'telegram',
    icon: '✈️',
    hardcodedEndpoint: 'https://api.telegram.org',
    endpointHint: '',
    credentialHint: 'Telegram Bot Token（从 @BotFather 获取）',
  },
];

// ==================== 扫码渠道手动凭据字段 ====================
// 【背景】飞书 OAuth / 企微 aibot_subscribe / 微信 iLink / QQ Bot 扫码
// 只能新建机器人，无法列出用户历史创建过的机器人（无官方接口）。因此为
// 全部扫码渠道提供"手动输入凭据"的替代绑定方式，字段与后端 adapter 的
// metadata 读取逻辑对齐（feishu_adapter / wecom_adapter / LongConnAdapter）。

interface ManualFieldDef {
  key: 'app_id' | 'app_secret' | 'bot_id' | 'bot_secret' | 'token';
  label: string;
  secret: boolean;
  placeholder?: string;
}

function manualFieldsFor(typeId: string): ManualFieldDef[] {
  switch (typeId) {
    case 'feishu':
    case 'feishu_lark':
      return [
        { key: 'app_id', label: 'App ID (cli_xxx)', secret: false, placeholder: 'cli_xxx' },
        { key: 'app_secret', label: 'App Secret', secret: true },
      ];
    case 'wecom':
      return [
        { key: 'bot_id', label: 'Bot ID', secret: false },
        { key: 'bot_secret', label: 'Bot Secret', secret: true },
      ];
    case 'weixin':
      return [
        { key: 'token', label: 'Bot Token (iLink)', secret: true },
      ];
    case 'qqbot':
      return [
        { key: 'token', label: 'Bot Token / AppID', secret: true },
      ];
    default:
      return [];
  }
}

// ==================== 辅助函数 ====================

function typeLabel(typeId: string, t: (key: string, options?: Record<string, unknown>) => string): string {
  return t(`imSettings.channelTypes.${typeId}`, { defaultValue: typeId });
}

function typeIcon(typeId: string): string {
  const t = CHANNEL_TYPES.find((c) => c.id === typeId);
  if (!t) {
    if (typeId === 'wecom') return '🤖';
    return '🔗';
  }
  return t.icon;
}

/** 该渠道类型是否为硬编码 endpoint(飞书/钉钉等官方直连)。 */
function isHardcodedType(typeId: string): boolean {
  const t = CHANNEL_TYPES.find((c) => c.id === typeId);
  return !!t?.hardcodedEndpoint;
}

interface ParsedProvider {
  type: string;
  endpoint: string;
  secret: string;
}

function parseProvider(provider: unknown): ParsedProvider {
  if (!provider || typeof provider !== 'object') {
    return { type: 'long_conn', endpoint: '', secret: '' };
  }
  const p = provider as Record<string, unknown>;
  const type = (typeof p.type === 'string' && p.type) || 'long_conn';
  const endpoint = (typeof p.endpoint === 'string' && p.endpoint) || (typeof p.url === 'string' && p.url) || '';
  const secret = (typeof p.secret === 'string' && p.secret) || '';
  return { type, endpoint, secret };
}

function buildProvider(type: string, relaySecret: string): ImChannelEntry['provider'] {
  // 【铁律】前端不收 endpoint。后端 im_config_set 会用
  // ImChannelKind::hardcoded_endpoint() 强制覆盖为官方直连 URL。
  if (type === 'web_socket' || type === 'websocket') {
    // 兼容旧数据：仅作为空壳占位
    return { type: 'web_socket', url: '' };
  }
  const provider: ImChannelEntry['provider'] = { type, endpoint: '' };
  if (relaySecret && relaySecret.trim()) {
    provider.secret = relaySecret.trim();
  }
  return provider;
}

function readErrorMessage(err: unknown): string {
  if (err && typeof err === 'object' && 'message' in err) {
    const m = (err as { message?: unknown }).message;
    if (typeof m === 'string' && m) return m;
  }
  if (typeof err === 'string' && err) return err;
  return 'Operation failed';
}

/** 把 OAuth begin 的 platform 字段还原为 channelType。 */
function domainFromFlow(flow: FeishuOAuthBeginResult): string {
  // 后端固定返回 "feishu" (国内/国际都用这个 platform 标签)
  // 但 begin 输入时按 UI 当前 tab 区分 feishu / feishu_lark
  if (flow.platform === 'feishu_lark') return 'feishu_lark';
  return 'feishu';
}

// ==================== 主组件 ====================

const ImSettingsTab: FC = () => {
  const { t } = useTranslation('common');
  const activeTab = useSettingsStore((s) => s.activeTab);
  const channelType = IM_TAB_TO_TYPE[activeTab as string] || 'long_conn';
  const channelTypeDef = CHANNEL_TYPES.find((c) => c.id === channelType);

  // 渠道列表
  const [channels, setChannels] = useState<ImChannelEntry[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [errorMsg, setErrorMsg] = useState('');

  // 编辑表单
  const [editId, setEditId] = useState('');
  const [editName, setEditName] = useState('');
  const [editType, setEditType] = useState(channelType);
  const [editRelaySecret, setEditRelaySecret] = useState('');
  const [editEnabled, setEditEnabled] = useState(true);
  const [editMode, setEditMode] = useState(false);
  const [saving, setSaving] = useState(false);
  // 编辑时保留原渠道 metadata(扫码渠道的 app_id/app_secret/open_id 不能丢)
  const [editMetadata, setEditMetadata] = useState<Record<string, unknown>>({});
  // 后端自动回复开关（默认开）。编辑渠道时保留原值，避免被默认值覆盖。
  const [editAutoReply, setEditAutoReply] = useState(true);

  // 启停切换防重复
  const [togglingId, setTogglingId] = useState<string | null>(null);

  // 刚连接的渠道 id（保存成功后高亮）
  const [justConnectedId, setJustConnectedId] = useState<string>('');

  // 凭据失效的渠道 id（聊天页因 token 失效跳过来时高亮）
  const [reauthChannelId, setReauthChannelId] = useState<string>('');

  // 飞书 / Lark 扫码 OAuth 弹窗状态
  // 【铁律】扫码完成后 oauthFlow 立即置 null（QR 数据不缓存、不展示），
  // 弹窗用 oauthModalOpen 独立控制，保留 1.5s 显示"成功"提示后自动关闭。
  // 下次点"扫码绑定"重新调 imOAuthBeginFeishu 拿新 QR。
  const [oauthModalOpen, setOauthModalOpen] = useState(false);
  const [oauthFlow, setOauthFlow] = useState<FeishuOAuthBeginResult | null>(null);
  const [oauthStatus, setOauthStatus] = useState<string>('');  // pending|scanned|completed|expired|error|denied|slow_down
  const [oauthError, setOauthError] = useState<string>('');
  const [oauthPolling, setOauthPolling] = useState(false);
  // 保存最后一个 flowId 用于关闭时 cancel（不依赖 oauthFlow 是否还在）
  const [oauthLastFlowId, setOauthLastFlowId] = useState<string>('');

  // ── 轮询控制 refs（避免 useEffect 依赖 oauthStatus 导致重建 timer 丢失 slow_down 间隔）──
  // 当前轮询间隔（毫秒）。slow_down 时 +5000，最大 60000。参考 lark-cli device_flow.go。
  const intervalMsRef = useRef<number>(5000);
  // 连续网络错误计数。参考 lark-cli:网络错误 continue + 退避,连续 3 次才报 error。
  const consecutiveErrorsRef = useRef<number>(0);
  // QR 过期倒计时（秒）。参考 oapi-sdk-go QRCodeInfo.ExpireIn。
  const [oauthCountdown, setOauthCountdown] = useState<number>(0);

  // 反劫持二次确认状态：扫码完成后不直接落库，先弹阻断式确认让用户核对 open_id
  // 参考 OpenClaw 实践：扫码/授权后必须比对 open_id 与发起者 ID，防止群聊中他人误点链接劫持会话
  const [oauthConfirm, setOauthConfirm] = useState<{
    appId: string;
    appSecret: string;
    openId: string;
    anchor: string;
    domain: string;
  } | null>(null);

  // ── 通用 QR 扫码状态 (微信 iLink / QQ Bot) ──
  const [qrModalOpen, setQrModalOpen] = useState(false);
  const [qrFlow, setQrFlow] = useState<QrBeginResult | null>(null);
  const [qrStatus, setQrStatus] = useState<string>('');
  const [qrError, setQrError] = useState<string>('');
  const [qrPolling, setQrPolling] = useState(false);
  const [qrLastFlowId, setQrLastFlowId] = useState<string>('');
  const [qrImage, setQrImage] = useState<string>('');
  const qrIntervalRef = useRef<number>(3000);
  const qrConsecutiveErrorsRef = useRef<number>(0);
  const [qrCountdown, setQrCountdown] = useState<number>(0);
  // 【反劫持】二维码流程：扫描完成 → 自动落库，**不再**弹阻断式确认弹窗。
  // 反劫持保护由后端 initiator_anchor + 短时窗口保证（详见 im_qr_login.rs）。
  // 真实绑库动作在 commitQrBind 内完成。

  // ── 手动输入凭据模式（扫码渠道的替代绑定方式）──
  // 扫码只能创建新机器人，无法列出历史机器人（无官方接口），
  // 因此为 feishu/feishu_lark/wecom/weixin/qqbot 提供手动填凭据入口。
  const [manualBindMode, setManualBindMode] = useState(false);
  // 手动表单各字段值（key 见 ManualFieldDef）
  const [manualValues, setManualValues] = useState<Record<string, string>>({});

  // ── 加载渠道列表 ──
  const loadChannels = useCallback(async () => {
    setLoading(true);
    setErrorMsg('');
    try {
      const config = await imConfigGetSnapshot();
      setChannels(config?.channels || []);
    } catch (e) {
      setErrorMsg(readErrorMessage(e));
      setChannels([]);
    }
    setLoading(false);
  }, []);

  useEffect(() => {
    if (channels === null) {
      void loadChannels();
    }
  }, [channels, loadChannels]);

  // 挂载时读取 sessionStorage 的 justConnected / reauthChannel 标记
  useEffect(() => {
    try {
      const id = sessionStorage.getItem(JUST_CONNECTED_KEY);
      if (id) {
        setJustConnectedId(id);
        sessionStorage.removeItem(JUST_CONNECTED_KEY);
      }
      // 聊天页因 token 失效跳过来时，记下要聚焦的 channel id。
      // 这里只用作视觉提示（红色高亮"需要重新认证"），不强改表单。
      const reauth = sessionStorage.getItem('tupai:im:reauthChannel');
      if (reauth) {
        setReauthChannelId(reauth);
        sessionStorage.removeItem('tupai:im:reauthChannel');
        notificationService.warning(
          t('imSettings.reauthRequired', { channelId: reauth }),
        );
      }
    } catch { /* ignore */ }
  }, [t]);

  // ── 表单重置/编辑 ──
  const resetForm = useCallback(() => {
    setEditId('');
    setEditName('');
    setEditType(channelType);
    setEditRelaySecret('');
    setEditEnabled(true);
    setEditMode(false);
    setEditMetadata({});
    setEditAutoReply(true);
  }, [channelType]);

  // 切换 tab 时重置表单 + 清除错误/高亮状态,避免上一 tab 的编辑态污染新 tab。
  // 参考 GitHub CLI repo switch:切换上下文时清空临时状态。
  useEffect(() => {
    resetForm();
    setErrorMsg('');
    setReauthChannelId('');
    setManualBindMode(false);
    setManualValues({});
  }, [activeTab, resetForm]);

  const startEdit = useCallback(
    (ch: ImChannelEntry) => {
      const { type, secret } = parseProvider(ch.provider);
      setEditId(ch.id);
      setEditName(ch.name || '');
      setEditType(type);
      // 【铁律】前端不显示/不编辑 endpoint；后端强制用 hardcoded_endpoint
      setEditRelaySecret(secret);
      setEditEnabled(!!ch.enabled);
      setEditMode(true);
      // 保留原 autoReply 值（默认 true），避免编辑保存后误关/误开后端自动回复。
      setEditAutoReply(ch.autoReply !== false);
      // 保留原 metadata(扫码渠道的 app_id/app_secret/open_id 不能丢)
      setEditMetadata(ch.metadata || {});
    },
    [],
  );

  // ── 保存渠道 ──
  const handleSave = async () => {
    if (!editId.trim()) {
      setErrorMsg(t('imSettings.channelIdRequired'));
      return;
    }
    // 【铁律】不再校验 endpoint — 前端不知道也不应该知道 endpoint，
    // 后端 im_config_set 会用 hardcoded endpoint 覆盖。
    setSaving(true);
    setErrorMsg('');
    try {
      const entry: ImChannelEntry = {
        id: editId.trim(),
        name: editName.trim() || editId.trim(),
        provider: buildProvider(editType, editRelaySecret.trim()),
        // 编辑模式保留原 metadata(扫码渠道的 app_id/app_secret/open_id);
        // 新建模式用空 metadata
        metadata: editMode ? editMetadata : {},
        enabled: editEnabled,
        autoReply: editAutoReply,
      };
      const config = await imConfigSetEntry(entry);
      setChannels(config?.channels || []);
      notificationService.success(t('imSettings.channelSaved'));
      resetForm();
    } catch (e) {
      setErrorMsg(readErrorMessage(e));
    }
    setSaving(false);
  };

  // ── 手动输入凭据绑定（扫码渠道替代方式）──
  // 扫码只能创建新机器人，无法列出历史机器人，故允许用户直接粘贴已有凭据。
  // 各渠道 entry 结构与扫码落库一致（feishu 参考 confirmOauthBind，
  // wecom/weixin/qqbot 参考 commitQrBind），后端 adapter 直接读取 metadata。
  const handleManualBind = async () => {
    const fields = manualFieldsFor(channelType);
    const required = fields.filter((f) => !manualValues[f.key]?.trim());
    if (required.length > 0) {
      setErrorMsg(t('imSettings.missingField', { field: required[0].label }));
      return;
    }
    setSaving(true);
    setErrorMsg('');
    try {
      const values = manualValues;
      let entry: ImChannelEntry;
      if (channelType === 'feishu' || channelType === 'feishu_lark') {
        const appId = values.app_id.trim();
        const appSecret = values.app_secret.trim();
        entry = {
          id: `feishu-${appId.toLowerCase()}`,
          name: `${t(`imSettings.channelTypes.${channelType}`, { defaultValue: channelType === 'feishu_lark' ? 'Lark' : '飞书' })} ${appId}`,
          provider: { type: channelType, endpoint: '', secret: `${appId}:${appSecret}` },
          metadata: { app_id: appId, app_secret: appSecret },
          enabled: true,
        };
      } else if (channelType === 'wecom') {
        const botId = values.bot_id.trim();
        const botSecret = values.bot_secret.trim();
        entry = {
          id: `wecom-${botId}`,
          name: `${t('imSettings.channelTypes.wecom', { defaultValue: '企微' })} ${botId}`,
          provider: { type: 'wecom', endpoint: '', secret: botSecret },
          metadata: { bot_id: botId, app_secret: botSecret },
          enabled: true,
        };
      } else {
        // weixin / qqbot：凭据即 token
        const token = values.token.trim();
        entry = {
          id: `${channelType}-${token.slice(0, 8)}`,
          name: `${t(`imSettings.channelTypes.${channelType}`, { defaultValue: channelType })} ${token.slice(0, 8)}`,
          provider: { type: channelType, endpoint: '', secret: token },
          metadata: { token },
          enabled: true,
        };
      }
      const snap = await imConfigSetEntry(entry);
      setChannels(snap?.channels || []);
      sessionStorage.setItem(JUST_CONNECTED_KEY, entry.id);
      setJustConnectedId(entry.id);
      notificationService.success(t('imSettings.oauthAutoConnected', { channelId: entry.id }));
      // 自动连接 + 跳转会话页（与扫码落库一致）
      try {
        await imConnect(entry.id);
      } catch (connErr) {
        console.warn('imConnect after manual bind failed (non-fatal):', connErr);
      }
      try {
        sessionStorage.setItem(SELECTED_CHANNEL_KEY, entry.id);
      } catch { /* ignore */ }
      setManualBindMode(false);
      setManualValues({});
      window.setTimeout(() => {
        useSceneStore.getState().openScene('session');
        notificationService.info(t('chatScene.imBridgeReady', { channelId: entry.id }));
      }, 1600);
    } catch (saveErr) {
      setErrorMsg(readErrorMessage(saveErr));
    }
    setSaving(false);
  };

  // ── 删除渠道 ──
  const handleRemove = async (id: string) => {
    const confirmed = await confirmDanger(
      t('imSettings.deleteChannelConfirmTitle'),
      t('imSettings.deleteChannelConfirmMessage'),
    );
    if (!confirmed) return;
    setErrorMsg('');
    try {
      const config = await imConfigRemove(id);
      setChannels(config?.channels || []);
      notificationService.info(t('imSettings.channelDeleted'));
      if (editId === id) resetForm();
    } catch (e) {
      setErrorMsg(readErrorMessage(e));
    }
  };

  // ── 启停切换 ──
  const handleToggle = async (ch: ImChannelEntry) => {
    setErrorMsg('');
    setTogglingId(ch.id);
    try {
      const entry: ImChannelEntry = {
        id: ch.id,
        name: ch.name,
        provider: ch.provider,
        metadata: ch.metadata || {},
        enabled: !ch.enabled,
        autoReply: ch.autoReply !== false,
      };
      const config = await imConfigSetEntry(entry);
      setChannels(config?.channels || []);
      notificationService.info(ch.enabled ? t('imSettings.channelDisconnected') : t('imSettings.channelReconnected'));
    } catch (e) {
      setErrorMsg(readErrorMessage(e));
    } finally {
      setTogglingId(null);
    }
  };

  // ── 进入对话 ──
  // 跳到会话页：写 SELECTED_CHANNEL_KEY 让 TupaiChatScene.loadChannels 预选该渠道。
  // 扫码绑定后该渠道的入站消息会进入主会话（同步桥接），无需独立会话入口。
  const handleEnterChat = useCallback((channelId: string) => {
    try {
      sessionStorage.setItem(SELECTED_CHANNEL_KEY, channelId);
    } catch { /* ignore */ }
    useSceneStore.getState().openScene('session');
  }, []);

  // ── 飞书 / Lark 扫码 OAuth 流程 ──
  // 后端 URL 硬编码在 im_endpoints::ImChannelKind::feishu_oauth_url()，
  // 前端只传 domain 标志。每次点"扫码绑定"都重新 begin 拿新 QR，不缓存旧 QR。
  const startFeishuOAuth = useCallback(async (domain: 'feishu' | 'feishu_lark') => {
    // 先取消旧 flow（如果还在轮询），避免后端 flow 表泄漏
    if (oauthLastFlowId && oauthPolling) {
      try { await imOAuthCancelFeishu(oauthLastFlowId); } catch { /* ignore */ }
    }
    setOauthError('');
    setOauthStatus('pending');
    setOauthFlow(null);          // 清旧 QR（不缓存）
    setOauthPolling(false);
    setOauthModalOpen(true);     // 打开弹窗
    // 重置轮询控制 refs（参考 lark-cli device_flow.go: 每次新 flow 都从默认间隔开始）
    intervalMsRef.current = 5000;
    consecutiveErrorsRef.current = 0;
    setOauthCountdown(0);
    try {
      const begin = await imOAuthBeginFeishu(domain);
      setOauthFlow(begin);
      setOauthLastFlowId(begin.flowId);
      setOauthStatus(begin.status || 'pending');
      // 用 begin 返回的 interval 初始化（飞书实际给 5s，尊重服务端）
      intervalMsRef.current = Math.max(2000, (begin.intervalSeconds || 5) * 1000);
      // QR 倒计时（参考 oapi-sdk-go QRCodeInfo.ExpireIn）
      const expireSec = Math.max(0, Math.floor((begin.expiresAtMs - Date.now()) / 1000));
      setOauthCountdown(expireSec);
      // begin 成功后立即开始轮询（无需用户再点"开始轮询"按钮）
      setOauthPolling(true);
    } catch (e) {
      const msg = readErrorMessage(e);
      setOauthError(msg);
      setOauthStatus('error');
      // begin 失败 3s 后自动关闭弹窗（参考 GitHub CLI:错误后短暂展示再关闭）
      window.setTimeout(() => {
        setOauthModalOpen(false);
        setOauthStatus('');
        setOauthError('');
      }, 3000);
    }
  }, [oauthLastFlowId, oauthPolling]);

  // 轮询扫码结果 — 完成后立即调 im_config_set 创建渠道
  // 【BUGFIX】之前依赖 oauthStatus 导致 pending→scanned 状态变化时 useEffect 重建,
  // setInterval 重新创建,currentIntervalMs 重置,slow_down 加的 5s 间隔被丢弃。
  // 现在改用 setTimeout 递归 + intervalMsRef,依赖只留 [oauthFlow, oauthPolling]。
  // 网络错误处理抄自 lark-cli device_flow.go: 连续 3 次才报 error,否则继续轮询。
  useEffect(() => {
    if (!oauthFlow || !oauthPolling) return;

    let cancelled = false;
    let timer: number | undefined;

    const tick = async () => {
      if (cancelled) return;
      // 本地硬截止：后端因 bug 一直返回 pending 时，前端用 expiresAtMs 兜底
      if (oauthFlow && Date.now() >= oauthFlow.expiresAtMs) {
        setOauthStatus('expired');
        setOauthError(t('imSettings.oauthLocalTimeout'));
        setOauthFlow(null);
        setOauthPolling(false);
        setOauthCountdown(0);
        return;
      }
      try {
        const poll = await imOAuthPollFeishu(oauthFlow.flowId);
        if (cancelled) return;
        // 成功响应,重置连续错误计数
        consecutiveErrorsRef.current = 0;
        setOauthStatus(poll.status);
        if (poll.status === 'completed') {
          setOauthPolling(false);
          // 【铁律】扫码完成立即清空 QR 数据（不缓存、不展示）
          setOauthFlow(null);
          setOauthCountdown(0);
          // 反劫持：不直接落库，先弹阻断式二次确认让用户核对 open_id 归属本机
          // 参考 OpenClaw 实践：扫码人必须明确确认"这个扫码人是我自己"才落库
          if (poll.appId && poll.appSecret) {
            setOauthConfirm({
              appId: poll.appId,
              appSecret: poll.appSecret,
              openId: poll.openId || '',
              anchor: poll.initiatorAnchor || '',
              domain: domainFromFlow(oauthFlow),
            });
            // 暂不进入 'completed' 状态（避免触发 1.5s 自动关闭 + 绿勾），等用户确认后再落库
            setOauthStatus('confirming');
          } else {
            setOauthError(t('imSettings.oauthCompletedNoCreds'));
            setOauthStatus('error');
          }
          return;  // 终态,不再调度
        } else if (poll.status === 'pending_admin_approval') {
          // 租户管理员审批中，不停止轮询，继续等待审批结果
        } else if (poll.status === 'expired' || poll.status === 'error' || poll.status === 'denied') {
          setOauthError(poll.error || poll.message || 'oauth flow failed');
          setOauthPolling(false);
          setOauthFlow(null);
          setOauthCountdown(0);
          return;  // 终态,不再调度
        } else if (poll.status === 'slow_down') {
          // 抄自 lark-cli device_flow.go: interval = min(interval+5, 60)
          intervalMsRef.current = Math.min(intervalMsRef.current + 5000, 60000);
        }
        // pending / scanned / slow_down: 继续调度下一次 tick
      } catch (e) {
        if (cancelled) return;
        // 网络错误:连续 3 次才报 error,否则继续轮询(参考 lark-cli continue + 退避)
        consecutiveErrorsRef.current += 1;
        if (consecutiveErrorsRef.current >= 3) {
          setOauthError(readErrorMessage(e));
          setOauthStatus('error');
          setOauthPolling(false);
          return;  // 不再调度
        }
        // 否则继续轮询(保持当前 status,不干扰用户)
      }
      // 用最新 intervalMsRef.current 调度(支持 slow_down 动态调整间隔)
      timer = window.setTimeout(tick, intervalMsRef.current);
    };
    // 立即 tick 一次
    void tick();
    return () => {
      cancelled = true;
      if (timer !== undefined) window.clearTimeout(timer);
    };
  }, [oauthFlow, oauthPolling, t]);

  // QR 过期倒计时:每秒更新(参考 oapi-sdk-go QRCodeInfo.ExpireIn)
  // 【BUGFIX】原依赖 [oauthFlow, oauthCountdown] 导致 oauthCountdown 每秒变化
  // 触发 useEffect 重建,interval 每秒被 clear+recreate。改为只依赖 [oauthFlow],
  // interval 内部用 functional updater 自然递减到 0。
  useEffect(() => {
    if (!oauthFlow) return;
    const timer = window.setInterval(() => {
      setOauthCountdown((prev) => (prev > 0 ? prev - 1 : 0));
    }, 1000);
    return () => window.clearInterval(timer);
  }, [oauthFlow]);

  // justConnectedId 自动清空:3s 后移除高亮(避免永久 is-just-connected)
  useEffect(() => {
    if (!justConnectedId) return;
    const timer = window.setTimeout(() => setJustConnectedId(''), 3000);
    return () => window.clearTimeout(timer);
  }, [justConnectedId]);

  // 弹窗自动关闭：completed 后 1.5s 关闭（QR 已清空，只显示成功提示）
  useEffect(() => {
    if (oauthStatus !== 'completed') return;
    const timer = window.setTimeout(() => {
      setOauthModalOpen(false);
      setOauthStatus('');
      setOauthError('');
      setOauthLastFlowId('');
    }, 1500);
    return () => window.clearTimeout(timer);
  }, [oauthStatus]);

  const closeOauthModal = useCallback(async () => {
    // 用户手动点 X 关闭：取消正在进行的 flow
    if (oauthLastFlowId && oauthPolling) {
      try { await imOAuthCancelFeishu(oauthLastFlowId); } catch { /* ignore */ }
    }
    setOauthModalOpen(false);
    setOauthFlow(null);
    setOauthStatus('');
    setOauthError('');
    setOauthPolling(false);
    setOauthLastFlowId('');
    setOauthCountdown(0);
    // 重置轮询控制 refs
    intervalMsRef.current = 5000;
    consecutiveErrorsRef.current = 0;
  }, [oauthLastFlowId, oauthPolling]);

  // ── 反劫持二次确认回调 ──
  // 用户在确认弹窗点"确认绑定"：落库 + 触发 completed 状态（1.5s 自动关闭 + 绿勾）
  const confirmOauthBind = useCallback(async () => {
    if (!oauthConfirm) return;
    const { appId, appSecret, openId, anchor, domain } = oauthConfirm;
    // anchor 仅用于确认视图展示，落库时不需要
    void anchor;
    const entry: ImChannelEntry = {
      id: `feishu-${appId.toLowerCase()}`,
      name: `${t(`imSettings.channelTypes.${domain}`, { defaultValue: domain === 'feishu_lark' ? 'Lark' : '飞书' })} ${appId}`,
      provider: {
        type: domain,
        endpoint: '',  // 后端强制覆盖为硬编码 URL
        secret: `${appId}:${appSecret}`,
      } as ImChannelEntry['provider'],
      metadata: {
        app_id: appId,
        app_secret: appSecret,
        open_id: openId,
      },
      enabled: true,
    };
    try {
      const snap = await imConfigSetEntry(entry);
      setChannels(snap?.channels || []);
      sessionStorage.setItem(JUST_CONNECTED_KEY, entry.id);
      setJustConnectedId(entry.id);
      notificationService.success(t('imSettings.oauthAutoConnected', { channelId: entry.id }));
      // ── 自动连接 IM 渠道 + 接通会话/work 队列 ──
      // 飞书 OAuth 扫码完成后同样需要自动连接 + 跳转会话页
      try {
        await imConnect(entry.id);
      } catch (connErr) {
        console.warn('imConnect after Feishu OAuth failed (non-fatal):', connErr);
      }
      try {
        sessionStorage.setItem(SELECTED_CHANNEL_KEY, entry.id);
      } catch { /* ignore */ }
      // 触发 1.5s 自动关闭 + 绿勾（completed 状态由 useEffect 监听）
      setOauthStatus('completed');
      // 1.6s 后自动跳转到会话页（该渠道的入站消息会进入主会话，同步桥接）
      window.setTimeout(() => {
        useSceneStore.getState().openScene('session');
        notificationService.info(t('chatScene.imBridgeReady', { channelId: entry.id }));
      }, 1600);
    } catch (saveErr) {
      setOauthError(readErrorMessage(saveErr));
      setOauthStatus('error');
    }
    setOauthConfirm(null);
  }, [oauthConfirm, t]);

  // 用户在确认弹窗点"取消"：取消后端 flow + 关闭弹窗
  const cancelOauthConfirm = useCallback(async () => {
    if (oauthConfirm?.anchor) {
      // 如果还有 flow 在后端，调 cancel（兜底，completed 时后端 flow 通常已结束）
      try { await imOAuthCancelFeishu(oauthLastFlowId); } catch { /* ignore */ }
    }
    setOauthConfirm(null);
    setOauthStatus('');
    setOauthError('');
    setOauthModalOpen(false);
    setOauthLastFlowId('');
  }, [oauthConfirm, oauthLastFlowId]);

  // 该渠道是否支持扫码自动连接（仅飞书 / Lark）。
  const supportsOAuth = channelType === 'feishu' || channelType === 'feishu_lark';
  // 该渠道是否支持通用 QR 扫码（微信 iLink / QQ Bot / 企微智能机器人）。
  const supportsQRScan = channelType === 'weixin' || channelType === 'qqbot' || channelType === 'wecom';

  // ── 通用 QR 扫码流程 (微信 iLink / QQ Bot) ──
  const startQRLogin = useCallback(async (platform: QrLoginPlatform) => {
    // 先取消旧 flow
    if (qrLastFlowId && qrPolling) {
      try { await imQrCancel(qrLastFlowId); } catch { /* ignore */ }
    }
    setQrError('');
    setQrStatus('pending');
    setQrFlow(null);
    setQrPolling(false);
    setQrModalOpen(true);
    setQrImage('');
    qrIntervalRef.current = 3000;
    qrConsecutiveErrorsRef.current = 0;
    setQrCountdown(0);
    try {
      const begin = await imQrBegin(platform);
      setQrFlow(begin);
      setQrLastFlowId(begin.flowId);
      setQrStatus(begin.status || 'pending');
      setQrImage(begin.qrImage || '');
      const expireSec = Math.max(0, Math.floor((begin.expiresAtMs - Date.now()) / 1000));
      setQrCountdown(expireSec);
      setQrPolling(true);
    } catch (e) {
      const msg = readErrorMessage(e);
      setQrError(msg);
      setQrStatus('error');
      window.setTimeout(() => {
        setQrModalOpen(false);
        setQrStatus('');
        setQrError('');
      }, 3000);
    }
  }, [qrLastFlowId, qrPolling]);

  // 扫码 completed 时由轮询 useEffect 直接调用，不再走手动确认弹窗。
  // 落库成功后弹"渠道已自动连接"通知 + 触发 1.5s 自动关闭弹窗。
  const commitQrBind = useCallback(
    async (bind: { platform: string; token: string; botId: string; baseUrl: string; anchor: string }) => {
      const { platform, token, botId, baseUrl } = bind;
      const entry: ImChannelEntry = {
        id: `${platform}-${botId || token.slice(0, 8)}`,
        name: `${t(`imSettings.channelTypes.${platform}`, { defaultValue: platform })} ${botId || token.slice(0, 8)}`,
        provider: {
          type: platform,
          endpoint: '',
          secret: token,
        } as ImChannelEntry['provider'],
        metadata: {
          token,
          bot_id: botId,
          base_url: baseUrl,
        },
        enabled: true,
      };
      try {
        const snap = await imConfigSetEntry(entry);
        setChannels(snap?.channels || []);
        sessionStorage.setItem(JUST_CONNECTED_KEY, entry.id);
        setJustConnectedId(entry.id);
        notificationService.success(
          t('imSettings.oauthAutoConnected', { channelId: entry.id }),
        );
        // ── 自动连接 IM 渠道 + 接通会话 ──
        // 扫码成功后不仅要落库，还要：
        //   1. 调 imConnect 建立长连接
        //   2. 自动跳转到 session 场景，该渠道的入站消息会进入主会话（同步桥接）
        try {
          await imConnect(entry.id);
        } catch (connErr) {
          // imConnect 失败不阻塞——后端 setup 阶段 init_im_channels 会重试
          console.warn('imConnect after QR bind failed (non-fatal):', connErr);
        }
        try {
          sessionStorage.setItem(SELECTED_CHANNEL_KEY, entry.id);
        } catch { /* ignore */ }
        // 1.5s 后自动关闭弹窗 + 跳转到会话页（让用户看到已连接的渠道）
        setQrStatus('completed');
        window.setTimeout(() => {
          useSceneStore.getState().openScene('session');
          notificationService.info(t('chatScene.imBridgeReady', { channelId: entry.id }));
        }, 1600);
      } catch (saveErr) {
        setQrError(readErrorMessage(saveErr));
        setQrStatus('error');
      }
    },
    [t],
  );

  // QR 轮询
  useEffect(() => {
    if (!qrFlow || !qrPolling) return;
    let cancelled = false;
    let timer: number | undefined;
    const tick = async () => {
      if (cancelled) return;
      try {
        const poll = await imQrPoll(qrFlow.flowId);
        if (cancelled) return;
        qrConsecutiveErrorsRef.current = 0;
        setQrStatus(poll.status);
        // expired 时可能返回新 QR 图片
        if (poll.qrImage) {
          setQrImage(poll.qrImage);
        }
        if (poll.status === 'completed') {
          setQrPolling(false);
          setQrFlow(null);
          setQrCountdown(0);
          // 【铁律】扫码成功后自动落库，不再弹反劫持二次确认弹窗。
          // 注入渠道的凭据（token / botId / baseUrl）由后端在 completed
          // 时一次性返回，前端无需再人工核对——这一步的"反劫持"保护
          // 已由后端 initiator_anchor + 短时窗口保证（详见 im_qr_login.rs）。
          const platform = poll.platform || channelType;
          const token = poll.token || '';
          const botId = poll.botId || '';
          if (!token) {
            setQrError(t('imSettings.qrCompletedNoToken'));
            setQrStatus('error');
            return;
          }
          setQrStatus('completed');
          // 异步落库，不阻塞当前 tick（避免长时间持锁）
          void commitQrBind({
            platform,
            token,
            botId,
            baseUrl: poll.baseUrl || '',
            anchor: poll.initiatorAnchor || '',
          });
          return;
        } else if (poll.status === 'expired' || poll.status === 'error') {
          if (poll.status === 'error') {
            setQrError(poll.error || poll.message || 'qr flow failed');
            setQrPolling(false);
            setQrFlow(null);
            setQrCountdown(0);
            return;
          }
          // expired: 如果有新 QR 图片，继续轮询；否则停止
          if (!poll.qrImage) {
            setQrError(poll.error || poll.message || 'qr expired');
            setQrPolling(false);
            setQrFlow(null);
            setQrCountdown(0);
            return;
          }
        }
      } catch (e) {
        if (cancelled) return;
        qrConsecutiveErrorsRef.current += 1;
        if (qrConsecutiveErrorsRef.current >= 3) {
          setQrError(readErrorMessage(e));
          setQrStatus('error');
          setQrPolling(false);
          return;
        }
      }
      timer = window.setTimeout(tick, qrIntervalRef.current);
    };
    void tick();
    return () => {
      cancelled = true;
      if (timer !== undefined) window.clearTimeout(timer);
    };
  }, [qrFlow, qrPolling, channelType, t, commitQrBind]);

  // QR 倒计时
  useEffect(() => {
    if (!qrFlow) return;
    const timer = window.setInterval(() => {
      setQrCountdown((prev) => (prev > 0 ? prev - 1 : 0));
    }, 1000);
    return () => window.clearInterval(timer);
  }, [qrFlow]);

  // QR 弹窗自动关闭
  useEffect(() => {
    if (qrStatus !== 'completed') return;
    const timer = window.setTimeout(() => {
      setQrModalOpen(false);
      setQrStatus('');
      setQrError('');
      setQrLastFlowId('');
      setQrImage('');
    }, 1500);
    return () => window.clearTimeout(timer);
  }, [qrStatus]);

  const closeQrModal = useCallback(async () => {
    if (qrLastFlowId && qrPolling) {
      try { await imQrCancel(qrLastFlowId); } catch { /* ignore */ }
    }
    setQrModalOpen(false);
    setQrFlow(null);
    setQrStatus('');
    setQrError('');
    setQrPolling(false);
    setQrLastFlowId('');
    setQrImage('');
    setQrCountdown(0);
    qrIntervalRef.current = 3000;
    qrConsecutiveErrorsRef.current = 0;
  }, [qrLastFlowId, qrPolling]);

  // ── 通用 QR 自动落库 ──
  const filteredChannels = (channels || []).filter((ch) => channelMatchesTab(ch, activeTab as string));

  // ── 手动输入凭据表单（扫码渠道替代绑定，复用给飞书 OAuth 与通用 QR）──
  const manualBindForm = (
    <div className="im-edit-form">
      {manualFieldsFor(channelType).map((field) => (
        <ConfigPageRow key={field.key} label={field.label} align="center">
          <Input
            type={field.secret ? 'password' : 'text'}
            placeholder={field.placeholder ?? field.label}
            value={manualValues[field.key] ?? ''}
            onChange={(e) => setManualValues((prev) => ({ ...prev, [field.key]: e.target.value }))}
            inputSize="medium"
          />
        </ConfigPageRow>
      ))}
      <div className="im-hint im-hint--muted">
        {t('imSettings.manualBindHint')}
      </div>
      <div className="im-form-actions">
        <Button
          variant="primary"
          onClick={() => void handleManualBind()}
          disabled={saving}
          isLoading={saving}
        >
          {t('imSettings.manualBindSubmit')}
        </Button>
        <Button variant="ghost" onClick={() => setManualBindMode(false)}>
          {t('imSettings.cancel')}
        </Button>
      </div>
    </div>
  );

  return (
    <ConfigPageLayout className="im-settings-tab">
      <ConfigPageHeader
        title={`${channelTypeDef?.icon ?? '🔗'} ${t(`imSettings.channelTypes.${channelType}`, { defaultValue: channelType })}`}
        subtitle={supportsOAuth || supportsQRScan
          ? t('imSettings.subtitleScan')
          : t('imSettings.subtitleConfig')
        }
      />
      <ConfigPageContent className="im-settings-tab__content">
        {/* ==================== 渠道列表 ==================== */}
        <ConfigPageSection
          title={t('imSettings.sectionChannels')}
          description={isHardcodedType(channelType)
            ? t('imSettings.sectionChannelsDescDirect')
            : t('imSettings.sectionChannelsDesc')
          }
          extra={
            <Button variant="ghost" size="small" onClick={() => void loadChannels()} disabled={loading}>
              {t('imSettings.refresh')}
            </Button>
          }
        >
          {loading && <div className="im-hint">{t('imSettings.loading')}</div>}
          {!loading && channels !== null && filteredChannels.length === 0 && (
            <div className="im-hint im-hint--muted">{t('imSettings.noChannels')}</div>
          )}
          {filteredChannels.length > 0 && (
            <div className="im-channel-list">
              {filteredChannels.map((ch) => {
                const { type, endpoint } = parseProvider(ch.provider);
                const isJustConnected = justConnectedId === ch.id;
                const isReauthNeeded = reauthChannelId === ch.id;
                return (
                  <div
                    key={ch.id}
                    className={[
                      'im-channel-card',
                      ch.enabled ? '' : 'is-disabled',
                      isJustConnected ? 'is-just-connected' : '',
                      isReauthNeeded ? 'is-reauth-needed' : '',
                    ].filter(Boolean).join(' ')}
                  >
                    <div className="im-channel-card__header">
                      <span className="im-channel-card__name">
                        <span className="im-channel-card__icon">{typeIcon(type)}</span>
                        {ch.name || ch.id}
                      </span>
                      {isJustConnected ? (
                        <span className="im-channel-card__badge is-just-connected">
                          {t('imSettings.justConnected')}
                        </span>
                      ) : isReauthNeeded ? (
                        <span className="im-channel-card__badge is-reauth">
                          {t('imSettings.reauth')}
                        </span>
                      ) : (
                        <span className={`im-channel-card__badge ${ch.enabled ? 'is-on' : 'is-off'}`}>
                          {ch.enabled ? 'ON' : 'OFF'}
                        </span>
                      )}
                    </div>
                    <div className="im-channel-card__meta">
                      <div className="im-channel-card__type-tag">{typeLabel(type, t)}</div>
                      <code className="im-channel-card__id">{ch.id}</code>
                      {isHardcodedType(type) ? (
                        <div className="im-channel-card__endpoint is-hardcoded">
                          {t('imSettings.officialDirectConnect')}
                        </div>
                      ) : endpoint ? (
                        <div className="im-channel-card__endpoint">{endpoint}</div>
                      ) : null}
                    </div>
                    <div className="im-channel-card__actions">
                      <Button
                        variant="primary"
                        size="small"
                        onClick={() => handleEnterChat(ch.id)}
                      >
                        {t('imSettings.enterChat')}
                      </Button>
                      <Button variant="secondary" size="small" onClick={() => startEdit(ch)}>
                        {t('imSettings.edit')}
                      </Button>
                      <Button
                        variant="ghost"
                        size="small"
                        onClick={() => void handleToggle(ch)}
                        disabled={togglingId === ch.id}
                        isLoading={togglingId === ch.id}
                      >
                        {ch.enabled ? t('imSettings.disconnect') : t('imSettings.reconnect')}
                      </Button>
                      <Button variant="danger" size="small" onClick={() => void handleRemove(ch.id)}>
                        {t('imSettings.delete')}
                      </Button>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </ConfigPageSection>

        {/* ==================== 添加/编辑 ==================== */}
        {supportsOAuth ? (
          /* ── 飞书 / Lark:扫码优先,不显示手动表单 ──
           * 【铁律】支持扫码的渠道不允许手动填凭据 — 所有参数由扫码自动获取。
           * 非编辑模式:大"扫码绑定"按钮 + 说明文字。
           * 编辑模式:只改名称/启停,凭据只读(扫码绑定后不可改)。 */
          <ConfigPageSection
            title={editMode ? t('imSettings.sectionEditChannel') : t('imSettings.sectionBindChannel')}
            description={editMode
              ? t('imSettings.sectionEditDescConfig')
              : manualBindMode
                ? t('imSettings.manualBindDesc')
                : t('imSettings.scanBindHint')
            }
          >
            {!editMode ? (
              <>
                <div className="im-scan-mode-toggle">
                  <button
                    type="button"
                    className={`im-scan-mode-toggle__btn ${!manualBindMode ? 'is-active' : ''}`}
                    onClick={() => setManualBindMode(false)}
                  >
                    {t('imSettings.scanBindToggle')}
                  </button>
                  <button
                    type="button"
                    className={`im-scan-mode-toggle__btn ${manualBindMode ? 'is-active' : ''}`}
                    onClick={() => setManualBindMode(true)}
                  >
                    {t('imSettings.manualBindToggle')}
                  </button>
                </div>
                {manualBindMode ? (
                  manualBindForm
                ) : (
                  <div className="im-scan-bind">
                    <button
                      className="im-scan-bind__btn"
                      onClick={() => {
                        const domain: 'feishu' | 'feishu_lark' =
                          channelType === 'feishu_lark' ? 'feishu_lark' : 'feishu';
                        void startFeishuOAuth(domain);
                      }}
                      disabled={oauthPolling}
                    >
                      <span className="im-scan-bind__icon">📱</span>
                      <span className="im-scan-bind__text">{t('imSettings.scanQRCode')}</span>
                    </button>
                    <div className="im-scan-bind__hint">
                      {t('imSettings.scanBindHint')}
                    </div>
                  </div>
                )}
              </>
            ) : (
              <div className="im-edit-form">
                <ConfigPageRow label={t('imSettings.channelName')} align="center">
                  <Input
                    type="text"
                    placeholder={t('imSettings.channelNamePlaceholder')}
                    value={editName}
                    onChange={(e) => setEditName(e.target.value)}
                    inputSize="medium"
                  />
                </ConfigPageRow>
                <ConfigPageRow label={t('imSettings.enable')} align="center">
                  <label className="im-checkbox">
                    <input
                      type="checkbox"
                      checked={editEnabled}
                      onChange={(e) => setEditEnabled(e.target.checked)}
                    />
                    <span>{t('imSettings.enableChannel')}</span>
                  </label>
                </ConfigPageRow>
                {/* 凭据由扫码自动管理,不提供修改入口 */}
                <div className="im-hint im-hint--muted">
                  {t('imSettings.credentialsManagedByOAuth')}
                </div>
                <div className="im-form-actions">
                  <Button
                    variant="primary"
                    onClick={() => void handleSave()}
                    disabled={saving}
                    isLoading={saving}
                  >
                    {t('imSettings.update')}
                  </Button>
                  <Button variant="ghost" onClick={resetForm}>
                    {t('imSettings.cancel')}
                  </Button>
                </div>
              </div>
            )}
            {errorMsg && <div className="im-error im-error--block">{errorMsg}</div>}
          </ConfigPageSection>
        ) : supportsQRScan ? (
          /* ── 微信 iLink / QQ Bot / 企微:通用 QR 扫码 ──
           * 扫码成功后自动落库，凭据由后端 QR 流程返回。
           * 非编辑模式:大“扫码绑定”按钮 + 说明文字。
           * 编辑模式:只改名称/启停。 */
          <ConfigPageSection
            title={editMode ? t('imSettings.sectionEditChannel') : t('imSettings.sectionBindChannel')}
            description={editMode
              ? t('imSettings.sectionEditDescConfig')
              : manualBindMode
                ? t('imSettings.manualBindDesc')
                : t('imSettings.qrScanBindHint')
            }
          >
            {!editMode ? (
              <>
                <div className="im-scan-mode-toggle">
                  <button
                    type="button"
                    className={`im-scan-mode-toggle__btn ${!manualBindMode ? 'is-active' : ''}`}
                    onClick={() => setManualBindMode(false)}
                  >
                    {t('imSettings.scanBindToggle')}
                  </button>
                  <button
                    type="button"
                    className={`im-scan-mode-toggle__btn ${manualBindMode ? 'is-active' : ''}`}
                    onClick={() => setManualBindMode(true)}
                  >
                    {t('imSettings.manualBindToggle')}
                  </button>
                </div>
                {manualBindMode ? (
                  manualBindForm
                ) : (
                  <div className="im-scan-bind">
                    <button
                      className="im-scan-bind__btn"
                      onClick={() => {
                        const platform = channelType;
                        void startQRLogin(platform as QrLoginPlatform);
                      }}
                      disabled={qrPolling}
                    >
                      <span className="im-scan-bind__icon">📱</span>
                      <span className="im-scan-bind__text">{t('imSettings.scanQRCode')}</span>
                    </button>
                    <div className="im-scan-bind__hint">
                      {t('imSettings.qrScanBindHint')}
                    </div>
                  </div>
                )}
              </>
            ) : (
              <div className="im-edit-form">
                <ConfigPageRow label={t('imSettings.channelName')} align="center">
                  <Input
                    type="text"
                    placeholder={t('imSettings.channelNamePlaceholder')}
                    value={editName}
                    onChange={(e) => setEditName(e.target.value)}
                    inputSize="medium"
                  />
                </ConfigPageRow>
                <ConfigPageRow label={t('imSettings.enable')} align="center">
                  <label className="im-checkbox">
                    <input
                      type="checkbox"
                      checked={editEnabled}
                      onChange={(e) => setEditEnabled(e.target.checked)}
                    />
                    <span>{t('imSettings.enableChannel')}</span>
                  </label>
                </ConfigPageRow>
                <div className="im-hint im-hint--muted">
                  {t('imSettings.credentialsManagedByOAuth')}
                </div>
                <div className="im-form-actions">
                  <Button
                    variant="primary"
                    onClick={() => void handleSave()}
                    disabled={saving}
                    isLoading={saving}
                  >
                    {t('imSettings.update')}
                  </Button>
                  <Button variant="ghost" onClick={resetForm}>
                    {t('imSettings.cancel')}
                  </Button>
                </div>
              </div>
            )}
            {errorMsg && <div className="im-error im-error--block">{errorMsg}</div>}
          </ConfigPageSection>
        ) : (
          /* ── 钉钉/WhatsApp/Telegram/long_conn:手动表单 ── */
          <ConfigPageSection
            title={editMode ? t('imSettings.sectionEditChannel') : t('imSettings.sectionAddChannel')}
            description={t('imSettings.sectionEditDescConfig')}
          >
            <div className="im-edit-form">
              <ConfigPageRow label={t('imSettings.channelId')} align="center">
                <Input
                  type="text"
                  placeholder={t('imSettings.channelIdPlaceholder')}
                  value={editId}
                  onChange={(e) => setEditId(e.target.value)}
                  disabled={editMode}
                  inputSize="medium"
                />
              </ConfigPageRow>
              <ConfigPageRow label={t('imSettings.channelName')} align="center">
                <Input
                  type="text"
                  placeholder={t('imSettings.channelNamePlaceholder')}
                  value={editName}
                  onChange={(e) => setEditName(e.target.value)}
                  inputSize="medium"
                />
              </ConfigPageRow>
              <ConfigPageRow
                label={t('imSettings.relaySecret')}
                description={channelTypeDef?.credentialHint || t('imSettings.optional')}
                align="center"
              >
                <Input
                  type="password"
                  placeholder={t('imSettings.relaySecretPlaceholder')}
                  value={editRelaySecret}
                  onChange={(e) => setEditRelaySecret(e.target.value)}
                  inputSize="medium"
                />
              </ConfigPageRow>
                <ConfigPageRow label={t('imSettings.enable')} align="center">
                  <label className="im-checkbox">
                    <input
                      type="checkbox"
                      checked={editEnabled}
                      onChange={(e) => setEditEnabled(e.target.checked)}
                    />
                    <span>{t('imSettings.enableChannel')}</span>
                  </label>
                </ConfigPageRow>
                <ConfigPageRow label={t('imSettings.autoReply')} align="center">
                  <label className="im-checkbox">
                    <input
                      type="checkbox"
                      checked={editAutoReply}
                      onChange={(e) => setEditAutoReply(e.target.checked)}
                    />
                    <span>{t('imSettings.autoReplyBackend')}</span>
                  </label>
                </ConfigPageRow>
              <div className="im-form-actions">
                <Button
                  variant="primary"
                  onClick={() => void handleSave()}
                  disabled={saving || !editId.trim()}
                  isLoading={saving}
                >
                  {editMode ? t('imSettings.update') : t('imSettings.sectionAddChannel')}
                </Button>
                {editMode && (
                  <Button variant="ghost" onClick={resetForm}>
                    {t('imSettings.cancel')}
                  </Button>
                )}
              </div>
            </div>
            {errorMsg && <div className="im-error im-error--block">{errorMsg}</div>}
          </ConfigPageSection>
        )}
      </ConfigPageContent>

      {/* ==================== 飞书 / Lark 扫码 OAuth 弹窗 ==================== */}
      {oauthModalOpen && !oauthConfirm && (
        <FeishuOAuthModal
          flow={oauthFlow}
          status={oauthStatus}
          error={oauthError}
          polling={oauthPolling}
          countdown={oauthCountdown}
          onClose={() => void closeOauthModal()}
        />
      )}

      {/* ==================== 反劫持二次确认弹窗（扫码完成后阻断式确认）==================== */}
      {oauthConfirm && (
        <div className="im-oauth-modal-mask" role="dialog" aria-modal="true">
          {/* 【反劫持】mask 点击不关闭，强制用户明确选择"确认绑定"或"取消" */}
          <div className="im-oauth-modal">
            <div className="im-oauth-modal__header">
              <h3>{t('imSettings.oauthConfirmTitle')}</h3>
            </div>
            <div className="im-oauth-modal__body">
              <div className="im-oauth-modal__confirm">
                <div
                  className="im-oauth-modal__confirm-icon"
                  style={{ fontSize: 36, textAlign: 'center', margin: '8px 0 12px' }}
                >
                  ⚠
                </div>
                <p style={{ textAlign: 'center', margin: '0 0 16px' }}>
                  {t('imSettings.oauthConfirmPrompt')}
                </p>
                <dl style={{ margin: '0 0 16px', fontSize: 13, lineHeight: 1.8 }}>
                  <dt style={{ fontWeight: 600, marginTop: 8 }}>{t('imSettings.oauthConfirmScannerOpenId')}</dt>
                  <dd style={{ margin: '0 0 0 12px', wordBreak: 'break-all' }}>
                    <code>{oauthConfirm.openId || t('imSettings.oauthConfirmUnknown')}</code>
                  </dd>
                  <dt style={{ fontWeight: 600, marginTop: 8 }}>{t('imSettings.oauthConfirmAppId')}</dt>
                  <dd style={{ margin: '0 0 0 12px', wordBreak: 'break-all' }}>
                    <code>{oauthConfirm.appId}</code>
                  </dd>
                  <dt style={{ fontWeight: 600, marginTop: 8 }}>{t('imSettings.oauthConfirmAnchor')}</dt>
                  <dd style={{ margin: '0 0 0 12px', wordBreak: 'break-all' }}>
                    <code>{oauthConfirm.anchor.slice(0, 8)}...</code>
                  </dd>
                </dl>
                <div
                  className="im-oauth-modal__confirm-actions"
                  style={{ display: 'flex', gap: 8, justifyContent: 'center', marginTop: 16 }}
                >
                  <Button variant="primary" size="small" onClick={() => void confirmOauthBind()}>
                    {t('imSettings.oauthConfirmBind')}
                  </Button>
                  <Button variant="secondary" size="small" onClick={() => void cancelOauthConfirm()}>
                    {t('imSettings.cancel')}
                  </Button>
                </div>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ==================== 通用 QR 扫码弹窗 (微信/QQ Bot) ==================== */}
      {qrModalOpen && (
        <QrLoginModal
          status={qrStatus}
          error={qrError}
          polling={qrPolling}
          qrImage={qrImage}
          countdown={qrCountdown}
          onClose={() => void closeQrModal()}
        />
      )}

      {/* ==================== 通用 QR 反劫持二次确认弹窗已移除 ==================== */}
      {/* 扫码成功后直接走 commitQrBind 自动落库，不再需要用户手动点"确认绑定"。 */}
    </ConfigPageLayout>
  );
};

// ==================== 飞书 / Lark OAuth 弹窗子组件 ====================

interface FeishuOAuthModalProps {
  // flow 为 null 表示 QR 已清空（扫码完成或失败后不缓存）
  flow: FeishuOAuthBeginResult | null;
  status: string;
  error: string;
  polling: boolean;
  /** QR 过期倒计时（秒）。参考 oapi-sdk-go QRCodeInfo.ExpireIn。 */
  countdown: number;
  onClose: () => void;
}

function FeishuOAuthModal({ flow, status, error, polling, countdown, onClose }: FeishuOAuthModalProps) {
  const { t } = useTranslation('common');
  const qrValue = flow?.scanData || flow?.qrUrl || '';
  const showQr = qrValue && status !== 'completed' && status !== 'expired' && status !== 'error' && status !== 'denied';
  const [copied, setCopied] = useState(false);

  // 复制 QR URL 到剪贴板(参考 GitHub CLI clipboard.WriteAll,失败时静默)
  const handleCopyUrl = useCallback(async () => {
    if (!qrValue) return;
    try {
      await navigator.clipboard.writeText(qrValue);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch { /* clipboard API 在部分 webview 中不可用,静默失败 */ }
  }, [qrValue]);

  return (
    <div className="im-oauth-modal-mask" role="dialog" aria-modal="true">
      {/* 【UX】mask 点击不关闭,防止用户误触中断扫码。只能点 X 关闭。 */}
      <div className="im-oauth-modal">
        <div className="im-oauth-modal__header">
          <h3>{t('imSettings.oauthModalTitle')}</h3>
          <button className="im-oauth-modal__close" onClick={onClose} aria-label="close">×</button>
        </div>
        <div className="im-oauth-modal__body">
          {showQr ? (
            <div className="im-oauth-modal__qr">
              <QRCodeSVG value={qrValue} size={220} />
            </div>
          ) : status === 'completed' ? (
            <div className="im-oauth-modal__qr is-success">✓</div>
          ) : status === 'pending' ? (
            <div className="im-oauth-modal__qr is-loading">{t('imSettings.oauthLoading')}</div>
          ) : (
            <div className="im-oauth-modal__qr is-hidden" />
          )}

          <div className="im-oauth-modal__status">
            {status === 'pending' && polling && (
              <span className="im-hint">{t('imSettings.oauthWaitingForScan')}</span>
            )}
            {status === 'pending' && !polling && (
              <span className="im-hint">{t('imSettings.oauthLoading')}</span>
            )}
            {status === 'scanned' && (
              <span className="im-hint is-success">{t('imSettings.oauthScanned')}</span>
            )}
            {status === 'completed' && (
              <span className="im-hint is-success">{t('imSettings.oauthCompleted')}</span>
            )}
            {status === 'expired' && (
              <span className="im-hint is-error">{t('imSettings.oauthExpired')}</span>
            )}
            {status === 'denied' && (
              <span className="im-hint is-error">{t('imSettings.oauthDenied')}</span>
            )}
            {status === 'slow_down' && (
              <span className="im-hint is-warning">{t('imSettings.oauthSlowDown')}</span>
            )}
            {status === 'pending_admin_approval' && (
              <span className="im-hint is-warning">{t('imSettings.oauthPendingAdminApproval')}</span>
            )}
            {status === 'error' && (
              <span className="im-hint is-error">{error || t('imSettings.oauthError')}</span>
            )}
          </div>

          {/* QR 过期倒计时(参考 oapi-sdk-go QRCodeInfo.ExpireIn) */}
          {showQr && countdown > 0 && (
            <div className="im-oauth-modal__countdown">
              {t('imSettings.oauthCountdown', { seconds: countdown })}
            </div>
          )}

          {flow?.userCode && showQr && (
            <div className="im-oauth-modal__user-code">
              <span>{t('imSettings.oauthUserCode')}: </span>
              <code>{flow.userCode}</code>
            </div>
          )}

          {/* 复制 QR URL 按钮(参考 GitHub CLI clipboard.WriteAll) */}
          {showQr && qrValue && (
            <button className="im-oauth-modal__copy-btn" onClick={() => void handleCopyUrl()}>
              {copied ? t('imSettings.oauthUrlCopied') : t('imSettings.oauthCopyUrl')}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

// ==================== 通用 QR 扫码弹窗子组件 (微信/QQ Bot) ====================

interface QrLoginModalProps {
  status: string;
  error: string;
  polling: boolean;
  /** QR 图片 URL 或 base64 data URL（后端返回，直接渲染） */
  qrImage: string;
  countdown: number;
  onClose: () => void;
}

function QrLoginModal({ status, error, polling, qrImage, countdown, onClose }: QrLoginModalProps) {
  const { t } = useTranslation('common');
  const showQr = qrImage && status !== 'completed' && status !== 'error';

  return (
    <div className="im-oauth-modal-mask" role="dialog" aria-modal="true">
      <div className="im-oauth-modal">
        <div className="im-oauth-modal__header">
          <h3>{t('imSettings.qrModalTitle')}</h3>
          <button className="im-oauth-modal__close" onClick={onClose} aria-label="close">×</button>
        </div>
        <div className="im-oauth-modal__body">
          {showQr ? (
            qrImage.startsWith('data:') ? (
              <div className="im-oauth-modal__qr">
                <img src={qrImage} alt="QR Code" style={{ width: 220, height: 220 }} />
              </div>
            ) : (
              <div className="im-oauth-modal__qr">
                <QRCodeSVG value={qrImage} size={220} />
              </div>
            )
          ) : status === 'completed' ? (
            <div className="im-oauth-modal__qr is-success">✓</div>
          ) : status === 'pending' && !qrImage ? (
            <div className="im-oauth-modal__qr is-loading">{t('imSettings.oauthLoading')}</div>
          ) : (
            <div className="im-oauth-modal__qr is-hidden" />
          )}

          <div className="im-oauth-modal__status">
            {status === 'pending' && polling && (
              <span className="im-hint">{t('imSettings.qrWaitingForScan')}</span>
            )}
            {status === 'pending' && !polling && (
              <span className="im-hint">{t('imSettings.oauthLoading')}</span>
            )}
            {status === 'scanned' && (
              <span className="im-hint is-success">{t('imSettings.oauthScanned')}</span>
            )}
            {status === 'completed' && (
              <span className="im-hint is-success">{t('imSettings.oauthCompleted')}</span>
            )}
            {status === 'expired' && (
              <span className="im-hint is-error">{t('imSettings.oauthExpired')}</span>
            )}
            {status === 'error' && (
              <span className="im-hint is-error">{error || t('imSettings.oauthError')}</span>
            )}
          </div>

          {showQr && countdown > 0 && (
            <div className="im-oauth-modal__countdown">
              {t('imSettings.oauthCountdown', { seconds: countdown })}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

export default ImSettingsTab;
