/**
 * @license
 * Copyright 2026 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import { fireEvent, render, screen } from '@testing-library/react';
import React, { useState } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { SlashCommandItem } from '@/common/chat/slash/types';

const { context } = vi.hoisted(() => ({
  // A conversation brings the app's own `/copy`; without one, the agent's commands are all there is.
  context: { current: { conversation_id: 'sendbox-ideographic-slash', type: 'acp' } as object | null },
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: { defaultValue?: string }) => options?.defaultValue ?? key,
  }),
}));

vi.mock('@/common', () => ({
  ipcBridge: {
    fs: {
      listAvailableSkills: { invoke: vi.fn().mockResolvedValue([]) },
      listWorkspaceFiles: { invoke: vi.fn().mockResolvedValue([]) },
    },
  },
}));

vi.mock('@/renderer/hooks/context/ConversationContext', () => ({
  useConversationContextSafe: () => context.current,
}));

vi.mock('@/renderer/hooks/context/LayoutContext', () => ({
  useLayoutContext: () => ({ isMobile: false }),
}));

vi.mock('@/renderer/pages/conversation/Preview', () => ({
  usePreviewContext: () => ({
    setSendBoxHandler: vi.fn(),
    domSnippets: [],
    removeDomSnippet: vi.fn(),
    clearDomSnippets: vi.fn(),
  }),
}));

vi.mock('@/renderer/pages/conversation/Messages/hooks', () => ({
  useMessageList: () => [],
}));

vi.mock('@/renderer/hooks/file/useConversationExport', () => ({
  useConversationExport: () => ({
    isOpen: false,
    showMenu: false,
    step: 'closed',
    filename: '',
    pathPreview: '',
    menuItems: [],
    activeIndex: 0,
    loading: false,
    openExportFlow: vi.fn(),
    closeExportFlow: vi.fn(),
    handleKeyDown: vi.fn(),
    onSelectMenuItem: vi.fn(),
    setActiveIndex: vi.fn(),
    setFilename: vi.fn(),
    submitFilename: vi.fn(),
  }),
}));

vi.mock('@/renderer/components/chat/BtwOverlay/useBtwCommand', () => ({
  useBtwCommand: () => ({ answer: '', question: '', isLoading: false, isOpen: false, ask: vi.fn(), dismiss: vi.fn() }),
}));

vi.mock('@/renderer/hooks/file/useDragUpload', () => ({
  useDragUpload: () => ({ isFileDragging: false, dragHandlers: {} }),
}));

vi.mock('@/renderer/hooks/file/usePasteService', () => ({
  usePasteService: () => ({ onPaste: vi.fn(), onFocus: vi.fn() }),
}));

vi.mock('@/renderer/hooks/file/useUploadState', () => ({
  useUploadState: () => ({ isUploading: false }),
}));

vi.mock('@/renderer/hooks/file/useAbortUploadsOnConversationChange', () => ({
  useAbortUploadsOnConversationChange: vi.fn(),
}));

vi.mock('@/renderer/utils/emitter', () => ({
  emitter: { emit: vi.fn() },
  useAddEventListener: vi.fn(),
}));

vi.mock('@/renderer/components/chat/BtwOverlay', () => ({ default: () => null }));
vi.mock('@/renderer/components/media/UploadProgressBar', () => ({ default: () => null }));

import SendBox from '@/renderer/components/chat/SendBox';

const COMMANDS: SlashCommandItem[] = [
  { name: 'goal', description: 'Work until a condition holds', kind: 'template', source: 'acp' },
  { name: 'permissions', description: 'Switch permissions', kind: 'template', source: 'acp' },
];

const Harness = ({
  initialValue = '',
  commands = COMMANDS,
}: {
  initialValue?: string;
  commands?: SlashCommandItem[];
}) => {
  const [value, setValue] = useState(initialValue);
  return (
    <SendBox
      value={value}
      onChange={setValue}
      onSend={vi.fn().mockResolvedValue(undefined)}
      slash_commands={commands}
    />
  );
};

const input = () => screen.getByTestId('sendbox-input') as HTMLTextAreaElement;
const slashMenuOptions = () => screen.queryAllByRole('option').map((option) => option.textContent ?? '');

describe('SendBox: the ideographic comma on an empty box', () => {
  beforeEach(() => {
    context.current = { conversation_id: 'sendbox-ideographic-slash', type: 'acp' };
  });

  it('turns a typed 、 into / and opens the command menu', () => {
    render(<Harness />);
    expect(slashMenuOptions()).toEqual([]);

    fireEvent.change(input(), { target: { value: '、' } });

    expect(input().value).toBe('/');
    expect(slashMenuOptions().some((option) => option.includes('/goal'))).toBe(true);
  });

  it('turns the 、 an input method commits into / as well', () => {
    render(<Harness />);

    fireEvent.compositionStart(input());
    // While the input method composes, the box keeps what it had.
    fireEvent.change(input(), { target: { value: '、' } });
    fireEvent.compositionEnd(input(), { target: { value: '、' } });

    expect(input().value).toBe('/');
    expect(slashMenuOptions().some((option) => option.includes('/goal'))).toBe(true);
  });

  it('keeps a 、 written after other text', () => {
    render(<Harness initialValue='先读代码' />);

    fireEvent.change(input(), { target: { value: '先读代码、' } });

    expect(input().value).toBe('先读代码、');
    expect(slashMenuOptions()).toEqual([]);
  });

  it('keeps pasted text that only starts with 、', () => {
    render(<Harness />);

    fireEvent.change(input(), { target: { value: '、然后跑测试' } });

    expect(input().value).toBe('、然后跑测试');
  });

  it('keeps 、 where there is no command to open', () => {
    context.current = null;
    render(<Harness commands={[]} />);

    fireEvent.change(input(), { target: { value: '、' } });

    expect(input().value).toBe('、');
    expect(slashMenuOptions()).toEqual([]);
  });
});
