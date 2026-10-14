// Vendored from mizchi/laya-mlx, web/packages/laya-web/test/calibration.test.ts at dc3aa6b (Apache-2.0).
// Tests that need the real tokenizer run only with LAYA_TOKENIZER_DIR=<model folder>/tokenizer.

import { describe, expect, it } from 'vitest';

import {
  argmax,
  confidenceFromProbs,
  formatAnswers,
  softmax,
  tempBucket,
} from '@process/services/localJudgeOnnx/laya/calibration.ts';
import { toInternal } from '@process/services/localJudgeOnnx/laya/questions.ts';
import { fixture } from './fixtures.ts';

describe('helpers', () => {
  it('softmax sums to one', () => {
    const p = softmax([1, 2, 3]);
    expect(p.reduce((a, b) => a + b)).toBeCloseTo(1, 12);
    expect(p[2]).toBeGreaterThan(p[1]!);
  });
  it('tempBucket names option-count buckets like Python', () => {
    expect(tempBucket(0, 2)).toBe('choice:2');
    expect(tempBucket(1, 5)).toBe('score:3-5');
    expect(tempBucket(0, 10)).toBe('choice:6-10');
    expect(tempBucket(2, 11)).toBe('noul:11+');
  });
  it('argmax returns the first maximum on a tie', () => {
    expect(argmax([1, 3, 2])).toBe(1);
    expect(argmax([5, 5, 1])).toBe(0);
  });
  it('confidence is 1 for a single option and 0 for a uniform distribution', () => {
    expect(confidenceFromProbs([1], 1)).toBe(1);
    expect(confidenceFromProbs([0.25, 0.25, 0.25, 0.25], 4)).toBeCloseTo(0, 12);
  });
});

describe('formatAnswers', () => {
  it.each(fixture.cases.map((c) => [c.name, c] as const))('%s: reproduces Python predict()', (_, c) => {
    const internal = Object.values(c.questions).map(toInternal);
    const result = formatAnswers({
      config: fixture.config,
      questionIds: Object.keys(c.questions),
      internal,
      items: c.items,
      logits: Float32Array.from(c.logits.flat()),
      actLogits: Float32Array.from(c.act_logits.flat()),
      markers: c.logits[0]!.length,
      actions: c.act_logits[0]!.length,
    });
    expect(result).toEqual(c.result);
  });
  it('applies temperature_by_options before the per-type temperature', () => {
    const config: typeof fixture.config = {
      ...fixture.config,
      temperature: [2, 1, 1],
      temperature_by_options: { 'choice:2': 0.5 },
    };
    const q = toInternal({ type: 'choice', instructions: 'x', criteria: ['a', 'b'] });
    const run = (cfg: typeof config) =>
      formatAnswers({
        config: cfg,
        questionIds: ['q'],
        internal: [q],
        items: [{ ids: [2, 1], markers: [1, 2], qtype: 0 }],
        logits: Float32Array.from([1, 0]),
        actLogits: Float32Array.from([0, 0]),
        markers: 2,
        actions: 2,
      }).answers['q']!;
    const bucketed = run(config);
    const typed = run({ ...config, temperature_by_options: {} });
    expect(bucketed.type === 'choice' && bucketed.probabilities['a']).toBeCloseTo(1 / (1 + Math.exp(-2)), 4);
    expect(typed.type === 'choice' && typed.probabilities['a']).toBeCloseTo(1 / (1 + Math.exp(-0.5)), 4);
  });
  it('rejects non-finite logits', () => {
    const q = toInternal({ type: 'noul', instructions: 'x' });
    expect(() =>
      formatAnswers({
        config: fixture.config,
        questionIds: ['q'],
        internal: [q],
        items: [{ ids: [2, 1], markers: [1, 2], qtype: 2 }],
        logits: Float32Array.from([NaN, 0]),
        actLogits: Float32Array.from([0, 0]),
        markers: 2,
        actions: 2,
      })
    ).toThrow(/Non-finite/);
  });

  describe('input length validation', () => {
    const q = toInternal({ type: 'noul', instructions: 'x' });
    const base = {
      config: fixture.config,
      questionIds: ['q'],
      internal: [q],
      items: [{ ids: [2, 1], markers: [1, 2], qtype: 2 }],
      logits: Float32Array.from([0, 0]),
      actLogits: Float32Array.from([0, 0]),
      markers: 2,
      actions: 2,
    };
    it('rejects a logits length mismatch', () => {
      expect(() => formatAnswers({ ...base, logits: Float32Array.from([0, 0, 0]) })).toThrow(
        /formatAnswers: logits length mismatch/
      );
    });
    it('rejects an actLogits length mismatch', () => {
      expect(() => formatAnswers({ ...base, actLogits: Float32Array.from([0, 0, 0]) })).toThrow(
        /formatAnswers: actLogits length mismatch/
      );
    });
    it('rejects a questionIds length mismatch', () => {
      expect(() => formatAnswers({ ...base, questionIds: ['q', 'q2'] })).toThrow(
        /formatAnswers: questionIds length mismatch/
      );
    });
    it('rejects an internal length mismatch', () => {
      expect(() => formatAnswers({ ...base, internal: [q, q] })).toThrow(/formatAnswers: internal length mismatch/);
    });
    it('rejects an item whose markers exceed the logits width', () => {
      expect(() => formatAnswers({ ...base, items: [{ ids: [2, 1], markers: [1, 2, 3], qtype: 2 }] })).toThrow(
        /formatAnswers: item markers exceed the logits width/
      );
    });
  });
});
