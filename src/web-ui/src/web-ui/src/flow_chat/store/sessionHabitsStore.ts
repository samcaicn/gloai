/**
 * sessionHabitsStore — Hermes 默默记住用户会话习惯。
 *
 * 无 UI、无打扰地在后台追踪用户行为模式：
 *   - 技能使用频率（哪些技能最常用）
 *   - 消息关键词/主题（用户关心什么）
 *   - 模型偏好（每个技能倾向用哪个模型）
 *   - 会话频率与时段（活跃时间段）
 *   - 消息长度分布（简短问答 vs 详细任务）
 *
 * 数据持久化到 localStorage，在发送 LLM 请求时自动注入到 system prompt，
 * 让 Hermes "了解"用户偏好从而提供更贴合的回复。
 *
 * 设计原则：
 *   - 纯后台，无任何 UI 弹窗/提示
 *   - 本地存储，不上传服务器（隐私优先）
 *   - 轻量：只记录统计计数，不存原始消息内容
 */

import { create } from 'zustand';
import { createLogger } from '@/shared/utils/logger';

const log = createLogger('sessionHabitsStore');

const STORAGE_KEY = 'tupai:session-habits';

// ── 习惯数据结构 ──────────────────────────────

interface SkillUsageStat {
  skillId: string;
  skillName: string;
  count: number;
  lastUsed: number;
}

interface ModelPreference {
  /** `${skillId}` → modelId */
  [skillId: string]: string;
}

interface KeywordStat {
  keyword: string;
  count: number;
  lastSeen: number;
}

interface SessionHabitsData {
  /** 技能使用统计，按 skillId 索引 */
  skillUsage: Record<string, SkillUsageStat>;
  /** 模型偏好（技能 → 模型） */
  modelPreferences: ModelPreference;
  /** 关键词统计（top 50） */
  keywords: Record<string, KeywordStat>;
  /**总会话数 */
  totalSessions: number;
  /** 总消息数 */
  totalMessages: number;
  /** 消息长度累计（用于计算平均值） */
  totalMessageLength: number;
  /** 活跃时段统计：hour(0-23) → count */
  activeHours: Record<number, number>;
  /** 最后更新时间 */
  updatedAt: number;
}

// ── 持久化 ──────────────────────────────────

function loadData(): SessionHabitsData {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return defaultData();
    const parsed = JSON.parse(raw) as SessionHabitsData;
    // 合并默认值，防止旧版本数据缺字段
    return { ...defaultData(), ...parsed };
  } catch {
    return defaultData();
  }
}

function defaultData(): SessionHabitsData {
  return {
    skillUsage: {},
    modelPreferences: {},
    keywords: {},
    totalSessions: 0,
    totalMessages: 0,
    totalMessageLength: 0,
    activeHours: {},
    updatedAt: 0,
  };
}

function saveData(data: SessionHabitsData) {
  try {
    data.updatedAt = Date.now();
    localStorage.setItem(STORAGE_KEY, JSON.stringify(data));
  } catch (err) {
    log.warn('Failed to persist session habits', { error: err });
  }
}

// ── 关键词提取 ──────────────────────────────

// 停用词表（中英文常见无意义词）
const STOP_WORDS = new Set([
  // 中文
  '的', '了', '是', '在', '我', '有', '和', '就', '不', '人', '都', '一', '一个',
  '上', '也', '很', '到', '说', '要', '去', '你', '会', '着', '没有', '看', '好',
  '这', '那', '它', '他', '她', '们', '什么', '怎么', '为什么', '可以', '能',
  '请', '帮', '帮我', '需要', '想', '要做', '一下', '还是', '或者', '但是',
  // 英文
  'the', 'a', 'an', 'is', 'are', 'was', 'were', 'be', 'been', 'being',
  'have', 'has', 'had', 'do', 'does', 'did', 'will', 'would', 'could',
  'should', 'may', 'might', 'must', 'can', 'need', 'to', 'of', 'in', 'for',
  'on', 'with', 'as', 'by', 'at', 'from', 'this', 'that', 'it', 'they',
  'them', 'their', 'we', 'us', 'our', 'you', 'your', 'he', 'she', 'his',
  'her', 'i', 'me', 'my', 'and', 'or', 'but', 'not', 'no', 'yes', 'if',
  'then', 'else', 'when', 'where', 'why', 'how', 'what', 'which', 'who',
  'please', 'help', 'want', 'need', 'like', 'just', 'about',
]);

// 从用户消息中提取关键词（简单分词 + 频率统计）
function extractKeywords(text: string): string[] {
  if (!text || text.length < 2) return [];
  const keywords: string[] = [];

  // 英文单词（2字符以上）
  const englishWords = text.match(/[a-zA-Z]{2,}/g) || [];
  for (const w of englishWords) {
    const lower = w.toLowerCase();
    if (!STOP_WORDS.has(lower) && lower.length >= 3) {
      keywords.push(lower);
    }
  }

  // 中文关键词（2-4字符连续中文）
  const chineseSegments = text.match(/[\u4e00-\u9fa5]{2,4}/g) || [];
  for (const seg of chineseSegments) {
    if (!STOP_WORDS.has(seg)) {
      keywords.push(seg);
    }
  }

  // 技术关键词（驼峰 / 下划线标识符）
  const identifiers = text.match(/[a-zA-Z_][a-zA-Z0-9_]{3,}/g) || [];
  for (const id of identifiers) {
    const lower = id.toLowerCase();
    if (!STOP_WORDS.has(lower)) {
      keywords.push(lower);
    }
  }

  // 去重，最多取前 5 个
  return [...new Set(keywords)].slice(0, 5);
}

// ── 习惯上下文构建 ──────────────────────────

/**
 * 构建用于注入 LLM system prompt 的用户习惯上下文。
 * 返回空字符串表示无足够习惯数据。
 */
export function buildHabitsContext(): string {
  const data = loadData();
  if (data.totalMessages < 3) return ''; // 消息太少，不注入

  const lines: string[] = ['## 用户习惯（Hermes 默默观察记录，请自然融入回复风格）'];

  // 常用技能 top 3
  const topSkills = Object.values(data.skillUsage)
    .sort((a, b) => b.count - a.count)
    .slice(0, 3);
  if (topSkills.length > 0) {
    lines.push(`- 常用技能：${topSkills.map(s => `${s.skillName}(${s.count}次)`).join('、')}`);
  }

  // 高频关键词 top 5
  const topKeywords = Object.values(data.keywords)
    .sort((a, b) => b.count - a.count)
    .slice(0, 5);
  if (topKeywords.length > 0) {
    lines.push(`- 关注话题：${topKeywords.map(k => k.keyword).join('、')}`);
  }

  // 消息风格
  const avgLen = data.totalMessages > 0 ? Math.round(data.totalMessageLength / data.totalMessages) : 0;
  if (avgLen > 0) {
    if (avgLen < 20) {
      lines.push(`- 偏好简短问答（平均消息 ${avgLen} 字）`);
    } else if (avgLen > 100) {
      lines.push(`- 偏好详细描述（平均消息 ${avgLen} 字）`);
    } else {
      lines.push(`- 消息长度适中（平均 ${avgLen} 字）`);
    }
  }

  // 活跃时段
  const hourEntries = Object.entries(data.activeHours) as [string, number][];
  if (hourEntries.length >= 3) {
    hourEntries.sort((a, b) => b[1] - a[1]);
    const topHours = hourEntries.slice(0, 3).map(([h]) => {
      const hour = parseInt(h, 10);
      if (hour < 6) return '深夜';
      if (hour < 12) return '上午';
      if (hour < 18) return '下午';
      return '晚间';
    });
    const uniquePeriods = [...new Set(topHours)];
    lines.push(`- 活跃时段：${uniquePeriods.join('、')}`);
  }

  // 总互动量
  lines.push(`- 累计互动：${data.totalMessages} 条消息，${data.totalSessions} 次会话`);

  return lines.join('\n');
}

// ── Zustand Store ──────────────────────────

interface SessionHabitsState extends SessionHabitsData {
  /** 记录技能使用（加载技能 prompt 时调用） */
  trackSkillUsage: (skillId: string, skillName: string) => void;
  /** 记录用户消息（发送时调用，提取关键词 + 统计） */
  trackMessage: (text: string) => void;
  /** 记录模型偏好（选择模型时调用） */
  trackModelPreference: (skillId: string, modelId: string) => void;
  /** 记录会话开始（进入聊天页时调用） */
  trackSessionStart: () => void;
  /** 获取习惯上下文（注入 system prompt） */
  getHabitsContext: () => string;
  /** 清空所有习惯数据 */
  clearAll: () => void;
}

const initialData = loadData();

export const useSessionHabitsStore = create<SessionHabitsState>((set, _get) => ({
  ...initialData,

  trackSkillUsage: (skillId, skillName) => {
    set((state) => {
      const existing = state.skillUsage[skillId];
      const stat: SkillUsageStat = existing
        ? { ...existing, count: existing.count + 1, lastUsed: Date.now() }
        : { skillId, skillName, count: 1, lastUsed: Date.now() };
      const newData = {
        ...state,
        skillUsage: { ...state.skillUsage, [skillId]: stat },
      };
      saveData(newData);
      return newData;
    });
    log.debug('Tracked skill usage', { skillId, skillName });
  },

  trackMessage: (text) => {
    if (!text || !text.trim()) return;
    const keywords = extractKeywords(text);
    const hour = new Date().getHours();
    set((state) => {
      // 更新关键词统计
      const newKeywords = { ...state.keywords };
      for (const kw of keywords) {
        const existing = newKeywords[kw];
        newKeywords[kw] = existing
          ? { keyword: kw, count: existing.count + 1, lastSeen: Date.now() }
          : { keyword: kw, count: 1, lastSeen: Date.now() };
      }
      // 只保留 top 50 关键词
      const keywordList = Object.values(newKeywords).sort((a, b) => b.count - a.count);
      const trimmedKeywords: Record<string, KeywordStat> = {};
      for (const k of keywordList.slice(0, 50)) {
        trimmedKeywords[k.keyword] = k;
      }

      const newData = {
        ...state,
        keywords: trimmedKeywords,
        totalMessages: state.totalMessages + 1,
        totalMessageLength: state.totalMessageLength + text.length,
        activeHours: {
          ...state.activeHours,
          [hour]: (state.activeHours[hour] || 0) + 1,
        },
      };
      saveData(newData);
      return newData;
    });
    log.debug('Tracked message', { length: text.length, keywords });
  },

  trackModelPreference: (skillId, modelId) => {
    set((state) => {
      const newData = {
        ...state,
        modelPreferences: { ...state.modelPreferences, [skillId]: modelId },
      };
      saveData(newData);
      return newData;
    });
    log.debug('Tracked model preference', { skillId, modelId });
  },

  trackSessionStart: () => {
    set((state) => {
      const newData = {
        ...state,
        totalSessions: state.totalSessions + 1,
      };
      saveData(newData);
      return newData;
    });
    log.debug('Tracked session start');
  },

  getHabitsContext: () => {
    return buildHabitsContext();
  },

  clearAll: () => {
    const fresh = defaultData();
    saveData(fresh);
    set(fresh);
    log.info('Cleared all session habits');
  },
}));
