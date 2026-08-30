"use strict";
"use strict";
const HUMAN_ID = "00000000-0000-0000-0000-000000000000";
let state = { agents: [], messages: [], board: null, human: { agentId: HUMAN_ID, agentName: "human" }, now: Date.now() };
let currentTab = "agents";
let historyAgentId = "";        // selected agent in History tab
let compose = { to: "", subject: "", body: "", newSession: false }; // sticky compose draft
// Board UI state that must survive re-renders (poll every 3s)
let boardUi = {
  taskModalId: null,            // task whose detail modal is open
  freshSession: true,           // newSession flag used when assigning
  newTask: { summary: "", description: "", column: "", level: "task", backlog: false, priority: "" },
  draftComments: {},            // taskId -> comment draft
  colsDraft: null,              // unsaved column edits (Settings tab)
  showArchive: false,           // status filter: show done (archived) tasks
  archiveTasks: null,           // cached archive tasks (null = not loaded); fetched on demand from /api/board?location=archive since /api/state no longer ships them
  groupFilter: "__all",         // "__all" = every group, else a project group
  dragTaskId: null,            // task id being dragged (DnD); suppresses poll re-render
  dragScroll: null,            // active drag edge-auto-scroll {raf, x, y} (rAF handle + last client coords)
};
// Mailbox UI state (Outlook-style conversation view) — survives re-renders.
let mailboxUi = {
  selectedKey: "",            // conversation key (agent id, or sorted "a|b" pair for inter-agent)
  showInterAgent: false,      // toggle: also list agent↔agent conversations
  folder: "all",              // nav-pane folder: all | inbox | sent | archive (drives the /api/messages filter)
  messages: [],               // accumulated messages (newest-first); grown via infinite scroll
  cursor: null,               // next-page cursor for loading older messages (null = no more)
  hasMore: false,             // whether more pages are available (mirrors cursor != null)
  loading: false,             // first-page / poll-refresh fetch in flight
  loadingMore: false,         // infinite-scroll append fetch in flight
  error: null,                // last fetch error message (for error state + retry)
};
// History tab message cache. Fetches from /api/messages?to=<agent> so the tab
// no longer depends on the full log being shipped in /api/state.
let historyUi = { messages: [], loading: false };
let pollTimer = null;
let lastSig = null;

const $ = (sel) => document.querySelector(sel);
const main = $("#main");
const el = (tag, cls, txt) => { const e = document.createElement(tag); if (cls) e.className = cls; if (txt != null) e.textContent = txt; return e; };

// ── Theme (dark / light / system) ─────────────────────────────────────────────
// Persisted via localStorage("pi-mail-theme"): "dark" | "light" | "system".
// Default is dark (no stored value → dark, no data-theme attribute), preserving
// the existing UX. "system" follows the OS prefers-color-scheme and updates
// live when the OS theme changes. The toggle (#theme-toggle) cycles
// dark 🌙 → light ☀️ → system 🖥️ → dark.
const THEME_KEY = "pi-mail-theme";
const THEME_ICONS = { dark: "🌙", light: "☀️", system: "🖥️" };
/** Resolve a stored theme to the effective light/dark value. */
function resolvedTheme(theme) {
  if (theme === "light") return "light";
  if (theme === "system")
    return (window.matchMedia && matchMedia("(prefers-color-scheme: light)").matches) ? "light" : "dark";
  return "dark"; // default
}
function currentTheme() {
  const t = localStorage.getItem(THEME_KEY);
  return (t === "light" || t === "system") ? t : "dark";
}
function applyTheme(theme) {
  const light = resolvedTheme(theme) === "light";
  if (light) document.documentElement.setAttribute("data-theme", "light");
  else document.documentElement.removeAttribute("data-theme");
  const btn = document.getElementById("theme-toggle");
  if (btn) {
    btn.textContent = THEME_ICONS[theme] || THEME_ICONS.dark;
    btn.title = "Theme: " + theme + " (click to switch)";
    btn.setAttribute("aria-label", "Toggle theme (current: " + theme + ")");
  }
}
function toggleTheme() {
  const order = ["dark", "light", "system"];
  const next = order[(order.indexOf(currentTheme()) + 1) % order.length];
  if (next === "dark") localStorage.removeItem(THEME_KEY);
  else localStorage.setItem(THEME_KEY, next);
  applyTheme(next);
}
// When in system mode, react to OS theme changes without a reload.
if (window.matchMedia) {
  matchMedia("(prefers-color-scheme: light)").addEventListener("change", () => {
    if (currentTheme() === "system") applyTheme("system");
  });
}
// Apply the persisted theme as early as possible to avoid a flash of the wrong
// theme. This runs once at script load (ui-core.js is the first UI script).
applyTheme(currentTheme());

function fmtTime(ts) {
  if (!ts) return "—";
  const d = new Date(ts);
  return d.toLocaleString();
}
function fmtRelative(ts) {
  if (!ts) return "—";
  const diff = Date.now() - new Date(ts).getTime();
  const secs = Math.floor(diff / 1000);
  if (secs < 0) return fmtTime(ts).split(",")[0]; // future date
  if (secs < 60) return "just now";
  const mins = Math.floor(secs / 60);
  if (mins < 60) return mins + "m ago";
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return hrs + "h ago";
  const days = Math.floor(hrs / 24);
  if (days < 7) return days + "d ago";
  return fmtTime(ts).split(",")[0]; // fallback to date
}
function fmtUptime(registeredAt, now) {
  if (!registeredAt) return "—";
  const s = Math.max(0, Math.round((now - registeredAt) / 1000));
  if (s < 60) return s + "s";
  if (s < 3600) return Math.round(s / 60) + "m";
  if (s < 86400) return Math.round(s / 3600) + "h";
  return Math.round(s / 86400) + "d";
}
function esc(s) { return String(s ?? ""); }
function shortId(id) { return id ? id.slice(0, 8) : ""; }
function projectOf(cwd) {
  if (!cwd) return "(no project)";
  const parts = cwd.replace(/\/+$/, "").split("/");
  return parts[parts.length - 1] || cwd;
}
/** Project group for a board task: the stamped group wins, else derived from
 *  the assignee's cwd via state.agents, else null (ungrouped/no-project). */
function taskGroup(t) {
  if (t.group) return t.group;
  if (t.assignee) {
    const a = (state.agents ?? []).find(x => x.agentName === t.assignee);
    if (a?.cwd) return projectOf(a.cwd);
  }
  return null;
}
/** Whether a task passes the current group filter (boardUi.groupFilter). */
function groupVisible(t) {
  if (boardUi.groupFilter === "__all") return true;
  return (taskGroup(t) ?? "(no project)") === boardUi.groupFilter;
}
/** All distinct groups present across the current board, sorted, plus a
 *  leading "(no project)" entry for ungrouped tasks. */
function boardGroups(board) {
  const set = new Set();
  for (const t of (board?.tasks ?? [])) {
    const g = taskGroup(t);
    set.add(g ?? "(no project)");
  }
  return [...set].sort((a, b) => a.localeCompare(b));
}
function ctxClass(pct) {
  if (pct == null) return "";
  if (pct >= 80) return "high";
  if (pct >= 50) return "mid";
  return "low";
}
function toast(msg, isErr) {
  const t = $("#toast");
  t.textContent = msg;
  t.classList.toggle("err", !!isErr);
  t.classList.remove("hidden");
  clearTimeout(toast._t);
  toast._t = setTimeout(() => t.classList.add("hidden"), 3500);
}

// ── Data fetch ──────────────────────────────────────────────────────────────

/** Fetch the archive (done) pool on demand. /api/state no longer ships
 *  archived tasks (task 312e01b3), so the "show done" panel loads them from
 *  /api/board?location=archive when toggled on. */
async function loadArchive() {
  try {
    const r = await fetch("/api/board?location=archive", { cache: "no-store" });
    if (!r.ok) throw new Error("HTTP " + r.status);
    const b = await r.json();
    boardUi.archiveTasks = b.tasks ?? [];
  } catch {
    boardUi.archiveTasks = [];
  }
}

/** Fetch a page of message history from the paginated /api/messages endpoint
 *  (task 312e01b3). Returns { messages, nextCursor, hasMore, total }. */
async function fetchMessages(opts = {}) {
  const params = new URLSearchParams();
  if (opts.limit) params.set("limit", String(opts.limit));
  if (opts.cursor) params.set("cursor", opts.cursor);
  if (opts.archived) params.set("archived", opts.archived);
  if (opts.to) params.set("to", opts.to);
  if (opts.from) params.set("from", opts.from);
  if (opts.involves) params.set("involves", opts.involves);
  const qs = params.toString();
  const r = await fetch("/api/messages" + (qs ? "?" + qs : ""), { cache: "no-store" });
  if (!r.ok) throw new Error("HTTP " + r.status);
  return r.json();
}

/** /api/messages filter params for a mailbox nav-pane folder. The folders
 *  map onto server-side filters; the conversation-grouped message list and
 *  infinite scroll work uniformly on whatever filtered set is returned. */
function mailboxFolderParams(folder) {
  switch (folder) {
    case "inbox":   return { to: "human", archived: "exclude" };
    case "sent":    return { from: "human" };
    case "archive": return { to: "human", archived: "only" };
    default:        return {}; // "all": no filter (archived included by default)
  }
}

/** Switch the mailbox nav-pane folder. A different folder is a different
 *  dataset, so the cache + scroll position are reset and the first page is
 *  re-fetched. The selected conversation is cleared too. */
function setMailboxFolder(folder) {
  if (mailboxUi.folder === folder) return;
  mailboxUi.folder = folder;
  mailboxUi.messages = [];
  mailboxUi.cursor = null;
  mailboxUi.hasMore = false;
  mailboxUi.selectedKey = "";
  mailboxUi.error = null;
  const p = loadMailboxPage();
  render();          // show the nav highlight + loading state immediately
  p.then(render);   // then render the new folder's first page
}

/** Fetch the first page of messages for the mailbox (newest-first), scoped to
 *  the current nav-pane folder. On the initial load this seeds the cache; on
 *  a 3s poll refresh it only PREPENDS newly-arrived messages (matched by id)
 *  so infinite-scroll accumulation below is never clobbered. The next-page
 *  cursor is preserved across refreshes — it is a stable (ts,id) boundary, so
 *  even after new mail arrives it still points to the correct older page
 *  (no gaps, no dupes). */
async function loadMailboxPage() {
  if (mailboxUi.loading || mailboxUi.loadingMore) return;
  mailboxUi.loading = true;
  mailboxUi.error = null;
  try {
    const page = await fetchMessages({ limit: 50, ...mailboxFolderParams(mailboxUi.folder) });
    const fresh = page.messages || [];
    if (!mailboxUi.messages.length) {
      // First load: seed the cache.
      mailboxUi.messages = fresh;
      mailboxUi.cursor = page.nextCursor || null;
      mailboxUi.hasMore = !!page.hasMore;
    } else {
      // Poll refresh: prepend any genuinely-new messages (those whose id we
      // don't already have). Page 1 is always the newest window, so any id in
      // it that we lack is newer than our current head. Leave the cursor and
      // accumulated older pages untouched.
      const have = new Set(mailboxUi.messages.map((m) => m.id));
      const prepend = fresh.filter((m) => !have.has(m.id));
      if (prepend.length) mailboxUi.messages = [...prepend, ...mailboxUi.messages];
      mailboxUi.hasMore = mailboxUi.cursor != null || !!page.hasMore;
    }
  } catch {
    if (!mailboxUi.messages.length) mailboxUi.error = "Couldn't load messages.";
    // else: leave the stale cache in place; the next poll will retry.
  }
  mailboxUi.loading = false;
}

/** Load the next page of older messages for infinite scroll (appends to the
 *  accumulated list via the stored cursor). Guards against concurrent fetches
 *  and a refresh in flight. Returns true if any new messages were added. */
async function loadMoreMailbox() {
  if (mailboxUi.loadingMore || mailboxUi.loading) return false;
  if (!mailboxUi.cursor) return false; // no more to load
  mailboxUi.loadingMore = true;
  mailboxUi.error = null;
  let added = false;
  try {
    const page = await fetchMessages({ limit: 50, cursor: mailboxUi.cursor, ...mailboxFolderParams(mailboxUi.folder) });
    const more = page.messages || [];
    const have = new Set(mailboxUi.messages.map((m) => m.id));
    for (const m of more) {
      if (!have.has(m.id)) { mailboxUi.messages.push(m); added = true; }
    }
    mailboxUi.cursor = page.nextCursor || null;
    mailboxUi.hasMore = !!page.hasMore;
  } catch {
    mailboxUi.error = "Couldn't load more messages.";
  }
  mailboxUi.loadingMore = false;
  return added;
}

/** Fetch the message history for the History tab (all mail delivered to an
 *  agent, including archived). */
async function loadHistoryPage(agentId) {
  if (!agentId) { historyUi.messages = []; return; }
  historyUi.loading = true;
  try {
    const page = await fetchMessages({ to: agentId, archived: "include", limit: 200 });
    historyUi.messages = page.messages || [];
  } catch { /* leave stale cache */ }
  historyUi.loading = false;
}

async function refresh() {
  try {
    const r = await fetch("/api/state", { cache: "no-store" });
    if (!r.ok) throw new Error("HTTP " + r.status);
    const next = await r.json();
    state = next;
    $("#status").innerHTML = "";
    const n = state.agents.filter(a => !a.isHuman).length;
    const span = el("span", "pulse", "● live");
    $("#status").appendChild(span);
    // state.messages is now a { total, unread } summary (the full log is no
    // longer shipped in /api/state — fetched on demand via /api/messages).
    const total = state.messages?.total ?? 0;
    $("#status").appendChild(document.createTextNode(`  ·  ${n} agent${n === 1 ? "" : "s"}  ·  ${total} message${total === 1 ? "" : "s"} in history`));
    // Re-rendering wipes the whole DOM tree in <main>, which on mobile
    // dismisses the on-screen keyboard every poll. So:
    //  - never re-render while the user is focused inside <main> (typing),
    //  - and skip when nothing actually changed.
    const focusedInMain = document.activeElement && main.contains(document.activeElement);
    // Also suppress the poll re-render while the task detail modal is open and
    // focused — the modal lives in <body> (not #main), so the guard above
    // misses it and the 3s rebuild would dismiss the on-screen keyboard and
    // reset scroll every tick.
    const taskModal = document.getElementById("task-modal");
    const focusedInModal = !!taskModal && document.activeElement && taskModal.contains(document.activeElement);
    // Suppress the poll re-render while a card drag is in progress so the
    // dragged element and drop-target highlights aren't rebuilt mid-drag.
    const dragging = !!boardUi.dragTaskId;
    const sig = JSON.stringify([state.agents, state.messages, state.board]);
    // Model list for the task create/edit dropdown (task 46c60a81). Cached,
    // so this is a no-op after the first fetch. Await so the first board
    // render already has the catalog (a missing dropdown is confusing).
    await loadModels();
    if (focusedInMain || focusedInModal || dragging) { lastSig = sig; return; }
    if (sig !== lastSig) {
      lastSig = sig;
      // Mailbox / history read from the paginated /api/messages endpoint, not
      // /api/state. Fetch their page (only when the tab is active) before
      // rendering so the re-render has fresh message data.
      if (currentTab === "mailbox") await loadMailboxPage();
      else if (currentTab === "history") await loadHistoryPage(historyAgentId);
      render();
    }
  } catch (e) {
    $("#status").innerHTML = "";
    $("#status").appendChild(el("span", "", "⚠ disconnected (" + esc(e.message) + ")"));
  }
}

async function post(path, payload) {
  const r = await fetch(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  return r.json().catch(() => ({ ok: false, error: "invalid response" }));
}

// ── Model list (task 46c60a81) ────────────────────────────────────────────
// The task create/edit model dropdown is hydrated from GET /api/models, which
// returns `{ provider, models: [{ id: "provider/slug", name, provider }] }`.
// Cached for the life of the page; the list changes rarely (only when the
// operator edits models.json / provider catalogs), so it's fetched once.
let modelsCache = null;
async function loadModels() {
  if (modelsCache) return modelsCache;
  try {
    const r = await fetch("/api/models", { cache: "no-store" });
    if (!r.ok) throw new Error("HTTP " + r.status);
    modelsCache = await r.json();
  } catch {
    modelsCache = { provider: null, models: [] };
  }
  return modelsCache;
}

/** Friendly label for a model id: the catalog's `name` when known, else the
 *  raw id. Never throws (used in card/modal rendering). */
function modelDisplay(model) {
  if (!model) return "";
  const m = (modelsCache?.models || []).find((x) => x.id === model);
  return m ? (m.name || m.id) : model;
}

/** A model <select>: blank "Default" option, the catalog's models, the
 *  currently-set model if it's not in the catalog (custom value), and a
 *  "Custom…" free-text fallback. `onChange(value)` is called with the chosen
 *  model id ("" for default); the select re-selects the current value after a
 *  custom prompt so re-renders stay consistent. */
function modelSelect(selected, onChange) {
  const sel = el("select");
  sel.className = "agentpick";
  sel.title = "Model for this task (Default = worker's model)";
  const opt = (val, label, isSel) => { const o = el("option"); o.value = val; o.textContent = label; if (isSel) o.selected = true; return o; };
  sel.appendChild(opt("", "Default", !selected));
  const models = (modelsCache?.models || []);
  for (const m of models) sel.appendChild(opt(m.id, m.name || m.id, m.id === selected));
  if (selected && !models.some((m) => m.id === selected)) sel.appendChild(opt(selected, selected, true));
  sel.appendChild(opt("__custom__", "Custom…", false));
  sel.addEventListener("change", () => {
    if (sel.value === "__custom__") {
      const v = prompt("Model (provider/model, e.g. anthropic/claude-sonnet-4):", selected || "");
      if (v !== null && v.trim()) onChange(v.trim());
      sel.value = selected || "";
      return;
    }
    onChange(sel.value);
  });
  return sel;
}

// ── Rendering ────────────────────────────────────────────────────────────────
function render() {
  if (currentTab === "agents") renderAgents();
  else if (currentTab === "board") renderBoard();
  else if (currentTab === "backlog") renderBacklog();
  else if (currentTab === "mailbox") renderMailbox();
  else if (currentTab === "costs") { if (!costsUi.data && !costsUi.loading) { loadCosts(false).then(renderCosts); } else renderCosts(); }
  else if (currentTab === "settings") renderSettings();
  else if (currentTab === "history") renderHistory();
  else if (currentTab === "logs") { loadLogs().then(renderLogs); }
  // Keep the projects dropdown in sync if it's open
  const projDD = document.getElementById("projects-dropdown");
  if (projDD && !projDD.classList.contains("hidden") && typeof renderProjectsDropdown === "function") {
    renderProjectsDropdown(); positionProjectsDropdown();
  }
}

function sortAgents(list) {
  return [...list].sort((a, b) => {
    if (a.isHuman !== b.isHuman) return a.isHuman ? 1 : -1;
    const pa = projectOf(a.cwd), pb = projectOf(b.cwd);
    if (pa !== pb) return pa < pb ? -1 : 1;
    return a.agentName < b.agentName ? -1 : 1;
  });
}

function renderAgents() {
  const prevMainTop = main.scrollTop;
  main.innerHTML = "";
  const card = el("div", "card");
  card.appendChild(el("h2", null, "Connected agents"));
  const wrap = el("div");
  wrap.style.overflowX = "auto";
  const table = el("table");
  const thead = el("thead"); const trh = el("tr");
  for (const h of ["Name", "Project", "Status", "Ctx", "Model", "Uptime", "ID", "Actions"]) {
    trh.appendChild(el("th", null, h));
  }
  thead.appendChild(trh); table.appendChild(thead);
  const tbody = el("tbody");
  let prevGroup = "";
  for (const a of sortAgents(state.agents)) {
    const grp = a.isHuman ? "operator" : projectOf(a.cwd);
    if (grp !== prevGroup) {
      prevGroup = grp;
      const gr = el("tr"); gr.className = "group-row"; const gtd = el("td"); gtd.colSpan = 8;
      gtd.style.color = "var(--accent)";
      gtd.style.background = "var(--group-bg)";
      gtd.textContent = (a.isHuman ? "👤 " : "📁 ") + grp + (a.cwd && !a.isHuman ? "   " + a.cwd : "");
      gr.appendChild(gtd); tbody.appendChild(gr);
    }
    const tr = el("tr");
    // Name
    const tdN = el("td"); tdN.dataset.label = "Name";
    const name = a.agentName + (a.isHuman ? "  (you)" : "");
    tdN.appendChild(el("span", a.isHuman ? "human-tag" : "", name));
    tr.appendChild(tdN);
    // Project
    const tdP = el("td", null, a.isHuman ? "—" : projectOf(a.cwd)); tdP.dataset.label = "Project"; tr.appendChild(tdP);
    // Status
    const tdS = el("td", null, a.status || "—"); tdS.dataset.label = "Status"; tr.appendChild(tdS);
    // Ctx
    const tdC = el("td", "ctx " + ctxClass(a.contextPct), a.contextPct == null ? "—" : a.contextPct + "%"); tdC.dataset.label = "Ctx"; tr.appendChild(tdC);
    // Model
    const tdM = el("td", null, a.model || "—"); tdM.dataset.label = "Model"; tr.appendChild(tdM);
    // Uptime
    const tdU = el("td", null, a.isHuman ? "—" : fmtUptime(a.registeredAt, state.now)); tdU.dataset.label = "Uptime"; tr.appendChild(tdU);
    // ID
    const tdI = el("td", null, shortId(a.agentId)); tdI.dataset.label = "ID"; tr.appendChild(tdI);
    // Actions
    const tdA = el("td"); tdA.className = "no-label"; tdA.dataset.label = "";
    if (!a.isHuman) {
      const sendBtn = el("button", "btn secondary", "Send mail");
      sendBtn.addEventListener("click", () => {
        compose.to = a.agentName;
        setTab("mailbox");
        document.querySelector(".compose input")?.scrollIntoView({ behavior: "smooth", block: "start" });
      });
      tdA.appendChild(sendBtn);
      // Spawned agents get a Terminal + Stop button. state.spawn.sessions is
      // the daemon's tracked set (only those it spawned), so the buttons
      // only appear for spawn-managed agents — never for operator-launched ones.
      // Link by agentId when the daemon has resolved it (robust); fall back to
      // name matching for sessions still waiting for the agent to register.
      const spawned = (state.spawn?.sessions || []).find(s =>
        (s.agentId && a.agentId && s.agentId === a.agentId) ||
        s.name === a.agentName || s.agentName === a.agentName);
      if (spawned) {
        const termBtn = el("button", "btn secondary mini", "Terminal");
        termBtn.addEventListener("click", () => openTerminal(spawned.name));
        tdA.appendChild(termBtn);
        if (spawned.alive) {
          const stopBtn = el("button", "btn secondary mini", "Stop");
          stopBtn.style.borderColor = "var(--error)"; stopBtn.style.color = "var(--error)";
          stopBtn.addEventListener("click", async () => {
            if (!confirm(`Stop agent '${spawned.name}'? (kills its tmux session)`)) return;
            const r = await post("/api/spawn/stop", { name: spawned.name });
            if (r.ok) { toast("✅ Stopped " + spawned.name); refresh(); } else toast("❌ " + (r.error || "failed"), true);
          });
          tdA.appendChild(stopBtn);
        }
      }
    } else {
      tdA.appendChild(el("span", "empty", "\u2014"));
    }
    tr.appendChild(tdA);
    tbody.appendChild(tr);
  }
  table.appendChild(tbody); wrap.appendChild(table); card.appendChild(wrap);
  main.appendChild(card);
  main.scrollTop = prevMainTop;
}

