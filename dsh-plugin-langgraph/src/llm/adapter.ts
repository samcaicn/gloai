import { type BaseMessage, type AIMessage, type ToolMessage, type HumanMessage, type SystemMessage } from '@langchain/core/messages'
import { type StructuredTool } from '@langchain/core/tools'
import { tool } from '@langchain/core/tools'
import { z } from 'zod'
import type { ToolRuntimeView, ToolRuntimeResult, LlmMessage } from '../types.js'

/**
 * Wraps the DSH tool runtime as LangChain StructuredTool instances.
 * Each DSH tool becomes a LangChain-compatible tool that can be bound to the LLM.
 */
export function buildLangChainTools(runtime: ToolRuntimeView): StructuredTool[] {
  const schemas = runtime.schemas()
  return schemas.map((schema) => {
    const paramSchema = buildZodSchema(schema.parameters)
    return tool(
      async (args: Record<string, unknown>, config?: { signal?: AbortSignal }) => {
        const result = await runtime.execute({
          callId: `lg-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
          name: schema.name,
          arguments: args,
          signal: config?.signal ?? new AbortController().signal,
        })
        return formatToolResult(result)
      },
      {
        name: schema.name,
        description: schema.description,
        schema: paramSchema,
      },
    )
  })
}

/**
 * Convert DSH tool parameters JSON Schema to a Zod schema for LangChain.
 */
function buildZodSchema(parameters: Record<string, unknown>): z.ZodObject<Record<string, z.ZodTypeAny>> {
  const props = (parameters.properties ?? {}) as Record<string, unknown>
  const required = (parameters.required as string[]) ?? []
  const shape: Record<string, z.ZodTypeAny> = {}

  for (const [key, rawSchema] of Object.entries(props)) {
    const propSchema = rawSchema as Record<string, unknown>
    let zodType = inferZodType(propSchema)
    const description = propSchema.description as string | undefined
    if (description) {
      zodType = zodType.describe(description)
    }
    if (!required.includes(key)) {
      zodType = zodType.optional()
    }
    shape[key] = zodType
  }

  return z.object(shape)
}

function inferZodType(schema: Record<string, unknown>): z.ZodTypeAny {
  const type = schema.type
  if (type === 'string') {
    if (Array.isArray(schema.enum) && schema.enum.length > 0) {
      return z.enum(schema.enum as [string, ...string[]])
    }
    return z.string()
  }
  if (type === 'number' || type === 'integer') return z.number()
  if (type === 'boolean') return z.boolean()
  if (type === 'array') {
    const items = schema.items as Record<string, unknown> | undefined
    const itemType = items ? inferZodType(items) : z.unknown()
    return z.array(itemType)
  }
  if (type === 'object') {
    const props = schema.properties as Record<string, unknown> | undefined
    if (props) {
      return buildZodSchema(schema)
    }
    return z.record(z.string(), z.unknown())
  }
  return z.unknown()
}

function formatToolResult(result: ToolRuntimeResult): string {
  if (result.isError) {
    return result.error?.message ?? extractText(result.content) ?? 'Tool execution failed'
  }
  return extractText(result.content) ?? (result.value !== undefined ? JSON.stringify(result.value, null, 2) : '(no output)')
}

function extractText(content: Array<Record<string, unknown>>): string | null {
  if (!content.length) return null
  const parts: string[] = []
  for (const block of content) {
    if (block.type === 'text' && typeof block.text === 'string') {
      parts.push(block.text)
    } else {
      parts.push(JSON.stringify(block))
    }
  }
  return parts.join('\n') || null
}

/**
 * Convert LangChain messages to the internal LlmMessage format.
 */
export function toLlmMessages(messages: BaseMessage[]): LlmMessage[] {
  return messages.map((msg) => {
    const base: LlmMessage = {
      role: mapRole(msg._getType()),
      content: typeof msg.content === 'string' ? msg.content : JSON.stringify(msg.content),
    }
    if (msg.name) base.name = msg.name
    if (msg._getType() === 'ai') {
      const aiMsg = msg as AIMessage
      if (aiMsg.tool_calls && aiMsg.tool_calls.length > 0) {
        base.toolCalls = aiMsg.tool_calls.map((tc) => ({
          name: tc.name,
          args: tc.args,
          id: tc.id ?? '',
        }))
      }
    }
    if (msg._getType() === 'tool') {
      const toolMsg = msg as ToolMessage
      base.toolCallId = toolMsg.tool_call_id
    }
    return base
  })
}

function mapRole(lcRole: string): LlmMessage['role'] {
  switch (lcRole) {
    case 'system': return 'system'
    case 'human': return 'user'
    case 'ai': return 'ai'
    case 'tool': return 'tool'
    default: return 'user'
  }
}

/**
 * Re-export for convenience.
 */
export type { BaseMessage, AIMessage, ToolMessage, HumanMessage, SystemMessage }
