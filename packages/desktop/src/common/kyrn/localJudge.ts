/**
 * The local judge (Laya) as the app manages it. Three runtimes, one contract on the same address:
 *
 * - coreml (Apple Silicon Macs): installed from the settings with one click after the person agrees to the download,
 *   started and stopped there. The main process runs the harness's own script,
 *   `kyrn/bin/kyrn-judge-local setup|start|stop`, so the app and `mu judge` in a terminal do the same thing.
 * - onnx (Windows, Linux): the app runs Laya itself (see `common/kyrn/layaOnnx.ts`). It can download the model with one
 *   click (`setup`, from the chosen source in `LAYA_ONNX_SOURCES`), or the person gets it from Hugging Face into the
 *   model folder, and the app checks it and starts the judge.
 * - gguf (any machine with a `llama-server`): the judge is the same model as a GGUF file, run by the llama.cpp the
 *   person already has (see `common/kyrn/layaGguf.ts`). The app starts `llama-server` beside the judge process, so a
 *   machine whose GPU has no ONNX provider can still run the judge on it.
 */

/** setup: coreml's install (downloads, needs consent). locate: "open the model folder". */
export type LocalJudgeAction = 'setup' | 'start' | 'stop' | 'locate';

/**
 * ok: this machine can run it. platform: no runtime for this machine. runtime: the runtime is there but the binary
 * it runs with is not (`llama-server` for gguf). uv: installing needs `uv`, which is missing.
 */
export type LocalJudgeSupport = 'ok' | 'platform' | 'runtime' | 'uv';

export type LocalJudgeRuntime = 'coreml' | 'onnx' | 'gguf';

/** onnx `setup`: how far the model download has got, so the screen can show a progress bar. */
export type LocalJudgeDownloadProgress = {
  /** The file being downloaded now. */
  file: string;
  /** Its index among all model files (0-based). */
  index: number;
  /** How many model files there are in all. */
  total: number;
  /** Bytes downloaded of the current file. */
  downloadedBytes: number;
  /** Bytes expected for the current file (0 when the server sends no length). */
  totalBytes: number;
  /** 0..1 of the current file. */
  percent: number;
};

export type LocalJudgeTask = {
  id: number;
  action: LocalJudgeAction;
  phase: 'running' | 'done' | 'failed';
  /** The script's last lines, for progress and for why it failed. English, as the script writes them. */
  output: string[];
  /** onnx: why it failed, as a code the screen words (see `LocalJudgeProblem`). */
  problem?: LocalJudgeProblem;
  /** onnx `setup`: live download progress across the model files. */
  progress?: LocalJudgeDownloadProgress;
  /** onnx `setup`: the source the files are downloaded from (its id). */
  source?: string;
  /** onnx `setup`: which build of the model is downloaded (its id, see `LAYA_ONNX_PRESETS`). */
  preset?: string;
};

/**
 * missing / wrong: model files absent, or not the tested version. runtime: no execution provider could run the model.
 * port: something else answers on the judge's port. stopped: the judge process ended on its own. foreign: a judge the
 * app did not start answers there, so the app cannot stop it. cancelled: the person stopped the model download.
 */
export type LocalJudgeProblem = 'missing' | 'wrong' | 'runtime' | 'port' | 'stopped' | 'foreign' | 'cancelled';

/** onnx: the model folder and what it still lacks. */
export type LocalJudgeModel = {
  folder: string;
  /** Repository paths of files not found (see a preset's `files`). */
  missing: string[];
  /** Files found but not the tested version (another size or hash). */
  wrong: string[];
  /** Which build of the model the folder is checked against (see `LAYA_ONNX_PRESETS`). */
  preset?: string;
};

export type LocalJudgeState = {
  support: LocalJudgeSupport;
  /** Which runtime this machine uses; absent from an older main process, which only had coreml. */
  runtime?: LocalJudgeRuntime;
  /** Every runtime this machine can run, so the settings can offer a choice where there is one. */
  runtimes?: LocalJudgeRuntime[];
  /** coreml: the Python environment and the weights are there. onnx/gguf: every model file is there and checked. */
  installed: boolean;
  /** Its health check answers on this machine. */
  running: boolean;
  /** The address it answers on. */
  url: string;
  /** onnx and gguf only. */
  model?: LocalJudgeModel;
  /** What the app runs now or ran last; unset before the first action of this app run. */
  task?: LocalJudgeTask;
};

/** What installing downloads, about: the Python packages (~125 MB) and Laya's weights (~680 MB). */
export const LOCAL_JUDGE_DOWNLOAD_MB = 800;
