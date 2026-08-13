import { homedir } from 'node:os'
import { join } from 'node:path'
import { parseArgs } from 'node:util'

export const SERVER_NAME = 'deepseek-harness-plugin-mcp'
export const SERVER_VERSION = '0.1.0'
export const DEFAULT_PROFILE = 'mcp-bridge'
export const DEFAULT_HTTP_PORT = 8765
export const DEFAULT_HTTP_HOST = '127.0.0.1'
export const DEFAULT_CACHE_TTL_MS = 30 * 60 * 1000
export const GITHUB_TOPIC = 'dsh-plugin'
export const CONTROL_TOOL_PREFIXES = ['dsh_plugin_', 'dsh_runtime_'] as const
export const BRIDGED_TOOL_PREFIX = 'dsh__'

export interface AppConfig {
  transport: 'stdio' | 'http'
  host: string
  port: number
  allowInstall: boolean
  allowRuntime: boolean
  dshRoot: string | null
  profile: string
  cacheDir: string
  cacheTtlMs: number
  githubToken: string | null
  catalog: boolean
  bridgeTools: boolean
}

function truthyEnv(raw: string | undefined): boolean {
  if (raw === undefined || raw === '') return false
  return raw !== '0' && raw !== 'false' && raw !== 'no'
}

function falsyEnv(raw: string | undefined): boolean {
  return raw === '0' || raw === 'false' || raw === 'no'
}

function parsePort(raw: string | undefined, label: string): number | undefined {
  if (raw === undefined || raw === '') return undefined
  const value = Number(raw)
  if (!Number.isInteger(value) || value < 1 || value > 65535) {
    throw new Error(`${label} must be an integer 1–65535, got ${JSON.stringify(raw)}`)
  }
  return value
}

export function defaultCacheDir(home: string = homedir()): string {
  return join(home, '.dsh-plugin-mcp')
}

/**
 * Resolve process configuration from argv and the environment.
 * CLI flags win over env vars; omitted values use documented defaults.
 * @param argv - `process.argv.slice(2)`
 * @param env - environment map
 */
export function resolveConfig(
  argv: string[] = process.argv.slice(2),
  env: NodeJS.ProcessEnv = process.env,
): AppConfig {
  const { values } = parseArgs({
    args: argv,
    options: {
      http: { type: 'boolean', default: false },
      host: { type: 'string' },
      port: { type: 'string' },
      'allow-install': { type: 'boolean', default: false },
      'allow-runtime': { type: 'boolean', default: false },
      'dsh-root': { type: 'string' },
      profile: { type: 'string' },
      'cache-dir': { type: 'string' },
      'cache-ttl-ms': { type: 'string' },
      'no-catalog': { type: 'boolean', default: false },
      help: { type: 'boolean', short: 'h', default: false },
    },
    strict: true,
    allowPositionals: false,
  })

  if (values.help === true) {
    throw new HelpRequested()
  }

  const port = parsePort(values.port, '--port')
    ?? parsePort(env.DSH_PLUGIN_MCP_PORT, 'DSH_PLUGIN_MCP_PORT')
    ?? DEFAULT_HTTP_PORT

  const ttlRaw = values['cache-ttl-ms'] ?? env.DSH_PLUGIN_MCP_CACHE_TTL_MS
  const cacheTtlMs = ttlRaw === undefined || ttlRaw === '' ? DEFAULT_CACHE_TTL_MS : Number(ttlRaw)
  if (!Number.isFinite(cacheTtlMs) || cacheTtlMs < 0) {
    throw new Error('--cache-ttl-ms / DSH_PLUGIN_MCP_CACHE_TTL_MS must be a non-negative number of milliseconds')
  }

  const catalog = values['no-catalog'] === true || falsyEnv(env.DSH_PLUGIN_MCP_CATALOG) ? false : true

  return {
    transport: values.http === true ? 'http' : 'stdio',
    host: values.host ?? env.DSH_PLUGIN_MCP_HOST ?? DEFAULT_HTTP_HOST,
    port,
    allowInstall: values['allow-install'] === true || truthyEnv(env.DSH_PLUGIN_MCP_ALLOW_INSTALL),
    allowRuntime: values['allow-runtime'] === true || truthyEnv(env.DSH_PLUGIN_MCP_ALLOW_RUNTIME),
    dshRoot: values['dsh-root'] ?? env.DSH_ROOT ?? null,
    profile: values.profile ?? env.DSH_PLUGIN_MCP_PROFILE ?? DEFAULT_PROFILE,
    cacheDir: values['cache-dir'] ?? env.DSH_PLUGIN_MCP_CACHE_DIR ?? defaultCacheDir(),
    cacheTtlMs,
    githubToken: env.GITHUB_TOKEN ?? env.GH_TOKEN ?? null,
    catalog,
    bridgeTools: !falsyEnv(env.DSH_PLUGIN_MCP_BRIDGE_TOOLS),
  }
}

export class HelpRequested extends Error {
  constructor() {
    super('help')
    this.name = 'HelpRequested'
  }
}

export const HELP_TEXT = `${SERVER_NAME} ${SERVER_VERSION}

MCP server for DeepSeek Harness plugins (https://github.com/topics/dsh-plugin).

Usage:
  dsh-plugin-mcp [options]

Options:
  --http                  Serve Streamable HTTP instead of stdio
  --host <addr>           HTTP bind address (default 127.0.0.1)
  --port <n>              HTTP port (default 8765)
  --allow-install         Enable dsh_plugin_install / uninstall
  --allow-runtime         Enable dsh_runtime_* and bridged dsh__* tools
  --dsh-root <path>       DeepSeek Harness checkout (DSH_ROOT)
  --profile <name>        DSH profile (default mcp-bridge)
  --cache-dir <path>      Catalog cache directory
  --cache-ttl-ms <n>      Catalog TTL in milliseconds (default 1800000)
  --no-catalog            Disable GitHub catalog tools (runtime-only child)
  -h, --help              Show this help

Environment:
  GITHUB_TOKEN / GH_TOKEN
  DSH_ROOT
  DSH_PLUGIN_MCP_ALLOW_INSTALL
  DSH_PLUGIN_MCP_ALLOW_RUNTIME
  DSH_PLUGIN_MCP_PROFILE
  DSH_PLUGIN_MCP_PORT
  DSH_PLUGIN_MCP_HOST
  DSH_PLUGIN_MCP_CACHE_DIR
  DSH_PLUGIN_MCP_CATALOG=0
`
