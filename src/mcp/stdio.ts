import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js'
import type { Server } from '@modelcontextprotocol/sdk/server/index.js'
import type { Transport } from '@modelcontextprotocol/sdk/shared/transport.js'

export async function serveStdio(server: Server): Promise<void> {
  const transport = new StdioServerTransport()
  await server.connect(transport as Transport)
}
