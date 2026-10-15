/**
 * Pulling a model's files off the network, once, for every backend: the ONNX builds of Laya and the GGUF ones are
 * different files, but nobody should notice that in how they are fetched.
 *
 * Each file is streamed while the progress bar moves, hashed (when the build pins a hash) before it is moved into
 * place, and a file already on disk at the right size is skipped -- so an interrupted run resumes instead of starting
 * over. A file that fails is retried with backoff, a partial download resumes with a `Range` request, and the person
 * can cancel the whole thing from the UI.
 */
import { createReadStream, createWriteStream, mkdirSync, renameSync, statSync, unlinkSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname, join } from 'node:path';
import type { LocalJudgeProblem, LocalJudgeTask } from '../../../common/kyrn/localJudge.ts';

/** One file of a model: what it is called, how big it is, and what it must hash to (when that is known). */
export type DownloadFile = {
  /** The path inside the repository, and the name it is saved under. */
  name: string;
  bytes: number;
  /** Absent for a build whose bytes were never hashed here: it is then checked by size alone. */
  sha256?: string;
};

/** What the caller knows about the download and the screen: how to report on it, and how to stop it. */
export type DownloadHooks = {
  /** True once the person cancelled from the UI, so no further file is fetched and nothing is retried. */
  cancelled: () => boolean;
  /** Remembers the in-flight request so the UI can abort it. */
  setAbort: (controller: AbortController | undefined) => void;
  say: (task: LocalJudgeTask, line: string) => void;
  end: (task: LocalJudgeTask, problem?: LocalJudgeProblem) => void;
};

export type DownloadOptions = {
  task: LocalJudgeTask;
  files: readonly DownloadFile[];
  /** Where the files go. */
  folder: string;
  /**
   * The candidate URLs for one file, in the order to try them. The first mirror that serves the bytes wins; a mirror
   * that fails (or serves a corrupt partial) is skipped for the next one, so a person in a region where Hugging Face is
   * slow or blocked still gets the model from a mirror without picking one by hand.
   */
  urlCandidates: (file: DownloadFile) => readonly string[];
  hooks: DownloadHooks;
  /** The first line: what is being downloaded. */
  header: string;
};

/**
 * Fetches every file into `folder`, updating `task.progress` as it goes. Returns false when the download did not
 * finish -- cancelled or a file that never arrived -- with the task already closed; the caller then only has to stop.
 */
export async function downloadModelFiles(options: DownloadOptions): Promise<boolean> {
  const { task, files, folder, urlCandidates, hooks } = options;
  const total = files.length;
  hooks.say(task, options.header);
  mkdirSync(folder, { recursive: true });
  for (let index = 0; index < total; index++) {
    if (hooks.cancelled()) return false;
    const file = files[index];
    if (!file) continue;
    const dest = join(folder, file.name);
    if (fileIsComplete(dest, file)) {
      hooks.say(task, `Already have ${file.name}`);
      task.progress = { file: file.name, index, total, downloadedBytes: file.bytes, totalBytes: file.bytes, percent: 1 };
      continue;
    }
    hooks.say(task, `Downloading ${file.name} (${Math.round(file.bytes / 1_000_000)} MB)`);
    try {
      await downloadOne(task, index, total, file, urlCandidates(file), `${dest}.part`, dest, hooks);
    } catch (error) {
      hooks.say(task, error instanceof Error ? error.message : String(error));
      hooks.setAbort(undefined);
      hooks.end(task, 'wrong');
      return false;
    }
  }
  hooks.setAbort(undefined);
  return true;
}

/** A destination file is already correct when it exists with the exact tested byte size. */
function fileIsComplete(dest: string, file: DownloadFile): boolean {
  try {
    return statSync(dest).size === file.bytes;
  } catch {
    return false;
  }
}

/**
 * Streams one file from the first mirror that serves it, with resume + retry per mirror: hashes it (including any
 * partial from a previous run), updates `task.progress`, and renames it into place on a good hash. Tries every URL in
 * `urls` in order -- a mirror that errors or serves a corrupt partial is dropped and the next one tried from scratch,
 * so the download finishes even when the person's nearest mirror is down. Honors the abort controller the hooks hold
 * (the UI can cancel), and aborts itself if no byte arrives for 60s.
 */
async function downloadOne(
  task: LocalJudgeTask,
  index: number,
  total: number,
  file: DownloadFile,
  urls: readonly string[],
  part: string,
  dest: string,
  hooks: DownloadHooks
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

  // Try each mirror in turn; only give up once every one has failed all its retries.
  for (let mirror = 0; mirror < urls.length; mirror++) {
    const url = urls[mirror]!;
    let failed: unknown;
    for (let attempt = 1; attempt <= retries; attempt++) {
      const controller = new AbortController();
      hooks.setAbort(controller);
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
            await new Promise<void>((resolve, reject) =>
              stream.write(value, (error: Error | null) => (error ? reject(error) : resolve()))
            );
            received += value.length;
            resetStall();
            const downloaded = offset + received;
            task.progress = {
              file: file.name,
              index,
              total,
              downloadedBytes: downloaded,
              totalBytes: expected,
              percent: expected ? downloaded / expected : 0,
            };
          }
        } finally {
          await new Promise<void>((resolve, reject) =>
            stream.end((error: Error | null) => (error ? reject(error) : resolve()))
          );
        }
        const digest = hash.digest('hex');
        // A build that pins no hash is checked by size alone: the stream is only trusted to have delivered all of it.
        if (file.sha256 && digest !== file.sha256)
          throw new Error(`Hash mismatch for ${file.name}: expected ${file.sha256}, got ${digest}`);
        clearTimeout(stall);
        hooks.setAbort(undefined);
        renameSync(part, dest);
        return;
      } catch (error) {
        clearTimeout(stall);
        hooks.setAbort(undefined);
        if (hooks.cancelled()) throw error; // user cancelled: do not retry
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
          hooks.say(task, `Retry ${attempt}/${retries - 1} for ${file.name}: ${message}`);
          await sleep(1000 * 2 ** (attempt - 1));
          continue;
        }
        failed = error;
      }
    }
    // This mirror is exhausted; start the next one from a clean slate (its partial is not reusable).
    if (mirror < urls.length - 1) {
      try {
        unlinkSync(part);
      } catch {
        /* ignore */
      }
      offset = 0;
      continue;
    }
    throw failed;
  }
}
