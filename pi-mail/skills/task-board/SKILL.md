---
name: task-board
description: >
  Use when working with the shared pi-mail kanban task board (optionally two-way
  synced with a Jira sprint). Covers the workflow for an agent that receives an
  assigned task by mail, how to move tasks through columns, board-only columns
  with custom instructions (e.g. Refine, Review), commenting (synced to Jira),
  and how orchestrators dispatch work by assigning board tasks instead of
  sending ad-hoc mail.
---

# Task Board Skill

The mail daemon hosts a shared kanban board for the whole federation. When Jira
is configured, the board mirrors the current sprint (default JQL:
`assignee = currentUser() AND sprint in openSprints()`), pulls remote changes
every ~60 s, and pushes your column moves back as Jira transitions.

## Concepts

- **Columns** are configurable. A column either **maps to a Jira status**
  (moving a task there transitions the Jira issue) or is **board-only**
  (e.g. `Refine`, `Review`) — those exist only on this board and usually carry
  **instructions** describing what to do with tasks placed there.
- **Tasks** come from Jira (origin `jira`, with a key like `PROJ-123`) or are
  created locally on the board (origin `local`, never pushed to Jira).
- **Assignee** is a federation agent name. Assigning a task mails the assignee
  the full task package (description + column instructions + tool crib sheet).
  Moving someone else's task also mails them, with the new column's
  instructions — that's how "put it in Refine" becomes an actionable request.

## Tools

```typescript
board_list_tasks({ mine?: true })          // board overview, grouped by column
board_get_task({ taskId })                 // full detail: description, instructions, subtasks, activity
board_move_task({ taskId, column, note? }) // move; Jira-mapped column ⇒ Jira transition. Folds recent progress into the description
board_comment_task({ taskId, text })       // activity log; Jira tasks ⇒ Jira comment too
board_progress_task({ taskId, text })      // post a work-in-progress note (internal; not posted to Jira; folded into the description when the task moves)
board_assign_task({ taskId, assignee, newSession? }) // mails the assignee the task
board_create_task({ summary, description?, column?, parent?, inJira? }) // new task / subtask
board_split_task({ taskId, subtasks: [{ summary, description? }] })     // subdivide a task
board_update_task({ taskId, summary?, description? }) // edit — pushed to Jira for Jira tasks
board_flag_task({ taskId, reason?, clear? })           // mark a task as unclear (notifies operator)
```

`taskId` accepts the 8-char id prefix or the Jira key.

## Progress updates vs comments

Two activity kinds, for two purposes:

- **`board_comment_task`** — a decision, answer, or note that belongs on the
  record. For Jira tasks it's also posted as a Jira comment (visible to humans
  in Jira). Use it for things the next person should see as part of the
  conversation.
- **`board_progress_task`** — a work-in-progress note (what you've done since
  the last update, what's blocking you). It is **internal**: it never becomes a
  Jira comment. Instead, when the task is **moved to the next column**, the
  progress entries recorded since the last move are folded into a
  `## Progress so far (→ <column>, <time>)` block appended to the description
  — and for Jira tasks that folded description is pushed to the issue. So the
  next agent (and the operator reading the card) inherit a snapshot of what
  was done, without cluttering Jira with per-step comments.

Rule of thumb: **post a `board_progress_task` update before moving a task
onward** (and when you hit a blocker), so the next column starts informed.

### Daemon nudge

If an in-progress task of yours goes quiet for a while (default 30 min), the
  daemon mails you a one-line reminder to post progress. Replying with
  `board_progress_task` clears it. The operator can tune or disable the nudge
  in Board → Settings.

## Clarity gate — before you start ANY assigned task

Check that the task is actually clear: goal, scope, acceptance criteria. If
anything is ambiguous, **do not guess**:

1. Post your specific questions with `board_comment_task` (lands on the Jira
   issue where the human sees it).
2. `board_flag_task({ taskId, reason })` — the operator gets a mail and the
   card shows a red ⚠ unclear badge on the board.
3. Mail whoever assigned it.

Once answered (or after refining yourself in a Refine column), make the task
permanently clear: write the refined goal/acceptance criteria into the task
with `board_update_task` (this pushes to Jira) and clear the flag with
`board_flag_task({ taskId, clear: true })`.

## Workflow: you were assigned a task (mail arrived)

1. `board_get_task` — read the description, the **column instructions**,
   subtasks, and recent activity before doing anything.
2. Apply the clarity gate above. Only proceed when clear.
3. `board_move_task` to the in-progress column when you start (this transitions
   Jira, so the human's sprint board stays truthful). Set your `mail_set_status`
   to the task key.
4. Too big for one pass? `board_split_task` it — subtasks of Jira tasks become
   real Jira sub-tasks. Work them one by one, or `board_assign_task` them to
   other workers.
5. Work the task. Log meaningful findings/decisions with `board_comment_task`
   — for Jira tasks these comments land on the issue, visible to the human.
   Comments made in Jira by humans flow back into the task's activity (synced
   every ~60 s), so re-check `board_get_task` when waiting on an answer.
   Post work-in-progress notes with `board_progress_task` — especially before
   moving the task onward, so the next column (and the operator) inherit a
   snapshot of what was done.
6. When done, move the task onward (e.g. `Review` or `Done` — follow the column
   instructions for where work goes next) and **mail a short summary to whoever
   assigned it** (the sender of the assignment mail). The board move alone is
   not a report.
7. If blocked: comment the blocker on the task AND mail the assigner. Never
   guess.
8. **Self-exit when finished.** If you are a daemon-spawned worker (you were
   spawned by `mail_spawn_agent` / the board UI, not launched interactively by
   an operator at a terminal) and your assigned task is fully done with no
   further work expected, call `mail_stop_self` to tear down your own session
   after reporting completion. Do not call it if you're a persistent worker
   expecting more tasks, or an operator-launched interactive agent (it will be
   refused).

### Ephemerality — you are killed after your pass, no matter what

Every spawned agent in the hierarchy (CEO → middle managers → workers) is
**ephemeral**: each is killed after its pass. Calling `mail_stop_self` when your
task is done is the primary, clean path. But the reaper is the **enforced
backstop**: if you don't self-exit (you hang, crash, get stuck in a long turn,
or simply forget), the daemon force-kills your session at its `*MaxLifetimeMin`
boundary (`workerMaxLifetimeMin` for plain workers, default 30) and removes the
registry entry. So finish cleanly and call `mail_stop_self` — don't rely on the
reaper to notice you. The reaper is the backstop, not the primary path. (See
the README "Ephemerality" section.)

### Reporting pi-mail bugs / improvements

If, while working, you notice a genuine pi-mail bug or a concrete, well-scoped
improvement that you can't fix inline and that genuinely needs the MM/CEO/
operator to act on it, create a board task for it (`board_create_task` into
**Backlog** or **To Do**). The normal MM/CEO board-review pass picks it up
automatically — no special plumbing. Keep it **surgical and substantive**: a
real, actionable bug or a concrete improvement, not a vague "would be nice".
Only raise one when no other option exists, and one task per distinct issue
(fold related points together). Do **not** flood the board with feature
requests.

## Subtasks

- `board_split_task` (or `board_create_task` with `parent`) subdivides a task.
  Under a Jira parent, subtasks are created as **real Jira sub-tasks** in the
  parent's project and stay synced (status, comments) like any other task.
- Subtasks are full tasks: movable, assignable, commentable. A parent is done
  when its subtasks are done — check with `board_get_task` on the parent.
- Sync also pulls subtasks that already exist in Jira under sprint issues,
  even when they don't match the sprint JQL themselves.

## Board-only columns (Refine / Review / …)

A task moved into a board-only column does **not** change its Jira status —
the column exists purely for federation workflow. Its instructions tell you
what the column means. Typical patterns:

- **Refine**: turn a vague ticket into a spec — clarify goal, acceptance
  criteria, approach; post the spec as a comment; move to `To Do`.
- **Review**: review the produced work; post findings as a comment; move to
  `Done` if clean, back to `In Progress` (with specifics) if not.

Always re-read the column's instructions from `board_get_task` — the operator
may have customized them.

## For orchestrators

Prefer the board over ad-hoc task mail — it gives the human a live view:

1. `board_list_tasks` to see the sprint; `mail_list_agents` to see workers.
2. Dispatch by `board_assign_task({ taskId, assignee, newSession: true })`
   — the worker gets the full package by mail automatically. Don't duplicate
   the task body in a separate mail.
3. Drive the pipeline with moves: assign + move to `Refine` first when a ticket
   is vague, then reassign/move to `To Do` → implementation, then `Review` with
   a different worker assigned as reviewer.
4. Decompose big tickets with `board_split_task`, then assign each subtask to
   a different worker — parallel work stays visible under the parent.
5. Track progress via `board_list_tasks` and task activity; nudge silent
   workers by mail (the daemon also auto-nudges in-progress assignees who
   haven't posted progress in a while). Watch for ⚠ unclear flags — resolve
   them (answer via comment, tighten the description with `board_update_task`,
   clear the flag) before re-dispatching.
6. Follow-ups that don't belong in Jira: `board_create_task` (local task).

## Rules

- Never leave a task you're working on in `To Do` — move it. The board is the
  single source of truth for who does what.
- Comments on Jira tasks are visible to real humans in Jira. Be professional
  and concise; no internal agent chatter (use mail for that).
- `board_update_task` edits on Jira tasks are pushed to the Jira issue — treat
  the description as the shared spec and keep it authoritative.
- A Jira task disappearing from the board means it left the sprint/JQL scope
  (board-created Jira tasks are pinned and stay).
