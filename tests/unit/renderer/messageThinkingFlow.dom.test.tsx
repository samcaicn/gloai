import React, { type PropsWithChildren } from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { IMessageAcpToolCall, IMessageThinking, TMessage } from '@/common/chat/chatLib';
import {
  MessageListLoadingProvider,
  MessageListProvider,
  MessagePaginationProvider,
  useUpdateMessageList,
} from '@/renderer/pages/conversation/Messages/hooks';
import MessageList from '@/renderer/pages/conversation/Messages/MessageList';

// The real MessageThinking and ThoughtHistory run inside the real list here: the point is that
// their open state survives the row changes a live turn causes. Only unrelated rows are stubbed.

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: { defaultValue?: string } & Record<string, unknown>) => {
      const templates: Record<string, string> = {
        'conversation.thinking.labelWithTime': '{{label}} · {{time}}',
        'conversation.thinking.completeWithTime': 'Thought complete · {{time}}',
        'conversation.thinking.thoughtFor': 'Thought for {{time}}',
      };
      const template = templates[key] ?? options?.defaultValue ?? key;
      return template.replace(/\{\{(\w+)\}\}/g, (match, name: string) =>
        options && name in options ? String(options[name]) : match
      );
    },
  }),
}));

vi.mock('react-router-dom', () => ({
  useLocation: () => ({ key: 'location-key', state: {} }),
}));

vi.mock('@/renderer/hooks/context/ConversationContext', () => ({
  useConversationContextSafe: () => ({ conversation_id: 'conversation-1', type: 'acp' }),
}));

vi.mock('@/renderer/pages/team/hooks/TeamPermissionContext', () => ({
  useTeamPermission: () => null,
}));

let mockIsProcessing = true;
vi.mock('@/renderer/pages/conversation/runtime/useConversationRuntimeView', () => ({
  useConversationRuntimeView: () => ({ isProcessing: mockIsProcessing, hydrated: true }),
}));

vi.mock('@/renderer/pages/conversation/Messages/artifacts', () => ({
  useConversationArtifacts: () => [],
}));

vi.mock('@/renderer/pages/conversation/Messages/useAutoScroll', () => ({
  useAutoScroll: () => ({
    handleScrollerRef: () => {},
    handleContentRef: () => {},
    handleScroll: () => {},
    handleWheel: () => {},
    handlePointerDown: () => {},
    showScrollButton: false,
    scrollToBottom: () => {},
    scrollElementIntoView: () => {},
    hideScrollButton: () => {},
  }),
}));

vi.mock('@/renderer/pages/conversation/Messages/components/MessageText', () => ({
  default: ({ message }: { message: { content: { content: string } } }) => <div>{message.content.content}</div>,
}));
vi.mock('@/renderer/pages/conversation/Messages/components/MessageTips', () => ({ default: () => null }));
vi.mock('@/renderer/pages/conversation/Messages/components/MessageToolCall', () => ({ default: () => null }));
vi.mock('@/renderer/pages/conversation/Messages/components/MessageToolGroup', () => ({ default: () => null }));
vi.mock('@/renderer/pages/conversation/Messages/components/MessageAgentStatus', () => ({ default: () => null }));
vi.mock('@/renderer/pages/conversation/Messages/components/MessagePermission', () => ({ default: () => null }));
vi.mock('@/renderer/pages/conversation/Messages/acp/MessageAcpPermission', () => ({ default: () => null }));
vi.mock('@/renderer/pages/conversation/Messages/acp/MessageAcpToolCall', () => ({ default: () => null }));
vi.mock('@/renderer/pages/conversation/Messages/acp/MessageAcpTerminalOutput', () => ({ default: () => null }));
vi.mock('@/renderer/pages/conversation/Messages/MessageQuestion', () => ({ default: () => null }));
vi.mock('@/renderer/pages/conversation/Messages/components/MessageCronTrigger', () => ({ default: () => null }));
vi.mock('@/renderer/pages/conversation/Messages/components/MessageSkillSuggest', () => ({ default: () => null }));
vi.mock('@/renderer/pages/conversation/Messages/components/SelectionReplyButton', () => ({ default: () => null }));
vi.mock('@/renderer/pages/conversation/Messages/anchorRail', () => ({ MessageAnchorRail: () => null }));
vi.mock('@/renderer/pages/conversation/Messages/MessageFileChanges', () => ({
  __esModule: true,
  default: () => null,
  parseDiff: vi.fn(),
}));
vi.mock('@/renderer/pages/conversation/Messages/components/MessageToolGroupSummary', () => ({
  default: ({ messages }: { messages: IMessageAcpToolCall[] }) => (
    <div data-testid='tool-summary'>{messages.map((message) => message.id).join(',')}</div>
  ),
}));

const user = (id: string, created_at: number): TMessage =>
  ({
    id,
    msg_id: id,
    conversation_id: 'conversation-1',
    type: 'text',
    position: 'right',
    created_at,
    content: { content: id },
  }) as TMessage;

const thought = (id: string, content: string, status: 'thinking' | 'done', created_at: number): IMessageThinking => ({
  id,
  msg_id: id,
  conversation_id: 'conversation-1',
  type: 'thinking',
  position: 'left',
  created_at,
  content: { content, status, ...(status === 'done' ? { duration: 2_000 } : {}) },
});

const tool = (id: string, created_at: number): IMessageAcpToolCall =>
  ({
    id,
    msg_id: id,
    conversation_id: 'conversation-1',
    type: 'acp_tool_call',
    position: 'left',
    created_at,
    content: {
      session_id: 'session-1',
      update: {
        sessionUpdate: 'tool_call_update',
        tool_call_id: id,
        status: 'completed',
        title: 'bash',
        kind: 'execute',
      },
    },
  }) as IMessageAcpToolCall;

let replaceMessages: (messages: TMessage[]) => void = () => {};
function ListDriver(): null {
  const updateMessages = useUpdateMessageList();
  replaceMessages = (messages) => act(() => updateMessages(messages));
  return null;
}

function Wrapper({ children, messages }: PropsWithChildren<{ messages: TMessage[] }>): JSX.Element {
  return (
    <MessageListLoadingProvider value={false}>
      <MessagePaginationProvider
        value={{ hasMoreBefore: false, hasMoreAfter: false, isLoadingBefore: false, isLoadingAnchor: false }}
      >
        <MessageListProvider value={messages}>{children}</MessageListProvider>
      </MessagePaginationProvider>
    </MessageListLoadingProvider>
  );
}

const tree = (
  <>
    <MessageList />
    <ListDriver />
  </>
);
const renderList = (messages: TMessage[]) =>
  render(tree, { wrapper: ({ children }) => <Wrapper messages={messages}>{children}</Wrapper> });

const liveButton = () => screen.queryByRole('button', { name: /Thinking\.\.\./ });
/** A run of thoughts folds to one line saying how long it took; without a duration it stays "Thinking history". */
const HISTORY = /Thought for|Thinking history/;
const historyButton = () => screen.getByRole('button', { name: HISTORY });

describe('thinking rows in a live turn', () => {
  beforeEach(() => {
    mockIsProcessing = true;
  });

  it('shows one closed live line and no completed-thought rows between tools', () => {
    renderList([
      user('user-1', 1),
      thought('thought-1', 'first thought', 'done', 2),
      tool('tool-1', 3),
      thought('thought-2', 'second thought', 'done', 4),
      tool('tool-2', 5),
      thought('thought-3', 'third thought', 'thinking', 6),
    ]);

    expect(screen.getAllByRole('button', { name: /Thinking\.\.\./ })).toHaveLength(1);
    expect(liveButton()).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getAllByRole('button', { name: HISTORY })).toHaveLength(1);
    expect(screen.queryByText(/Thought complete/)).not.toBeInTheDocument();
    // The live thought keeps streaming as a quiet line; its body still waits for a click.
    expect(screen.getByTestId('thinking-stream')).toHaveTextContent('third thought');

    // Public thinking stays available on demand, in order, in one place.
    fireEvent.click(historyButton());
    expect(screen.getByText('first thought')).toBeInTheDocument();
    expect(screen.getByText('second thought')).toBeInTheDocument();
    expect(screen.getAllByText(/Thought complete/)).toHaveLength(2);
  });

  it('keeps an opened history open while the turn keeps thinking and running tools', () => {
    const start = [user('user-1', 1), thought('thought-1', 'first thought', 'done', 2), tool('tool-1', 3)];
    renderList([...start, thought('thought-2', 'second thought', 'thinking', 4)]);
    fireEvent.click(historyButton());
    expect(screen.getByText('first thought')).toBeInTheDocument();

    replaceMessages([
      ...start,
      thought('thought-2', 'second thought', 'done', 4),
      tool('tool-2', 5),
      thought('thought-3', 'third thought', 'thinking', 6),
    ]);

    expect(historyButton()).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText('first thought')).toBeInTheDocument();
    expect(screen.getByText('second thought')).toBeInTheDocument();
    expect(liveButton()).toHaveAttribute('aria-expanded', 'false');
  });

  it('keeps a body the reader opened through completion, then folds it into the history on close', () => {
    renderList([user('user-1', 1), thought('thought-1', 'the plan', 'thinking', 2)]);
    fireEvent.click(liveButton()!);
    expect(screen.getByText('the plan')).toBeInTheDocument();

    replaceMessages([user('user-1', 1), thought('thought-1', 'the plan', 'done', 2), tool('tool-1', 3)]);

    expect(liveButton()).not.toBeInTheDocument();
    expect(screen.getByText('the plan')).toBeInTheDocument();
    expect(historyButton()).toHaveAttribute('aria-expanded', 'true');

    fireEvent.click(historyButton());
    expect(screen.queryByText('the plan')).not.toBeInTheDocument();
    // Closed means it is an ordinary completed thought again: one history line for the turn.
    expect(screen.getAllByRole('button', { name: HISTORY })).toHaveLength(1);
    expect(historyButton()).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(historyButton());
    expect(screen.getByText('the plan')).toBeInTheDocument();
  });

  it('turns a cancelled live line into a closed record instead of a spinner that never ends', () => {
    const { container, rerender } = renderList([user('user-1', 1), thought('thought-1', 'interrupted', 'thinking', 2)]);
    expect(liveButton()).toBeInTheDocument();
    expect(container.querySelector('.arco-spin')).toBeInTheDocument();

    mockIsProcessing = false;
    rerender(tree);

    expect(liveButton()).not.toBeInTheDocument();
    expect(container.querySelector('.arco-spin')).not.toBeInTheDocument();
    expect(historyButton()).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(historyButton());
    expect(screen.getByText('interrupted')).toBeInTheDocument();
    expect(screen.queryByText(/Thought complete/)).not.toBeInTheDocument();
  });
});
