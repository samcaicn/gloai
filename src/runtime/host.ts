import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'
import type { Transport } from '@modelcontextprotocol/sdk/shared/transport.js'
import { fileURLToPath } from 'node:url'
import { homedir } from 'node:os'
import { join } from 'node:path'
import { readFile } from 'node:fs/promises'
import type { AppConfig } from '../config.js'
import { formatDshFailure, pluginAddArgs, pluginRemoveArgs } from '../profile/dsh-cli.js'
import { isControlToolName } from '../plugin/names.js'
import { bridgedToolName } from '../plugin/names.js'
import type { ChildHandle, DshRunner, McpToolSpec, ToolCallOutput } from '../types.js'

export function thisPackageRoot(): string {
  return fileURLToPath(new URL('../..', import.meta.url))
}

export function resolveProfileDir(profile: string, dshHome: string = process.env.DSH_HOME ?? join(homedir(), '.dsh')): string {
  return join(dshHome, 'profiles', profile)
}

export interface InstalledProfile {
  profile: string
  directory: string
  bundles: string[]
  dependencies: Record<string, string>
}

export async function readInstalledProfile(profile: string, dshHome?: string): Promise<InstalledProfile | null> {
  const directory = resolveProfileDir(profile, dshHome)
  try {
    const raw = await readFile(join(directory, 'package.json'), 'utf8')
    const manifest = JSON.parse(raw) as {
      dependencies?: Record<string, string>
      dsh?: { profile?: { bundles?: string[] } }
    }
    return {
      profile,
      directory,
      bundles: manifest.dsh?.profile?.bundles ?? [],
      dependencies: manifest.dependencies ?? {},
    }
  } catch (error) {
    const code = (error as NodeJS.ErrnoException).code
    if (code === 'ENOENT') return null
    throw error
  }
}

export interface RuntimeStatus {
  running: boolean
  pid: number | null
  url: string | null
  profile: string
  bridgedToolCount: number
}

export type HttpWaiter = (url: string, signal: AbortSignal) => Promise<void>
export type McpConnector = (url: string) => Promise<RuntimeMcpClient>

export interface RuntimeMcpClient {
  listTools(): Promise<Array<{ name: string; description?: string | undefined; inputSchema: Record<string, unknown> }>>
  callTool(name: string, args: Record<string, unknown>, signal: AbortSignal): Promise<ToolCallOutput>
  close(): Promise<void>
}

export class RuntimeHost {
  private child: ChildHandle | null = null
  private client: RuntimeMcpClient | null = null
  private exitCode: number | null = null
  private bridged: McpToolSpec[] = []
  private rawToPublic = new Map<string, string>()

  constructor(
    private readonly config: AppConfig,
    private readonly dsh: DshRunner,
    private readonly waitForHttp: HttpWaiter = defaultWaitForHttp,
    private readonly connect: McpConnector = defaultConnect,
    private readonly packageRoot: string = thisPackageRoot(),
  ) {}

  status(): RuntimeStatus {
    return {
      running: this.child !== null && this.client !== null,
      pid: this.child?.pid ?? null,
      url: this.child ? this.mcpUrl() : null,
      profile: this.config.profile,
      bridgedToolCount: this.bridged.length,
    }
  }

  listBridged(): McpToolSpec[] {
    return this.bridged
  }

  mcpUrl(): string {
    return `http://${this.config.host}:${this.config.port}/mcp`
  }

  healthUrl(): string {
    return `http://${this.config.host}:${this.config.port}/health`
  }

  async start(pluginSpecs: string[] = []): Promise<RuntimeStatus> {
    if (!this.config.allowRuntime) {
      throw new Error('runtime is disabled; pass --allow-runtime or set DSH_PLUGIN_MCP_ALLOW_RUNTIME=1')
    }
    if (!this.dsh.whichDsh()) {
      throw new Error('dsh not found on PATH — install DeepSeek Harness, or set DSH_ROOT and ensure `dsh` is on PATH')
    }
    await this.stop()
    await this.ensureSelfInstalled()
    for (const spec of pluginSpecs) {
      await this.addSpec(spec)
    }
    await this.spawnAndConnect()
    return this.status()
  }

  async stop(): Promise<void> {
    if (this.client) {
      await this.client.close().catch(() => undefined)
      this.client = null
    }
    const child = this.child
    this.child = null
    this.bridged = []
    this.rawToPublic.clear()
    if (child) await terminateChild(child)
  }

  async load(spec: string): Promise<RuntimeStatus> {
    await this.addSpec(spec)
    return await this.restartKeepingPlugins()
  }

  async unload(packageName: string): Promise<RuntimeStatus> {
    const result = await this.dsh.runPlugin(this.config.profile, pluginRemoveArgs(packageName))
    if (result.exitCode !== 0) throw new Error(formatDshFailure(result))
    return await this.restartKeepingPlugins()
  }

  async call(publicName: string, args: unknown, signal: AbortSignal): Promise<ToolCallOutput> {
    if (!this.client) {
      return { content: [{ type: 'text', text: 'runtime is not started; call dsh_runtime_start first' }], isError: true }
    }
    const raw = [...this.rawToPublic.entries()].find(([, pub]) => pub === publicName)?.[0]
    if (!raw) {
      return { content: [{ type: 'text', text: `unknown bridged tool ${publicName}` }], isError: true }
    }
    const argObj = (typeof args === 'object' && args !== null ? args : {}) as Record<string, unknown>
    return await this.client.callTool(raw, argObj, signal)
  }

  private async restartKeepingPlugins(): Promise<RuntimeStatus> {
    await this.stop()
    await this.spawnAndConnect()
    return this.status()
  }

  private async ensureSelfInstalled(): Promise<void> {
    const result = await this.dsh.runPlugin(this.config.profile, pluginAddArgs(this.packageRoot))
    if (result.exitCode !== 0) throw new Error(formatDshFailure(result))
  }

  private async addSpec(spec: string): Promise<void> {
    const result = await this.dsh.runPlugin(this.config.profile, pluginAddArgs(spec))
    if (result.exitCode !== 0) throw new Error(formatDshFailure(result))
  }

  private async spawnAndConnect(): Promise<void> {
    this.exitCode = null
    this.child = this.dsh.spawnProfile({
      profile: this.config.profile,
      env: {
        DSH_PLUGIN_MCP_PORT: String(this.config.port),
        DSH_PLUGIN_MCP_HOST: this.config.host,
        DSH_PLUGIN_MCP_CATALOG: '0',
        DSH_PLUGIN_MCP_ALLOW_INSTALL: '0',
        DSH_PLUGIN_MCP_ALLOW_RUNTIME: '0',
      },
    })
    this.child.onExit((code) => {
      this.exitCode = code
    })
    const timeout = AbortSignal.timeout(60_000)
    try {
      await this.waitForHttp(this.healthUrl(), timeout)
      this.client = await this.connect(this.mcpUrl())
      await this.refreshBridged()
    } catch (error) {
      const died = this.exitCode !== null ? ` child exited ${this.exitCode}.` : ''
      await this.stop()
      throw new Error(`DSH runtime did not become ready at ${this.healthUrl()}.${died} ${String(error)}`)
    }
  }

  private async refreshBridged(): Promise<void> {
    if (!this.client) return
    const listed = await this.client.listTools()
    this.bridged = []
    this.rawToPublic.clear()
    for (const tool of listed) {
      if (isControlToolName(tool.name)) continue
      const publicName = tool.name.startsWith('dsh__') ? tool.name : bridgedToolName(tool.name)
      this.rawToPublic.set(tool.name, publicName)
      this.bridged.push({
        name: publicName,
        description: tool.description ?? '',
        inputSchema: tool.inputSchema,
      })
    }
  }
}

const CHILD_SIGKILL_AFTER_MS = 5_000

async function terminateChild(child: ChildHandle): Promise<void> {
  await new Promise<void>(resolve => {
    let settled = false
    const finish = () => {
      if (settled) return
      settled = true
      clearTimeout(escalation)
      resolve()
    }
    const escalation = setTimeout(() => {
      child.kill('SIGKILL')
    }, CHILD_SIGKILL_AFTER_MS)
    child.onExit(() => finish())
    child.kill('SIGTERM')
  })
}

export async function defaultWaitForHttp(url: string, signal: AbortSignal): Promise<void> {
  let last = 'no response yet'
  while (!signal.aborted) {
    try {
      const response = await fetch(url, { signal })
      if (response.ok) return
      last = `${response.status} ${response.statusText}`
    } catch (error) {
      last = String(error)
    }
    await sleep(200, signal).catch(() => undefined)
  }
  throw new Error(`timed out waiting for ${url}: ${last}`)
}

function sleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) {
      reject(signal.reason instanceof Error ? signal.reason : new Error('aborted'))
      return
    }
    const timer = setTimeout(resolve, ms)
    signal.addEventListener('abort', () => {
      clearTimeout(timer)
      reject(signal.reason instanceof Error ? signal.reason : new Error('aborted'))
    }, { once: true })
  })
}

export async function defaultConnect(url: string): Promise<RuntimeMcpClient> {
  const client = new Client({ name: 'dsh-plugin-mcp-runtime', version: '0.1.0' })
  const transport = new StreamableHTTPClientTransport(new URL(url))
  await client.connect(transport as Transport)
  return {
    async listTools() {
      const result = await client.listTools()
      return result.tools.map(tool => ({
        name: tool.name,
        description: tool.description,
        inputSchema: tool.inputSchema as Record<string, unknown>,
      }))
    },
    async callTool(name, args, signal) {
      const result = await client.callTool({ name, arguments: args }, undefined, { signal })
      const content = Array.isArray(result.content)
        ? result.content
          .filter((block): block is { type: 'text'; text: string } => block.type === 'text' && typeof block.text === 'string')
        : [{ type: 'text' as const, text: JSON.stringify(result) }]
      const output: ToolCallOutput = { content }
      if (result.isError === true) output.isError = true
      if (result.structuredContent !== undefined) output.structuredContent = result.structuredContent
      return output
    },
    async close() {
      await client.close()
    },
  }
}
