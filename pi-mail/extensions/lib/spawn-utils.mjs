/**
 * Path + tmux-session helper functions for agent spawning.
 *
 * Pure(ish) utilities with no dependency on the spawn registry state, so they
 * can be shared and unit-tested without the daemon. Extracted from spawn.mjs.
 * validateSpawnCwd + listSpawnDir deal with directory resolution for the
 * picker; safeSessionName / defaultSpawnName / tmuxSessionExists deal with
 * tmux session naming and liveness.
 */

import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { spawnSync } from "node:child_process";

const TMUX_BIN = process.env.PI_MAIL_TMUX_BIN || "tmux";

/** Resolve and validate a cwd: must be a real directory anywhere on the
 *  filesystem. The picker can browse and spawn from any path — there is no
 *  allowlist (the former "allowed root" restriction was removed). */
export function validateSpawnCwd(cwd) {
  if (!cwd || typeof cwd !== "string") return { error: "cwd is required" };
  let resolved;
  try {
    resolved = path.resolve(cwd);
  } catch {
    return { error: `invalid path: ${cwd}` };
  }
  let st;
  try {
    st = fs.statSync(resolved);
  } catch {
    return { error: `not a directory: ${resolved}` };
  }
  if (!st.isDirectory()) return { error: `not a directory: ${resolved}` };
  return { resolved };
}

/** Sanitise a name for use as a tmux session name (tmux disallows '.' and ':'). */
export function safeSessionName(name) {
  return String(name || "").replace(/[.:\\]/g, "-").replace(/\s+/g, "-").slice(0, 80);
}

/** Default agent name: <dir-basename>-<id6>, matching the extension's auto-slug. */
export function defaultSpawnName(cwd) {
  const base = path.basename(cwd) || "pi-agent";
  return `${base}-${crypto.randomUUID().slice(0, 6)}`;
}

export function tmuxSessionExists(name) {
  try {
    const r = spawnSync(TMUX_BIN, ["has-session", "-t", name]);
    return r.status === 0;
  } catch {
    return false;
  }
}

/** Directory listing for the picker: subdirectories of `dir` (any path on the
 *  filesystem). validateSpawnCwd only checks it's a real directory. When
 *  `hidden` is true, dot-directories (e.g. .git, .config) are included too. */
export function listSpawnDir(dir, { hidden = false } = {}) {
  const v = validateSpawnCwd(dir);
  if (v.error) return { error: v.error };
  const resolved = v.resolved;
  try {
    const entries = fs.readdirSync(resolved, { withFileTypes: true });
    const dirs = entries
      .filter((e) => e.isDirectory() && (hidden || !e.name.startsWith(".")))
      .map((e) => ({ name: e.name, path: path.join(resolved, e.name) }))
      .sort((a, b) => a.name.localeCompare(b.name));
    return { dir: resolved, dirs };
  } catch (e) {
    return { error: `could not read ${resolved}: ${e?.message ?? String(e)}` };
  }
}

export { TMUX_BIN };
