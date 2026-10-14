import { join } from 'node:path';
import { describe, expect, it, vi } from 'vitest';
import {
  LAYA_ONNX_DOWNLOAD_MB,
  LAYA_ONNX_FILES,
  LAYA_ONNX_PAGE,
  LAYA_ONNX_REVISION,
  layaOnnxCommand,
  layaOnnxFileUrl,
} from '@/common/kyrn/layaOnnx.ts';
import { checkBundle, type BundleFs } from '@process/services/localJudgeOnnx/bundle.ts';

const DIR = '/models/laya';
type Entry = { size: number; mtimeMs: number; sha256: string };

/** An in-memory folder: path → size, date and the hash `sha256` returns. */
function folder(entries: Record<string, Entry>) {
  const written: Record<string, string> = {};
  const io: BundleFs = {
    stat: async (path) => {
      const entry = entries[path];
      return entry ? { size: entry.size, mtimeMs: entry.mtimeMs, isFile: () => true } : undefined;
    },
    readText: async (path) => written[path],
    writeText: async (path, text) => {
      written[path] = text;
    },
    sha256: vi.fn(async (path: string) => entries[path].sha256),
  };
  return { io, written };
}

/** Every pinned file at its repository path, with its real size and hash. */
function complete(): Record<string, Entry> {
  const entries: Record<string, Entry> = {};
  for (const file of LAYA_ONNX_FILES)
    entries[join(DIR, ...file.name.split('/'))] = { size: file.bytes, mtimeMs: 1, sha256: file.sha256 };
  return entries;
}

describe('the Laya ONNX files', () => {
  it('are pinned to one revision, with links and a command for the person to download them', () => {
    expect(LAYA_ONNX_FILES.map((file) => file.name)).toEqual([
      'model.onnx',
      'onnx_config.json',
      'rl_agent_config.json',
      'tokenizer/tokenizer.json',
      'tokenizer/tokenizer_config.json',
    ]);
    expect(LAYA_ONNX_DOWNLOAD_MB).toBe(681);
    expect(LAYA_ONNX_PAGE).toBe(`https://huggingface.co/mizchi/laya-multilingual-onnx/tree/${LAYA_ONNX_REVISION}`);
    expect(layaOnnxFileUrl('tokenizer/tokenizer.json')).toBe(
      `https://huggingface.co/mizchi/laya-multilingual-onnx/resolve/${LAYA_ONNX_REVISION}/tokenizer/tokenizer.json?download=true`
    );
    expect(layaOnnxCommand('C:\\Users\\a\\.mu\\local-judge\\laya-multilingual-onnx')).toBe(
      `hf download mizchi/laya-multilingual-onnx --revision ${LAYA_ONNX_REVISION} --local-dir "C:\\Users\\a\\.mu\\local-judge\\laya-multilingual-onnx"`
    );
  });
});

describe('checkBundle', () => {
  it('lists every file as missing in an empty folder', async () => {
    const { io } = folder({});
    expect(await checkBundle(DIR, { io })).toEqual({
      paths: {},
      missing: LAYA_ONNX_FILES.map((file) => file.name),
      wrong: [],
      ready: false,
    });
  });

  it('is not ready before the hashes were checked, and hashes each file once', async () => {
    const { io, written } = folder(complete());
    const quick = await checkBundle(DIR, { io });
    expect(quick).toMatchObject({ missing: [], wrong: [], ready: false });
    expect(io.sha256).not.toHaveBeenCalled();

    expect(await checkBundle(DIR, { io, hash: true })).toMatchObject({ missing: [], wrong: [], ready: true });
    expect(io.sha256).toHaveBeenCalledTimes(LAYA_ONNX_FILES.length);
    expect(JSON.parse(written[join(DIR, '.mu-verified.json')]).revision).toBe(LAYA_ONNX_REVISION);

    // The memo answers the next look, with or without hashing.
    expect((await checkBundle(DIR, { io })).ready).toBe(true);
    expect((await checkBundle(DIR, { io, hash: true })).ready).toBe(true);
    expect(io.sha256).toHaveBeenCalledTimes(LAYA_ONNX_FILES.length);
  });

  it('accepts the tokenizer files saved flat, as a browser download puts them', async () => {
    const entries = complete();
    for (const name of ['tokenizer.json', 'tokenizer_config.json']) {
      entries[join(DIR, name)] = entries[join(DIR, 'tokenizer', name)];
      delete entries[join(DIR, 'tokenizer', name)];
    }
    const { io } = folder(entries);
    const check = await checkBundle(DIR, { io, hash: true });
    expect(check.ready).toBe(true);
    expect(check.paths['tokenizer/tokenizer.json']).toBe(join(DIR, 'tokenizer.json'));
  });

  it('calls a file of another size wrong without hashing it', async () => {
    const entries = complete();
    entries[join(DIR, 'model.onnx')].size = 1000;
    const { io } = folder(entries);
    const check = await checkBundle(DIR, { io, hash: true });
    expect(check).toMatchObject({ wrong: ['model.onnx'], ready: false });
    expect(io.sha256).toHaveBeenCalledTimes(LAYA_ONNX_FILES.length - 1);
  });

  it('calls a file with the right size and another hash wrong', async () => {
    const entries = complete();
    entries[join(DIR, 'onnx_config.json')].sha256 = 'f'.repeat(64);
    const { io } = folder(entries);
    expect(await checkBundle(DIR, { io, hash: true })).toMatchObject({ wrong: ['onnx_config.json'], ready: false });
  });

  it('forgets a hash when the file is replaced, so a new download is checked again', async () => {
    const entries = complete();
    const { io } = folder(entries);
    expect((await checkBundle(DIR, { io, hash: true })).ready).toBe(true);
    entries[join(DIR, 'model.onnx')] = { ...entries[join(DIR, 'model.onnx')], mtimeMs: 2, sha256: 'f'.repeat(64) };
    expect((await checkBundle(DIR, { io })).ready).toBe(false);
    expect(await checkBundle(DIR, { io, hash: true })).toMatchObject({ wrong: ['model.onnx'], ready: false });
  });
});
