import { create } from 'zustand';
import { createLogger } from '@/shared/utils/logger';
import {
  installUpdateWithProgress,
  type UpdateDownloadProgressPayload
} from './installUpdateWithProgress';

const log = createLogger('UpdateInstallStore');

export type UpdateInstallStatus = 'idle' | 'downloading' | 'installed' | 'error';

interface UpdateInstallState {
  status: UpdateInstallStatus;
  progress: UpdateDownloadProgressPayload;
  error: string | null;
  startedAt: number | null;
  /**
   * true 表示当前 'installed' 状态来自静默下载完成事件(upgrade_pending),
   * 此时升级包已下载但尚未安装,重启后才触发 NSIS 静默安装。
   * false (默认) 表示来自手动安装路径(install_update),升级包已安装完成。
   * UpdateInstallProgressModal 据此显示不同的提示文案。
   */
  pendingInstall: boolean;
  startInstall: () => Promise<void>;
  clearError: () => void;
  clearInstalled: () => void;
}

const initialProgress: UpdateDownloadProgressPayload = {
  downloaded: 0,
  total: null
};

export const useUpdateInstallStore = create<UpdateInstallState>((set, get) => ({
  status: 'idle',
  progress: initialProgress,
  error: null,
  startedAt: null,
  pendingInstall: false,

  startInstall: async () => {
    const status = get().status;
    if (status === 'downloading' || status === 'installed') {
      return;
    }

    set({
      status: 'downloading',
      progress: initialProgress,
      error: null,
      startedAt: Date.now(),
      pendingInstall: false,
    });

    try {
      await installUpdateWithProgress(progress => {
        set({ progress });
      });
      set({ status: 'installed', error: null, pendingInstall: false });
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      log.error('Background update install failed', error);
      set({ status: 'error', error: message, pendingInstall: false });
    }
  },

  clearError: () => {
    set({
      status: 'idle',
      error: null,
      progress: initialProgress,
      startedAt: null,
      pendingInstall: false,
    });
  },

  clearInstalled: () => {
    set({
      status: 'idle',
      error: null,
      progress: initialProgress,
      startedAt: null,
      pendingInstall: false,
    });
  }
}));
