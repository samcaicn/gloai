// Isolation harness for the agent-spawn tests.
//
// Spins up a fully isolated mail-daemon: a throwaway HOME (so the socket +
// spawn registry live in a temp dir), a fake `tmux` bin that records has/new/
// kill-session against a state dir, a free UI port, and a short spawn register
// timeout. Everything is driven over the daemon socket — no real tmux/pi is
// spawned and nothing touches the operator's ~/.pi.
//
// Extracted from test/spawn.test.mjs so the test file stays focused on
// assertions. All functions are stateless (take explicit args, return values);
// the test file owns the module-level handles (proc, client, paths).

import { spawn as pSpawn } from "node:child_process";
import * as net from "node:net";
import * as fs from "node:fs";
import * as path from "node:path";
import * as crypto from "node:crypto";

const REPO = path.resolve(import.meta.dirname, "..", "..");
const DAEMON = path.join(REPO, "extensions", "daemon.mjs");

/** Write a fake `tmux` shell script that records sessions against $TMUX_STATE_DIR. */
export function mkFakeTmux(fakeTmux) {
  const script = `#!/bin/sh
STATE="$TMUX_STATE_DIR"
case "$1" in
  has-session)
    name="$3"
    [ -f "$STATE/sessions/$name" ] && exit 0 || exit 1 ;;
  new-session)
    name=""
    while [ $# -gt 0 ]; do
      case "$1" in -s) name="$2"; shift 2 ;; *) shift ;; esac
    done
    mkdir -p "$STATE/sessions"
    touch "$STATE/sessions/$name"
    exit 0 ;;
  kill-session)
    name="$3"
    rm -f "$STATE/sessions/$name"
    exit 0 ;;
  *)
    exit 0 ;;
esac
`;
  fs.writeFileSync(fakeTmux, script, { mode: 0o755 });
}

/** Start an isolated daemon. Returns the child process (caller owns its lifetime). */
export function startDaemon({ tmpHome, tmpState, fakeTmux, sockPath, envExtra } = {}) {
  return new Promise((resolve, reject) => {
    const proc = pSpawn(process.execPath, [DAEMON], {
      env: {
        ...process.env,
        HOME: tmpHome,                 // socket + registry land in tmpHome/.pi/agent
        PI_MAIL_TMUX_BIN: fakeTmux,
        PI_MAIL_PI_BIN: "/bin/true",   // never actually run (fake tmux ignores it)
        PI_MAIL_UI_PORT: "0",          // OS-picked UI port; we don't use the UI here
        PI_MAIL_UI_HOST: "127.0.0.1",
        PI_MAIL_SPAWN_TIMEOUT: "1500", // fast register-wait for the timeout test
        TMUX_STATE_DIR: tmpState,
        PATH: `${path.dirname(fakeTmux)}:${process.env.PATH}`,
        ...(envExtra || {}),
      },
      stdio: ["ignore", "pipe", "pipe"],
    });
    let stderr = "";
    proc.stderr.on("data", (c) => { stderr += c.toString(); });
    proc.on("exit", (code, sig) => {
      if (!proc.__stopped) console.error("daemon exited unexpectedly", code, sig, stderr.slice(-500));
    });
    // Wait for the socket to appear, then resolve.
    const tryConnect = (retries = 0) => {
      const s = net.createConnection(sockPath);
      s.once("connect", () => { s.destroy(); resolve(proc); });
      s.once("error", () => {
        if (retries > 200) return reject(new Error("daemon socket never appeared\n" + stderr));
        setTimeout(() => tryConnect(retries + 1), 30);
      });
    };
    tryConnect();
  });
}

/** Stop a daemon process started by startDaemon. Resolves on exit. */
export function stopDaemon(proc) {
  if (!proc) return Promise.resolve();
  proc.__stopped = true;
  return new Promise((r) => {
    let done = false;
    const finish = () => { if (!done) { done = true; r(); } };
    proc.once("exit", () => { finish(); });
    proc.kill("SIGTERM");
    setTimeout(() => { if (!done) { proc.kill("SIGKILL"); } finish(); }, 3000);
  });
}

/** Minimal newline-delimited JSON socket client (matches the extension). */
export function mkClient(sockPath) {
  return new Promise((resolve, reject) => {
    const s = net.createConnection(sockPath);
    s.setEncoding("utf8");
    let buf = "";
    let nextId = 1;
    const pending = new Map();
    const onNewMail = [];
    s.on("data", (chunk) => {
      buf += chunk;
      const lines = buf.split("\n");
      buf = lines.pop();
      for (const line of lines) {
        if (!line.trim()) continue;
        let m; try { m = JSON.parse(line); } catch { continue; }
        if (m.type === "ping") { s.write(JSON.stringify({ type: "pong" }) + "\n"); continue; }
        if (m.type === "new_mail") { onNewMail.forEach((cb) => cb(m.message)); continue; }
        if (m._reqId != null && pending.has(m._reqId)) {
          const e = pending.get(m._reqId); clearTimeout(e.t); pending.delete(m._reqId); e.res(m);
        }
      }
    });
    s.once("connect", () => resolve({
      request(msg, timeoutMs = 5000) {
        const id = nextId++;
        return new Promise((res, rej) => {
          const t = setTimeout(() => { pending.delete(id); rej(new Error("timeout: " + msg.type)); }, timeoutMs);
          pending.set(id, { res, rej, t });
          s.write(JSON.stringify({ ...msg, _reqId: id }) + "\n");
        });
      },
      onNewMail(cb) { onNewMail.push(cb); },
      close() { s.destroy(); },
    }));
    s.once("error", reject);
  });
}

/** Register as an agent (required before board/spawn RPCs). */
export function register(c, name, cwd) {
  return c.request({ type: "register", agentId: crypto.randomUUID(), agentName: name, cwd });
}
