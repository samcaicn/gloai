import { describe, expect, it } from 'vitest';
import { BadRequest, OnnxJudge, fromLayaAnswer, toLayaQuestion } from '@process/services/localJudgeOnnx/judge.ts';
import { LayaAgent, type Runner } from '@process/services/localJudgeOnnx/laya/agent.ts';
import { LayaTokenizer, type TokenizerJson } from '@process/services/localJudgeOnnx/laya/tokenizer.ts';
import type { Batch } from '@process/services/localJudgeOnnx/laya/types.ts';
import { tinyAgentConfig, tinyTokenizerConfig, tinyTokenizerJson } from './fixtures.ts';

/** Logits = marker positions, like the stand-in graph; `seen` records every batch. */
function runner(): Runner & { seen: Batch[]; gate?: Promise<void> } {
  const self: Runner & { seen: Batch[]; gate?: Promise<void> } = {
    seen: [],
    async run(batch) {
      self.seen.push(batch);
      if (self.gate) await self.gate;
      const logits = Float32Array.from(batch.markerPos, (value) => Number(value));
      const actLogits = Float32Array.from({ length: batch.rows * 2 }, () => 0);
      return { rows: batch.rows, markers: batch.markers, actions: 2, logits, actLogits };
    },
  };
  return self;
}

const judgeWith = (run: Runner) =>
  new OnnxJudge(
    new LayaAgent({
      config: tinyAgentConfig,
      tokenizer: new LayaTokenizer(tinyTokenizerJson as unknown as TokenizerJson, tinyTokenizerConfig),
      runner: run,
    }),
    { model: 'laya:test', provider: 'cpu' }
  );

describe('toLayaQuestion (the gateway names, like server.py)', () => {
  it('turns boolean into noul and keeps only the true/false descriptions that say something', () => {
    expect(toLayaQuestion('q', { type: 'boolean', instructions: 'Done?' })).toEqual({
      type: 'noul',
      instructions: 'Done?',
    });
    expect(
      toLayaQuestion('q', { type: 'boolean', instructions: 'Done?', criteria: { true: 'yes', false: '', other: 1 } })
    ).toEqual({ type: 'noul', instructions: 'Done?', criteria: { true: 'yes' } });
  });

  it('keeps choice criteria and fills empty score levels', () => {
    expect(toLayaQuestion('q', { type: 'choice', instructions: 'Which?', criteria: { a: 'first', b: null } })).toEqual({
      type: 'choice',
      instructions: 'Which?',
      criteria: { a: 'first', b: null },
    });
    expect(toLayaQuestion('q', { type: 'score', instructions: 'How?', criteria: ['low', null, 'high'] })).toEqual({
      type: 'score',
      instructions: 'How?',
      criteria: ['low', '', 'high'],
    });
  });

  it('writes structured instructions as JSON without escaping CJK', () => {
    expect(toLayaQuestion('q', { type: 'boolean', instructions: { 任务: '完成了吗' } }).instructions).toBe(
      '{"任务":"完成了吗"}'
    );
  });

  it.each([
    [null, 'must be an object'],
    [{ type: 'boolean' }, 'is missing instructions'],
    [{ type: 'choice', instructions: 'x', criteria: {} }, 'needs a non-empty criteria object'],
    [{ type: 'score', instructions: 'x', criteria: ['one'] }, 'needs at least two levels'],
    [{ type: 'rank', instructions: 'x' }, 'unknown type "rank"'],
  ])('refuses %j', (question, message) => {
    expect(() => toLayaQuestion('q', question)).toThrow(BadRequest);
    expect(() => toLayaQuestion('q', question)).toThrow(message);
  });
});

describe('fromLayaAnswer', () => {
  it('answers in the gateway shape', () => {
    const action = { act_probability: 0.1 };
    expect(fromLayaAnswer({ type: 'noul', confidence: 0.9, action, noul: 0.8 })).toEqual({
      type: 'boolean',
      probability: 0.8,
    });
    expect(
      fromLayaAnswer({ type: 'choice', confidence: 1, action, choice: 'a', probabilities: { a: 0.7, b: 0.3 } })
    ).toEqual({ type: 'choice', choice: 'a', probabilities: { a: 0.7, b: 0.3 } });
    expect(
      fromLayaAnswer({ type: 'score', confidence: 1, action, score: 1.2, legend: {}, probabilities: { '0': 1 } })
    ).toEqual({ type: 'score', score: 1.2, probabilities: { '0': 1 } });
  });
});

describe('OnnxJudge', () => {
  it('refuses a request without questions or with a state of the wrong kind', async () => {
    const judge = judgeWith(runner());
    await expect(judge.evaluate('a', {})).rejects.toThrow(BadRequest);
    await expect(judge.evaluate(42, { q: { type: 'boolean', instructions: 'x' } })).rejects.toThrow(
      'state must be a string'
    );
  });

  it('turns a question the prompt cannot hold into a bad request, not a crash', async () => {
    const judge = judgeWith(runner());
    const many = Object.fromEntries(Array.from({ length: 40 }, (_, i) => [`option${i}`, null]));
    await expect(judge.evaluate('a', { q: { type: 'choice', instructions: 'x', criteria: many } })).rejects.toThrow(
      BadRequest
    );
  });

  it('runs one request at a time and reports latency and truncation', async () => {
    const run = runner();
    let open!: () => void;
    run.gate = new Promise((resolve) => (open = resolve));
    const judge = judgeWith(run);
    const question = { q: { type: 'boolean', instructions: 'Is it a?' } };
    const first = judge.evaluate('a', question);
    const second = judge.evaluate('b '.repeat(80), question);
    await new Promise((resolve) => setTimeout(resolve, 10));
    expect(run.seen).toHaveLength(1);
    open();
    const [a, b] = await Promise.all([first, second]);
    expect(run.seen).toHaveLength(2);
    // The stand-in vocabulary spells unknown words letter by letter, so the options overflow too: only the state differs.
    const truncated = (warnings: { type: string }[]) =>
      warnings.filter((warning) => warning.type === 'state_truncated');
    expect(truncated(a.warnings)).toEqual([]);
    expect(truncated(b.warnings)).toEqual([expect.objectContaining({ type: 'state_truncated', questionId: 'q' })]);
    expect(a.warnings).toContainEqual(expect.objectContaining({ type: 'question_truncated', questionId: 'q' }));
    expect(a.providerMetadata.laya).toMatchObject({ model: 'laya:test', provider: 'cpu' });
    expect(judge.health()).toMatchObject({ status: 'warming', requests: 2 });
  });

  it('is warm after warming up, without counting that as a request', async () => {
    const judge = judgeWith(runner());
    await judge.warmUp();
    expect(judge.health()).toMatchObject({ status: 'ok', requests: 0, runtime: 'onnx' });
  });
});
