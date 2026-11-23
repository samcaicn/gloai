"use strict";

// Only the lock holder compares snapshots and keeps its normal desk reader awake.
// A queued tab inherits the last comparison when the holder closes. The first
// tab after a full close starts quietly, even if the desk server is still up.
function deskNotifications(ticket, openTurn, reschedule) {
  const toggle = $("notifications");
  const preference = "mikaDeskNotifications", checkpoint = "mikaDeskNotificationStates";
  const notify = new Set(["waiting_for_answer", "waiting_on_phone", "done", "failed"]);
  const live = new Map();
  const supported = window.isSecureContext && "Notification" in window && navigator.locks && crypto.subtle;
  let scope, election, owner, previous, recent, sequence = 0, suspended = false, unavailable = !supported;
  let deliveryFailed = false;

  async function digest(text) {
    const data = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
    return Array.from(new Uint8Array(data), byte => byte.toString(16).padStart(2, "0")).join("");
  }

  function enabled() {
    return scope && !suspended && !unavailable && Notification.permission === "granted" &&
      localStorage.getItem(preference) === scope;
  }

  function show(task) {
    const id = task.task_id;
    const note = new Notification("Mika · " + t(task.status), {
      body: t("alertOpen"), icon: "/mika-logo.png"
    });
    live.set(id, note);
    note.onerror = () => { deliveryFailed = true; sync(); };
    note.onshow = () => { if (deliveryFailed) { deliveryFailed = false; sync(); } };
    note.onclick = () => {
      window.focus();
      openTurn(task);
      note.close();
    };
    note.onclose = () => { if (live.get(id) === note) live.delete(id); };
  }

  async function compare(tasks) {
    const generation = ++sequence;
    const entries = await Promise.all(tasks.map(async task => {
      // Two consecutive questions or handoffs can arrive between reads. Keep
      // their identity across tabs without storing task text in browser storage.
      let detail;
      if (task.status === "waiting_for_answer") detail = [task.question, task.options];
      if (task.status === "waiting_on_phone") detail = [task.waiting_for, task.app, task.reason, task.message];
      return [task.task_id, detail ? task.status + "/" + await digest(JSON.stringify(detail)) : task.status];
    }));
    if (!owner || generation !== sequence || !enabled()) return;
    const now = new Map(entries), before = previous;
    previous = now;
    const changes = tasks.filter(task => before?.get(task.task_id) !== now.get(task.task_id));
    if (!before || changes.length || before.size !== now.size) {
      localStorage.setItem(checkpoint, JSON.stringify(entries));
    }
    if (!before) return;
    for (const task of changes) {
      live.get(task.task_id)?.close();
      if (notify.has(task.status)) show(task);
    }
  }

  async function elect(signal) {
    async function lead(inherit) {
      if (signal.aborted) return;
      previous = null;
      if (inherit) {
        const saved = localStorage.getItem(checkpoint);
        if (saved) previous = new Map(JSON.parse(saved));
      } else localStorage.removeItem(checkpoint);
      owner = true;
      if (!inherit && recent) await compare(recent);
      if (signal.aborted) return;
      reschedule();
      await new Promise(resolve => signal.addEventListener("abort", resolve, { once: true }));
    }
    // ifAvailable distinguishes a new watch from a handover without a heartbeat.
    const first = await navigator.locks.request(scope, { ifAvailable: true }, async lock => {
      if (!lock) return false;
      await lead(false);
      return true;
    });
    if (!first && !signal.aborted) await navigator.locks.request(scope, { signal }, () => lead(true));
  }

  function failed() {
    unavailable = true;
    sync();
  }

  function sync() {
    const on = !!enabled(), blocked = supported && Notification.permission === "denied";
    toggle.checked = on;
    toggle.disabled = !scope || unavailable || !!blocked;
    const hint = unavailable ? "alertUnavailable" : blocked ? "alertBlocked" : on && deliveryFailed ? "alertFailed" : "alertScope";
    message("notification-hint", t(hint));
    if (on && !election) {
      election = new AbortController();
      const { signal } = election;
      elect(signal).catch(() => { if (!signal.aborted) failed(); });
    } else if (!on && election) {
      sequence++;
      owner = false;
      deliveryFailed = false;
      election.abort();
      election = null;
      for (const note of live.values()) note.close();
      live.clear();
      reschedule();
    }
  }

  toggle.addEventListener("change", async () => {
    if (!toggle.checked) localStorage.removeItem(preference);
    else {
      try {
        const permission = Notification.permission === "granted" ? "granted" : await Notification.requestPermission();
        if (permission === "granted") localStorage.setItem(preference, scope);
      } catch { failed(); }
    }
    sync();
  });
  window.addEventListener("storage", event => { if (!event.key || event.key === preference) sync(); });
  window.addEventListener("focus", sync);
  window.addEventListener("pagehide", () => { recent = null; suspended = true; sync(); });
  window.addEventListener("pageshow", () => { suspended = false; sync(); });
  if (supported && ticket) digest(ticket).then(id => { scope = "mika-alerts-" + id; sync(); }).catch(failed);
  sync();
  return {
    sync,
    watching: () => !!owner && enabled(),
    observe(tasks) {
      recent = tasks;
      sync();
      if (owner) compare(tasks).catch(failed);
    }
  };
}
