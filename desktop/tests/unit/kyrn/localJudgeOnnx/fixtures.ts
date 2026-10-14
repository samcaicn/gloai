/**
 * A stand-in Laya bundle for tests: a tiny ONNX graph with Laya's inputs and outputs, and a minimal Metaspace
 * tokenizer. No real model is downloaded.
 *
 * The graph's logits are the marker positions (so the last option of a question always wins, and a boolean question
 * always answers true) and its act_logits are [qtype, qtype].
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

// A minimal protobuf writer, enough for an ONNX ModelProto.
const varint = (value: number): number[] => {
  const out: number[] = [];
  let v = value;
  while (v > 127) {
    out.push((v % 128) | 128);
    v = Math.floor(v / 128);
  }
  out.push(v);
  return out;
};
const tag = (field: number, wire: number) => varint(field * 8 + wire);
const int = (field: number, value: number) => [...tag(field, 0), ...varint(value)];
const bytes = (field: number, value: number[]) => [...tag(field, 2), ...varint(value.length), ...value];
const str = (field: number, value: string) => bytes(field, [...Buffer.from(value, 'utf8')]);

const FLOAT = 1;
const INT64 = 7;
const BOOL = 9;

/** ValueInfoProto for a tensor whose dimensions are named (symbolic). */
const tensorInfo = (name: string, elem: number, dims: string[]) =>
  str(1, name).concat(
    bytes(
      2,
      bytes(1, [
        ...int(1, elem),
        ...bytes(
          2,
          dims.flatMap((dim) => bytes(1, str(2, dim)))
        ),
      ])
    )
  );

const node = (op: string, inputs: string[], outputs: string[], attributes: number[][] = []) => [
  ...inputs.flatMap((input) => str(1, input)),
  ...outputs.flatMap((output) => str(2, output)),
  ...str(4, op),
  ...attributes.flatMap((attribute) => bytes(5, attribute)),
];
const intAttribute = (name: string, value: number) => [...str(1, name), ...int(3, value), ...int(20, 2)];

export function tinyModel(): Uint8Array {
  const axes = [...int(1, 1), ...int(2, INT64), ...str(8, 'axes'), ...bytes(7, varint(1))];
  const graph = [
    ...bytes(1, node('Cast', ['marker_pos'], ['logits'], [intAttribute('to', FLOAT)])),
    ...bytes(1, node('Cast', ['qtype'], ['q'], [intAttribute('to', FLOAT)])),
    ...bytes(1, node('Unsqueeze', ['q', 'axes'], ['q2'])),
    ...bytes(1, node('Concat', ['q2', 'q2'], ['act_logits'], [intAttribute('axis', 1)])),
    ...str(2, 'tiny-laya'),
    ...bytes(5, axes),
    ...bytes(11, tensorInfo('input_ids', INT64, ['batch', 'sequence'])),
    ...bytes(11, tensorInfo('attention_mask', INT64, ['batch', 'sequence'])),
    ...bytes(11, tensorInfo('marker_pos', INT64, ['batch', 'markers'])),
    ...bytes(11, tensorInfo('marker_mask', BOOL, ['batch', 'markers'])),
    ...bytes(11, tensorInfo('qtype', INT64, ['batch'])),
    ...bytes(12, tensorInfo('logits', FLOAT, ['batch', 'markers'])),
    ...bytes(12, tensorInfo('act_logits', FLOAT, ['batch', 'actions'])),
  ];
  return Uint8Array.from([...int(1, 8), ...str(2, 'mu-test'), ...bytes(7, graph), ...bytes(8, int(2, 18))]);
}

const added = (id: number, content: string, lstrip = false) => ({
  id,
  content,
  single_word: false,
  lstrip,
  rstrip: false,
  normalized: false,
});

/** A Metaspace BPE tokenizer with a handful of pieces; anything else is `<unk>`. */
export const tinyTokenizerJson = {
  version: '1.0',
  model: {
    type: 'BPE',
    vocab: {
      '<pad>': 0,
      '<eos>': 1,
      '<bos>': 2,
      '<unk>': 3,
      '<mask>': 4,
      '▁': 20,
      '▁a': 21,
      '▁b': 22,
      '▁question:': 23,
      '▁choice': 24,
      '▁noul': 25,
      '▁score': 26,
    },
    merges: [],
    unk_token: '<unk>',
    ignore_merges: true,
  },
  normalizer: { type: 'Replace', pattern: { String: ' ' }, content: '▁' },
  pre_tokenizer: { type: 'Metaspace', replacement: '▁', prepend_scheme: 'always', split: true },
  post_processor: null,
  decoder: null,
  added_tokens: [added(0, '<pad>'), added(1, '<eos>'), added(2, '<bos>'), added(4, '<mask>', true)],
};

export const tinyTokenizerConfig = {
  cls_token: '<bos>',
  sep_token: '<eos>',
  pad_token: '<pad>',
  mask_token: '<mask>',
};

export const tinyAgentConfig = {
  encoder: 'test',
  head_layers: 2,
  max_len: 64,
  head_max_len: 32,
  temperature: [1, 1, 1],
  temperature_by_options: {},
};

/** Writes a complete stand-in bundle (repository layout) into `dir` and returns its paths. */
export function writeTinyBundle(dir: string): Record<string, string> {
  mkdirSync(join(dir, 'tokenizer'), { recursive: true });
  const files: Record<string, string | Uint8Array> = {
    'model.onnx': tinyModel(),
    'onnx_config.json': JSON.stringify({ format: 'laya-onnx', format_version: 1 }),
    'rl_agent_config.json': JSON.stringify(tinyAgentConfig),
    'tokenizer/tokenizer.json': JSON.stringify(tinyTokenizerJson),
    'tokenizer/tokenizer_config.json': JSON.stringify(tinyTokenizerConfig),
  };
  const paths: Record<string, string> = {};
  for (const [name, content] of Object.entries(files)) {
    paths[name] = join(dir, ...name.split('/'));
    writeFileSync(paths[name], content);
  }
  return paths;
}
