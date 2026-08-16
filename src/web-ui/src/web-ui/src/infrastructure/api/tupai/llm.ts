// LLM 流式调用桥接层 —— 统一经 MCP 请求 LLM 会话。
//
// 2026-07-15: 改为真正的逐 token 流式。
// 之前使用 `mcp_call_v2`（非流式），后端等整个 SSE 流结束后一次性返回
// 完整文本，前端只收到一个 content chunk，体验为"等待很久后一次性
// 出现全部文本"。
//
// 现在改用 `mcpStream`（后端 `mcp_stream` 命令），通过 Tauri 2 Channel
// API 逐 SSE 帧推送 delta 文本，前端逐 chunk 追加渲染，实现真正的
// 逐 token 流式效果。
//
// 后端 `mcp_stream` 对 `llm.stream_request` 的 SSE 响应逐行解析：
//   * OpenAI Chat: choices[0].delta.content
//   * Anthropic: delta.text / delta.content
//   * 纯文本: data 行直接作为 delta
// 每个 delta 通过 Channel 推送 `{ type: "content", data: { content: "delta" } }`。
//
// 本桥接层把 mcpStream 返回的 AsyncIterable 转成 `AsyncGenerator<LlmStreamChunk>`，
// 对外保留 `llmStreamChat(req)` 契约：
//   * content chunk: data 为字符串（delta 文本），消费方直接追加
//   * error chunk: data 为错误信息字符串
//   * done chunk: 流结束
//
// 注意：TupaiChatScene 消费 `chunk.data` 为字符串（见
// TupaiChatScene.tsx），因此 content chunk 的 data 直接是文本字符串，
// 不再包裹成 `{ content: "..." }` 对象。
import { mcpStream } from './mcp';
import { refreshDeviceToken, isAuthTokenInvalid, getDeviceApprovalStatus } from './device';
import type { LlmMessage, LlmStreamChunk, ToolSchema } from './types';

export interface LlmRequest {
sessionId: string;
messages: LlmMessage[];
// 可选 model：不传或 'default' 时由云端 MCP 走默认路由。
model?: string;
// 可选 tools：OpenAI function calling schema 数组，传给 LLM 让其知道可调什么工具。
tools?: ToolSchema[];
}

// localStorage key 与 skill.ts / model.ts / device.ts 保持一致
// （后端 mcp_stream 用作 Bearer token）。
const DEVICE_TOKEN_KEY = 'trae_device_token';

function readDeviceToken(): string | null {
  try {
    return typeof localStorage !== 'undefined' ? localStorage.getItem(DEVICE_TOKEN_KEY) : null;
  } catch {
    // localStorage 在某些上下文（隐私模式 / 无 webview）可能抛错，保守返回 null。
    return null;
  }
}

/**
 * 调用后端 `mcp_stream` 命令（action = `llm.stream_request`），通过
 * Tauri 2 Channel API 接收真正的 SSE 逐 token 增量，转成
 * `AsyncGenerator<LlmStreamChunk>`。
 *
 * 成功：逐个 yield content chunk（data 为 delta 文本字符串），最后 yield done。
 * 失败：yield 一个 error chunk（data 为错误信息字符串）。
 */
export async function* llmStreamChat(req: LlmRequest): AsyncGenerator<LlmStreamChunk> {
  // 白名单门控：pending_approval/rejected 设备不能调 llm.stream_request（非白名单 action），
  // 直接 yield error，避免无意义 refresh+重试循环。
  // unknown 放行（启动竞态由服务器兜底，避免误阻塞）。
  const approvalStatus = getDeviceApprovalStatus();
  if (approvalStatus === 'pending_approval' || approvalStatus === 'rejected') {
    yield { type: 'error', data: '设备未审批通过，此功能暂不可用' };
    return;
  }

  let token = readDeviceToken();
  const params: Record<string, unknown> = {
    session_id: req.sessionId,
    messages: req.messages,
    stream: true,
  };
  // 仅在明确指定且非 'default' 时传 model，让云端走默认路由（与旧逻辑一致）。
  if (req.model && req.model !== 'default') {
    params.model = req.model;
  }
  // 透传 tools 参数：OpenAI function calling schema，让 LLM 知道可调什么工具。
  if (req.tools && req.tools.length > 0) {
    params.tools = req.tools;
  }

  // 最多重试 2 次：第 1 次用当前 token，auth 失败则 refresh 后重试 1 次。
  // 服务器在流式开始前校验 auth，auth 失败不会产生任何 content chunk，
  // 所以首包 error = auth 错误（或连接级错误）。流中途的 error 不是 auth 问题，不重试。
  for (let attempt = 0; attempt < 2; attempt++) {
    let authRetryNeeded = false;

    try {
      // mcpStream 返回 AsyncIterable<McpStreamChunk>，其中：
      //   McpStreamChunk.type = 'content' | 'error' | 'done'
      //   McpStreamChunk.data = { content: "delta" } (content) | { message: "..." } (error) | null (done)
      const stream = await mcpStream('', 'llm.stream_request', params, token);
      let firstChunk = true;

      for await (const chunk of stream) {
        // 首包为 error → 可能是 auth 失败（服务器在流式开始前校验 auth）
        if (firstChunk && chunk.type === 'error') {
          firstChunk = false;
          const errMsg =
            typeof chunk.data === 'string'
              ? chunk.data
              : chunk.data?.message ?? 'llm.stream_request failed';
          // 仅第 1 次尝试检测 auth 错误并刷新 token 重试
          if (attempt === 0 && isAuthTokenInvalid(errMsg)) {
            const refresh = await refreshDeviceToken();
            if (refresh.success && refresh.token) {
              token = refresh.token;
              authRetryNeeded = true;
              break; // break 内层 for-await，外层循环用新 token 重启流
            }
          }
          // 非 auth 错误或 refresh 失败 → yield error，结束
          yield { type: 'error', data: errMsg };
          return;
        }
        firstChunk = false;

        if (chunk.type === 'content') {
          // 后端 mcp_stream 推送的 content chunk data 是 { content: "delta" } 对象，
          // 提取 content 字符串作为 delta，与消费方期望一致。
          const delta =
            typeof chunk.data === 'string'
              ? chunk.data
              : chunk.data?.content ?? '';
          if (delta) {
            yield { type: 'content', data: delta };
          }
        } else if (chunk.type === 'error') {
          const errMsg =
            typeof chunk.data === 'string'
              ? chunk.data
              : chunk.data?.message ?? 'llm.stream_request failed';
          yield { type: 'error', data: errMsg };
          return;
        } else if (chunk.type === 'done') {
          yield { type: 'done', data: {} };
          return;
        }
      }

      if (authRetryNeeded) {
        continue; // 外层循环用新 token 重启流
      }

      // 如果流自然结束但没收到 done chunk，补一个。
      yield { type: 'done', data: {} };
      return;
    } catch (err: unknown) {
      // mcpStream 本身抛错（极罕见）→ 检测 auth → refresh + retry
      const message =
        err instanceof Error
          ? err.message
          : typeof err === 'string'
            ? err
            : 'mcp_stream invoke rejected';
      if (attempt === 0 && isAuthTokenInvalid(message)) {
        const refresh = await refreshDeviceToken();
        if (refresh.success && refresh.token) {
          token = refresh.token;
          continue; // 外层循环重试
        }
      }
      yield { type: 'error', data: message };
      return;
    }
  }

  // 重试耗尽（refresh 成功但重试仍失败，或 refresh 失败后第 2 次也失败）
  yield { type: 'error', data: 'llm.stream_request failed after token refresh' };
}
