/**
 * The local judge where Core ML does not run (Windows, Linux): Laya multilingual exported to ONNX, run by the app with
 * onnxruntime-node. The app can download the model for the person (`setup` from the settings panel or the first-run
 * guide) from one of `LAYA_ONNX_SOURCES`; a person who prefers can still get the files themselves (a link, or the `hf`
 * command below), put them in the model folder, and the app checks them and starts the judge.
 *
 * The files are pinned to one revision of the repository, so the hashes below say exactly what was tested.
 *
 * More than one build is offered (`LAYA_ONNX_PRESETS`): the export mu first shipped, and weight-only 4-bit exports of
 * the same checkpoint that are a third of the size at the same accuracy. Each preset names its repository, revision,
 * home folder and graph file, so downloading, checking and loading all read one object.
 */

/** One published build of the model: where it comes from, where it goes, and what it is made of. */
export type LayaOnnxPreset = {
  /** Stable id, stored in the settings and reported on the download task. */
  id: string;
  /** i18n key of the build's display name. */
  labelKey: string;
  /** i18n key of one line saying what this build is, for the settings panel. */
  noteKey: string;
  /** Hugging Face (or mirror) repository id. */
  repo: string;
  /** The revision every file is pinned to. */
  revision: string;
  /** The folder under mu's home that holds the model, unless `MU_LOCAL_JUDGE_ONNX_DIR` names another one. */
  folder: readonly string[];
  /** The graph file inside the folder, by its repository path. */
  model: string;
  /** Every file the build needs, including the graph's external data when it has one. */
  files: readonly LayaOnnxFile[];
  /** What the person downloads in all, in MB. */
  downloadMb: number;
  /**
   * Whether the build ships `onnx_config.json` describing the export. The 4-bit builds do not, so the loader reads the
   * graph's own input and output names instead of trusting a side file.
   */
  declaresFormat: boolean;
};

export type LayaOnnxFile = {
  /** The path inside the repository. */
  name: string;
  bytes: number;
  sha256: string;
};

const mb = (files: readonly LayaOnnxFile[]): number =>
  Math.round(files.reduce((sum, file) => sum + file.bytes, 0) / 1_000_000);

/** The tokenizer is byte-identical across the builds: one checkpoint, exported more than once. */
const TOKENIZER_FILES: readonly LayaOnnxFile[] = [
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

/** The build mu shipped first: the full multilingual export, `mizchi/laya-multilingual-onnx`. */
const FULL_FILES: readonly LayaOnnxFile[] = [
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
  ...TOKENIZER_FILES,
];

/**
 * Weight-only 4-bit exports of the same checkpoint (`androidli/laya-multilingual-onnx-int4`): the layer projections go
 * to `MatMulNBits` and the 256k-token embedding to `GatherBlockQuantized`, so the graph keeps the table packed instead
 * of materializing it as floats. On the publisher's 60-item set both match the full build within noise (0.700 and 0.683
 * against 0.683) at a third of the size. They ship no `onnx_config.json`.
 */
const int4Files = (
  graph: string,
  graphBytes: number,
  graphSha: string,
  dataBytes: number,
  dataSha: string
): readonly LayaOnnxFile[] => [
  { name: graph, bytes: graphBytes, sha256: graphSha },
  { name: `${graph}.data`, bytes: dataBytes, sha256: dataSha },
  {
    name: 'rl_agent_config.json',
    bytes: 472,
    sha256: '25061739243b617ad88d1219ba6f8a9c86c5881ca28df024fa2d9b3b2fcc30c6',
  },
  ...TOKENIZER_FILES,
];

export const LAYA_ONNX_PRESETS: readonly LayaOnnxPreset[] = [
  {
    id: 'default',
    labelKey: 'mu.judges.laya.presetDefault',
    noteKey: 'mu.judges.laya.presetDefaultNote',
    repo: 'mizchi/laya-multilingual-onnx',
    revision: 'd9d003d543e63d6d3375c21d44624136bd1e0bad',
    folder: ['local-judge', 'laya-multilingual-onnx'],
    model: 'model.onnx',
    files: FULL_FILES,
    downloadMb: mb(FULL_FILES),
    declaresFormat: true,
  },
  {
    id: 'int4',
    labelKey: 'mu.judges.laya.presetInt4',
    noteKey: 'mu.judges.laya.presetInt4Note',
    repo: 'androidli/laya-multilingual-onnx-int4',
    revision: 'ad024da1cc6abb5fed4dbdd60352387bec78a5c5',
    folder: ['local-judge', 'laya-multilingual-int4'],
    model: 'laya-multilingual-int4-blk32.onnx',
    files: int4Files(
      'laya-multilingual-int4-blk32.onnx',
      160_779,
      '278ab0709fefc93d877c1ef6a1eedb0f812d693df01dc40c03bdd369d339ddd2',
      206_043_136,
      '477b9b0d2ee4e9dc5477ac7df9f46e1297e46a36cecadd5ed99368160c5732a4'
    ),
    downloadMb: 241,
    declaresFormat: false,
  },
  {
    id: 'int4-small',
    labelKey: 'mu.judges.laya.presetInt4Small',
    noteKey: 'mu.judges.laya.presetInt4SmallNote',
    repo: 'androidli/laya-multilingual-onnx-int4',
    revision: 'ad024da1cc6abb5fed4dbdd60352387bec78a5c5',
    folder: ['local-judge', 'laya-multilingual-int4-small'],
    model: 'laya-multilingual-int4-layer32-vocab128.onnx',
    files: int4Files(
      'laya-multilingual-int4-layer32-vocab128.onnx',
      163_701,
      '82a8b4e328d9930cfa9f1383170bb8a17f68120afca1ff0f8b41c73c6e3b973e',
      187_611_136,
      'ed2277fd2186b8ff5e32f1aa0f1f27ef0054e807f203cff330c387df931e3468'
    ),
    downloadMb: 222,
    declaresFormat: false,
  },
];

/** The build a machine gets that never chose one. */
export const LAYA_ONNX_DEFAULT_ID = 'default';

/** The default build's own names, so code and tests that speak of one model can keep reading them. */
export const LAYA_ONNX_REPO = 'mizchi/laya-multilingual-onnx';
export const LAYA_ONNX_REVISION = 'd9d003d543e63d6d3375c21d44624136bd1e0bad';
/** The folder under mu's home that holds the model unless `MU_LOCAL_JUDGE_ONNX_DIR` names another one. */
export const LAYA_ONNX_FOLDER = ['local-judge', 'laya-multilingual-onnx'] as const;
export const LAYA_ONNX_FILES: readonly LayaOnnxFile[] = FULL_FILES;
/** What the person downloads in all, in MB, for the consent-free "get it yourself" text. */
export const LAYA_ONNX_DOWNLOAD_MB = mb(FULL_FILES);

/** One preset by id, never undefined: an unknown id falls back to the default build. */
export function layaOnnxPreset(id?: string): LayaOnnxPreset {
  return LAYA_ONNX_PRESETS.find((preset) => preset.id === id) ?? LAYA_ONNX_PRESETS[0]!;
}

/** The files one build is made of. */
export const layaOnnxFiles = (id?: string): readonly LayaOnnxFile[] => layaOnnxPreset(id).files;

/** The folder under mu's home one build goes into. */
export const layaOnnxFolder = (id?: string): readonly string[] => layaOnnxPreset(id).folder;

/** The repository's file list at the pinned revision, for a person who downloads in the browser. */
export const LAYA_ONNX_PAGE = `https://huggingface.co/${LAYA_ONNX_REPO}/tree/${LAYA_ONNX_REVISION}`;

/** The same page for one build. */
export function layaOnnxPage(id?: string): string {
  const preset = layaOnnxPreset(id);
  return `https://huggingface.co/${preset.repo}/tree/${preset.revision}`;
}

/** A direct link to one file at the pinned revision: the browser downloads it. */
export function layaOnnxFileUrl(name: string): string {
  return `https://huggingface.co/${LAYA_ONNX_REPO}/resolve/${LAYA_ONNX_REVISION}/${name}?download=true`;
}

/** The one command that fetches everything into the model folder, for a person who has the `hf` CLI. */
export function layaOnnxCommand(folder: string, id?: string): string {
  const preset = layaOnnxPreset(id);
  return `hf download ${preset.repo} --revision ${preset.revision} --local-dir "${folder}"`;
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
  /** Builds the direct download URL for one file at the pinned revision of one build. */
  fileUrl: (name: string, preset: LayaOnnxPreset, customBase?: string) => string;
  /** Whether the person may type a base URL for this source (only the custom source). */
  needsBase?: boolean;
};

export const LAYA_ONNX_SOURCES: readonly LayaSource[] = [
  {
    id: 'huggingface',
    labelKey: 'mu.judges.laya.sourceHuggingface',
    fileUrl: (name, preset) => `https://huggingface.co/${preset.repo}/resolve/${preset.revision}/${name}?download=true`,
  },
  {
    id: 'hf-mirror',
    labelKey: 'mu.judges.laya.sourceHfMirror',
    fileUrl: (name, preset) => `https://hf-mirror.com/${preset.repo}/resolve/${preset.revision}/${name}?download=true`,
  },
  {
    id: 'modelscope',
    labelKey: 'mu.judges.laya.sourceModelscope',
    fileUrl: (name, preset) => `https://modelscope.cn/models/${preset.repo}/resolve/${preset.revision}/${name}`,
  },
  {
    id: 'custom',
    labelKey: 'mu.judges.laya.sourceCustom',
    needsBase: true,
    fileUrl: (name, preset, customBase) =>
      `${customBase?.replace(/\/+$/, '') ?? ''}/${preset.repo}/resolve/${preset.revision}/${name}?download=true`,
  },
];

/** The direct download URL for one file from the named source and build (default: Hugging Face, default build). */
export function layaOnnxFileUrlFor(sourceId: string, name: string, customBase?: string, presetId?: string): string {
  const source = LAYA_ONNX_SOURCES.find((candidate) => candidate.id === sourceId) ?? LAYA_ONNX_SOURCES[0]!;
  return source.fileUrl(name, layaOnnxPreset(presetId), customBase);
}

/**
 * The mirrors to try for one file, in the order the app falls back through them: Hugging Face, then its China mirror
 * hf-mirror.com, then ModelScope, and finally a custom base the person typed. The downloader uses the first that
 * serves the bytes, so a person never has to pick a mirror by hand -- the closest working one wins.
 */
export function layaOnnxUrlCandidates(name: string, presetId?: string, customBase?: string): string[] {
  const preset = layaOnnxPreset(presetId);
  const urls = LAYA_ONNX_SOURCES.filter((source) => source.id !== 'custom').map((source) =>
    source.fileUrl(name, preset)
  );
  if (customBase) {
    const custom = LAYA_ONNX_SOURCES.find((source) => source.id === 'custom');
    if (custom) urls.push(custom.fileUrl(name, preset, customBase));
  }
  return urls;
}
