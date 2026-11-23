# Computer access (MCP): let AI tools on your computer hand tasks to Mika

[简体中文](mcp.md) | English

> Using Mika from a computer? Start with [Use Mika from a computer](computer.en.md): how to choose between CLI + Skill and MCP, and how to connect. For terminal or Skill-based use, see [CLI and Skill](cli.en.md).

With "Computer access" on, Mika is an MCP server: an AI tool on your computer that speaks MCP (an MCP client) can hand Mika a job, and Mika does it on the phone its own way (operating apps, using the built-in browser, working with files), then hands back the result. The computer says what to get done; Mika on the phone decides how.

There are two ways to connect. Each has its own switch and token, and both can be on at once:

- **Over USB (recommended)**: with the phone on a cable and USB debugging on, one command on the computer turns it on, without touching the phone. It listens on the phone itself only, so it can't be reached over Wi-Fi.
- **Over Wi-Fi or a private network**: turned on in the phone's settings, off by default. When the computer and the phone are on the same Wi-Fi, or in the same private VPN network (a virtual LAN made by a mesh networking tool), they can connect without any port forwarding on your router. This is also how you use Mika remotely.

## Connecting over USB (recommended)

You need adb on the computer, the phone listed in `adb devices`, and Mika opened at least once (that's when it writes the script onto the phone).

1. On the computer, run:

   ```
   adb shell sh /sdcard/Android/data/com.wangzan101.mika/files/connect-computer.sh
   ```

   It first starts Mika's phone helper (the same as "Start from a computer"; skipped if it's already running), then turns USB mode on, and finally prints the connection info, like this:

   ```json
   {
       "transport": "streamable-http",
       "url": "http://127.0.0.1:8765/mcp",
       "headers": {
           "Authorization": "Bearer <token>"
       },
       "token": "<token>",
       "port": 8765,
       "forward": "adb forward tcp:8765 tcp:8765",
       "boot": "17"
   }
   ```

2. Forward port 8765 on the computer to the phone:

   ```
   adb forward tcp:8765 tcp:8765
   ```

3. In your MCP client, add a server of type Streamable HTTP (also called "HTTP"): URL `http://127.0.0.1:8765/mcp`, header `Authorization: Bearer <token>`. See the config example under "Connecting over Wi-Fi or a private network" below; just use `http://127.0.0.1:8765/mcp` as the URL.

To see the connection info again later:

```
adb shell cat /sdcard/Android/data/com.wangzan101.mika/files/mcp-usb.json
```

Once it's on:

- A "Computer access is on" notification stays in the shade, and it stays on while Mika is in the background. On the phone, Settings › Connect a computer initially shows "Waiting for a computer"; only after a request arrives does it show "Request received from a computer" and the request time.
- **After you unplug and replug the cable** (or adb on the computer restarts), run `adb forward tcp:8765 tcp:8765` again. The token stays the same.
- Running the command in step 1 again while it's on keeps the token, so clients you already set up keep working.
- **It turns off when the phone restarts.** Run the command in step 1 again; the token is new then, so update it in your client config.

To turn it off (any of these; the old token stops working at once and the connection info file is deleted; the next time you turn it on you get a new token):

```
adb shell sh /sdcard/Android/data/com.wangzan101.mika/files/connect-computer.sh off
```

Or switch off "Connect through USB" in Settings › Connect a computer › Advanced settings on the phone, or tap "Turn off" on the notification (which also turns off the Wi-Fi mode if it's on).

What the script does can also be done with plain commands (after turning it on, the connection info file takes a second or two to appear):

```
adb shell am start-foreground-service -n com.wangzan101.mika/.mcp.McpService -a com.wangzan101.mika.mcp.USB_ON
adb shell cat /sdcard/Android/data/com.wangzan101.mika/files/mcp-usb.json
adb shell am start-foreground-service -n com.wangzan101.mika/.mcp.McpService -a com.wangzan101.mika.mcp.USB_OFF
```

## Connecting over Wi-Fi or a private network

### Turning it on

1. In Mika, open Settings › Connect a computer, select "MCP" › "Wi-Fi / VPN", then tap "Enable network access". Network access is off by default; selecting a guide does not enable it.
2. While it is on, a "Computer access is on" notification stays in the shade to show Mika is listening in the background; its "Turn off" button switches it off right there.
3. Select an address reachable from the computer, then tap "Copy config" and add it to your MCP client. The copied config contains the address and token. "Preview" is collapsed by default and only displays a token placeholder when expanded.
4. Connect or refresh tools in the client, then check that the page shows "Request received from a computer" and the request time.

Access switches, token copying and replacement, and remote wake/unlock are under "Advanced settings", collapsed by default. "CLI / Skill" and "MCP" share the USB / Wi-Fi / VPN connection selector. "CLI / Skill" has two steps: install and connect; connect verifies the connection. Network mode uses the HTTP origin and a token file, without MCP configuration.

The token is stored encrypted on the phone, the same way as provider API keys. Turning the switch off closes the port (if USB mode is still on, only the listener on the phone itself stays). If the default port 8765 is taken by another service on the phone, change it under Advanced settings › Listening port (1024–65535); it applies immediately, and `connect` and `adb forward` on the computer follow the port shown on the phone.

### Connecting from a computer

- **Same Wi-Fi**: use the Wi-Fi address from the list (usually `192.168.*.*` or `10.*.*.*`).
- **Private VPN network**: once the phone and the computer are in the same network, use that network's address from the list (these networks often use the `100.64.*.*` to `100.127.*.*` range).
- When the phone is on mobile data, or the other side has a public address, Mika refuses the connection.

In your MCP client, add a server of type "HTTP" (also called Streamable HTTP), enter the address, and add the header `Authorization: Bearer <token>`. Each client words its config file a little differently; most look like this:

```json
{
  "mcpServers": {
    "mika": {
      "type": "http",
      "url": "http://192.168.1.23:8765/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

You can also try it from a terminal first (use your own address and token):

```sh
curl -s http://192.168.1.23:8765/mcp \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'
```

## Self-hosted remote access

For public remote access, deploy the optional self-hosted gateway. Pair and explicitly enable access under **MCP → Remote**, then copy the config to a client supporting Authorization headers. **CLI / Skill → Remote** uses the same gateway and phone LAN access token. The phone authenticates requests; the gateway manages pairing and forwards requests, without access-token management or OAuth flows. No shared Wi-Fi or VPN is required. The first version is single-owner, single-device. See the [deployment guide](../companions/relay/README.md). HTTPS ends at the gateway, which can read forwarded contents; use one you trust.

## Tools

| Tool | Arguments | What it does |
| --- | --- | --- |
| `mika_run_task` | `goal` (required), `conversation` and `"request_id"` (optional) | Hands Mika a job and returns a `task_id` right away. The task is recorded in a conversation you can see in the app. To follow up on an earlier job, pass the `conversation` that task returned. |
| `mika_task_status` | `task_id` | The task's state now: status, latest progress, what it is waiting for, and the answer when done. |
| `mika_wait_task` | `task_id`, `timeout_s` (optional, default 30, at most 50) | Waits until the status changes (for example Mika starts waiting for an answer, or finishes) or time runs out, then returns the state. Progress notes alone don't count, so it doesn't return on every step. |
| `mika_answer` | `task_id`, `text` | Answers the question Mika asked in this task. |
| `mika_steer_task` | `task_id`, `text` | Adds instructions to a running task (up to 4000 characters), keeping its ID and context. Returns accepted and message_id, not proof of execution. |
| `mika_cancel_task` | `task_id` | Stops the task. |
| `mika_list_tasks` | `limit` (optional, default 10) | The most recent tasks handed over from computers, newest first. |

Statuses:

- `starting`: just handed to the phone, not started yet.
- `running`: in progress, with the latest progress note.
- `queued`: not started yet, waiting behind other tasks (the limit on tasks running at once is reached, or this conversation already has a task running); it starts by itself when its turn comes. Wait or cancel.
- `paused`: waiting for a condition, such as the phone being unlocked (it carries on by itself afterwards).
- `waiting_for_answer`: Mika asked a question; answer with `mika_answer` (answering on the phone works too). If nobody answers for a long time, Mika carries on as if there were no answer.
- `waiting_on_phone`: this step can only be handled by a person on the phone, such as a step handed over for you to do yourself. The computer can't answer it; wait or cancel. The phone shows its usual notification.
- `done`: finished, with the answer; `verified` says whether the answer was checked.
- `failed`: not done, with the reason.
- `cancelled`: stopped.

When the schema of `mika_run_task` includes `"request_id"`, save a lowercase UUID before submitting and use it with `mika_task_status` if you lose the reply. Replaying the same ID, goal and conversation starts only one task. Use a fresh ID for a different request. After a phone process restart, finished tasks retain their results and unfinished tasks report `failed` with `failure_kind=interrupted`. Finished records are kept for 30 days, up to the latest 1000.

Suggested flow: `mika_run_task` → `mika_wait_task` repeatedly → on `waiting_for_answer`, reply with `mika_answer` → until `done` / `failed` / `cancelled`.

After steering, use `mika_task_status` to find the `message_id` in `steers`: `received` means queued, `joined` means added to the context, and `not_applied` means unused when the task ended or was cancelled. An action already executing finishes first; past actions are not undone. Queued/paused, question/approval/handoff-waiting and terminal tasks reject steering. Unconsumed remote instructions never start a new task. Do not automatically resend after a lost response. Older APKs do not list `mika_steer_task`; update the phone app first.

## Remote wake and unlock

On the phone, open Settings › Connect a computer › Advanced settings and turn on “Allow remote wake and unlock” (off by default). Clients connecting through a self-hosted gateway also need “Allow wake and unlock through the gateway” on the Remote page (off by default); otherwise these three tools are not offered to them. Swipe/no-credential locks need no saved credential. Configure the current PIN or password twice on that page; the computer cannot set or read it.

| Tool | Arguments | Purpose |
| --- | --- | --- |
| `mika_device_state` | None | Read screen, keyguard, helper and local permission state. |
| `mika_wake_device` | None | Wake the main screen without authenticating. |
| `mika_unlock_device` | None | Wake and dismiss a swipe lock, or authenticate with the credential saved on the phone. |

Call `mika_unlock_device` before starting a task. Proceed when `status` is `unlocked` and `device.ready` is true. Existing tasks waiting for unlock resume automatically after Android confirms it. An `awake` result only means the screen is on.

PINs must have 4–16 digits. Passwords support 4–64 printable ASCII characters. Pattern locks, SIM PINs and unrecognized vendor credential screens are not entered (`unsupported_keyguard`). Entering a PIN or password requires Android 13 or later: keys are delivered only to the system lock screen, and Android rejects them if another window (an app shown over the lock screen, an incoming call) has focus, so the credential cannot reach another app. On Android 12 only swipe/no-credential locks can be dismissed. Phone control and accessibility must be available; some phones also need a separate developer option that allows input injection over USB debugging.

Credentials are encrypted locally and never sent to models, computers or logs. These tools reject all arguments. A credential attempt is persisted before input: failure, timeout, cancellation or process interruption blocks subsequent attempts (`attempt_blocked`) until the credential is checked and saved again on the phone. Toggling permission or restarting the app does not reset this protection.

Other statuses include `disabled`, `helper_unavailable`, `credential_required`, and `unlock_failed`. Success is based on Android state, not the input command exit code. Unlock the phone manually once after reboot; this feature does not enable remote access before the first unlock, change system lock credentials, or approve task payments/confirmations.

## Security

- **Off by default**; it listens only while on and stops at once when turned off.
- **USB mode listens on the phone itself (127.0.0.1) only**; it can't be reached over Wi-Fi or any other network, and the computer comes in through adb's port forwarding. Other apps on the phone can reach that port too, so the token is still required, and the USB token is only accepted from the phone itself.
- **Only the computer can turn USB mode on and get its token**: Mika's service only accepts callers holding the `INTERACT_ACROSS_USERS_FULL` permission. The adb shell has it; ordinary apps can't get it (and adb can't grant it to other apps). The token is written in Mika's external files folder (`/sdcard/Android/data/com.wangzan101.mika/files/mcp-usb.json`); since Android 11 other apps can't read that folder, only Mika and the adb shell can.
- **Every request needs the token.** The token is 32 random bytes and is compared in constant time; 5 wrong tries from one address within a minute lock that address out for a minute.
- **No browser requests**: anything carrying an `Origin` header is refused, so web pages can't reach the port through your browser.
- **Private networks only**: both ends must be on a LAN, a private VPN network, or the phone itself; connections from public addresses are dropped immediately.
- **Spending and confirmations stay on the phone**: in tasks handed over from a computer, payments and actions that need your approval are never carried out, whether or not "Autonomous mode" is on; a step handed over for you to do yourself can only be done on the phone. `mika_answer` answers questions only and cannot approve any of these.
- **Only the computer's own tasks**: a computer can't see or stop tasks you started on the phone, and the conversations it opens use a fixed prefix, so it can't join your conversations on the phone.
- Every run record notes that the run was started by a computer (MCP).
- The transport is plain HTTP, so use it only on networks you trust. Don't share the token; if you think it leaked, use "New token".

## When it doesn't work

- **No address in the list**: the phone isn't on Wi-Fi or a private VPN network.
- **Status says "Couldn't start"**: another app may be using the port; turn the switch off and on again.
- **The USB script says the file doesn't exist**: open Mika once, wait a few seconds, and try again. If it's still missing, the Mika on the phone doesn't have this feature yet.
- **Can't connect over USB** (`curl` reports connection refused or reset): run `adb forward tcp:8765 tcp:8765` again; if the phone restarted, run the command that turns it on again first.
- **Port 8765 is already used on the computer** (the forward fails with `cannot bind`, or you get a response that isn't from Mika): pick another free port on the computer, e.g. `adb forward tcp:18765 tcp:8765`, and use `http://127.0.0.1:18765/mcp` in the config. Nothing changes on the phone.
- **HTTP 429**: too many wrong tokens from one address; wait a minute. Over USB, everything on the phone that connects to the local port counts as the same address.
- **The computer can't connect**: make sure both are on the same network; some Wi-Fi networks (guest networks, for example) block devices from reaching each other.
- **The task never starts** (the call fails right away, or the task turns `failed` after two minutes): open Mika on the phone and try again. Android sometimes stops a background app from starting work on its own; once you've opened Mika it works.
- **The task stays `paused`**: the phone is most likely locked. Mika needs the phone unlocked to work.
