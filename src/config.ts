// 参数 schema + 默认值。
// 字段完全对齐 bbt-zb 真实接口（2026-11-02 由官方前端 pages/login.js + features/chat.js
// 反查 + 实打 /api 验证）：
//   * 登录:    action=account/login  {telephone, password} -> data.token
//   * 拉取:    action=chat/configGet -> data {aiEnabled, dailyLimit, onlineStatus,
//              displayName, personaPrompt, chatPurpose, faqMarkdown, takeoverMode,
//              dailyLimitCeiling, ...}
//   * 推送:    action=chat/configSave condition {aiEnabled(1/0), dailyLimit,
//              onlineStatus(online/busy/hidden), displayName, personaPrompt,
//              chatPurpose, faqMarkdown}

export type FieldType =
  | "text"
  | "number"
  | "select"
  | "toggle"
  | "textarea"
  | "password";

export interface FieldDef {
  key: string;
  label: string;
  type: FieldType;
  default: any;
  options?: { value: string; label: string }[];
  step?: number;
  help?: string;
}

export interface GroupDef {
  id: string;
  title: string;
  desc?: string;
  fields: FieldDef[];
}

// ---- 连接设置（仅本地使用，不上传 bbt-zb）----
export const CONNECTION_FIELDS: FieldDef[] = [
  {
    key: "base_url",
    label: "BBT-ZB 接口地址",
    type: "text",
    default: "https://bbt-zb.pages.dev",
    help: "认准官方网址，谨防假冒",
  },
  { key: "telephone", label: "手机号", type: "text", default: "" },
  { key: "password", label: "密码", type: "password", default: "" },
  { key: "token", label: "Token（登录后自动填入）", type: "text", default: "" },
  {
    key: "update_base_url",
    label: "升级源地址",
    type: "text",
    default: "https://raw.githubusercontent.com/eeshop/zhifutuan/pcapp",
    help: "读取 /updates/version.json 检查新版本（weauto 模式：下载→验签→重启生效）；国内走 gh-proxy 镜像自动回退",
  },
];

// ---- 聊天参数（chat/configGet ↔ chat/configSave，真实字段）----
export const SCHEMA: GroupDef[] = [
  {
    id: "chat_ai",
    title: "自动回复",
    desc: "对应 bbt-zb 团长后台「自动回复设置」",
    fields: [
      { key: "aiEnabled", label: "开启自动回复", type: "toggle", default: true },
      {
        key: "dailyLimit",
        label: "每人每日免费条数",
        type: "number",
        default: 10,
        step: 1,
        help: "超出后转为团长人工回复",
      },
      {
        key: "onlineStatus",
        label: "在线状态（对团员展示）",
        type: "select",
        default: "online",
        options: [
          { value: "online", label: "在线" },
          { value: "busy", label: "忙碌" },
          { value: "hidden", label: "隐身" },
        ],
      },
      {
        key: "displayName",
        label: "展示名（团员侧署名，留空用团长姓名）",
        type: "text",
        default: "",
      },
      {
        key: "personaPrompt",
        label: "团长人格设定（语气、性格、身份背景）",
        type: "textarea",
        default: "",
        help: "例如：我是本团团长，做事爽快、说话接地气，喜欢用 emoji…",
      },
      {
        key: "chatPurpose",
        label: "聊天目的 / 场景",
        type: "textarea",
        default: "",
        help: "例如：解答团购流程、催单、处理售后",
      },
      {
        key: "faqMarkdown",
        label: "常见问答知识库（Q: / A: 形式）",
        type: "textarea",
        default: "",
        help: "可手填，也可在聊天中一键采纳",
      },
    ],
  },
  {
    id: "readonly",
    title: "远程状态（只读）",
    desc: "由 bbt-zb 下发，拉取远程后刷新",
    fields: [
      {
        key: "takeoverMode",
        label: "人工接管中",
        type: "toggle",
        default: false,
        help: "开启后由团长人工回复（chat/takeover 切换）",
      },
      {
        key: "dailyLimitCeiling",
        label: "每日条数上限",
        type: "number",
        default: 50,
        step: 1,
        help: "服务端限制，不可修改",
      },
    ],
  },
];

// 推送时排除只读字段（chat/configSave 只接受 CONFIG_KEYS 里的键）
export const READONLY_KEYS = new Set(["takeoverMode", "dailyLimitCeiling"]);

// 生成带默认值的参数对象（不含连接信息）
export function defaultParams(): Record<string, any> {
  const out: Record<string, any> = {};
  for (const g of SCHEMA) {
    for (const f of g.fields) out[f.key] = f.default;
  }
  return out;
}

// 默认连接对象
export function defaultConnection(): Record<string, any> {
  const out: Record<string, any> = {};
  for (const f of CONNECTION_FIELDS) out[f.key] = f.default;
  return out;
}

// 把已加载的值与默认值合并，保证新增字段有兜底
export function mergeParams(loaded: any): Record<string, any> {
  return { ...defaultParams(), ...(loaded || {}) };
}

export function mergeConnection(loaded: any): Record<string, any> {
  return { ...defaultConnection(), ...((loaded && loaded.connection) || {}) };
}
