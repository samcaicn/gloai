/**
 * AiBuddy 内置助手头像 / Built-in AiBuddy assistant avatars
 *
 * 与 WorkBuddy 同形制的 3D 潮玩机器人形象（白头盔 + 猫耳 + 发光眼，AiBuddy 蓝紫配色），
 * 供助手编辑器「内置头像」页签直接选用；选中后 assistant.avatar 存图片 URL。
 */

import baseAvatar from '@renderer/assets/avatars/ai-buddy-01-base.webp';
import headphoneAvatar from '@renderer/assets/avatars/ai-buddy-02-headphone.webp';
import techAvatar from '@renderer/assets/avatars/ai-buddy-03-tech.webp';
import hatAvatar from '@renderer/assets/avatars/ai-buddy-04-hat.webp';

export type AiBuddyAvatarOption = {
  id: string;
  label: string;
  src: string;
};

/** Stable order shown in the picker; first item is the reference style. */
export const AI_BUDDY_AVATARS: AiBuddyAvatarOption[] = [
  { id: 'ai-buddy-01-base', label: 'AiBuddy 01', src: baseAvatar },
  { id: 'ai-buddy-02-headphone', label: 'AiBuddy 02', src: headphoneAvatar },
  { id: 'ai-buddy-03-tech', label: 'AiBuddy 03', src: techAvatar },
  { id: 'ai-buddy-04-hat', label: 'AiBuddy 04', src: hatAvatar },
];

/** The default brand mark, used when an AiBuddy assistant has no avatar of its own. */
export const AI_BUDDY_DEFAULT_AVATAR_SRC = AI_BUDDY_AVATARS[0].src;
