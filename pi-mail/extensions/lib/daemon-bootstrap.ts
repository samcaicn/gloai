/**
 * Daemon bootstrap for the pi-mail extension — connect to an existing daemon
 * or spawn a fresh one. Extracted from index.ts.
 */

import { readFileSync } from "node:fs";
import { spawn } from "node:child_process";
import { MailClient } from "./mail-client.js";

export async function sleep(ms: number): Promise<void> {
  return new Promise<void>((r) => setTimeout(r, ms));
}

async function tryConnect(socketPath: string): Promise<MailClient | null> {
  try {
    const c = new MailClient();
    await c.connect(socketPath);
    return c;
  } catch {
    return null;
  }
}

/** True if a daemon process is currently alive (per its PID file). */
export function isDaemonAlive(pidPath: string): boolean {
  try {
    const raw = readFileSync(pidPath, "utf8").trim();
    const pid = parseInt(raw, 10);
    if (pid > 0) {
      process.kill(pid, 0); // throws if the process no longer exists
      return true;
    }
  } catch {
    // No PID file or process dead
  }
  return false;
}

export async function ensureDaemonAndConnect(
  socketPath: string,
  daemonScript: string,
  pidPath: string,
): Promise<MailClient> {
  // Try an existing daemon first.
  let c = await tryConnect(socketPath);
  if (c) return c;

  // Only spawn when no daemon process is alive. When several agents reconnect
  // at once (e.g. after a daemon crash), every one of them would otherwise
  // spawn its own daemon — the daemons then fight over the socket, agents
  // briefly connect to a doomed daemon, disconnect, and reconnect again: the
  // reconnect loop. Gating the spawn on the PID file makes it single-flight.
  // (The daemon also probes the socket before taking over, so a race here is
  // safe — the loser just exits.)
  if (!isDaemonAlive(pidPath)) {
    const child = spawn("node", [daemonScript], {
      detached: true,
      stdio: "ignore",
    });
    child.unref();
  }

  // Wait up to 6 s for the daemon (existing or freshly spawned) to answer.
  for (let i = 0; i < 60; i++) {
    await sleep(100);
    c = await tryConnect(socketPath);
    if (c) return c;
  }

  throw new Error("Failed to connect to pi-mail daemon after 6 s");
}
