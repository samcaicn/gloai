/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import { Button, Message } from '@arco-design/web-react';
import { FolderOpen, Github, Right } from '@icon-park/react';
import React from 'react';
import MuMark from '@renderer/components/brand/MuMark';
import { useTranslation } from 'react-i18next';
import classNames from 'classnames';
import { useSettingsViewMode } from '../settingsViewContext';
import { isElectronDesktop, openExternalUrl } from '@/renderer/utils/platform';
import type { UpdatePhase } from '@/common/update/updateTypes';
import { useUpdateState } from '@/renderer/hooks/system/useUpdateState';
import { updateDetails, updateHeadline, updateMainAction } from '@/renderer/components/settings/updateText';

// Injected by electron.vite.config.ts `define:`: the app's version (the repository root's package.json), shown until
// the main process says which version runs.
declare const __APP_VERSION__: string;

const MU_REPO_URL = 'https://github.com/qybaihe/mu';
const MU_RELEASES_URL = 'https://github.com/qybaihe/mu/releases';

/** While these run, another check has nothing to add. */
const BUSY_PHASES: ReadonlySet<UpdatePhase> = new Set(['downloading', 'installing', 'downloadingInstaller']);

type LinkItem = { title: string; icon: React.ReactNode; open: () => void };

const AboutModalContent: React.FC = () => {
  const { t, i18n } = useTranslation();
  const viewMode = useSettingsViewMode();
  const isPageMode = viewMode === 'page';
  const isElectron = isElectronDesktop();

  const { state: update, run } = useUpdateState();

  const openLink = async (url: string) => {
    try {
      await openExternalUrl(url);
    } catch (error) {
      console.log('Failed to open link:', error);
    }
  };

  // Only the desktop app has a log folder to open.
  const openLogFolder = window.electronAPI?.openLogFolder;
  const linkItems: LinkItem[] = [
    {
      title: t('common.github'),
      icon: <Github theme='outline' size='16' />,
      open: () => void openLink(MU_REPO_URL).catch((error) => console.error('Failed to open link:', error)),
    },
    {
      title: t('settings.updateLog'),
      icon: <Right theme='outline' size='16' className='rtl-mirror' />,
      open: () => void openLink(MU_RELEASES_URL).catch((error) => console.error('Failed to open link:', error)),
    },
    ...(openLogFolder
      ? [
          {
            title: t('common.backendStartup.openLogs'),
            icon: <FolderOpen theme='outline' size='16' />,
            open: () => {
              void openLogFolder().catch(() => {
                Message.error(t('common.backendStartup.openLogsFailed'));
              });
            },
          },
        ]
      : []),
  ];

  const version = `v${update?.currentVersion ?? __APP_VERSION__}`;
  const checking = update?.checking ?? false;
  // The last check's answer, and what the update in hand is; nothing while a new check runs over an old answer.
  const status =
    update && !(checking && ['idle', 'upToDate', 'failed'].includes(update.phase))
      ? updateHeadline(t, i18n.language, update)
      : null;
  const details = update ? updateDetails(t, update) : [];
  const main = update ? updateMainAction(t, update) : null;
  const rowClass = 'flex min-h-48px items-center justify-between gap-24px py-10px';
  const rowTitleClass = 'text-14px font-500 text-t-primary';

  return (
    <div
      className={classNames('flex w-full flex-col gap-16px', !isPageMode && 'px-24px pb-16px')}
      data-testid='about-content'
    >
      {/* Which mu this is: the mark and the name, set on the left like every settings page. In the app the version
          heads the update row, beside the button that checks it; elsewhere it sits by the name. */}
      <div className='flex items-center gap-16px'>
        <MuMark size={48} halo />
        <div className='flex min-w-0 flex-col gap-4px'>
          <div className='flex items-center gap-8px'>
            <span className='text-18px font-600 leading-24px text-t-primary'>mu</span>
            {isElectron ? null : (
              <span
                className='rd-4px bg-fill-2 px-6px py-2px text-12px font-500 text-t-secondary'
                data-testid='about-version'
              >
                {version}
              </span>
            )}
          </div>
          <span className='text-13px text-t-secondary'>{t('common.kyrn.brand.tagline')}</span>
        </div>
      </div>

      <div className='settings-list'>
        {isElectron ? (
          <div className={rowClass}>
            <div className='flex min-w-0 flex-col gap-2px'>
              <span className={rowTitleClass} data-testid='about-version'>
                {t('update.currentVersion', { version })}
              </span>
              {status ? (
                <span className='text-12px leading-18px text-t-secondary' data-testid='about-update-status'>
                  {status}
                </span>
              ) : null}
              {details.map((line) => (
                <span key={line} className='text-12px leading-18px text-t-secondary'>
                  {line}
                </span>
              ))}
            </div>
            <div className='flex shrink-0 items-center gap-8px'>
              {/* 稍后 puts off a found update: the sidebar notice goes until the next check; 下载 stays here. */}
              {update?.phase === 'found' && !update.dismissed ? (
                <Button type='text' size='small' onClick={() => void run('later')}>
                  {t('update.later')}
                </Button>
              ) : null}
              {update?.phase === 'installerReady' ? (
                <Button type='text' size='small' onClick={() => void run('showInstaller')}>
                  {t('update.showInFolder')}
                </Button>
              ) : null}
              {main ? (
                <Button
                  type='primary'
                  size='small'
                  loading={main.busy}
                  disabled={main.busy}
                  onClick={() => void run(main.action)}
                >
                  {main.label}
                </Button>
              ) : (
                <Button
                  type='secondary'
                  size='small'
                  loading={checking}
                  disabled={checking || (update ? BUSY_PHASES.has(update.phase) : false)}
                  onClick={() => void run('check')}
                >
                  {checking ? t('settings.checkingForUpdates') : t('settings.checkForUpdates')}
                </Button>
              )}
            </div>
          </div>
        ) : null}
        {linkItems.map((item) => (
          <div
            key={item.title}
            role='button'
            tabIndex={0}
            className={classNames(rowClass, 'group cursor-pointer')}
            onClick={(e) => {
              e.preventDefault();
              e.stopPropagation();
              item.open();
            }}
            onKeyDown={(e) => {
              if (e.key !== 'Enter' && e.key !== ' ') return;
              e.preventDefault();
              item.open();
            }}
          >
            <span className={rowTitleClass}>{item.title}</span>
            <span className='flex text-t-secondary transition-colors group-hover:text-t-primary'>{item.icon}</span>
          </div>
        ))}
      </div>
    </div>
  );
};

export default AboutModalContent;
