import { tauriApi } from './tauriApi';

// 创建与 Electron API 兼容的接口
export function createElectronCompatLayer() {
  // 获取实际平台信息
  let platform = 'unknown';
  if (typeof window !== 'undefined' && navigator) {
    platform = navigator.platform;
  }
  
  const compatLayer = {
    platform: platform,
    store: {
      get: (key: string) => tauriApi.store.get(key),
      set: (key: string, value: any) => tauriApi.store.set(key, value),
      remove: (key: string) => tauriApi.store.remove(key),
    },
    skills: {
      list: () => tauriApi.skills.list(),
      setEnabled: (skillId: string, enabled: boolean) => tauriApi.skills.setEnabled(skillId, enabled),
      delete: (skillId: string) => tauriApi.skills.delete(skillId),
      download: async () => {},
      getRoot: () => tauriApi.skills.getRoot(),
      autoRoutingPrompt: async () => '',
      getConfig: async () => ({}),
      setConfig: async () => {},
      testEmailConnectivity: async () => false,
      onChanged: () => () => {},
    },
    permissions: {
      checkCalendar: async () => false,
      requestCalendar: async () => false,
    },
    api: {
      fetch: async () => ({ ok: true, json: async () => ({}) }),
      stream: async () => {},
      cancelStream: async () => {},
      onStreamData: () => () => {},
      onStreamDone: () => () => {},
      onStreamError: () => () => {},
      onStreamAbort: () => () => {},
    },
    ipcRenderer: {
      send: () => {},
      on: () => () => {},
    },
    window: {
      minimize: () => tauriApi.window.minimize(),
      toggleMaximize: () => tauriApi.window.toggleMaximize(),
      close: () => tauriApi.window.close(),
      isMaximized: () => tauriApi.window.isMaximized(),
      showSystemMenu: () => console.warn('window.showSystemMenu not implemented'),
      onStateChanged: () => () => {},
    },
    getApiConfig: async () => ({}),
    checkApiConfig: async () => false,
    saveApiConfig: async () => {},
    generateSessionTitle: async () => '',
    getRecentCwds: async () => [],
    cowork: {
      startSession: async () => ({ sessionId: '' }),
      continueSession: async () => {},
      stopSession: async () => {},
      deleteSession: async () => {},
      setSessionPinned: async () => {},
      renameSession: async () => {},
      getSession: async (sessionId: string) => {
        // 从 store 中获取会话信息
        try {
          const { store } = await import('../store');
          const state = store.getState();
          const session = state.cowork.sessions.find(s => s.id === sessionId);
          if (session) {
            return { success: true, session };
          } else {
            return { success: false, error: 'Session not found' };
          }
        } catch (error) {
          console.error('Failed to get session:', error);
          return { success: false, error: String(error) };
        }
      },
      listSessions: async () => [],
      exportResultImage: async () => {},
      captureImageChunk: async () => '',
      saveResultImage: async () => {},
      respondToPermission: async () => {},
      getConfig: async () => ({}),
      setConfig: async () => {},
      listMemoryEntries: async () => [],
      createMemoryEntry: async () => ({ id: '' }),
      updateMemoryEntry: async () => {},
      deleteMemoryEntry: async () => {},
      getMemoryStats: async () => ({}),
      getSandboxStatus: async () => ({ available: false }),
      installSandbox: async () => {},
      onSandboxDownloadProgress: () => () => {},
      onStreamMessage: () => () => {},
      onStreamMessageUpdate: () => () => {},
      onStreamPermission: () => () => {},
      onStreamComplete: () => () => {},
      onStreamError: () => () => {},
    },
    dialog: {
      selectDirectory: async () => tauriApi.dialog.selectDirectory(),
      selectFile: async (options?: { title?: string; filters?: { name: string; extensions: string[] }[] }) => tauriApi.dialog.selectFile(options),
      saveInlineFile: async () => {
        console.warn('dialog.saveInlineFile not implemented');
        return { success: false, path: null, error: 'Not implemented' };
      },
    },
    shell: tauriApi.shell,
    autoLaunch: {
      get: async () => false,
      set: async () => {},
    },
    appInfo: {
      getVersion: () => tauriApi.appInfo.getVersion(),
      getSystemLocale: () => tauriApi.appInfo.getSystemLocale(),
    },
    log: {
      getPath: async () => '',
      openFolder: async () => {},
    },
    im: {
      getConfig: async () => ({}),
      setConfig: async () => {},
      startGateway: async () => {},
      stopGateway: async () => {},
      testGateway: async () => ({ success: false }),
      getStatus: async () => ({}),
      onStatusChange: () => () => {},
      onMessageReceived: () => () => {},
    },
    scheduledTasks: {
      list: async () => [],
      get: async () => null,
      create: async () => ({ id: '' }),
      update: async () => {},
      delete: async () => {},
      toggle: async () => {},
      runManually: async (id: string) => {
        // 模拟任务启动，创建一个新的会话
        try {
          const { coworkService } = await import('./cowork');
          const session = await coworkService.startSession({
            prompt: `执行定时任务: ${id}`,
            title: `定时任务执行: ${id}`,
          });
          
          // 确保会话已完全创建并保存
          if (session) {
            // 等待一小段时间确保所有异步操作完成
            await new Promise(resolve => setTimeout(resolve, 500));
            return { success: true, session };
          } else {
            return { success: false, error: 'Failed to create session' };
          }
        } catch (error) {
          console.error('Failed to run task manually:', error);
          return { success: false, error: String(error) };
        }
      },
      stop: async () => {},
      listRuns: async () => [],
      countRuns: async () => 0,
      listAllRuns: async () => [],
      onStatusUpdate: () => () => {},
      onRunUpdate: () => () => {},
    },
    networkStatus: {
      send: () => {},
    },
    tuptup: {
      setConfig: (config: any) => tauriApi.tuptup.setConfig(config),
      getConfig: () => tauriApi.tuptup.getConfig(),
      clearConfig: () => tauriApi.tuptup.clearConfig(),
      getUserInfo: () => tauriApi.tuptup.getUserInfo(),
      getTokenBalance: () => tauriApi.tuptup.getTokenBalance(),
      getPlan: () => tauriApi.tuptup.getPlan(),
      getOverview: () => tauriApi.tuptup.getOverview(),
    },
  };

  return compatLayer;
}

// 注入到 window 对象
export function injectElectronCompat() {
  if (typeof window !== 'undefined') {
    (window as any).electron = createElectronCompatLayer();
  }
}
