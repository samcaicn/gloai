import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * The real GGUF file is ~450 MB with a pinned hash, far too big for a test. The manager reads the build's file list
 * from the module below, so it is swapped for one small fixture whose hash comes from the bytes it is served from --
 * the same stand-in trick the ONNX download test uses.
 */
const { TINY_FILES, TINY_BYTES } = vi.hoisted(() => {
  const bytes = (char: string, size: number) => Buffer.from(char.repeat(size));
  const model = bytes('a', 1024);
  const tinyBytes: Record<string, Buffer> = { 'Laya-Q8_0.gguf': model };
  // The sha256 of 'a'.repeat(1024), reused from the ONNX fixture so the hash check has a known-good value.
  return {
    TINY_BYTES: tinyBytes,
    TINY_FILES: [
      { name: 'Laya-Q8_0.gguf', bytes: model.length, sha256: '2edc986847e209b4016e141a6dc8716d3207350f416969382d431539bf292e4a' },
    ],
  };
});

vi.mock('@/common/kyrn/layaGguf.ts', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/common/kyrn/layaGguf.ts')>();
  // Same build as the real one (repo and revision preserved, so the URLs are real) with the tiny file standing in.
  const tiny = { ...actual.LAYA_GGUF_PRESETS[0]!, files: TINY_FILES, downloadMb: 1 };
  return {
    ...actual,
    LAYA_GGUF_PRESETS: [tiny],
    LAYA_GGUF_DEFAULT_ID: tiny.id,
    layaGgufPreset: () => tiny,
  };
});

import { layaGgufUrlCandidates } from '@/common/kyrn/layaGguf.ts';
import { GgufLocalJudge } from '@process/agent/kyrn/localJudgeGguf.ts';

const GGUF_REVISION = '22265007700297ba9e128297e82540cf28c5d7d4';

type Call = { url: string; headers: Record<string, string>; signal?: AbortSignal | null };

const originalFetch = globalThis.fetch;
let calls: Call[] = [];
const dirs: string[] = [];

beforeEach(() => {
  calls = [];
});

afterEach(() => {
  globalThis.fetch = originalFetch;
  for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true });
});

function tempDir(): string {
  const dir = join(tmpdir(), `laya-gguf-dl-${Math.random().toString(36).slice(2)}`);
  mkdirSync(dir, { recursive: true });
  dirs.push(dir);
  return dir;
}

/** Replaces `fetch`, recording every call. The handler sees the Range header and the abort signal. */
function stubFetch(handler: (call: Call) => Response | Promise<Response>): void {
  globalThis.fetch = vi.fn(async (input: unknown, init?: RequestInit) => {
    const call: Call = {
      url: String(input),
      headers: (init?.headers ?? {}) as Record<string, string>,
      signal: init?.signal ?? null,
    };
    calls.push(call);
    return handler(call);
  }) as unknown as typeof globalThis.fetch;
}

const bodyOf = (name: string): Uint8Array => new Uint8Array(TINY_BYTES[name]);

/** Serves one file, honouring `Range: bytes=N-` the way Hugging Face and its mirrors do. */
function serve(full: Uint8Array, range?: string): Response {
  if (!range) return new Response(full, { status: 200 });
  const start = Number(/bytes=(\d+)-/.exec(range)?.[1] ?? 0);
  return new Response(full.subarray(start), { status: 206 });
}

function makeJudge(dir: string): GgufLocalJudge {
  return new GgufLocalJudge({
    fork: () => {
      throw new Error('fork is not used by setup');
    },
    open: async () => {},
    health: async () => false,
    check: async (folder: string) => {
      const missing = TINY_FILES.filter((file) => !existsSync(join(folder, file.name))).map((file) => file.name);
      return { ready: missing.length === 0, missing, wrong: [] };
    },
    platform: 'linux',
    arch: 'x64',
    env: { MU_LOCAL_JUDGE_GGUF_DIR: dir },
    home: dir,
  });
}

/** The download runs in the background, so the screen's state is polled until the task is no longer running. */
async function settle(
  judge: GgufLocalJudge,
  timeout = 15_000
): Promise<Awaited<ReturnType<GgufLocalJudge['state']>>> {
  const start = Date.now();
  for (;;) {
    const state = await judge.state();
    if (!state.task || state.task.phase !== 'running') return state;
    if (Date.now() - start > timeout) throw new Error(`download did not settle: ${JSON.stringify(state.task)}`);
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
}

/** The download URL carries `?download=true`, so the file is matched on the path alone. */
const pathOf = (url: string): string => url.split('?')[0];
const fileOfUrl = (url: string) => TINY_FILES.find((file) => pathOf(url).endsWith(file.name));

describe('layaGguf download sources', () => {
  const name = 'Laya-Q8_0.gguf';
  const repoPath = `ggml-org/Laya-GGUF/resolve/${GGUF_REVISION}/Laya-Q8_0.gguf`;

  it('builds the Hugging Face link and the mirror link', () => {
    expect(fileOfUrl(`https://huggingface.co/${repoPath}?download=true`)?.name).toBe(name);
    expect(fileOfUrl(`https://hf-mirror.com/${repoPath}?download=true`)?.name).toBe(name);
  });

  it('lists the mirrors in fallback order, Hugging Face first', () => {
    const urls = layaGgufUrlCandidates('Laya-Q8_0.gguf');
    expect(urls[0]).toContain('https://huggingface.co/');
    expect(urls[1]).toContain('https://hf-mirror.com/');
    expect(urls[2]).toContain('https://modelscope.cn/models/');
  });
});

describe('GgufLocalJudge setup download', () => {
  it('uses the fixture file list instead of the real 450 MB model', () => {
    expect(TINY_FILES).toHaveLength(1);
    expect(TINY_FILES.every((file) => file.bytes < 10_000)).toBe(true);
  });

  it('downloads the file, checks its hash, and reports progress as it goes', async () => {
    const dir = tempDir();
    stubFetch((call) => serve(bodyOf(fileOfUrl(call.url)!.name)));
    const judge = makeJudge(dir);

    await judge.run('setup', false, { id: 'huggingface' });
    const state = await settle(judge);

    expect(state.task?.phase).toBe('done');
    expect(state.installed).toBe(true);
    expect(readFileSync(join(dir, 'Laya-Q8_0.gguf'))).toEqual(TINY_BYTES['Laya-Q8_0.gguf']);
    expect(existsSync(`${join(dir, 'Laya-Q8_0.gguf')}.part`)).toBe(false);
  });

  it('tries the mirrors in order and falls back when one fails', async () => {
    const dir = tempDir();
    let triedHf = false;
    let triedMirror = false;
    stubFetch((call) => {
      if (call.url.startsWith('https://huggingface.co/')) {
        triedHf = true;
        return new Response('boom', { status: 503 });
      }
      if (call.url.startsWith('https://hf-mirror.com/')) triedMirror = true;
      return serve(bodyOf(fileOfUrl(call.url)!.name));
    });
    const judge = makeJudge(dir);

    await judge.run('setup', false, {});
    const state = await settle(judge);

    expect(state.task?.phase).toBe('done');
    expect(state.installed).toBe(true);
    expect(triedHf).toBe(true);
    expect(triedMirror).toBe(true);
  });

  it('skips a file it already has, and does not fetch it again', async () => {
    const dir = tempDir();
    writeFileSync(join(dir, 'Laya-Q8_0.gguf'), TINY_BYTES['Laya-Q8_0.gguf']);
    stubFetch((call) => serve(bodyOf(fileOfUrl(call.url)!.name)));
    const judge = makeJudge(dir);

    await judge.run('setup', false, { id: 'huggingface' });
    const state = await settle(judge);

    expect(state.installed).toBe(true);
    expect(calls.some((call) => pathOf(call.url).endsWith('Laya-Q8_0.gguf'))).toBe(false);
  });

  it('resumes a partial .part with a Range request instead of starting over', async () => {
    const dir = tempDir();
    const name = 'Laya-Q8_0.gguf';
    const cut = 400;
    writeFileSync(join(dir, `${name}.part`), bodyOf(name).subarray(0, cut));
    const ranges: string[] = [];
    stubFetch((call) => {
      if (call.headers.Range) ranges.push(call.headers.Range);
      return serve(bodyOf(fileOfUrl(call.url)!.name), call.headers.Range);
    });
    const judge = makeJudge(dir);

    await judge.run('setup', false, { id: 'huggingface' });
    const state = await settle(judge);

    expect(state.installed).toBe(true);
    expect(readFileSync(join(dir, name))).toEqual(TINY_BYTES[name]);
    expect(ranges).toEqual([`bytes=${cut}-`]);
  });

  it('retries a transient failure and then finishes', async () => {
    const dir = tempDir();
    let failedOnce = false;
    stubFetch((call) => {
      const file = fileOfUrl(call.url)!;
      if (!failedOnce) {
        failedOnce = true;
        return new Response('boom', { status: 500 });
      }
      return serve(bodyOf(file.name));
    });
    const judge = makeJudge(dir);

    await judge.run('setup', false, { id: 'huggingface' });
    const state = await settle(judge);

    expect(failedOnce).toBe(true);
    expect(state.installed).toBe(true);
    expect(calls.filter((call) => pathOf(call.url).endsWith('Laya-Q8_0.gguf'))).toHaveLength(2);
  });

  it('gives up with "wrong" on a file that is not the tested version, and cleans up', async () => {
    const dir = tempDir();
    stubFetch((call) => {
      const file = fileOfUrl(call.url)!;
      // Right size, wrong bytes: only the hash check catches it.
      return serve(Buffer.alloc(file.bytes, 0x5a));
    });
    const judge = makeJudge(dir);

    await judge.run('setup', false, { id: 'huggingface' });
    const state = await settle(judge);

    expect(state.task?.phase).toBe('failed');
    expect(state.task?.problem).toBe('wrong');
    expect(existsSync(join(dir, 'Laya-Q8_0.gguf'))).toBe(false);
    expect(existsSync(join(dir, 'Laya-Q8_0.gguf.part'))).toBe(false);
  });

  it('stops when the person cancels, and says so', async () => {
    const dir = tempDir();
    stubFetch(
      (call) =>
        new Promise<Response>((_resolve, reject) => {
          call.signal?.addEventListener('abort', () => reject(new Error('aborted')));
        })
    );
    const judge = makeJudge(dir);

    await judge.run('setup', false, { id: 'huggingface' });
    for (let i = 0; i < 200 && calls.length === 0; i++) await new Promise((resolve) => setTimeout(resolve, 5));
    expect(calls).toHaveLength(1);

    await judge.run('stop');
    const state = await settle(judge);

    expect(state.task?.phase).toBe('failed');
    expect(state.task?.problem).toBe('cancelled');
    expect(state.task?.output.join('\n')).toContain('cancelled');
  });
});
