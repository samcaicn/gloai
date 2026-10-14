/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import { FolderUpload } from '@icon-park/react';
import React, { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import type { MobileActionSheetEntry } from './types';

type UseAttachEntryOptions = {
  /** Open the native file picker (paths from disk via IPC). */
  openFileSelector: () => void;
  /** Whether to render the first entry above a divider — passed through. */
  dividerBefore?: boolean;
};

type UseAttachEntryResult = {
  /** One "Attach" row; it opens the native picker. */
  entries: MobileActionSheetEntry[];
};

/**
 * Builds the "Attach" entry for the mobile action sheet. mu runs in Electron only,
 * so there is one way to attach: the host's own file dialog.
 */
export const useAttachEntry = ({ openFileSelector, dividerBefore }: UseAttachEntryOptions): UseAttachEntryResult => {
  const { t } = useTranslation();

  const entries = useMemo<MobileActionSheetEntry[]>(
    () => [
      {
        key: 'attach',
        icon: <FolderUpload theme='outline' size='16' />,
        label: t('common.fileAttach.addFiles', { defaultValue: 'Add files' }),
        variant: 'muted',
        dividerBefore,
        onClick: () => openFileSelector(),
      },
    ],
    [dividerBefore, openFileSelector, t]
  );

  return { entries };
};
