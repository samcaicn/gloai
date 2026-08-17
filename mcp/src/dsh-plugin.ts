import { serveHttp } from './mcp/http.js'
import { createPluginMcpServer } from './mcp/server.js'
import { FileCatalogStore, PluginCatalog, catalogCachePath } from './github/catalog.js'
import { GithubClient } from './github/client.js'
import { ProcessDshRunner } from './profile/dsh-cli.js'
import { RuntimeHost } from './runtime/host.js'
import { defaultCacheDir, DEFAULT_HTTP_HOST, DEFAULT_HTTP_PORT, DEFAULT_PROFILE, SERVER_VERSION } from './config.js'
import { ToolBridge } from './runtime/bridge.js'
import type { AppConfig } from './config.js'
import type { DshPluginContext } from './types.js'

export const name = 'deepseek-harness-plugin-mcp'
export const inject = ['tools']

export interface PluginConfig {
  host?: string
  port?: number
  catalog?: boolean
  bridgeTools?: boolean
  allowInstall?: boolean
  allowRuntime?: boolean
  profile?: string
}

/**
 * Cordis plugin: serve Streamable HTTP MCP from a live Harness `ctx.tools`.
 * @param ctx - Harness context providing the tool registry.
 * @param config - bind address, catalog, and bridge flags from cordis.patch.yml.
 */
export async function apply(ctx: DshPluginContext, config: PluginConfig = {}): Promise<void> {
  const resolved = resolvePluginConfig(config)
  const github = new GithubClient({ token: process.env.GITHUB_TOKEN ?? process.env.GH_TOKEN ?? null })
  const catalog = new PluginCatalog(
    github,
    new FileCatalogStore(catalogCachePath(resolved.cacheDir)),
    resolved.cacheTtlMs,
  )
  const dsh = new ProcessDshRunner()
  const runtime = new RuntimeHost(resolved, dsh)
  const bridge = resolved.bridgeTools ? new ToolBridge(ctx.tools) : null
  if (bridge) bridge.sync()

  const deps = {
    config: resolved,
    catalog,
    github,
    dsh,
    runtime: overlayRuntime(runtime, bridge),
  }

  const listening = await serveHttp(() => createPluginMcpServer(deps).server, resolved)
  try {
    ctx.logger.info(`${name} listening at ${listening.url}`)
    const stopChange = ctx.on('tools/change', () => {
      bridge?.sync()
      listening.notifyToolsChanged()
    })
    ctx.effect(() => () => {
      stopChange()
      void listening.close()
    }, `${name}.http`)
  } catch (error) {
    await listening.close()
    throw error
  }
}

function overlayRuntime(runtime: RuntimeHost, bridge: ToolBridge | null): RuntimeHost {
  if (!bridge) return runtime
  const originalList = runtime.listBridged.bind(runtime)
  const originalCall = runtime.call.bind(runtime)
  const overlaid = Object.create(runtime) as RuntimeHost
  overlaid.listBridged = () => {
    const live = bridge.mcpSpecs()
    return live.length > 0 ? live : originalList()
  }
  overlaid.call = async (publicName, args, signal) => {
    const live = bridge.list()
    if (live.some(tool => tool.publicName === publicName)) {
      return await bridge.call(publicName, args, signal)
    }
    return await originalCall(publicName, args, signal)
  }
  return overlaid
}

export function resolvePluginConfig(config: PluginConfig): AppConfig {
  return {
    transport: 'http',
    host: config.host ?? process.env.DSH_PLUGIN_MCP_HOST ?? DEFAULT_HTTP_HOST,
    port: config.port ?? Number(process.env.DSH_PLUGIN_MCP_PORT ?? DEFAULT_HTTP_PORT),
    allowInstall: config.allowInstall === true,
    allowRuntime: config.allowRuntime === true,
    dshRoot: process.env.DSH_ROOT ?? null,
    profile: config.profile ?? process.env.DSH_PLUGIN_MCP_PROFILE ?? DEFAULT_PROFILE,
    cacheDir: process.env.DSH_PLUGIN_MCP_CACHE_DIR ?? defaultCacheDir(),
    cacheTtlMs: Number(process.env.DSH_PLUGIN_MCP_CACHE_TTL_MS ?? 30 * 60 * 1000),
    githubToken: process.env.GITHUB_TOKEN ?? process.env.GH_TOKEN ?? null,
    catalog: config.catalog !== false && process.env.DSH_PLUGIN_MCP_CATALOG !== '0',
    bridgeTools: config.bridgeTools !== false,
  }
}

export { SERVER_VERSION }
