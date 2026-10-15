import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import { BadRequest, JudgeTimeout } from './judge.ts';

const MAX_BODY_BYTES = 1024 * 1024;
/**
 * How many requests may be answered at once. The judge runs one at a time, so anything beyond this is a queue the
 * machine cannot drain: refusing the excess keeps a burst from growing without bound instead of timing out one by one.
 */
const MAX_INFLIGHT = Math.max(1, Number(process.env.MU_LOCAL_JUDGE_MAX_INFLIGHT ?? 64)) || 64;

/** What the server needs of the judge: `OnnxJudge`, or a stand-in in tests. */
export type JudgeLike = {
  evaluate(state: unknown, questions: unknown, log?: (line: string) => void): Promise<unknown>;
  health(): Record<string, unknown>;
};

function send(response: ServerResponse, status: number, payload: unknown): void {
  const body = Buffer.from(JSON.stringify(payload), 'utf8');
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': body.length });
  response.end(body);
}

function readBody(request: IncomingMessage): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let size = 0;
    request.on('data', (chunk: Buffer) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        reject(new RangeError('too large'));
        request.destroy();
        return;
      }
      chunks.push(chunk);
    });
    request.on('end', () => resolve(Buffer.concat(chunks)));
    request.on('error', reject);
  });
}

/**
 * The local judge over HTTP. Listen on loopback only (`server.listen(port, '127.0.0.1')`). A request that carries an
 * `Origin` header comes from a web page, never from the harness, and is refused: any site open in a browser could
 * otherwise make this machine run the model.
 */
export function createJudgeServer(judge: JudgeLike, log: (line: string) => void = () => undefined): Server {
  let inflight = 0;
  const server = createServer((request, response) => {
    void (async () => {
      if (request.headers.origin) return send(response, 403, { error: 'Requests from web pages are refused' });
      const path = (request.url ?? '').split('?')[0];
      if (request.method === 'GET' && path === '/health') return send(response, 200, judge.health());
      if (request.method !== 'POST' || path !== '/evaluate') return send(response, 404, { error: 'Not found' });
      if (inflight >= MAX_INFLIGHT) return send(response, 503, { error: 'The local judge is busy' });
      inflight++;
      try {
        return await evaluate(request, response);
      } finally {
        inflight--;
      }
    })();
  });
  // A harness that keeps one connection open must not have it dropped between requests, and a request that never
  // arrives must not be waited for either. The judge's own timeout bounds a slow answer; this one bounds a slow caller.
  server.keepAliveTimeout = 5_000;
  server.headersTimeout = 10_000;
  server.requestTimeout = 300_000;
  return server;

  async function evaluate(request: IncomingMessage, response: ServerResponse): Promise<void> {
    let body: { state?: unknown; questions?: unknown };
    try {
      body = JSON.parse((await readBody(request)).toString('utf8')) as typeof body;
    } catch (error) {
      return error instanceof RangeError
        ? send(response, 413, { error: `The request is larger than ${MAX_BODY_BYTES} bytes` })
        : send(response, 400, { error: 'The body is not JSON' });
    }
    if (typeof body !== 'object' || body === null) return send(response, 400, { error: 'The body must be an object' });
    try {
      return send(response, 200, await judge.evaluate(body.state, body.questions, log));
    } catch (error) {
      if (error instanceof BadRequest) return send(response, 400, { error: error.message });
      // The message of a runtime failure can quote internals; the log keeps the type, the caller gets a fixed text.
      log(`evaluate failed: ${error instanceof Error ? error.name : 'Error'}`);
      return send(response, error instanceof JudgeTimeout ? 504 : 500, { error: 'The local judge failed' });
    }
  }
}
