import { describe, expect, it } from 'vitest'
import { GithubApiError, GithubClient } from '../src/github/client.js'
import { filterRepos } from '../src/github/catalog.js'
import { githubFetch, jsonResponse, makeCatalog, sampleRepo, searchPayload } from './helpers.js'

describe('PluginCatalog', () => {
  it('paginates the GitHub topic until a short page', async () => {
    const page1 = Array.from({ length: 100 }, (_, i) => ({
      ...sampleRepo,
      name: `plug-${i}`,
      fullName: `owner/plug-${i}`,
    }))
    const page2 = [{ ...sampleRepo, name: 'tail', fullName: 'owner/tail', stars: 99 }]
    const fetch = githubFetch({
      '/search/repositories?q=topic%3Adsh-plugin&sort=stars&order=desc&per_page=100&page=1': searchPayload(page1),
      '/search/repositories?q=topic%3Adsh-plugin&sort=stars&order=desc&per_page=100&page=2': searchPayload(page2),
    })
    const catalog = makeCatalog(fetch)
    const snapshot = await catalog.getSnapshot(true)
    expect(snapshot.repos).toHaveLength(101)
    expect(snapshot.repos[0]?.fullName).toBe('owner/tail')
    expect(snapshot.query).toBe('topic:dsh-plugin')
  })

  it('reuses a fresh in-memory snapshot', async () => {
    let calls = 0
    const fetch = githubFetch({
      '/search/repositories': searchPayload([sampleRepo]),
    })
    const counting = async (url: string, init?: RequestInit): Promise<Response> => {
      calls += 1
      return fetch(url, init)
    }
    const catalog = makeCatalog(counting)
    await catalog.getSnapshot(true)
    await catalog.getSnapshot()
    expect(calls).toBe(1)
  })

  it('surfaces a rate-limit error', async () => {
    const fetch = githubFetch({
      '/search/repositories': jsonResponse({ message: 'API rate limit exceeded' }, 403, {
        'x-ratelimit-remaining': '0',
        'x-ratelimit-reset': '1',
      }),
    })
    const catalog = makeCatalog(fetch)
    await expect(catalog.getSnapshot(true)).rejects.toBeInstanceOf(GithubApiError)
  })
})

describe('filterRepos', () => {
  const repos = [
    sampleRepo,
    { ...sampleRepo, name: 'dsh-web-ui', fullName: 'zhu/dsh-web-ui', description: 'skins', stars: 199, language: 'TypeScript' },
    { ...sampleRepo, name: 'old', fullName: 'x/old', archived: true, stars: 1 },
  ]

  it('matches tokens and hides archived by default', () => {
    expect(filterRepos(repos, { query: 'csv tool' }).map(r => r.name)).toEqual(['dsh-tool-csv'])
    expect(filterRepos(repos, { query: 'old' })).toEqual([])
    expect(filterRepos(repos, { query: 'old', includeArchived: true }).map(r => r.name)).toEqual(['old'])
  })

  it('filters by language, stars, and paginates', () => {
    expect(filterRepos(repos, { minStars: 100 }).map(r => r.name)).toEqual(['dsh-web-ui'])
    expect(filterRepos(repos, { language: 'TypeScript', limit: 1 }).map(r => r.name)).toEqual(['dsh-tool-csv'])
    expect(filterRepos(repos, { language: 'TypeScript', offset: 1, limit: 1 }).map(r => r.name)).toEqual(['dsh-web-ui'])
  })
})

describe('GithubClient.getFileText', () => {
  it('decodes base64 contents payloads', async () => {
    const client = new GithubClient({
      token: 't',
      fetch: githubFetch({
        '/repos/o/r/contents/package.json': {
          type: 'file',
          name: 'package.json',
          path: 'package.json',
          encoding: 'base64',
          content: Buffer.from('{"name":"x"}').toString('base64'),
        },
      }),
    })
    expect(client.authenticated).toBe(true)
    await expect(client.getFileText('o', 'r', 'package.json')).resolves.toBe('{"name":"x"}')
  })
})
