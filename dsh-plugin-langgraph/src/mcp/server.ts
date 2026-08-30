import { Server } from '@modelcontextprotocol/sdk/server/index.js'
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from '@modelcontextprotocol/sdk/types.js'
import { z } from 'zod'
import { SERVER_NAME, SERVER_VERSION } from '../config.js'
import type { DshPluginContext, McpToolSpec, ToolCallOutput } from '../types.js'
import type { PiMailClient } from '../pi-mail/index.js'
import { LangGraphScheduler } from '../scheduler/core.js'

export interface SchedulerDeps {
  scheduler: LangGraphScheduler
  context: DshPluginContext
  piMail?: PiMailClient
}

const DispatchTask = z.object({
  taskId: z.string().min(1),
  mode: z.enum(['supervisor', 'handoff']),
  objective: z.string().min(1),
  subAgents: z.array(z.object({
    name: z.string().min(1),
    description: z.string(),
    systemPrompt: z.string(),
    tools: z.array(z.string()),
    canHandoffTo: z.array(z.string()).optional(),
  })).min(1),
  maxSteps: z.number().int().positive().optional(),
  initialState: z.record(z.unknown()).optional(),
})

const StreamTask = z.object({
  taskId: z.string().min(1),
  mode: z.enum(['supervisor', 'handoff']),
  objective: z.string().min(1),
  subAgents: z.array(z.object({
    name: z.string().min(1),
    description: z.string(),
    systemPrompt: z.string(),
    tools: z.array(z.string()),
    canHandoffTo: z.array(z.string()).optional(),
  })).min(1),
  maxSteps: z.number().int().positive().optional(),
  initialState: z.record(z.unknown()).optional(),
})

const GetResult = z.object({
  taskId: z.string().min(1),
})

const DeleteTask = z.object({
  threadId: z.string().min(1),
})

function objectSchema(properties: Record<string, unknown>, required: string[] = []): Record<string, unknown> {
  return {
    type: 'object',
    properties,
    required,
    additionalProperties: false,
  }
}

export function createSchedulerMcpServer(deps: SchedulerDeps) {
  const { scheduler, context, piMail } = deps
  const server = new Server(
    { name: SERVER_NAME, version: SERVER_VERSION },
    {
      capabilities: {
        tools: { listChanged: true },
      },
      instructions: [
        'LangGraph multi-agent scheduler for DeepSeek Harness.',
        'Dispatch tasks to coordinated multi-agent workflows using Supervisor or Handoff patterns.',
        'Supervisor mode: a coordinator delegates tasks to specialist workers sequentially.',
        'Handoff mode: agents pass control between each other based on task needs.',
        piMail ? 'pi-mail federation enabled: mail_send, mail_list, mail_broadcast, board_* tools available.' : '',
      ].filter(Boolean).join(' '),
    },
  )

  const tools: McpToolSpec[] = [
    {
      name: 'lg_dispatch',
      description: 'Dispatch a multi-agent task using LangGraph. Supports supervisor and handoff coordination modes.',
      inputSchema: objectSchema({
        taskId: { type: 'string', description: 'Unique identifier for this task' },
        mode: { type: 'string', enum: ['supervisor', 'handoff'], description: 'Coordination pattern' },
        objective: { type: 'string', description: 'The overall objective for the agents to achieve' },
        subAgents: {
          type: 'array',
          description: 'Array of agent configurations',
          items: {
            type: 'object',
            properties: {
              name: { type: 'string', description: 'Agent name' },
              description: { type: 'string', description: 'Agent role description' },
              systemPrompt: { type: 'string', description: 'Agent system prompt' },
              tools: { type: 'array', items: { type: 'string' }, description: 'Tool names available to this agent' },
              canHandoffTo: { type: 'array', items: { type: 'string' }, description: 'Agents this one can hand off to (handoff mode only)' },
            },
            required: ['name', 'description', 'systemPrompt', 'tools'],
          },
        },
        maxSteps: { type: 'integer', description: 'Maximum total steps before forced termination' },
        initialState: { type: 'object', description: 'Initial shared state data' },
      }, ['taskId', 'mode', 'objective', 'subAgents']),
    },
    {
      name: 'lg_stream',
      description: 'Stream a multi-agent task execution, returning intermediate step traces.',
      inputSchema: objectSchema({
        taskId: { type: 'string', description: 'Unique identifier for this task' },
        mode: { type: 'string', enum: ['supervisor', 'handoff'], description: 'Coordination pattern' },
        objective: { type: 'string', description: 'The overall objective' },
        subAgents: {
          type: 'array',
          description: 'Array of agent configurations',
          items: {
            type: 'object',
            properties: {
              name: { type: 'string' },
              description: { type: 'string' },
              systemPrompt: { type: 'string' },
              tools: { type: 'array', items: { type: 'string' } },
              canHandoffTo: { type: 'array', items: { type: 'string' } },
            },
            required: ['name', 'description', 'systemPrompt', 'tools'],
          },
        },
        maxSteps: { type: 'integer' },
        initialState: { type: 'object' },
      }, ['taskId', 'mode', 'objective', 'subAgents']),
    },
    {
      name: 'lg_get_result',
      description: 'Get the result of a completed task.',
      inputSchema: objectSchema({
        taskId: { type: 'string', description: 'Task identifier' },
      }, ['taskId']),
    },
    {
      name: 'lg_list_results',
      description: 'List all tracked task results.',
      inputSchema: objectSchema({}),
    },
    {
      name: 'lg_delete_task',
      description: 'Delete a task and its checkpoint data.',
      inputSchema: objectSchema({
        threadId: { type: 'string', description: 'Thread identifier to delete' },
      }, ['threadId']),
    },
    {
      name: 'lg_status',
      description: 'Report scheduler status: active tasks, available tools, configuration.',
      inputSchema: objectSchema({}),
    },
    ...(piMail ? PiMailMcpTools : []),
  ]

  server.setRequestHandler(ListToolsRequestSchema, async () => ({
    tools: tools.map(tool => ({
      name: tool.name,
      description: tool.description,
      inputSchema: tool.inputSchema,
    })),
  }))

  server.setRequestHandler(CallToolRequestSchema, async (request, extra) => {
    const args = (request.params.arguments ?? {}) as Record<string, unknown>
    const signal = extra.signal ?? AbortSignal.timeout(120_000)

    try {
      const result = await handleToolCall(request.params.name, args, signal)
      return {
        content: result.content,
        ...(result.structuredContent !== undefined ? { structuredContent: result.structuredContent } : {}),
        ...(result.isError === true ? { isError: true } : {}),
      }
    } catch (error) {
      return { content: [{ type: 'text', text: String(error) }], isError: true }
    }
  })

  async function handleToolCall(name: string, args: Record<string, unknown>, signal: AbortSignal): Promise<ToolCallOutput> {
    switch (name) {
      case 'lg_dispatch': {
        const parsed = DispatchTask.parse(args)
        const result = await scheduler.dispatch(parsed)
        return jsonOutput(result)
      }
      case 'lg_stream': {
        const parsed = StreamTask.parse(args)
        const traces: unknown[] = []
        for await (const trace of scheduler.stream(parsed)) {
          traces.push(trace)
        }
        return jsonOutput({ taskId: parsed.taskId, steps: traces, count: traces.length })
      }
      case 'lg_get_result': {
        const parsed = GetResult.parse(args)
        const result = scheduler.getResult(parsed.taskId)
        if (!result) return jsonOutput({ error: `task ${parsed.taskId} not found` }, true)
        return jsonOutput(result)
      }
      case 'lg_list_results': {
        return jsonOutput({ results: scheduler.listResults() })
      }
      case 'lg_delete_task': {
        const parsed = DeleteTask.parse(args)
        await scheduler.deleteTask(parsed.threadId)
        return jsonOutput({ ok: true, threadId: parsed.threadId })
      }
      case 'lg_status': {
        const toolSchemas = context.tools.schemas()
        return jsonOutput({
          server: SERVER_NAME,
          version: SERVER_VERSION,
          activeTasks: scheduler.listResults().length,
          availableTools: toolSchemas.map(t => t.name),
          modes: ['supervisor', 'handoff'],
          piMail: piMail ? 'connected' : 'disabled',
        })
      }
      case 'pi_mail_list_agents':
        return await handlePiMail(piMail, async (c) => {
          const agents = await c.listAgents()
          return jsonOutput({ agents })
        })
      case 'pi_mail_list':
        return await handlePiMail(piMail, async (c) => {
          const page = await c.listMessages({ limit: 20 })
          return jsonOutput(page)
        })
      case 'pi_mail_send':
        return await handlePiMail(piMail, async (c) => {
          const { to, subject, body, newSession } = args as { to: string; subject: string; body: string; newSession?: boolean }
          const result = await c.sendMail(to, subject, body, newSession)
          return jsonOutput(result)
        })
      case 'pi_mail_broadcast':
        return await handlePiMail(piMail, async (c) => {
          const { subject, body } = args as { subject: string; body: string }
          const result = await c.broadcast(subject, body)
          return jsonOutput(result)
        })
      case 'pi_mail_archive':
        return await handlePiMail(piMail, async (c) => {
          const { id } = args as { id: string }
          const result = await c.archiveMessage(id)
          return jsonOutput(result)
        })
      case 'pi_board_list':
        return await handlePiMail(piMail, async (c) => {
          const { status } = args as { status?: string }
          const board = await c.getBoard()
          const filtered = status ? board.tasks.filter(t => t.column === status) : board.tasks
          return jsonOutput({ columns: board.columns, tasks: filtered })
        })
      case 'pi_board_create':
        return await handlePiMail(piMail, async (c) => {
          const req = args as { summary: string; description?: string; column?: string; assignee?: string }
          const result = await c.createTask({ summary: req.summary, description: req.description, column: req.column })
          if (result.ok && result.taskId && req.assignee) {
            await c.assignTask(result.taskId, req.assignee, true)
          }
          return jsonOutput(result)
        })
      case 'pi_board_move':
        return await handlePiMail(piMail, async (c) => {
          const { taskId, column, note } = args as { taskId: string; column: string; note?: string }
          const result = await c.moveTask(taskId, column, note)
          return jsonOutput(result)
        })
      case 'pi_board_assign':
        return await handlePiMail(piMail, async (c) => {
          const { taskId, assignee, newSession } = args as { taskId: string; assignee: string; newSession?: boolean }
          const result = await c.assignTask(taskId, assignee, newSession ?? true)
          return jsonOutput(result)
        })
      default:
        return jsonOutput({ error: `unknown tool: ${name}` }, true)
    }
  }

  return { server, tools }
}

function handlePiMail(
  piMail: PiMailClient | undefined,
  handler: (client: PiMailClient) => Promise<ToolCallOutput>,
): Promise<ToolCallOutput> {
  if (!piMail) {
    return Promise.resolve(jsonOutput({ error: 'pi-mail not enabled' }, true))
  }
  return handler(piMail)
}

const PiMailMcpTools: McpToolSpec[] = [
  {
    name: 'pi_mail_list_agents',
    description: 'List all agents connected to the pi-mail federation.',
    inputSchema: objectSchema({}),
  },
  {
    name: 'pi_mail_list',
    description: 'List recent mail messages.',
    inputSchema: objectSchema({}),
  },
  {
    name: 'pi_mail_send',
    description: 'Send mail to an agent by name.',
    inputSchema: objectSchema({
      to: { type: 'string', description: 'Recipient agent name' },
      subject: { type: 'string', description: 'Subject line' },
      body: { type: 'string', description: 'Message body' },
      newSession: { type: 'boolean', description: 'Start fresh session on recipient' },
    }, ['to', 'subject', 'body']),
  },
  {
    name: 'pi_mail_broadcast',
    description: 'Broadcast mail to all agents.',
    inputSchema: objectSchema({
      subject: { type: 'string' },
      body: { type: 'string' },
    }, ['subject', 'body']),
  },
  {
    name: 'pi_mail_archive',
    description: 'Archive a mail message.',
    inputSchema: objectSchema({
      id: { type: 'string', description: 'Message ID' },
    }, ['id']),
  },
  {
    name: 'pi_board_list',
    description: 'List board tasks, optionally filtered by column.',
    inputSchema: objectSchema({
      status: { type: 'string', description: 'Filter by column name' },
    }),
  },
  {
    name: 'pi_board_create',
    description: 'Create a board task, optionally assign to an agent.',
    inputSchema: objectSchema({
      summary: { type: 'string' },
      description: { type: 'string' },
      column: { type: 'string' },
      assignee: { type: 'string' },
    }, ['summary']),
  },
  {
    name: 'pi_board_move',
    description: 'Move a task to a different column.',
    inputSchema: objectSchema({
      taskId: { type: 'string' },
      column: { type: 'string' },
      note: { type: 'string' },
    }, ['taskId', 'column']),
  },
  {
    name: 'pi_board_assign',
    description: 'Assign a task to an agent.',
    inputSchema: objectSchema({
      taskId: { type: 'string' },
      assignee: { type: 'string' },
      newSession: { type: 'boolean' },
    }, ['taskId', 'assignee']),
  },
]

function jsonOutput(value: unknown, isError = false): ToolCallOutput {
  return {
    content: [{ type: 'text', text: JSON.stringify(value, null, 2) }],
    isError,
  }
}
