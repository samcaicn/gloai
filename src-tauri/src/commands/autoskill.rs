// Copyright (c) 2026 MeeJoy
//
// AutoSkill 自进化 IPC 命令层。
//
// 7 个命令暴露 autoskill::AutoSkillEngine 给前端：
//   autoskill_list_candidates       — 列出单技能优化候选
//   autoskill_list_merge_candidates — 列出可合并的相似技能组
//   autoskill_list_pending_drafts   — 列出待确认草稿
//   autoskill_confirm_draft         — 确认草稿（升级 + 落盘）
//   autoskill_reject_draft          — 拒绝草稿
//   autoskill_trigger_scan          — 手动触发单技能扫描 + 生成草稿
//   autoskill_trigger_merge         — 手动触发合并扫描 + 生成合并草稿
//
// 引擎通过 app.try_state::<Arc<AutoSkillEngine>>() 获取，未注册时返回友好错误。

use std::sync::Arc;

use tauri::{AppHandle, Manager};

use crate::autoskill::{
    AutoSkillEngine, DraftResult, MergeCandidate, OptimizationCandidate,
};
use crate::storage::autoskill_draft::{DraftRow, STATUS_PENDING_CONFIRM, STATUS_REJECTED};

/// 从 Tauri state 获取 AutoSkillEngine，未注册时返回友好错误。
fn get_engine(
    app: &AppHandle,
) -> Result<Arc<AutoSkillEngine>, String> {
    app.try_state::<Arc<AutoSkillEngine>>()
        .map(|s| s.inner().clone())
        .ok_or_else(|| "AutoSkillEngine 尚未初始化".to_string())
}

/// 列出单技能优化候选。
#[tauri::command]
pub async fn autoskill_list_candidates(
    scene: String,
    app: AppHandle,
) -> Result<Vec<OptimizationCandidate>, String> {
    let engine = get_engine(&app)?;
    engine
        .scan_for_optimization(&scene)
        .await
        .map_err(|e| e.to_string())
}

/// 列出可合并的相似技能组。
#[tauri::command]
pub async fn autoskill_list_merge_candidates(
    scene: String,
    app: AppHandle,
) -> Result<Vec<MergeCandidate>, String> {
    let engine = get_engine(&app)?;
    engine
        .scan_merge_candidates(&scene)
        .await
        .map_err(|e| e.to_string())
}

/// 列出待确认草稿（status = 'pending_confirm'）。
///
/// 顶部幂等调用 `migrate_phase1` 补齐 Phase 1 的 4 个信号元数据列,
/// 这样老 DuckDB 文件无需修改 app setup 也能 lazy 升级 schema。
#[tauri::command]
pub async fn autoskill_list_pending_drafts(
    scene: String,
    app: AppHandle,
) -> Result<Vec<DraftRow>, String> {
    let engine = get_engine(&app)?;
    let db = engine.db();
    // Phase 1 schema 迁移 (幂等, 安全每次 list 都调用)
    crate::storage::autoskill_draft::migrate_phase1(db).map_err(|e| e.to_string())?;
    crate::storage::autoskill_draft::query_pending(db, &scene).map_err(|e| e.to_string())
}

/// 确认草稿：调用 confirm_upgrade 升级技能版本（写 skill_version_manage +
/// 更新 draft 状态），然后按 `skill_kind` 路由把 content 落盘。
///
/// Phase 1 起, 实际落盘由 `autoskill::upgrade_writer::UpgradeWriter` 完成:
///   * Mcp        → <app_data>/skills_optimized/<id>.md
///                  + <hermes_home>/skills/<id>/SKILL.md
///   * Automation → Phase 2 (返回错误)
///   * Builtin    → Skipped (no-op)
///
/// `skill_kind` 从 draft 行的新列读取, NULL/缺失时降级到 `Mcp` (最安全默认)。
#[tauri::command]
pub async fn autoskill_confirm_draft(
    draft_id: String,
    app: AppHandle,
) -> Result<(), String> {
    let engine = get_engine(&app)?;

    // 1. 在 confirm_upgrade 之前读取草稿的 skill_id / content / skill_kind。
    //    用 block 作用域释放 conn 守卫, 避免 confirm_upgrade 再拿 conn 时死锁。
    //    同时幂等补齐 Phase 1 列, 防止老 DB 没有 skill_kind 列时 SELECT 报错。
    let draft_info: Option<(String, Option<String>, Option<String>)> = {
        let db = engine.db();
        // 幂等: 老库没有 skill_kind 列也能补齐
        crate::storage::autoskill_draft::migrate_phase1(db)
            .map_err(|e| e.to_string())?;
        let conn = db.get_conn();
        let mut stmt = conn
            .prepare(
                "SELECT skill_id, content, skill_kind FROM skill_auto_iter_draft WHERE id = ?",
            )
            .map_err(|e| e.to_string())?;
        let mut rows = stmt
            .query_map(duckdb::params![draft_id], |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, Option<String>>(1)?,
                    row.get::<_, Option<String>>(2)?,
                ))
            })
            .map_err(|e| e.to_string())?;
        match rows.next() {
            Some(Ok(r)) => Some(r),
            _ => None,
        }
    };

    let (skill_id, content, skill_kind_str) = draft_info.ok_or_else(|| {
        format!("草稿 {} 不存在", draft_id)
    })?;

    // 2. 调用 confirm_upgrade（写 skill_version_manage + 更新 draft 状态）。
    //    必须在落盘之前: confirm_upgrade 失败就不应改文件, 避免版本表与文件不一致。
    engine
        .confirm_upgrade(&draft_id)
        .await
        .map_err(|e| e.to_string())?;

    // 3. 按 skill_kind 路由落盘 (UpgradeWriter 内部做原子写 + 双写)
    if let Some(md_content) = content {
        if !md_content.trim().is_empty() {
            // NULL / 未知值降级到 Mcp (最安全, 落盘到文件即可见)
            let skill_kind = skill_kind_str
                .as_deref()
                .map(crate::hermes::evolution_signal::SkillKind::from_str_lossy)
                .unwrap_or_default();

            // UpgradeWriter::upgrade 失败时补偿: confirm_upgrade 已把 draft 置为
            // watching + 写 skill_version_manage (旧版本备份为新版本 watching), 但文件
            // 落盘失败会导致 DB 已改而文件未写。best-effort 把 draft 状态改回
            // pending_confirm 让用户可重试 (skill_version_manage 的不一致留待 Phase 2
            // 引入跨表事务回滚; 当前最小修复优先保证 draft 可重试 + 留 error 日志)。
            let outcome = match crate::autoskill::upgrade_writer::UpgradeWriter::upgrade(
                &app,
                &skill_id,
                skill_kind,
                &md_content,
            ) {
                Ok(o) => o,
                Err(e) => {
                    let err_msg = e.to_string();
                    log::error!(
                        "[autoskill] 草稿 {} 落盘失败, 尝试补偿回滚 draft 状态: skill_id={} kind={} err={}",
                        draft_id,
                        skill_id,
                        skill_kind.as_str(),
                        err_msg
                    );
                    let db = engine.db();
                    if let Err(rb_err) = crate::storage::autoskill_draft::update_status(
                        db,
                        &draft_id,
                        STATUS_PENDING_CONFIRM,
                        None,
                        None,
                    ) {
                        log::error!(
                            "[autoskill] 草稿 {} 补偿回滚 (改回 pending_confirm) 也失败: {}",
                            draft_id,
                            rb_err
                        );
                    }
                    return Err(format!(
                        "升级落盘失败, 草稿 {} 已 best-effort 回退到 pending_confirm: {}",
                        draft_id, err_msg
                    ));
                }
            };

            match outcome {
                crate::autoskill::upgrade_writer::UpgradeOutcome::Applied { targets } => {
                    log::info!(
                        "[autoskill] 草稿 {} 升级落盘: skill_id={} kind={} targets={:?}",
                        draft_id,
                        skill_id,
                        skill_kind.as_str(),
                        targets
                    );
                    // Phase 3: 升级落盘成功后, best-effort 广播 SkillSync 给 mesh 对端。
                    // mesh 未激活时跳过; 广播失败只 log warn 不阻断升级流程
                    // (本地落盘已完成, 同步是附加收益)。
                    #[cfg(feature = "mesh")]
                    if let Some(mesh) =
                        app.try_state::<crate::hermes::mesh::MeshHandle>()
                    {
                        if let Some(node) = mesh.get().await {
                            // Best-effort: extract version from draft's skill_md front matter.
                            // If unavailable, pass empty string (degrades to first-writer-wins).
                            let version = extract_version_from_md(&md_content);
                            if let Err(e) = node
                                .broadcast_skill_sync(
                                    &skill_id,
                                    skill_kind.as_str(),
                                    &md_content,
                                    &version,
                                )
                                .await
                            {
                                log::warn!(
                                    "[autoskill] SkillSync 广播失败: skill_id={} err={}",
                                    skill_id,
                                    e
                                );
                            }
                        }
                    }
                }
                crate::autoskill::upgrade_writer::UpgradeOutcome::Skipped { reason } => {
                    log::info!(
                        "[autoskill] 草稿 {} 升级跳过: skill_id={} kind={} reason={}",
                        draft_id,
                        skill_id,
                        skill_kind.as_str(),
                        reason
                    );
                }
            }
        }
    }

    Ok(())
}

/// 拒绝草稿：更新 draft status='rejected'。
#[tauri::command]
pub async fn autoskill_reject_draft(
    draft_id: String,
    app: AppHandle,
) -> Result<(), String> {
    let engine = get_engine(&app)?;
    let db = engine.db();
    crate::storage::autoskill_draft::update_status(
        db,
        &draft_id,
        STATUS_REJECTED,
        None,
        None,
    )
    .map_err(|e| e.to_string())?;
    Ok(())
}

/// 手动触发单技能扫描：遍历 scan_for_optimization 结果，对每个候选调用
/// generate_draft，返回所有生成的草稿。
#[tauri::command]
pub async fn autoskill_trigger_scan(
    scene: String,
    app: AppHandle,
) -> Result<Vec<DraftResult>, String> {
    let engine = get_engine(&app)?;
    let candidates = engine
        .scan_for_optimization(&scene)
        .await
        .map_err(|e| e.to_string())?;

    let mut drafts = Vec::new();
    for candidate in &candidates {
        match engine.generate_draft(&scene, &candidate.skill_id).await {
            Ok(draft) => drafts.push(draft),
            Err(e) => {
                log::warn!(
                    "[autoskill] generate_draft 失败: skill_id={}, err={}",
                    candidate.skill_id,
                    e
                );
            }
        }
    }
    Ok(drafts)
}

/// 手动触发合并扫描：遍历 scan_merge_candidates 结果，对每组调用
/// generate_merge_draft，返回所有合并草稿。
#[tauri::command]
pub async fn autoskill_trigger_merge(
    scene: String,
    app: AppHandle,
) -> Result<Vec<DraftResult>, String> {
    let engine = get_engine(&app)?;
    let candidates = engine
        .scan_merge_candidates(&scene)
        .await
        .map_err(|e| e.to_string())?;

    let mut drafts = Vec::new();
    for candidate in &candidates {
        match engine
            .generate_merge_draft(&scene, &candidate.skill_ids)
            .await
        {
            Ok(draft) => drafts.push(draft),
            Err(e) => {
                log::warn!(
                    "[autoskill] generate_merge_draft 失败: skill_ids={:?}, err={}",
                    candidate.skill_ids,
                    e
                );
            }
        }
    }
    Ok(drafts)
}

/// Best-effort extraction of `version:` from YAML front matter of a SKILL.md.
/// Returns empty string if not found (degrades to first-writer-wins on mesh).
fn extract_version_from_md(md: &str) -> String {
    // Quick scan for `version:` line inside the first `---` block
    let mut in_front = false;
    for line in md.lines() {
        let trimmed = line.trim();
        if trimmed == "---" {
            if in_front {
                break; // end of front matter
            }
            in_front = true;
            continue;
        }
        if in_front && trimmed.starts_with("version:") {
            let v = trimmed.strip_prefix("version:").unwrap_or("").trim();
            // Strip surrounding quotes
            let v = v.trim_matches('"').trim_matches('\'');
            return v.to_string();
        }
    }
    String::new()
}
