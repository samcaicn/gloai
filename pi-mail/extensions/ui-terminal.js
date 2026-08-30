"use strict";
// ── Web terminal (xterm.js over WebSocket) ──────────────────────────────────
// Extracted from ui-board.js. openTerminal attaches a browser xterm.js instance
// to a spawned agent's tmux session via the /api/spawn/terminal WebSocket.

let termOpen = null;

/** Open a web terminal (xterm.js) over a WebSocket to a spawned tmux session. */
function openTerminal(name) {
  closeModal("termOverlay");
  if (typeof Terminal === "undefined") { toast("❌ xterm.js failed to load (offline?); cannot open terminal", true); return; }
  const overlay = el("div", "term-overlay"); overlay.id = "termOverlay";
  overlay.addEventListener("click", (e) => { if (e.target === overlay) closeTerminal(); });
  const card = el("div", "card");
  const bar = el("div", "bar");
  bar.appendChild(el("h3", null, "🖥 Terminal — " + name));
  const closeBtn = el("button", "btn secondary mini", "Close"); closeBtn.addEventListener("click", closeTerminal);
  bar.appendChild(closeBtn);
  card.appendChild(bar);
  const host = el("div"); host.id = "xterm-host";
  card.appendChild(host);
  overlay.appendChild(card);
  document.body.appendChild(overlay);

  const term = new Terminal({ cursorBlink: true, fontSize: 13, fontFamily: "monospace", scrollback: 5000 });
  const fit = new FitAddon.FitAddon();
  term.loadAddon(fit);
  term.open(host);
  try { fit.fit(); } catch {}
  term.writeln("Connecting to " + name + "…");

  const proto = location.protocol === "https:" ? "wss:" : "ws:";
  const ws = new WebSocket(`${proto}//${location.host}/api/spawn/terminal?name=${encodeURIComponent(name)}`);
  ws.binaryType = "arraybuffer";
  term.termWs = ws; term.termFit = fit; // stash for teardown
  termOpen = term;

  ws.onopen = () => { /* stream starts */ };
  ws.onmessage = (ev) => {
    const data = ev.data instanceof ArrayBuffer ? new Uint8Array(ev.data) : ev.data;
    term.write(data);
  };
  ws.onclose = () => { try { term.writeln("\r\n[disconnected]"); } catch {} };
  ws.onerror = () => { try { term.writeln("\r\n[error]"); } catch {} };
  term.onData((d) => { if (ws.readyState === WebSocket.OPEN) ws.send(new TextEncoder().encode(d)); });

  // Refit on window resize.
  const onResize = () => { try { fit.fit(); } catch {} };
  window.addEventListener("resize", onResize);
  term.termOnResize = onResize;
}

function closeTerminal() {
  if (termOpen) {
    try { if (termOpen.termWs) termOpen.termWs.close(); } catch {}
    try { if (termOpen.termOnResize) window.removeEventListener("resize", termOpen.termOnResize); } catch {}
    try { termOpen.dispose(); } catch {}
    termOpen = null;
  }
  closeModal("termOverlay");
}
