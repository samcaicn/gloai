// Copyright (c) 2026 AIMarketing
//
// Tauri commands — Skill compilation & execution,
// and the ClientAdopt inbox surface.
//
// The commands in this file are the public surface the front-end
// uses to drive the skill pipeline:
//
//   1. `compile_skill`     — YAML -> MCP blob (returns base64).
//   2. `decompile_skill`   — MCP blob (base64) -> YAML (for debug / diff).
//   3. `execute_skill`     — kick off a skill, returns `request_id`.
//   4. `cancel_execution`  — stop an in-flight skill (cancel, not pause).
//   5. `adopt_proposal`     — route a server evaluation into
//                            the registry, swap versions / buffer
//                            into inbox / reject per the policy band.
//   6. `list_inbox`         — snapshot the "needs review"
//                            inbox for the front-end sheet.
//   7. `dismiss_proposal`   — user clicked "dismiss" on an
//                            inbox card. Removes it from the inbox.
//
// The complementary `pause_execution` / `resume_execution` /
// `get_execution_status` / `get_execution_history` commands live
// in `commands::automation` (smart retry).
//
// The `skill_id` parameter on `execute_skill` accepts either:
//   * a raw `skill.md` YAML body (the dev / demo path used today
//     by the floating panel), or
//   * a base64-encoded MCP blob (what the front-end should hand
//     over after a successful `compile_skill` round-trip).
//
// This dual mode means the front-end can ship compiled blobs
// without re-implementing the YAML parser in JavaScript.

use base64::Engine;
use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Emitter, Manager, State};

use crate::automation::state::AutomationState;
use crate::automation::{spawn_execution, AutomationEngine};
use crate::skill::compiler::{compile_skill_md, decompile_to_skill_md};
use crate::skill::{CompiledMcp, McpRuntime, SkillEvaluation, SkillManifest, SkillRegistry};

fn engine(app: &AppHandle) -> Result<std::sync::Arc<AutomationEngine>, String> {
    let state = app
        .try_state::<std::sync::Arc<AutomationState>>()
        .ok_or_else(|| "AutomationState is not initialized".to_string())?;
    let state = state.inner().clone();
    Ok(std::sync::Arc::new(AutomationEngine::new(state, app.clone())))
}

/// Load a manifest from the `skill_id` payload. Resolution order:
///
///   1. **By skill ID** — look up `<app_data>/skills_optimized/<id>.md`.
///      This is the canonical path for saved / Hermes-optimized skills.
///   2. **Base64 MCP blob** — compact binary format produced by
///      `compile_skill`. Used when the front-end caches a compiled skill.
///   3. **Raw YAML** — the dev / floating-panel path where the user
///      pastes raw `skill.md` source.
///
/// The three-tier lookup means callers can pass *either* a stable ID
/// *or* an inline payload, and the right thing happens without the
/// caller having to know which form it has.
fn load_manifest_from_skill_id(
    app: &AppHandle,
    skill_id: &str,
) -> Result<(crate::skill::SkillManifest, String), String> {
    // 1. Try by ID — optimized skills directory.
    if let Ok(dir) = optimized_skills_dir(app) {
        let file = dir.join(format!("{}.md", safe_skill_filename(skill_id)));
        if file.exists() {
            if let Some(body) = read_utf8_or_warn(&file) {
                if let Ok(manifest) = crate::skill::SkillManifest::from_skill_md(&body) {
                    return Ok((manifest, body));
                }
            }
        }
    }

    // 2. Try base64 MCP.
    if let Ok(bytes) = base64::engine::general_purpose::STANDARD.decode(skill_id.as_bytes()) {
        if let Ok(yaml) = decompile_to_skill_md(&bytes) {
            if let Ok(manifest) = crate::skill::SkillManifest::from_skill_md(&yaml) {
                return Ok((manifest, yaml));
            }
        }
    }

    // 3. Fall back to raw YAML.
    let manifest = crate::skill::SkillManifest::from_skill_md(skill_id)?;
    Ok((manifest, skill_id.to_string()))
}

#[tauri::command]
pub fn compile_skill(skill_md: String) -> Result<CompiledMcpResponse, String> {
    let compiled: CompiledMcp = compile_skill_md(&skill_md)?;
    let mcp_base64 = base64::engine::general_purpose::STANDARD.encode(&compiled.mcp_binary);
    Ok(CompiledMcpResponse {
        manifest: compiled.manifest,
        mcp_base64,
        timestamp: compiled.timestamp,
    })
}

#[tauri::command]
pub fn decompile_skill(mcp_base64: String) -> Result<String, String> {
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(mcp_base64.as_bytes())
        .map_err(|e| format!("base64 decode failed: {}", e))?;
    decompile_to_skill_md(&bytes)
}

#[tauri::command]
pub fn execute_skill(app: AppHandle, skill_id: String) -> Result<String, String> {
    // Resolve the manifest by skill_id. Three-tier lookup:
    // ID → base64 MCP → raw YAML.
    let (manifest, _source) = load_manifest_from_skill_id(&app, &skill_id)?;
    manifest.validate()?;

    // Platform compatibility check (Hermes desktop `platforms` field).
    // Skills restricted to other platforms fail fast with a clear message
    // instead of cryptic errors halfway through the step ladder.
    if !manifest.is_compatible_with_current_platform() {
        let current = crate::skill::manifest::SkillPlatform::current();
        let supported: Vec<&str> = manifest.platforms.iter().map(|p| p.as_str()).collect();
        return Err(format!(
            "skill '{}' is not compatible with {} (supports: {})",
            manifest.name,
            current.as_str(),
            supported.join(", ")
        ));
    }

    let runtime = McpRuntime::from_skill_md(
        manifest
            .to_skill_md()
            .map_err(|e| format!("yaml re-serialize failed: {}", e))?
            .as_str(),
    )
    .map_err(|e| format!("failed to build runtime: {}", e))?;

    let request_id = format!("req_{}", uuid::Uuid::new_v4());
    let engine = engine(&app)?;
    spawn_execution(engine, request_id.clone(), skill_id, runtime);
    Ok(request_id)
}

#[tauri::command]
pub fn cancel_execution(app: AppHandle, request_id: String) -> Result<(), String> {
    let state = app
        .try_state::<std::sync::Arc<AutomationState>>()
        .ok_or_else(|| "AutomationState is not initialized".to_string())?;
    state.request_cancel(&request_id);
    Ok(())
}

/// The on-the-wire shape of `compile_skill`. We return the MCP as
/// base64 (rather than `Vec<u8>`) so the JS side can serialize it
/// through `JSON.stringify` without an extra array-buffer round
/// trip.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CompiledMcpResponse {
    pub manifest: crate::skill::SkillManifest,
    pub mcp_base64: String,
    pub timestamp: i64,
}

// =====================================================================
// ClientAdopt inbox surface.
// =====================================================================
//
// `SkillRegistry` is mounted on the `AppHandle` from `lib.rs::setup`
// (the main-thread-only boot file) as a `SkillRegistry::new()`. The
// three commands below are the entire public surface the front-end
// uses to drive adoption; we keep the implementation thin and
// delegate the actual policy + swap logic to `SkillRegistry` so
// unit tests can exercise the policy without a Tauri harness.

/// Input shape for `adopt_proposal`. The front-end transport
/// hands us a `SkillEvaluation` together with the
/// proposal id and the raw `skill.md` body so the registry can
/// surface the YAML in the inbox card's "preview" affordance.
#[allow(dead_code)]
// ClientAdopt inbox surface; the `invoke_handler!`
// registration in `lib.rs` is the main thread's reserved action.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AdoptProposalRequest {
    pub proposal_id: String,
    pub skill_id: String,
    pub skill_name: String,
    pub source: String,
    pub skill_md: String,
    pub evaluation: SkillEvaluation,
}

/// Route a freshly-received evaluation into the registry. The
/// registry classifies the score and either swaps versions
/// (`>= 0.85`), buffers into the inbox (`0.60..0.85`), or rejects
/// outright (`< 0.60`).
#[allow(dead_code)]
// ClientAdopt inbox surface; the `invoke_handler!`
// registration in `lib.rs` is the main thread's reserved action.
#[tauri::command]
pub async fn adopt_proposal(
    registry: State<'_, SkillRegistry>,
    request: AdoptProposalRequest,
) -> Result<crate::skill::AdoptOutcome, String> {
    registry.adopt(
        &request.proposal_id,
        &request.skill_id,
        &request.skill_name,
        &request.source,
        &request.skill_md,
        &request.evaluation,
    )
}

/// Snapshot the inbox. Newest-first. Returns an empty array if
/// the user has dismissed everything (which is the common case
/// in dev — we always return `Ok` here so the front-end doesn't
/// have to special-case "no inbox").
#[allow(dead_code)]
// ClientAdopt inbox surface; the `invoke_handler!`
// registration in `lib.rs` is the main thread's reserved action.
#[tauri::command]
pub async fn list_inbox(
    registry: State<'_, SkillRegistry>,
) -> Result<Vec<crate::skill::InboxItem>, String> {
    Ok(registry.list_inbox())
}

/// User explicitly accepts a `NeedsReview` proposal from the
/// inbox UI. Mirrors the auto-accept path but requires a user
/// click, so the registry bypasses the high-confidence policy
/// gate and treats the human decision as authoritative.
#[allow(dead_code)]
#[tauri::command]
pub async fn user_accept_proposal(
    app: AppHandle,
    registry: State<'_, SkillRegistry>,
    proposal_id: String,
) -> Result<crate::skill::AdoptOutcome, String> {
    let outcome = registry.user_accept(&proposal_id)?;
    let _ = app.emit(
        "skill:adopt-outcome",
        serde_json::json!({
            "proposalId": outcome.proposal_id,
            "skillId": outcome.skill_id,
            "decision": outcome.decision,
            "score": outcome.score,
            "newVersion": outcome.new_version,
            "previousVersion": outcome.previous_version,
            "degraded": outcome.degraded,
        }),
    );
    Ok(outcome)
}

/// User clicked "dismiss" on an inbox card (whether the proposal
/// was in the review band or already auto-accepted). The registry
/// removes the entry. `reason` is recorded on the bounded history
/// for the evolution loop.
#[allow(dead_code)]
// ClientAdopt inbox surface; the `invoke_handler!`
// registration in `lib.rs` is the main thread's reserved action.
#[tauri::command]
pub async fn dismiss_proposal(
    registry: State<'_, SkillRegistry>,
    proposal_id: String,
    reason: String,
) -> Result<(), String> {
    registry.dismiss(&proposal_id, &reason)
}

// =====================================================================
// Optimized-skill local persistence (Hermes-modified skills)
// =====================================================================
//
// 设计取舍 (与 `discover_skills_from_server` 形成对比):
//   * 远程下载的 skill_md —— 留在内存 inbox / history，不落盘。
//     原因: 未经评估/修改的下载内容不应污染本地文件系统, 评估失败的
//     候选也不应留在磁盘上。
//   * Hermes 修改 / 优化 / 保存后的 skill_md —— 明文落盘到
//     `<app_data>/skills_optimized/<skill_id>.md`。原因:
//       1) 用户主动认可 = 本地资产, 应跨重启保留;
//       2) 不加密 —— 用户可能想用文本编辑器查看/调试, 加密反而
//          拖累开发体验。需要保密的场景由 OS 文件权限处理即可;
//       3) 明文 YAML 也方便 git 版本化 / diff。
//
// 文件命名: `<safe(skill_id)>.md` —— safe() 把非法字符替换为 `_`,
// 折叠连续下划线, 剥前导 `.`/`_`/空格, 屏蔽 Windows 保留设备名。
// 同名 skill 二次保存会原子覆盖 (write 到 .tmp 再 rename), tmp 文件
// 名带 uuid 防并发撞名; rename 失败会清理 tmp。

/// 元数据返回给前端, 包含本地文件路径 / 大小 / 修改时间, 让
/// SettingsModal / EvolutionPanel 能渲染 "已保存的优化技能" 列表。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OptimizedSkillMeta {
    pub skill_id: String,
    pub skill_name: String,
    pub version: String,
    pub source: String,
    /// Platforms this skill supports (lowercase: `windows`, `macos`, `linux`).
    /// Empty list means "all platforms". Follows Hermes desktop convention.
    #[serde(default)]
    pub platforms: Vec<String>,
    /// `true` if this skill is compatible with the current OS.
    /// Front-end can use this to gray-out / hide platform-incompatible skills.
    #[serde(default)]
    pub is_compatible_with_current_platform: bool,
    /// `<app_data>/skills_optimized/<skill_id>.md` 的绝对路径, 主要给
    /// "打开所在文件夹" 按钮使用; 前端不需要解析它。
    pub file_path: String,
    pub size_bytes: u64,
    /// Unix seconds. 用 i64 而不是 chrono 是为了和前端 `new Date(ts*1000)`
    /// 直接对接。
    pub modified_at: i64,
}

/// 解析 `<app_data>/skills_optimized/` 目录。setup 阶段会先创建它。
pub fn optimized_skills_dir(app: &AppHandle) -> Result<std::path::PathBuf, String> {
    let dir = app
        .path()
        .app_data_dir()
        .map_err(|e| format!("Failed to get app_data_dir: {}", e))?
        .join("skills_optimized");
    std::fs::create_dir_all(&dir)
        .map_err(|e| format!("Failed to create skills_optimized dir {:?}: {}", dir, e))?;
    Ok(dir)
}

/// 把任意 skill_id 转成安全的文件名片段。规则:
///   * 仅保留 `[A-Za-z0-9._-]`, 其它全部替换为 `_`
///   * 折叠连续 `_`
///   * **同时**剥前导 `.`/`_`/空格 (一次扫描, 防止 `._..` → `..` 的交错漏网)
///   * 剥末尾 `.`/空格 (Windows 会自动剥导致歧义)
///   * 屏蔽 Windows 保留设备名 (CON/PRN/AUX/NUL/COM1-9/LPT1-9/CONIN$/CONOUT$)
///   * 长度截断到 128 字符 (避免某些 FS 的 255 字节限制)
pub fn safe_skill_filename(skill_id: &str) -> String {
    let mut out: String = skill_id
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '-' || c == '_' || c == '.' {
                c
            } else {
                '_'
            }
        })
        .collect();
    // 折叠连续下划线
    while out.contains("__") {
        out = out.replace("__", "_");
    }
    // 一次性同时剥前导 `.`/`_`/空格 —— 防止 `._..` → `..` 的 trim 顺序漏洞
    out = out
        .trim_start_matches(['.', '_', ' '])
        .to_string();
    // 剥末尾 `.`/空格 (Windows 会自动剥导致歧义)
    out = out.trim_end_matches(['.', ' ']).to_string();
    // 截断
    if out.chars().count() > 128 {
        out = out.chars().take(128).collect();
        // 截断后再剥一次末尾 (防止截在 `.`/`_` 上)
        out = out.trim_end_matches(['.', '_', ' ']).to_string();
    }
    // 屏蔽 Windows 保留设备名 (跨平台一致行为)
    if is_windows_reserved_name(&out) {
        out = format!("_{}", out);
    }
    if out.is_empty() {
        "unnamed".to_string()
    } else {
        out
    }
}

/// 检测 Windows 保留设备名。Windows 把 stem (即第一个 `.` 之前的部分)
/// 为这些名字的文件视为设备, 不论扩展名。在 Linux/macOS 上虽不致命但
/// 也保持一致行为以避免跨平台差异。
fn is_windows_reserved_name(s: &str) -> bool {
    let stem = s.split('.').next().unwrap_or("").to_ascii_uppercase();
    matches!(
        stem.as_str(),
        "CON" | "PRN" | "AUX" | "NUL" | "CONIN$" | "CONOUT$" | "COM1" | "COM2" | "COM3"
            | "COM4" | "COM5" | "COM6" | "COM7" | "COM8" | "COM9" | "LPT1" | "LPT2" | "LPT3"
            | "LPT4" | "LPT5" | "LPT6" | "LPT7" | "LPT8" | "LPT9"
    )
}

/// 只读文件前 N 行且总字节数不超过 max_bytes, 用于 peek name/version。
/// 用 `BufReader` 流式读, 避免 `read_to_string` 把整个文件加载到内存
/// (大文件 OOM 风险)。
fn read_head(
    path: &std::path::Path,
    max_lines: usize,
    max_bytes: usize,
) -> Option<String> {
    use std::io::{BufRead, BufReader};
    let f = std::fs::File::open(path).ok()?;
    let mut r = BufReader::new(f);
    let mut out = String::new();
    let mut line = String::new();
    for i in 0..max_lines {
        line.clear();
        let n = match r.read_line(&mut line) {
            Ok(n) => n,
            Err(_) => break,
        };
        if n == 0 {
            break; // EOF
        }
        // 第一行总是保留 (哪怕超过 max_bytes): 否则一个超长首行会让
        // peek_skill_md_meta 拿不到 name/version, 列表/启动加载全部回退到
        // 文件名 fallback。从第二行起再 enforce 字节上限。
        if i > 0 && out.len() + line.len() > max_bytes {
            break; // 字节上限
        }
        out.push_str(&line);
    }
    Some(out)
}

/// 读取 skill.md YAML 头里的 `name` / `version` 字段。失败不报错 ——
/// 让 caller 用文件名作为 fallback。
///
/// 处理的边界情况:
///   * UTF-8 BOM (`\u{FEFF}`) —— 显式剥离, `trim_start` 不包含它
///   * YAML front-matter (`---` 包围) —— 跟踪 in_front 状态
///   * 冒号前后空格 (`name : foo`) —— 用 `split_once(':')` 而非 `strip_prefix`
///   * 行内注释 (`name: foo # 注释`) —— 截断 " #" 之后
///   * 单/双引号包裹的值
fn peek_skill_md_meta(skill_md: &str) -> (String, String, Vec<String>) {
    // 显式剥离 UTF-8 BOM (trim_start 不剥它, 因为 U+FEFF 不算 whitespace)
    let body = skill_md.strip_prefix('\u{FEFF}').unwrap_or(skill_md);
    let mut name = String::new();
    let mut version = String::new();
    let mut platforms: Vec<String> = Vec::new();
    let mut in_front = false; // YAML front-matter 状态
    for line in body.lines().take(50) {
        let trimmed = line.trim_start();
        // front-matter 边界 --- 单独成行
        if trimmed == "---" {
            in_front = !in_front;
            continue;
        }
        // 只在 front-matter 内解析 name/version/platforms 键值对。之前 in_front
        // 是死代码 (跟踪了但不过滤), 导致正文的任意 `key: value` 行都可能
        // 被误判为 name/version。如果 front-matter 里没有 name/version,
        // 不解析正文 —— caller 用文件名 fallback。
        if !in_front {
            continue;
        }
        let Some((key, val)) = trimmed.split_once(':') else {
            continue;
        };
        let key = key.trim();
        if key.eq_ignore_ascii_case("name") && name.is_empty() {
            name = parse_yaml_scalar(val);
        } else if key.eq_ignore_ascii_case("version") && version.is_empty() {
            version = parse_yaml_scalar(val);
        } else if key.eq_ignore_ascii_case("platforms") {
            // 内联数组形式: `platforms: [windows, macos]`
            let v = val.trim();
            if v.starts_with('[') && v.ends_with(']') {
                let inner = &v[1..v.len() - 1];
                platforms = inner
                    .split(',')
                    .map(|s| s.trim().trim_matches(|c: char| c == '"' || c == '\'').to_string())
                    .filter(|s| !s.is_empty())
                    .collect();
            }
        }
        if !name.is_empty() && !version.is_empty() && !platforms.is_empty() {
            break;
        }
    }
    (name, version, platforms)
}

/// 解析单个 YAML 标量值。支持双引号 / 单引号 / 无引号 (含行内注释截断)。
fn parse_yaml_scalar(raw: &str) -> String {
    let s = raw.trim();
    if let Some(rest) = s.strip_prefix('"') {
        // 双引号: 截到下一个未转义的 `"`
        return rest.split('"').next().unwrap_or("").to_string();
    }
    if let Some(rest) = s.strip_prefix('\'') {
        // 单引号: 截到下一个 `'`
        return rest.split('\'').next().unwrap_or("").to_string();
    }
    // 无引号: 截掉 " #" 之后的行内注释, 再 trim
    s.split(" #").next().unwrap_or(s).trim_end().to_string()
}

/// 读文件 bytes 并按 UTF-8 解码, 剥 BOM。失败返回 None + log warn
/// (避免 `read_to_string` 静默吞错)。
fn read_utf8_or_warn(path: &std::path::Path) -> Option<String> {
    // 先用 metadata 检查大小，超过 4MB 直接跳过，避免 read 大文件 OOM
    let metadata = match std::fs::metadata(path) {
        Ok(m) => m,
        Err(e) => {
            log::warn!("[skill/optimized] stat {:?} failed: {}", path, e);
            return None;
        }
    };
    if metadata.len() > 4 * 1024 * 1024 {
        log::warn!(
            "[skill/optimized] {:?} too large ({} bytes), skipped",
            path,
            metadata.len()
        );
        return None;
    }
    let bytes = match std::fs::read(path) {
        Ok(b) => b,
        Err(e) => {
            log::warn!("[skill/optimized] read {:?} failed: {}", path, e);
            return None;
        }
    };
    match std::str::from_utf8(&bytes) {
        Ok(s) => Some(s.strip_prefix('\u{FEFF}').unwrap_or(s).to_string()),
        Err(_) => {
            log::warn!(
                "[skill/optimized] {:?} is not UTF-8, skipped",
                path
            );
            None
        }
    }
}

/// 把 Hermes 修改/优化后的 skill.md 明文保存到本地磁盘。
///
/// 落盘路径: `<app_data>/skills_optimized/<safe(skill_id)>.md`
/// 写入策略: 先写 `<file>.<uuid>.tmp` 再 rename, 保证原子性
///   * uuid 防止并发同名 save 时 tmp 撞名 (旧实现用固定 `.tmp` 名,
///     两个并发会数据损坏)
///   * rename 失败会清理 tmp (防残留泄漏)
/// 同名 skill 二次保存会覆盖, 不做版本归档 —— 历史版本由 sqlite
/// `skill_versions` 表维护 (本函数末尾同步调 `save_skill_version`)。
///
/// 同步 fn (非 async): Tauri v2 会自动 spawn 到 blocking 池,
/// 避免阻塞 async runtime。大文件 I/O 不会卡住其他 IPC 命令。
#[tauri::command]
pub fn save_optimized_skill(
    app: AppHandle,
    skill_id: String,
    skill_md: String,
    source: Option<String>,
) -> Result<OptimizedSkillMeta, String> {
    if skill_md.trim().is_empty() {
        return Err("skill_md is empty".to_string());
    }
    // 拒绝 builtin- 前缀的 skill_id, 避免和内置技能命名空间冲突
    // (内置技能 ID 在 skills_embedded.rs 里以 "builtin-" 开头)
    // to_ascii_lowercase 与前端 SkillEditorPage 的 /^builtin-/i 大小写不敏感保持一致
    if skill_id.to_ascii_lowercase().starts_with("builtin-") {
        return Err(format!(
            "skill_id cannot start with 'builtin-' (reserved for compiled-in skills): {}",
            skill_id
        ));
    }
    // 校验 skill_md 是合法的 manifest —— 防止把损坏/恶意内容落盘
    let manifest = SkillManifest::from_skill_md(&skill_md)
        .map_err(|e| format!("skill_md is not a valid manifest: {}", e))?;
    manifest.validate().map_err(|e| format!("skill_md validation failed: {}", e))?;
    // SkillManifest 没有 version 字段, 用 peek_skill_md_meta 从 YAML 头解析
    let (peeked_name, peeked_version, _) = peek_skill_md_meta(&skill_md);

    let dir = optimized_skills_dir(&app)?;
    let file_name = format!("{}.md", safe_skill_filename(&skill_id));
    let target = dir.join(&file_name);
    // tmp 名带 uuid: 防并发同名 save 撞名 (旧实现用固定 ".tmp" 后缀,
    // 两个并发调用会互相覆盖 .tmp 内容, 导致 rename 后内容错误)
    let tmp = dir.join(format!("{}.{}.tmp", file_name, uuid::Uuid::new_v4().simple()));

    // 原子写入: .tmp -> rename。Windows 上同目录 rename 不跨文件系统,
    // 不会失败。rename 失败时清理 tmp 防泄漏。
    if let Err(e) = std::fs::write(&tmp, skill_md.as_bytes()) {
        // 写失败也清理 tmp 残留，避免泄漏
        let _ = std::fs::remove_file(&tmp);
        return Err(format!("Failed to write tmp file {:?}: {}", tmp, e));
    }
    if let Err(e) = std::fs::rename(&tmp, &target) {
        // best-effort 清理: 即使清理失败也不影响错误返回
        let _ = std::fs::remove_file(&tmp);
        return Err(format!("Failed to rename tmp -> target {:?}: {}", target, e));
    }

    let meta = std::fs::metadata(&target)
        .map_err(|e| format!("Failed to stat {:?}: {}", target, e))?;
    let modified_at = meta
        .modified()
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);

    log::info!(
        "[skill/optimized] saved skill_id={} -> {} ({} bytes)",
        skill_id,
        target.display(),
        meta.len()
    );

    // 同步写 sqlite skill_versions 表, 让 FTS5 全文检索能搜到。
    // 之前 save_skill_version 是 dead_code, 永不被调用, 导致
    // commands::memory::search_skills 永远返回空。
    if let Some(db) = app.try_state::<crate::skill::memory::SkillDb>() {
        // version 用 MAX(version)+1 而非硬编码 1: 这样和 adopt 路径
        // (bumps version_counter) 语义对齐, 重复保存会产生递增版本行,
        // 不会把已有版本覆盖成 "v1"。next_skill_version 失败时退回 1,
        // 保证 save 不因查 version 失败而整体回滚。
        let version = crate::skill::memory::next_skill_version(&db, &skill_id)
            .unwrap_or(1);
        let v = crate::skill::memory::SkillVersion {
            skill_id: skill_id.clone(),
            version,
            parent_skill_id: None,
            parent_version: None,
            source: source.clone().unwrap_or_else(|| "hermes-optimized".to_string()),
            skill_md: skill_md.clone(),
            created_at: chrono::Utc::now().to_rfc3339(),
            state: "adopted".to_string(),
        };
        if let Err(e) = crate::skill::memory::save_skill_version(&db, &v) {
            // FTS 索引失败不阻断主流程, 只 log warn
            log::warn!("[skill/optimized] FTS index failed for {}: {}", skill_id, e);
        }
    } else {
        log::warn!(
            "[skill/optimized] SkillDb not registered, skip FTS index for {}",
            skill_id
        );
    }

    // 同步 SkillRegistry 内存态, 让运行时立即生效 (旧实现只在重启加载)
    let skill_name = if peeked_name.is_empty() {
        manifest.name.clone()
    } else {
        peeked_name
    };
    if let Some(registry) = app.try_state::<SkillRegistry>() {
        // install_persisted: 不 bump version counter, 不创建 RollbackGuard,
        // 不写 history —— 因为这是用户主动保存的本地资产, 不是评估过的 proposal
        if let Err(e) = registry.install_persisted(&skill_id, &skill_name) {
            log::warn!("[skill/optimized] registry sync failed for {}: {}", skill_id, e);
        }
    }

    Ok(OptimizedSkillMeta {
        skill_id,
        skill_name: if skill_name.is_empty() {
            file_name.trim_end_matches(".md").to_string()
        } else {
            skill_name
        },
        version: if peeked_version.is_empty() {
            "1.0.0".to_string()
        } else {
            peeked_version
        },
        source: source.unwrap_or_else(|| "hermes-optimized".to_string()),
        platforms: vec![],
        is_compatible_with_current_platform: true,
        file_path: target.to_string_lossy().to_string(),
        size_bytes: meta.len(),
        modified_at,
    })
}

/// 列出本地保存的所有优化技能。文件顺序按修改时间倒序 (最新在前)。
/// 用 `read_head` 只读前 50 行取 name/version, 不读全文档 ——
/// 大文件也能秒级返回, 防 OOM。
#[tauri::command]
pub fn list_optimized_skills(app: AppHandle) -> Result<Vec<OptimizedSkillMeta>, String> {
    let dir = optimized_skills_dir(&app)?;
    let mut items = Vec::new();
    for entry in std::fs::read_dir(&dir)
        .map_err(|e| format!("Failed to read dir {:?}: {}", dir, e))?
    {
        let entry = match entry {
            Ok(e) => e,
            Err(e) => {
                log::warn!("[skill/optimized] readdir entry failed: {}", e);
                continue;
            }
        };
        let path = entry.path();
        if path.extension().and_then(|s| s.to_str()) != Some("md") {
            continue;
        }
        // 注: .tmp 残留已被上面的 "md" 扩展名校验跳过 (tmp 文件扩展名是 "tmp" 不是 "md"),
        // 无需再单独判断; 此处曾经的 .tmp 判断是死代码已移除。
        let stem = path
            .file_stem()
            .and_then(|s| s.to_str())
            .unwrap_or("unknown")
            .to_string();
        let meta = match std::fs::metadata(&path) {
            Ok(m) => m,
            Err(e) => {
                log::warn!("[skill/optimized] stat {:?} failed: {}", path, e);
                continue;
            }
        };
        let modified_at = meta
            .modified()
            .ok()
            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|d| d.as_secs() as i64)
            .unwrap_or(0);
        // 只读前 50 行 (max 8KB), 防 OOM
        let body = read_head(&path, 50, 8 * 1024).unwrap_or_default();
        let (skill_name, version, platforms) = peek_skill_md_meta(&body);
        let is_compatible = if platforms.is_empty() {
            true
        } else {
            let current = std::env::consts::OS;
            platforms.iter().any(|p| p.eq_ignore_ascii_case(current))
        };
        items.push(OptimizedSkillMeta {
            skill_id: stem.clone(),
            skill_name: if skill_name.is_empty() {
                stem.clone()
            } else {
                skill_name
            },
            version: if version.is_empty() {
                "1.0.0".to_string()
            } else {
                version
            },
            source: "hermes-optimized".to_string(),
            platforms,
            is_compatible_with_current_platform: is_compatible,
            file_path: path.to_string_lossy().to_string(),
            size_bytes: meta.len(),
            modified_at,
        });
    }
    // 最新在前
    items.sort_by_key(|b| std::cmp::Reverse(b.modified_at));
    Ok(items)
}

/// 删除本地保存的优化技能。skill_id 经过 safe_skill_filename 转换后
/// 拼路径, 所以传入任意字符串也不会越界 (路径穿越被堵死)。
///
/// 幂等: NotFound 当成功 (避免 exists + remove 的 TOCTOU race)。
#[tauri::command]
pub fn delete_optimized_skill(app: AppHandle, skill_id: String) -> Result<(), String> {
    let dir = optimized_skills_dir(&app)?;
    let target = dir.join(format!("{}.md", safe_skill_filename(&skill_id)));
    match std::fs::remove_file(&target) {
        Ok(()) => {
            log::info!("[skill/optimized] deleted skill_id={}", skill_id);
            // 同步从 SkillRegistry 移除内存态
            if let Some(registry) = app.try_state::<SkillRegistry>() {
                if let Err(e) = registry.remove_persisted(&skill_id) {
                    log::warn!(
                        "[skill/optimized] registry remove failed for {}: {}",
                        skill_id,
                        e
                    );
                }
            }
            // 同步清理 sqlite skill_versions + skill_fts 行: 之前只删文件 +
            // registry.remove_persisted, 不清 DB, 导致删除后 search_skills
            // 仍能搜到 (FTS 残留), 点击命中即崩 (文件已不存在)。
            // FTS 失败按 warn 不阻断 (delete_skill_versions 内部已 best-effort)。
            if let Some(db) = app.try_state::<crate::skill::memory::SkillDb>() {
                if let Err(e) =
                    crate::skill::memory::delete_skill_versions(&db, &skill_id)
                {
                    log::warn!(
                        "[skill/optimized] DB cleanup failed for {}: {}",
                        skill_id,
                        e
                    );
                }
            }
            Ok(())
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
            // 幂等: 删除不存在的技能不算错。但 DB 里可能仍有残留行
            // (文件被外部 rm 但 DB 未清), 顺手清一次保证最终一致。
            if let Some(db) = app.try_state::<crate::skill::memory::SkillDb>() {
                if let Err(e) =
                    crate::skill::memory::delete_skill_versions(&db, &skill_id)
                {
                    log::warn!(
                        "[skill/optimized] DB cleanup (NotFound path) failed for {}: {}",
                        skill_id,
                        e
                    );
                }
            }
            Ok(())
        }
        Err(e) => Err(format!("Failed to remove {:?}: {}", target, e)),
    }
}

/// 启动时把本地保存的优化技能加载回 SkillRegistry 内存态。
///
/// 这一步在 `lib.rs::setup` 里 `app.manage(SkillRegistry::new())`
/// 之后调用。**SkillDb 可选** —— sqlite 的写入由 `save_optimized_skill`
/// 在保存时同步完成; 但若启动时 SkillDb 已注册 (init_skill_db 先于本函数),
/// 这里会对每个加载成功的文件调一次 `save_skill_version` 做 **FTS 对账**,
/// 补齐因 save 路径非原子写 / 旧版本未写 FTS / 文件被外部拷入等造成的
/// skill_fts 缺失, 保证磁盘文件与 FTS5 索引最终一致。
///
/// 用 `install_persisted` 而非 `adopt`:
///   * adopt 会 bump version_counter + 创建 RollbackGuard + 写 history,
///     导致每次重启 version 单调递增 (v1→v2→v3...), 但 skill_md 没变,
///     版本号语义错乱。RollbackGuard 的 previous_version 指向上一轮的
///     同一份 skill_md, rollback 没意义。
///   * install_persisted 直接 insert running_versions, 不 bump counter,
///     不创建 RollbackGuard, 不写 history —— 适合"加载本地缓存"的语义。
///
/// **OOM 注意**: install_persisted 不再接收 skill_md (旧实现把整个文件
/// 读进内存只为传给一个忽略它的参数)。name peek 走 `read_head` (8KB/50行
/// 上限)。FTS 对账需要全文内容, 这一步的 `read_utf8_or_warn` 是必要的
/// 一次性读 (读完即 drop, 不缓存在 registry)。
pub fn load_optimized_skills_into_registry(app: &AppHandle) -> Result<usize, String> {
    let registry = app
        .try_state::<SkillRegistry>()
        .ok_or_else(|| "SkillRegistry is not initialized".to_string())?;
    // SkillDb 可选: init_skill_db 失败时 (磁盘满 / 权限) 仍能只装内存 registry。
    let db = app.try_state::<crate::skill::memory::SkillDb>();

    let dir = optimized_skills_dir(app)?;
    let mut loaded = 0usize;
    let mut skipped = 0usize;
    let mut fts_reconciled = 0usize;
    for entry in std::fs::read_dir(&dir)
        .map_err(|e| format!("Failed to read dir {:?}: {}", dir, e))?
    {
        let entry = match entry {
            Ok(e) => e,
            Err(e) => {
                log::warn!("[skill/optimized] readdir entry failed: {}", e);
                continue;
            }
        };
        let path = entry.path();
        if path.extension().and_then(|s| s.to_str()) != Some("md") {
            continue;
        }
        let stem = path
            .file_stem()
            .and_then(|s| s.to_str())
            .unwrap_or("unknown")
            .to_string();
        // 拒绝 builtin- 前缀的本地文件 (虽然 save 路径已经挡住, 但用户可能
        // 手动塞文件, 这里二次防御)。to_ascii_lowercase 与前端大小写不敏感一致
        if stem.to_ascii_lowercase().starts_with("builtin-") {
            log::warn!(
                "[skill/optimized] skipping builtin- prefixed file: {}",
                path.display()
            );
            skipped += 1;
            continue;
        }
        // 只读前 50 行 (max 8KB, 第一行不受字节上限): 拿 name + 校验头部
        // UTF-8。不再用 read_utf8_or_warn 读全文喂 install_persisted (那是
        // wasteful read —— install_persisted 不接收 skill_md)。
        let head = match read_head(&path, 50, 8 * 1024) {
            Some(h) => h,
            None => {
                log::warn!(
                    "[skill/optimized] read_head failed for {:?}, skipped",
                    path
                );
                skipped += 1;
                continue;
            }
        };
        let (skill_name, _, _) = peek_skill_md_meta(&head);
        let name = if skill_name.is_empty() {
            stem.clone()
        } else {
            skill_name
        };

        match registry.install_persisted(&stem, &name) {
            Ok(()) => {
                log::info!("[skill/optimized] loaded {}", stem);
                loaded += 1;
            }
            Err(e) => {
                log::warn!("[skill/optimized] failed to load {}: {}", stem, e);
                skipped += 1;
                // install 失败就不补 FTS —— 避免 DB 里有索引但 registry 没装。
                continue;
            }
        }

        // FTS 启动对账: 若 SkillDb 可用, 读全文写 skill_versions + skill_fts,
        // 补齐 save 路径非原子写 (write tmp → rename → save_skill_version 三步
        // 中途崩溃) 或旧版本未写 FTS 造成的索引缺失。读完即 drop, 不缓存在
        // registry, 不会 OOM。
        if let Some(db) = &db {
            if let Some(full_md) = read_utf8_or_warn(&path) {
                let version =
                    crate::skill::memory::next_skill_version(db, &stem).unwrap_or(1);
                let v = crate::skill::memory::SkillVersion {
                    skill_id: stem.clone(),
                    version,
                    parent_skill_id: None,
                    parent_version: None,
                    source: "hermes-optimized".to_string(),
                    skill_md: full_md,
                    created_at: chrono::Utc::now().to_rfc3339(),
                    state: "adopted".to_string(),
                };
                if let Err(e) = crate::skill::memory::save_skill_version(db, &v) {
                    log::warn!(
                        "[skill/optimized] FTS reconcile failed for {}: {}",
                        stem,
                        e
                    );
                } else {
                    fts_reconciled += 1;
                }
            }
        }
    }
    log::info!(
        "[skill/optimized] loaded {} skills from disk (skipped {}, fts_reconciled {})",
        loaded,
        skipped,
        fts_reconciled
    );
    Ok(loaded)
}
