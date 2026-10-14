/**
 * AssistantAvatar — Renders an assistant's avatar: its image or emoji, else the first letter of its name.
 */
import type { AssistantListItem } from './types';
import { Avatar } from '@arco-design/web-react';
import React from 'react';
import { isEmoji, resolveAvatarImageSrc } from './assistantUtils';
import ThemedLogo from '@/renderer/components/agent/ThemedLogo';

/** Whether an assistant has a picture of its own, an image or an emoji, rather than only a name. */
export const hasOwnAvatar = (assistant: AssistantListItem): boolean => {
  const avatar = assistant.avatar?.trim();
  return Boolean(resolveAvatarImageSrc(avatar) || (avatar && isEmoji(avatar)));
};

type AssistantAvatarProps = {
  assistant: AssistantListItem;
  imageFit?: 'contain' | 'cover';
  shape?: 'circle' | 'square';
  size?: number;
};

const AssistantAvatar: React.FC<AssistantAvatarProps> = ({
  assistant,
  imageFit = 'cover',
  shape = 'square',
  size = 32,
}) => {
  const resolvedAvatar = assistant.avatar?.trim();
  const hasEmojiAvatar = Boolean(resolvedAvatar && isEmoji(resolvedAvatar));
  const avatarImage = resolveAvatarImageSrc(resolvedAvatar);
  const initialSize = Math.floor(size * 0.45);
  const emojiSize = Math.floor(size * 0.6);
  // No picture of its own: the first letter of its name, in the text colour, never a robot.
  const initial = Array.from(assistant.name?.trim() ?? '')[0]?.toUpperCase() ?? '';

  return (
    <Avatar.Group size={size}>
      <Avatar className='border-none' shape={shape} style={{ backgroundColor: 'var(--color-fill-2)', border: 'none' }}>
        {avatarImage ? (
          <ThemedLogo
            src={avatarImage}
            alt=''
            className={`rounded-inherit ${imageFit === 'contain' ? 'object-contain' : 'object-cover'}`}
            // Arco Avatar forces color:var(--color-white); pin to theme text so
            // the currentColor mask stays visible in light mode too.
            style={{ display: 'block', width: size, height: size, color: 'var(--text-primary)' }}
          />
        ) : hasEmojiAvatar ? (
          <span style={{ fontSize: emojiSize }}>{resolvedAvatar}</span>
        ) : (
          <span className='font-600 text-t-secondary' style={{ fontSize: initialSize }}>
            {initial}
          </span>
        )}
      </Avatar>
    </Avatar.Group>
  );
};

export default AssistantAvatar;
