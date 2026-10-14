import { readFileSync } from 'node:fs';
import { LAYA_ONNX_REPO } from '../../../common/kyrn/layaOnnx.ts';
import { OnnxJudge } from './judge.ts';
import { LayaAgent } from './laya/agent.ts';
import { LayaTokenizer, type TokenizerConfig, type TokenizerJson } from './laya/tokenizer.ts';
import type { AgentConfig } from './laya/types.ts';
import { NodeOnnxRunner, providersFor } from './runner.ts';

/** The files of a Laya ONNX export, by their repository path (see `LAYA_ONNX_FILES`). */
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

/** Loads the tokenizer and the model and puts a judge in front of them. `say` gets progress lines. */
export async function loadJudge(
  paths: BundlePaths,
  options: { providers?: string[]; say?: (line: string) => void } = {}
): Promise<{ judge: OnnxJudge; runner: NodeOnnxRunner }> {
  const say = options.say ?? (() => undefined);
  if (json<{ format?: string }>(paths, 'onnx_config.json').format !== 'laya-onnx')
    throw new BundleError('onnx_config.json does not describe a Laya ONNX export');
  const config = json<AgentConfig>(paths, 'rl_agent_config.json');
  say('loading the tokenizer');
  const tokenizer = new LayaTokenizer(
    json<TokenizerJson>(paths, 'tokenizer/tokenizer.json'),
    json<TokenizerConfig>(paths, 'tokenizer/tokenizer_config.json')
  );
  const model = paths['model.onnx'];
  if (!model) throw new BundleError('model.onnx is missing');
  say('loading the model');
  const runner = await NodeOnnxRunner.create(model, options.providers ?? providersFor(process.platform));
  const agent = new LayaAgent({ config, tokenizer, runner });
  return { judge: new OnnxJudge(agent, { model: `laya:${LAYA_ONNX_REPO}`, provider: runner.provider }), runner };
}
