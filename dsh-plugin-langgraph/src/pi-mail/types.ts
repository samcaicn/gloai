/** TypeScript types for pi-mail federation API */

export interface MailMessage {
  id: string
  fromId: string
  fromName: string
  toId: string
  toName: string
  subject: string
  body: string
  timestamp: number
  read: boolean
  newSession?: boolean
}

export interface AgentInfo {
  agentId: string
  agentName: string
  cwd: string
  status: string
  model: string
  contextSaturation: number
  uptime: number
  alive: boolean
}

export interface FederationState {
  human: { agentId: string; agentName: string }
  agents: AgentInfo[]
  messages: { total: number; unread: number }
  board: BoardState
  spawn: SpawnState
  ceo: { enabled: boolean; intervalMin: number; lastSpawnTs: number }
  now: number
}

export interface BoardColumn {
  id: string
  name: string
  jiraStatus?: string
  instructions?: string
}

export interface BoardTask {
  id: string
  summary: string
  description?: string
  column: string
  assignee?: string
  origin?: 'jira' | 'board'
  jiraKey?: string
  level?: 'epic' | 'story' | 'task' | 'subtask'
  parent?: string
  flagged?: boolean
  flaggedReason?: string
  activity?: Array<{ type: string; text: string; timestamp: number }>
}

export interface BoardState {
  columns: BoardColumn[]
  tasks: BoardTask[]
  jiraConfigured: boolean
  lastSync?: number
  syncError?: string
}

export interface SpawnSession {
  name: string
  cwd: string
  model: string
  alive: boolean
  agentId: string
}

export interface SpawnState {
  sessions: SpawnSession[]
}

export interface MessagePage {
  messages: MailMessage[]
  nextCursor?: string
  hasMore: boolean
  total: number
}

export interface SendMailRequest {
  to: string
  subject: string
  body: string
  newSession?: boolean
}

export interface BroadcastRequest {
  subject: string
  body: string
}

export interface CreateTaskRequest {
  summary: string
  description?: string
  column?: string
  parent?: string
  inJira?: boolean
  level?: 'epic' | 'story' | 'task' | 'subtask'
  epicId?: string
  backlog?: boolean
}

export interface MoveTaskRequest {
  taskId: string
  column: string
  note?: string
}

export interface AssignTaskRequest {
  taskId: string
  assignee: string
  newSession?: boolean
}

export interface CommentRequest {
  taskId: string
  text: string
}
