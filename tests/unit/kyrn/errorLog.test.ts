import { spawn } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { describeError } from '../../../packages/desktop/src/process/agent/kyrn/errorLog.ts';

const ROOT = join(__dirname, '../../..');
const ERROR_LOG = join(ROOT, 'packages/desktop/src/process/agent/kyrn/errorLog.ts');
const dirs: string[] = [];

afterEach(() => {
  for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true });
});

/** Runs `body` in a process of its own after the adapter's guards are in, as index.ts puts them in. */
async function run(body: string): Promise<{ code: number | null; out: string; err: string }> {
  const dir = mkdtempSync(join(tmpdir(), 'kyrn-errorlog-'));
  dirs.push(dir);
  // CommonJS, as tsx runs the adapter's sources.
  writeFileSync(
    join(dir, 'adapter.ts'),
    `import { logUncaught } from ${JSON.stringify(ERROR_LOG)};\nlogUncaught();\n${body}\n`
  );
  const tsx = createRequire(join(ROOT, 'package.json')).resolve('tsx/cli');
  const child = spawn(process.execPath, [tsx, join(dir, 'adapter.ts')], {
    cwd: dir,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let out = '';
  let err = '';
  child.stdout.on('data', (chunk: Buffer) => {
    out += String(chunk);
  });
  child.stderr.on('data', (chunk: Buffer) => {
    err += String(chunk);
  });
  const code = await new Promise<number | null>((resolve) => child.on('close', resolve));
  return { code, out, err };
}

describe('the adapter’s errors nothing handled', () => {
  it('logs a promise that failed with nothing to handle it, and goes on', async () => {
    const { code, out, err } = await run(`
void Promise.reject(new Error('the answer went nowhere'));
setTimeout(() => {
  process.stdout.write('alive\\n');
}, 300);
`);
    expect(code).toBe(0);
    expect(out).toBe('alive\n');
    expect(err).toContain(
      '[mu] a promise failed and nothing handled it; the adapter goes on: Error: the answer went nowhere\n    at '
    );
  }, 20000);

  it('logs an exception nothing caught with its stack, then exits with code 1', async () => {
    const { code, out, err } = await run(`
setTimeout(() => {
  process.stdout.write('before\\n');
  throw new Error('broken state');
}, 50);
setTimeout(() => {
  process.stdout.write('after\\n');
}, 500);
`);
    expect(code).toBe(1);
    expect(out).toBe('before\n');
    expect(err).toContain('[mu] the adapter stops on an error nothing handled: Error: broken state\n    at ');
  }, 20000);

  it('describes what is not an error, too', () => {
    expect(describeError({ code: 'EPIPE', syscall: 'write' })).toBe("{ code: 'EPIPE', syscall: 'write' }");
    expect(describeError('gone')).toBe("'gone'");
    expect(describeError(undefined)).toBe('undefined');
    const error = new Error('x');
    error.stack = undefined;
    expect(describeError(error)).toBe('Error: x');
  });
});
