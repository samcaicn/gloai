"use strict";
// ── Spawn agent modal + directory picker ────────────────────────────────────
// Extracted from ui-board.js. openSpawnModal shows a directory picker (recent
// + favorite projects, browse-anywhere, manual path) and spawns a fresh agent
// via /api/spawn. closeModal is a shared overlay-removal helper.

function closeModal(id) { const m = document.getElementById(id); if (m) m.remove(); }

/** State for the directory picker: current path + cached ls results. */
const spawnUi = { path: "", dirs: [], manualMode: false, loadingLs: false };

/** Open the spawn modal with the directory picker. */
async function openSpawnModal() {
  // Default to the filesystem root; the picker can browse anywhere.
  if (!spawnUi.path) spawnUi.path = "/";
  await spawnLs(spawnUi.path);
  renderSpawnModal();
}

/** Fetch a directory listing for `path` (any directory on the filesystem). */
async function spawnLs(path) {
  spawnUi.loadingLs = true;
  if (document.getElementById("spawnModal")) renderSpawnModal();
  try {
    const hidden = spawnUi.showHidden ? "&hidden=1" : "";
    const r = await fetch("/api/spawn/ls?path=" + encodeURIComponent(path) + hidden).then(r => r.json());
    if (r.ok) { spawnUi.path = r.dir; spawnUi.dirs = r.dirs; spawnUi.manualMode = false; }
    else { spawnUi.dirs = []; toast("❌ " + (r.error || "ls failed"), true); }
  } catch (e) { toast("❌ ls failed: " + e.message, true); }
  spawnUi.loadingLs = false;
  if (document.getElementById("spawnModal")) renderSpawnModal();
}

function renderSpawnModal() {
  closeModal("spawnModal");
  const overlay = el("div", "spawn-modal"); overlay.id = "spawnModal";
  overlay.addEventListener("click", (e) => { if (e.target === overlay) overlay.remove(); });
  const card = el("div", "card");
  card.appendChild(el("h3", null, "➕ Spawn a fresh agent"));

  // Recent + favorite projects quick-pick (tracked by the daemon). Clicking a
  // chip jumps the picker straight to that dir. Favorites are starred.
  const projects = state.spawn?.projects;
  const favs = (projects?.favorites || []).map((f) => ({ cwd: f.cwd, fav: true, alive: f.alive }));
  const recents = (projects?.history || [])
    .filter((h) => !favs.some((f) => f.cwd === h.cwd))
    .slice(0, 8)
    .map((h) => ({ cwd: h.cwd, fav: false, alive: h.alive }));
  const quick = [...favs, ...recents];
  if (quick.length) {
    card.appendChild(el("label", null, "Recent / favorite projects"));
    const chips = el("div", "chips");
    for (const q of quick) {
      const dot = q.alive ? " 🟢" : "";
      const star = q.fav ? "⭐ " : "";
      const b = el("button", "chip");
      b.title = q.cwd;
      b.textContent = star + q.cwd.replace(/^.*\//, "") + dot;
      b.addEventListener("click", () => { spawnUi.manualMode = false; spawnLs(q.cwd); });
      chips.appendChild(b);
    }
    card.appendChild(chips);
  }

  // Directory picker
  card.appendChild(el("label", null, "Working directory (cwd)"));
  const picker = el("div", "picker");
  // Up-to-parent navigation: go to the parent directory (disabled at /).
  const upBtn = el("button", "btn secondary mini", "↑");
  upBtn.title = "Go to parent directory";
  upBtn.disabled = spawnUi.path === "/";
  upBtn.addEventListener("click", () => {
    if (spawnUi.path !== "/") {
      const parent = spawnUi.path.replace(/\/[^/]+\/?$/, "") || "/";
      spawnLs(parent);
    }
  });
  picker.appendChild(upBtn);
  // Subdirectory list (navigate into). NOTE: this is a real <button> list, not
  // a <select size> listbox. iOS Safari ignores the size attribute and collapses
  // any <select> to a single-line dropdown + native wheel sheet — the inline
  // browsable list the picker relies on simply does not exist on iOS. A button
  // list renders and navigates identically on touch and desktop, so the file
  // browser actually browses on iOS. (Same class of quirk we already worked
  // around for <datalist>, which renders nothing on iOS Safari.)
  const dirList = el("div", "dir-list");
  if (spawnUi.loadingLs) dirList.appendChild(el("div", "dir-empty", "loading…"));
  else if (!spawnUi.dirs.length) dirList.appendChild(el("div", "dir-empty", "(no subdirectories)"));
  else {
    for (const d of spawnUi.dirs) {
      const b = el("button", "dir-item", "📁 " + d.name);
      b.title = d.path;
      b.addEventListener("click", () => spawnLs(d.path));
      dirList.appendChild(b);
    }
  }
  picker.appendChild(dirList);
  card.appendChild(picker);
  // Crumbs + favorite-this-dir toggle + manual path input
  const crumbs = el("div", "crumbs"); crumbs.textContent = spawnUi.path || "(pick a directory)";
  const curCwd = spawnUi.manualMode ? "" : spawnUi.path;
  const isFav = !!(curCwd && (state.spawn?.projects?.favorites || []).some((f) => f.cwd === curCwd));
  const starBtn = el("button", "btn secondary mini");
  starBtn.title = "Toggle favorite for this project dir";
  starBtn.textContent = isFav ? "★ favorited" : "☆ favorite";
  starBtn.addEventListener("click", async () => {
    const cwd = spawnUi.manualMode ? manualIn.value.trim() : spawnUi.path;
    if (!cwd) { toast("❌ pick a directory first", true); return; }
    const currently = (state.spawn?.projects?.favorites || []).some((f) => f.cwd === cwd);
    const r = await post("/api/spawn/favorite", { cwd, favorite: !currently });
    if (r.ok) { toast(currently ? "☆ Unfavorited " + cwd : "⭐ Favorited " + cwd); refresh(); renderSpawnModal(); }
    else toast("❌ " + (r.error || "failed"), true);
  });
  const crumbsRow = el("div"); crumbsRow.style.display = "flex"; crumbsRow.style.alignItems = "center"; crumbsRow.style.gap = "8px"; crumbsRow.style.marginTop = "4px";
  crumbsRow.appendChild(crumbs); crumbsRow.appendChild(starBtn);
  card.appendChild(crumbsRow);
  const manualWrap = el("div"); manualWrap.style.marginTop = "6px";
  const manualIn = el("input"); manualIn.placeholder = "…or type an absolute path"; manualIn.value = spawnUi.manualMode ? spawnUi.path : "";
  manualIn.addEventListener("change", () => { if (manualIn.value.trim()) { spawnUi.manualMode = true; spawnLs(manualIn.value.trim()); } });
  manualWrap.appendChild(manualIn);
  card.appendChild(manualWrap);

  // Name / model / kickoff
  const nameL = el("label", null, "Agent name (optional — defaults to <dir>-<id6>)");
  card.appendChild(nameL);
  const nameIn = el("input"); nameIn.placeholder = "e.g. reader-worker-1"; card.appendChild(nameIn);
  const modelL = el("label", null, "Model (optional)");
  card.appendChild(modelL);
  const modelIn = el("input"); modelIn.placeholder = "e.g. anthropic/claude-sonnet-4"; card.appendChild(modelIn);
  const kickL = el("label", null, "Kickoff prompt (optional — sent as a new-session task once the agent registers)");
  card.appendChild(kickL);
  const kickIn = el("textarea"); kickIn.rows = 3; kickIn.placeholder = "e.g. /new-task Implement the auth refactor"; card.appendChild(kickIn);

  // Show hidden (dot-)directories in the picker above. Off by default so the
  // list stays tidy; toggle re-fetches the current directory.
  const hidWrap = el("span", "checkbox");
  const hidCb = el("input"); hidCb.type = "checkbox"; hidCb.id = "sh"; hidCb.checked = !!spawnUi.showHidden;
  hidCb.addEventListener("change", () => { spawnUi.showHidden = hidCb.checked; spawnLs(spawnUi.path); });
  const hidLab = el("label"); hidLab.htmlFor = "sh"; hidLab.textContent = "Show hidden directories";
  hidWrap.appendChild(hidCb); hidWrap.appendChild(hidLab);
  card.appendChild(hidWrap);

  const row = el("div", "row");
  const cancel = el("button", "btn secondary", "Cancel");
  cancel.addEventListener("click", () => closeModal("spawnModal"));
  row.appendChild(cancel);
  const spawnGo = el("button", "btn spawn-btn", "Spawn");
  spawnGo.addEventListener("click", async () => {
    const cwd = spawnUi.manualMode ? manualIn.value.trim() : spawnUi.path;
    if (!cwd) { toast("❌ pick a working directory", true); return; }
    spawnGo.disabled = true; spawnGo.textContent = "Spawning…";
    const r = await post("/api/spawn", { cwd, name: nameIn.value.trim() || undefined, model: modelIn.value.trim() || undefined, kickoff: kickIn.value.trim() || undefined });
    spawnGo.disabled = false; spawnGo.textContent = "Spawn";
    if (r.ok) { toast("✅ Spawned " + (r.name || "agent") + " — it'll appear in the Agents table"); closeModal("spawnModal"); refresh(); }
    else toast("❌ " + (r.error || "spawn failed"), true);
  });
  row.appendChild(spawnGo);
  card.appendChild(row);
  overlay.appendChild(card);
  document.body.appendChild(overlay);
}
