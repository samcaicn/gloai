"use strict";

const STRINGS = {
  attachFile: ["附加文件", "Attach files"],
  removeFile: ["移除 {name}", "Remove {name}"],
  downloadFile: ["下载 {name}", "Download {name}"],
  uploadingFile: ["正在上传 {name}（{index}/{total}）", "Uploading {name} ({index}/{total})"],
  downloadingFile: ["正在下载 {name}…", "Downloading {name}…"],
  fileSaved: ["文件已交给浏览器保存。", "File sent to the browser for saving."],
  fileLimit: ["最多可选 9 个文件，合计不超过 200 MiB。", "Choose up to 9 files totalling at most 200 MiB."],
  filesNeedUpdate: ["此手机版本或设备不支持任务附件。", "Task attachments are unavailable on this phone or version."],
  uploadRejected: ["{name} 未传完。附件已保留，请检查手机连接和剩余空间后重试。", "{name} did not finish uploading. Your attachments are still here. Check the connection and free space, then try again."],
  resultUnavailable: ["文件已变化或无法读取，请在手机上检查后重新导出。", "The file changed or is unavailable. Check it on the phone and export it again."],
  fileTransferFailed: ["文件传输未完成，请检查连接后重试。", "File transfer did not complete. Check the connection and try again."],
  title: ["Mika · 会话桌面", "Mika · Conversation desk"],
  heading: ["会话桌面", "Conversation desk"],
  displayLanguage: ["界面语言", "Language"],
  desktopAlerts: ["电脑提醒", "Desktop notifications"],
  alertScope: ["追问、手机接管、完成或失败时提醒。任务台在后台也有效，关闭所有任务台标签页或退出浏览器后暂停。", "Get alerts for questions, phone takeovers, completions and failures, even in a background tab. Closing all desk tabs or quitting the browser pauses alerts."],
  alertBlocked: ["通知已被阻止。请在浏览器的网站设置中允许通知，再开启提醒。", "Notifications are blocked. Allow them in your browser’s site settings, then turn alerts on."],
  alertUnavailable: ["此浏览器无法发送任务台提醒。", "Desk notifications are unavailable in this browser."],
  alertFailed: ["浏览器未能显示通知。请检查系统是否允许浏览器发送通知。", "The browser could not show a notification. Check its notification settings on your computer."],
  alertOpen: ["点击查看对应的对话和轮次。", "Click to open this turn in its conversation."],
  browserLanguage: ["跟随浏览器", "Browser default"],
  newChat: ["新对话", "New chat"],
  recentChats: ["最近会话", "Recent conversations"],
  conversations: ["会话导航", "Conversations"],
  currentChat: ["当前对话", "Current conversation"],
  openNav: ["打开会话导航", "Open conversations"],
  closeNav: ["关闭会话导航", "Close navigation"],
  myPhone: ["我的手机", "My phone"],
  networkPhone: ["局域网手机", "Network phone"],
  adbPhone: ["ADB 手机", "ADB phone"],
  connectionDetails: ["连接详情", "Connection details"],
  connectedPhone: ["连接的手机", "Connected phone"],
  taskService: ["任务服务", "Task service"],
  phoneState: ["手机状态", "Phone state"],
  chooseDevice: ["选择设备", "Choose a phone"],
  usePhone: ["使用此设备", "Use this phone"],
  refreshNow: ["立即更新", "Update now"],
  updated: ["更新于 {time}", "Updated {time}"],
  autoUpdate: ["自动更新", "Updates automatically"],
  connecting: ["连接中", "Connecting"],
  connected: ["已连接", "Connected"],
  online: ["在线", "Online"],
  offline: ["未连接", "Offline"],
  choosing: ["待选机", "Choose phone"],
  unlocked: ["已解锁", "Unlocked"],
  locked: ["已锁屏", "Locked"],
  screenOff: ["未亮屏", "Screen off"],
  unknown: ["未知", "Unknown"],
  helperOnline: ["助手在线", "Helper online"],
  helperOffline: ["助手离线", "Helper offline"],
  recentHint: ["按对话归集最近 50 个远程任务", "Grouped from the latest 50 remote tasks"],
  noChats: ["还没有近期会话", "No recent conversations"],
  newHeading: ["想让手机帮你做什么？", "What can your phone do for you?"],
  newHint: ["把要做的事告诉 Mika。它的进展、追问和答复会出现在这里。", "Tell Mika what you want done. Its updates, questions, and replies will appear here."],
  connectHint: ["连接手机后就可以派任务。", "Connect a phone to send a task."],
  loading: ["正在读取会话…", "Reading conversations…"],
  unavailableChat: ["这段会话不在近期任务中", "This conversation is no longer available"],
  unavailableHint: ["从左侧选择其他会话，或新建对话。", "Choose another conversation or start a new one."],
  remoteTask: ["远程任务", "Remote task"],
  rounds: ["{n} 轮", "{n} turns"],
  round: ["第 {n} 轮", "Turn {n}"],
  attention: ["待回答", "Answer"],
  handoff: ["接管", "On phone"],
  active: ["进行中", "Active"],
  starting: ["正在启动", "Starting"],
  queued: ["排队中", "Queued"],
  running: ["进行中", "Running"],
  paused: ["已暂停", "Paused"],
  waiting_for_answer: ["等你回答", "Needs your answer"],
  waiting_on_phone: ["需手机接管", "Needs you on the phone"],
  done: ["已返回结果", "Result returned"],
  failed: ["未能完成", "Not completed"],
  cancelled: ["已取消", "Cancelled"],
  msgStarting: ["任务已交给手机，正在等待启动。", "The phone accepted the task and is about to start."],
  msgRunning: ["Mika 正在执行任务。", "Mika is working on the task."],
  msgQueued: ["排在前面的任务做完后自动开始。", "Starts by itself once the tasks ahead finish."],
  msgPaused: ["正在等待手机继续执行。", "Waiting for the phone to continue."],
  waitingCondition: ["等待条件", "Waiting for"],
  msgQuestion: ["Mika 正在等你回答。", "Mika is waiting for your answer."],
  msgConfirm: ["请在手机上确认这一步。", "Confirm this step on the phone."],
  msgHandoff: ["请在手机上接管这一步。", "Take over this step on the phone."],
  msgNoAnswer: ["手机没有附上答复正文。", "The phone returned no answer text."],
  msgNoReason: ["手机没有附上失败原因。", "The phone gave no failure reason."],
  msgCancelled: ["这轮任务已停止。", "This turn was stopped."],
  handoffGuide: ["打开手机上的 Mika 通知处理，完成后这里会自动更新。", "Open Mika’s notification on the phone; this conversation updates automatically."],
  verified: ["已核实", "Verified"],
  verifyFailed: ["核实未通过", "Verification failed"],
  verifyUnknown: ["未报告核实结果", "Verification not reported"],
  verification: ["核实情况", "Verification"],
  expandGoal: ["展开要求", "Show full request"],
  collapseGoal: ["收起要求", "Collapse request"],
  viewProcess: ["查看过程", "View process"],
  closeProcess: ["收起过程", "Hide process"],
  process: ["执行过程", "Execution process"],
  sidePanel: ["侧栏", "Side panel"],
  copyResult: ["复制结果", "Copy result"],
  copied: ["已复制结果。", "Result copied."],
  copyFailed: ["未能复制，可以直接选择答复文字。", "Could not copy. You can select the reply text instead."],
  cancelTask: ["取消本轮", "Cancel turn"],
  confirmCancel: ["向手机发送这轮任务的取消请求？", "Ask the phone to cancel this turn?"],
  targetTurn: ["处理这轮", "Respond to this turn"],
  message: ["给 Mika 的消息", "Message to Mika"],
  newPlaceholder: ["把任务交给手机…", "Give your phone a task…"],
  followPlaceholder: ["接着这段对话说…", "Continue this conversation…"],
  askPlaceholder: ["回答 Mika 的问题…", "Answer Mika’s question…"],
  steerPlaceholder: ["补充这轮任务的要求…", "Add instructions to this turn…"],
  onPhonePlaceholder: ["请在手机上完成这一步…", "Complete this step on the phone…"],
  waitingPlaceholder: ["等待手机继续…", "Waiting for the phone…"],
  noConversation: ["要发送新的要求，请选「新对话」。", "Start a new chat to send another request."],
  continueChat: ["继续此对话", "Continue this conversation"],
  reply: ["回答追问", "Answer question"],
  steer: ["追加要求", "Add instructions"],
  submit: ["交给手机", "Send to phone"],
  sendAnswer: ["发送回答", "Send answer"],
  sendSteer: ["追加到本轮", "Add to this turn"],
  sendContinue: ["在此对话继续", "Continue in this conversation"],
  startAt: ["开始时间", "Started"],
  phoneReport: ["手机当前记录", "Latest phone report"],
  observed: ["本页看到的进展", "Progress seen in this tab"],
  noSteps: ["手机未提供这轮的历史步骤。", "The phone did not provide past steps for this turn."],
  observingNow: ["后续收到的步骤和状态变化会显示在这里。", "New steps and state changes will appear here as they arrive."],
  recentChanges: ["显示最近 {n} 次变化。", "Showing the last {n} changes."],
  stepN: ["第 {n} 步", "Step {n}"],
  original: ["任务信息", "Task information"],
  taskGoal: ["原始要求", "Original request"],
  taskId: ["任务编号", "Task ID"],
  observationText: ["手机返回", "Phone update"],
  steerRecords: ["追加要求", "Added instructions"],
  received: ["手机已收到", "Received by the phone"],
  joined: ["已加入任务上下文", "Added to the task context"],
  not_applied: ["未被采用", "Not applied"],
  questionFromPhone: ["Mika 的追问", "Mika asked"],
  yourAnswer: ["你的回答", "Your answer"],
  yourSteer: ["你的追加要求", "Your instructions"],
  cancelSent: ["已送出取消请求，以手机返回的状态为准。", "Cancellation requested. The phone will report the final state."],
  notAccepted: ["手机未接受这次操作，请查看当前状态。", "The phone did not accept this request. Check the current state."],
  errSession: ["当前任务台会话已失效，请重新打开任务台链接。", "This desk session expired. Reopen the current desk link."],
  errDeviceSelection: ["请连接手机，或从在线设备中选择一台。", "Connect a phone or choose an online device."],
  errDevicePinned: ["这个任务台已选择另一台手机，请重新读取。", "This desk already uses another phone. Refresh its state."],
  errAdb: ["无法读取所选手机，请检查 ADB 连接。", "Cannot read the selected phone. Check its ADB connection."],
  errAdbUnavailable: ["找不到可运行的 adb，请先安装 Android platform-tools，或检查 --adb 指定的路径。", "adb was not found or cannot be run. Install Android platform-tools or check the --adb path."],
  errConnectionInfo: ["无法读取 Mika 的连接信息。请解锁手机，打开一次 Mika，然后重新连接。", "Could not read Mika’s connection information. Unlock the phone, open Mika once, then connect again."],
  errDeviceOffline: ["这台手机已离线，重连后会自动更新。", "This phone is offline. Updates resume after reconnection."],
  errDeviceUnauthorized: ["手机还没授权这台电脑。请解锁手机，允许 USB 调试，任务台会自动重试。", "The phone hasn’t authorized this computer. Unlock it and allow USB debugging; the desk retries by itself."],
  errDeviceReconnect: ["这台手机处于离线状态。请重新插拔数据线，或在手机上重新打开 USB 调试，任务台会自动重试。", "This phone is offline. Reconnect the cable or turn USB debugging off and on; the desk retries by itself."],
  errStale: ["手机已重启，请重新运行 mika connect。", "The phone restarted. Run mika connect again."],
  errConnection: ["未能读取手机回复，任务台会自动重连。", "No reply from the phone. The desk will reconnect."],
  errDeskOffline: ["无法读取本机任务台，请检查它是否仍在运行。", "Cannot reach the local desk. Check that it is running."],
  errOutcomeUnknown: ["操作可能已到达手机。请核对会话，再决定是否重新提交。", "The request may have reached the phone. Check the conversation before sending again."],
  errConflict: ["这轮的状态已改变，正在读取手机的最新状态。", "This turn changed. Reading its latest state."],
  errFallback: ["暂时无法更新任务。", "Cannot update tasks right now."],
  keptList: ["保留上次读取的会话。", "Showing the last successful read."],
  taskGone: ["手机已找不到之前选中的任务。", "The phone no longer knows the previously selected task."],
  historyHint: ["手机保存的对话与近期远程任务", "Saved phone conversations and recent remote tasks"],
  historyOff: ["在手机「连接电脑 → 高级设置」打开「允许电脑查看历史记录」，即可查看手机上的对话和 Trace。", "In Connect a computer → Advanced on the phone, allow computers to view history to read saved conversations and traces."],
  phoneReadOnly: ["在手机上继续此对话", "Continue this conversation on the phone"],
  olderChats: ["加载更多对话", "Load more conversations"],
  archivedChat: ["已归档", "Archived"],
  olderTurns: ["加载更早的轮次", "Load earlier turns"],
  historyLoading: ["正在读取手机记录…", "Reading saved phone records…"],
  historyFailed: ["未能读取这份记录，请重试。", "Could not read this record. Try again."],
  retryHistory: ["重试读取", "Try again"],
  traceTitle: ["详细 Trace", "Detailed Trace"],
  traceRecording: ["录制中，随任务更新", "Recording · updates with the task"],
  traceMissing: ["这次运行没有可读取的详细 Trace，可能未开启录制或已按容量清理。", "No detailed trace is available. Recording may have been off, or the archive may have been removed by storage rotation."],
  runMissing: ["这轮没有可读取的运行记录。", "No saved run record is available for this turn."],
  tracePartial: ["这份 Trace 包含未完整录制的内容。", "This trace includes incomplete recording."],
  traceEmpty: ["这一页没有模型请求或工具调用。", "This page has no model attempts or tool calls."],
  requestN: ["第 {step} 步 · 请求 {attempt} · {name}", "Step {step} · attempt {attempt} · {name}"],
  traceTiming: ["{status} · {ms} ms", "{status} · {ms} ms"],
  traceTokens: ["输入 {input} · 缓存 {cached} · 未命中 {uncached} · 输出 {output}", "Input {input} · cached {cached} · uncached {uncached} · output {output}"],
  traceDifference: ["首处变化 · {path}", "First change · {path}"],
  traceBody: ["查看请求与回复", "Read request and reply"],
  traceNoBody: ["这次请求没有录下正文。", "No body was recorded for this request."],
  traceOk: ["完成", "Completed"],
  traceError: ["错误", "Error"],
  traceCancelled: ["取消", "Cancelled"],
  traceIncomplete: ["部分记录", "Partial"],
  savedEvents: ["手机保存的运行记录", "Saved run events"],
  clippedRecord: ["这条记录较长，当前仅显示开头。", "This long record shows only its beginning."],
  previousPage: ["上一页", "Previous page"],
  nextPage: ["下一页", "Next page"],
  pageN: ["第 {n} 页", "Page {n}"],
  closeBody: ["收起正文", "Hide body"],
  pendingRun: ["待核对的提交 · {id}", "Submission to check · {id}"],
  lostReceipt: ["手机可能已受理。连接恢复后，点「查找原任务」核对。", "The phone may have accepted this task. Once connected, choose Find task to check."],
  recoveredRun: ["已按编号确认手机收到任务 · {id}", "Submission confirmed by its task ID · {id}"],
  lookupRun: ["查找原任务", "Find task"],
  retryRun: ["用同一编号重试", "Retry with the same ID"],
  forgetRun: ["放弃核对", "Dismiss"],
  forgetConfirm: ["放弃核对不会取消手机上的任务。之后再次发送会创建新任务，确定放弃？", "Dismiss this receipt? Any task on the phone keeps running. Sending again will create a new task."],
  notReceived: ["手机暂未查到这个编号。要再提交，请用同一编号重试。", "The phone does not have this ID yet. Retry with the same ID if you still want to submit it."],
  removedRun: ["这次提交已受理过，所属对话后来被删除。", "This submission was accepted and its conversation was later deleted."],
};

const $ = id => document.getElementById(id);
// Reattach the same detail view to its turn when a refresh rebuilds the transcript.
const processContent = $("process-content");
const processSlotId = id => "turn-process-" + id;
const browserLanguage = navigator.languages?.[0] || navigator.language || "en";
let lang;
const t = (key, vars = {}) => STRINGS[key][lang].replace(/\{(\w+)\}/g, (_, name) => vars[name]);
function applyLanguage() {
  const pref = localStorage.getItem("mikaDeskLanguage");
  $("language").value = pref === "zh" || pref === "en" ? pref : "auto";
  lang = (pref === "zh" || pref === "en" ? pref : browserLanguage).toLowerCase().startsWith("zh") ? 0 : 1;
  document.documentElement.lang = lang ? "en" : "zh-CN";
  document.title = t("title");
  for (const node of document.querySelectorAll("[data-i18n]")) node.textContent = t(node.dataset.i18n);
  for (const node of document.querySelectorAll("[data-i18n-label]"))
    node.setAttribute("aria-label", t(node.dataset.i18nLabel));
}
applyLanguage();

const tones = {
  starting: "active",
  queued: "active",
  running: "active",
  paused: "",
  waiting_for_answer: "attention",
  waiting_on_phone: "attention",
  done: "",
  failed: "bad",
  cancelled: ""
};
const terminal = new Set(["done", "failed", "cancelled"]);
const fresh = "@new";
const isActive = task => !terminal.has(task.status);
const urgency = task => (tones[task.status] === "attention" ? 0 : isActive(task) ? 1 : 2);
const threadOf = task => (task.conversation ? "chat:" + task.conversation : "task:" + task.task_id);
const date = ms =>
  new Date(ms).toLocaleString(undefined, { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
const clock = ms =>
  new Date(ms).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit", second: "2-digit" });
const byId = (items, id) => items?.find(task => task.task_id === id);
const label = task =>
  (task?.request_text || task?.input_files?.[0]?.name || task?.goal || "")
    .trim()
    .split(/[\r\n。！？!?；;]/, 1)[0]
    .trim() || t("remoteTask");
function groupTasks(tasks) {
  const threads = new Map();
  // API order is newest first (including ties); show turns oldest first inside each conversation.
  for (const task of tasks) {
    const key = threadOf(task);
    if (!threads.has(key)) threads.set(key, { key, turns: [], primary: task });
    const group = threads.get(key);
    group.turns.push(task);
    if (urgency(task) < urgency(group.primary)) group.primary = task;
  }
  const ordered = [...threads.values()].sort((a, b) => urgency(a.primary) - urgency(b.primary));
  for (const thread of ordered) thread.turns.reverse();
  return ordered;
}

// The private launch link is the only source of the browser session.
const ticket = location.hash.slice(1) || sessionStorage.getItem("deskSession");
if (location.hash) {
  if (sessionStorage.getItem("deskSession") !== ticket) sessionStorage.removeItem("deskPendingRun");
  sessionStorage.setItem("deskSession", ticket);
  for (const key of ["deskGroup", "selectedTask"]) sessionStorage.removeItem(key);
  history.replaceState(null, "", location.pathname);
}
let pendingRun = JSON.parse(sessionStorage.getItem("deskPendingRun") || "null");
function setPendingRun(value) {
  pendingRun = value;
  if (value) sessionStorage.setItem("deskPendingRun", JSON.stringify(value));
  else sessionStorage.removeItem("deskPendingRun");
}
let overview = null,
  groups = [],
  groupKey = sessionStorage.getItem("deskGroup"),
  anchorId = sessionStorage.getItem("selectedTask");
let inspectId = null,
  selectedObservation = null,
  preferredTask = null,
  online = false,
  busy = false,
  observing = false;
let revision = 0,
  updateTimer,
  retryDelay = 0,
  writeWarning = "",
  autoDisclosure = false;
let pendingAlertTurn = null;
const alerts = deskNotifications(ticket, openAlertTurn, () => {
  invalidate();
  schedule(true);
});
const attachmentDrafts = new Map();
const drafts = new Map(),
  observed = new Map(),
  exchanges = new Map(),
  expandedGoals = new Set();
const savedChats = new Map(), savedTurns = new Map();
let moreChats = null, recordView = null, recordBody = null, recordRequest = 0, bodyRequest = 0;
let composerKey = "",
  transcriptKey = "",
  transcriptSignature = "",
  navSignature = "",
  processSignature = "";
const group = () => groups.find(item => item.key === groupKey);
function currentTask(g = group()) {
  if (!g) return null;
  const preferred = byId(g.turns, preferredTask);
  return preferred && isActive(preferred) ? preferred : g.primary;
}

function rememberGroup(key, anchor) {
  groupKey = key;
  anchorId = anchor || null;
  if (key) sessionStorage.setItem("deskGroup", key);
  else sessionStorage.removeItem("deskGroup");
  if (anchorId) sessionStorage.setItem("selectedTask", anchorId);
  else sessionStorage.removeItem("selectedTask");
}

function chooseGroup(key) {
  const g = groups.find(i => i.key === key);
  preferredTask = null;
  inspectId = null;
  rememberGroup(key, g ? currentTask(g)?.task_id : null);
  setNav(false);
  invalidate();
  render();
  recordView = recordBody = null;
  schedule(true);
}

async function openAlertTurn(task) {
  if (busy) {
    pendingAlertTurn = task;
    return;
  }
  const id = task.task_id;
  preferredTask = id;
  inspectId = null;
  rememberGroup(threadOf(task), id);
  setNav(false);
  invalidate();
  // A notification can outlive the recent list. Reuse the selected-task lookup.
  if (!byId(overview?.tasks, id)) await work(() => refresh());
  if (groupKey !== threadOf(task) || (preferredTask && preferredTask !== id)) return;
  render();
  const turn = $(processSlotId(id))?.closest(".turn");
  if (turn) {
    turn.tabIndex = -1;
    turn.focus({ preventScroll: true });
    turn.scrollIntoView({ block: "start" });
  } else if (online) message("notice", t("taskGone"));
  schedule(true);
}

function setNav(open) {
  const closing = !open && $("workspace").classList.contains("nav-open");
  $("workspace").classList.toggle("nav-open", open);
  $("open-nav").setAttribute("aria-expanded", String(open));
  if (open) ($("threads").querySelector('[aria-current="true"]') || $("new-chat")).focus();
  if (closing) $("open-nav").focus();
}

function el(tag, cls, text) {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text != null) n.textContent = text;
  return n;
}

function icon(name) {
  const ns = "http://www.w3.org/2000/svg",
    n = document.createElementNS(ns, "svg"),
    use = document.createElementNS(ns, "use");
  n.setAttribute("class", "ico");
  n.setAttribute("aria-hidden", "true");
  use.setAttribute("href", "#i-" + name);
  n.append(use);
  return n;
}

function commandButton(action, name, glyph, id) {
  const b = el("button");
  b.type = "button";
  b.dataset.action = action;
  if (id) b.dataset.task = id;
  if (glyph) b.append(icon(glyph));
  b.append(el("span", "", name));
  return b;
}

function message(id, text) {
  if ($(id).textContent !== text) $(id).textContent = text;
  $(id).hidden = !text;
}

function taskMessage(task) {
  switch (task.status) {
    case "starting":
      return t("msgStarting");
    case "running":
      return task.progress || t("msgRunning");
    case "queued":
      return task.progress || t("msgQueued");
    case "paused":
      return task.progress || task.waiting_for || t("msgPaused");
    case "waiting_for_answer":
      return task.question || t("msgQuestion");
    case "waiting_on_phone":
      return (
        task.message ||
        task.progress ||
        task.reason ||
        t(task.waiting_for === "confirmation" ? "msgConfirm" : "msgHandoff")
      );
    case "done":
      return task.answer || t("msgNoAnswer");
    case "failed":
      return task.reason || task.failure_kind || t("msgNoReason");
    case "cancelled":
      return t("msgCancelled");
  }
}

function verification(task) {
  return t(task.verified === true ? "verified" : task.verified === false ? "verifyFailed" : "verifyUnknown");
}

function noteProgress(tasks) {
  const ids = new Set(tasks.map(task => task.task_id));
  for (const map of [observed, exchanges, expandedGoals])
    for (const id of map.keys()) if (!ids.has(id)) map.delete(id);
  for (const task of tasks) {
    // Finished snapshots are results, not an archive of earlier phone steps.
    if (!isActive(task)) continue;
    // Compare phone fields. Translating a fallback message does not change what the phone reported.
    const { status, step, progress, question, message, reason, waiting_for } = task;
    const item = { status, step, progress, question, message, reason, waiting_for };
    let history = observed.get(task.task_id);
    if (!history) {
      history = { rows: [], trimmed: false };
      observed.set(task.task_id, history);
    }
    const prev = history.rows.at(-1);
    if (prev && Object.keys(item).every(field => prev[field] === item[field])) continue;
    history.rows.push({ ...item, at: Date.now() });
    if (history.rows.length > 60) {
      history.rows.shift();
      history.trimmed = true;
    }
  }
}

function mergeHistoryGroups() {
  const merged = groupTasks(overview?.tasks || []);
  for (const chat of savedChats.values()) {
    let g = merged.find(item => item.key === "chat:" + chat.id);
    if (!g) {
      g = { key: "chat:" + chat.id, turns: [], primary: null };
      merged.push(g);
    }
    g.title = chat.title;
    g.updated = chat.updated_at_ms;
    g.archived = chat.archived === true;
    g.conversation = chat.id;
  }
  for (const g of merged) {
    g.conversation ||= g.primary?.conversation;
    const saved = savedTurns.get(g.conversation);
    if (saved) {
      const remote = new Map(g.turns.filter(task => task.run_id).map(task => [task.run_id, task]));
      const remoteIds = new Map(g.turns.map(task => [task.task_id, task]));
      const ids = new Set(saved.turns.map(task => task.run_id).filter(Boolean));
      const sources = new Set(saved.turns.map(task => task.source_task_id).filter(Boolean));
      g.turns = [
        ...saved.turns.map(task => {
          const current = remote.get(task.run_id) || remoteIds.get(task.source_task_id);
          return current ? { ...task, ...current, read_only: false } : task;
        }),
        ...g.turns.filter(task => !ids.has(task.run_id) && !sources.has(task.task_id))
      ].sort((a, b) => a.turn_index != null && b.turn_index != null
        ? a.turn_index - b.turn_index : (a.started_at_ms || 0) - (b.started_at_ms || 0));
      g.primary = [...g.turns].reverse().sort((a, b) => urgency(a) - urgency(b))[0] || null;
    }
  }
  groups = merged.sort((a, b) => (a.primary ? urgency(a.primary) : 2) - (b.primary ? urgency(b.primary) : 2) ||
    (b.updated || b.primary?.started_at_ms || 0) - (a.updated || a.primary?.started_at_ms || 0));
}

async function loadSavedTurns(conversation, offset = 0, at = revision) {
  try {
    const page = await call("history_turns", { conversation, offset: String(offset) });
    if (at !== revision || !overview?.history_enabled) return;
    const inspecting = byId(group()?.turns, inspectId);
    const previous = savedTurns.get(conversation)?.turns || [];
    const turns = new Map(previous.filter(task => task.turn_index < page.total && (offset !== 0 || !task.live))
      .map(task => [task.turn_index, task]));
    for (const task of page.turns) turns.set(task.turn_index, task);
    const ordered = [...turns.values()].sort((a, b) => a.turn_index - b.turn_index);
    // Offsets count backwards from the newest turn. Older cached pages can be
    // separated from that end by a gap after the phone adds new turns.
    let contiguous = 0;
    while (turns.has(page.total - contiguous - 1)) contiguous++;
    savedTurns.set(conversation, { turns: ordered, next_offset: contiguous < page.total ? contiguous : null });
    mergeHistoryGroups();
    if (inspecting?.source_task_id && !byId(group()?.turns, inspectId)) {
      const replacement = group()?.turns.find(task => task.source_task_id === inspecting.source_task_id);
      if (replacement) { inspectId = replacement.task_id; recordView = recordBody = null; recordRequest++; bodyRequest++; }
    }
  } catch (error) {
    if (at !== revision) return;
    if (error.code === "history_disabled") forgetHistory();
    else if (error.http_status === 404) {
      savedChats.delete(conversation); savedTurns.delete(conversation); mergeHistoryGroups();
    } else message("notice", t("historyFailed"));
  }
}

function forgetHistory() {
  if (overview) overview.history_enabled = false;
  savedChats.clear(); savedTurns.clear(); moreChats = null;
  recordView = recordBody = null;
  recordRequest++; bodyRequest++;
  mergeHistoryGroups();
}

async function loadSavedRun(task, offset = 0) {
  if (!task?.run_id || !overview?.history_enabled || task.task_id !== inspectId) return;
  const request = ++recordRequest;
  const prior = recordView?.taskId === task.task_id && recordView.offset === offset ? recordView.data : null;
  recordView = { taskId: task.task_id, offset, ...(prior ? { data: prior } : { loading: true }) };
  renderProcess();
  try {
    const data = await call("history_run", { conversation: task.conversation, run_id: task.run_id, offset: String(offset) });
    if (request !== recordRequest || inspectId !== task.task_id) return;
    recordView = { taskId: task.task_id, offset, data };
  } catch (error) {
    if (request !== recordRequest || inspectId !== task.task_id) return;
    if (error.code === "history_disabled") forgetHistory();
    else recordView = { taskId: task.task_id, offset, error: error.http_status === 404 ? "runMissing" : "historyFailed" };
  }
  render();
  schedule();
}

async function loadTraceBody(span, offset = 0) {
  const task = byId(group()?.turns, inspectId);
  if (!task?.run_id || !overview?.history_enabled) return;
  const request = ++bodyRequest;
  recordBody = { span, offset, loading: true };
  renderProcess();
  try {
    const page = await call("history_body", { conversation: task.conversation, run_id: task.run_id, span_id: span, offset: String(offset) });
    if (request !== bodyRequest || inspectId !== task.task_id) return;
    recordBody = { span, offset, ...page };
  } catch (error) {
    if (request !== bodyRequest || inspectId !== task.task_id) return;
    if (error.code === "history_disabled") forgetHistory();
    else recordBody = { span, offset, error: "historyFailed" };
  }
  render();
}

function pageButtons(offset, next, size, kind) {
  const bar = el("div", "trace-pages");
  for (const [name, value] of [["previousPage", Math.max(0, offset - size)], ["nextPage", next]]) {
    const button = el("button", "", t(name));
    button.type = "button";
    button.dataset[kind] = String(value ?? 0);
    button.disabled = !online || (name === "previousPage" ? offset === 0 : next == null);
    bar.append(button);
    if (name === "previousPage") bar.append(el("span", "", t("pageN", { n: Math.floor(offset / size) + 1 })));
  }
  return bar;
}

function renderSavedRun(content, task) {
  if (!overview?.capabilities.includes("history")) return;
  const box = el("div", "saved-run");
  content.append(processSection(t("traceTitle"), box));
  if (!overview.history_enabled) { box.append(el("p", "process-note", t("historyOff"))); return; }
  if (!task.run_id) { box.append(el("p", "process-note", t("runMissing"))); return; }
  const view = recordView?.taskId === task.task_id ? recordView : null;
  if (!view || (view.loading && !view.data)) { box.append(el("p", "process-note", t("historyLoading"))); return; }
  if (view.error) {
    box.append(el("p", "process-note", t(view.error)));
    const retry = el("button", "", t("retryHistory"));
    retry.type = "button"; retry.dataset.recordPage = String(view.offset); box.append(retry);
    return;
  }
  const data = view.data;
  if (!data.trace_available) box.append(el("p", "process-note", t("traceMissing")));
  else if (data.recording || data.trace_status === "partial")
    box.append(el("p", "process-note", t(data.recording ? "traceRecording" : "tracePartial")));
  if (data.trace_available && !data.rows.length) box.append(el("p", "process-note", t("traceEmpty")));
  const count = value => value == null ? t("unknown") : new Intl.NumberFormat().format(value);
  for (const row of data.rows) {
    const entry = el("div", "trace-entry");
    const title = row.attempt == null ? t("stepN", { n: row.step ?? 0 }) + " · " + row.name
      : t("requestN", { step: row.step ?? 0, attempt: row.attempt, name: row.name });
    entry.append(el("h4", "", title));
    const status = t(({ ok: "traceOk", error: "traceError", cancelled: "traceCancelled" })[row.status] || "traceIncomplete");
    entry.append(el("p", "process-note", t("traceTiming", { status, ms: count(row.duration_ms) })));
    if (row.attempt != null) entry.append(el("p", "trace-tokens", t("traceTokens", {
      input: count(row.input), cached: count(row.cached), uncached: count(row.uncached), output: count(row.output)
    })));
    if (row.difference) entry.append(el("p", "process-note", t("traceDifference", { path: row.difference })));
    if (row.context_change) entry.append(el("p", "process-value", row.context_change));
    if (row.captured) {
      const selected = recordBody?.span === row.id;
      const button = el("button", "trace-body-toggle", t(selected ? "closeBody" : "traceBody"));
      button.type = "button"; button.dataset.bodySpan = row.id;
      button.setAttribute("aria-expanded", String(selected));
      entry.append(button);
      if (selected) {
        if (recordBody.loading || recordBody.error)
          entry.append(el("p", "process-note", t(recordBody.error || "historyLoading")));
        else {
          entry.append(el("pre", "trace-body", recordBody.text));
          entry.append(pageButtons(recordBody.offset, recordBody.next_offset, 3000, "bodyPage"));
        }
      }
    } else if (row.attempt != null) entry.append(el("p", "process-note", t("traceNoBody")));
    box.append(entry);
  }
  const events = el("details", "saved-events");
  events.dataset.record = "events";
  events.append(el("summary", "", t("savedEvents")));
  if (!data.record_available) events.append(el("p", "process-note", t("runMissing")));
  for (const [i, event] of data.events.entries()) {
    const detail = el("details", "trace-event");
    detail.dataset.record = "event-" + (view.offset + i);
    const name = (event.step == null ? "" : t("stepN", { n: event.step }) + " · ") + event.type;
    detail.append(el("summary", "", name), el("pre", "trace-body", event.detail));
    if (event.truncated) detail.append(el("p", "process-note", t("clippedRecord")));
    events.append(detail);
  }
  box.append(events);
  if (view.offset || data.next_offset != null) box.append(pageButtons(view.offset, data.next_offset, 20, "recordPage"));
}

function renderNav() {
  $("new-chat").setAttribute("aria-current", String(groupKey === fresh));
  $("count").textContent = groups.length || "";
  $("count").title = t(overview?.history_enabled ? "historyHint" : "recentHint");
  const rows = groups.map(g => {
    const task = g.primary;
    const shortLabel = { waiting_for_answer: "attention", waiting_on_phone: "handoff" }[task?.status];
    const marker = task && isActive(task) ? t(shortLabel || task.status) : "";
    return { key: g.key, name: g.title || label(g.turns[0]), count: g.turns.length, marker, archived: g.archived };
  });
  const signature = JSON.stringify([rows, groupKey, busy, !!overview, moreChats, overview?.history_enabled]);
  if (signature === navSignature) return;
  navSignature = signature;
  const list = $("threads"),
    focus = document.activeElement?.dataset.group;
  list.replaceChildren();
  if (!rows.length) list.append(el("p", "nav-empty", t(overview ? "noChats" : "loading")));
  for (const row of rows) {
    const b = el("button", "conversation");
    b.type = "button";
    b.dataset.group = row.key;
    b.disabled = busy;
    b.setAttribute("aria-current", String(row.key === groupKey));
    b.title = row.name + (row.archived ? " · " + t("archivedChat") : "") + (row.count ? " · " + t("rounds", { n: row.count }) : "");
    b.append(icon("chat"), el("span", "name", row.name));
    if (row.archived) b.append(el("small", "archive-mark", t("archivedChat")));
    if (row.marker) b.append(el("small", "", row.marker));
    list.append(b);
    if (focus === row.key && !busy) b.focus({ preventScroll: true });
  }
  if (moreChats != null) {
    const more = commandButton("history-list", t("olderChats"));
    more.disabled = busy || !online;
    list.append(more);
  }
  if (overview?.capabilities.includes("history") && !overview.history_enabled)
    list.append(el("p", "nav-empty", t("historyOff")));
}

function scrollAnchor() {
  const sc = $("scroll-thread"),
    top = sc.getBoundingClientRect().top;
  const child = Array.from($("transcript").children).find(
    n => n.dataset.id && n.getBoundingClientRect().bottom > top
  );
  return {
    end: sc.scrollHeight - sc.clientHeight - sc.scrollTop < 28,
    id: child?.dataset.id,
    offset: child ? child.getBoundingClientRect().top - top : 0
  };
}

function restoreScroll(a, reset = false) {
  const sc = $("scroll-thread");
  if (reset || a.end) {
    sc.scrollTop = sc.scrollHeight;
    return;
  }
  const n = Array.from($("transcript").children).find(item => item.dataset.id === a.id);
  if (n) sc.scrollTop += n.getBoundingClientRect().top - sc.getBoundingClientRect().top - a.offset;
}

function addTurn(task, index) {
  const id = task.task_id,
    node = el("article", "turn");
  node.dataset.id = id;
  const turn = t("round", { n: (task.turn_index ?? index) + 1 });
  node.setAttribute("aria-label", turn);
  node.append(el("div", "turn-label", turn + (task.started_at_ms ? " · " + date(task.started_at_ms) : "")));
  const request = el("div", "request"),
    goal = task.request_text ?? (task.goal || t("remoteTask")),
    chars = Array.from(goal),
    long = chars.length > 160;
  if (task.input_files?.length) {
    const tray = el("div", "file-tray");
    for (const file of task.input_files) {
      const chip = el("div", "file-chip");
      chip.append(icon("attach"), el("span", "file-name", file.name), el("small", "", file.size_label));
      chip.title = file.name;
      tray.append(chip);
    }
    request.append(tray);
  }
  if (goal) request.append(el("p", "", long && !expandedGoals.has(id) ? chars.slice(0, 150).join("") + "…" : goal));
  if (long) {
    const b = commandButton("goal", t(expandedGoals.has(id) ? "collapseGoal" : "expandGoal"), "", id);
    b.className = "expand-text";
    b.setAttribute("aria-expanded", String(expandedGoals.has(id)));
    request.append(b);
  }
  node.append(request);
  for (const item of exchanges.get(id) || []) {
    if (item.question) node.append(el("p", "guidance", item.question));
    const msg = el("div", "request extra-input");
    msg.append(el("small", "", t(item.mode === "answer" ? "yourAnswer" : "yourSteer")), el("p", "", item.text));
    node.append(msg);
  }
  const author = el("div", "author"),
    logo = el("img");
  logo.src = "/mika-logo.png";
  logo.width = 18;
  logo.height = 18;
  logo.alt = "";
  const state = el("span", "state", t(task.status));
  state.dataset.tone = tones[task.status];
  author.append(logo, el("span", "", "Mika"), state);
  if (task.status === "done") {
    const mark = el("span", "verification", "· " + verification(task));
    mark.dataset.tone = task.verified === true ? "good" : task.verified === false ? "warn" : "";
    author.append(mark);
  } else if (task.status === "running" && task.step != null)
    author.append(el("span", "state", t("stepN", { n: task.step })));
  const process = commandButton("process", t("viewProcess"), "chevron", id);
  process.className = "turn-process";
  const slot = el("div");
  slot.id = processSlotId(id);
  slot.hidden = true;
  process.setAttribute("aria-controls", slot.id);
  process.setAttribute("aria-expanded", "false");
  node.append(author, process, slot, el("p", "answer", taskMessage(task)));
  if (task.text_truncated) node.append(el("p", "guidance", t("clippedRecord")));
  if (task.status === "paused" && task.progress && task.waiting_for && task.progress !== task.waiting_for) {
    node.append(el("p", "guidance", t("waitingCondition") + " · " + task.waiting_for));
  }
  if (task.status === "waiting_on_phone")
    node.append(el("p", "guidance", [task.app, t("handoffGuide")].filter(Boolean).join(" · ")));
  if (task.files?.length) {
    const list = el("div", "result-files");
    for (const file of task.files) {
      const button = commandButton("download", file.name, "download", id);
      button.querySelector("span").className = "file-name";
      button.dataset.fileId = file.id;
      button.setAttribute("aria-label", t("downloadFile", { name: file.name }));
      button.title = button.getAttribute("aria-label");
      button.disabled = !online || busy;
      button.append(el("small", "", fileSize(file.size)));
      list.append(button);
    }
    node.append(list);
  }
  const tools = el("div", "turn-tools");
  if (task.status === "done" && task.answer) tools.append(commandButton("copy", t("copyResult"), "copy", id));
  if (isActive(task) && !task.read_only) {
    if (
      currentTask()?.task_id !== id &&
      (task.status === "waiting_for_answer" ||
        (task.status === "running" && overview?.capabilities.includes("task_steer")))
    )
      tools.append(commandButton("target", t("targetTurn"), "reply", id));
    const stop = commandButton("cancel", t("cancelTask"), "", id);
    stop.disabled = busy || !online;
    tools.append(stop);
  }
  if (tools.childElementCount) node.append(tools);
  return node;
}

function renderTurns() {
  const g = group(),
    container = $("transcript");
  const signature = JSON.stringify([
    groupKey,
    g?.turns || [],
    !!overview,
    online,
    busy,
    preferredTask,
    [...expandedGoals],
    g?.turns.map(task => exchanges.get(task.task_id)?.length || 0)
  ]);
  if (signature === transcriptSignature) {
    renderProcess();
    return;
  }
  transcriptSignature = signature;
  const reset = transcriptKey !== groupKey;
  transcriptKey = groupKey;
  const pos = scrollAnchor(),
    focus = document.activeElement,
    ft = focus?.dataset.task,
    fa = focus?.dataset.action;
  const focusedInProcess = processContent.contains(focus);
  const focusedTurn = focus?.matches(".turn") && focus.dataset.id;
  container.replaceChildren();
  if (g) {
    if (!g.turns.length) container.append(el("p", "guidance", t("historyLoading")));
    if (savedTurns.get(g.conversation)?.next_offset != null) {
      const more = commandButton("history-turns", t("olderTurns"));
      more.disabled = busy || !online;
      container.append(more);
    }
    g.turns.forEach((task, index) => container.append(addTurn(task, index)));
  } else {
    const empty = el("div", "empty"),
      missing = !!groupKey && groupKey !== fresh && !!overview;
    empty.append(
      el("h2", "", t(missing ? "unavailableChat" : overview ? "newHeading" : "loading")),
      el("p", "", t(missing ? "unavailableHint" : online ? "newHint" : "connectHint"))
    );
    container.append(empty);
  }
  renderProcess();
  if (focusedInProcess && inspectId) {
    const replacement = focus.isConnected
      ? focus
      : processContent.querySelector(focus.matches("summary") ? "summary" : '.event-choice[aria-pressed="true"]');
    replacement?.focus({ preventScroll: true });
  } else if (focusedTurn) {
    const turn = $(processSlotId(focusedTurn))?.closest(".turn");
    if (turn) {
      turn.tabIndex = -1;
      turn.focus({ preventScroll: true });
    }
  } else if (ft && fa)
    Array.from(container.querySelectorAll("button"))
      .find(b => b.dataset.task === ft && b.dataset.action === fa)
      ?.focus({ preventScroll: true });
  restoreScroll(pos, reset);
}

function processSection(name, child) {
  const sec = el("section", "process-section");
  sec.append(el("h3", "", name), child);
  return sec;
}

function renderProcess() {
  const g = group(),
    task = byId(g?.turns, inspectId);
  if (inspectId && !task) inspectId = null;
  const open = !!task;
  const toggle = $("toggle-process");
  toggle.hidden = !g;
  const target = task || currentTask();
  if (target) toggle.setAttribute("aria-controls", processSlotId(target.task_id));
  else toggle.removeAttribute("aria-controls");
  toggle.setAttribute("aria-expanded", String(open));
  toggle.querySelector("span").textContent = t(open ? "closeProcess" : "viewProcess");
  for (const button of $("transcript").querySelectorAll('[data-action="process"]')) {
    const expanded = open && button.dataset.task === inspectId;
    const slot = $(button.getAttribute("aria-controls"));
    button.setAttribute("aria-expanded", String(expanded));
    button.querySelector("span").textContent = t(expanded ? "closeProcess" : "viewProcess");
    slot.hidden = !expanded;
    if (expanded && processContent.parentElement !== slot) slot.append(processContent);
  }
  processContent.hidden = !open;
  if (!open) {
    processSignature = "";
    return;
  }
  const hist = observed.get(inspectId),
    n = (task.turn_index ?? g.turns.indexOf(task)) + 1;
  const rows = hist?.rows || [],
    focusedRow = rows.includes(selectedObservation) ? selectedObservation : rows.at(-1);
  const sig = JSON.stringify([task, n, rows.length, rows.at(-1)?.at, hist?.trimmed, rows.indexOf(focusedRow), recordView, recordBody, overview?.history_enabled]);
  if (sig === processSignature) return;
  processSignature = sig;
  const content = processContent,
    keepDetails = content.dataset.id === inspectId && content.querySelector(".process-facts")?.open;
  const openRecords = new Set(content.dataset.id === inspectId
    ? Array.from(content.querySelectorAll("details[data-record][open]"), n => n.dataset.record) : []);
  const focusStep =
    content.contains(document.activeElement) && document.activeElement.hasAttribute("data-observation");
  const focused = content.contains(document.activeElement) ? document.activeElement : null;
  const focusAttribute = focused && ["data-body-span", "data-body-page", "data-record-page"].find(name => focused.hasAttribute(name));
  const focusValue = focusAttribute ? focused.getAttribute(focusAttribute) : null;
  const focusRecord = focused?.tagName === "SUMMARY" ? focused.parentElement.dataset.record : null;
  content.dataset.id = inspectId;
  content.setAttribute("aria-label", t("round", { n }) + " · " + t("process"));
  content.replaceChildren();
  renderSavedRun(content, task);
  for (const detail of content.querySelectorAll("details[data-record]")) detail.open = openRecords.has(detail.dataset.record);
  const timeline = el("div");
  if (rows.length) {
    const list = el("ol", "events");
    for (const [i, row] of rows.entries()) {
      const item = el("li"),
        button = el("button", "event-choice");
      button.type = "button";
      button.dataset.observation = String(i);
      button.setAttribute("aria-pressed", String(row === focusedRow));
      const description = taskMessage(row);
      button.append(
        el("span", "event-step", row.step != null ? t("stepN", { n: row.step }) : t(row.status)),
        el("span", "event-title", description)
      );
      button.title = description;
      item.append(button);
      list.append(item);
    }
    timeline.append(list);
    if (hist.trimmed) timeline.append(el("p", "process-note", t("recentChanges", { n: hist.rows.length })));
  } else timeline.append(el("p", "process-note", t(isActive(task) ? "observingNow" : "noSteps")));
  if (rows.length || !recordView?.data?.record_available) content.append(processSection(t("observed"), timeline));
  if (focusedRow) {
    const reading = el("div", "event-reading");
    reading.append(
      el("div", "observed-at", clock(focusedRow.at) + " · " + t(focusedRow.status)),
      el("p", "event-body", taskMessage(focusedRow))
    );
    content.append(processSection(t("observationText"), reading));
  }
  if (task.status === "failed" || task.status === "cancelled")
    content.append(processSection(t("phoneReport"), el("p", "process-value", taskMessage(task))));
  if (task.status === "done")
    content.append(
      processSection(
        t("verification"),
        el(
          "p",
          "process-value",
          [verification(task), task.verification_note, ...(task.unconfirmed || [])].filter(Boolean).join("\n")
        )
      )
    );
  if (task.steers?.length)
    content.append(
      processSection(
        t("steerRecords"),
        el(
          "p",
          "process-value",
          task.steers.map((s, i) => i + 1 + ". " + (STRINGS[s.state] ? t(s.state) : s.state)).join("\n")
        )
      )
    );
  const details = el("details", "process-facts");
  details.open = !!keepDetails;
  details.append(el("summary", "", t("original")));
  const fields = el("dl");
  if (task.started_at_ms) fields.append(el("dt", "", t("startAt")), el("dd", "", date(task.started_at_ms)));
  fields.append(
    el("dt", "", t("taskGoal")),
    el("dd", "", task.goal || "—"),
    el("dt", "", t("taskId")),
    el("dd", "", task.task_id)
  );
  details.append(fields);
  content.append(details);
  if (focusStep) content.querySelector('.event-choice[aria-pressed="true"]')?.focus({ preventScroll: true });
  else if (focusAttribute) content.querySelector(`[${focusAttribute}="${CSS.escape(focusValue)}"]`)?.focus({ preventScroll: true });
  else if (focusRecord) content.querySelector(`[data-record="${CSS.escape(focusRecord)}"] > summary`)?.focus({ preventScroll: true });
}

function toggleProcess(id) {
  const slot = $(processSlotId(id));
  if (!slot) return;
  const trigger = slot.previousElementSibling;
  const scroller = $("scroll-thread");
  const viewport = scroller.getBoundingClientRect();
  const previousTop = trigger.getBoundingClientRect().top;
  inspectId = inspectId === id ? null : id;
  recordView = recordBody = null;
  recordRequest++;
  bodyRequest++;
  selectedObservation = null;
  setNav(false);
  renderProcess();
  // Keep the clicked line in place when closing an earlier disclosure above it.
  if (previousTop >= viewport.top && previousTop < viewport.bottom) {
    scroller.scrollTop += trigger.getBoundingClientRect().top - previousTop;
  }
  if (inspectId) {
    loadSavedRun(byId(group()?.turns, inspectId));
    const triggerTop = trigger.getBoundingClientRect().top;
    if (triggerTop < viewport.top || slot.getBoundingClientRect().bottom > viewport.bottom) {
      scroller.scrollTop += triggerTop - viewport.top - 8;
    }
  }
}

function composeTarget() {
  const g = group(),
    task = currentTask(g);
  if (!g) {
    if (groupKey && groupKey !== fresh) return { kind: "wait", mode: "unavailableChat", hint: "unavailableHint" };
    return { kind: "run", key: fresh, mode: "newChat", hint: "newPlaceholder", send: "submit" };
  }
  if (!task) return { kind: "wait", mode: "historyLoading", hint: "historyLoading" };
  if (task.read_only && (isActive(task) || !task.conversation?.startsWith("mcp-")))
    return { kind: "wait", mode: "phoneReadOnly", hint: "phoneReadOnly" };
  const turn = g.turns.indexOf(task) + 1;
  if (task.status === "waiting_for_answer")
    return {
      kind: "answer",
      key: JSON.stringify(["answer", task.task_id, task.question]),
      task,
      turn,
      mode: "reply",
      hint: "askPlaceholder",
      send: "sendAnswer",
      options: task.options
    };
  if (task.status === "running" && overview?.capabilities.includes("task_steer"))
    return {
      kind: "steer",
      key: "steer:" + task.task_id,
      task,
      turn,
      mode: "steer",
      hint: "steerPlaceholder",
      send: "sendSteer"
    };
  if (isActive(task))
    return {
      kind: "wait",
      task,
      turn,
      mode: task.status,
      hint: task.status === "waiting_on_phone" ? "onPhonePlaceholder" : "waitingPlaceholder"
    };
  if (task.conversation)
    return {
      kind: "run",
      key: "continue:" + g.key,
      conversation: task.conversation,
      mode: "continueChat",
      hint: "followPlaceholder",
      send: "sendContinue"
    };
  return { kind: "wait", mode: "noConversation", hint: "noConversation" };
}
function fileSize(bytes) {
  const scale = bytes >= 1048576 ? 1048576 : bytes >= 1024 ? 1024 : 1;
  return new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 }).format(bytes / scale) +
    " " + ({ 1: "B", 1024: "KiB", 1048576: "MiB" })[scale];
}

function addFiles(items) {
  const route = composeTarget();
  if (busy || route.kind !== "run") return;
  if (!overview?.capabilities.includes("task_files")) {
    message("notice", t("filesNeedUpdate"));
    return;
  }
  const files = [...(attachmentDrafts.get(route.key) || []), ...items];
  if (files.length > 9 || files.reduce((n, f) => n + f.size, 0) > 200 * 1048576) {
    message("notice", t("fileLimit"));
    return;
  }
  attachmentDrafts.set(route.key, files);
  syncComposer();
  $("goal").focus();
}

let shownFiles, traySignature = "";
function syncAttachments(route) {
  const files = attachmentDrafts.get(route.key);
  const supported = !!overview?.capabilities.includes("task_files");
  $("attach-file").hidden = !supported || route.kind !== "run";
  $("attach-file").disabled = busy || !online;
  const signature = [route.key, busy, lang].join("|");
  if (shownFiles !== files || signature !== traySignature) {
    shownFiles = files;
    traySignature = signature;
    const tray = $("attachments");
    tray.replaceChildren();
    for (const [index, file] of (files || []).entries()) {
      const chip = el("div", "file-chip");
      const remove = commandButton("remove", "", "x");
      remove.lastElementChild.remove();
      remove.disabled = busy;
      remove.setAttribute("aria-label", t("removeFile", { name: file.name }));
      remove.addEventListener("click", () => {
        attachmentDrafts.set(route.key, files.filter((_, i) => i !== index));
        syncComposer();
        $("goal").focus();
      });
      chip.title = file.name;
      chip.append(icon("attach"), el("span", "file-name", file.name), el("small", "", fileSize(file.size)), remove);
      tray.append(chip);
    }
  }
  return files?.length || 0;
}

$("attach-file").addEventListener("click", () => $("file-picker").click());
$("file-picker").addEventListener("change", () => {
  addFiles(Array.from($("file-picker").files));
  $("file-picker").value = "";
});
$("goal").addEventListener("paste", event => {
  const clipboard = event.clipboardData;
  if (!clipboard || composeTarget().kind !== "run") return;
  let images = Array.from(clipboard.files).filter(file => file.type.startsWith("image/"));
  if (!images.length) images = Array.from(clipboard.items)
    .filter(item => item.kind === "file" && item.type.startsWith("image/"))
    .map(item => item.getAsFile()).filter(Boolean);
  if (!images.length) return;
  event.preventDefault();
  addFiles(images.map(file => {
    if (file.name) return file;
    const suffix = ({ "image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp", "image/gif": ".gif" })[file.type] || "";
    return new File([file], "clipboard-image" + suffix, { type: file.type });
  }));
});

let optionsSignature = "";
function syncComposer() {
  const route = composeTarget(),
    goal = $("goal"),
    key = route.key || "";
  if (key !== composerKey) {
    goal.value = drafts.get(key) || "";
    composerKey = key;
  }
  goal.placeholder = t(route.hint);
  goal.maxLength = route.kind === "answer" ? 2000 : 4000;
  goal.readOnly = busy;
  goal.disabled = route.kind === "wait";
  $("compose-mode").textContent = (route.turn ? t("round", { n: route.turn }) + " · " : "") + t(route.mode);
  $("compose-label").textContent = t(route.send || "message");
  $("submit-goal").setAttribute("aria-label", t(route.send || "submit"));
  $("submit-goal").title = t(route.send || "submit");
  const attached = syncAttachments(route);
  goal.required = !attached;
  $("submit-goal").disabled = !online || busy || route.kind === "wait" || (route.kind === "run" && !!pendingRun) || (!goal.value.trim() && !attached) ||
    (!!attached && !overview?.capabilities.includes("task_files"));
  const opts = Array.isArray(route.options) ? route.options : [];
  const sig = JSON.stringify([key, opts, busy, online]);
  if (sig === optionsSignature) return;
  optionsSignature = sig;
  const box = $("options"),
    focused = box.contains(document.activeElement) ? document.activeElement.textContent : null;
  box.replaceChildren();
  for (const option of opts) {
    const b = el("button", "", option);
    b.type = "button";
    b.disabled = busy || !online;
    b.addEventListener("click", () => {
      goal.value = option;
      goal.dispatchEvent(new Event("input"));
      goal.focus();
    });
    box.append(b);
    if (focused === option && !b.disabled) b.focus({ preventScroll: true });
  }
}

function render() {
  const g = group();
  const title = g ? g.title || label(g.turns[0]) : t(groupKey && groupKey !== fresh ? "heading" : "newChat");
  $("thread-title").textContent = title;
  $("thread-title").title = title;
  $("thread-meta").textContent = g ? t("rounds", { n: g.turns.length }) : "";
  $("new-chat").disabled = busy;
  $("refresh").disabled = busy;
  $("pick-device").disabled = busy;
  $("pending-run").hidden = !pendingRun || busy;
  if (pendingRun) {
    message("pending-label", t("pendingRun", { id: pendingRun.fields.request_id }));
    $("lookup-run").disabled = !online;
    $("retry-run").disabled = !online || !overview?.capabilities.includes("task_recovery");
  }
  renderNav();
  renderTurns();
  syncComposer();
}

const errorNames = {
  session: "errSession",
  device_selection: "errDeviceSelection",
  device_pinned: "errDevicePinned",
  adb: "errAdb",
  adb_unavailable: "errAdbUnavailable",
  device_offline: "errDeviceOffline",
  stale_connection: "errStale",
  connection_info: "errConnectionInfo",
  connection: "errConnection",
  desk_offline: "errDeskOffline",
  outcome_unknown: "errOutcomeUnknown",
  conflict: "errConflict"
};
// device_offline carries the phone's ADB state; any other state keeps the generic text.
const deviceStates = new Map([
  ["unauthorized", "errDeviceUnauthorized"],
  ["offline", "errDeviceReconnect"]
]);
function connectionState(phase, labelKey) {
  const d = $("connection-details");
  if (d.dataset.phase !== phase) {
    if (phase !== "connected" && !d.open) {
      d.open = true;
      autoDisclosure = true;
    } else if (phase === "connected" && autoDisclosure) {
      d.open = false;
      autoDisclosure = false;
    }
    d.dataset.phase = phase;
  }
  $("connection-line").textContent = t(labelKey);
}

function updateDevice(next) {
  $("pick").hidden = true;
  $("device-label").textContent = t(next.transport === "adb" ? "adbPhone" : "networkPhone");
  $("target").textContent = next.target || "—";
  $("connection").textContent = t("connected") + (next.server_version ? " · " + next.server_version : "");
  const d = next.device || {};
  const state = t(
    d.ready === true
      ? "unlocked"
      : d.device_locked || d.keyguard_showing
        ? "locked"
        : d.interactive === false
          ? "screenOff"
          : "unknown"
  );
  $("device").textContent = [
    state,
    d.helper_available == null ? "" : t(d.helper_available ? "helperOnline" : "helperOffline")
  ]
    .filter(Boolean)
    .join(" · ");
  connectionState("connected", "online");
  $("updated").textContent = t("updated", { time: clock(Date.now()) });
}

function showProblem(e) {
  if (e.code === "upload_failed") { message("notice", e.message); return; }
  const key = (e.code === "device_offline" && deviceStates.get(e.state)) || errorNames[e.code];
  const detail = e.code === "outcome_unknown" && pendingRun && (!e.task_id || e.task_id === pendingRun.fields.request_id)
    ? t("lostReceipt") : key ? t(key) : e.message || t("errFallback");
  if (e.code === "outcome_unknown") writeWarning = detail;
  // A phone rejecting an outdated action is still a connected phone.
  if (e.http_status === 409 && e.code !== "device_selection" && e.code !== "device_pinned" && online) {
    message("notice", detail);
    return;
  }
  online = false;
  retryDelay = [401, 403, 429].includes(e.http_status) ? 60000 : Math.min(30000, retryDelay * 2 || 2000);
  const devices = e.code === "device_selection" ? e.devices || [] : [];
  const picker = $("devices");
  if (JSON.stringify(Array.from(picker.options, o => o.value)) !== JSON.stringify(devices)) {
    const prev = picker.value;
    picker.replaceChildren(...devices.map(serial => new Option(serial, serial)));
    if (devices.includes(prev)) picker.value = prev;
  }
  $("pick").hidden = !devices.length;
  $("connection").textContent = t(devices.length ? "choosing" : "offline");
  $("device").textContent = "—";
  connectionState(devices.length ? "choose" : "retry", devices.length ? "choosing" : "offline");
  message(
    "error",
    [writeWarning !== detail && writeWarning, detail, overview && t("keptList")].filter(Boolean).join(" ")
  );
}

async function call(action, fields = {}) {
  const write = !["overview", "select", "watch", "status", "discard_upload", "download"].includes(action) && !action.startsWith("history_");
  return deskRequest("/desk", JSON.stringify({ action, ...fields }), { "Content-Type": "application/json" }, write, action === "download", action);
}

async function deskRequest(path, body, headers, write = false, download = false, action = "") {
  let response, data;
  try {
    response = await fetch(path, { method: "POST", headers: { ...headers, "X-Mika-Desk": ticket || "" }, body });
    if (download && response.ok && response.headers.get("Content-Type") === "application/octet-stream") {
      return await response.blob();
    }
    if (response.headers.get("Content-Type")?.includes("application/json")) data = await response.json();
    if (action?.startsWith("history_") && [401, 403].includes(response.status)) forgetHistory();
  } catch {
    throw { code: write ? "outcome_unknown" : "desk_offline" };
  }
  if (data?.error) {
    if ([401, 403].includes(data.error.http_status) && overview?.history_enabled) forgetHistory();
    throw data.error;
  }
  if (!response.ok || !data) {
    if (response.status === 403) throw { code: "session" };
    throw { code: write && (response.ok || response.status >= 500) ? "outcome_unknown" : "desk_offline" };
  }
  return data;
}

async function refresh({ device, watchId, at = revision } = {}) {
  let next;
  if (device !== undefined) next = await call("select", { device });
  else if (watchId) next = await call("watch", { task_id: watchId });
  else next = await call("overview");
  if (at !== revision) return false;
  const recent = next.tasks.slice();
  const keep = inspectId || anchorId;
  // Saved turn identifiers are local to the history view; the task-control API accepts remote UUIDs.
  const savedId = keep && /^(run|turn|live)-/.test(keep);
  if (keep && !savedId && !byId(next.tasks, keep) && !byId(group()?.turns, keep)?.read_only && groupKey !== fresh) {
    try {
      const task = await call("status", { task_id: keep });
      if (at !== revision) return false;
      if (!groupKey || threadOf(task) === groupKey) next.tasks.push(task);
    } catch (e) {
      if (at !== revision) return false;
      if (![404, 410].includes(e.http_status)) throw e;
      if (keep === inspectId) inspectId = null;
      if (keep === preferredTask) preferredTask = null;
      if (keep === anchorId) {
        anchorId = null;
        sessionStorage.removeItem("selectedTask");
      }
      message("notice", t("taskGone"));
    }
  }
  noteProgress(next.tasks);
  if (next.history_enabled) {
    const page = next.history, previous = overview?.history_enabled ? overview.history : null;
    // The next page's offset is relative to the current ordering. Keep older
    // selections visible, but restart paging if the first page changed.
    if (!previous || previous.next_offset !== page.next_offset || previous.conversations.length !== page.conversations.length ||
        page.conversations.some((chat, i) => chat.id !== previous.conversations[i].id))
      moreChats = page.next_offset ?? null;
    if (page.next_offset == null) savedChats.clear();
    for (const chat of page.conversations) savedChats.set(chat.id, chat);
  } else {
    savedChats.clear(); savedTurns.clear(); moreChats = null; recordView = recordBody = null;
    recordRequest++; bodyRequest++;
  }
  overview = next;
  mergeHistoryGroups();
  const recovered = pendingRun && byId(next.tasks, pendingRun.fields.request_id);
  if (recovered) {
    const { key, fields } = pendingRun;
    const draft = drafts.get(key);
    // Confirm from the ordinary read without sending another command or replacing an edited draft.
    const atSubmission = groupKey === (fields.conversation ? threadOf(recovered) : fresh);
    acknowledgeSubmission(recovered, key, fields.goal, atSubmission && (!draft || draft.trim() === fields.goal) && !inspectId);
    message("notice", t("recoveredRun", { id: recovered.task_id }));
  }
  if (!groupKey) {
    const legacy = byId(next.tasks, anchorId),
      chosen = legacy ? threadOf(legacy) : groups[0]?.key || fresh;
    rememberGroup(chosen, legacy?.task_id || groups.find(g => g.key === chosen)?.turns.at(-1)?.task_id);
  }
  const current = byId(group()?.turns, preferredTask) || currentTask();
  if (current && anchorId !== current.task_id) rememberGroup(groupKey, current.task_id);
  online = true;
  retryDelay = 0;
  updateDevice(next);
  alerts.observe(recent);
  message("error", writeWarning);
  const selected = group();
  if (overview.history_enabled && selected?.conversation) {
    await loadSavedTurns(selected.conversation, 0, at);
    if (at !== revision) return false;
    const inspected = byId(group()?.turns, inspectId);
    if (inspected && (!recordView?.data || recordView.data.recording || isActive(inspected)))
      await loadSavedRun(inspected, recordView?.offset || 0);
  }
  return true;
}

function invalidate() {
  revision++;
  clearTimeout(updateTimer);
}

function watchedTask() {
  const sel = currentTask();
  return sel && !sel.read_only && isActive(sel) ? sel : overview?.tasks.find(isActive);
}

function schedule(immediate = false) {
  clearTimeout(updateTimer);
  if (busy || observing || (document.hidden && !alerts.watching())) return;
  updateTimer = setTimeout(() => observe(immediate), immediate ? 0 : retryDelay || (watchedTask() ? 750 : recordView?.data?.recording ? 4000 : 8000));
}

async function observe(immediate) {
  if (busy || observing || (document.hidden && !alerts.watching())) return;
  observing = true;
  const at = revision;
  try {
    if (await refresh({ watchId: online && !immediate ? watchedTask()?.task_id : undefined, at })) render();
  } catch (e) {
    if (at === revision) {
      showProblem(e);
      render();
    }
  } finally {
    observing = false;
    schedule(at !== revision);
  }
}

async function work(fn, write = false) {
  if (busy) return;
  busy = true;
  invalidate();
  if (write) {
    writeWarning = "";
    message("notice", "");
    message("error", "");
  }
  render();
  let refreshConflict = false;
  try {
    await fn();
  } catch (e) {
    showProblem(e);
    refreshConflict = e.http_status === 409 && online;
  } finally {
    busy = false;
    render();
    schedule(refreshConflict);
    if (pendingAlertTurn) {
      const task = pendingAlertTurn;
      pendingAlertTurn = null;
      openAlertTurn(task);
    }
  }
}

function acknowledgeSubmission(task, key, text, select) {
  if (drafts.get(key)?.trim() === text) {
    drafts.delete(key);
    if (composerKey === key) $("goal").value = "";
  }
  setPendingRun(null);
  writeWarning = "";
  if (select) {
    preferredTask = task.task_id;
    inspectId = null;
    rememberGroup(threadOf(task), task.task_id);
  }
}

function openSubmittedTask(task, key, text) {
  acknowledgeSubmission(task, key, text, true);
  const recent = overview?.tasks || [];
  const tasks = byId(recent, task.task_id)
    ? recent.map(old => old.task_id === task.task_id ? task : old)
    : [task, ...recent];
  overview = { ...overview, tasks };
  mergeHistoryGroups();
  noteProgress(tasks);
  render();
}

async function recoverSubmission(retry) {
  if (!pendingRun || !online) return;
  const { key, fields } = pendingRun;
  let opened = false;
  await work(async () => {
    let task;
    try {
      task = retry ? await call("run", fields) : await call("status", { task_id: fields.request_id });
    } catch (e) {
      if (!retry && e.http_status === 404) return message("notice", t("notReceived"));
      if (e.http_status !== 410) throw e;
      setPendingRun(null);
      writeWarning = "";
      message("error", "");
      return message("notice", t("removedRun"));
    }
    openSubmittedTask(task, key, fields.goal);
    opened = true;
    message("notice", "");
    await refresh();
  });
  if (opened) $(processSlotId(fields.request_id))?.closest(".turn")?.scrollIntoView({ block: "nearest" });
}
$("lookup-run").addEventListener("click", () => recoverSubmission(false));
$("retry-run").addEventListener("click", () => recoverSubmission(true));
$("forget-run").addEventListener("click", () => {
  if (!busy && confirm(t("forgetConfirm"))) {
    setPendingRun(null);
    writeWarning = "";
    message("error", "");
    message("notice", "");
    render();
  }
});

async function submit() {
  const route = composeTarget(),
    goal = $("goal"),
    text = goal.value.trim(),
    key = route.key;
  const files = attachmentDrafts.get(key) || [];
  if (!online || busy || (!text && !files.length) || !key || route.kind === "wait" || (route.kind === "run" && pendingRun)) return;
  await work(async () => {
    if (route.kind === "run") {
      const fields = { goal: text };
      if (route.conversation) fields.conversation = route.conversation;
      const uploads = [];
      let task;
      try {
        for (const file of files) {
          message("notice", t("uploadingFile", { name: file.name, index: uploads.length + 1, total: files.length }));
          try {
            const receipt = await deskRequest("/desk/upload", file, { "Content-Type": "application/octet-stream",
              "X-Mika-Filename": encodeURIComponent(file.name) });
            uploads.push(receipt.upload_id);
          } catch (error) {
            if (error.code === "session") throw error;
            throw { code: "upload_failed", message: t(error.code === "incompatible_api" ? "filesNeedUpdate" : "uploadRejected", { name: file.name }) };
          }
        }
        if (uploads.length) fields.attachments = uploads;
        if (overview.capabilities.includes("task_recovery")) {
          fields.request_id = crypto.randomUUID();
          setPendingRun({ key, fields });
        }
        task = await call("run", fields);
      } catch (error) {
        const rejected = error.code !== "outcome_unknown" && error.http_status >= 400 && error.http_status < 500;
        if (fields.request_id && rejected) setPendingRun(null);
        if (error.code !== "outcome_unknown" && !pendingRun) {
          for (const id of uploads) await call("discard_upload", { upload_id: id }).catch(() => {});
        }
        // A recoverable submission already keeps the staged IDs, including across a tab reload.
        if (pendingRun) attachmentDrafts.delete(key);
        throw error;
      } finally {
        if (files.length) message("notice", "");
      }
      attachmentDrafts.delete(key);
      openSubmittedTask(task, key, text);
    } else {
      const id = route.task.task_id,
        receipt = await call(route.kind, { task_id: id, text });
      if (!receipt.accepted) {
        message("notice", t("notAccepted"));
      } else {
        drafts.delete(key);
        goal.value = "";
        const turns = exchanges.get(id) || [];
        turns.push({ mode: route.kind, text, question: route.kind === "answer" ? route.task.question : null });
        exchanges.set(id, turns);
      }
    }
    await refresh();
  }, true);
}
$("new-chat").addEventListener("click", () => {
  if (!busy) {
    chooseGroup(fresh);
    $("goal").focus();
  }
});
$("threads").addEventListener("click", ev => {
  const b = ev.target.closest("button[data-group]");
  if (b && !busy) chooseGroup(b.dataset.group);
  if (ev.target.closest('[data-action="history-list"]') && moreChats != null) work(async () => {
    const page = await call("history_list", { offset: String(moreChats) });
    for (const chat of page.conversations) savedChats.set(chat.id, chat);
    moreChats = page.next_offset;
    mergeHistoryGroups();
  });
});
$("open-nav").addEventListener("click", () => setNav(true));
$("close-nav").addEventListener("click", () => {
  setNav(false);
  $("open-nav").focus();
});
$("connection-details")
  .querySelector("summary")
  .addEventListener("click", () => {
    autoDisclosure = false;
  });
$("language").addEventListener("change", () => {
  const value = $("language").value;
  if (value === "auto") localStorage.removeItem("mikaDeskLanguage");
  else localStorage.setItem("mikaDeskLanguage", value);
  applyLanguage();
  alerts.sync();
  navSignature = transcriptSignature = processSignature = optionsSignature = "";
  if (online) updateDevice(overview);
  else
    connectionState($("connection-details").dataset.phase || "retry", $("pick").hidden ? "offline" : "choosing");
  render();
});
$("refresh").addEventListener("click", () => work(() => refresh()));
$("pick").addEventListener("submit", ev => {
  ev.preventDefault();
  work(() => refresh({ device: $("devices").value }));
});
$("toggle-process").addEventListener("click", () => {
  const task = currentTask();
  if (task) toggleProcess(inspectId || task.task_id);
});
function collapseProcess() {
  if (!inspectId) return;
  const old = inspectId;
  const returnFocus = processContent.contains(document.activeElement);
  toggleProcess(old);
  if (returnFocus) $(processSlotId(old))?.previousElementSibling?.focus({ preventScroll: true });
}
processContent.addEventListener("click", ev => {
  const action = ev.target.closest("button");
  if (action?.dataset.recordPage != null) {
    recordBody = null; bodyRequest++;
    loadSavedRun(byId(group()?.turns, inspectId), Number(action.dataset.recordPage));
    return;
  }
  if (action?.dataset.bodyPage != null && recordBody) {
    loadTraceBody(recordBody.span, Number(action.dataset.bodyPage));
    return;
  }
  if (action?.dataset.bodySpan) {
    if (recordBody?.span === action.dataset.bodySpan) { recordBody = null; bodyRequest++; renderProcess(); }
    else loadTraceBody(action.dataset.bodySpan);
    return;
  }
  const b = ev.target.closest("button[data-observation]");
  if (!b) return;
  selectedObservation = observed.get(inspectId)?.rows[Number(b.dataset.observation)] || null;
  renderProcess();
});
document.addEventListener("keydown", e => {
  if (e.key !== "Escape") return;
  if ($("workspace").classList.contains("nav-open")) {
    setNav(false);
    $("open-nav").focus();
  } else if ($("connection-details").open && $("connection-details").contains(document.activeElement)) {
    $("connection-details").open = false;
    $("connection-details").querySelector("summary").focus();
  } else collapseProcess();
});
$("transcript").addEventListener("click", async ev => {
  const b = ev.target.closest("button[data-action]");
  if (!b) return;
  if (b.dataset.action === "history-turns") {
    const conversation = group()?.conversation, offset = savedTurns.get(conversation)?.next_offset;
    if (conversation && offset != null) await work(() => loadSavedTurns(conversation, offset));
    return;
  }
  const task = byId(group()?.turns, b.dataset.task);
  if (!task) return;
  const id = task.task_id;
  switch (b.dataset.action) {
    case "process":
      toggleProcess(id);
      break;
    case "goal":
      if (expandedGoals.has(id)) expandedGoals.delete(id);
      else expandedGoals.add(id);
      renderTurns();
      break;
    case "download": {
      const file = task.files?.find(item => item.id === b.dataset.fileId);
      if (!file || !online || busy) return;
      await work(async () => {
        message("notice", t("downloadingFile", { name: file.name }));
        try {
          const blob = await call("download", { task_id: id, file_id: file.id });
          if (!(blob instanceof Blob) || blob.size !== file.size) throw new Error(t("fileTransferFailed"));
          const link = document.createElement("a");
          link.href = URL.createObjectURL(blob);
          link.download = file.name;
          link.click();
          setTimeout(() => URL.revokeObjectURL(link.href), 60_000);
          message("notice", t("fileSaved"));
        } catch (error) {
          message("notice", t([404, 409].includes(error.http_status) || error.code === "file_changed" ? "resultUnavailable" : "fileTransferFailed"));
        }
      });
      break;
    }
    case "copy":
      try {
        await navigator.clipboard.writeText(task.answer);
        message("notice", t("copied"));
      } catch {
        message("notice", t("copyFailed"));
      }
      break;
    case "target":
      if (!busy) {
        preferredTask = id;
        invalidate();
        render();
        schedule();
        $("goal").focus();
      }
      break;
    case "cancel":
      if (!online || busy || !isActive(task) || !confirm(t("confirmCancel"))) return;
      await work(async () => {
        const receipt = await call("cancel", { task_id: id });
        message("notice", t(receipt.accepted ? "cancelSent" : "notAccepted"));
        await refresh();
      }, true);
      break;
  }
});
$("goal").addEventListener("input", () => {
  if (composerKey) drafts.set(composerKey, $("goal").value);
  syncComposer();
});
$("goal").addEventListener("keydown", ev => {
  if (
    ev.key !== "Enter" ||
    ev.shiftKey ||
    ev.altKey ||
    ev.ctrlKey ||
    ev.metaKey ||
    ev.isComposing ||
    ev.keyCode === 229
  )
    return;
  ev.preventDefault();
  if (!ev.repeat && !$("submit-goal").disabled) $("compose").requestSubmit($("submit-goal"));
});
$("compose").addEventListener("submit", ev => {
  ev.preventDefault();
  submit();
});
document.addEventListener("visibilitychange", () => {
  alerts.sync();
  invalidate();
  schedule(true);
});
render();
schedule(true);
