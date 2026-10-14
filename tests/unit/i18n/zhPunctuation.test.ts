import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * The Chinese copy quotes a name or a word with corner brackets (「」), not straight double quotes, and spells the
 * judge Jev, never JeV.
 */

const repoRoot = fileURLToPath(new URL('../../../', import.meta.url));
const localeRoot = join(repoRoot, 'packages/desktop/src/renderer/services/i18n/locales');

/** Every string in a locale file, with its key path. */
function localeValues(node: unknown, path = ''): Array<[string, string]> {
  if (typeof node === 'string') return [[path, node]];
  if (Array.isArray(node)) return node.flatMap((item, index) => localeValues(item, `${path}[${index}]`));
  if (node && typeof node === 'object') {
    return Object.entries(node).flatMap(([key, item]) => localeValues(item, path ? `${path}.${key}` : key));
  }
  return [];
}

describe.each(['zh-CN', 'zh-TW'])('the %s copy', (language) => {
  const values = readdirSync(join(localeRoot, language))
    .filter((file) => file.endsWith('.json'))
    .flatMap((file) =>
      localeValues(JSON.parse(readFileSync(join(localeRoot, language, file), 'utf8'))).map(
        ([key, value]) => [`${file} ${key}`, value] as const
      )
    );

  it('quotes with 「」, never with straight double quotes', () => {
    expect(values.filter(([, value]) => value.includes('"')).map(([key]) => key)).toEqual([]);
  });

  it('spells the judge Jev', () => {
    expect(values.filter(([, value]) => value.includes('JeV')).map(([key]) => key)).toEqual([]);
  });
});
