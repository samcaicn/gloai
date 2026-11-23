"use strict";

const tx = navigator.language.toLowerCase().startsWith("zh")
  ? {
      running: "正在运行", conversations: "对话", logout: "断开", back: "‹ 返回",
      emptyRunning: "现在没有在跑的任务", emptyConversations: "还没有对话",
      loadOlder: "加载更早", loginLead: "用「连接电脑」里的访问令牌连接你的手机。",
      tokenLabel: "访问令牌", connect: "连接",
      loginNote: "令牌只保存在网关内存里，不会写进浏览器存储；关掉网关即失效。",
      loginFailed: "连不上手机：检查令牌，并确认手机上「允许远程访问」已打开。",
      rateLimited: "试得太频繁了，等一分钟再试。",
      tokenRejected: "令牌不对，或手机的远程访问没有打开。",
      needsYou: "需要你", step: "第 {n} 步", turns: "{n} 轮",
      updated: "更新于 {t}", answerEmpty: "（没有回答）", screen: "[屏幕]",
      unsupported: "手机上的 Mika 还没有只读控制台接口。升级到包含该接口的版本后再打开这一页。",
      unsupportedShort: "手机端需要升级",
    }
  : {
      running: "Running", conversations: "Conversations", logout: "Disconnect", back: "‹ Back",
      emptyRunning: "Nothing is running", emptyConversations: "No conversations yet",
      loadOlder: "Load earlier", loginLead: "Connect to your phone with the access token from Computer access.",
      tokenLabel: "Access token", connect: "Connect",
      loginNote: "The token stays in gateway memory; it is never written to browser storage and is lost when the gateway restarts.",
      loginFailed: "Could not reach the phone. Check the token and that remote access is on.",
      rateLimited: "Too many attempts. Try again in a minute.",
      tokenRejected: "The token was rejected, or remote access is off.",
      needsYou: "Needs you", step: "Step {n}", turns: "{n} turns",
      updated: "Updated {t}", answerEmpty: "(no answer)", screen: "[screen]",
      unsupported: "This Mika build does not expose the read-only console endpoints yet. Update the phone app to a build that includes them, then reload this page.",
      unsupportedShort: "Phone update required",
    };

const statusText = navigator.language.toLowerCase().startsWith("zh")
  ? { running: "进行中", queued: "排队中", paused: "已暂停", waiting_for_answer: "等你回答", waiting_on_phone: "需在手机上处理", starting: "准备中", done: "完成", failed: "没办成", cancelled: "已停止" }
  : { running: "Running", queued: "Queued", paused: "Paused", waiting_for_answer: "Waiting for you", waiting_on_phone: "On the phone", starting: "Starting", done: "Done", failed: "Failed", cancelled: "Cancelled" };

const $ = id => document.getElementById(id);
const login = $("login");
const loginForm = $("login-form");
const tokenInput = $("token");
const loginError = $("login-error");
const connectButton = $("connect");
const app = $("app");
const layout = document.querySelector(".layout");
const runningBox = $("running");
const runningCount = $("running-count");
const conversationsBox = $("conversations");
const detailPane = $("detail-pane");
const detailTitle = $("detail-title");
const turnsBox = $("turns");
const loadMore = $("load-more");
const online = $("online");

const state = { connected: false, conversations: [], running: [], selected: null, page: null, limit: 10, pagedBack: false, capabilities: null, unsupported: false };
let pollTimer = null;
let refreshing = false;
let detailVersion = 0;
let paging = null;

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function clean(text) {
  return String(text || "").replace(/\[\[screen:[^\]]+\]\]/g, tx.screen);
}

function formatRelative(ms) {
  if (!ms) return "";
  const diff = Date.now() - ms;
  if (diff < 60_000) return navigator.language.startsWith("zh") ? "刚刚" : "just now";
  const minutes = Math.floor(diff / 60_000);
  if (minutes < 60) return navigator.language.startsWith("zh") ? `${minutes} 分钟前` : `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return navigator.language.startsWith("zh") ? `${hours} 小时前` : `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 30) return navigator.language.startsWith("zh") ? `${days} 天前` : `${days}d ago`;
  return new Date(ms).toLocaleDateString();
}

function statusClass(status) {
  if (status === "running" || status === "starting") return "running";
  if (status === "waiting_for_answer" || status === "waiting_on_phone" || status === "queued" || status === "paused") return "need";
  if (status === "done") return "done";
  if (status === "failed" || status === "cancelled") return "bad";
  return "";
}

function applyText() {
  document.documentElement.lang = navigator.language;
  $("running-title").textContent = tx.running;
  $("conversations-title").textContent = tx.conversations;
  $("login-lead").textContent = tx.loginLead;
  $("token-label").textContent = tx.tokenLabel;
  connectButton.textContent = tx.connect;
  $("login-note").textContent = tx.loginNote;
  $("logout").textContent = tx.logout;
  $("back").textContent = tx.back;
  loadMore.textContent = tx.loadOlder;
}

async function api(path, isCurrent = () => true) {
  const response = await fetch("/web" + path, {
    credentials: "same-origin",
    headers: { Accept: "application/json" },
    cache: "no-store",
  });
  if (!isCurrent()) return null;
  if (response.status === 401) {
    await fetch("/web/logout", { method: "POST", credentials: "same-origin" }).catch(() => {});
    showLogin(tx.tokenRejected);
    throw new Error("not_connected");
  }
  if (!response.ok) {
    const error = new Error("http_" + response.status);
    error.status = response.status;
    throw error;
  }
  return response.json();
}

function showLogin(message) {
  state.connected = false;
  state.capabilities = null;
  state.unsupported = false;
  clearDetail();
  stopPolling();
  app.hidden = true;
  login.hidden = false;
  loginError.hidden = !message;
  if (message) loginError.textContent = message;
  tokenInput.focus();
}

async function boot() {
  applyText();
  let session;
  try {
    session = await fetch("/web/session", { credentials: "same-origin", cache: "no-store" }).then(r => r.json());
  } catch (_) {
    showLogin(tx.loginFailed);
    return;
  }
  if (!session.connected) {
    showLogin();
    return;
  }
  state.connected = true;
  login.hidden = true;
  app.hidden = false;
  loginError.hidden = true;
  await refreshAll();
  startPolling();
}

async function refreshAll() {
  if (!state.connected || refreshing || state.unsupported) return;
  refreshing = true;
  try {
    if (!state.capabilities) {
      const info = await api("/api/v1");
      state.capabilities = info.capabilities || [];
      if (!state.capabilities.includes("conversations") || !state.capabilities.includes("running")) {
        unsupported();
        return;
      }
    }
    const [running, conversations] = await Promise.all([
      api("/api/v1/running"),
      api("/api/v1/conversations?limit=30"),
    ]);
    state.running = running.running || [];
    state.conversations = conversations.conversations || [];
    renderRunning();
    renderConversations();
    if (state.selected && !state.pagedBack) await loadSelected({ keepScroll: true });
  } catch (error) {
    if (error.status === 404) {
      unsupported();
      return;
    }
    if (error.message !== "not_connected") offline();
  } finally {
    refreshing = false;
  }
}

function offline() {
  online.classList.add("off");
  $("top-sub").textContent = navigator.language.startsWith("zh") ? "手机不在线" : "Phone offline";
}

// The gateway can be updated before the phone app. Probe the phone's capability list once and
// say so plainly instead of showing a broken page.
function unsupported() {
  state.unsupported = true;
  stopPolling();
  online.classList.add("off");
  $("top-sub").textContent = tx.unsupportedShort;
  runningCount.textContent = "";
  runningBox.replaceChildren(el("div", "empty", tx.unsupported));
  conversationsBox.replaceChildren();
}

function onlineNow() {
  online.classList.remove("off");
  $("top-sub").textContent = "";
}

function renderRunning() {
  runningBox.replaceChildren();
  runningCount.textContent = state.running.length ? String(state.running.length) : "";
  if (!state.running.length) {
    runningBox.appendChild(el("div", "empty", tx.emptyRunning));
    return;
  }
  for (const task of state.running) {
    const card = el("div", "run-card" + (task.needs_you ? " need" : ""));
    const head = el("div", "run-head");
    head.appendChild(el("span", "status " + statusClass(task.status), statusText[task.status] || task.status));
    if (task.needs_you) head.appendChild(el("span", "status need", tx.needsYou));
    if (typeof task.step === "number") head.appendChild(el("span", "sub", tx.step.replace("{n}", task.step)));
    card.appendChild(head);
    card.appendChild(el("p", "run-goal", clean(task.goal)));
    if (task.question) card.appendChild(el("p", "run-question", clean(task.question)));
    else if (task.progress) card.appendChild(el("p", "run-progress", clean(task.progress)));
    if (task.options && task.options.length) {
      const options = el("div", "options");
      for (const option of task.options) options.appendChild(el("span", "option", clean(option)));
      card.appendChild(options);
    }
    card.addEventListener("click", () => {
      if (task.conversation) openConversation(task.conversation);
    });
    runningBox.appendChild(card);
  }
}

function renderConversations() {
  conversationsBox.replaceChildren();
  if (!state.conversations.length) {
    conversationsBox.appendChild(el("div", "empty", tx.emptyConversations));
    return;
  }
  for (const conversation of state.conversations) {
    const button = el("button", "conversation" + (conversation.conversation === state.selected ? " active" : ""));
    button.type = "button";
    const copy = el("div", "copy");
    copy.appendChild(el("div", "title", clean(conversation.title) || conversation.conversation));
    const meta = el("div", "meta");
    const parts = [formatRelative(conversation.updated_at_ms)];
    if (typeof conversation.turn_count === "number") parts.push(tx.turns.replace("{n}", conversation.turn_count));
    meta.textContent = parts.filter(Boolean).join(" · ");
    copy.appendChild(meta);
    button.appendChild(copy);
    button.appendChild(el("span", "chev", "›"));
    button.addEventListener("click", () => openConversation(conversation.conversation));
    conversationsBox.appendChild(button);
  }
}

async function openConversation(id) {
  clearDetail();
  state.selected = id;
  detailPane.hidden = false;
  app.classList.add("mobile-detail");
  layout.classList.add("has-detail");
  renderConversations();
  await loadSelected();
}

function clearDetail() {
  detailVersion++;
  paging = null;
  state.selected = null;
  state.page = null;
  state.pagedBack = false;
  detailPane.hidden = true;
  app.classList.remove("mobile-detail");
  layout.classList.remove("has-detail");
  detailTitle.textContent = "";
  turnsBox.replaceChildren();
  loadMore.hidden = true;
}

async function loadSelected(options = {}) {
  const id = state.selected;
  if (!id || paging !== null) return;
  const version = ++detailVersion;
  const isCurrent = () => version === detailVersion;
  const wasAtBottom = nearBottom();
  const data = await api("/api/v1/conversations/" + encodeURIComponent(id) + "?limit=" + state.limit, isCurrent)
    .catch(error => { if (isCurrent()) throw error; });
  if (!isCurrent()) return;
  onlineNow();
  state.page = data;
  detailTitle.textContent = clean(data.title) || state.selected;
  renderTurns();
  loadMore.hidden = data.from <= 0;
  if (options.keepScroll && wasAtBottom) turnsBox.scrollIntoView({ block: "end" });
}

function renderTurns() {
  const page = state.page;
  if (!page) return;
  turnsBox.replaceChildren();
  if (!page.turns || !page.turns.length) {
    turnsBox.appendChild(el("div", "empty", navigator.language.startsWith("zh") ? "这个对话还没有轮次" : "No turns yet"));
    return;
  }
  for (const turn of page.turns) turnsBox.appendChild(renderTurn(turn));
}

function renderTurn(turn) {
  const wrap = el("div", "turn");
  wrap.appendChild(el("div", "bubble user", clean(turn.user_text)));
  for (const steer of turn.steers || []) wrap.appendChild(el("div", "steer", clean(steer)));
  const answer = el("div", "bubble mika" + (turn.status === "failed" ? " failed" : ""), clean(turn.outcome) || tx.answerEmpty);
  wrap.appendChild(answer);
  const meta = el("div", "turn-meta");
  const chip = el("span", "status " + statusClass(turn.status), statusText[turn.status] || turn.status);
  meta.appendChild(chip);
  if (turn.note) meta.appendChild(el("span", "", clean(turn.note)));
  if (turn.truncated) meta.appendChild(el("span", "", navigator.language.startsWith("zh") ? "已截断" : "truncated"));
  wrap.appendChild(meta);
  return wrap;
}

async function loadOlder() {
  const { selected: id, page } = state;
  if (!id || !page || page.from <= 0 || paging !== null) return;
  const count = Math.min(state.limit, page.from);
  const from = page.from - count;
  const version = ++detailVersion;
  const isCurrent = () => version === detailVersion;
  paging = version;
  try {
    const data = await api("/api/v1/conversations/" + encodeURIComponent(id) + "?from=" + from + "&limit=" + count, isCurrent);
    if (!isCurrent()) return;
    const height = document.body.scrollHeight;
    const top = window.scrollY;
    state.page = {
      ...page,
      from: data.from,
      total: data.total,
      turns: [...(data.turns || []), ...(page.turns || [])],
    };
    state.pagedBack = true;
    renderTurns();
    loadMore.hidden = state.page.from <= 0;
    window.scrollTo(0, top + document.body.scrollHeight - height);
  } catch (error) {
    if (isCurrent()) throw error;
  } finally {
    if (paging === version) paging = null;
  }
}

function nearBottom() {
  return window.innerHeight + window.scrollY >= document.body.scrollHeight - 80;
}

function startPolling() {
  stopPolling();
  if (state.unsupported) return;
  pollTimer = setInterval(() => {
    if (document.hidden || !state.connected) return;
    refreshAll();
  }, 4000);
}

function stopPolling() {
  if (pollTimer) clearInterval(pollTimer);
  pollTimer = null;
}

loginForm.addEventListener("submit", async event => {
  event.preventDefault();
  connectButton.disabled = true;
  loginError.hidden = true;
  try {
    const body = new URLSearchParams({ token: tokenInput.value.trim() });
    const response = await fetch("/web/login", {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body,
    });
    if (!response.ok) {
      loginError.hidden = false;
      loginError.textContent = response.status === 429 ? tx.rateLimited : tx.loginFailed;
      return;
    }
    tokenInput.value = "";
    await boot();
  } catch (_) {
    loginError.hidden = false;
    loginError.textContent = tx.loginFailed;
  } finally {
    connectButton.disabled = false;
  }
});

$("logout").addEventListener("click", async () => {
  try {
    await fetch("/web/logout", { method: "POST", credentials: "same-origin" });
  } catch (_) {
    // The cookie is dropped locally even if the gateway cannot be reached.
  }
  showLogin();
});

$("back").addEventListener("click", () => {
  clearDetail();
  renderConversations();
});

loadMore.addEventListener("click", () => { loadOlder().catch(() => offline()); });

document.addEventListener("visibilitychange", () => {
  if (!document.hidden && state.connected) refreshAll();
});

boot();
