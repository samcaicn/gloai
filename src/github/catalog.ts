import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { GITHUB_TOPIC } from '../config.js'
import type { CatalogSnapshot, GithubRepo, PluginKind } from '../types.js'
import type { GithubClient } from './client.js'
import { mapSearchItem } from './client.js'

export interface CatalogStore {
  load(): Promise<CatalogSnapshot | null>
  save(snapshot: CatalogSnapshot): Promise<void>
}

export class FileCatalogStore implements CatalogStore {
  constructor(private readonly filePath: string) {}

  async load(): Promise<CatalogSnapshot | null> {
    try {
      const raw = await readFile(this.filePath, 'utf8')
      const parsed = JSON.parse(raw) as CatalogSnapshot
      if (!Array.isArray(parsed.repos) || typeof parsed.fetchedAt !== 'string') return null
      return parsed
    } catch (error) {
      const code = (error as NodeJS.ErrnoException).code
      if (code === 'ENOENT') return null
      throw error
    }
  }

  async save(snapshot: CatalogSnapshot): Promise<void> {
    await mkdir(dirname(this.filePath), { recursive: true })
    await writeFile(this.filePath, `${JSON.stringify(snapshot, null, 2)}\n`, 'utf8')
  }
}

export class MemoryCatalogStore implements CatalogStore {
  private snapshot: CatalogSnapshot | null = null

  async load(): Promise<CatalogSnapshot | null> {
    return this.snapshot
  }

  async save(snapshot: CatalogSnapshot): Promise<void> {
    this.snapshot = snapshot
  }
}

export class PluginCatalog {
  private memory: CatalogSnapshot | null = null

  constructor(
    private readonly github: GithubClient,
    private readonly store: CatalogStore,
    private readonly ttlMs: number,
    private readonly now: () => number = Date.now,
  ) {}

  async getSnapshot(force = false): Promise<CatalogSnapshot> {
    if (!force && this.memory && !this.isStale(this.memory)) return this.memory
    if (!force) {
      const disk = await this.store.load()
      if (disk && !this.isStale(disk)) {
        this.memory = disk
        return disk
      }
    }
    const fresh = await this.fetchTopic()
    this.memory = fresh
    await this.store.save(fresh)
    return fresh
  }

  isStale(snapshot: CatalogSnapshot): boolean {
    const age = this.now() - Date.parse(snapshot.fetchedAt)
    return Number.isNaN(age) || age > this.ttlMs
  }

  async fetchTopic(): Promise<CatalogSnapshot> {
    const repos: GithubRepo[] = []
    let page = 1
    let incomplete = false
    for (;;) {
      const response = await this.github.searchTopic(GITHUB_TOPIC, page, 100)
      incomplete = incomplete || response.incomplete_results
      for (const item of response.items) repos.push(mapSearchItem(item))
      if (response.items.length < 100) break
      page += 1
      if (page > 10) {
        incomplete = true
        break
      }
    }
    const unique = dedupeRepos(repos)
    unique.sort((a, b) => b.stars - a.stars || a.fullName.localeCompare(b.fullName))
    return {
      fetchedAt: new Date(this.now()).toISOString(),
      source: 'github-topic',
      query: `topic:${GITHUB_TOPIC}`,
      incomplete,
      repos: unique,
    }
  }
}

export function dedupeRepos(repos: GithubRepo[]): GithubRepo[] {
  const map = new Map<string, GithubRepo>()
  for (const repo of repos) map.set(repo.fullName.toLowerCase(), repo)
  return [...map.values()]
}

export interface ListFilter {
  query?: string | undefined
  kind?: PluginKind | undefined
  language?: string | undefined
  minStars?: number | undefined
  includeArchived?: boolean | undefined
  offset?: number | undefined
  limit?: number | undefined
}

export function filterRepos(repos: readonly GithubRepo[], filter: ListFilter): GithubRepo[] {
  const query = filter.query?.trim().toLowerCase()
  const result = repos.filter(repo => {
    if (filter.includeArchived !== true && repo.archived) return false
    if (filter.minStars !== undefined && repo.stars < filter.minStars) return false
    if (filter.language && (repo.language ?? '').toLowerCase() !== filter.language.toLowerCase()) return false
    if (!query) return true
    const hay = `${repo.fullName} ${repo.description ?? ''} ${repo.topics.join(' ')} ${repo.language ?? ''}`.toLowerCase()
    return query.split(/\s+/).every(token => hay.includes(token))
  })
  const offset = filter.offset ?? 0
  const limit = filter.limit ?? 30
  return result.slice(offset, offset + limit)
}

export function catalogCachePath(cacheDir: string): string {
  return join(cacheDir, 'catalog.json')
}
