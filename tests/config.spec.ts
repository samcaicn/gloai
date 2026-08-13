import { describe, expect, it } from 'vitest'
import { HelpRequested, resolveConfig } from '../src/config.js'

describe('resolveConfig', () => {
  it('defaults to stdio catalog-only', () => {
    const config = resolveConfig([], {})
    expect(config.transport).toBe('stdio')
    expect(config.allowInstall).toBe(false)
    expect(config.allowRuntime).toBe(false)
    expect(config.catalog).toBe(true)
    expect(config.profile).toBe('mcp-bridge')
    expect(config.port).toBe(8765)
  })

  it('lets flags override env', () => {
    const config = resolveConfig(
      ['--http', '--port', '9999', '--allow-install', '--allow-runtime', '--profile', 'web', '--no-catalog'],
      { DSH_PLUGIN_MCP_PORT: '1', DSH_PLUGIN_MCP_PROFILE: 'headless' },
    )
    expect(config.transport).toBe('http')
    expect(config.port).toBe(9999)
    expect(config.allowInstall).toBe(true)
    expect(config.allowRuntime).toBe(true)
    expect(config.profile).toBe('web')
    expect(config.catalog).toBe(false)
  })

  it('treats DSH_PLUGIN_MCP_CATALOG=0 as off', () => {
    expect(resolveConfig([], { DSH_PLUGIN_MCP_CATALOG: '0' }).catalog).toBe(false)
  })

  it('rejects an out-of-range port', () => {
    expect(() => resolveConfig(['--port', '0'], {})).toThrow(/1–65535/)
  })

  it('throws HelpRequested for -h', () => {
    expect(() => resolveConfig(['-h'], {})).toThrow(HelpRequested)
  })

  it('reads GitHub tokens from the environment', () => {
    expect(resolveConfig([], { GH_TOKEN: 'ghs_x' }).githubToken).toBe('ghs_x')
  })
})
