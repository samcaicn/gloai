// Copyright (c) 2026 AIMarketing
//
// EvolutionOrchestrator — Hermes 自进化编排器 (Phase 1)。
//
// 单一入口, 把"采集 → 分析 → 门控 → 落 draft"串成一个闭环:
//
//   sessions.db(messages) ─┐
//   tupai.db(memories)    ─┼─► SessionAnalyzer.collect_window
//                          │   └─► analyze_window (LLM, hermes_llm_service)
//                          │        └─► Vec<EvolutionSignal>
//                          │
//                          ▼
//   evolution_signals 表 (SQLite, HermesDb) — 去重入库
//                          │
//                          ▼
//   EvolutionGate.handle(signal, current_skill_md)
//                          │
//            ┌─────────────┴─────────────┐
//            ▼                           ▼
//      Evaluated(ProposalResult)    PassThrough
//            │                           │
//   should_propose?                    (交给既有 AutoSkillEngine,
//      ├ yes → insert DraftRow          Phase 1 不处理, signal 标 consumed=3)
//      │       (status=pending_confirm)
//      └ no  → mark consumed=2 (拒绝留痕)
//
// 触发: 会话结束 / auto_evolve 周期 / 手动 (commands::evolution)。
//
// 设计要点:
//   * 跨 DB 边界: signals 落 SQLite (HermesDb), drafts 落 DuckDB (autoskill_draft)。
//     以 signal_id 作外键引用 (draft.signal_ref), 不跨库事务。
//   * migrate_phase1 在每次 run 顶部调用 (幂等), 保证 DuckDB 4 个新列存在。
//   * LLM 经 MCP `llm.stream_request` (hermes_llm_complete_messages) 始终可用;
//     MCP 失败时 SessionAnalyzer 内部降级到 heuristic_analyze (confidence<0.6 会被 gate 丢)。

use std::sync::Arc;

use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Emitter, Manager};

use crate::hermes::evolution_gate::{EvolutionGate, GateOutcome, ProposalResult};
use crate::hermes::evolution_signal::{
    EvolutionSignal, SkillKind, DEFAULT_ANALYSIS_WINDOW_MS,
};
use crate::hermes::persistence::{AnalysisRunRow, HermesDb, StoredSignal};
use crate::hermes::session_analyzer::{AnalyzeResult, SessionAnalyzer, SkillSummary};
use crate::storage::autoskill_draft::{
    insert_draft, migrate_phase1, DraftInsert, STATUS_PENDING_CONFIRM, SOURCE_TEACHING,
};
use crate::storage::DuckDBPool;

/// 单次会话分析的摘要, 返回给前端。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AnalysisRunSummary {
    pub run_id: String,
    pub sessions_scanned: u32,
    pub signals_emitted: u32,
    pub drafts_created: u32,
    /// 本次 run 中被标记 consumed=3 (PassThrough / Automation 跳过) 的信号数。
    /// 由 run_session_analysis 累加, run_once 透传到 RunSummary.passthrough_count。
    pub passthrough_count: u32,
    pub degraded: bool,
    pub llm_tokens_used: Option<u32>,
}

/// 全量 run 摘要 (会话分析 + PassThrough 计数)。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RunSummary {
    pub analysis: AnalysisRunSummary,
    pub passthrough_count: u32,
    pub reason: String,
}

pub struct EvolutionOrchestrator {
    hermes_db: Arc<HermesDb>,
    duckdb_pool: Arc<DuckDBPool>,
    gate: EvolutionGate,
    analyzer: SessionAnalyzer,
    /// 默认 scene。Phase 1 固定 "default"; 后续可按 workspace 切分。
    scene: String,
}

impl EvolutionOrchestrator {
    pub fn new(hermes_db: Arc<HermesDb>, duckdb_pool: Arc<DuckDBPool>) -> Self {
        Self {
            hermes_db,
            duckdb_pool,
            gate: EvolutionGate::new(),
            analyzer: SessionAnalyzer::new(),
            scene: "default".to_string(),
        }
    }

    /// 跑一次会话分析 + 把 SessionInsight 信号转成 draft。
    /// `since_ms = None` → 默认覆盖最近 24h (DEFAULT_ANALYSIS_WINDOW_MS)。
    pub async fn run_session_analysis(
        &self,
        app: &AppHandle,
        since_ms: Option<i64>,
    ) -> Result<AnalysisRunSummary, String> {
        // 1. 幂等迁移 DuckDB (Track C caveat: insert_draft 前必须确保 4 列存在)
        migrate_phase1(&self.duckdb_pool).map_err(|e| e.to_string())?;

        let started_at = chrono::Utc::now();
        // DEFAULT_ANALYSIS_WINDOW_MS 是"时长"(24h), 必须用"现在 - 时长"得到绝对时间戳;
        // 否则 since=86_400_000 会被 collect_window 当作 1970-01-01 的时间戳, 扫描全量历史。
        let since = since_ms.unwrap_or_else(|| {
            chrono::Utc::now().timestamp_millis() - DEFAULT_ANALYSIS_WINDOW_MS
        });

        // 2. 采集会话摘要
        let sessions = self.analyzer.collect_window(app, since).await?;
        let sessions_scanned = sessions.len() as u32;

        // 3. LLM 分析 (走 MCP `llm.stream_request`, 始终可用; MCP 失败/解析失败时
        //    SessionAnalyzer 内部降级到启发式并置 degraded=true)
        let installed = self.collect_installed_skills(app);
        let analyze_result: AnalyzeResult = self
            .analyzer
            .analyze_window(&sessions, &installed)
            .await
            .unwrap_or_else(|_| AnalyzeResult {
                signals: self.analyzer.heuristic_analyze(&sessions),
                degraded: true,
                llm_tokens_used: None,
            });

        // 4. 信号入库 (去重, signal_id 唯一)。signals_emitted 只计成功 insert 的数量,
        //    避免把 dup/失败信号算作"已发射"造成指标高估。
        let mut drafts_created: u32 = 0;
        let mut signals_emitted: u32 = 0;
        let mut passthrough_count: u32 = 0;
        for sig in &analyze_result.signals {
            match self.hermes_db.insert_evolution_signal(sig) {
                Ok(()) => signals_emitted += 1,
                Err(e) => log::warn!("insert_evolution_signal failed (likely dup): {}", e),
            }
        }

        // 5. 处理每条 SessionInsight 信号 → gate → draft
        for sig in &analyze_result.signals {
            // 仅处理 SessionInsight; 其他类型 (Telemetry/MergeCandidate/MemoryLinked)
            // 在 Phase 1 留给既有 AutoSkillEngine, 这里标记 consumed=3 (passthrough)
            let is_session_insight = matches!(sig, EvolutionSignal::SessionInsight { .. });
            if !is_session_insight {
                let sid = sig.signal_id().to_string();
                let _ = self.hermes_db.mark_signal_consumed(&sid, 3);
                passthrough_count += 1;
                continue;
            }

            // Automation 技能不在 skills_optimized/<id>.md 路径下 (走加密 skill store),
            // fetch_current_skill_md 取不到 current_md 会让 gate 报 MissingCurrent 误标
            // consumed=2 (拒绝留痕)。Phase 1 直接 passthrough (consumed=3), 留给
            // Phase 2 的加密技能升级路径处理。
            if sig.skill_kind() == SkillKind::Automation {
                let _ = self.hermes_db.mark_signal_consumed(sig.signal_id(), 3);
                passthrough_count += 1;
                continue;
            }

            // 取当前 skill.md (FrequentCorrection/NegativeRating 需要)
            let current_md = self.fetch_current_skill_md(app, sig);

            let outcome = self.gate.handle(sig, current_md.as_deref()).await;

            match outcome {
                Ok(GateOutcome::Evaluated(result)) => {
                    if result.should_propose {
                        if let Err(e) = self.insert_draft_from_result(sig, &result) {
                            log::warn!("insert_draft failed for {}: {}", result.signal_id, e);
                            // 落 draft 失败也要标 consumed=2 留痕, 避免信号
                            // consumed=0 永久堆积在 pending 队列。
                            let _ = self.hermes_db.mark_signal_consumed(&result.signal_id, 2);
                        } else {
                            drafts_created += 1;
                            let _ = self.hermes_db.mark_signal_consumed(&result.signal_id, 1);
                        }
                    } else {
                        // 拒绝留痕
                        let _ = self.hermes_db.mark_signal_consumed(&result.signal_id, 2);
                        log::info!(
                            "signal {} skipped: {:?}",
                            result.signal_id,
                            result.skip_reason
                        );
                    }
                }
                Ok(GateOutcome::PassThrough(_)) => {
                    let _ = self.hermes_db.mark_signal_consumed(sig.signal_id(), 3);
                    passthrough_count += 1;
                }
                Err(e) => {
                    log::warn!("gate error on signal {}: {}", sig.signal_id(), e);
                    let _ = self.hermes_db.mark_signal_consumed(sig.signal_id(), 2);
                }
            }
        }

        // 6. 落 analysis run 记录。run_id 追加 8 位 uuid 防同毫秒并发冲突。
        let run_id = format!(
            "run_{}_{}",
            chrono::Utc::now().timestamp_millis(),
            &uuid::Uuid::new_v4().to_string()[..8]
        );
        let run = AnalysisRunRow {
            run_id: run_id.clone(),
            started_at: started_at.to_rfc3339(),
            finished_at: chrono::Utc::now().to_rfc3339(),
            sessions_scanned: sessions_scanned as i64,
            signals_emitted: signals_emitted as i64,
            llm_tokens_used: analyze_result.llm_tokens_used.map(|u| u as i64),
            degraded: analyze_result.degraded,
            summary: Some(format!(
                "drafts_created={}, passthrough={}",
                drafts_created, passthrough_count
            )),
        };
        if let Err(e) = self.hermes_db.insert_analysis_run(&run) {
            log::warn!("insert_analysis_run failed: {}", e);
        }

        Ok(AnalysisRunSummary {
            run_id,
            sessions_scanned,
            signals_emitted,
            drafts_created,
            passthrough_count,
            degraded: analyze_result.degraded,
            llm_tokens_used: analyze_result.llm_tokens_used,
        })
    }

    /// 全量 run: 会话分析 + (PassThrough 在 run_session_analysis 内已标记 consumed=3)。
    pub async fn run_once(&self, app: &AppHandle, reason: &str) -> Result<RunSummary, String> {
        let analysis = self.run_session_analysis(app, None).await?;
        let passthrough_count = analysis.passthrough_count;
        Ok(RunSummary {
            analysis,
            passthrough_count,
            reason: reason.to_string(),
        })
    }

    pub fn list_pending_signals(&self, limit: u32) -> Result<Vec<StoredSignal>, String> {
        self.hermes_db.list_pending_signals(limit)
    }

    /// 便捷: 仅返回未消费的 SessionInsight 信号 (前端"会话洞察"tab 用)。
    pub fn list_session_insights(&self, _scene: &str) -> Result<Vec<StoredSignal>, String> {
        let all = self.hermes_db.list_pending_signals(200)?;
        // list_pending_signals 的 SQL 已 WHERE consumed = 0, 这里无需再过滤 consumed
        // (冗余过滤会让任何非 0 值的行被丢弃, 但 SQL 已保证全为 0; 仅按 signal_kind 过滤)。
        Ok(all
            .into_iter()
            .filter(|s| s.signal_kind == "sessionInsight")
            .collect())
    }

    pub fn mark_signal_consumed(&self, signal_id: &str, consumed: i32) -> Result<(), String> {
        self.hermes_db.mark_signal_consumed(signal_id, consumed)
    }

    // === 内部辅助 ===========================================================

    /// 把 ProposalResult 转成 DraftInsert 并落盘。
    fn insert_draft_from_result(
        &self,
        signal: &EvolutionSignal,
        result: &ProposalResult,
    ) -> Result<String, duckdb::Error> {
        let evidence_json = serde_json::to_string(signal).ok();
        let draft = DraftInsert {
            scene: self.scene.clone(),
            skill_id: result.skill_id.clone(),
            draft_version: format!("ev-{}", chrono::Utc::now().timestamp_millis()),
            source: SOURCE_TEACHING.to_string(),
            status: STATUS_PENDING_CONFIRM.to_string(),
            content: Some(result.proposed_skill_md.clone()),
            old_score: result.old_score.map(|s| (s * 100.0) as i32),
            new_score: Some((result.new_score * 100.0) as i32),
            optimization_points: Some(serde_json::json!({
                "suggested_action": result.suggested_action,
                "evidence": result.evidence,
                "verdict": format!("{:?}", result.evaluation.verdict),
            })),
            skill_kind: Some(result.skill_kind.as_str().to_string()),
            source_kind: Some(result.source_kind.as_str().to_string()),
            evidence_json,
            signal_ref: Some(result.signal_id.clone()),
        };
        insert_draft(&self.duckdb_pool, &draft)
    }

    /// 枚举本地已安装的技能 (`<app_data>/skills_optimized/*.md`), 喂给 LLM
    /// 避免 MissingSkill 误判 (把已存在技能建议为"缺失")。
    /// Phase 2: 通过 front matter 的 `preferred_execution_type` 识别 automation,
    /// 二者均纳入; builtin 仍不在此列 (Rust 内建, 无 skill.md)。
    fn collect_installed_skills(&self, app: &AppHandle) -> Vec<SkillSummary> {
        let metas = match crate::commands::skill::list_optimized_skills(app.clone()) {
            Ok(m) => m,
            Err(e) => {
                log::warn!("[evolution] list_optimized_skills failed: {}", e);
                return Vec::new();
            }
        };
        metas
            .into_iter()
            .map(|m| SkillSummary {
                skill_id: m.skill_id,
                name: m.skill_name,
                kind: peek_skill_kind(&m.file_path),
                description: peek_skill_description(&m.file_path),
            })
            .collect()
    }

    /// 对 FrequentCorrection/NegativeRating/RepetitiveAction 信号,
    /// 取当前 skill.md 内容供 gate 改写。
    /// Phase 2: mcp / automation 从 optimized skills dir 读; builtin 从
    /// skills_overrides 覆盖层读 (若存在)。
    ///
    /// 注: 本函数用 `std::fs::read_to_string` 在 async 上下文里做同步文件 IO。
    /// 单个 skill.md 体积小 (KB 级), 阻塞 tokio worker 的时间可忽略; 且当前 tokio
    /// 依赖未启用 `fs` feature, 无法直接用 `tokio::fs`。若未来 skill.md 变大或调用
    /// 频率升高, 应改为 `tauri::async_runtime::spawn_blocking` 包裹同步读。
    /// Automation 技能不在 skills_optimized/<id>.md 路径 (走加密 skill store),
    /// 直接返回 None; 调用方 (run_session_analysis) 已对 Automation 早期 passthrough,
    /// 这里是防御性兜底, 避免误读到错误文件。
    fn fetch_current_skill_md(&self, app: &AppHandle, signal: &EvolutionSignal) -> Option<String> {
        let skill_id = signal.skill_id()?;
        let app_data = app.path().app_data_dir().ok()?;
        match signal.skill_kind() {
            SkillKind::Mcp => {
                let path = app_data
                    .join("skills_optimized")
                    .join(format!("{}.md", skill_id));
                std::fs::read_to_string(path).ok()
            }
            SkillKind::Automation => {
                // Automation 走加密 skill store, 不在 skills_optimized 路径;
                // 调用方应在进 gate 前已 passthrough, 这里返回 None 防御兜底。
                None
            }
            SkillKind::Builtin => {
                let path = app_data
                    .join("skills_overrides")
                    .join(format!("{}.md", skill_id));
                std::fs::read_to_string(path).ok()
            }
        }
    }
}

// === 辅助: skill.md description 提取 =====================================

/// 从 skill.md front matter 提取 `description` 字段 (轻量扫描, 失败/缺失返回空)。
/// 与 `commands::skill::peek_skill_md_meta` 同思路, 但只取 description。
/// 只扫前 60 行 (description 通常在 front matter 顶部), 防 OOM。
///
/// 注: 用 `std::fs::read_to_string` 同步读; 调用方 `collect_installed_skills` 是
/// 同步函数, 在 async `run_session_analysis` 内被直接调用。skill.md 体积小 (KB 级),
/// 阻塞可忽略; tokio 依赖未启用 `fs` feature 无法用 `tokio::fs`。若未来变为热点路径,
/// 应把 `collect_installed_skills` 改为 async + `spawn_blocking` 包裹。
fn peek_skill_description(file_path: &str) -> String {
    let Ok(content) = std::fs::read_to_string(file_path) else {
        return String::new();
    };
    let body = content.strip_prefix('\u{FEFF}').unwrap_or(&content);
    let mut in_front = false;
    for line in body.lines().take(60) {
        let trimmed = line.trim_start();
        if trimmed == "---" {
            in_front = !in_front;
            continue;
        }
        if !in_front {
            continue;
        }
        if let Some((key, val)) = trimmed.split_once(':') {
            if key.trim().eq_ignore_ascii_case("description") {
                let v = val.trim();
                // 剥行内注释 + 单/双引号
                let v = v.split(" #").next().unwrap_or(v).trim();
                return v.trim_matches('"').trim_matches('\'').to_string();
            }
        }
    }
    String::new()
}

/// 从 skill.md front matter 判断技能类型。automation 技能由 generate_skill_md
/// (evolution_gate.rs) 的 prompt 约定携带 `preferred_execution_type` 字段;
/// 缺失该字段则视为 mcp。builtin 无 skill.md, 不经过本函数。
/// 只扫前 60 行 (front matter 顶部), 防 OOM。
///
/// 注: 用 `std::fs::read_to_string` 同步读 (见 `peek_skill_description` 注释,
/// 同样的阻塞 IO 权衡)。
fn peek_skill_kind(file_path: &str) -> SkillKind {
    let Ok(content) = std::fs::read_to_string(file_path) else {
        return SkillKind::Mcp;
    };
    let body = content.strip_prefix('\u{FEFF}').unwrap_or(&content);
    let mut in_front = false;
    for line in body.lines().take(60) {
        let trimmed = line.trim_start();
        if trimmed == "---" {
            in_front = !in_front;
            continue;
        }
        if !in_front {
            continue;
        }
        if let Some((key, _val)) = trimmed.split_once(':') {
            if key.trim().eq_ignore_ascii_case("preferred_execution_type") {
                return SkillKind::Automation;
            }
        }
    }
    SkillKind::Mcp
}

// === Tauri state 访问辅助 =================================================

/// 从 Tauri state 获取 orchestrator, 未注册时返回友好错误。
pub fn get_orchestrator(app: &AppHandle) -> Result<Arc<EvolutionOrchestrator>, String> {
    app.try_state::<Arc<EvolutionOrchestrator>>()
        .map(|s| s.inner().clone())
        .ok_or_else(|| "EvolutionOrchestrator 尚未初始化".to_string())
}

// === 触发入口 (周期 + session_end 共用) ==================================
//
// `try_trigger_analysis` 是统一的非阻塞触发入口, 被 lib.rs 的 5 分钟周期
// spawn 和 commands::session::chat_session_save 的 session_end 钩子共用。
// 用 `AtomicBool` + RAII guard 保证同一时刻只有一个分析在跑, 避免
// session 高频保存 + 周期 tick 并发导致重复 LLM 调用。返回 `true` 表示
// 已排队执行, `false` 表示已有分析在跑 (调用方可安全跳过)。

use std::sync::atomic::{AtomicBool, Ordering};

static ANALYSIS_IN_PROGRESS: AtomicBool = AtomicBool::new(false);

/// RAII guard: 构造时什么都不做, drop 时重置 `ANALYSIS_IN_PROGRESS`。
/// 保证即使 `run_once` 出错 / panic, flag 也会被重置 (tokio::spawn 会
/// 捕获 panic 不让进程崩溃, 但 flag 需要 Drop 来重置)。
struct AnalysisGuard;
impl Drop for AnalysisGuard {
    fn drop(&mut self) {
        ANALYSIS_IN_PROGRESS.store(false, Ordering::SeqCst);
    }
}

/// 非阻塞触发一次自进化分析 (会话分析 → gate → draft)。
///
/// - `reason`: 触发来源标签 ("periodic" / "session_save" / "manual"),
///   写入 `RunSummary.reason` 供前端展示 + 日志追溯。
/// - 返回 `true` = 已排队执行; `false` = 已有分析在跑, 跳过。
/// - orchestrator 未注册时静默跳过 (返回 `true` 但 spawn 内部立即返回)。
/// - 分析完成后 emit `evolution://analysis-done` 通知前端刷新 "会话洞察" tab。
pub fn try_trigger_analysis(app: &AppHandle, reason: &str) -> bool {
    if ANALYSIS_IN_PROGRESS
        .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
        .is_err()
    {
        log::debug!(
            "[evolution] trigger '{}' skipped: analysis in progress",
            reason
        );
        return false;
    }
    let app_handle = app.clone();
    let reason = reason.to_string();
    tauri::async_runtime::spawn(async move {
        // guard 在 block 退出时重置 flag (无论 run_once 成功/失败/panic)
        let _guard = AnalysisGuard;

        let orch = match get_orchestrator(&app_handle) {
            Ok(o) => o,
            Err(e) => {
                log::debug!(
                    "[evolution] trigger '{}' skipped: orchestrator not registered ({})",
                    reason, e
                );
                return;
            }
        };
        match orch.run_once(&app_handle, &reason).await {
            Ok(summary) => {
                log::info!(
                    "[evolution] trigger '{}' done: sessions={}, signals={}, drafts={}, degraded={}",
                    reason,
                    summary.analysis.sessions_scanned,
                    summary.analysis.signals_emitted,
                    summary.analysis.drafts_created,
                    summary.analysis.degraded,
                );
                let _ = app_handle.emit(
                    "evolution://analysis-done",
                    serde_json::json!({
                        "sessionsScanned": summary.analysis.sessions_scanned,
                        "signalsEmitted": summary.analysis.signals_emitted,
                        "draftsCreated": summary.analysis.drafts_created,
                        "degraded": summary.analysis.degraded,
                        "reason": summary.reason,
                        "ok": true,
                    }),
                );
            }
            Err(e) => {
                // 失败也要 emit, 否则前端 AutoskillScene 的 loading 永远不结束、
                // "会话洞察" tab 不刷新 (前端只监听 analysis-done 事件)。
                log::warn!("[evolution] trigger '{}' failed: {}", reason, e);
                let _ = app_handle.emit(
                    "evolution://analysis-done",
                    serde_json::json!({
                        "ok": false,
                        "reason": reason,
                        "error": e,
                    }),
                );
            }
        }
    });
    true
}

/// 阻塞式触发一次自进化分析, 供 `evolution_trigger_session_analysis` 命令使用。
///
/// 与 `try_trigger_analysis` 的区别: 后者 spawn 后立即返回 (非阻塞, 无返回值);
/// 本函数在调用方上下文 acquire AtomicBool → run → emit → 释放, 返回完整摘要。
/// 这样手动触发也走统一并发护栏, 避免与 periodic/session_save 并发产生重复信号。
///
/// 返回:
/// - `Ok(summary)` — 分析完成 (可能 degraded, 但流程跑通)
/// - `Err("analysis in progress")` — 已有分析在跑, 跳过
pub async fn trigger_analysis_await(
    app: &AppHandle,
    reason: &str,
    since_ms: Option<i64>,
) -> Result<AnalysisRunSummary, String> {
    if ANALYSIS_IN_PROGRESS
        .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
        .is_err()
    {
        return Err("analysis in progress".to_string());
    }
    // guard 在 block 退出时重置 flag (无论成功/失败/panic)
    let _guard = AnalysisGuard;
    let orch = get_orchestrator(app)?;
    let result = orch.run_session_analysis(app, since_ms).await;
    // 统一 emit (成功/失败都通知前端, 与 try_trigger_analysis 行为一致)
    match &result {
        Ok(summary) => {
            let _ = app.emit(
                "evolution://analysis-done",
                serde_json::json!({
                    "sessionsScanned": summary.sessions_scanned,
                    "signalsEmitted": summary.signals_emitted,
                    "draftsCreated": summary.drafts_created,
                    "degraded": summary.degraded,
                    "reason": reason,
                    "ok": true,
                }),
            );
        }
        Err(e) => {
            let _ = app.emit(
                "evolution://analysis-done",
                serde_json::json!({
                    "ok": false,
                    "reason": reason,
                    "error": e,
                }),
            );
        }
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn peek_skill_kind_detects_automation_via_front_matter() {
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("auto.md");
        std::fs::write(
            &path,
            "---\nname: open-app\ndescription: opens an app\npreferred_execution_type: automation\n---\n# body\n",
        )
        .unwrap();
        assert_eq!(peek_skill_kind(path.to_str().unwrap()), SkillKind::Automation);
    }

    #[test]
    fn peek_skill_kind_defaults_to_mcp_when_field_missing() {
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("mcp.md");
        std::fs::write(
            &path,
            "---\nname: search-web\ndescription: searches the web\n---\n# body\n",
        )
        .unwrap();
        assert_eq!(peek_skill_kind(path.to_str().unwrap()), SkillKind::Mcp);
    }

    #[test]
    fn peek_skill_kind_defaults_to_mcp_when_file_missing() {
        assert_eq!(peek_skill_kind("/nonexistent/skill.md"), SkillKind::Mcp);
    }

    #[test]
    fn peek_skill_kind_ignores_field_outside_front_matter() {
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("body.md");
        // preferred_execution_type 在正文中 (非 front matter), 不应被识别为 automation。
        std::fs::write(
            &path,
            "---\nname: mcp-skill\n---\npreferred_execution_type: automation\n",
        )
        .unwrap();
        assert_eq!(peek_skill_kind(path.to_str().unwrap()), SkillKind::Mcp);
    }

    #[test]
    fn peek_skill_description_extracts_from_front_matter() {
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("skill.md");
        std::fs::write(
            &path,
            "---\nname: x\ndescription: \"A skill that does Y\"\npreferred_execution_type: automation\n---\n",
        )
        .unwrap();
        let desc = peek_skill_description(path.to_str().unwrap());
        assert_eq!(desc, "A skill that does Y");
    }
}
