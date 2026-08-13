import { describe, expect, it } from 'vitest'
import { serveHttp } from '../src/mcp/http.js'
import { Server } from '@modelcontextprotocol/sdk/server/index.js'
import { testConfig } from './helpers.js'

describe('HTTP health', () => {
  it('serves GET /health without an MCP session', async () => {
    const server = new Server({ name: 'test', version: '0.0.0' }, { capabilities: {} })
    const config = testConfig({ transport: 'http', port: 0, host: '127.0.0.1' })
    // serveHttp uses config.port; 0 lets the OS pick. Node listen(0) works.
    const listening = await serveHttp(server, { ...config, port: 0 })
    const healthUrl = listening.url.replace(/\/mcp$/, '/health')
    const response = await fetch(healthUrl)
    expect(response.ok).toBe(true)
    expect(await response.json()).toEqual({ ok: true, server: 'deepseek-harness-plugin-mcp' })
    const missing = await fetch(new URL('/nope', healthUrl))
    expect(missing.status).toBe(404)
    await listening.close()
    await server.close()
  })
})
