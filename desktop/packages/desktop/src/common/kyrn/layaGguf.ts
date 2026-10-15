/**
 * The local judge as a GGUF model run by llama.cpp: `llama-server` serves a decision model on `/v1/systemone`, an
 * endpoint with the same state-and-questions contract the app's own ONNX judge speaks, so the harness talks to either
 * without knowing which one answers.
 *
 * This path is for a machine that already runs llama.cpp -- it is the same runtime the person's other local models
 * use, and it reaches a GPU the app's ONNX runtime has no provider for. The app does not ship llama.cpp: it starts the
 * `llama-server` it is pointed at (`MU_LLAMA_SERVER_BIN`, or one on the PATH).
 *
 * The builds below are the ones ggml-org publishes from the English checkpoint. A checkpoint in another language has
 * no GGUF build published yet, so `custom` takes a file the person already has.
 */

export type LayaGgufFile = {
  /** The path inside the repository. */
  name: string;
  bytes: number;
  /** Absent for a build whose bytes were never hashed here: it is then checked by size alone. */
  sha256?: string;
};

/** One runnable GGUF build. */
export type LayaGgufPreset = {
  id: string;
  /** i18n key of the build's display name. */
  labelKey: string;
  /** i18n key of one line saying what this build is. */
  noteKey: string;
  /** Hugging Face (or mirror) repository id, or empty for a file the person already has. */
  repo: string;
  revision: string;
  /** The folder under mu's home that holds the model, unless `MU_LOCAL_JUDGE_GGUF_DIR` names another one. */
  folder: readonly string[];
  /** The graph file inside the folder, by its repository path. */
  model: string;
  files: readonly LayaGgufFile[];
  downloadMb: number;
  /** `custom` names no repository: the person points the app at a file that is already on disk. */
  custom?: boolean;
};

const mb = (files: readonly LayaGgufFile[]): number =>
  Math.round(files.reduce((sum, file) => sum + file.bytes, 0) / 1_000_000);

/** Hashed here when the build was pinned: the 8-bit file, downloaded once. */
const LAYA_Q8_SHA256 = 'c06528c5746d3bb8baa72a27938be95abbfd0b226f8471e8a9e365ed0bb066d2';

/** `ggml-org/Laya-GGUF`, the build llama.cpp itself publishes: bf16 and 8-bit. */
const LAYS_GGUF_REPO = 'ggml-org/Laya-GGUF';
const LAYS_GGUF_REVISION = '22265007700297ba9e128297e82540cf28c5d7d4';

export const LAYA_GGUF_PRESETS: readonly LayaGgufPreset[] = [
  {
    id: 'gguf-q8',
    labelKey: 'mu.judges.laya.ggufQ8',
    noteKey: 'mu.judges.laya.ggufQ8Note',
    repo: LAYS_GGUF_REPO,
    revision: LAYS_GGUF_REVISION,
    folder: ['local-judge', 'laya-gguf-q8'],
    model: 'Laya-Q8_0.gguf',
    files: [{ name: 'Laya-Q8_0.gguf', bytes: 449_397_600, sha256: LAYA_Q8_SHA256 }],
    downloadMb: mb([{ name: 'Laya-Q8_0.gguf', bytes: 449_397_600 }]),
  },
  {
    id: 'gguf-bf16',
    labelKey: 'mu.judges.laya.ggufBf16',
    noteKey: 'mu.judges.laya.ggufBf16Note',
    repo: LAYS_GGUF_REPO,
    revision: LAYS_GGUF_REVISION,
    folder: ['local-judge', 'laya-gguf-bf16'],
    model: 'Laya-BF16.gguf',
    files: [{ name: 'Laya-BF16.gguf', bytes: 844_026_720 }],
    downloadMb: mb([{ name: 'Laya-BF16.gguf', bytes: 844_026_720 }]),
  },
  {
    id: 'gguf-custom',
    labelKey: 'mu.judges.laya.ggufCustom',
    noteKey: 'mu.judges.laya.ggufCustomNote',
    repo: '',
    revision: '',
    folder: ['local-judge', 'laya-gguf'],
    model: '',
    files: [],
    downloadMb: 0,
    custom: true,
  },
];

export const LAYA_GGUF_DEFAULT_ID = 'gguf-q8';

export function layaGgufPreset(id?: string): LayaGgufPreset {
  return LAYA_GGUF_PRESETS.find((preset) => preset.id === id) ?? LAYA_GGUF_PRESETS[0]!;
}

/** The repository page of one build, for a person who downloads in the browser. */
export function layaGgufPage(id?: string): string {
  const preset = layaGgufPreset(id);
  return preset.repo ? `https://huggingface.co/${preset.repo}/tree/${preset.revision}` : '';
}

/** The direct download URL of one file of one build, from one of the same mirrors the ONNX builds use. */
export function layaGgufFileUrl(name: string, id?: string, sourceId = 'huggingface', customBase?: string): string {
  const preset = layaGgufPreset(id);
  const path = `${preset.repo}/resolve/${preset.revision}/${name}`;
  switch (sourceId) {
    case 'hf-mirror':
      return `https://hf-mirror.com/${path}?download=true`;
    case 'modelscope':
      return `https://modelscope.cn/models/${path}`;
    case 'custom':
      return `${customBase?.replace(/\/+$/, '') ?? ''}/${path}?download=true`;
    default:
      return `https://huggingface.co/${path}?download=true`;
  }
}
