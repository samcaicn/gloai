/**
 * The local judge as a client of llama.cpp: `/evaluate` in, `/v1/systemone` out.
 *
 * The two contracts are the same shape -- a state, a map of typed questions, a map of typed answers -- so this is a
 * translation of names, not of meaning: the gateway calls a yes/no question `boolean` and llama.cpp calls it `noul`, and
 * llama.cpp reports no action head, which only the RL-trained checkpoints have.
 */
import { BadRequest, fromLayaAnswer, toLayaQuestion, type Warning } from '../localJudgeOnnx/judge.ts';
import { answerConfidence } from '../localJudgeOnnx/laya/calibration.ts';
import type { Question } from '../localJudgeOnnx/laya/types.ts';

/** What the upstream server needs of the transport: `fetch`, or a stand-in in tests. */
export type FetchJson = (
  url: string,
  init: { method: string; headers: Record<string, string>; body: string }
) => Promise<{
  ok: boolean;
  status: number;
  json(): Promise<unknown>;
}>;

type SystemOneAnswer =
  | { type: 'choice'; choice: string; probabilities: Record<string, number>; confidence?: number }
  | {
      type: 'score';
      score: number;
      legend?: Record<string, unknown>;
      probabilities: Record<string, number>;
      confidence?: number;
    }
  | { type: 'noul'; noul: number; confidence?: number };

type SystemOneResponse = {
  model?: string;
  answers?: Record<string, SystemOneAnswer>;
  usage?: { input_tokens?: number; output_tokens?: number };
};

export type GgufJudgeOptions = {
  /** The address of the llama.cpp server, e.g. `http://127.0.0.1:47824`. */
  upstream: string;
  /** What to call it in the answers' metadata. */
  model: string;
  /** Which GGUF build it loaded. */
  preset: string;
  /** How long one request may wait for llama.cpp, in ms. */
  timeoutMs?: number;
  fetchJson?: FetchJson;
};

const DEFAULT_TIMEOUT_MS = 60_000;

const isObject = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

/** Answers the judge contract by asking a running llama.cpp server. */
export class GgufJudge {
  private readonly options: GgufJudgeOptions;
  private readonly fetchJson: FetchJson;
  private readonly startedAt = Date.now();
  private requests = 0;
  private errors = 0;
  private lastLatencyMs = 0;
  private warm = false;
  private readonly timeoutMs: number;

  constructor(options: GgufJudgeOptions) {
    this.options = options;
    const timeout = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;
    this.timeoutMs = Number.isFinite(timeout) && timeout > 0 ? timeout : DEFAULT_TIMEOUT_MS;
    this.fetchJson =
      options.fetchJson ??
      (async (url, init) =>
        (await fetch(url, { ...init, signal: AbortSignal.timeout(this.timeoutMs) })) as Awaited<ReturnType<FetchJson>>);
  }

  async evaluate(state: unknown, questions: unknown, log?: (line: string) => void) {
    if (!isObject(questions) || !Object.keys(questions).length)
      throw new BadRequest('questions must be a non-empty object keyed by question id');
    if (typeof state !== 'string' && !isObject(state) && !Array.isArray(state))
      throw new BadRequest('state must be a string, an object or an array');
    const laya: Record<string, Question> = {};
    for (const [id, question] of Object.entries(questions)) laya[id] = toLayaQuestion(id, question);
    const started = performance.now();
    let response: SystemOneResponse;
    try {
      response = (await this.fetchJson(`${this.options.upstream}/v1/systemone`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ state, questions: laya }),
      }).then((r) => {
        if (!r.ok) throw new Error(`llama.cpp answered ${r.status}`);
        return r.json();
      })) as SystemOneResponse;
    } catch (error) {
      this.errors++;
      throw new Error(`The llama.cpp judge failed: ${error instanceof Error ? error.message : String(error)}`);
    }
    const elapsed = performance.now() - started;
    this.requests++;
    this.lastLatencyMs = Math.round(elapsed * 10) / 10;
    const answers: Record<string, unknown> = {};
    const metadata: Record<string, { confidence: number; answerConfidence: number }> = {};
    for (const [id, answer] of Object.entries(response.answers ?? {})) {
      const probabilities =
        'probabilities' in answer ? Object.values(answer.probabilities) : [answer.noul, 1 - answer.noul];
      metadata[id] = {
        confidence: answer.confidence ?? 0,
        // The one number to gate on: the mass on the answer reported, whichever type the question was.
        answerConfidence: answerConfidence(probabilities, probabilities.length),
      };
      answers[id] = fromLayaAnswer(normalize(answer));
    }
    const usage = response.usage?.input_tokens ?? 0;
    log?.(`evaluate questions=${Object.keys(laya).length} tokens=${usage} ms=${Math.round(elapsed)}`);
    // Truncation is llama.cpp's to report and it does not: the app's own judge says what its budget cut.
    const warnings: Warning[] = [];
    return {
      answers,
      usage: { inputTokens: usage, outputTokens: response.usage?.output_tokens ?? 0 },
      warnings,
      providerMetadata: {
        laya: {
          model: this.options.model,
          runtime: 'gguf',
          preset: this.options.preset,
          latencyMs: this.lastLatencyMs,
          answers: metadata,
        },
      },
    };
  }

  /** One small request, so the first real one does not pay for the server's first allocation. */
  async warmUp(): Promise<void> {
    try {
      await this.evaluate('a warm-up request', {
        warm: { type: 'boolean', instructions: 'Is this a warm-up request?' },
      });
    } finally {
      this.warm = true;
    }
  }

  health() {
    return {
      status: this.warm ? 'ok' : 'warming',
      model: this.options.model,
      runtime: 'gguf',
      provider: 'llama.cpp',
      preset: this.options.preset,
      upstream: this.options.upstream,
      requests: this.requests,
      errors: this.errors,
      lastLatencyMs: this.lastLatencyMs,
      uptimeSeconds: Math.round((Date.now() - this.startedAt) / 1000),
      pid: process.pid,
    };
  }
}

/** llama.cpp's answer shapes are the vendored ones with `noul` spelled the way the gateway spells it. */
function normalize(answer: SystemOneAnswer) {
  if (answer.type === 'noul')
    return {
      type: 'noul' as const,
      noul: answer.noul,
      confidence: answer.confidence ?? Math.max(answer.noul, 1 - answer.noul),
      action: { act_probability: 1 },
    };
  if (answer.type === 'score')
    return {
      type: 'score' as const,
      score: answer.score,
      legend: (answer.legend ?? {}) as Record<string, never>,
      probabilities: answer.probabilities,
      confidence: answer.confidence ?? 0,
      action: { act_probability: 1 },
    };
  return {
    type: 'choice' as const,
    choice: answer.choice,
    probabilities: answer.probabilities,
    confidence: answer.confidence ?? 0,
    action: { act_probability: 1 },
  };
}
