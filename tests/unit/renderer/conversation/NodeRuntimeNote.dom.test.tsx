/**
 * @license
 * Copyright 2026 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import { act, render, screen } from '@testing-library/react';
import React from 'react';
import { describe, expect, it, vi } from 'vitest';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}));

import NodeRuntimeNote from '@/renderer/pages/conversation/platforms/acp/NodeRuntimeNote';
import { deferredRuntimeNeeds } from '@/renderer/services/runtime/deferredNodeRuntime';

describe('NodeRuntimeNote', () => {
  it('says in one line that Node.js is missing once this conversation needed it', () => {
    const { container } = render(<NodeRuntimeNote conversationId='conv-note' />);
    expect(container).toBeEmptyDOMElement();

    act(() => {
      deferredRuntimeNeeds.record({ kind: 'conversation', id: 'other-conv' });
    });
    expect(container).toBeEmptyDOMElement();

    act(() => {
      deferredRuntimeNeeds.record({ kind: 'conversation', id: 'conv-note' });
    });
    expect(screen.getByRole('status')).toHaveTextContent('common.nodeRuntime.note');
  });
});
