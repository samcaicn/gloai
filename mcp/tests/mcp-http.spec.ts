import { describe, expect, it } from 'vitest'
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'
import type { Transport } from '@modelcontextprotocol/sdk/shared/transport.js'
import { serveHttp } from '../src/mcp/http.js'
import { createPluginMcpServer } from '../src/mcp/server.js'
import { GithubClient } from '../src/github/client.js'
import { FakeDsh, githubFetch, makeCatalog, makeRuntime, sampleRepo, searchPayload, testConfig } from './helpers.js'

async function connectHttp(url: string): Promise<{ client: Client; close: () => Promise<void> }> {
  const client = new Client({ name: 'sdk-http-test', version: '0.0.0' })
  const transport = new StreamableHTTPClientTransport(new URL(url))
  await client.connect(transport as Transport)
  return {
    client,
    close: async () => {
      await client.close()
    },
  }
}

function sessionDeps() {
  const fetch = githubFetch({
    '/search/repositories': searchPayload([sampleRepo]),
  })
  const config = testConfig({ transport: 'http', host: '127.0.0.1', port: 0 })
  const github = new GithubClient({ token: null, fetch })
  const catalog = makeCatalog(fetch)
  const dsh = new FakeDsh()
  const runtime = makeRuntime(config, dsh)
  return { config, catalog, github, dsh, runtime }
}

describe('Streamable HTTP MCP (official SDK client)', () => {
  it('completes initialize, lists tools, and accepts two concurrent sessions', async () => {
    const deps = sessionDeps()
    const listening = await serveHttp(() => createPluginMcpServer(deps).server, { ...deps.config, port: 0 })

    const first = await connectHttp(listening.url)
    const second = await connectHttp(listening.url)

    const [toolsA, toolsB] = await Promise.all([
      first.client.listTools(),
      second.client.listTools(),
    ])
    const namesA = toolsA.tools.map(tool => tool.name)
    const namesB = toolsB.tools.map(tool => tool.name)
    expect(namesA).toContain('dsh_plugin_search')
    expect(namesB).toEqual(namesA)

    const status = await first.client.callTool({ name: 'dsh_plugin_status', arguments: {} })
    const statusText = (status.content as Array<{ text: string }>)[0]?.text ?? ''
    expect(statusText).toContain('deepseek-harness-plugin-mcp')
    expect(statusText).toContain('"empty": true')

    const search = await second.client.callTool({ name: 'dsh_plugin_search', arguments: { query: 'csv' } })
    expect((search.content as Array<{ text: string }>)[0]?.text).toContain('dsh-tool-csv')

    await first.close()
    await second.close()
    await listening.close()
  })

  it('answers OPTIONS /mcp with CORS headers for browser Inspector', async () => {
    const deps = sessionDeps()
    const listening = await serveHttp(() => createPluginMcpServer(deps).server, { ...deps.config, port: 0 })
    const response = await fetch(listening.url, { method: 'OPTIONS' })
    expect(response.status).toBe(204)
    expect(response.headers.get('access-control-allow-origin')).toBe('*')
    expect(response.headers.get('access-control-allow-headers')).toMatch(/mcp-session-id/)
    await listening.close()
  })
})
