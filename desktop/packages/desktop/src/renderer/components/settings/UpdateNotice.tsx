/**
 * @license
 * Copyright 2026 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import type { UpdatePhase } from '@/common/update/updateTypes';
import { useUpdateState } from '@renderer/hooks/system/useUpdateState';
import { rowButtonProps } from '@renderer/utils/ui/rowButton';
import type { SiderTooltipProps } from '@renderer/utils/ui/siderTooltip';
import { Button, Tooltip } from '@arco-design/web-react';
import { UpdateRotation } from '@icon-park/react';
import React from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { updateDetails, updateHeadline, updateMainAction } from './updateText';

/** The phases with something for the person to do or to follow: an update on offer, and a download they started. */
const NOTICE_PHASES: ReadonlySet<UpdatePhase> = new Set([
  'found',
  'downloading',
  'ready',
  'installing',
  'available',
  'downloadingInstaller',
  'installerReady',
]);

type UpdateNoticeProps = {
  collapsed?: boolean;
  siderTooltipProps: SiderTooltipProps;
};

/**
 * A small, calm notice above the sidebar's footer once an update asks for something: "发现新版本 x.y.z（约 N MB）" with
 * 下载 (download) and 稍后 (later), then the download's progress, then "新版本 x.y.z 已就绪" with 重启并更新 (restart and
 * update) and 稍后; or the installer to download and open where updates come that way. Nothing downloads before 下载.
 * 稍后 hides the notice (an update not yet downloaded comes back at the next check); 关于 (About) keeps offering the
 * update. In the collapsed sidebar it is an icon with a dot, which leads to 关于.
 */
const UpdateNotice: React.FC<UpdateNoticeProps> = ({ collapsed = false, siderTooltipProps }) => {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const { state, run } = useUpdateState();

  if (!state || state.dismissed || !NOTICE_PHASES.has(state.phase)) return null;
  const headline = updateHeadline(t, i18n.language, state);
  const details = updateDetails(t, state);
  const main = updateMainAction(t, state);

  if (collapsed) {
    return (
      <Tooltip {...siderTooltipProps} content={headline} position='right'>
        <div
          {...rowButtonProps(() => void navigate('/settings/about'))}
          data-testid='update-notice'
          aria-label={headline ?? undefined}
          className='relative mb-2px h-32px w-full flex items-center justify-center rd-8px cursor-pointer text-t-secondary transition-colors hover:bg-fill-2 hover:text-t-primary'
        >
          <UpdateRotation theme='outline' size='16' fill='currentColor' className='block leading-none' />
          <span className='absolute top-6px end-12px size-6px rd-full bg-[var(--mu-accent-dot)]' />
        </div>
      </Tooltip>
    );
  }

  const installing = state.phase === 'installing';
  return (
    <div
      data-testid='update-notice'
      role='status'
      className='mb-8px flex flex-col gap-4px rd-8px border border-solid border-[var(--color-border-2)] bg-1 px-10px py-8px'
    >
      <span className='text-13px font-500 leading-20px text-t-primary'>{headline}</span>
      {details.map((line) => (
        <span key={line} className='text-12px leading-18px text-t-secondary'>
          {line}
        </span>
      ))}
      <div className='mt-4px flex flex-wrap items-center gap-4px'>
        {main ? (
          <Button
            type='primary'
            size='mini'
            loading={main.busy}
            disabled={main.busy}
            onClick={() => void run(main.action)}
          >
            {main.label}
          </Button>
        ) : null}
        {installing ? null : (
          <Button type='text' size='mini' onClick={() => void run('later')}>
            {t('update.later')}
          </Button>
        )}
      </div>
    </div>
  );
};

export default UpdateNotice;
