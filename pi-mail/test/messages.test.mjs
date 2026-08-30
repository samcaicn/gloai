// Tests for the lean paginated/filtered message endpoint (task 312e01b3).
//
// Background: /api/state used to ship the ENTIRE messageLog (unbounded history)
// on every 3s poll. It now returns a { total, unread } summary instead, and a
// new GET /api/messages endpoint serves pages of the history (newest-first,
// cursor-paginated) with filters (archived, to/from/involves). This test boots
// an isolated daemon, populates the history via the socket + HTTP send paths,
// then asserts:
//   - /api/state no longer dumps the full messages array (and its board
//     excludes the archive pool by default),
//   - /api/messages paginates with a stable cursor (no overlap between pages),
//   - the to / from / involves / archived filters work and `total` reflects
//     the filtered set.
//
// Run: npm test   (node:test runner)

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { spawn as pSpawn } from "node:child_process";
import * as net from "node:net";
import * as fs from "node:fs";
import * as path from "node:path";
import * as os from "node:os";
import * as crypto from "node:crypto";

const REPO = path.resolve(import.meta.dirname, "..");
const DAEMON = path.join(REPO, "extensions", "daemon.mjs");

let tmpHome, proc, sockPath, port, alice, bob;
process.on("exit", () => { try { if (proc) proc.kill("SIGKILL"); } catch {} });

function freePort() {
  return new Promise((resolve) => {
    const s = net.createServer();
    s.listen(0, "127.0.0.1", () => { const p = s.address().port; s.close(() => resolve(p)); });
  });
}

function startDaemon() {
  return new Promise((resolve, reject) => {
    proc = pSpawn(process.execPath, [DAEMON], {
      env: { ...process.env, HOME: tmpHome, PI_MAIL_UI_HOST: "127.0.0.1", PI_MAIL_UI_PORT: String(port) },
      stdio: ["ignore", "pipe", "pipe"],
    });
    let stderr = "";
    proc.stderr.on("data", (c) => { stderr += c.toString(); });
    proc.on("exit", (code, sig) => {
      if (!proc.__stopped) console.error("daemon exited unexpectedly", code, sig, stderr.slice(-500));
    });
    const tryConnect = (retries = 0) => {
      const s = net.createConnection(sockPath);
      s.once("connect", () => { s.destroy(); resolve(); });
      s.once("error", () => {
        if (retries > 200) return reject(new Error("daemon socket never appeared\n" + stderr));
        setTimeout(() => tryConnect(retries + 1), 30);
      });
    };
    tryConnect();
  });
}

function stopDaemon() {
  if (!proc) return Promise.resolve();
  proc.__stopped = true;
  return new Promise((r) => {
    proc.once("exit", () => { proc = null; r(); });
    proc.kill("SIGTERM");
    setTimeout(() => { if (proc) { proc.kill("SIGKILL"); proc = null; } r(); }, 3000);
  });
}

// Minimal newline-delimited JSON socket client (handles ping/pong).
function mkClient(name) {
  return new Promise((resolve, reject) => {
    const s = net.createConnection(sockPath);
    s.setEncoding("utf8");
    let buf = "";
    let nextId = 1;
    const pending = new Map();
    const agentId = crypto.randomUUID();
    s.on("data", (chunk) => {
      buf += chunk;
      const lines = buf.split("\n");
      buf = lines.pop();
      for (const line of lines) {
        if (!line.trim()) continue;
        let m; try { m = JSON.parse(line); } catch { continue; }
        if (m.type === "ping") { s.write(JSON.stringify({ type: "pong" }) + "\n"); continue; }
        if (m._reqId != null && pending.has(m._reqId)) {
          const e = pending.get(m._reqId); clearTimeout(e.t); pending.delete(m._reqId); e.res(m);
        }
      }
    });
    s.once("connect", () =>
      resolve({
        agentId,
        agentName: name,
        request(msg, timeoutMs = 5000) {
          const id = nextId++;
          return new Promise((res, rej) => {
            const t = setTimeout(() => { pending.delete(id); rej(new Error("timeout: " + msg.type)); }, timeoutMs);
            pending.set(id, { res, rej, t });
            s.write(JSON.stringify({ ...msg, _reqId: id }) + "\n");
          });
        },
        close() { s.destroy(); },
      })
    );
    s.once("error", reject);
  });
}

const httpGet = (p) => fetch(`http://127.0.0.1:${port}${p}`).then((r) => r.json());
const httpPost = (p, body) =>
  fetch(`http://127.0.0.1:${port}${p}`, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  }).then((r) => r.json());

before(async () => {
  tmpHome = fs.mkdtempSync(path.join(os.tmpdir(), "pimail-msg-"));
  sockPath = path.join(tmpHome, ".pi", "agent", "mail-daemon.sock");
  port = await freePort();
  await startDaemon();

  // Register two agents to create cross-agent traffic.
  alice = await mkClient("alice");
  await alice.request({ type: "register", agentId: alice.agentId, agentName: "alice", cwd: tmpHome });
  bob = await mkClient("bob");
  await bob.request({ type: "register", agentId: bob.agentId, agentName: "bob", cwd: tmpHome });

  // Populate history: 3 alice→human, 1 bob→human, 1 alice→bob, 1 human→alice.
  for (let i = 0; i < 3; i++) {
    await alice.request({ type: "send", to: "human", subject: `alice->human ${i}`, body: `body ${i}` });
  }
  await bob.request({ type: "send", to: "human", subject: "bob->human", body: "hi" });
  await alice.request({ type: "send", to: "bob", subject: "alice->bob", body: " hey" });
  await httpPost("/api/send", { to: "alice", subject: "human->alice", body: "hello alice" });
});

after(async () => {
  alice?.close(); bob?.close();
  await stopDaemon();
  fs.rmSync(tmpHome, { recursive: true, force: true });
});

// ── /api/state is lean (no full message dump) ───────────────────────────────

test("/api/state returns a message summary, not the full array", async () => {
  const st = await httpGet("/api/state");
  assert.ok(st.messages && typeof st.messages === "object", "messages is an object summary");
  assert.ok(!Array.isArray(st.messages), "messages is NOT the full array anymore");
  assert.equal(typeof st.messages.total, "number");
  assert.equal(typeof st.messages.unread, "number");
  // 6 messages were sent in setup.
  assert.equal(st.messages.total, 6);
  // 4 of them are addressed to the human (3 alice→human + 1 bob→human), none archived.
  assert.equal(st.messages.unread, 4);
});

test("/api/state board excludes the archive pool by default", async () => {
  // Create a task and archive it.
  const created = await httpPost("/api/board/create", { summary: "to-be-archived" });
  const id = created.taskId;
  await httpPost("/api/board/move", { taskId: id, column: "archive" });
  const st = await httpGet("/api/state");
  const archived = (st.board.tasks ?? []).filter((t) => t.location === "archive");
  assert.equal(archived.length, 0, "archived tasks must NOT be in /api/state board by default");
  // ...but the dedicated /api/board endpoint with includeArchived reveals it.
  const full = await httpGet("/api/board?includeArchived=true");
  assert.ok((full.tasks ?? []).some((t) => t.location === "archive"), "archive is reachable via /api/board?includeArchived=true");
});

// ── /api/messages pagination ────────────────────────────────────────────────

test("/api/messages returns a newest-first page with cursor metadata", async () => {
  const page = await httpGet("/api/messages?limit=2");
  assert.equal(page.messages.length, 2);
  assert.equal(page.total, 6, "total reflects the whole filtered set");
  assert.ok(page.hasMore, "more pages available");
  assert.ok(typeof page.nextCursor === "string" && page.nextCursor.length > 0, "nextCursor is set");
  // Newest-first: timestamps non-increasing.
  assert.ok(page.messages[0].timestamp >= page.messages[1].timestamp);
});

test("cursor pagination returns disjoint older pages (no overlap)", async () => {
  const p1 = await httpGet("/api/messages?limit=2");
  const p2 = await httpGet(`/api/messages?limit=2&cursor=${encodeURIComponent(p1.nextCursor)}`);
  assert.equal(p1.messages.length, 2);
  assert.equal(p2.messages.length, 2);
  const ids1 = new Set(p1.messages.map((m) => m.id));
  const ids2 = new Set(p2.messages.map((m) => m.id));
  for (const id of ids2) assert.ok(!ids1.has(id), `page 2 overlaps page 1 at ${id}`);
  // Page 2 items are older-or-equal to the last page-1 item.
  const last = p1.messages[1];
  for (const m of p2.messages) {
    assert.ok(m.timestamp < last.timestamp || (m.timestamp === last.timestamp && m.id < last.id),
      "page 2 item is strictly older than the page 1 cursor item");
  }
});

test("walking all pages via cursor reconstructs the full set", async () => {
  const seen = new Set();
  let cursor = "";
  let guard = 0;
  do {
    const url = "/api/messages?limit=2" + (cursor ? `&cursor=${encodeURIComponent(cursor)}` : "");
    const page = await httpGet(url);
    for (const m of page.messages) seen.add(m.id);
    cursor = page.nextCursor || "";
    assert.ok(++guard < 50, "pagination did not terminate");
  } while (cursor);
  assert.equal(seen.size, 6, "reconstructed all 6 messages across pages");
});

test("limit is clamped to a safe maximum", async () => {
  const page = await httpGet("/api/messages?limit=99999");
  assert.ok(page.messages.length <= 200, "limit clamped to MAX_PAGE_SIZE (200)");
  assert.equal(page.total, 6);
});

// ── /api/messages filtering ─────────────────────────────────────────────────

test("to= filter returns only mail addressed to that agent", async () => {
  const page = await httpGet("/api/messages?to=alice");
  // Only the human→alice message is addressed to alice.
  assert.equal(page.total, 1);
  assert.equal(page.messages[0].subject, "human->alice");
  assert.equal(page.messages[0].toName, "alice");
});

test("from= filter returns only mail sent by that agent", async () => {
  const page = await httpGet("/api/messages?from=alice");
  // alice→human (3) + alice→bob (1) = 4.
  assert.equal(page.total, 4);
  for (const m of page.messages) assert.equal(m.fromName, "alice");
});

test("involves= filter returns mail either sent by or addressed to the agent", async () => {
  const page = await httpGet("/api/messages?involves=alice");
  // alice→human (3) + alice→bob (1) + human→alice (1) = 5.
  assert.equal(page.total, 5);
  for (const m of page.messages) {
    assert.ok(m.fromName === "alice" || m.toName === "alice", "alice is sender or recipient");
  }
});

test("archived= filter partitions the human inbox", async () => {
  // Archive one human-addressed message (alice→human 0).
  const all = await httpGet("/api/messages?to=human&archived=exclude");
  const target = all.messages.find((m) => m.subject === "alice->human 0");
  assert.ok(target, "fixture message present");
  const r = await httpPost("/api/archive", { id: target.id });
  assert.equal(r.ok, true);

  const excl = await httpGet("/api/messages?to=human&archived=exclude");
  const only = await httpGet("/api/messages?to=human&archived=only");
  // Human inbox: 4 total, 1 now archived → 3 excluded, 1 only.
  assert.equal(excl.total, 3, "archived=exclude hides the archived one");
  assert.equal(only.total, 1, "archived=only shows just the archived one");
  assert.equal(only.messages[0].id, target.id);
  // Default (no archived param) includes both.
  const def = await httpGet("/api/messages?to=human");
  assert.equal(def.total, 4, "default includes archived + non-archived");
});

test("total reflects the filtered set, not the whole log", async () => {
  const page = await httpGet("/api/messages?from=bob");
  assert.equal(page.total, 1, "only bob→human");
  assert.equal(page.messages[0].fromName, "bob");
});

// ── Infinite-scroll + live-refresh contract (task 276b3643) ───────────────────
// The mailbox UI loads page 1, appends older pages on scroll, and on the 3s
// poll refresh re-fetches ONLY page 1 (prepending new mail) while keeping the
// accumulated older pages and the stored next-page cursor. That design is only
// correct if a cursor obtained before new mail arrived still points at the
// strictly-older page afterwards — i.e. new mail never creates a gap or a
// duplicate relative to the already-loaded window. This test pins that.
test("a cursor stays valid after new mail arrives (infinite-scroll live-refresh)", async () => {
  // 1. Load page 1 + one load-more → accumulate the 4 newest of the original set.
  const p1 = await httpGet("/api/messages?limit=2");
  const p2 = await httpGet(`/api/messages?limit=2&cursor=${encodeURIComponent(p1.nextCursor)}`);
  const acc = [...p1.messages, ...p2.messages]; // newest-first [M1..M4]
  const oldCursor = p2.nextCursor;              // boundary after M4
  assert.equal(acc.length, 4);

  // 2. New mail arrives (2 messages with the newest timestamps).
  for (let i = 0; i < 2; i++) {
    await alice.request({ type: "send", to: "human", subject: `new ${i}`, body: "fresh" });
  }

  // 3. Poll refresh: re-fetch page 1 → the 2 new messages are now the newest.
  const fresh = await httpGet("/api/messages?limit=2");
  const have = new Set(acc.map((m) => m.id));
  const prepend = fresh.messages.filter((m) => !have.has(m.id));
  assert.equal(prepend.length, 2, "page 1 now holds the 2 new messages");
  const joined = [...prepend, ...acc]; // [new, new, M1..M4]

  // 4. The OLD cursor still returns a strictly-older page with NO dupes.
  const older = await httpGet(`/api/messages?limit=2&cursor=${encodeURIComponent(oldCursor)}`);
  const seen = new Set(joined.map((m) => m.id));
  for (const m of older.messages) {
    assert.ok(!seen.has(m.id), `cursor page duplicates an already-loaded message (${m.id})`);
  }

  // 5. joined + cursor page reconstructs the full 8-message set (no gaps).
  for (const m of older.messages) seen.add(m.id);
  assert.equal(seen.size, 8, "6 original + 2 new = 8 contiguous messages, no gaps");

  // 6. Contiguity: every cursor-page item is strictly older than the join tail.
  const tail = joined[joined.length - 1];
  for (const m of older.messages) {
    assert.ok(m.timestamp < tail.timestamp || (m.timestamp === tail.timestamp && m.id < tail.id),
      "cursor-page item is strictly older than the accumulated tail");
  }
});
