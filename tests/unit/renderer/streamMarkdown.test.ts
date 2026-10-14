/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import { describe, expect, it } from 'vitest';
import { closeOpenFence } from '@/renderer/pages/conversation/Messages/components/streamMarkdown';

describe('half-written Markdown while a reply streams', () => {
  it('closes a fence the model has only opened', () => {
    expect(closeOpenFence('here:\n```ts\nconst a = 1;')).toBe('here:\n```ts\nconst a = 1;\n```');
  });

  it('leaves a finished block exactly as written', () => {
    const done = 'here:\n```ts\nconst a = 1;\n```\nand after.';
    expect(closeOpenFence(done)).toBe(done);
  });

  it('leaves text with no fence at all untouched', () => {
    expect(closeOpenFence('just words, and `inline code` too')).toBe('just words, and `inline code` too');
  });

  it('closes with the opener’s own marker, so a longer fence is not closed by a shorter one', () => {
    expect(closeOpenFence('````\n```\nstill inside')).toBe('````\n```\nstill inside\n````');
    expect(closeOpenFence('~~~\nconst a = 1;')).toBe('~~~\nconst a = 1;\n~~~');
  });

  it('does not add a second newline when the text already ends with one', () => {
    expect(closeOpenFence('```\ncode\n')).toBe('```\ncode\n```');
  });

  it('reopens after a closed block, so a second half-written block is closed too', () => {
    expect(closeOpenFence('```\none\n```\ntext\n```\ntwo')).toBe('```\none\n```\ntext\n```\ntwo\n```');
  });
});
