/**
 * dsh-plugin-memory — Cordis Host 端插件入口
 *
 * 【铁律】UI 入口通过官方 slots 系统注册，不使用 cordis.patch.yml 的 ui 配置。
 */

export const name = 'dsh-plugin-memory'
export const inject = ['slots']

export interface MemoryConfig {
  decay: {
    hot: number
    warm: number
    cold: number
  }
}

export const Config = {
  decay: {
    hot: 0,
    warm: 0.1,
    cold: 0.5,
  },
}

export function apply(ctx: any, config: MemoryConfig): void {
  // 【官方方式】通过 slots 系统注册侧边栏入口
  if (ctx.slots) {
    ctx.slots.inject('sidebar.settings', () =>
      ctx.slots.register({
        name: 'sidebar.settings',
        id: 'memory',
        order: 40,
      }, () => null))
  }

  console.log(`[${name}] Memory 插件已加载`)
}

export default { name, inject, Config, apply }
