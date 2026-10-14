/**
 * The local judge process: started by the app (an Electron utility process) with the model folder and the port in its
 * environment, it loads Laya and serves the judge contract on loopback until it is killed. Lines on stderr are the
 * app's progress text; `parentPort` gets `{type: 'ready' | 'failed'}`.
 */
import type { Server } from 'node:http';
import { checkBundle } from './bundle.ts';
import { loadJudge } from './load.ts';
import { createJudgeServer } from './server.ts';

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

async function main(): Promise<void> {
  const dir = process.env.MU_LOCAL_JUDGE_ONNX_DIR;
  const port = Number(process.env.MU_LOCAL_JUDGE_PORT || process.env.KYRN_LOCAL_JUDGE_PORT || 47823);
  if (!dir) fail('config', 'MU_LOCAL_JUDGE_ONNX_DIR is not set');
  if (!Number.isInteger(port) || port < 1 || port > 65535) fail('config', `Invalid port: ${String(port)}`);

  // Hashes were checked by the app before it started this process; sizes are enough here.
  const check = await checkBundle(dir);
  if (check.missing.length) fail('missing', `Model files missing: ${check.missing.join(', ')}`);
  if (check.wrong.length) fail('wrong', `Model files that are not the tested version: ${check.wrong.join(', ')}`);

  const providers = process.env.MU_LOCAL_JUDGE_ONNX_PROVIDERS?.split(',').filter(Boolean);
  let loaded: Awaited<ReturnType<typeof loadJudge>>;
  try {
    loaded = await loadJudge(check.paths, { providers, say });
  } catch (error) {
    fail('runtime', error instanceof Error ? error.message : String(error));
  }
  const { judge, runner } = loaded;
  const server = createJudgeServer(judge, say);
  try {
    await listen(server, port);
  } catch (error) {
    const code = (error as NodeJS.ErrnoException).code;
    fail(code === 'EADDRINUSE' ? 'port' : 'listen', `Cannot listen on 127.0.0.1:${port}: ${code ?? String(error)}`);
  }
  say(`listening on http://127.0.0.1:${port} (${runner.provider})`);
  await judge.warmUp();
  say('ready');
  parent?.postMessage({ type: 'ready', provider: runner.provider });

  const stop = () => {
    server.close();
    void runner.release().finally(() => process.exit(0));
  };
  process.on('SIGTERM', stop);
  process.on('SIGINT', stop);
}

main().catch((error: unknown) => fail('crash', error instanceof Error ? error.message : String(error)));
