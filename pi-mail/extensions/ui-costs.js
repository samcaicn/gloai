"use strict";
// ── Costs tab — Pi usage cost aggregation ───────────────────────────────
// Fetches from /api/costs (cached with 5-min TTL server-side). Renders
// summary cards, bar charts, and a daily trend table — all vanilla JS + CSS.

let costsUi = { data: null, loading: false, error: null };

/** Fetch cost data from the daemon. Pass refresh=true to bypass the cache. */
async function loadCosts(refresh) {
  if (costsUi.loading) return;
  costsUi.loading = true;
  costsUi.error = null;
  try {
    const qs = refresh ? "?refresh=1" : "";
    const r = await fetch("/api/costs" + qs, { cache: "no-store" });
    if (!r.ok) throw new Error("HTTP " + r.status);
    costsUi.data = await r.json();
  } catch (e) {
    costsUi.error = e.message || "Failed to load cost data";
  }
  costsUi.loading = false;
}

/** Format a USD dollar amount. */
function fmtDollar(n) {
  if (n == null) return "$0.00";
  return "$" + n.toFixed(2);
}
/** Format token counts with commas. */
function fmtTokens(n) {
  if (n == null) return "0";
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + "M";
  if (n >= 1_000) return (n / 1_000).toFixed(1) + "K";
  return String(n);
}
/** Format a project name from the session-dir slug: strip -- wrapper, replace
 *  double-hyphens with " / " for readability. */
function fmtProject(slug) {
  let s = slug;
  if (s.startsWith("--")) s = s.slice(2);
  if (s.endsWith("--")) s = s.slice(0, -2);
  // Show just the last component unless it's short
  const parts = s.split("--");
  if (parts.length <= 2) return s.replace(/--/g, " / ");
  // Show first and last segments
  return parts[0] + " / … / " + parts[parts.length - 1];
}

/** Render a horizontal CSS bar chart given an array of { label, value, max, cost, tokens, calls }. */
function barChart(items, opts = {}) {
  const chart = el("div", "cost-chart");
  if (!items.length) { chart.appendChild(el("div", "empty", "No data")); return chart; }
  const max = opts.max != null ? opts.max : Math.max(...items.map(d => d.value), 1);

  for (const d of items) {
    const row = el("div", "cost-bar-row");
    const label = el("span", "cost-bar-label", d.label);
    label.title = d.label; // full name on hover
    row.appendChild(label);

    const barWrap = el("div", "cost-bar-wrap");
    const bar = el("div", "cost-bar");
    const pct = (d.value / max) * 100;
    bar.style.width = Math.max(pct, 0.3) + "%"; // minimum width so label is visible
    if (opts.color) bar.style.background = opts.color;
    bar.appendChild(el("span", "cost-bar-val", fmtDollar(d.value)));
    if (d.tokens != null) {
      bar.appendChild(el("span", "cost-bar-sub", fmtTokens(d.tokens) + " tokens · " + (d.calls || 0) + " calls"));
    }
    barWrap.appendChild(bar);
    const tail = el("span", "cost-bar-tail", fmtDollar(d.value));
    row.appendChild(barWrap);
    row.appendChild(tail);
    chart.appendChild(row);
  }
  return chart;
}

function tokenChart(data) {
  if (!data) return el("div", "empty", "No data");
  const d = data.totalTokens;
  if (!d || !d.total) return el("div", "empty", "No token data");
  const items = [
    { label: "Input", value: d.input, color: "var(--accent)" },
    { label: "Output", value: d.output, color: "var(--success)" },
    { label: "Cache read", value: d.cacheRead, color: "var(--broadcast)" },
    { label: "Cache write", value: d.cacheWrite, color: "var(--warning)" },
  ];
  const max = Math.max(...items.map(i => i.value), 1);
  const chart = el("div", "cost-chart");
  for (const item of items) {
    const row = el("div", "cost-bar-row");
    const label = el("span", "cost-bar-label", item.label);
    row.appendChild(label);
    const wrap = el("div", "cost-bar-wrap");
    const bar = el("div", "cost-bar");
    bar.style.width = Math.max((item.value / max) * 100, 0.5) + "%";
    bar.style.background = item.color;
    bar.appendChild(el("span", "cost-bar-val", fmtTokens(item.value)));
    wrap.appendChild(bar);
    const tail = el("span", "cost-bar-tail", fmtTokens(item.value));
    row.appendChild(wrap);
    row.appendChild(tail);
    chart.appendChild(row);
  }
  return chart;
}

function renderCosts() {
  const prevMainTop = main.scrollTop;
  main.innerHTML = "";
  const card = el("div", "card");
  card.style.maxWidth = "900px";

  // Header + refresh button
  const head = el("div"); head.style.display = "flex"; head.style.alignItems = "baseline"; head.style.gap = "10px"; head.style.marginBottom = "16px"; head.style.flexWrap = "wrap";
  head.appendChild(el("h2", null, "💰 Pi Usage Costs"));
  if (costsUi.data?.generated) {
    const gen = el("span"); gen.style.cssText = "font-size:12px;color:var(--dim)";
    gen.textContent = "generated " + fmtRelative(costsUi.data.generated);
    head.appendChild(gen);
  }
  const refreshBtn = el("button", "btn secondary mini", costsUi.loading ? "Scanning…" : "🔄 Refresh");
  refreshBtn.disabled = costsUi.loading;
  refreshBtn.addEventListener("click", async () => { await loadCosts(true); renderCosts(); });
  head.appendChild(refreshBtn);
  card.appendChild(head);

  // Loading / error states
  if (costsUi.loading && !costsUi.data) {
    card.appendChild(el("div", "empty", "Scanning session files… this may take a moment on first load."));
    main.appendChild(card); main.scrollTop = prevMainTop; return;
  }
  if (costsUi.error) {
    const err = el("div", "empty", "⚠ " + costsUi.error);
    const retry = el("button", "btn secondary mini", "Try again");
    retry.style.marginLeft = "8px";
    retry.addEventListener("click", async () => { await loadCosts(true); renderCosts(); });
    err.appendChild(retry);
    card.appendChild(err);
    main.appendChild(card); main.scrollTop = prevMainTop; return;
  }
  const d = costsUi.data;
  if (!d) {
    // First load: trigger fetch
    card.appendChild(el("div", "empty", "Loading cost data…"));
    main.appendChild(card);
    loadCosts(false).then(renderCosts);
    main.scrollTop = prevMainTop;
    return;
  }

  // ── Summary cards ─────────────────────────────────────────────────────
  const grid = el("div", "costs-grid");
  const cards = [
    { label: "All-time spend", value: fmtDollar(d.totals?.allTime), icon: "📊" },
    { label: "This month", value: fmtDollar(d.totals?.thisMonth), icon: "📅" },
    { label: "Today", value: fmtDollar(d.totals?.today), icon: "🕐" },
    { label: "Total tokens", value: fmtTokens(d.totalTokens?.total), icon: "🔢", sub: fmtTokens(d.totalTokens?.input) + " in · " + fmtTokens(d.totalTokens?.output) + " out" },
  ];
  for (const c of cards) {
    const cc = el("div", "cost-card");
    const icon = el("span", "cost-card-icon", c.icon);
    cc.appendChild(icon);
    const body = el("div");
    body.appendChild(el("div", "cost-card-val", c.value));
    body.appendChild(el("div", "cost-card-lbl", c.label));
    if (c.sub) body.appendChild(el("div", "cost-card-sub", c.sub));
    cc.appendChild(body);
    grid.appendChild(cc);
  }
  card.appendChild(grid);

  // ── Token breakdown ────────────────────────────────────────────────────
  const tokSection = el("div", "cost-section");
  tokSection.appendChild(el("h3", null, "Token Usage"));
  tokSection.appendChild(tokenChart(d));
  card.appendChild(tokSection);

  // ── Cost by project ───────────────────────────────────────────────────
  const projSection = el("div", "cost-section");
  projSection.appendChild(el("h3", null, "Cost by Project"));
  const projItems = (d.byProject || []).map(p => ({
    label: fmtProject(p.project), value: p.cost, tokens: p.tokens, calls: p.calls,
  }));
  projSection.appendChild(barChart(projItems));
  card.appendChild(projSection);

  // ── Cost by model ──────────────────────────────────────────────────────
  const modelSection = el("div", "cost-section");
  modelSection.appendChild(el("h3", null, "Cost by Model"));
  const modelItems = (d.byModel || []).map(m => ({
    label: m.model, value: m.cost, tokens: m.tokens, calls: m.calls,
  }));
  modelSection.appendChild(barChart(modelItems));
  card.appendChild(modelSection);

  // ── Cost over time ─────────────────────────────────────────────────────
  const timeSection = el("div", "cost-section");
  timeSection.appendChild(el("h3", null, "Daily Spend Trend"));
  const dates = d.byDate || [];
  if (dates.length) {
    // Sparkline: stacked inline bars with labels every few days
    const spark = el("div", "cost-spark");
    const dateMax = Math.max(...dates.map(dd => dd.cost), 1);
    const labelEvery = Math.max(1, Math.ceil(dates.length / 12));
    for (let i = 0; i < dates.length; i++) {
      const dd = dates[i];
      const col = el("div", "cost-spark-col");
      const bar = el("div", "cost-spark-bar");
      bar.style.height = Math.max((dd.cost / dateMax) * 100, 1) + "%";
      col.appendChild(bar);
      if (i % labelEvery === 0 || i === dates.length - 1) {
        // Show date label (month-day)
        const lbl = el("span", "cost-spark-lbl", dd.date.slice(5));
        col.appendChild(lbl);
      }
      col.title = dd.date + ": " + fmtDollar(dd.cost);
      spark.appendChild(col);
    }
    timeSection.appendChild(spark);
  } else {
    timeSection.appendChild(el("div", "empty", "No daily data available"));
  }
  card.appendChild(timeSection);

  main.appendChild(card);
  main.scrollTop = prevMainTop;
}