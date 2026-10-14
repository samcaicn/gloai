import type { CSSProperties } from 'react';
import { vs, vs2015 } from 'react-syntax-highlighter/dist/esm/styles/hljs';

/**
 * Syntax colours of the mu colour scheme.
 *
 * react-syntax-highlighter writes its colours inline, so a stylesheet cannot re-tint them. Each upstream style is
 * copied with its colours wrapped in `var(--mu-code-<token>, <upstream colour>)`: the mu scheme defines the tokens
 * (mu-color-scheme.css, contrast held by scripts/kyrn/theme/contrast.mts), and under any scheme that does not, the
 * upstream colour is what renders. Nothing else of the upstream style is touched.
 */
export type CodeToken = 'text' | 'comment' | 'keyword' | 'string' | 'number' | 'type' | 'attr' | 'meta' | 'add' | 'del';

type SyntaxStyle = Record<string, CSSProperties>;

const TOKEN_SELECTORS: Record<CodeToken, string[]> = {
  text: ['hljs', 'hljs-subst', 'hljs-function', 'hljs-params', 'hljs-formula'],
  comment: ['hljs-comment', 'hljs-quote', 'hljs-doctag'],
  keyword: ['hljs-keyword', 'hljs-selector-tag', 'hljs-literal', 'hljs-name', 'hljs-tag', 'hljs-meta-keyword'],
  string: ['hljs-string', 'hljs-meta-string', 'hljs-regexp', 'hljs-template-tag'],
  number: ['hljs-number', 'hljs-symbol', 'hljs-bullet', 'hljs-link'],
  type: ['hljs-built_in', 'hljs-builtin-name', 'hljs-type', 'hljs-class', 'hljs-title', 'hljs-section'],
  attr: [
    'hljs-attr',
    'hljs-attribute',
    'hljs-variable',
    'hljs-template-variable',
    'hljs-selector-id',
    'hljs-selector-class',
    'hljs-selector-attr',
    'hljs-selector-pseudo',
  ],
  meta: ['hljs-meta'],
  add: ['hljs-addition'],
  del: ['hljs-deletion'],
};

export const TOKEN_OF_SELECTOR: Record<string, CodeToken> = Object.fromEntries(
  (Object.entries(TOKEN_SELECTORS) as [CodeToken, string[]][]).flatMap(([token, selectors]) =>
    selectors.map((selector) => [selector, token])
  )
);

/** The upstream dark style marks diff lines with a solid background; every other case falls back to no background. */
function tinted(selector: string, style: CSSProperties, fallbackText: string): CSSProperties {
  const token = TOKEN_OF_SELECTOR[selector];
  if (!token) return style;
  const next: CSSProperties = { ...style, color: `var(--mu-code-${token}, ${style.color ?? fallbackText})` };
  if (token === 'add' || token === 'del') {
    next.backgroundColor = `var(--mu-code-${token}-bg, ${style.backgroundColor ?? 'transparent'})`;
  }
  return next;
}

export function withMuTokens(upstream: SyntaxStyle): SyntaxStyle {
  const fallbackText = String(upstream.hljs?.color ?? 'inherit');
  const style: SyntaxStyle = {};
  for (const [selector, value] of Object.entries(upstream)) style[selector] = tinted(selector, value, fallbackText);
  // Tokens the upstream style leaves uncoloured (numbers in the light one) still get their mu colour.
  for (const [selector, token] of Object.entries(TOKEN_OF_SELECTOR)) {
    if (style[selector] || token === 'add' || token === 'del') continue;
    style[selector] = { color: `var(--mu-code-${token}, ${fallbackText})` };
  }
  return style;
}

const LIGHT = withMuTokens(vs as SyntaxStyle);
const DARK = withMuTokens(vs2015 as SyntaxStyle);

export function muCodeTheme(appearance: string): SyntaxStyle {
  return appearance === 'dark' ? DARK : LIGHT;
}
