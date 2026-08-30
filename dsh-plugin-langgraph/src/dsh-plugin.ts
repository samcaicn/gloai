import { serveHttp } from './mcp/http.js'
import { createSchedulerMcpServer } from './mcp/server.js'
import { LangGraphScheduler } from './scheduler/core.js'
import { resolvePluginConfig, type PluginConfigInput } from './config.js'
import { PiMailDaemon, buildPiMailTools } from './pi-mail/index.js'
import type { DshPluginContext } from './types.js'

export const name = 'dsh-plugin-langgraph'
export const inject = ['tools']

export type PluginConfig = PluginConfigInput

/**
 * Cordis 插件入口 - LangGraph 多 Agent 调度器 + pi-mail 联邦邮件
 * 配置即插即用，无需额外设置
 */
export async function apply(ctx: DshPluginContext, config: PluginConfig = {}): Promise<void> {
  const resolved = resolvePluginConfig(config)

  // ── pi-mail daemon ─────────────────────────────────────────────────────
  let piMail: PiMailDaemon | null = null
  let extraTools: ReturnType<typeof buildPiMailTools> = []

  if (resolved.piMail.enabled) {
    piMail = new PiMailDaemon({
      extensionDir: resolved.piMail.extensionDir,
      host: resolved.piMail.host,
      port: resolved.piMail.port,
      logger: ctx.logger,
    })

    // Try to start the daemon (non-fatal if pi-mail not installed)
    if (piMail.scriptExists()) {
      try {
        const info = await piMail.start()
        ctx.logger.info(`pi-mail 联邦邮件已启动: ${info.url}`)
        extraTools = buildPiMailTools(piMail.createClient())
      } catch (error) {
        ctx.logger.error(`pi-mail 启动失败: ${error instanceof Error ? error.message : String(error)}`)
        piMail = null
      }
    } else {
      ctx.logger.info('pi-mail 未安装，跳过 (设置 DSH_LANGGRAPH_PIMAIL_DIR 指向 pi-mail/extensions 目录以启用)')
      piMail = null
    }
  }

  // ── LangGraph scheduler ────────────────────────────────────────────────
  const scheduler = new LangGraphScheduler(
    ctx.tools,
    resolved.checkpointDir,
    resolved.maxAgents,
    extraTools,
  )
  const { server } = createSchedulerMcpServer({ scheduler, context: ctx, piMail: piMail?.createClient() })

  const listening = await serveHttp(server, resolved)
  try {
    ctx.logger.info(`${name} 已启动: ${listening.url}`)
    const stopChange = ctx.on('tools/change', () => {
      listening.notifyToolsChanged()
    })
    ctx.effect(() => () => {
      stopChange()
      void listening.close()
      void piMail?.stop()
    }, `${name}.http`)
  } catch (error) {
    await listening.close()
    await piMail?.stop()
    throw error
  }
}
