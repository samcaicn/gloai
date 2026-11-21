# Installing Mika with an AI agent on your computer

[简体中文](install-with-agent.md) | English

This document is written for **an AI agent on your computer that can run commands**. With the phone connected over USB, the agent follows the steps below and uses adb to install and set up Mika. People can follow it too: every command can be copied as is.

This guide starts with the phone APK. If the phone is already set up and you only need computer access, use the [CLI / Skill installation guide](computer-install.en.md) or [MCP guide](mcp.en.md) for your chosen entry point; do not repeat phone installation and permission setup. Step 10 below is also only for users who request computer access.

If you are the user: connect the phone to the computer with a USB cable, turn on USB debugging on the phone (in Developer options, don't change switches this document doesn't mention: some can erase the phone), and send this to the AI agent on your computer:

```
Follow https://github.com/wangzan101/Mika/blob/main/docs/install-with-agent.en.md to install Mika on the Android phone connected over USB.
```

If you are the AI agent: read the "Rules" first. Start at step 1 when phone installation and setup are needed; for computer access only, use the entry points above.

## Rules

1. **Only install and set up Mika.** Do what this document describes and nothing else.
2. **Leave unrelated system settings alone.** Do not change the lock screen, screen timeout, display, sound, network, other developer options, or any setting this document does not mention.
3. **Never uninstall a phone app or clear its data**, including Mika itself. If uninstalling is needed, explain why and let the user decide and do it.
4. **Never handle the user's model API key.** Do not ask for it, type it, or send it over adb. The user enters it in Mika.
5. **Stop and tell the user whenever a step needs a tap on the phone.** Say what will appear on screen and which button to tap, and wait until the user says it's done. Do not use adb to simulate taps or input to confirm system security prompts for the user, including allowing USB debugging or wireless debugging on a network.
6. **Before each step, briefly tell the user what you are doing and why, in the user's language.** When a command fails, show the user the exact error, then the likely causes.
7. The commands below are written for a Unix-style shell (macOS, Linux, or WSL / Git Bash on Windows). In Windows PowerShell, rewrite them to do the same thing.

The package name is `com.wangzan101.mika`.

## Step 1: Check the prerequisites

### 1.1 adb

```
adb version
```

Success: it prints `Android Debug Bridge version ...`.

If adb is missing, tell the user to download "SDK Platform-Tools" from the Android developer site (there are builds for Windows, macOS and Linux), unzip it and add the folder to PATH, or install platform-tools with their system's package manager. Check again afterwards.

### 1.2 The phone is connected and authorized

```
adb devices
```

Success: **exactly one** device is listed, with state `device`.

- State is `unauthorized`: ask the user to look at the phone and tap "Allow" on the "Allow USB debugging?" prompt (ticking "Always allow from this computer" is fine), then run `adb devices` again.
- The list is empty: ask the user to check that the cable carries data (some cables only charge), that Developer options and USB debugging are on, and to set the USB mode to "File transfer", then try again. Steps 1 and 2 of the [phone control setup guide](setup-guide.en.md) show how to turn on USB debugging.
- More than one device: ask the user to unplug the others or tell you which one to use, then write every command as `adb -s <serial> ...`.

### 1.3 Android version

```
adb shell getprop ro.build.version.sdk
```

- On API 28 or lower (Android 9 or earlier), tell the user their system is too old to install Mika, which requires Android 10 or later. Stop the installation.
- On API 29 or 30 (Android 10 or 11), Mika can be installed for web browsing, files and chat. Tell the user that phone control requires Android 12 (API 31) or later and ask whether to continue installing.
- 31 to 33 (Android 12 and 13): tasks work, but background work may briefly take input focus away from the main screen. 34 or higher (Android 14+) is best.

### 1.4 CPU architecture

```
adb shell getprop ro.product.cpu.abi
```

- `arm64-v8a`: the Linux sandbox works.
- Anything else: Mika installs and works normally, just without the Linux sandbox (Settings shows "not supported on this device"). Let the user know and carry on.

## Step 2: Download the latest APK

Ask the GitHub API for the latest release, pick the all-architecture asset named like `Mika-v0.1.N.apk`, and download its `browser_download_url`:

```
URL=$(curl -fsSL https://api.github.com/repos/wangzan101/Mika/releases/latest \
  | grep -o '"browser_download_url": *"[^"]*/Mika-v[0-9.]*\.apk"' \
  | sed 's/.*"\(https[^"]*\)"/\1/' | head -n 1)
echo "$URL"
curl -fL -o Mika.apk "$URL"
```

Success: `$URL` looks like `https://github.com/wangzan101/Mika/releases/download/v0.1.N/Mika-v0.1.N.apk`, `curl` reports no error, and `Mika.apk` is in the current folder.

If jq is available, parsing the JSON with it is more robust; the idea is the same: take the entry in `assets` whose name matches `Mika-v0.1.N.apk`.

If step 1.4 printed `arm64-v8a`, you can take the asset ending in `-arm64.apk` instead (replace `[0-9.]*\.apk` above with `[0-9.]*-arm64\.apk`): it carries native libraries for that one architecture only, so it is smaller and works the same.

If GitHub cannot be reached: ask the user to open [Releases](https://github.com/wangzan101/Mika/releases/latest) in a browser, download the APK by hand and tell you where it was saved, then continue from step 3.

## Step 3: Install

```
adb install -r Mika.apk
```

Success: the last line is `Success`.

- Some phones show a confirmation on the phone for installs over USB. Ask the user to watch the phone and tap "Allow" or "Install" when it appears. The command waits until they do.
- On failure, show the user the exact error. Common ones:
  - `INSTALL_FAILED_UPDATE_INCOMPATIBLE`: a copy of Mika signed with a different key (for example a self-built one) is already installed. The only way to install this one is to uninstall the old one first, and **its chats and settings will be lost**. Explain this and ask whether the user wants to uninstall it themselves. Do not uninstall it for them. Once they have, run the install again.
  - `INSTALL_FAILED_VERSION_DOWNGRADE`: the Mika on the phone is newer than this APK. No install needed; go to step 4.
  - `INSTALL_FAILED_USER_RESTRICTED` or `INSTALL_FAILED_ABORTED`: the user declined on the phone, or the system blocks installs over USB. Ask the user to look in Developer options for a switch like "Install via USB" and turn it on (only some phones have it), then retry.
  - `INSTALL_FAILED_INSUFFICIENT_STORAGE`: the phone is out of space; ask the user to free some up.

## Step 4: Notification permission (Android 13 and later)

Mika uses notifications to show task progress and to ask the user to take over. Skip this step if the SDK level is below 33.

```
adb shell pm grant com.wangzan101.mika android.permission.POST_NOTIFICATIONS
```

Success: no output.

## Step 5: Turn on Mika's accessibility service

Only do this for phone app control on Android 12+. Skip this step if the phone runs Android 10 or 11, or if the user only needs chat, web and files. In those cases, installation can succeed with accessibility turned off.

Mika uses its accessibility service to read the screen and tap. Turn it on by writing the system setting over adb. The reason: for apps installed from outside an app store, Android often blocks turning accessibility on by hand ("Restricted setting"); writing the setting over adb does not hit that block.

Mika's accessibility service component (from `MikaAccessibilityService` in `app/src/main/AndroidManifest.xml`):

```
com.wangzan101.mika/.a11y.MikaAccessibilityService
```

This is the same component as the long form `com.wangzan101.mika/com.wangzan101.mika.a11y.MikaAccessibilityService`; accept either form when checking whether Mika is already in the list.

**Do not remove other accessibility services the user already has on.** Read the current list, append Mika only if it is missing (entries are separated by colons), and write it back:

```
ME=com.wangzan101.mika/.a11y.MikaAccessibilityService
CUR=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
echo "current: $CUR"
case ":$CUR:" in
  *":$ME:"*|*":com.wangzan101.mika/com.wangzan101.mika.a11y.MikaAccessibilityService:"*)
    NEW="$CUR" ;;
  *)
    if [ -z "$CUR" ] || [ "$CUR" = "null" ]; then NEW="$ME"; else NEW="$CUR:$ME"; fi ;;
esac
adb shell settings put secure enabled_accessibility_services "'$NEW'"
adb shell settings put secure accessibility_enabled 1
adb shell settings get secure enabled_accessibility_services
```

Success: the last output contains Mika's component, and every service that was there before is still there.

Notes: `settings get` prints `null` for a setting that was never written; treat it as an empty list. The value is wrapped in single quotes when written back because some service names contain a `$`.

## Step 6: Battery allowlist

Add Mika to the system's battery-optimization allowlist so background and scheduled tasks are not cut off:

```
adb shell cmd deviceidle whitelist +com.wangzan101.mika
```

Success: it prints `Added: com.wangzan101.mika` (also when it was already there).
If the phone does not accept `cmd`, use `adb shell dumpsys deviceidle whitelist +com.wangzan101.mika` instead; it does the same.

Check:

```
adb shell cmd deviceidle whitelist | grep com.wangzan101.mika
```

## Step 7: Start Mika's phone helper

First, open Mika once (needed for all uses, to initialize the app and connection scripts):

```
adb shell am start -n com.wangzan101.mika/.ui.MainActivity
```

`adb shell monkey -p com.wangzan101.mika 1` also works. On first launch Mika walks the user through adding a model; they can skip that for now ("Not now" or similar) and do it in step 8.

After opening the app, go straight to step 8 if the phone runs Android 10 or 11, or if the user only needs chat, web and files. Only phone app control requires the helper to be ready.

For phone app control, the goal is to give Mika's built-in helper the adb shell identity (uid 2000). Both wireless debugging pairing and USB debugging can provide that identity: pair when the system has a working Wireless debugging page; if the system has removed that page, start over USB directly without a pairing code.

### Check whether the Wireless debugging page exists

Before pairing, run these read-only checks:

```
adb shell cmd package resolve-activity --brief -a android.settings.WIRELESS_DEBUGGING_SETTINGS
adb shell dumpsys package com.android.settings | grep -iE 'wireless.?debug|AdbWireless|AdbPairing'
```

- If the first command returns `No activity found` and the second prints nothing, the system no longer has this page. Tell the user there is no need to keep looking in Settings, and do not repeatedly turn on Wireless debugging and wait for a pairing code. Go directly to option B below.
- If an activity or related component is found, the page may exist; continue with option A. If the user still cannot open the pairing page, or no pairing code appears after turning on the switch, use B instead of repeatedly toggling the switch.
- If a command fails or its output is inconclusive, show the user the exact error. This does not prove that the page was removed. With USB already connected and authorized, use B directly rather than probing private system implementation details.

If the user has not chosen a startup method, explain the difference:

- **A. Pair wireless debugging (recommended, set up once)**: pair on the phone. With the required permissions retained and wireless debugging and network conditions met, it can recover after a reboot without a computer.
- **B. Start over USB**: run once with the cable connected, without pairing. Run it again after each phone reboot unless A can already recover automatically.

You can use B first, then configure A for recovery. This pairing starts Mika's phone helper; it does not pair the computer for wireless ADB or enable computer access. Computer access is covered in step 10.

### Optional: allow Mika to modify system settings

This permission lets Mika turn on Wireless debugging for automatic recovery when that capability is available. **It is not required to start the helper over USB, and it cannot restore a pairing page removed by the system.** Explain that it changes the phone's permission state and get the user's consent before running these commands. If the user declines, skip this step; B still works:

```
adb shell pm grant com.wangzan101.mika android.permission.WRITE_SECURE_SETTINGS
adb shell dumpsys package com.wangzan101.mika | grep 'WRITE_SECURE_SETTINGS: granted'
```

Success: the second command prints `granted=true`.

### A. Pair wireless debugging

1. After confirming that the checks above did not show the page to be removed, turn on Wireless debugging:

   ```
   adb shell settings put global adb_wifi_enabled 1
   adb shell settings get global adb_wifi_enabled
   ```

   Success: the second command prints `1`. The phone must be on Wi-Fi (internet access not required). If the phone asks "Allow wireless debugging on this network?", ask the user to tap "Allow" and tick "Always allow on this network". If it reads back `0`, the phone is probably not on Wi-Fi, or the user did not allow it in that prompt. If the switch turns on but no pairing code dialog appears, do not keep writing this setting; use B instead.

2. Tell the user: in Mika, open Settings › Phone control to reach the "Set up phone control" (设置手机操作) wizard. (It also opens on first launch after the model setup. If it does not show up, tap the notice in the status card at the top of Settings, or tap Settings › Phone control › Pair again.) The wizard checks Developer options, USB debugging and Wireless debugging by itself and ticks the steps that are already done.

3. The user has to pair on the phone themselves: in the system's "Wireless debugging" page, tap "Pair device with pairing code", and enter the 6-digit code in Mika's notification (or let Mika read it). You cannot pair for them. Wait until the user says pairing is done.

4. For where to tap at each step and what to do when stuck, point the user to the [phone control setup guide](setup-guide.en.md).

### B. Start over USB

Mika must have been opened at least once, so it can write its start script to the phone. First check the files and read the script to confirm that it is Mika's generated start script; do not modify it:

```
adb shell ls /sdcard/Android/data/com.wangzan101.mika/files/
adb shell cat /sdcard/Android/data/com.wangzan101.mika/files/usb-start.sh
```

The file list should contain `usb-start.sh`. With the cable plugged in, run:

```
adb shell sh /sdcard/Android/data/com.wangzan101.mika/files/usb-start.sh
```

- If it says the file does not exist: open Mika with the command above, wait a few seconds and try again. If it is still missing, the Mika version on the phone may not have this feature yet; use A instead.
- If it says `Mika was updated or reinstalled`, `Mika helper did not start` or `is not running`: open Mika once so it regenerates the script for the current APK, then run the same command again.
- After unplugging, check the helper status on the phone. If phone control becomes unavailable, reconnect and run this command again, or recover through an already-paired option A.
- After a phone reboot, run this command again from the computer unless option A successfully restores the helper. Open Mika once first after the reboot.
- Do not run `connect-computer.sh` to pair or start the phone helper. It enables computer access in step 10, starts a local service and generates a token; run it only when the user explicitly requests computer access.
- Do not use `adb tcpip 5555` and `adb connect <phone-IP>:5555` as the default alternative for this flow: that is network ADB from the computer to the phone and may be blocked by Wi-Fi AP isolation. Mika's own pairing and connection use `127.0.0.1` on the phone and are unaffected by AP isolation, so there is no need to change router settings for this.

### Check that it is running

You can use the process list as a supplementary check:

```
adb shell ps -A -o USER,PID,NAME | grep -i mika
```

You will normally see `shell ... com.wangzan101.mika:helper` and `u0_aXXX ... com.wangzan101.mika`. A running process alone does not prove that handoff has completed. Finally, ask the user to open Mika's Settings and look at the status card at the top: "Phone control" shows ready (with a model set up too, it says "Model set up · Phone control ready"). If it says "Phone control isn't ready", redo the steps above or see "Troubleshooting" below.

## Step 8: Add a model

Tell the user: in Mika, open Settings › Model, add a provider, enter their own API key and tap "Test and save"; or, when adding a provider, tap "Continue with ChatGPT" and sign in with a ChatGPT account (Plus or Pro required).

**The user does this step entirely on the phone.** Do not ask for the key, and do not type or send it over adb.

## Step 9: Check the result

```
adb shell dumpsys package com.wangzan101.mika | grep versionName
adb shell settings get secure enabled_accessibility_services
```

Success: the first shows the currently installed version. If step 5 was performed, the second should contain Mika's component; it is not required when phone control setup was skipped.

If task execution needs to be verified, suggest a simple task for the user to send in Mika themselves. With phone control configured, they can try:

```
Open Settings and tell me the Android version
```

For chat, web and files only, start with an ordinary question. If no task was run, report "Installation and setup checked; task execution unverified."

## Step 10 (optional): Use Mika from the computer (CLI + Skill or MCP)

Do this only when the user requests computer access. **Normally choose CLI + Skill or MCP; do not set up both by default.** Keep the entry point and Skill location the user already specified; otherwise use the table in [Use Mika from a computer](computer.en.md) to establish the choice.

- **CLI + Skill**: use the Skill installation section; no prior pipx or global CLI installation is needed. Complete its connection checks, then skip the MCP steps below. For terminal/script use alone, install just the CLI.
- **MCP**: continue with the USB steps below; no CLI or Skill installation is needed. Access uses ADB forwarding; other connection methods are in the [MCP guide](mcp.en.md).

Keep the phone selected in step 1 for all ADB operations: pass the same `--device SERIAL` to CLI / Skill commands and use `adb -s <serial> ...` below. Submit test tasks only when the user requested task verification.

1. Turn it on. This also attempts to start the phone helper from step 7 when needed:

   ```
   adb shell sh /sdcard/Android/data/com.wangzan101.mika/files/connect-computer.sh
   ```

   Success: it ends with "Computer access over USB is on" and prints a JSON object with `url`, `headers` and `token`. If it says the file doesn't exist, open Mika once (step 7), wait a few seconds and try again.

2. Forward the port:

   ```
   adb forward tcp:8765 tcp:8765
   ```

3. Read the token again whenever you need it (it is the `token` field):

   ```
   adb shell cat /sdcard/Android/data/com.wangzan101.mika/files/mcp-usb.json
   ```

4. Check that it answers (put the token in):

   ```
   curl -s http://127.0.0.1:8765/mcp \
     -H "Authorization: Bearer <token>" \
     -H "Content-Type: application/json" \
     -H "Accept: application/json, text/event-stream" \
     -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'
   ```

   Success: a JSON reply whose `result` has `serverInfo`. This verifies service reachability, authentication and the MCP handshake, not client configuration loading or model task success.

5. The MCP client config: a Streamable HTTP (often called "http") server with URL `http://127.0.0.1:8765/mcp` and the header `Authorization: Bearer <token>`. This is a common JSON example; use the format required by the selected client:

   ```json
   {
     "mcpServers": {
       "mika": {
         "type": "http",
         "url": "http://127.0.0.1:8765/mcp",
         "headers": { "Authorization": "Bearer <token>" }
       }
     }
   }
   ```

   Add Mika to the user's selected client and configuration scope, preserving other entries. Use your own MCP configuration only when the user chose the current assistant. If a restart or reload is needed, tell the user, then confirm that the client discovers Mika's tools. If this cannot be checked, report "Client loading unverified." Don't show the token in the chat unless the user asks for it.

Tell the user:

- After unplugging and replugging the cable (or after adb restarts), `adb forward tcp:8765 tcp:8765` has to be run again. The token stays the same.
- After a phone restart USB mode is off. Run the command in 1 again; the token changes then, so the MCP configuration needs the new one.
- To turn it off: `adb shell sh /sdcard/Android/data/com.wangzan101.mika/files/connect-computer.sh off`, the switch in Mika's Settings › Connect a computer › Advanced settings, or "Turn off" on the notification. The old token stops working at once.

## Troubleshooting

| Problem | What to do |
|---|---|
| The phone is not in `adb devices` | Use a cable that carries data; check that USB debugging is on; set the USB mode to "File transfer"; unplug and plug in again. |
| State is `unauthorized` | Ask the user to tap "Allow" on the phone's "Allow USB debugging?" prompt. No prompt: unplug and replug, or tap "Revoke USB debugging authorizations" in Developer options and plug in again. |
| The install is blocked | Ask the user to check the phone for a confirmation prompt, and to turn on a switch like "Install via USB" in Developer options if there is one. For the different-signature error see step 3; uninstalling is the user's call. |
| Accessibility turns itself off again | Redo step 5. Once wireless debugging is paired, Mika turns it back on by itself (on by default in Settings › Recovery & repair). |
| The phone helper is not running after a restart | With A: connect to Wi-Fi and wait a moment; Mika recovers by itself. If not, tap Pair again in Settings › Phone control. With only B: run the command from step 7 B again from the computer, or set up A. |
| GitHub cannot be reached | Ask the user to open [Releases](https://github.com/wangzan101/Mika/releases/latest) in a browser and download the APK by hand, then run `adb install -r <path to the APK>`. |
| Wireless debugging won't turn on | The phone must be on Wi-Fi, and the user must allow wireless debugging on this network in the prompt. If it still won't, use B. |
| The MCP client can't reach Mika over USB (step 10) | Run `adb forward tcp:8765 tcp:8765` again (needed after every replug). After a phone restart, run the step 10 command that turns it on again and update the token. |
| The manual MCP forward fails with `cannot bind`, or the reply isn't from Mika (step 10) | Port 8765 is taken on the computer. Forward from another free port, e.g. `adb forward tcp:18765 tcp:8765`, and use `http://127.0.0.1:18765/mcp` in the config; nothing changes on the phone. |

## Finally: a short summary for the user

When everything is done, tell the user in their language:

- **What was done**: the current APK version; the chosen computer access method and components actually installed; CLI/Skill version tag and location, or the target MCP client. Report installation, connectivity, Skill/MCP tool discovery and real task execution separately. Report notifications, accessibility, battery settings and the helper according to the required scope, identifying skipped or unverified steps.
- **What the user still needs to do**: configure a model on the phone if needed; complete pairing if phone control and option A were chosen; with B only, restart the helper after a phone reboot. For task verification, have the user send a simple task or test within their existing authorization. If step 10 was performed, link the CLI/Skill [maintenance guide](computer-install.en.md) and explain that ADB access needs `connect` after reboot. For MCP, report whether the client loaded the tools and explain USB forwarding after replugging and token renewal after reboot.
