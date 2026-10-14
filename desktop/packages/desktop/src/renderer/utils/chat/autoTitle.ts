import type { TMessage } from '@/common/chat/chatLib';
import { readMessageContent } from '@/renderer/utils/chat/conversationExport';
import { hasThinkTags, stripThinkTags } from '@/renderer/utils/chat/thinkTagFilter';

export const buildAutoTitleFromContent = (content: string): string | null => {
  const withoutThinkTags = hasThinkTags(content) ? stripThinkTags(content) : content;
  const lines = withoutThinkTags
    .replace(/\r/g, '')
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line && line !== '```');

  const firstLine = lines[0] ?? '';
  const normalized = firstLine
    .replace(/^[#>*\-\d.\s]+/u, '')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 50);

  return normalized || null;
};

/** A slash command (`/board on`, `/help`) says nothing about what a conversation is about. */
const isSlashCommand = (content: string): boolean => content.trimStart().startsWith('/');

/**
 * Pick the very first user prompt from conversation history, skipping slash commands.
 * Falls back to the current send-box content when history has no user prompt yet.
 */
export const deriveAutoTitleFromMessages = (messages: TMessage[], fallbackContent?: string): string | null => {
  for (const message of messages) {
    if (message.type !== 'text' || message.position !== 'right') {
      continue;
    }

    const content = readMessageContent(message);
    if (isSlashCommand(content)) {
      continue;
    }
    const title = buildAutoTitleFromContent(content);
    if (title) {
      return title;
    }
  }

  if (fallbackContent && !isSlashCommand(fallbackContent)) {
    return buildAutoTitleFromContent(fallbackContent);
  }

  return null;
};
