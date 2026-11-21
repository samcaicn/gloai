# Mika documentation

[简体中文](README.md) | English

All of Mika's documentation lives here. If you're new, reading the "Getting started" docs in order is enough; look up the rest when you need it.

## Getting started (in order)

| Doc | What it covers | When to read it |
| --- | --- | --- |
| [Models and sign-in](models.en.md) | Adding a model provider, signing in with an account, reasoning effort, models on your own computer or LAN | First thing after installing |
| [Setting up phone control](setup-guide.en.md) | Pairing wireless debugging, accessibility, starting from a computer over USB, step by step | When you want Mika to operate phone apps; skip it if you only chat, browse and work with files |
| [Ask and Autonomous](modes.en.md) | Which steps Mika asks you about first in each mode | Before you hand it real work |

## Using it

| Doc | What it covers |
| --- | --- |
| [Features in detail](features.en.md) | The details behind each item under "What it can do" in the README: background screen, browser, the screen you're looking at, schedules, interrupting, memory, sandbox and more |
| [FAQ](faq.en.md) | Questions about installing, phone control, models, computers, language and privacy |
| [For AI assistants: writing a persona](persona-writing.en.md) | Persona-writing rules for an AI assistant: what a persona can change, picking options, writing instructions, how to deliver |
| [Disclaimer, data and privacy](disclaimer.en.md) | Beta notes, risks of use, where your data is stored and where it goes |

## Using it from a computer

Start with [Use Mika from a computer](computer.en.md): pick CLI + Skill or MCP, and USB or Wi-Fi / VPN. Then follow the matching doc below.

| Doc | What it covers |
| --- | --- |
| [CLI / Skill: install, update and remove](computer-install.en.md) | Installing the `mika` command or the Skill for your agent |
| [CLI and Skill: connection and task commands](cli.en.md) | Commands to connect to the phone, submit tasks, check progress, answer questions and add instructions |
| [Feishu bot](feishu.en.md) | QR creation, linking an existing app, private-message tasks and results |
| [Computer access (MCP)](mcp.en.md) | Letting an MCP client hand tasks to Mika: USB, Wi-Fi / VPN, tools, security |
| [Install with an AI agent on your computer](install-with-agent.en.md) | Installation steps written for an AI agent on your computer: with the phone on USB, hand it this doc and it sets things up for you |

## Developing Mika

To contribute code or add usage knowledge for apps and websites, read the [contributing guide](../.github/CONTRIBUTING.md) first, then the [developer docs](dev/README.en.md) (architecture, knowledge format, roadmap and more).
