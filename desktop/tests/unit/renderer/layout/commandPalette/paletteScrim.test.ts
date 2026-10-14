/**
 * @license
 * Copyright 2026 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The veil under the command palette is the page's own colour, white in light and black in dark: what lies under the
 * palette (the home composer's chips, the send button's lavender) recedes, and the page never turns grey.
 */

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const renderer = resolve(__dirname, '../../../../../packages/desktop/src/renderer');
const palette = readFileSync(resolve(renderer, 'components/layout/Sider/CommandPalette/index.tsx'), 'utf8');
const scheme = readFileSync(resolve(renderer, 'styles/themes/mu-color-scheme.css'), 'utf8');

/** The body of the first rule whose selector is exactly `selector`. */
const block = (selector: string): string => {
  const start = scheme.indexOf(`${selector} {`);
  return start < 0 ? '' : scheme.slice(start, scheme.indexOf('\n}', start));
};

describe('the command palette’s veil', () => {
  it('is the page’s own colour in each theme', () => {
    expect(palette).toContain("backgroundColor: 'var(--mu-scrim");
    expect(palette).toContain('maskStyle={MASK_STYLE}');
    expect(block("[data-color-scheme='mu']")).toMatch(/--mu-scrim: rgba\(255, 255, 255, 0\.\d+\);/);
    expect(block("[data-color-scheme='mu'][data-theme='dark']")).toMatch(/--mu-scrim: rgba\(0, 0, 0, 0\.\d+\);/);
  });
});
