/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import { autoUpdater } from 'electron-updater';
import type { ProgressInfo, UpdateFileInfo } from 'electron-updater';
import { UpdateError, updateErrorDetail } from '@/common/update/updateErrors';
import { app, autoUpdater as nativeAutoUpdater } from 'electron';
import log from 'electron-log';
import fs from 'fs';
import path from 'path';
import {
  recordAutoUpdateNativeInstallError,
  recordAutoUpdateNativeInstallReady,
  recordAutoUpdateNativeInstallTimeout,
  recordAutoUpdateQuitAndInstall,
} from './autoUpdateDiagnostics';
import { isNewerVersion } from './githubReleases';
import type { RestartUpdate, RestartUpdater } from './updateService';

/** Unpacking the app and checking its signature can take a while on a slow Mac; beyond this, the update failed. */
const SQUIRREL_READY_TIMEOUT_MS = 5 * 60_000;
/** How long the installer has to start after quitAndInstall before the handover counts as failed. */
const QUIT_HANDOFF_TIMEOUT_MS = 5_000;
const EXIT_AFTER_HANDOFF_MS = 1_000;

const diagnosticOptions = () => ({ currentAppVersion: app.getVersion(), userDataPath: app.getPath('userData') });

/**
 * The size of the file electron-updater downloads here, as the feed gives it: the zip for this Mac's architecture
 * (arm64 or not, as MacUpdater picks it), or the installer whose name holds this PC's architecture. Unknown when the
 * feed does not say (electron-builder writes no size for an NSIS installer without a differential package).
 */
const downloadSize = (files: readonly UpdateFileInfo[] | undefined): number | undefined => {
  const mac = process.platform === 'darwin';
  const own = (files ?? []).filter((file) => file.url.toLowerCase().endsWith(mac ? '.zip' : '.exe'));
  const arm64 = process.arch === 'arm64';
  const file =
    own.find((candidate) => (mac ? candidate.url.includes('arm64') === arm64 : candidate.url.includes(process.arch))) ??
    own[0];
  return file?.size && file.size > 0 ? file.size : undefined;
};

/**
 * electron-updater, for the systems where an update installs itself on a restart: macOS (Squirrel.Mac, from the zip)
 * and Windows (the NSIS installer). The feed is the releases of github.com/samcaicn/gloai, all of which are pre-releases:
 * electron-updater reads the newest one's latest-mac.yml or latest.yml, which list every architecture's file.
 */
export class AutoUpdaterService implements RestartUpdater {
  private configured = false;
  /** macOS: Squirrel.Mac holds the downloaded update, so a restart installs it. */
  private squirrelReady = false;
  private offeredVersion: string | undefined;
  private activeDownload: Promise<void> | null = null;
  private onProgress: ((percent: number) => void) | null = null;

  private updater(): typeof autoUpdater {
    if (!this.configured) {
      this.configured = true;
      autoUpdater.logger = log;
      // A check never downloads: the update downloads only when the person chooses 下载 (download).
      autoUpdater.autoDownload = false;
      // "Later" still updates: the downloaded update installs when mu quits.
      autoUpdater.autoInstallOnAppQuit = true;
      // mu's releases are GitHub pre-releases. No channel is set: with none, electron-updater reads the newest
      // release, while a channel would also allow downgrades.
      autoUpdater.allowPrerelease = true;
      autoUpdater.disableWebInstaller = true;
      autoUpdater.setFeedURL({ provider: 'github', owner: 'samcaicn', repo: 'gloai' });
      autoUpdater.on('download-progress', (progress: ProgressInfo) => this.onProgress?.(progress.percent));
    }
    return autoUpdater;
  }

  async check(): Promise<RestartUpdate | null> {
    const result = await this.updater().checkForUpdates();
    if (!result?.isUpdateAvailable) return null;
    const { version, files } = result.updateInfo;
    // electron-updater compares too; this keeps a version that is not newer from ever being offered.
    if (!isNewerVersion(version, app.getVersion())) return null;
    this.offeredVersion = version;
    return { version, size: downloadSize(files) };
  }

  download(onProgress: (percent: number) => void): Promise<void> {
    this.onProgress = onProgress;
    if (!this.activeDownload) {
      this.activeDownload = this.runDownload().finally(() => {
        this.activeDownload = null;
        this.onProgress = null;
      });
    }
    return this.activeDownload;
  }

  private async runDownload(): Promise<void> {
    const updater = this.updater();
    if (process.platform !== 'darwin') {
      await updater.downloadUpdate();
      return;
    }
    // On a Mac electron-updater downloads the zip and hands it to Squirrel.Mac, which unpacks it and checks its
    // signature. Only then can a restart install it, so the update is ready when Squirrel says so, not before.
    this.squirrelReady = false;
    const squirrel = this.waitForSquirrel();
    try {
      await updater.downloadUpdate();
    } catch (error) {
      squirrel.cancel();
      throw error;
    }
    await squirrel.ready;
  }

  private waitForSquirrel(): { ready: Promise<void>; cancel: () => void } {
    const startedAt = Date.now();
    const version = this.offeredVersion;
    let settle: ((error?: Error) => void) | null = null;
    const ready = new Promise<void>((resolve, reject) => {
      const onReady = () => settle?.();
      // Downloaded, but not installable: an unsigned build, a signature that does not match, a read-only volume.
      const onError = (error: Error) =>
        settle?.(
          new UpdateError({ code: 'prepareInstallFailed' }, `Squirrel.Mac refused the update: ${error.message}`, {
            cause: error,
          })
        );
      const timer = setTimeout(
        () =>
          settle?.(
            new UpdateError(
              { code: 'prepareInstallTimeout' },
              `Squirrel.Mac did not get the update ready within ${SQUIRREL_READY_TIMEOUT_MS} ms`
            )
          ),
        SQUIRREL_READY_TIMEOUT_MS
      );
      settle = (error) => {
        settle = null;
        clearTimeout(timer);
        nativeAutoUpdater.removeListener('update-downloaded', onReady);
        nativeAutoUpdater.removeListener('error', onError);
        if (error) reject(error);
        else resolve();
      };
      nativeAutoUpdater.on('update-downloaded', onReady);
      nativeAutoUpdater.on('error', onError);
    });
    const recorded = ready.then(
      () => {
        this.squirrelReady = true;
        log.info('[update] Squirrel.Mac has the update ready', { version });
        recordAutoUpdateNativeInstallReady({ elapsedMs: Date.now() - startedAt, version }, diagnosticOptions());
      },
      (error: unknown) => {
        const elapsedMs = Date.now() - startedAt;
        if (error instanceof UpdateError && error.info.code === 'prepareInstallTimeout') {
          recordAutoUpdateNativeInstallTimeout({ elapsedMs, version }, diagnosticOptions());
        } else {
          recordAutoUpdateNativeInstallError(
            { elapsedMs, error: updateErrorDetail(error), version },
            diagnosticOptions()
          );
        }
        throw error;
      }
    );
    // The download itself can fail first; the wait is then cancelled and nobody awaits it.
    recorded.catch((): void => undefined);
    return { ready: recorded, cancel: () => settle?.(new Error('The download failed before Squirrel.Mac got it')) };
  }

  async prepareInstall(): Promise<void> {
    if (process.platform === 'darwin' && !this.squirrelReady) {
      throw new UpdateError({ code: 'prepareInstallFailed' }, 'Squirrel.Mac holds no update to install');
    }
  }

  async quitAndInstall(): Promise<void> {
    this.moveCwdOutOfInstallDirForWindowsHandoff();
    // Both installers announce the handover with before-quit-for-update: Squirrel.Mac natively, electron-updater's
    // NSIS updater by emitting it once the installer runs. No event means the installer did not start.
    const handedOver = new Promise<boolean>((resolve) => {
      const onHandover = () => {
        clearTimeout(timer);
        resolve(true);
      };
      const timer = setTimeout(() => {
        nativeAutoUpdater.removeListener('before-quit-for-update', onHandover);
        resolve(false);
      }, QUIT_HANDOFF_TIMEOUT_MS);
      nativeAutoUpdater.once('before-quit-for-update', onHandover);
    });
    // Not silent: on Windows the installer shows its progress, then starts mu again.
    this.updater().quitAndInstall(false, true);
    if (!(await handedOver)) {
      throw new UpdateError({ code: 'prepareInstallFailed' }, 'The update installer did not start');
    }
    recordAutoUpdateQuitAndInstall(diagnosticOptions());
    // mu can stay open with its windows closed (close to tray; macOS keeps an app running): make sure it exits, so
    // the installer can replace it.
    setTimeout(() => app.exit(0), EXIT_AFTER_HANDOFF_MS);
  }

  /** Windows: the installer replaces the install folder, which must not be this process's working directory. */
  private moveCwdOutOfInstallDirForWindowsHandoff(): void {
    if (process.platform !== 'win32') return;
    try {
      const safeCwd = path.join(app.getPath('temp'), 'mu-updater-cwd');
      fs.mkdirSync(safeCwd, { recursive: true });
      process.chdir(safeCwd);
      log.info('[update] Moved the working directory before the installer handover', { cwd: safeCwd });
    } catch (error) {
      log.warn('[update] Could not move the working directory before the installer handover', {
        error: updateErrorDetail(error),
      });
    }
  }
}
