/**
 * dsh-plugin-watermark — Cordis Host 端插件入口
 *
 * 注册水印去除相关的服务和工具到 Cordis 上下文。
 * 遵循 DSH 插件规范：导出 name、inject、Config、apply。
 *
 * 【铁律】UI 入口通过官方 slots 系统注册，不使用 cordis.patch.yml 的 ui 配置。
 */

export const name = 'dsh-plugin-watermark'
export const inject = ['slots']

// 配置 Schema
export interface WatermarkConfig {
  defaultMethod: 'fft_kcf' | 'lama'
  fftKcf: {
    autoDownload: boolean
    modelUrl: string
  }
  lama: {
    autoDownload: boolean
    modelUrl: string
  }
  modelDir: string
}

export const Config = {
  defaultMethod: 'fft_kcf',
  fftKcf: {
    autoDownload: true,
    modelUrl: 'https://github.com/whitelok/watermark-remover/releases/download/v1.0/fft_kcf_model.pth',
  },
  lama: {
    autoDownload: false,
    modelUrl: 'https://github.com/advimman/lama/releases/download/v1.0.0/best.ckpt',
  },
  modelDir: '~/.dsh/models',
}

export interface Context {
  watermark: WatermarkService
}

export interface WatermarkService {
  getConfig(): WatermarkConfig
  getDefaultMethod(): 'fft_kcf' | 'lama'
  getModelDir(): string
}

export function apply(ctx: any, config: WatermarkConfig): void {
  // 注册水印服务到 Cordis 上下文
  const service: WatermarkService = {
    getConfig: () => config,
    getDefaultMethod: () => config.defaultMethod,
    getModelDir: () => config.modelDir,
  }

  if (ctx.provide) {
    ctx.provide('watermark', service)
  }

  // 【官方方式】通过 slots 系统注册侧边栏入口
  // 注入到 sidebar.settings 槽位，注册去水印设置项
  if (ctx.slots) {
    ctx.slots.inject('sidebar.settings', () =>
      ctx.slots.register({
        name: 'sidebar.settings',
        id: 'watermark',
        order: 60,
      }, () => null))  // 实际组件由客户端渲染
  }

  console.log(`[${name}] 水印插件已加载，默认方法: ${config.defaultMethod}`)
}

export default { name, inject, Config, apply }
