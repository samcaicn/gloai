import { store } from '../store';
import { tauriApi, isTauriReady, localStorageFallback } from './tauriApi';
import { loggerService } from './logger';
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

    // 添加超时机制，确保初始化过程不会卡住
    try {
      const timeoutPromise = new Promise<void>((_, reject) => {
        setTimeout(() => reject(new Error('Cowork service initialization timeout')), 5000);
      });

      await Promise.race([
        (async () => {
          // Load initial config
          await this.loadConfig();

          // Load sessions list
          await this.loadSessions();

          // Set up stream listeners
          await this.setupStreamListeners();
        })(),
        timeoutPromise
      ]);

      this.initialized = true;
    } catch (error) {
      console.error('Cowork service initialization failed:', error);
      // 即使失败也继续，不影响应用启动
      this.initialized = true;
    }
  }

  private async setupStreamListeners(): Promise<void> {
    if (!isTauriReady()) return;

    // Clean up any existing listeners
    this.cleanupListeners();

    // Message listener - also check if session exists (for IM-created sessions)
    const messageCleanup = await tauriApi.on('cowork_message', async ({ sessionId, message }) => {
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
    const messageUpdateCleanup = await tauriApi.on('cowork_message_update', ({ sessionId, messageId, content }) => {
      store.dispatch(updateMessageContent({ sessionId, messageId, content }));
    });
    this.streamListenerCleanups.push(messageUpdateCleanup);

    // Permission request listener
    const permissionCleanup = await tauriApi.on('cowork_permission', ({ sessionId, request }) => {
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
    const completeCleanup = await tauriApi.on('cowork_complete', ({ sessionId }) => {
      store.dispatch(updateSessionStatus({ sessionId, status: 'completed' }));
    });
    this.streamListenerCleanups.push(completeCleanup);

    // Error listener
    const errorCleanup = await tauriApi.on('cowork_error', ({ sessionId }) => {
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
      const sessions = await tauriApi.invoke('cowork_list_sessions');
      if (sessions && Array.isArray(sessions)) {
        store.dispatch(setSessions(sessions));
      }
    } catch (error) {
      console.error('Failed to load sessions:', error);
      // 尝试使用数据库 API
      try {
        const dbSessions = await tauriApi.invoke('cowork_list_sessions');
        if (dbSessions && Array.isArray(dbSessions)) {
          store.dispatch(setSessions(dbSessions));
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
      const configJson = await tauriApi.invoke<string>('kv_get', { key: 'cowork_config' });
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
      loggerService.info(`Starting new session with options: ${JSON.stringify(options)}`);
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

      loggerService.info(`Created session ${sessionId} with title: ${session.title}`);

      // 保存到数据库（非阻塞，即使失败也继续执行）
      if (isTauriReady()) {
        // 保存到数据库
        setTimeout(async () => {
          try {
            loggerService.info(`Saving session ${sessionId} to database`);
            await tauriApi.invoke('cowork_create_session', {
              title: session.title,
            });
            loggerService.info(`Session ${sessionId} saved to database successfully`);
          } catch (dbError) {
            loggerService.error(`Failed to save session ${sessionId} to database:`, dbError as Error);
          }
        }, 0);

        // 保存到内存存储
        setTimeout(async () => {
          try {
            loggerService.info(`Saving session ${sessionId} to KV store`);
            await tauriApi.invoke('cowork_create_session', {
              title: session.title,
            });
            loggerService.info(`Session ${sessionId} saved to KV store successfully`);
          } catch (kvError) {
            loggerService.error(`Failed to save session ${sessionId} to KV store:`, kvError as Error);
          }
        }, 0);
      }

      // 立即返回会话，不等待GoClaw操作
      store.dispatch(addSession(session));
      loggerService.info(`Session ${sessionId} added to store`);

      // 在后台处理GoClaw连接，不阻塞会话创建
      if (isTauriReady()) {
        setTimeout(async () => {
          // 添加超时机制，确保GoClaw操作不会无限等待
          const timeoutPromise = new Promise<void>((_, reject) => {
            setTimeout(() => reject(new Error('GoClaw operation timeout')), 10000); // 10秒超时
          });
          
          try {
            await Promise.race([
              (async () => {
                loggerService.info('Checking GoClaw status...');
                const isRunning = await tauriApi.invoke('goclaw_is_running');
                loggerService.info(`GoClaw is running: ${isRunning}`);
                
                if (isRunning) {
                  try {
                    loggerService.info('Connecting to GoClaw WebSocket...');
                    await tauriApi.invoke('goclaw_connect');
                    loggerService.info('GoClaw WebSocket connected');
                  } catch (connectError) {
                    loggerService.warn('Failed to connect to GoClaw WebSocket:', connectError as Error);
                    loggerService.info('GoClaw is running but connection failed, continuing without WebSocket');
                  }
                } else {
                  loggerService.warn('GoClaw is not running, starting...');
                  try {
                    // 尝试启动 GoClaw
                    loggerService.info('Attempting to start GoClaw...');
                    const startResult = await tauriApi.invoke('goclaw_start');
                    loggerService.info(`GoClaw start result: ${JSON.stringify(startResult)}`);
                    loggerService.info('GoClaw started, waiting for ready...');
                    // 等待 GoClaw 启动
                    await new Promise(resolve => setTimeout(resolve, 3000)); // 增加等待时间
                    
                    // 检查 GoClaw 是否真的启动成功
                    const isRunningAfterStart = await tauriApi.invoke('goclaw_is_running');
                    loggerService.info(`GoClaw running status after startup: ${isRunningAfterStart}`);
                    
                    if (isRunningAfterStart) {
                      try {
                        loggerService.info('Connecting to GoClaw WebSocket...');
                        await tauriApi.invoke('goclaw_connect');
                        loggerService.info('GoClaw WebSocket connected after startup');
                      } catch (connectError) {
                        loggerService.warn('Failed to connect to GoClaw WebSocket after startup:', connectError as Error);
                        loggerService.info('GoClaw started but WebSocket connection failed, continuing without WebSocket');
                      }
                    } else {
                      loggerService.error('GoClaw failed to start properly');
                      loggerService.info('Continuing without GoClaw');
                    }
                  } catch (startError) {
                    loggerService.error('Failed to start GoClaw:', startError as Error);
                    // 尝试获取更多诊断信息
                    try {
                      const platform = await tauriApi.invoke('get_platform');
                      loggerService.info(`Platform: ${platform}`);
                    } catch (e) {
                      loggerService.warn('Failed to get platform info:', e instanceof Error ? e : new Error(String(e)));
                    }
                    loggerService.info('Continuing without GoClaw');
                  }
                }
              })(),
              timeoutPromise
            ]);
          } catch (goclawError) {
            loggerService.error('GoClaw operation failed:', goclawError as Error);
            // GoClaw 操作失败，但不影响会话创建和任务执行
            loggerService.info('Continuing without GoClaw');
          }
        }, 0);
      }

      loggerService.info(`Session ${sessionId} started successfully`);
      return session;
    } catch (error) {
      store.dispatch(setStreaming(false));
      loggerService.error('Failed to start session:', error as Error);
      return null;
    }
  }

  async continueSession(options: CoworkContinueOptions): Promise<boolean> {
    try {
      loggerService.info(`Continuing session ${options.sessionId} with prompt: ${options.prompt.substring(0, 100)}...`);
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
          loggerService.info(`Saving message ${messageId} to database for session ${options.sessionId}`);
          await tauriApi.invoke('cowork_add_message', {
            id: messageId,
            sessionId: options.sessionId,
            msg_type: 'user',
            content: options.prompt,
          });
          loggerService.info(`Message ${messageId} saved to database successfully`);
        } catch (dbError) {
          loggerService.error(`Failed to save message ${messageId} to database:`, dbError as Error);
        }

        // 在后台处理GoClaw操作，不阻塞消息发送
        setTimeout(async () => {
          // 添加超时机制，确保GoClaw操作不会无限等待
          const timeoutPromise = new Promise<void>((_, reject) => {
            setTimeout(() => reject(new Error('GoClaw operation timeout')), 10000); // 10秒超时
          });
          
          try {
            await Promise.race([
              (async () => {
                // 检查并连接 GoClaw
                loggerService.info('Checking GoClaw status for message sending...');
                const isRunning = await tauriApi.invoke('goclaw_is_running');
                loggerService.info(`GoClaw is running: ${isRunning}`);
                
                if (isRunning) {
                  try {
                    loggerService.info('Connecting to GoClaw WebSocket...');
                    await tauriApi.invoke('goclaw_connect');
                    loggerService.info('GoClaw WebSocket connected');
                  } catch (connectError) {
                    loggerService.warn('Failed to connect to GoClaw WebSocket:', connectError as Error);
                    loggerService.info('GoClaw is running but connection failed, continuing without WebSocket');
                  }
                } else {
                  loggerService.warn('GoClaw is not running, starting...');
                  try {
                    // 尝试启动 GoClaw
                    loggerService.info('Attempting to start GoClaw...');
                    const startResult = await tauriApi.invoke('goclaw_start');
                    loggerService.info(`GoClaw start result: ${JSON.stringify(startResult)}`);
                    loggerService.info('GoClaw started, waiting for ready...');
                    // 等待 GoClaw 启动
                    await new Promise(resolve => setTimeout(resolve, 3000)); // 增加等待时间
                    
                    // 检查 GoClaw 是否真的启动成功
                    const isRunningAfterStart = await tauriApi.invoke('goclaw_is_running');
                    loggerService.info(`GoClaw running status after startup: ${isRunningAfterStart}`);
                    
                    if (isRunningAfterStart) {
                      try {
                        loggerService.info('Connecting to GoClaw WebSocket...');
                        await tauriApi.invoke('goclaw_connect');
                        loggerService.info('GoClaw WebSocket connected after startup');
                      } catch (connectError) {
                        loggerService.warn('Failed to connect to GoClaw WebSocket after startup:', connectError as Error);
                        loggerService.info('GoClaw started but WebSocket connection failed, continuing without WebSocket');
                      }
                    } else {
                      loggerService.error('GoClaw failed to start properly');
                      loggerService.info('Continuing without GoClaw');
                    }
                  } catch (startError) {
                    loggerService.error('Failed to start GoClaw:', startError as Error);
                    // 尝试获取更多诊断信息
                    try {
                      const platform = await tauriApi.invoke('get_platform');
                      loggerService.info(`Platform: ${platform}`);
                    } catch (e) {
                      loggerService.warn('Failed to get platform info:', e instanceof Error ? e : new Error(String(e)));
                    }
                    loggerService.info('Continuing without GoClaw');
                    return;
                  }
                }

                // 发送消息到 GoClaw
                try {
                  loggerService.info(`Sending message to GoClaw for session ${options.sessionId}...`);
                  const response = await tauriApi.invoke('cowork_send_message', {
                    session_id: options.sessionId,
                    content: options.prompt,
                  });
                  loggerService.info(`GoClaw message response for session ${options.sessionId}: ${JSON.stringify(response)}`);
                } catch (sendError) {
                  loggerService.error(`Failed to send message to GoClaw for session ${options.sessionId}:`, sendError as Error);
                  // GoClaw 消息发送失败，但不影响任务执行
                  loggerService.info('Continuing without GoClaw message send');
                }
              })(),
              timeoutPromise
            ]);
          } catch (goclawError) {
            loggerService.error('GoClaw integration failed:', goclawError as Error);
            // GoClaw 集成失败，但不影响任务执行
            loggerService.info('Continuing without GoClaw');
          }
        }, 0);
      }

      store.dispatch(addMessage({ sessionId: options.sessionId, message: userMessage }));
      loggerService.info(`Message ${messageId} added to session ${options.sessionId}`);

      return true;
    } catch (error) {
      store.dispatch(setStreaming(false));
      store.dispatch(updateSessionStatus({ sessionId: options.sessionId, status: 'error' }));
      loggerService.error(`Failed to continue session ${options.sessionId}:`, error as Error);
      return false;
    }
  }

  async stopSession(sessionId: string): Promise<boolean> {
    if (!isTauriReady()) return false;

    try {
      const result = await tauriApi.invoke<{ success: boolean; error?: string }>('cowork_stop_session', { sessionId });
      if (result.success) {
        store.dispatch(setStreaming(false));
        store.dispatch(updateSessionStatus({ sessionId, status: 'idle' }));
        return true;
      }
      console.error('Failed to stop session:', result.error);
      return false;
    } catch (error) {
      console.error('Failed to stop session:', error);
      return false;
    }
  }

  async deleteSession(sessionId: string): Promise<boolean> {
    try {
      if (isTauriReady()) {
        // 从数据库中删除
        try {
          await tauriApi.invoke('cowork_delete_session', {
            id: sessionId,
          });
        } catch (dbError) {
          console.error('Failed to delete session from database:', dbError);
        }

        // 从 KV 存储中删除
        try {
          await tauriApi.invoke('cowork_delete_session', {
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
          await tauriApi.invoke('cowork_update_session', {
            id: sessionId,
            pinned: pinned,
          });
        } catch (dbError) {
          console.error('Failed to update session pinned in database:', dbError);
        }

        // 更新 KV 存储
        try {
          await tauriApi.invoke('cowork_update_session', {
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
          await tauriApi.invoke('cowork_update_session', {
            id: sessionId,
            title: normalizedTitle,
          });
        } catch (dbError) {
          console.error('Failed to update session title in database:', dbError);
        }

        // 更新 KV 存储
        try {
          await tauriApi.invoke('cowork_update_session', {
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
    if (!isTauriReady()) {
      return { success: false, error: 'Tauri not ready' };
    }

    try {
      const result = await tauriApi.invoke<{ success: boolean; canceled?: boolean; path?: string; error?: string }>('cowork_export_result_image', options);
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
    if (!isTauriReady()) {
      return { success: false, error: 'Tauri not ready' };
    }

    try {
      const result = await tauriApi.invoke<{ success: boolean; width?: number; height?: number; pngBase64?: string; error?: string }>('cowork_capture_image_chunk', options);
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
    if (!isTauriReady()) {
      return { success: false, error: 'Tauri not ready' };
    }

    try {
      const result = await tauriApi.invoke<{ success: boolean; canceled?: boolean; path?: string; error?: string }>('cowork_save_result_image', options);
      return result ?? { success: false, error: 'Failed to save session image' };
    } catch (error) {
      return {
        success: false,
        error: error instanceof Error ? error.message : 'Failed to save session image',
      };
    }
  }

  async loadSession(sessionId: string): Promise<CoworkSession | null> {
    if (!isTauriReady()) return null;

    try {
      // 添加超时机制，确保会话加载不会无限等待
      const timeoutPromise = new Promise<never>((_, reject) => {
        setTimeout(() => reject(new Error('Session loading timeout')), 10000); // 10秒超时
      });
      
      const result = await Promise.race([tauriApi.invoke<{ success: boolean; session?: CoworkSession; error?: string }>('cowork_get_session', { sessionId }), timeoutPromise]);
      
      if (result.success && result.session) {
        store.dispatch(setCurrentSession(result.session));
        store.dispatch(setStreaming(result.session.status === 'running'));
        return result.session;
      }

      console.error('Failed to load session:', result.error);
      return null;
    } catch (error) {
      console.error('Failed to load session:', error);
      return null;
    }
  }

  async respondToPermission(requestId: string, result: CoworkPermissionResult): Promise<boolean> {
    if (!isTauriReady()) return false;

    try {
      const response = await tauriApi.invoke<{ success: boolean; error?: string }>('cowork_respond_to_permission', { requestId, result });
      if (response.success) {
        store.dispatch(dequeuePendingPermission({ requestId }));
        return true;
      }

      console.error('Failed to respond to permission:', response.error);
      return false;
    } catch (error) {
      console.error('Failed to respond to permission:', error);
      return false;
    }
  }

  async updateConfig(config: CoworkConfigUpdate): Promise<boolean> {
    try {
      const currentConfig = store.getState().cowork.config;
      const updatedConfig = { ...currentConfig, ...config };

      // 保存到存储
      if (isTauriReady()) {
        try {
          await tauriApi.invoke('kv_set', {
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
    if (!isTauriReady()) return null;
    try {
      return await tauriApi.invoke('cowork_get_api_config');
    } catch (error) {
      console.error('Failed to get API config:', error);
      return null;
    }
  }

  async checkApiConfig(): Promise<{ hasConfig: boolean; config: CoworkApiConfig | null; error?: string } | null> {
    if (!isTauriReady()) return null;
    try {
      return await tauriApi.invoke('cowork_check_api_config');
    } catch (error) {
      console.error('Failed to check API config:', error);
      return null;
    }
  }

  async saveApiConfig(config: CoworkApiConfig): Promise<{ success: boolean; error?: string } | null> {
    if (!isTauriReady()) return null;
    try {
      return await tauriApi.invoke('cowork_save_api_config', { config });
    } catch (error) {
      console.error('Failed to save API config:', error);
      return null;
    }
  }

  async getSandboxStatus(): Promise<CoworkSandboxStatus | null> {
    if (!isTauriReady()) return null;
    try {
      return await tauriApi.invoke('cowork_get_sandbox_status');
    } catch (error) {
      console.error('Failed to get sandbox status:', error);
      return null;
    }
  }

  async installSandbox(): Promise<{ success: boolean; status: CoworkSandboxStatus; error?: string } | null> {
    if (!isTauriReady()) return null;
    try {
      return await tauriApi.invoke('cowork_install_sandbox');
    } catch (error) {
      console.error('Failed to install sandbox:', error);
      return null;
    }
  }

  async listMemoryEntries(input: {
    query?: string;
    status?: 'created' | 'stale' | 'deleted' | 'all';
    includeDeleted?: boolean;
    limit?: number;
    offset?: number;
  }): Promise<CoworkUserMemoryEntry[]> {
    if (!isTauriReady()) return [];
    try {
      const result = await tauriApi.invoke<{ success: boolean; entries?: CoworkUserMemoryEntry[] }>('cowork_list_memory_entries', input);
      if (result.success && result.entries) return result.entries;
      return [];
    } catch (error) {
      console.error('Failed to list memory entries:', error);
      return [];
    }
  }

  async createMemoryEntry(input: {
    text: string;
    confidence?: number;
    isExplicit?: boolean;
  }): Promise<CoworkUserMemoryEntry | null> {
    if (!isTauriReady()) return null;
    try {
      const result = await tauriApi.invoke<{ success: boolean; entry?: CoworkUserMemoryEntry }>('cowork_create_memory_entry', input);
      if (result.success && result.entry) return result.entry;
      return null;
    } catch (error) {
      console.error('Failed to create memory entry:', error);
      return null;
    }
  }

  async updateMemoryEntry(input: {
    id: string;
    text?: string;
    confidence?: number;
    status?: 'created' | 'stale' | 'deleted';
    isExplicit?: boolean;
  }): Promise<CoworkUserMemoryEntry | null> {
    if (!isTauriReady()) return null;
    try {
      const result = await tauriApi.invoke<{ success: boolean; entry?: CoworkUserMemoryEntry }>('cowork_update_memory_entry', input);
      if (result.success && result.entry) return result.entry;
      return null;
    } catch (error) {
      console.error('Failed to update memory entry:', error);
      return null;
    }
  }

  async deleteMemoryEntry(input: { id: string }): Promise<boolean> {
    if (!isTauriReady()) return false;
    try {
      const result = await tauriApi.invoke<{ success: boolean }>('cowork_delete_memory_entry', input);
      return Boolean(result.success);
    } catch (error) {
      console.error('Failed to delete memory entry:', error);
      return false;
    }
  }

  async getMemoryStats(): Promise<CoworkMemoryStats | null> {
    if (!isTauriReady()) return null;
    try {
      const result = await tauriApi.invoke<{ success: boolean; stats?: CoworkMemoryStats }>('cowork_get_memory_stats');
      if (result.success && result.stats) return result.stats;
      return null;
    } catch (error) {
      console.error('Failed to get memory stats:', error);
      return null;
    }
  }

  async onSandboxDownloadProgress(callback: (progress: CoworkSandboxProgress) => void): Promise<() => void> {
    if (!isTauriReady()) {
      return () => {};
    }
    return await tauriApi.on('cowork_sandbox_download_progress', callback);
  }

  async generateSessionTitle(prompt: string | null): Promise<string | null> {
    if (!isTauriReady()) return null;
    try {
      return await tauriApi.invoke('cowork_generate_session_title', { prompt });
    } catch (error) {
      console.error('Failed to generate session title:', error);
      return null;
    }
  }

  async getRecentCwds(limit?: number): Promise<string[]> {
    if (!isTauriReady()) return [];
    try {
      return await tauriApi.invoke('cowork_get_recent_cwds', { limit });
    } catch (error) {
      console.error('Failed to get recent cwds:', error);
      return [];
    }
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
