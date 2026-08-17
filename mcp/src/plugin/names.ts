import { createHash } from 'node:crypto'
import { BRIDGED_TOOL_PREFIX } from '../config.js'

const MAX_PUBLIC_NAME_LENGTH = 64
const INVALID_NAME_CHARS = /[^A-Za-z0-9_-]/g
const HASH_LENGTH = 12

/**
 * Deterministic MCP-facing name for one DSH tool.
 * Clean case is `dsh__<rawName>`. Character replacement or truncation appends a
 * 12-hex SHA-256 of the raw name so distinct tools never collapse.
 */
export function bridgedToolName(rawName: string): string {
  const joined = `${BRIDGED_TOOL_PREFIX}${rawName}`
  const normalized = joined.replace(INVALID_NAME_CHARS, '_')
  if (normalized === joined && normalized.length <= MAX_PUBLIC_NAME_LENGTH) return normalized
  const hash = createHash('sha256').update(rawName).digest('hex').slice(0, HASH_LENGTH)
  return `${normalized.slice(0, MAX_PUBLIC_NAME_LENGTH - HASH_LENGTH - 1)}_${hash}`
}

export function isControlToolName(name: string): boolean {
  return name.startsWith('dsh_plugin_') || name.startsWith('dsh_runtime_')
}

export function jsonToolResult(value: unknown, isError = false): {
  content: Array<{ type: 'text'; text: string }>
  isError?: boolean
} {
  const text = typeof value === 'string' ? value : JSON.stringify(value, null, 2)
  if (isError) return { content: [{ type: 'text', text }], isError: true }
  return { content: [{ type: 'text', text }] }
}
