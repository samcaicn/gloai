// Vendored from mizchi/laya-mlx, web/packages/laya-web/test/agent.test.ts at dc3aa6b (Apache-2.0).
// Tests that need the real tokenizer run only with LAYA_TOKENIZER_DIR=<model folder>/tokenizer.

import { readFileSync } from 'node:fs';
import { beforeAll, describe, expect, it } from 'vitest';

import { LayaAgent, type Runner } from '@process/services/localJudgeOnnx/laya/agent.ts';
import { LayaTokenizer } from '@process/services/localJudgeOnnx/laya/tokenizer.ts';
import type { Batch, RunnerOutput } from '@process/services/localJudgeOnnx/laya/types.ts';
import { fixture, type FixtureCase } from './fixtures.ts';

const dir = process.env.LAYA_TOKENIZER_DIR;

/** Replays the fixture logits for whichever rows of the case the agent asks for. */
class FixtureRunner implements Runner {
  calls: Batch[] = [];
  constructor(private readonly c: FixtureCase) {}
  async run(batch: Batch): Promise<RunnerOutput> {
    this.calls.push(batch);
    const offset = this.calls.slice(0, -1).reduce((n, b) => n + b.rows, 0);
    const markers = this.c.logits[0]!.length;
    const actions = this.c.act_logits[0]!.length;
    return {
      rows: batch.rows,
      markers,
      actions,
      logits: Float32Array.from(this.c.logits.slice(offset, offset + batch.rows).flat()),
      actLogits: Float32Array.from(this.c.act_logits.slice(offset, offset + batch.rows).flat()),
    };
  }
}

describe.skipIf(!dir)('LayaAgent (needs LAYA_TOKENIZER_DIR)', () => {
  let tokenizer: LayaTokenizer;
  beforeAll(() => {
    tokenizer = new LayaTokenizer(
      JSON.parse(readFileSync(`${dir}/tokenizer.json`, 'utf8')),
      JSON.parse(readFileSync(`${dir}/tokenizer_config.json`, 'utf8'))
    );
  });

  it.each(fixture.cases.map((c) => [c.name, c] as const))('%s: predict matches Python', async (_, c) => {
    const runner = new FixtureRunner(c);
    const agent = new LayaAgent({ config: fixture.config, tokenizer, runner, batchSize: 64 });
    expect(await agent.predict(c.state, c.questions)).toEqual(c.result);
    expect(runner.calls.length).toBe(1);
  });

  it('splits large requests into batchSize chunks', async () => {
    const c = fixture.cases.find((x) => x.name === 'many_questions')!;
    const runner = new FixtureRunner(c);
    const agent = new LayaAgent({ config: fixture.config, tokenizer, runner, batchSize: 16 });
    expect(await agent.predict(c.state, c.questions)).toEqual(c.result);
    expect(runner.calls.map((b) => b.rows)).toEqual([16, 4]);
  });

  it('rejects non-dictionary questions and handles empty requests', async () => {
    const agent = new LayaAgent({
      config: fixture.config,
      tokenizer,
      runner: new FixtureRunner(fixture.cases[0]!),
    });
    await expect(agent.predict('x', [] as never)).rejects.toThrow('questions must be a dictionary');
    expect(await agent.predict('x', {})).toEqual({
      model: 'laya-rl-agent',
      answers: {},
      usage: { input_tokens: 0, output_tokens: 0 },
    });
  });

  it('validates batchSize and calibration config like Python', () => {
    const runner = new FixtureRunner(fixture.cases[0]!);
    expect(() => new LayaAgent({ config: fixture.config, tokenizer, runner, batchSize: 0 })).toThrow(
      /positive integer/
    );
    expect(() => new LayaAgent({ config: { ...fixture.config, temperature: [1, 1] }, tokenizer, runner })).toThrow(
      /finite and positive/
    );
    expect(
      () =>
        new LayaAgent({
          config: { ...fixture.config, temperature_by_options: { 'choice:2': 0 } },
          tokenizer,
          runner,
        })
    ).toThrow(/finite and positive/);
    expect(() => new LayaAgent({ config: { ...fixture.config, head_max_len: 2000 }, tokenizer, runner })).toThrow(
      /head_max_len/
    );
  });

  it('applies temperature/temperature_by_options defaults when the config omits them', async () => {
    const c = fixture.cases[0]!;
    // The fixture config's `temperature` is [1, 1, 1] and `temperature_by_options` is {}, i.e.
    // exactly the constructor's defaults, so predicting with those two keys deleted must still
    // match the fixture result byte for byte.
    const { temperature: _temperature, temperature_by_options: _temperatureByOptions, ...rest } = fixture.config;
    const runner = new FixtureRunner(c);
    const agent = new LayaAgent({ config: rest as never, tokenizer, runner, batchSize: 64 });
    expect(await agent.predict(c.state, c.questions)).toEqual(c.result);
  });

  it("attributes a failing chunk's inference error to its question ids", async () => {
    const c = fixture.cases.find((x) => x.name === 'many_questions')!;
    const original = new Error('boom');
    const runner: Runner = {
      run: async () => {
        throw original;
      },
    };
    const agent = new LayaAgent({ config: fixture.config, tokenizer, runner, batchSize: 16 });
    const ids = Object.keys(c.questions).slice(0, 16);
    await expect(agent.predict(c.state, c.questions)).rejects.toMatchObject({
      message: `Laya inference failed for questions ${ids.join(', ')}`,
      cause: original,
    });
  });
});
