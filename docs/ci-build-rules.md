# CI 构建规则

本文档固化了 GitHub Actions CI 的构建规则和约定，确保所有贡献者遵循统一标准。

## 1. 触发条件

### 1.1 构建工作流 (build.yml)

| 事件 | BRAND | 构建产物 | Release |
|------|-------|---------|---------|
| push → `v2` 分支 | safeopc | dmg (macOS) + nsis (Windows) | 否 |
| push → `v2-branch` 分支 | tupai | dmg (macOS) + nsis (Windows) | 否 |
| push → `v*` tag | tupai | dmg + nsis + app | 是 (GitHub Release) |
| workflow_dispatch | 手动选择 | dmg + nsis | 仅 tag 时有 Release |

### 1.2 代码质量验证工作流 (ci-validate.yml)

> 借鉴 understudy 的 CI 模式 — push 时运行 lint + typecheck + cargo check。

| 事件 | 验证内容 | 阻塞构建 |
|------|---------|---------|
| push → `v2` / `v2-branch` / `main` | 前端 tsc + Rust cargo check + i18n | 否（Phase 1 非阻塞） |
| pull_request | 同上 | 否（Phase 1 非阻塞） |
| workflow_dispatch | 同上 | 否 |

**Jobs：**
- `validate-frontend` — TypeScript 类型检查 + ESLint + 前端构建检查
- `validate-rust` — `cargo check --lib` + `cargo check --all-targets` + clippy
- `validate-i18n` — 三语种 locale JSON 合法性检查

> Phase 1 所有检查均为非阻塞 (`continue-on-error: true`)，用于收集代码质量信号。
> Phase 2 将逐步启用阻塞模式。

## 2. 平台与架构矩阵

### macOS
- **aarch64-apple-darwin** → `macos-latest` (Apple Silicon 原生编译)
- **x86_64-apple-darwin** → `macos-latest` (Apple Silicon 交叉编译，不再使用已淘汰的 `macos-13`)

> ⚠️ **禁止使用 `macos-13`**：该 Intel runner 已被 GitHub 淘汰，会导致构建任务无限排队。

### Windows
- **x86_64-pc-windows-msvc** → `windows-latest`

## 3. 构建产物

| 平台 | Bundle 类型 | 产物路径 | 产物命名 |
|------|------------|---------|---------|
| Windows | `nsis` | `src-tauri/target/x86_64-pc-windows-msvc/release/bundle/nsis/` | `*_x64-setup.exe` |
| macOS ARM | `dmg` | `src-tauri/target/aarch64-apple-darwin/release/bundle/dmg/` | `*_aarch64.dmg` |
| macOS x64 | `dmg` | `src-tauri/target/x86_64-apple-darwin/release/bundle/dmg/` | `*_x64.dmg` |

## 4. Artifact 上传规则

- **分支推送**：所有构建产物通过 `actions/upload-artifact@v4` 上传为 CI Artifact，可在 Actions 页面直接下载。
- **Tag 推送**：通过 `tauri-action` 自动上传到 GitHub Release。
- Artifact 保留期限：30 天。
- Artifact 命名规则：`{brand}-{platform}-{arch}`，如 `safeopc-windows-x64`、`safeopc-macos-aarch64`。

## 5. 缓存策略

| 缓存项 | 工具 | Key 策略 |
|--------|------|---------|
| pnpm store | `pnpm/action-setup@v4` (内置) | `pnpm-lock.yaml` hash |
| Rust target | `Swatinem/rust-cache@v2` | `Cargo.lock` hash + target triple |
| 前端 dist | `actions/cache@v4` | `frontend-dist` + lockfile hash |

## 6. 加速优化

1. **前端只构建一次**：通过 `build-frontend` job 统一构建，后续平台 job 依赖此产物。
2. **macOS 交叉编译**：x86_64 在 ARM runner 上交叉编译，避免使用已淘汰的 `macos-13`。
3. **只构建需要的 bundle**：macOS 只构建 `dmg`，Windows 只构建 `nsis`，跳过 `app` 和其他格式。
4. **concurrency 取消旧构建**：同分支新 push 自动取消正在进行的旧构建。
5. **Rust 增量缓存**：`Swatinem/rust-cache` 按 target triple 分组缓存。

## 7. 失败诊断

- 构建失败时，最后 250 行日志自动写入 `$GITHUB_STEP_SUMMARY`。
- 可在 Actions 运行页面的 Summary 标签页直接查看错误摘要。
- Artifact 即使在构建失败时也会上传（如果产物已生成）。

## 8. 多品牌配置

项目支持多品牌（tupai / safeopc），通过 `src-tauri/tauri.{brand}.conf.json` 覆盖基础配置：

- `tupai` — 默认品牌
- `safeopc` — OEM 版本，v2 分支推送时自动使用

切换品牌通过 `--config src-tauri/tauri.{BRAND}.conf.json` 参数实现。

## 9. 禁止事项

- ❌ 禁止在 `.cargo/config.toml` 中硬编码本地路径（如 `E:\...`），会导致 CI runner 构建失败。
- ❌ 禁止使用 `macos-13` runner。
- ❌ 禁止在分支推送时构建全部 bundle 类型（只构建 dmg/nsis）。
- ❌ 禁止移除 `concurrency` 配置（会导致资源浪费）。

## 10. 提交前验证清单

> **教训**：2026-07-23 —— `cargo check --lib` 通过但 `cargo test --lib` 失败：测试代码中的 `STATUS_PENDING_CONFIRM` 未导入只在 `#[cfg(test)]` 编译时暴露。`settingsTabSearchContent.ts` 缺 `mesh` 条目导致 `tsc --noEmit` 类型错误。两类问题都不在常规 `cargo check` / `cargo build` 中暴露。

### 10.1 Rust 验证

| 命令 | 覆盖范围 | 说明 |
|------|---------|------|
| `cargo check --lib` | lib 代码（不含 test） | 最快，日常迭代用（`pnpm check:fast`） |
| `cargo check --all-targets` | lib + test + bench + examples | **推送前必跑**，覆盖 `#[cfg(test)]` 代码 |
| `cargo test --lib <module>` | 实际执行测试 | 验证测试逻辑正确性 |

**关键**：`cargo check --lib` **不编译** `#[cfg(test)]` 模块。测试代码中的 import 错误、类型错误只在 `cargo check --all-targets` 或 `cargo test` 时暴露。推送前必须跑 `cargo check --all-targets`。

### 10.2 前端验证

| 命令 | 覆盖范围 | 说明 |
|------|---------|------|
| `npx tsc --noEmit` | 全项目 TypeScript 类型 | 检查 `Record<Enum, T>` 完整性、类型契约一致性 |
| `npx eslint src/<changed-dirs>` | 改动目录 lint | 检查代码风格、未使用变量、react-hooks 依赖 |
| `npx vitest run src/<changed-dirs>` | 改动目录单元测试 | 验证 mock + 断言 |

### 10.3 i18n 验证

新增 `t('namespace.key')` 调用时，必须检查三语种 locale 文件中都已定义对应键：

```powershell
# 验证三语种 JSON 合法性 + 键存在
node -e "const fs=require('fs'); for (const f of ['src/locales/en-US/common.json','src/locales/zh-CN/common.json','src/locales/zh-TW/common.json']) { try { JSON.parse(fs.readFileSync(f,'utf8')); console.log('OK:', f); } catch(e) { console.log('FAIL:', f, e.message); } }"
```

### 10.4 tauri.conf.json 验证

修改 `tauri.conf.json` 的 `plugins` 段后，必须本地 `pnpm tauri dev` 启动验证（详见 `CLAUDE.md`「tauri.conf.json 插件配置规则」）。`cargo build` / `cargo check` **不会** 校验配置文件——错误只在运行时 `app.run()` 暴露。

### 10.5 验证流程

```
代码改动完成
  → cargo check --all-targets     (Rust 全量检查)
  → npx tsc --noEmit               (前端类型检查)
  → npx eslint src/<changed-dirs>  (前端 lint)
  → npx vitest run src/<changed>   (前端测试)
  → cargo test --lib <module>      (Rust 测试)
  → git add + git commit           (分批提交)
  → git push tencent v2            (推送腾讯云)
```
