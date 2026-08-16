// Copyright (c) 2026 AIMarketing
//
// tauri-dev.mjs — tauri dev / build 调用的 sccache 加速包装
//
// 作用：
//   * 探测本机是否安装 sccache；安装了则注入
//     CARGO_BUILD_RUSTC_WRAPPER=sccache + CARGO_BUILD_INCREMENTAL=false，
//     让 tauri dev / build 的公共依赖编译产物被缓存（切分支、cargo
//     clean 后、重编都能快速回填，而非全量重编）。
//   * 未安装 sccache 时不做任何事，行为与原生 `tauri` 命令完全一致
//     （不阻断开发 / 打包）。
//   * 透传所有命令行参数（如 build、--profile release-fast）与 stdio，
//     并正确转发 SIGINT/SIGTERM，确保 Ctrl-C 直接作用于 tauri 进程。
//
// 用法（package.json 的 dev:* / build:tauri / *:fast 已接入）：
//   node scripts/tauri-dev.mjs                 # 等价 tauri dev（带 sccache）
//   node scripts/tauri-dev.mjs build           # 等价 tauri build（带 sccache）
//   node scripts/tauri-dev.mjs --profile release-fast
//   未带子命令或以 `-` 开头的参数时自动补 `dev`，确保 `tauri dev` 的
//   所有既有调用都能直接替换为本包装器。

import { spawn, spawnSync } from "node:child_process";

// ── sccache 加速 ─────────────────────────────────────────────────
// src-tauri 与 up/cua 会各自重编 tokio / serde 等公共依赖。sccache
// 按源码哈希缓存 rustc 产物，可在两次 workspace 之间去重公共依赖的
// 编译产物，并在 cargo clean / 切分支后快速回填。注意 sccache 在
// incremental 开启时不会缓存，因此显式把 CARGO_BUILD_INCREMENTAL 关掉。
function resolveSccacheEnv() {
  const probe = spawnSync(process.platform === "win32" ? "where" : "which", ["sccache"], {
    stdio: "ignore",
  });
  if (probe.status !== 0) {
    console.log("[tauri-dev] 未检测到 sccache，跳过加速（构建仍正常）");
    return {};
  }
  console.log("[tauri-dev] 启用 sccache 缓存（CARGO_BUILD_INCREMENTAL=false）");
  return {
    CARGO_BUILD_RUSTC_WRAPPER: "sccache",
    CARGO_BUILD_INCREMENTAL: "false",
  };
}

let args = process.argv.slice(2);
// 缺省子命令（或首个参数以 `-` 开头，如 --profile）时补 `dev`，
// 让 `node scripts/tauri-dev.mjs` 与 `node scripts/tauri-dev.mjs --profile x`
// 都等价于 `tauri dev` / `tauri dev --profile x`。
if (args.length === 0 || args[0].startsWith("-")) {
  args = ["dev", ...args];
}
const sccacheEnv = resolveSccacheEnv();

// 在 Windows 上 `tauri` 实际是 tauri.cmd，用 shell:true 让 cmd 解析；
// 在 Unix 上同样经 shell 查找 node_modules/.bin/tauri。npm 运行 script
// 时会把 node_modules/.bin 加入 PATH，故此处能正确找到 tauri 命令。
const child = spawn("tauri", args, {
  stdio: "inherit",
  env: { ...process.env, ...sccacheEnv },
  shell: true,
});

// 转发信号：Ctrl-C / 终止信号直达 tauri 进程，避免 wrapper 成为僵尸父。
const forward = (sig) => {
  if (child.pid) {
    try {
      process.kill(child.pid, sig);
    } catch {
      // 子进程可能已退出，忽略
    }
  }
};
process.on("SIGINT", () => forward("SIGINT"));
process.on("SIGTERM", () => forward("SIGTERM"));

child.on("exit", (code, signal) => {
  if (signal) process.exit(1);
  process.exit(code ?? 0);
});
