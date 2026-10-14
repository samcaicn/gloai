import type { AddressInfo } from 'node:net';
import type { Server } from 'node:http';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { BadRequest } from '@process/services/localJudgeOnnx/judge.ts';
import { createJudgeServer, type JudgeLike } from '@process/services/localJudgeOnnx/server.ts';

let server: Server | undefined;
afterEach(() => new Promise<void>((resolve) => (server ? server.close(() => resolve()) : resolve())));

async function start(judge: JudgeLike, log = vi.fn()) {
  server = createJudgeServer(judge, log);
  await new Promise<void>((resolve) => server!.listen(0, '127.0.0.1', resolve));
  const { port } = server.address() as AddressInfo;
  return { url: `http://127.0.0.1:${port}`, log };
}

const judge = (evaluate: JudgeLike['evaluate'] = async () => ({ answers: {} })): JudgeLike => ({
  evaluate: vi.fn(evaluate),
  health: () => ({ status: 'ok' }),
});

const post = (url: string, body: string, headers: Record<string, string> = {}) =>
  fetch(`${url}/evaluate`, { method: 'POST', body, headers: { 'Content-Type': 'application/json', ...headers } });

describe('the local judge server', () => {
  it('answers the health check', async () => {
    const { url } = await start(judge());
    const response = await fetch(`${url}/health`);
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ status: 'ok' });
  });

  it('evaluates a request and passes state and questions on', async () => {
    const stub = judge(async () => ({ answers: { q: { type: 'boolean', probability: 0.9 } } }));
    const { url } = await start(stub);
    const response = await post(url, JSON.stringify({ state: 's', questions: { q: {} } }));
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ answers: { q: { type: 'boolean', probability: 0.9 } } });
    expect(stub.evaluate).toHaveBeenCalledWith('s', { q: {} }, expect.any(Function));
  });

  it('answers 400 with the reason for a bad request', async () => {
    const { url } = await start(
      judge(async () => {
        throw new BadRequest('questions must be a non-empty object keyed by question id');
      })
    );
    const response = await post(url, JSON.stringify({ state: 's', questions: {} }));
    expect(response.status).toBe(400);
    expect(await response.json()).toEqual({ error: 'questions must be a non-empty object keyed by question id' });
    expect((await post(url, '{not json')).status).toBe(400);
    expect((await post(url, 'null')).status).toBe(400);
  });

  it('keeps the text of a runtime failure out of the answer', async () => {
    const { url, log } = await start(
      judge(async () => {
        throw new TypeError('secret internals /Users/someone/model.onnx');
      })
    );
    const response = await post(url, JSON.stringify({ state: 's', questions: { q: {} } }));
    expect(response.status).toBe(500);
    expect(await response.json()).toEqual({ error: 'The local judge failed' });
    expect(log).toHaveBeenCalledWith('evaluate failed: TypeError');
  });

  it('refuses a body over 1 MiB', async () => {
    const { url } = await start(judge());
    const response = await post(url, JSON.stringify({ state: 'x'.repeat(1024 * 1024), questions: {} })).catch(
      () => undefined
    );
    // The server stops reading: the client sees 413 or a reset connection, never an evaluation.
    if (response) expect(response.status).toBe(413);
  });

  it('refuses requests from web pages', async () => {
    const stub = judge();
    const { url } = await start(stub);
    const response = await post(url, JSON.stringify({ state: 's', questions: { q: {} } }), {
      Origin: 'https://example.com',
    });
    expect(response.status).toBe(403);
    expect(stub.evaluate).not.toHaveBeenCalled();
  });

  it('answers 404 elsewhere', async () => {
    const { url } = await start(judge());
    expect((await fetch(`${url}/evaluate`)).status).toBe(404);
    expect((await fetch(`${url}/other`, { method: 'POST' })).status).toBe(404);
  });
});
