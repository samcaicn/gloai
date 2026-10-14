/**
 * The local judge where Core ML does not run (Windows, Linux): Laya multilingual exported to ONNX, run by the app with
 * onnxruntime-node. The app can download the model for the person (`setup` from the settings panel or the first-run
 * guide) from one of `LAYA_ONNX_SOURCES`; a person who prefers can still get the files themselves (a link, or the `hf`
 * command below), put them in the model folder, and the app checks them and starts the judge.
 *
 * The files are pinned to one revision of the repository, so the hashes below say exactly what was tested.
 */

export const LAYA_ONNX_REPO = 'mizchi/laya-multilingual-onnx';
export const LAYA_ONNX_REVISION = 'd9d003d543e63d6d3375c21d44624136bd1e0bad';
/** The folder under mu's home that holds the model unless `MU_LOCAL_JUDGE_ONNX_DIR` names another one. */
export const LAYA_ONNX_FOLDER = ['local-judge', 'laya-multilingual-onnx'] as const;

export type LayaOnnxFile = {
  /** The path inside the repository. */
  name: string;
  bytes: number;
  sha256: string;
};

export const LAYA_ONNX_FILES: readonly LayaOnnxFile[] = [
  {
    name: 'model.onnx',
    bytes: 646_870_871,
    sha256: '0b095e005a4c295cae74d47b7eb6931c369d48f5b720b45278d774165798310c',
  },
  {
    name: 'onnx_config.json',
    bytes: 332,
    sha256: '13db475255d076da580a3435f28904e3360fe7f6380d7e3c75ee586e966ca5f0',
  },
  {
    name: 'rl_agent_config.json',
    bytes: 473,
    sha256: '9a669a70961064c3c6cc76d2afb8bc5fb10dcd8349bb66e5f7b9b1afb74440d5',
  },
  {
    name: 'tokenizer/tokenizer.json',
    bytes: 34_363_188,
    sha256: '609d8f4c067cd3950f88594c5a802616cea245823836ef5848ee4fc40aab5b6f',
  },
  {
    name: 'tokenizer/tokenizer_config.json',
    bytes: 524,
    sha256: '6c6b2d8e3c84ce0e671c129cd6b374b235d6f9863042a5836358d00a89bbb5a1',
  },
];

/** What the person downloads in all, in MB, for the consent-free "get it yourself" text. */
export const LAYA_ONNX_DOWNLOAD_MB = Math.round(LAYA_ONNX_FILES.reduce((sum, file) => sum + file.bytes, 0) / 1_000_000);

/** The repository's file list at the pinned revision, for a person who downloads in the browser. */
export const LAYA_ONNX_PAGE = `https://huggingface.co/${LAYA_ONNX_REPO}/tree/${LAYA_ONNX_REVISION}`;

/** A direct link to one file at the pinned revision: the browser downloads it. */
export function layaOnnxFileUrl(name: string): string {
  return `https://huggingface.co/${LAYA_ONNX_REPO}/resolve/${LAYA_ONNX_REVISION}/${name}?download=true`;
}

/** The one command that fetches everything into the model folder, for a person who has the `hf` CLI. */
export function layaOnnxCommand(folder: string): string {
  return `hf download ${LAYA_ONNX_REPO} --revision ${LAYA_ONNX_REVISION} --local-dir "${folder}"`;
}

/**
 * Download sources for the Laya model. The official Hugging Face repo is proxied by hf-mirror.com (a China mirror that
 * serves the same repository and revision), and ModelScope carries a copy too. A custom source lets the person name any
 * server that exposes the same `repo/resolve/revision/path?download=true` layout (their own mirror, an internal cache,
 * a CDN). Adding a source here is enough for both the settings panel and the first-run guide to offer it.
 */
export type LayaSource = {
  /** Stable id, stored on the download task and remembered by the UI. */
  id: string;
  /** i18n key of the source's display name. */
  labelKey: string;
  /** Builds the direct download URL for one file at the pinned revision. */
  fileUrl: (name: string, customBase?: string) => string;
  /** Whether the person may type a base URL for this source (only the custom source). */
  needsBase?: boolean;
};

export const LAYA_ONNX_SOURCES: readonly LayaSource[] = [
  {
    id: 'huggingface',
    labelKey: 'mu.judges.laya.sourceHuggingface',
    fileUrl: (name) => `https://huggingface.co/${LAYA_ONNX_REPO}/resolve/${LAYA_ONNX_REVISION}/${name}?download=true`,
  },
  {
    id: 'hf-mirror',
    labelKey: 'mu.judges.laya.sourceHfMirror',
    fileUrl: (name) => `https://hf-mirror.com/${LAYA_ONNX_REPO}/resolve/${LAYA_ONNX_REVISION}/${name}?download=true`,
  },
  {
    id: 'modelscope',
    labelKey: 'mu.judges.laya.sourceModelscope',
    fileUrl: (name) => `https://modelscope.cn/models/${LAYA_ONNX_REPO}/resolve/${LAYA_ONNX_REVISION}/${name}`,
  },
  {
    id: 'custom',
    labelKey: 'mu.judges.laya.sourceCustom',
    needsBase: true,
    fileUrl: (name, customBase) =>
      `${customBase?.replace(/\/+$/, '') ?? ''}/${LAYA_ONNX_REPO}/resolve/${LAYA_ONNX_REVISION}/${name}?download=true`,
  },
];

/** The direct download URL for one file from the named source (default: Hugging Face). */
export function layaOnnxFileUrlFor(sourceId: string, name: string, customBase?: string): string {
  const source = LAYA_ONNX_SOURCES.find((candidate) => candidate.id === sourceId) ?? LAYA_ONNX_SOURCES[0];
  return source.fileUrl(name, customBase);
}
