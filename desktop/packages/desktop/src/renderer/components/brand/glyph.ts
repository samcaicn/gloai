/**
 * The mu glyph: the app icon's glyph traced as three round-capped strokes in a 100x100 box that
 * stands for the tile. scripts/kyrn/icon/build-icon.py draws the 16-32 px icons from the same path;
 * tests/unit/kyrn/brandMark.test.ts keeps the two in step.
 */
export const MU_GLYPH_PATH =
  'M41 32 L35 74.4 ' +
  'M38.9 47 C37.77 55 41 59.4 47.6 59.4 C54.8 59.4 61.05 54.5 61.9 48.5 ' +
  'M64.25 32 L61.35 52.5 C60.6 58 62.3 61.2 66.4 60.4';

export const MU_GLYPH_STROKE = 9.8;

/** The icon's diagonal as three stops, a shade deeper in the middle so the white glyph separates. */
export const MU_TILE_STOPS = ['#5E7CFB', '#4D6BFE', '#3C57D4'] as const;
