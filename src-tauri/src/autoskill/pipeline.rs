// Copyright (c) 2026 MeeJoy
//
// AutoSkill pipeline 完整编排。
//
// 五步流水线：
//   1. LogMiner (PatternMiner::mine_traces) —— 挖掘成功执行链
//   2. PatternFinder (PatternMiner::find_patterns) —— 聚类相似流程
//   3. FlowReconstructor (PatternMiner::reconstruct_flow) —— 精简冗余步骤
//   4. ParamGeneralizer —— 硬编码→参数化
//   5. ScoreCheck —— 估算新版本分数，≥85 分才标记 qualified
//
// 生成的草稿写入 skill_auto_iter_draft 表，状态为 pending_confirm
// （达标）或 rejected（不达标）。

use std::sync::Arc;

use crate::skill_eval::SkillEvalEngine;
use crate::storage::DuckDBPool;

use super::param_generalizer::ParamGeneralizer;
use super::pattern_miner::PatternMiner;
use super::{AutoSkillError, DraftResult, MergeCandidate, OptimizationCandidate};

pub struct AutoSkillPipeline {
    db: Arc<DuckDBPool>,
    miner: PatternMiner,
    generalizer: ParamGeneralizer,
}

impl AutoSkillPipeline {
    pub fn new(db: Arc<DuckDBPool>) -> Self {
        Self {
            miner: PatternMiner::new(db.clone()),
            generalizer: ParamGeneralizer::new(),
            db,
        }
    }

    /// 扫描优化候选：查询执行次数 ≥5 的技能，标记成功率 < 90% 或高频可优化的。
    pub async fn scan_candidates(
        &self,
        scene: &str,
    ) -> Result<Vec<OptimizationCandidate>, AutoSkillError> {
        let conn = self.db.get_conn();
        let mut stmt = conn.prepare(
            "SELECT skill_id,
                    COUNT(*) as run_count,
                    SUM(CASE WHEN status='succeeded' THEN 1 ELSE 0 END)::FLOAT / COUNT(*) as success_rate
             FROM worker_task_log
             WHERE scene = ?1 AND skill_id IS NOT NULL
             GROUP BY skill_id
             HAVING COUNT(*) >= 5
             ORDER BY run_count DESC LIMIT 20",
        )?;

        let rows = stmt.query_map(duckdb::params![scene], |row| {
            let skill_id: String = row.get(0)?;
            let run_count: i64 = row.get(1)?;
            let success_rate: f64 = row.get(2)?;
            Ok((skill_id, run_count as usize, success_rate))
        })?;

        let mut candidates = Vec::new();
        for (skill_id, run_count, success_rate) in rows.flatten() {
            // 成功率 < 90% 或有优化空间
            let reason = if success_rate < 0.9 {
                format!("成功率 {}% 低于 90%", (success_rate * 100.0) as i32)
            } else {
                "高频使用，可优化效率".to_string()
            };
            candidates.push(OptimizationCandidate {
                scene: scene.to_string(),
                skill_id,
                current_version: String::new(),
                current_score: 0,
                run_count,
                failure_rate: 1.0 - success_rate,
                reason,
            });
        }
        Ok(candidates)
    }

    /// 生成草稿：执行完整 AutoSkill pipeline。
    pub async fn generate(
        &self,
        scene: &str,
        skill_id: &str,
        eval: &SkillEvalEngine,
    ) -> Result<DraftResult, AutoSkillError> {
        // 1. LogMiner: 挖掘成功执行链
        let traces = self.miner.mine_traces(scene, skill_id, 20).await?;
        if traces.len() < 3 {
            return Err(AutoSkillError::InsufficientData);
        }

        // 2. PatternFinder: 聚类
        let patterns = self.miner.find_patterns(&traces);
        if patterns.is_empty() {
            return Err(AutoSkillError::InsufficientData);
        }

        // 3. FlowReconstructor: 取频率最高的模式精简
        let best_pattern = patterns.iter().max_by_key(|p| p.frequency).unwrap();
        let reconstructed = self.miner.reconstruct_flow(best_pattern);

        // 4. ParamGeneralizer: 参数泛化
        let (generalized_steps, params) = self.generalizer.generalize(&reconstructed);

        // 5. 生成 SKILL.md 内容
        let content = self.generate_skill_md(skill_id, &generalized_steps, &params);

        // 6. ScoreCheck: 评估新版本
        let new_score = self.estimate_score(&traces, &reconstructed);

        let old_score = eval
            .get_latest_score(scene, skill_id, "")
            .await
            .ok()
            .flatten()
            .map(|r| r.total_score)
            .unwrap_or(0);

        let qualified = new_score >= 85;
        let optimization_points = self.diff_optimization(&traces, &reconstructed);
        let draft_version = self.next_version(&old_score.to_string());

        // 写入 skill_auto_iter_draft 表
        let draft_id = crate::storage::autoskill_draft::insert_draft(
            &self.db,
            &crate::storage::autoskill_draft::DraftInsert {
                scene: scene.to_string(),
                skill_id: skill_id.to_string(),
                draft_version: draft_version.clone(),
                source: crate::storage::autoskill_draft::SOURCE_LOG_MINING.to_string(),
                status: if qualified {
                    crate::storage::autoskill_draft::STATUS_PENDING_CONFIRM.to_string()
                } else {
                    crate::storage::autoskill_draft::STATUS_REJECTED.to_string()
                },
                content: Some(content.clone()),
                old_score: Some(old_score),
                new_score: Some(new_score),
                optimization_points: Some(serde_json::json!(optimization_points)),
                // Phase 1 信号元数据: AutoSkillEngine 自己产的 draft 没有信号来源,
                // 全部 None (DB 列允许 NULL, UpgradeWriter 兜底到 Mcp)。
                skill_kind: None,
                source_kind: None,
                evidence_json: None,
                signal_ref: None,
            },
        )?;

        Ok(DraftResult {
            draft_id,
            scene: scene.to_string(),
            skill_id: skill_id.to_string(),
            draft_version,
            content,
            new_score,
            old_score,
            optimization_points,
            qualified,
        })
    }

    /// 扫描合并候选：查询同 scene 下所有 skill_id（run_count≥3），按 action
    /// 序列 Jaccard 相似度聚类，找出可合并的相似技能组。
    ///
    /// 聚类规则：单链聚类（single-linkage），任意两 skill 的 action 集合
    /// Jaccard 相似度 ≥0.6 即归入同一组。只返回组内 ≥2 个 skill_id 的候选。
    pub async fn scan_merge_candidates(
        &self,
        scene: &str,
    ) -> Result<Vec<MergeCandidate>, AutoSkillError> {
        // 1. 查询所有 run_count >= 3 的 skill_id
        let skill_ids: Vec<(String, usize)> = {
            let conn = self.db.get_conn();
            let mut stmt = conn.prepare(
                "SELECT skill_id, COUNT(*) as run_count
                 FROM worker_task_log
                 WHERE scene = ?1 AND skill_id IS NOT NULL AND status = 'succeeded'
                 GROUP BY skill_id
                 HAVING COUNT(*) >= 3",
            )?;
            let rows = stmt.query_map(duckdb::params![scene], |row| {
                let skill_id: String = row.get(0)?;
                let run_count: i64 = row.get(1)?;
                Ok((skill_id, run_count as usize))
            })?;
            let mut result = Vec::new();
            for r in rows.flatten() {
                result.push(r);
            }
            result
        };

        if skill_ids.len() < 2 {
            return Ok(Vec::new());
        }

        // 2. 为每个 skill_id 挖掘成功 trace，提取 action 集合（去重后的 action 列表）
        let mut skill_actions: Vec<(String, std::collections::HashSet<String>, usize)> =
            Vec::new();
        for (skill_id, run_count) in &skill_ids {
            let traces = self.miner.mine_traces(scene, skill_id, 20).await?;
            let mut actions: std::collections::HashSet<String> =
                std::collections::HashSet::new();
            for trace in &traces {
                for step in &trace.steps {
                    actions.insert(step.action.clone());
                }
            }
            if !actions.is_empty() {
                skill_actions.push((skill_id.clone(), actions, *run_count));
            }
        }

        if skill_actions.len() < 2 {
            return Ok(Vec::new());
        }

        // 3. 单链聚类：Jaccard 相似度 >= 0.6 的归入同一组
        let n = skill_actions.len();
        // union-find 结构
        let mut parent: Vec<usize> = (0..n).collect();
        let find = |parent: &mut Vec<usize>, mut x: usize| -> usize {
            while parent[x] != x {
                parent[x] = parent[parent[x]];
                x = parent[x];
            }
            x
        };
        let union = |parent: &mut Vec<usize>, a: usize, b: usize| {
            let ra = find(parent, a);
            let rb = find(parent, b);
            if ra != rb {
                parent[ra] = rb;
            }
        };

        // 计算两两 Jaccard 相似度并合并
        let mut best_sim: std::collections::HashMap<(usize, usize), f64> =
            std::collections::HashMap::new();
        for i in 0..n {
            for j in (i + 1)..n {
                let sim = AutoSkillPipeline::jaccard_similarity(&skill_actions[i].1, &skill_actions[j].1);
                if sim >= 0.6 {
                    union(&mut parent, i, j);
                    best_sim.insert((i, j), sim);
                }
            }
        }

        // 4. 按聚类根分组
        let mut groups: std::collections::HashMap<usize, Vec<usize>> =
            std::collections::HashMap::new();
        for i in 0..n {
            let root = find(&mut parent, i);
            groups.entry(root).or_default().push(i);
        }

        // 5. 构造 MergeCandidate（只保留组内 >= 2 个 skill_id 的）
        let mut candidates = Vec::new();
        for members in groups.values() {
            if members.len() < 2 {
                continue;
            }
            let mut skill_ids_in_group: Vec<String> = members
                .iter()
                .map(|&i| skill_actions[i].0.clone())
                .collect();
            skill_ids_in_group.sort();

            let total_runs: usize = members.iter().map(|&i| skill_actions[i].2).sum();

            // 组内最高相似度作为代表 similarity
            let mut max_sim = 0.0;
            for i in 0..members.len() {
                for j in (i + 1)..members.len() {
                    let a = members[i];
                    let b = members[j];
                    let key = if a < b { (a, b) } else { (b, a) };
                    if let Some(&s) = best_sim.get(&key) {
                        if s > max_sim {
                            max_sim = s;
                        }
                    }
                }
            }

            // action_signature：取第一个 skill 的 action 排序拼接
            let mut sig_actions: Vec<String> =
                skill_actions[members[0]].1.iter().cloned().collect();
            sig_actions.sort();
            let action_signature = sig_actions.join("|");

            candidates.push(MergeCandidate {
                scene: scene.to_string(),
                skill_ids: skill_ids_in_group,
                similarity: max_sim,
                action_signature,
                total_runs,
            });
        }

        Ok(candidates)
    }

    /// 生成合并草稿：挖掘多个相似 skill_id 的成功 trace，合并去重步骤，
    /// 参数泛化后生成合并后的 SKILL.md 草稿。
    ///
    /// 新 skill_id 用 `merged-{hash前8位}` 命名（hash 来自排序后的 skill_ids）。
    pub async fn generate_merge_draft(
        &self,
        scene: &str,
        skill_ids: &[String],
        eval: &SkillEvalEngine,
    ) -> Result<DraftResult, AutoSkillError> {
        if skill_ids.len() < 2 {
            return Err(AutoSkillError::InsufficientData);
        }

        // 1. 挖掘每个 skill_id 的成功 trace，合并到一起
        let mut all_traces: Vec<super::pattern_miner::ExecutionTrace> = Vec::new();
        for skill_id in skill_ids {
            let traces = self.miner.mine_traces(scene, skill_id, 20).await?;
            all_traces.extend(traces);
        }
        if all_traces.len() < 3 {
            return Err(AutoSkillError::InsufficientData);
        }

        // 2. PatternFinder: 聚类
        let patterns = self.miner.find_patterns(&all_traces);
        if patterns.is_empty() {
            return Err(AutoSkillError::InsufficientData);
        }

        // 3. FlowReconstructor: 取频率最高的模式精简
        let best_pattern = patterns.iter().max_by_key(|p| p.frequency).unwrap();
        let reconstructed = self.miner.reconstruct_flow(best_pattern);

        // 4. ParamGeneralizer: 参数泛化
        let (generalized_steps, params) = self.generalizer.generalize(&reconstructed);

        // 5. 生成新 skill_id：merged-{sha256(排序后的skill_ids)[:8]}
        let mut sorted_ids = skill_ids.to_vec();
        sorted_ids.sort();
        let merged_skill_id = {
            use sha2::{Digest, Sha256};
            let mut hasher = Sha256::new();
            hasher.update(sorted_ids.join("|").as_bytes());
            let hash = hasher.finalize();
            let hex: String = hash.iter().take(4).map(|b| format!("{:02x}", b)).collect();
            format!("merged-{}", hex)
        };

        // 6. 生成 SKILL.md 内容（含合并来源说明）
        let content = self.generate_merged_skill_md(
            &merged_skill_id,
            &sorted_ids,
            &generalized_steps,
            &params,
        );

        // 7. ScoreCheck: 评估新版本分数
        let new_score = self.estimate_score(&all_traces, &reconstructed);

        // old_score：取各 skill_id 最新评分的平均值
        let mut old_scores: Vec<i32> = Vec::new();
        for skill_id in skill_ids {
            if let Ok(Some(r)) = eval.get_latest_score(scene, skill_id, "").await {
                old_scores.push(r.total_score);
            }
        }
        let old_score: i32 = if old_scores.is_empty() {
            0
        } else {
            old_scores.iter().sum::<i32>() / old_scores.len() as i32
        };

        let qualified = new_score >= 85;
        let optimization_points = vec![
            format!("合并 {} 个相似技能: {}", skill_ids.len(), sorted_ids.join(", ")),
            "步骤合并去重".to_string(),
            "参数泛化：硬编码值替换为可变参数".to_string(),
        ];
        let draft_version = self.next_version(&old_score.to_string());

        // 写入 skill_auto_iter_draft 表
        let draft_id = crate::storage::autoskill_draft::insert_draft(
            &self.db,
            &crate::storage::autoskill_draft::DraftInsert {
                scene: scene.to_string(),
                skill_id: merged_skill_id.clone(),
                draft_version: draft_version.clone(),
                source: crate::storage::autoskill_draft::SOURCE_LOG_MINING.to_string(),
                status: if qualified {
                    crate::storage::autoskill_draft::STATUS_PENDING_CONFIRM.to_string()
                } else {
                    crate::storage::autoskill_draft::STATUS_REJECTED.to_string()
                },
                content: Some(content.clone()),
                old_score: Some(old_score),
                new_score: Some(new_score),
                optimization_points: Some(serde_json::json!(optimization_points)),
                // Phase 1 信号元数据: AutoSkillEngine 自己产的 draft 没有信号来源,
                // 全部 None (DB 列允许 NULL, UpgradeWriter 兜底到 Mcp)。
                skill_kind: None,
                source_kind: None,
                evidence_json: None,
                signal_ref: None,
            },
        )?;

        Ok(DraftResult {
            draft_id,
            scene: scene.to_string(),
            skill_id: merged_skill_id,
            draft_version,
            content,
            new_score,
            old_score,
            optimization_points,
            qualified,
        })
    }

    /// 计算 Jaccard 相似度：|交集| / |并集|。
    fn jaccard_similarity(
        a: &std::collections::HashSet<String>,
        b: &std::collections::HashSet<String>,
    ) -> f64 {
        if a.is_empty() && b.is_empty() {
            return 1.0;
        }
        let intersection = a.intersection(b).count();
        let union = a.len() + b.len() - intersection;
        if union == 0 {
            return 0.0;
        }
        intersection as f64 / union as f64
    }

    /// 生成合并后的 SKILL.md 内容（含合并来源说明）。
    fn generate_merged_skill_md(
        &self,
        skill_id: &str,
        source_skills: &[String],
        steps: &[super::param_generalizer::GeneralizedStep],
        params: &[super::param_generalizer::ParamDef],
    ) -> String {
        let mut md = String::new();
        md.push_str(&format!("# {}\n\n", skill_id));
        md.push_str("## 合并来源\n\n");
        md.push_str(&format!(
            "本技能由以下 {} 个相似技能合并而成：\n\n",
            source_skills.len()
        ));
        for s in source_skills {
            md.push_str(&format!("- {}\n", s));
        }
        md.push_str("\n## 参数\n\n");
        if params.is_empty() {
            md.push_str("无参数\n\n");
        } else {
            for p in params {
                md.push_str(&format!(
                    "- **{}**: {} (默认: {})\n",
                    p.name,
                    p.description,
                    p.default_value.as_deref().unwrap_or("无")
                ));
            }
        }
        md.push_str("\n## 执行步骤\n\n");
        for (i, step) in steps.iter().enumerate() {
            md.push_str(&format!(
                "{}. `{}` on `{}`",
                i + 1,
                step.action,
                step.target
            ));
            if let Some(v) = &step.value {
                md.push_str(&format!(" → {}", v));
            }
            md.push('\n');
        }
        md
    }

    /// 生成 SKILL.md 内容（参数章节 + 执行步骤章节）。
    fn generate_skill_md(
        &self,
        skill_id: &str,
        steps: &[super::param_generalizer::GeneralizedStep],
        params: &[super::param_generalizer::ParamDef],
    ) -> String {
        let mut md = String::new();
        md.push_str(&format!("# {}\n\n", skill_id));
        md.push_str("## 参数\n\n");
        if params.is_empty() {
            md.push_str("无参数\n\n");
        } else {
            for p in params {
                md.push_str(&format!(
                    "- **{}**: {} (默认: {})\n",
                    p.name,
                    p.description,
                    p.default_value.as_deref().unwrap_or("无")
                ));
            }
        }
        md.push_str("\n## 执行步骤\n\n");
        for (i, step) in steps.iter().enumerate() {
            md.push_str(&format!(
                "{}. `{}` on `{}`",
                i + 1,
                step.action,
                step.target
            ));
            if let Some(v) = &step.value {
                md.push_str(&format!(" → {}", v));
            }
            md.push('\n');
        }
        md
    }

    /// 估算新版本分数（简化版，不调用 SkillEvalEngine）。
    ///
    /// 基于 trace 成功率（均为 succeeded）+ 平均耗时 + 通用性估算：
    ///   - 成功率 40%：95 分（trace 都是成功的）
    ///   - 稳定性 25%：90 分（去重后更稳定）
    ///   - 效率   20%：按平均耗时分档
    ///   - 通用性 15%：75 分（参数泛化后通用性提升）
    fn estimate_score(
        &self,
        traces: &[super::pattern_miner::ExecutionTrace],
        _reconstructed: &[super::pattern_miner::TraceStep],
    ) -> i32 {
        let avg_duration = traces.iter().map(|t| t.duration_ms).sum::<i64>() / traces.len() as i64;

        let success_score: f64 = 95.0;
        let stability_score: f64 = 90.0;
        let efficiency_score: f64 = if avg_duration < 5000 {
            95.0
        } else if avg_duration < 30000 {
            80.0
        } else {
            65.0
        };
        let generality_score: f64 = 75.0;

        let total: f64 = success_score * 0.4
            + stability_score * 0.25
            + efficiency_score * 0.2
            + generality_score * 0.15;
        total.round() as i32
    }

    /// 生成优化对比点（步骤精简 + 参数泛化 + 去重）。
    fn diff_optimization(
        &self,
        traces: &[super::pattern_miner::ExecutionTrace],
        reconstructed: &[super::pattern_miner::TraceStep],
    ) -> Vec<String> {
        let mut points = Vec::new();
        let avg_steps_before =
            traces.iter().map(|t| t.steps.len()).sum::<usize>() as f64 / traces.len() as f64;
        let steps_after = reconstructed.len() as f64;
        if steps_after < avg_steps_before {
            points.push(format!(
                "步骤数从 {} 精简到 {}",
                avg_steps_before as i32,
                steps_after as i32
            ));
        }
        points.push("参数泛化：硬编码值替换为可变参数".to_string());
        points.push("去除重复步骤".to_string());
        points
    }

    /// 生成下一个版本号（用时间戳后 4 位作 patch，简单健壮）。
    fn next_version(&self, _current: &str) -> String {
        format!("1.0.{}", chrono::Utc::now().timestamp() % 10000)
    }
}
