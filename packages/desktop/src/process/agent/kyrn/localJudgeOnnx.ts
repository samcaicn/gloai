import { createReadStream, createWriteStream, mkdirSync, renameSync, statSync, unlinkSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
import { KyrnError } from '../../../common/kyrn/errors';
import { LAYA_ONNX_FILES, LAYA_ONNX_FOLDER, layaOnnxFileUrlFor } from '../../../common/kyrn/layaOnnx';
import type {
  LocalJudgeAction,
  LocalJudgeProblem,
  LocalJudgeState,
  LocalJudgeSupport,
  LocalJudgeTask,
} from '../../../common/kyrn/localJudge';
import { checkBundle, type BundleCheck } from '../../services/localJudgeOnnx/bundle';
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
  check: (folder: string, hash: boolean) => Promise<BundleCheck>;
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

  constructor(deps: Pick<OnnxJudgeDeps, 'fork' | 'open'> & Partial<OnnxJudgeDeps>) {
    this.deps = {
      health,
      check: (folder, hash) => checkBundle(folder, { hash }),
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

  /** `MU_LOCAL_JUDGE_ONNX_DIR`, else `<mu home>/local-judge/laya-multilingual-onnx`. */
  get folder(): string {
    const { env, home } = this.deps;
    return env.MU_LOCAL_JUDGE_ONNX_DIR || join(muHome(home), ...LAYA_ONNX_FOLDER);
  }

  private support(): LocalJudgeSupport {
    const { platform, arch, env } = this.deps;
    return usesOnnxJudge(platform, arch, env) ? 'ok' : 'platform';
  }

  async state(): Promise<LocalJudgeState> {
    const [check, running] = await Promise.all([this.deps.check(this.folder, false), this.deps.health(this.url)]);
    return {
      support: this.support(),
      runtime: 'onnx',
      installed: check.ready,
      running,
      url: this.url,
      model: { folder: this.folder, missing: check.missing, wrong: check.wrong },
      ...(this.task ? { task: { ...this.task, output: [...this.task.output] } } : {}),
    };
  }

  /** `start` checks the model (hashing it the first time) and starts the judge; `consent` is coreml's and unused.
   *  `setup` downloads the model from the chosen source (onnx) so the first-run guide and the settings can offer
   *  one click instead of a manual `hf download`. */
  async run(action: LocalJudgeAction, _consent = false, source?: { id: string; base?: string }): Promise<LocalJudgeState> {
    if (!ACTIONS.has(action)) throw new KyrnError('invalid', `Unknown local judge action: ${String(action)}`);
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
    if (!(await this.deps.check(this.folder, false)).ready || (await this.deps.health(this.url))) return false;
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
   * onnx `setup`: pulls every `LAYA_ONNX_FILES` from the chosen source into the model folder, streaming each file
   * while updating `task.progress` for the progress bar, and SHA-256 checks each one before it is moved into place.
   * A file already on disk with the right size is skipped, so a failed or partial run resumes instead of re-downloading.
   * The pull is resilient: each file retries on a transient error and resumes from a partial `.part`, and the person
   * can cancel it from the UI (which aborts the in-flight request).
   */
  private async download(task: LocalJudgeTask, source?: { id: string; base?: string }): Promise<void> {
    const sourceId = source?.id ?? 'huggingface';
    task.source = sourceId;
    this.cancelled = false;
    this.say(task, `Downloading the Laya model from ${sourceId}`);
    mkdirSync(this.folder, { recursive: true });
    const total = LAYA_ONNX_FILES.length;
    for (let index = 0; index < total; index++) {
      if (this.cancelled) return;
      const file = LAYA_ONNX_FILES[index];
      const url = layaOnnxFileUrlFor(sourceId, file.name, source?.base);
      const dest = join(this.folder, file.name);
      if (this.fileIsComplete(dest, file)) {
        this.say(task, `Already have ${file.name}`);
        task.progress = { file: file.name, index, total, downloadedBytes: file.bytes, totalBytes: file.bytes, percent: 1 };
        continue;
      }
      this.say(task, `Downloading ${file.name} (${Math.round(file.bytes / 1_000_000)} MB)`);
      const part = `${dest}.part`;
      try {
        await this.downloadFile(task, index, total, file, url, part, dest);
      } catch (error) {
        this.say(task, error instanceof Error ? error.message : String(error));
        this.abort = undefined;
        this.end(task, 'wrong');
        return;
      }
    }
    this.say(task, 'Verifying the model files');
    const check = await this.deps.check(this.folder, false);
    if (check.missing.length || check.wrong.length) {
      this.say(task, `Verification failed: ${[...check.missing, ...check.wrong].join(', ')}`);
      this.abort = undefined;
      this.end(task, 'wrong');
      return;
    }
    this.say(task, 'Model ready');
    this.abort = undefined;
    this.end(task);
  }

  /** A destination file is already correct when it exists with the exact tested byte size (hash is verified on download). */
  private fileIsComplete(dest: string, file: LayaOnnxFile): boolean {
    try {
      return statSync(dest).size === file.bytes;
    } catch {
      return false;
    }
  }

  /**
   * Streams one file with resume + retry: hashes it (including any partial from a previous run), updates
   * `task.progress`, and renames it into place on a good hash. Honors `this.abort` so the UI can cancel, and aborts
   * itself if no byte arrives for 60s (a slow but moving stream resets the timer). A corrupt partial is dropped on a
   * hash mismatch so the next try downloads fresh instead of appending to bad bytes.
   */
  private async downloadFile(
    task: LocalJudgeTask,
    index: number,
    total: number,
    file: LayaOnnxFile,
    url: string,
    part: string,
    dest: string,
  ): Promise<void> {
    const expected = file.bytes;
    mkdirSync(dirname(part), { recursive: true });
    // Resume from a partial `.part` left by an interrupted run; a full-size-but-not-finalized part is corrupt, redo.
    let offset = 0;
    try {
      const size = statSync(part).size;
      if (size < expected) offset = size;
      else unlinkSync(part);
    } catch {
      /* no partial yet */
    }
    const retries = 3;
    const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));
    for (let attempt = 1; attempt <= retries; attempt++) {
      const controller = new AbortController();
      this.abort = controller;
      let stall = setTimeout(() => controller.abort(new Error('Download stalled (no data for 60s)')), 60_000);
      const resetStall = () => {
        clearTimeout(stall);
        stall = setTimeout(() => controller.abort(new Error('Download stalled (no data for 60s)')), 60_000);
      };
      try {
        const headers: Record<string, string> = {};
        if (offset > 0) headers.Range = `bytes=${offset}-`;
        const response = await fetch(url, { headers, signal: controller.signal });
        if (offset > 0) {
          if (response.status === 206) {
            /* partial content: resume from `offset` */
          } else if (response.status === 200) {
            offset = 0; // server ignored the range; download fresh from the start
          } else {
            throw new Error(`HTTP ${response.status} ${response.statusText} for ${file.name}`);
          }
        } else if (!response.ok) {
          throw new Error(`HTTP ${response.status} ${response.statusText} for ${file.name}`);
        }
        if (!response.body) throw new Error(`No response body for ${file.name}`);
        const hash = createHash('sha256');
        if (offset > 0) {
          // The bytes already on disk are part of the final file: include them in the hash.
          for await (const chunk of createReadStream(part)) hash.update(chunk);
        }
        const stream = createWriteStream(part, { flags: offset > 0 ? 'a' : 'w' });
        const reader = response.body.getReader();
        let received = 0;
        try {
          for (;;) {
            const { done, value } = await reader.read();
            if (done) break;
            if (!value) continue;
            hash.update(value);
            await new Promise<void>((resolve, reject) => stream.write(value, (error) => (error ? reject(error) : resolve())));
            received += value.length;
            resetStall();
            const downloaded = offset + received;
            task.progress = { file: file.name, index, total, downloadedBytes: downloaded, totalBytes: expected, percent: expected ? downloaded / expected : 0 };
          }
        } finally {
          await new Promise<void>((resolve, reject) => stream.end((error) => (error ? reject(error) : resolve())));
        }
        const digest = hash.digest('hex');
        if (digest !== file.sha256) {
          throw new Error(`Hash mismatch for ${file.name}: expected ${file.sha256}, got ${digest}`);
        }
        clearTimeout(stall);
        this.abort = undefined;
        renameSync(part, dest);
        return;
      } catch (error) {
        clearTimeout(stall);
        this.abort = undefined;
        if (this.cancelled) throw error; // user cancelled: do not retry
        const message = error instanceof Error ? error.message : String(error);
        // A corrupt partial can't be healed by appending: drop it so the next try downloads fresh.
        if (/Hash mismatch/.test(message)) {
          try {
            unlinkSync(part);
          } catch {
            /* ignore */
          }
          offset = 0;
        }
        if (attempt < retries) {
          this.say(task, `Retry ${attempt}/${retries - 1} for ${file.name}: ${message}`);
          await sleep(1000 * 2 ** (attempt - 1));
          continue;
        }
        throw error;
      }
    }
  }


  private async start(task: LocalJudgeTask): Promise<void> {
    try {
      if (await this.deps.health(this.url)) return this.end(task);
      this.say(task, 'Checking the model files');
      const check = await this.deps.check(this.folder, true);
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
