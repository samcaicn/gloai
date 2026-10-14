import { randomBytes } from 'node:crypto';
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { WebSocketServer, type WebSocket } from 'ws';
import type { BrowserBridge } from './bridge';
import { pathCarriesToken } from './protocol';

const HOST = '127.0.0.1';

export type BridgeServer = {
  /** `ws://127.0.0.1:<port>/<token>`: the whole secret. It goes into the advert file and nowhere else, never a log. */
  readonly url: string;
  readonly port: number;
  close(): Promise<void>;
};

/**
 * The loopback socket the harness connects to. Deliberately not Chromium's `--remote-debugging-port`: that one is
 * application-wide and would hand the app's own privileged window to any local process. Here a connection gets a
 * bridge that only knows the pages it opened itself.
 *
 * The path is a 256-bit random token. A request for anything else is answered `404` before any upgrade happens,
 * so a wrong path is indistinguishable from a server that has no WebSocket at all.
 */
export async function startBridgeServer(
  bridge: BrowserBridge,
  token = randomBytes(32).toString('hex')
): Promise<BridgeServer> {
  const http: Server = createServer((_request, response) => {
    response.writeHead(404).end();
  });
  const sockets = new Set<WebSocket>();
  // Requests are small (the largest is the page snapshot script); results flow the other way and are not limited.
  const wss = new WebSocketServer({ noServer: true, maxPayload: 8 * 1024 * 1024 });

  http.on('upgrade', (request, socket, head) => {
    if (!pathCarriesToken(request.url, token)) {
      socket.end('HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n');
      return;
    }
    wss.handleUpgrade(request, socket, head, (ws) => {
      sockets.add(ws);
      const connection = bridge.connect({
        send: (message) => {
          if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(message));
        },
      });
      ws.on('message', (data, isBinary) => {
        if (!isBinary) connection.receive(data.toString());
      });
      const end = () => {
        sockets.delete(ws);
        connection.close();
      };
      ws.on('close', end);
      ws.on('error', end);
    });
  });

  const port = await new Promise<number>((resolve, reject) => {
    http.once('error', reject);
    // Port 0: the system picks a free one. Loopback only: never reachable from another machine.
    http.listen(0, HOST, () => resolve((http.address() as AddressInfo).port));
  });

  return {
    url: `ws://${HOST}:${port}/${token}`,
    port,
    close: async () => {
      for (const ws of sockets) ws.terminate();
      sockets.clear();
      await new Promise<void>((resolve) => wss.close(() => http.close(() => resolve())));
    },
  };
}
