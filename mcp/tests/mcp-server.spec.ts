import { describe, expect, it } from 'vitest'
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js'
import { createPluginMcpServer } from '../src/mcp/server.js'
import { GithubClient } from '../src/github/client.js'
import { makeCatalog, makeRuntime, FakeDsh, githubFetch, sampleRepo, searchPayload, testConfig } from './helpers.js'
import { renderPrompt } from '../src/mcp/prompts.js'

describe('MCP session', () => {
  it('lists control tools, resources, and prompts, and searches the catalog', async () => {
    const fetch = githubFetch({
      '/search/repositories': searchPayload([sampleRepo]),
      '/repos/dsh-external/dsh-tool-csv/contents/': [
        { type: 'file', name: 'package.json', path: 'package.json' },
      ],
      '/repos/dsh-external/dsh-tool-csv/contents/package.json': {
        encoding: 'base64',
        content: Buffer.from(JSON.stringify({
          name: '@deepseek-ai/dsh-tool-csv',
          dsh: { bundle: { patch: './cordis.patch.yml' } },
        })).toString('base64'),
      },
      '/repos/dsh-external/dsh-tool-csv/contents/cordis.patch.yml': { message: 'missing' },
      '/repos/dsh-external/dsh-tool-csv/readme': {
        encoding: 'base64',
        content: Buffer.from('# hello\n').toString('base64'),
      },
    })
    const config = testConfig({ allowInstall: false })
    const github = new GithubClient({ token: null, fetch })
    const catalog = makeCatalog(fetch)
    const dsh = new FakeDsh()
    const runtime = makeRuntime(config, dsh)
    const handle = createPluginMcpServer({ config, catalog, github, dsh, runtime })

    const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair()
    await handle.server.connect(serverTransport)
    const client = new Client({ name: 'test', version: '0.0.0' })
    await client.connect(clientTransport)

    const tools = await client.listTools()
    const names = tools.tools.map(tool => tool.name)
    expect(names).toContain('dsh_plugin_search')
    expect(names).toContain('dsh_plugin_inspect')
    expect(names).toContain('dsh_runtime_start')

    const search = await client.callTool({ name: 'dsh_plugin_search', arguments: { query: 'csv' } })
    const searchText = (search.content as Array<{ text: string }>)[0]?.text ?? ''
    expect(searchText).toContain('dsh-tool-csv')

    const inspect = await client.callTool({
      name: 'dsh_plugin_inspect',
      arguments: { spec: 'dsh-external/dsh-tool-csv' },
    })
    expect((inspect.content as Array<{ text: string }>)[0]?.text).toContain('github:dsh-external/dsh-tool-csv')

    const denied = await client.callTool({
      name: 'dsh_plugin_install',
      arguments: { spec: 'github:dsh-external/dsh-tool-csv' },
    })
    expect(denied.isError).toBe(true)

    const resources = await client.listResources()
    expect(resources.resources.map(r => r.uri)).toContain('dsh-plugin://catalog')

    const catalogResource = await client.readResource({ uri: 'dsh-plugin://catalog' })
    const catalogText = catalogResource.contents.map(item => 'text' in item ? item.text : '').join('')
    expect(catalogText).toContain('dsh-plugin')

    const prompts = await client.listPrompts()
    expect(prompts.prompts.map(p => p.name)).toContain('use-dsh-plugin')

    const prompt = await client.getPrompt({
      name: 'search-dsh-plugins',
      arguments: { task: 'parse csv' },
    })
    expect(prompt.messages[0]?.content).toMatchObject({ type: 'text', text: expect.stringContaining('parse csv') })

    await client.close()
    await handle.server.close()
  })

  it('reports an empty catalog from cache without contacting GitHub', async () => {
    const fetch = async (): Promise<Response> => {
      throw new Error('network should not run for status')
    }
    const config = testConfig()
    const github = new GithubClient({ token: null, fetch })
    const catalog = makeCatalog(fetch)
    const dsh = new FakeDsh()
    const runtime = makeRuntime(config, dsh)
    const handle = createPluginMcpServer({ config, catalog, github, dsh, runtime })
    const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair()
    await handle.server.connect(serverTransport)
    const client = new Client({ name: 'test', version: '0.0.0' })
    await client.connect(clientTransport)

    const status = await client.callTool({ name: 'dsh_plugin_status', arguments: {} })
    const text = (status.content as Array<{ text: string }>)[0]?.text ?? ''
    expect(status.isError).not.toBe(true)
    expect(text).toContain('"empty": true')

    await client.close()
    await handle.server.close()
  })
})

describe('renderPrompt', () => {
  it('rejects unknown names', () => {
    expect(() => renderPrompt('nope', {})).toThrow(/unknown prompt/)
  })
})
