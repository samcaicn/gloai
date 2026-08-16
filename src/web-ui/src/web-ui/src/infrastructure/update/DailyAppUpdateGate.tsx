import { useEffect } from 'react';
import { systemAPI } from '@/infrastructure/api';
import { configManager } from '@/infrastructure/config/services/ConfigManager';
import { createLogger } from '@/shared/utils/logger';
import { scheduleAfterStartupSignal } from '@/shared/utils/startupTaskScheduling';
import type { CheckForUpdatesResponse } from '@/infrastructure/api/service-api/SystemAPI';
import { isTauriRuntime } from './tauriEnv';

const log = createLogger('DailyAppUpdate');

const UPGRADE_PENDING_EVENT = 'upgrade_pending';
const UPGRADE_FAILED_EVENT = 'upgrade_failed';
const TRAY_CHECK_UPDATES_EVENT = 'tray:check-updates-requested';

export function DailyAppUpdateGate(): null {
  useEffect(() => {
    if (!isTauriRuntime()) return;
    let cancelled = false;
    let cancelStartupSchedule: () => void;

    const runDailyCheck = async () => {
      let autoUpdate = true;
      try {
        const v = await configManager.getConfig<boolean>('app.auto_update');
        if (v === false) {
          autoUpdate = false;
        }
      } catch { /* config not ready */ }
      if (!autoUpdate || cancelled) return;
      try {
        const token = localStorage.getItem('trae_device_token') || '';
        if (!token) return;
        const res: CheckForUpdatesResponse = await systemAPI.checkForUpdates(token);
        if (cancelled) return;
        if (res.updateAvailable) {
          await systemAPI.silentDownloadUpgrade(token);
        }
      } catch (e) {
        log.warn('Daily silent update check failed', e);
      }
    };

    const result = scheduleAfterStartupSignal(runDailyCheck, {
      frameCount: 1,
      onError: (error: unknown) => {
        log.warn('Failed to schedule daily update check after startup', error);
      },
    });
    cancelStartupSchedule = result;

    return () => {
      cancelled = true;
      cancelStartupSchedule();
    };
  }, []);

  useEffect(() => {
    if (!isTauriRuntime()) return;
    let disposed = false;
    let unlisten: (() => void) | null = null;

    void import('@tauri-apps/api/event')
      .then(({ listen }) =>
        listen<string>(UPGRADE_PENDING_EVENT, (event) => {
          if (disposed) return;
          const version = event.payload;
          if (version && version !== 'already-in-progress') {
            log.info('Silent upgrade downloaded, pending install', { version });
          }
        }),
      )
      .then((remove) => {
        if (disposed) { remove(); return; }
        unlisten = remove;
      })
      .catch((err) => {
        log.warn('Failed to listen for upgrade_pending event', err);
      });

    return () => {
      disposed = true;
      unlisten?.();
    };
  }, []);

  useEffect(() => {
    if (!isTauriRuntime()) return;
    let disposed = false;
    let unlisten: (() => void) | null = null;

    void import('@tauri-apps/api/event')
      .then(({ listen }) =>
        listen<string>(UPGRADE_FAILED_EVENT, (event) => {
          if (disposed) return;
          log.warn('Silent upgrade failed', { reason: event.payload });
        }),
      )
      .then((remove) => {
        if (disposed) { remove(); return; }
        unlisten = remove;
      })
      .catch((err) => {
        log.warn('Failed to listen for upgrade_failed event', err);
      });

    return () => {
      disposed = true;
      unlisten?.();
    };
  }, []);

  useEffect(() => {
    if (!isTauriRuntime()) return;
    let disposed = false;
    let unlisten: (() => void) | null = null;

    void import('@tauri-apps/api/event')
      .then(({ listen }) =>
        listen(TRAY_CHECK_UPDATES_EVENT, () => {
          if (disposed) return;
          log.info('Tray check-updates requested, triggering silent download');
          const token =
            (typeof localStorage !== 'undefined' &&
              localStorage.getItem('trae_device_token')) ||
            '';
          if (!token) {
            log.warn('Cannot trigger silent download: device token missing');
            return;
          }
          systemAPI
            .silentDownloadUpgrade(token)
            .catch((err) => {
              log.warn('Tray-triggered silent download failed', err);
            });
        }),
      )
      .then((remove) => {
        if (disposed) { remove(); return; }
        unlisten = remove;
      })
      .catch((err) => {
        log.warn('Failed to listen for tray:check-updates-requested', err);
      });

    return () => {
      disposed = true;
      unlisten?.();
    };
  }, []);

  return null;
}
