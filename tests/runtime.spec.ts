import { describe, expect, it } from 'vitest'
import { pluginAddArgs, pluginRemoveArgs, which } from '../src/profile/dsh-cli.js'
import { FakeDsh, fakeRuntimeClient, makeRuntime, testConfig } from './helpers.js'

describe('dsh plugin argv', () => {
  it('forwards add and remove unchanged', () => {
    expect(pluginAddArgs('github:dsh-external/dsh-tool-csv')).toEqual(['add', 'github:dsh-external/dsh-tool-csv'])
    expect(pluginRemoveArgs('@deepseek-ai/dsh-tool-csv')).toEqual(['remove', '@deepseek-ai/dsh-tool-csv'])
  })
})

describe('which', () => {
  it('returns null when PATH is empty', () => {
    expect(which('dsh', '')).toBeNull()
  })
})

describe('RuntimeHost', () => {
  it('refuses to start without --allow-runtime', async () => {
    const host = makeRuntime(testConfig(), new FakeDsh())
    await expect(host.start()).rejects.toThrow(/allow-runtime/)
  })

  it('refuses to start without dsh', async () => {
    const dsh = new FakeDsh()
    dsh.path = null
    const host = makeRuntime(testConfig({ allowRuntime: true }), dsh)
    await expect(host.start()).rejects.toThrow(/dsh not found/)
  })

  it('installs this package then requested plugins, then connects', async () => {
    const dsh = new FakeDsh()
    const calls: string[] = []
    const host = makeRuntime(
      testConfig({ allowRuntime: true }),
      dsh,
      fakeRuntimeClient(
        [{ name: 'csv_query', description: 'q', inputSchema: { type: 'object' } }],
        calls,
      ),
    )
    const status = await host.start(['github:dsh-external/dsh-tool-csv'])
    expect(dsh.pluginCalls.map(c => c.args)).toEqual([
      ['add', '/pkg/deepseek-harness-plugin-mcp'],
      ['add', 'github:dsh-external/dsh-tool-csv'],
    ])
    expect(dsh.spawned[0]?.env.DSH_PLUGIN_MCP_CATALOG).toBe('0')
    expect(status.running).toBe(true)
    expect(status.pid).toBe(4242)
    expect(host.listBridged().map(t => t.name)).toEqual(['dsh__csv_query'])
    const result = await host.call('dsh__csv_query', { q: 'a' }, AbortSignal.timeout(1000))
    expect(calls).toEqual(['csv_query'])
    expect(result.content[0]?.text).toContain('csv_query')
    await host.stop()
    expect(dsh.killed).toBe(true)
    expect(host.status().running).toBe(false)
  })
})
