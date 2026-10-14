import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';
import { describe, expect, it } from 'vitest';

const sourceRoot = fileURLToPath(new URL('../../../packages/desktop/src/', import.meta.url));

function sources(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    return entry.isDirectory() ? sources(path) : /\.[jt]sx?$/.test(entry.name) ? [path] : [];
  });
}

describe('renderer stylesheet imports', () => {
  it('resolves local stylesheets even when TypeScript accepts ambient CSS imports', () => {
    const missing: string[] = [];
    let checked = 0;
    for (const file of sources(join(sourceRoot, 'renderer'))) {
      for (const entry of ts.preProcessFile(readFileSync(file, 'utf8')).importedFiles) {
        const name = entry.fileName.split('?')[0];
        if (!/\.(css|scss|sass|less)$/.test(name)) continue;
        const target = name.startsWith('.')
          ? resolve(dirname(file), name)
          : name.startsWith('@/')
            ? join(sourceRoot, name.slice(2))
            : name.startsWith('@renderer/')
              ? join(sourceRoot, 'renderer', name.slice(10))
              : undefined;
        if (!target) continue;
        checked++;
        if (!existsSync(target)) missing.push(`${relative(sourceRoot, file)} -> ${name}`);
      }
    }
    expect(checked).toBeGreaterThan(0);
    expect(missing).toEqual([]);
  });
});
