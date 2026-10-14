import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { loadJudge } from '@process/services/localJudgeOnnx/load.ts';
import { NodeOnnxRunner, providersFor } from '@process/services/localJudgeOnnx/runner.ts';
import { writeTinyBundle } from './fixtures.ts';

describe('NodeOnnxRunner with a stand-in Laya graph', () => {
  let dir: string;
  let paths: Record<string, string>;
  beforeAll(() => {
    dir = mkdtempSync(join(tmpdir(), 'mu-laya-onnx-'));
    paths = writeTinyBundle(dir);
  });
  afterAll(() => rmSync(dir, { recursive: true, force: true }));

  it('tries DirectML first on Windows and the CPU everywhere', () => {
    expect(providersFor('win32')).toEqual(['dml', 'cpu']);
    expect(providersFor('linux')).toEqual(['cpu']);
    expect(providersFor('darwin')).toEqual(['cpu']);
  });

  it('falls back to the next provider when one cannot run the graph', async () => {
    const runner = await NodeOnnxRunner.create(paths['model.onnx'], ['no-such-provider', 'cpu']);
    expect(runner.provider).toBe('cpu');
    await runner.release();
  });

  it('says why when no provider works', async () => {
    await expect(NodeOnnxRunner.create(paths['model.onnx'], ['no-such-provider'])).rejects.toThrow(
      /No execution provider could run the model \(no-such-provider: /
    );
  });

  it('runs a batch and returns logits per marker and act logits per row', async () => {
    const runner = await NodeOnnxRunner.create(paths['model.onnx'], ['cpu']);
    const big = (values: number[]) => BigInt64Array.from(values, (value) => BigInt(value));
    const output = await runner.run({
      rows: 2,
      length: 4,
      markers: 2,
      inputIds: big([2, 5, 6, 1, 2, 7, 1, 0]),
      attentionMask: big([1, 1, 1, 1, 1, 1, 1, 0]),
      markerPos: big([1, 3, 2, 5]),
      markerMask: Uint8Array.from([1, 1, 1, 1]),
      qtype: big([0, 2]),
    });
    expect(output).toMatchObject({ rows: 2, markers: 2, actions: 2 });
    expect(Array.from(output.logits)).toEqual([1, 3, 2, 5]);
    expect(Array.from(output.actLogits)).toEqual([0, 0, 2, 2]);
    await runner.release();
  });

  it('answers typed questions end to end in the gateway shape', async () => {
    const { judge, runner } = await loadJudge(paths, { providers: ['cpu'] });
    await judge.warmUp();
    expect(judge.health()).toMatchObject({ status: 'ok', runtime: 'onnx', provider: 'cpu', requests: 0 });
    const result = await judge.evaluate('a b a', {
      pick: { type: 'choice', instructions: 'Which one?', criteria: { first: 'a', second: null } },
      done: { type: 'boolean', instructions: 'Is it done?' },
      size: { type: 'score', instructions: 'How big?', criteria: ['small', 'medium', 'large'] },
    });
    // The stand-in graph's logits are the marker positions: the last option wins, and true beats false.
    expect(result.answers.pick).toMatchObject({ type: 'choice', choice: 'second' });
    expect(result.answers.done.type).toBe('boolean');
    expect((result.answers.done as { probability: number }).probability).toBeGreaterThan(0.5);
    expect(result.answers.size).toMatchObject({ type: 'score' });
    expect(result.usage.inputTokens).toBeGreaterThan(0);
    expect(result.providerMetadata.laya).toMatchObject({
      model: 'laya:mizchi/laya-multilingual-onnx',
      provider: 'cpu',
    });
    expect(judge.health().requests).toBe(1);
    await runner.release();
  });
});
