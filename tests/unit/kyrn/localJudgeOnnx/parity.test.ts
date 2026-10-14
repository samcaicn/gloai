import { describe, expect, it } from 'vitest';
import { collate } from '@process/services/localJudgeOnnx/laya/prompt.ts';
import { renderOptions, toInternal } from '@process/services/localJudgeOnnx/laya/questions.ts';
import { fixture } from './laya/fixtures.ts';

// Model-free checks against Python laya-mlx outputs of the multilingual model (the tokenizer-dependent ones need
// LAYA_TOKENIZER_DIR, see ./laya/).
describe('the vendored Laya core against Python, without the model', () => {
  it.each(fixture.cases.map((c) => [c.name, c] as const))('%s: batches like collate_items', (_, c) => {
    const batch = collate(c.items, fixture.special_tokens.pad);
    expect(batch.rows).toBe(c.batch.qtype.length);
    expect(Array.from(batch.inputIds, Number)).toEqual(c.batch.input_ids.flat());
    expect(Array.from(batch.attentionMask, Number)).toEqual(c.batch.attention_mask.flat());
    expect(Array.from(batch.markerPos, Number)).toEqual(c.batch.marker_pos.flat());
    expect(Array.from(batch.markerMask, Number)).toEqual(c.batch.marker_mask.flat().map(Number));
    expect(Array.from(batch.qtype, Number)).toEqual(c.batch.qtype);
  });

  it.each(fixture.cases.map((c) => [c.name, c] as const))('%s: renders options like render_options', (_, c) => {
    expect(Object.values(c.questions).map((q) => renderOptions(toInternal(q)))).toEqual(c.options);
  });
});
