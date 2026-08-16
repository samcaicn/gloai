// Copyright (c) 2026 AIMarketing
//
// Hermes 自进化 IPC 命令层 (Phase 1)。
//
// 4 个命令暴露 EvolutionOrchestrator 给前端 AutoskillScene 的"会话洞察"tab:
//   evolution_trigger_session_analysis  — 手动/会话结束触发会话分析
//   evolution_list_signals              — 列出未消费信号 (含 evidence)
//   evolution_list_session_insights     — 便捷: 仅 session_insight 类型 (前端 tab 用)
//   evolution_mark_signal_consumed      — 标记信号已采纳/拒绝 (consumed=1/2)
//
// orchestrator 通过 app.try_state::<Arc<EvolutionOrchestrator>>() 获取,
// 未注册 (hermes_llm_service / autoskill engine 未就绪) 时返回友好错误。

use tauri::AppHandle;

use crate::hermes::evolution_orchestrator::{get_orchestrator, trigger_analysis_await, AnalysisRunSummary};
use crate::hermes::persistence::StoredSignal;

/// 触发一次会话内容分析 + 自动把 SessionInsight 信号转成待确认 draft。
///
/// `since_ms = None` → 默认覆盖最近 24h。返回本次 run 摘要。
///
/// 走统一并发护栏 `trigger_analysis_await` (AtomicBool + RAII guard),
/// 避免与 periodic / session_save 触发并发产生重复信号/草稿。
/// 已有分析在跑时返回 `Err("analysis in progress")`。
#[tauri::command]
pub async fn evolution_trigger_session_analysis(
    since_ms: Option<i64>,
    app: AppHandle,
) -> Result<AnalysisRunSummary, String> {
    trigger_analysis_await(&app, "manual", since_ms).await
}

/// 列出未消费 (consumed=0) 的进化信号, 按 created_at 倒序。
/// 前端"会话洞察"tab 展示用, evidence_json 字段含完整 EvolutionSignal 序列化。
#[tauri::command]
pub fn evolution_list_signals(
    limit: Option<u32>,
    app: AppHandle,
) -> Result<Vec<StoredSignal>, String> {
    let orch = get_orchestrator(&app)?;
    orch.list_pending_signals(limit.unwrap_or(50))
}

/// 便捷: 仅返回未消费的 session_insight 类型信号 (过滤 telemetry/merge/memory)。
/// `scene` 参数 Phase 1 暂未使用 (固定 default), 保留以兼容多 workspace 后续。
#[tauri::command]
pub fn evolution_list_session_insights(
    scene: Option<String>,
    app: AppHandle,
) -> Result<Vec<StoredSignal>, String> {
    let orch = get_orchestrator(&app)?;
    orch.list_session_insights(&scene.unwrap_or_else(|| "default".to_string()))
}

/// 标记信号消费状态。
///   consumed=0 → 未处理 (还原)
///   consumed=1 → 已转 draft (采纳)
///   consumed=2 → 用户拒绝/降级 (拒绝留痕)
///   consumed=3 → PassThrough (交给 AutoSkillEngine)
#[tauri::command]
pub async fn evolution_mark_signal_consumed(
    signal_id: String,
    consumed: i32,
    app: AppHandle,
) -> Result<(), String> {
    // 校验 consumed 取值, 防止前端/外部传入非法值污染 hermes_evolution_signals.consumed 列。
    if !matches!(consumed, 0..=3) {
        return Err(format!(
            "invalid consumed value: {} (allowed 0-3)",
            consumed
        ));
    }
    let orch = get_orchestrator(&app)?;
    orch.mark_signal_consumed(&signal_id, consumed)
}
