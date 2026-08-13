#!/usr/bin/env node
import { HELP_TEXT, HelpRequested, resolveConfig } from './config.js'
import { createApp } from './app.js'
import { serveStdio } from './mcp/stdio.js'
import { serveHttp } from './mcp/http.js'

async function main(): Promise<void> {
  let config
  try {
    config = resolveConfig()
  } catch (error) {
    if (error instanceof HelpRequested) {
      process.stdout.write(`${HELP_TEXT}\n`)
      return
    }
    throw error
  }

  const app = createApp(config)
  if (config.transport === 'http') {
    const listening = await serveHttp(app.server, config)
    process.stderr.write(`${listening.url}  health http://${config.host}:${config.port}/health\n`)
    const shutdown = async () => {
      await app.runtime.stop()
      await listening.close()
      process.exit(0)
    }
    process.on('SIGINT', () => void shutdown())
    process.on('SIGTERM', () => void shutdown())
    return
  }

  await serveStdio(app.server)
}

main().catch((error: unknown) => {
  process.stderr.write(`${error instanceof Error ? error.stack ?? error.message : String(error)}\n`)
  process.exit(1)
})
