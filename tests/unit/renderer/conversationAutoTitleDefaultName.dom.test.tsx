/**
 * @license
 * Copyright 2026 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * A new conversation is stored under the default title of the language active when it was created. After a
 * language switch that title must still count as "not named yet", or the conversation never gets its auto title.
 */

import React from 'react';
import { cleanup, render, waitFor } from '@testing-library/react';
import { SWRConfig } from 'swr';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { TChatConversation } from '@/common/config/storage';

const mocks = vi.hoisted(() => ({
  syncTitleFromHistory: vi.fn(),
  getConversationOrNull: vi.fn<(id: string) => Promise<TChatConversation | null>>(),
}));

// The app language is English: `t` answers the English default title.
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => (key === 'conversation.welcome.newConversation' ? 'New Chat' : key),
  }),
}));
vi.mock('react-router-dom', () => ({
  useParams: () => ({ id: 'c1' }),
  useNavigate: () => vi.fn(),
}));
vi.mock('@/renderer/pages/conversation/components/ChatConversation', () => ({
  default: () => <div data-testid='chat' />,
}));
vi.mock('@/renderer/pages/conversation/Preview', () => ({
  usePreviewContext: () => ({ closePreviewIfScopeChanged: vi.fn() }),
}));
vi.mock('@/renderer/hooks/chat/useAutoTitle', () => ({
  useAutoTitle: () => ({ syncTitleFromHistory: mocks.syncTitleFromHistory }),
}));
vi.mock('@/renderer/pages/conversation/utils/conversationCache', () => ({
  getConversationOrNull: (id: string) => mocks.getConversationOrNull(id),
}));
vi.mock('@/common', () => ({
  ipcBridge: { conversation: { listChanged: { on: () => () => {} } } },
}));

import ChatConversationIndex from '@/renderer/pages/conversation/index';
import zhCNConversation from '@/renderer/services/i18n/locales/zh-CN/conversation.json';

const conversationNamed = (name: string): TChatConversation =>
  ({ id: 'c1', name, type: 'acp', extra: {}, project_id: null }) as unknown as TChatConversation;

const renderIndex = () =>
  render(
    <SWRConfig value={{ provider: () => new Map(), dedupingInterval: 0 }}>
      <ChatConversationIndex />
    </SWRConfig>
  );

beforeEach(() => {
  mocks.syncTitleFromHistory.mockReset();
  mocks.getConversationOrNull.mockReset();
});
afterEach(() => cleanup());

describe('conversation page: auto title after a language switch', () => {
  it('retitles a conversation still named with the default title of another language', async () => {
    mocks.getConversationOrNull.mockResolvedValue(conversationNamed(zhCNConversation.welcome.newConversation));
    renderIndex();

    await waitFor(() => expect(mocks.syncTitleFromHistory).toHaveBeenCalledWith('c1'));
  });

  it('leaves a conversation the user or the model already named alone', async () => {
    mocks.getConversationOrNull.mockResolvedValue(conversationNamed('Refactor the parser'));
    renderIndex();

    await waitFor(() => expect(mocks.getConversationOrNull).toHaveBeenCalled());
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(mocks.syncTitleFromHistory).not.toHaveBeenCalled();
  });
});
