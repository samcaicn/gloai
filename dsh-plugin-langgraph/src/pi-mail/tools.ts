import { tool } from '@langchain/core/tools'
import { z } from 'zod'
import type { StructuredTool } from '@langchain/core/tools'
import { PiMailClient } from './client.js'

/**
 * Build LangChain StructuredTool instances for pi-mail operations.
 * These tools let LangGraph agents send, read, and broadcast mail
 * through the pi-mail federation daemon.
 */
export function buildPiMailTools(client: PiMailClient): StructuredTool[] {
  return [
    tool(
      async () => {
        const agents = await client.listAgents()
        if (agents.length === 0) return 'No agents currently connected to the federation.'
        return agents
          .map((a) => `- ${a.agentName} (${a.agentId.slice(0, 8)}...) | project: ${a.cwd} | status: ${a.status || 'idle'} | model: ${a.model || 'default'}`)
          .join('\n')
      },
      {
        name: 'pi_mail_list_agents',
        description: 'List all agents currently connected to the pi-mail federation. Shows name, project, status, and model.',
      },
    ),

    tool(
      async (input: { limit?: number; includeArchived?: boolean }) => {
        const page = await client.listMessages({
          limit: input.limit ?? 20,
          archived: input.includeArchived ? 'include' : 'exclude',
        })
        if (page.messages.length === 0) return 'No messages found.'
        const lines = page.messages.map(
          (m) => `[${m.read ? 'read' : 'UNREAD'}] From: ${m.fromName} | Subject: ${m.subject}\n${m.body.slice(0, 500)}${m.body.length > 500 ? '...' : ''}`,
        )
        return `Messages (${page.total} total, showing ${page.messages.length}):\n\n${lines.join('\n\n')}`
      },
      {
        name: 'pi_mail_list',
        description: 'List recent mail messages in the federation inbox. Optionally include archived messages.',
        schema: z.object({
          limit: z.number().int().min(1).max(100).optional().describe('Maximum messages to return'),
          includeArchived: z.boolean().optional().describe('Include archived messages'),
        }),
      },
    ),

    tool(
      async (input: { to: string; subject: string; body: string; newSession?: boolean }) => {
        const result = await client.sendMail(input.to, input.subject, input.body, input.newSession)
        if (result.ok) return `Mail sent to "${input.to}" (id: ${result.messageId ?? 'n/a'})`
        throw new Error('Failed to send mail')
      },
      {
        name: 'pi_mail_send',
        description: 'Send a mail message to a specific agent by name. Useful for delegating tasks or asking questions to other agents.',
        schema: z.object({
          to: z.string().describe('Name of the recipient agent'),
          subject: z.string().describe('Message subject line'),
          body: z.string().describe('Message body content'),
          newSession: z.boolean().optional().describe('If true, starts a fresh session on the recipient side'),
        }),
      },
    ),

    tool(
      async (input: { subject: string; body: string }) => {
        const result = await client.broadcast(input.subject, input.body)
        return `Broadcast sent to ${result.recipients} agents.`
      },
      {
        name: 'pi_mail_broadcast',
        description: 'Broadcast a mail message to all connected agents simultaneously.',
        schema: z.object({
          subject: z.string().describe('Message subject line'),
          body: z.string().describe('Message body content'),
        }),
      },
    ),

    tool(
      async (input: { messageId: string }) => {
        const result = await client.archiveMessage(input.messageId)
        if (result.ok) return `Message ${input.messageId} archived.`
        throw new Error('Failed to archive message')
      },
      {
        name: 'pi_mail_archive',
        description: 'Archive a mail message by its ID.',
        schema: z.object({
          messageId: z.string().describe('ID of the message to archive'),
        }),
      },
    ),

    tool(
      async (input: { status?: string; includeArchived?: boolean }) => {
        const board = await client.getBoard({
          location: 'board',
          includeArchived: input.includeArchived,
        })
        if (board.tasks.length === 0) return 'Board is empty.'
        const byCol = new Map<string, typeof board.tasks>()
        for (const t of board.tasks) {
          if (input.status && t.column !== input.status) continue
          const arr = byCol.get(t.column) ?? []
          arr.push(t)
          byCol.set(t.column, arr)
        }
        const lines: string[] = []
        for (const col of board.columns) {
          const tasks = byCol.get(col.id)
          if (!tasks || tasks.length === 0) continue
          lines.push(`## ${col.name}`)
          for (const t of tasks) {
            const assignee = t.assignee ? ` @${t.assignee}` : ''
            const flag = t.flagged ? ' ⚠' : ''
            lines.push(`- [${t.id.slice(0, 8)}] ${t.summary}${assignee}${flag}`)
          }
        }
        return lines.join('\n') ?? 'No tasks match the filter.'
      },
      {
        name: 'pi_board_list',
        description: 'List tasks on the kanban board, grouped by column. Optionally filter by column/status name.',
        schema: z.object({
          status: z.string().optional().describe('Filter by column name (e.g. "In Progress", "Review")'),
          includeArchived: z.boolean().optional().describe('Include archived tasks'),
        }),
      },
    ),

    tool(
      async (input: { summary: string; description?: string; column?: string; assignee?: string }) => {
        const result = await client.createTask({
          summary: input.summary,
          description: input.description,
          column: input.column,
        })
        if (!result.ok || !result.taskId) throw new Error('Failed to create task')
        if (input.assignee) {
          await client.assignTask(result.taskId, input.assignee, true)
        }
        return `Task created: "${input.summary}" (id: ${result.taskId.slice(0, 8)})${input.assignee ? `, assigned to ${input.assignee}` : ''}`
      },
      {
        name: 'pi_board_create_task',
        description: 'Create a new task on the kanban board. Optionally assign it to an agent (which mails them the task).',
        schema: z.object({
          summary: z.string().describe('Task title/summary'),
          description: z.string().optional().describe('Task description'),
          column: z.string().optional().describe('Target column name (e.g. "To Do", "Refine")'),
          assignee: z.string().optional().describe('Agent name to assign (mails them the task)'),
        }),
      },
    ),

    tool(
      async (input: { taskId: string; column: string; note?: string }) => {
        const result = await client.moveTask(input.taskId, input.column, input.note)
        if (result.ok) return `Task ${input.taskId.slice(0, 8)} moved to "${input.column}".`
        throw new Error('Failed to move task')
      },
      {
        name: 'pi_board_move_task',
        description: 'Move a board task to a different column (e.g. "In Progress", "Done", "Review", "Archive").',
        schema: z.object({
          taskId: z.string().describe('Task ID (full or first 8 chars)'),
          column: z.string().describe('Target column name'),
          note: z.string().optional().describe('Optional note/reason for the move'),
        }),
      },
    ),

    tool(
      async (input: { taskId: string; assignee: string; newSession?: boolean }) => {
        const result = await client.assignTask(input.taskId, input.assignee, input.newSession ?? true)
        if (result.ok) return `Task ${input.taskId.slice(0, 8)} assigned to ${input.assignee}.`
        throw new Error('Failed to assign task')
      },
      {
        name: 'pi_board_assign_task',
        description: 'Assign a board task to an agent. The agent receives a mail with the task details.',
        schema: z.object({
          taskId: z.string().describe('Task ID'),
          assignee: z.string().describe('Agent name'),
          newSession: z.boolean().optional().describe('Start a fresh session for the assignee (default true)'),
        }),
      },
    ),

    tool(
      async (input: { taskId: string; text: string }) => {
        const result = await client.commentOnTask(input.taskId, input.text)
        if (result.ok) return `Comment added to task ${input.taskId.slice(0, 8)}.`
        throw new Error('Failed to add comment')
      },
      {
        name: 'pi_board_comment',
        description: 'Add a comment to a board task.',
        schema: z.object({
          taskId: z.string().describe('Task ID'),
          text: z.string().describe('Comment text'),
        }),
      },
    ),
  ]
}
