"use strict";
function renderBoard() {
  // Preserve scroll positions across the 3s poll re-render.
  const prevMainTop = main.scrollTop;
  const prevBoard = main.querySelector(".board");
  const prevBoardLeft = prevBoard ? prevBoard.scrollLeft : 0;

  main.innerHTML = "";
  const board = state.board;
  if (!board) { main.appendChild(el("div", "empty", "Board not available (daemon too old? restart with /restart-mail-daemon).")); return; }

  // Toolbar
  const bar = el("div", "board-toolbar");
  const sync = el("span", "sync" + (board.syncError ? " err" : ""));
  sync.textContent = board.jiraEnabled === false
    ? "Jira disabled — board-only mode (open Settings)"
    : board.jiraConfigured
      ? (board.syncError ? "⚠ Jira sync error: " + board.syncError : "Jira sync · last " + (board.lastSync ? fmtTime(board.lastSync) : "never"))
      : "Jira not configured — board-only mode (open Settings)";
  bar.appendChild(sync);
  const syncBtn = el("button", "btn secondary mini", "Fetch from Jira");
  syncBtn.disabled = board.jiraEnabled === false;
  syncBtn.title = board.jiraEnabled === false ? "Jira is disabled — enable it in Settings to fetch" : "Fetch issue state + column mapping from Jira now";
  syncBtn.addEventListener("click", async () => {
    syncBtn.disabled = true;
    const r = await post("/api/board/sync", {});
    syncBtn.disabled = false;
    if (r.ok) {
      const col = r.columns;
      const changed = col && col.ok && (col.added?.length || col.promoted?.length);
      toast("🔄 Fetched from Jira (issues + columns)" + (changed ? ` — columns: ${[...(col.added || []).map(s => "+" + s), ...(col.promoted || []).map(s => "~" + s)].join(", ")}` : ""));
      refresh();
    } else toast("❌ " + (r.error || "fetch failed"), true);
  });
  bar.appendChild(syncBtn);
  const cbWrap = el("span", "checkbox");
  const cb = el("input"); cb.type = "checkbox"; cb.id = "fs"; cb.checked = boardUi.freshSession;
  cb.addEventListener("change", () => boardUi.freshSession = cb.checked);
  const cbl = el("label", null, "fresh session on assign"); cbl.setAttribute("for", "fs"); cbl.style.margin = "0";
  cbWrap.appendChild(cb); cbWrap.appendChild(cbl);
  bar.appendChild(cbWrap);
  // Status filter: show done (archived) tasks. The checkbox is a FILTER, not
  // an assignment — it reveals the Archive panel below the board.
  const archWrap = el("span", "checkbox");
  const archCb = el("input"); archCb.type = "checkbox"; archCb.id = "fa"; archCb.checked = boardUi.showArchive;
  archCb.addEventListener("change", () => { boardUi.showArchive = archCb.checked; if (archCb.checked && boardUi.archiveTasks == null) { loadArchive().then(renderBoard); } renderBoard(); });
  const archLbl = el("label", null, "show done (archive)"); archLbl.setAttribute("for", "fa"); archLbl.style.margin = "0";
  archWrap.appendChild(archCb); archWrap.appendChild(archLbl);
  bar.appendChild(archWrap);
  // Group filter: focus the board on one project group. The operator sees
  // all groups; this just hides the others. "__all" = no filter.
  const groups = boardGroups(board);
  if (groups.length > 1 || (groups.length === 1 && groups[0] !== "(no project)")) {
    const gWrap = el("span", "checkbox");
    const gSel = el("select"); gSel.style.fontSize = "12px";
    const gAll = el("option"); gAll.value = "__all"; gAll.textContent = "all groups"; if (boardUi.groupFilter === "__all") gAll.selected = true; gSel.appendChild(gAll);
    for (const g of groups) {
      const o = el("option"); o.value = g; o.textContent = g; if (boardUi.groupFilter === g) o.selected = true; gSel.appendChild(o);
    }
    gSel.addEventListener("change", () => { boardUi.groupFilter = gSel.value; renderBoard(); });
    gWrap.appendChild(el("label", null, "group:")); gWrap.lastChild.style.margin = "0"; gWrap.lastChild.style.color = "var(--dim)";
    gWrap.appendChild(gSel);
    bar.appendChild(gWrap);
  }
  // Backlog shortcut — opens the dedicated Backlog tab. Shows the current
  // backlog count so parked items are discoverable without being on the board.
  const blCount = (board.tasks ?? []).filter(t => (t.location ?? "board") === "backlog").length;
  const blBtn = el("button", "btn secondary mini", "📥 Backlog" + (blCount ? " (" + blCount + ")" : ""));
  blBtn.addEventListener("click", () => setTab("backlog"));
  bar.appendChild(blBtn);
  // CEO last-run indicator — shows when the CEO last ran and warns if overdue.
  const ceoInfo = state.ceo;
  const ceoLastTs = ceoInfo?.lastSpawnTs || 0;
  const ceoIntervalMs = (ceoInfo?.intervalMin || 120) * 60_000;
  const ceoGraceMs = 10 * 60_000; // 10-min grace period before "overdue" warning
  const ceoAgo = ceoLastTs ? (state.now - ceoLastTs) : null;
  const ceoOverdue = ceoAgo != null && ceoAgo > (ceoIntervalMs + ceoGraceMs);
  const ceoLabel = ceoInfo?.enabled
    ? (ceoLastTs
      ? "👔 CEO ran " + (ceoAgo < 60_000 ? "just now" : ceoAgo < 3600_000 ? Math.round(ceoAgo / 60_000) + "m ago" : ceoAgo < 86400_000 ? Math.round(ceoAgo / 3600_000) + "h ago" : Math.round(ceoAgo / 86400_000) + "d ago")
      : "👔 CEO not yet run")
    : "👔 CEO off";
  const ceoInd = el("span", "ceo-indicator" + (ceoOverdue ? " overdue" : "") + (ceoInfo?.enabled ? "" : " off"));
  ceoInd.textContent = ceoLabel;
  if (ceoLastTs) ceoInd.title = "Last CEO run: " + new Date(ceoLastTs).toLocaleString();
  bar.appendChild(ceoInd);
  // Run a CEO cycle now (manual trigger). Spawns a top-tier manager pass on
  // demand via a forced ceoTick — reuses the scheduler's own spawnCeo (first
  // favorite cwd, ceoModel, no-overlap guard, canonical ceoKickoff), so no
  // cwd picker is needed. Disabled while a CEO session is already live; if
  // the CEO feature is off, the daemon returns a skipped reason we toast.
  const ceoLive = (state.spawn?.sessions || []).some((s) => s.ceo && s.alive);
  const ceoBtn = el("button", "btn secondary mini", ceoLive ? "👔 CEO running…" : "👔 Run CEO");
  ceoBtn.disabled = ceoLive;
  ceoBtn.title = "Spawn a CEO management pass now (reviews the federation, spawns middle managers)";
  ceoBtn.addEventListener("click", async () => {
    ceoBtn.disabled = true; ceoBtn.textContent = "Spawning…";
    const r = await post("/api/ceo/tick", { force: true });
    ceoBtn.disabled = false; ceoBtn.textContent = "👔 Run CEO";
    if (r.ok) { toast("✅ CEO spawned: " + (r.name || "agent")); refresh(); }
    else if (r.skipped) toast("ℹ️ CEO not spawned: " + r.skipped + (r.skipped === "live CEO running" ? "" : " (check Settings)"), true);
    else toast("❌ " + (r.error || "failed"), true);
  });
  bar.appendChild(ceoBtn);
  const spawnBtn = el("button", "btn spawn-btn", "➕ Spawn agent");
  spawnBtn.addEventListener("click", openSpawnModal);
  bar.appendChild(spawnBtn);
  main.appendChild(bar);

  // Idle-agents summary — surfaces who's available for assignment, right on
  // the board (mirrors Agents-tab statuses, but inline where you assign).
  const idle = state.agents.filter(isIdle).map(a => a.agentName).sort();
  const idleRow = el("div", "idle-row");
  idleRow.appendChild(el("span", "idle-label", idle.length ? "🟢 Idle (" + idle.length + "):" : "🟢 Idle:"));
  if (idle.length) {
    for (const n of idle) idleRow.appendChild(el("span", "idle-chip", n));
  } else {
    idleRow.appendChild(el("span", "idle-none", "no agents idle"));
  }
  main.appendChild(idleRow);

  // New (local) task — supports level (epic/story/task) and a backlog flag.
  const nt = el("div", "newtask");
  const inSum = el("input"); inSum.placeholder = "New task summary…"; inSum.value = boardUi.newTask.summary;
  inSum.addEventListener("input", () => boardUi.newTask.summary = inSum.value);
  const inDesc = el("textarea"); inDesc.placeholder = "Description (optional)…"; inDesc.value = boardUi.newTask.description;
  inDesc.addEventListener("input", () => boardUi.newTask.description = inDesc.value);
  inDesc.rows = 2; inDesc.style.minHeight = "40px";
  const colPick = el("select", "agentpick");
  for (const c of board.columns) {
    const o = el("option"); o.value = c.id; o.textContent = c.name;
    if (c.id === boardUi.newTask.column) o.selected = true;
    colPick.appendChild(o);
  }
  colPick.addEventListener("change", () => boardUi.newTask.column = colPick.value);
  const lvlPick = el("select", "agentpick");
  for (const lv of ["task", "epic", "story"]) {
    const o = el("option"); o.value = lv; o.textContent = lv; if (lv === (boardUi.newTask.level || "task")) o.selected = true; lvlPick.appendChild(o);
  }
  lvlPick.title = "Issue level";
  lvlPick.addEventListener("change", () => boardUi.newTask.level = lvlPick.value);
  const blWrap = el("span", "checkbox");
  const blCb = el("input"); blCb.type = "checkbox"; blCb.id = "nbl"; blCb.checked = boardUi.newTask.backlog;
  blCb.addEventListener("change", () => { boardUi.newTask.backlog = blCb.checked; colPick.disabled = blCb.checked; });
  if (boardUi.newTask.backlog) colPick.disabled = true;
  const blLbl = el("label", null, "backlog"); blLbl.setAttribute("for", "nbl"); blLbl.style.margin = "0";
  blWrap.appendChild(blCb); blWrap.appendChild(blLbl);
  // Group picker — hydrated from favorites + spawn history + running agents
  const grpPick = el("select", "agentpick");
  grpPick.title = "Project group";
  grpPick.appendChild((() => { const o = el("option"); o.value = ""; o.textContent = "(no group)"; return o; })());
  const seenGroups = new Set();
  const addGroupOpt = (g) => { if (!g || seenGroups.has(g) || g === "(no project)") return; seenGroups.add(g); const o = el("option"); o.value = g; o.textContent = g; grpPick.appendChild(o); };
  for (const f of (state.spawn?.projects?.favorites || [])) addGroupOpt(projectOf(f.cwd));
  for (const h of (state.spawn?.projects?.history || [])) addGroupOpt(projectOf(h.cwd));
  for (const a of (state.agents || [])) addGroupOpt(projectOf(a.cwd));
  // Priority picker (task df729d21)
  const priPick = el("select", "agentpick");
  priPick.title = "Priority";
  priPick.appendChild((() => { const o = el("option"); o.value = ""; o.textContent = "priority…"; return o; })());
  for (const p of ["high", "medium", "low"]) {
    const o = el("option"); o.value = p; o.textContent = p; if (p === (boardUi.newTask.priority || "")) o.selected = true; priPick.appendChild(o);
  }
  priPick.addEventListener("change", () => boardUi.newTask.priority = priPick.value);
  // Model picker (task 46c60a81) — per-task model override.
  const modelPick = modelSelect(boardUi.newTask.model || "", (v) => boardUi.newTask.model = v);
  modelPick.title = "Model for this task";
  const addBtn = el("button", "btn", "Add task");
  addBtn.addEventListener("click", async () => {
    const summary = boardUi.newTask.summary.trim();
    if (!summary) { toast("Give the task a summary", true); return; }
    const payload = { summary, level: boardUi.newTask.level, backlog: boardUi.newTask.backlog };
    if (!boardUi.newTask.backlog) payload.column = colPick.value;
    if (grpPick.value) payload.group = grpPick.value;
    if (priPick.value) payload.priority = priPick.value;
    if (boardUi.newTask.model) payload.model = boardUi.newTask.model;
    const desc = boardUi.newTask.description.trim();
    if (desc) payload.description = desc;
    const r = await boardPost("/api/board/create", payload, "Task created");
    if (r.ok) { boardUi.newTask.summary = ""; boardUi.newTask.description = ""; boardUi.newTask.priority = ""; boardUi.newTask.model = ""; inSum.value = ""; inDesc.value = ""; }
  });
  nt.appendChild(inSum); nt.appendChild(inDesc); nt.appendChild(lvlPick); nt.appendChild(colPick); nt.appendChild(grpPick); nt.appendChild(priPick); nt.appendChild(modelPick); nt.appendChild(blWrap); nt.appendChild(addBtn);
  main.appendChild(nt);

  // Kanban columns
  const kb = el("div", "board");
  for (const c of board.columns) {
    const col = el("div", "bcol");
    const head = el("div", "bhead");
    head.appendChild(el("span", "bname", c.name));
    head.appendChild(el("span", "badge " + (c.jiraStatus ? "jira" : "custom"), c.jiraStatus ? c.jiraStatus : "board-only"));
    const tasks = orderColumnTasks((board.tasks ?? []).filter(t => (t.location ?? "board") === "board" && t.columnId === c.id && groupVisible(t)), board);
    head.appendChild(el("span", "bcount", String(tasks.length)));
    col.appendChild(head);
    if (c.instructions) col.appendChild(el("div", "binstr", c.instructions));
    if (!tasks.length) col.appendChild(el("div", "empty", "—"));
    for (const t of tasks) col.appendChild(taskCard(t, board));
    makeDropTarget(col, c.id);
    kb.appendChild(col);
  }
  main.appendChild(kb);

  // Archive panel — the "done board". Shown only when the status filter
  // (show done) is on. Archived tasks are removed from their column (incl.
  // Done) and restorable via the card's move dropdown.
  if (boardUi.showArchive) {
    const archTasks = (boardUi.archiveTasks ?? []).filter(t => groupVisible(t));
    const ar = el("div", "bcol"); ar.style.flex = "1 1 100%"; ar.style.maxWidth = "none";
    const arHead = el("div", "bhead");
    arHead.appendChild(el("span", "bname", "🗄 Archive (done board)"));
    arHead.appendChild(el("span", "badge custom", "off-board"));
    arHead.appendChild(el("span", "bcount", String(archTasks.length)));
    ar.appendChild(arHead);
    if (boardUi.archiveTasks == null) {
      ar.appendChild(el("div", "empty", "Loading…"));
      if (!boardUi._archLoading) {
        boardUi._archLoading = true;
        loadArchive().then(() => { boardUi._archLoading = false; renderBoard(); });
      }
    } else if (!archTasks.length) ar.appendChild(el("div", "empty", "—"));
    for (const t of archTasks) ar.appendChild(taskCard(t, board));
    makeDropTarget(ar, "archive");
    main.appendChild(ar);
  }

  // Keep the task detail modal live across the 3s poll re-render.
  renderTaskModal();

  // Restore scroll positions
  main.scrollTop = prevMainTop;
  const boardEl = main.querySelector(".board");
  if (boardEl) boardEl.scrollLeft = prevBoardLeft;
}

// ── Backlog tab (daa0148b) ─────────────────────────────────────────────────
// A dedicated page for the backlog pool (location='backlog', not on a board
// column). Cards can be dragged onto columns, or moved via their dropdown.
// Includes a quick-create input so items can be added straight to the backlog.
function renderBacklog() {
  const prevMainTop = main.scrollTop;
  main.innerHTML = "";
  const board = state.board;
  if (!board) { main.appendChild(el("div", "empty", "Board not available (daemon too old? restart with /restart-mail-daemon).")); return; }

  const head = el("div", "board-toolbar");
  head.appendChild(el("h2", null, "📥 Backlog"));
  head.appendChild(el("span", "sync", "Off-board items not yet placed on a column (local-only). Drag onto a column or use a card's move dropdown to place it."));
  main.appendChild(head);

  // Quick-create straight into the backlog.
  const nt = el("div", "newtask");
  const inSum = el("input"); inSum.placeholder = "New backlog item summary…"; inSum.value = boardUi.newTask.summary;
  inSum.addEventListener("input", () => boardUi.newTask.summary = inSum.value);
  const lvlPick = el("select", "agentpick");
  for (const lv of ["task", "epic", "story"]) {
    const o = el("option"); o.value = lv; o.textContent = lv; if (lv === (boardUi.newTask.level || "task")) o.selected = true; lvlPick.appendChild(o);
  }
  lvlPick.title = "Issue level";
  lvlPick.addEventListener("change", () => boardUi.newTask.level = lvlPick.value);
  const addBtn = el("button", "btn", "Add to backlog");
  addBtn.addEventListener("click", async () => {
    const summary = boardUi.newTask.summary.trim();
    if (!summary) { toast("Give the item a summary", true); return; }
    const desc = boardUi.newTask.description.trim();
    const payload = { summary, level: boardUi.newTask.level, backlog: true };
    if (desc) payload.description = desc;
    const r = await boardPost("/api/board/create", payload, "Backlog item created");
    if (r.ok) { boardUi.newTask.summary = ""; boardUi.newTask.description = ""; inSum.value = ""; }
  });
  nt.appendChild(inSum); nt.appendChild(lvlPick); nt.appendChild(addBtn);
  main.appendChild(nt);

  // Backlog pool as a full-width column.
  const backlogTasks = (board.tasks ?? []).filter(t => (t.location ?? "board") === "backlog");
  const bl = el("div", "bcol"); bl.style.flex = "1 1 100%"; bl.style.maxWidth = "none";
  const blHead = el("div", "bhead");
  blHead.appendChild(el("span", "bname", "📥 Backlog"));
  blHead.appendChild(el("span", "badge custom", "off-board"));
  blHead.appendChild(el("span", "bcount", String(backlogTasks.length)));
  bl.appendChild(blHead);
  bl.appendChild(el("div", "binstr", "Drag a card onto a board column (switch to the Board tab), or use the card's move dropdown to place an item."));
  if (!backlogTasks.length) bl.appendChild(el("div", "empty", "Backlog is empty."));
  for (const t of backlogTasks) bl.appendChild(taskCard(t, board));
  makeDropTarget(bl, "backlog");
  main.appendChild(bl);

  renderTaskModal();
  main.scrollTop = prevMainTop;
}

// ── Settings tab (0f3b5549) ────────────────────────────────────────────────
// Board + Jira + MM + CEO + columns configuration on its own dedicated tab.
// The settings card is shared (boardSettingsCard), defined in ui-board.js.
async function renderSettings() {
  const prevMainTop = main.scrollTop;
  main.innerHTML = "";
  await ensureBoardCfg();
  const board = state.board;
  if (!board) { main.appendChild(el("div", "empty", "Board not available (daemon too old? restart with /restart-mail-daemon).")); return; }
  main.appendChild(boardSettingsCard({ ...board, _cfg: boardCfgCache.cfg?.config, _cfgColumns: boardCfgCache.cfg?.columns }));
  main.scrollTop = prevMainTop;
}

function renderHistory() {
  const prevMainTop = main.scrollTop;
  main.innerHTML = "";
  const card = el("div", "card");
  card.appendChild(el("h2", null, "Mail history per agent"));
  const pick = el("select", "agentpick");
  const def = el("option"); def.value = ""; def.textContent = "— select an agent —"; pick.appendChild(def);
  const sorted = sortAgents(state.agents);
  for (const a of sorted) {
    const o = el("option"); o.value = a.agentId;
    o.textContent = (a.isHuman ? "👤 " : "  ") + a.agentName + (a.isHuman ? " (you)" : "");
    pick.appendChild(o);
  }
  if (historyAgentId) pick.value = historyAgentId;
  card.appendChild(pick);
  card.appendChild(el("div", null, "")).style.height = "10px";

  const list = el("div"); list.style.marginTop = "8px";
  if (!historyAgentId) {
    list.appendChild(el("div", "empty", "Pick an agent to see all mail delivered to it (including archived and broadcasts)."));
  } else if (historyUi.loading && !historyUi.messages.length) {
    list.appendChild(el("div", "empty", "Loading…"));
  } else {
    // historyUi.messages is already filtered server-side (to=<agent>) and
    // sorted newest-first by /api/messages, so no extra filtering/sorting here.
    const msgs = historyUi.messages;
    if (!msgs.length) list.appendChild(el("div", "empty", "No mail for this agent."));
    for (const m of msgs) list.appendChild(messageRow(m, { showFrom: true }));
  }
  card.appendChild(list);
  main.appendChild(card);
  main.scrollTop = prevMainTop;

  pick.addEventListener("change", () => { historyAgentId = pick.value; syncHash(); const p = loadHistoryPage(historyAgentId); render(); p.then(render); });
}

// ── URL routing + tabs + polling ─────────────────────────────────────────────
// The active tab (and the selected agent in History) live in the URL hash so
// a page refresh — or browser back/forward — restores the view instead of
// always dropping back onto the Agents tab. setTab updates state, renders
// synchronously (so immediate follow-ups like scrollIntoView still work), and
// pushes the hash; the hashchange listener handles navigations that arrive
// from outside setTab (back/forward, initial deep-link) and no-ops when the
// hash already matches in-memory state (no render loop).
const VALID_TABS = ["agents", "board", "backlog", "mailbox", "history", "costs", "settings", "logs"];

function routeFor(tab, agentId) {
  if (tab === "history" && agentId) return "history/" + agentId;
  return tab;
}
function parseRoute(hash) {
  const h = (hash || "").replace(/^#\/?/, ""); // strip leading "#" and optional "/"
  const [tab, agentId] = h.split("/");
  if (!VALID_TABS.includes(tab)) return { tab: "agents", agentId: "" };
  return { tab, agentId: agentId || "" };
}
// Apply the URL hash to in-memory state; returns true if it changed anything
// (so the caller can skip a redundant re-render). Does not touch the hash.
function applyRouteFromHash() {
  const { tab, agentId } = parseRoute(location.hash);
  let changed = false;
  if (tab !== currentTab) { currentTab = tab; changed = true; }
  if (tab === "history" && agentId && agentId !== historyAgentId) { historyAgentId = agentId; changed = true; }
  document.querySelectorAll("nav button").forEach(x => x.classList.toggle("active", x.dataset.tab === currentTab));
  return changed;
}
// Push the current tab (+ history selection) into the URL hash. No-op if it
// already matches, so this never triggers a hashchange→render loop. Uses the
// "/" prefix so the fragment never matches a real element id (no scroll jump).
function syncHash() {
  const want = "#/" + routeFor(currentTab, currentTab === "history" ? historyAgentId : "");
  if (location.hash !== want) location.hash = want;
}

function setTab(name) {
  currentTab = name;
  if (name !== "board") closeTaskModal();
  document.querySelectorAll("nav button").forEach(x => x.classList.toggle("active", x.dataset.tab === name));
  syncHash();
  // Mailbox / history read from /api/messages (not /api/state). Kick off the
  // fetch first (it sets the loading flag synchronously), render right away
  // with the cached page (so the tab shows a loading state, not stale data),
  // then re-render when the fetch lands. Load-more-on-scroll is wired separately.
  if (name === "mailbox") { const p = loadMailboxPage(); render(); p.then(render); return; }
  if (name === "history") { const p = loadHistoryPage(historyAgentId); render(); p.then(render); return; }
  render();
}

document.querySelectorAll("nav button").forEach(b => {
  b.addEventListener("click", () => { if (b.dataset.tab) setTab(b.dataset.tab); });
});
// Theme toggle button — cycles dark/light/system and persists via localStorage.
document.getElementById("theme-toggle")?.addEventListener("click", toggleTheme);

window.addEventListener("hashchange", () => { if (applyRouteFromHash()) render(); });

// Restore the view from the URL before the first render so a refresh keeps
// you on the page you were on (instead of always landing on Agents).
applyRouteFromHash();
refresh();
// SSE push: listen for state-change events from the daemon so the UI
// refreshes immediately instead of waiting for the 3s poll.
(() => {
  try {
    const es = new EventSource("/events");
    const onEvent = () => { try { refresh(); } catch {} };
    es.addEventListener("board-update", onEvent);
    es.addEventListener("mail-received", onEvent);
    es.addEventListener("agents-changed", onEvent);
    es.addEventListener("error", () => { /* SSE reconnect is built-in */ });
  } catch { /* SSE not supported — 3s poll fallback is fine */ }
})();
// ── Projects dropdown ─────────────────────────────────────────────────────
// Lists favorited project directories from state.spawn.projects.favorites.
// Add via inline filesystem browser (reuses /api/spawn/ls) or manual path
// input; remove via API unfavorite. Re-built fresh every time it opens.
const projectsBtn = document.getElementById("projects-btn");
const projUi = { path: "/", dirs: [], loading: false, manualMode: false };

async function projLs(path) {
  projUi.loading = true; projUi.manualMode = false;
  refreshProjBrowser();
  try {
    const r = await fetch("/api/spawn/ls?path=" + encodeURIComponent(path)).then(r => r.json());
    if (r.ok) { projUi.path = r.dir; projUi.dirs = r.dirs; }
    else { projUi.dirs = []; toast("❌ " + (r.error || "ls failed"), true); }
  } catch (e) { toast("❌ ls failed: " + e.message, true); }
  projUi.loading = false;
  refreshProjBrowser();
}
function refreshProjBrowser() {
  const browser = document.getElementById("proj-browser");
  if (!browser) return;
  browser.innerHTML = "";
  // Up button
  const upBtn = el("button", "btn secondary mini", "↑");
  upBtn.title = "Go to parent directory";
  upBtn.disabled = projUi.path === "/";
  upBtn.addEventListener("click", () => {
    if (projUi.path !== "/") {
      const parent = projUi.path.replace(/\/[^/]+\/?$/, "") || "/";
      projLs(parent);
    }
  });
  browser.appendChild(upBtn);
  // Current path crumb
  const crumb = el("span", ""); crumb.style.cssText = "font-size:11px;color:var(--dim);margin-left:6px;word-break:break-all";
  crumb.textContent = projUi.path;
  browser.appendChild(crumb);
  // Dir list
  const list = el("div", "dir-list"); list.style.cssText = "height:120px;margin-top:4px";
  if (projUi.loading) list.appendChild(el("div", "dir-empty", "loading…"));
  else if (!projUi.dirs.length) list.appendChild(el("div", "dir-empty", "(no subdirectories)"));
  else {
    for (const d of projUi.dirs) {
      const b = el("button", "dir-item", "📁 " + d.name);
      b.title = d.path;
      b.addEventListener("click", () => projLs(d.path));
      list.appendChild(b);
    }
  }
  browser.appendChild(list);
  // Favorite-this-dir button
  const favRow = el("div", "row"); favRow.style.marginTop = "4px";
  const isFav = (state.spawn?.projects?.favorites || []).some(f => f.cwd === projUi.path);
  const favBtn = el("button", "btn secondary mini", isFav ? "★ favorited" : "☆ favorite this dir");
  favBtn.disabled = projUi.path === "/" || !projUi.path;
  favBtn.addEventListener("click", async () => {
    const currently = (state.spawn?.projects?.favorites || []).some(f => f.cwd === projUi.path);
    const r = await post("/api/spawn/favorite", { cwd: projUi.path, favorite: !currently });
    if (r.ok) { toast(currently ? "☆ Unfavorited" : "⭐ Favorited"); refresh(); closeProjectsDropdown(); }
    else toast("❌ " + (r.error || "failed"), true);
  });
  favRow.appendChild(favBtn);
  browser.appendChild(favRow);
}

function renderProjectsDropdown() {
  let dd = document.getElementById("projects-dropdown");
  if (!dd) { dd = el("div", "projects-dropdown"); dd.id = "projects-dropdown"; document.body.appendChild(dd); }
  dd.innerHTML = "";
  const favs = state.spawn?.projects?.favorites || [];
  // Favorites list
  if (!favs.length) {
    dd.appendChild(el("div", "proj-empty", "No projects yet — browse below to add one"));
  } else {
    for (const f of favs) {
      const row = el("div", "proj-item");
      row.title = f.cwd;
      const name = el("span", "proj-name"); name.textContent = f.cwd.replace(/^.*\//, "");
      row.appendChild(name);
      const remove = el("button", "proj-remove", "✖");
      remove.title = "Remove project";
      remove.addEventListener("click", async (e) => {
        e.stopPropagation();
        const r = await post("/api/spawn/favorite", { cwd: f.cwd, favorite: false });
        if (r.ok) { toast("☆ Unfavorited " + f.cwd); refresh(); closeProjectsDropdown(); }
        else toast("❌ " + (r.error || "failed"), true);
      });
      row.appendChild(remove);
      dd.appendChild(row);
    }
  }
  // Add section with filesystem browser
  const add = el("div", "proj-add");
  const addLabel = el("label", ""); addLabel.style.cssText = "display:block;font-size:12px;color:var(--dim);margin-bottom:4px";
  addLabel.textContent = "Add a project (pick a dir below or paste a path):";
  add.appendChild(addLabel);
  // Manual path input
  const pathIn = el("input"); pathIn.placeholder = "/absolute/path/to/project"; pathIn.style.marginBottom = "6px";
  pathIn.value = projUi.manualMode ? projUi.path : "";
  pathIn.addEventListener("change", () => {
    const v = pathIn.value.trim();
    if (v && v !== "/") { projUi.manualMode = true; projLs(v); }
  });
  add.appendChild(pathIn);
  // Inline filesystem browser
  const browser = el("div"); browser.id = "proj-browser";
  add.appendChild(browser);
  dd.appendChild(add);
  // Populate browser
  if (!projUi.dirs.length && projUi.path === "/") projLs("/");
  else refreshProjBrowser();
}
function positionProjectsDropdown() {
  const dd = document.getElementById("projects-dropdown");
  if (!dd || !projectsBtn) return;
  const rect = projectsBtn.getBoundingClientRect();
  dd.style.position = "fixed";
  dd.style.top = (rect.bottom + 4) + "px";
  // Right-align to the right edge of the button. On mobile, pin to viewport
  // edge so the dropdown isn't pushed off-screen.
  const right = window.innerWidth - rect.right;
  dd.style.right = Math.max(4, right) + "px";
  // Keep the dropdown within the viewport: max-width = viewport - 16px
  dd.style.maxWidth = (window.innerWidth - 16) + "px";
}
function openProjectsDropdown() {
  renderProjectsDropdown();
  const dd = document.getElementById("projects-dropdown");
  if (dd) { dd.classList.remove("hidden"); positionProjectsDropdown(); }
  projectsBtn?.classList.add("active");
}
function closeProjectsDropdown() {
  const dd = document.getElementById("projects-dropdown");
  if (dd) dd.classList.add("hidden");
  projectsBtn?.classList.remove("active");
}
if (projectsBtn) {
  projectsBtn.addEventListener("click", (e) => {
    e.stopPropagation();
    const dd = document.getElementById("projects-dropdown");
    if (dd && !dd.classList.contains("hidden")) { closeProjectsDropdown(); }
    else { openProjectsDropdown(); }
  });
}
// Close on outside click
document.addEventListener("click", (e) => {
  const dd = document.getElementById("projects-dropdown");
  if (dd && !dd.classList.contains("hidden") && !dd.contains(e.target) && e.target !== projectsBtn) {
    closeProjectsDropdown();
  }
});
// Reposition on resize/scroll
window.addEventListener("resize", () => positionProjectsDropdown());
window.addEventListener("scroll", () => positionProjectsDropdown(), true);

pollTimer = setInterval(refresh, 3000);
