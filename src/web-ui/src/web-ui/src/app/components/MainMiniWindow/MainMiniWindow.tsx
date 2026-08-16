import React, { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { fwClose, fwShowMainWindow } from '@/infrastructure/api/tupai';
import { createLogger } from '@/shared/utils/logger';
import './MainMiniWindow.scss';

const log = createLogger('MainMiniWindow');

export interface MainMiniWindowProps {
  /**
   * Floating entry id parsed from the URL hash
   * (`index.html#/floating-window?id=xxx`).
   * Used for close operation.
   */
  id?: string;
}

const DEVICE_TOKEN_KEY = 'trae_device_token';

// 通过 fwClose 让后端 hide() + destroy() 窗口，避免 window.close()
// 触发 OS 级关闭导致 webview 撕裂黑屏。
function closeMiniWindow(id?: string): void {
  if (id) {
    void fwClose(id).catch(() => { /* 窗口可能已被 destroy */ });
  } else {
    window.close();
  }
}

function pad2(n: number): string {
  return n < 10 ? `0${n}` : String(n);
}

function formatTime(date: Date): string {
  return `${pad2(date.getHours())}:${pad2(date.getMinutes())}:${pad2(date.getSeconds())}`;
}

function formatDate(date: Date): string {
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`;
}

function readDeviceConnected(): boolean {
  try {
    const token = localStorage.getItem(DEVICE_TOKEN_KEY);
    return Boolean(token && token.length > 0);
  } catch (error) {
    log.warn('Failed to read device token from localStorage', error);
    return false;
  }
}

export const MainMiniWindow: React.FC<MainMiniWindowProps> = ({ id }) => {
  const { t } = useTranslation('common');
  const [now, setNow] = useState<Date>(() => new Date());
  const [isDeviceConnected, setIsDeviceConnected] = useState<boolean>(() => readDeviceConnected());
  const [isRestoring, setIsRestoring] = useState<boolean>(false);

  useEffect(() => {
    if (id) {
      log.info('MainMiniWindow mounted', { entryId: id });
    }
  }, [id]);

  // Tick every second.
  useEffect(() => {
    const timer = window.setInterval(() => {
      setNow(new Date());
    }, 1000);
    return () => window.clearInterval(timer);
  }, []);

  // Re-check device connection status when the window regains focus
  // (the token may have been added/removed from the main window).
  useEffect(() => {
    const refresh = () => setIsDeviceConnected(readDeviceConnected());
    window.addEventListener('focus', refresh);
    window.addEventListener('storage', refresh);
    return () => {
      window.removeEventListener('focus', refresh);
      window.removeEventListener('storage', refresh);
    };
  }, []);

  const handleRestoreMainWindow = async () => {
    if (isRestoring) {
      return;
    }
    setIsRestoring(true);
    try {
      await fwShowMainWindow();
      log.info('Main window restore requested', { entryId: id });
    } catch (error) {
      log.error('Failed to restore main window', error);
    } finally {
      setIsRestoring(false);
    }
  };

  const handleClose = useCallback(() => {
    // 先主动恢复主窗口，避免关闭小窗后主窗口仍被隐藏。
    void fwShowMainWindow().catch((err) => {
      log.warn('MainMiniWindow: fwShowMainWindow on close failed', err);
    });
    closeMiniWindow(id);
  }, [id]);

  return (
    <div className="tupai-main-mini-window" role="dialog" aria-label="Tupai Mini Window">
      <div className="tupai-main-mini-window__titlebar" data-tauri-drag-region>
        <span className="tupai-main-mini-window__title" data-tauri-drag-region>tupai</span>
        <button
          type="button"
          className="tupai-main-mini-window__close-btn"
          onClick={handleClose}
          aria-label={t('mainMiniWindow.close')}
        >
          ×
        </button>
      </div>
      <div className="tupai-main-mini-window__clock">
        <div className="tupai-main-mini-window__time">{formatTime(now)}</div>
        <div className="tupai-main-mini-window__date">{formatDate(now)}</div>
      </div>
      <div className="tupai-main-mini-window__status">
        <span
          className={`tupai-main-mini-window__status-dot${
            isDeviceConnected ? ' is-connected' : ''
          }`}
          aria-hidden="true"
        />
        <span className="tupai-main-mini-window__status-text">
          {isDeviceConnected ? t('mainMiniWindow.connected') : t('mainMiniWindow.disconnected')}
        </span>
      </div>
      <button
        type="button"
        className="tupai-main-mini-window__restore-btn"
        onClick={handleRestoreMainWindow}
        disabled={isRestoring}
      >
        {isRestoring ? t('mainMiniWindow.restoring') : t('mainMiniWindow.restoreMainWindow')}
      </button>
    </div>
  );
};

export default MainMiniWindow;
