import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { IMessageToolCall } from '@/common/chat/chatLib';
import MessageToolCall from '@/renderer/pages/conversation/Messages/components/MessageToolCall';
import styles from '@/renderer/pages/conversation/Messages/components/MessageToolGroupSummary.module.css';

vi.mock('@/renderer/components/base/FileChangesPanel', () => ({ default: () => null }));
vi.mock('@/renderer/hooks/file/useDiffPreviewHandlers', () => ({ useDiffPreviewHandlers: () => ({}) }));
afterEach(cleanup);

const message: IMessageToolCall = {
  id: 'tool',
  conversation_id: 'fixture',
  type: 'tool_call',
  content: { call_id: 'call', name: 'Bash', status: 'running', args: { command: 'pwd' }, output: '/fixture/project' },
};

describe('single tool message stylesheet migration', () => {
  it('loads the actual component and keeps running status and expanded output styled', () => {
    const { container } = render(<MessageToolCall message={message} />);
    expect(container.querySelector(`.${styles.breathing}`)).not.toBeNull();
    fireEvent.click(screen.getByText('Bash'));
    expect(screen.getByText('/fixture/project')).toHaveClass(styles.detailContent);
    fireEvent.click(screen.getByText('Bash'));
    expect(screen.queryByText('/fixture/project')).not.toBeInTheDocument();
  });

  it('keeps failed tool output visible without a running animation', () => {
    const { container } = render(
      <MessageToolCall
        message={{ ...message, content: { ...message.content, status: 'error', output: 'fixture failure' } }}
      />
    );
    fireEvent.click(screen.getByText('Bash'));
    expect(screen.getByText('fixture failure')).toHaveClass(styles.detailContent);
    expect(container.querySelector(`.${styles.breathing}`)).toBeNull();
  });
});
