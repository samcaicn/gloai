import { z } from 'zod'
import type { AppConfig } from '../config.js'
import type { PluginCatalog } from '../github/catalog.js'
import { filterRepos } from '../github/catalog.js'
import type { GithubClient } from '../github/client.js'
import { inspectPlugin, resolveRepo } from '../plugin/inspect.js'
import { parseRepoSpec } from '../plugin/classify.js'
import { jsonToolResult } from '../plugin/names.js'
import { formatDshFailure, pluginAddArgs, pluginRemoveArgs } from '../profile/dsh-cli.js'
import { readInstalledProfile } from '../runtime/host.js'
import type { RuntimeHost } from '../runtime/host.js'
import type { DshRunner, McpToolSpec, ToolCallOutput } from '../types.js'

export interface ControlTool {
  spec: McpToolSpec
  handle(args: Record<string, unknown>, signal: AbortSignal): Promise<ToolCallOutput>
}

const RepoSpec = z.object({ spec: z.string().min(1) })
const Search = z.object({
  query: z.string().optional(),
  kind: z.string().optional(),
  language: z.string().optional(),
  minStars: z.number().int().nonnegative().optional(),
  includeArchived: z.boolean().optional(),
  offset: z.number().int().nonnegative().optional(),
  limit: z.number().int().positive().max(100).optional(),
})
const RuntimeStart = z.object({
  plugins: z.array(z.string()).optional(),
})
const RuntimeLoad = z.object({ spec: z.string().min(1) })
const RuntimeUnload = z.object({ packageName: z.string().min(1) })
const Install = z.object({ spec: z.string().min(1) })
const Uninstall = z.object({ packageName: z.string().min(1) })

function objectSchema(properties: Record<string, unknown>, required: string[] = []): Record<string, unknown> {
  return {
    type: 'object',
    properties,
    required,
    additionalProperties: false,
  }
}

export interface SessionDeps {
  config: AppConfig
  catalog: PluginCatalog
  github: GithubClient
  dsh: DshRunner
  runtime: RuntimeHost
}

export function createControlTools(deps: SessionDeps): ControlTool[] {
  const { config, catalog, github, dsh, runtime } = deps

  const tools: ControlTool[] = [
    {
      spec: {
        name: 'dsh_plugin_status',
        description: 'Report GitHub auth, catalog cache, dsh binary, profile, and runtime bridge status.',
        inputSchema: objectSchema({}),
      },
      async handle() {
        const snapshot = await catalog.getSnapshot().catch((error: unknown) => ({ error: String(error) }))
        const installed = await readInstalledProfile(config.profile)
        return jsonToolResult({
          server: 'deepseek-harness-plugin-mcp',
          githubAuthenticated: github.authenticated,
          catalog: 'repos' in snapshot
            ? { fetchedAt: snapshot.fetchedAt, count: snapshot.repos.length, incomplete: snapshot.incomplete, stale: catalog.isStale(snapshot) }
            : snapshot,
          dsh: dsh.whichDsh(),
          dshRoot: config.dshRoot,
          profile: config.profile,
          installed,
          allowInstall: config.allowInstall,
          allowRuntime: config.allowRuntime,
          runtime: runtime.status(),
        })
      },
    },
    {
      spec: {
        name: 'dsh_plugin_refresh_catalog',
        description: 'Force-refresh the GitHub topic:dsh-plugin catalog and replace the on-disk cache.',
        inputSchema: objectSchema({}),
      },
      async handle() {
        const snapshot = await catalog.getSnapshot(true)
        return jsonToolResult({
          fetchedAt: snapshot.fetchedAt,
          count: snapshot.repos.length,
          incomplete: snapshot.incomplete,
        })
      },
    },
    {
      spec: {
        name: 'dsh_plugin_list',
        description: 'List cached dsh-plugin repositories. Call dsh_plugin_refresh_catalog if the cache is empty or stale.',
        inputSchema: objectSchema({
          query: { type: 'string', description: 'Case-insensitive tokens matched against name, description, topics' },
          kind: { type: 'string', description: 'Hint only on list; use inspect for authoritative kinds' },
          language: { type: 'string' },
          minStars: { type: 'integer', minimum: 0 },
          includeArchived: { type: 'boolean' },
          offset: { type: 'integer', minimum: 0 },
          limit: { type: 'integer', minimum: 1, maximum: 100 },
        }),
      },
      async handle(args) {
        const parsed = Search.parse(args)
        const snapshot = await catalog.getSnapshot()
        const repos = filterRepos(snapshot.repos, {
          query: parsed.query,
          language: parsed.language,
          minStars: parsed.minStars,
          includeArchived: parsed.includeArchived,
          offset: parsed.offset,
          limit: parsed.limit ?? 30,
        })
        return jsonToolResult({
          fetchedAt: snapshot.fetchedAt,
          total: snapshot.repos.length,
          returned: repos.length,
          repos,
        })
      },
    },
    {
      spec: {
        name: 'dsh_plugin_search',
        description: 'Search the cached dsh-plugin catalog by free-text query. Same filters as dsh_plugin_list.',
        inputSchema: objectSchema({
          query: { type: 'string', description: 'Search tokens' },
          language: { type: 'string' },
          minStars: { type: 'integer', minimum: 0 },
          limit: { type: 'integer', minimum: 1, maximum: 100 },
        }, ['query']),
      },
      async handle(args) {
        const parsed = Search.parse(args)
        if (!parsed.query) return jsonToolResult({ error: 'query is required' }, true)
        const snapshot = await catalog.getSnapshot()
        const repos = filterRepos(snapshot.repos, {
          query: parsed.query,
          language: parsed.language,
          minStars: parsed.minStars,
          limit: parsed.limit ?? 20,
        })
        return jsonToolResult({ query: parsed.query, returned: repos.length, repos })
      },
    },
    {
      spec: {
        name: 'dsh_plugin_get',
        description: 'Get one catalog card by owner/repo, github:owner/repo, or GitHub URL.',
        inputSchema: objectSchema({ spec: { type: 'string' } }, ['spec']),
      },
      async handle(args) {
        const { spec } = RepoSpec.parse(args)
        const snapshot = await catalog.getSnapshot()
        const repo = await resolveRepo(github, snapshot.repos, spec)
        return jsonToolResult(repo)
      },
    },
    {
      spec: {
        name: 'dsh_plugin_inspect',
        description: 'Inspect package.json, cordis.patch.yml, README, skills, and DSH bundle metadata for one plugin.',
        inputSchema: objectSchema({ spec: { type: 'string' } }, ['spec']),
      },
      async handle(args) {
        const { spec } = RepoSpec.parse(args)
        const snapshot = await catalog.getSnapshot()
        const repo = await resolveRepo(github, snapshot.repos, spec)
        const inspection = await inspectPlugin(github, repo)
        return jsonToolResult({
          ...inspection,
          readme: truncate(inspection.readme, 24_000),
          patchText: truncate(inspection.patchText, 16_000),
        })
      },
    },
    {
      spec: {
        name: 'dsh_plugin_readme',
        description: 'Return the full README of a dsh-plugin repository.',
        inputSchema: objectSchema({ spec: { type: 'string' } }, ['spec']),
      },
      async handle(args) {
        const { spec } = RepoSpec.parse(args)
        const { owner, repo } = parseRepoSpec(spec)
        const readme = await github.getReadme(owner, repo)
        if (readme === null) return jsonToolResult({ error: 'README not found' }, true)
        return jsonToolResult({ spec: `${owner}/${repo}`, readme })
      },
    },
    {
      spec: {
        name: 'dsh_plugin_list_installed',
        description: 'List bundles and dependencies installed in the configured DSH profile.',
        inputSchema: objectSchema({}),
      },
      async handle() {
        const installed = await readInstalledProfile(config.profile)
        if (!installed) {
          return jsonToolResult({
            profile: config.profile,
            installed: false,
            hint: `profile is missing; dsh plugin --profile ${config.profile} add <spec> will create it`,
          })
        }
        return jsonToolResult({ installed: true, ...installed })
      },
    },
    {
      spec: {
        name: 'dsh_plugin_install',
        description: 'Install a plugin into the DSH profile via `dsh plugin add`. Requires --allow-install.',
        inputSchema: objectSchema({ spec: { type: 'string', description: 'github:owner/repo, npm name, or path' } }, ['spec']),
      },
      async handle(args) {
        if (!config.allowInstall) {
          return jsonToolResult({
            error: 'install is disabled; pass --allow-install or set DSH_PLUGIN_MCP_ALLOW_INSTALL=1',
          }, true)
        }
        const { spec } = Install.parse(args)
        const result = await dsh.runPlugin(config.profile, pluginAddArgs(spec))
        if (result.exitCode !== 0) return jsonToolResult({ error: formatDshFailure(result) }, true)
        const installed = await readInstalledProfile(config.profile)
        return jsonToolResult({ ok: true, spec, profile: config.profile, installed })
      },
    },
    {
      spec: {
        name: 'dsh_plugin_uninstall',
        description: 'Remove a plugin from the DSH profile via `dsh plugin remove`. Requires --allow-install.',
        inputSchema: objectSchema({ packageName: { type: 'string' } }, ['packageName']),
      },
      async handle(args) {
        if (!config.allowInstall) {
          return jsonToolResult({
            error: 'uninstall is disabled; pass --allow-install or set DSH_PLUGIN_MCP_ALLOW_INSTALL=1',
          }, true)
        }
        const { packageName } = Uninstall.parse(args)
        const result = await dsh.runPlugin(config.profile, pluginRemoveArgs(packageName))
        if (result.exitCode !== 0) return jsonToolResult({ error: formatDshFailure(result) }, true)
        const installed = await readInstalledProfile(config.profile)
        return jsonToolResult({ ok: true, packageName, profile: config.profile, installed })
      },
    },
    {
      spec: {
        name: 'dsh_runtime_start',
        description: 'Install this MCP bundle plus optional plugins into the profile, spawn `dsh --profile`, and bridge ctx.tools as dsh__* MCP tools. Requires --allow-runtime.',
        inputSchema: objectSchema({
          plugins: { type: 'array', items: { type: 'string' }, description: 'github:owner/repo specs to install before boot' },
        }),
      },
      async handle(args) {
        const parsed = RuntimeStart.parse(args)
        try {
          const status = await runtime.start(parsed.plugins ?? [])
          return jsonToolResult({ ok: true, status, tools: runtime.listBridged().map(tool => tool.name) })
        } catch (error) {
          return jsonToolResult({ error: String(error) }, true)
        }
      },
    },
    {
      spec: {
        name: 'dsh_runtime_stop',
        description: 'Stop the spawned DSH runtime and drop bridged dsh__* tools.',
        inputSchema: objectSchema({}),
      },
      async handle() {
        await runtime.stop()
        return jsonToolResult({ ok: true, status: runtime.status() })
      },
    },
    {
      spec: {
        name: 'dsh_runtime_load',
        description: 'Install one more plugin into the live profile and restart the DSH runtime. Requires --allow-runtime.',
        inputSchema: objectSchema({ spec: { type: 'string' } }, ['spec']),
      },
      async handle(args) {
        const { spec } = RuntimeLoad.parse(args)
        try {
          const status = await runtime.load(spec)
          return jsonToolResult({ ok: true, spec, status, tools: runtime.listBridged().map(tool => tool.name) })
        } catch (error) {
          return jsonToolResult({ error: String(error) }, true)
        }
      },
    },
    {
      spec: {
        name: 'dsh_runtime_unload',
        description: 'Remove a package from the live profile and restart the DSH runtime. Requires --allow-runtime.',
        inputSchema: objectSchema({ packageName: { type: 'string' } }, ['packageName']),
      },
      async handle(args) {
        const { packageName } = RuntimeUnload.parse(args)
        try {
          const status = await runtime.unload(packageName)
          return jsonToolResult({ ok: true, packageName, status, tools: runtime.listBridged().map(tool => tool.name) })
        } catch (error) {
          return jsonToolResult({ error: String(error) }, true)
        }
      },
    },
    {
      spec: {
        name: 'dsh_runtime_list_tools',
        description: 'List DSH tools currently bridged onto this MCP server as dsh__* names.',
        inputSchema: objectSchema({}),
      },
      async handle() {
        return jsonToolResult({ status: runtime.status(), tools: runtime.listBridged() })
      },
    },
  ]

  return config.catalog ? tools : tools.filter(tool => tool.spec.name.startsWith('dsh_runtime_') || tool.spec.name === 'dsh_plugin_status')
}

function truncate(text: string | null, max: number): string | null {
  if (text === null) return null
  if (text.length <= max) return text
  return `${text.slice(0, max)}\n…[truncated ${text.length - max} characters]`
}
