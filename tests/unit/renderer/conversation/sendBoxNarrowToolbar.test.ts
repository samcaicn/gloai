/**
 * @license
 * Copyright 2026 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const root = resolve(__dirname, '../../../../packages/desktop/src/renderer/components/chat/SendBox');
const css = readFileSync(resolve(root, 'sendbox.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
const tsx = readFileSync(resolve(root, 'index.tsx'), 'utf8');

/** The body of the rule whose selector list names exactly this selector. */
const rule = (selector: string): string => {
  const found = (css.match(/[^{}]+\{[^}]+\}/g) ?? []).find((candidate) =>
    candidate
      .slice(0, candidate.indexOf('{'))
      .split(',')
      .some((part) => part.trim() === selector)
  );
  return found?.slice(found.indexOf('{') + 1, -1) ?? '';
};

// B4 of the visual QA: beside the work panel in a 900px window the composer is 320px wide, and its chips used to
// overlap the model chip and the send button. Checked in the built app at 320, 398, 478 and 598px.
describe('SendBox toolbar in a narrow composer', () => {
  it('wraps the model chip and the send button to a second line on the desktop, never on a phone', () => {
    expect(tsx).toContain("isMobile ? '' : ' sendbox-toolbar'");
    expect(rule('.sendbox-toolbar')).toContain('flex-wrap: wrap');
    expect(rule('.sendbox-toolbar > .sendbox-actions')).toContain('margin-inline-start: auto');
  });

  // Round 2 of the visual QA: at 900px with the panel open, "权限 · Jev 审批" was cut mid-character at rest.
  it('wraps a chip that does not fit to the next line whole, instead of cutting its label', () => {
    expect(rule('.sendbox-toolbar > .sendbox-tools')).toContain('display: flex');
    const group = rule('.sendbox-toolbar .sendbox-left-tool-group');
    expect(group).toContain('flex-wrap: wrap !important');
    expect(group).toContain('min-width: 0 !important');
    const chip = rule('.sendbox-toolbar .sendbox-left-tool-group > :has(.agent-mode-compact-pill)');
    // Its own width, shrinking only when it alone is wider than the line.
    expect(chip).toContain('flex: 0 1 auto');
    expect(chip).not.toContain('flex: 1 1 0');
    expect(rule('.sendbox-toolbar .sendbox-left-tool-group > *')).toContain('max-width: 100%');
  });
});
