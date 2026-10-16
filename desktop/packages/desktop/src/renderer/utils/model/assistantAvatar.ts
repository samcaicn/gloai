/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import { resolveBackendAssetUrl } from '@/renderer/utils/platform';

export type AssistantAvatar =
  | { kind: 'image'; value: string }
  | { kind: 'emoji'; value: string }
  | { kind: 'fallback' };

export function isBackendRelativeAssetPath(value: string): boolean {
  return value.startsWith('/api/') || value.startsWith('/assets/');
}

/** Whether a path points at a bundled front-end image asset (any build flavour). A relative path or one served from
 * the dev bundle (`/src/...`) or static root (`/assets/...`) is bundled; an arbitrary absolute path such as
 * `/Users/x.png` or `/path/to/x.png` is a local file and must not be treated as a bundled asset. */
export function isBundledImagePath(value: string): boolean {
  if (!/\.(svg|png|jpe?g|webp|gif)(?:[?#].*)?$/i.test(value)) return false;
  if (!value.startsWith('/')) return true;
  return /^\/(?:src|assets)\//.test(value);
}

export function isLikelyLocalFilePath(value: string): boolean {
  if (value.startsWith('file://')) return true;
  if (/^[A-Za-z]:[\\/]/.test(value)) return true;
  if (/^\/[A-Za-z]:[\\/]/.test(value)) return true;

  const unixLocalPathPrefixes = ['/Users/', '/home/', '/var/', '/tmp/', '/private/', '/Volumes/', '/mnt/'];
  return unixLocalPathPrefixes.some((prefix) => value.startsWith(prefix));
}

export function resolveAssistantAvatar(avatar: string | undefined): AssistantAvatar {
  const value = avatar?.trim();
  if (!value) return { kind: 'fallback' };

  if (isLikelyLocalFilePath(value)) {
    return { kind: 'fallback' };
  }
  // Backend-relative assets (`/api/...`, `/assets/...`) and bundled front-end assets
  // (AiBuddy built-in avatars, which may resolve to `/src/...` in dev) are both image sources.
  if (value.startsWith('/') && !isBackendRelativeAssetPath(value) && !isBundledImagePath(value)) {
    return { kind: 'fallback' };
  }

  const resolved = resolveBackendAssetUrl(value) ?? value;
  const isImage = /\.(svg|png|jpe?g|webp|gif)$/i.test(resolved) || /^(https?:|data:|\/)/i.test(resolved);
  if (isImage) {
    return { kind: 'image', value: resolved };
  }

  return { kind: 'emoji', value };
}
