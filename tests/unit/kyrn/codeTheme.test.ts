import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { vs, vs2015 } from 'react-syntax-highlighter/dist/esm/styles/hljs';
import { TOKEN_OF_SELECTOR, muCodeTheme, withMuTokens } from '@renderer/components/Markdown/muCodeTheme';

const scheme = readFileSync(
  resolve(__dirname, '../../../packages/desktop/src/renderer/styles/themes/mu-color-scheme.css'),
  'utf8'
);

describe('mu code theme', () => {
  it('wraps every upstream colour in a mu token that falls back to it', () => {
    const light = muCodeTheme('light');
    const dark = muCodeTheme('dark');
    expect(light['hljs-keyword'].color).toBe('var(--mu-code-keyword, #00f)');
    expect(dark['hljs-keyword'].color).toBe('var(--mu-code-keyword, #569CD6)');
    expect(light['hljs-string'].color).toBe('var(--mu-code-string, #a31515)');
    expect(light.hljs.color).toBe('var(--mu-code-text, black)');
    // Anything that is not 'dark' is the light style, as upstream did.
    expect(muCodeTheme('system')).toBe(light);
  });

  it('keeps every other property of the upstream styles', () => {
    for (const upstream of [vs, vs2015] as Record<string, Record<string, unknown>>[]) {
      const tinted = withMuTokens(upstream as never) as Record<string, Record<string, unknown>>;
      for (const [selector, style] of Object.entries(upstream)) {
        const { color: _color, backgroundColor: _background, ...rest } = style;
        expect(tinted[selector], selector).toMatchObject(rest);
      }
    }
  });

  it('colours tokens the upstream light style leaves plain, and diff lines in both', () => {
    const light = muCodeTheme('light');
    expect((vs as Record<string, unknown>)['hljs-number']).toBeUndefined();
    expect(light['hljs-number'].color).toBe('var(--mu-code-number, black)');
    expect(light['hljs-addition'].backgroundColor).toBe('var(--mu-code-add-bg, transparent)');
    expect(muCodeTheme('dark')['hljs-deletion'].backgroundColor).toBe('var(--mu-code-del-bg, #600)');
  });

  it('only uses tokens that the scheme defines in both appearances', () => {
    const [light, dark] = scheme.split("[data-color-scheme='mu'][data-theme='dark']");
    for (const token of new Set(Object.values(TOKEN_OF_SELECTOR))) {
      expect(light, token).toContain(`--mu-code-${token}:`);
      expect(dark, token).toContain(`--mu-code-${token}:`);
    }
  });
});
