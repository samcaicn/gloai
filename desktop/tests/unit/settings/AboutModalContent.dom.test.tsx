/**
 * @license
 * Copyright 2026 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

/** 关于 (About): the version that runs, the check for updates, and what the update in hand asks for. */

import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { UpdateState } from '@/common/update/updateTypes';

const mocks = vi.hoisted(() => ({
  isElectron: true,
  getState: vi.fn(),
  run: vi.fn(),
  push: undefined as undefined | ((state: UpdateState) => void),
  messageErrorMock: vi.fn(),
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, params?: Record<string, unknown>) =>
      key === 'update.currentVersion'
        ? `当前版本：${params?.version}`
        : params
          ? `${key}(${Object.values(params).join(',')})`
          : key,
    i18n: { language: 'zh-CN' },
  }),
}));

vi.mock('@arco-design/web-react', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@arco-design/web-react')>();
  return { ...actual, Message: { ...actual.Message, error: mocks.messageErrorMock } };
});

vi.mock('@/common', () => ({
  ipcBridge: {
    update: {
      getState: { invoke: mocks.getState },
      run: { invoke: mocks.run },
      state: {
        on: (callback: (state: UpdateState) => void) => {
          mocks.push = callback;
          return () => {
            mocks.push = undefined;
          };
        },
      },
    },
  },
}));

vi.mock('@/renderer/utils/platform', () => ({
  isElectronDesktop: () => mocks.isElectron,
  openExternalUrl: vi.fn(),
}));

vi.mock('@/renderer/components/settings/SettingsModal/settingsViewContext', () => ({
  useSettingsViewMode: () => 'modal',
}));

import AboutModalContent from '@/renderer/components/settings/SettingsModal/contents/AboutModalContent';

const state = (patch: Partial<UpdateState> = {}): UpdateState => ({
  phase: 'idle',
  checking: false,
  method: 'restart',
  currentVersion: '0.1.2',
  dismissed: false,
  lastInstallFailed: false,
  ...patch,
});

const renderAbout = async (current: UpdateState = state()) => {
  mocks.getState.mockResolvedValue(current);
  render(<AboutModalContent />);
  await act(async () => undefined);
};

describe('AboutModalContent', () => {
  beforeEach(() => {
    mocks.isElectron = true;
    vi.stubGlobal('__APP_VERSION__', '0.1.1');
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    vi.unstubAllGlobals();
  });

  it('shows the version that runs, as the main process says, once: on the row that checks for updates', async () => {
    await renderAbout(state({ currentVersion: '0.1.2' }));
    expect(screen.getByTestId('about-version')).toHaveTextContent('当前版本：v0.1.2');
    expect(screen.queryByText(/0\.1\.1/)).toBeNull();
    // Updates are on; there is nothing to switch.
    expect(screen.queryByRole('switch')).not.toBeInTheDocument();
  });

  it('shows the build version until the main process answers', () => {
    mocks.getState.mockReturnValue(new Promise(() => undefined));
    render(<AboutModalContent />);
    expect(screen.getByTestId('about-version')).toHaveTextContent('当前版本：v0.1.1');
  });

  it('checks for updates and says what it found', async () => {
    mocks.run.mockResolvedValue(state({ phase: 'upToDate' }));
    await renderAbout();
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'settings.checkForUpdates' }));
    });
    expect(mocks.run).toHaveBeenCalledWith({ action: 'check' });
    expect(screen.getByTestId('about-update-status')).toHaveTextContent('update.upToDateTitle');
  });

  it('shows a check in progress on its button', async () => {
    await renderAbout(state({ checking: true, phase: 'upToDate' }));
    expect(screen.getByRole('button', { name: /settings.checkingForUpdates/ })).toBeDisabled();
    // The old answer does not stand beside a new check.
    expect(screen.queryByTestId('about-update-status')).not.toBeInTheDocument();
  });

  it('says in one sentence why a check failed', async () => {
    await renderAbout(state({ phase: 'failed', error: { code: 'serverError', status: 503 } }));
    expect(screen.getByTestId('about-update-status')).toHaveTextContent('update.errors.serverError(503)');
  });

  it('offers a found version with its size: 下载 downloads it, 稍后 puts the sidebar notice off', async () => {
    mocks.run.mockImplementation(async ({ action }: { action: string }) =>
      action === 'download'
        ? state({ phase: 'downloading', version: '0.1.3', percent: 0 })
        : state({ phase: 'found', version: '0.1.3', size: 206_117_345, dismissed: action === 'later' })
    );
    await renderAbout(state({ phase: 'found', version: '0.1.3', size: 206_117_345 }));
    // 206 117 345 bytes are about 197 MB.
    expect(screen.getByTestId('about-update-status')).toHaveTextContent('update.foundVersionSize(0.1.3,197)');
    expect(mocks.run).not.toHaveBeenCalled();

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'update.later' }));
    });
    expect(mocks.run).toHaveBeenCalledWith({ action: 'later' });
    // Put off: 关于 still offers the download, without a second 稍后.
    expect(screen.queryByRole('button', { name: 'update.later' })).not.toBeInTheDocument();

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'update.download' }));
    });
    expect(mocks.run).toHaveBeenCalledWith({ action: 'download' });
    expect(screen.getByTestId('about-update-status')).toHaveTextContent('update.downloadingVersion(0.1.3,0%)');
  });

  it('says the found version without a size when the update feed gives none', async () => {
    await renderAbout(state({ phase: 'found', version: '0.1.3' }));
    expect(screen.getByTestId('about-update-status')).toHaveTextContent('update.foundVersion(0.1.3)');
    expect(screen.getByRole('button', { name: 'update.download' })).toBeInTheDocument();
  });

  it('follows the download and offers the restart once the update is ready', async () => {
    await renderAbout(state({ phase: 'downloading', version: '0.1.3', percent: 42 }));
    expect(screen.getByTestId('about-update-status')).toHaveTextContent('update.downloadingVersion(0.1.3,42%)');
    expect(screen.getByRole('button', { name: 'settings.checkForUpdates' })).toBeDisabled();

    act(() => mocks.push?.(state({ phase: 'ready', version: '0.1.3', dismissed: true })));
    // 稍后 hid the sidebar notice; 关于 still offers the update.
    expect(screen.getByTestId('about-update-status')).toHaveTextContent('update.readyTitle(0.1.3)');
    fireEvent.click(screen.getByRole('button', { name: 'update.restartToUpdate' }));
    expect(mocks.run).toHaveBeenCalledWith({ action: 'restart' });
  });

  it('offers the installer where updates come that way, then opens it or shows it in its folder', async () => {
    await renderAbout(
      state({
        phase: 'available',
        method: 'installer',
        version: '0.1.3',
        installerReason: 'linux',
        installerName: 'mu-0.1.3-linux-amd64.deb',
      })
    );
    expect(screen.getByText('update.installerReason.linux')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'update.downloadInstaller' }));
    expect(mocks.run).toHaveBeenCalledWith({ action: 'downloadInstaller' });

    act(() =>
      mocks.push?.(
        state({ phase: 'installerReady', version: '0.1.3', installerPath: '/home/me/Downloads/mu-0.1.3.deb' })
      )
    );
    fireEvent.click(screen.getByRole('button', { name: 'update.openInstaller' }));
    expect(mocks.run).toHaveBeenCalledWith({ action: 'openInstaller' });
    fireEvent.click(screen.getByRole('button', { name: 'update.showInFolder' }));
    expect(mocks.run).toHaveBeenCalledWith({ action: 'showInstaller' });
  });

  it('opens the log folder from About, and says so when it cannot', async () => {
    const openLogFolder = vi.fn(() => Promise.reject(new Error('denied')));
    Object.assign(window, { electronAPI: { openLogFolder } });
    try {
      await renderAbout();
      fireEvent.click(screen.getByRole('button', { name: 'common.backendStartup.openLogs' }));
      expect(openLogFolder).toHaveBeenCalledTimes(1);
      await waitFor(() => {
        expect(mocks.messageErrorMock).toHaveBeenCalledWith('common.backendStartup.openLogsFailed');
      });
    } finally {
      delete (window as { electronAPI?: unknown }).electronAPI;
    }
  });

  it('offers no log folder where there is none to open', async () => {
    await renderAbout();
    expect(screen.queryByRole('button', { name: 'common.backendStartup.openLogs' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'settings.updateLog' })).toBeInTheDocument();
  });

  it('outside the desktop app shows the version by the name and checks nothing', () => {
    mocks.isElectron = false;
    render(<AboutModalContent />);
    expect(screen.getByTestId('about-version')).toHaveTextContent('v0.1.1');
    expect(screen.queryByRole('button', { name: 'settings.checkForUpdates' })).not.toBeInTheDocument();
    expect(mocks.getState).not.toHaveBeenCalled();
  });
});
