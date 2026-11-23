# Read-only web console

The gateway serves a browser console at `/app`. It shows the conversations stored on the phone, their turns, and tasks that are running on the phone (including tasks started on the phone itself). It is read-only: it cannot start, answer, steer or cancel tasks, approve payments, or change phone settings.

This document covers the gateway side and the endpoints it expects from the phone. The phone implementation ships in a companion change; the gateway can be deployed first.

## Requirements

- A paired gateway with an outbound device channel (see the main README).
- A phone build whose `GET /api/v1` capabilities include `conversations` and `running`.
- Remote access enabled on the phone.

If the phone does not advertise those capabilities, the console says the phone app needs an update and stops polling. Nothing else is affected.

## Session model

- The browser signs in with the phone access token at `POST /web/login` (form field `token`). The phone must be online and accept the token before the gateway creates a session.
- The gateway keeps the token in process memory only. It is never written to disk, never sent to the browser, and disappears when the gateway restarts.
- The browser receives an HttpOnly `mika_console` cookie (`Path=/web`, `SameSite=Strict`, `Secure` on HTTPS, 8 hours). The gateway keeps at most 16 sessions, with an 8-hour idle and 24-hour absolute limit.
- Browser `Origin` and cookies are stripped before forwarding; the phone still authenticates every request with the stored Bearer token. The phone's own Origin rejection and per-caller failure cooldown stay in force.
- Login is rate-limited per caller (10 per minute) and rejects a foreign `Origin`.

## Gateway endpoints

| Endpoint | Method | Purpose |
| --- | --- | --- |
| `/app` | GET | Console page |
| `/console.css`, `/console.js` | GET | Embedded assets |
| `/web/session` | GET | `{"connected": true/false}` |
| `/web/login` | POST | Form `token=…`; sets the session cookie |
| `/web/logout` | POST | Deletes the session and clears the cookie |
| `/web/api/...` | GET | Read-only forwarding |

`/web/api/...` strips the `/web` prefix and forwards only this allowlist:

- `/api/v1`
- `/api/v1/tasks`
- `/api/v1/tasks/{id}`
- `/api/v1/running`
- `/api/v1/conversations`
- `/api/v1/conversations/{id}`

Write paths (`/wait`, `/answer`, `/steer`, `/cancel`, task creation) are not reachable from the console. Query strings are preserved.

## Phone contract

The console expects these read-only endpoints. Field values are truncated by the phone with a `truncated` flag; the gateway passes the JSON through unchanged.

`GET /api/v1` must include `conversations` and `running` in `capabilities`.

`GET /api/v1/conversations?limit=`

```json
{ "conversations": [
  { "conversation": "…", "title": "…", "updated_at_ms": 0, "pinned_at_ms": 0, "turn_count": 0 }
] }
```

`GET /api/v1/conversations/{id}?from=&limit=` — omit `from` for the last page.

```json
{ "conversation": "…", "title": "…", "total": 0, "from": 0,
  "turns": [ { "index": 0, "user_text": "…", "outcome": "…", "status": "succeeded",
               "note": "…", "run_id": "…", "steers": [], "truncated": false } ] }
```

`GET /api/v1/running`

```json
{ "running": [ { "conversation": "…", "goal": "…", "status": "running", "run_id": "…",
                 "step": 1, "progress": "…", "question": "…", "options": [],
                 "needs_you": false, "truncated": false } ] }
```

## Limitations

- Polling only: runs and conversations refresh every few seconds; there is no streaming output.
- No screen frames, files, run timeline, schedules or settings.
- Sessions live in one gateway process; a restart signs every browser out. Do not run multiple gateway instances behind a load balancer.

## Verification

```sh
go test ./... && go vet ./...
```

`console_test.go` covers the page/CSP, login/session/logout, the read-only path allowlist, Origin and cookie stripping before forwarding, and the foreign-Origin rejection. The proxy test uses a websocket device stub, so it does not need a phone.
