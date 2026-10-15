/**
 * The local judge process on the GGUF path: started by the app (an Electron utility process) with the model file and
 * the port in its environment, it starts `llama-server` beside itself and serves the judge contract on loopback,
 * translating `/evaluate` to llama.cpp's `/v1/systemone`, until it is killed. Lines on stderr are the app's progress
 * text; `parentPort` gets `{type: 'ready' | 'failed'}`.
 */
import { spawn, type ChildProcess } from 'node:child_process';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import type { Server } from 'node:http';
import { layaGgufPreset } from '../../../common/kyrn/layaGguf.ts';
import { GgufJudge } from './client.ts';
import { createJudgeServer } from '../localJudgeOnnx/server.ts';

type ParentPort = { postMessage(message: unknown): void };
const parent = (process as unknown as { parentPort?: ParentPort }).parentPort;

const say = (line: string) => process.stderr.write(`[${new Date().toISOString().slice(11, 19)}] ${line}\n`);

function fail(reason: string, detail: string): never {
  say(detail);
  parent?.postMessage({ type: 'failed', reason });
  process.exit(1);
}

function listen(server: Server, port: number): Promise<void> {
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', () => {
      server.off('error', reject);
      resolve();
    });
  });
}

/** Where the gguf file is: named outright, or a build's folder and file. */
function modelPath(): string {
  const { env } = process;
  if (env.MU_LOCAL_JUDGE_GGUF_FILE) return env.MU_LOCAL_JUDGE_GGUF_FILE;
  const dir = env.MU_LOCAL_JUDGE_GGUF_DIR;
  if (!dir) fail('config', 'MU_LOCAL_JUDGE_GGUF_DIR or MU_LOCAL_JUDGE_GGUF_FILE is not set');
  const preset = layaGgufPreset(env.MU_LOCAL_JUDGE_GGUF_PRESET);
  if (!preset.model) fail('config', 'This build names no file: set MU_LOCAL_JUDGE_GGUF_FILE');
  return join(dir, preset.model);
}

/** Waits until the server answers `/health`, or gives up after `ms`. */
async function waitForHealth(url: string, ms: number, child: ChildProcess): Promise<void> {
  const deadline = Date.now() + ms;
  for (;;) {
    if (child.exitCode !== null) throw new Error(`llama-server exited with code ${String(child.exitCode)}`);
    try {
      const response = await fetch(`${url}/health`, { signal: AbortSignal.timeout(2000) });
      if (response.ok) return;
    } catch {
      // not up yet
    }
    if (Date.now() > deadline) throw new Error(`llama-server did not answer on ${url} within ${Math.round(ms / 1000)}s`);
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
}

async function main(): Promise<void> {
  const port = Number(process.env.MU_LOCAL_JUDGE_PORT || process.env.KYRN_LOCAL_JUDGE_PORT || 47823);
  const upstreamPort = Number(process.env.MU_LOCAL_JUDGE_GGUF_UPSTREAM_PORT || port + 1);
  if (!Number.isInteger(port) || port < 1 || port > 65535) fail('config', `Invalid port: ${String(port)}`);
  const model = modelPath();
  if (!existsSync(model)) fail('missing', `No model file at ${model}`);

  const presetId = process.env.MU_LOCAL_JUDGE_GGUF_PRESET;
  const bin = process.env.MU_LLAMA_SERVER_BIN || 'llama-server';
  const extra = (process.env.MU_LLAMA_SERVER_ARGS ?? '').split(/\s+/).filter(Boolean);
  say(`starting ${bin} with ${model}`);
  const child = spawn(bin, ['-m', model, '--host', '127.0.0.1', '--port', String(upstreamPort), ...extra], {
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let output = '';
  const remember = (chunk: Buffer | string) => {
    const line = String(chunk);
    output = (output + line).slice(-2000);
    for (const text of line.split(/[\r\n]+/).filter(Boolean)) say(text.trim().slice(0, 300));
  };
  child.stdout?.on('data', remember);
  child.stderr?.on('data', remember);

  const stop = () => {
    if (child.exitCode === null) child.kill();
    process.exit(0);
  };
  process.on('SIGTERM', stop);
  process.on('SIGINT', stop);

  const upstream = `http://127.0.0.1:${upstreamPort}`;
  try {
    await waitForHealth(upstream, Number(process.env.MU_LOCAL_JUDGE_GGUF_START_TIMEOUT_MS ?? 180_000), child);
  } catch (error) {
    fail('runtime', `${error instanceof Error ? error.message : String(error)}${output ? `\n${output}` : ''}`);
  }
  say('llama-server is up');

  const judge = new GgufJudge({
    upstream,
    model: `laya-gguf:${layaGgufPreset(presetId).model || model}`,
    preset: presetId ?? layaGgufPreset(presetId).id,
  });
  const server = createJudgeServer(judge, say);
  try {
    await listen(server, port);
  } catch (error) {
    const code = (error as NodeJS.ErrnoException).code;
    fail(code === 'EADDRINUSE' ? 'port' : 'listen', `Cannot listen on 127.0.0.1:${port}: ${code ?? String(error)}`);
  }
  say(`listening on http://127.0.0.1:${port} (gguf, ${upstream})`);
  await judge.warmUp();
  say('ready');
  parent?.postMessage({ type: 'ready', provider: 'llama.cpp', preset: judge.health().preset });
}

main().catch((error: unknown) => fail('crash', error instanceof Error ? error.message : String(error)));
