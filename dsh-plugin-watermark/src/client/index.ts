/**
 * dsh-plugin-watermark — Cordis 浏览器端插件
 *
 * 【铁律】通过官方 ModuleLoader + slots 系统注册 UI 组件。
 * 遵循 Cordis 插件规范：声明 dsh.client，通过 window.__ModuleLoader__.load() 注册。
 *
 * 集成方式：
 * 1. 通过 ModuleLoader 注册客户端工厂
 * 2. 工厂函数内通过 ctx.slots 注册侧边栏入口和场景页面
 */

// ---------------------------------------------------------------------------
// 类型声明
// ---------------------------------------------------------------------------

/** Cordis 上下文 — 浏览器端注入的服务 */
interface CordisContext {
  tools: ClientTools
  clientModules: ClientModules
  slots?: SlotsService
}

/** 客户端工具运行时 */
interface ClientTools {
  schemas(): Array<{ name: string; description: string; parameters: Record<string, unknown> }>
  execute(input: {
    callId: string
    name: string
    arguments: unknown
    signal: AbortSignal
  }): Promise<{ isError: boolean; content: Array<Record<string, unknown>> }>
  on?(event: string, handler: () => void): void
}

/** 客户端模块系统 */
interface ClientModules {
  notifyToolsChanged(): void
}

/** Slots 服务 — 官方 UI 注册接口 */
interface SlotsService {
  inject(key: string, callback: () => void): void
  register(entry: SlotEntry, component: React.ComponentType<any> | (() => null)): void
}

/** 槽位注册入口 */
interface SlotEntry {
  name: string
  id: string
  order: number
}

/** ModuleLoader 注册接口 */
interface ModuleLoaderEntry {
  id: string
  factory: (ctx: CordisContext) => void | Promise<void>
}

/** 全局 ModuleLoader — 由 DSH WebUI 引导阶段注入 */
interface WindowWithModuleLoader extends Window {
  __ModuleLoader__?: {
    load(entry: ModuleLoaderEntry): void
  }
}

// ---------------------------------------------------------------------------
// 插件注册
// ---------------------------------------------------------------------------

const PLUGIN_ID = 'dsh-plugin-watermark-client'

/**
 * 插件工厂函数 — 由 ModuleLoader 在 materialization 阶段调用
 */
function watermarkClientFactory(ctx: CordisContext): void {
  const { tools } = ctx

  // 注册工具变更监听
  if ('on' in tools && typeof tools.on === 'function') {
    ;(tools as ClientTools & { on(event: string, handler: () => void): void }).on(
      'tools/change',
      () => ctx.clientModules?.notifyToolsChanged(),
    )
  }

  // 【官方方式】通过 slots 系统注册侧边栏入口
  if (ctx.slots) {
    ctx.slots.inject('sidebar.settings', () =>
      ctx.slots!.register({
        name: 'sidebar.settings',
        id: 'watermark',
        order: 60,
      }, () => null))
  }

  console.log(`[${PLUGIN_ID}] 视频去水印客户端插件已加载，工具数: ${tools.schemas().length}`)
}

// ---------------------------------------------------------------------------
// 引导注册 — 通过 ModuleLoader
// ---------------------------------------------------------------------------

function registerPlugin(): void {
  const win = window as WindowWithModuleLoader

  if (win.__ModuleLoader__) {
    win.__ModuleLoader__.load({
      id: PLUGIN_ID,
      factory: watermarkClientFactory,
    })
    console.log(`[${PLUGIN_ID}] 已通过 ModuleLoader 注册`)
  } else {
    console.warn(`[${PLUGIN_ID}] __ModuleLoader__ 未注入，等待引导...`)

    const checkInterval = window.setInterval(() => {
      if ((window as WindowWithModuleLoader).__ModuleLoader__) {
        window.clearInterval(checkInterval)
        ;(window as WindowWithModuleLoader).__ModuleLoader__?.load({
          id: PLUGIN_ID,
          factory: watermarkClientFactory,
        })
        console.log(`[${PLUGIN_ID}] 延迟注册完成`)
      }
    }, 100)

    window.setTimeout(() => {
      window.clearInterval(checkInterval)
    }, 10000)
  }
}

// 立即执行注册
registerPlugin()

// 导出供测试使用
export { watermarkClientFactory, PLUGIN_ID }
