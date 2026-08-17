# DeepSeek Harness GUI

English | [中文](README.zh.md)

Made by [BitFun](https://github.com/GCWing/BitFun/).

Tauri 2 desktop shell for [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness). The window chrome, adapter split, and welcome/workspace layout follow [BitFun](https://github.com/GCWing/BitFun)'s desktop + web-ui design. The session surface is the official `dsh web` GUI, loaded in a loopback iframe after this app spawns the runtime.

Design: [docs/design.md](docs/design.md).

## Requirements

- Node.js `^22.19 || >=24`
- pnpm 10+
- Rust (stable), for `pnpm desktop:dev` / `pnpm desktop:build`
- A DeepSeek API key (`DEEPSEEK_API_KEY`)
- `dsh` on PATH, or `npx` (the app falls back to `npx --yes @deepseek-ai/dsh@^0.1.0-rc.6`)

## Commands

```sh
pnpm install
pnpm test
pnpm typecheck
pnpm test:smoke
pnpm desktop:dev
pnpm desktop:build
```

`pnpm desktop:dev` starts Vite on port 1420 and the Tauri window. Open a workspace from the welcome scene; the host starts `dsh web --host 127.0.0.1 --port 0` in that directory and embeds the printed loopback URL.

`pnpm test:smoke` spawns a real `dsh web` (PATH `dsh`, else `npx @deepseek-ai/dsh`) on loopback, waits for the `dsh web:` URL, and GETs it. It skips if neither launcher exists. A placeholder `DEEPSEEK_API_KEY` is enough to bind the port.

Window size and position are restored across launches. A second process focuses the existing window. `pnpm icons` regenerates tray and bundle icons from `src-tauri/icons/app-icon.svg`.

## Settings

- API key: OS keychain when available, otherwise the app settings file
- Optional harness executable override
- Theme (dark / light / system) and locale (zh / en)
- Close-to-tray

## Layout (BitFun-aligned)

```
NavBar (38px, macOS traffic-light inset) + NavPanel
SceneBar + Welcome | Session (dsh web iframe) | Settings
```

UI components never call Tauri APIs. Native work goes through `src/infrastructure/adapters`.

## License

MIT
