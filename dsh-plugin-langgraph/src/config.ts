import { homedir } from 'node:os'
import { join } from 'node:path'

export const SERVER_NAME = 'dsh-plugin-langgraph'
export const SERVER_VERSION = '0.1.0'

export interface AppConfig {
  transport: 'stdio' | 'http'
  host: string
  port: number
  allowSupervisor: boolean
  allowHandoff: boolean
  maxAgents: number
  checkpointDir: string | null
  piMail: PiMailConfig
}

export interface PiMailConfig {
  enabled: boolean
  extensionDir: string
  host: string
  port: number
}

/** pi-mail 默认配置 */
const DEFAULT_PI_MAIL: PiMailConfig = {
  enabled: true,
  extensionDir: join(homedir(), '.dsh-langgraph', 'pi-mail', 'extensions'),
  host: '127.0.0.1',
  port: 1994,
}

/** 默认配置 - 开箱即用 */
const DEFAULTS: AppConfig = {
  transport: 'http',
  host: '127.0.0.1',
  port: 8766,
  allowSupervisor: true,
  allowHandoff: true,
  maxAgents: 10,
  checkpointDir: join(homedir(), '.dsh-langgraph', 'checkpoints'),
  piMail: DEFAULT_PI_MAIL,
}

/** 从环境变量解析端口 */
function parsePort(raw: string | undefined): number | undefined {
  if (!raw) return undefined
  const value = Number(raw)
  if (Number.isInteger(value) && value >= 1 && value <= 65535) return value
  return undefined
}

/** 解析布尔值 */
function parseBool(raw: string | undefined): boolean | undefined {
  if (!raw) return undefined
  return raw !== '0' && raw !== 'false' && raw !== 'no'
}

/** 解析 pi-mail 配置 */
function resolvePiMailConfig(env: NodeJS.ProcessEnv): PiMailConfig {
  const extDir = env.DSH_LANGGRAPH_PIMAIL_DIR ?? DEFAULT_PI_MAIL.extensionDir
  return {
    enabled: parseBool(env.DSH_LANGGRAPH_PIMAIL_ENABLED) ?? DEFAULT_PI_MAIL.enabled,
    extensionDir: extDir,
    host: env.DSH_LANGGRAPH_PIMAIL_HOST ?? DEFAULT_PI_MAIL.host,
    port: parsePort(env.DSH_LANGGRAPH_PIMAIL_PORT) ?? DEFAULT_PI_MAIL.port,
  }
}

/** 解析配置 - 环境变量优先，缺省用默认值 */
export function resolveConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  return {
    transport: 'http',
    host: env.DSH_LANGGRAPH_HOST ?? DEFAULTS.host,
    port: parsePort(env.DSH_LANGGRAPH_PORT) ?? DEFAULTS.port,
    allowSupervisor: parseBool(env.DSH_LANGGRAPH_ALLOW_SUPERVISOR) ?? DEFAULTS.allowSupervisor,
    allowHandoff: parseBool(env.DSH_LANGGRAPH_ALLOW_HANDOFF) ?? DEFAULTS.allowHandoff,
    maxAgents: Number(env.DSH_LANGGRAPH_MAX_AGENTS ?? DEFAULTS.maxAgents),
    checkpointDir: env.DSH_LANGGRAPH_CHECKPOINT_DIR ?? DEFAULTS.checkpointDir,
    piMail: resolvePiMailConfig(env),
  }
}

/** 解析 Cordis 插件配置 */
export function resolvePluginConfig(config: Partial<PluginConfigInput> = {}): AppConfig {
  const env = process.env
  return {
    transport: 'http',
    host: config.host ?? env.DSH_LANGGRAPH_HOST ?? DEFAULTS.host,
    port: config.port ?? parsePort(env.DSH_LANGGRAPH_PORT) ?? DEFAULTS.port,
    allowSupervisor: config.allowSupervisor ?? parseBool(env.DSH_LANGGRAPH_ALLOW_SUPERVISOR) ?? DEFAULTS.allowSupervisor,
    allowHandoff: config.allowHandoff ?? parseBool(env.DSH_LANGGRAPH_ALLOW_HANDOFF) ?? DEFAULTS.allowHandoff,
    maxAgents: config.maxAgents ?? Number(env.DSH_LANGGRAPH_MAX_AGENTS ?? DEFAULTS.maxAgents),
    checkpointDir: config.checkpointDir ?? env.DSH_LANGGRAPH_CHECKPOINT_DIR ?? DEFAULTS.checkpointDir,
    piMail: {
      enabled: config.pimail?.enabled ?? parseBool(env.DSH_LANGGRAPH_PIMAIL_ENABLED) ?? DEFAULT_PI_MAIL.enabled,
      extensionDir: config.pimail?.path ?? env.DSH_LANGGRAPH_PIMAIL_DIR ?? DEFAULT_PI_MAIL.extensionDir,
      host: config.pimail?.host ?? env.DSH_LANGGRAPH_PIMAIL_HOST ?? DEFAULT_PI_MAIL.host,
      port: config.pimail?.port ?? parsePort(env.DSH_LANGGRAPH_PIMAIL_PORT) ?? DEFAULT_PI_MAIL.port,
    },
  }
}

/** 插件输入配置（可选字段） */
export interface PluginConfigInput {
  host?: string
  port?: number
  allowSupervisor?: boolean
  allowHandoff?: boolean
  maxAgents?: number
  checkpointDir?: string
  pimail?: {
    enabled?: boolean
    path?: string
    host?: string
    port?: number
  }
}
