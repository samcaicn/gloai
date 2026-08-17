import { describe, expect, it } from 'vitest'
import { resolvePluginConfig } from '../src/dsh-plugin.js'

describe('resolvePluginConfig', () => {
  it('defaults to HTTP catalog+bridge with mutating planes off', () => {
    const config = resolvePluginConfig({})
    expect(config.transport).toBe('http')
    expect(config.catalog).toBe(true)
    expect(config.bridgeTools).toBe(true)
    expect(config.allowInstall).toBe(false)
    expect(config.allowRuntime).toBe(false)
    expect(config.port).toBe(8765)
  })

  it('honors explicit catalog false', () => {
    expect(resolvePluginConfig({ catalog: false }).catalog).toBe(false)
  })
})
