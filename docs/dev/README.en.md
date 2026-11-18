# Developer docs

[简体中文](README.md) | English

For people who want to read or change the code, or add knowledge. For building and opening PRs, read the [contributing guide](../../.github/CONTRIBUTING.md) first. User docs are [one level up](../README.en.md).

Apart from the knowledge format, the developer docs are in Chinese for now.

## Read first

| Doc | What it covers |
| --- | --- |
| [architecture.md](architecture.md) | The overall structure as the code stands, with class names and file paths: main loop, tools, phone control, browser, sandbox, computer access, knowledge and skills |
| [roadmap.md](roadmap.md) | Done, next, known issues and deferred items |

## Writing knowledge and skills

| Doc | What it covers |
| --- | --- |
| [knowledge-format.en.md](knowledge-format.en.md) ([简体中文](knowledge-format.md)) | Format of built-in app knowledge (`APP.md`), website knowledge (`SITE.md`) and skills (`SKILL.md`) |
| [skill-scripts.md](skill-scripts.md) | Where scripts that come with knowledge and skills go, and how they run in the sandbox |

## Topic notes and verification records

| Doc | What it covers |
| --- | --- |
| [account-signin.md](account-signin.md) | Design of signing in to model providers with an account (no key), and what is deliberately left out |
| [persona.md](persona.md) | Named custom instructions (persona): prompt position, storage, per-chat binding and safety boundaries (Chinese) |
| [chatgpt-signin-check.md](chatgpt-signin-check.md) | Walking through "Sign in with ChatGPT" on a computer with the app's own code, before trying it on a phone |
| [remote-device-access-check.md](remote-device-access-check.md) | Verification record for remote wake and unlock |
| [remote-mcp.md](remote-mcp.md) | Remote CLI / MCP design (Chinese); [English deployment guide](../../tools/relay/README.md) |
| [teach-mika.md](teach-mika.md) | How a demonstration becomes a skill draft waiting for review (Chinese) |

## Developer docs elsewhere in the repo

- [`eval/README.md`](../../eval/README.md): on-device evaluation.
- [`tools/sandbox/README.md`](../../tools/sandbox/README.md): rebuilding the sandbox's native programs and bundled runtime.
