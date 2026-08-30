/**
 * dsh-plugin-skill — Cordis Host 端插件入口
 *
 * 【铁律】UI 入口通过官方 slots 系统注册，不使用 cordis.patch.yml 的 ui 配置。
 */

export const name = 'dsh-plugin-skill'
export const inject = ['slots']

export interface SkillConfig {
  registryPath: string
  compilerEnabled: boolean
  evalEnabled: boolean
}

export const Config = {
  registryPath: '~/.dsh/skills',
  compilerEnabled: true,
  evalEnabled: true,
}

export function apply(ctx: any, config: SkillConfig): void {
  // 【官方方式】通过 slots 系统注册侧边栏入口
  if (ctx.slots) {
    ctx.slots.inject('sidebar.settings', () =>
      ctx.slots.register({
        name: 'sidebar.settings',
        id: 'skill',
        order: 50,
      }, () => null))
  }

  console.log(`[${name}] Skill 插件已加载，注册表: ${config.registryPath}`)
}

export default { name, inject, Config, apply }
