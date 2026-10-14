/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The sidebar's plugin rows: whatever is registered in `sider.nav`, in the order the registrations asked for.
 *
 * This component knows the slot and nothing about the plugins that fill it — no import of one, no list to keep in
 * step. A plugin that never loads simply leaves no row; one loaded later appears on the next render, because the slot
 * tells its listeners when it changes.
 */

import React from 'react';
import { useLocation } from 'react-router-dom';
import { Tooltip } from '@arco-design/web-react';
import classNames from 'classnames';
import { SIDER_NAV_SLOT, useSlotEntries } from '@renderer/plugins/kernel';
import type { SiderNavEntry } from '@renderer/plugins/types';
import { rowButtonProps } from '@renderer/utils/ui/rowButton';
import type { SiderTooltipProps } from '@renderer/utils/ui/siderTooltip';

interface SiderPluginEntriesProps {
  isMobile: boolean;
  collapsed: boolean;
  siderTooltipProps: SiderTooltipProps;
  onNavigate: (path: string) => void;
}

const SiderPluginEntries: React.FC<SiderPluginEntriesProps> = ({
  isMobile,
  collapsed,
  siderTooltipProps,
  onNavigate,
}) => {
  const entries = useSlotEntries<SiderNavEntry>(SIDER_NAV_SLOT);
  const { pathname } = useLocation();

  if (entries.length === 0) return null;

  return (
    <>
      {entries.map(({ id, payload }) => {
        const { path, label, icon } = payload;
        const isActive = pathname === path;

        if (collapsed) {
          return (
            <Tooltip key={id} {...siderTooltipProps} content={label} position='right'>
              <div
                data-testid={`sider-plugin-${id}`}
                aria-label={label}
                aria-current={isActive ? 'page' : undefined}
                className={classNames(
                  'w-full h-34px flex items-center justify-center cursor-pointer transition-colors rd-8px text-t-primary',
                  isActive ? 'bg-fill-3' : 'hover:bg-fill-3 active:bg-fill-4'
                )}
                {...rowButtonProps(() => onNavigate(path))}
              >
                <span className='block leading-none shrink-0' style={{ lineHeight: 0 }}>
                  {icon}
                </span>
              </div>
            </Tooltip>
          );
        }

        return (
          <Tooltip key={id} {...siderTooltipProps} content={label} position='right'>
            <div
              data-testid={`sider-plugin-${id}`}
              aria-current={isActive ? 'page' : undefined}
              className={classNames(
                'box-border group h-34px w-full flex items-center justify-start gap-8px ps-10px pe-8px rd-0.5rem cursor-pointer shrink-0 transition-all text-t-primary',
                isMobile && 'sider-action-btn-mobile',
                isActive ? 'bg-fill-3' : 'hover:bg-fill-3 active:bg-fill-4'
              )}
              {...rowButtonProps(() => onNavigate(path))}
            >
              <span className='size-22px flex items-center justify-center shrink-0 text-t-primary'>{icon}</span>
              <span className='collapsed-hidden text-t-primary text-14px font-[500] leading-24px'>{label}</span>
            </div>
          </Tooltip>
        );
      })}
    </>
  );
};

export default SiderPluginEntries;
