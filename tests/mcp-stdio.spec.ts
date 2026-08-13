import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { getDefaultEnvironment, StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js'
import type { Transport } from '@modelcontextprotocol/sdk/shared/transport.js'

const cli = fileURLToPath(new URL('../dist/cli.js', import.meta.url))

describe.skipIf(!existsSync(cli))('stdio CLI (official SDK StdioClientTransport)', () => {
  it('lists control tools from the built binary', async () => {
    const transport = new StdioClientTransport({
      command: process.execPath,
      args: [cli],
      env: {
        ...getDefaultEnvironment(),
        ...(process.env.GITHUB_TOKEN ? { GITHUB_TOKEN: process.env.GITHUB_TOKEN } : {}),
        ...(process.env.GH_TOKEN ? { GH_TOKEN: process.env.GH_TOKEN } : {}),
      },
      stderr: 'pipe',
    })
    const client = new Client({ name: 'sdk-stdio-test', version: '0.0.0' })
    await client.connect(transport as Transport)
    const tools = await client.listTools()
    expect(tools.tools.map(tool => tool.name)).toContain('dsh_plugin_status')
    expect(tools.tools.map(tool => tool.name)).toContain('dsh_plugin_search')
    const status = await client.callTool({ name: 'dsh_plugin_status', arguments: {} })
    const text = (status.content as Array<{ text: string }>)[0]?.text ?? ''
    expect(text).toContain('deepseek-harness-plugin-mcp')
    await client.close()
  })
})
