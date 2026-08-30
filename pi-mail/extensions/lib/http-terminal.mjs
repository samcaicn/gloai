/**
 * WebSocket terminal: stream a spawned agent's tmux session to the browser.
 * Extracted from http.mjs.
 *
 * The browser opens a WebSocket at /api/spawn/terminal?name=<session>. The
 * daemon attaches to the tmux session via `script -qec 'tmux attach -t <name>'`
 * which gives a real PTY pair; stdout bytes are forwarded to the WS as binary
 * frames, and incoming WS bytes are written to the PTY stdin (so the browser
 * can type into the live pi TUI). Only sessions the daemon spawned are
 * attachable (defence-in-depth: the picker/stop already gate on tracking).
 *
 * The WS protocol is the minimal one: raw bytes both directions. The browser
 * uses xterm.js to render. No subprotocol, no JSON framing — keeps it cheap.
 */
import crypto from "node:crypto";
import { spawn } from "node:child_process";
import { log, shellQuote } from "./core.mjs";
import { spawnRegistry, safeSessionName, tmuxSessionExists } from "./spawn.mjs";

/** Register the /api/spawn/terminal WebSocket upgrade handler on httpServer. */
export function attachTerminalUpgrade(httpServer) {
  httpServer.on("upgrade", (req, socket) => {
    const url = new URL(req.url, "http://localhost");
    if (url.pathname !== "/api/spawn/terminal") {
      socket.destroy();
      return;
    }
    const name = safeSessionName(url.searchParams.get("name") || "");
    if (!name || !spawnRegistry.sessions[name]) {
      socket.write("HTTP/1.1 403 Forbidden\r\n\r\n");
      socket.destroy();
      return;
    }
    if (!tmuxSessionExists(name)) {
      socket.write("HTTP/1.1 404 Not Found\r\n\r\n");
      socket.destroy();
      return;
    }
    // Minimal RFC6455 server handshake (no deps). The browser speaks standard WS.
    const key = req.headers["sec-websocket-key"];
    if (!key) { socket.destroy(); return; }
    const accept = crypto.createHash("sha1").update(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").digest("base64");
    socket.write(
      "HTTP/1.1 101 Switching Protocols\r\n" +
      "Upgrade: websocket\r\n" +
      "Connection: Upgrade\r\n" +
      `Sec-WebSocket-Accept: ${accept}\r\n` +
      "\r\n"
    );

    // Attach to the tmux session through a PTY (script -qec '<cmd>' /dev/null).
    // -q: quiet (no "Script started" header). -e <cmd>: run cmd under a PTY.
    const child = spawn("script", ["-qec", `tmux attach -t ${shellQuote(name)}`, "/dev/null"], {
      stdio: ["pipe", "pipe", "pipe"],
    });
    log(`Terminal WS attached to '${name}'`);

    let closed = false;
    const cleanup = () => {
      if (closed) return;
      closed = true;
      try { child.kill(); } catch {}
      try { socket.destroy(); } catch {}
    };

    // tmux stdout → WS: frame as binary (opcode 2).
    const sendFrame = (buf) => {
      if (closed || socket.destroyed) return;
      // Frame: FIN(1) + opcode(2) + mask(0) + len + payload. Server→client is
      // unmasked per RFC6455.
      let header;
      const len = buf.length;
      if (len < 126) {
        header = Buffer.alloc(2);
        header[0] = 0x82; // FIN + binary
        header[1] = len;
      } else if (len < 65536) {
        header = Buffer.alloc(4);
        header[0] = 0x82;
        header[1] = 126;
        header.writeUInt16BE(len, 2);
      } else {
        header = Buffer.alloc(10);
        header[0] = 0x82;
        header[1] = 127;
        header.writeBigUInt64BE(BigInt(len), 2);
      }
      socket.write(Buffer.concat([header, buf]));
    };
    child.stdout.on("data", (b) => sendFrame(b));
    child.stderr.on("data", (b) => sendFrame(b));
    child.on("exit", () => {
      // Send a close frame and tear down.
      if (!closed) { try { socket.write(Buffer.from([0x88, 0x00])); } catch {} }
      cleanup();
      log(`Terminal WS detached from '${name}' (tmux attach exited)`);
    });

    // WS → tmux stdin: decode incoming frames (client→server is masked).
    let inBuf = Buffer.alloc(0);
    socket.on("data", (chunk) => {
      inBuf = Buffer.concat([inBuf, chunk]);
      while (inBuf.length >= 2) {
        const b0 = inBuf[0];
        const b1 = inBuf[1];
        const opcode = b0 & 0x0f;
        const masked = (b1 & 0x80) !== 0;
        let len = b1 & 0x7f;
        let idx = 2;
        if (len === 126) { if (inBuf.length < 4) return; len = inBuf.readUInt16BE(2); idx = 4; }
        else if (len === 127) { if (inBuf.length < 10) return; len = Number(inBuf.readBigUInt64BE(2)); idx = 10; }
        let mask = Buffer.alloc(0);
        if (masked) { if (inBuf.length < idx + 4) return; mask = inBuf.subarray(idx, idx + 4); idx += 4; }
        if (inBuf.length < idx + len) return;
        let payload = inBuf.subarray(idx, idx + len);
        if (masked) {
          const out = Buffer.allocUnsafe(len);
          for (let i = 0; i < len; i++) out[i] = payload[i] ^ mask[i % 4];
          payload = out;
        }
        inBuf = inBuf.subarray(idx + len);
        if (opcode === 0x8) { cleanup(); return; } // close
        if (opcode === 0x1 || opcode === 0x2 || opcode === 0x0) { // text / binary / continuation
          if (child.stdin && !child.stdin.destroyed) child.stdin.write(payload);
        }
        if (opcode === 0x9) { // ping → pong
          const pong = Buffer.alloc(2 + payload.length);
          pong[0] = 0x8a; pong[1] = payload.length; payload.copy(pong, 2);
          try { socket.write(pong); } catch {}
        }
      }
    });
    socket.on("close", cleanup);
    socket.on("error", cleanup);
  });
}
