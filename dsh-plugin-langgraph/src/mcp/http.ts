import { type Server } from '@modelcontextprotocol/sdk/server/index.js'
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js'
import { createMcpExpressApp } from '@modelcontextprotocol/sdk/server/express.js'
import { type AppConfig } from '../config.js'

export interface HttpListening {
  url: string
  close(): Promise<void>
  notifyToolsChanged(): void
}

export async function serveHttp(server: Server, config: AppConfig): Promise<HttpListening> {
  const express = await import('express')

  // 使用 SDK 的 Express 应用
  const app = createMcpExpressApp()

  // 健康检查
  app.get('/health', (_req, res) => {
    res.json({ status: 'ok', server: 'dsh-plugin-langgraph' })
  })

  // MCP 端点 - 无状态模式
  app.post('/mcp', async (req, res) => {
    try {
      const transport = new StreamableHTTPServerTransport({
        sessionIdGenerator: undefined, // 无状态模式
      })
      await server.connect(transport)
      await transport.handleRequest(req, res, req.body)
      res.on('close', () => {
        transport.close()
      })
    } catch (error) {
      console.error('[MCP] Error:', error)
      if (!res.headersSent) {
        res.status(500).json({
          jsonrpc: '2.0',
          error: { code: -32603, message: 'Internal server error' },
          id: null,
        })
      }
    }
  })

  // 启动 HTTP 服务器
  const httpServer = await new Promise<import('node:http').Server>((resolve) => {
    const s = app.listen(config.port, config.host, () => resolve(s))
  })

  const url = `http://${config.host}:${config.port}`

  return {
    url,
    async close(): Promise<void> {
      await new Promise<void>((resolve, reject) => {
        httpServer.close((err) => err ? reject(err) : resolve())
      })
    },
    notifyToolsChanged(): void {
      void server.sendToolListChanged()
    },
  }
}
