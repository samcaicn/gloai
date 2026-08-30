"use strict";
// ── Logs tab — session log viewer ─────────────────────────────────────────
// Lists recent pi session logs (JSONL transcripts) from all projects, grouped
// by project. Each entry can be expanded to view its contents inline.

let logsUi = { entries: [], loading: false, expandedPath: null, content: null, loadingContent: false };

/** Fetch log entries from /api/logs (max 100). Always returns a Promise. */
async function loadLogs() {
  if (logsUi.loading) return;
  logsUi.loading = true;
  try {
    const r = await fetch("/api/logs?max=100").then(r => r.json());
    logsUi.entries = r.entries || [];
  } catch { /* leave stale */ }
  logsUi.loading = false;
}

/** Fetch the content of one log file. */
async function loadLogContent(fp) {
  logsUi.loadingContent = true; logsUi.expandedPath = fp; logsUi.content = null;
  renderLogs();
  try {
    const r = await fetch("/api/logs/content?path=" + encodeURIComponent(fp) + "&tail=500").then(r => r.json());
    logsUi.content = r.content || "";
  } catch { logsUi.content = "(error loading content)"; }
  logsUi.loadingContent = false;
  renderLogs();
}

function toggleLog(fp) {
  if (logsUi.expandedPath === fp) {
    logsUi.expandedPath = null; logsUi.content = null;
    renderLogs();
  } else {
    loadLogContent(fp);
  }
}

/** Render a short relative-time label. */
function relTime(ts) {
  if (!ts) return "";
  const d = Math.round((Date.now() - new Date(ts).getTime()) / 1000);
  if (d < 60) return d + "s ago";
  if (d < 3600) return Math.round(d / 60) + "m ago";
  if (d < 86400) return Math.round(d / 3600) + "h ago";
  if (d < 604800) return Math.round(d / 86400) + "d ago";
  return Math.round(d / 604800) + "w ago";
}

function fmtSize(bytes) {
  if (bytes < 1024) return bytes + " B";
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + " KB";
  return (bytes / (1024 * 1024)).toFixed(1) + " MB";
}

/** Strip ansi escape codes from text for plain rendering. */
function stripAnsi(s) {
  return (s || "").replace(/\x1b\[[0-9;]*[a-zA-Z]/g, "").replace(/\x1b\][^\x07]*\x07/g, "");
}

/** Extract a human-readable label from a JSONL event line. */
function eventLabel(line) {
  try { const e = JSON.parse(line); if (e.type) return e.type; return ""; } catch { return ""; }
}

/** Try to extract a text preview from a JSONL event line. */
function eventPreview(line) {
  try {
    const e = JSON.parse(line);
    if (e.message?.content) {
      const parts = (Array.isArray(e.message.content) ? e.message.content : [e.message.content]);
      const texts = parts.map(p => typeof p === "string" ? p : p?.text || "").join(" ");
      return texts.slice(0, 120);
    }
    if (e.text) return String(e.text).slice(0, 120);
    if (e.name) return String(e.name);
    return "";
  } catch { return ""; }
}

function renderLogs() {
  const prevMainTop = main.scrollTop;
  main.innerHTML = "";
  const card = el("div", "card");
  card.appendChild(el("h2", null, "📜 Session logs"));

  if (logsUi.loading && !logsUi.entries.length) {
    card.appendChild(el("div", "empty", "Loading…"));
    main.appendChild(card);
    return;
  }

  if (!logsUi.entries.length) {
    card.appendChild(el("div", "empty", "No session logs found (sessions appear here after pi runs)."));
    main.appendChild(card);
    return;
  }

  // Group by project
  const groups = new Map();
  for (const e of logsUi.entries) {
    const proj = e.project || "(unknown)";
    if (!groups.has(proj)) groups.set(proj, []);
    groups.get(proj).push(e);
  }

  const sortedGroups = [...groups.keys()].sort();

  for (const proj of sortedGroups) {
    const entries = groups.get(proj);

    // Collapsible group header
    const groupKey = "logs-group-" + proj.replace(/[^a-zA-Z0-9]/g, "-");
    const header = el("div", "logs-group-header");
    header.style.cursor = "pointer";
    header.addEventListener("click", () => {
      const body = document.getElementById(groupKey);
      if (body) body.classList.toggle("hidden");
    });
    header.appendChild(el("span", "logs-group-name", "📁 " + (proj.length > 48 ? "…" + proj.slice(-47) : proj)));
    header.appendChild(el("span", "logs-group-count", entries.length + " session" + (entries.length !== 1 ? "s" : "")));
    card.appendChild(header);

    const body = el("div", "logs-group-body"); body.id = groupKey;
    for (const e of entries) {
      const row = el("div", "logs-row");
      const info = el("div", "logs-row-info");
      const fname = e.name.replace(".jsonl", "");
      info.appendChild(el("span", "logs-row-name", fname));
      const meta = el("span", "logs-row-meta");
      meta.textContent = new Date(e.ts).toLocaleString() + " · " + relTime(e.ts) + " · " + fmtSize(e.size);
      info.appendChild(meta);
      row.appendChild(info);

      const expandBtn = el("button", "btn secondary mini", logsUi.expandedPath === e.path ? "Collapse" : "View");
      expandBtn.addEventListener("click", (ev) => { ev.stopPropagation(); toggleLog(e.path); });
      row.appendChild(expandBtn);

      body.appendChild(row);

      // Expanded content
      if (logsUi.expandedPath === e.path) {
        const detail = el("div", "logs-detail");
        if (logsUi.loadingContent) {
          detail.appendChild(el("div", "empty", "Loading…"));
        } else if (logsUi.content) {
          // Parse JSONL into a compact event list
          const lines = logsUi.content.split("\n").filter(l => l.trim());
          const eventList = el("div", "logs-event-list");
          for (const line of lines) {
            const evt = el("div", "logs-event");
            const kind = eventLabel(line);
            const preview = eventPreview(line);
            const head = el("div", "logs-event-head");
            if (kind) head.appendChild(el("span", "logs-event-kind", kind));
            if (preview) head.appendChild(el("span", "logs-event-preview", preview));
            evt.appendChild(head);
            // Expand: show the full JSONL line
            const full = el("div", "logs-event-full hidden");
            full.appendChild(el("pre", null, stripAnsi(line).slice(0, 2000)));
            head.addEventListener("click", () => full.classList.toggle("hidden"));
            head.style.cursor = "pointer";
            evt.appendChild(full);
            eventList.appendChild(evt);
          }
          detail.appendChild(eventList);
          if (lines.length >= 500) {
            detail.appendChild(el("div", "empty", "(showing last " + lines.length + " lines; full file is " + fmtSize(e.size) + ")"));
          }
        }
        body.appendChild(detail);
      }
    }
    card.appendChild(body);
  }
  main.appendChild(card);
  main.scrollTop = prevMainTop;
}
