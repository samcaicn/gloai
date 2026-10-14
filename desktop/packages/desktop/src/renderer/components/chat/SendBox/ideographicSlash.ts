/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

/** U+3001, what a Chinese IME commits when the `/` key is pressed. */
export const IDEOGRAPHIC_COMMA = '、';

/**
 * Rewrites the `、` an IME commits into the `/` the command menu listens for.
 *
 * Only the very first character of an empty box is rewritten, and only when it
 * is that character alone: the menu opens on a value of exactly `/…`, so this
 * is the one keystroke where the rewrite both helps and cannot surprise. A `、`
 * typed inside a sentence, pasted with other text, or added to a box that
 * already holds something is the punctuation the person meant.
 */
export function rewriteIdeographicSlash(previous: string, next: string): string {
  if (previous === '' && next === IDEOGRAPHIC_COMMA) return '/';
  return next;
}
