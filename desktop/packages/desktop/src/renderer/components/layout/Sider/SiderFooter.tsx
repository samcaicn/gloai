/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import React from 'react';
import { useTranslation } from 'react-i18next';
import { Tooltip } from '@arco-design/web-react';
import { ArrowCircleLeft, Moon, SettingTwo, SunOne } from '@icon-park/react';
import classNames from 'classnames';
import UpdateNotice from '@renderer/components/settings/UpdateNotice';
import { rowButtonProps } from '@renderer/utils/ui/rowButton';
import type { SiderTooltipProps } from '@renderer/utils/ui/siderTooltip';

interface SiderFooterProps {
  isMobile: boolean;
  isSettings: boolean;
  collapsed?: boolean;
  theme: string;
  siderTooltipProps: SiderTooltipProps;
  onSettingsClick: () => void;
  onThemeToggle: () => void;
}

/**
 * Two things and no more: the way into the settings (or back out), and light or dark. Above them, only while an
 * update asks for something, the update notice.
 */
const SiderFooter: React.FC<SiderFooterProps> = ({
  isMobile,
  isSettings,
  collapsed = false,
  theme,
  siderTooltipProps,
  onSettingsClick,
  onThemeToggle,
}) => {
  const { t } = useTranslation();

  const settingsIcon = isSettings ? (
    <ArrowCircleLeft
      theme='outline'
      size='16'
      fill='currentColor'
      className='block leading-none'
      style={{ lineHeight: 0 }}
    />
  ) : (
    <SettingTwo
      theme='outline'
      size='16'
      fill='currentColor'
      className='block leading-none'
      style={{ lineHeight: 0 }}
    />
  );
  const themeTooltip = theme === 'dark' ? t('settings.lightMode') : t('settings.darkMode');

  return (
    <div className='shrink-0 sider-footer mt-auto pt-8px pb-8px border-t border-solid border-[var(--color-border-2)] border-s-0 border-e-0 border-b-0'>
      <UpdateNotice collapsed={collapsed} siderTooltipProps={siderTooltipProps} />
      <div className={classNames('flex', collapsed ? 'flex-col gap-2px' : 'items-center gap-2px')}>
        <Tooltip {...siderTooltipProps} content={isSettings ? t('common.back') : t('common.settings')} position='right'>
          <div
            {...rowButtonProps(onSettingsClick)}
            data-testid='sider-settings'
            aria-label={collapsed ? (isSettings ? t('common.back') : t('common.settings')) : undefined}
            // A plain row in the settings too: a fill there would read as a second selected page next to the rail's.
            className={classNames(
              'group h-32px flex items-center rd-8px cursor-pointer transition-colors hover:bg-fill-2',
              collapsed ? 'w-full justify-center' : 'flex-1 min-w-0 justify-start gap-8px ps-8px pe-8px',
              isMobile && 'sider-footer-btn-mobile'
            )}
          >
            <span className='size-22px flex items-center justify-center shrink-0 text-t-secondary'>{settingsIcon}</span>
            <span className='collapsed-hidden text-t-primary text-13px font-[500] leading-24px truncate'>
              {isSettings ? t('common.back') : t('common.settings')}
            </span>
          </div>
        </Tooltip>
        {/* The theme, wherever you are: the shell is white or black, and this is the switch. */}
        <Tooltip {...siderTooltipProps} content={themeTooltip} position='right'>
          <div
            {...rowButtonProps(onThemeToggle)}
            data-testid='theme-toggle'
            className={classNames(
              'h-32px shrink-0 flex items-center justify-center cursor-pointer rd-8px transition-colors text-t-secondary hover:bg-fill-2 hover:text-t-primary',
              collapsed ? 'w-full' : 'w-32px',
              isMobile && 'sider-footer-btn-mobile'
            )}
            aria-label={themeTooltip}
          >
            <span className='size-22px flex items-center justify-center shrink-0'>
              {theme === 'dark' ? (
                <SunOne theme='outline' size='16' fill='currentColor' className='block leading-none' />
              ) : (
                <Moon theme='outline' size='16' fill='currentColor' className='block leading-none' />
              )}
            </span>
          </div>
        </Tooltip>
      </div>
    </div>
  );
};

export default SiderFooter;
