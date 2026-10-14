import React from 'react';
import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

// Who renders the preview. A conversation page passes `panelHosted`: the Layout's
// work panel holds its preview on every width (a docked column on desktop, a
// sheet on a phone), so ChatLayout must never render a second one. Pages that
// are not hosted (a team page without a project) keep their own preview.
//
// Earlier regression this still guards: a hosted preview on narrow widths had
// no renderer at all, because the Layout host was desktop-only. The work panel
// renders on mobile too, so hosting no longer depends on the width.

let mockIsMobile = false;

vi.mock('@/renderer/hooks/context/LayoutContext', () => ({
  useLayoutContext: () => ({ isMobile: mockIsMobile }),
}));

vi.mock('@/renderer/pages/conversation/Preview', () => ({
  usePreviewContext: () => ({ isOpen: true }),
  PreviewPanel: () => <div data-testid='preview-panel'>preview</div>,
}));

vi.mock('@/renderer/pages/conversation/components/ChatLayout/MobileWorkspaceOverlay', () => ({
  default: () => <div data-testid='mobile-workspace-overlay' />,
}));

vi.mock('@/renderer/pages/conversation/components/ChatLayout/WorkspacePanelHeader', () => ({
  default: ({ children }: { children?: React.ReactNode }) => <div>{children}</div>,
}));

vi.mock('@/renderer/pages/conversation/components/ChatTitleEditor', () => ({
  default: () => <div>title</div>,
}));

vi.mock('@/renderer/components/agent/AgentBadge', () => ({
  AgentLogoIcon: () => <div>logo</div>,
}));

vi.mock('@/renderer/components/layout/FlexFullContainer', () => ({
  default: ({ children }: { children?: React.ReactNode }) => <div>{children}</div>,
}));

vi.mock('@/renderer/hooks/ui/useResizableSplit', () => ({
  useResizableSplit: () => ({
    splitRatio: 60,
    setSplitRatio: vi.fn(),
    createDragHandle: () => null,
  }),
}));

vi.mock('@/renderer/pages/conversation/hooks/useContainerWidth', () => ({
  useContainerWidth: () => ({ containerRef: { current: null }, containerWidth: 800 }),
}));

vi.mock('@/renderer/pages/conversation/hooks/useLayoutConstraints', () => ({
  useLayoutConstraints: () => undefined,
}));

vi.mock('@/renderer/pages/conversation/hooks/useTitleRename', () => ({
  useTitleRename: () => ({
    editingTitle: false,
    setEditingTitle: vi.fn(),
    titleDraft: '',
    setTitleDraft: vi.fn(),
    renameLoading: false,
    canRenameTitle: false,
    submitTitleRename: vi.fn(),
  }),
}));

vi.mock('@/renderer/pages/conversation/hooks/useWorkspaceCollapse', () => ({
  useWorkspaceCollapse: () => ({ rightSiderCollapsed: true, setRightSiderCollapsed: vi.fn() }),
}));

import ChatLayout from '@/renderer/pages/conversation/components/ChatLayout';

function renderChatLayout(panelHosted: boolean) {
  return render(
    <ChatLayout panelHosted={panelHosted} sider={<div>sider</div>} workspaceEnabled={false}>
      <div>chat body</div>
    </ChatLayout>
  );
}

describe('ChatLayout under the work panel', () => {
  afterEach(() => {
    mockIsMobile = false;
  });

  it('yields the preview to the work panel on mobile (no double render)', () => {
    mockIsMobile = true;
    renderChatLayout(true);
    expect(screen.queryByTestId('preview-panel')).not.toBeInTheDocument();
    expect(screen.queryByTestId('mobile-workspace-overlay')).not.toBeInTheDocument();
  });

  it('yields the preview to the work panel on desktop (no double render)', () => {
    mockIsMobile = false;
    renderChatLayout(true);
    expect(screen.queryByTestId('preview-panel')).not.toBeInTheDocument();
  });

  it('renders the preview locally for a page the work panel does not host', () => {
    mockIsMobile = false;
    renderChatLayout(false);
    expect(screen.getByTestId('preview-panel')).toBeInTheDocument();
  });
});
