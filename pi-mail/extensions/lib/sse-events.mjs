/**
 * SSE event bus — a global EventEmitter singleton that the daemon's HTTP
 * /events SSE endpoint subscribes to. Board ops, mail delivery, and agent
 * registration emit events here so connected web UI clients receive push
 * notifications instead of relying solely on the 3s poll.
 */
import { EventEmitter } from "node:events";

export const sseEvents = new EventEmitter();
sseEvents.setMaxListeners(100); // one per SSE client

/** Convenience: emit a simple event with an optional detail.
 *  Never throws — SSE is purely cosmetic; core ops must not fail. */
export function notifySSE(type, detail) {
  try { sseEvents.emit("event", { type, detail }); } catch { /* best-effort */ }
}
