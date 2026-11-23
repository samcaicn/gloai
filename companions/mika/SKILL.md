---
name: mika
description: Delegate tasks to Mika on a connected Android phone, follow progress, add instructions to running tasks, answer task questions, and retrieve results through the bundled CLI. Requires an Android phone running Mika, reachable by USB ADB or Wi-Fi. Use when the user wants Mika to operate phone apps, browse, or work with phone files.
---

# Mika on your phone

Mika runs its own agent on Android. Give it the user's complete goal, target and constraints; it chooses the phone actions. The bundled CLI talks directly to Mika's task API. No MCP client or MCP configuration is required.

## Quick start / first connection

Do these in order, without asking the user unless a step says so.

1. Pick the command. If `mika --version` works, use `mika`. Otherwise use the bundled script (skill-dir = the folder containing this SKILL.md; Python 3.10+): POSIX `python3 <skill-dir>/scripts/mika_cli.py`, Windows `py -3 <skill-dir>\scripts\mika_cli.py`. Below, `mika` means whichever you chose.
2. Use the connection method the user picked on the phone. You cannot see the phone: if the user has not said which one, ask once.
3. USB/ADB: `mika devices --json` lists ADB phones; run `mika connect --device SERIAL --json` for the chosen one. This enables computer access through Mika's official bootstrap script. With several phones online, ask the user which one, then keep that device for the whole task.
4. `mika doctor --device SERIAL --json` checks connection and phone state. Only then submit tasks.

Wi-Fi/private VPN: use `--url http://PHONE:8765 --token-file /path/to/token-file` (both from the user) instead of `--device`. Never print, read aloud or put token contents in a command, goal or reply.

Notes:
- ADB mode needs Android platform-tools; the APK must include task API v1 (an older MCP-only APK is insufficient). Humans can run `mika ui` for a local task desk; agents should use the commands below.
- If the user selects a parallel Mika build, add `--package APPLICATION_ID` to `connect`, `doctor` and each `task` command. The default ADB package is `com.wangzan101.mika`.

## Common failures

For missing app notifications, `mika notification-doctor --device SERIAL --package com.example.app --json`
reads configuration through ADB without a running Mika server. Optional `--dependency com.example.push`
checks a related package and `--listener com.example.agent/.Listener` checks listener access and binding.
Use `--user ID` for another Android profile. This command does not launch apps, change settings, or return
notification contents. A clear report does not prove delivery; test with a real incoming notification afterward.

| Symptom | Action |
| --- | --- |
| Device shows `unauthorized` | Ask the user to accept the USB debugging prompt on the phone, then rerun `devices`. |
| `adb_unavailable` | Install Android platform-tools or pass `--adb PATH`. |
| `device_selection`, `device_offline` | Run `devices`, choose `--device SERIAL`; replug or reconnect if offline. |
| `stale_connection`, `credentials` | Phone rebooted or token changed: rerun `connect`. |
| `connection_info` | Mika's connection information could not be read or is invalid: ask the user to unlock the phone and open Mika once, then rerun `connect`. |
| `incompatible_api` | The phone app is older than the option needs. The message names the missing capability and the phone's version. Update the Mika APK on the phone, or rerun without the gated option (for example drop `--request-id` and the task still runs, without recovery). |
| `adb` / `connection` errors, phone locked | Ask the user to unlock the phone and open Mika, then rerun `connect`. |

## Delegate and follow the task

Use a UTF-8 file or stdin for goals and answers containing multiline text. Installation or connectivity does not authorize additional phone actions: preserve the user's requested scope when writing the goal.

```bash
mika --device SERIAL task run --goal-file /path/to/goal.txt --json
mika --device SERIAL task wait TASK_ID --timeout 45 --json
mika --device SERIAL task status TASK_ID --json
```

For a task that needs a file on this computer, add `--attach PATH` to `task run` for each user-selected input (up to 9 files totalling 200 MiB). When a task returns `files`, save the requested result with `mika task download TASK_ID FILE_ID --output NEW_PATH` using the same phone connection. The file ID comes from that task’s status. A path in answer text alone is not a downloadable result.

For a recoverable submission, generate and save a lowercase UUID before the call and pass `--request-id UUID`. On a compatible phone this is also the task ID. The CLI generates one automatically when the phone advertises `task_recovery`. If the phone answers `incompatible_api` for `--request-id`, rerun without it: the task still runs, only recovery after a lost receipt is unavailable.

If the task is stuck, failed, or behaved unexpectedly, export the redacted run record before guessing. It is the same event stream as the phone timeline, without screenshot files. `partial: true` means the run is still in progress. An unknown id is an error, not an empty record. Use `task_id`, `conversation_id` and `run_id` in the header to line it up with `task status`.

```bash
mika --device SERIAL task export TASK_OR_RUN_ID --json
```

Keep the returned task ID and conversation ID. A successful submission only means that Mika accepted the task.

Wait rule: call `task wait --timeout 45` (allowed 1-50) in the foreground, one call at a time, and re-check the returned state each time. A wait never cancels or resubmits; a timeout is not a failure, so never resubmit on timeout. Stop and report to the user when the task needs an answer or phone takeover, or after a reasonable budget (for example 10-15 waits, longer if progress is clearly advancing). Do not build a background polling loop.

| Returned state | Next action |
| --- | --- |
| `starting`, `queued`, `running`, `paused` | Read progress; wait again within the active task when appropriate. |
| `waiting_for_answer` | Inspect `question` and `options`. Answer from known user instructions, or ask the user for missing information. |
| `waiting_on_phone` | Tell the user what needs attention on the phone; an answer command cannot approve or complete this step. |
| `done` | Report the answer and its actual verification fields. Missing `verified` means unknown. |
| `failed`, `cancelled` | Report the recorded outcome; do not silently start the task again. |

```bash
mika --device SERIAL task answer TASK_ID --text-file /path/to/answer.txt --json
mika --device SERIAL task steer TASK_ID --text-file /path/to/additional-instructions.txt --json
mika --device SERIAL task cancel TASK_ID --json
mika --device SERIAL task list --json
```

Cancellation's `accepted: true` is an acknowledgement, not evidence of a cancelled terminal state. Query the same task to see its final state. `answer` only answers an open question; it does not redirect a running goal. To continue a completed conversation, submit a new task with `task run --conversation CONVERSATION_ID`.

Use `steer` only when the user adds or corrects requirements for the same `running` task. It keeps the task ID and context; `accepted: true` and `message_id` mean inbox receipt, not execution. Query `status` for that message's entry in `steers`: `received` (queued), `joined` (added to context), or `not_applied` (unused at completion/cancellation). An in-flight action finishes first and past actions are not undone. Steering cannot answer a question, approve payment, resume a pause or finish handoff. Other states reject it; do not turn the rejection into a new task. It requires an APK advertising `task_steer`; update the phone app if unsupported. Text may come from `--text`, a UTF-8 file or stdin (`--text-file -`), up to 4000 characters.

## Exit codes

| Exit | Meaning |
| --- | --- |
| 0 | Success, including an active task or an accepted cancellation. |
| 1 | Operation rejected by the phone, or the task was observed as `failed`/`cancelled`. |
| 2 | Input, configuration, ADB or connection failure. |
| 4 | `outcome_unknown`: a write may have reached the phone. |
| 130 | Local wait interrupted; the phone task is left alone. |

## Handle uncertain outcomes

- Exit 4: do not automatically repeat the write. A recoverable run returns its preassigned task ID even when the receipt is lost. Query that ID on the same phone. If a retry is needed, reuse the original request ID, goal and conversation. An interrupted run reports `failed` with `failure_kind=interrupted`. Query the known task ID, or inspect `task list` if no ID was received. If this cannot establish the outcome, report that uncertainty.
- ADB loss or phone reboot: reconnect to the same phone. On a phone with task recovery, query the retained ID after reconnecting. Older phones can lose their in-memory index on restart.
- `doctor` reports model configuration as unverified and does not prove that a real phone task can succeed.
