# Computer CLI and Agent Skill

[简体中文](cli.md) | English

> Using Mika from a computer? Start with [Use Mika from a computer](computer.en.md): how to choose between CLI + Skill and MCP, and how to connect.

CLI and MCP are peer entry points backed by the same remote task service on the phone. The CLI calls an ordinary HTTP task API; it needs no MCP SDK, handshake, tool discovery or MCP client configuration. The Skill tells a computer agent how to delegate, handle questions and report actual results. Mika on Android still plans and executes the task.

```mermaid
flowchart LR
    Skill["Computer agent + Skill"] --> CLI["Mika CLI"]
    Shell["User / shell script"] --> CLI
    Client["MCP client"] --> MCP
    subgraph Phone["Android phone · shared listener and authentication"]
        API["RemoteTaskApi · /api/v1"]
        MCP["McpRpc + McpTaskTools · /mcp"]
        Shared["RemoteTaskService · shared task operations"]
        Hub["RemoteTaskHub · one task index"]
        Engine["TaskService / TaskRun / AgentLoop"]
        API --> Shared
        MCP --> Shared
        Shared --> Hub --> Engine
    end
    CLI -->|"HTTP, optionally forwarded through ADB"| API
```

## Installation and maintenance

See the [CLI / Skill installation guide](computer-install.en.md) for installation, updates, rollback and removal. Normally choose either the CLI or MCP. The phone must support task API v1, first shipped in official APK v0.1.23.

With only the Skill installed, `mika` below means `python3 <skills-directory>/mika/scripts/mika_cli.py` (`py -3` on Windows).

## Connect

ADB mode requires Android platform-tools and either USB or connected wireless ADB. For wireless ADB, this computer must be paired and connected to the phone, which must appear as `device` in `adb devices`. Pairing Mika's built-in helper on the phone does not pair the computer; both also differ from direct LAN access below.

```bash
mika devices --json
mika connect --device SERIAL --json
mika doctor --device SERIAL --json
```

You may omit `--device` when exactly one device is online; the existing ANDROID_SERIAL environment variable is also supported. Multiple devices require a choice, and connection failure never selects a different phone. `connect` runs Mika's official bootstrap script to enable computer access and start the required helper; it does not submit a task. Other commands only read the current connection.

Each invocation reads the current token, port and boot number, allocates its own temporary `adb forward tcp:0` mapping, then removes only that mapping. Tokens stay in process memory; bootstrap output is captured instead of printed. Exiting the CLI leaves the phone service and ongoing tasks running.

If a debug build is installed alongside Mika, connect with `mika connect --package com.wangzan101.mika.dev`. Use the same package for subsequent `doctor`, `task` and `ui` commands. Omitting `--package` selects `com.wangzan101.mika`. Set different listening ports if both builds have computer access enabled. `--package` selects an ADB target and cannot be used with `--url`.

The CLI handles forwarding; you do not need the MCP instructions for manually forwarding a fixed port. After reconnecting the cable, make sure the same phone is back in ADB. After a phone reboot or turning off computer access, run `connect` again.

For LAN/private-network access explicitly enabled in the phone's settings:

```bash
mika --url http://PHONE:8765 --token-file /path/to/private-token-file doctor --json
```

The private token file contains only the access token. Use the server origin or `/api/v1` URL, not `/mcp`; URL credentials are rejected. The CLI refuses redirects and ignores ambient HTTP proxies. The server retains its private-network restrictions. Plain LAN HTTP does not encrypt traffic; ADB or a trusted private network can be used.

## Tasks

```bash
mika --device SERIAL task run --goal-file task.txt --json
mika --device SERIAL task status TASK_ID --json
mika --device SERIAL task wait TASK_ID --timeout 45 --json
mika --device SERIAL task answer TASK_ID --text-file answer.txt --json
mika --device SERIAL task steer TASK_ID --text "Only summarize today's messages" --json
mika --device SERIAL task cancel TASK_ID --json
mika --device SERIAL task list --limit 10 --json
mika --device SERIAL task export TASK_OR_RUN_ID --json
```

Use `--goal` / `--text` for simple text, or UTF-8 files for multiline input. A file argument of `-` reads stdin. `run --conversation CONVERSATION_ID` starts another task in an existing remote conversation; `answer` only answers an open question. Existing mcp- conversation IDs remain valid across both interfaces. Their historical prefix does not mean the CLI uses MCP.

`run` returns as soon as a task ID is available. Each `wait` lasts at most 50 seconds and returns on a status change or completion. An existing question or phone handoff is returned immediately. Budget expiry returns the current state without failing, cancelling or resubmitting the phone task. Cancellation's `accepted` is an acknowledgement; query the task and observe cancelled before reporting that it has stopped.

`steer` adds instructions to a `running` remote task without changing its task ID or context. It also accepts `--text-file` (a file or `-` for stdin), up to 4000 characters. `accepted: true` and `message_id` acknowledge inbox delivery only. Query `status` and find the message in `steers`: `received` means queued, `joined` means added to this task's context, and `not_applied` means unused when the task ended or was cancelled. `joined` does not prove successful execution. An action already executing finishes first; steering does not undo past actions.

Queued, paused, question-waiting, phone-handoff and terminal tasks reject steering (409). Use `answer` for questions and the phone for handoff/approvals. Even a race with task completion never starts another task. Do not repeat a write whose response was lost: query the same task, and report uncertainty if no message ID was received and delivery cannot be established. Steering requires the APK's `task_steer` capability; older APKs prompt an update instead of falling back to a new task.

`export` pulls the redacted on-disk record for a task id or run id (the same `run.json` and `events.jsonl` as the phone's process view, redacted again). The default is one JSON object with `exportFormat` 1. The header maps `task_id`, `conversation_id` and run_id, and `events` is an array. `partial: true` means the run is still in progress and the events are what has been written so far. `--events` prints the header as the first line and then one event per line. Screenshot files are omitted; `media` events keep only the id and step. An unknown id fails with an explicit error. Runs started on the phone itself are not exported. Older APKs without the run_export capability ask you to update.

`--json` writes one JSON object to stdout for both success and errors. The default is indented JSON, with errors on stderr. Connection options and `--json` work before or after subcommands. `--request-timeout` controls HTTP timeouts; a wait request has at least its wait budget plus five seconds to receive a response.

| Exit code | Meaning |
| --- | --- |
| 0 | Successful call; a task may still be active or awaiting user action, or cancellation may still be pending. |
| 1 | Explicit API rejection, or a task observed as failed / cancelled. |
| 2 | Input, configuration, connection or protocol incompatibility. |
| 4 | Write outcome unknown; the phone may have accepted it, and it was not retried. |
| 130 | A local read/wait was interrupted; no phone task cancellation was requested. |

On a phone advertising `task_recovery`, the CLI generates a lowercase UUID before submitting and the phone records it before starting the task. For automation, save your own UUID first and pass it to `task run --request-id UUID --goal ...`. A lost receipt returns exit 4 with the `task_id`. Reconnect to the same phone and run `mika task status UUID`. If the phone still returns 404 and you intend to retry, use the same ID, goal and conversation argument. A replay returns the existing task. Reusing the ID for a different request returns 409. The CLI never automatically resends a write.

After an app process restart the same ID retrieves the answer and verification fields, failure reason or cancellation. An unfinished task returns `failed` with `failure_kind=interrupted`. Deleting a conversation clears its remote task contents. Its used IDs return 410 and cannot start tasks again. Finished task records are kept for 30 days, up to the latest 1000; after that the same ID counts as a new request. IDs held only in memory before upgrading cannot be reconstructed. Ordinary submissions still work on older phones with the previous recent-task lookup. An explicit `--request-id` requires a compatible phone; the error then names the phone version and the missing capability, and dropping the flag submits normally.

`doctor` checks connectivity/API and available device state; it explicitly reports model configuration as unverified. It neither invokes a model nor starts a task, and cannot establish real-device task acceptance. Missing `verified` means unknown. Payment confirmations, phone handoff and access to phone-local tasks retain the same boundaries as MCP.

## Task attachments and downloads

Phones advertising `task_files` accept up to 9 attachments totalling 200 MiB per task. The client transfers them in chunks and the phone imports them into the conversation workspace before starting the task. Answers and steering messages accept text.

```bash
mika task run --goal "Check the list and export the result" --attach list.csv --attach photo.png --json
mika task status TASK_ID --json
mika task download TASK_ID FILE_ID --output checked.csv
```

Use the file ID, name, size and export digest in the task’s `files` field. The CLI stages the download in the destination directory and creates the requested file only after verification. An existing file is preserved. On macOS and Linux volumes without hard links, such as exFAT, it copies the verified file into an exclusively created destination. Check that file if the final local write is interrupted. Files can be downloaded while the phone retains the remote task ID. The original export remains in the phone’s Downloads after the task receipt expires. If a submission with attachments loses its receipt, look up the original request ID. The desk retries with the same upload IDs in their original order. Running the CLI again with local paths creates new upload IDs. If the original request was accepted, the phone returns 409 and you can retrieve the task by its original request ID.

## Browser task desk

```bash
mika ui                      # open the task desk in a local browser
mika ui --device SERIAL --no-open
```

`mika ui` serves a page for this computer only on a random `127.0.0.1` port. It uses the same task commands to start tasks, follow progress, answer questions, add instructions and cancel, and shows Simplified Chinese or English depending on the browser's language. The part of the link after `#` is the session secret for this run; browsers do not send it anywhere. The server also checks Host and Origin and accepts only a fixed set of actions. It is an interactive page for people and does not support `--json`. See [Use Mika from a computer](computer.en.md#task-desk-mika-ui).

## HTTP contract

All paths below are relative to `/api/v1` and use the same Bearer authentication. POST bodies are application/json objects. Errors use HTTP status plus a structured code/message, without MCP envelopes. Unknown fields are rejected.

| Method and path | Input / response |
| --- | --- |
| GET root | API version, server version, capabilities and input limits. |
| GET /device | Read-only device state; 503 if unavailable. |
| POST /uploads | Send `name` and byte count `size` to obtain a single-use `upload_id`. |
| POST /uploads/ID | Send the next `offset` and Base64 `data` (up to 128 KiB raw). The phone acknowledges `next_offset`. |
| POST /uploads/ID/discard | An empty object discards a pending upload. Unused IDs expire after 30 idle minutes. |
| POST /tasks | A goal, optional conversation, request_id and an ordered attachments array of upload IDs. With a request ID, the returned task_id equals that ID. |
| GET /tasks/ID/files/FILE_ID?offset=N | Read a chunk of a file exported by that task. Returns the byte `offset` and Base64 `data`. |
| GET /tasks?limit=10 | Recent remote tasks; limit 1–50. |
| GET /tasks/ID | Current task state. |
| POST /tasks/ID/wait | timeout_s 1–50, default 30; state and changed. |
| POST /tasks/ID/answer | text; returns task_id and accepted. |
| POST /tasks/ID/steer | text; 202 with task_id, message_id and accepted. Only accepts running tasks. |
| POST /tasks/ID/cancel | Empty object; 202 when accepted, 200 for an already-terminal task; returns accepted. |

Business validation errors return 400, missing tasks 404, answers incompatible with the current state 409, and refused starts 503. Authentication, origin and rate-limit policies are shared with `/mcp`. Both adapters control tasks in the same remote task index. When available, status also includes `"run_id"`, separate from the remote task ID.

Phones with history support advertise the `history` capability and the `"history_enabled"` field. The owner must enable history viewing under **Connect a computer → Advanced** on the phone before these GET endpoints can read saved conversations and runs. Missing permission, including revocation during a read, returns `403 history_disabled`. Missing conversations, runs or requests return 404.

| Path | Response |
| --- | --- |
| /history/conversations | `conversations` with IDs, titles and update times; `next_offset` identifies the next page. |
| /history/conversations/C | `turns` with requests, replies, `"run_id"` and `turn_index`, in conversation order within each page. `offset` counts back from the latest turn; `total` is the current turn count. |
| /history/conversations/C/runs/R | Saved `events` and Trace `rows` belonging to this conversation, with archive availability and recording state. |
| /history/conversations/C/runs/R/bodies/S | One page of a span’s redacted request and reply in `text`; `next_offset` is the next character position. |

A saved turn includes `verified` only when a review actually ran. Older records without a verdict omit the field. A turn started from a computer can include `"source_task_id"` even when cancelled in the queue before a run ID was assigned.

Only `offset` and `limit` query parameters are accepted. Pages default to 20 items, up to 50; body pages default to and allow at most 3,000 characters. A null `next_offset` ends pagination. Long conversation text and event excerpts report `text_truncated` or `truncated`. History reads do not register phone tasks in the remote control index or allow answering, steering or cancelling them.

## Development checks

```bash
./gradlew :core:test :core:cliTest :app:testDebugUnitTest :app:assembleDebug
# Python tests alone; use -PmikaPython=/path/to/python3 to select an interpreter
python3 -m unittest discover -s companions/mika/tests -v
```

Pure JVM tests cover the shared service, API/MCP interoperability, authentication, states and existing protocol regressions. `cliTest` (needs Python 3.10+; not part of `check`, run it explicitly) runs the real CLI against the Kotlin HTTP server, checking it only requests `/api/v1`, plus fake-ADB, lost-response, token-rotation, forwarding-cleanup and standalone-Skill tests. No phone or model key is required. Actual wireless disconnects, phone reboot and real agent execution need separate device acceptance; isolated tests do not establish those results.
