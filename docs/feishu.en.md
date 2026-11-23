# Connect a Feishu bot

[简体中文](feishu.md) | English

Send Mika a private Feishu message, answer its questions and receive its result. An optional connector runs on your computer; the phone still plans and executes the task. No public webhook server is needed.

## Install

Requirements: Python 3.10+, pipx, a **Feishu enterprise custom app** with bot capability, and a phone exposing Mika task API v1 (APK v0.1.23+). This connector supports Feishu, not international Lark tenants. The computer and phone need mutual access over LAN or a trusted private VPN such as Tailscale.

From a repository checkout:

```bash
pipx install ./companions/mika-feishu
mika-feishu setup
```

On the phone, enable Settings › Connect a computer › Network access and copy the network access token. Enter the phone's private address, e.g. `http://100.64.1.2:8765`, using the port shown on the phone. The USB token, model API key and Tailscale auth key are different credentials and cannot replace this token. This version connects directly over the private network; it does not bootstrap USB/ADB.

## Choose how to add a bot

`mika-feishu setup` offers three paths:

1. **Scan to create.** A local page and terminal QR code open Feishu's official authorization flow. Scan using Feishu, review the new app and requested permissions, and confirm. Returned application credentials are saved locally without being printed.
2. **Link an existing app.** Enter its App ID or leave it blank to select the app on Feishu's authorization page. Review the changes before confirming. Only choose an app you are entitled to administer.
3. **Manual.** Enter an existing App ID and App Secret. Secret inputs are hidden; configure the permissions and event below in the developer console.

The explicit equivalents are `mika-feishu setup --mode create`, `--mode existing`, and `--mode manual`. Add `--no-browser` to show the QR/link without opening a browser. Authorization denial, expiry or an incomplete response leaves the previous configuration intact. A registration URL is temporary authorization material; do not share it.

QR setup uses the official SDK's `register_app` and minimal template (`preset: false`) with these two application scopes and one event:

| Type | Identifier |
| --- | --- |
| Read users' private bot messages | `im:message.p2p_msg:readonly` |
| Send as the application | `im:message:send_as_bot` |
| Message received v2.0 | `im.message.receive_v1` |

The authorization page must confirm the requested items. Depending on tenant policy and app state, bot capability, long-connection event transport, release or administrator approval may still need completing in the [Feishu developer console](https://open.feishu.cn/app). QR authorization does not override those controls. For manual setup, enable the bot, grant these scopes, add the event, select **Receive events using long connection**, and publish the changes with yourself in the available-user scope. The connector may need to be running before Feishu lets you save long-connection mode.

Do not run another event consumer for the same app: Feishu distributes long-connection events among clients rather than broadcasting them. Use a dedicated app or integrate this connector into the existing consumer.

Official references: [registration SDK](https://github.com/larksuite/oapi-sdk-python), [long connections](https://open.feishu.cn/document/ukTMukTMukTM/uYDNxYjL2QTM24iN0EjN/event-subscription-configure-).

## Check, pair and run

```bash
mika-feishu doctor
mika-feishu run
```

`doctor` checks the phone API version/capabilities and Feishu application authentication, and reports local pairing. It sends no message or phone task. It cannot verify event delivery or task success; its report explicitly marks that as unverified.

The first `run` displays `/pair <one-time-code>`. Send it in a **private chat with the bot within ten minutes**. The user who has that locally displayed code becomes the allowed owner. App authorization alone does not grant phone control; neither the first arbitrary user to message the bot nor the authorizing user is silently trusted. Restarting an unpaired connector generates a new code. Already paired users need not pair again.

Send a simple read-only task and verify the phone and returned result. Keep the connector process and computer online; sleeping or shutting down the computer prevents delivery. Ctrl+C stops the connector, not tasks already running on the phone.

## Chat commands

| Message | Behavior |
| --- | --- |
| Plain text | Start a task or answer the current question shown in Feishu. An active task keeps the connector from starting another one. |
| `/status` | Read the current progress, question or result. |
| `/answer TEXT` | Answer the current question shown in Feishu. Use `/status` first if you haven't seen it. |
| `/steer TEXT` | Add instructions to a running task if the phone advertises `task_steer`. |
| `/cancel` | Request cancellation. The final phone status, not the acknowledgement, confirms it. |
| `/new` | Start a fresh conversation after the current task ends. |
| `/reset checked` | After checking the phone, clear uncertain local tracking; never cancels phone tasks. |
| `/help` | Show commands. |

Questions, pauses, phone takeovers and final results are reported automatically. Use `/status` to see ordinary progress. When you quote a message in an answer, quote the current question. Payment confirmations, logins, verification codes and manual takeover remain on the phone. The connector reports a result as verified when the phone explicitly reports `verified: true`.

Only allowed users' private text messages are accepted. Group messages, attachments, bot messages and messages over five minutes old are ignored. Saved but unprocessed messages also expire after five minutes. This first version does not support group chats, media or streaming cards.

## Data and upgrades

Configuration defaults to `$XDG_CONFIG_HOME/mika-feishu/config.json` or `~/.config/mika-feishu/config.json` on macOS/Linux, and `%LOCALAPPDATA%\mika-feishu\config.json` on Windows. To override it, put `--config PATH` before the command. Credentials are local plaintext with mode 600 in a mode 700 directory on POSIX; on Windows protect the user directory with its normal private NTFS permissions. Do not commit this directory or share its contents. Feishu receives task text and results as part of the messaging workflow.

SQLite state, grouped by app and phone address, stores the paired user, message deduplication and task/conversation association, including task text. Changing the app or phone address requires fresh pairing. Rotating only a secret/token preserves pairing. Stop `run` before rerunning `setup` or `unpair`; a process lock prevents simultaneous consumers of the same configuration.

Updates to the connector do not replace configuration or require modifying/rebuilding Mika. After updating the checkout, run `pipx install --force ./companions/mika-feishu`, then `doctor` and a read-only task. Before every write, the adapter checks public API version 1 and capabilities. Additive response fields are tolerated; unknown API versions/statuses or missing capabilities stop the corresponding operation. Future incompatible API changes need an adapter update, not a silent fallback.

Incoming message IDs are durably deduplicated. A write whose response is lost is **not automatically repeated**; a crash during processing blocks new writes until the owner checks the phone and sends `/reset checked`. The phone API currently has no idempotency key, so the connector does not promise exactly-once execution. Unknown outcomes must be inspected. If the phone restarts and loses an old task ID, it is reported, not resubmitted. Notifications use deterministic Feishu UUIDs to reduce duplicate replies.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| `http_401` from the phone | Copy its **network** token again; stop, rerun setup and restart. |
| Phone unreachable | Tailscale/LAN reachability, private address, Mika network-access switch and actual port. |
| Bot authentication passes but no response | Event subscription, long-connection transport, published permissions, available-user scope and absence of another consumer. Authentication is not event-delivery verification. |
| Pairing ignored | Private chat, fresh code from the current process, correct app and ten-minute expiry. |
| App lacks registration support | Update the SDK/package, or choose manual setup. |
| Old task missing after phone restart | Check the phone, then `/reset checked`; do not automatically resubmit. |

To remove the local owner, stop the process and run `mika-feishu unpair`. Explicit `allowed_open_ids` configured manually are unaffected. Unpairing does not revoke the application itself or cancel phone tasks.
