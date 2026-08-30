# pi-mail — federated agent mail

A `pi` extension that lets multiple pi agent processes communicate via a
shared mailbox daemon. Peer-to-peer federation — no central authority required.

## Architecture

```
  pi (agent-a)        pi (agent-b)        pi (agent-c)
      │                   │                   │
      └──────┬────────────┴──────┬────────────┘
             │                  │
       [mail-daemon]  ←  singleton process
       ~/.pi/agent/mail-daemon.sock
```

- **Daemon** (`daemon.mjs`) — auto-started when the first pi process loads the
  extension. Singleton (Unix socket). Manages the agent registry and all
  mailboxes. Survives individual agent restarts.
- **Extension** (`index.ts`) — loaded by every pi process. Registers on
  `session_start`, unregisters on `session_shutdown`.
- **Heartbeat** — daemon pings every 5 s; no pong = agent removed from registry.
  Mailbox is preserved for reconnect. Only a clean exit (`unregister`) clears it.
- **Buffering** — outgoing messages are buffered when the socket is temporarily
  unavailable and flushed automatically on reconnect.
- **Web UI** — the daemon also serves an HTTP console (default `0.0.0.0:1994`) so
  a human operator can browse the federation, read per-agent mail history, and
  send or broadcast mail as a first-class `human` agent. See [Web UI](#web-ui).

## Web UI

The daemon hosts a dependency-free single-page web console alongside the Unix
socket. Open it in a browser:

```
http://localhost:1994
```

### Configuration

| Env var | Default | Description |
|---------|---------|-------------|
| `PI_MAIL_UI_PORT` | `1994` | TCP port for the web UI |
| `PI_MAIL_UI_HOST` | `0.0.0.0` | Bind address (use `127.0.0.1` to restrict to localhost) |
| `PI_MAIL_MM_TICK_MS` | `60000` | How often the middle-manager scheduler + reaper wake up to check |

The UI starts with the daemon and is non-fatal if the port is taken — the mail
federation keeps working regardless. Restart the daemon to apply changes:

```
/restart-mail-daemon
```

### The `human` agent

The UI acts as a fixed virtual agent named `human` (well-known id
`00000000-0000-0000-0000-000000000000`). It has no live socket of its own —
its inbox is the slice of the persisted message history addressed to it.

- The human appears in `mail_list_agents`, so agents can discover it and reply
  to your messages by sending to `human`.
- Broadcasts are copied to the human's inbox so the operator sees everything.
- Mail you send through the UI is delivered to agents exactly like agent-to-agent
  mail (including the `newSession` flag, which starts a fresh session on the
  recipient).

### How agents reply: mail channel vs direct TUI

Agents can tell whether the task they're working on arrived via mail or
through the TUI you're driving directly:

- **Mail-driven task** (a `📬 Mail` message from `human` or another agent is in
  the agent's context): the agent replies via `mail_send` to the sender when
  done, and asks questions via `mail_send` instead of `ask_user_question` —
  because no one is at the TUI to answer a prompt. Your reply lands back in the
  web UI inbox.
- **Direct TUI task** (you're typing to the agent in the terminal): the agent
  responds in place and does **not** send mail for that task; `ask_user_question`
  works as normal.

The extension signals this to the agent each turn via a `## Current task
channel:` header in the system prompt (`mail` or `direct (TUI)`), and the
mail-orchestrator skill documents the same rule. So when you dispatch a task
from the web UI, you can expect the result back as mail — and when you're
pairing in the terminal, the agent stays in the terminal.

### Views

1. **Agents** — live table of every connected agent: name, project (cwd),
   status, context saturation, model, uptime, id. Auto-refreshes every 3 s.
2. **Board** — kanban task board, optionally two-way synced with your current
   Jira sprint. See [Task board](#task-board).
3. **Mailbox** — an Outlook-style three-pane view: a folder/navigation
   pane (All mail · Inbox · Sent · Archive), a conversation list (grouped per
   correspondent, with inter-agent threads togglable), and a reading pane
   showing the selected thread with reply/archive actions and a compose form.
   Messages load incrementally via infinite scroll; the 3 s poll prepends new
   mail without resetting your scroll position.
4. **History** — pick any agent and see the full history of mail delivered to
   it (direct + broadcast, including archived messages).

### Persistence

The full message history (the UI's source of truth) is persisted to
`~/.pi/agent/mail-daemon.history.json` and survives daemon restarts. Live agent
mailboxes remain in-memory with their existing reclaim-on-reconnect semantics.

### HTTP API

The SPA talks to a tiny JSON API you can also call directly:

| Method & path | Body | Returns |
|---------------|------|---------|
| `GET /api/state` | — | `{ human, agents[], messages{total,unread}, board, spawn, now }` — lean snapshot (no full message dump; board excludes archive) |
| `GET /api/messages` | — | Paginated message history (newest-first): `?limit=&cursor=&archived=include\|exclude\|only&to=&from=&involves=` → `{ messages[], nextCursor, hasMore, total }` |
| `POST /api/send` | `{ to, subject, body, newSession? }` | `{ ok, messageId? \| error? }` |
| `POST /api/broadcast` | `{ subject, body }` | `{ ok, recipients }` |
| `POST /api/archive` | `{ id }` | `{ ok }` — archives a message in the human inbox |
| `GET /api/board` | — | Board snapshot: `{ columns[], tasks[], jiraConfigured, lastSync, syncError }`. Query: `?location=board\|backlog\|archive&includeArchived=true&group=all\|<name>` (archive hidden by default) |
| `POST /api/board/move` | `{ taskId, column, note? }` | Move a task to a column, or to `backlog`/`archive` (off-board; local-only). Jira transition if the column is mapped |
| `POST /api/board/assign` | `{ taskId, assignee, newSession? }` | Assign a task; the assignee is mailed the task package. For Jira-synced tasks, also pushes the assignee change to the Jira issue (when push is enabled) |
| `POST /api/board/comment` | `{ taskId, text }` | Comment (also posted to Jira for Jira tasks) |
| `POST /api/board/progress` | `{ taskId, text }` | Post an internal progress update (folded into the description on move; not posted to Jira) |
| `POST /api/board/create` | `{ summary, description?, column?, parent?, inJira?, level?, epicId?, backlog? }` | Create a task (subtask under `parent`; Jira issue when parent is Jira or `inJira`; `backlog:true` creates in the Backlog pool; `level` sets epic/story/task/subtask) |
| `POST /api/board/update` | `{ taskId, summary?, description? }` | Edit summary/description (pushed to Jira for Jira tasks) |
| `POST /api/board/flag` | `{ taskId, reason?, clear? }` | Flag a task as ⚠ unclear (or clear the flag) |
| `GET/POST /api/board/config` | `{ config?, columns? }` | Read/update Jira connection + column layout. `config.jiraEnabled` toggles Jira off entirely (board-only mode) |
| `POST /api/board/sync` | — | Fetch from Jira now — pull remote issue state AND refresh the board's column↔status mapping. Returns `{ ok, error?, columns: { added, promoted, source } | null }` |
| `GET /api/mm` | — | Middle-manager state: config + active MM sessions |
| `GET /api/ceo` | — | CEO state: config + active CEO sessions |
| `GET /api/spawn` | — | Spawned sessions: name, cwd, model, alive, agentId |
| `POST /api/spawn` | `{ cwd, name?, model?, kickoff? }` | Spawn a fresh agent (tmux); returns `{ name }` |
| `POST /api/spawn/stop` | `{ name }` | Stop a daemon-spawned agent |
| `GET /api/spawn/ls?path=` | — | List subdirectories of any directory |
| `GET /api/spawn/terminal?name=` | (WebSocket upgrade) | Live PTY stream of the spawned tmux session (raw bytes both ways) |

## Task board

The daemon hosts a shared kanban board for the whole federation, with optional
**two-way Jira sync** for your current sprint. In the web UI you can **drag a
task card between columns**; dragging toward an edge of the board auto-scrolls
the board (and the page) so columns that are off-screen stay reachable drop
targets, and each card's move dropdown lists columns + the off-board pools.
Beyond the kanban columns there are two off-board pools — **Backlog** and
**Archive** — both local-only (never pushed to Jira).

- **Pull**: every 60 s the daemon runs the configured JQL (default
  `assignee = currentUser() AND sprint in openSprints()`) and mirrors those
  issues as board tasks — including their **subtasks** (fetched via
  `parent in (…)` even when the subtasks don't match the JQL) and **Jira
  comments** (merged into the task's activity log, deduped). Remote status
  changes move the cards; issues that leave the sprint disappear from the
  board (except board-created ones, which are pinned). On an explicit
  **fetch from Jira** (the UI's *Fetch from Jira* button, `POST
  /api/board/sync`, or the `sync_board` MCP tool) the pull *also* refreshes
  the board's **column↔status mapping** from the remote project's columns —
  see [Fetching columns from Jira](#fetching-columns-from-jira). The 60 s
  interval pulls issues only (columns change rarely).
- **Push**: moving a task into a column that maps to a Jira status performs the
  matching Jira transition. Board comments on Jira tasks are posted to the
  issue. Board assignments on Jira tasks update the Jira assignee.
  Summary/description edits are pushed to the issue. Agents can
  **subdivide** a Jira task (`board_split_task`) — subtasks are created as real
  Jira sub-tasks under the parent; top-level issues can be created with
  `inJira: true` (uses the configured project key).
  Push can be disabled independently via `pushEnabled: false` in board config
  (default `true`) — pull sync continues to run, but transitions, comments,
  assignments, and description updates stay local. Push failures are logged
  and surfaced as warnings but never block board operations.

Configure Jira in the UI (Board → ⚙ Settings): base URL
(`https://yourorg.atlassian.net`), account email, an
[API token](https://id.atlassian.com/manage-profile/security/api-tokens), and
the JQL. Env vars `JIRA_BASE_URL`, `JIRA_EMAIL`, `JIRA_API_TOKEN`, `JIRA_JQL`
serve as defaults. Without Jira the board still works in board-only mode.
State persists in `~/.pi/agent/mail-board.json`.

#### Disabling Jira integration

If you don't use Jira, turn it off entirely with the **Enable Jira sync**
switch in Board → ⚙ Settings (or set `jiraEnabled: false` via the
`POST /api/board/config` config object / `set_board_config` MCP tool). It
defaults to **on**, so existing setups keep syncing until you opt out.

You can also **disable push only** by setting `pushEnabled: false` while
leaving `jiraEnabled: true`. Pull sync (Jira → board) keeps running, but
board→Jira push — transitions, comments, assignments, and description
updates — is suppressed. This is useful when you want to read from Jira
without writing back.

With Jira disabled the board runs in **board-only mode** (the same mode
applies whenever Jira is not configured — i.e. no credentials set — so an
unconfigured board never surfaces stale Jira ticket references either):

- **No Jira network calls** — the periodic sync, startup sync, transitions on
  move, comment mirroring, and issue creation all short-circuit (credentials
  are kept, so flipping the switch back on resumes sync with the stored
  creds).
- **Zero Jira references in board output** — `board_list_tasks`,
  `board_get_task`, the web UI, and every board request hide Jira keys,
  statuses, URLs, and origin badges — including the per-column `(jira: …)`
  mapping annotations, which render as `(board-only)`. Already-synced tasks
  are displayed as local cards; their stored Jira data is preserved and
  restored the moment
  you re-enable Jira.

### Columns — including ones Jira doesn't have

Columns are fully editable in the UI. Each column either **maps to a Jira
status** (`To Do`, `In Progress`, `Done`, …) or is **board-only** with custom
**instructions** — e.g. the default `Refine` and `Review` columns. A task in a
board-only column keeps its Jira status untouched; the instructions are mailed
to the assignee whenever a task is assigned or moved there, which is what makes
"drag it to Refine" an actionable request for an agent.

### Fetching columns from Jira

An on-demand **fetch from Jira** (the board's *Fetch from Jira* button,
`POST /api/board/sync`, or the `sync_board` MCP tool) pulls the remote
project's board columns and reconciles the board's **column↔status mapping**
so it reflects what Jira has — without clobbering your local layout. The merge
is **non-destructive**:

- A remote status that no local column maps to → a new Jira-mapped column is
  **added** (inserted after the last existing Jira-mapped column so mapped
  columns stay clustered). Reorder/edit it in Board → Settings.
- A remote status whose name matches an existing **board-only** column → that
  column is **promoted** to Jira-mapped (its id/name/instructions are kept;
  only its `jiraStatus` is set).
- A status already mapped (case-insensitive) → no-op.
- Your columns, board-only columns, and instructions are **never removed**.

It prefers the agile board configuration (the statuses actually on the
project's board columns) and falls back to the project's full status list when
the agile API is unavailable or no board is configured. The 60 s automatic
sync pulls **issues only** (columns change rarely); the explicit fetch pulls
issues **and** columns. The fetch makes **no Jira network calls** when Jira
is disabled or unconfigured (board-only mode).

### Assignment = mail

Assigning a task (UI dropdown or `board_assign_task`) mails the assignee the
full task package: description, column instructions, and the board-tool crib
sheet. Moving someone else's task notifies them the same way. The "fresh
session on assign" checkbox (default on) dispatches with `newSession: true`.

### Backlog, Archive & issue hierarchy

On top of the kanban columns there are two **off-board locations** (both
local-only — never pushed to Jira). For Jira-origin tasks these placements
only stick while the remote Jira status is unchanged: the moment Jira
reports a new status, the task is pulled back onto the board into the
mapped column (Jira is the source of truth). Board-only tasks are never
moved automatically.

- **Backlog** — a shared pool of items not yet placed on a column. Add items
  from the UI (the "backlog" checkbox on the new-task row), via
  `board_create_task` with `backlog: true`, or (via MCP) by creating with
  `backlog:true`. Place a backlog item onto a board by moving it to a column
  (the card's move dropdown, `board_move_task`, or `/api/board/move`).
- **Archive** — the "done board". Moving a task to Archive removes it from its
column (including Done) while keeping the record queryable and restorable.
  Archive is a **filter**, not an assignment: archived tasks are hidden by
  default and revealed by the "show done (archive)" checkbox on the board
  toolbar (or `includeArchived:true` / `location:"archive"` on
  `board_list_tasks`). Restore by moving the card back to a column.

To move a task to either location, use the column value `"backlog"` or
`"archive"` in `board_move_task` / `/api/board/move` (the UI move dropdowns
list them too).

Tasks also carry a **level** — `epic | story | task | subtask` (default
`task`, or `subtask` when created under a `parent`). Set it at create time via
the UI level picker or `board_create_task`'s `level` param. A story may
reference its epic by board id via `epicId`. Levels are a local hierarchy
layer for grouping/display; the real Jira issue type stays on `issueType`.

### Clarity gate

Every assignment mail tells the agent to first check the task is clear (goal,
scope, acceptance criteria) and to **ask instead of guess**: post questions as
a comment, `board_flag_task` the card (red ⚠ unclear badge in the UI + a mail
to you), and wait. Once resolved, the refined spec goes into the description
via `board_update_task` (pushed to Jira) and the flag is cleared. The UI has
Flag/Clear-flag buttons on each card.

Agents work tasks with the `board_*` tools (below); the `task-board` skill
teaches them the workflow, and the `mail-orchestrator` skill tells
orchestrators to dispatch via `board_assign_task` for board work.

### Progress updates & task detail view

Two activity kinds keep work-in-progress noise out of Jira while still
forwarding context to the next agent:

- **`board_comment_task`** — a decision/answer that belongs on the record;
  posted to the Jira issue for Jira tasks.
- **`board_progress_task`** — a work-in-progress note (what's done, what's
  blocking). Internal: never becomes a Jira comment. When the task is **moved
  to the next column**, recent progress entries are folded into a
  `## Progress so far (→ <column>, <time>)` block appended to the description
  — and for Jira tasks that folded description is pushed to the issue. So the
  next agent inherits a snapshot without Jira comment spam.

The web UI's card detail is a **modal**: click a card (or its *Details*
button) to open a full view — description (incl. any folded progress block),
the **complete activity timeline** (progress entries marked distinctly),
subtasks, column instructions, and actions (comment, add progress, move,
assign, flag/clear, +subtask). It re-renders every 3 s poll, so it stays live.

A **daemon nudge** mails in-progress assignees who haven't posted progress in
a while (default 60 min; one reminder per gap). The operator can tune or
disable it in Board → Settings (`nudgeEnabled`, `nudgeIntervalMin`).

## Middle manager

The **middle manager** (MM) is an ephemeral agent the daemon spawns on a
schedule (default every 30 min, when enabled) to keep the board moving
without an operator babysitting it. On each cycle one MM reviews the board
for the **favorited** (managed) projects, unblocks stuck workers, shepherds
finished tasks into Done/Archive, and curates the favorites list — then mails
`human` a completion summary and exits. Its tmux session is reaped
automatically.

- **Managed projects = favorites.** Star a project dir
  (`mail_set_project_favorite`, the board UI spawn picker, or `favorite:true`
  on `mail_spawn_agent`) to add it to the MM's roster. The MM may add/remove
  favorites itself to curate its roster over time.
- **All-groups visibility.** The MM sees every project group's tasks (like the
  human), then focuses on its managed projects' tasks.
- **Escalation = the board.** Workers surface blockers by updating the board
  (a comment, a progress post, or flagging the task unclear) — they don't need
  to mail the human or the MM directly. The MM re-reads each task's activity on
  every cycle, so that's how blockers reach it. It resolves what it can and
  only mails the human for blockers it can't resolve.
- **Lifecycle.** Spawned fresh each cycle, then self-deletes on completion
  (calls `mail_stop_self`). A reaper stops any MM session whose tmux session
  has already ended, and forcibly stops any exceeding `mmMaxLifetimeMin`
  (default 15) so dead/stuck MM sessions never accumulate. No overlap: a new
  cycle is skipped while an MM is still alive. The MM tick also runs the
  **worker reaper** (see Ephemerality) so hung workers are reaped on every
  cycle.
- **Config** (Board → Settings, or `set_board_config`): `mmEnabled` (default
  `false`), `mmIntervalMin` (default `30`), `mmModel` (optional),
  `mmMaxLifetimeMin` (default `15`), `workerMaxLifetimeMin` (default `30`, the
  worker reaper safety bound — see Ephemerality). Disabled by default; no spawn
  when the favorites list is empty. A `GET /api/mm` endpoint exposes the live
  state (config + active sessions).
- **Force a cycle now.** A diagnostic `mm_tick` socket RPC (with `force: true`)
  runs a cycle immediately, bypassing the interval gate.

The MM workflow is documented in the bundled `middle-manager` skill, which is
loaded into the spawned agent's context.

## CEO (top-tier manager)

The **CEO** is a top-tier ephemeral manager above the MM — a 3-level hierarchy
**CEO (scheduled) → middle managers (spawned by CEO) → workers**. When
`ceoEnabled` is true, the CEO **replaces the daemon's fixed-interval MM
loop**: the CEO becomes the sole spawner of middle managers (the daemon's own
MM timer skips spawning while `ceoEnabled`), and the MM reaper still runs as a
safety net. With `ceoEnabled` false, the existing MM loop works unchanged
(backward-compat).

The CEO is a pure manager and does **no task administration** (no
moving/unblocking/archiving tasks — that's the managers' job). Each pass it
reviews the federation at a high level, decides which managed (favorited)
projects need an MM pass, spawns MMs for them (one at a time, respecting the
no-overlap guard), optionally tunes the favorites list, mails `human` a
summary, and self-deletes via `mail_stop_self`.

- **Config** (Board → Settings, or `set_board_config`): `ceoEnabled` (default
  `false`), `ceoIntervalMin` (default `120`), `ceoModel` (optional),
  `ceoMaxLifetimeMin` (default `15` — the CEO is a ~15-minute management
  thread). Disabled by default; no spawn when the favorites list is empty.
  `GET /api/ceo` exposes the live state (config + active sessions).
- **Spawn an MM from the CEO.** `mail_spawn_agent({ cwd, mm: true })` spawns an
  MM-marked session that runs the MM pass and self-deletes.
- The CEO workflow is documented in the bundled `ceo` skill.

## Self-deleting sessions (`mail_stop_self`)

A daemon-spawned agent can tear down its **own** session + spawn-registry
entry when its work is fully done, via the `mail_stop_self` tool. Workers,
middle managers, CEOs, and any other daemon-spawned agent may call it; the
daemon removes the registry entry immediately and kills the tmux session after
a short grace so the tool response + any final mail flush first. **Refuses
operator-launched interactive agents** (not in the spawn registry) — they stay
alive unless explicitly stopped via the UI / `mail_stop_agent`.

- The `task-board` skill instructs a board-dispatched worker to call it after
  finishing its assigned task; the `middle-manager` and `ceo` skills call it
  after their pass + completion summary.
- Use `mail_stop_agent` (orchestrator-initiated) to tear down a worker that
  went silent without self-exiting.

## Ephemerality — every spawned agent is killed after its pass

The 3-tier hierarchy — **CEO (scheduled) → middle managers (spawned by CEO) →
workers** — is **ephemeral by invariant**: every daemon-spawned session is
killed after its pass, regardless of whether it self-exits. Self-exit
(`mail_stop_self`) is the primary, clean path; the **reaper is the enforced
backstop**, not the primary path. If an agent doesn't self-exit — it hangs,
crashes, gets stuck in a long turn, or simply forgets — the daemon force-kills
its session at its tier's max-lifetime boundary and removes the spawn-registry
entry. No daemon-spawned session in this hierarchy can outlive its pass.

- **Per-tier lifetimes (Board → Settings, or `set_board_config`):**
  `ceoMaxLifetimeMin` (default `15` — the CEO is a ~15-minute management
  thread), `mmMaxLifetimeMin` (default `15`), `workerMaxLifetimeMin` (default
  `30`; workers often run longer than a management pass).
- **Liveness signal = the tmux session (the agent process) being alive** — via
  `tmux has-session`. The reaper does NOT depend on the agent being responsive,
  so an agent that is **alive but stuck** (in a long turn, not calling
  `mail_stop_self`) is still caught: its tmux session is alive, so the reaper
  takes the over-lifetime branch and force-kills it at its boundary.
- **Cascade cleanup is independent per tier.** A reaped parent can never leave
  orphans: each tier has its own reaper (`reapCeos`, `reapMiddleManagers`,
  `reapWorkers`) and each daemon-spawned session is reaped by exactly one tier's
  reaper regardless of who spawned it. When a CEO is reaped mid-pass, the
  MM/worker it spawned are not tracked as its children — they're reaped on their
  own lifetimes by the MM and worker reapers. No parent/child bookkeeping, no
  leaked sessions.
- The reapers run on every scheduler tick (default every 60 s). The MM tick runs
  the worker + MM reapers every tick even when the CEO is the sole MM spawner,
  so all three tiers are reaped either way.

## Board MCP server

The MCP server is hosted **inside the pi-mail daemon** itself — it serves
`/mcp` (GET / POST / DELETE) on the daemon's existing HTTP UI port (default
`1994`) over the **Streamable HTTP** transport, backed by an **in-process**
board backend that calls the daemon's board functions directly. No separate process, no HTTP
loopback: the daemon, the web UI, the WebSocket terminal, and the MCP server
all live in one. A `--stdio` bridge (`mcp/build/index.js`) remains for MCP
clients that prefer to spawn the server as a subprocess.

It is a thin shim over the daemon's board logic — every tool maps one-to-one
onto a board operation, so all Jira sync, column resolution, and assignment
notifications stay in the daemon. The tool names and parameter shapes mirror
the in-pi `board_*` agent tools, so an MCP client drives the board the same
way an agent does. Board operations run as the `human` agent
(`HUMAN_AGENT_ID`, same as the web UI).

### Tools

| MCP tool | Board operation |
|---|---|
| `board_list_tasks({ mine?, location?, level?, includeArchived? })` | list the board by location/column (Backlog, columns, Archive) |
| `board_get_task({ taskId })` | full task detail + activity (id prefix or Jira key) |
| `board_move_task({ taskId, column, note? })` | move to a column or `backlog`/`archive` (Jira-mapped ⇒ Jira transition; backlog/archive are local-only) |
| `board_comment_task({ taskId, text })` | add activity comment (⇒ Jira comment for jira tasks) |
| `board_progress_task({ taskId, text })` | post internal progress note (folded into the description on move; not posted to Jira) |
| `board_assign_task({ taskId, assignee, newSession? })` | assign + mail the assignee |
| `board_create_task({ summary, description?, column?, parent?, inJira?, level?, epicId?, backlog? })` | create task / subtask (level=epic\|story\|task\|subtask; backlog=true ⇒ Backlog pool) |
| `board_split_task({ taskId, subtasks: [{ summary, description? }] })` | subdivide (Jira sub-tasks under a Jira parent) |
| `board_update_task({ taskId, summary?, description? })` | edit summary/description (pushed to Jira) |
| `board_flag_task({ taskId, reason?, clear? })` | mark/clear "unclear" (notifies the operator) |
| `get_board_config` / `set_board_config({ config })` | read/write board + Jira config |
| `sync_board` | fetch from Jira now — pull remote issue state AND refresh the board's column↔status mapping (non-destructive) |

#### Project chat tools

The MCP server also exposes **project chat** tools that let an MCP client hold
a multi-turn conversation with a project's spawned agent. All traffic flows
over pi-mail: `chat_post` spawns (or reuses) a "chat worker" agent in the
target project cwd, delivers the question as mail, and (by default) blocks
until the agent replies; `chat_get` fetches a thread's mail history, blocking
non-busily until the agent has answered. Chat workers are auto-killed after a
configurable idle timeout (`chatIdleMin`, default 60 min) with no
communication.

| MCP tool | Description |
|---|---|
| `chat_post({ cwd, message, thread_id?, wait?, timeout_ms? })` | Send a question to a project's agent. No `thread_id` ⇒ starts a new thread (spawns a chat worker) and returns `thread_id`. Existing `thread_id` ⇒ continues the conversation. `wait:true` (default) blocks and returns the answer; `wait:false` returns the `thread_id` immediately (fetch the answer later with `chat_get`). |
| `chat_get({ thread_id, timeout_ms? })` | Get a chat thread's mail history (oldest-first). Blocks until the LAST message in the thread is a reply from the agent — no polling. |

Both tools are also reachable as daemon socket RPCs (`chat_post` / `chat_get` /
`chat_state`) and HTTP endpoints (`POST /api/chat/post`, `POST /api/chat/get`,
`GET /api/chat`).

### Build & run

The HTTP MCP server needs no separate start — it comes up with the daemon.
Just build the TypeScript so the daemon can import it:

```bash
npm install
npm run build:mcp                 # tsc → mcp/build/ (daemon imports mcp/build/board-mcp.js)
```

The daemon serves `/mcp` on its UI port (`PI_MAIL_UI_HOST`/
`PI_MAIL_UI_PORT`, default `0.0.0.0:1994`). The server is **stateless** — a
fresh `McpServer` + transport per request, no session id — but handles the
full Streamable HTTP method surface: `POST` carries JSON-RPC, `GET` opens a
standalone SSE stream (`406` unless the client sends `Accept: text/event-stream`),
and `DELETE`/other methods get a `405` with `Allow: GET, POST, DELETE`. POST/
DELETE dispatch is delegated to the SDK transport. The GET stream is served
directly by the daemon as a keep-alive: it emits an SSE comment (`: keepalive`)
immediately on open and then every 15s, because the SDK's stateless GET
handler emits nothing and the board server pushes no notifications — without
an initial byte, clients that wait for the first SSE event (e.g. `bundle-mcp`,
with a 30s connect timeout) hang. SSE comment lines are explicitly endorsed as
keep-alives by the Streamable HTTP spec. Stateful sessions and server-initiated
push are not supported. The SDK + compiled `mcp/build/board-mcp.js` are imported
lazily, so the daemon still runs if the MCP build or its npm deps are absent
(`/mcp` answers `503` for POST/DELETE in that case; GET keep-alive still serves).

The stdio bridge (`mcp/build/index.js --stdio`) talks to the daemon over its
HTTP `/api/board*` endpoints; its daemon address is read from env, defaulting
to `http://127.0.0.1:1994`:

| Env var | Default | Description |
|---|---|---|
| `PI_MAIL_BASE_URL` | — | Daemon URL (stdio bridge); overrides the host/port below |
| `PI_MAIL_UI_HOST` | `127.0.0.1` | Daemon host (stdio bridge; ignored if `PI_MAIL_BASE_URL` is set) |
| `PI_MAIL_UI_PORT` | `1994` | Daemon port (stdio bridge; ignored if `PI_MAIL_BASE_URL` is set) |

(`PI_MAIL_MCP_HOST`/`PI_MAIL_MCP_PORT` are no longer used — the HTTP server
now lives in the daemon.)

### Claude Desktop / remote MCP config

Point the client at the daemon's HTTP endpoint (no subprocess spawn needed):

```jsonc
{
  "mcpServers": {
    "pi-mail-board": {
      "url": "http://127.0.0.1:1994/mcp"
    }
  }
}
```

For a local subprocess that prefers stdio, use the `--stdio` bridge instead:

```jsonc
{
  "mcpServers": {
    "pi-mail-board": {
      "command": "node",
      "args": ["/abs/path/to/pi-mail/mcp/build/index.js", "--stdio"],
      "env": { "PI_MAIL_BASE_URL": "http://127.0.0.1:1994" }
    }
  }
}
```

Or, once published, via npx: `"command": "npx", "args": ["-y", "pi-mail-board-mcp", "--stdio"]`.

## Spawning agents

The daemon can bring up a brand-new, long-running pi agent process in a
chosen working directory — so you (via the board UI) and orchestrators (via
the `mail_spawn_agent` tool) can spin up fresh workers without opening a
terminal. Each spawned agent runs in its own detached **tmux** session, which
gives it a PTY (interactive `pi` works unmodified), is attachable
(`tmux attach -t <name>`), and survives daemon restarts.

- **From the board UI:** the **➕ Spawn agent** button opens a directory
  picker that can browse the whole filesystem (starts at `/`, with
  up-to-parent navigation; you can also type any absolute path). Optionally set
  a name, model, and a kickoff prompt; the new agent appears in the Agents table
  within a few seconds and is assignable from board cards like any other
  agent.
- **From an orchestrator:** `mail_spawn_agent({ cwd, name?, model?, kickoff? })`
  returns the new agent's name; then `board_assign_task` /
  `mail_send newSession:true` gives it work. `mail_stop_agent({ name })` tears
  it down.
- **Web terminal:** the Agents view has a **Terminal** button on spawned
  agents that opens a live xterm.js terminal over a WebSocket (`script -qec
  'tmux attach'` PTY bridge) — real stdin/stdout forwarding of the pi TUI.
  **Stop** kills only daemon-spawned sessions; an operator-launched agent is
  never touched.

The set of daemon-spawned sessions is persisted (`~/.pi/agent/mail-spawn.json`)
and reconciled against live tmux on each daemon start, so a
`/restart-mail-daemon` keeps tracking (and can still stop) previously-spawned
agents.

**Session ↔ agent name linkage.** The daemon names the tmux session and
launches pi with `pi -n <session>`. The extension adopts that `-n` value as
its mail federation display name (unless you've set a custom name with
`mail_set_name`), so the registered agent name **matches** the tmux session
name. That match is what lets the daemon link the registered `agentId` back to
the tmux session, deliver the kickoff prompt, and show Terminal/Stop buttons on
the right agent in the UI. (The internal `agentId` is a stable per-process UUID
— distinct from the human-readable session name — and is tracked separately in
the spawn registry.)

### Recent projects + favorites

The daemon also remembers the project directories you spawn into, so you don't
have to browse the filesystem every time. Two lists are tracked, shared across
the federation and persisted in the same `mail-spawn.json`:

- **Recent projects (history):** every dir you spawn an agent into is recorded
  (deduped, newest-first, with a spawn count + last session name), capped at 50.
- **Favorites:** dirs you've starred show at the top.

Each entry is tagged with whether a spawned agent is currently running in it.

- **`mail_list_projects`** — lists favorites + recent project dirs (use it to
  pick a `cwd` for `mail_spawn_agent`).
- **`mail_set_project_favorite({ cwd, favorite })`** — star/unstar a dir.
- `mail_spawn_agent` also takes an optional `favorite: true` to star the dir at
  spawn time.
- In the **board UI spawn picker**, recent/favorite project chips appear at the
  top (favorites starred, a 🟢 dot when a live agent is running in that dir);
  a **☆ favorite / ★ favorited** toggle next to the path crumbs stars the
  current dir.

## Setup

This is a pi package that bundles the extension **and** the `mail-orchestrator`
skill. Install it like any other package:

```bash
pi install git:github.com/tanevanwifferen/pi-mail    # from GitHub
pi install ./pi-mail                                 # from a local clone
```

Installing registers the mail extension (tools + commands) and makes the
`mail-orchestrator` skill available automatically — no separate skill copy
needed.

## Commands

| Command | Description |
|---------|-------------|
| `/mail-name [name]` | View or set your display name in the federation |
| `/mail-status` | Show connection status and unread count |
| `/new-task [prompt]` | **Start a fresh session** (clears context). Optional kickoff prompt. |
| `/prune-agents [seconds]` | Probe all agents, remove ones that don't reply within N s (default 15) |
| `/restart-mail-daemon` | Kill the daemon and reconnect (spawns a fresh one) |

## Tools (callable by the LLM)

| Tool | Description |
|------|-------------|
| `mail_list` | List inbox messages |
| `mail_read <id>` | Read a message in full |
| `mail_send` | Send to a named agent — see parameters below |
| `mail_broadcast` | Send to all connected agents |
| `mail_mark_read <id>` | Archive a message |
| `mail_list_agents` | List agents with status, context %, and uptime |
| `mail_set_name <name>` | Set your display name |
| `mail_set_status <status>` | Set your status line (empty string clears it) |
| `mail_restart_daemon` | Restart the shared mail daemon (briefly disconnects every agent; auto-reconnects) |
| `mail_spawn_agent { cwd, name?, model?, kickoff?, favorite?, mm?, ceo? }` | Spawn a fresh pi agent in a directory (tmux); returns its name. `favorite:true` stars the dir; `mm:true`/`ceo:true` spawn a manager session |
| `mail_stop_agent { name }` | Stop a daemon-spawned agent (kills its tmux session) |
| `mail_stop_self` | A daemon-spawned agent tears down its own session + registry entry when done (workers, MM, CEO, any spawned agent; refuses operator-launched agents) |
| `mail_list_projects` | List recent + favorite project dirs (history + starred), each tagged alive/not |
| `mail_set_project_favorite { cwd, favorite }` | Star/unstar a project dir (shared federation-wide) |
| `board_list_tasks` | Task board overview grouped by column (`mine: true` filters to you) |
| `board_get_task <id>` | Full task detail: description, column instructions, subtasks, activity |
| `board_move_task` | Move a task to a column (Jira transition if mapped; notifies assignee) |
| `board_comment_task` | Comment on a task (posted to Jira for Jira tasks) |
| `board_progress_task` | Post a work-in-progress note on a task — internal (not posted to Jira); folded into the description when the task moves columns |
| `board_assign_task` | Assign to an agent — assignee gets the task package by mail |
| `board_create_task` | Create a task; `parent` makes it a subtask (real Jira sub-task under Jira parents), `inJira` creates a top-level Jira issue |
| `board_split_task` | Subdivide a task into subtasks in one call |
| `board_update_task` | Edit summary/description (pushed to Jira for Jira tasks) |
| `board_flag_task` | Flag a task as ⚠ unclear with questions (operator notified); `clear: true` removes it |

### mail_send parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `to` | string | Recipient name or agent ID |
| `subject` | string | Subject line |
| `body` | string | Message body |
| `newSession` | boolean? | **If `true`: the receiving agent will start a fresh session (cleared context) before acting on this message.** Use when sending an unrelated new task. |

## Orchestrator guide

### Sending a new unrelated task to an agent

```json
mail_send({
  "to": "agent-name",
  "subject": "Task: implement feature X",
  "body": "Detailed instructions...",
  "newSession": true
})
```

The agent automatically:
1. Archives this message
2. Waits until idle (followUp delivery)
3. Opens a fresh session with the body as the first prompt

Do **not** use a special subject convention — use the `newSession` flag.

### Checking agent state

```json
mail_list_agents()
```

Returns per agent: name, id, uptime, context saturation (`ctx=34%`), status.

### Status conventions (expected from agents)

Agents update `mail_set_status` automatically when:
- Starting a task → `"implementing X in repo Y (issue-123)"`
- Shifting focus → updated description
- Going idle → `"idle"` or empty

The orchestrator should rely on these statuses to decide whether an agent is
available for new work.

### Pruning dead sessions

If `mail_list_agents` shows more agents than expected:

```
/prune-agents 20
```

Broadcasts a probe, waits 20 s, then removes agents that didn't respond.

## Agent guide

### Identity and status

- Set a **descriptive name** with `mail_set_name` (e.g. `"portal-web-worker"`).
  Default names are auto-generated slugs.
- **Keep status current** — the orchestrator reads it to coordinate work:
  - Task start: `mail_set_status "implementing auth refactor (issue-456)"`
  - Shift: update to new action
  - Done/idle: `mail_set_status ""` or `"idle"`

### Context window saturation

`mail_list_agents` shows `ctx=N%` per agent — updated after each LLM turn.
When an agent's context is near full the orchestrator may send a `newSession`
message to reset it before the next task.

### Session lifecycle on reload

Agent IDs are persisted in the session so `/reload` reuses the same ID.
The daemon treats it as a reconnect (no duplicate registration).

## Files

```
pi-mail/                              Package root
├── package.json                      pi manifest (extensions + skills)
├── extensions/
│   ├── index.ts                      Extension entry point (TypeScript, loaded via jiti)
│   ├── daemon.mjs                    Singleton daemon (plain Node.js, no build step) — also serves the web UI
│   └── ui.html                       Web UI single-page app (served by the daemon)
├── skills/
│   ├── mail-orchestrator/SKILL.md    Orchestrator skill, shipped with the plugin
│   ├── middle-manager/SKILL.md      Middle-manager (scheduled) workflow skill
│   ├── ceo/SKILL.md                  CEO (top-tier scheduled manager) skill
│   └── task-board/SKILL.md           Task board workflow skill (agents + orchestrators)
└── README.md                         This file
```
