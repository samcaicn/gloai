/**
 * IM Message Handler Service
 * Processes IM messages through LLM service with optional skills integration
 */

import { store } from '../store';
import { invoke as tauriInvoke } from '@tauri-apps/api/core';
import { isTauriReady } from './tauriApi';
import { addSession, setCurrentSessionId, addMessage, setStreaming } from '../store/slices/coworkSlice';
import type { IMMessage, IMSettings } from '../types/im';

interface LLMConfig {
  apiKey: string;
  baseUrl: string;
  model: string;
  provider?: string;
}

export type MessageMode = 'chat' | 'cowork';

export interface IMMessageHandlerOptions {
  mode: MessageMode;
  getLLMConfig: () => Promise<LLMConfig | null>;
  getSkillsPrompt?: () => Promise<string | null>;
  imSettings: IMSettings;
}

class IMMessageHandler {
  private options: IMMessageHandlerOptions;
  private messageUnsubscribe: (() => void) | null = null;
  private isProcessing = false;

  constructor(options: IMMessageHandlerOptions) {
    this.options = options;
  }

  /**
   * Start listening for IM messages
   */
  start(): void {
    if (this.messageUnsubscribe) {
      return;
    }

    this.messageUnsubscribe = window.electron.im.onMessageReceived(async (message: IMMessage) => {
      console.log('[IM Message Handler] Received message:', message);
      await this.processMessage(message);
    });

    console.log('[IM Message Handler] Started listening for messages');
  }

  /**
   * Stop listening for IM messages
   */
  stop(): void {
    if (this.messageUnsubscribe) {
      this.messageUnsubscribe();
      this.messageUnsubscribe = null;
    }
    console.log('[IM Message Handler] Stopped listening for messages');
  }

  /**
   * Process an incoming IM message and generate a response
   */
  async processMessage(message: IMMessage): Promise<void> {
    if (this.isProcessing) {
      console.log('[IM Message Handler] Already processing a message, skipping');
      return;
    }

    this.isProcessing = true;

    try {
      switch (this.options.mode) {
        case 'chat':
          await this.processChatMessage(message);
          break;
        case 'cowork':
          await this.processCoworkMessage(message);
          break;
      }
    } catch (error) {
      console.error('[IM Message Handler] Error processing message:', error);
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      await this.sendReply(message, `❌ 处理消息时出错: ${errorMessage}`);
    } finally {
      this.isProcessing = false;
    }
  }

  /**
   * Process message in Chat mode (simple LLM response)
   */
  private async processChatMessage(message: IMMessage): Promise<void> {
    const llmConfig = await this.options.getLLMConfig();
    if (!llmConfig) {
      throw new Error('LLM configuration not found');
    }

    let systemPrompt = this.options.imSettings.systemPrompt || '';

    if (this.options.imSettings.skillsEnabled && this.options.getSkillsPrompt) {
      const skillsPrompt = await this.options.getSkillsPrompt();
      if (skillsPrompt) {
        systemPrompt = systemPrompt
          ? `${systemPrompt}\n\n${skillsPrompt}`
          : skillsPrompt;
      }
    }

    const response = await this.callLLM(llmConfig, message.content, systemPrompt);
    await this.sendReply(message, response);
  }

  /**
   * Process message in Cowork mode (creates a session and runs agent)
   */
  private async processCoworkMessage(message: IMMessage): Promise<void> {
    if (!isTauriReady()) {
      console.warn('[IM Message Handler] Tauri not ready, cannot process cowork message');
      await this.sendReply(message, '❌ 系统未就绪，请稍后再试');
      return;
    }

    const sessionTitle = `[IM] ${message.senderName || message.senderId}: ${message.content.slice(0, 30)}...`;

    const newSession = await tauriInvoke<{ id: string }>('create_cowork_session', {
      title: sessionTitle,
    });

    const now = Date.now();
    store.dispatch(addSession({
      id: newSession.id,
      title: sessionTitle,
      claudeSessionId: null,
      status: 'running',
      pinned: false,
      cwd: '',
      systemPrompt: '',
      executionMode: 'auto',
      activeSkillIds: [],
      messages: [],
      createdAt: now,
      updatedAt: now,
    }));

    store.dispatch(setCurrentSessionId(newSession.id));

    store.dispatch(addMessage({
      sessionId: newSession.id,
      message: {
        id: `im-${Date.now()}`,
        type: 'user',
        content: message.content,
        timestamp: Date.now(),
      },
    }));

    store.dispatch(setStreaming(true));

    try {
      await tauriInvoke('start_cowork_session', {
        sessionId: newSession.id,
        prompt: message.content,
      });
    } catch (error) {
      store.dispatch(setStreaming(false));
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      await this.sendReply(message, `❌ 创建会话失败: ${errorMessage}`);
    }
  }

  /**
   * Send reply back to the IM platform
   */
  private async sendReply(message: IMMessage, text: string): Promise<void> {
    try {
      // TODO: Implement send message via IM service
      console.log('[IM Message Handler] Reply would be sent:', message.platform, text);
    } catch (error) {
      console.error('[IM Message Handler] Failed to send reply:', error);
    }
  }

  /**
   * Call LLM API and get response
   */
  private async callLLM(
    config: LLMConfig,
    userMessage: string,
    systemPrompt?: string
  ): Promise<string> {
    const provider = this.detectProvider(config);

    if (provider === 'anthropic') {
      return this.callAnthropicAPI(config, userMessage, systemPrompt);
    }

    return this.callOpenAICompatibleAPI(config, userMessage, systemPrompt);
  }

  /**
   * Detect provider from config
   */
  private detectProvider(config: LLMConfig): 'anthropic' | 'openai' {
    if (config.provider === 'anthropic') return 'anthropic';
    if (config.baseUrl.includes('anthropic')) return 'anthropic';
    if (config.model.startsWith('claude')) return 'anthropic';
    return 'openai';
  }

  /**
   * Call Anthropic API
   */
  private async callAnthropicAPI(
    config: LLMConfig,
    userMessage: string,
    systemPrompt?: string
  ): Promise<string> {
    const url = `${config.baseUrl.replace(/\/$/, '')}/v1/messages`;

    const body: Record<string, unknown> = {
      model: config.model || 'claude-3-5-sonnet-20241022',
      max_tokens: 4096,
      messages: [{ role: 'user', content: userMessage }],
    };

    if (systemPrompt) {
      body.system = systemPrompt;
    }

    const response = await fetch(url, {
      method: 'POST',
      headers: {
        'x-api-key': config.apiKey,
        'anthropic-version': '2023-06-01',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(body),
    });

    if (!response.ok) {
      const error = await response.text();
      throw new Error(`Anthropic API error: ${response.status} - ${error}`);
    }

    const data = await response.json();
    const content = data.content;

    if (Array.isArray(content)) {
      return content
        .filter((c: { type: string }) => c.type === 'text')
        .map((c: { text: string }) => c.text)
        .join('');
    }

    return '';
  }

  /**
   * Call OpenAI compatible API
   */
  private async callOpenAICompatibleAPI(
    config: LLMConfig,
    userMessage: string,
    systemPrompt?: string
  ): Promise<string> {
    const normalized = config.baseUrl.replace(/\/+$/, '');
    const url = normalized
      ? `${normalized}/v1/chat/completions`
      : '/v1/chat/completions';

    const messages: Array<{ role: string; content: string }> = [];

    if (systemPrompt) {
      messages.push({ role: 'system', content: systemPrompt });
    }

    messages.push({ role: 'user', content: userMessage });

    const response = await fetch(url, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${config.apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        model: config.model || 'gpt-4',
        messages,
        max_tokens: 4096,
      }),
    });

    if (!response.ok) {
      const error = await response.text();
      throw new Error(`LLM API error: ${response.status} - ${error}`);
    }

    const data = await response.json();
    return data.choices?.[0]?.message?.content || '';
  }

  /**
   * Update handler options
   */
  setOptions(options: Partial<IMMessageHandlerOptions>): void {
    this.options = { ...this.options, ...options };
  }

  /**
   * Get current mode
   */
  getMode(): MessageMode {
    return this.options.mode;
  }

  /**
   * Switch mode
   */
  setMode(mode: MessageMode): void {
    this.options.mode = mode;
    console.log('[IM Message Handler] Mode switched to:', mode);
  }
}

let imMessageHandlerInstance: IMMessageHandler | null = null;

/**
 * Get or create the IM message handler singleton
 */
export function getIMMessageHandler(options: IMMessageHandlerOptions): IMMessageHandler {
  if (!imMessageHandlerInstance) {
    imMessageHandlerInstance = new IMMessageHandler(options);
  }
  return imMessageHandlerInstance;
}

/**
 * Initialize the IM message handler with current settings
 */
export async function initIMMessageHandler(
  getLLMConfig: () => Promise<LLMConfig | null>,
  getSkillsPrompt?: () => Promise<string | null>
): Promise<IMMessageHandler> {
  const state = store.getState();
  const imSettings = state.im?.config?.settings || { skillsEnabled: true };

  const handler = getIMMessageHandler({
    mode: 'chat',
    getLLMConfig,
    getSkillsPrompt,
    imSettings,
  });

  handler.start();

  return handler;
}

/**
 * Stop and destroy the IM message handler
 */
export function destroyIMMessageHandler(): void {
  if (imMessageHandlerInstance) {
    imMessageHandlerInstance.stop();
    imMessageHandlerInstance = null;
  }
}

/**
 * Switch message handler mode
 */
export function switchIMMessageHandlerMode(mode: MessageMode): void {
  if (imMessageHandlerInstance) {
    imMessageHandlerInstance.setMode(mode);
  }
}

export type { IMMessageHandler };
