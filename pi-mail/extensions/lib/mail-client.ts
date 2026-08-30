/**
 * MailClient — Unix-socket client for the pi-mail daemon.
 *
 * Wraps a newline-delimited JSON connection. The daemon can push
 * { type: "ping" } (respond with pong) and { type: "new_mail", message } at
 * any time; all other messages are responses matched by _reqId.
 *
 * Extracted from index.ts so the extension entry stays focused on registration.
 */

import type { ExtensionContext } from "@earendil-works/pi-coding-agent";
import * as net from "node:net";
import { basename } from "node:path";

export interface MailMessage {
  id: string;
  fromId: string;
  fromName: string;
  subject: string;
  body: string;
  timestamp: number;
  read: boolean;
  broadcast?: boolean;
  /** If true, the receiving agent should start a fresh session before acting on this mail. */
  newSession?: boolean;
}

export interface AgentInfo {
  agentId: string;
  agentName: string;
  registeredAt: number;
  status?: string;
  contextPct: number | null;
  /** Working directory of the agent process, used to group agents by project. */
  cwd?: string;
  /** Active model identifier, e.g. "anthropic/claude-sonnet-4". */
  model?: string;
}

/** Group key for listing agents: the basename of the agent's cwd. */
export function projectGroupKey(cwd?: string): string {
  if (!cwd) return "(no project)";
  return basename(cwd) || cwd;
}

/**
 * Wraps a Unix socket connection to the daemon.
 * Protocol: newline-delimited JSON.
 *
 * The daemon can send two push message types at any time:
 *   { type: "ping" }              — respond immediately with { type: "pong" }
 *   { type: "new_mail", message } — pushed when new mail arrives
 *
 * All other daemon messages are responses to client requests.
 * Requests are sequential (one inflight at a time); responses arrive in order.
 */
export class MailClient {
  private socket: net.Socket | null = null;
  private buf = "";
  // Map-based pending requests keyed by _reqId — immune to queue corruption
  private pending = new Map<
    number,
    { resolve: (v: unknown) => void; reject: (e: Error) => void; timer: ReturnType<typeof setTimeout> }
  >();
  private nextReqId = 1;

  /** Called when a push notification arrives */
  onNewMail: ((msg: MailMessage) => void) | null = null;
  /** Called when the daemon pushes a model switch (set_model) for a task. */
  onSetModel: ((model: string) => void) | null = null;
  /** Called when the socket closes (cleanly or on error) */
  onDisconnect: (() => void) | null = null;

  get connected(): boolean {
    return this.socket != null && !this.socket.destroyed;
  }

  async connect(socketPath: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const sock = net.createConnection(socketPath);
      sock.setEncoding("utf8");

      sock.once("connect", () => {
        this.socket = sock;
        resolve();
      });

      sock.once("error", (err) => {
        if (!this.socket) reject(err);
      });

      sock.on("data", (chunk: string) => {
        this.buf += chunk;
        const lines = this.buf.split("\n");
        this.buf = lines.pop() ?? "";

        for (const line of lines) {
          if (!line.trim()) continue;
          let msg: { type: string; _reqId?: number; [k: string]: unknown };
          try {
            msg = JSON.parse(line);
          } catch {
            continue;
          }

          if (msg.type === "ping") {
            this.rawWrite({ type: "pong" });
            continue;
          }

          if (msg.type === "new_mail") {
            // Run async so the socket data handler is never blocked by callback work
            setImmediate(() => this.onNewMail?.(msg.message as MailMessage));
            continue;
          }

          // Daemon → agent push: switch to the model required by an assigned
          // task. Best-effort (the callback resolves the model and switches;
          // failures are swallowed by the consumer).
          if (msg.type === "set_model") {
            setImmediate(() => this.onSetModel?.(String(msg.model ?? "")));
            continue;
          }

          // Match response to pending request by _reqId
          if (msg._reqId != null) {
            const entry = this.pending.get(msg._reqId as number);
            if (entry) {
              clearTimeout(entry.timer);
              this.pending.delete(msg._reqId as number);
              entry.resolve(msg);
            }
            // Unknown _reqId (e.g. late response after timeout) — discard safely
            continue;
          }

          // Legacy fallback: no _reqId, pick the oldest pending entry
          const first = this.pending.entries().next();
          if (!first.done) {
            const [id, entry] = first.value;
            clearTimeout(entry.timer);
            this.pending.delete(id);
            entry.resolve(msg);
          }
        }
      });

      sock.on("close", () => {
        this.socket = null;
        this.drainPending("disconnected");
        this.onDisconnect?.();
      });

      sock.on("error", (err) => {
        // Drain pending entries immediately on socket error; close event follows.
        this.drainPending(err.message);
      });
    });
  }

  private drainPending(reason: string): void {
    for (const [, entry] of this.pending) {
      clearTimeout(entry.timer);
      entry.reject(new Error(reason));
    }
    this.pending.clear();
  }

  // Outgoing messages that couldn't be written yet (socket unavailable / reconnecting).
  // Flushed automatically once a connection is (re)established.
  private writeQueue: string[] = [];

  flushWriteQueue(): void {
    if (!this.socket || this.socket.destroyed) return;
    const items = this.writeQueue.splice(0);
    for (const data of items) {
      try {
        this.socket.write(data);
      } catch {}
    }
  }

  private rawWrite(msg: unknown, onWriteError?: (err: Error) => void): void {
    const data = JSON.stringify(msg) + "\n";
    if (this.socket && !this.socket.destroyed) {
      try {
        this.socket.write(data, (err) => {
          if (err) onWriteError?.(err);
        });
      } catch (e) {
        onWriteError?.(e instanceof Error ? e : new Error(String(e)));
      }
    } else {
      // Socket temporarily unavailable — buffer and retry after reconnect.
      // Only buffer if there's a chance of reconnect (i.e. not a fire-and-forget
      // that already has nowhere to go); callers that need reliability pass onWriteError.
      if (!onWriteError) {
        this.writeQueue.push(data);
      } else {
        onWriteError(new Error("socket not available"));
      }
    }
  }

  /** Fire-and-forget: buffers the message if not connected; sent on reconnect. */
  fire(msg: unknown): void {
    this.rawWrite(msg); // no onWriteError → buffered automatically
  }

  async request<T = { type: string; [k: string]: unknown }>(
    msg: Record<string, unknown>,
    timeoutMs = 12_000,
  ): Promise<T> {
    const reqId = this.nextReqId++;
    const tagged = { ...msg, _reqId: reqId };
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        if (this.pending.delete(reqId)) {
          reject(new Error(`Request timed out (type=${msg.type})`));
          // Don't destroy the socket — other requests may still be in flight.
        }
      }, timeoutMs);
      this.pending.set(reqId, { resolve: resolve as (v: unknown) => void, reject, timer });
      // Buffer rather than reject when socket is temporarily gone;
      // the message will be flushed once the connection is restored.
      this.rawWrite(tagged);
    });
  }

  disconnect(): void {
    this.socket?.destroy();
    this.socket = null;
  }
}

// Keep the ExtensionContext type import referenced (used by callers that pass
// context into helpers alongside MailClient-based tooling).
export type { ExtensionContext };
