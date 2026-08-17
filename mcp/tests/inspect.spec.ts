import { describe, expect, it } from 'vitest'
import { inspectPlugin, parsePatchYaml, resolveRepo } from '../src/plugin/inspect.js'
import { GithubClient } from '../src/github/client.js'
import { githubFetch, sampleRepo } from './helpers.js'

const packageJson = {
  name: '@deepseek-ai/dsh-tool-csv',
  dsh: { bundle: { patch: './cordis.patch.yml' } },
}

describe('inspectPlugin', () => {
  it('loads manifest, patch, readme, and classifies a bundle', async () => {
    const github = new GithubClient({
      token: null,
      fetch: githubFetch({
        '/repos/dsh-external/dsh-tool-csv/contents/': [
          { type: 'file', name: 'package.json', path: 'package.json' },
          { type: 'file', name: 'cordis.patch.yml', path: 'cordis.patch.yml' },
          { type: 'file', name: 'README.md', path: 'README.md' },
          { type: 'file', name: 'SKILL.md', path: 'SKILL.md' },
        ],
        '/repos/dsh-external/dsh-tool-csv/contents/package.json': {
          encoding: 'base64',
          content: Buffer.from(JSON.stringify(packageJson)).toString('base64'),
        },
        '/repos/dsh-external/dsh-tool-csv/contents/cordis.patch.yml': {
          encoding: 'base64',
          content: Buffer.from('- insert:\n  - id: tool-csv\n    name: "@deepseek-ai/dsh-tool-csv"\n').toString('base64'),
        },
        '/repos/dsh-external/dsh-tool-csv/readme': {
          encoding: 'base64',
          content: Buffer.from('# csv\n').toString('base64'),
        },
      }),
    })
    const inspection = await inspectPlugin(github, sampleRepo)
    expect(inspection.isDshBundle).toBe(true)
    expect(inspection.packageName).toBe('@deepseek-ai/dsh-tool-csv')
    expect(inspection.installSpec).toBe('github:dsh-external/dsh-tool-csv')
    expect(inspection.kinds).toEqual(expect.arrayContaining(['bundle', 'tool', 'skill']))
    expect(inspection.readme).toBe('# csv\n')
    expect(inspection.patchText).toContain('tool-csv')
    expect(inspection.skillFiles).toContain('SKILL.md')
  })
})

describe('resolveRepo', () => {
  it('uses the catalog hit when present', async () => {
    const github = new GithubClient({ token: null, fetch: githubFetch({}) })
    await expect(resolveRepo(github, [sampleRepo], 'github:dsh-external/dsh-tool-csv')).resolves.toEqual(sampleRepo)
  })
})

describe('parsePatchYaml', () => {
  it('accepts Cordis !!js scalars as opaque strings', () => {
    const parsed = parsePatchYaml(`
- insert:
    - id: dsh-plugin-mcp
      config:
        port: !!js Number(process.env.DSH_PLUGIN_MCP_PORT ?? 8765)
        catalog: !!js process.env.DSH_PLUGIN_MCP_CATALOG !== '0'
`) as Array<{ insert: Array<{ config: { port: string; catalog: string } }> }>
    expect(parsed[0]?.insert[0]?.config.port).toContain('DSH_PLUGIN_MCP_PORT')
    expect(parsed[0]?.insert[0]?.config.catalog).toContain('DSH_PLUGIN_MCP_CATALOG')
  })
})
