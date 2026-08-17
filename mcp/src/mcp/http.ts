import { createServer, type IncomingMessage, type ServerResponse } from 'node:http'
import { randomUUID } from 'node:crypto'
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js'
import { isInitializeRequest } from '@modelcontextprotocol/sdk/types.js'
import type { Server } from '@modelcontextprotocol/sdk/server/index.js'
import type { Transport } from '@modelcontextprotocol/sdk/shared/transport.js'
import type { AppConfig } from '../config.js'
import { notifyToolsChanged } from './server.js'

export type McpSessionFactory = () => Server

interface SessionRecord {
  transport: StreamableHTTPServerTransport
  server: Server
}

const CORS_HEADERS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET,POST,DELETE,OPTIONS',
  'Access-Control-Allow-Headers': 'content-type,mcp-session-id,mcp-protocol-version,accept,last-event-id',
  'Access-Control-Expose-Headers': 'mcp-session-id,mcp-protocol-version',
} as const

async function readJsonBody(req: IncomingMessage): Promise<unknown> {
  const chunks: Buffer[] = []
  for await (const chunk of req) chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk))
  if (chunks.length === 0) return undefined
  return JSON.parse(Buffer.concat(chunks).toString('utf8')) as unknown
}

/**
 * Serve Streamable HTTP MCP plus GET /health.
 * Each initialize POST gets its own MCP Server instance; catalog/runtime state is shared by the factory closure.
 */
export function serveHttp(
  createSession: McpSessionFactory,
  config: AppConfig,
): Promise<{ close: () => Promise<void>; url: string; notifyToolsChanged: () => void }> {
  const sessions = new Map<string, SessionRecord>()

  const http = createServer(async (req, res) => {
    try {
      await handleHttp(req, res, createSession, sessions, config)
    } catch (error) {
      if (!res.headersSent) {
        applyCors(res)
        res.writeHead(500, { 'Content-Type': 'application/json' })
      }
      res.end(JSON.stringify({ error: String(error) }))
    }
  })

  return new Promise((resolve, reject) => {
    http.on('error', reject)
    http.listen(config.port, config.host, () => {
      const address = http.address()
      const port = typeof address === 'object' && address !== null ? address.port : config.port
      resolve({
        url: `http://${config.host}:${port}/mcp`,
        notifyToolsChanged: () => {
          for (const session of sessions.values()) notifyToolsChanged(session.server)
        },
        close: async () => {
          for (const session of [...sessions.values()]) {
            await session.transport.close().catch(() => undefined)
            await session.server.close().catch(() => undefined)
          }
          sessions.clear()
          await new Promise<void>((done, fail) => {
            http.close(error => error ? fail(error) : done())
          })
        },
      })
    })
  })
}

async function handleHttp(
  req: IncomingMessage,
  res: ServerResponse,
  createSession: McpSessionFactory,
  sessions: Map<string, SessionRecord>,
  config: AppConfig,
): Promise<void> {
  const host = req.headers.host ?? `${config.host}:${config.port}`
  const url = new URL(req.url ?? '/', `http://${host}`)

  if (url.pathname === '/health' && req.method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify({ ok: true, server: 'deepseek-harness-plugin-mcp' }))
    return
  }

  if (url.pathname !== '/mcp') {
    res.writeHead(404, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify({ error: 'not found' }))
    return
  }

  applyCors(res)

  if (req.method === 'OPTIONS') {
    res.writeHead(204)
    res.end()
    return
  }

  const sessionId = header(req, 'mcp-session-id')
  if (sessionId && sessions.has(sessionId)) {
    const body = req.method === 'POST' ? await readJsonBody(req) : undefined
    await sessions.get(sessionId)!.transport.handleRequest(req, res, body)
    return
  }

  if (req.method === 'POST') {
    const body = await readJsonBody(req)
    if (!isInitializeRequest(body)) {
      res.writeHead(400, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({
        jsonrpc: '2.0',
        error: { code: -32000, message: 'Bad Request: Server not initialized' },
        id: null,
      }))
      return
    }
    const sessionServer = createSession()
    const transport = new StreamableHTTPServerTransport({
      sessionIdGenerator: () => randomUUID(),
      onsessioninitialized: (id) => {
        sessions.set(id, { transport, server: sessionServer })
      },
    })
    transport.onclose = () => {
      const id = transport.sessionId
      if (id) sessions.delete(id)
      void sessionServer.close()
    }
    await sessionServer.connect(transport as Transport)
    await transport.handleRequest(req, res, body)
    return
  }

  res.writeHead(400, { 'Content-Type': 'application/json' })
  res.end(JSON.stringify({ error: 'missing mcp-session-id' }))
}

function applyCors(res: ServerResponse): void {
  for (const [name, value] of Object.entries(CORS_HEADERS)) res.setHeader(name, value)
}

function header(req: IncomingMessage, name: string): string | undefined {
  const value = req.headers[name]
  if (Array.isArray(value)) return value[0]
  return value
}
