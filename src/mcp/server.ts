import { Server } from '@modelcontextprotocol/sdk/server/index.js'
import {
  CallToolRequestSchema,
  GetPromptRequestSchema,
  ListPromptsRequestSchema,
  ListResourceTemplatesRequestSchema,
  ListResourcesRequestSchema,
  ListToolsRequestSchema,
  ReadResourceRequestSchema,
} from '@modelcontextprotocol/sdk/types.js'
import { SERVER_NAME, SERVER_VERSION, type AppConfig } from '../config.js'
import { createControlTools, type ControlTool } from './control-tools.js'
import { listStaticResources, readResource, RESOURCE_TEMPLATES } from './resources.js'
import { PROMPTS, renderPrompt } from './prompts.js'
import type { SessionDeps } from './control-tools.js'
import type { McpToolSpec, ToolCallOutput } from '../types.js'

export interface PluginMcpHandle {
  server: Server
  listToolSpecs(): McpToolSpec[]
  callTool(name: string, args: Record<string, unknown>, signal: AbortSignal): Promise<ToolCallOutput>
}

/**
 * Build the MCP Server that merges control-plane tools with live bridged DSH tools.
 */
export function createPluginMcpServer(deps: SessionDeps): PluginMcpHandle {
  const control = createControlTools(deps)
  const byName = new Map<string, ControlTool>(control.map(tool => [tool.spec.name, tool]))

  const server = new Server(
    { name: SERVER_NAME, version: SERVER_VERSION },
    {
      capabilities: {
        tools: { listChanged: true },
        resources: { listChanged: true },
        prompts: {},
      },
      instructions: [
        'This server exposes the public DeepSeek Harness plugin ecosystem (GitHub topic dsh-plugin).',
        'Discover plugins with dsh_plugin_search / dsh_plugin_list, then dsh_plugin_inspect.',
        'Install into a DSH profile with dsh_plugin_install (needs --allow-install).',
        'Execute plugin tools by dsh_runtime_start (needs --allow-runtime); bridged tools are named dsh__*.',
        'UI/TUI/skin plugins are catalogued and installable but do not become MCP tools.',
      ].join(' '),
    },
  )

  const listToolSpecs = (): McpToolSpec[] => [
    ...control.map(tool => tool.spec),
    ...deps.runtime.listBridged(),
  ]

  const callTool = async (name: string, args: Record<string, unknown>, signal: AbortSignal): Promise<ToolCallOutput> => {
    const controlTool = byName.get(name)
    if (controlTool) return await controlTool.handle(args, signal)
    const bridged = deps.runtime.listBridged().find(tool => tool.name === name)
    if (bridged) return await deps.runtime.call(name, args, signal)
    return { content: [{ type: 'text', text: `unknown tool ${name}` }], isError: true }
  }

  server.setRequestHandler(ListToolsRequestSchema, async () => ({
    tools: listToolSpecs().map(tool => ({
      name: tool.name,
      description: tool.description,
      inputSchema: tool.inputSchema,
    })),
  }))

  server.setRequestHandler(CallToolRequestSchema, async (request, extra) => {
    const args = (request.params.arguments ?? {}) as Record<string, unknown>
    const signal = extra.signal ?? AbortSignal.timeout(60_000)
    try {
      const result = await callTool(request.params.name, args, signal)
      return {
        content: result.content,
        ...(result.structuredContent !== undefined ? { structuredContent: result.structuredContent } : {}),
        ...(result.isError === true ? { isError: true } : {}),
      }
    } catch (error) {
      return { content: [{ type: 'text', text: String(error) }], isError: true }
    }
  })

  server.setRequestHandler(ListResourcesRequestSchema, async () => ({
    resources: listStaticResources(),
  }))

  server.setRequestHandler(ListResourceTemplatesRequestSchema, async () => ({
    resourceTemplates: RESOURCE_TEMPLATES,
  }))

  server.setRequestHandler(ReadResourceRequestSchema, async (request) => {
    const body = await readResource(request.params.uri, {
      catalog: deps.catalog,
      github: deps.github,
      config: deps.config,
      bridgedJson: () => deps.runtime.listBridged(),
    })
    return {
      contents: [{ uri: request.params.uri, mimeType: body.mimeType, text: body.text }],
    }
  })

  server.setRequestHandler(ListPromptsRequestSchema, async () => ({
    prompts: PROMPTS.map(prompt => ({
      name: prompt.name,
      description: prompt.description,
      arguments: [...prompt.arguments],
    })),
  }))

  server.setRequestHandler(GetPromptRequestSchema, async (request) => {
    const args = (request.params.arguments ?? {}) as Record<string, string>
    const text = renderPrompt(request.params.name, args)
    return {
      description: PROMPTS.find(prompt => prompt.name === request.params.name)?.description ?? '',
      messages: [{ role: 'user', content: { type: 'text', text } }],
    }
  })

  return { server, listToolSpecs, callTool }
}

export function notifyToolsChanged(server: Server): void {
  void server.sendToolListChanged()
}

export type { AppConfig }
