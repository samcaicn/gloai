import type {
  FederationState,
  MessagePage,
  MailMessage,
  AgentInfo,
  BoardState,
  BoardTask,
  SpawnState,
} from './types.js'

/**
 * HTTP client for the pi-mail federation daemon.
 * Talks to the daemon's REST API (default http://127.0.0.1:1994).
 */
export class PiMailClient {
  constructor(
    private readonly baseUrl: string = 'http://127.0.0.1:1994',
    private readonly fetchImpl: typeof fetch = fetch,
  ) {}

  /** Check if the daemon is reachable. */
  async isAlive(): Promise<boolean> {
    try {
      const res = await this.fetchImpl(`${this.baseUrl}/api/state`, {
        signal: AbortSignal.timeout(2000),
      })
      return res.ok
    } catch {
      return false
    }
  }

  /** Get the full federation state snapshot. */
  async getState(): Promise<FederationState> {
    return this.getJson<FederationState>('/api/state')
  }

  /** List connected agents. */
  async listAgents(): Promise<AgentInfo[]> {
    const state = await this.getState()
    return state.agents
  }

  /** Get paginated message history. */
  async listMessages(opts?: {
    limit?: number
    cursor?: string
    archived?: 'include' | 'exclude' | 'only'
    to?: string
    from?: string
    involves?: string
  }): Promise<MessagePage> {
    const params = new URLSearchParams()
    if (opts?.limit) params.set('limit', String(opts.limit))
    if (opts?.cursor) params.set('cursor', opts.cursor)
    if (opts?.archived) params.set('archived', opts.archived)
    if (opts?.to) params.set('to', opts.to)
    if (opts?.from) params.set('from', opts.from)
    if (opts?.involves) params.set('involves', opts.involves)
    const qs = params.toString()
    return this.getJson<MessagePage>(`/api/messages${qs ? `?${qs}` : ''}`)
  }

  /** Send a message to a specific agent. */
  async sendMail(to: string, subject: string, body: string, newSession?: boolean): Promise<{ ok: boolean; messageId?: string }> {
    return this.postJson('/api/send', { to, subject, body, newSession })
  }

  /** Broadcast a message to all agents. */
  async broadcast(subject: string, body: string): Promise<{ ok: boolean; recipients: number }> {
    return this.postJson('/api/broadcast', { subject, body })
  }

  /** Archive a message in the human inbox. */
  async archiveMessage(id: string): Promise<{ ok: boolean }> {
    return this.postJson('/api/archive', { id })
  }

  /** Get board state (kanban). */
  async getBoard(opts?: {
    location?: 'board' | 'backlog' | 'archive'
    includeArchived?: boolean
    group?: string
  }): Promise<BoardState> {
    const params = new URLSearchParams()
    if (opts?.location) params.set('location', opts.location)
    if (opts?.includeArchived) params.set('includeArchived', 'true')
    if (opts?.group) params.set('group', opts.group)
    const qs = params.toString()
    return this.getJson<BoardState>(`/api/board${qs ? `?${qs}` : ''}`)
  }

  /** Create a new board task. */
  async createTask(req: {
    summary: string
    description?: string
    column?: string
    parent?: string
    inJira?: boolean
    level?: string
    epicId?: string
    backlog?: boolean
  }): Promise<{ ok: boolean; taskId?: string }> {
    return this.postJson('/api/board/create', req)
  }

  /** Move a task to a different column. */
  async moveTask(taskId: string, column: string, note?: string): Promise<{ ok: boolean }> {
    return this.postJson('/api/board/move', { taskId, column, note })
  }

  /** Assign a task to an agent. */
  async assignTask(taskId: string, assignee: string, newSession?: boolean): Promise<{ ok: boolean }> {
    return this.postJson('/api/board/assign', { taskId, assignee, newSession })
  }

  /** Add a comment to a task. */
  async commentOnTask(taskId: string, text: string): Promise<{ ok: boolean }> {
    return this.postJson('/api/board/comment', { taskId, text })
  }

  /** Update a task's summary/description. */
  async updateTask(taskId: string, patch: { summary?: string; description?: string }): Promise<{ ok: boolean }> {
    return this.postJson('/api/board/update', { taskId, ...patch })
  }

  /** Flag/unflag a task. */
  async flagTask(taskId: string, opts?: { reason?: string; clear?: boolean }): Promise<{ ok: boolean }> {
    return this.postJson('/api/board/flag', { taskId, ...opts })
  }

  /** Get spawned sessions. */
  async getSpawnSessions(): Promise<SpawnState> {
    return this.getJson<SpawnState>('/api/spawn')
  }

  /** Spawn a new agent session (via tmux). */
  async spawnAgent(req: { cwd: string; name?: string; model?: string; kickoff?: string }): Promise<{ ok: boolean; name?: string }> {
    return this.postJson('/api/spawn', req)
  }

  /** Stop a spawned agent session. */
  async stopAgent(name: string): Promise<{ ok: boolean }> {
    return this.postJson('/api/spawn/stop', { name })
  }

  /** List subdirectories of a path (for spawn directory picker). */
  async listDirs(path: string): Promise<{ dirs: string[] }> {
    const params = new URLSearchParams({ path })
    return this.getJson<{ dirs: string[] }>(`/api/spawn/ls?${params}`)
  }

  // ── HTTP helpers ──────────────────────────────────────────────────────────

  private async getJson<T>(path: string): Promise<T> {
    const res = await this.fetchImpl(`${this.baseUrl}${path}`, {
      headers: { Accept: 'application/json' },
      signal: AbortSignal.timeout(10_000),
    })
    if (!res.ok) {
      throw new Error(`pi-mail GET ${path} failed: ${res.status} ${res.statusText}`)
    }
    return (await res.json()) as T
  }

  private async postJson<T>(path: string, body: unknown): Promise<T> {
    const res = await this.fetchImpl(`${this.baseUrl}${path}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(10_000),
    })
    if (!res.ok) {
      const text = await res.text().catch(() => '')
      throw new Error(`pi-mail POST ${path} failed: ${res.status} ${text}`)
    }
    return (await res.json()) as T
  }
}
