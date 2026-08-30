/**
 * dsh-plugin-autoskill — Cordis Host 端插件入口
 *
 * 【铁律】UI 入口通过官方 slots 系统注册，不使用 cordis.patch.yml 的 ui 配置。
 */

export const name = 'dsh-plugin-autoskill'
export const inject = ['slots']

export interface AutoSkillConfig {
  pipeline: string[]
}

export const Config = {
  pipeline: ['param_generalizer', 'pattern_miner', 'state_machine', 'compiler', 'evaluator'],
}

export function apply(ctx: any, config: AutoSkillConfig): void {
  // 【官方方式】通过 slots 系统注册侧边栏入口
  if (ctx.slots) {
    ctx.slots.inject('sidebar.settings', () =>
      ctx.slots.register({
        name: 'sidebar.settings',
        id: 'autoskill',
        order: 20,
      }, () => null))
  }

  console.log(`[${name}] AutoSkill 插件已加载，流水线: ${config.pipeline.join(' -> ')}`)
}

export default { name, inject, Config, apply }
