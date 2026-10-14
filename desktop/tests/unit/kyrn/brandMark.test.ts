import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { MU_GLYPH_PATH, MU_GLYPH_STROKE, MU_TILE_STOPS } from '@/renderer/components/brand/glyph';

const ROOT = resolve(__dirname, '../../..');
const script = readFileSync(resolve(ROOT, 'scripts/kyrn/icon/build-icon.py'), 'utf8');
const logo = readFileSync(resolve(ROOT, 'packages/desktop/src/renderer/assets/logo.svg'), 'utf8');

/** A Python constant written as one string or as adjacent string literals in parentheses. */
function pythonString(name: string): string {
  const match = new RegExp(`^${name} = (\\([\\s\\S]*?^\\)|"[^"]*")`, 'm').exec(script);
  if (!match) throw new Error(`${name} not found in build-icon.py`);
  return [...match[1].matchAll(/"([^"]*)"/g)].map((part) => part[1]).join('');
}

const normal = (path: string): string => path.replace(/\s+/g, ' ').trim();

describe('mu brand mark', () => {
  it('draws the same glyph in the app, in logo.svg and in the 16-32 px icons', () => {
    expect(normal(pythonString('GLYPH_PATH'))).toBe(normal(MU_GLYPH_PATH));
    expect(normal(/ d="([^"]+)"/.exec(logo)?.[1] ?? '')).toBe(normal(MU_GLYPH_PATH));
    expect(Number(/^GLYPH_STROKE = ([\d.]+)/m.exec(script)?.[1])).toBe(MU_GLYPH_STROKE);
    expect(Number(/stroke-width="([\d.]+)"/.exec(logo)?.[1])).toBe(MU_GLYPH_STROKE);
  });

  it('uses the same diagonal for the tile everywhere', () => {
    const stops = [...script.matchAll(/\(0x([0-9A-F]{2}), 0x([0-9A-F]{2}), 0x([0-9A-F]{2})\)/g)].map(
      (m) => `#${m[1]}${m[2]}${m[3]}`
    );
    expect(stops).toEqual([...MU_TILE_STOPS]);
    expect([...logo.matchAll(/stop-color="(#[0-9A-Fa-f]{6})"/g)].map((m) => m[1].toUpperCase())).toEqual([
      ...MU_TILE_STOPS,
    ]);
  });

  it('only uses the path commands the icon script can rasterise', () => {
    expect(MU_GLYPH_PATH.replace(/[-\d.\s]/g, '')).toMatch(/^[MLC]+$/);
  });
});
