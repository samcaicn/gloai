/**
 * TupaiChatScene — tupai 会话 + IM 合并场景（UI-4-3/4-4）。
 *
 * 双面板布局：
 *   左侧（60%）：LLM 流式对话面板，调用 llmStreamChat 逐字渲染 content chunk。
 *   右侧（40%）：IM 渠道面板，imStatus 显示状态、imConnect 连接、
 *               imSend 发送、imSubscribe 实时接收消息。
 *
 * 替代原 BitFun SessionScene（ChatPane + AuxPane + BottomTerminalPane
 * 复杂布局），完全基于 tupai 桥接层实现，不依赖 BitFun 的 useApp /
 * panelConfig / terminalPanelPreferenceService。
 *
 * 文案沿用中文字面量（与 TupaiHomeScene 一致，不新增 locale 文件）。
 */

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Send, RefreshCw, RadioTower, AlertTriangle, ChevronDown, Check, X, Loader2, Paperclip, Image as ImageIcon } from 'lucide-react';
import {
  llmStreamChat,
  imStatus,
  imConnect,
  imSend,
  imSetBridged,
  imSubscribe,
  imListTargets,
  skillLoadDetailed,
  listModelsViaMcp,
} from '@/infrastructure/api/tupai';
import type { LlmMessage, ImAdapterEvent, ModelInfo, ImTargetItem, ImTargetList, ImTargetType, ChatToolEvent } from '@/infrastructure/api/tupai';
import { createLogger } from '@/shared/utils/logger';
import { notificationService } from '@/shared/notification-system';
import { useSceneStore } from '@/app/stores/sceneStore';
import { useSessionHabitsStore } from '@/flow_chat/store/sessionHabitsStore';
import { MarkdownRenderer } from '@/component-library';
import { createStreamingTypewriter } from '@/shared/utils/streamingTypewriter';
import { listen } from '@tauri-apps/api/event';
import './TupaiChatScene.scss';

const log = createLogger('TupaiChatScene');

// localStorage key：持久化 LLM sessionId，避免每次刷新都新建会话。
const SESSION_ID_KEY = 'tupai_chat_session_id';
// localStorage key 前缀：按 skillId 持久化模型选择。
const MODEL_PREF_KEY_PREFIX = 'tupai_chat_model_';
// sessionStorage keys：TupaiSkillsScene 点击技能卡片时写入。
const SKILL_ID_KEY = 'tupai:session:skillId';
const SKILL_NAME_KEY = 'tupai:session:skillName';
const CHAT_QUERY_KEY = 'tupai:session:chatQuery';
// sessionStorage keys：PipelinesScene 点击流水线时写入。
const PIPELINE_ID_KEY = 'tupai:session:pipelineId';
const PIPELINE_NAME_KEY = 'tupai:session:pipelineName';
const PIPELINE_STEPS_KEY = 'tupai:session:pipelineSteps';
// sessionStorage key：ImSettingsTab 点"进入对话" / 扫码成功时写入渠道 id，
// loadChannels 时作为 preset 优先选中（一次性）。
const FOCUSED_CHANNEL_KEY = 'tupai:im:focusedChannel';
// localStorage key：持久化"上次选定的 IM 渠道"，跨刷新 / 重开仍记忆。
const LAST_SELECTED_CHANNEL_KEY = 'tupai:im:lastSelectedChannel';
// localStorage key：持久化每个渠道的发送目标（channelId → target），跨刷新仍记忆。
const IM_TARGETS_KEY = 'tupai:im:targets';

// 模型类型分组顺序（text 默认不列出，仅显示 'default'）。
const MODEL_TYPE_ORDER = ['image', 'video', 'audio', 'embedding'];

/** 生成或读取持久化的 sessionId。localStorage 不可用时降级为内存级 id。 */
function ensureSessionId(): string {
  try {
    let id = localStorage.getItem(SESSION_ID_KEY);
    if (!id) {
      id = `tupai-chat-${Date.now()}`;
      localStorage.setItem(SESSION_ID_KEY, id);
    }
    return id;
  } catch {
    return `tupai-chat-${Date.now()}`;
  }
}

/** 前端展示用消息结构（比 LlmMessage 多 id / 状态标记）。 */
interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  isError?: boolean;
  isStreaming?: boolean;
  /** 是否还在等待首包（TTFB），显示思考中指示器。 */
  isThinking?: boolean;
  /** 首包到达时间戳（ms），用于显示首字延迟。 */
  firstChunkAt?: number;
  /** 发送时间戳（ms），用于计算 TTFB。 */
  sentAt?: number;
  /** 主会话 @mention 转发记录：LLM 回复完成后自动转发到这些 IM 目标。 */
  forwardedTo?: { channelId: string; target: string; label: string }[];
  /** 若该 user 消息来自 IM 入站（选定渠道同步桥接），记录回复路由：
   *  LLM 用主会话上下文回复后，自动 imSend 到此 target。仅 user 消息携带。 */
  replyTarget?: { channelId: string; target: string; label: string };
}

/** 后端 im_status 返回的单条渠道状态（Vec<ImChannelStatus>）。 */
interface ImChannelStatus {
  channelId: string;
  connected: boolean;
  lastError?: string;
  cooldownUntil?: number;
  /** 后端自动回复开关：true 时前端跳过自己的 LLM 回复，由后端直接回发。 */
  backendAutoReply?: boolean;
}

/**
 * IM 会话入口 —— 按 channelId + target 分组。
 *
 * 每条 IM 入站消息会找到或创建对应的 ImConversation，在独立上下文里
 * 跑 LLM，回复自动发回 IM。用户可以在会话切换栏里切换查看不同 target
 * 的对话上下文。
 *
 * 与"主会话"（用户在 textarea 输入的）互斥：activeConvId === null 时
 * 显示主会话，activeConvId !== null 时显示对应 IM 会话。
 */
interface ImConversation {
  /** 格式：`${channelId}:${target}`，保证全局唯一。 */
  id: string;
  channelId: string;
  /** IM 对端标识（群 ID / 用户 openId / 手机号等）。 */
  target: string;
  /** 展示用标签（优先用 target 前 12 字符，或后端给的 from 名称）。 */
  label: string;
  /** 该会话的消息列表（与主会话 messages 隔离）。 */
  messages: ChatMessage[];
  /** 最后活动时间戳，用于排序。 */
  lastActivity: number;
  /** 未读消息数（用户未切换到该会话时的入站消息计数）。 */
  unread: number;
  /** 是否正在等 LLM 回复（用于状态条展示）。 */
  llmReplying: boolean;
}

/** 简单自增 id 生成器，保证消息 key 稳定。 */
let idCounter = 0;
function nextId(): string {
  idCounter += 1;
  return `m${Date.now().toString(36)}-${idCounter}`;
}

const TupaiChatScene: React.FC = () => {
  const { t } = useTranslation('common');
  // ============ LLM 对话状态 ============
  const [sessionId] = useState<string>(() => ensureSessionId());
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  // messages 的 ref 镜像：供 runMainLLM / handleExecuteSkill 等异步回调同步读取
  // 最新已提交的消息快照，避免把 messages 放进 useCallback 依赖数组
  // （会导致 imSubscribe useEffect 重订阅风暴）。仿 imConversationsRef 模式。
  const messagesRef = useRef<ChatMessage[]>([]);
  const [input, setInput] = useState<string>('');
const [attachedFiles, setAttachedFiles] = useState<Array<{ name: string; size: number; type: string; dataUrl?: string }>>([]);
const fileInputRef = useRef<HTMLInputElement>(null);
const imageInputRef = useRef<HTMLInputElement>(null);
  const [streaming, setStreaming] = useState<boolean>(false);
  const [chatError, setChatError] = useState<string | null>(null);
  // 用 ref 防止并发发送（流式过程中忽略新的发送请求）。
  const streamingRef = useRef<boolean>(false);
  const chatListRef = useRef<HTMLDivElement>(null);
  // 标记组件是否仍挂载，用于在流式输出 / setTimeout 回调中避免卸载后 setState。
  const mountedRef = useRef<boolean>(true);
  // IM 会话 LLM 并发守卫：跟踪每个 convId 是否正在回复。
  // 修复 H5：原代码在 setState updater 内赋值 convMessages 再检查 .some(isStreaming)，
  // 但 React 18 updater 在渲染阶段执行（非调用时同步），导致守卫恒为空数组通过。
  // 改用 ref 同步检查，并在 finally 中清理。
  const imReplyingRefs = useRef<Set<string>>(new Set());
  // 流式状态：'idle' | 'thinking'（等待首包）| 'streaming'（正在接收内容）
  const [streamPhase, setStreamPhase] = useState<'idle' | 'thinking' | 'streaming'>('idle');
  // 首包延迟计时（显示给用户）
  const [, setTtfbMs] = useState<number>(0);
  const ttfbStartRef = useRef<number>(0);

  // ============ Tool Calling 状态 ============
  // 跟踪后端 AgentLoop 工具执行进度（chattoolevent）。
  const [activeToolCalls, setActiveToolCalls] = useState<Map<string, { name: string; phase: string; output?: string }>>(new Map());

  // ============ 技能 prompt 状态 ============
  // 从 sessionStorage 读取技能 ID / 名称（TupaiSkillsScene 点击技能卡片时写入）。
  const [skillId, setSkillId] = useState<string>('');
  const [skillName, setSkillName] = useState<string>('');
  // 技能 SKILL.md 内容，作为 system prompt 注入到 LLM 请求。
  const [skillPrompt, setSkillPrompt] = useState<string>('');
  const [skillLoading, setSkillLoading] = useState<boolean>(false);
  // 技能挂载状态：技能必须成功挂载为 system prompt 才能执行对话。
  //   idle     — 未选择技能
  //   loading  — 正在加载 SKILL.md（多级重试中）
  //   mounted  — SKILL.md 已成功挂载为 system prompt，可以对话
  //   failed   — 所有加载级别均失败，阻断普通对话回退，提示用户重试
  const [skillMountStatus, setSkillMountStatus] = useState<'idle' | 'loading' | 'mounted' | 'failed'>('idle');

  // ============ 流水线上下文状态 ============
  // 从 sessionStorage 读取流水线 ID / 名称（PipelinesScene 点击流水线时写入）。
  const [pipelineId, setPipelineId] = useState<string>('');
  const [pipelineName, setPipelineName] = useState<string>('');
  const [pipelineSteps, setPipelineSteps] = useState<any[]>([]);

  // ============ 模型选择状态 ============
  // 'default' 表示文本默认模型（不传 model 字段，云端走默认路由）。
  const [selectedModel, setSelectedModel] = useState<string>('default');
  const [models, setModels] = useState<ModelInfo[]>([]);
  const [modelDropdownOpen, setModelDropdownOpen] = useState<boolean>(false);
  const modelDropdownRef = useRef<HTMLDivElement>(null);

  // ============ IM 悬浮菜单状态 ============
  // IM 渠道面板由常驻右侧栏改为悬浮菜单：入口按钮在 header 右上角，点击弹出、点击外部关闭。
  const [imMenuOpen, setImMenuOpen] = useState<boolean>(false);
  const imMenuTriggerRef = useRef<HTMLDivElement>(null);
  const imMenuPanelRef = useRef<HTMLElement>(null);

  // ============ IM 状态 ============
  const [channels, setChannels] = useState<ImChannelStatus[]>([]);
  const [channelsLoading, setChannelsLoading] = useState<boolean>(false);
  const [channelsError, setChannelsError] = useState<string | null>(null);
  const [connectingId, setConnectingId] = useState<string | null>(null);
  /** 多渠道桥接：哪些渠道的入站消息桥接到主会话、LLM 回复同步发送。 */
  const [bridgedChannelIds, setBridgedChannelIds] = useState<Set<string>>(new Set());
  /** 当前"焦点"渠道（目标输入框正在编辑的渠道）。 */
  const [focusedChannelId, setFocusedChannelId] = useState<string>('');
  /** 每个渠道对应的目标 ID（群聊 chat_id / 好友 open_id / 文档 token 等）。 */
  // 从 localStorage 恢复，使刷新 / 重开后目标不丢失。
  const [imTargetsMap, setImTargetsMap] = useState<Record<string, string>>(() => {
    try {
      const raw = localStorage.getItem(IM_TARGETS_KEY);
      if (raw) return JSON.parse(raw) as Record<string, string>;
    } catch { /* ignore */ }
    return {};
  });
  /** 当前焦点渠道对应的目标（派生，方便 UI 双向绑定）。 */
  const imTarget = focusedChannelId ? (imTargetsMap[focusedChannelId] ?? '') : '';
  const setImTarget = useCallback((val: string) => {
    if (!focusedChannelId) return;
    setImTargetsMap((prev) => ({ ...prev, [focusedChannelId]: val }));
  }, [focusedChannelId]);
  // ============ 对象选择器状态 ============
  const [selectorOpen, setSelectorOpen] = useState<boolean>(false);
  const [selectorTab, setSelectorTab] = useState<ImTargetType>('chat');
  const [selectorLoading, setSelectorLoading] = useState<boolean>(false);
  const [selectorList, setSelectorList] = useState<ImTargetList | null>(null);
  const [selectorQuery, setSelectorQuery] = useState<string>('');
  const selectorRef = useRef<HTMLDivElement>(null);

  // ============ IM 会话分组状态 ============
  // 按 channelId + target 分组的 IM 会话列表，每个会话有独立 messages 上下文。
  const [imConversations, setImConversations] = useState<ImConversation[]>([]);
  // imConversations 的 ref 镜像：供 runImLLM 等异步回调同步读取最新已提交的会话快照。
  // 修复 H5 不完整：原代码在 setImConversations updater 内赋值 convMessages 再同步读取，
  // 但 React 18 concurrent mode 下 updater 在渲染阶段执行（非调用时同步），convMessages 恒为 []，
  // 导致多轮 IM 对话的 LLM 上下文丢失。ref 始终持有上一次 render 提交后的值，
  // runImLLM 读 ref 即可拿到上一轮的历史消息（正是构造 LLM 上下文所需的"已发生的对话"）。
  // 首条消息场景：调用方在 setImConversations 里新建 conv（state 已 queue 未提交），
  // ref 尚未更新 → conv 查不到 → convMessages = [] —— 这正是首次对话应有的空历史。
  const imConversationsRef = useRef<ImConversation[]>([]);
  useEffect(() => {
    imConversationsRef.current = imConversations;
  }, [imConversations]);
  // messagesRef 同步：runMainLLM 用它构造 LLM 上下文（避免闭包陷阱）。
  useEffect(() => {
    messagesRef.current = messages;
  }, [messages]);
  // 当前激活的 IM 会话 id；null 表示显示主会话（用户在 textarea 输入的）。
  const [activeConvId, setActiveConvId] = useState<string | null>(null);

  // ============ IM 桥接队列（主会话繁忙时排队入站消息）============
  // 选定渠道的 IM 入站触发主会话 LLM；若主会话正在流式，入站消息排队等待，
  // runMainLLM finally 块消费下一条。imQueueCount 用于 UI 显示排队提示。
  const [imQueueCount, setImQueueCount] = useState<number>(0);
  const pendingImQueueRef = useRef<Array<{ channelId: string; target: string; label: string; text: string }>>([]);
  // 入站回声去重：记录最近 5s 内 imSend 出去的 (channelId+target+text) 摘要，
  // 防止 IM 服务器把"自己发的消息"作为入站事件回传导致无限循环。
  const recentSentRef = useRef<Array<{ key: string; ts: number }>>([]);
  // IM 统计数据（客户端累计）用于右侧栏展示。
  const imStatsRef = useRef<{ messagesReceived: number; errors: number }>({ messagesReceived: 0, errors: 0 });
  const [imMessageCount, setImMessageCount] = useState(0);
  const [imErrorCount, setImErrorCount] = useState(0);
  // imSubscribe 用 ref 引用"最新版本"的 runMainLLM / mirrorAssistantToConv / bridgedChannels，
  // 避免 imSubscribe useEffect 因这些值变化而重订阅（保持单一订阅，也回避声明顺序问题）。
  // 这些函数 / 值在下方才定义，ref 初始 noop，定义后由各自 useEffect 同步到 ref。
  const runMainLLMRef = useRef<(text: string, opts?: {
    replyTarget?: { channelId: string; target: string; label: string };
    parsedMentions?: { channelId: string; target: string; label: string }[];
  }) => Promise<string | null>>(async () => null);
  const mirrorAssistantToConvRef = useRef<(convId: string, content: string) => void>(() => {});
  /** ref 镜像：imSubscribe 等回调读取最新 bridged 渠道集合，避免重订阅。 */
  const bridgedChannelsRef = useRef<Set<string>>(new Set());
  /** ref 镜像：标记了后端自动回复（backendAutoReply=true）的渠道集合。 */
  const backendAutoReplyRef = useRef<Set<string>>(new Set());
  const focusedChannelRef = useRef<string>('');
  const imTargetsMapRef = useRef<Record<string, string>>({});
  useEffect(() => { bridgedChannelsRef.current = bridgedChannelIds; }, [bridgedChannelIds]);
  // 卸载时清空后端桥接集合，恢复后端自动回复接管。
  // 注意：挂载/变更时的桥接上报由 loadChannels / toggleBridgedChannel / focusChannel
  // 同步调用 imSetBridged 完成，这里不再用 bridgedChannelIds 依赖——否则每次变更
  // 会先跑 cleanup(imSetBridged([])) 清空集合再重新上报，产生后端误判窗口（双回复）。
  useEffect(() => {
    return () => {
      imSetBridged([]).catch(() => { /* best-effort clear on unmount */ });
    };
  }, []);
  // 心跳：前端挂载期间周期性刷新桥接状态，防止后端 TTL 过期误判渠道失联
  // （即使窗口被强制关闭、React cleanup 未执行，后端也会在 TTL 后恢复自动回复）。
  useEffect(() => {
    const timer = setInterval(() => {
      const ids = Array.from(bridgedChannelsRef.current);
      if (ids.length > 0) {
        imSetBridged(ids).catch((e) => log.error('imSetBridged heartbeat failed', { error: e }));
      }
    }, 30_000);
    return () => clearInterval(timer);
  }, []);
  useEffect(() => {
    backendAutoReplyRef.current = new Set(
      channels.filter((c) => c.backendAutoReply !== false).map((c) => c.channelId),
    );
  }, [channels]);
  useEffect(() => { focusedChannelRef.current = focusedChannelId; }, [focusedChannelId]);
  useEffect(() => { imTargetsMapRef.current = imTargetsMap; }, [imTargetsMap]);
  // 持久化 imTargetsMap 到 localStorage，刷新 / 重开后恢复。
  useEffect(() => {
    try { localStorage.setItem(IM_TARGETS_KEY, JSON.stringify(imTargetsMap)); } catch { /* ignore */ }
  }, [imTargetsMap]);

  // ============ 主会话 @mention 状态 ============
  // textarea 输入 @ 后弹候选下拉，选中后插入 @label；发送时解析并转发 LLM 回复。
  // null 表示下拉关闭；string 表示当前 @ 后的查询文本（可为空串）。
  const [mentionQuery, setMentionQuery] = useState<string | null>(null);
  const [mentionIndex, setMentionIndex] = useState(0);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  // ============ Hermes 默默记住用户会话习惯 ============
  const trackSkillUsage = useSessionHabitsStore(s => s.trackSkillUsage);
  const trackMessage = useSessionHabitsStore(s => s.trackMessage);
  const trackModelPreference = useSessionHabitsStore(s => s.trackModelPreference);
  const trackSessionStart = useSessionHabitsStore(s => s.trackSessionStart);
  const getHabitsContext = useSessionHabitsStore(s => s.getHabitsContext);

  // ============ IM 渠道状态加载 ============
  // SELECTED_CHANNEL_KEY (sessionStorage)：ImSettingsTab 点"进入对话" / 扫码成功时写入，一次性 preset。
  // LAST_SELECTED_CHANNEL_KEY (localStorage)：跨刷新持久化"上次选定的 IM 渠道"。
  const loadChannels = useCallback(async () => {
    setChannelsLoading(true);
    setChannelsError(null);
    try {
      const list = await imStatus();
      // imStatus 返回 Vec<ImChannelStatus>；做字段规整以兼容后端大小写差异。
      const normalized: ImChannelStatus[] = Array.isArray(list)
          ? list.map((it: any) => ({
              channelId: String(it?.channelId ?? it?.channel_id ?? ''),
              connected: Boolean(it?.connected),
              lastError: it?.lastError ?? it?.last_error ?? undefined,
              cooldownUntil: it?.cooldownUntil ?? it?.cooldown_until ?? undefined,
              backendAutoReply:
                it?.backendAutoReply ?? it?.backend_auto_reply ?? it?.autoReply ?? true,
            }))
        : [];
      setChannels(normalized);
      // 桥接渠道优先级：sessionStorage preset (跳转一次性) → localStorage last → 第一个已连接渠道 → 第一个。
      // preset 和 last 都可能是逗号分隔的多渠道 id（新逻辑），也兼容单 id（旧逻辑）。
      let presetIds: string[] = [];
      try {
        const raw = sessionStorage.getItem(FOCUSED_CHANNEL_KEY) || '';
        if (raw) {
          sessionStorage.removeItem(FOCUSED_CHANNEL_KEY);
          presetIds = raw.split(',').map((s) => s.trim()).filter(Boolean);
        }
      } catch { /* ignore */ }
      let lastIds: string[] = [];
      try {
        const raw = localStorage.getItem(LAST_SELECTED_CHANNEL_KEY) || '';
        if (raw) lastIds = raw.split(',').map((s) => s.trim()).filter(Boolean);
      } catch { /* ignore */ }
      const validIds = new Set(normalized.map((c) => c.channelId));
      // 计算初始 bridged 集合（preset > 当前会话已桥接 > last > firstConnected）。
      // 用 bridgedChannelsRef（同步维护的当前值）代替原 functional update 的 prev，
      // 避免 loadChannels 重跑（重连等）时丢失用户本次会话内的勾选。
      const merged = new Set<string>();
      presetIds.forEach((id) => { if (validIds.has(id)) merged.add(id); });
      if (merged.size === 0) {
        bridgedChannelsRef.current.forEach((id) => { if (validIds.has(id)) merged.add(id); });
      }
      if (merged.size === 0) {
        lastIds.forEach((id) => { if (validIds.has(id)) merged.add(id); });
      }
      if (merged.size === 0 && normalized.length > 0) {
        const firstConnected = normalized.find((c) => c.connected);
        merged.add(firstConnected?.channelId ?? normalized[0].channelId);
      }
      try { localStorage.setItem(LAST_SELECTED_CHANNEL_KEY, Array.from(merged).join(',')); } catch { /* ignore */ }
      // 同步 ref + 状态 + 后端（mount 时一次性同步，消除竞态）。
      bridgedChannelsRef.current = merged;
      setBridgedChannelIds(merged);
      imSetBridged(Array.from(merged)).catch((e) => log.error('imSetBridged failed', { error: e }));
      // 焦点渠道：取第一个 bridged 渠道（若有），否则第一个渠道。
      setFocusedChannelId((prev) => {
        if (prev && validIds.has(prev)) return prev;
        const firstBridged = presetIds.find((id) => validIds.has(id))
          || lastIds.find((id) => validIds.has(id))
          || (normalized.find((c) => c.connected)?.channelId)
          || normalized[0]?.channelId
          || '';
        return firstBridged;
      });
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      log.error('Failed to load im status', err);
      setChannelsError(message);
    } finally {
      setChannelsLoading(false);
    }
  }, []);

  // ============ 多渠道桥接：切换选中 / 聚焦编辑 ============
  /** 切换某渠道是否桥接到主会话（入站→LLM，LLM 回复→该渠道 target）。 */
  const toggleBridgedChannel = useCallback((channelId: string) => {
    setBridgedChannelIds((prev) => {
      const next = new Set(prev);
      const willBeBridged = !next.has(channelId);
      if (willBeBridged) next.add(channelId);
      else next.delete(channelId);
      try { localStorage.setItem(LAST_SELECTED_CHANNEL_KEY, Array.from(next).join(',')); } catch { /* ignore */ }
      // 同步 ref + 上报后端（在 state 更新前完成，消除竞态）。
      bridgedChannelsRef.current = next;
      imSetBridged(Array.from(next)).catch((e) => log.error('imSetBridged failed', { error: e }));
      return next;
    });
    setFocusedChannelId((prevFocus) => {
      const next = new Set(bridgedChannelsRef.current);
      if (!next.has(channelId)) {
        // 刚取消勾选：若焦点正是被取消的，切到第一个其他 bridged；否则保持。
        if (prevFocus === channelId) {
          const remaining = Array.from(next).filter((id) => id !== channelId);
          return remaining[0] ?? '';
        }
      } else {
        // 刚勾选：聚焦到该渠道方便用户配 target
        return channelId;
      }
      return prevFocus;
    });
  }, []);

  /** 聚焦某渠道（目标输入框开始编辑它的 target），不改变桥接状态。 */
  const focusChannel = useCallback((channelId: string) => {
    setFocusedChannelId(channelId);
    // 聚焦时自动确保该渠道也被桥接（避免用户配完 target 才发现没勾上）。
    setBridgedChannelIds((prev) => {
      if (prev.has(channelId)) return prev;
      const next = new Set(prev);
      next.add(channelId);
      try { localStorage.setItem(LAST_SELECTED_CHANNEL_KEY, Array.from(next).join(',')); } catch { /* ignore */ }
      // 同步 ref + 上报后端。
      bridgedChannelsRef.current = next;
      imSetBridged(Array.from(next)).catch((e) => log.error('imSetBridged failed', { error: e }));
      return next;
    });
  }, []);

  // 组件卸载时标记，供流式输出与 setTimeout 回调判断是否仍可安全 setState。
  useEffect(() => {
    return () => {
      mountedRef.current = false;
    };
  }, []);

  // Hermes 默默记录会话开始（进入聊天页时调用一次）
  useEffect(() => {
    trackSessionStart();
  }, [trackSessionStart]);

  // ============ 监听后端 AgentLoop 工具调用事件 ============
  // 后端 legacy.rs 在 response.completed 检测到 function_call 时，
  // 通过 app.emit("chattoolevent", ...) 逐个推送工具的 started/completed 状态。
  // 前端用 Map<callId, {name, phase, output?}> 追踪，UI 显示执行进度。
  useEffect(() => {
    const unlistenPromise = listen<ChatToolEvent>('chattoolevent', (event) => {
      const e = event.payload;
      const callId = e.callId;
      if (!callId) return;
      setActiveToolCalls((prev) => {
        const next = new Map(prev);
        if (e.phase === 'started') {
          next.set(callId, { name: e.name ?? 'unknown', phase: 'started' });
        } else if (e.phase === 'completed') {
          const existing = next.get(callId);
          next.set(callId, {
            name: existing?.name ?? e.name ?? 'unknown',
            phase: 'completed',
            output: e.output ?? undefined,
          });
          // 3s 后自动清除已完成的工具调用（避免 Map 无限增长）
          setTimeout(() => {
            setActiveToolCalls((p) => {
              const n = new Map(p);
              n.delete(callId);
              return n;
            });
          }, 3000);
        }
        return next;
      });
    });
    return () => {
      unlistenPromise.then((fn) => fn());
    };
  }, []);

  // ============ 加载技能 prompt ============
  // 从 sessionStorage 读取 skillId 后拉取 SKILL.md 作为 system prompt。
  const loadSkillPrompt = useCallback(async (sid: string, sname: string) => {
    if (!sid) return;

    setSkillId(sid);
    setSkillName(sname);
    setSkillMountStatus('loading');
    // 恢复该技能上次选过的模型
    try {
      const saved = localStorage.getItem(MODEL_PREF_KEY_PREFIX + sid);
      if (saved) setSelectedModel(saved);
    } catch { /* ignore */ }
    setSkillLoading(true);

    // ── skillLoadDetailed 五级加载 + 多级重试 ──
    //   L0. localStorage 缓存（秒加载）
    //   L1. 本地 get_skill_detail（已安装/builtin 技能）
    //   L2. MCP skill.detail（服务器市场技能）
    //   L3. install_skill 下载到本地后重试 L1
    //   L4. 持久化：L2/L3 成功后落盘到本地 MD 文件
    //
    // 技能必须挂载成功才能对话——不退化为无 prompt 的普通聊天。
    // 加载失败时设置 mountStatus='failed'，阻断发送按钮，提示用户重试。
    try {
      const result = await skillLoadDetailed(sid);
      const content = result?.content || '';
      setSkillPrompt(content);
      if (result.mountStatus === 'success' && content) {
        // 挂载成功：SKILL.md 已作为 system prompt 就绪
        setSkillMountStatus('mounted');
        log.info('skill mounted successfully', {
          skillId: sid,
          source: result.source,
          contentLen: content.length,
        });
      } else {
        // 挂载失败：所有加载级别均未拿到 SKILL.md 全文。
        // 阻断普通对话回退——技能模式必须挂载成功才能执行。
        setSkillMountStatus('failed');
        notificationService.error(
          t('skillsScene.skillMountFailed', { name: sname }) ||
            `技能「${sname}」挂载失败，请点击重试或检查网络连接`,
        );
        log.error('skill mount failed, blocking chat to prevent promptless conversation', {
          skillId: sid,
          source: result.source,
        });
      }
      // Hermes 默默记录技能使用习惯
      trackSkillUsage(sid, sname);
    } catch (e) {
      log.error('skillLoadDetailed threw, blocking chat', { skillId: sid, error: e });
      setSkillPrompt('');
      setSkillMountStatus('failed');
      notificationService.error(
        t('skillsScene.skillMountFailed', { name: sname }) ||
          `技能「${sname}」挂载失败，请点击重试或检查网络连接`,
      );
    } finally {
      setSkillLoading(false);
    }
  }, [trackSkillUsage, t]);

  // 挂载时从 sessionStorage 读取技能信息（首次打开 session tab）。
  useEffect(() => {
    const sid = sessionStorage.getItem(SKILL_ID_KEY) || '';
    const sname = sessionStorage.getItem(SKILL_NAME_KEY) || sid;
    if (sid) {
      void loadSkillPrompt(sid, sname);
    } else {
      // 无技能 ID：检查是否有 chatQuery（技能搜索 >5 字时跳转）
      const q = sessionStorage.getItem(CHAT_QUERY_KEY) || '';
      if (q) {
        setInput(q);
        try { sessionStorage.removeItem(CHAT_QUERY_KEY); } catch { /* ignore */ }
      }
    }
  }, [loadSkillPrompt]);

  // 监听技能卡片点击事件（session tab 已打开时，openScene 仅激活不重挂载，
  // 通过此事件重新加载技能 prompt）。
  useEffect(() => {
    const handler = (e: Event) => {
      const detail = (e as CustomEvent<{ skillId: string; skillName: string }>).detail;
      if (detail?.skillId) {
        // 清空当前对话，让新技能的对话从零开始
        setMessages([]);
        setChatError(null);
        void loadSkillPrompt(detail.skillId, detail.skillName);
      }
    };
    window.addEventListener('tupai:session:openSkill', handler as EventListener);
    return () => window.removeEventListener('tupai:session:openSkill', handler as EventListener);
  }, [loadSkillPrompt]);

  // 监听 chatQuery 事件（session tab 已打开时，TupaiSkillsScene >5 字跳转）。
  useEffect(() => {
    const handler = (e: Event) => {
      const detail = (e as CustomEvent<{ query: string }>).detail;
      if (detail?.query) {
        setInput(detail.query);
        try { sessionStorage.removeItem(CHAT_QUERY_KEY); } catch { /* ignore */ }
      }
    };
    window.addEventListener('tupai:session:chatQuery', handler as EventListener);
    return () => window.removeEventListener('tupai:session:chatQuery', handler as EventListener);
  }, []);

  // ============ 流水线上下文加载 ============
  // 从 sessionStorage 读取流水线信息（PipelinesScene 点击流水线时写入）。
  // 流水线上下文作为系统提示注入对话，用户可以在会话中查看和管理流水线。
  useEffect(() => {
    try {
      const pid = sessionStorage.getItem(PIPELINE_ID_KEY) || '';
      const pname = sessionStorage.getItem(PIPELINE_NAME_KEY) || '';
      const pstepsRaw = sessionStorage.getItem(PIPELINE_STEPS_KEY) || '';
      if (pid) {
        setPipelineId(pid);
        setPipelineName(pname);
        if (pstepsRaw) {
          try { setPipelineSteps(JSON.parse(pstepsRaw)); } catch { /* ignore */ }
        }
        // 清除一次性 sessionStorage
        sessionStorage.removeItem(PIPELINE_ID_KEY);
        sessionStorage.removeItem(PIPELINE_NAME_KEY);
        sessionStorage.removeItem(PIPELINE_STEPS_KEY);
      }
    } catch { /* ignore */ }
  }, []);

  // 监听 openPipeline 事件（session tab 已打开时，PipelinesScene 点击流水线）。
  useEffect(() => {
    const handler = (e: Event) => {
      const detail = (e as CustomEvent<{ pipelineId: string; pipelineName: string; steps: any[] }>).detail;
      if (detail?.pipelineId) {
        setPipelineId(detail.pipelineId);
        setPipelineName(detail.pipelineName);
        setPipelineSteps(detail.steps || []);
        // 清空当前对话，让流水线上下文从零开始
        setMessages([]);
        setChatError(null);
      }
    };
    window.addEventListener('tupai:session:openPipeline', handler as EventListener);
    return () => window.removeEventListener('tupai:session:openPipeline', handler as EventListener);
  }, []);

  // ============ 通过 MCP 获取多模态模型列表 ============
  useEffect(() => {
    let cancelled = false;
    (async () => {
      const list = await listModelsViaMcp();
      if (cancelled) return;
      setModels(list);
    })();
    return () => { cancelled = true; };
  }, []);

  // ============ 模型下拉框点击外部关闭 ============
  useEffect(() => {
    if (!modelDropdownOpen) return;
    const handler = (e: MouseEvent) => {
      if (modelDropdownRef.current && !modelDropdownRef.current.contains(e.target as Node)) {
        setModelDropdownOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [modelDropdownOpen]);

  // ============ IM 悬浮菜单点击外部关闭 ============
  useEffect(() => {
    if (!imMenuOpen) return;
    const handler = (e: MouseEvent) => {
      const inTrigger = imMenuTriggerRef.current?.contains(e.target as Node);
      const inPanel = imMenuPanelRef.current?.contains(e.target as Node);
      if (!inTrigger && !inPanel) {
        setImMenuOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [imMenuOpen]);

  // 挂载后加载一次渠道状态。
  useEffect(() => {
    void loadChannels();
  }, [loadChannels]);

  // ============ IM 会话 LLM 流式（独立于主会话）============
  // 每个 ImConversation 有独立的 messages 上下文。入站 IM 消息触发后：
  //   1. 找到/创建 conv → push 用户消息 + assistant 占位
  //   2. 用 conv.messages 作为上下文跑 llmStreamChat
  //   3. 流式更新 conv 里 assistant 占位的内容
  //   4. 完成后 imSend 回 IM + 清除 llmReplying
  //
  // 必须在 imSubscribe useEffect 之前声明，否则依赖数组会引用未初始化的
  // const（TDZ ReferenceError）。
  // 不复用 runLLMWithUserMessage，因为后者操作主会话的 messages state，
  // 会跟 IM 会话的消息互相污染。
  const runImLLM = useCallback(
    async (convId: string, userText: string, channelId: string, target: string) => {
      // 并发守卫：用 ref 同步检查，避免 setState updater 副作用时序问题。
      // 旧代码在 updater 内赋值 convMessages 再检查 .some(isStreaming)，但 React 18
      // updater 在渲染阶段执行（非调用时同步），convMessages 恒为空数组，守卫永不生效。
      if (imReplyingRefs.current.has(convId)) return;
      imReplyingRefs.current.add(convId);

      // 取当前 conv 的 messages 快照（作为 LLM 上下文）—— 从 ref 同步读取。
      // 修复 H5 不完整：原代码在 setImConversations updater 内赋值 convMessages，
      // 但 React 18 updater 在渲染阶段执行（非调用时同步），convMessages 恒为 []，
      // 导致多轮 IM 对话的 LLM 上下文丢失。ref 始终持有已提交的最新值，安全可同步读。
      // conv 查不到（首条消息 / 会话已删除）时 convMessages = []，对首条消息是正确的空历史。
      const conv = imConversationsRef.current.find((c) => c.id === convId);
      const convMessages: ChatMessage[] = conv ? [...conv.messages] : [];
      setImConversations((prev) =>
        prev.map((c) =>
          c.id === convId ? { ...c, llmReplying: true, lastActivity: Date.now() } : c,
        ),
      );

      const userMsg: ChatMessage = { id: nextId(), role: 'user', content: userText };
      const assistantMsg: ChatMessage = { id: nextId(), role: 'assistant', content: '', isStreaming: true };
      setImConversations((prev) =>
        prev.map((c) =>
          c.id === convId
            ? { ...c, messages: [...c.messages, userMsg, assistantMsg], llmReplying: true }
            : c,
        ),
      );

      // 构造 LLM 请求
      const llmMessages: LlmMessage[] = [];
      if (skillPrompt) llmMessages.push({ role: 'system', content: skillPrompt });
      llmMessages.push(
        ...convMessages.map((m) => ({ role: m.role, content: m.content })),
        { role: 'user' as const, content: userText },
      );

      // 打字机渲染（同 runMainLLM）：上游伪流式 + React 18 批处理会"瞬间出全文"，
      // 用 rAF 逐字渲染保证 IM 会话也有逐字流式体验。在 try 外声明，catch 可 cancel。
      const typer = createStreamingTypewriter(
        (text) => {
          if (!mountedRef.current) return;
          setImConversations((prev) =>
            prev.map((c) =>
              c.id === convId
                ? {
                    ...c,
                    messages: c.messages.map((m) =>
                      m.id === assistantMsg.id ? { ...m, content: text } : m,
                    ),
                  }
                : c,
            ),
          );
        },
        () => {
          if (!mountedRef.current) return;
          setImConversations((prev) =>
            prev.map((c) =>
              c.id === convId
                ? {
                    ...c,
                    messages: c.messages.map((m) =>
                      m.id === assistantMsg.id ? { ...m, isStreaming: false } : m,
                    ),
                  }
                : c,
            ),
          );
        },
      );
      try {
        const stream = llmStreamChat({
          sessionId,
          messages: llmMessages,
          model: selectedModel !== 'default' ? selectedModel : undefined,
        });
        let fullContent = '';
        for await (const chunk of stream) {
          if (!mountedRef.current) {
            typer.cancel();
            break;
          }
          if (chunk.type === 'content') {
            const delta = typeof chunk.data === 'string' ? chunk.data : String(chunk.data ?? '');
            fullContent += delta;
            typer.push(fullContent);
          } else if (chunk.type === 'error') {
            typer.cancel();
            const errMsg =
              typeof chunk.data === 'string'
                ? chunk.data
                : String(chunk.data?.message ?? chunk.data ?? t('chatScene.llmError'));
            setImConversations((prev) =>
              prev.map((c) =>
                c.id === convId
                  ? {
                      ...c,
                      messages: c.messages.map((m) =>
                        m.id === assistantMsg.id
                          ? { ...m, content: m.content || `${t('chatScene.errorPrefix')} ${errMsg}`, isError: true, isStreaming: false }
                          : m,
                      ),
                      llmReplying: false,
                    }
                  : c,
              ),
            );
            break;
          } else if (chunk.type === 'done') {
            break;
          }
        }
        // 完成：流结束立即解除 conv 回复态（不阻塞后续），isStreaming 由 typer onDone
        // 在打字结束后置 false。
        typer.finishStream();
        if (mountedRef.current) {
          setImConversations((prev) =>
            prev.map((c) =>
              c.id === convId
                ? { ...c, llmReplying: false, lastActivity: Date.now() }
                : c,
            ),
          );
        }
        // 回 IM
        if (fullContent.trim()) {
          try {
            await imSend(channelId, { text: fullContent }, target);
          } catch (err) {
            log.error('im auto-reply failed', { channelId, target, err });
            notificationService.warning(`IM回复失败: ${err instanceof Error ? err.message : String(err)}`);
          }
        }
      } catch (err) {
        typer.cancel();
        const message = err instanceof Error ? err.message : String(err);
        log.error('runImLLM failed', err);
        if (mountedRef.current) {
          setImConversations((prev) =>
            prev.map((c) =>
              c.id === convId
                ? {
                    ...c,
                    messages: c.messages.map((m) =>
                      m.id === assistantMsg.id
                        ? { ...m, content: m.content || `${t('chatScene.errorPrefix')} ${message}`, isError: true, isStreaming: false }
                        : m,
                    ),
                    llmReplying: false,
                  }
                : c,
            ),
          );
        }
      } finally {
        // 并发守卫清理：无论成功 / 失败 / 异常，都释放 convId 锁，
        // 避免某次失败后该会话永久无法再触发 LLM 回复。
        imReplyingRefs.current.delete(convId);
      }
    },
    [sessionId, skillPrompt, selectedModel, t],
  );

  // ============ 订阅 IM 事件 ============
  // 后端 im_config.rs::spawn_inbound_forwarder 通过 app.emit("im_adapter_event", ev)
  // 推送 IMAdapterEvent { binding_id, kind, payload, ts }。
  // kind 取值由各 IMAdapter 实现决定，常见 "message" / "status" / "error"。
  useEffect(() => {
    const unsubscribe = imSubscribe((event: ImAdapterEvent) => {
      // 累计统计数据
      if (event.kind === 'message') {
        imStatsRef.current.messagesReceived++;
        setImMessageCount(imStatsRef.current.messagesReceived);
      } else if (event.kind === 'error') {
        imStatsRef.current.errors++;
        setImErrorCount(imStatsRef.current.errors);
      }
      const channelId = event.binding_id;
      if (event.kind === 'message') {
        // 收到消息：payload 形如 { target, text } 或 { from, content }，兼容多种字段名。
        const data = (event.payload ?? {}) as any;
        const target = String(data.target ?? data.from ?? data.sender ?? 'unknown');
        const text = String(data.text ?? data.content ?? data.message ?? '');
        const fromLabel = String(data.from_name ?? data.sender_name ?? data.nickname ?? target);
        // 回声去重：若该 (channelId:target:text) 在 5s 内被我们 imSend 出去过，
        // 视为 IM 服务器回声，跳过整条入站处理（不留痕、不触发 LLM），防无限循环。
        const echoKey = `${channelId}:${target}:${text}`;
        const echoNow = Date.now();
        if (recentSentRef.current.some((r) => r.key === echoKey && echoNow - r.ts < 5000)) {
          return;
        }
        if (!text) return;
        const convId = `${channelId}:${target}`;
        const label = fromLabel !== target ? fromLabel : target.slice(0, 16);
        // 2) 建/更新 conv（所有渠道都做，作为按 channelId+target 分组的历史镜像）。
        //    用户可在 conv-tabs 切换查看不同 target 的对话上下文。
        setImConversations((prev) => {
          const existing = prev.find((c) => c.id === convId);
          if (existing) {
            const addUnread = activeConvId !== convId ? 1 : 0;
            return prev.map((c) =>
              c.id === convId
                ? { ...c, unread: c.unread + addUnread, lastActivity: Date.now() }
                : c,
            );
          }
          const newConv: ImConversation = {
            id: convId,
            channelId,
            target,
            label,
            messages: [],
            lastActivity: Date.now(),
            unread: activeConvId !== convId ? 1 : 0,
            llmReplying: false,
          };
          return [...prev, newConv];
        });
        // 3) 分流：
        //    a) 已桥接渠道 → 主会话桥接（共用主会话上下文 + 技能内容，回复自动发回 IM target）。
        //       后端 inbound auto_reply 已通过 im_set_bridged 跳过这些渠道（前端驱动），
        //       因此桥接渠道必须优先于后端自动回复处理，避免丢消息 + 丢技能上下文。
        //    b) 后端自动回复渠道（backendAutoReply=true）：入站消息由 Rust 后端
        //       直接调 LLM 回发。前端只镜像 user 消息到 conv，不触发任何前端 LLM，
        //       避免双回复。后端回复经 kind === 'backend_reply' 事件镜像回来。
        //    c) 未桥接且非后端回复渠道 → 原 runImLLM 在 conv 独立上下文跑。
        // 通过 ref 读取最新值，避免把这些函数/值放进 deps 数组引发重订阅 + 声明顺序问题。
        if (bridgedChannelsRef.current.has(channelId)) {
          const replyTarget = { channelId, target, label };
          if (streamingRef.current) {
            // 主会话繁忙，入站消息排队等待（runMainLLM finally 块消费）。
            pendingImQueueRef.current.push({ channelId, target, label, text });
            setImQueueCount((c) => c + 1);
          } else {
            // 镜像 user 消息到 conv（带 replyTarget，标识该消息来自 IM 入站）。
            setImConversations((prev) =>
              prev.map((c) =>
                c.id === convId
                  ? { ...c, messages: [...c.messages, { id: nextId(), role: 'user' as const, content: text, replyTarget }] }
                  : c,
              ),
            );
            void runMainLLMRef.current(text, { replyTarget }).then((fc) => {
              if (fc && fc.trim() && mountedRef.current) {
                mirrorAssistantToConvRef.current(convId, fc);
              }
            });
          }
        } else if (backendAutoReplyRef.current.has(channelId)) {
          // 仅镜像 user 消息（与 bridged 非 busy 分支同构），不调 LLM。
          setImConversations((prev) =>
            prev.map((c) =>
              c.id === convId
                ? { ...c, messages: [...c.messages, { id: nextId(), role: 'user' as const, content: text, replyTarget: { channelId, target, label } }] }
                : c,
            ),
          );
        } else {
          void runImLLM(convId, text, channelId, target);
        }
      } else if (event.kind === 'backend_reply') {
        // 后端自动回复生成的回复：镜像为 assistant 消息展示在对应 conv。
        // payload 形如 { channelId, target, content }（auto_reply.rs emit）。
        const data = (event.payload ?? {}) as any;
        const bChannelId = String(data.channelId ?? data.channel_id ?? '');
        const bTarget = String(data.target ?? '');
        const content = String(data.content ?? '');
        if (bChannelId && bTarget && content) {
          const convId = `${bChannelId}:${bTarget}`;
          mirrorAssistantToConvRef.current(convId, content);
        }
      } else if (event.kind === 'status') {
        // 状态变更：刷新渠道列表。
        void loadChannels();
      } else if (event.kind === 'error') {
        const data = (event.payload ?? {}) as any;
        const msg = String(data.message ?? data.error ?? t('chatScene.imChannelError'));
        log.warn('IM channel error', { channelId, message: msg });
      } else if (event.kind === 'auth_error') {
        // 凭据失效：弹一个错误通知，提示用户去设置页扫码/重新填 secret。
        const data = (event.payload ?? {}) as any;
        const errMsg = String(data.error ?? t('chatScene.tokenInvalid'));
        notificationService.error(t('chatScene.tokenInvalidTitle', { channelId, message: errMsg }));
        const openSettings = useSceneStore.getState().openScene;
        if (typeof openSettings === 'function') {
          try {
            sessionStorage.setItem('tupai:im:reauthChannel', channelId);
            openSettings('settings');
          } catch (e) {
            log.warn('openScene(settings) failed', e);
          }
        }
      }
    });
    return () => {
      unsubscribe();
    };
  }, [loadChannels, runImLLM, activeConvId, t]);

  // ============ LLM 消息列表自动滚动到底部 ============
  useEffect(() => {
    const el = chatListRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messages]);

  // ============ 主会话 @mention 候选 ============
  // 候选来源：
  //   1. 已建过的 ImConversation（收到过入站消息的 channelId+target）
  //   2. 已配置且已连接的渠道（即使没有收到过入站消息也能 @mention）
  //      用渠道名作为 label，目标 ID 从 imTargetsMap 取。
  // 同一 label 跨渠道算不同候选，下拉里用 channelId 后缀消歧。
  // 必须在 insertMention / handleSend / handleInputKeyDown 之前声明（TDZ）。
  const mentionCandidates = useMemo(() => {
    const result: { label: string; channelId: string; target: string; key: string }[] = [];
    const seen = new Set<string>();

    // 来源 1：已有会话记录的 IM 联系人/群
    for (const c of imConversations) {
      const key = `${c.channelId}:${c.target}`;
      if (!seen.has(key)) {
        seen.add(key);
        result.push({ label: c.label, channelId: c.channelId, target: c.target, key: c.id });
      }
    }

    // 来源 2：已连接且配置了目标 ID 的渠道（补充未收到入站消息的渠道）
    for (const ch of channels) {
      if (!ch.connected) continue;
      const target = (imTargetsMap[ch.channelId] ?? '').trim();
      if (!target) continue;
      const key = `${ch.channelId}:${target}`;
      if (!seen.has(key)) {
        seen.add(key);
        result.push({
          label: ch.channelId,
          channelId: ch.channelId,
          target,
          key: `cfg-${ch.channelId}`,
        });
      }
    }

    return result;
  }, [imConversations, channels, imTargetsMap]);

  // 当前 @query 过滤后的候选列表（最多 8 条）。
  const mentionFiltered = useMemo(() => {
    if (mentionQuery === null) return [];
    const q = mentionQuery.toLowerCase();
    return mentionCandidates
      .filter((c) => c.label.toLowerCase().includes(q) || c.target.toLowerCase().includes(q))
      .slice(0, 8);
  }, [mentionCandidates, mentionQuery]);

  // ============ 主会话 @mention 插入 ============
  // 选中候选后，把 textarea 里 `@query` 替换为 `@label `（带尾空格方便继续输入）。
  const insertMention = useCallback(
    (candidate: { label: string; channelId: string; target: string }) => {
      const textarea = textareaRef.current;
      if (!textarea) return;
      const pos = textarea.selectionStart;
      const before = input.slice(0, pos);
      const after = input.slice(pos);
      // 匹配光标前的 `@query`（query 可为空）
      const match = before.match(/@([^\s@]*)$/);
      if (!match) return;
      const insertText = `@${candidate.label} `;
      const newBefore = before.slice(0, match.index) + insertText;
      const newVal = newBefore + after;
      setInput(newVal);
      setMentionQuery(null);
      setMentionIndex(0);
      // 恢复光标到插入文本之后
      requestAnimationFrame(() => {
        if (textareaRef.current) {
          textareaRef.current.selectionStart = newBefore.length;
          textareaRef.current.selectionEnd = newBefore.length;
          textareaRef.current.focus();
        }
      });
    },
    [input],
  );

  // ============ 文件/图片附件处理 ============
  const handleFileSelect = useCallback(() => {
    fileInputRef.current?.click();
  }, []);

  const handleImageSelect = useCallback(() => {
    imageInputRef.current?.click();
  }, []);

  const handleFileChange = useCallback(async (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = e.target.files;
    if (!files || files.length === 0) return;
    const newFiles: Array<{ name: string; size: number; type: string; dataUrl?: string }> = [];
    for (const file of Array.from(files)) {
      if (file.size > 10 * 1024 * 1024) {
        notificationService.warning(`${file.name}: 文件超过 10MB 限制`, { duration: 3000 });
        continue;
      }
      // 图片生成预览 dataUrl
      if (file.type.startsWith('image/') && file.size < 2 * 1024 * 1024) {
        const dataUrl = await new Promise<string>((resolve) => {
          const reader = new FileReader();
          reader.onload = () => resolve(reader.result as string);
          reader.onerror = () => resolve('');
          reader.readAsDataURL(file);
        });
        newFiles.push({ name: file.name, size: file.size, type: file.type, dataUrl });
      } else {
        newFiles.push({ name: file.name, size: file.size, type: file.type });
      }
    }
    setAttachedFiles((prev) => [...prev, ...newFiles].slice(0, 10));
    // 清空 input 允许重复选同一文件
    e.target.value = '';
  }, []);

  const handleRemoveAttachment = useCallback((index: number) => {
    setAttachedFiles((prev) => prev.filter((_, i) => i !== index));
  }, []);

  // 发送时把附件信息拼入消息文本
  const buildMessageWithAttachments = useCallback((text: string) => {
    if (attachedFiles.length === 0) return text;
    const fileList = attachedFiles.map(f => {
      if (f.dataUrl) {
        return `![${f.name}](${f.dataUrl})`;
      }
      return `[📎 ${f.name} (${(f.size / 1024).toFixed(1)}KB)]`;
    }).join('\n');
    return `${text}\n\n${fileList}`;
  }, [attachedFiles]);
  const handleInputChange = useCallback(
    (e: React.ChangeEvent<HTMLTextAreaElement>) => {
      const val = e.target.value;
      setInput(val);
      // 检测光标前是否有未闭合的 `@query`
      const cursorPos = e.target.selectionStart;
      const before = val.slice(0, cursorPos);
      const match = before.match(/@([^\s@]*)$/);
      if (match) {
        setMentionQuery(match[1]);
        setMentionIndex(0);
      } else {
        setMentionQuery(null);
      }
    },
    [],
  );

  // ============ 主会话 LLM 流式（可复用：用户手输 + IM 入站桥接）============
  // 抽取自原 handleSend：构造 user/assistant 消息 → 流式 → 完成后转发到 IM 目标。
  // - 用户手输：handleSend 解析 @label 后传入 parsedMentions。
  // - IM 入站桥接：imSubscribe 传入 replyTarget，LLM 回复自动 imSend 回该 target。
  // 用 messagesRef（而非 messages state）构造 LLM 上下文，避免把 messages 放进
  // useCallback 依赖数组导致 imSubscribe useEffect 重订阅风暴。
  type RunMainLlmOpts = {
    replyTarget?: { channelId: string; target: string; label: string };
    parsedMentions?: { channelId: string; target: string; label: string }[];
  };
  // 把指定 conv 追加一条 assistant 消息（IM 入站桥接的回复镜像到历史）。
  // 在 runMainLLM 之前声明，供其 finally 块的 .then 回调引用。
  const mirrorAssistantToConv = useCallback((convId: string, content: string) => {
    setImConversations((prev) =>
      prev.map((c) =>
        c.id === convId
          ? { ...c, messages: [...c.messages, { id: nextId(), role: 'assistant' as const, content }] }
          : c,
      ),
    );
  }, []);

  const runMainLLM = useCallback(
    async (userText: string, opts?: RunMainLlmOpts): Promise<string | null> => {
      if (streamingRef.current) return null;
      streamingRef.current = true;
      setStreaming(true);
      setChatError(null);
      const { replyTarget, parsedMentions = [] } = opts ?? {};

      const userMsg: ChatMessage = {
        id: nextId(),
        role: 'user',
        content: userText,
        replyTarget,
      };
      const assistantMsg: ChatMessage = {
        id: nextId(),
        role: 'assistant',
        content: '',
        isStreaming: true,
        isThinking: true,
        sentAt: Date.now(),
      };
      setMessages((prev) => [...prev, userMsg, assistantMsg]);
      setStreamPhase('thinking');
      ttfbStartRef.current = Date.now();
      setTtfbMs(0);

      // 构造 LlmMessage：Hermes 习惯上下文 + 技能 prompt（system）+ 历史（ref）+ 当前用户消息。
      const llmMessages: LlmMessage[] = [];
      const habitsContext = getHabitsContext();
      if (habitsContext) {
        llmMessages.push({ role: 'system', content: habitsContext });
      }
      if (skillPrompt) {
        llmMessages.push({ role: 'system', content: skillPrompt });
      }
      llmMessages.push(
        ...messagesRef.current.map((m) => ({ role: m.role, content: m.content })),
        { role: 'user' as const, content: userText },
      );
      trackMessage(userText);

      let fullContent = '';
      // 打字机渲染器：上游虽返回 text/event-stream 但实测是"伪流式"——服务器/代理
      // 缓冲整段响应，生成完成后在最后几十 ms 内一次性推送全部内容帧（见 tupai.log
      // [mcp_stream] 诊断）。直接逐 chunk setState 会被 React 18 批处理合并成单次
      // 渲染 → "等很久后瞬间出全文"。用 rAF 按匀速逐字追上目标文本，保证稳定的逐字
      // 流式体验；isStreaming 在打字结束后（onDone）才置 false，光标持续到打字完毕。
      const typer = createStreamingTypewriter(
        (text) => {
          if (!mountedRef.current) return;
          setMessages((prev) =>
            prev.map((m) => (m.id === assistantMsg.id ? { ...m, content: text } : m)),
          );
        },
        () => {
          if (!mountedRef.current) return;
          setMessages((prev) =>
            prev.map((m) =>
              m.id === assistantMsg.id ? { ...m, isStreaming: false, isThinking: false } : m,
            ),
          );
        },
      );
      try {
        const stream = llmStreamChat({
          sessionId,
          messages: llmMessages,
          model: selectedModel !== 'default' ? selectedModel : undefined,
        });
        let isFirstChunk = true;
        for await (const chunk of stream) {
          if (!mountedRef.current) {
            typer.cancel();
            break;
          }
          if (chunk.type === 'content') {
            const delta = typeof chunk.data === 'string' ? chunk.data : String(chunk.data ?? '');
            fullContent += delta;
            if (isFirstChunk) {
              isFirstChunk = false;
              const ttfb = Date.now() - ttfbStartRef.current;
              setTtfbMs(ttfb);
              setStreamPhase('streaming');
              setMessages((prev) =>
                prev.map((m) =>
                  m.id === assistantMsg.id ? { ...m, isThinking: false, firstChunkAt: Date.now() } : m,
                ),
              );
            }
            typer.push(fullContent);
          } else if (chunk.type === 'error') {
            typer.cancel();
            const errMsg =
              typeof chunk.data === 'string'
                ? chunk.data
                : String(chunk.data?.message ?? chunk.data ?? t('chatScene.llmError'));
            setChatError(errMsg);
            setMessages((prev) =>
              prev.map((m) =>
                m.id === assistantMsg.id
                  ? { ...m, content: m.content || `${t('chatScene.errorPrefix')} ${errMsg}`, isError: true, isStreaming: false }
                  : m,
              ),
            );
            break;
          } else if (chunk.type === 'done') {
            break;
          }
        }
        typer.finishStream();
        if (mountedRef.current) {
          setStreamPhase('idle');
          // isStreaming/isThinking 由 typer 的 onDone 在打字结束后置 false，
          // 不在此处重复设置（避免打字未完就关掉流式光标）。
          // ============ 转发 LLM 回复到 IM 目标 ============
          // 合并：所有已桥接且配置了 target 的渠道 + @mention 列表 + IM 入站 replyTarget，
          // 按 (channelId,target) 去重，逐个 imSend，失败不阻塞主流程。
          const allTargets: { channelId: string; target: string; label: string }[] = [...parsedMentions];
          if (replyTarget) allTargets.push(replyTarget);
          // 追加所有已桥接且有 target 的渠道（多渠道同步）
          bridgedChannelsRef.current.forEach((cid) => {
            const t = (imTargetsMapRef.current[cid] ?? '').trim();
            if (t) {
              allTargets.push({ channelId: cid, target: t, label: cid });
            }
          });
          // 去重：相同 (channelId,target) 只发一次
          const seen = new Set<string>();
          const dedupTargets = allTargets.filter((tgt) => {
            const k = `${tgt.channelId}:${tgt.target}`;
            if (seen.has(k)) return false;
            seen.add(k);
            return true;
          });
          if (dedupTargets.length > 0 && fullContent.trim()) {
            const forwardedTo: { channelId: string; target: string; label: string }[] = [];
            for (const tgt of dedupTargets) {
              try {
                await imSend(tgt.channelId, { text: fullContent }, tgt.target);
                forwardedTo.push(tgt);
                recentSentRef.current.push({ key: `${tgt.channelId}:${tgt.target}:${fullContent}`, ts: Date.now() });
              } catch (err) {
                log.error('forward to IM target failed', { target: tgt, err });
              }
            }
            if (forwardedTo.length > 0) {
              setMessages((prev) =>
                prev.map((msg) =>
                  msg.id === assistantMsg.id ? { ...msg, forwardedTo } : msg,
                ),
              );
            }
          }
        }
      } catch (err) {
        typer.cancel();
        const message = err instanceof Error ? err.message : String(err);
        log.error('llmStreamChat failed', err);
        if (mountedRef.current) {
          setChatError(message);
          setMessages((prev) =>
            prev.map((m) =>
              m.id === assistantMsg.id
                ? { ...m, content: m.content || `${t('chatScene.errorPrefix')} ${message}`, isError: true, isStreaming: false }
                : m,
            ),
          );
        }
      } finally {
        streamingRef.current = false;
        if (mountedRef.current) {
          setStreaming(false);
          setStreamPhase('idle');
        }
        // 清理过期回声记录（>5s）
        const now = Date.now();
        recentSentRef.current = recentSentRef.current.filter((r) => now - r.ts < 5000);
        // 消费排队中的 IM 入站消息（主会话此前繁忙时入站被排队）。
        const next = pendingImQueueRef.current.shift();
        if (next && mountedRef.current) {
          setImQueueCount((c) => Math.max(0, c - 1));
          const nextReplyTarget = { channelId: next.channelId, target: next.target, label: next.label };
          const convId = `${next.channelId}:${next.target}`;
          // 镜像 user 消息到 conv（带 replyTarget）
          setImConversations((prev) =>
            prev.map((c) =>
              c.id === convId
                ? { ...c, messages: [...c.messages, { id: nextId(), role: 'user' as const, content: next.text, replyTarget: nextReplyTarget }] }
                : c,
            ),
          );
          void runMainLLMRef.current(next.text, { replyTarget: nextReplyTarget }).then((fc) => {
            if (fc && fc.trim() && mountedRef.current) {
              mirrorAssistantToConvRef.current(convId, fc);
            }
          });
        }
      }
      return fullContent;
    },
    [sessionId, skillPrompt, selectedModel, t, getHabitsContext, trackMessage],
  );

  useEffect(() => {
    runMainLLMRef.current = runMainLLM;
  }, [runMainLLM]);
  // mirrorAssistantToConv 的 ref 同步：imSubscribe body 通过 ref 调用最新版本，
  // 避免把它放进 deps 数组引发重订阅 + 声明顺序问题。
  useEffect(() => {
    mirrorAssistantToConvRef.current = mirrorAssistantToConv;
  }, [mirrorAssistantToConv]);

  // ============ 发送 LLM 消息（用户手输）+ @mention 解析 ============
  const handleSend = useCallback(async () => {
    // 如果当前在 IM 会话上下文，走 runImLLM 发送到 IM 渠道
    if (activeConvId) {
      const text = input.trim();
      if (!text || streamingRef.current) return;
      const conv = imConversationsRef.current.find((c) => c.id === activeConvId);
      if (!conv) return;
      const fullText = buildMessageWithAttachments(text);
      setInput('');
      setAttachedFiles([]);
      void runImLLM(activeConvId, fullText, conv.channelId, conv.target);
      return;
    }

    const text = input.trim();
    if (!text || streamingRef.current) return;
    // 技能挂载失败或正在加载时阻断发送——不退化为无 prompt 的普通对话。
    if (skillMountStatus === 'failed') {
      notificationService.warning(
        t('skillsScene.skillMountBlocked') ||
          '技能未挂载成功，请先点击重试挂载技能',
      );
      return;
    }
    if (skillMountStatus === 'loading') {
      notificationService.info(
        t('chatScene.skillLoadingHint') ||
          '技能正在加载中，请稍候…',
      );
      return;
    }
    setMentionQuery(null);
    // 解析文本里的 @label，匹配候选列表得到 channelId + target。
    // LLM 看到的是原始文本（含 @label），转发用 LLM 的最终回复。
    // 匹配策略：精确匹配优先，大小写不敏感匹配次之，
    // 避免 label 含特殊字符时匹配失败。
    const mentionRegex = /@([^\s@]+)/g;
    const parsedMentions: { channelId: string; target: string; label: string }[] = [];
    const seenKeys = new Set<string>();
    let m: RegExpExecArray | null;
    while ((m = mentionRegex.exec(text)) !== null) {
      const label = m[1];
      // 精确匹配优先
      let candidate = mentionCandidates.find((c) => c.label === label);
      // 大小写不敏感匹配次之
      if (!candidate) {
        candidate = mentionCandidates.find(
          (c) => c.label.toLowerCase() === label.toLowerCase(),
        );
      }
      if (candidate) {
        const key = `${candidate.channelId}:${candidate.target}`;
        if (!seenKeys.has(key)) {
          seenKeys.add(key);
          parsedMentions.push({
            channelId: candidate.channelId,
            target: candidate.target,
            label: candidate.label,
          });
        }
      }
    }
    setInput('');
    setAttachedFiles([]);
    const fullText = buildMessageWithAttachments(text);
    await runMainLLM(fullText, { parsedMentions });
  }, [activeConvId, input, runMainLLM, runImLLM, mentionCandidates, skillMountStatus, t, buildMessageWithAttachments]);

  const handleInputKeyDown = useCallback((e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    // ============ @mention 下拉键盘导航 ============
    if (mentionQuery !== null && mentionFiltered.length > 0) {
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setMentionIndex((i) => (i + 1) % mentionFiltered.length);
        return;
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault();
        setMentionIndex((i) => (i - 1 + mentionFiltered.length) % mentionFiltered.length);
        return;
      }
      if (e.key === 'Enter' || e.key === 'Tab') {
        e.preventDefault();
        const candidate = mentionFiltered[mentionIndex];
        if (candidate) {
          void insertMention(candidate);
        }
        return;
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        setMentionQuery(null);
        return;
      }
    }
    // Enter 发送，Shift+Enter 换行。
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      void handleSend();
    }
  }, [handleSend, mentionQuery, mentionFiltered, mentionIndex, insertMention]);

  // ============ 连接 IM 渠道 ============
  // 注意：不再用 connectingId 单一锁限制并发——autoReconnect 会在挂载时对
  // 所有未连接渠道发命令，需要支持并行。后端 replace() 会原子替换旧 adapter。
  const handleConnect = useCallback(async (channelId: string, silent = false) => {
    if (!channelId) return;
    setConnectingId(channelId);
    try {
      await imConnect(channelId);
      // 连接命令立即返回（异步建链），通过 imSubscribe status 事件刷新；
      // 主动延迟刷新一次，给后端建链留出时间。
      setTimeout(() => {
        if (mountedRef.current) void loadChannels();
      }, 800);
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      log.error('imConnect failed', { channelId, error: err });
      // 自动重连场景下不弹错误到 UI（避免每次重启都弹一屏）
      if (!silent) {
        setChannelsError(t('chatScene.connectChannelFailed', { channelId, message }));
      }
    } finally {
      setConnectingId(null);
    }
  }, [loadChannels, t]);

  // ============ 对象选择器：加载目标列表 ============
  const loadTargets = useCallback(async (channelId: string, tab: ImTargetType, query?: string) => {
    if (!channelId) {
      setSelectorList(null);
      return;
    }
    setSelectorLoading(true);
    try {
      const result = await imListTargets(channelId, tab, query);
      if (mountedRef.current) setSelectorList(result);
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      log.error('imListTargets failed', { channelId, tab, error: err });
      if (mountedRef.current) {
        setSelectorList({ items: [], status: 'error', message });
      }
    } finally {
      if (mountedRef.current) setSelectorLoading(false);
    }
  }, []);

  // 切换 Tab 或焦点渠道时重新加载
  useEffect(() => {
    if (selectorOpen && focusedChannelId) {
      void loadTargets(focusedChannelId, selectorTab, selectorQuery || undefined);
    }
  }, [selectorOpen, selectorTab, focusedChannelId, loadTargets]); // eslint-disable-line react-hooks/exhaustive-deps

  // 点击外部关闭选择器
  useEffect(() => {
    if (!selectorOpen) return;
    const onDocClick = (e: MouseEvent) => {
      if (selectorRef.current && !selectorRef.current.contains(e.target as Node)) {
        setSelectorOpen(false);
      }
    };
    document.addEventListener('mousedown', onDocClick);
    return () => document.removeEventListener('mousedown', onDocClick);
  }, [selectorOpen]);

  // 打开设置页进行授权
  const openAuthSettings = useCallback(() => {
    setSelectorOpen(false);
    const openSettings = useSceneStore.getState().openScene;
    if (typeof openSettings === 'function') {
      sessionStorage.setItem('tupai:im:reauthChannel', focusedChannelId);
      openSettings('settings');
    }
  }, [focusedChannelId]);

  // ============ 自动重连：挂载时对所有未连且不在 cooldown 的渠道发 connect ============
  // 后端 setup 阶段 init_im_channels 已经尝试连接过；进入聊天页时如果还有
  // 渠道 disconnected，多半是 circuit breaker 冷却中（60s）。这里重新触发
  // connect 命令让后端 replace() adapter 重试，不等用户手动点。
  useEffect(() => {
    if (channels.length === 0) return;
    const now = Date.now();
    const toReconnect = channels.filter((c) => {
      if (c.connected) return false;
      // cooldown 期内不重试，等后端冷却结束
      if (c.cooldownUntil && c.cooldownUntil > now) return false;
      return true;
    });
    if (toReconnect.length === 0) return;
    log.info('auto-reconnecting IM channels', { count: toReconnect.length, ids: toReconnect.map((c) => c.channelId) });
    toReconnect.forEach((c) => {
      void handleConnect(c.channelId, true);
    });
    // 故意不把 channels 加到依赖里——只在挂载 + channels 列表首次填充时跑一次
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [channels.length > 0]);

  // 已桥接且已连接的渠道数量（用于桥接状态条）。
  const bridgedConnectedCount = useMemo(
    () => channels.filter((c) => bridgedChannelIds.has(c.channelId) && c.connected).length,
    [channels, bridgedChannelIds],
  );
  // 所有 IM 会话未读总数（用于悬浮菜单入口角标）。
  const imUnreadTotal = useMemo(
    () => imConversations.reduce((sum, c) => sum + c.unread, 0),
    [imConversations],
  );
  // ============ 模型选择处理 ============
  // 按类型分组的多模态模型（image / video / audio / embedding）。
  const modelsByType = useMemo(() => {
    const grouped: Record<string, ModelInfo[]> = {};
    for (const m of models) {
      const t = (m.type || '').toLowerCase();
      // 匹配 type 或 capabilities 中包含的多模态关键词
      const caps = Array.isArray(m.capabilities) ? m.capabilities.map((c) => c.toLowerCase()) : [];
      const matched = MODEL_TYPE_ORDER.find(
        (k) => t.includes(k) || caps.some((c) => c.includes(k)),
      );
      if (!matched) continue;
      if (!grouped[matched]) grouped[matched] = [];
      grouped[matched].push(m);
    }
    return grouped;
  }, [models]);

  const handleSelectModel = useCallback((modelId: string) => {
    setSelectedModel(modelId);
    setModelDropdownOpen(false);
    // 按 skillId 持久化模型选择
    if (skillId) {
      try { localStorage.setItem(MODEL_PREF_KEY_PREFIX + skillId, modelId); } catch { /* ignore */ }
      // Hermes 默默记录模型偏好
      trackModelPreference(skillId, modelId);
    }
  }, [skillId, trackModelPreference]);

  // 当前选中模型的显示名。
  const selectedModelLabel = useMemo(() => {
    if (selectedModel === 'default') return t('chatScene.defaultModel');
    const m = models.find((it) => it.id === selectedModel);
    return m?.name || selectedModel;
  }, [selectedModel, models, t]);

  // 清除当前技能（移除 skillId，清空 prompt）。
  const handleClearSkill = useCallback(() => {
    // 清除技能 prompt
    setSkillId('');
    setSkillName('');
    setSkillPrompt('');
    setSkillMountStatus('idle');
    try {
      sessionStorage.removeItem(SKILL_ID_KEY);
      sessionStorage.removeItem(SKILL_NAME_KEY);
    } catch { /* ignore */ }
  }, []);

  // ============ 技能挂载重试 ============
  // 当 mountStatus==='failed' 时，用户点击"重试挂载"按钮重新触发加载。
  // skillLoadDetailed 内部已有多级重试（L0-L3 + 指数退避），
  // 这里是用户主动触发的整体重试入口。
  const handleRetryMountSkill = useCallback(() => {
    if (!skillId) return;
    void loadSkillPrompt(skillId, skillName || skillId);
  }, [skillId, skillName, loadSkillPrompt]);

  // ============ 技能执行 ============
  // 技能不再需要手动「执行」按钮——LLM 对话过程中会自动通过 tool calling
  // 调用 UIA / CDP / CLI 等能力。后端 AgentLoop 检测到 function_call 时
  // 通过 chattoolevent 事件推送工具执行进度，前端 activeToolCalls 负责展示。

  return (
    <div className="tupai-chat">
      {/* ============ 左侧：LLM 对话面板（60%）============ */}
      <section className="tupai-chat__llm">
        <header className="tupai-chat__llm-header">
          <div className="tupai-chat__llm-header-left">
            <span className="tupai-chat__llm-title">{t('chatScene.title')}</span>
            {skillName && (
              <span className="tupai-chat__skill-badge" title={skillId}>
                {skillLoading ? '⏳' : '⚡'} {skillName}
                <button
                  className="tupai-chat__skill-badge-close"
                  type="button"
                  onClick={handleClearSkill}
                  title={t('chatScene.removeSkillPrompt')}
                >
                  <X size={11} />
                </button>
              </span>
            )}
            {pipelineName && (
              <span className="tupai-chat__skill-badge tupai-chat__pipeline-badge" title={pipelineId}>
                ⚡ {pipelineName}
                <span className="tupai-chat__pipeline-steps">{pipelineSteps.length} 步</span>
                <button
                  className="tupai-chat__skill-badge-close"
                  type="button"
                  onClick={() => { setPipelineId(''); setPipelineName(''); setPipelineSteps([]); }}
                  title="清除流水线上下文"
                >
                  <X size={11} />
                </button>
              </span>
            )}
          </div>
          <div className="tupai-chat__llm-header-right">
            {/* 模型选择器 */}
            <div className="tupai-chat__model-selector" ref={modelDropdownRef}>
              <button
                className="tupai-chat__model-trigger"
                type="button"
                onClick={() => setModelDropdownOpen(!modelDropdownOpen)}
              >
                <span className="tupai-chat__model-name">{selectedModelLabel}</span>
                <ChevronDown size={11} className="tupai-chat__model-chevron" />
              </button>
              {modelDropdownOpen && (
                <div className="tupai-chat__model-dropdown">
                  <div
                    className={`tupai-chat__model-option ${selectedModel === 'default' ? 'tupai-chat__model-option--selected' : ''}`}
                    onClick={() => handleSelectModel('default')}
                  >
                    <span className="tupai-chat__model-option-name">{t('chatScene.defaultModel')}</span>
                    <span className="tupai-chat__model-option-hint">{t('chatScene.textChat')}</span>
                    {selectedModel === 'default' && <Check size={12} className="tupai-chat__model-option-check" />}
                  </div>
                  {MODEL_TYPE_ORDER.map((typeKey) => {
                    const group = modelsByType[typeKey];
                    if (!group || group.length === 0) return null;
                    return (
                      <div key={typeKey} className="tupai-chat__model-group">
                        <div className="tupai-chat__model-group-label">
                          {t(`chatScene.modelGroups.${typeKey}`)}
                        </div>
                        {group.map((m) => (
                          <div
                            key={m.id}
                            className={`tupai-chat__model-option ${selectedModel === m.id ? 'tupai-chat__model-option--selected' : ''}`}
                            onClick={() => handleSelectModel(m.id)}
                          >
                            <span className="tupai-chat__model-option-name">{m.name}</span>
                            {m.provider && (
                              <span className="tupai-chat__model-option-hint">{m.provider}</span>
                            )}
                            {selectedModel === m.id && <Check size={12} className="tupai-chat__model-option-check" />}
                          </div>
                        ))}
                      </div>
                    );
                  })}
                  {models.length === 0 && (
                    <div className="tupai-chat__model-empty">{t('chatScene.onlyDefaultModel')}</div>
                  )}
                </div>
              )}
            </div>
            {/* IM 悬浮菜单入口 */}
            <div className="tupai-chat__im-menu-trigger-wrap" ref={imMenuTriggerRef}>
              <button
                className={[
                  'tupai-chat__im-menu-trigger',
                  imMenuOpen && 'tupai-chat__im-menu-trigger--active',
                ].filter(Boolean).join(' ')}
                type="button"
                onClick={() => setImMenuOpen((v) => !v)}
                title={t('chatScene.imTitle')}
              >
                <RadioTower size={13} />
                {imUnreadTotal > 0 && (
                  <span className="tupai-chat__im-menu-badge">{imUnreadTotal}</span>
                )}
              </button>
            </div>
          </div>
         </header>

        {/* ============ 会话切换栏 ============ */}
        {/* 主会话 + IM 会话列表（按 channelId+target 分组） */}
        {imConversations.length > 0 && (
          <div className="tupai-chat__conv-tabs">
            <button
              className={[
                'tupai-chat__conv-tab',
                activeConvId === null && 'tupai-chat__conv-tab--active',
              ].filter(Boolean).join(' ')}
              onClick={() => setActiveConvId(null)}
            >
              {t('chatScene.mainSession')}
            </button>
            {imConversations.map((conv) => (
              <button
                key={conv.id}
                className={[
                  'tupai-chat__conv-tab',
                  activeConvId === conv.id && 'tupai-chat__conv-tab--active',
                  conv.channelId && bridgedChannelIds.has(conv.channelId) && 'tupai-chat__conv-tab--bridged',
                ].filter(Boolean).join(' ')}
                onClick={() => {
                  setActiveConvId(conv.id);
                  // 切换到该会话时清除未读
                  setImConversations((prev) =>
                    prev.map((c) => (c.id === conv.id ? { ...c, unread: 0 } : c)),
                  );
                }}
                title={`${conv.channelId} → ${conv.target}`}
              >
                <span className="tupai-chat__conv-tab-label">{conv.label}</span>
                {conv.llmReplying && <span className="tupai-chat__conv-tab-spinner" />}
                {conv.unread > 0 && (
                  <span className="tupai-chat__conv-tab-badge">{conv.unread}</span>
                )}
              </button>
            ))}
          </div>
        )}

        {/* LLM 回复状态条（IM 会话正在回复时显示） */}
        {activeConvId && (() => {
          const conv = imConversations.find((c) => c.id === activeConvId);
          if (!conv?.llmReplying) return null;
          return (
            <div className="tupai-chat__im-replying-bar">
              <span className="tupai-chat__im-replying-dot" />
              <span>{t('chatScene.imReplying', { target: conv.label })}</span>
            </div>
          );
        })()}

        <div className="tupai-chat__messages" ref={chatListRef}>
          {(() => {
            // 根据当前选中的会话切换消息源
            const displayMessages = activeConvId
              ? imConversations.find((c) => c.id === activeConvId)?.messages ?? []
              : messages;
            if (displayMessages.length === 0) {
              return (
                <div className="tupai-chat__messages-empty">
                  {activeConvId
                    ? t('chatScene.emptyImSession')
                    : skillName
                      ? skillMountStatus === 'failed'
                        ? (
                          <div className="tupai-chat__mount-failed">
                            <AlertTriangle size={20} />
                            <span>
                              {t('skillsScene.skillMountFailed', { name: skillName }) ||
                                `技能「${skillName}」挂载失败，SKILL.md 未成功加载`}
                            </span>
                            <button
                              type="button"
                              className="tupai-chat__mount-retry-btn"
                              onClick={handleRetryMountSkill}
                              disabled={skillLoading}
                            >
                              <RefreshCw size={12} className={skillLoading ? 'is-spinning' : ''} />
                              {skillLoading
                                ? t('chatScene.skillLoadingHint')
                                : (t('skillsScene.retryMount') || '重试挂载')}
                            </button>
                          </div>
                        )
                        : t('chatScene.emptyWithSkill', {
                            skillName,
                            loadingHint: skillLoading
                              ? t('chatScene.skillLoadingHint')
                              : ''
                          })
                      : t('chatScene.emptyWithoutSkill')}
                </div>
              );
            }
            return displayMessages.map((m) => (
              <div
                key={m.id}
                className={[
                  'tupai-chat__bubble',
                  `tupai-chat__bubble--${m.role}`,
                  m.isError && 'tupai-chat__bubble--error',
                  m.isThinking && 'tupai-chat__bubble--thinking',
                  m.isStreaming && !m.isThinking && m.content && 'tupai-chat__bubble--streaming',
                ].filter(Boolean).join(' ')}
              >
                <div className="tupai-chat__bubble-role">
                  {m.role === 'user' ? t('chatScene.roleUser') : t('chatScene.roleAssistant')}
                  {/* 首包延迟显示 */}
                  {m.role === 'assistant' && m.firstChunkAt && m.sentAt && (
                    <span className="tupai-chat__bubble-ttfb">
                      {((m.firstChunkAt - m.sentAt) / 1000).toFixed(1)}s
                    </span>
                  )}
                </div>
                {/* IM 入站来源标签：该 user 消息来自选定渠道桥接时显示对端 */}
                {m.role === 'user' && m.replyTarget && (
                  <div className="tupai-chat__bubble-im-source">
                    {t('chatScene.bubbleImSource', { label: m.replyTarget.label })}
                  </div>
                )}
                <div className="tupai-chat__bubble-content">
                  {/* 思考中状态：脉冲动画 + 骨架行 */}
                  {m.isThinking && (
                    <div className="tupai-chat__thinking">
                      <div className="tupai-chat__thinking-indicator">
                        <Loader2 size={14} className="tupai-chat__thinking-spinner" />
                        <span className="tupai-chat__thinking-text">{t('chatScene.thinking', { defaultValue: 'Thinking...' })}</span>
                      </div>
                      <div className="tupai-chat__thinking-skeleton">
                        <div className="tupai-chat__thinking-skeleton-line" style={{ width: '85%' }} />
                        <div className="tupai-chat__thinking-skeleton-line" style={{ width: '65%' }} />
                        <div className="tupai-chat__thinking-skeleton-line" style={{ width: '40%' }} />
                      </div>
                    </div>
                  )}
                  {/* 流式内容：Markdown 渲染 */}
                  {m.role === 'assistant' && m.content ? (
                    <>
                      <MarkdownRenderer content={m.content} isStreaming={m.isStreaming} />
                      {m.isStreaming && <span className="tupai-chat__cursor" />}
                    </>
                  ) : m.role === 'user' ? (
                    m.content
                  ) : null}
                </div>
                {m.forwardedTo && m.forwardedTo.length > 0 && (
                  <div className="tupai-chat__bubble-forwarded">
                    {t('chatScene.forwardedTo', {
                      targets: m.forwardedTo.map((f) => f.label).join(', '),
                    })}
                  </div>
                )}
              </div>
            ));
          })()}
        </div>

        {/* ============ 工具调用进度指示器 ============ */}
        {activeToolCalls.size > 0 && !activeConvId && (
          <div className="tupai-chat__tool-calls-bar">
            {Array.from(activeToolCalls.entries()).map(([callId, tc]) => (
              <div key={callId} className={`tupai-chat__tool-call tupai-chat__tool-call--${tc.phase}`}>
                <span className="tupai-chat__tool-call-dot" />
                <span className="tupai-chat__tool-call-name">{tc.name}</span>
                {tc.phase === 'started' && <Loader2 size={12} className="tupai-chat__tool-call-spinner" />}
                {tc.phase === 'completed' && <Check size={12} className="tupai-chat__tool-call-check" />}
              </div>
            ))}
          </div>
        )}

        {chatError && !activeConvId && (
          <div className="tupai-chat__error-bar">
            <AlertTriangle size={14} />
            <span>{chatError}</span>
          </div>
        )}

        <div className="tupai-chat__composer">
          {/* 文件/图片附件选择 */}
          <input
            ref={fileInputRef}
            type="file"
            multiple
            style={{ display: 'none' }}
            onChange={handleFileChange}
          />
          <input
            ref={imageInputRef}
            type="file"
            accept="image/*"
            multiple
            style={{ display: 'none' }}
            onChange={handleFileChange}
          />
          {/* 附件预览区 */}
          {attachedFiles.length > 0 && (
            <div className="tupai-chat__attachments">
              {attachedFiles.map((file, i) => (
                <div key={i} className="tupai-chat__attachment-chip">
                  {file.dataUrl ? (
                    <img src={file.dataUrl} alt={file.name} className="tupai-chat__attachment-preview" />
                  ) : (
                    <Paperclip size={12} />
                  )}
                  <span className="tupai-chat__attachment-name">{file.name}</span>
                  <button
                    type="button"
                    className="tupai-chat__attachment-remove"
                    onClick={() => handleRemoveAttachment(i)}
                  >
                    <X size={12} />
                  </button>
                </div>
              ))}
            </div>
          )}
          {/* 主会话 / IM 会话共用输入区 */}
          <div className="tupai-chat__composer-input-wrap">
            <div className="tupai-chat__composer-toolbar">
              <button
                type="button"
                className="tupai-chat__composer-tool-btn"
                onClick={handleImageSelect}
                disabled={streaming}
                title="选择图片"
              >
                <ImageIcon size={16} />
              </button>
              <button
                type="button"
                className="tupai-chat__composer-tool-btn"
                onClick={handleFileSelect}
                disabled={streaming}
                title="选择文件"
              >
                <Paperclip size={16} />
              </button>
            </div>
            <textarea
              ref={textareaRef}
              className="tupai-chat__composer-input"
              placeholder={
                activeConvId
                  ? '输入消息回复到 IM 渠道…'
                  : mentionCandidates.length > 0
                    ? t('chatScene.inputPlaceholderWithMention')
                    : t('chatScene.inputPlaceholder')
              }
              value={input}
              onChange={handleInputChange}
              onKeyDown={handleInputKeyDown}
              rows={2}
              disabled={streaming}
            />
            {/* @mention 候选下拉（仅主会话时显示） */}
            {!activeConvId && mentionQuery !== null && mentionFiltered.length > 0 && (
              <div className="tupai-chat__mention-dropdown">
                {mentionFiltered.map((c, i) => (
                  <div
                    key={c.key}
                    className={[
                      'tupai-chat__mention-item',
                      i === mentionIndex && 'tupai-chat__mention-item--active',
                    ].filter(Boolean).join(' ')}
                    onMouseDown={(e) => {
                      e.preventDefault();
                      void insertMention(c);
                    }}
                    onMouseEnter={() => setMentionIndex(i)}
                  >
                    <span className="tupai-chat__mention-at">@</span>
                    <span className="tupai-chat__mention-label">{c.label}</span>
                    <span className="tupai-chat__mention-channel">{c.channelId}</span>
                  </div>
                ))}
              </div>
            )}
            {!activeConvId && mentionQuery !== null && mentionFiltered.length === 0 && mentionCandidates.length === 0 && (
              <div className="tupai-chat__mention-dropdown tupai-chat__mention-dropdown--empty">
                <div className="tupai-chat__mention-empty">{t('chatScene.noMentionCandidates')}</div>
              </div>
            )}
          </div>
          <button
                className={['tupai-chat__composer-btn', streamPhase !== 'idle' && 'tupai-chat__composer-btn--active'].filter(Boolean).join(' ')}
                type="button"
                onClick={() => void handleSend()}
                disabled={streaming || !input.trim() || skillMountStatus === 'failed' || skillMountStatus === 'loading'}
              >
                {streamPhase === 'thinking' ? (
                  <Loader2 size={14} className="tupai-chat__composer-spinner" />
                ) : (
                  <Send size={14} />
                )}
                <span>
                  {streamPhase === 'thinking'
                    ? t('chatScene.thinking', { defaultValue: 'Thinking...' })
                    : streamPhase === 'streaming'
                      ? t('chatScene.generating')
                      : t('chatScene.send')}
                </span>
              </button>
        </div>
      </section>

      {/* ============ 右侧：IM 渠道面板（悬浮菜单，点击 header 入口弹出）============ */}
      {imMenuOpen && (
        <section className="tupai-chat__im" ref={imMenuPanelRef}>
          <header className="tupai-chat__im-header">
            <span className="tupai-chat__im-title">
              <RadioTower size={14} />
              <span>{t('chatScene.imTitle')}</span>
            </span>
            {/* 总未读数 badge */}
            {imUnreadTotal > 0 && (
              <span className="tupai-chat__im-unread-total">
                {imUnreadTotal}
              </span>
            )}
          <button
            className="tupai-chat__im-refresh"
            type="button"
            onClick={() => void loadChannels()}
            disabled={channelsLoading}
            title={t('chatScene.refreshChannels')}
          >
            <RefreshCw size={13} />
          </button>
        </header>

        {/* ============ 桥接状态条：任意已桥接渠道已连接时显示 ============ */}
        {bridgedConnectedCount > 0 && (
          <div className="tupai-chat__im-bridge-bar" title={t('chatScene.imBridgeActive')}>
            <span className="tupai-chat__im-bridge-dot" />
            <span className="tupai-chat__im-bridge-text">
              {t('chatScene.imBridgeActive')} · {bridgedConnectedCount}个渠道
            </span>
            {imQueueCount > 0 && (
              <span className="tupai-chat__im-bridge-queue-badge">
                {t('chatScene.imBridgeQueuePending', { count: imQueueCount })}
              </span>
            )}
          </div>
        )}

        {/* 渠道列表（多选桥接） */}
        <div className="tupai-chat__channels">
          {channelsLoading && channels.length === 0 ? (
            <div className="tupai-chat__channels-empty">{t('chatScene.channelsLoading')}</div>
          ) : channelsError ? (
            <div className="tupai-chat__channels-error">
              <AlertTriangle size={14} />
              <span>{channelsError}</span>
            </div>
          ) : channels.length === 0 ? (
            <div className="tupai-chat__channels-empty">
              {t('chatScene.noChannels')}
            </div>
          ) : (
            channels.map((c) => {
              const isBridged = bridgedChannelIds.has(c.channelId);
              const isFocused = focusedChannelId === c.channelId;
              const chTarget = (imTargetsMap[c.channelId] ?? '').trim();
              return (
              <div
                key={c.channelId}
                className={[
                  'tupai-chat__channel',
                  isBridged && 'tupai-chat__channel--bridged',
                  isFocused && 'tupai-chat__channel--focused',
                ].filter(Boolean).join(' ')}
                onClick={() => focusChannel(c.channelId)}
                role="button"
                tabIndex={0}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    focusChannel(c.channelId);
                  }
                }}
              >
                <button
                  type="button"
                  className="tupai-chat__channel-check"
                  onClick={(e) => {
                    e.stopPropagation();
                    toggleBridgedChannel(c.channelId);
                  }}
                  title={isBridged ? '取消桥接' : '桥接到主会话'}
                >
                  <span className={[
                    'tupai-chat__channel-check-box',
                    isBridged && 'tupai-chat__channel-check-box--on',
                  ].filter(Boolean).join(' ')}>
                    {isBridged && <Check size={10} />}
                  </span>
                </button>
                <span
                  className={[
                    'tupai-chat__channel-dot',
                    c.connected
                      ? 'tupai-chat__channel-dot--on'
                      : 'tupai-chat__channel-dot--off',
                  ].filter(Boolean).join(' ')}
                />
                <span className="tupai-chat__channel-id">{c.channelId}</span>
                {chTarget && (
                  <span className="tupai-chat__channel-target" title={chTarget}>→ {chTarget.slice(0, 12)}{chTarget.length > 12 ? '…' : ''}</span>
                )}
                <span className="tupai-chat__channel-state">
                  {c.connected ? t('chatScene.online') : t('chatScene.offline')}
                </span>
                <button
                  className="tupai-chat__channel-btn"
                  type="button"
                  disabled={!!connectingId || c.connected}
                  onClick={(e) => {
                    e.stopPropagation();
                    void handleConnect(c.channelId);
                  }}
                >
                  {connectingId === c.channelId ? t('chatScene.connecting') : c.connected ? t('chatScene.connected') : t('chatScene.connect')}
                </button>
              </div>
              );
            })
          )}
        </div>

        {/* IM 统计数据 */}
        <div className="tupai-chat__im-stats">
          <span className="tupai-chat__im-stat">
            <span className="tupai-chat__im-stat-label">渠道</span>
            <span className="tupai-chat__im-stat-value">
              {channels.filter((c) => c.connected).length}/{channels.length}
            </span>
          </span>
          <span className="tupai-chat__im-stat">
            <span className="tupai-chat__im-stat-label">桥接</span>
            <span className="tupai-chat__im-stat-value">{bridgedConnectedCount}</span>
          </span>
          <span className="tupai-chat__im-stat">
            <span className="tupai-chat__im-stat-label">收信</span>
            <span className="tupai-chat__im-stat-value">{imMessageCount}</span>
          </span>
          {imErrorCount > 0 && (
            <span className="tupai-chat__im-stat tupai-chat__im-stat--error">
              <span className="tupai-chat__im-stat-label">错误</span>
              <span className="tupai-chat__im-stat-value">{imErrorCount}</span>
            </span>
          )}
        </div>

        {/* 发送区 */}
        <div className="tupai-chat__im-send">
          {/* 已桥接渠道 chip 条：快速切换编辑目标 */}
          {bridgedChannelIds.size > 0 && (
            <div className="tupai-chat__im-chips">
              <span className="tupai-chat__im-chips-label">桥接:</span>
              {[...bridgedChannelIds].map((cid) => {
                const ch = channels.find((c) => c.channelId === cid);
                const t = (imTargetsMap[cid] ?? '').trim();
                const isFoc = cid === focusedChannelId;
                return (
                  <button
                    key={cid}
                    type="button"
                    className={[
                      'tupai-chat__im-chip',
                      isFoc && 'tupai-chat__im-chip--active',
                      !ch?.connected && 'tupai-chat__im-chip--off',
                    ].filter(Boolean).join(' ')}
                    onClick={() => focusChannel(cid)}
                    title={t ? `${cid} → ${t}` : cid}
                  >
                    <span className="tupai-chat__im-chip-dot" />
                    {cid}
                    {!t && <span className="tupai-chat__im-chip-warn" title="未设置目标">!</span>}
                  </button>
                );
              })}
            </div>
          )}

          <div className="tupai-chat__im-send-row tupai-chat__im-target-row" ref={selectorRef}>
            <label className="tupai-chat__im-label">
              {focusedChannelId ? (
                <span className="tupai-chat__im-label-channel" title={focusedChannelId}>
                  {focusedChannelId}
                </span>
              ) : t('chatScene.target')}
            </label>
            <div className="tupai-chat__im-target-wrap">
              <input
                className="tupai-chat__im-target"
                type="text"
                placeholder={focusedChannelId ? t('chatScene.targetPlaceholder') : '先勾选渠道，再设置目标'}
                value={imTarget}
                onChange={(e) => setImTarget(e.target.value)}
                onFocus={() => focusedChannelId && setSelectorOpen(true)}
                onClick={() => focusedChannelId && setSelectorOpen(true)}
                disabled={!focusedChannelId}
              />
              <button
                type="button"
                className="tupai-chat__im-target-dropdown"
                onClick={() => focusedChannelId && setSelectorOpen((v) => !v)}
                disabled={!focusedChannelId}
                aria-label="选择对象"
              >
                <ChevronDown size={14} />
              </button>
              {selectorOpen && focusedChannelId && (
                <div className="tupai-chat__selector">
                  <div className="tupai-chat__selector-tabs">
                    {([
                      { key: 'chat', label: '群聊' },
                      { key: 'friend', label: '好友' },
                      { key: 'doc', label: '文档' },
                    ] as { key: ImTargetType; label: string }[]).map((tab) => (
                      <button
                        key={tab.key}
                        type="button"
                        className={[
                          'tupai-chat__selector-tab',
                          selectorTab === tab.key ? 'tupai-chat__selector-tab--active' : '',
                        ].filter(Boolean).join(' ')}
                        onClick={() => { setSelectorTab(tab.key); setSelectorQuery(''); }}
                      >
                        {tab.label}
                      </button>
                    ))}
                  </div>
                  <div className="tupai-chat__selector-search">
                    <input
                      type="text"
                      placeholder="搜索..."
                      value={selectorQuery}
                      onChange={(e) => setSelectorQuery(e.target.value)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') {
                          e.preventDefault();
                          void loadTargets(focusedChannelId, selectorTab, selectorQuery || undefined);
                        }
                      }}
                    />
                  </div>
                  <div className="tupai-chat__selector-list">
                    {selectorLoading && (
                      <div className="tupai-chat__selector-empty">
                        <Loader2 size={16} className="tupai-chat__spin" />
                        <span>加载中...</span>
                      </div>
                    )}
                    {!selectorLoading && selectorList?.status === 'needs_auth' && (
                      <div className="tupai-chat__selector-auth">
                        <div className="tupai-chat__selector-auth-text">
                          {selectorList.message || '需要授权才能获取列表'}
                        </div>
                        <button
                          type="button"
                          className="tupai-chat__selector-auth-btn"
                          onClick={openAuthSettings}
                        >
                          去授权
                        </button>
                      </div>
                    )}
                    {!selectorLoading && selectorList?.status === 'not_connected' && (
                      <div className="tupai-chat__selector-empty">
                        <AlertTriangle size={16} />
                        <span>渠道未连接，请先连接</span>
                      </div>
                    )}
                    {!selectorLoading && selectorList?.status === 'error' && (
                      <div className="tupai-chat__selector-empty">
                        <AlertTriangle size={16} />
                        <span>{selectorList.message || '加载失败'}</span>
                      </div>
                    )}
                    {!selectorLoading && selectorList?.status === 'ok' && selectorList.items.length === 0 && (
                      <div className="tupai-chat__selector-empty">
                        <span>{selectorList.message || '暂无结果，请在上方输入框手动输入 ID'}</span>
                      </div>
                    )}
                    {!selectorLoading && selectorList?.status === 'ok' && selectorList.items.map((item: ImTargetItem) => (
                      <button
                        key={item.id}
                        type="button"
                        className="tupai-chat__selector-item"
                        onClick={() => {
                          setImTarget(item.id);
                          setSelectorOpen(false);
                        }}
                      >
                        <div className="tupai-chat__selector-item-name">{item.name}</div>
                        {item.description && (
                          <div className="tupai-chat__selector-item-desc">{item.description}</div>
                        )}
                        <div className="tupai-chat__selector-item-id">{item.id}</div>
                      </button>
                    ))}
                  </div>
                </div>
              )}
            </div>
          </div>

        </div>
        </section>
      )}
    </div>
  );
};

export default TupaiChatScene;
