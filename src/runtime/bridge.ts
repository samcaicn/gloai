import { randomUUID } from 'node:crypto'
import { bridgedToolName, isControlToolName } from '../plugin/names.js'
import type { McpToolSpec, ToolCallOutput, ToolRuntimeResult, ToolRuntimeView } from '../types.js'

export interface BridgedTool {
  publicName: string
  rawName: string
  description: string
  inputSchema: Record<string, unknown>
}

/**
 * Mirror a DSH `ctx.tools` registry as MCP tools named `dsh__<rawName>`.
 */
export class ToolBridge {
  private tools: BridgedTool[] = []
  private readonly byPublic = new Map<string, BridgedTool>()

  constructor(private readonly runtime: ToolRuntimeView) {}

  sync(): BridgedTool[] {
    this.tools = []
    this.byPublic.clear()
    for (const schema of this.runtime.schemas()) {
      if (isControlToolName(schema.name)) continue
      const publicName = bridgedToolName(schema.name)
      const tool: BridgedTool = {
        publicName,
        rawName: schema.name,
        description: schema.description,
        inputSchema: asObjectSchema(schema.parameters),
      }
      this.tools.push(tool)
      this.byPublic.set(publicName, tool)
    }
    return this.tools
  }

  list(): BridgedTool[] {
    return this.tools
  }

  mcpSpecs(): McpToolSpec[] {
    return this.tools.map(tool => ({
      name: tool.publicName,
      description: `[DSH plugin tool: ${tool.rawName}] ${tool.description}`,
      inputSchema: tool.inputSchema,
    }))
  }

  async call(publicName: string, args: unknown, signal: AbortSignal): Promise<ToolCallOutput> {
    const tool = this.byPublic.get(publicName)
    if (!tool) {
      return { content: [{ type: 'text', text: `unknown bridged tool ${publicName}` }], isError: true }
    }
    const result = await this.runtime.execute({
      callId: `mcp-${randomUUID()}`,
      name: tool.rawName,
      arguments: args ?? {},
      signal,
    })
    return mapRuntimeResult(result)
  }
}

export function asObjectSchema(parameters: Record<string, unknown>): Record<string, unknown> {
  if (parameters.type === 'object' || parameters.properties !== undefined) return parameters
  return { type: 'object', properties: parameters, additionalProperties: true }
}

export function mapRuntimeResult(result: ToolRuntimeResult): ToolCallOutput {
  if (result.isError) {
    const message = result.error?.message ?? textFromContent(result.content) ?? 'tool failed'
    return { content: [{ type: 'text', text: message }], isError: true }
  }
  const text = textFromContent(result.content)
    ?? (result.value !== undefined ? JSON.stringify(result.value, null, 2) : '(no output)')
  const output: ToolCallOutput = { content: [{ type: 'text', text }] }
  if (result.value !== undefined) output.structuredContent = result.value
  return output
}

function textFromContent(content: Array<Record<string, unknown>> | undefined): string | null {
  if (!content?.length) return null
  const parts: string[] = []
  for (const block of content) {
    if (block.type === 'text' && typeof block.text === 'string') parts.push(block.text)
    else parts.push(JSON.stringify(block))
  }
  return parts.join('\n') || null
}
