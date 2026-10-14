import * as ort from 'onnxruntime-node';
import type { Runner } from './laya/agent.ts';
import type { Batch, RunnerOutput } from './laya/types.ts';

/** The execution providers worth trying on this machine, fastest first. The CPU provider always works. */
export function providersFor(platform: NodeJS.Platform): string[] {
  // DirectML ships inside onnxruntime-node on Windows and uses any DirectX 12 GPU; a machine without one fails the
  // probe and falls back to the CPU.
  return platform === 'win32' ? ['dml', 'cpu'] : ['cpu'];
}

const big = (values: number[]): BigInt64Array => BigInt64Array.from(values, (value) => BigInt(value));

/** A tiny valid batch (two rows, two question types) run once before a provider is accepted. */
function probeBatch(): Batch {
  return {
    rows: 2,
    length: 8,
    markers: 3,
    inputIds: big(Array.from({ length: 16 }, () => 0)),
    attentionMask: big(Array.from({ length: 16 }, () => 1)),
    markerPos: big([1, 2, 3, 1, 2, 3]),
    markerMask: Uint8Array.from([1, 1, 1, 1, 1, 1]),
    qtype: big([0, 2]),
  };
}

/** Runs a Laya ONNX graph with onnxruntime-node (the Node port of upstream's browser `OnnxRunner`). */
export class NodeOnnxRunner implements Runner {
  readonly session: ort.InferenceSession;
  readonly provider: string;

  private constructor(session: ort.InferenceSession, provider: string) {
    this.session = session;
    this.provider = provider;
  }

  /** Opens the model with the first provider that can run it, checked with a real run, not only a load. */
  static async create(
    modelPath: string,
    providers: string[] = providersFor(process.platform)
  ): Promise<NodeOnnxRunner> {
    const failures: string[] = [];
    for (const provider of providers) {
      let session: ort.InferenceSession | undefined;
      try {
        session = await ort.InferenceSession.create(modelPath, {
          executionProviders: [provider],
          graphOptimizationLevel: 'all',
        });
        const runner = new NodeOnnxRunner(session, provider);
        await runner.run(probeBatch());
        return runner;
      } catch (error) {
        failures.push(`${provider}: ${error instanceof Error ? error.message : String(error)}`);
        if (session) await session.release().catch((): void => undefined);
      }
    }
    throw new Error(`No execution provider could run the model (${failures.join('; ')})`);
  }

  async run(batch: Batch): Promise<RunnerOutput> {
    const output = await this.session.run({
      input_ids: new ort.Tensor('int64', batch.inputIds, [batch.rows, batch.length]),
      attention_mask: new ort.Tensor('int64', batch.attentionMask, [batch.rows, batch.length]),
      marker_pos: new ort.Tensor('int64', batch.markerPos, [batch.rows, batch.markers]),
      marker_mask: new ort.Tensor('bool', batch.markerMask, [batch.rows, batch.markers]),
      qtype: new ort.Tensor('int64', batch.qtype, [batch.rows]),
    });
    const logits = output.logits;
    const actLogits = output.act_logits;
    if (!logits || !actLogits) throw new Error('The model did not return logits and act_logits');
    if (Number(logits.dims[0]) !== batch.rows)
      throw new Error(`The model returned ${logits.dims[0]} rows, expected ${batch.rows}`);
    return {
      rows: batch.rows,
      markers: Number(logits.dims[1]),
      actions: Number(actLogits.dims[1]),
      logits: logits.data as Float32Array,
      actLogits: actLogits.data as Float32Array,
    };
  }

  release(): Promise<void> {
    return this.session.release();
  }
}
