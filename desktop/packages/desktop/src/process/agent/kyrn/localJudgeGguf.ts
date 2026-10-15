/**
 * Laya as a GGUF model run by llama.cpp, where Core ML and ONNX are not the right answer: a machine that already runs
 * llama.cpp for its other local models can run the judge the same way, and reach a GPU the app's ONNX runtime has no
 * provider for.
 *
 * The app does not ship llama.cpp. It starts the `llama-server` it is pointed at (`MU_LLAMA_SERVER_BIN`, or one on the
 * PATH) with the model the person chose, and the judge process beside it serves the same contract the ONNX judge
 * serves on the same address, so nothing above this layer knows which one answers.
 *
 * The same `state()` / `run()` / `autoStart()` as the Core ML and ONNX judges: the settings do not care which runtime
 * this machine has.
 */
import { existsSync, statSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
import { KyrnError } from '../../../common/kyrn/errors';
import { LAYA_GGUF_DEFAULT_ID, layaGgufFileUrl, layaGgufPreset } from '../../../common/kyrn/layaGguf';
import type {
  LocalJudgeAction,
  LocalJudgeProblem,
  LocalJudgeRuntime,
  LocalJudgeState,
  LocalJudgeSupport,
  LocalJudgeTask,
} from '../../../common/kyrn/localJudge';
import { checkFiles, type BundleCheck } from '../../services/localJudgeOnnx/bundle.ts';
import { readOptional, writeJson } from './config/files.ts';
import { downloadModelFiles } from './modelDownload.ts';
import { configPath, muHome } from './naming.ts';
import { wantsLocalJudge } from './localJudgeOnnx.ts';
import type { JudgeChild } from './localJudgeOnnx.ts';

/** Where the choice of runtime and build is remembered: `~/.mu/local-judge.json`. */
export function choicePath(home: string = homedir()): string {
  return join(muHome(home), 'local-judge.json');
}

/** What the person chose last, and what the environment overrides it with. */
export type JudgeChoice = {
  /** Which runtime this machine runs the judge with. */
  runtime?: LocalJudgeRuntime;
  /** Which build of the model (`LAYA_ONNX_PRESETS` / `LAYA_GGUF_PRESETS`). */
  preset?: string;
  /** A GGUF file the person already has, for the `custom` build. */
  file?: string;
};

/** Reads the remembered choice; a file that is not the expected shape is no choice at all. */
export function readChoice(home: string = homedir()): JudgeChoice {
  try {
    const parsed = JSON.parse(readOptional(choicePath(home)) || '{}') as JudgeChoice;
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
}

/** Remembers the choice, so the next app run and the settings panel agree. */
export function writeChoice(choice: JudgeChoice, home: string = homedir()): void {
  writeJson(choicePath(home), choice);
}

/** The judge process as the manager needs it: an Electron utility process, or a stand-in in tests. */
export type GgufJudgeDeps = {
  /** Starts `localJudgeGguf.js` (see services/localJudgeGguf/entry.ts) with this environment. */
  fork: (env: NodeJS.ProcessEnv) => JudgeChild;
  health: (url: string) => Promise<boolean>;
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
  config: 'runtime',
};

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
 * The `llama-server` this machine runs the judge with. A path in the environment is used as it is; anything else is a
 * name looked up on the PATH, which the judge process does when it spawns it.
 */
export function llamaServerBin(env: NodeJS.ProcessEnv): string {
  return env.MU_LLAMA_SERVER_BIN || 'llama-server';
}

/** Whether that binary is there to be started: a named path must exist, a bare name is the PATH's business. */
export function llamaServerPresent(env: NodeJS.ProcessEnv): boolean {
  const bin = llamaServerBin(env);
  if (!/[\\/]/.test(bin)) return true; // a name on the PATH: the child finds out when it spawns it
  return existsSync(bin);
}

/**
 * What one build's check looks at: the build's own files, or the file the person named for `custom` -- which is read
 * where it is, not looked for in the model folder.
 */
function checkGguf(folder: string, hash: boolean, presetId: string, file?: string): Promise<BundleCheck> {
  const preset = layaGgufPreset(presetId);
  if (preset.files.length) return checkFiles(folder, preset.files, { hash, revision: preset.revision });
  if (!file) return Promise.resolve({ paths: {}, missing: ['MU_LOCAL_JUDGE_GGUF_FILE'], wrong: [], ready: false });
  try {
    const size = statSync(file).size;
    return Promise.resolve({
      paths: { [file]: file },
      missing: [],
      // A file the person pointed at is trusted by its size: its bytes were never hashed here to compare against.
      wrong: size > 0 ? [] : [file],
      ready: size > 0,
    });
  } catch {
    return Promise.resolve({ paths: {}, missing: [file], wrong: [], ready: false });
  }
}

/**
 * The judge as llama.cpp runs it: the app picks a GGUF build, downloads it with one click from one of the same mirrors
 * the ONNX builds use (or the person points it at a file they already have), checks it, and starts `llama-server`
 * beside the judge process.
 */
export class GgufLocalJudge {
  private deps: GgufJudgeDeps;
  private task?: LocalJudgeTask;
  private next = 0;
  private child?: JudgeChild;
  /** Aborts the in-flight `setup` download when the person cancels it from the UI. */
  private abort?: AbortController;
  /** Set when the person cancels a `setup` download, so a transient error is not retried and the task is not closed twice. */
  private cancelled = false;
  /** The build the person picked last, if any. */
  private chosen?: string;
  /** A GGUF file the person already has, for the `custom` build. */
  private chosenFile?: string;

  constructor(deps: Pick<GgufJudgeDeps, 'fork' | 'open'> & Partial<GgufJudgeDeps>) {
    const home = deps.home ?? homedir();
    const env = deps.env ?? process.env;
    this.deps = {
      health,
      check: (folder, hash, preset) => checkGguf(folder, hash, preset ?? this.preset, this.customFile),
      platform: process.platform,
      arch: process.arch,
      env,
      home,
      ...deps,
    };
    // What this machine ran last, so a restart offers the same build; the environment still wins.
    const choice = readChoice(this.deps.home);
    this.chosen = choice.preset;
    this.chosenFile = choice.file;
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
    return this.deps.env.MU_LOCAL_JUDGE_GGUF_PRESET || this.chosen || LAYA_GGUF_DEFAULT_ID;
  }

  /** `MU_LOCAL_JUDGE_GGUF_DIR`, else `<mu home>/local-judge/<build folder>`. */
  get folder(): string {
    const { env, home } = this.deps;
    if (env.MU_LOCAL_JUDGE_GGUF_DIR) return env.MU_LOCAL_JUDGE_GGUF_DIR;
    const file = this.customFile;
    if (file) return dirname(file);
    return join(muHome(home), ...layaGgufPreset(this.preset).folder);
  }

  /** The GGUF file the person already has, for the `custom` build; the environment's own naming still wins. */
  get customFile(): string | undefined {
    return this.deps.env.MU_LOCAL_JUDGE_GGUF_FILE || this.chosenFile;
  }

  /** The model file the judge process is started with: the one the person named, else the build's. */
  get modelFile(): string {
    const { env } = this.deps;
    return env.MU_LOCAL_JUDGE_GGUF_FILE || this.customFile || join(this.folder, layaGgufPreset(this.preset).model);
  }

  private support(): LocalJudgeSupport {
    // Any machine can run llama.cpp; what it cannot do is run it without the binary.
    return llamaServerPresent(this.deps.env) ? 'ok' : 'runtime';
  }

  async state(): Promise<LocalJudgeState> {
    const [check, running] = await Promise.all([
      this.deps.check(this.folder, false, this.preset),
      this.deps.health(this.url),
    ]);
    return {
      support: this.support(),
      runtime: 'gguf',
      installed: check.ready,
      running,
      url: this.url,
      model: { folder: this.folder, missing: check.missing, wrong: check.wrong, preset: this.preset },
      ...(this.task ? { task: { ...this.task, output: [...this.task.output] } } : {}),
    };
  }

  /**
   * `start` checks the model and starts `llama-server` behind the judge; `setup` downloads a build from the chosen
   * mirror; `locate` opens the model folder. Which build this machine runs is chosen in the settings and remembered
   * by the app (see the bridge), so it is not an action here.
   */
  async run(
    action: LocalJudgeAction,
    _consent = false,
    source?: { id: string; base?: string; preset?: string; file?: string }
  ): Promise<LocalJudgeState> {
    if (!ACTIONS.has(action)) throw new KyrnError('invalid', `Unknown local judge action: ${String(action)}`);
    // `setup` also carries the build to download: the panel lets the person pick one, and it decides the folder.
    if (source?.preset) this.chosen = source.preset;
    if (source?.file) this.chosenFile = source.file;
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
      if (this.support() !== 'ok')
        throw new KyrnError('invalid', 'This machine has no llama-server to run the local judge with');
      const build = layaGgufPreset(this.preset);
      if (build.custom) throw new KyrnError('invalid', 'A file you already have needs no download: start it');
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
    if (this.support() !== 'ok')
      throw new KyrnError('invalid', 'This machine has no llama-server to run the local judge with');
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

  /** Starts the judge when mu.json asks for a local judge and the model is there. Called at app start. */
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
   * gguf `setup`: pulls the chosen build's file from the chosen mirror into the model folder, streaming it while
   * updating `task.progress`, and checking its hash when the build pins one. A file already on disk at the right size
   * is skipped, so an interrupted run resumes instead of downloading again, and the person can cancel from the UI.
   */
  private async download(task: LocalJudgeTask, source?: { id: string; base?: string }): Promise<void> {
    const sourceId = source?.id ?? 'huggingface';
    const presetId = this.preset;
    const build = layaGgufPreset(presetId);
    const files = build.files;
    task.source = sourceId;
    task.preset = presetId;
    this.cancelled = false;
    const fetched = await downloadModelFiles({
      task,
      files,
      folder: this.folder,
      urlFor: (file) => layaGgufFileUrl(file.name, presetId, sourceId, source?.base),
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
    this.say(task, 'Verifying the model file');
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
      if (!llamaServerPresent(this.deps.env)) {
        this.say(task, `No llama-server at ${llamaServerBin(this.deps.env)}`);
        return this.end(task, 'runtime');
      }
      this.say(task, 'Checking the model file');
      const check = await this.deps.check(this.folder, true, this.preset);
      if (check.missing.length) {
        this.say(task, `Missing: ${check.missing.join(', ')}`);
        return this.end(task, 'missing');
      }
      if (check.wrong.length) {
        this.say(task, `Not the tested version: ${check.wrong.join(', ')}`);
        return this.end(task, 'wrong');
      }
      const model = this.modelFile;
      if (!existsSync(model)) {
        this.say(task, `Missing: ${model}`);
        return this.end(task, 'missing');
      }
      const child = this.deps.fork({
        ...this.deps.env,
        MU_LOCAL_JUDGE_GGUF_DIR: this.folder,
        MU_LOCAL_JUDGE_GGUF_PRESET: this.preset,
        MU_LOCAL_JUDGE_GGUF_FILE: model,
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
