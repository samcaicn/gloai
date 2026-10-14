/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import type { GitHubReleaseAsset } from '@/common/update/updateTypes';
import { UpdateError } from '@/common/update/updateErrors';
import log from 'electron-log';
import * as fs from 'fs';
import * as path from 'path';
import { Readable, Transform } from 'stream';
import { pipeline } from 'stream/promises';
import type { ReadableStream as NodeReadableStream } from 'stream/web';
import { USER_AGENT } from './githubReleases';

/**
 * The hosts an installer may come from: GitHub's release pages and the storage its downloads redirect to. Every
 * redirect hop is checked against them.
 */
const ALLOWED_DOWNLOAD_HOSTS = new Set<string>([
  'github.com',
  'objects.githubusercontent.com',
  'github-releases.githubusercontent.com',
  'release-assets.githubusercontent.com',
]);
const MAX_REDIRECTS = 8;
const PROGRESS_INTERVAL_MS = 250;

const assertAllowedUrl = (rawUrl: string): void => {
  let parsed: URL;
  try {
    parsed = new URL(rawUrl);
  } catch {
    throw new UpdateError({ code: 'downloadFailed' }, `Invalid download URL: ${rawUrl}`);
  }
  if (parsed.protocol !== 'https:') {
    throw new UpdateError({ code: 'downloadFailed' }, `Only HTTPS download URLs are allowed: ${rawUrl}`);
  }
  if (!ALLOWED_DOWNLOAD_HOSTS.has(parsed.hostname)) {
    throw new UpdateError({ code: 'downloadFailed' }, `Download host is not allowed: ${parsed.hostname}`);
  }
};

const fetchWithAllowlistedRedirects = async (rawUrl: string, signal?: AbortSignal): Promise<Response> => {
  let current = rawUrl;
  for (let i = 0; i <= MAX_REDIRECTS; i++) {
    assertAllowedUrl(current);
    // Each hop's host is checked before it is asked, so the hops go one after the other.
    // eslint-disable-next-line no-await-in-loop
    const res = await fetch(current, { signal, redirect: 'manual', headers: { 'User-Agent': USER_AGENT } });
    if (res.status >= 300 && res.status < 400) {
      const location = res.headers.get('location');
      if (!location) {
        throw new UpdateError({ code: 'downloadFailed' }, 'Download redirect did not include a Location header');
      }
      current = new URL(location, current).toString();
      continue;
    }
    return res;
  }
  throw new UpdateError({ code: 'downloadFailed' }, 'Too many download redirects');
};

/** A free file name in `dir`: `mu-0.1.3-linux-amd64 (1).deb` when the plain one is taken. */
const uniquePath = (dir: string, fileName: string): string => {
  const target = path.join(dir, fileName);
  if (!fs.existsSync(target)) return target;
  const ext = path.extname(fileName);
  const base = path.basename(fileName, ext);
  for (let i = 1; i < 1000; i++) {
    const next = path.join(dir, `${base} (${i})${ext}`);
    if (!fs.existsSync(next)) return next;
  }
  return path.join(dir, `${base}-${Date.now()}${ext}`);
};

/**
 * Downloads a release's installer into `dir` (the Downloads folder) and answers with its path. Only GitHub's hosts
 * are asked, redirects included; a partial file is removed when the download fails.
 */
export const downloadInstaller = async (
  asset: Pick<GitHubReleaseAsset, 'name' | 'url'>,
  options: { dir: string; onProgress?: (percent: number) => void; signal?: AbortSignal }
): Promise<string> => {
  const fileName = path.basename(asset.name).trim() || `mu-update-${Date.now()}`;
  const filePath = uniquePath(options.dir, fileName);
  log.info('[update] Downloading the installer', asset.url, 'to', filePath);
  try {
    const res = await fetchWithAllowlistedRedirects(asset.url, options.signal);
    if (!res.ok) {
      throw new UpdateError({ code: 'serverError', status: res.status }, `Download request failed (${res.status})`);
    }
    if (!res.body) {
      throw new UpdateError({ code: 'downloadFailed' }, 'Download response did not include a body');
    }
    const total = Number.parseInt(res.headers.get('content-length') ?? '', 10);
    let received = 0;
    let lastReport = 0;
    const progress = new Transform({
      transform(chunk: Buffer, _encoding, callback) {
        received += chunk.length;
        const now = Date.now();
        if (total > 0 && now - lastReport >= PROGRESS_INTERVAL_MS) {
          lastReport = now;
          options.onProgress?.(Math.min(100, (received / total) * 100));
        }
        callback(null, chunk);
      },
    });
    await pipeline(
      Readable.fromWeb(res.body as unknown as NodeReadableStream<Uint8Array>),
      progress,
      fs.createWriteStream(filePath)
    );
    options.onProgress?.(100);
    return filePath;
  } catch (error) {
    try {
      fs.rmSync(filePath, { force: true });
    } catch (cleanupError) {
      log.warn('[update] Could not remove the partial installer', filePath, cleanupError);
    }
    throw error;
  }
};
