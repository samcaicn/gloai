/**
 * @license
 * Copyright 2026 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import { deferredRuntimeNeeds } from '@renderer/services/runtime/deferredNodeRuntime';
import React, { useSyncExternalStore } from 'react';
import { useTranslation } from 'react-i18next';

/**
 * One quiet line above the composer once this conversation needed the Node.js runtime whose download the person put
 * off at this start (see common/adapter/nodeRuntimeDeferral.ts).
 */
const NodeRuntimeNote: React.FC<{ conversationId: string }> = ({ conversationId }) => {
  const { t } = useTranslation();
  const needed = useSyncExternalStore(deferredRuntimeNeeds.subscribe, () =>
    deferredRuntimeNeeds.neededBy(conversationId)
  );
  if (!needed) return null;
  return (
    <p className='m-0 mb-6px px-10px text-12px leading-18px text-t-secondary' role='status'>
      {t('common.nodeRuntime.note')}
    </p>
  );
};

export default NodeRuntimeNote;
