# AGENTS.md

This repository is the Tauri desktop shell for DeepSeek Harness. Product rules live in [docs/design.md](docs/design.md).

- UI components must not import `@tauri-apps/*`. Use `src/infrastructure/adapters`.
- Keep BitFun layout geometry unless the change is intentional: NavBar/SceneBar 38px, macOS NavBar padding-left 78px, nav default 240px.
- Spawned `dsh web` binds loopback only. Reject non-loopback URLs in `parse_dsh_web_url`.
- Do not vendor BitFun editor/git/miniapp/peer-device code.
- Product copy is Chinese by default; keep `zh.ts` / `en.ts` keys aligned.
