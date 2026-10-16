import { describe, expect, it, vi } from 'vitest';
import { GgufJudge, type FetchJson } from '@process/services/localJudgeGguf/client.ts';

/** A stand-in for llama.cpp's `/v1/systemone`, shaped like the real server's answers. */
type SystemOneAnswer =
  | { type: 'choice'; choice: string; probabilities: Record<string, number>; confidence?: number }
  | { type: 'score'; score: number; legend?: Record<string, unknown>; probabilities: Record<string, number>; confidence?: number }
  | { type: 'noul'; noul: number; confidence?: number };

function server(answers: Record<string, SystemOneAnswer>, status = 200) {
  const fetchJson: FetchJson = vi.fn(async () => ({
    ok: status >= 200 && status < 300,
    status,
    json: async () => ({ model: 'laya-gguf:Laya-Q8_0.gguf', answers, usage: { input_tokens: 123, output_tokens: 7 } }),
  }));
  return fetchJson;
}

const questions = {
  safe: { type: 'boolean', instructions: 'Is this safe?' },
  kind: { type: 'choice', instructions: 'Which?', criteria: { a: 'first', b: 'second' } },
  rate: { type: 'score', instructions: 'Rate it', criteria: ['low', 'high'] },
};

describe('GgufJudge.translate', () => {
  it('turns a llama.cpp choice answer into the gateway choice answer', async () => {
    const fetchJson = server({ kind: { type: 'choice', choice: 'a', probabilities: { a: 0.7, b: 0.3 }, confidence: 0.9 } });
    const judge = new GgufJudge({ upstream: 'http://127.0.0.1:47824', model: 'laya-gguf:Laya-Q8_0.gguf', preset: 'gguf-q8', fetchJson });

    const out = await judge.evaluate('state text', { kind: questions.kind });

    expect(fetchJson).toHaveBeenCalledWith(
      'http://127.0.0.1:47824/v1/systemone',
      expect.objectContaining({ method: 'POST' })
    );
    expect(out.answers.kind).toEqual({ type: 'choice', choice: 'a', probabilities: { a: 0.7, b: 0.3 } });
    expect(out.usage).toEqual({ inputTokens: 123, outputTokens: 7 });
    expect(out.warnings).toEqual([]);
    const meta = out.providerMetadata?.laya;
    expect(meta?.runtime).toBe('gguf');
    expect(meta?.preset).toBe('gguf-q8');
    // The gating number: the mass on the answer reported.
    expect(meta?.answers.kind.answerConfidence).toBeCloseTo(0.7, 5);
  });

  it('turns a noul answer into a boolean with probability', async () => {
    const fetchJson = server({ safe: { type: 'noul', noul: 0.82, confidence: 0.82 } });
    const judge = new GgufJudge({ upstream: 'http://127.0.0.1:47824', model: 'm', preset: 'gguf-q8', fetchJson });

    const out = await judge.evaluate('state', { safe: questions.safe });

    expect(out.answers.safe).toEqual({ type: 'boolean', probability: 0.82 });
    expect(out.providerMetadata?.laya.answers.safe.answerConfidence).toBeCloseTo(0.82, 5);
  });

  it('turns a score answer into the gateway score', async () => {
    const fetchJson = server({ rate: { type: 'score', score: 3, probabilities: { '1': 0.1, '2': 0.2, '3': 0.4, '4': 0.3 } } });
    const judge = new GgufJudge({ upstream: 'http://127.0.0.1:47824', model: 'm', preset: 'gguf-q8', fetchJson });

    const out = await judge.evaluate('state', { rate: questions.rate });

    expect(out.answers.rate).toEqual({
      type: 'score',
      score: 3,
      probabilities: { '1': 0.1, '2': 0.2, '3': 0.4, '4': 0.3 },
    });
  });

  it('rejects a non-ok answer from llama.cpp', async () => {
    const fetchJson = server({}, 500);
    const judge = new GgufJudge({ upstream: 'http://127.0.0.1:47824', model: 'm', preset: 'gguf-q8', fetchJson });

    await expect(judge.evaluate('state', { safe: questions.safe })).rejects.toThrow(/llama\.cpp answered 500/);
  });

  it('rejects an empty question set like the ONNX judge does', async () => {
    const fetchJson = server({});
    const judge = new GgufJudge({ upstream: 'http://127.0.0.1:47824', model: 'm', preset: 'gguf-q8', fetchJson });

    await expect(judge.evaluate('state', {})).rejects.toThrow(/non-empty/);
  });
});

describe('GgufJudge.lifecycle', () => {
  it('reports health as a gguf runtime after warm-up', async () => {
    const fetchJson = server({ safe: { type: 'noul', noul: 0.5 } });
    const judge = new GgufJudge({ upstream: 'http://127.0.0.1:47824', model: 'm', preset: 'gguf-q8', fetchJson });

    expect(judge.health().status).toBe('warming');
    await judge.warmUp();
    const health = judge.health();
    expect(health.status).toBe('ok');
    expect(health.runtime).toBe('gguf');
    expect(health.provider).toBe('llama.cpp');
    expect(health.requests).toBeGreaterThanOrEqual(1);
  });
});
