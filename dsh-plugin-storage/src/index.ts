/**
 * dsh-plugin-storage — Cordis Host 端插件入口
 *
 * 注册数据存储相关的服务和工具到 Cordis 上下文。
 * 遵循 DSH 插件规范：导出 name、inject、Config、apply。
 *
 * 【铁律】UI 入口通过官方 slots 系统注册，不使用 cordis.patch.yml 的 ui 配置。
 */

export const name = 'dsh-plugin-storage'
export const inject = ['slots']

export interface StorageConfig {
  dataPath: string
  engine: 'sqlite' | 'duckdb' | 'json'
  autoBackup: boolean
  backupInterval: number
}

export const Config = {
  dataPath: '~/.dsh/storage',
  engine: 'sqlite',
  autoBackup: true,
  backupInterval: 3600,
}

export interface Context {
  storage: StorageService
}

export interface StorageService {
  getConfig(): StorageConfig
  getEngine(): string
  getDataPath(): string
}

export function apply(ctx: any, config: StorageConfig): void {
  const service: StorageService = {
    getConfig: () => config,
    getEngine: () => config.engine,
    getDataPath: () => config.dataPath,
  }

  if (ctx.provide) {
    ctx.provide('storage', service)
  }

  // 【官方方式】通过 slots 系统注册侧边栏入口
  if (ctx.slots) {
    ctx.slots.inject('sidebar.settings', () =>
      ctx.slots.register({
        name: 'sidebar.settings',
        id: 'storage',
        order: 45,
      }, () => null))
  }

  console.log(`[${name}] 存储插件已加载，引擎: ${config.engine}`)
}

export default { name, inject, Config, apply }
