# Computer CLI / Skill: install, update and remove

[简体中文](computer-install.md) · [Connection and task commands](cli.en.md) · [MCP configuration](mcp.en.md)

The starting point for using Mika from a computer is [Use Mika from a computer](computer.en.md): pick CLI + Skill or MCP there, and USB or Wi-Fi / VPN. This page only covers installing, updating and removing the CLI and the Skill; if you use an MCP client, skip it and follow the [MCP guide](mcp.en.md).

Agents wanting a fixed shortest path: see the [agent quick path](agent-quickstart.en.md).

## Before you start

- To let a computer agent that can run commands and supports Skills use Mika, install the [Skill](#skill-install). It includes its own CLI script, so no separate CLI install is needed.
- To submit tasks from a terminal or script, install the [CLI](#cli-install), which gives you the `mika` command.

On the phone: install the APK ([Releases](https://github.com/wangzan101/Mika/releases/latest)), open Mika once and add a model. **If Mika only needs to chat, browse the web and work with files, you can skip accessibility and the phone helper, and Android 11 works**; only phone app control needs the phone-control setup in the [setup guide](setup-guide.en.md) (Android 12+). Installing or updating the computer tools does not install or update the APK on the phone.

Below, `<tag>` means the version tag of the [latest release](https://github.com/wangzan101/Mika/releases/latest) (the `v0.1.x` in its title). Keep the computer tools and the phone APK on the same version where you can.

## CLI: install

Requires Python 3.10+, Git and pipx; ADB connections also need Android platform-tools. Install:

```bash
pipx install 'git+https://github.com/wangzan101/Mika.git@<tag>#subdirectory=companions/mika'
mika --version
```

The pipx package is `mika-phone-cli` and the command is `mika`. The CLI has its own version number, separate from the APK's. With a repository checkout you can also run `pipx install ./companions/mika` from the repository root, or skip installing and run `python3 companions/mika/scripts/mika_cli.py` directly (`py -3` on Windows).

## CLI: update, roll back and remove

To update, use the new `<tag>`; to roll back, use the previous one; `--force` replaces the installed copy:

```bash
pipx install --force 'git+https://github.com/wangzan101/Mika.git@<tag>#subdirectory=companions/mika'
```

`pipx upgrade` does not move to another tag; use the command above to change versions. To remove:

```bash
pipx uninstall mika-phone-cli
```

## Skill: install

The Skill is the repository's `companions/mika` folder: `SKILL.md` tells the agent how to use it, and `scripts/mika_cli.py` is the bundled CLI, which needs only Python 3.10+ (plus platform-tools for ADB connections).

1. From the [latest release](https://github.com/wangzan101/Mika/releases/latest), download **Source code (zip)** and extract it.
2. Copy the **whole** `companions/mika` folder into your agent's skills directory (see that agent's own documentation for where it is). The result should be `<skills-directory>/mika/SKILL.md`, without an extra nested `mika` folder.
3. Check that the bundled script runs:

   ```bash
   python3 <skills-directory>/mika/scripts/mika_cli.py --version   # use py -3 instead of python3 on Windows
   ```

4. Start a new agent session and confirm it can see a Skill named `mika`.

If a `mika` command is already installed on the computer, the Skill uses it first. Updating the Skill does not update that CLI; maintain each separately.

## Skill: update, roll back and remove

- **Update or roll back**: move the old `mika` folder out of the skills directory as a backup, then copy the whole `companions/mika` folder of the target version. Do not copy over the old folder.
- **Remove**: delete only the `mika` folder in the skills directory.

Start a new agent session after any change.

## After installing: check the connection

With only the Skill installed, replace `mika` below with `python3 <skills-directory>/mika/scripts/mika_cli.py` (`py -3` on Windows).

Over USB or connected wireless ADB:

```bash
mika devices --json
mika connect --device SERIAL --json
mika doctor --device SERIAL --json
```

Directly over Wi-Fi / a private network (first turn on network access in Settings › Connect a computer on the phone):

```bash
mika --url http://PHONE:8765 --token-file /path/to/private-token-file doctor --json
```

**A successful `connect` or `doctor` does not mean a task gets done.** They only show that the API is reachable and device state is readable; `doctor` reports the model configuration as unverified, and neither shows that the agent has loaded the Skill. To verify a real task, submit a simple one with `mika task run`, then use `mika task wait` until it reports `done` and check the result; see [Task commands](cli.en.md#tasks).

| Symptom | What to do |
| --- | --- |
| `incompatible_api` | The APK on the phone is too old. The message names the phone version and the missing capability: update the APK, or rerun without the gated option (for example `--request-id`; the task still runs, only recovery after a lost receipt is unavailable). |
| `stale_connection` | The phone has rebooted; run `connect` again. |
| `device_selection` | More than one phone is online; find the serial with `mika devices` and add `--device SERIAL`. |
| `connection_info` | Mika's connection information could not be read (the phone is locked, Mika hasn't been opened yet, and so on): unlock the phone, open Mika once, then run `connect` again. |
| `adb` / `adb_unavailable` | Make sure adb is on PATH and the phone shows as `device` in `adb devices`, open Mika once on the phone, then run `connect` again. |
| The agent does not see the Skill | Check for an extra nested folder and that `scripts/` was copied too, then start a new session. |

Removing the computer tools does not delete phone data or turn off computer access on the phone. To stop remote access, turn off the relevant switch in Settings › Connect a computer on the phone.
