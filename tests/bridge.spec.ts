import { describe, expect, it } from 'vitest'
import { ToolBridge, mapRuntimeResult } from '../src/runtime/bridge.js'
import { FakeTools } from './helpers.js'

describe('ToolBridge', () => {
  it('skips control-plane names and prefixes the rest', () => {
    const tools = new FakeTools([
      { name: 'dsh_plugin_search', description: 'no', parameters: { type: 'object' } },
      { name: 'csv_query', description: 'Query CSV', parameters: { type: 'object', properties: { q: { type: 'string' } } } },
    ])
    const bridge = new ToolBridge(tools)
    const listed = bridge.sync()
    expect(listed.map(t => t.publicName)).toEqual(['dsh__csv_query'])
    expect(bridge.mcpSpecs()[0]?.description).toContain('csv_query')
  })

  it('executes the raw DSH name and maps success', async () => {
    const tools = new FakeTools(
      [{ name: 'csv_query', description: 'Query CSV', parameters: { type: 'object' } }],
      { csv_query: { isError: false, content: [{ type: 'text', text: 'rows: 3' }], value: { rows: 3 } } },
    )
    const bridge = new ToolBridge(tools)
    bridge.sync()
    const result = await bridge.call('dsh__csv_query', { q: 'a' }, AbortSignal.timeout(1000))
    expect(result.isError).toBeUndefined()
    expect(result.content[0]?.text).toBe('rows: 3')
    expect(result.structuredContent).toEqual({ rows: 3 })
  })

  it('maps pipeline errors', () => {
    const mapped = mapRuntimeResult({ isError: true, content: [], error: { message: 'denied' } })
    expect(mapped).toEqual({ content: [{ type: 'text', text: 'denied' }], isError: true })
  })
})
