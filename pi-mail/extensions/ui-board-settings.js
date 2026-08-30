"use strict";
// ── Board settings card ─────────────────────────────────────────────────────
// Extracted from ui-board.js. boardSettingsCard renders the Jira + middle-
// manager + CEO + columns configuration form; ensureBoardCfg caches the config
// fetch so the Settings tab doesn't refetch on every poll re-render.

const boardCfgCache = {};
async function ensureBoardCfg() {
  if (!boardCfgCache.cfg) {
    try { boardCfgCache.cfg = await (await fetch("/api/board/config", { cache: "no-store" })).json(); } catch { boardCfgCache.cfg = null; }
  }
  return boardCfgCache.cfg;
}

function boardSettingsCard(board) {
  const card = el("div", "card board-settings");
  card.appendChild(el("h2", null, "Board settings"));

  // Jira master switch (task 6e6e2ab2). Defaults on; unchecking disables
  // Jira entirely (board-only mode): no sync, no push on move/comment/create,
  // and Jira ticket info is hidden from all board output. Credentials are
  // kept so toggling back on resumes sync.
  card.appendChild(el("h3", null, "Jira integration"));
  const jiraRow = el("div", "row"); jiraRow.style.display = "flex"; jiraRow.style.gap = "8px"; jiraRow.style.alignItems = "center"; jiraRow.style.marginTop = "6px";
  const jiraCheck = el("input"); jiraCheck.type = "checkbox"; jiraCheck.id = "jiraEnabled"; jiraCheck.checked = (board._cfg?.jiraEnabled !== false);
  const jiraLabel = el("label", null, "Enable Jira sync (uncheck for board-only mode)"); jiraLabel.htmlFor = "jiraEnabled"; jiraLabel.style.margin = "0";
  jiraRow.appendChild(jiraCheck); jiraRow.appendChild(jiraLabel); card.appendChild(jiraRow);

  // Jira connection
  card.appendChild(el("label", null, "Jira base URL (e.g. https://yourorg.atlassian.net)"));
  const inUrl = el("input"); inUrl.value = board._cfg?.baseUrl ?? ""; card.appendChild(inUrl);
  card.appendChild(el("label", null, "Jira account email"));
  const inMail = el("input"); inMail.value = board._cfg?.email ?? ""; card.appendChild(inMail);
  card.appendChild(el("label", null, "API token" + (board._cfg?.apiTokenSet ? " (set — leave blank to keep)" : "")));
  const inTok = el("input"); inTok.type = "password"; inTok.placeholder = board._cfg?.apiTokenSet ? "••••••••" : "paste a Jira API token";
  card.appendChild(inTok);
  card.appendChild(el("label", null, "JQL (which issues to sync)"));
  const inJql = el("input"); inJql.value = board._cfg?.jql ?? ""; card.appendChild(inJql);
  card.appendChild(el("label", null, "Project key — used when creating top-level Jira issues from the board (e.g. PROJ)"));
  const inProj = el("input"); inProj.value = board._cfg?.projectKey ?? ""; card.appendChild(inProj);

  // Middle-manager section
  card.appendChild(el("h3", null, "Middle manager"));
  card.appendChild(el("p", "muted", "An ephemeral agent spawned on a schedule that reviews the board for the managed (favorited) projects, unblocks stuck workers, and shepherds finished tasks into Done/Archive. The managed set = your favorited project dirs (see the Spawn modal / mail_list_projects)."));
  const mmRow = el("div", "row"); mmRow.style.display = "flex"; mmRow.style.gap = "8px"; mmRow.style.alignItems = "center"; mmRow.style.marginTop = "6px";
  const mmCheck = el("input"); mmCheck.type = "checkbox"; mmCheck.id = "mmEnabled"; mmCheck.checked = board._cfg?.mmEnabled === true;
  const mmLabel = el("label", null, "Enabled"); mmLabel.htmlFor = "mmEnabled"; mmLabel.style.margin = "0";
  mmRow.appendChild(mmCheck); mmRow.appendChild(mmLabel); card.appendChild(mmRow);
  card.appendChild(el("label", null, "Interval (minutes between cycles)"));
  const mmInterval = el("input"); mmInterval.type = "number"; mmInterval.min = "1"; mmInterval.value = board._cfg?.mmIntervalMin ?? 30; card.appendChild(mmInterval);
  card.appendChild(el("label", null, "Model (optional, e.g. anthropic/claude-sonnet-4; blank = default)"));
  const mmModel = el("input"); mmModel.value = board._cfg?.mmModel ?? ""; mmModel.placeholder = "default"; card.appendChild(mmModel);
  card.appendChild(el("label", null, "MM max lifetime (minutes — safety bound to reap stuck sessions)"));
  const mmMaxLife = el("input"); mmMaxLife.type = "number"; mmMaxLife.min = "1"; mmMaxLife.value = board._cfg?.mmMaxLifetimeMin ?? 15; card.appendChild(mmMaxLife);
  card.appendChild(el("label", null, "Worker max lifetime (minutes — safety bound to reap stuck/hung workers; the third tier of CEO→MM→worker)"));
  const workerMaxLife = el("input"); workerMaxLife.type = "number"; workerMaxLife.min = "1"; workerMaxLife.value = board._cfg?.workerMaxLifetimeMin ?? 30; card.appendChild(workerMaxLife);
  const favs = (board._cfg && state.spawn?.projects?.favorites) || state.spawn?.projects?.favorites || [];
  if (favs.length) {
    card.appendChild(el("label", null, "Managed projects (favorites):"));
    const favList = el("div", "muted"); favList.style.marginBottom = "6px";
    favList.textContent = favs.map((f) => f.cwd).join("\n"); favList.style.whiteSpace = "pre";
    card.appendChild(favList);
  } else {
    card.appendChild(el("p", "muted", "No favorited projects yet — favorite a project dir (Spawn modal / mail_set_project_favorite) to add it to the managed set."));
  }

  // CEO section (top-tier manager — spawns middle managers on demand)
  card.appendChild(el("h3", null, "CEO"));
  card.appendChild(el("p", "muted", "An ephemeral top-tier manager spawned on a schedule (default every 120 min). It reviews the federation at a high level, decides which managed projects need a middle-manager pass, spawns MMs for them, and keeps the managed-projects roster healthy. When enabled, it REPLACES the fixed-interval MM timer above — the CEO becomes the sole MM spawner (the MM reaper still runs as a safety net). Disabled by default."));
  const ceoRow = el("div", "row"); ceoRow.style.display = "flex"; ceoRow.style.gap = "8px"; ceoRow.style.alignItems = "center"; ceoRow.style.marginTop = "6px";
  const ceoCheck = el("input"); ceoCheck.type = "checkbox"; ceoCheck.id = "ceoEnabled"; ceoCheck.checked = board._cfg?.ceoEnabled === true;
  const ceoLabel = el("label", null, "Enabled"); ceoLabel.htmlFor = "ceoEnabled"; ceoLabel.style.margin = "0";
  ceoRow.appendChild(ceoCheck); ceoRow.appendChild(ceoLabel); card.appendChild(ceoRow);
  card.appendChild(el("label", null, "Interval (minutes between cycles)"));
  const ceoInterval = el("input"); ceoInterval.type = "number"; ceoInterval.min = "1"; ceoInterval.value = board._cfg?.ceoIntervalMin ?? 120; card.appendChild(ceoInterval);
  card.appendChild(el("label", null, "Model (optional, e.g. anthropic/claude-sonnet-4; blank = default)"));
  const ceoModel = el("input"); ceoModel.value = board._cfg?.ceoModel ?? ""; ceoModel.placeholder = "default"; card.appendChild(ceoModel);
  card.appendChild(el("label", null, "CEO max lifetime (minutes — safety bound to reap stuck sessions; the CEO is a ~15-min thread)"));
  const ceoMaxLife = el("input"); ceoMaxLife.type = "number"; ceoMaxLife.min = "1"; ceoMaxLife.value = board._cfg?.ceoMaxLifetimeMin ?? 15; card.appendChild(ceoMaxLife);

  // Columns editor — draft lives in boardUi so poll re-renders don't wipe edits
  card.appendChild(el("label", null, "Columns — order matters; blank Jira status = board-only column with instructions"));
  const colWrap = el("div");
  // Source the columns editor from the *config* endpoint's columns, not the
  // board-state view. In board-only mode (Jira disabled OR unconfigured)
  // boardState scrubs each column's jiraStatus → null in its VIEW (task
  // 6e6e2ab2), so the /api/board view would render the Jira status input empty
  // even though the stored mapping is intact. The /api/board/config endpoint
  // returns the stored (unscrubbed) board.columns, so the Settings form edits
  // and persists the real stored mapping — re-enabling Jira restores it in the
  // board view. (boardUi.colsDraft is the editable draft, cached in boardUi so
  // poll re-renders don't wipe in-progress edits.)
  if (!boardUi.colsDraft) boardUi.colsDraft = (board._cfgColumns ?? board.columns).map(c => ({ ...c }));
  const rows = boardUi.colsDraft;
  const renderCols = () => {
    colWrap.innerHTML = "";
    rows.forEach((c, i) => {
      const r = el("div", "colrow");
      const rr = el("div", "rr");
      const inName = el("input"); inName.value = c.name; inName.placeholder = "Column name";
      inName.addEventListener("input", () => c.name = inName.value);
      const inStatus = el("input"); inStatus.value = c.jiraStatus ?? ""; inStatus.placeholder = "Jira status (blank = board-only)";
      inStatus.addEventListener("input", () => c.jiraStatus = inStatus.value);
      rr.appendChild(inName); rr.appendChild(inStatus);
      const up = el("button", "btn secondary mini", "↑");
      up.addEventListener("click", () => { if (i > 0) { rows.splice(i - 1, 0, rows.splice(i, 1)[0]); renderCols(); } });
      const down = el("button", "btn secondary mini", "↓");
      down.addEventListener("click", () => { if (i < rows.length - 1) { rows.splice(i + 1, 0, rows.splice(i, 1)[0]); renderCols(); } });
      const del = el("button", "btn secondary mini", "✕");
      del.addEventListener("click", () => { rows.splice(i, 1); renderCols(); });
      rr.appendChild(up); rr.appendChild(down); rr.appendChild(del);
      r.appendChild(rr);
      const inInstr = el("textarea"); inInstr.value = c.instructions ?? ""; inInstr.placeholder = "Instructions mailed to the assignee when a task lands here (optional)";
      inInstr.addEventListener("input", () => c.instructions = inInstr.value);
      r.appendChild(inInstr);
      colWrap.appendChild(r);
    });
  };
  renderCols();
  card.appendChild(colWrap);

  const btnRow = el("div", "row"); btnRow.style.display = "flex"; btnRow.style.gap = "8px"; btnRow.style.marginTop = "10px";
  const addCol = el("button", "btn secondary", "+ Column");
  addCol.addEventListener("click", () => { rows.push({ id: "", name: "New column", jiraStatus: null, instructions: "" }); renderCols(); });
  const save = el("button", "btn", "Save settings");
  save.addEventListener("click", async () => {
    save.disabled = true;
    const r = await post("/api/board/config", {
      config: { jiraEnabled: jiraCheck.checked, baseUrl: inUrl.value, email: inMail.value, apiToken: inTok.value, jql: inJql.value, projectKey: inProj.value,
        mmEnabled: mmCheck.checked, mmIntervalMin: parseInt(mmInterval.value, 10), mmModel: mmModel.value, mmMaxLifetimeMin: parseInt(mmMaxLife.value, 10), workerMaxLifetimeMin: parseInt(workerMaxLife.value, 10),
        ceoEnabled: ceoCheck.checked, ceoIntervalMin: parseInt(ceoInterval.value, 10), ceoModel: ceoModel.value, ceoMaxLifetimeMin: parseInt(ceoMaxLife.value, 10) },
      columns: rows,
    });
    save.disabled = false;
    if (r.ok) { toast("✅ Board settings saved — syncing"); delete boardCfgCache.cfg; boardUi.colsDraft = null; refresh(); }
    else toast("❌ " + (r.error || "save failed"), true);
  });
  btnRow.appendChild(addCol); btnRow.appendChild(save);
  card.appendChild(btnRow);
  return card;
}
