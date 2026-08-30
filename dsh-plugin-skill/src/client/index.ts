/**
 * dsh-plugin-skill — Cordis 浏览器端插件
 *
 * 【铁律】通过官方 ModuleLoader + slots 系统注册 UI 组件。
 */

interface CordisContext {
  tools: ClientTools
  clientModules: ClientModules
  slots?: SlotsService
}

interface ClientTools {
  schemas(): Array<{ name: string; description: string; parameters: Record<string, unknown> }>
  execute(input: { callId: string; name: string; arguments: unknown; signal: AbortSignal }): Promise<{ isError: boolean; content: Array<Record<string, unknown>> }>
  on?(event: string, handler: () => void): void
}

interface ClientModules {
  notifyToolsChanged(): void
}

interface SlotsService {
  inject(key: string, callback: () => void): void
  register(entry: SlotEntry, component: React.ComponentType<any> | (() => null)): void
}

interface SlotEntry {
  name: string
  id: string
  order: number
}

interface ModuleLoaderEntry {
  id: string
  factory: (ctx: CordisContext) => void | Promise<void>
}

interface WindowWithModuleLoader extends Window {
  __ModuleLoader__?: {
    load(entry: ModuleLoaderEntry): void
  }
}

const PLUGIN_ID = 'dsh-plugin-skill-client'

function skillClientFactory(ctx: CordisContext): void {
  const { tools } = ctx
  if ('on' in tools && typeof tools.on === 'function') {
    ;(tools as ClientTools & { on(event: string, handler: () => void): void }).on('tools/change', () => ctx.clientModules?.notifyToolsChanged())
  }

  // 【官方方式】通过 slots 系统注册侧边栏入口
  if (ctx.slots) {
    ctx.slots.inject('sidebar.settings', () =>
      ctx.slots!.register({
        name: 'sidebar.settings',
        id: 'skill',
        order: 50,
      }, () => null))
  }

  console.log(`[${PLUGIN_ID}] Skill 客户端插件已加载，工具数: ${tools.schemas().length}`)
}

function registerPlugin(): void {
  const win = window as WindowWithModuleLoader
  if (win.__ModuleLoader__) {
    win.__ModuleLoader__.load({ id: PLUGIN_ID, factory: skillClientFactory })
    console.log(`[${PLUGIN_ID}] 已通过 ModuleLoader 注册`)
  } else {
    console.warn(`[${PLUGIN_ID}] __ModuleLoader__ 未注入，等待引导...`)
    const checkInterval = window.setInterval(() => {
      if ((window as WindowWithModuleLoader).__ModuleLoader__) {
        window.clearInterval(checkInterval)
        ;(window as WindowWithModuleLoader).__ModuleLoader__?.load({ id: PLUGIN_ID, factory: skillClientFactory })
        console.log(`[${PLUGIN_ID}] 延迟注册完成`)
      }
    }, 100)
    window.setTimeout(() => window.clearInterval(checkInterval), 10000)
  }
}

registerPlugin()

export { skillClientFactory, PLUGIN_ID }
