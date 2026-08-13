import { FileCatalogStore, PluginCatalog, catalogCachePath } from './github/catalog.js'
import { GithubClient } from './github/client.js'
import { ProcessDshRunner } from './profile/dsh-cli.js'
import { RuntimeHost } from './runtime/host.js'
import { createPluginMcpServer, type PluginMcpHandle } from './mcp/server.js'
import type { AppConfig } from './config.js'
import type { DshRunner } from './types.js'

export interface AppHandles extends PluginMcpHandle {
  catalog: PluginCatalog
  runtime: RuntimeHost
}

export function createApp(config: AppConfig, dsh: DshRunner = new ProcessDshRunner()): AppHandles {
  const github = new GithubClient({ token: config.githubToken })
  const catalog = new PluginCatalog(
    github,
    new FileCatalogStore(catalogCachePath(config.cacheDir)),
    config.cacheTtlMs,
  )
  const runtime = new RuntimeHost(config, dsh)
  const mcp = createPluginMcpServer({ config, catalog, github, dsh, runtime })
  return { ...mcp, catalog, runtime }
}
