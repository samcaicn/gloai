// 技能执行静默上报 —— 统一经 MCP action `execution.report` 上报到 Hermes 评估系统。
//
// 设计原则：
//   - fire-and-forget：对外返回 void，内部 `void doReport(...).catch(...)`，
//     调用方无需 await，绝不阻塞 UI、绝不抛错。
//   - 静默：上报成功/失败仅写日志（debug / warn），无用户可见提示。
//   - 复用 mcpCallWithRefresh：自动处理 token 刷新（走 tenant.get 验证路径，不轮换 token）。
//   - 预算式序列化：output 序列化成本为 O(MAX_OUTPUT_LENGTH) 而非 O(input)，
//     超大对象不会撑爆内存、不会长时间阻塞 UI 线程；同时天然处理循环引用。
//
// 两个场景（参数对齐服务端 execution.report 契约）：
//   场景 1 执行失败 → { skill_id, status: "failure", error_message }
//   场景 2 执行成功 → { skill_id, status: "success",
//                       result: { success: true, output, duration_ms } }
//
// spec 中 result.summary / human_confirmed / human_approved_output / human_feedback
// 均为选填字段，本次不接入人工确认流程，故全部省略。

import { mcpCallWithRefresh } from './device';
import { createLogger } from '@/shared/utils/logger';

const log = createLogger('executionReport');

// 上报 output 字段最大长度，防止 MCP payload 膨胀 / 后端存储压力。
const MAX_OUTPUT_LENGTH = 4000;
// 递归深度上限，防御病态深层结构导致栈溢出。
const MAX_DEPTH = 50;
const TRUNCATED_MARKER = '…[truncated]';

/** 规范化 skillId：字符串 trim，非字符串安全转字符串，空 → ''。 */
function toSkillId(skillId: unknown): string {
  if (typeof skillId === 'string') return skillId.trim();
  if (skillId == null) return '';
  return String(skillId).trim();
}

/**
 * 规范化 errorMessage：Error 取 .message，其余安全转字符串，null/undefined → ''。
 * 关键修复：直接把 Error 对象塞进 MCP params 会在 JSON 序列化时变成 '{}'，
 * 服务端拿不到错误描述，自动修复链路彻底失效。这里统一兜底。
 */
function toErrorMessage(errorMessage: unknown): string {
  if (errorMessage == null) return '';
  if (errorMessage instanceof Error) return errorMessage.message || '';
  if (typeof errorMessage === 'string') return errorMessage;
  try {
    const s = JSON.stringify(errorMessage);
    return s && s !== '{}' ? s : String(errorMessage);
  } catch {
    return String(errorMessage);
  }
}

/** 规范化 durationMs：非有限数（NaN/Infinity）→ 0，负数 → 0，浮点 → 截断为整数。 */
function toDurationMs(durationMs: unknown): number {
  const n = Number(durationMs);
  if (!Number.isFinite(n)) return 0;
  return Math.max(0, Math.trunc(n));
}

/**
 * 预算式 output 序列化：
 *   - 字符串原样（不 JSON 引号包裹），超长直接切片 —— 快速路径，O(slice)。
 *   - 对象走紧凑 JSON（无缩进空格），遇循环引用写 "[Circular]"，遇超大对象
 *     达到 MAX_OUTPUT_LENGTH 立即停止 —— 成本 O(MAX_OUTPUT_LENGTH) 而非 O(input)，
 *     不会为超大对象分配完整字符串、不阻塞 UI 线程。
 *   - 超过 MAX_DEPTH 写 "[depth-limit]"，防御病态深层结构栈溢出。
 *
 * 截断时追加 `…[truncated]` 可见标记。截断后的 JSON 可能不完整（仅作诊断字符串，
 * 服务端按字符串存储，不解析为 JSON）。
 */
function serializeOutput(output: unknown): string {
  // 快速路径：字符串原样返回，超长直接切片，O(1) 不分配大中间串。
  if (typeof output === 'string') {
    return output.length > MAX_OUTPUT_LENGTH
      ? output.slice(0, MAX_OUTPUT_LENGTH) + TRUNCATED_MARKER
      : output;
  }

  const maxLen = MAX_OUTPUT_LENGTH;
  const seen = new WeakSet<object>();
  let buf = '';
  let capped = false;

  // 追加片段；超预算即截断到 maxLen 并置 capped=true，后续写入全部短路。
  function write(s: string): void {
    if (capped) return;
    if (buf.length + s.length <= maxLen) {
      buf += s;
      return;
    }
    buf += s.slice(0, Math.max(0, maxLen - buf.length));
    capped = true;
  }

  function serialize(v: unknown, depth: number): void {
    if (capped) return;
    if (v === null || v === undefined) { write('null'); return; }
    const t = typeof v;
    if (t === 'string') { write(JSON.stringify(v)); return; }
    if (t === 'number') { write(Number.isFinite(v) ? String(v) : 'null'); return; }
    if (t === 'boolean') { write(v ? 'true' : 'false'); return; }
    if (t === 'bigint') { write('"' + String(v) + '"'); return; }
    if (t === 'function' || t === 'symbol') { write('null'); return; }
    // Date → ISO 字符串（与原生 JSON.stringify 行为一致）。
    if (v instanceof Date) { write(JSON.stringify(v.toISOString())); return; }
    // 对象 / 数组 —— 循环引用安全。
    if (t === 'object') {
      if (depth >= MAX_DEPTH) { write('"[depth-limit]"'); return; }
      const obj = v as object;
      if (seen.has(obj)) { write('"[Circular]"'); return; }
      seen.add(obj);
      if (Array.isArray(v)) {
        write('[');
        for (let i = 0; i < v.length; i++) {
          if (capped) break;
          if (i > 0) write(',');
          serialize(v[i], depth + 1);
        }
        write(']');
      } else {
        write('{');
        let first = true;
        let keys: string[];
        try { keys = Object.keys(obj); } catch { keys = []; }
        for (const k of keys) {
          if (capped) break;
          if (!first) write(',');
          first = false;
          write(JSON.stringify(k) + ':');
          let val: unknown;
          try { val = (v as Record<string, unknown>)[k]; } catch { val = '[unreadable]'; }
          serialize(val, depth + 1);
        }
        write('}');
      }
      // DFS 树形结构无环时安全释放，便于兄弟节点复用。
      seen.delete(obj);
    } else {
      // 兜底：Symbol wrapper 等未知类型，尽量转字符串。
      write(JSON.stringify(String(v)));
    }
  }

  serialize(output, 0);
  return capped ? buf + TRUNCATED_MARKER : buf;
}

/** 实际发起 MCP execution.report；捕获所有异常，仅 warn 日志，绝不抛错。 */
async function doReport(params: Record<string, any>): Promise<void> {
  try {
    const r = await mcpCallWithRefresh('execution.report', params);
    if (r && r.ok === false) {
      log.warn('execution.report returned ok=false', { skill_id: params.skill_id, error: r?.error });
    } else {
      log.debug('execution.report sent', { skill_id: params.skill_id, status: params.status });
    }
  } catch (e) {
    log.warn('execution.report failed silently', { skill_id: params.skill_id, error: e });
  }
}

/**
 * 静默上报技能执行失败。
 *
 * @param skillId      技能 ID
 * @param errorMessage 错误描述，服务端自动修复全靠这个（Error 对象自动取 .message）
 */
export function reportSkillFailure(skillId: string, errorMessage: string): void {
  const id = toSkillId(skillId);
  if (!id) {
    log.warn('reportSkillFailure: empty skillId');
    return;
  }
  void doReport({
    skill_id: id,
    status: 'failure',
    error_message: toErrorMessage(errorMessage),
  });
}

/**
 * 静默上报技能执行成功。
 *
 * @param skillId    技能 ID
 * @param output     原样输出（预算式截断超长内容，循环引用安全）
 * @param durationMs 执行耗时（毫秒），非有限数（NaN/Infinity）按 0 处理
 */
export function reportSkillSuccess(skillId: string, output: unknown, durationMs: number): void {
  const id = toSkillId(skillId);
  if (!id) {
    log.warn('reportSkillSuccess: empty skillId');
    return;
  }
  void doReport({
    skill_id: id,
    status: 'success',
    result: {
      success: true,
      output: serializeOutput(output),
      duration_ms: toDurationMs(durationMs),
    },
  });
}
