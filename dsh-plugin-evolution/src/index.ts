/**
 * dsh-plugin-evolution — Cordis Host 端插件入口
 *
 * 【铁律】UI 入口通过官方 slots 系统注册，不使用 cordis.patch.yml 的 ui 配置。
 */

export const name = 'dsh-plugin-evolution'
export const inject = ['slots']

export interface EvolutionConfig {
  windowSize: number
}

export const Config = {
  windowSize: 7,
}

export function apply(ctx: any, config: EvolutionConfig): void {
  // 【官方方式】通过 slots 系统注册侧边栏入口
  if (ctx.slots) {
    ctx.slots.inject('sidebar.settings', () =>
      ctx.slots.register({
        name: 'sidebar.settings',
        id: 'evolution',
        order: 30,
      }, () => null))
  }

  console.log(`[${name}] Evolution 插件已加载，窗口大小: ${config.windowSize} 天`)
}

export default { name, inject, Config, apply }
