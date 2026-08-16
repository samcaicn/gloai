/**
 * ChatFloaterButton — circular button in bottom-right that opens a
 * standalone Tauri floating chat window (chat-floater).
 *
 * Unlike FloatingMiniChat (which expands inline), this opens an
 * independent webview window that persists even when the main
 * window is hidden. The floating window self-replies via the
 * `chat_stream` Tauri command and transfers full conversation
 * history back to the main window on maximize.
 *
 * Listens for two Tauri events emitted by the backend:
 *   * `chat-floater:new-message`      — single message from floater
 *   * `chat-floater:transfer-history` — full message history on maximize
 *
 * 点击行为（与托盘"悬浮聊天"一致）：
 *   * 不存在          → 新建
 *   * 存在且贴边中     → 还原（fw_restore）
 *   * 存在且显示中     → 贴边隐藏（fw_minimize），保留输入
 */

import React, { useCallback, useEffect, useState } from 'react';
import { MessageSquare, X } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { fwGetState, fwHideMainWindow, fwMinimize, fwOpen, fwRestore } from '@/infrastructure/api/tupai';
import { isTauriRuntime } from '@/infrastructure/runtime';
import { FlowChatManager } from '../../flow_chat/services/FlowChatManager';
import { useSceneStore } from '@/app/stores/sceneStore';
import { useSessionModeStore } from '@/app/stores/sessionModeStore';
import { createLogger } from '@/shared/utils/logger';
import type { DialogTurn, ModelRound, FlowTextItem } from '@/flow_chat/types/flow-chat';
import './ChatFloaterButton.scss';

const log = createLogger('ChatFloaterButton');

const FLOATER_ID = 'chat-floater';

// 缓存当前浮窗状态：open=docked=true 表示"存在但贴边"。
interface FloaterSnapshot {
  exists: boolean;
  docked: boolean;
}

const NO_FLOATER: FloaterSnapshot = { exists: false, docked: false };

// 从浮窗转交过来的消息（与 Rust 端 TransferMessage 对齐）。
interface TransferMessage {
  role: string;
  content: string;
}

// 用唯一 id 生成器构造历史回放所需的 DialogTurn。
// 浮窗历史是纯文本 user/assistant 消息对，这里把每一对组装成一个已完成的
// DialogTurn：userMessage = 用户输入，modelRounds = 单轮含一个 FlowTextItem。
// 末尾若没有配对的 assistant 消息（用户在浮窗输入后立即点最大化），
// 把这条 user 消息留出来走 sendMessage 触发真实模型回复。
function buildHistoryDialogTurns(
  messages: TransferMessage[],
  sessionId: string,
): { turns: DialogTurn[]; trailingUserMessage: string | null } {
  const turns: DialogTurn[] = [];
  let trailingUserMessage: string | null = null;

  let baseTs = Date.now() - messages.length * 1000;
  let round = 0;

  for (let i = 0; i < messages.length; i++) {
    const msg = messages[i];
    if (!msg?.content) continue;

    if (msg.role === 'user') {
      const next = messages[i + 1];
      if (next && next.role === 'assistant' && next.content) {
        // 配对成功：构造一个已完成的 DialogTurn。
        const turnId = `floater-turn-${round}`;
        const userMsgId = `floater-user-${round}`;
        const roundId = `floater-round-${round}`;
        const textItemId = `floater-text-${round}`;
        const startTs = baseTs;
        const endTs = baseTs + 500;

        const textItem: FlowTextItem = {
          id: textItemId,
          type: 'text',
          timestamp: endTs,
          status: 'completed',
          content: next.content,
          isStreaming: false,
          isMarkdown: true,
        };

        const modelRound: ModelRound = {
          id: roundId,
          index: 0,
          items: [textItem],
          isStreaming: false,
          isComplete: true,
          status: 'completed',
          startTime: startTs,
          endTime: endTs,
        };

        const turn: DialogTurn = {
          id: turnId,
          sessionId,
          kind: 'user_dialog',
          userMessage: {
            id: userMsgId,
            content: msg.content,
            timestamp: startTs,
          },
          modelRounds: [modelRound],
          status: 'completed',
          startTime: startTs,
          endTime: endTs,
          success: true,
          finishReason: 'stop',
        } as DialogTurn;

        turns.push(turn);
        round++;
        baseTs += 1000;
        // 跳过已配对的 assistant。
        i++;
      } else {
        // 末尾未配对的 user 消息 —— 留给 sendMessage 触发真实回复。
        trailingUserMessage = msg.content;
      }
    }
  }

  return { turns, trailingUserMessage };
}

export const ChatFloaterButton: React.FC = () => {
  const { t } = useTranslation('flow-chat');
  const [snapshot, setSnapshot] = useState<FloaterSnapshot>(NO_FLOATER);
  const activateScene = useSceneStore((s) => s.activateScene);

  // Listen for the Tauri event `chat-floater:new-message` emitted by
  // the backend when the floating chat window sends a single message
  // (legacy path — kept for backward compatibility).
  useEffect(() => {
    if (!isTauriRuntime()) return;
    let disposed = false;
    let unlisten: (() => void) | null = null;

    void import('@tauri-apps/api/event')
      .then(({ listen }) =>
        listen<{ message: string }>('chat-floater:new-message', async (event) => {
          if (disposed) return;
          const message = event.payload?.message;
          if (!message || !message.trim()) return;

          log.info('Chat floater: received new message', { messageLength: message.length });

          try {
            // Switch to session scene first
            activateScene('session');

            // Create a new chat session and send the message
            const flowChatManager = FlowChatManager.getInstance();
            const setMode = useSessionModeStore.getState().setMode;
            setMode('code');
            const sessionId = await flowChatManager.createChatSession({}, 'agentic');

            if (sessionId) {
              await flowChatManager.sendMessage(message, sessionId);
            }
          } catch (err) {
            log.error('Chat floater: failed to create session and send message', err);
          }
        }),
      )
      .then((removeListener) => {
        if (disposed) {
          removeListener();
          return;
        }
        unlisten = removeListener;
      })
      .catch((err) => {
        if (!disposed) {
          log.warn('Failed to listen for chat-floater:new-message', err);
        }
      });

    return () => {
      disposed = true;
      unlisten?.();
    };
  }, [activateScene]);

  // 监听 `chat-floater:transfer-history`：浮窗点最大化时把整段会话历史
  // 转交主窗口。这里创建新 FlowChat 会话，回放所有已完成 (user → assistant)
  // 对话为 DialogTurn，并对末尾未配对的 user 消息触发真实模型回复。
  useEffect(() => {
    if (!isTauriRuntime()) return;
    let disposed = false;
    let unlisten: (() => void) | null = null;

    void import('@tauri-apps/api/event')
      .then(({ listen }) =>
        listen<{ messages: TransferMessage[] }>('chat-floater:transfer-history', async (event) => {
          if (disposed) return;
          const messages = event.payload?.messages;
          if (!Array.isArray(messages) || messages.length === 0) return;

          log.info('Chat floater: received transfer-history', { messageCount: messages.length });

          try {
            activateScene('session');
            const flowChatManager = FlowChatManager.getInstance();
            const setMode = useSessionModeStore.getState().setMode;
            setMode('code');

            const sessionId = await flowChatManager.createChatSession({}, 'agentic');
            if (!sessionId) {
              log.warn('Chat floater: transfer-history create session returned empty id');
              return;
            }

            const { turns, trailingUserMessage } = buildHistoryDialogTurns(messages, sessionId);

            // 把已完成的对话对依次塞进 session.dialogTurns。
            // 这些 turn 不触发模型调用 —— 它们只是作为历史上下文展示。
            for (const turn of turns) {
              try {
                flowChatManager.addDialogTurn(sessionId, turn);
              } catch (err) {
                log.warn('Chat floater: addDialogTurn failed for transferred turn', err);
              }
            }

            // 末尾有未回复的 user 消息 → 触发真实模型回复，让主窗口接管对话。
            if (trailingUserMessage) {
              await flowChatManager.sendMessage(trailingUserMessage, sessionId);
            }
          } catch (err) {
            log.error('Chat floater: failed to replay transferred history', err);
          }
        }),
      )
      .then((removeListener) => {
        if (disposed) {
          removeListener();
          return;
        }
        unlisten = removeListener;
      })
      .catch((err) => {
        if (!disposed) {
          log.warn('Failed to listen for chat-floater:transfer-history', err);
        }
      });

    return () => {
      disposed = true;
      unlisten?.();
    };
  }, [activateScene]);

  // 拉取当前浮窗 snapshot。state 不变也跳过 setState，避免无谓渲染。
  const refreshSnapshot = useCallback(async () => {
    if (!isTauriRuntime()) return;
    try {
      const entries = await fwGetState();
      const entry = entries.find((e) => e.id === FLOATER_ID);
      const next: FloaterSnapshot = entry
        ? { exists: true, docked: Boolean(entry.docked) || Boolean(entry.minimized) }
        : NO_FLOATER;
      setSnapshot((prev) =>
        prev.exists === next.exists && prev.docked === next.docked ? prev : next,
      );
    } catch (err) {
      log.warn('fw_get_state failed', err);
    }
  }, []);

  // 初次拉 snapshot + 订阅 state-changed 事件。
  useEffect(() => {
    if (!isTauriRuntime()) return;
    void refreshSnapshot();
    let disposed = false;
    let unlisten: (() => void) | null = null;

    void import('@tauri-apps/api/event')
      .then(({ listen }) =>
        listen('floating_window:state-changed', () => {
          if (disposed) return;
          void refreshSnapshot();
        }),
      )
      .then((removeListener) => {
        if (disposed) {
          removeListener();
          return;
        }
        unlisten = removeListener;
      })
      .catch(() => { /* ignore */ });

    return () => {
      disposed = true;
      unlisten?.();
    };
  }, [refreshSnapshot]);

  // 智能 toggle：贴边 ↔ 显示 ↔ 不存在。
  const handleClick = useCallback(async () => {
    if (!isTauriRuntime()) return;

    // 重新拉一次最新状态，避免 stale state 导致连点错位。
    let latest: FloaterSnapshot = snapshot;
    try {
      const entries = await fwGetState();
      const entry = entries.find((e) => e.id === FLOATER_ID);
      latest = entry
        ? { exists: true, docked: Boolean(entry.docked) || Boolean(entry.minimized) }
        : NO_FLOATER;
    } catch (err) {
      log.warn('fw_get_state failed during toggle', err);
    }

    try {
      if (!latest.exists) {
        // 不存在 → 新建浮窗。体积扩大为 1.5 倍 (240×400 → 360×600)，
        // 字体也同步增大 1 号，提升可读性和交互体验。
        // 打开浮窗后隐藏主界面：让浮窗独立承载对话，主窗退到后台。
        await fwOpen({
          id: FLOATER_ID,
          title: t('toolCards.toolbar.startNewChat'),
          width: 360,
          height: 600,
        });
        await fwHideMainWindow();
      } else if (latest.docked) {
        // 贴边中 → 还原浮窗，同时隐藏主界面保持「浮窗可见 = 主窗隐藏」的一致体验。
        await fwRestore(FLOATER_ID);
        await fwHideMainWindow();
      } else {
        // 已显示 → 贴边隐藏（保输入内容）。
        await fwMinimize(FLOATER_ID);
      }
      // 让后端 emit 触发 refresh，这里不强写 setState。
    } catch (err) {
      log.error('Chat floater toggle failed', err);
    }
  }, [snapshot, t]);

  const isOpen = snapshot.exists && !snapshot.docked;

  return (
    <div className="chat-floater-btn">
      <button
        type="button"
        className={`chat-floater-btn__circle${isOpen ? ' is-open' : ''}`}
        onClick={handleClick}
        aria-label={t('toolCards.toolbar.startNewChat')}
      >
        {isOpen ? <X size={20} /> : <MessageSquare size={20} />}
      </button>
    </div>
  );
};

export default ChatFloaterButton;
