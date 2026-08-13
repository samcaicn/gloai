/** Kinds a dsh-plugin repository may belong to. A plugin may have several. */
export type PluginKind =
  | 'official'
  | 'bundle'
  | 'tool'
  | 'skill'
  | 'ui'
  | 'tui'
  | 'mcp'
  | 'directory'
  | 'desktop'
  | 'workflow'
  | 'unknown'

/** Public GitHub repository fields this catalog stores. */
export interface GithubRepo {
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
}

/** Cached topic listing. */
export interface CatalogSnapshot {
  fetchedAt: string
  source: 'github-topic'
  query: string
  incomplete: boolean
  repos: GithubRepo[]
}

/** `package.json` slice used to decide whether a repo is a DSH bundle. */
export interface PluginPackageJson {
  name?: string
  description?: string
  private?: boolean
  dsh?: {
    bundle?: { patch?: string }
    client?: unknown
    profile?: unknown
  }
}

/** Full inspect result for one repository. */
export interface PluginInspection {
  repo: GithubRepo
  kinds: PluginKind[]
  packageName: string | null
  isDshBundle: boolean
  hasClient: boolean
  patchPath: string | null
  patchText: string | null
  packageJson: PluginPackageJson | null
  readme: string | null
  skillFiles: string[]
  rootEntries: string[]
  installSpec: string
  inspectWarnings: string[]
}

/** One tool the MCP control plane or a DSH composition exposes. */
export interface McpToolSpec {
  name: string
  description: string
  inputSchema: Record<string, unknown>
}

/** Result of a control-plane or bridged tool call. */
export interface ToolCallOutput {
  content: Array<{ type: 'text'; text: string }>
  structuredContent?: unknown
  isError?: boolean
}

/** Injected GitHub HTTP. */
export type GithubFetcher = (url: string, init?: RequestInit) => Promise<Response>

/** Result of one `dsh plugin` invocation. */
export interface DshCommandResult {
  exitCode: number
  stdout: string
  stderr: string
}

/** Spawn `dsh plugin` / `dsh --profile`. */
export interface DshRunner {
  whichDsh(): string | null
  runPlugin(profile: string, args: readonly string[]): Promise<DshCommandResult>
  spawnProfile(options: {
    profile: string
    env: Record<string, string>
    cwd?: string
  }): ChildHandle
}

/** Long-lived child process the runtime plane owns. */
export interface ChildHandle {
  pid: number | undefined
  kill(signal?: NodeJS.Signals): void
  onExit(handler: (code: number | null, signal: NodeJS.Signals | null) => void): void
  stdout: AsyncIterable<string> | NodeJS.ReadableStream
  stderr: NodeJS.ReadableStream
}

/** Live DSH tool registry as this package consumes it. */
export interface ToolRuntimeView {
  schemas(): Array<{ name: string; description: string; parameters: Record<string, unknown> }>
  execute(input: {
    callId: string
    name: string
    arguments: unknown
    signal: AbortSignal
  }): Promise<ToolRuntimeResult>
}

/** Canonical DSH tool pipeline outcome (the fields this bridge reads). */
export interface ToolRuntimeResult {
  isError: boolean
  content: Array<Record<string, unknown>>
  value?: unknown
  error?: { message: string }
}

/** Minimal Cordis context this bundle needs. */
export interface DshPluginContext {
  tools: ToolRuntimeView
  on(event: 'tools/change', handler: () => void): () => void
  effect(callback: () => (() => void) | Promise<void>, label?: string): void
  logger: {
    info(message: string): void
    warn(message: string): void
    error(message: string): void
  }
}
