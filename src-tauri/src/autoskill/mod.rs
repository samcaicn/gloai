// Copyright (c) 2026 MeeJoy
//
// AutoSkill 自进化模块 —— 技能自动生成与版本迭代升级。
//
// 基于 worker_task_log 执行日志挖掘成功模式，聚类精简后参数泛化，
// 生成新版本草稿。经用户确认后替换旧版本进入 24h 观察期，
// 观察期分数下降 >15 分自动回滚。
//
// 状态流转：Monitoring → Drafting → Scoring → PendingConfirm →
//           Upgrading → Watching → Running / Rollback
//
// 纯本地实现，不调用 LLM；参数泛化用 regex 规则。

pub mod pipeline;
pub mod state_machine;
pub mod pattern_miner;
pub mod param_generalizer;
pub mod upgrade_writer;

pub use pipeline::AutoSkillPipeline;
#[allow(unused_imports)]
pub use state_machine::{UpgradeState, UpgradeStateMachine};

use std::sync::Arc;

use crate::skill_eval::SkillEvalEngine;
use crate::storage::DuckDBPool;

/// AutoSkill 自进化引擎。
///
/// 持有 DuckDB 连接池 + 技能评估引擎引用，对外暴露监测 / 生成草稿 /
/// 确认升级 / 回滚四个核心阶段。Pipeline 内部完成 LogMiner →
/// PatternFinder → FlowReconstructor → ParamGeneralizer → ScoreCheck
/// 五步流水线。
pub struct AutoSkillEngine {
    db: Arc<DuckDBPool>,
    eval: Arc<SkillEvalEngine>,
    pipeline: AutoSkillPipeline,
}

impl AutoSkillEngine {
    pub fn new(db: Arc<DuckDBPool>, eval: Arc<SkillEvalEngine>) -> Self {
        let pipeline = AutoSkillPipeline::new(db.clone());
        Self { db, eval, pipeline }
    }

    /// 获取 DuckDBPool 引用（供 commands 层查询草稿内容等）。
    pub fn db(&self) -> &Arc<DuckDBPool> {
        &self.db
    }

    /// 监测阶段：扫描高频使用的技能，找出有优化空间的。
    ///
    /// 触发条件：执行次数 ≥5 且成功率 < 90%，或高频使用可优化效率。
    pub async fn scan_for_optimization(
        &self,
        scene: &str,
    ) -> Result<Vec<OptimizationCandidate>, AutoSkillError> {
        self.pipeline.scan_candidates(scene).await
    }

    /// 生成草稿：对候选技能执行 AutoSkill pipeline。
    ///
    /// 1. LogMiner: 挖掘成功执行链
    /// 2. PatternFinder: 聚类相似流程
    /// 3. FlowReconstructor: 精简冗余步骤
    /// 4. ParamGeneralizer: 硬编码→参数化
    /// 5. SemanticDedup: 去重
    /// 6. ScoreCheck: 评分 ≥85 才保留
    pub async fn generate_draft(
        &self,
        scene: &str,
        skill_id: &str,
    ) -> Result<DraftResult, AutoSkillError> {
        self.pipeline.generate(scene, skill_id, &self.eval).await
    }

    /// 合并扫描：查找同 scene 下可合并的相似技能组。
    ///
    /// 查询所有执行次数 ≥3 的 skill_id，按 action 序列 Jaccard 相似度
    /// 聚类，找出 action 序列相似度 ≥0.6 且组内 ≥2 个 skill_id 的合并候选。
    pub async fn scan_merge_candidates(
        &self,
        scene: &str,
    ) -> Result<Vec<MergeCandidate>, AutoSkillError> {
        self.pipeline.scan_merge_candidates(scene).await
    }

    /// 生成合并草稿：挖掘多个相似 skill_id 的成功 trace，合并去重步骤，
    /// 参数泛化后生成合并后的 SKILL.md 草稿。
    ///
    /// 新 skill_id 用 `merged-{hash前8位}` 命名（hash 来自排序后的 skill_ids）。
    /// 写入 skill_auto_iter_draft 表，source='log_mining'，status='pending_confirm'。
    pub async fn generate_merge_draft(
        &self,
        scene: &str,
        skill_ids: &[String],
    ) -> Result<DraftResult, AutoSkillError> {
        self.pipeline
            .generate_merge_draft(scene, skill_ids, &self.eval)
            .await
    }

    /// 确认升级：用户确认后替换技能。
    ///
    /// 1. 从 draft 读取新版本内容
    /// 2. 备份旧版本到 skill_version_manage (status=rollback)
    /// 3. 写入新版本 (status=watching)
    /// 4. 更新 draft status=upgrading→watching
    pub async fn confirm_upgrade(&self, draft_id: &str) -> Result<(), AutoSkillError> {
        // 1. 从 draft 读取新版本内容（block 作用域释放 conn 守卫，避免后续死锁）
        let draft = {
            let conn = self.db.get_conn();
            let mut stmt = conn.prepare(
                "SELECT scene, skill_id, draft_version, content, new_score
                 FROM skill_auto_iter_draft
                 WHERE id = ?",
            )?;
            let rows = stmt.query_map(duckdb::params![draft_id], |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                    row.get::<_, Option<String>>(3)?,
                    row.get::<_, Option<i32>>(4)?,
                ))
            })?;
            let mut result = Vec::new();
            for row in rows {
                result.push(row?);
            }
            result.into_iter().next()
        };

        let (scene, skill_id, new_version, content, new_score) = match draft {
            Some(row) => row,
            None => return Err(AutoSkillError::NotFound(draft_id.to_string())),
        };
        let content = content.ok_or_else(|| {
            AutoSkillError::NotFound(format!("草稿 {} 内容为空", draft_id))
        })?;

        // 2. 备份旧版本到 skill_version_manage (status=rollback)
        let old_active =
            crate::storage::skill_version::get_active(&self.db, &scene, &skill_id)?;
        if let Some(old) = &old_active {
            crate::storage::skill_version::upsert_version(
                &self.db,
                &crate::storage::skill_version::SkillVersionUpsert {
                    scene: scene.clone(),
                    skill_id: skill_id.clone(),
                    version: old.version.clone(),
                    status: crate::storage::skill_version::STATUS_ROLLBACK.to_string(),
                    score: old.score,
                    score_detail: None,
                    content: old.content.clone(),
                    changelog: Some(format!("升级到 {} 前备份", new_version)),
                },
            )?;
        }

        // 3. 写入新版本 (status=watching)
        crate::storage::skill_version::upsert_version(
            &self.db,
            &crate::storage::skill_version::SkillVersionUpsert {
                scene: scene.clone(),
                skill_id: skill_id.clone(),
                version: new_version.clone(),
                status: crate::storage::skill_version::STATUS_WATCHING.to_string(),
                score: new_score,
                score_detail: None,
                content: Some(content),
                changelog: Some(format!("AutoSkill 草稿 {} 确认升级", draft_id)),
            },
        )?;

        // 4. 更新 draft status=upgrading→watching
        crate::storage::autoskill_draft::update_status(
            &self.db,
            draft_id,
            crate::storage::autoskill_draft::STATUS_WATCHING,
            None,
            None,
        )?;

        Ok(())
    }

    /// 回滚：观察期分数下降 > threshold 自动回滚。
    ///
    /// 1. 对比新旧版本最近评分
    /// 2. 分数下降 > threshold → 回滚
    /// 3. 恢复旧版本 status=active
    pub async fn rollback_if_degraded(
        &self,
        draft_id: &str,
        threshold: i32,
    ) -> Result<bool, AutoSkillError> {
        // 1. 读取 draft（仅 watching 状态可回滚）
        let draft = {
            let conn = self.db.get_conn();
            let mut stmt = conn.prepare(
                "SELECT scene, skill_id, draft_version, new_score, old_score
                 FROM skill_auto_iter_draft
                 WHERE id = ? AND status = 'watching'",
            )?;
            let rows = stmt.query_map(duckdb::params![draft_id], |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                    row.get::<_, Option<i32>>(3)?,
                    row.get::<_, Option<i32>>(4)?,
                ))
            })?;
            let mut result = Vec::new();
            for row in rows {
                result.push(row?);
            }
            result.into_iter().next()
        };

        let (scene, skill_id, new_version, draft_new_score, draft_old_score) = match draft {
            Some(row) => row,
            None => return Err(AutoSkillError::NotFound(draft_id.to_string())),
        };

        // 获取新版本最新评分（优先用 eval 引擎查询，降级用 draft 中记录的分数）
        let new_score = self
            .eval
            .get_latest_score(&scene, &skill_id, &new_version)
            .await
            .ok()
            .flatten()
            .map(|r| r.total_score)
            .or(draft_new_score)
            .unwrap_or(0);
        let old_score = draft_old_score.unwrap_or(0);

        // 2. 分数下降 ≤ threshold → 不回滚
        if old_score - new_score <= threshold {
            return Ok(false);
        }

        // 3. 回滚：查询最近备份的 rollback 版本并恢复
        let rollback_version = {
            let conn = self.db.get_conn();
            let mut stmt = conn.prepare(
                "SELECT version, score, content, changelog
                 FROM skill_version_manage
                 WHERE scene = ? AND skill_id = ? AND status = 'rollback'
                 ORDER BY activated_at DESC NULLS LAST
                 LIMIT 1",
            )?;
            let rows = stmt.query_map(duckdb::params![scene, skill_id], |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, Option<i32>>(1)?,
                    row.get::<_, Option<String>>(2)?,
                    row.get::<_, Option<String>>(3)?,
                ))
            })?;
            let mut result = Vec::new();
            for row in rows {
                result.push(row?);
            }
            result.into_iter().next()
        };

        // 恢复旧版本 status=active
        if let Some((old_version, old_ver_score, old_content, old_changelog)) = rollback_version {
            crate::storage::skill_version::upsert_version(
                &self.db,
                &crate::storage::skill_version::SkillVersionUpsert {
                    scene: scene.clone(),
                    skill_id: skill_id.clone(),
                    version: old_version,
                    status: crate::storage::skill_version::STATUS_ACTIVE.to_string(),
                    score: old_ver_score,
                    score_detail: None,
                    content: old_content,
                    changelog: old_changelog,
                },
            )?;
        }

        // 新版本标记为 rollback
        crate::storage::skill_version::upsert_version(
            &self.db,
            &crate::storage::skill_version::SkillVersionUpsert {
                scene: scene.clone(),
                skill_id: skill_id.clone(),
                version: new_version,
                status: crate::storage::skill_version::STATUS_ROLLBACK.to_string(),
                score: Some(new_score),
                score_detail: None,
                content: None,
                changelog: Some(format!(
                    "回滚：分数从 {} 降到 {}（超过阈值 {}）",
                    old_score, new_score, threshold
                )),
            },
        )?;

        // 更新 draft status=rollback
        crate::storage::autoskill_draft::update_status(
            &self.db,
            draft_id,
            crate::storage::autoskill_draft::STATUS_ROLLBACK,
            None,
            Some(new_score),
        )?;

        Ok(true)
    }

    /// 批量回滚检查：遍历所有 watching 状态的草稿，
    /// 对每个草稿调用 rollback_if_degraded，返回回滚的数量。
    pub async fn rollback_all_degraded(&self, threshold: i32) -> Result<usize, AutoSkillError> {
        let draft_ids: Vec<String> = {
            let conn = self.db.get_conn();
            let mut stmt = conn.prepare(
                "SELECT id FROM skill_auto_iter_draft WHERE status = 'watching'",
            )?;
            let rows = stmt.query_map([], |row| row.get::<_, String>(0))?;
            let mut ids = Vec::new();
            for row in rows {
                ids.push(row?);
            }
            ids
        };

        let mut rolled_back = 0;
        for id in &draft_ids {
            match self.rollback_if_degraded(id, threshold).await {
                Ok(true) => {
                    rolled_back += 1;
                    log::info!("[autoskill] 草稿 {} 触发回滚", id);
                }
                Ok(false) => {}
                Err(e) => {
                    log::warn!("[autoskill] 草稿 {} 回滚检查失败: {}", id, e);
                }
            }
        }
        Ok(rolled_back)
    }
}

#[derive(Debug, thiserror::Error)]
pub enum AutoSkillError {
    #[error("数据库错误: {0}")]
    Db(#[from] duckdb::Error),
    #[error("无足够数据生成草稿")]
    InsufficientData,
    #[error("草稿评分不达标: {0}")]
    ScoreTooLow(i32),
    #[error("草稿不存在: {0}")]
    NotFound(String),
    #[error("序列化错误: {0}")]
    Serde(#[from] serde_json::Error),
}

/// 优化候选：监测阶段产出的有优化空间的技能。
#[derive(Debug, Clone, serde::Serialize)]
pub struct OptimizationCandidate {
    pub scene: String,
    pub skill_id: String,
    pub current_version: String,
    pub current_score: i32,
    pub run_count: usize,
    pub failure_rate: f64,
    pub reason: String,
}

/// 草稿生成结果：AutoSkill pipeline 产出的新版本草稿。
#[derive(Debug, Clone, serde::Serialize)]
pub struct DraftResult {
    pub draft_id: String,
    pub scene: String,
    pub skill_id: String,
    pub draft_version: String,
    pub content: String, // 生成的 SKILL.md
    pub new_score: i32,
    pub old_score: i32,
    pub optimization_points: Vec<String>,
    pub qualified: bool,
}

/// 合并候选：同 scene 下 action 序列相似的可合并技能组。
#[derive(Debug, Clone, serde::Serialize)]
pub struct MergeCandidate {
    pub scene: String,
    pub skill_ids: Vec<String>,
    pub similarity: f64,
    pub action_signature: String,
    pub total_runs: usize,
}
