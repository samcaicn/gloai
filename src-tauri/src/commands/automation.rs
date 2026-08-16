// Copyright (c) 2026 AIMarketing
//
// Tauri commands — Smart retry + user takeover.
//
// The four commands here complement `commands::skill`:
//
//   1. `pause_execution`        — `cancel` semantic on the Rust
//      side (alias kept for clarity — the real "pause" happens
//      implicitly when the engine gives up after 3 attempts and
//      transitions to `PausedForUser`).
//   2. `resume_execution`       — wake the resume `Notify` so the
//      engine restarts the failed step.
//   3. `get_execution_status`   — current `ExecutionStatus` for a
//      `request_id`.
//   4. `get_execution_history`  — last N `ExecutionRecord`s.
//
// `pause_execution` is intentionally a *no-op* in this iteration:
// the engine pauses itself after exhausting all retry attempts.
// The command exists so the front-end can call a stable API and
// is a no-op when the request is already in a non-paused state.

use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Manager};

use crate::automation::state::{AutomationState, ExecutionRecord, ExecutionStatus};
use crate::automation::{EvolutionEvent, EvolutionLoop};
use crate::hermes::evolution_stats;

// =============================================================
// v5.7 — Evolution panel commands.
//
// The front-end's "自进化" page (Evolution.jsx) used to:
//   1. Increment counters in component state (lost on reload).
//   2. Persist the "自动进化" flag in localStorage (also lost).
//   3. Have a toggle that *did nothing*.
//
// The commands below back the page with real process-local
// state (see `crate::hermes::evolution_stats`). The counters
// surface through the same `/stats` HTTP route the Stats page
// already polls, so the "自进化" panel can refresh without a
// dedicated IPC round-trip.
//
// v5.8 adds per-skill stats, a circuit breaker, and a
// time-based dedup check. `report_skill_execution_result`
// grew two parameters (skill_id + skill_name) so the
// per-skill record can be created on first sight, and a new
// `should_skip_skill` command lets the front-end ask the
// canonical store whether a given skill is eligible *before*
// burning an LLM call on it.
// =============================================================

/// Report one skill execution result to the cumulative
/// counters + the per-skill record + worker_task_log (DuckDB).
/// The `skill_id` / `skill_name` are used to upsert the per-skill
/// row — `name` is the human-friendly label the panel shows.
/// 同时写入 worker_task_log 表，作为 AutoSkill 迭代优化的数据源。
/// DuckDB 未初始化时降级（只写内存计数器，不报错）。
#[tauri::command]
pub fn report_skill_execution_result(
    app: AppHandle,
    skill_id: String,
    skill_name: String,
    success: bool,
) {
    evolution_stats::record_run(&skill_id, &skill_name, success);

    // 写入 worker_task_log（AutoSkill 数据源）
    if let Some(pool) = app.try_state::<std::sync::Arc<crate::storage::DuckDBPool>>() {
        let task = crate::storage::worker_task_log::TaskLogInsert {
            scene: "work".to_string(),
            task_type: "lightweight".to_string(),
            skill_id: Some(skill_id.clone()),
            skill_version: None,
            status: if success {
                crate::storage::worker_task_log::STATUS_SUCCEEDED.to_string()
            } else {
                crate::storage::worker_task_log::STATUS_FAILED.to_string()
            },
            priority: None,
            params: Some(serde_json::json!({ "skill_name": skill_name })),
        };
        if let Err(e) =
            crate::storage::worker_task_log::insert_task(&pool, &task)
        {
            log::warn!(
                "[autoskill] worker_task_log insert 失败: skill={}, err={}",
                skill_id,
                e
            );
        }
    }
}

/// Front-end asks the backend "should I skip this skill?".
/// The backend is the only place that knows the last
/// `last_success_ms` and the circuit-breaker state, so the
/// decision has to live there.
///
/// `min_interval_ms` defaults to
/// `evolution_stats::DEFAULT_MIN_INTERVAL_MS` on the
/// front-end side (2 min); the front-end passes it
/// explicitly so a future "settings page" can let the user
/// tune it.
#[tauri::command]
pub fn should_skip_skill(
    skill_id: String,
    min_interval_ms: i64,
) -> evolution_stats::SkipReason {
    evolution_stats::should_skip(&skill_id, min_interval_ms)
}

/// Toggle the "自动进化" flag. The front-end schedules its own
/// `setInterval` based on this; the backend just owns the
/// canonical value so the next page mount reads the same
/// position the user toggled to (no flash from default `false`).
#[tauri::command]
pub fn set_auto_evolve(enabled: bool) {
    evolution_stats::set_auto_evolve(enabled);
}

/// Read the "自动进化" flag at panel mount time. Keeps the
/// checkbox in sync with whatever value the user last toggled
/// to (including values set by a previous webview session —
/// the flag survives a webview reload because it lives in the
/// Rust process, not in localStorage).
#[tauri::command]
pub fn get_auto_evolve() -> bool {
    evolution_stats::is_auto_evolve()
}

/// Full state snapshot. Equivalent to reading `/stats` but
/// goes over IPC for callers that already have a Tauri channel
/// open. Returns the camelCase struct directly so the
/// front-end doesn't have to remap field names.
#[tauri::command]
pub fn get_evolution_state() -> evolution_stats::EvolutionState {
    evolution_stats::snapshot()
}

/// Wipe all counters + per-skill records. The auto-evolve
/// flag is preserved (clearing stats is not the same intent as
/// stopping the scheduler). Bound to the "重置统计" button.
#[tauri::command]
pub fn clear_evolution_stats() {
    evolution_stats::clear_stats();
}

#[tauri::command]
pub fn pause_execution(_app: AppHandle, request_id: String) -> Result<(), String> {
    // 真正的 no-op:引擎在 MAX_ATTEMPTS 后自暂停,前端调用此命令仅为保持
    // API 对称性(参考模块顶部文档)。旧实现调用 try_state<AutomationState>
    // 并在状态未初始化时返回 Err ——与"no-op"语义矛盾:前端在尚未启动任何
    // 自动化任务的早期(如应用刚启动、EvolutionPanel 挂载)点暂停按钮会拿到
    // 红色错误。改为完全不读 AutomationState,任何状态下都成功返回。
    // 注:request_id 保留原名(不加 _ 前缀),Tauri 据此映射 JS 端 requestId 参数。
    let _ = request_id;
    Ok(())
}

#[tauri::command]
pub fn resume_execution(app: AppHandle, request_id: String) -> Result<(), String> {
    let state = app
        .try_state::<std::sync::Arc<AutomationState>>()
        .ok_or_else(|| "AutomationState is not initialized".to_string())?;
    let woke = state.notify_resume(&request_id);
    if !woke {
        // No task is currently waiting on the resume notify. We
        // treat this as a soft warning: the user clicked "继续"
        // before any retry loop paused. Reset the cancel flag so a
        // subsequent `execute_skill` starts clean.
        state.clear_cancel(&request_id);
    }
    Ok(())
}

#[tauri::command]
pub fn get_execution_status(
    app: AppHandle,
    request_id: String,
) -> Result<ExecutionStatus, String> {
    let state = app
        .try_state::<std::sync::Arc<AutomationState>>()
        .ok_or_else(|| "AutomationState is not initialized".to_string())?;
    // If the request is unknown we return `Idle` rather than 404-
    // ing the call so the polling loop in the floating panel
    // doesn't show a red error on every tick after the run ends.
    Ok(state.get_status(&request_id).unwrap_or(ExecutionStatus::Idle))
}

#[tauri::command]
pub fn get_execution_history(
    app: AppHandle,
    limit: u32,
) -> Result<Vec<ExecutionRecord>, String> {
    let state = app
        .try_state::<std::sync::Arc<AutomationState>>()
        .ok_or_else(|| "AutomationState is not initialized".to_string())?;
    Ok(state.snapshot_history(limit))
}

// === Single-step debugging commands ============================================
//
// These commands let the front-end drive the automation engine like a
// debugger: set/clear breakpoints by step index, enable step mode so
// every step pauses, and step over one paused step.

#[tauri::command]
pub fn set_execution_breakpoint(
    app: AppHandle,
    request_id: String,
    step_index: usize,
) -> Result<bool, String> {
    let state = app
        .try_state::<std::sync::Arc<AutomationState>>()
        .ok_or_else(|| "AutomationState is not initialized".to_string())?;
    Ok(state.set_breakpoint(&request_id, step_index))
}

#[tauri::command]
pub fn clear_execution_breakpoint(
    app: AppHandle,
    request_id: String,
    step_index: usize,
) -> Result<bool, String> {
    let state = app
        .try_state::<std::sync::Arc<AutomationState>>()
        .ok_or_else(|| "AutomationState is not initialized".to_string())?;
    Ok(state.clear_breakpoint(&request_id, step_index))
}

#[tauri::command]
pub fn clear_execution_breakpoints(app: AppHandle, request_id: String) -> Result<(), String> {
    let state = app
        .try_state::<std::sync::Arc<AutomationState>>()
        .ok_or_else(|| "AutomationState is not initialized".to_string())?;
    state.clear_all_breakpoints(&request_id);
    Ok(())
}

#[tauri::command]
pub fn enable_step_mode(app: AppHandle, request_id: String) -> Result<(), String> {
    let state = app
        .try_state::<std::sync::Arc<AutomationState>>()
        .ok_or_else(|| "AutomationState is not initialized".to_string())?;
    state.enable_step_mode(&request_id);
    Ok(())
}

#[tauri::command]
pub fn disable_step_mode(app: AppHandle, request_id: String) -> Result<(), String> {
    let state = app
        .try_state::<std::sync::Arc<AutomationState>>()
        .ok_or_else(|| "AutomationState is not initialized".to_string())?;
    state.disable_step_mode(&request_id);
    Ok(())
}

#[tauri::command]
pub fn step_over(app: AppHandle, request_id: String) -> Result<bool, String> {
    let state = app
        .try_state::<std::sync::Arc<AutomationState>>()
        .ok_or_else(|| "AutomationState is not initialized".to_string())?;
    Ok(state.notify_step(&request_id))
}

// === A3 surface (P1 §3) — system_software + browser automation ===
//
// The system-software commands are thin pass-throughs to
// `crate::automation::system_software`. The browser-session commands
// route through the chromiumoxide-backed `automation::browser` +
// `automation::browser_steps` modules: `start_session` launches a real
// CDP-attached Chromium, `run_action` dispatches click/type/screenshot
// primitives, and `close_session` tears the session down. The shared
// `SessionMap` is registered as Tauri global state in `lib.rs`.

use tauri::State;
use uuid::Uuid;

use crate::automation::browser::{
    self, detect_installed_browsers, BrowserInfo, SessionMap,
};
use crate::automation::browser_steps::{self, BrowserAction};
use crate::automation::system_software::{
    launch_software, list_all_installed_software, list_installed_software, LocalSoftwareEntry,
    SoftwareInfo,
};

#[tauri::command]
pub fn detect_installed_software() -> Vec<SoftwareInfo> {
    list_installed_software()
}

/// 全量扫描本地已安装软件（含 exe 路径 + 安装位置）
/// 用于前端建立本地软件索引 + UIA/CDP 能力判定
#[tauri::command]
pub fn scan_installed_software() -> Vec<LocalSoftwareEntry> {
    list_all_installed_software()
}

#[tauri::command]
pub fn launch_software_cmd(software_name: String) -> Result<(), String> {
    launch_software(&software_name)
}

#[tauri::command]
pub fn detect_installed_browsers_cmd() -> Vec<BrowserInfo> {
    detect_installed_browsers()
}

#[tauri::command]
pub async fn start_browser_session_cmd(
    map: State<'_, SessionMap>,
    browser_type: String,
) -> Result<String, String> {
    // v1.9.6 重打：空字符串 → 自动探测最佳浏览器（chrome>msedge>brave>firefox）。
    // 旧版空字符串直接报错，技能只能硬编码 brave→chrome，Win11-only-Edge 机器失败。
    let bt = if browser_type.trim().is_empty() {
        browser::detect_best_browser()
            .ok_or_else(|| "未检测到任何已安装浏览器 (Chrome/Edge/Brave/Firefox)".to_string())?
            .to_string()
    } else {
        browser_type
    };
    let session = browser::start_session(&bt, None).await?;
    let id = Uuid::new_v4().to_string();
    map.lock().await.insert(id.clone(), session);
    Ok(id)
}

#[tauri::command]
pub async fn execute_browser_action_cmd(
    map: State<'_, SessionMap>,
    session_id: String,
    action: serde_json::Value,
) -> Result<String, String> {
    let action: BrowserAction = serde_json::from_value(action)
        .map_err(|e| format!("无法解析浏览器动作: {}", e))?;

    // 锁内只 clone 出 Page 句柄(含必要的懒启动初始化),立即释放锁;
    // 锁外执行 action(可能耗时数秒到数十秒)。chromiumoxide Page 是
    // Arc<InnerPage> 的包装,实现 Clone,clone 出来的句柄与原句柄共享
    // 同一 CDP 会话,可独立 await 而不阻塞其他锁请求。
    // 旧实现把 `run_action().await` 整段放在 `map.lock()` 临界区内,导致
    // 所有并发 CDP 调用串行化(screenshot/wait_for 等长动作会卡住其他
    // session 的 list_browser_targets_cmd / close_browser_session_cmd)。
    let page = {
        let mut guard = map.lock().await;
        let session = guard
            .get_mut(&session_id)
            .ok_or_else(|| format!("浏览器会话不存在: {}", session_id))?;
        // 懒启动:首次动作前若无活动页面,开一个 about:blank。
        if session.current_page.is_none() {
            let page = session
                .browser
                .new_page("about:blank")
                .await
                .map_err(|e| format!("打开页面失败: {}", e))?;
            session.current_page = Some(page);
        }
        session
            .current_page
            .clone()
            .ok_or_else(|| "浏览器没有活动页面".to_string())?
    };

    // 锁外执行 action,避免串行化所有 CDP 调用。
    let result = browser_steps::run_action(&page, &action).await?;

    // 二次取锁更新 last_action(动作期间 session 可能被 close_session 移除,
    // 此时静默跳过更新,不影响 action 结果返回)。
    {
        let mut guard = map.lock().await;
        if let Some(session) = guard.get_mut(&session_id) {
            session.last_action = Some(result.action.clone());
        }
    }

    serde_json::to_string(&result).map_err(|e| format!("序列化结果失败: {}", e))
}

#[tauri::command]
pub async fn close_browser_session_cmd(
    map: State<'_, SessionMap>,
    session_id: String,
) -> Result<(), String> {
    browser::close_session(&map, &session_id).await
}

/// 浏览器目标页（CDP Target）的传输对象。
///
/// 字段名 `id`（而非 CDP 原生的 `targetId`）是为了匹配技能 JS 侧的读取：
/// `auto-product-comm.js` 的 `checkNewTab` / `getStatus` 都读 `t.id` /
/// `t.url` / `t.title`，若叫 `targetId` 会导致 `t.id === undefined`，
/// `checkNewTab` 永远检测不到新标签页。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TargetInfoDto {
    /// CDP targetId（CDP 原生字段名 targetId，这里重命名为 id 匹配 JS）
    pub id: String,
    pub url: String,
    /// 目标类型（"page" / "service_worker" / "browser" 等），rename_all=camelCase
    /// 下 `r#type` 序列化为 "type"
    pub r#type: String,
    pub title: String,
    pub attached: bool,
}

/// 枚举当前浏览器会话的所有页面目标（CDP targets，type=="page"）。
///
/// 替代旧的 `BrowserAction::GetTargets` 空壳 stub——后者只返回
/// `ActionResult{success:true}` 不带任何 targets 数据，导致技能 `ensureCdp`
/// 永远拿不到目标、最终返回 `status:failed`。
///
/// 直接调 `Browser::fetch_targets`（chromiumoxide 0.7）拿 CDP `TargetInfo` 列表，
/// 过滤 `type=="page"`（跳过 service_worker / browser 等非页面目标），
/// 映射为 `TargetInfoDto` 返回给前端。Tauri serde 层直接序列化为 JS 数组，
/// 前端无需 `JSON.parse` round-trip。
#[tauri::command]
pub async fn list_browser_targets_cmd(
    map: State<'_, SessionMap>,
    session_id: String,
) -> Result<Vec<TargetInfoDto>, String> {
    let mut guard = map.lock().await;
    let session = guard
        .get_mut(&session_id)
        .ok_or_else(|| format!("浏览器会话不存在: {}", session_id))?;
    let targets = session
        .browser
        .fetch_targets()
        .await
        .map_err(|e| format!("获取目标失败: {}", e))?;
    Ok(targets
        .into_iter()
        .filter(|t| t.r#type == "page")
        .map(|t| TargetInfoDto {
            id: t.target_id.inner().clone(),
            url: t.url,
            r#type: t.r#type,
            title: t.title,
            attached: t.attached,
        })
        .collect())
}

/// v1.9.6 重打新增：确保浏览器会话存在——若 session_id 有效则复用，
/// 否则按 browser_type 启动；browser_type 为空时自动探测最佳浏览器。
///
/// 让技能可以显式调用 "确保有浏览器" 而非依赖懒触发 _ensureCdpSession，
/// 失败时把真实错误（哪个浏览器都启动不了 / 路径未找到）冒泡给调用方。
#[tauri::command]
pub async fn ensure_browser_session_cmd(
    map: State<'_, SessionMap>,
    session_id: Option<String>,
    browser_type: Option<String>,
) -> Result<String, String> {
    // 1. 复用现有 session（若仍存活）
    if let Some(sid) = session_id {
        let guard = map.lock().await;
        if guard.contains_key(&sid) {
            return Ok(sid);
        }
    }
    // 2. 按 browser_type 启动；None/空时自动探测最佳
    let bt = match browser_type.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        Some(t) => t.to_string(),
        None => browser::detect_best_browser()
            .ok_or_else(|| "未检测到任何已安装浏览器 (Chrome/Edge/Brave/Firefox)".to_string())?
            .to_string(),
    };
    let session = browser::start_session(&bt, None).await?;
    let id = Uuid::new_v4().to_string();
    map.lock().await.insert(id.clone(), session);
    Ok(id)
}

/// v1.9.6 重打新增：诊断命令——返回会话存活状态 + 目标数 + 浏览器类型。
/// 供前端调试面板 / 技能 ensureCdp 失败时拉取详细状态用。
#[tauri::command]
pub async fn get_browser_session_status_cmd(
    map: State<'_, SessionMap>,
    session_id: String,
) -> Result<serde_json::Value, String> {
    let mut guard = map.lock().await;
    let session = guard
        .get_mut(&session_id)
        .ok_or_else(|| format!("浏览器会话不存在: {}", session_id))?;
    let targets = session
        .browser
        .fetch_targets()
        .await
        .map_err(|e| format!("获取目标失败: {}", e))?;
    Ok(serde_json::json!({
        "alive": true,
        "targetCount": targets.iter().filter(|t| t.r#type == "page").count(),
        "browserType": session.browser_type,
    }))
}

// === EvolutionLoop surface ===
//
// Three commands are bound to the `EvolutionLoop` Tauri state:
//   1. `trigger_evolution_now` — manual "立即跑进化" from the UI.
//   2. `get_evolution_history` — recent evolution events for the
//      panel / inbox drawer.
//   3. `disable_automation`    — flips the daily-batch off switch.
//
// The three command *names* MUST be registered in
// `lib.rs::tauri::generate_handler![...]` by the main thread. The
// implementations stay here so the loop, the history ring, and
// the disable flag all live next to each other.

/// Fire a one-shot evolution pass over every known skill. The
/// daily 02:00 batch is suppressed while this is in flight so
/// we don't double-work. Returns the freshly-appended events
/// so the UI can surface a "已触发 N 条进化" toast.
#[allow(dead_code)]
#[tauri::command]
pub async fn trigger_evolution_now(
    loop_state: tauri::State<'_, EvolutionLoop>,
) -> Result<Vec<EvolutionEvent>, String> {
    loop_state.trigger_now().await
}

/// Read the last `limit` evolution events whose `ran_at` ≥
/// `since`. Both arguments are optional; the default is "all
/// history, up to `HISTORY_LIMIT` entries".
#[allow(dead_code)]
#[tauri::command]
pub async fn get_evolution_history(
    loop_state: tauri::State<'_, EvolutionLoop>,
    since: Option<String>,
    limit: Option<u32>,
) -> Result<Vec<EvolutionEvent>, String> {
    let since_ts = match since.as_deref() {
        None | Some("") => None,
        Some(text) => Some(
            chrono::DateTime::parse_from_rfc3339(text)
                .map_err(|e| format!("invalid `since` (expected RFC3339): {}", e))?
                .with_timezone(&chrono::Utc),
        ),
    };
    loop_state.get_history(since_ts, limit)
}

/// Flip the auto-automation switch. When `disabled = true` the
/// daily 02:00 batch is paused; manual triggers still run.
/// Bound to the "禁用自动化" toggle in `EvolutionPanel`.
#[allow(dead_code)]
#[tauri::command]
pub async fn disable_automation(
    loop_state: tauri::State<'_, EvolutionLoop>,
    disabled: bool,
) -> Result<(), String> {
    loop_state.set_automation_disabled(disabled);
    Ok(())
}

// === Embedded webview URL launcher ============================================
//
// 用 Tauri 内嵌 webview(WebView2 / WKWebView / WebKitGTK)打开外部 URL,
// 供网页类技能使用。CDP 自动化(chromiumoxide)由 `start_browser_session_cmd`
// 等命令驱动,与这里的"打开网页给用户看"是两条独立路径。
//
// 设计:
//   * WebviewWindowBuilder 创建独立窗口,label 固定 `web-skill-window`(单例复用);
//   * URL 严格校验 http/https scheme + 非空 host,防 CRLF 注入;
//   * 失败抛 Err 给前端,UI 自行决定如何提示。

use tauri::{WebviewUrl, WebviewWindowBuilder};

/// 内嵌 webview 窗口的固定 label(单例复用)。
const WEB_SKILL_WINDOW_LABEL: &str = "web-skill-window";

/// 用内嵌 webview 打开外部 URL。
///
/// 已存在同 label 窗口时,先 set_focus 复用,不重复创建(避免任务栏
/// 堆多个 icon)。窗口被用户关掉后再次调用会重建。
#[tauri::command]
pub async fn open_url_in_webview(
    app: AppHandle,
    url: String,
    title: Option<String>,
) -> Result<(), String> {
    if url.trim().is_empty() {
        return Err("URL 不能为空".to_string());
    }
    // 拒绝包含控制字符的 URL,防止 CRLF 注入等攻击
    if url.contains('\n') || url.contains('\r') || url.contains('\t') || url.contains('\0') {
        return Err("URL contains invalid control characters".to_string());
    }
    // 用 url::Url::parse 严格校验 scheme 与 host,避免畸形 URL 通过
    let parsed = url::Url::parse(&url).map_err(|e| format!("invalid URL: {}", e))?;
    // mailto: 协议白名单内(参考 commands::system::is_safe_external_url),
    // webview 无法渲染邮件客户端,交给系统默认邮件程序处理(opener plugin)。
    if parsed.scheme() == "mailto" {
        tauri_plugin_opener::open_url(&url, None::<&str>)
            .map_err(|e| format!("打开 mailto 失败: {}", e))?;
        return Ok(());
    }
    if parsed.scheme() != "http" && parsed.scheme() != "https" {
        return Err("仅允许 http/https/mailto URL".to_string());
    }
    if parsed.host_str().map(|h| h.is_empty()).unwrap_or(true) {
        return Err("URL missing host".to_string());
    }

    let window_title = title
        .as_ref()
        .map(|t| {
            let trimmed = t.trim();
            if trimmed.is_empty() {
                "网页技能".to_string()
            } else {
                trimmed.to_string()
            }
        })
        .unwrap_or_else(|| "网页技能".to_string());

    // 复用已存在的窗口:set_focus + set_title,然后通过 webview 导航到新 URL。
    // Tauri 的 WebviewWindow::navigate 会保留窗口壳,只换 URL 内容。
    //
    // navigate 失败时降级:destroy 旧窗口后重建。用 `reused` flag
    // 明确控制流,避免依赖 get_webview_window 的判断 —— destroy 在
    // 部分平台上是异步的,get_webview_window 可能仍返回已 destroy
    // 的句柄,导致 use-after-destroy(执行 existing.show() 时 panic)。
    let mut reused = false;
    if let Some(existing) = app.get_webview_window(WEB_SKILL_WINDOW_LABEL) {
        if let Err(e) = existing.set_title(&window_title) {
            log::warn!("[webview] set_title 失败: {}", e);
        }
        // webview 导航到新 URL(navigate 会重新加载)
        match existing.navigate(url.parse().map_err(|e| format!("parse url failed: {}", e))?) {
            Ok(()) => {
                // navigate 成功 → 拉到前台 + 标记复用完成
                let _ = existing.show();
                if let Err(e) = existing.set_focus() {
                    log::warn!("[webview] set_focus 失败: {}", e);
                }
                log::info!("[webview] 复用窗口导航到 {}", url);
                reused = true;
            }
            Err(e) => {
                // navigate 失败:destroy 旧窗口后落入下面的新建分支
                log::warn!("[webview] navigate 失败 ({}),降级重建窗口", e);
                let _ = existing.destroy();
                // destroy 在部分平台是异步执行,固定 sleep(120ms) 不可靠:
                // 太短 → get_webview_window 仍返回旧句柄,后续 WebviewWindowBuilder
                // 会因 label 冲突 panic;太长 → 用户感知卡顿。改循环探测窗口
                // 真正消失(每 50ms 探测一次,最多 ~2s),既稳又不浪费等待时间。
                let destroy_deadline = std::time::Instant::now()
                    + std::time::Duration::from_millis(2000);
                while std::time::Instant::now() < destroy_deadline {
                    if app.get_webview_window(WEB_SKILL_WINDOW_LABEL).is_none() {
                        break;
                    }
                    tokio::time::sleep(std::time::Duration::from_millis(50)).await;
                }
                if app.get_webview_window(WEB_SKILL_WINDOW_LABEL).is_some() {
                    log::warn!(
                        "[webview] destroy 后 2s 窗口仍未消失,后续 build 可能 label 冲突"
                    );
                }
            }
        }
    }

    if reused {
        return Ok(());
    }

    // 不存在(或已 destroy)则新建一个独立 webview 窗口,加载外部 URL
    let window = WebviewWindowBuilder::new(
        &app,
        WEB_SKILL_WINDOW_LABEL,
        WebviewUrl::External(url.parse().map_err(|e| format!("parse url failed: {}", e))?),
    )
    .title(window_title)
    .inner_size(1280.0, 820.0)
    .min_inner_size(800.0, 600.0)
    .resizable(true)
    .focused(true)
    .visible(true)
    .build()
    .map_err(|e| format!("创建 webview 窗口失败: {}", e))?;

    // 防御:窗口创建成功后立即 set_title(部分平台 External url 会覆盖标题)
    let _ = window.set_title(title.as_deref().unwrap_or("网页技能"));

    log::info!("[webview] 新建窗口打开 {}", url);
    Ok(())
}

/// 查询网页技能 webview 窗口是否已打开(用于前端 UI 状态展示)。
#[tauri::command]
pub fn is_webview_window_open(app: AppHandle) -> bool {
    app.get_webview_window(WEB_SKILL_WINDOW_LABEL).is_some()
}

/// 关闭网页技能 webview 窗口(应用退出或前端主动调用时使用)。
#[tauri::command]
pub fn close_webview_window(app: AppHandle) -> Result<(), String> {
    if let Some(win) = app.get_webview_window(WEB_SKILL_WINDOW_LABEL) {
        win.destroy().map_err(|e| format!("destroy webview 失败: {}", e))?;
    }
    Ok(())
}
