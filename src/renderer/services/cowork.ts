import { store } from '../store';
import { invoke as tauriInvoke } from '@tauri-apps/api/core';
import {
  isTauriReady,
  localStorageFallback,
} from './tauriApi';
import {
  setSessions,
  setCurrentSession,
  addSession,
  updateSessionStatus,
  deleteSession as deleteSessionAction,
  addMessage,
  updateMessageContent,
  setStreaming,
  updateSessionPinned,
  updateSessionTitle,
  enqueuePendingPermission,
  dequeuePendingPermission,
  setConfig,
  clearCurrentSession,
} from '../store/slices/coworkSlice';
import type {
  CoworkSession,
  CoworkMessage,
  CoworkConfigUpdate,
  CoworkApiConfig,
  CoworkSandboxStatus,
  CoworkSandboxProgress,
  CoworkUserMemoryEntry,
  CoworkMemoryStats,
  CoworkPermissionResult,
  CoworkStartOptions,
  CoworkContinueOptions,
} from '../types/cowork';

class CoworkService {
  private streamListenerCleanups: Array<() => void> = [];
  private initialized = false;

  async init(): Promise<void> {
    if (this.initialized) return;

    // Load initial config
    await this.loadConfig();

    // Load sessions list
    await this.loadSessions();

    // Set up stream listeners
    this.setupStreamListeners();

    this.initialized = true;
  }

  private setupStreamListeners(): void {
    const cowork = window.electron?.cowork;
    if (!cowork) return;

    // Clean up any existing listeners
    this.cleanupListeners();

    // Message listener - also check if session exists (for IM-created sessions)
    const messageCleanup = cowork.onStreamMessage(async ({ sessionId, message }) => {
      // Check if session exists in current list
      const state = store.getState().cowork;
      const sessionExists = state.sessions.some(s => s.id === sessionId);

      if (!sessionExists) {
        // Session was created by IM or another source, refresh the session list
        await this.loadSessions();
      }

      // A new user turn means this session is actively running again
      // (especially important for IM-triggered turns that do not call continueSession from renderer).
      if (message.type === 'user') {
        store.dispatch(updateSessionStatus({ sessionId, status: 'running' }));
      }

      // Do not force status back to "running" on arbitrary messages.
      // Late stream chunks can arrive after an error/complete event.
      store.dispatch(addMessage({ sessionId, message }));
    });
    this.streamListenerCleanups.push(messageCleanup);

    // Message update listener (for streaming content updates)
    const messageUpdateCleanup = cowork.onStreamMessageUpdate(({ sessionId, messageId, content }) => {
      store.dispatch(updateMessageContent({ sessionId, messageId, content }));
    });
    this.streamListenerCleanups.push(messageUpdateCleanup);

    // Permission request listener
    const permissionCleanup = cowork.onStreamPermission(({ sessionId, request }) => {
      store.dispatch(enqueuePendingPermission({
        sessionId,
        toolName: request.toolName,
        toolInput: request.toolInput,
        requestId: request.requestId,
        toolUseId: request.toolUseId ?? null,
      }));
    });
    this.streamListenerCleanups.push(permissionCleanup);

    // Complete listener
    const completeCleanup = cowork.onStreamComplete(({ sessionId }) => {
      store.dispatch(updateSessionStatus({ sessionId, status: 'completed' }));
    });
    this.streamListenerCleanups.push(completeCleanup);

    // Error listener
    const errorCleanup = cowork.onStreamError(({ sessionId }) => {
      store.dispatch(updateSessionStatus({ sessionId, status: 'error' }));
    });
    this.streamListenerCleanups.push(errorCleanup);
  }

  private cleanupListeners(): void {
    this.streamListenerCleanups.forEach(cleanup => cleanup());
    this.streamListenerCleanups = [];
  }

  async loadSessions(): Promise<void> {
    if (!isTauriReady()) {
      console.warn('[cowork] Tauri not ready, skipping loadSessions');
      return;
    }
    try {
      const sessions = await tauriInvoke<Array<any>>('cowork_list_sessions');
      if (sessions) {
        store.dispatch(setSessions(sessions));
      }
    } catch (error) {
      console.error('Failed to load sessions:', error);
      // 尝试使用数据库 API
      try {
        const dbSessions = await tauriInvoke<any>('db_cowork_list_sessions');
        if (dbSessions) {
          store.dispatch(setSessions(dbSessions as Array<any>));
        }
      } catch (dbError) {
        console.error('Failed to load sessions from database:', dbError);
      }
    }
  }

  async loadConfig(): Promise<void> {
    if (!isTauriReady()) {
      console.warn('[cowork] Tauri not ready, using localStorage fallback for config');
      const config = localStorageFallback.get('cowork_config');
      if (config) {
        store.dispatch(setConfig(config));
      }
      return;
    }
    try {
      // 从存储中加载配置
      const configJson = await tauriInvoke<string | null>('kv_get', { key: 'cowork_config' });
      if (configJson) {
        const config = JSON.parse(configJson);
        store.dispatch(setConfig(config));
      }
    } catch (error) {
      console.error('Failed to load config:', error);
    }
  }

  async startSession(options: CoworkStartOptions): Promise<CoworkSession | null> {
    try {
      store.dispatch(setStreaming(true));

      // 创建会话 ID
      const sessionId = `session_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
      const now = Date.now();
      
      // 创建会话对象
      const session: CoworkSession = {
        id: sessionId,
        title: options.title || '新会话',
        claudeSessionId: null,
        status: 'running',
        pinned: false,
        cwd: options.cwd || '',
        systemPrompt: options.systemPrompt || '',
        executionMode: 'auto',
        activeSkillIds: options.activeSkillIds || [],
        messages: [],
        createdAt: now,
        updatedAt: now,
      };

      // 保存到数据库
      if (isTauriReady()) {
        try {
          await tauriInvoke('db_cowork_create_session', {
            id: sessionId,
            title: session.title,
          });
        } catch (dbError) {
          console.error('Failed to save session to database:', dbError);
        }

        // 保存到内存存储
        try {
          await tauriInvoke('cowork_create_session', {
            title: session.title,
          });
        } catch (kvError) {
          console.error('Failed to save session to KV store:', kvError);
        }
      }

      // 立即返回会话，不等待GoClaw操作
      store.dispatch(addSession(session));

      // 在后台处理GoClaw连接，不阻塞会话创建
      if (isTauriReady()) {
        setTimeout(async () => {
          try {
            console.log('Checking GoClaw status...');
            const isRunning = await tauriInvoke<boolean>('goclaw_is_running');
            console.log('GoClaw is running:', isRunning);
            
            if (isRunning) {
              try {
                console.log('Connecting to GoClaw WebSocket...');
                await tauriInvoke('goclaw_connect');
                console.log('GoClaw WebSocket connected');
              } catch (connectError) {
                console.warn('Failed to connect to GoClaw WebSocket:', connectError);
              }
            } else {
              console.warn('GoClaw is not running, starting...');
              try {
                await tauriInvoke('goclaw_start');
                console.log('GoClaw started, waiting for ready...');
                // 等待 GoClaw 启动
                await new Promise(resolve => setTimeout(resolve, 2000));
                try {
                  await tauriInvoke('goclaw_connect');
                  console.log('GoClaw WebSocket connected after startup');
                } catch (connectError) {
                  console.warn('Failed to connect to GoClaw WebSocket after startup:', connectError);
                }
              } catch (startError) {
                console.warn('Failed to start GoClaw:', startError);
              }
            }
          } catch (goclawError) {
            console.warn('GoClaw status check failed:', goclawError);
          }
        }, 0);
      }

      return session;
    } catch (error) {
      store.dispatch(setStreaming(false));
      console.error('Failed to start session:', error);
      return null;
    }
  }

  async continueSession(options: CoworkContinueOptions): Promise<boolean> {
    try {
      store.dispatch(setStreaming(true));
      store.dispatch(updateSessionStatus({ sessionId: options.sessionId, status: 'running' }));

      // 创建消息 ID
      const messageId = `message_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;

      // 添加用户消息
      const userMessage: CoworkMessage = {
        id: messageId,
        type: 'user',
        content: options.prompt,
        timestamp: Date.now(),
      };

      // 保存消息到数据库
      if (isTauriReady()) {
        try {
          await tauriInvoke('db_cowork_add_message', {
            id: messageId,
            sessionId: options.sessionId,
            msg_type: 'user',
            content: options.prompt,
          });
        } catch (dbError) {
          console.error('Failed to save message to database:', dbError);
        }

        // 检查并连接 GoClaw
        try {
          console.log('Checking GoClaw status for message sending...');
          const isRunning = await tauriInvoke<boolean>('goclaw_is_running');
          console.log('GoClaw is running:', isRunning);
          
          if (isRunning) {
            try {
              console.log('Connecting to GoClaw WebSocket...');
              await tauriInvoke('goclaw_connect');
              console.log('GoClaw WebSocket connected');
            } catch (connectError) {
              console.warn('Failed to connect to GoClaw WebSocket:', connectError);
              // 继续执行，不阻止消息发送
            }
          } else {
            console.warn('GoClaw is not running, starting...');
            try {
              await tauriInvoke('goclaw_start');
              console.log('GoClaw started, waiting for ready...');
              // 等待 GoClaw 启动
              await new Promise(resolve => setTimeout(resolve, 2000));
              try {
                await tauriInvoke('goclaw_connect');
                console.log('GoClaw WebSocket connected after startup');
              } catch (connectError) {
                console.warn('Failed to connect to GoClaw WebSocket after startup:', connectError);
              }
            } catch (startError) {
              console.warn('Failed to start GoClaw:', startError);
              // 继续执行，不阻止消息发送
            }
          }

          // 发送消息到 GoClaw
          try {
            console.log('Sending message to GoClaw...');
            const response = await tauriInvoke<any>('cowork_send_message', {
              session_id: options.sessionId,
              content: options.prompt,
            });
            console.log('GoClaw message response:', response);
          } catch (sendError) {
            console.error('Failed to send message to GoClaw:', sendError);
            // 继续执行，不阻止消息发送
          }
        } catch (goclawError) {
          console.warn('GoClaw integration failed:', goclawError);
          // 继续执行，不阻止消息发送
        }
      }

      store.dispatch(addMessage({ sessionId: options.sessionId, message: userMessage }));

      return true;
    } catch (error) {
      store.dispatch(setStreaming(false));
      store.dispatch(updateSessionStatus({ sessionId: options.sessionId, status: 'error' }));
      console.error('Failed to continue session:', error);
      return false;
    }
  }

  async stopSession(sessionId: string): Promise<boolean> {
    const cowork = window.electron?.cowork;
    if (!cowork) return false;

    const result = await cowork.stopSession(sessionId);
    if (result.success) {
      store.dispatch(setStreaming(false));
      store.dispatch(updateSessionStatus({ sessionId, status: 'idle' }));
      return true;
    }

    console.error('Failed to stop session:', result.error);
    return false;
  }

  async deleteSession(sessionId: string): Promise<boolean> {
    try {
      if (isTauriReady()) {
        // 从数据库中删除
        try {
          await tauriInvoke('db_cowork_delete_session', {
            id: sessionId,
          });
        } catch (dbError) {
          console.error('Failed to delete session from database:', dbError);
        }

        // 从 KV 存储中删除
        try {
          await tauriInvoke('cowork_delete_session', {
            id: sessionId,
          });
        } catch (kvError) {
          console.error('Failed to delete session from KV store:', kvError);
        }
      }

      store.dispatch(deleteSessionAction(sessionId));
      return true;
    } catch (error) {
      console.error('Failed to delete session:', error);
      return false;
    }
  }

  async setSessionPinned(sessionId: string, pinned: boolean): Promise<boolean> {
    try {
      if (isTauriReady()) {
        // 更新数据库
        try {
          await tauriInvoke('db_cowork_update_session', {
            id: sessionId,
            pinned: pinned,
          });
        } catch (dbError) {
          console.error('Failed to update session pinned in database:', dbError);
        }

        // 更新 KV 存储
        try {
          await tauriInvoke('cowork_update_session', {
            id: sessionId,
            pinned: pinned,
          });
        } catch (kvError) {
          console.error('Failed to update session pinned in KV store:', kvError);
        }
      }

      store.dispatch(updateSessionPinned({ sessionId, pinned }));
      return true;
    } catch (error) {
      console.error('Failed to update session pin:', error);
      return false;
    }
  }

  async renameSession(sessionId: string, title: string): Promise<boolean> {
    const normalizedTitle = title.trim();
    if (!normalizedTitle) return false;

    try {
      if (isTauriReady()) {
        // 更新数据库
        try {
          await tauriInvoke('db_cowork_update_session', {
            id: sessionId,
            title: normalizedTitle,
          });
        } catch (dbError) {
          console.error('Failed to update session title in database:', dbError);
        }

        // 更新 KV 存储
        try {
          await tauriInvoke('cowork_update_session', {
            id: sessionId,
            title: normalizedTitle,
          });
        } catch (kvError) {
          console.error('Failed to update session title in KV store:', kvError);
        }
      }

      store.dispatch(updateSessionTitle({ sessionId, title: normalizedTitle }));
      return true;
    } catch (error) {
      console.error('Failed to rename session:', error);
      return false;
    }
  }

  async exportSessionResultImage(options: {
    rect: { x: number; y: number; width: number; height: number };
    defaultFileName?: string;
  }): Promise<{ success: boolean; canceled?: boolean; path?: string; error?: string }> {
    const cowork = window.electron?.cowork;
    if (!cowork?.exportResultImage) {
      return { success: false, error: 'Cowork export API not available' };
    }

    try {
      const result = await cowork.exportResultImage(options);
      return result ?? { success: false, error: 'Failed to export session image' };
    } catch (error) {
      return {
        success: false,
        error: error instanceof Error ? error.message : 'Failed to export session image',
      };
    }
  }

  async captureSessionImageChunk(options: {
    rect: { x: number; y: number; width: number; height: number };
  }): Promise<{ success: boolean; width?: number; height?: number; pngBase64?: string; error?: string }> {
    const cowork = window.electron?.cowork;
    if (!cowork?.captureImageChunk) {
      return { success: false, error: 'Cowork capture API not available' };
    }

    try {
      const result = await cowork.captureImageChunk(options);
      return result ?? { success: false, error: 'Failed to capture session image chunk' };
    } catch (error) {
      return {
        success: false,
        error: error instanceof Error ? error.message : 'Failed to capture session image chunk',
      };
    }
  }

  async saveSessionResultImage(options: {
    pngBase64: string;
    defaultFileName?: string;
  }): Promise<{ success: boolean; canceled?: boolean; path?: string; error?: string }> {
    const cowork = window.electron?.cowork;
    if (!cowork?.saveResultImage) {
      return { success: false, error: 'Cowork save image API not available' };
    }

    try {
      const result = await cowork.saveResultImage(options);
      return result ?? { success: false, error: 'Failed to save session image' };
    } catch (error) {
      return {
        success: false,
        error: error instanceof Error ? error.message : 'Failed to save session image',
      };
    }
  }

  async loadSession(sessionId: string): Promise<CoworkSession | null> {
    const cowork = window.electron?.cowork;
    if (!cowork) return null;

    const result = await cowork.getSession(sessionId);
    if (result.success && result.session) {
      store.dispatch(setCurrentSession(result.session));
      store.dispatch(setStreaming(result.session.status === 'running'));
      return result.session;
    }

    console.error('Failed to load session:', result.error);
    return null;
  }

  async respondToPermission(requestId: string, result: CoworkPermissionResult): Promise<boolean> {
    const cowork = window.electron?.cowork;
    if (!cowork) return false;

    const response = await cowork.respondToPermission({ requestId, result });
    if (response.success) {
      store.dispatch(dequeuePendingPermission({ requestId }));
      return true;
    }

    console.error('Failed to respond to permission:', response.error);
    return false;
  }

  async updateConfig(config: CoworkConfigUpdate): Promise<boolean> {
    try {
      const currentConfig = store.getState().cowork.config;
      const updatedConfig = { ...currentConfig, ...config };

      // 保存到存储
      if (isTauriReady()) {
        try {
          await tauriInvoke('kv_set', {
            key: 'cowork_config',
            value: JSON.stringify(updatedConfig),
          });
        } catch (storeError) {
          console.error('Failed to save config to store:', storeError);
        }
      } else {
        localStorageFallback.set('cowork_config', updatedConfig);
      }

      store.dispatch(setConfig(updatedConfig));
      return true;
    } catch (error) {
      console.error('Failed to update config:', error);
      return false;
    }
  }

  async getApiConfig(): Promise<CoworkApiConfig | null> {
    if (!window.electron?.getApiConfig) {
      return null;
    }
    return window.electron.getApiConfig();
  }

  async checkApiConfig(): Promise<{ hasConfig: boolean; config: CoworkApiConfig | null; error?: string } | null> {
    if (!window.electron?.checkApiConfig) {
      return null;
    }
    return window.electron.checkApiConfig();
  }

  async saveApiConfig(config: CoworkApiConfig): Promise<{ success: boolean; error?: string } | null> {
    if (!window.electron?.saveApiConfig) {
      return null;
    }
    return window.electron.saveApiConfig(config);
  }

  async getSandboxStatus(): Promise<CoworkSandboxStatus | null> {
    if (!window.electron?.cowork?.getSandboxStatus) {
      return null;
    }
    return window.electron.cowork.getSandboxStatus();
  }

  async installSandbox(): Promise<{ success: boolean; status: CoworkSandboxStatus; error?: string } | null> {
    if (!window.electron?.cowork?.installSandbox) {
      return null;
    }
    return window.electron.cowork.installSandbox();
  }

  async listMemoryEntries(input: {
    query?: string;
    status?: 'created' | 'stale' | 'deleted' | 'all';
    includeDeleted?: boolean;
    limit?: number;
    offset?: number;
  }): Promise<CoworkUserMemoryEntry[]> {
    const api = window.electron?.cowork?.listMemoryEntries;
    if (!api) return [];
    const result = await api(input);
    if (!result?.success || !result.entries) return [];
    return result.entries;
  }

  async createMemoryEntry(input: {
    text: string;
    confidence?: number;
    isExplicit?: boolean;
  }): Promise<CoworkUserMemoryEntry | null> {
    const api = window.electron?.cowork?.createMemoryEntry;
    if (!api) return null;
    const result = await api(input);
    if (!result?.success || !result.entry) return null;
    return result.entry;
  }

  async updateMemoryEntry(input: {
    id: string;
    text?: string;
    confidence?: number;
    status?: 'created' | 'stale' | 'deleted';
    isExplicit?: boolean;
  }): Promise<CoworkUserMemoryEntry | null> {
    const api = window.electron?.cowork?.updateMemoryEntry;
    if (!api) return null;
    const result = await api(input);
    if (!result?.success || !result.entry) return null;
    return result.entry;
  }

  async deleteMemoryEntry(input: { id: string }): Promise<boolean> {
    const api = window.electron?.cowork?.deleteMemoryEntry;
    if (!api) return false;
    const result = await api(input);
    return Boolean(result?.success);
  }

  async getMemoryStats(): Promise<CoworkMemoryStats | null> {
    const api = window.electron?.cowork?.getMemoryStats;
    if (!api) return null;
    const result = await api();
    if (!result?.success || !result.stats) return null;
    return result.stats;
  }

  onSandboxDownloadProgress(callback: (progress: CoworkSandboxProgress) => void): () => void {
    if (!window.electron?.cowork?.onSandboxDownloadProgress) {
      return () => {};
    }
    return window.electron.cowork.onSandboxDownloadProgress(callback);
  }

  async generateSessionTitle(prompt: string | null): Promise<string | null> {
    if (!window.electron?.generateSessionTitle) {
      return null;
    }
    return window.electron.generateSessionTitle(prompt);
  }

  async getRecentCwds(limit?: number): Promise<string[]> {
    if (!window.electron?.getRecentCwds) {
      return [];
    }
    return window.electron.getRecentCwds(limit);
  }

  clearSession(): void {
    store.dispatch(clearCurrentSession());
  }

  destroy(): void {
    this.cleanupListeners();
    this.initialized = false;
  }
}

export const coworkService = new CoworkService();
