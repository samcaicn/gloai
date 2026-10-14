// Vendored from mizchi/laya-mlx, web/packages/laya-web/test/tokenizer.test.ts at dc3aa6b (Apache-2.0).
// Tests that need the real tokenizer run only with LAYA_TOKENIZER_DIR=<model folder>/tokenizer.

import { readFileSync } from 'node:fs';
import { beforeAll, describe, expect, it } from 'vitest';

import { LayaTokenizer } from '@process/services/localJudgeOnnx/laya/tokenizer.ts';
import { fixture } from './fixtures.ts';

const dir = process.env.LAYA_TOKENIZER_DIR;

describe.skipIf(!dir)('LayaTokenizer (needs LAYA_TOKENIZER_DIR=<bundle>/tokenizer)', () => {
  let tokenizer: LayaTokenizer;
  beforeAll(() => {
    tokenizer = new LayaTokenizer(
      JSON.parse(readFileSync(`${dir}/tokenizer.json`, 'utf8')),
      JSON.parse(readFileSync(`${dir}/tokenizer_config.json`, 'utf8'))
    );
  });
  it('exposes the special token ids the checkpoint declares', () => {
    expect(tokenizer.clsTokenId).toBe(fixture.special_tokens.cls);
    expect(tokenizer.sepTokenId).toBe(fixture.special_tokens.sep);
    expect(tokenizer.padTokenId).toBe(fixture.special_tokens.pad);
    expect(tokenizer.maskTokenId).toBe(fixture.special_tokens.mask);
    expect(tokenizer.maskToken).toBe(fixture.special_tokens.mask_token);
  });
  it.each(fixture.tokenizer_cases)('encodes $text like Rust tokenizers', ({ text, ids }) => {
    expect(tokenizer.encode(text)).toEqual(ids);
  });
});

// Runs without LAYA_TOKENIZER_DIR / the exported bundle, so it always runs in CI.
// Covers the two-phase added-token matching (see `matchAddedTokens` in
// src/tokenizer.ts) and the constructor guards on a hand-written, minimal
// tokenizer.json/tokenizer_config.json pair.
describe('LayaTokenizer matching (minimal vocab)', () => {
  const PAD_ID = 0;
  const EOS_ID = 1;
  const BOS_ID = 2;
  const UNK_ID = 3;
  const MASK_ID = 4;
  const NEWLINE_ID = 10;
  const R_ID = 11;
  const A_PIECE_ID = 21;

  const baseAddedTokens = [
    {
      id: PAD_ID,
      content: '<pad>',
      single_word: false,
      lstrip: false,
      rstrip: false,
      normalized: false,
    },
    {
      id: EOS_ID,
      content: '<eos>',
      single_word: false,
      lstrip: false,
      rstrip: false,
      normalized: false,
    },
    {
      id: BOS_ID,
      content: '<bos>',
      single_word: false,
      lstrip: false,
      rstrip: false,
      normalized: false,
    },
    {
      id: MASK_ID,
      content: '<mask>',
      single_word: false,
      lstrip: true,
      rstrip: false,
      normalized: false,
    },
    // Whitespace-only added token: tokenizers.js's own added-token matching
    // (which this class deliberately bypasses) would `trimEnd()` this away
    // when it directly precedes an lstrip token like <mask>.
    {
      id: NEWLINE_ID,
      content: '\n',
      single_word: false,
      lstrip: false,
      rstrip: false,
      normalized: false,
    },
    {
      id: R_ID,
      content: '<r>',
      single_word: false,
      lstrip: false,
      rstrip: true,
      normalized: false,
    },
  ];

  const baseTokenizerJson = {
    model: {
      type: 'BPE',
      vocab: {
        '<pad>': PAD_ID,
        '<eos>': EOS_ID,
        '<bos>': BOS_ID,
        '<unk>': UNK_ID,
        '<mask>': MASK_ID,
        '\n': NEWLINE_ID,
        '<r>': R_ID,
        '▁': 20,
        '▁a': A_PIECE_ID,
        '▁b': 22,
        a: 23,
        b: 24,
      },
      merges: [],
      unk_token: '<unk>',
      // No merges needed: every piece this test produces is already a whole
      // vocab entry, and ignore_merges routes an exact vocab hit straight
      // through without running the (empty) BPE merge algorithm.
      ignore_merges: true,
    },
    normalizer: { type: 'Replace', pattern: { String: ' ' }, content: '▁' },
    pre_tokenizer: { type: 'Metaspace', replacement: '▁', prepend_scheme: 'always', split: true },
    post_processor: null,
    decoder: null,
    added_tokens: baseAddedTokens,
  };

  const tokenizerConfig = {
    cls_token: '<bos>',
    sep_token: '<eos>',
    pad_token: '<pad>',
    mask_token: '<mask>',
  };

  // LayaTokenizer's own constructor parameter type (TokenizerJson isn't exported),
  // recovered structurally so `overrides` gets real type-checking instead of `any`.
  type TokenizerJsonLike = ConstructorParameters<typeof LayaTokenizer>[0];

  const buildTokenizer = (overrides: Partial<TokenizerJsonLike> = {}): LayaTokenizer =>
    // baseTokenizerJson also carries the library's own required fields
    // (model/post_processor/decoder) that TokenizerJsonLike doesn't declare;
    // cast through unknown rather than widen that type just for this test.
    new LayaTokenizer({ ...baseTokenizerJson, ...overrides } as unknown as TokenizerJsonLike, tokenizerConfig);

  it.each([
    ['\n<mask>', [NEWLINE_ID, MASK_ID]],
    ['a <mask>', [A_PIECE_ID, MASK_ID]],
    ['<r> a', [R_ID, A_PIECE_ID]],
    ['<r>\n', [R_ID, NEWLINE_ID]],
  ])('encodes %j as %j', (text, ids) => {
    expect(buildTokenizer().encode(text)).toEqual(ids);
  });

  it('rejects a normalizer that is not null or Replace(" " -> replacement)', () => {
    expect(() => buildTokenizer({ normalizer: { type: 'Lowercase' } })).toThrow(/null or Replace/);
  });

  it('rejects an added token with normalized=true', () => {
    expect(() =>
      buildTokenizer({
        added_tokens: baseAddedTokens.map((t, i) => (i === 0 ? { ...t, normalized: true } : t)),
      })
    ).toThrow(/normalized=false and single_word=false/);
  });

  it('rejects an added token with single_word=true', () => {
    expect(() =>
      buildTokenizer({
        added_tokens: baseAddedTokens.map((t, i) => (i === 0 ? { ...t, single_word: true } : t)),
      })
    ).toThrow(/normalized=false and single_word=false/);
  });

  it('rejects a Metaspace replacement that is not a single code point', () => {
    expect(() =>
      buildTokenizer({
        pre_tokenizer: {
          type: 'Metaspace',
          replacement: 'ab',
          prepend_scheme: 'always',
          split: true,
        },
      })
    ).toThrow(/single character/);
  });
});
