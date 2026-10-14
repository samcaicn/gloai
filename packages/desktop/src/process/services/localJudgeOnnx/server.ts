import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import { BadRequest } from './judge.ts';

const MAX_BODY_BYTES = 1024 * 1024;

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
  return createServer((request, response) => {
    void (async () => {
      if (request.headers.origin) return send(response, 403, { error: 'Requests from web pages are refused' });
      const path = (request.url ?? '').split('?')[0];
      if (request.method === 'GET' && path === '/health') return send(response, 200, judge.health());
      if (request.method !== 'POST' || path !== '/evaluate') return send(response, 404, { error: 'Not found' });
      let body: { state?: unknown; questions?: unknown };
      try {
        body = JSON.parse((await readBody(request)).toString('utf8')) as typeof body;
      } catch (error) {
        return error instanceof RangeError
          ? send(response, 413, { error: `The request is larger than ${MAX_BODY_BYTES} bytes` })
          : send(response, 400, { error: 'The body is not JSON' });
      }
      if (typeof body !== 'object' || body === null)
        return send(response, 400, { error: 'The body must be an object' });
      try {
        return send(response, 200, await judge.evaluate(body.state, body.questions, log));
      } catch (error) {
        if (error instanceof BadRequest) return send(response, 400, { error: error.message });
        // The message of a runtime failure can quote internals; the log keeps the type, the caller gets a fixed text.
        log(`evaluate failed: ${error instanceof Error ? error.name : 'Error'}`);
        return send(response, 500, { error: 'The local judge failed' });
      }
    })();
  });
}
