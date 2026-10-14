/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import type { UpdateErrorInfo } from './updateErrors';

/** One installer of a release, as GitHub lists it. */
export interface GitHubReleaseAsset {
  name: string;
  /** Its download URL on GitHub. */
  url: string;
  size: number;
  contentType?: string;
}

/** A release of github.com/qybaihe/mu. */
export interface UpdateReleaseInfo {
  tagName: string;
  version: string;
  name?: string;
  body?: string;
  htmlUrl: string;
  publishedAt?: string;
  prerelease: boolean;
  assets: GitHubReleaseAsset[];
  /** The installer for this system, when the release has one. */
  recommendedAsset?: GitHubReleaseAsset;
}

export type InstallerLastFailureKind = 'app-cannot-be-closed';
export type InstallerLastFailurePhase = 'customCheckAppRunning';

/** What the Windows installer leaves behind when a silent update could not close mu (installer-process-control.nsh). */
export interface InstallerLastFailureMarker {
  schemaVersion: 1;
  kind: InstallerLastFailureKind;
  phase: InstallerLastFailurePhase;
  silent: true;
  updated: true;
  retryCount: number;
  instDir: string;
  logPath: string;
  at: string;
  blockers?: unknown[];
}

/**
 * How this mu takes an update. `restart`: once the person chooses 下载 (download), electron-updater downloads it and
 * installs it when mu restarts (macOS, Windows). `installer`: the person downloads the new installer and opens it
 * (Linux, where installing a .deb needs an administrator's password in a terminal, and a build that is not
 * installed). Nothing downloads before the person asks for it.
 */
export type UpdateMethod = 'restart' | 'installer';

/** Why an update comes as an installer to open: the notice says so under it. */
export type InstallerReason =
  /** Linux: mu cannot install a .deb by itself. */
  | 'linux'
  /** A build run from its sources, not installed. */
  | 'unpackaged'
  /** The update would install on a restart, but that failed; `error` says why. */
  | 'autoUpdateFailed';

export type UpdatePhase =
  /** No check has finished yet. */
  | 'idle'
  /** The last check found nothing newer. */
  | 'upToDate'
  /** A newer version that installs on a restart (`restart`): it downloads once the person chooses 下载. */
  | 'found'
  /** The person chose 下载: the update is downloading (`restart`). */
  | 'downloading'
  /** The update is downloaded: it installs on 重启并更新 (restart and update), or on the next quit. */
  | 'ready'
  /** 重启并更新 was chosen: mu is about to quit and install. */
  | 'installing'
  /** A newer release whose installer can be downloaded (`installer`, or after the restart way failed). */
  | 'available'
  /** Its installer is downloading. */
  | 'downloadingInstaller'
  /** Its installer is in the Downloads folder, ready to open. */
  | 'installerReady'
  /** The last check failed, and nothing newer is known. */
  | 'failed';

/** Everything the app shows about updates: the notice in the sidebar and the update row of 关于 (About). */
export interface UpdateState {
  phase: UpdatePhase;
  /** A check is running. A check in the background leaves the phase as it is until it finds something. */
  checking: boolean;
  method: UpdateMethod;
  /** The version running now. */
  currentVersion: string;
  /** The newer version, once one is known. */
  version?: string;
  /** `found`: how many bytes 下载 fetches, as the update feed gives it; unknown when the feed does not say. */
  size?: number;
  /** Download progress, 0-100, while the update or its installer downloads. */
  percent?: number;
  /** Why the last step failed. */
  error?: UpdateErrorInfo;
  /** `available` and after: why this update comes as an installer. */
  installerReason?: InstallerReason;
  /** `available` and after: the file name of the installer for this system; none when the release has none. */
  installerName?: string;
  /** `installerReady`: where the installer was saved. */
  installerPath?: string;
  /**
   * The person chose 稍后 (later): the sidebar notice stays away, 关于 still offers the update. For a `found` update
   * until the next check; for a downloaded one until a newer version, while it still installs on the next quit.
   */
  dismissed: boolean;
  /** Windows: the last update could not be installed on quit, because mu could not be closed. */
  lastInstallFailed: boolean;
}

/** What the person can do about an update. */
export type UpdateAction =
  | 'check'
  | 'download'
  | 'restart'
  | 'later'
  | 'downloadInstaller'
  | 'openInstaller'
  | 'showInstaller'
  | 'openReleasePage';
