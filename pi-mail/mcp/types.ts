/**
 * Shared types for the pi-mail board MCP server.
 *
 * These mirror the shapes returned by the mail daemon's HTTP board API
 * (extensions/daemon.mjs `/api/board*`). They are deliberately a minimal
 * subset — enough to render tool output — rather than a full client SDK.
 * The daemon is the source of truth; if it adds fields we don't model
 * here, they are simply ignored.
 */

/** A single kanban column on the board. */
export interface BoardColumn {
  id: string;
  name: string;
  /** Jira status this column maps to (null = board-only column). */
  jiraStatus: string | null;
  /** Column instructions (board-only columns often carry workflow guidance). */
  instructions: string;
}

/** The "unclear" flag on a task (set via board_flag). */
export interface TaskFlag {
  by: string;
  reason: string;
  ts: number;
}

/** One activity-log entry on a task. */
export interface TaskActivity {
  ts: number;
  who: string;
  text: string;
  /** "progress" for board_progress_task updates; otherwise unset (treated as a comment/system event). */
  kind?: string;
}

/** A board task. `id` is the canonical uuid; `key` is the Jira key when origin=jira. */
export interface BoardTask {
  id: string;
  key: string | null;
  origin: "local" | "jira";
  summary: string;
  description: string;
  url: string | null;
  jiraStatus: string | null;
  columnId: string;
  assignee: string | null;
  priority: string | null;
  issueType: string | null;
  parentId: string | null;
  parentKey: string | null;
  flagged: TaskFlag | null;
  updatedAt: number;
  /** ts lower bound for progress entries already folded into the description. */
  progressSince?: number;
  /** ts of the most recent kind:"progress" activity entry. */
  lastProgressTs?: number;
  /** ts of the most recent progress-nudge mail (dedup). */
  lastNudgeTs?: number;
  activity: TaskActivity[];
  /** Where the task sits: "board" (a column), "backlog" (off-board pool), or "archive" (the done board). Older tasks backfill to "board". */
  location?: "board" | "backlog" | "archive";
  /** Issue hierarchy: epic > story > (task|subtask). Backfills to "task" (or "subtask" when it has a parent). */
  level?: "epic" | "story" | "task" | "subtask";
  /** Board id of the epic a story belongs to (local-only). */
  epicId?: string | null;
  /** Project group that owns this task (cwd basename, e.g. "reader"). */
  group?: string | null;
  /** Per-task model override (e.g. "openrouter/deepseek/deepseek-v4-pro"). */
  model?: string | null;
}

/** Response from GET /api/board — the whole board state. */
export interface BoardState {
  columns: BoardColumn[];
  tasks: BoardTask[];
  jiraConfigured: boolean;
  /** Master switch (task 6e6e2ab2): false = Jira disabled, board-only mode. */
  jiraEnabled?: boolean;
  lastSync: number | null;
  syncError: string | null;
  /** The caller's own project group (cwd basename), or null for the operator. */
  myGroup?: string | null;
  /** The group filter actually applied (task b59e930a): "all", a specific
   *  group name, or null (default same-group/operator scoping). */
  group?: string | null;
}

/** Location/archive/group filter for GET /api/board (task 6586b9ca / b59e930a). When omitted
 *  entirely the daemon returns the full board (board + backlog + archive).
 *  Pass { includeArchived: false } for the user-facing default (archive hidden). */
export interface BoardListOpts {
  /** Filter to a location: 'board' | 'backlog' | 'archive'. */
  location?: string;
  /** Include archived tasks (location='archive') in the listing. */
  includeArchived?: boolean;
  /** Scope by project group: 'all' = every project's tasks (cross-group), or a
   *  specific group name. Omit for the default same-group (agent) / all-groups
   *  (human) scoping. */
  group?: string;
}

/** Normalized result from a board mutation HTTP call. */
export interface BoardOpResponse {
  ok: boolean;
  error?: string;
  warning?: string;
  message?: string;
  task?: BoardTask;
  taskId?: string;
  key?: string;
}

/** Shape of the create-task body (mirrors POST /api/board/create). */
export interface CreateTaskBody {
  summary: string;
  description?: string;
  column?: string;
  parent?: string;
  inJira?: boolean;
  /** Issue hierarchy: "epic" | "story" | "task" | "subtask". */
  level?: string;
  /** Board id/prefix of the epic a story belongs to. */
  epicId?: string;
  /** Create in the Backlog pool (off-board, local-only) instead of a column. */
  backlog?: boolean;
  /** Project group name for the task (omit for ungrouped/current behavior). */
  group?: string;
  /** Per-task model override (e.g. "openrouter/deepseek/deepseek-v4-pro"). */
  model?: string;
}

/** Shape of the update-task body (mirrors POST /api/board/update). */
export interface UpdateTaskBody {
  summary?: string;
  description?: string;
  /** Project group for the task (empty string to clear, omit to leave unchanged). */
  group?: string;
  /** Per-task model override (empty string to clear, omit to leave unchanged). */
  model?: string;
}

// ── MCP project chat ─────────────────────────────────────────────────────────

/** A single message in a chat thread's history. */
export interface ChatMessage {
  id: string;
  /** "question" (human→agent) or "reply" (agent→human). */
  direction: "question" | "reply";
  from: string;
  to: string;
  subject: string;
  body: string;
  timestamp: number;
}

/** Body for chat_post (mirrors POST /api/chat/post). */
export interface ChatPostBody {
  /** Project working directory to chat with. */
  cwd: string;
  /** The question / message to send. */
  message: string;
  /** Existing thread id to continue a multi-turn chat; omit to start a new thread. */
  threadId?: string;
  /** When true (default), block until the agent replies and return the answer. */
  wait?: boolean;
  /** Per-request wait timeout in ms (default 120000). */
  timeoutMs?: number;
}

/** Body for chat_get (mirrors POST /api/chat/get). */
export interface ChatGetBody {
  /** Thread id whose history to fetch. */
  threadId: string;
  /** Per-request wait timeout in ms (default 120000). */
  timeoutMs?: number;
}

/** Result from chat_post / chat_get (mirrors the daemon's response). */
export interface ChatResult {
  ok?: boolean;
  error?: string;
  threadId?: string;
  answer?: string;
  history?: ChatMessage[];
  /** True when the latest message in the thread is a reply from the agent. */
  answered?: boolean;
}

/** Result from POST /api/board/sync (the on-demand "fetch from Jira").
 *  `columns` reports the non-destructive column-mapping refresh: statuses
 *  added as new mapped columns, and same-named board-only columns promoted. */
export interface SyncResult {
  ok: boolean;
  error?: string;
  /** The column-mapping refresh summary, or null when columns weren't refreshed
   *  (e.g. the 60s issue-only interval, or Jira off). */
  columns?: {
    ok: boolean;
    reason?: string;
    source?: string;
    added?: string[];
    promoted?: string[];
  } | null;
}

/**
 * The backend the MCP server talks to. The default implementation
 * (`http.ts` → `httpBackend`) is a thin HTTP client over the daemon's
 * `/api/board*` + `/api/chat/*` endpoints. When the MCP server is hosted *inside* the
 * daemon (the daemon serves `/mcp` directly), the daemon supplies an
 * in-process backend whose methods call the board functions without an
 * HTTP round-trip. The method shapes mirror the daemon's HTTP response
 * bodies so the tool formatters work unchanged either way.
 */
export interface BoardBackend {
  getBoard(opts?: BoardListOpts): Promise<BoardState>;
  getBoardConfig(): Promise<unknown>;
  setBoardConfig(config: Record<string, unknown>): Promise<unknown>;
  syncBoard(): Promise<SyncResult>;
  moveTask(taskId: string, column: string, note?: string): Promise<BoardOpResponse>;
  commentTask(taskId: string, text: string): Promise<BoardOpResponse>;
  progressTask(taskId: string, text: string): Promise<BoardOpResponse>;
  assignTask(taskId: string, assignee: string, newSession?: boolean): Promise<BoardOpResponse>;
  createTask(body: CreateTaskBody): Promise<BoardOpResponse>;
  updateTask(taskId: string, body: UpdateTaskBody): Promise<BoardOpResponse>;
  flagTask(taskId: string, reason?: string, clear?: boolean): Promise<BoardOpResponse>;
  // ── Projects (favorites + spawn history) ─────────────────────────────────
  listProjects(): Promise<{ favorites: { cwd: string; alive: boolean }[]; history: { cwd: string; lastSpawnedAt: number; count: number; lastName: string; alive: boolean }[] }>;
  // ── MCP project chat ────────────────────────────────────────────────────
  chatPost(body: ChatPostBody): Promise<ChatResult>;
  chatGet(body: ChatGetBody): Promise<ChatResult>;
}
