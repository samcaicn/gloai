import { mkdirSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { KyrnError } from '../../../common/kyrn/errors';
import {
  LAYA_ONNX_DEFAULT_ID,
  layaOnnxFiles,
  layaOnnxFolder,
  layaOnnxFileUrlFor,
  layaOnnxPreset,
} from '../../../common/kyrn/layaOnnx';
import type {
  LocalJudgeAction,
  LocalJudgeProblem,
  LocalJudgeState,
  LocalJudgeSupport,
  LocalJudgeTask,
} from '../../../common/kyrn/localJudge';
import { checkBundle, type BundleCheck } from '../../services/localJudgeOnnx/bundle';
import { downloadModelFiles } from './modelDownload.ts';
import { parseObject, readOptional, stripJsonComments } from './config/files';
import { configPath, muHome } from './naming.ts';

/** The judge process as the manager needs it: an Electron utility process, or a stand-in in tests. */
export type JudgeChild = {
  stdout: NodeJS.ReadableStream | null;
  stderr: NodeJS.ReadableStream | null;
  on(event: 'message', listener: (message: unknown) => void): unknown;
  on(event: 'exit', listener: (code: number) => void): unknown;
  kill(): boolean;
};

export type OnnxJudgeDeps = {
  /** Starts `localJudgeOnnx.js` (see services/localJudgeOnnx/entry.ts) with this environment. */
  fork: (env: NodeJS.ProcessEnv) => JudgeChild;
  health: (url: string) => Promise<boolean>;
  /** `preset` names which build of the model to look for. */
  check: (folder: string, hash: boolean, preset?: string) => Promise<BundleCheck>;
  /** Creates the folder if needed and shows it in the file manager. */
  open: (folder: string) => Promise<void>;
  platform: NodeJS.Platform;
  arch: string;
  env: NodeJS.ProcessEnv;
  home: string;
};

const ACTIONS: ReadonlySet<string> = new Set<LocalJudgeAction>(['setup', 'start', 'stop', 'locate']);
const OUTPUT_LINES = 12;
const LINE_CHARS = 300;
const FAILURES: Record<string, LocalJudgeProblem> = {
  missing: 'missing',
  wrong: 'wrong',
  runtime: 'runtime',
  port: 'port',
};

/**
 * Whether this machine runs the local judge with ONNX: Windows and Linux on x64 or arm64, where Core ML does not exist.
 * `MU_LOCAL_JUDGE_RUNTIME=onnx` picks it on an Apple Silicon Mac too (onnxruntime-node has no Intel Mac build).
 */
export function usesOnnxJudge(platform: NodeJS.Platform, arch: string, env: NodeJS.ProcessEnv): boolean {
  if (arch !== 'x64' && arch !== 'arm64') return false;
  if (platform === 'win32' || platform === 'linux') return true;
  return platform === 'darwin' && arch === 'arm64' && env.MU_LOCAL_JUDGE_RUNTIME === 'onnx';
}

/** Whether mu.json asks a local judge at any tier: then the app starts it by itself when the model is there. */
export function wantsLocalJudge(configText: string): boolean {
  try {
    const config = parseObject(stripJsonComments(configText));
    const judges = config.judges as Record<string, { type?: unknown }> | undefined;
    const tiers = Array.isArray(config.tiers) ? config.tiers : [];
    return tiers.some((name) => typeof name === 'string' && judges?.[name]?.type === 'local');
  } catch {
    return false;
  }
}

async function health(url: string): Promise<boolean> {
  try {
    const response = await fetch(`${url}/health`, { signal: AbortSignal.timeout(1500) });
    if (!response.ok) return false;
    const body = (await response.json()) as { status?: unknown };
    return typeof body.status === 'string';
  } catch {
    return false;
  }
}

/**
 * Laya where Core ML does not run: the app checks the model the person downloaded, starts the judge process, stops
 * it, and opens the model folder. The same `state()` / `run()` as the Core ML `LocalJudge`, so the settings do not
 * care which one this machine has.
 */
export class OnnxLocalJudge {
  private deps: OnnxJudgeDeps;
  private task?: LocalJudgeTask;
  private next = 0;
  private child?: JudgeChild;
  /** Aborts the in-flight `setup` download when the person cancels it from the UI. */
  private abort?: AbortController;
  /** Set when the person cancels a `setup` download, so a transient error is not retried and the task is not closed twice. */
  private cancelled = false;
  /** The build the person picked last, if any: `MU_LOCAL_JUDGE_ONNX_PRESET`, then this, then the default build. */
  private chosen?: string;

  constructor(deps: Pick<OnnxJudgeDeps, 'fork' | 'open'> & Partial<OnnxJudgeDeps>) {
    this.deps = {
      health,
      check: (folder, hash, preset) => checkBundle(folder, { hash, preset }),
      platform: process.platform,
      arch: process.arch,
      env: process.env,
      home: homedir(),
      ...deps,
    };
  }

  private get url(): string {
    return `http://127.0.0.1:${this.port}`;
  }

  private get port(): string {
    const { env } = this.deps;
    return env.MU_LOCAL_JUDGE_PORT || env.KYRN_LOCAL_JUDGE_PORT || '47823';
  }

  /** Which build of the model this machine uses: the environment, then the last choice, then the default build. */
  get preset(): string {
    return this.deps.env.MU_LOCAL_JUDGE_ONNX_PRESET || this.chosen || LAYA_ONNX_DEFAULT_ID;
  }

  /** `MU_LOCAL_JUDGE_ONNX_DIR`, else `<mu home>/local-judge/<build folder>`. */
  get folder(): string {
    const { env, home } = this.deps;
    return env.MU_LOCAL_JUDGE_ONNX_DIR || join(muHome(home), ...layaOnnxFolder(this.preset));
  }

  private support(): LocalJudgeSupport {
    const { platform, arch, env } = this.deps;
    return usesOnnxJudge(platform, arch, env) ? 'ok' : 'platform';
  }

  async state(): Promise<LocalJudgeState> {
    const [check, running] = await Promise.all([
      this.deps.check(this.folder, false, this.preset),
      this.deps.health(this.url),
    ]);
    return {
      support: this.support(),
      runtime: 'onnx',
      installed: check.ready,
      running,
      url: this.url,
      model: { folder: this.folder, missing: check.missing, wrong: check.wrong, preset: this.preset },
      ...(this.task ? { task: { ...this.task, output: [...this.task.output] } } : {}),
    };
  }

  /** `start` checks the model (hashing it the first time) and starts the judge; `consent` is coreml's and unused.
   *  `setup` downloads the model from the chosen source (onnx) so the first-run guide and the settings can offer
   *  one click instead of a manual `hf download`. */
  async run(
    action: LocalJudgeAction,
    _consent = false,
    source?: { id: string; base?: string; preset?: string }
  ): Promise<LocalJudgeState> {
    if (!ACTIONS.has(action)) throw new KyrnError('invalid', `Unknown local judge action: ${String(action)}`);
    // `setup` also carries the build to download: the panel lets the person pick one, and it decides the folder.
    if (source?.preset) this.chosen = source.preset;
    // Cancelling a `setup` download from the UI aborts the in-flight request; the stop handler for a running judge
    // below does not apply because `setup` has no child process yet.
    const runningTask = this.task;
    if (action === 'stop' && runningTask?.action === 'setup' && runningTask.phase === 'running') {
      this.cancelled = true;
      this.abort?.abort();
      this.say(runningTask, 'Download cancelled');
      this.end(runningTask, 'cancelled');
      return this.state();
    }
    if (action === 'setup') {
      if (this.support() !== 'ok') throw new KyrnError('invalid', 'This machine cannot run the local judge');
      const task: LocalJudgeTask = {
        id: ++this.next,
        action,
        phase: 'running',
        output: [],
        source: source?.id ?? 'huggingface',
        preset: this.preset,
      };
      this.task = task;
      // Runs in the background like `start`: the screen follows via `localJudgeState`, which reports progress.
      void this.download(task, source).catch((error: unknown) => {
        if (this.cancelled) return; // the stop handler already closed the task
        this.say(task, error instanceof Error ? error.message : String(error));
        this.end(task, 'wrong');
      });
      return this.state();
    }
    if (this.support() !== 'ok') throw new KyrnError('invalid', 'This machine cannot run the local judge');
    if (action === 'locate') {
      await this.deps.open(this.folder);
      return this.state();
    }
    // One action at a time, except that a stop may cut a start short (loading the model takes a while).
    if (this.task?.phase === 'running' && !(action === 'stop' && this.child)) return this.state();
    const previous = this.task;
    const task: LocalJudgeTask = { id: ++this.next, action, phase: 'running', output: [] };
    this.task = task;
    if (action === 'start') void this.start(task);
    else {
      if (previous) this.end(previous, 'stopped');
      void this.stop(task);
    }
    return this.state();
  }

  /** Starts the judge when mu.json asks for a local judge and the model is there, checked. Called at app start. */
  async autoStart(agentDir: string): Promise<boolean> {
    if (this.support() !== 'ok' || this.child || this.task?.phase === 'running') return false;
    if (!wantsLocalJudge(readOptional(configPath(agentDir)))) return false;
    if (!(await this.deps.check(this.folder, false, this.preset)).ready || (await this.deps.health(this.url))) return false;
    await this.run('start');
    return true;
  }

  private say(task: LocalJudgeTask, chunk: Buffer | string): void {
    const lines = String(chunk)
      .split(/[\r\n]+/)
      .map((line) => line.trim())
      .filter(Boolean)
      .map((line) => (line.length > LINE_CHARS ? `${line.slice(0, LINE_CHARS)}…` : line));
    task.output = [...task.output, ...lines].slice(-OUTPUT_LINES);
  }

  private end(task: LocalJudgeTask, problem?: LocalJudgeProblem): void {
    if (task.phase !== 'running') return;
    task.phase = problem ? 'failed' : 'done';
    if (problem) task.problem = problem;
  }

  /**
   * onnx `setup`: pulls every file of the chosen build from the chosen source into the model folder, streaming each
   * file while updating `task.progress` for the progress bar. A file already on disk with the right size is skipped,
   * so a failed or partial run resumes instead of re-downloading, and the person can cancel from the UI. What each
   * file must be is then checked once, the way `start` checks it.
   */
  private async download(task: LocalJudgeTask, source?: { id: string; base?: string }): Promise<void> {
    const sourceId = source?.id ?? 'huggingface';
    const presetId = this.preset;
    const files = layaOnnxFiles(presetId);
    const build = layaOnnxPreset(presetId);
    task.source = sourceId;
    task.preset = presetId;
    this.cancelled = false;
    const fetched = await downloadModelFiles({
      task,
      files,
      folder: this.folder,
      urlFor: (file) => layaOnnxFileUrlFor(sourceId, file.name, source?.base, presetId),
      hooks: {
        cancelled: () => this.cancelled,
        setAbort: (controller) => {
          this.abort = controller;
        },
        say: (target, line) => this.say(target, line),
        end: (target, problem) => this.end(target, problem),
      },
      header: `Downloading the Laya model (${build.id}, ${build.downloadMb} MB) from ${sourceId}`,
    });
    if (!fetched) return; // cancelled, or a file that never arrived: the task is already closed
    this.say(task, 'Verifying the model files');
    const check = await this.deps.check(this.folder, false, presetId);
    if (check.missing.length || check.wrong.length) {
      this.say(task, `Verification failed: ${[...check.missing, ...check.wrong].join(', ')}`);
      this.end(task, 'wrong');
      return;
    }
    this.say(task, 'Model ready');
    this.end(task);
  }

  private async start(task: LocalJudgeTask): Promise<void> {
    try {
      if (await this.deps.health(this.url)) return this.end(task);
      this.say(task, 'Checking the model files');
      const check = await this.deps.check(this.folder, true, this.preset);
      if (check.missing.length) {
        this.say(task, `Missing: ${check.missing.join(', ')}`);
        return this.end(task, 'missing');
      }
      if (check.wrong.length) {
        this.say(task, `Not the tested version: ${check.wrong.join(', ')}`);
        return this.end(task, 'wrong');
      }
      const child = this.deps.fork({
        ...this.deps.env,
        MU_LOCAL_JUDGE_ONNX_DIR: this.folder,
        MU_LOCAL_JUDGE_ONNX_PRESET: this.preset,
        MU_LOCAL_JUDGE_PORT: this.port,
      });
      this.child = child;
      child.stdout?.on('data', (chunk: Buffer) => this.say(task, chunk));
      child.stderr?.on('data', (chunk: Buffer) => this.say(task, chunk));
      child.on('message', (message) => {
        const { type, reason } = (message ?? {}) as { type?: unknown; reason?: unknown };
        if (type === 'ready') this.end(task);
        else if (type === 'failed') this.end(task, FAILURES[String(reason)] ?? 'stopped');
      });
      child.on('exit', () => {
        if (this.child === child) this.child = undefined;
        this.end(task, 'stopped');
      });
    } catch (error) {
      this.say(task, error instanceof Error ? error.message : String(error));
      this.end(task, 'stopped');
    }
  }

  private async stop(task: LocalJudgeTask): Promise<void> {
    const child = this.child;
    if (!child) return this.end(task, (await this.deps.health(this.url)) ? 'foreign' : undefined);
    child.on('exit', () => this.end(task));
    child.kill();
  }
}

/** Creates the folder if needed; `show` opens it in the file manager. */
export async function openFolder(folder: string, show: (path: string) => Promise<string>): Promise<void> {
  mkdirSync(folder, { recursive: true });
  const problem = await show(folder);
  if (problem) throw new KyrnError('unreadable', problem, { file: folder });
}
