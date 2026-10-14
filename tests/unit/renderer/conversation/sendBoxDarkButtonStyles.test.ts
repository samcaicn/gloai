import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const cssPath = resolve(__dirname, '../../../../packages/desktop/src/renderer/components/chat/SendBox/sendbox.css');
const tsxPath = resolve(__dirname, '../../../../packages/desktop/src/renderer/components/chat/SendBox/index.tsx');
const css = readFileSync(cssPath, 'utf8');
const tsx = readFileSync(tsxPath, 'utf8');

const getRuleBodyContainingSelector = (selector: string): string => {
  const rules = css.match(/[^{}]+\{[^}]+\}/g) ?? [];
  const rule = rules.find((candidate) => candidate.slice(0, candidate.indexOf('{')).includes(selector));
  return rule?.slice(rule.indexOf('{') + 1, -1) ?? '';
};

/** The body of the rule whose selector list names exactly this selector, comments left out. */
const getRuleBodyForSelector = (selector: string): string => {
  const rules = css.replace(/\/\*[\s\S]*?\*\//g, '').match(/[^{}]+\{[^}]+\}/g) ?? [];
  const rule = rules.find((candidate) =>
    candidate
      .slice(0, candidate.indexOf('{'))
      .split(',')
      .some((part) => part.trim() === selector)
  );
  return rule?.slice(rule.indexOf('{') + 1, -1) ?? '';
};

describe('SendBox action button styles', () => {
  it('keeps disabled send and draft buttons synchronized in dark mode', () => {
    const sendBody = getRuleBodyContainingSelector(
      "html[data-theme='dark'] body .sendbox-panel .send-button-custom--disabled.arco-btn:disabled"
    );
    const draftBody = getRuleBodyContainingSelector(
      "html[data-theme='dark'] body .sendbox-panel .sendbox-draft-tool-action--disabled.arco-btn-secondary:disabled"
    );

    expect(sendBody).toContain('background-color: var(--bg-4)');
    expect(sendBody).toContain('color: var(--text-disabled)');
    expect(draftBody).toContain('background-color: var(--bg-4)');
    expect(draftBody).toContain('color: var(--text-disabled)');
  });

  it('keeps the send button the only colour: an active draft button stays monochrome', () => {
    const sendBody = getRuleBodyContainingSelector('.send-button-custom--enabled.arco-btn');
    const draftBody = getRuleBodyContainingSelector('.sendbox-draft-tool-action--enabled.arco-btn-secondary');
    const draftHoverBody = getRuleBodyContainingSelector(
      '.sendbox-draft-tool-action--enabled.arco-btn:not(:disabled):hover'
    );

    expect(sendBody).toContain('background-color: rgb(var(--primary-6))');
    expect(sendBody).toContain('color: var(--text-white)');
    expect(draftBody).toContain('background-color: var(--color-fill-2)');
    expect(draftBody).toContain('color: var(--color-text-1)');
    expect(draftBody).not.toContain('primary');
    expect(draftHoverBody).toContain('background-color: var(--color-fill-3)');
    expect(draftHoverBody).not.toContain('primary');
  });

  it('draws the composer as a pill on the composer surface with a neutral hairline, tinted nowhere', () => {
    const panelBody = getRuleBodyForSelector('.sendbox-panel');
    const activeBody = getRuleBodyForSelector('.sendbox-panel--active');
    const draggingBody = getRuleBodyForSelector('.sendbox-panel--dragging');

    expect(panelBody).toContain('background-color: var(--mu-composer-bg, var(--fill-white-to-black))');
    expect(panelBody).toContain('border: 1px solid var(--mu-input-border, var(--color-border-2))');
    expect(activeBody).toContain('border-color: var(--color-border-3)');
    expect(draggingBody).toContain('border-style: dashed');
    for (const body of [panelBody, activeBody, draggingBody]) {
      expect(body).not.toMatch(/primary|accent|violet|pink/);
    }
    expect(draggingBody).not.toContain('background');
    expect(css).not.toContain('gradient');
    expect(tsx).toContain('sendbox-panel--active');
    expect(tsx).not.toContain('useInputFocusRing');
  });

  it('prevents the send button from drawing a square primary background', () => {
    const layoutBody = getRuleBodyContainingSelector('.send-button-custom.arco-btn');

    expect(layoutBody).toContain('border-radius: 50%');
    expect(layoutBody).toContain('clip-path: circle(50% at 50% 50%)');
    expect(tsx).toContain("type='text'");
    expect(tsx).not.toContain('<ArrowUp');
  });
});
