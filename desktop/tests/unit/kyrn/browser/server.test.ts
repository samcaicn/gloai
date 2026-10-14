import { mkdtempSync, readFileSync, rmSync, statSync, writeFileSync, existsSync, readdirSync } from 'node:fs';
import { request } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import WebSocket from 'ws';
import { ADVERT_FILE, removeAdvert, writeAdvert } from '@process/services/muBrowser/advert';
import { BrowserBridge } from '@process/services/muBrowser/bridge';
import { startBridgeServer, type BridgeServer } from '@process/services/muBrowser/server';

const quietHost = {
  openTab: () => Promise.reject(new Error('no panel in this test')),
  publish: () => undefined,
  keyboard: () => Promise.resolve(false),
};

describe('the bridge server', () => {
  const servers: BridgeServer[] = [];
  afterEach(async () => {
    await Promise.all(servers.splice(0).map((server) => server.close()));
  });

  async function started() {
    const server = await startBridgeServer(new BrowserBridge(quietHost));
    servers.push(server);
    return server;
  }

  const upgradeStatus = (url: string) =>
    new Promise<number | 'open'>((resolve) => {
      const socket = new WebSocket(url);
      socket.on('open', () => {
        socket.close();
        resolve('open');
      });
      socket.on('unexpected-response', (_request, response) => resolve(response.statusCode ?? 0));
      socket.on('error', () => undefined);
    });

  it('listens on the loopback address only, with a token of at least 128 bits as its path', async () => {
    const server = await started();
    const url = new URL(server.url);
    expect(url.protocol).toBe('ws:');
    expect(url.hostname).toBe('127.0.0.1');
    expect(Number(url.port)).toBe(server.port);
    expect(url.pathname).toMatch(/^\/[0-9a-f]{64}$/);
    expect((await started()).url).not.toBe(server.url);
  });

  it('refuses any other path with 404 before the upgrade', async () => {
    const server = await started();
    const base = `ws://127.0.0.1:${server.port}`;
    expect(await upgradeStatus(`${base}/`)).toBe(404);
    expect(await upgradeStatus(`${base}/devtools/browser`)).toBe(404);
    expect(await upgradeStatus(`${server.url}0`)).toBe(404);
    expect(await upgradeStatus(`${base}/?token=${new URL(server.url).pathname.slice(1)}`)).toBe(404);
    expect(await upgradeStatus(server.url)).toBe('open');
  });

  it('has nothing to discover over plain http', async () => {
    const server = await started();
    const statuses = await Promise.all(
      ['/json/version', '/json/list', new URL(server.url).pathname].map(
        (path) =>
          new Promise<number>((resolve) => {
            request({ host: '127.0.0.1', port: server.port, path }, (response) => {
              response.resume();
              resolve(response.statusCode ?? 0);
            }).end();
          })
      )
    );
    expect(statuses).toEqual([404, 404, 404]);
  });

  it('speaks the protocol over the socket and drops the connection’s claims when it closes', async () => {
    const server = await started();
    const socket = new WebSocket(server.url);
    await new Promise((resolve) => socket.on('open', resolve));
    const answers: Record<string, unknown>[] = [];
    socket.on('message', (data) => answers.push(JSON.parse(data.toString()) as Record<string, unknown>));
    socket.send(JSON.stringify({ id: 1, method: 'Mu.hello', params: { version: 1 } }));
    socket.send('not json');
    socket.send(JSON.stringify({ id: 2, method: 'Target.createTarget', params: { url: 'about:blank' } }));
    await expect.poll(() => answers.length).toBe(2);
    expect(answers[0]).toEqual({ id: 1, result: { embedded: true, version: 1 } });
    expect(answers[1]).toEqual({ id: 2, error: { message: 'no panel in this test' } });
    socket.close();
  });
});

describe('the advert file', () => {
  const homes: string[] = [];
  afterEach(() => {
    while (homes.length > 0) rmSync(homes.pop() as string, { recursive: true, force: true });
  });
  const home = () => {
    const root = mkdtempSync(join(tmpdir(), 'mu-advert-'));
    homes.push(root);
    return join(root, '.mu');
  };
  const posix = process.platform !== 'win32';

  it('is written for the user alone, in a home it may have to create', () => {
    const dir = home();
    const path = writeAdvert(dir, { url: 'ws://127.0.0.1:41000/secret', pid: 4242 });
    expect(path).toBe(join(dir, ADVERT_FILE));
    expect(JSON.parse(readFileSync(path, 'utf8'))).toEqual({ url: 'ws://127.0.0.1:41000/secret', pid: 4242 });
    if (posix) {
      expect(statSync(path).mode & 0o777).toBe(0o600);
      expect(statSync(dir).mode & 0o777).toBe(0o700);
    }
    expect(readdirSync(dir)).toEqual([ADVERT_FILE]);
  });

  it('replaces what a crashed app left behind, whatever its mode was', () => {
    const dir = home();
    writeAdvert(dir, { url: 'ws://127.0.0.1:1/old', pid: 1 });
    writeFileSync(join(dir, ADVERT_FILE), '{"url":"ws://127.0.0.1:1/stale","pid":1}', { mode: 0o644 });
    const path = writeAdvert(dir, { url: 'ws://127.0.0.1:2/new', pid: 2 });
    expect(JSON.parse(readFileSync(path, 'utf8'))).toEqual({ url: 'ws://127.0.0.1:2/new', pid: 2 });
    if (posix) expect(statSync(path).mode & 0o777).toBe(0o600);
  });

  it('is removed on quit, but never another running app’s', () => {
    const dir = home();
    const path = writeAdvert(dir, { url: 'ws://127.0.0.1:2/mine', pid: 2 });
    removeAdvert(dir, 'ws://127.0.0.1:3/someone-else');
    expect(existsSync(path)).toBe(true);
    removeAdvert(dir, 'ws://127.0.0.1:2/mine');
    expect(existsSync(path)).toBe(false);
    expect(() => removeAdvert(dir, 'ws://127.0.0.1:2/mine')).not.toThrow();
  });
});
