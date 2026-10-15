import { readFileSync } from 'node:fs';
import { layaOnnxPreset, type LayaOnnxPreset } from '../../../common/kyrn/layaOnnx.ts';
import { OnnxJudge } from './judge.ts';
import { LayaAgent } from './laya/agent.ts';
import { LayaTokenizer, type TokenizerConfig, type TokenizerJson } from './laya/tokenizer.ts';
import type { AgentConfig } from './laya/types.ts';
import { NodeOnnxRunner, providersFor } from './runner.ts';

/** The files of a Laya ONNX export, by their repository path (see a preset's `files`). */
export type BundlePaths = Record<string, string>;

export class BundleError extends Error {}

const json = <T>(paths: BundlePaths, name: string): T => {
  const path = paths[name];
  if (!path) throw new BundleError(`${name} is missing`);
  try {
    return JSON.parse(readFileSync(path, 'utf8')) as T;
  } catch {
    throw new BundleError(`${name} is not valid JSON`);
  }
};

/** Every graph input and output the runner feeds the session; a build without them is not a Laya export. */
const REQUIRED = {
  inputs: ['input_ids', 'attention_mask', 'marker_pos', 'marker_mask', 'qtype'],
  outputs: ['logits', 'act_logits'],
} as const;

/**
 * The names a session exposes, for a build that ships no `onnx_config.json`. The 4-bit exports are stripped of value
 * info, so the side file that used to vouch for the graph is absent: ask the session itself instead of trusting the
 * folder, and say which name is missing rather than failing on the first `run`.
 */
function checkGraph(runner: NodeOnnxRunner): void {
  const missing: string[] = [];
  for (const name of REQUIRED.inputs) if (!runner.session.inputNames.includes(name)) missing.push(name);
  for (const name of REQUIRED.outputs) if (!runner.session.outputNames.includes(name)) missing.push(name);
  if (missing.length)
    throw new BundleError(
      `the graph is not a Laya ONNX export: it has no ${missing.join(', ')} (found inputs ${runner.session.inputNames.join(', ')} and outputs ${runner.session.outputNames.join(', ')})`
    );
}

/** Loads the tokenizer and the model and puts a judge in front of them. `say` gets progress lines. */
export async function loadJudge(
  paths: BundlePaths,
  options: { providers?: string[]; say?: (line: string) => void; preset?: string } = {}
): Promise<{ judge: OnnxJudge; runner: NodeOnnxRunner; preset: LayaOnnxPreset }> {
  const preset = layaOnnxPreset(options.preset);
  const say = options.say ?? (() => undefined);
  if (preset.declaresFormat && json<{ format?: string }>(paths, 'onnx_config.json').format !== 'laya-onnx')
    throw new BundleError('onnx_config.json does not describe a Laya ONNX export');
  const config = json<AgentConfig>(paths, 'rl_agent_config.json');
  say('loading the tokenizer');
  const tokenizer = new LayaTokenizer(
    json<TokenizerJson>(paths, 'tokenizer/tokenizer.json'),
    json<TokenizerConfig>(paths, 'tokenizer/tokenizer_config.json')
  );
  const model = paths[preset.model];
  if (!model) throw new BundleError(`${preset.model} is missing`);
  say('loading the model');
  const runner = await NodeOnnxRunner.create(model, options.providers ?? providersFor(process.platform));
  if (!preset.declaresFormat) checkGraph(runner);
  const agent = new LayaAgent({ config, tokenizer, runner });
  return {
    judge: new OnnxJudge(agent, {
      model: `laya:${preset.repo}`,
      provider: runner.provider,
      preset: preset.id,
    }),
    runner,
    preset,
  };
}
