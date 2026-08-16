// Copyright (c) 2026 MeeJoy
//
// Tauri command surface for teaching + self-healing.
//
// We hold two global state items: a `Recorder` and a `HealingEngine`,
// both installed by `lib.rs` via `app.manage(...)`.  The Tauri commands
// here are thin wrappers around them.
//
// At the end of every teaching cycle (`stop_recording`) and every
// successful healing cycle (`attempt_heal`) we publish a
// `SkillProposal` to the `skill_proposals` table and emit a
// `proposal-created` Tauri event so the server evaluator and the
// front-end inbox UI can pick it up.

use std::sync::Arc;
use std::hash::Hasher;
use std::time::Instant;

use base64::Engine;
use serde::Serialize;
use serde_json::Value;
use tauri::{AppHandle, Emitter, Manager, State};

use crate::automation::{
    flowchart::{events_to_flowchart, Flowchart},
    FailureContext, HealRecord, HealResult, HealingEngine, Recorder, RecordingStatus,
};
use crate::skill::proposal::{ProposalSource, SkillLineage, SkillProposal, ProposalTelemetry};
use crate::skill::proposal_store;

// -- shared state -----------------------------------------------------------------

/// Wrapper for the global state managed by Tauri.
pub struct TeachingState {
    pub recorder: Arc<Recorder>,
    pub healing: Arc<HealingEngine>,
}

impl TeachingState {
    pub fn new() -> Self {
        Self {
            recorder: Arc::new(Recorder::new()),
            healing: Arc::new(HealingEngine::new()),
        }
    }
}

impl Default for TeachingState {
    fn default() -> Self {
        Self::new()
    }
}

// -- proposal publishing helper -------------------------------------------------

/// Persist a `SkillProposal` and emit the `proposal-created` Tauri
/// event.  Best-effort: a failure to write the DB is logged but
/// does not propagate up — the user's teaching/healing cycle is
/// considered successful even when the proposal persistence path
/// hiccups (the front-end still gets the original return value
/// from the Tauri command).
pub fn publish_proposal(app: &AppHandle, proposal: &SkillProposal) -> Result<(), String> {
    let conn = proposal_store::open_proposals_db(app)?;
    proposal_store::save(&conn, proposal)?;
    if let Err(err) = app.emit("proposal-created", proposal) {
        log::warn!(
            "[teaching] proposal-created event emit failed for {}: {}",
            proposal.proposal_id,
            err
        );
    }
    Ok(())
}

// -- commands --------------------------------------------------------------------

/// Start a new recording session.  Returns an error if one is already
/// running.
#[tauri::command]
pub fn start_recording(state: State<'_, TeachingState>) -> Result<(), String> {
    state.recorder.start()
}

/// Stop the active session, generate a `skill.md`
/// from the captured events, and *immediately* compile it into an
/// MCP binary blob.  The front-end receives both pieces so it can:
///   * show / save the YAML source (`skill_md`)
///   * hand the MCP straight to `execute_skill` for a "Run now"
///     path (`mcp_blob_base64`)
///   * surface a step counter to the UI (`step_count`)
///
/// Also publishes two `SkillProposal` rows (one
/// with `source=Recorder`, one with `source=Teaching`) carrying
/// the same `skill_md`.  The teaching proposal is what the
/// inbox UI defaults to; the recorder proposal is kept
/// for the evaluator and for any future automated
/// monitor that bypasses the teaching UI.
// 必须带 #[serde(rename_all = "camelCase")]：本结构体同时通过 Tauri 命令
// 返回 + emit("recording:stopped", ...) 事件载荷两条路径到达前端。
// 事件 emit 走的是 serde_json 直接序列化，**不会**像 #[tauri::command]
// 入参那样做 snake_case → camelCase 转换；返回路径虽然 Tauri 会用 serde
// 反射处理，但要让两条路径产生的 JSON 形状一致（前端只用一套 camelCase
// 类型 TeachingStopResult 消费），结构体自身必须先转成 camelCase。
// 修复前：事件载荷实际是 { skill_md, mcp_blob_base64, step_count, flowchart }，
// 但前端读 result.skillMd / mcpBlobBase64 / stepCount 全部为 undefined，
// 导致通知永远显示"未捕获到任何步骤"，Run now 按钮因拿不到 blob 失效。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TeachingStopResult {
    pub skill_md: String,
    pub mcp_blob_base64: String,
    pub step_count: u32,
    /// 由 RecordedEvent 实时转出的可视化流程图。
    /// 前端 FlowchartView 直接消费：节点 (start / click / type / hotkey / end) +
    /// 线性连接。空录制 → 最小框架 (start → end)。
    pub flowchart: Flowchart,
}

#[tauri::command]
pub fn stop_recording(
    app: AppHandle,
    app_name: Option<String>,
    state: State<'_, TeachingState>,
) -> Result<TeachingStopResult, String> {
    // 1. finalize：stop（内部会等异步 UIA lookup 完成 + drain events）+
    //    dedup + 生成 skill.md + SkillProposal，同时返回去重后的 events。
    //    用 finalize_with_events 而非 finalize_into_proposal + snapshot_events：
    //    之前 snapshot 时机早于 stop()，element 可能还没回填完，导致 flowchart
    //    中的 MouseClick 节点没有元素信息（fallback 到坐标显示），与 step_count
    //    （基于已回填的 events dedup 后计数）不一致。现在用同一份 events 生成
    //    flowchart，保证 step_count 与 flowchart 节点数严格一致。
    let (recorder_proposal, skill_md, step_count, events) =
        state.recorder.finalize_with_events()?;
    let flowchart = events_to_flowchart(&events);

    // 1.5 把录制结果（Flowchart）落库到 recording::store，使现有
    //     get_recorded_flowchart_cmd / recordingLoad 路径能直接读得到
    //     （悬浮窗录制原本只写 proposal_store，与批次存储不相连）。
    if let Some(app_name_str) = &app_name {
        let fc_val = serde_json::to_value(&flowchart)
            .map_err(|e| format!("serialize flowchart failed: {}", e))?;
        if let Err(e) = crate::recording::store::save_app_flowchart(app_name_str, &fc_val) {
            log::warn!("[teaching] failed to persist flowchart for {}: {}", app_name_str, e);
        }

        // 1.6 同步写入 DuckDB teach_record_log 表，作为 AutoSkill 技能生成
        //     的示教数据源。best-effort：失败仅 warn 不阻断录制完成流程。
        let events_json = serde_json::to_value(&events)
            .unwrap_or_else(|_| serde_json::json!([]));
        if let Some(pool_arc) = app.try_state::<std::sync::Arc<crate::storage::DuckDBPool>>() {
            let dedup_input = format!("{}-{}", app_name_str, step_count);
            let mut hasher = std::collections::hash_map::DefaultHasher::new();
            std::hash::Hash::hash(&dedup_input, &mut hasher);
            let dedup_hash = format!("{:016x}", hasher.finish());
            let record = crate::storage::teach_record::TeachInsert {
                scene: app_name_str.clone(),
                app_name: app_name_str.clone(),
                protocol: "teaching".to_string(),
                steps: events_json,
                step_count: Some(step_count as i32),
                dedup_hash: Some(dedup_hash),
            };
            if let Err(e) = crate::storage::teach_record::insert_teach(&pool_arc, &record) {
                log::warn!(
                    "[teaching] failed to insert teach_record_log for {}: {}",
                    app_name_str,
                    e
                );
            }
        } else {
            log::warn!(
                "[teaching] DuckDBPool not available, skipping teach_record_log for {}",
                app_name_str
            );
        }
    }

    // Persist the Recorder-sourced proposal.
    if let Err(err) = publish_proposal(&app, &recorder_proposal) {
        log::warn!(
            "[teaching] failed to publish recorder proposal {}: {}",
            recorder_proposal.proposal_id,
            err
        );
    }

    // Build and persist the Teaching-sourced proposal.  Same body
    // and telemetry, but a distinct `proposal_id` and `source`.
    let teaching_proposal = SkillProposal::new(
        ProposalSource::Teaching,
        skill_md.clone(),
        SkillLineage {
            parent_skill_id: recorder_proposal.lineage.parent_skill_id.clone(),
            parent_version: recorder_proposal.lineage.parent_version,
            derivation_note: Some(format!(
                "manual teaching ({} event(s) captured)",
                step_count
            )),
        },
        ProposalTelemetry {
            source_success_rate: 1.0,
            avg_latency_ms: 0,
            sample_size: step_count,
        },
    );
    if let Err(err) = publish_proposal(&app, &teaching_proposal) {
        log::warn!(
            "[teaching] failed to publish teaching proposal {}: {}",
            teaching_proposal.proposal_id,
            err
        );
    }

    // Compile the skill.md to MCP and return the on-the-wire shape
    // (unchanged from the original Tauri command).
    let compiled = crate::skill::compiler::compile_skill_md(&skill_md)
        .map_err(|e| format!("即时编译 MCP 失败: {}", e))?;
    let mcp_blob_base64 = base64::engine::general_purpose::STANDARD.encode(&compiled.mcp_binary);
    let result = TeachingStopResult {
        skill_md,
        mcp_blob_base64,
        step_count,
        flowchart,
    };

    // 让主窗口在录制刚结束时统一弹出"录制完成"通知（带 Run now / View flowchart
    // 按钮）。无论本次 stop 是从录制浮窗、BrowserScene 浮窗还是单步执行路径触发，
    // 主窗口订阅本事件后都能收到 TeachingStopResult 全量产物，避免此前所有调用方
    // 都把后端编译好的 mcp_blob / step_count 丢弃掉的浪费。
    if let Err(err) = app.emit(
        "recording:stopped",
        serde_json::json!({
            "appName": app_name,
            "result": &result,
        }),
    ) {
        log::warn!(
            "[teaching] recording:stopped event emit failed for {}: {}",
            result.step_count,
            err
        );
    }

    // 录制结束后检测小循环，如果有则通知前端弹窗让用户确认是否合并
    let loop_proposals = crate::automation::flowchart::detect_small_loops(&result.flowchart.nodes);
    if !loop_proposals.is_empty() {
        log::info!(
            "[teaching] detected {} small loop(s) in recording for {}",
            loop_proposals.len(),
            app_name.as_deref().unwrap_or("unknown")
        );
        let _ = app.emit(
            "recording:loop-detected",
            serde_json::json!({
                "appName": app_name,
                "proposals": &loop_proposals,
                "flowchartTitle": &result.flowchart.title,
            }),
        );
    }

    Ok(result)
}

/// 录制过程中 / 录制结束后，把当前 buffer 的 events 转成结构化流程图。
///
/// 与 `stop_recording` 内部会返回的 flowchart 等价（同样的 `events_to_flowchart`
/// 函数路径），但本命令不消耗 buffer、不 finalize、不生成 SkillProposal。
///
/// 主要用途：
///   * 前端"边录边看"：每来 N 个事件就拉一次流程图实时刷新 UI
///   * 录制意外中断：buffer 还在 → 仍能拿到当前流程图
#[tauri::command]
pub fn recording_to_flowchart(
    state: State<'_, TeachingState>,
) -> Result<Flowchart, String> {
    let events = state.recorder.snapshot_events()?;
    Ok(events_to_flowchart(&events))
}

/// 持久化一个编辑过的流程图（来自前端 FlowchartView 的 save）。
///
/// 两路落库：
///   1. 解析 JSON 写入 `recording::store` 的 flowchart.json（合并去重），
///      使 FlowchartScene 重新加载时读到的是编辑后的版本；
///   2. 同时写一条 source=Manual 的 SkillProposal（兼容既有 inbox UI）。
#[tauri::command]
pub fn save_flowchart(
    app: AppHandle,
    app_name: String,
    title: String,
    flowchart_json: String,
) -> Result<SkillProposal, String> {
    // 1. 编辑结果落到 recording::store，供加载路径读取（自动合并去重）。
    let parsed: Value = serde_json::from_str(&flowchart_json)
        .map_err(|e| format!("invalid flowchart json: {}", e))?;
    if let Err(e) = crate::recording::store::save_app_flowchart(&app_name, &parsed) {
        log::warn!("[teaching] save_flowchart persist failed for {}: {}", app_name, e);
    }

    let proposal = SkillProposal::new(
        ProposalSource::Manual,
        flowchart_json,
        SkillLineage {
            parent_skill_id: None,
            parent_version: None,
            derivation_note: Some(format!("manual flowchart edit: {}", title)),
        },
        ProposalTelemetry::default(),
    );
    publish_proposal(&app, &proposal)?;
    Ok(proposal)
}

/// Snapshot the recorder state machine.
#[tauri::command]
pub fn get_recording_status(
    state: State<'_, TeachingState>,
) -> Result<RecordingStatus, String> {
    state.recorder.status()
}

/// 暂停录制: rdev 监视线程继续运行, 但事件不再推入 buffer。
/// 用户可通过 resume_recording 恢复, 或通过 stop_recording 结束并保存。
#[tauri::command]
pub fn pause_recording(state: State<'_, TeachingState>) -> Result<(), String> {
    state.recorder.pause()
}

/// 恢复录制: 从暂停状态继续, rdev 监视线程重新开始推入事件。
#[tauri::command]
pub fn resume_recording(state: State<'_, TeachingState>) -> Result<(), String> {
    state.recorder.resume()
}

/// 丢弃录制（不生成 skill.md / 不触发 completion 事件）。
/// 用于悬浮窗「取消」按钮。
#[tauri::command]
pub fn discard_recording(state: State<'_, TeachingState>) -> Result<(), String> {
    state.recorder.discard()
}

/// Attempt to heal a failed skill execution.  The front-end supplies a
/// failure context; the engine decides whether light heuristics can
/// repair it or whether the deep re-parse path is required.
///
/// When the engine is in `deep` mode, every heal attempt
/// short-circuits to `attempt_deep_heal` (YOLO/UI-TARS re-parse).
/// In `off` / `light` mode we fall back to the classic heuristic path.
///
/// On a `Healed` or `DeepPending` outcome we publish
/// a `SkillProposal` (source=`Healing`) so the evaluator
/// sees the patch as a skill candidate.  `Failed` /
/// `NeedsReparse` outcomes do **not** produce a proposal (they are
/// not skill candidates — they are "we tried and gave up" signals).
#[tauri::command]
pub fn attempt_heal(
    app: AppHandle,
    skill_id: String,
    failure: Option<FailureContext>,
    state: State<'_, TeachingState>,
) -> Result<HealResult, String> {
    let ctx = failure.unwrap_or_default();
    let mode = state.healing.current_mode();
    let started_at = Instant::now();

    let result = match mode.as_str() {
        "deep" => state.healing.attempt_deep_heal(&skill_id, &ctx),
        _ => state.healing.attempt_heal(&skill_id, &ctx)?,
    };

    let should_publish = matches!(
        result,
        HealResult::Healed { .. } | HealResult::DeepPending { .. }
    );
    if should_publish {
        let elapsed_ms = started_at.elapsed().as_millis() as u32;
        let proposal = state.healing.emit_proposal(&skill_id, &ctx, &result, elapsed_ms);
        if let Err(err) = publish_proposal(&app, &proposal) {
            log::warn!(
                "[teaching] failed to publish heal proposal {}: {}",
                proposal.proposal_id,
                err
            );
        }
    }

    Ok(result)
}

/// Switch healing mode (`off` / `light` / `deep`).
#[tauri::command]
pub fn set_healing_mode(mode: String, state: State<'_, TeachingState>) -> Result<(), String> {
    state.healing.set_mode(&mode)
}

/// Return the last `limit` healing records (most recent first).
#[tauri::command]
pub fn get_healing_history(
    limit: u32,
    state: State<'_, TeachingState>,
) -> Result<Vec<HealRecord>, String> {
    state.healing.history(limit)
}

// -- v4 §2.1 — SkillSource front-end bridge ------------------------------------
//
// The following three Tauri commands back `src/api/skill-source.js`.
// They are intentionally side-channel commands (separate from
// `stop_recording` / `attempt_heal`) so the front-end can push and
// query proposals directly without round-tripping through the
// teaching flow.  They are NOT registered in `lib.rs`'s
// `invoke_handler!` macro by this PR (the registration is the
// main thread's reserved action) — the front-end API falls back
// to the `proposal-created` event cache when the commands are
// "not found" at runtime; the moment the main thread adds them
// to `invoke_handler!` the front-end starts using them.

/// Push an arbitrary `SkillProposal` (front-end hand-edit, community
/// import, etc.) and return the persisted copy.  Persists via
/// `proposal_store::save` and emits the `proposal-created` event.
#[allow(dead_code)]
// SkillSource front-end bridge; the `invoke_handler!`
// registration in `lib.rs` is the main thread's reserved action.
#[tauri::command]
pub fn push_proposal(app: AppHandle, proposal: SkillProposal) -> Result<SkillProposal, String> {
    publish_proposal(&app, &proposal)?;
    Ok(proposal)
}

/// List proposals, optionally filtered by `source` and limited to
/// the most recent `limit` rows.  Both parameters are optional;
/// the default cap is 100.
#[allow(dead_code)]
// SkillSource front-end bridge; the `invoke_handler!`
// registration in `lib.rs` is the main thread's reserved action.
#[tauri::command]
pub fn list_proposals(
    app: AppHandle,
    source: Option<String>,
    limit: Option<u32>,
) -> Result<Vec<SkillProposal>, String> {
    let conn = proposal_store::open_proposals_db(&app)?;
    let parsed_source = match source.as_deref() {
        Some("teaching") => Some(ProposalSource::Teaching),
        Some("healing") => Some(ProposalSource::Healing),
        Some("recorder") => Some(ProposalSource::Recorder),
        Some("monitoring") => Some(ProposalSource::Monitoring),
        Some("community") => Some(ProposalSource::Community),
        Some("manual") => Some(ProposalSource::Manual),
        Some(other) => {
            return Err(format!("unknown proposal source `{}`", other));
        }
        None => None,
    };
    proposal_store::list(&conn, parsed_source, limit)
}

/// Delete a proposal by id.  Returns `true` when a row was
/// removed, `false` when the id was not present.
#[allow(dead_code)]
// SkillSource front-end bridge; the `invoke_handler!`
// registration in `lib.rs` is the main thread's reserved action.
#[tauri::command]
pub fn delete_proposal(app: AppHandle, id: String) -> Result<bool, String> {
    let conn = proposal_store::open_proposals_db(&app)?;
    proposal_store::delete(&conn, &id)
}

#[cfg(test)]
mod tests {
    //! End-to-end smoke test for the "stop recording -> compile"
    //! pipeline.  We do not exercise the `State<'_, TeachingState>`
    //! plumbing (Tauri's runtime is not available in unit tests);
    //! instead we drive the same function chain the command uses
    //! and assert that the recorded events produce a valid MCP
    //! binary.

    use crate::automation::recorder::{generate_skill_md, RecordedEvent};
    use crate::skill::proposal::ProposalSource;
    use crate::skill::proposal_store;
    use crate::skill::{compile_skill_md, SkillManifest};
    use base64::Engine as _;
    use rusqlite::Connection;

    #[test]
    fn stop_recording_chain_produces_mcp_blob() {
        // mouse_click + key_press + mouse_move (dropped by the
        // recorder heuristics) + a second key_press — three
        // events, but the recorder collapses the two printable
        // keys into a single `type` step.
        let events = vec![
            RecordedEvent::MouseClick {
                x: 320,
                y: 240,
                button: "left".into(),
                element: None,
            },
            RecordedEvent::KeyPress {
                key: "\"h\"".into(),
            },
            RecordedEvent::KeyPress {
                key: "\"i\"".into(),
            },
            RecordedEvent::MouseMove { x: 400, y: 300 },
        ];

        let skill_md = generate_skill_md(&events);
        assert!(!skill_md.is_empty(), "skill.md must not be empty");
        assert!(skill_md.contains("name: new_skill"));
        assert!(skill_md.contains("steps:"));

        // Round-trip parse: the recorded YAML must be a valid
        // SkillManifest document.
        let manifest = SkillManifest::from_skill_md(&skill_md)
            .expect("recorded skill.md should parse as a SkillManifest");
        assert_eq!(manifest.name, "new_skill");
        assert!(!manifest.steps.is_empty(), "manifest must have steps");

        // Compile: produces a non-empty MCP binary that starts with
        // the MCP1 magic.
        let compiled = compile_skill_md(&skill_md)
            .expect("recorded skill.md should compile to an MCP");
        assert!(!compiled.mcp_binary.is_empty());
        assert_eq!(&compiled.mcp_binary[..4], b"MCP1");

        // Mirror the on-the-wire result the command builds.
        let mcp_blob_base64 =
            base64::engine::general_purpose::STANDARD.encode(&compiled.mcp_binary);
        assert!(!mcp_blob_base64.is_empty());
        // Base64 of an N-byte payload is ceil(N / 3) * 4 chars and
        // therefore always a multiple of 4.
        assert_eq!(mcp_blob_base64.len() % 4, 0);
        let decoded = base64::engine::general_purpose::STANDARD
            .decode(mcp_blob_base64.as_bytes())
            .expect("base64 must round-trip");
        assert_eq!(decoded, compiled.mcp_binary);

        // The task's `step_count` mirrors the number of *captured*
        // events (not the number of recorded skill.md steps — the
        // recorder drops `MouseMove` and collapses adjacent
        // printable keys).
        assert_eq!(events.len(), 4);
    }

    #[test]
    fn recorder_finalize_into_proposal_round_trip() {
        use crate::automation::recorder::Recorder;

        let recorder = Recorder::new();
        recorder.start().expect("start");
        recorder
            .push(RecordedEvent::MouseClick {
                x: 10,
                y: 20,
                button: "left".into(),
                element: None,
            })
            .expect("push click");
        let (proposal, skill_md, step_count) =
            recorder.finalize_into_proposal().expect("finalize");

        assert_eq!(proposal.source, ProposalSource::Recorder);
        assert_eq!(step_count, 1);
        assert_eq!(proposal.skill_md, skill_md);
        assert_eq!(proposal.telemetry.sample_size, 1);
        assert!(proposal
            .lineage
            .derivation_note
            .as_deref()
            .unwrap_or("")
            .contains("1 event"));

        // The proposal must round-trip through the store.  Use an
        // in-memory DB so the test is hermetic.
        let conn = Connection::open_in_memory().expect("in-memory");
        conn.execute_batch(
            "CREATE TABLE skill_proposals (
                proposal_id TEXT PRIMARY KEY,
                source TEXT NOT NULL,
                skill_md TEXT NOT NULL,
                parent_skill_id TEXT, parent_version INTEGER, derivation_note TEXT,
                source_success_rate REAL NOT NULL DEFAULT 0.0,
                avg_latency_ms INTEGER NOT NULL DEFAULT 0,
                sample_size INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL
             )",
        )
        .expect("schema");
        proposal_store::save(&conn, &proposal).expect("save");
        let loaded = proposal_store::get(&conn, &proposal.proposal_id)
            .expect("get")
            .expect("present");
        assert_eq!(loaded.proposal_id, proposal.proposal_id);
        assert_eq!(loaded.source, ProposalSource::Recorder);
    }
}
