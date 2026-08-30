/**
 * dsh-plugin-desktop — Cordis Host 端插件入口
 *
 * 注册 Tauri 桌面窗口管理和后端启动服务到 Cordis 上下文。
 * 遵循 DSH 插件规范：导出 name、inject、Config、apply。
 */

export const name = 'dsh-plugin-desktop'
export const inject = []

// 配置 Schema
export interface DesktopConfig {
  backend: {
    host: string
    port: number
    profile: string
    noOpen: boolean
  }
  window: {
    title: string
    width: number
    height: number
    minWidth: number
    minHeight: number
    decorations: boolean
    theme: string
  }
  product: {
    name: string
    version: string
    identifier: string
  }
}

export const Config = {
  backend: {
    host: '127.0.0.1',
    port: 3080,
    profile: 'aimarketing',
    noOpen: true,
  },
  window: {
    title: 'AiMarketing',
    width: 1200,
    height: 800,
    minWidth: 800,
    minHeight: 600,
    decorations: true,
    theme: 'Dark',
  },
  product: {
    name: 'AiMarketing',
    version: '1.0.0',
    identifier: 'com.aimarketing.desktop',
  },
}

export interface Context {
  desktop: DesktopService
}

export interface DesktopService {
  getConfig(): DesktopConfig
  getBackendUrl(): string
  getProductName(): string
  getVersion(): string
}

export function apply(ctx: any, config: DesktopConfig): void {
  const service: DesktopService = {
    getConfig: () => config,
    getBackendUrl: () => `http://${config.backend.host}:${config.backend.port}`,
    getProductName: () => config.product.name,
    getVersion: () => config.product.version,
  }

  if (ctx.provide) {
    ctx.provide('desktop', service)
  }

  console.log(`[${name}] 桌面插件已加载，后端: ${service.getBackendUrl()}`)
}

export default { name, inject, Config, apply }
