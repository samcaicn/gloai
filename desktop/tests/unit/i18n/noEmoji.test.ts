import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { SUPPORTED_LANGUAGES } from '@/common/config/i18n';

/**
 * No string the app shows carries an emoji: the copy is words, and the marks in the interface come from the icon set.
 *
 * An emoji here is a pictograph (Unicode's Extended_Pictographic: the emoji blocks from U+1F000 up and the older
 * symbols that show as emoji, such as ⏰ and ❌), the selector that asks for a character's emoji form (U+FE0F), a
 * keycap (U+20E3) or a regional indicator (half of a flag). The copyright, registered and trade mark signs are
 * pictographs to Unicode but ordinary marks in prose, so they pass; arrows, ⌘ and ⚑ are not pictographs and pass too.
 */

const repoRoot = fileURLToPath(new URL('../../../', import.meta.url));
const localeRoot = join(repoRoot, 'packages/desktop/src/renderer/services/i18n/locales');
const EMOJI = /(?![©®™])[\p{Extended_Pictographic}\p{Regional_Indicator}\u{FE0F}\u{20E3}]/u;

/** Every string in a locale file, with its key path. */
function localeValues(node: unknown, path = ''): Array<[string, string]> {
  if (typeof node === 'string') return [[path, node]];
  if (Array.isArray(node)) return node.flatMap((item, index) => localeValues(item, `${path}[${index}]`));
  if (node && typeof node === 'object') {
    return Object.entries(node).flatMap(([key, item]) => localeValues(item, path ? `${path}.${key}` : key));
  }
  return [];
}

describe('the emoji check', () => {
  it('catches emoji, including a text symbol asked into its emoji form, flags and keycaps', () => {
    for (const text of ['⏰ Missed', '💡 Tip', '⏱️ Timeout', '❌ Refused', '🇨🇳', '1️⃣', '✅', '👍🏽']) {
      expect(EMOJI.test(text), text).toBe(true);
    }
  });

  it('lets the marks of ordinary prose and keyboard hints through', () => {
    for (const text of ['© 2026', 'mu®', 'mu™', '⌘ + Enter', '⚑ = Leader', '← →', '↑ ↓', '«»', '—', '•', '…', '「」']) {
      expect(EMOJI.test(text), text).toBe(false);
    }
  });
});

describe('no emoji in what the app shows', () => {
  it('has none in any locale value, in each of the 13 languages', () => {
    const languages = readdirSync(localeRoot, { withFileTypes: true })
      .filter((entry) => entry.isDirectory())
      .map((entry) => entry.name)
      .toSorted();
    expect(languages).toEqual([...SUPPORTED_LANGUAGES].toSorted());
    expect(languages).toHaveLength(13);

    const offenders: string[] = [];
    let checked = 0;
    for (const language of languages) {
      const files = readdirSync(join(localeRoot, language)).filter((name) => name.endsWith('.json'));
      for (const file of files) {
        const parsed: unknown = JSON.parse(readFileSync(join(localeRoot, language, file), 'utf8'));
        for (const [key, value] of localeValues(parsed)) {
          checked++;
          if (EMOJI.test(value)) offenders.push(`${language}/${file} ${key}: ${value}`);
        }
      }
    }
    expect(checked).toBeGreaterThan(13 * 1000);
    expect(offenders).toEqual([]);
  });
});
