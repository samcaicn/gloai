import { existsSync } from "node:fs";
import { spawn, type ChildProcess } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { homedir, tmpdir } from "node:os";
import { join } from "node:path";
import { parseDshWebUrl } from "./dshWebUrl";

const READY_MS = 90_000;
const DEFAULT_PACKAGE = "@deepseek-ai/dsh@^0.1.0-rc.6";

function extraPathDirs(): string[] {
  const home = homedir();
  return [
    join(home, ".npm-global/bin"),
    join(home, ".local/bin"),
    join(home, ".local/share/pnpm"),
    join(home, "Library/pnpm"),
    "/opt/homebrew/bin",
    "/usr/local/bin",
  ];
}

function which(name: string): string | null {
  const sep = process.platform === "win32" ? ";" : ":";
  const dirs = [...extraPathDirs(), ...(process.env.PATH ?? "").split(sep)];
  const files =
    process.platform === "win32"
      ? [`${name}.cmd`, `${name}.exe`, `${name}.bat`, name]
      : [name];
  for (const dir of dirs) {
    if (!dir) continue;
    for (const file of files) {
      const candidate = join(dir, file);
      if (existsSync(candidate)) return candidate;
    }
  }
  return null;
}

function resolveLauncher(): { program: string; args: string[] } | null {
  const dsh = which("dsh");
  if (dsh) {
    return { program: dsh, args: ["web", "--host", "127.0.0.1", "--port", "0"] };
  }
  const npx = which("npx");
  if (npx) {
    return {
      program: npx,
      args: ["--yes", DEFAULT_PACKAGE, "web", "--host", "127.0.0.1", "--port", "0"],
    };
  }
  return null;
}

function waitForReady(child: ChildProcess): Promise<string> {
  return new Promise((resolve, reject) => {
    let out = "";
    const timer = setTimeout(() => {
      reject(new Error(`dsh web not ready in ${READY_MS}ms; output:\n${out}`));
    }, READY_MS);
    const onData = (chunk: Buffer): void => {
      out += chunk.toString();
      for (const line of out.split(/\r?\n/)) {
        const url = parseDshWebUrl(line);
        if (url) {
          clearTimeout(timer);
          resolve(url);
          return;
        }
      }
    };
    child.stdout?.on("data", onData);
    child.stderr?.on("data", onData);
    child.once("exit", (code) => {
      clearTimeout(timer);
      reject(new Error(`dsh web exited early (code ${code}); output:\n${out}`));
    });
  });
}

async function terminate(child: ChildProcess): Promise<void> {
  if (child.exitCode !== null || child.pid === undefined) return;
  const closed = new Promise<void>((resolve) => {
    child.once("close", () => resolve());
  });
  if (process.platform === "win32") {
    spawn("taskkill", ["/T", "/F", "/PID", String(child.pid)], { stdio: "ignore" });
  } else {
    try {
      process.kill(-child.pid, "SIGTERM");
    } catch {
      child.kill("SIGTERM");
    }
  }
  await Promise.race([
    closed,
    new Promise<void>((resolve) => {
      setTimeout(resolve, 5_000).unref();
    }),
  ]);
  if (child.exitCode === null && child.pid !== undefined) {
    if (process.platform !== "win32") {
      try {
        process.kill(-child.pid, "SIGKILL");
      } catch {
        child.kill("SIGKILL");
      }
    }
    await Promise.race([
      closed,
      new Promise<void>((resolve) => {
        setTimeout(resolve, 2_000).unref();
      }),
    ]);
  }
}

export async function smokeDshWeb(): Promise<{ url: string; status: number }> {
  const launcher = resolveLauncher();
  if (!launcher) {
    throw new Error("neither dsh nor npx is on PATH");
  }
  const workspace = await mkdtemp(join(tmpdir(), "dsh-gui-smoke-"));
  const sep = process.platform === "win32" ? ";" : ":";
  const child = spawn(launcher.program, launcher.args, {
    cwd: workspace,
    env: {
      ...process.env,
      PATH: [...extraPathDirs(), process.env.PATH ?? ""].join(sep),
      DEEPSEEK_API_KEY: process.env.DEEPSEEK_API_KEY || "keyless-web-no-call",
      DSH_HOME: join(workspace, ".dsh"),
      DSH_AGENTS_HOME: join(workspace, ".agents"),
    },
    stdio: ["ignore", "pipe", "pipe"],
    detached: process.platform !== "win32",
  });
  try {
    const url = await waitForReady(child);
    const response = await fetch(url);
    return { url, status: response.status };
  } finally {
    await terminate(child);
    await rm(workspace, { recursive: true, force: true });
  }
}

export { resolveLauncher };
