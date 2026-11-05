// 仅用于本地浏览器预览（不提交、不进入生产构建）：给 Tauri invoke / listen 打桩，
// 让纯前端界面能在普通浏览器里跑起来，便于做 UI 走查与截图。
const cfg: any = {
  aiEnabled: true,
  dailyLimit: 10,
  onlineStatus: "online",
  displayName: "",
  personaPrompt: "",
  chatPurpose: "",
  faqMarkdown: "",
  takeoverMode: false,
  dailyLimitCeiling: 50,
  privateEnabled: true,
  groupEnabled: false,
  groupMode: "at",
  atList: [],
  keywords: [],
  whitelist: [],
  blacklist: [],
  bannedWords: [],
  groupRateLimit: 10,
  cooldownSec: 3,
  enabled: true,
  listen: [],
  poll_interval_sec: 3,
  targets: [],
  forward_leader_to_bots: true,
  forward_inbound_to_bbt: true,
  session_list_action: "",
  history_action: "",
  send_action: "",
};

const CHANNEL_TARGETS = {
  wx_private: [
    { id: "wx-1", name: "张三（团员）", is_group: false, summary: "收到，稍后付款", last_time: Date.now() },
    { id: "wx-2", name: "李四（团员）", is_group: false, summary: "本轮预算多少？", last_time: Date.now() },
  ],
  wx_group: [{ id: "g-1", name: "抱抱团 · 一团", is_group: true, summary: "今日开团", last_time: Date.now() }],
  wx_note: "wxauto 已连接",
  ilink: [{ bot_id: "bot-1", label: "团长机器人", status: "running" }],
  ilink_note: "iLink 已就绪",
  member: [{ id: "m-1", name: "张三" }],
  member_note: "名册已同步",
  member_map: {},
};

const TABLE: Record<string, any> = {
  load_config: { connection: { base_url: "https://bbt-zb.pages.dev", telephone: "138****0000", password: "", token: "preview-token" }, ...cfg },
  local_cache_status: { path: "config.json", bytes: 2048, updatedAt: Date.now(), valid: true },
  channel_targets: CHANNEL_TARGETS,
  autoreply_get_config: cfg,
  autoreply_logs: [
    { ts: Date.now() - 60000, level: "info", text: "收到张三消息，AI 已生成回复" },
    { ts: Date.now() - 120000, level: "info", text: "自动回复已启用" },
  ],
  autoreply_self_test: { ok: true, reply: "（预览）本轮预算可以在「我的」页查看～" },
  ai_gateway_status: { enabled: true, leader: "团长", pending: 0 },
  ai_gateway_logs: [],
  wxauto_get_config: cfg,
  wxauto_logs: [],
  bridge_get_config: cfg,
  ilink_list_accounts: [{ bot_id: "bot-1", label: "团长机器人", user_id: "u-1", status: "running" }],
  ilink_local_history: [],
  memberqr_set_map: null,
  test_connection: { ok: true, latencyMs: 42 },
  fetch_remote_config: cfg,
  update_check: { hasUpdate: false, version: "0.4.0" },
  bbt_call: { status: true, data: {} },
  login: { token: "preview-token", account: { role: "leader" } },
};

export async function invoke<T = any>(cmd: string, _args: any = {}): Promise<T> {
  await new Promise((r) => setTimeout(r, 20));
  if (cmd in TABLE) return TABLE[cmd] as T;
  return {} as T;
}

export async function listen(_event: string, _cb: (e: any) => void): Promise<() => void> {
  return () => {};
}

export type UnlistenFn = () => void;
