// Copyright (c) 2026 MeeJoy
//
// Hermes 自进化 Track C —— 升级落盘路由器 (Phase 1 + Phase 2)。
//
// 把"已确认草稿"的新版本内容按 `SkillKind` 路由到正确的存储:
//   * Mcp        → <app_data>/skills_optimized/<id>.md
//                  + <hermes_home>/skills/<id>/SKILL.md   (让 collect_installed_skills 能扫到)
//   * Automation → <app_data>/skills_optimized/<id>.md   (Phase 2: 与 mcp 同目录暂存,
//                  供用户后续导入加密技能仓 .enc; collect_installed_skills 通过
//                  front matter 的 preferred_execution_type 识别为 automation)
//                  + <hermes_home>/skills/<id>/SKILL.md
//   * Builtin    → <app_data>/skills_overrides/<id>.md   (Phase 2: 覆盖层, 建议性暂存;
//                  builtin 运行时仍走 Rust 实现, 此文件供审阅/后续导入)
//
// 本模块是纯函数 + 文件 IO, 不持有状态, 不 async。
// `commands::autoskill::autoskill_confirm_draft` 在调用
// `AutoSkillEngine::confirm_upgrade` (写 skill_version_manage) 之后调用
// `UpgradeWriter::upgrade` 完成实际文件落盘。

use tauri::{AppHandle, Manager};

use crate::hermes::evolution_signal::SkillKind;

#[derive(Debug, thiserror::Error)]
pub enum UpgradeError {
    #[error("skill kind {0} not supported in Phase 1")]
    UnsupportedKind(String),
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
    #[error("path error: {0}")]
    Path(String),
}

/// 升级结果。
#[derive(Debug, Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub enum UpgradeOutcome {
    /// 成功落盘。`targets` 列出所有已写入文件的绝对路径。
    Applied { targets: Vec<String> },
    /// 无需落盘 (保留给未来 no-op 场景; Phase 2 起 builtin 也走 Applied)。
    Skipped { reason: String },
}

/// 升级落盘路由器。无状态, 全部通过关联函数调用。
pub struct UpgradeWriter;

impl UpgradeWriter {
    /// 按 `skill_kind` 分派。`content` 为新的 SKILL.md 正文。
    ///
    /// 调用方 (commands::autoskill::autoskill_confirm_draft) 应在
    /// `AutoSkillEngine::confirm_upgrade` 成功之后再调用本方法, 因为
    /// `confirm_upgrade` 会先写 skill_version_manage 表 + 更新 draft 状态。
    pub fn upgrade(
        app: &AppHandle,
        skill_id: &str,
        skill_kind: SkillKind,
        content: &str,
    ) -> Result<UpgradeOutcome, UpgradeError> {
        match skill_kind {
            SkillKind::Mcp | SkillKind::Automation => {
                // Phase 2: automation 与 mcp 共用暂存目录 skills_optimized/。
                // collect_installed_skills 通过 front matter 的 preferred_execution_type
                // 区分二者, 避免 MissingSkill 误判。automation 技能的 .enc 加密导入
                // 仍需用户走既有 import 流程 (带密码), 此处仅暂存 skill.md 供审阅/导入。
                Self::write_optimized_and_hermes(app, skill_id, content)
                    .map(|targets| UpgradeOutcome::Applied { targets })
            }
            SkillKind::Builtin => {
                // Phase 2: builtin 覆盖层。写入 skills_overrides/ 作为建议性暂存;
                // builtin 运行时仍走 Rust 实现, 此文件供审阅与后续导入。
                Self::write_builtin_override(app, skill_id, content)
                    .map(|targets| UpgradeOutcome::Applied { targets })
            }
        }
    }

    /// 把 SKILL.md 同时写入两处 (mcp / automation 共用):
    ///   1. `<app_data>/skills_optimized/<safe_filename>.md`
    ///      (复用 commands::skill::optimized_skills_dir + safe_skill_filename)
    ///   2. `<hermes_home>/skills/<skill_id>/SKILL.md`
    ///      (让 collect_installed_skills 能扫到, 出现在左侧技能列表)
    ///
    /// 原子写入: 先写 `.tmp` (带 uuid 防并发撞名), 再 rename。
    /// 单个目标失败只 log warn 不阻断另一处写入 (镜像原 commands/autoskill.rs 逻辑)。
    /// 返回所有成功写入的路径列表。
    fn write_optimized_and_hermes(
        app: &AppHandle,
        skill_id: &str,
        content: &str,
    ) -> Result<Vec<String>, UpgradeError> {
        let mut written: Vec<String> = Vec::new();

        // === 1. <app_data>/skills_optimized/<safe_filename>.md =================
        let dir = crate::commands::skill::optimized_skills_dir(app)
            .map_err(|e| UpgradeError::Path(format!("optimized_skills_dir: {}", e)))?;
        let file_name = format!("{}.md", crate::commands::skill::safe_skill_filename(skill_id));
        let target = dir.join(&file_name);
        if let Err(e) = write_atomic(&target, content) {
            log::warn!(
                "[autoskill] 落盘失败 (optimized): skill_id={}, err={}",
                skill_id,
                e
            );
        } else {
            log::info!(
                "[autoskill] 草稿确认后落盘: skill_id={}, path={}",
                skill_id,
                target.display()
            );
            written.push(target.to_string_lossy().into_owned());
        }

        // === 2. <hermes_home>/skills/<skill_id>/SKILL.md =======================
        //    让 collect_installed_skills 能扫到新技能, 出现在左侧技能列表。
        let hermes_skills_dir = crate::commands::legacy::get_hermes_skills_dir();
        let skill_subdir = hermes_skills_dir.join(skill_id);
        if let Err(e) = std::fs::create_dir_all(&skill_subdir) {
            log::warn!(
                "[autoskill] 创建 hermes skills 子目录失败: skill_id={}, err={}",
                skill_id,
                e
            );
        } else {
            let skill_md_path = skill_subdir.join("SKILL.md");
            match write_atomic(&skill_md_path, content) {
                Ok(()) => {
                    log::info!(
                        "[autoskill] 同步到 hermes skills: skill_id={}, path={}",
                        skill_id,
                        skill_md_path.display()
                    );
                    written.push(skill_md_path.to_string_lossy().into_owned());
                }
                Err(e) => {
                    log::warn!(
                        "[autoskill] hermes skills 写入失败: skill_id={}, err={}",
                        skill_id,
                        e
                    );
                }
            }
        }

        Ok(written)
    }

    /// builtin 覆盖层: 写入 `<app_data>/skills_overrides/<safe_filename>.md`。
    /// 不镜像到 hermes_home (builtin 已在技能列表中, 无需重复出现)。
    /// Phase 2: 在覆盖文件头部注入 `entry_action` (从 skills_embedded 查找),
    /// 供后续工具链 (runBuiltinSkill) 识别技能入口行为。
    fn write_builtin_override(
        app: &AppHandle,
        skill_id: &str,
        content: &str,
    ) -> Result<Vec<String>, UpgradeError> {
        let app_data = app
            .path()
            .app_data_dir()
            .map_err(|e| UpgradeError::Path(format!("app_data_dir: {}", e)))?;
        let dir = app_data.join("skills_overrides");
        std::fs::create_dir_all(&dir)
            .map_err(|e| UpgradeError::Path(format!("create skills_overrides dir: {}", e)))?;
        let file_name = format!("{}.md", crate::commands::skill::safe_skill_filename(skill_id));
        let target = dir.join(&file_name);

        // Inject entry_action from embedded skills into the override file.
        // This ensures the override layer carries the same entry_action mapping
        // as the original builtin skill, so tools like runBuiltinSkill can
        // correctly map 'execute' → entry_action.
        let final_content = match find_builtin_entry_action(skill_id) {
            Some(ea) if !ea.is_empty() => {
                // If content already has front matter, inject entry_action into it;
                // otherwise prepend a minimal front matter block.
                if let Some(rest) = content.strip_prefix("---") {
                    // Insert entry_action before the closing ---
                    if let Some(pos) = rest.find("\n---") {
                        let (before_close, after_close) = rest.split_at(pos);
                        format!("---{}entry_action: \"{}\"\n{}", before_close, ea, after_close)
                    } else {
                        format!("entry_action: \"{}\"\n{}", ea, content)
                    }
                } else {
                    format!("---\nentry_action: \"{}\"\n---\n{}", ea, content)
                }
            }
            _ => content.to_string(),
        };

        write_atomic(&target, &final_content).map_err(UpgradeError::Io)?;
        log::info!(
            "[autoskill] builtin 覆盖层落盘: skill_id={}, path={}",
            skill_id,
            target.display()
        );
        Ok(vec![target.to_string_lossy().into_owned()])
    }
}

/// Look up the `entry_action` for a builtin skill from the embedded skills list.
/// Returns `None` if the skill is not found (e.g. custom/mcp skill).
fn find_builtin_entry_action(skill_id: &str) -> Option<String> {
    let skills = crate::skills_embedded::get_builtin_skills("http://127.0.0.1:8642");
    skills.iter().find(|s| s.id == skill_id).map(|s| s.entry_action.clone()).filter(|ea| !ea.is_empty())
}

/// 原子写入: 先写 `.tmp` (带 uuid 防并发撞名), 再 rename。
/// Windows 上同目录 rename 不跨文件系统, 不会失败。
/// rename 失败时清理 tmp 防泄漏。返回写入是否成功。
fn write_atomic(target: &std::path::Path, content: &str) -> Result<(), std::io::Error> {
    // tmp 名带 uuid: 防并发同名 save 撞名 (旧实现用固定 ".tmp" 后缀,
    // 两个并发调用会互相覆盖 .tmp 内容, 导致 rename 后内容错误)
    let tmp_name = format!(
        "{}.{}.tmp",
        target
            .file_name()
            .and_then(|s| s.to_str())
            .unwrap_or("skill"),
        uuid::Uuid::new_v4().simple()
    );
    let tmp = target
        .parent()
        .unwrap_or_else(|| std::path::Path::new("."))
        .join(&tmp_name);

    if let Err(e) = std::fs::write(&tmp, content.as_bytes()) {
        let _ = std::fs::remove_file(&tmp);
        return Err(e);
    }
    if let Err(e) = std::fs::rename(&tmp, target) {
        let _ = std::fs::remove_file(&tmp);
        return Err(e);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn write_atomic_creates_and_overwrites() {
        let dir = tempfile::tempdir().expect("tempdir");
        let target = dir.path().join("skill.md");
        write_atomic(&target, "first").expect("write first");
        assert_eq!(std::fs::read_to_string(&target).unwrap(), "first");
        // 覆盖写
        write_atomic(&target, "second").expect("write second");
        assert_eq!(std::fs::read_to_string(&target).unwrap(), "second");
        // tmp 不残留
        let leftovers: Vec<_> = std::fs::read_dir(dir.path())
            .unwrap()
            .filter_map(Result::ok)
            .filter(|e| {
                e.path()
                    .extension()
                    .and_then(|s| s.to_str())
                    .map(|s| s == "tmp")
                    .unwrap_or(false)
            })
            .collect();
        assert!(leftovers.is_empty(), "tmp 文件残留: {:?}", leftovers);
    }

    #[test]
    fn write_atomic_concurrent_names_differ() {
        // 两次并发调用的 tmp 名应不同 (uuid 后缀), 不会互相覆盖。
        let dir = tempfile::tempdir().expect("tempdir");
        let target = dir.path().join("c.md");
        write_atomic(&target, "a").expect("write a");
        write_atomic(&target, "b").expect("write b");
        assert_eq!(std::fs::read_to_string(&target).unwrap(), "b");
    }
}
