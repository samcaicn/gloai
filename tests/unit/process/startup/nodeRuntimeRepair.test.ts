/**
 * @license
 * Copyright 2026 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { repairManagedNodeRuntime } from '@/process/startup/nodeRuntimeRepair';

const dirs: string[] = [];

function tempDir(prefix: string): string {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), prefix));
  dirs.push(dir);
  return dir;
}

afterEach(() => {
  for (const dir of dirs.splice(0)) fs.rmSync(dir, { recursive: true, force: true });
});

/** A stubbed fetch: the body is served as one chunk, with the length the caller names. */
function stubFetch(body: Uint8Array, options: { status?: number; contentLength?: number } = {}) {
  const calls: string[] = [];
  const fetchImpl = (async (input: unknown) => {
    calls.push(String(input));
    return new Response(body, {
      status: options.status ?? 200,
      headers: options.contentLength === undefined ? {} : { 'content-length': String(options.contentLength) },
    });
  }) as unknown as typeof fetch;
  return { fetchImpl, calls };
}

describe('repairManagedNodeRuntime', () => {
  it('refuses a platform it has no runtime for, without fetching anything', async () => {
    const { fetchImpl, calls } = stubFetch(new Uint8Array());
    const result = await repairManagedNodeRuntime({
      dataDir: tempDir('node-repair-'),
      platform: 'freebsd',
      arch: 'x64',
      cacheDir: tempDir('node-repair-cache-'),
      fetchImpl,
    });
    expect(result).toEqual({ ok: false, error: 'This machine (freebsd-x64) has no managed Node.js runtime' });
    expect(calls).toEqual([]);
  });

  it('reports a failed download instead of unpacking a broken body', async () => {
    const { fetchImpl } = stubFetch(new Uint8Array([1, 2, 3]), { status: 503 });
    const dataDir = tempDir('node-repair-');
    const result = await repairManagedNodeRuntime({
      dataDir,
      platform: 'win32',
      arch: 'x64',
      cacheDir: tempDir('node-repair-cache-'),
      fetchImpl,
    });
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.error).toContain('HTTP 503');
    // Nothing was written where the backend looks for the runtime.
    expect(fs.existsSync(path.join(dataDir, 'runtime'))).toBe(false);
  });

  it('refuses an archive shorter than the tested size, and leaves no half-unpacked runtime', async () => {
    const short = new Uint8Array(1024);
    const { fetchImpl } = stubFetch(short, { contentLength: '999999' });
    const dataDir = tempDir('node-repair-');
    const cacheDir = tempDir('node-repair-cache-');
    const result = await repairManagedNodeRuntime({
      dataDir,
      platform: 'win32',
      arch: 'x64',
      cacheDir,
      fetchImpl,
    });
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.error).toContain('expected 36356854');
    // The truncated archive is not left in the cache either.
    expect(fs.readdirSync(cacheDir)).toEqual([]);
    expect(fs.existsSync(path.join(dataDir, 'runtime', 'node'))).toBe(false);
  });
});
