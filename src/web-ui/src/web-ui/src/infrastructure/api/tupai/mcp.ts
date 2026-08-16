// MCP 相关 Tauri 命令封装。
// 命令名已对齐后端 lib.rs 的 invoke_handler 注册：
//   mcpCall → mcp_call_v2  (mcp_proxy.rs: mcp_call_v2(action, params, timeout_secs?, token?) —— serverName 不被使用)
//   mcpStream → mcp_stream  (ext_streams.rs: mcp_stream(action, params, on_event: Channel, token?) —— 流式经 Tauri 2 Channel API 推送 SSE 增量)
import { Channel } from '@tauri-apps/api/core';
import { invoke } from './invoke';

// 后端 mcp_call_v2 期望 (action, params, timeout_secs?, token?)；toolName 作为 action，args 作为 params。serverName 保留在 invoke 对象中以维持函数签名，后端 serde 忽略未知字段（后端为单上游代理）。
export async function mcpCall(serverName: string, toolName: string, args: any): Promise<any> {
  return invoke('mcp_call_v2', { serverName, action: toolName, params: args });
}

// 后端 mcp_stream 期望 (action, params, on_event: Channel<McpStreamChunk>, token?)；
// toolName 作为 action，args 作为 params。serverName 沿用 mcpCall 约定保留在 invoke 对象中
// 以维持函数签名（后端 serde 忽略未知字段）。
// 通过 Tauri 2 Channel API 接收 SSE 增量，包装成 AsyncIterable 返回（签名保持不变）。
interface McpStreamChunk {
  type: 'content' | 'error' | 'done';
  data: any;
}

export async function mcpStream(
  serverName: string,
  toolName: string,
  args: any,
  token?: string | null,
): Promise<AsyncIterable<any>> {
  const channel = new Channel<McpStreamChunk>();
  const queue: McpStreamChunk[] = [];
  let pending: ((result: IteratorResult<McpStreamChunk>) => void) | null = null;
  let finished = false;

  channel.onmessage = (msg: McpStreamChunk) => {
    console.log('[mcpStream] Received chunk:', msg);
    if (msg.type === 'done') {
      finished = true;
      if (pending) {
        const resolve = pending;
        pending = null;
        resolve({ value: undefined, done: true });
      }
      return;
    }
    if (pending) {
      const resolve = pending;
      pending = null;
      resolve({ value: msg, done: false });
      return;
    }
    queue.push(msg);
  };

  // fire-and-forget：invoke 的 Promise 不 await，错误经 error 分片推给消费方。
  console.log('[mcpStream] Invoking mcp_stream with:', { serverName, toolName, args, token });
  invoke('mcp_stream', { serverName, action: toolName, params: args, onEvent: channel, token: token ?? undefined }).catch((e: unknown) => {
    const errChunk: McpStreamChunk = { type: 'error', data: { message: String(e) } };
    if (pending) {
      const resolve = pending;
      pending = null;
      resolve({ value: errChunk, done: false });
    } else {
      queue.push(errChunk);
    }
    finished = true;
  });

  const iterator: AsyncIterator<McpStreamChunk> = {
    next(): Promise<IteratorResult<McpStreamChunk>> {
      if (queue.length > 0) {
        return Promise.resolve({ value: queue.shift()!, done: false });
      }
      if (finished) {
        return Promise.resolve({ value: undefined, done: true });
      }
      return new Promise<IteratorResult<McpStreamChunk>>((resolve) => {
        pending = resolve;
      });
    },
    // 消费方 `for await ... break` / 提前终止时被运行时调用：标记结束 + 排空
    // pending resolver + 清空队列，避免迭代器悬挂与 Promise 泄漏。
    //
    // 注：Tauri 2.11 的 Channel 无关闭检测 API（无 is_closed/closed），后端 mcp_stream
    // 无法感知前端提前终止，会继续消费上游直到响应自然结束或超时——这是已知平台限制，
    // 需要真正的取消需另加 cancel token 机制（本次不做，保持简单健壮）。前端 return()
    // 至少保证消费方干净退出、不泄漏 Promise / 不悬挂迭代器。
    return(): Promise<IteratorResult<McpStreamChunk>> {
      finished = true;
      queue.length = 0;
      if (pending) {
        const resolve = pending;
        pending = null;
        resolve({ value: undefined, done: true });
      }
      return Promise.resolve({ value: undefined, done: true });
    },
  };

  return {
    [Symbol.asyncIterator]() {
      return iterator;
    },
  };
}
