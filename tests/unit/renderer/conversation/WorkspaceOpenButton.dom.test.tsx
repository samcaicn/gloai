/**
 * @license
 * Copyright 2026 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import { render, screen, waitFor } from '@testing-library/react';
import { createInstance } from 'i18next';
import React from 'react';
import { I18nextProvider } from 'react-i18next';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import conversation from '@/renderer/services/i18n/locales/en-US/conversation.json';

vi.mock('@/common', () => ({
  ipcBridge: {
    shell: {
      checkToolInstalled: { invoke: vi.fn(() => Promise.resolve(true)) },
      openFolderWith: { invoke: vi.fn(() => Promise.resolve()) },
    },
  },
}));
vi.mock('@/renderer/utils/platform', () => ({ isElectronDesktop: () => true }));
vi.mock('@/renderer/pages/conversation/Preview/context/PreviewContext', () => ({
  useOptionalPreviewContext: () => null,
}));

import WorkspaceOpenButton from '@/renderer/pages/conversation/components/ChatLayout/WorkspaceOpenButton';

const i18n = createInstance();
beforeAll(async () => {
  await i18n.init({ lng: 'en', resources: { en: { translation: { conversation } } } });
});
const show = (labelled: boolean) =>
  render(
    <I18nextProvider i18n={i18n}>
      <WorkspaceOpenButton workspacePath='/tmp/project' isTemporary={false} labelled={labelled} />
    </I18nextProvider>
  );

// The visual QA found the files tab's open-with menu unclear: a lone ⌘ icon reads as a keyboard sign.
describe('WorkspaceOpenButton', () => {
  it('names the tool it opens with when labelled, and says what its arrow does', async () => {
    show(true);
    expect(await screen.findByRole('button', { name: 'VS Code' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Choose how to open' })).toBeInTheDocument();
  });

  it('stays an icon with an accessible name elsewhere', async () => {
    show(false);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Open project folder' })).toBeInTheDocument());
    expect(screen.queryByText('VS Code')).not.toBeInTheDocument();
  });
});
