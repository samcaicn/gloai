import type { GithubFetcher } from '../types.js'

export type { GithubFetcher }

export class GithubApiError extends Error {
  readonly status: number
  readonly rateLimitRemaining: string | null
  readonly rateLimitReset: string | null

  constructor(message: string, status: number, remaining: string | null, reset: string | null) {
    super(message)
    this.name = 'GithubApiError'
    this.status = status
    this.rateLimitRemaining = remaining
    this.rateLimitReset = reset
  }
}

export interface GithubClientOptions {
  token: string | null
  fetch?: GithubFetcher
  userAgent?: string
  apiBase?: string
}

interface GithubContentFile {
  type: string
  name: string
  path: string
  encoding?: string
  content?: string
  download_url?: string | null
}

interface GithubSearchRepo {
  full_name: string
  name: string
  owner?: { login?: string }
  description: string | null
  html_url: string
  stargazers_count: number
  forks_count: number
  language: string | null
  topics?: string[]
  default_branch: string
  archived: boolean
  updated_at: string
}

interface GithubSearchResponse {
  total_count: number
  incomplete_results: boolean
  items: GithubSearchRepo[]
}

/**
 * Thin GitHub REST client. All catalog and inspect I/O goes through here so tests inject fetch.
 */
export class GithubClient {
  private readonly token: string | null
  private readonly fetchImpl: GithubFetcher
  private readonly userAgent: string
  private readonly apiBase: string

  constructor(options: GithubClientOptions) {
    this.token = options.token
    this.fetchImpl = options.fetch ?? fetch
    this.userAgent = options.userAgent ?? 'deepseek-harness-plugin-mcp'
    this.apiBase = (options.apiBase ?? 'https://api.github.com').replace(/\/+$/, '')
  }

  get authenticated(): boolean {
    return this.token !== null && this.token.length > 0
  }

  async request(path: string): Promise<Response> {
    const url = path.startsWith('http') ? path : `${this.apiBase}${path}`
    const headers: Record<string, string> = {
      Accept: 'application/vnd.github+json',
      'User-Agent': this.userAgent,
      'X-GitHub-Api-Version': '2022-11-28',
    }
    if (this.token) headers.Authorization = `Bearer ${this.token}`
    const response = await this.fetchImpl(url, { headers })
    if (!response.ok) {
      const remaining = response.headers.get('x-ratelimit-remaining')
      const reset = response.headers.get('x-ratelimit-reset')
      const body = await response.text().catch(() => '')
      const hint = response.status === 403 && remaining === '0'
        ? ` GitHub rate limit exceeded; retry after ${reset ?? 'unknown'} unix seconds, or set GITHUB_TOKEN.`
        : ''
      throw new GithubApiError(
        `GitHub ${response.status} ${response.statusText} for ${path}.${hint} ${body.slice(0, 300)}`.trim(),
        response.status,
        remaining,
        reset,
      )
    }
    return response
  }

  async json<T>(path: string): Promise<T> {
    const response = await this.request(path)
    return await response.json() as T
  }

  async searchTopic(topic: string, page: number, perPage = 100): Promise<GithubSearchResponse> {
    const q = encodeURIComponent(`topic:${topic}`)
    return await this.json<GithubSearchResponse>(
      `/search/repositories?q=${q}&sort=stars&order=desc&per_page=${perPage}&page=${page}`,
    )
  }

  async listRoot(owner: string, repo: string): Promise<string[]> {
    try {
      const entries = await this.json<GithubContentFile[]>(`/repos/${owner}/${repo}/contents/`)
      return entries.map(entry => entry.name)
    } catch (error) {
      if (error instanceof GithubApiError && error.status === 404) return []
      throw error
    }
  }

  async getFileText(owner: string, repo: string, path: string): Promise<string | null> {
    try {
      const file = await this.json<GithubContentFile>(`/repos/${owner}/${repo}/contents/${encodeURIComponent(path)}`)
      if (file.encoding === 'base64' && typeof file.content === 'string') {
        return Buffer.from(file.content.replace(/\n/g, ''), 'base64').toString('utf8')
      }
      if (typeof file.content === 'string') return file.content
      if (file.download_url) {
        const response = await this.fetchImpl(file.download_url)
        if (!response.ok) return null
        return await response.text()
      }
      return null
    } catch (error) {
      if (error instanceof GithubApiError && error.status === 404) return null
      throw error
    }
  }

  async getReadme(owner: string, repo: string): Promise<string | null> {
    try {
      const file = await this.json<GithubContentFile>(`/repos/${owner}/${repo}/readme`)
      if (file.encoding === 'base64' && typeof file.content === 'string') {
        return Buffer.from(file.content.replace(/\n/g, ''), 'base64').toString('utf8')
      }
      return file.content ?? null
    } catch (error) {
      if (error instanceof GithubApiError && error.status === 404) return null
      throw error
    }
  }
}

export function mapSearchItem(item: GithubSearchRepo): {
  owner: string
  name: string
  fullName: string
  description: string | null
  htmlUrl: string
  stars: number
  forks: number
  language: string | null
  topics: string[]
  defaultBranch: string
  archived: boolean
  updatedAt: string
} {
  const owner = item.owner?.login ?? item.full_name.split('/')[0] ?? ''
  return {
    owner,
    name: item.name,
    fullName: item.full_name,
    description: item.description,
    htmlUrl: item.html_url,
    stars: item.stargazers_count,
    forks: item.forks_count,
    language: item.language,
    topics: item.topics ?? [],
    defaultBranch: item.default_branch,
    archived: item.archived,
    updatedAt: item.updated_at,
  }
}
