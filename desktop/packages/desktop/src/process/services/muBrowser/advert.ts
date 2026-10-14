import { chmodSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

/** The file name the harness looks for in the mu home (`ADVERT_FILE` in its `browser/embedded.ts`). */
export const ADVERT_FILE = 'desktop-browser.json';

export type Advert = { url: string; pid: number };

/**
 * Tells a harness on this machine where the app's browser listens: `<mu home>/desktop-browser.json`.
 *
 * The address carries the token, so the file is for the user alone: mode 0600 in a home of mode 0700. Windows
 * does not honour these mode bits; there the file is protected by the ACL of the user profile it lives in, which
 * other accounts cannot read by default. Nothing here pretends otherwise.
 *
 * `home` comes from `muHome()`, which never names `~/.mu` while only `~/.kyrn` exists, so creating the directory
 * here cannot split the data home in two.
 */
export function writeAdvert(home: string, advert: Advert): string {
  mkdirSync(home, { recursive: true, mode: 0o700 });
  const path = join(home, ADVERT_FILE);
  // Written beside and renamed over: a reader never sees half a file, and an advert left by a crashed app
  // (whatever its mode was) is replaced by one created with the right mode from the start.
  const draft = `${path}.${advert.pid}.tmp`;
  try {
    writeFileSync(draft, `${JSON.stringify(advert)}\n`, { mode: 0o600, flag: 'w' });
    // `mode` only applies when the file is created, and is cut down by the umask but never widened. Be explicit.
    chmodSync(draft, 0o600);
    renameSync(draft, path);
  } catch (error) {
    rmSync(draft, { force: true });
    throw error;
  }
  return path;
}

/** Removes the advert on quit, unless another running app has meanwhile put its own there. */
export function removeAdvert(home: string, url: string): void {
  const path = join(home, ADVERT_FILE);
  try {
    const current = JSON.parse(readFileSync(path, 'utf8')) as { url?: unknown };
    if (current.url !== url) return;
  } catch {
    // Unreadable or already gone: nothing of ours is left to remove.
    return;
  }
  rmSync(path, { force: true });
}
