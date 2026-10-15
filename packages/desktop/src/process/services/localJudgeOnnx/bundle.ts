import { createHash } from 'node:crypto';
import { createReadStream, promises as fs } from 'node:fs';
import { basename, join } from 'node:path';
import { layaOnnxPreset } from '../../../common/kyrn/layaOnnx.ts';
import type { DownloadFile } from '../../agent/kyrn/modelDownload.ts';

/** Where each model file was found, and which are missing or not the tested version. */
export type BundleCheck = {
  /** Repository path → file on disk. A file may sit at its repository path or, as a browser saves it, flat. */
  paths: Record<string, string>;
  missing: string[];
  /** Present, but with another size or hash than the pinned revision. */
  wrong: string[];
  /** Every file is there and matches. */
  ready: boolean;
};

/** What was hashed already: a 650 MB file is hashed once, not on every look at the settings. */
type Verified = { revision: string; files: Record<string, { bytes: number; mtimeMs: number; sha256: string }> };
const VERIFIED = '.mu-verified.json';

export type BundleFs = {
  stat(path: string): Promise<{ size: number; mtimeMs: number; isFile(): boolean } | undefined>;
  readText(path: string): Promise<string | undefined>;
  writeText(path: string, text: string): Promise<void>;
  sha256(path: string): Promise<string>;
};

export const nodeBundleFs: BundleFs = {
  stat: (path) => fs.stat(path).catch((): undefined => undefined),
  readText: (path) => fs.readFile(path, 'utf8').catch((): undefined => undefined),
  writeText: (path, text) => fs.writeFile(path, text, 'utf8'),
  sha256: (path) =>
    new Promise((resolve, reject) => {
      const hash = createHash('sha256');
      createReadStream(path)
        .on('data', (chunk) => hash.update(chunk))
        .on('error', reject)
        .on('end', () => resolve(hash.digest('hex')));
    }),
};

async function locate(dir: string, file: DownloadFile, io: BundleFs) {
  for (const path of [join(dir, ...file.name.split('/')), join(dir, basename(file.name))]) {
    const stat = await io.stat(path);
    if (stat?.isFile()) return { path, stat };
  }
  return undefined;
}

/**
 * Looks for the model files in `dir`. Sizes are always compared; hashes only when `hash` is set (the first time a file
 * is seen at this size and date, it takes a few seconds), and a hash that matched is remembered in the folder. A file
 * whose build pins no hash is trusted once its size matches: there is nothing here to compare its bytes against.
 *
 * Every backend's files go through here -- the ONNX builds of Laya and the GGUF ones are different files, checked the
 * same way -- and `revision` only says in the memo which build's hashes it holds.
 */
export async function checkFiles(
  dir: string,
  files: readonly DownloadFile[],
  options: { hash?: boolean; io?: BundleFs; revision?: string } = {}
): Promise<BundleCheck> {
  const io = options.io ?? nodeBundleFs;
  const memo = join(dir, VERIFIED);
  let verified: Verified = { revision: options.revision ?? '', files: {} };
  try {
    const read = JSON.parse((await io.readText(memo)) ?? '') as Verified;
    if (read?.files && typeof read.files === 'object') verified = { ...read, revision: options.revision ?? read.revision };
  } catch {
    // No memo yet, or an unreadable one: hash again.
  }
  const check: BundleCheck = { paths: {}, missing: [], wrong: [], ready: false };
  let learned = false;
  let confirmed = 0;
  for (const file of files) {
    const found = await locate(dir, file, io);
    if (!found) {
      check.missing.push(file.name);
      continue;
    }
    check.paths[file.name] = found.path;
    if (found.stat.size !== file.bytes) {
      check.wrong.push(file.name);
      continue;
    }
    const seen = verified.files[file.name];
    let sha256: string | undefined;
    if (seen && seen.bytes === found.stat.size && seen.mtimeMs === found.stat.mtimeMs) sha256 = seen.sha256;
    else if (options.hash) {
      sha256 = await io.sha256(found.path);
      verified.files[file.name] = { bytes: found.stat.size, mtimeMs: found.stat.mtimeMs, sha256 };
      learned = true;
    }
    // Not hashed yet (a quick look): neither wrong nor confirmed.
    if (sha256 === undefined) continue;
    if (file.sha256 === undefined || sha256 === file.sha256) confirmed++;
    else check.wrong.push(file.name);
  }
  if (learned) await io.writeText(memo, JSON.stringify(verified, null, 2)).catch((): void => undefined);
  check.ready = confirmed === files.length;
  return check;
}

/**
 * Looks for one build of the Laya ONNX model in `dir`. `preset` names which build: the 4-bit builds are different
 * files, not a smaller copy of the same ones.
 */
export async function checkBundle(
  dir: string,
  options: { hash?: boolean; io?: BundleFs; preset?: string } = {}
): Promise<BundleCheck> {
  const preset = layaOnnxPreset(options.preset);
  return checkFiles(dir, preset.files, { ...options, revision: preset.revision });
}
