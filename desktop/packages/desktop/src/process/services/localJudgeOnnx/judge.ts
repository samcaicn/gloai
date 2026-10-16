/**
 * The local judge's wire contract, the same as the harness's Core ML sidecar (`kyrn/local-judge/server.py`) and the
 * AI Gateway `evaluation-model` endpoint, so the harness swaps Jev for Laya without translating anything:
 *
 *   POST /evaluate  {state, questions: {id: {type, instructions, criteria?}}}
 *                -> {answers, usage, warnings, providerMetadata: {laya: {...}}}
 *   GET  /health -> {status: 'ok' | 'warming', ...}
 *
 * Question types use the gateway names: `boolean` (Laya calls it `noul`), `choice`, `score`.
 * Nothing here logs state or question text.
 */
import { buildPrefix, serializeState } from './laya/prompt.ts';
import { renderOptions, toInternal } from './laya/questions.ts';
import { answerConfidence } from './laya/calibration.ts';
import type { LayaAgent } from './laya/agent.ts';
import type { Answer, Json, Question, State, StateTruncation } from './laya/types.ts';

export class BadRequest extends Error {}

/** The model did not answer within the judge's timeout (see `MU_LOCAL_JUDGE_TIMEOUT_MS`). */
export class JudgeTimeout extends Error {}

type JsonObject = { [key: string]: Json };
const isObject = (value: unknown): value is JsonObject =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

/** Laya writes non-string instructions with ASCII escapes, which mangles CJK. Written here instead, like server.py. */
const text = (value: unknown): string => (typeof value === 'string' ? value : JSON.stringify(value));

/** How long one request may hold the model before it is given up on. A stuck graph must not wedge the judge forever. */
const TIMEOUT_MS = Number(process.env.MU_LOCAL_JUDGE_TIMEOUT_MS ?? 60_000);

export function toLayaQuestion(id: string, question: unknown): Question {
  if (!isObject(question)) throw new BadRequest(`Question "${id}" must be an object`);
  if (!('instructions' in question)) throw new BadRequest(`Question "${id}" is missing instructions`);
  const instructions = text(question.instructions);
  const criteria = question.criteria;
  switch (question.type) {
    case 'boolean': {
      const kept: { false?: Json; true?: Json } = {};
      if (isObject(criteria))
        for (const key of ['false', 'true'] as const)
          if (criteria[key] !== undefined && criteria[key] !== null && criteria[key] !== '') kept[key] = criteria[key];
      return Object.keys(kept).length ? { type: 'noul', instructions, criteria: kept } : { type: 'noul', instructions };
    }
    case 'choice':
      if (!isObject(criteria) || !Object.keys(criteria).length)
        throw new BadRequest(`Choice question "${id}" needs a non-empty criteria object`);
      return { type: 'choice', instructions, criteria: { ...criteria } };
    case 'score':
      if (!Array.isArray(criteria) || criteria.length < 2)
        throw new BadRequest(`Score question "${id}" needs at least two levels`);
      return { type: 'score', instructions, criteria: criteria.map((level) => (level === null ? '' : level)) };
    default:
      throw new BadRequest(
        `Question "${id}" has unknown type ${JSON.stringify(question.type)}; expected boolean, choice or score`
      );
  }
}

export type GatewayAnswer =
  | { type: 'boolean'; probability: number }
  | { type: 'choice'; choice: string; probabilities: Record<string, number> }
  | { type: 'score'; score: number; probabilities: Record<string, number> };

export function fromLayaAnswer(answer: Answer): GatewayAnswer {
  if (answer.type === 'noul') return { type: 'boolean', probability: answer.noul };
  if (answer.type === 'choice') return { type: 'choice', choice: answer.choice, probabilities: answer.probabilities };
  return { type: 'score', score: answer.score, probabilities: answer.probabilities };
}

export type Warning = { type: string; questionId?: string; message: string };

/**
 * What the token budget silently cut, like server.py's diagnostics. Never throws.
 *
 * `sink` carries what `Agent.prepare` measured while it built the sequences, so the state is measured once instead of
 * being serialized and encoded again here just to say how much of it was dropped; without it (a caller that predicted
 * on its own) the room is recomputed the way server.py does.
 */
export function diagnostics(
  agent: LayaAgent,
  state: State,
  questions: Record<string, Question>,
  sink?: Record<string, StateTruncation>
): Warning[] {
  const warnings: Warning[] = [];
  try {
    const tok = agent.tokenizer;
    const { max_len: maxLen, head_max_len: headMaxLen } = agent.config;
    const stateTokens =
      sink === undefined ? tok.encode(serializeState(state).replaceAll(tok.maskToken, ' ')).length : undefined;
    for (const [questionId, definition] of Object.entries(questions)) {
      const q = toInternal(definition);
      const prefix = buildPrefix(tok, q, headMaxLen);
      const head = tok.encode(`${q.t} question: ${q.ins}`).length;
      const options = renderOptions(q).reduce((sum, option) => sum + 1 + tok.encode(` ${option}`).length, 0);
      const full = 1 + head + 1 + options + 1;
      if (prefix.ids.length < full)
        warnings.push({
          type: 'question_truncated',
          questionId,
          message: `question and options need ${full} tokens, ${prefix.ids.length} were kept`,
        });
      const measured = sink?.[questionId];
      const total = measured?.tokens ?? stateTokens ?? 0;
      const used = measured?.used ?? Math.min(total, Math.max(0, maxLen - prefix.ids.length - 1));
      if (total > used)
        warnings.push({
          type: 'state_truncated',
          questionId,
          message: `state has ${total} tokens, only the first ${used} were read`,
        });
    }
  } catch (error) {
    warnings.push({ type: 'diagnostics_failed', message: error instanceof Error ? error.name : 'Error' });
  }
  return warnings;
}

/** One model, one request at a time: the questions of a request already run as one batch. */
export class OnnxJudge {
  readonly agent: LayaAgent;
  readonly model: string;
  readonly provider: string;
  /** Which build of the model answered: mu offers more than one (see `LAYA_ONNX_PRESETS`). */
  readonly preset: string;
  private queue: Promise<unknown> = Promise.resolve();
  private warm = false;
  private requests = 0;
  private errors = 0;
  private waiting = 0;
  private readonly timeoutMs: number;
  private readonly startedAt = Date.now();

  constructor(agent: LayaAgent, options: { model: string; provider: string; preset?: string; timeoutMs?: number }) {
    this.agent = agent;
    this.model = options.model;
    this.provider = options.provider;
    this.preset = options.preset ?? 'default';
    const timeout = options.timeoutMs ?? TIMEOUT_MS;
    this.timeoutMs = Number.isFinite(timeout) && timeout > 0 ? timeout : 60_000;
  }

  async evaluate(state: unknown, questions: unknown, log?: (line: string) => void) {
    if (!isObject(questions) || !Object.keys(questions).length)
      throw new BadRequest('questions must be a non-empty object keyed by question id');
    if (typeof state !== 'string' && !isObject(state) && !Array.isArray(state))
      throw new BadRequest('state must be a string, an object or an array');
    const laya: Record<string, Question> = {};
    for (const [id, question] of Object.entries(questions)) laya[id] = toLayaQuestion(id, question);
    const queued = performance.now();
    this.waiting++;
    const run = this.queue.then(async () => {
      const started = performance.now();
      const sink: Record<string, StateTruncation> = {};
      try {
        const result = await this.withTimeout(this.agent.predict(state as State, laya, sink), this.timeoutMs);
        return { result, sink, waited: started - queued, started };
      } catch (error) {
        this.errors++;
        // The vendored port throws plain Errors for questions it cannot build (too many options, bad criteria).
        if (error instanceof Error && !/inference failed|timed out/i.test(error.message))
          throw new BadRequest(error.message);
        throw error;
      }
    });
    this.queue = run.catch((): void => undefined);
    const { result, sink, waited, started } = await run.finally(() => {
      this.waiting--;
    });
    const elapsed = performance.now() - started;
    this.requests++;
    const usage = result.usage.input_tokens;
    log?.(
      `evaluate questions=${Object.keys(laya).length} tokens=${usage} ms=${Math.round(elapsed)} waited_ms=${Math.round(waited)}`
    );
    const metadata: Record<string, { confidence: number; answerConfidence: number; actProbability: number }> = {};
    const answers: Record<string, GatewayAnswer> = {};
    for (const [id, answer] of Object.entries(result.answers)) {
      answers[id] = fromLayaAnswer(answer);
      metadata[id] = {
        confidence: answer.confidence,
        // Python's `answer_confidence`: the mass on the answer reported, max(p). Unlike `confidence`, which is
        // normalized entropy, this is the quantity temperature scaling fits and the one to gate a threshold on.
        answerConfidence: round4(answerConfidenceOf(answer)),
        actProbability: answer.action.act_probability,
      };
    }
    return {
      answers,
      usage: { inputTokens: usage, outputTokens: 0 },
      warnings: diagnostics(this.agent, state as State, laya, sink),
      providerMetadata: {
        laya: {
          model: this.model,
          provider: this.provider,
          preset: this.preset,
          latencyMs: Math.round(elapsed * 10) / 10,
          queuedMs: Math.round(waited),
          answers: metadata,
        },
      },
    };
  }

  /** Rejects a prediction that has not answered in time, so one stuck request cannot hold the judge forever. */
  private async withTimeout<T>(work: Promise<T>, ms: number): Promise<T> {
    if (!Number.isFinite(ms) || ms <= 0) return work;
    let timer: NodeJS.Timeout | undefined;
    try {
      return await Promise.race([
        work,
        new Promise<never>((_resolve, reject) => {
          timer = setTimeout(
            () => reject(new JudgeTimeout(`The local judge timed out after ${Math.round(ms)} ms`)),
            ms
          );
        }),
      ]);
    } finally {
      if (timer) clearTimeout(timer);
    }
  }

  /** One small request so the first real one does not pay for the session's first allocation. */
  async warmUp(): Promise<void> {
    try {
      await this.agent.predict('a warm-up request', {
        warm: { type: 'noul', instructions: 'Is this a warm-up request?' },
      });
    } finally {
      this.warm = true;
    }
  }

  health() {
    return {
      status: this.warm ? 'ok' : 'warming',
      model: this.model,
      runtime: 'onnx',
      provider: this.provider,
      preset: this.preset,
      requests: this.requests,
      waiting: this.waiting,
      errors: this.errors,
      uptimeSeconds: Math.round((Date.now() - this.startedAt) / 1000),
      pid: process.pid,
    };
  }
}

const round4 = (x: number): number => Number(x.toFixed(4));

/** max(p) over the answer's options, which for a two-option `noul` is the confidence already reported. */
function answerConfidenceOf(answer: Answer): number {
  if (answer.type === 'noul') return Math.max(answer.noul, 1 - answer.noul);
  const values = Object.values(answer.probabilities);
  return answerConfidence(values, values.length);
}
