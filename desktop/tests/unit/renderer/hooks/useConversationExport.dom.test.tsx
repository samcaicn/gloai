/**
 * @license
 * Copyright 2026 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The exported transcript is written in the app language: its header lines and speaker headings come from translated
 * templates (a Chinese full-width colon, not an ASCII one) and the export time is formatted for the app language.
 */

import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { TMessage } from '@/common/chat/chatLib';
import type { TChatConversation } from '@/common/config/storage';
import { formatDateTime } from '@/renderer/services/i18n/format';

const mocks = vi.hoisted(() => ({
  copyText: vi.fn<(text: string) => Promise<void>>(),
  getConversationOrNull: vi.fn(),
  loadAllConversationMessagesPaged: vi.fn(),
}));

vi.mock('@/common', () => ({
  ipcBridge: {
    application: { getPath: { invoke: vi.fn(async () => '/Users/me/Desktop') } },
    fs: { writeFile: { invoke: vi.fn(async () => true) } },
  },
}));
vi.mock('@/renderer/pages/conversation/utils/conversationCache', () => ({
  getConversationOrNull: mocks.getConversationOrNull,
}));
vi.mock('@/renderer/utils/chat/messagePagination', () => ({
  loadAllConversationMessagesPaged: mocks.loadAllConversationMessagesPaged,
}));
vi.mock('@/renderer/utils/ui/clipboard', () => ({
  copyText: mocks.copyText,
}));

import { useConversationExport } from '@/renderer/hooks/file/useConversationExport';

const zhCN: Record<string, string> = {
  'messages.exportHeaderLine': '{{label}}：{{value}}',
  'messages.exportSpeakerLine': '{{speaker}}：',
  'messages.export.conversationLabel': '会话',
  'messages.export.conversationIdLabel': '会话 ID',
  'messages.export.exportedAtLabel': '导出时间',
  'messages.export.userLabel': '用户',
  'messages.export.assistantLabel': '助手',
  'messages.export.systemLabel': '系统',
  'messages.export.noMessages': '没有消息',
};

const t = (key: string, options?: Record<string, unknown>) =>
  (zhCN[key] ?? key).replace(/\{\{(\w+)\}\}/g, (_match, name: string) => String(options?.[name] ?? ''));

const EXPORTED_AT = new Date(2026, 8, 22, 14, 5, 9);

const conversation = { id: 'conv-1', name: '重构解析器', type: 'acp', extra: {} } as unknown as TChatConversation;
const messages = [
  { id: 'm1', type: 'text', position: 'right', content: { content: '你好' } },
  { id: 'm2', type: 'text', position: 'left', content: { content: '你好！' } },
] as unknown as TMessage[];

describe('useConversationExport: transcript in the app language', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(EXPORTED_AT);
    mocks.copyText.mockReset().mockResolvedValue(undefined);
    mocks.getConversationOrNull.mockReset().mockResolvedValue(conversation);
    mocks.loadAllConversationMessagesPaged.mockReset().mockResolvedValue(messages);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('words the header and speaker lines through the translated templates and dates the export for the language', async () => {
    const messageApi = { success: vi.fn(), error: vi.fn() };
    const { result } = renderHook(() =>
      useConversationExport({ conversation_id: 'conv-1', workspace: '/work', t, language: 'zh-CN', messageApi })
    );

    await act(async () => {
      await result.current.openExportFlow();
    });
    act(() => {
      result.current.onSelectMenuItem('copy');
    });

    await waitFor(() => expect(mocks.copyText).toHaveBeenCalledTimes(1));
    const exportedAt = formatDateTime(EXPORTED_AT, 'zh-CN');
    // The Chinese date differs from the English one, so the assertion below really checks the language.
    expect(exportedAt).not.toBe(formatDateTime(EXPORTED_AT, 'en-US'));
    expect(mocks.copyText.mock.calls[0][0].split('\n')).toEqual([
      '会话：重构解析器',
      '会话 ID：conv-1',
      `导出时间：${exportedAt}`,
      '',
      '用户：',
      '你好',
      '',
      '助手：',
      '你好！',
    ]);
  });
});
