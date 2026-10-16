/**
 * @license
 * Copyright 2026 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Repair a packaged app's managed Node.js runtime by downloading it.
 *
 * A packaged app ships the runtime in `resources/bundled-aioncore/<platform>-<arch>/managed-resources/` and the
 * backend copies it into the data folder on first start. When that copy cannot be used -- most often a security
 * program quarantining the unsigned `node.exe` next to the freshly installed app -- the backend reports the runtime
 * as a broken bundled resource and the app shows the "incomplete installation" dialog with no way out but
 * reinstalling.
 *
 * This is the way out: it fetches the very archive an unpackaged app would (same URL, same size, see
 * nodeRuntimeConsent.ts) into the data folder instead, which a security program has usually no reason to touch.
 * Only ever called after the person agreed to the download, and it re-checks what is already in place first.
 */

import { execFile } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { mkdirSync, existsSync, rmSync } from 'node:fs';
import path from 'node:path';
import yauzl from 'yauzl';
import { hasManagedNodeRuntime, managedNodeRuntime, type ManagedNodeRuntime } from './nodeRuntimeConsent';

export type NodeRuntimeRepairResult = { ok: true; dir: string } | { ok: false; error: string };

export type NodeRuntimeRepairOptions = {
  dataDir: string;
  platform: NodeJS.Platform;
  arch: string;
  /** Where the archive is downloaded to before it is unpacked (the app's cache folder). */
  cacheDir: string;
  fetchImpl?: typeof fetch;
  /** Bytes received / expected, for a progress line while the repair runs. */
  onProgress?: (received: number, total: number) => void;
};

/** Streams the archive to disk, refusing a body that is not the tested size. */
async function downloadArchive(
  runtime: ManagedNodeRuntime,
  archivePath: string,
  fetchImpl: typeof fetch,
  onProgress?: (received: number, total: number) => void
): Promise<void> {
  const response = await fetchImpl(runtime.url);
  if (!response.ok || !response.body) {
    throw new Error(`HTTP ${response.status} ${response.statusText} for the Node.js archive`);
  }
  const expected = Number(response.headers.get('content-length') ?? runtime.bytes);
  const stream = createWriteStream(archivePath);
  const reader = response.body.getReader();
  let received = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      if (!value) continue;
      await new Promise<void>((resolve, reject) =>
        stream.write(value, (error: Error | null) => (error ? reject(error) : resolve()))
      );
      received += value.length;
      onProgress?.(received, expected);
    }
  } finally {
    await new Promise<void>((resolve, reject) =>
      stream.end((error: Error | null) => (error ? reject(error) : resolve()))
    );
  }
  // A short body is a truncated or blocked download; never unpack it into place.
  if (received !== runtime.bytes) {
    throw new Error(`The Node.js archive is ${received} bytes, expected ${runtime.bytes}`);
  }
}

/** Unpacks one zip entry to `target`, refusing anything that would escape the runtime folder. */
function entryTarget(entry: yauzl.Entry, root: string): string | undefined {
  const target = path.resolve(root, entry.fileName);
  const relative = path.relative(root, target);
  if (relative.startsWith('..') || path.isAbsolute(relative)) return undefined;
  return target;
}

function extractEntry(zip: yauzl.ZipFile, entry: yauzl.Entry, target: string): Promise<void> {
  return new Promise((resolve, reject) => {
    zip.openReadStream(entry, (error, readStream) => {
      if (error || !readStream) return reject(error ?? new Error(`Cannot read ${entry.fileName} from the archive`));
      const writeStream = createWriteStream(target);
      readStream.pipe(writeStream);
      writeStream.on('error', reject);
      writeStream.on('finish', () => resolve());
    });
  });
}

/** Unpacks the archive under `<dataDir>/runtime/node/`, where the backend looks for the runtime. */
async function unpackArchive(archivePath: string, runtimeDir: string): Promise<void> {
  const nodeRoot = path.dirname(runtimeDir);
  mkdirSync(nodeRoot, { recursive: true });
  await new Promise<void>((resolve, reject) => {
    yauzl.open(archivePath, { lazyEntries: true }, (error, zip) => {
      if (error || !zip) return reject(error ?? new Error('Cannot open the Node.js archive'));
      zip.readEntry();
      zip.on('entry', (entry) => {
        const target = entryTarget(entry, nodeRoot);
        if (!target) {
          zip.readEntry();
          return;
        }
        if (entry.fileName.endsWith('/')) {
          mkdirSync(target, { recursive: true });
          zip.readEntry();
          return;
        }
        extractEntry(zip, entry, target)
          .then(() => zip.readEntry())
          .catch(reject);
      });
      zip.on('end', () => resolve());
      zip.on('error', reject);
    });
  });
}

/** Runs the unpacked `node` and checks it answers with the version the backend manages. */
function verifyRuntime(runtime: ManagedNodeRuntime, platform: NodeJS.Platform): Promise<void> {
  const binary = path.join(runtime.dir, platform === 'win32' ? 'node.exe' : path.join('bin', 'node'));
  if (!existsSync(binary)) throw new Error(`The unpacked runtime has no ${path.basename(binary)}`);
  return new Promise((resolve, reject) => {
    execFile(binary, ['--version'], { timeout: 30_000 }, (error, stdout) => {
      const version = stdout.trim();
      if (error) return reject(new Error(`The unpacked Node.js does not run: ${error.message}`));
      if (version !== `v${runtime.version}`) {
        return reject(new Error(`The unpacked Node.js is ${version}, expected v${runtime.version}`));
      }
      resolve();
    });
  });
}

/**
 * Fetches the managed Node.js runtime into the data folder, unpacked and verified. Safe to call when it is already
 * there: it then only verifies and reports success. The archive lands in `cacheDir` first and is removed after --
 * on the success path and on every failure, so a refused download leaves nothing behind.
 */
export async function repairManagedNodeRuntime(options: NodeRuntimeRepairOptions): Promise<NodeRuntimeRepairResult> {
  const runtime = managedNodeRuntime(options.dataDir, options.platform, options.arch);
  if (!runtime)
    return { ok: false, error: `This machine (${options.platform}-${options.arch}) has no managed Node.js runtime` };
  mkdirSync(options.cacheDir, { recursive: true });
  const archivePath = path.join(options.cacheDir, `node-repair-${process.pid}.zip`);
  try {
    if (hasManagedNodeRuntime(runtime, options.platform, (file) => existsSync(file))) {
      await verifyRuntime(runtime, options.platform);
      return { ok: true, dir: runtime.dir };
    }
    const fetchImpl = options.fetchImpl ?? fetch;
    await downloadArchive(runtime, archivePath, fetchImpl, options.onProgress);
    // A half-unpacked runtime is worse than none: start from a clean folder.
    rmSync(runtime.dir, { recursive: true, force: true });
    await unpackArchive(archivePath, runtime.dir);
    await verifyRuntime(runtime, options.platform);
    return { ok: true, dir: runtime.dir };
  } catch (error) {
    return { ok: false, error: error instanceof Error ? error.message : String(error) };
  } finally {
    rmSync(archivePath, { force: true });
  }
}
