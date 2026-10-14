// Vendored from mizchi/laya-mlx, web/packages/laya-web/test/prompt.test.ts at dc3aa6b (Apache-2.0).
// Tests that need the real tokenizer run only with LAYA_TOKENIZER_DIR=<model folder>/tokenizer.

import { readFileSync } from 'node:fs';
import { beforeAll, describe, expect, it } from 'vitest';

import { buildSequence, collate, serializeState } from '@process/services/localJudgeOnnx/laya/prompt.ts';
import { toInternal } from '@process/services/localJudgeOnnx/laya/questions.ts';
import { LayaTokenizer } from '@process/services/localJudgeOnnx/laya/tokenizer.ts';
import { fixture } from './fixtures.ts';

const dir = process.env.LAYA_TOKENIZER_DIR;

describe('serializeState', () => {
  it('passes strings through and dumps JSON with ensure_ascii=False', () => {
    expect(serializeState('plain')).toBe('plain');
    expect(serializeState({ message: '請求書', n: 1 })).toBe('{"message": "請求書", "n": 1}');
    expect(serializeState([{ role: 'user', content: 'hi' }])).toBe('[{"role": "user", "content": "hi"}]');
  });
});

describe('collate', () => {
  it('pads markers to at least two slots', () => {
    const batch = collate([{ ids: [2, 5, 1], markers: [1], qtype: 0 }], 0);
    expect(batch.rows).toBe(1);
    expect(batch.length).toBe(3);
    expect(batch.markers).toBe(2);
    expect(Array.from(batch.markerMask)).toEqual([1, 0]);
    expect(Array.from(batch.markerPos, Number)).toEqual([1, 0]);
  });
  it('right-pads shorter rows with the pad id and zero attention', () => {
    const batch = collate(
      [
        { ids: [2, 5, 1], markers: [1, 2], qtype: 0 },
        { ids: [2, 1], markers: [1], qtype: 2 },
      ],
      7
    );
    expect(Array.from(batch.inputIds, Number)).toEqual([2, 5, 1, 2, 1, 7]);
    expect(Array.from(batch.attentionMask, Number)).toEqual([1, 1, 1, 1, 1, 0]);
    expect(Array.from(batch.qtype, Number)).toEqual([0, 2]);
  });
  it('rejects an empty batch', () => {
    expect(() => collate([], 0)).toThrow('Cannot collate an empty batch');
  });
});

describe.skipIf(!dir)('buildSequence and collate against Python (needs LAYA_TOKENIZER_DIR)', () => {
  let tokenizer: LayaTokenizer;
  beforeAll(() => {
    tokenizer = new LayaTokenizer(
      JSON.parse(readFileSync(`${dir}/tokenizer.json`, 'utf8')),
      JSON.parse(readFileSync(`${dir}/tokenizer_config.json`, 'utf8'))
    );
  });
  const { max_len, head_max_len } = fixture.config;

  it.each(fixture.cases.map((c) => [c.name, c] as const))('%s: ids, markers and qtype match Python', (_, c) => {
    Object.values(c.questions).forEach((question, i) => {
      const item = buildSequence(tokenizer, c.state, toInternal(question), max_len, head_max_len);
      expect(item, `${c.name}#${i}`).toEqual(c.items[i]);
    });
  });

  it.each(fixture.cases.map((c) => [c.name, c] as const))('%s: collate matches Python', (_, c) => {
    const batch = collate(c.items, tokenizer.padTokenId);
    expect(batch.rows).toBe(c.batch.input_ids.length);
    expect(batch.length).toBe(c.batch.input_ids[0]!.length);
    expect(batch.markers).toBe(c.batch.marker_pos[0]!.length);
    expect(Array.from(batch.inputIds, Number)).toEqual(c.batch.input_ids.flat());
    expect(Array.from(batch.attentionMask, Number)).toEqual(c.batch.attention_mask.flat());
    expect(Array.from(batch.markerPos, Number)).toEqual(c.batch.marker_pos.flat());
    expect(Array.from(batch.markerMask)).toEqual(c.batch.marker_mask.flat());
    expect(Array.from(batch.qtype, Number)).toEqual(c.batch.qtype);
  });
});
