import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * The real model files are ~680 MB with pinned hashes, far too big for a test. The download reads the list from the
 * module below, so it is swapped for two small fixtures whose hashes come from the bytes they are served from.
 */
const { TINY_FILES, TINY_BYTES } = vi.hoisted(() => {
  const bytes = (char: string, size: number) => Buffer.from(char.repeat(size));
  const model = bytes('a', 1024);
  const config = bytes('b', 64);
  const tinyBytes: Record<string, Buffer> = { 'model.onnx': model, 'onnx_config.json': config };
  return {
    TINY_BYTES: tinyBytes,
    TINY_FILES: [
      { name: 'model.onnx', bytes: model.length, sha256: '2edc986847e209b4016e141a6dc8716d3207350f416969382d431539bf292e4a' },
      { name: 'onnx_config.json', bytes: config.length, sha256: 'a0fab1377f49a759b57f63318262ebe89fabfc990e8e93ceac2984561482b9d4' },
    ],
  };
});

vi.mock('@/common/kyrn/layaOnnx.ts', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/common/kyrn/layaOnnx.ts')>();
  // The same build as the real one -- same repository and revision, so the URLs are the real ones -- with the tiny
  // files standing in for the 680 MB model, which is what the download and the check read.
  const tiny = { ...actual.LAYA_ONNX_PRESETS[0]!, files: TINY_FILES, downloadMb: 1 };
  return {
    ...actual,
    LAYA_ONNX_FILES: TINY_FILES,
    LAYA_ONNX_DEFAULT_ID: tiny.id,
    layaOnnxFiles: () => TINY_FILES,
    layaOnnxPreset: () => tiny,
  };
});

import { LAYA_ONNX_FILES, LAYA_ONNX_REVISION, LAYA_ONNX_SOURCES, layaOnnxFileUrlFor, layaOnnxUrlCandidates } from '@/common/kyrn/layaOnnx.ts';
import { OnnxLocalJudge } from '@process/agent/kyrn/localJudgeOnnx.ts';

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
  const dir = join(tmpdir(), `laya-dl-${Math.random().toString(36).slice(2)}`);
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

function makeJudge(dir: string): OnnxLocalJudge {
  return new OnnxLocalJudge({
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
    env: { MU_LOCAL_JUDGE_ONNX_DIR: dir },
    home: dir,
  });
}

/** The download runs in the background, so the screen's state is polled until the task is no longer running. */
async function settle(judge: OnnxLocalJudge, timeout = 15_000): Promise<Awaited<ReturnType<OnnxLocalJudge['state']>>> {
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

describe('layaOnnx download sources', () => {
  const file = 'model.onnx';
  const repoPath = `mizchi/laya-multilingual-onnx/resolve/${LAYA_ONNX_REVISION}/model.onnx`;

  it('builds the Hugging Face link, the mirror link and the ModelScope link', () => {
    expect(layaOnnxFileUrlFor('huggingface', file)).toBe(`https://huggingface.co/${repoPath}?download=true`);
    expect(layaOnnxFileUrlFor('hf-mirror', file)).toBe(`https://hf-mirror.com/${repoPath}?download=true`);
    expect(layaOnnxFileUrlFor('modelscope', file)).toBe(`https://modelscope.cn/models/${repoPath}`);
  });

  it('builds a custom source from the base the person types', () => {
    expect(layaOnnxFileUrlFor('custom', file, 'https://mirror.example/')).toBe(
      `https://mirror.example/${repoPath}?download=true`
    );
  });

  it('falls back to Hugging Face for an unknown source', () => {
    expect(layaOnnxFileUrlFor('nope', file)).toBe(layaOnnxFileUrlFor('huggingface', file));
  });

  it('offers every source with an id and a label, and only the custom one needs a base', () => {
    expect(LAYA_ONNX_SOURCES.map((source) => source.id)).toEqual(['huggingface', 'hf-mirror', 'modelscope', 'custom']);
    expect(LAYA_ONNX_SOURCES.every((source) => source.labelKey.startsWith('mu.judges.laya.source'))).toBe(true);
    expect(LAYA_ONNX_SOURCES.filter((source) => source.needsBase).map((source) => source.id)).toEqual(['custom']);
  });

  it('lists the mirrors in fallback order, Hugging Face first', () => {
    const urls = layaOnnxUrlCandidates('model.onnx');
    expect(urls[0]).toContain('https://huggingface.co/');
    expect(urls[1]).toContain('https://hf-mirror.com/');
    expect(urls[2]).toContain('https://modelscope.cn/models/');
  });

  it('appends a custom mirror when a base is given', () => {
    const urls = layaOnnxUrlCandidates('model.onnx', undefined, 'https://mirror.example/');
    expect(urls[3]).toBe(
      'https://mirror.example/mizchi/laya-multilingual-onnx/resolve/d9d003d543e63d6d3375c21d44624136bd1e0bad/model.onnx?download=true'
    );
  });
});

describe('OnnxLocalJudge setup download', () => {
  it('uses the fixture file list instead of the real 680 MB model', () => {
    expect(LAYA_ONNX_FILES).toHaveLength(2);
    expect(LAYA_ONNX_FILES.every((file) => file.bytes < 10_000)).toBe(true);
  });

  it('downloads every file, checks its hash, and reports progress as it goes', async () => {
    const dir = tempDir();
    stubFetch((call) => serve(bodyOf(fileOfUrl(call.url)!.name)));
    const judge = makeJudge(dir);

    await judge.run('setup', false, { id: 'huggingface' });
    const state = await settle(judge);

    expect(state.task?.phase).toBe('done');
    expect(state.installed).toBe(true);
    for (const file of TINY_FILES) {
      expect(readFileSync(join(dir, file.name))).toEqual(TINY_BYTES[file.name]);
      // The .part file is renamed into place, not left behind.
      expect(existsSync(`${join(dir, file.name)}.part`)).toBe(false);
    }
    expect(state.task?.progress).toMatchObject({ file: 'onnx_config.json', index: 1, total: 2, percent: 1 });
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
    writeFileSync(join(dir, 'model.onnx'), TINY_BYTES['model.onnx']);
    stubFetch((call) => serve(bodyOf(fileOfUrl(call.url)!.name)));
    const judge = makeJudge(dir);

    await judge.run('setup', false, { id: 'huggingface' });
    const state = await settle(judge);

    expect(state.installed).toBe(true);
    expect(calls.some((call) => pathOf(call.url).endsWith('model.onnx'))).toBe(false);
  });

  it('resumes a partial .part with a Range request instead of starting over', async () => {
    const dir = tempDir();
    const name = 'model.onnx';
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
    // Resumed from the bytes already on disk, and the hash still covers the whole file.
    expect(readFileSync(join(dir, name))).toEqual(TINY_BYTES[name]);
    expect(ranges).toEqual([`bytes=${cut}-`]);
  });

  it('retries a transient failure and then finishes', async () => {
    const dir = tempDir();
    let failedOnce = false;
    stubFetch((call) => {
      const file = fileOfUrl(call.url)!;
      if (!failedOnce && file.name === 'model.onnx') {
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
    expect(calls.filter((call) => pathOf(call.url).endsWith('model.onnx'))).toHaveLength(2);
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
    expect(existsSync(join(dir, 'model.onnx'))).toBe(false);
    expect(existsSync(join(dir, 'model.onnx.part'))).toBe(false);
  });

  it('stops when the person cancels, and says so', async () => {
    const dir = tempDir();
    // The response never arrives by itself: only the abort ends it.
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
