import { describe, expect, it } from 'vitest'
import { classifyPlugin, installSpecFor, parseRepoSpec } from '../src/plugin/classify.js'
import { bridgedToolName, isControlToolName } from '../src/plugin/names.js'

describe('classifyPlugin', () => {
  it('marks deepseek-ai as official', () => {
    expect(classifyPlugin({
      owner: 'deepseek-ai',
      name: 'deepseek-harness',
      description: 'Everything is a Plugin',
      topics: ['dsh-plugin'],
      packageJson: null,
      rootEntries: ['README.md'],
    })).toContain('official')
  })

  it('detects a Cordis bundle from dsh.bundle.patch', () => {
    const kinds = classifyPlugin({
      owner: 'dsh-external',
      name: 'dsh-tool-csv',
      description: 'CSV data tool',
      topics: ['dsh-plugin'],
      packageJson: { name: '@deepseek-ai/dsh-tool-csv', dsh: { bundle: { patch: './cordis.patch.yml' } } },
      rootEntries: ['package.json', 'cordis.patch.yml'],
    })
    expect(kinds).toEqual(expect.arrayContaining(['bundle', 'tool']))
    expect(kinds).not.toContain('unknown')
  })

  it('detects UI client plugins', () => {
    const kinds = classifyPlugin({
      owner: 'zhu1090093659',
      name: 'dsh-web-ui',
      description: 'Plugin and skin collection for DSH Web UI',
      topics: ['web-ui', 'dsh-plugin'],
      packageJson: { dsh: { bundle: { patch: './cordis.patch.yml' }, client: { platform: 'web' } } },
      rootEntries: ['package.json'],
    })
    expect(kinds).toEqual(expect.arrayContaining(['bundle', 'ui']))
  })

  it('falls back to unknown when nothing matches', () => {
    expect(classifyPlugin({
      owner: 'alice',
      name: 'notes',
      description: null,
      topics: ['dsh-plugin'],
      packageJson: null,
      rootEntries: [],
    })).toEqual(['unknown'])
  })
})

describe('parseRepoSpec', () => {
  it('accepts owner/repo, github: spec, and HTML URLs', () => {
    expect(parseRepoSpec('dsh-external/dsh-tool-csv')).toEqual({ owner: 'dsh-external', repo: 'dsh-tool-csv' })
    expect(parseRepoSpec('github:dsh-external/dsh-tool-csv')).toEqual({ owner: 'dsh-external', repo: 'dsh-tool-csv' })
    expect(parseRepoSpec('https://github.com/dsh-external/dsh-tool-csv.git')).toEqual({
      owner: 'dsh-external',
      repo: 'dsh-tool-csv',
    })
  })

  it('rejects empty garbage', () => {
    expect(() => parseRepoSpec('not-a-repo')).toThrow(/expected owner\/repo/)
  })
})

describe('installSpecFor', () => {
  it('emits the dsh plugin add github spec', () => {
    expect(installSpecFor('bobleer', 'deepseek-harness-plugin-mcp')).toBe('github:bobleer/deepseek-harness-plugin-mcp')
  })
})

describe('bridgedToolName', () => {
  it('keeps a clean dsh__ prefix', () => {
    expect(bridgedToolName('csv_query')).toBe('dsh__csv_query')
  })

  it('replaces illegal characters and appends a hash', () => {
    const name = bridgedToolName('csv.query')
    expect(name.startsWith('dsh__csv_query_')).toBe(true)
    expect(name.length).toBeLessThanOrEqual(64)
    expect(/^[A-Za-z0-9_-]+$/.test(name)).toBe(true)
  })
})

describe('isControlToolName', () => {
  it('recognizes control-plane prefixes', () => {
    expect(isControlToolName('dsh_plugin_search')).toBe(true)
    expect(isControlToolName('dsh_runtime_start')).toBe(true)
    expect(isControlToolName('dsh__csv_query')).toBe(false)
  })
})
