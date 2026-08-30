#!/usr/bin/env node
import { resolveConfig } from './config.js'
import { LangGraphScheduler } from './scheduler/core.js'
import { createSchedulerMcpServer } from './mcp/server.js'
import { serveHttp } from './mcp/http.js'

async function main(): Promise<void> {
  const config = resolveConfig()

  // 创建运行时（独立模式下使用内置工具）
  const runtime = createStandaloneRuntime()
  const scheduler = new LangGraphScheduler(runtime, config.checkpointDir, config.maxAgents)
  const { server } = createSchedulerMcpServer({
    scheduler,
    context: {
      tools: runtime,
      on: () => () => {},
      effect: () => {},
      logger: console,
    },
  })

  const listening = await serveHttp(server, config)
  console.error(`dsh-plugin-langgraph 已启动: ${listening.url}`)
}

/** 独立模式运行时 - 提供基础工具 */
function createStandaloneRuntime() {
  return {
    schemas(): Array<{ name: string; description: string; parameters: Record<string, unknown> }> {
      return [
        {
          name: 'echo',
          description: '回显输入的文本',
          parameters: { type: 'object', properties: { text: { type: 'string' } }, required: ['text'] },
        },
        {
          name: 'now',
          description: '获取当前时间戳',
          parameters: { type: 'object', properties: {} },
        },
      ]
    },
    async execute(input: { callId: string; name: string; arguments: unknown; signal: AbortSignal }) {
      const args = input.arguments as Record<string, unknown>
      if (input.name === 'echo') {
        return { isError: false, content: [{ type: 'text', text: String(args.text ?? '') }] }
      }
      if (input.name === 'now') {
        return { isError: false, content: [{ type: 'text', text: new Date().toISOString() }] }
      }
      return { isError: true, content: [{ type: 'text', text: `未知工具: ${input.name}` }] }
    },
  }
}

main().catch((error) => {
  console.error('启动失败:', error)
  process.exit(1)
})
