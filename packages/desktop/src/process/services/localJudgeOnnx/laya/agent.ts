// Vendored from mizchi/laya-mlx, web/packages/laya-web/src/agent.ts at dc3aa6b (Apache-2.0).
// See ./NOTICE.md. Local changes are marked "mu:".

import { formatAnswers } from './calibration.ts';
import { buildSequence, collate, serializeState } from './prompt.ts';
import { renderOptions, toInternal } from './questions.ts';
import type { LayaTokenizer } from './tokenizer.ts';
import type {
  AgentConfig,
  Answer,
  Batch,
  InternalQuestion,
  PredictResult,
  PreparedItem,
  Question,
  RunnerOutput,
  State,
  StateTruncation,
} from './types.ts';

/** Executes one collated batch. `OnnxRunner` in session.ts is the production implementation. */
export interface Runner {
  run(batch: Batch): Promise<RunnerOutput>;
}

export interface LayaAgentOptions {
  config: AgentConfig;
  tokenizer: LayaTokenizer;
  runner: Runner;
  /** Questions per forward pass; Python defaults to 16. */
  batchSize?: number;
}

/** Port of Python `Agent` without model loading; see `loadAgent` in session.ts. */
export class LayaAgent {
  readonly config: AgentConfig;
  readonly tokenizer: LayaTokenizer;
  readonly runner: Runner;
  readonly batchSize: number;

  constructor(options: LayaAgentOptions) {
    const batchSize = options.batchSize ?? 16;
    if (!Number.isInteger(batchSize) || batchSize < 1) throw new Error('batchSize must be a positive integer');
    validateConfig(options.config);
    // Store a normalized config with `temperature`/`temperature_by_options` defaults applied, so
    // `calibration.ts` (formatAnswers) can index them directly without repeating the `?? [1, 1,
    // 1]` / `?? {}` fallback validateConfig already computed to check for.
    this.config = {
      ...options.config,
      temperature: options.config.temperature ?? [1, 1, 1],
      temperature_by_options: options.config.temperature_by_options ?? {},
    };
    this.tokenizer = options.tokenizer;
    this.runner = options.runner;
    this.batchSize = batchSize;
  }

  /**
   * Python `Agent.prepare` / `_encode_state`: upstream-compatible token sequences for every question.
   *
   * The state is serialized and encoded once, not once per question: every question of a request sees the same state,
   * so re-encoding it was the same ids for N times the work (upstream `Agent._encode_state`).
   *
   * `sink` collects what the token budget cut from the state, keyed by question id, for a caller that wants to say so
   * without re-tokenizing the state a second time.
   */
  prepare(
    state: State,
    questions: Record<string, Question>,
    sink?: Record<string, StateTruncation>
  ): { items: PreparedItem[]; internal: InternalQuestion[] } {
    if (typeof questions !== 'object' || questions === null || Array.isArray(questions)) {
      throw new Error('questions must be a dictionary keyed by question id');
    }
    const { max_len: maxLen, head_max_len: headMaxLen } = this.config;
    const stateIds = this.tokenizer.encode(serializeState(state).replaceAll(this.tokenizer.maskToken, ' '));
    const items: PreparedItem[] = [];
    const internal: InternalQuestion[] = [];
    for (const [qid, definition] of Object.entries(questions)) {
      const q = toInternal(definition);
      const item = buildSequence(this.tokenizer, state, q, maxLen, headMaxLen, {
        stateIds,
        // A conversation list is serialized newest-last, so a right cut would drop the newest turn.
        truncateLeft: Array.isArray(state),
        ...(sink
          ? {
              onState: (truncation) => {
                sink[qid] = truncation;
              },
            }
          : {}),
      });
      const options = renderOptions(q).length;
      if (item.markers.length !== options) {
        // Python names both knobs: `head_max_len` is what the question's own block was given and `max_len` is the
        // ceiling that dropped the markers, so saying only one of them points at the wrong dial in both directions.
        throw new Error(
          `question ${JSON.stringify(qid)}: only ${item.markers.length} of its ${options} option markers fit in ` +
            `max_len=${maxLen} with head_max_len=${headMaxLen} spent on the question; ` +
            'lower head_max_len, raise max_len, or use fewer options'
        );
      }
      items.push(item);
      internal.push(q);
    }
    return { items, internal };
  }

  /**
   * Python `Agent.predict` / `system_one`. `sink` receives the per-question state truncation `prepare` measured; the
   * result itself is exactly Python's.
   */
  async predict(state: State, questions: Record<string, Question>, sink?: Record<string, StateTruncation>): Promise<PredictResult> {
    const { items, internal } = this.prepare(state, questions, sink);
    const questionIds = Object.keys(questions);
    const answers: Record<string, Answer> = {};
    let inputTokens = 0;
    for (let start = 0; start < items.length; start += this.batchSize) {
      const chunk = items.slice(start, start + this.batchSize);
      const chunkIds = questionIds.slice(start, start + chunk.length);
      let output: RunnerOutput;
      try {
        output = await this.runner.run(collate(chunk, this.tokenizer.padTokenId));
      } catch (error) {
        throw new Error(`Laya inference failed for questions ${chunkIds.join(', ')}`, {
          cause: error,
        });
      }
      const result = formatAnswers({
        config: this.config,
        questionIds: chunkIds,
        internal: internal.slice(start, start + chunk.length),
        items: chunk,
        logits: output.logits,
        actLogits: output.actLogits,
        markers: output.markers,
        actions: output.actions,
      });
      Object.assign(answers, result.answers);
      inputTokens += result.usage.input_tokens;
    }
    return {
      model: 'laya-rl-agent',
      answers,
      usage: { input_tokens: inputTokens, output_tokens: 0 },
    };
  }
}

/**
 * Python `Agent.__init__` checks on the agent configuration. Python also checks
 * `max_len <= max_position_embeddings` using the encoder config; the web bundle does not load
 * the encoder config, so that check is skipped here.
 */
function validateConfig(config: AgentConfig): void {
  if (!('encoder' in config) || !('head_layers' in config)) {
    throw new Error('Laya config must specify encoder and head_layers');
  }
  const { max_len: maxLen, head_max_len: headMaxLen } = config;
  if (!(4 < headMaxLen && headMaxLen < maxLen)) {
    throw new Error('Expected 4 < head_max_len < max_len');
  }
  const temperature = config.temperature ?? [1, 1, 1];
  const temperatureByOptions = config.temperature_by_options ?? {};
  const temperatures = [...temperature, ...Object.values(temperatureByOptions)];
  if (temperature.length !== 3 || temperatures.some((t) => !Number.isFinite(t) || t <= 0)) {
    throw new Error('Calibration temperatures must be finite and positive');
  }
}
