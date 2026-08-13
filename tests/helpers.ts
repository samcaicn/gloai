import type { ChildHandle, DshCommandResult, DshRunner, GithubRepo, ToolRuntimeResult, ToolRuntimeView } from '../src/types.js'
import type { AppConfig } from '../src/config.js'
import { DEFAULT_CACHE_TTL_MS, DEFAULT_HTTP_HOST, DEFAULT_HTTP_PORT, DEFAULT_PROFILE } from '../src/config.js'
import { MemoryCatalogStore, PluginCatalog } from '../src/github/catalog.js'
import { GithubClient, type GithubFetcher } from '../src/github/client.js'
import { RuntimeHost, type HttpWaiter, type McpConnector, type RuntimeMcpClient } from '../src/runtime/host.js'

export const sampleRepo: GithubRepo = {
  owner: 'dsh-external',
  name: 'dsh-tool-csv',
  fullName: 'dsh-external/dsh-tool-csv',
  description: 'CSV tool for DeepSeek Harness',
  htmlUrl: 'https://github.com/dsh-external/dsh-tool-csv',
  stars: 12,
  forks: 1,
  language: 'TypeScript',
  topics: ['dsh-plugin', 'dsh'],
  defaultBranch: 'main',
  archived: false,
  updatedAt: '2026-08-13T00:00:00Z',
}

export function testConfig(overrides: Partial<AppConfig> = {}): AppConfig {
  return {
    transport: 'stdio',
    host: DEFAULT_HTTP_HOST,
    port: DEFAULT_HTTP_PORT,
    allowInstall: false,
    allowRuntime: false,
    dshRoot: null,
    profile: DEFAULT_PROFILE,
    cacheDir: '/tmp/dsh-plugin-mcp-test',
    cacheTtlMs: DEFAULT_CACHE_TTL_MS,
    githubToken: null,
    catalog: true,
    bridgeTools: true,
    ...overrides,
  }
}

export function jsonResponse(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  })
}

export function githubFetch(routes: Record<string, unknown | Response>): GithubFetcher {
  return async (url) => {
    const parsed = new URL(url)
    const key = `${parsed.pathname}${parsed.search}`
    const exact = routes[key]
    if (exact !== undefined) return asMockResponse(exact)
    const prefix = Object.keys(routes)
      .filter(pattern => key.startsWith(pattern))
      .sort((a, b) => b.length - a.length)[0]
    if (prefix !== undefined && routes[prefix] !== undefined) return asMockResponse(routes[prefix])
    return jsonResponse({ message: `no mock for ${key}` }, 404)
  }
}

function asMockResponse(value: unknown | Response): Response {
  if (value instanceof Response) return value
  return jsonResponse(value)
}

export function searchPayload(repos: GithubRepo[], incomplete = false) {
  return {
    total_count: repos.length,
    incomplete_results: incomplete,
    items: repos.map(repo => ({
      full_name: repo.fullName,
      name: repo.name,
      owner: { login: repo.owner },
      description: repo.description,
      html_url: repo.htmlUrl,
      stargazers_count: repo.stars,
      forks_count: repo.forks,
      language: repo.language,
      topics: repo.topics,
      default_branch: repo.defaultBranch,
      archived: repo.archived,
      updated_at: repo.updatedAt,
    })),
  }
}

export function makeCatalog(fetch: GithubFetcher, ttlMs = 60_000, now = () => Date.parse('2026-08-13T00:00:00Z')): PluginCatalog {
  return new PluginCatalog(new GithubClient({ token: null, fetch }), new MemoryCatalogStore(), ttlMs, now)
}

export class FakeDsh implements DshRunner {
  path: string | null = '/usr/local/bin/dsh'
  pluginCalls: Array<{ profile: string; args: readonly string[] }> = []
  pluginResult: DshCommandResult = { exitCode: 0, stdout: 'ok', stderr: '' }
  spawned: Array<{ profile: string; env: Record<string, string> }> = []
  killed = false

  whichDsh(): string | null {
    return this.path
  }

  async runPlugin(profile: string, args: readonly string[]): Promise<DshCommandResult> {
    this.pluginCalls.push({ profile, args })
    return this.pluginResult
  }

  spawnProfile(options: { profile: string; env: Record<string, string> }): ChildHandle {
    this.spawned.push({ profile: options.profile, env: options.env })
    this.killed = false
    let exited = false
    let exitSignal: NodeJS.Signals | null = null
    const exitHandlers: Array<(code: number | null, signal: NodeJS.Signals | null) => void> = []
    const fireExit = (signal: NodeJS.Signals) => {
      if (exited) return
      exited = true
      exitSignal = signal
      this.killed = true
      for (const handler of exitHandlers) handler(0, signal)
    }
    return {
      pid: 4242,
      kill: (signal?: NodeJS.Signals) => { fireExit(signal ?? 'SIGTERM') },
      onExit: (handler) => {
        if (exited) handler(0, exitSignal)
        else exitHandlers.push(handler)
      },
      stdout: { on() { return this }, [Symbol.asyncIterator]: async function* () {} } as unknown as NodeJS.ReadableStream,
      stderr: { on() { return this } } as unknown as NodeJS.ReadableStream,
    }
  }
}

export function fakeRuntimeClient(tools: RuntimeMcpClient extends never ? never : Array<{ name: string; description?: string; inputSchema: Record<string, unknown> }>, calls: string[] = []): RuntimeMcpClient {
  return {
    async listTools() {
      return tools
    },
    async callTool(name, args) {
      calls.push(name)
      return { content: [{ type: 'text', text: JSON.stringify({ name, args }) }] }
    },
    async close() {
      return
    },
  }
}

export function makeRuntime(config: AppConfig, dsh: FakeDsh, client?: RuntimeMcpClient): RuntimeHost {
  const wait: HttpWaiter = async () => undefined
  const connect: McpConnector = async () => client ?? fakeRuntimeClient([])
  return new RuntimeHost(config, dsh, wait, connect, '/pkg/deepseek-harness-plugin-mcp')
}

export class FakeTools implements ToolRuntimeView {
  constructor(
    public registered: Array<{ name: string; description: string; parameters: Record<string, unknown> }> = [],
    public results: Record<string, ToolRuntimeResult> = {},
  ) {}

  schemas() {
    return this.registered
  }

  async execute(input: { name: string }): Promise<ToolRuntimeResult> {
    return this.results[input.name] ?? {
      isError: false,
      content: [{ type: 'text', text: `ran ${input.name}` }],
      value: { ok: true },
    }
  }
}
