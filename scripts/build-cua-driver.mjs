// Copyright (c) 2026 tupAI
//
// build-cua-driver.mjs — 开发期 cua-driver 二进制构建助手
//
// 作用：
//   * 在 `up/cua` (vendored trycua/cua) 工作区内以 debug 配置构建 cua-driver
//     （兼容精简 vendor 副本与完整 monorepo 两种目录布局，见 resolveCuaWorkspaceRoot）
//   * 构建前做 Windows/MSVC 工具链自检：提前发现缺 “MSVC Spectre-mitigated
//     libs” 这类问题并以人话提示，避免 cargo 编译到一半才甩 cryptic 错误
//   * 二进制已存在时自动跳过，避免每次 `tauri dev` 都重新编译（cua 是大型
//     Rust workspace，首次构建很慢，后续增量构建很快）
//   * 构建完成后打印二进制绝对路径，与 `pc_automation/cua_driver/mod.rs`
//     的 `resolve_binary_path` 查找顺序一致（up/cua/target/{debug,release}）
//
// 用法：
//   node scripts/build-cua-driver.mjs            # debug 构建（存在则跳过）
//   node scripts/build-cua-driver.mjs --force    # 强制重新构建
//   node scripts/build-cua-driver.mjs --release  # release 构建
//   node scripts/build-cua-driver.mjs --check    # 仅检查是否已构建，不编译
//
// 配合 npm 脚本：
//   pnpm cua:build        # 等价上面的默认调用
//   pnpm dev:cua          # 先确保 cua-driver 已构建，再 tauri dev

import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import { existsSync, readdirSync } from "node:fs";
import { spawnSync } from "node:child_process";

// ── sccache 加速 ─────────────────────────────────────────────────
// cua 是巨型 Rust workspace，且 src-tauri 与 up/cua 会各自重编 tokio /
// serde 等公共依赖。sccache 按源码哈希缓存 rustc 产物，可以：
//   * 在两次 workspace 之间去重公共依赖的编译产物；
//   * 在 `cargo clean` / 切分支后快速回填，而不是全量重编。
// 注意：sccache 在 `incremental` 开启时不会缓存，因此这里显式把
// CARGO_BUILD_INCREMENTAL 关掉。cua 不在 tauri dev 的热循环里（只构建
// 一次作为 sidecar），关增量对开发体验几乎无影响，却能让 sccache 真正生效。
// 若本机未安装 sccache，则跳过注入，构建照常进行（不阻断开发）。
function resolveSccacheEnv() {
  const probe = spawnSync(process.platform === "win32" ? "where" : "which", ["sccache"], {
    stdio: "ignore",
  });
  if (probe.status !== 0) {
    console.log("[build-cua-driver] 未检测到 sccache，跳过加速（构建仍正常）");
    return {};
  }
  console.log("[build-cua-driver] 启用 sccache 缓存（CARGO_BUILD_INCREMENTAL=false）");
  return {
    CARGO_BUILD_RUSTC_WRAPPER: "sccache",
    CARGO_BUILD_INCREMENTAL: "false",
  };
}

// ── 构建前环境自检 ───────────────────────────────────────────────
// 抓“工具链缺件”这类问题，在 cargo 跑掉几十个 crate 前就给到人话提示，
// 而不是等 msvc_spectre_libs 这种 crate 编译到一半才甩 cryptic 错误。
// 仅做只读探测；探测不到（如非 Windows、找不到 VS 安装）时静默放行，
// 交给 cargo 自行报错，避免误杀本可构建的环境。可用 CUA_SKIP_ENV_CHECK=1 跳过。
function preflightEnvironment() {
  if (process.env.CUA_SKIP_ENV_CHECK) {
    console.log("[build-cua-driver] CUA_SKIP_ENV_CHECK 已设置，跳过环境自检");
    return;
  }
  // 已用本地 stub 替换 msvc_spectre_libs（见 workspace Cargo.toml 的
  // [patch.crates-io]）→ 构建不再依赖 VS “Spectre-mitigated libs” 工作负载，
  // 跳过自检，避免在本就走绕过的机器上误杀 dev 链路。
  const stub = resolve(CUA_DIR, "crates", "msvc-spectre-libs-stub", "Cargo.toml");
  if (existsSync(stub)) {
    console.log("[build-cua-driver] 检测到本地 msvc_spectre_libs stub，跳过 Spectre 工具链自检");
    return;
  }
  if (process.platform !== "win32") return; // Spectre-mitigated libs 仅 MSVC (Windows) 需要

  // 1) 定位所有 VS 安装（优先 vswhere，再兜底扫描常见版本/发行渠道）
  const vsRoots = [];
  const vswhere = "C:\\Program Files (x86)\\Microsoft Visual Studio\\Installer\\vswhere.exe";
  if (existsSync(vswhere)) {
    const out = spawnSync(
      vswhere,
      ["-products", "*", "-format", "value", "-property", "installationPath"],
      { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }
    );
    if (out.status === 0 && out.stdout) {
      out.stdout.split(/\r?\n/).map((s) => s.trim()).filter(Boolean).forEach((p) => vsRoots.push(p));
    }
  }
  const seen = new Set();
  const deduped = vsRoots.filter((p) => (seen.has(p) ? false : (seen.add(p), true)));
  vsRoots.length = 0;
  vsRoots.push(...deduped);
  const base = "C:\\Program Files (x86)\\Microsoft Visual Studio";
  for (const year of ["2022", "2019", "2017"]) {
    for (const ed of ["Community", "Professional", "Enterprise", "BuildTools"]) {
      const p = `${base}\\${year}\\${ed}`;
      if (existsSync(p)) vsRoots.push(p);
    }
  }
  if (vsRoots.length === 0) return; // 找不到 VS，交给 cargo 报“工具链缺失”

  // 2) 检查任一安装下是否存在 Spectre-mitigated libs
  //    (VC\Tools\MSVC\<ver>\lib\spectre\x64\libcmt.lib)
  let hasSpectre = false;
  for (const vs of vsRoots) {
    const msvcRoot = resolve(vs, "VC", "Tools", "MSVC");
    if (!existsSync(msvcRoot)) continue;
    for (const ver of readdirSync(msvcRoot)) {
      const spectre = resolve(msvcRoot, ver, "lib", "spectre", "x64", "libcmt.lib");
      if (existsSync(spectre)) { hasSpectre = true; break; }
    }
    if (hasSpectre) break;
  }
  if (hasSpectre) return; // 工具链齐全

  // 3) 缺失 → 友好提示 + 非零退出（仍可用 CUA_SKIP_ENV_CHECK=1 强制忽略）
  console.error(
    `[build-cua-driver] 检测到 Visual Studio 但未安装 “MSVC Spectre-mitigated libs”，\n` +
      `  cua-driver 依赖的 msvc_spectre_libs crate 会构建失败。\n` +
      `  修复（任选其一）：\n` +
      `    1. Visual Studio Installer → 修改 → 单个组件 →\n` +
      `       勾选 “MSVC v143 - VS 2022 C++ x64/x86 Spectre-mitigated libs”\n` +
      `       （版本号按你的 VS 年份选 v142/v143）\n` +
      `    2. 或设置 CUA_SKIP_ENV_CHECK=1 跳过本自检（仅当你确定不需要 Spectre 缓解时）`
  );
  process.exit(3);
}

const __dirname = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(__dirname, "..");
const CUA_REPO = resolve(REPO_ROOT, "up", "cua");

// cua 的 Rust workspace 根目录存在两种布局，两种都要兼容：
//   * 精简 vendor 副本：`up/cua/Cargo.toml` 即 workspace 根
//   * 完整 trycua/cua monorepo：真实根在 `up/cua/libs/cua-driver/rust/`
// 这里自动探测，避免 `cargo` 在错误目录里找不到 Cargo.toml 而直接失败。
function resolveCuaWorkspaceRoot() {
  if (existsSync(resolve(CUA_REPO, "Cargo.toml"))) return CUA_REPO;
  const mono = resolve(CUA_REPO, "libs", "cua-driver", "rust");
  if (existsSync(resolve(mono, "Cargo.toml"))) return mono;
  return CUA_REPO; // 兜底：沿用原行为，由后续 cargo 报错给出明确信息
}
const CUA_DIR = resolveCuaWorkspaceRoot();

// 无论 workspace 根在哪，二进制都统一输出到 `up/cua/target/{profile}`，
// 与 `pc_automation/cua_driver/mod.rs` 的 `resolve_binary_path`（开发路径）
// 完全一致，避免“构建成功却找不到二进制”的错位。
const TARGET_BASE = resolve(CUA_REPO, "target");

// ── 参数解析 ──────────────────────────────────────────────────────
const args = new Set(process.argv.slice(2));
const FORCE = args.has("--force");
const RELEASE = args.has("--release");
const CHECK_ONLY = args.has("--check");
const PROFILE = RELEASE ? "release" : "debug";
const EXE_EXT = process.platform === "win32" ? ".exe" : "";
const BIN_NAME = `cua-driver${EXE_EXT}`;

function binPathFor(profile) {
  return resolve(TARGET_BASE, profile, BIN_NAME);
}

// ── 前置检查 ──────────────────────────────────────────────────────
if (!existsSync(CUA_REPO)) {
  // `up/cua` 是 gitignored 的 vendored 上游，在 CI 及未拉取上游的仓库里
  // 不存在。cua-driver 只是「可选」sidecar：runtime 缺失时会回落到 enigo，
  // 因此不应阻断 tauri dev / build。软跳过（exit 0）而非硬失败（exit 2）。
  // 需要本地构建 cua-driver 时再拉取上游即可。
  console.log(
    `[build-cua-driver] 未找到 up/cua 目录，跳过 cua-driver（cua 为可选 sidecar，runtime 将回落到 enigo）。\n` +
      `  如需本地构建 cua-driver：bash scripts/upgrade-cua-upstream.sh`
  );
  process.exit(0);
}

const existingDebug = existsSync(binPathFor("debug"));
const existingRelease = existsSync(binPathFor("release"));
const alreadyBuilt = existingDebug || existingRelease;

if (CHECK_ONLY) {
  // 逃生口：CI/纯前端调试时若不需要 cua，设置 CUA_SKIP_CHECK=1 可跳过
  // 二进制校验，避免 beforeDevCommand 硬失败阻断 tauri dev。
  if (process.env.CUA_SKIP_CHECK) {
    console.log("[build-cua-driver] CUA_SKIP_CHECK 已设置，跳过二进制校验");
    process.exit(0);
  }
  if (alreadyBuilt) {
    const p = existingRelease && !existingDebug ? binPathFor("release") : binPathFor("debug");
    console.log(`[build-cua-driver] 已构建: ${p}`);
    process.exit(0);
  }
  console.error("[build-cua-driver] 未构建 cua-driver 二进制");
  process.exit(1);
}

if (alreadyBuilt && !FORCE) {
  const p = existingRelease && !existingDebug ? binPathFor("release") : binPathFor("debug");
  console.log(`[build-cua-driver] 二进制已存在，跳过构建: ${p}`);
  console.log(`[build-cua-driver] 如需强制重建，加 --force`);
  process.exit(0);
}

// ── 构建 ──────────────────────────────────────────────────────────
preflightEnvironment();

console.log(
  `[build-cua-driver] 开始 ${PROFILE} 构建 cua-driver (首次较慢，后续增量很快) ...`
);
const cargoArgs = ["build", "--target-dir", TARGET_BASE, "-p", "cua-driver"];
if (RELEASE) cargoArgs.push("--release");

const result = spawnSync("cargo", cargoArgs, {
  cwd: CUA_DIR,
  stdio: "inherit",
  env: { ...process.env, ...resolveSccacheEnv() },
});

if (result.error) {
  console.error(
    `[build-cua-driver] 无法启动 cargo: ${result.error.message}\n` +
      `  请确认 Rust 工具链已安装且在 PATH 中。`
  );
  process.exit(2);
}
if (result.status !== 0) {
  console.error(`[build-cua-driver] cargo build 失败，退出码 ${result.status}`);
  process.exit(result.status ?? 1);
}

const builtPath = binPathFor(PROFILE);
if (!existsSync(builtPath)) {
  console.error(`[build-cua-driver] 构建成功但未找到预期二进制: ${builtPath}`);
  process.exit(1);
}

console.log(`[build-cua-driver] 构建完成: ${builtPath}`);
console.log(`[build-cua-driver] 现在可运行: pnpm dev:cua  (或 pnpm dev:tauri)`);
