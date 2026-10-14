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
import type { LayaAgent } from './laya/agent.ts';
import type { Answer, Json, Question, State } from './laya/types.ts';

export class BadRequest extends Error {}

type JsonObject = { [key: string]: Json };
const isObject = (value: unknown): value is JsonObject =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

/** Laya writes non-string instructions with ASCII escapes, which mangles CJK. Written here instead, like server.py. */
const text = (value: unknown): string => (typeof value === 'string' ? value : JSON.stringify(value));

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

/** What the token budget silently cut, like server.py's diagnostics. Never throws. */
export function diagnostics(agent: LayaAgent, state: State, questions: Record<string, Question>): Warning[] {
  const warnings: Warning[] = [];
  try {
    const tok = agent.tokenizer;
    const { max_len: maxLen, head_max_len: headMaxLen } = agent.config;
    const stateTokens = tok.encode(serializeState(state).replaceAll(tok.maskToken, ' ')).length;
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
      const room = Math.max(0, maxLen - prefix.ids.length - 1);
      if (stateTokens > room)
        warnings.push({
          type: 'state_truncated',
          questionId,
          message: `state has ${stateTokens} tokens, only the first ${room} were read`,
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
  private queue: Promise<unknown> = Promise.resolve();
  private warm = false;
  private requests = 0;
  private readonly startedAt = Date.now();

  constructor(agent: LayaAgent, options: { model: string; provider: string }) {
    this.agent = agent;
    this.model = options.model;
    this.provider = options.provider;
  }

  async evaluate(state: unknown, questions: unknown, log?: (line: string) => void) {
    if (!isObject(questions) || !Object.keys(questions).length)
      throw new BadRequest('questions must be a non-empty object keyed by question id');
    if (typeof state !== 'string' && !isObject(state) && !Array.isArray(state))
      throw new BadRequest('state must be a string, an object or an array');
    const laya: Record<string, Question> = {};
    for (const [id, question] of Object.entries(questions)) laya[id] = toLayaQuestion(id, question);
    const queued = performance.now();
    const run = this.queue.then(async () => {
      const started = performance.now();
      try {
        return { result: await this.agent.predict(state as State, laya), waited: started - queued, started };
      } catch (error) {
        // The vendored port throws plain Errors for questions it cannot build (too many options, bad criteria).
        if (error instanceof Error && !/inference failed/i.test(error.message)) throw new BadRequest(error.message);
        throw error;
      }
    });
    this.queue = run.catch((): void => undefined);
    const { result, waited, started } = await run;
    const elapsed = performance.now() - started;
    this.requests++;
    const usage = result.usage.input_tokens;
    log?.(
      `evaluate questions=${Object.keys(laya).length} tokens=${usage} ms=${Math.round(elapsed)} waited_ms=${Math.round(waited)}`
    );
    const metadata: Record<string, { confidence: number; actProbability: number }> = {};
    const answers: Record<string, GatewayAnswer> = {};
    for (const [id, answer] of Object.entries(result.answers)) {
      answers[id] = fromLayaAnswer(answer);
      metadata[id] = { confidence: answer.confidence, actProbability: answer.action.act_probability };
    }
    return {
      answers,
      usage: { inputTokens: usage, outputTokens: 0 },
      warnings: diagnostics(this.agent, state as State, laya),
      providerMetadata: {
        laya: {
          model: this.model,
          provider: this.provider,
          latencyMs: Math.round(elapsed * 10) / 10,
          answers: metadata,
        },
      },
    };
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
      requests: this.requests,
      uptimeSeconds: Math.round((Date.now() - this.startedAt) / 1000),
      pid: process.pid,
    };
  }
}
