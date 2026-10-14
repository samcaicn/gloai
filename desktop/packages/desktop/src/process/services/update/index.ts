/**
 * @license
 * Copyright 2026 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import { ipcBridge } from '@/common';
import { app, shell } from 'electron';
import log from 'electron-log';
import { recordUpdateState } from './autoUpdateDiagnostics';
import { AutoUpdaterService } from './autoUpdaterService';
import { fetchLatestRelease } from './githubReleases';
import { downloadInstaller } from './installerDownload';
import { consumeInstallerLastFailure } from './installerLastFailure';
import { UpdateService } from './updateService';

let service: UpdateService | null = null;

/**
 * The app's one update service. An installed mu on macOS or Windows updates on a restart (electron-updater); Linux
 * and a build run from its sources get the new installer to open instead: installing a .deb takes an administrator's
 * password, which electron-updater could only ask for in a terminal.
 */
export function getUpdateService(): UpdateService {
  if (!service) service = createUpdateService();
  return service;
}

function createUpdateService(): UpdateService {
  const restarts = app.isPackaged && (process.platform === 'darwin' || process.platform === 'win32');
  return new UpdateService({
    currentVersion: app.getVersion(),
    restartUpdater: restarts ? new AutoUpdaterService() : undefined,
    installerReason: process.platform === 'linux' && app.isPackaged ? 'linux' : 'unpackaged',
    latestRelease: () => fetchLatestRelease(),
    downloadInstaller: (asset, onProgress) => downloadInstaller(asset, { dir: app.getPath('downloads'), onProgress }),
    openPath: (filePath) => shell.openPath(filePath),
    showItemInFolder: (filePath) => shell.showItemInFolder(filePath),
    openExternal: (url) => shell.openExternal(url),
    broadcast: (state) => ipcBridge.update.state.emit(state),
    record: (state) =>
      recordUpdateState(state, { currentAppVersion: app.getVersion(), userDataPath: app.getPath('userData') }),
    consumeInstallerFailure:
      process.platform === 'win32'
        ? async () => Boolean(await consumeInstallerLastFailure({ appDataDir: app.getPath('appData') }))
        : undefined,
    log,
  });
}
