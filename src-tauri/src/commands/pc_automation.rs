// Copyright (c) 2026 AIMarketing
//
// Tauri commands — PCUI 路线（UIA + CDP + OCR 路由器 + 券商 API）
//
// 17 commands surface the three-strategy router + broker router
// to the front-end. The router picks the primary tier by
// domain (CDP for Web, UIA for Desktop) and cascades to OCR on
// miss; the broker router is the *only* sanctioned path to
// place an order (UI automation is forbidden from reaching the
// order API — see `broker::router::assert_broker_only_context`).
//
// 1.  router_health                 — overall router + per-tier
// 2.  check_uia                     — UIA tier health
// 3.  check_cdp                     — CDP tier health
// 4.  check_ocr                     — OCR tier health
// 5.  check_broker                  — broker API availability
// 6.  list_brokers                  — configured broker list
// 7.  configure_broker              — register / update a broker
// 8.  set_app_profile               — bind app_profile id
// 9.  list_app_profiles             — full registry
// 10. get_app_profile               — lookup by id
// 11. parse_selector                — `uia:` / `cdp:` / `ocr:` parser
// 12. parse_step                    — full PcStep parser
// 13. select_strategy               — pick tier for a step
// 14. execute_step                  — dispatch a step
// 15. no_broker_available           — guard for "UI 自动化 only"
// 16. broker_only                   — guard for "下单必须 broker API"
// 17. parse_screen                  — flat screen content (UIA+OCR)

use std::collections::HashSet;
use std::path::PathBuf;
use std::sync::{Arc, OnceLock};

use serde::{Deserialize, Serialize};
use tauri::Manager;

use crate::pc_automation::apps::{find_profile, ALL_PROFILES};
use crate::pc_automation::broker::types::BrokerHealth;
use crate::pc_automation::broker::BrokerRouter;
use crate::pc_automation::cdp::websockets::WebSocketCdpBackend;
use crate::pc_automation::ocr::backend::OcrHealth;
use crate::pc_automation::parse_error::ParseError;
use crate::pc_automation::router::PcRouter;
use crate::pc_automation::step::{PcStep, StepOutcome, StepStrategy};
use crate::pc_automation::terminator_bridge::TerminatorUiaBackend;
#[cfg(not(target_os = "windows"))]
use crate::pc_automation::terminator_bridge::TerminatorOcrBackend;
use uuid;

#[allow(dead_code)]
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct RouterHealthReport {
    pub overall: String,
    pub uia: bool,
    pub cdp: bool,
    pub ocr: OcrHealth,
    pub broker_configured: bool,
    pub broker_health: Vec<BrokerHealth>,
}

#[allow(dead_code)]
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct BrokerSummary {
    pub broker_id: String,
    pub connected: bool,
    pub latency_ms: u64,
    pub last_error: Option<String>,
}

#[allow(dead_code)]
impl From<BrokerHealth> for BrokerSummary {
    fn from(h: BrokerHealth) -> Self {
        BrokerSummary {
            broker_id: h.broker_id,
            connected: h.connected,
            latency_ms: h.latency_ms,
            last_error: h.last_error,
        }
    }
}

#[allow(dead_code)]
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct PcStepView {
    pub id: String,
    pub description: String,
    pub app_profile: Option<String>,
    pub strategy: String,
    pub primary_selector: String,
    pub fallback_selectors: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub recorded_coords: Option<(i32, i32)>,
}

#[allow(dead_code)]
impl From<&PcStep> for PcStepView {
    fn from(s: &PcStep) -> Self {
        PcStepView {
            id: s.id.clone(),
            description: s.description.clone(),
            app_profile: s.app_profile.clone(),
            strategy: match s.strategy {
                StepStrategy::Uia => "uia".to_string(),
                StepStrategy::Cdp => "cdp".to_string(),
                StepStrategy::Ocr => "ocr".to_string(),
                StepStrategy::Vlm => "vlm".to_string(),
            },
            primary_selector: s.primary_selector.clone(),
            fallback_selectors: s.fallback_selectors.clone(),
            recorded_coords: s.recorded_coords,
        }
    }
}

#[allow(dead_code)]
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct StepResult {
    pub step_id: String,
    pub ok: bool,
    pub outcome: Option<StepOutcome>,
    pub error: Option<String>,
}

#[allow(dead_code)] // internal helper, accessed by Hermes tool handlers
pub(crate) struct PcAutomationState {
    pub(crate) router: PcRouter,
    brokers: BrokerRouter,
    /// Flat screen-content composer (UIA + OCR). Wired on
    /// Windows to the real backend; stub on macOS / Linux. The
    /// 17th Tauri command (`parse_screen`) reads through it.
    screen_parser: Arc<dyn crate::pc_automation::screen_parser::backend::ScreenParserBackend>,
    /// Active CDP browser sessions (kept alive so they don't get dropped).
    /// Key = session ID, Value = BrowserSession from automation::browser.
    browser_sessions: tokio::sync::Mutex<std::collections::HashMap<String, crate::automation::browser::BrowserSession>>,
}

impl PcAutomationState {
    fn new() -> Self {
        // UIA — terminator_bridge provides a cross-platform
        // implementation (UIAutomation on Windows, AXUIElement on
        // macOS, AT-SPI on Linux). This replaces the old
        // Windows-only `WindowsUiaBackend` and the non-Windows
        // `StubUiaBackend` with a single real implementation.
        let uia: Arc<dyn crate::pc_automation::uia::backend::UiaBackend> =
            Arc::new(TerminatorUiaBackend);
        // CDP — real implementation over raw WebSocket. Replaces
        // the v5-skeleton `StubCdpBackend` so the CDP tier is
        // functional in the packaged exe, not just a code path
        // that returns "not wired".
        let cdp: Arc<dyn crate::pc_automation::cdp::backend::CdpBackend> =
            Arc::new(WebSocketCdpBackend::new());
        // OCR — real on Windows (Windows.Media.Ocr via WinRT,
        // returns per-line coordinates). On non-Windows, use
        // terminator's OCR (basic text-only, no coordinates).
        // The router cascades misses to VLM rescue so the
        // degraded non-Windows OCR is safe.
        let ocr: Arc<dyn crate::pc_automation::ocr::backend::OcrBackend> = {
            #[cfg(target_os = "windows")]
            {
                Arc::new(crate::pc_automation::ocr::windows::WindowsOcrBackend::new())
            }
            #[cfg(not(target_os = "windows"))]
            {
                Arc::new(TerminatorOcrBackend)
            }
        };
        // Screen-parser — composes UIA + OCR into a single
        // flat list. Shares the same backend instances so we
        // don't pay the COM / WinRT init cost twice.
        let screen_parser = crate::pc_automation::screen_parser::default_backend(uia.clone(), ocr.clone());
        Self {
            router: PcRouter::new(uia, cdp, ocr),
            brokers: BrokerRouter::new(),
            screen_parser,
            browser_sessions: tokio::sync::Mutex::new(std::collections::HashMap::new()),
        }
    }
}

#[allow(dead_code)] // internal helper, used by Hermes tool handlers
pub(crate) fn shared_state() -> Arc<PcAutomationState> {
    static STATE: OnceLock<Arc<PcAutomationState>> = OnceLock::new();
    STATE
        .get_or_init(|| Arc::new(PcAutomationState::new()))
        .clone()
}

// ---- 1. router_health ---------------------------------------------------

#[tauri::command]
pub fn router_health() -> Result<RouterHealthReport, String> {
    let state = shared_state();
    let ocr = state
        .router
        .ocr
        .health()
        .unwrap_or(OcrHealth {
            pp_ocr_v5_available: false,
            paddle_vl_1_6_available: false,
            vulkan_enabled: false,
        });
    let uia = state.router.uia.get_root().is_ok();
    let cdp = state
        .router
        .cdp
        .attach_or_launch(None)
        .map(|_| true)
        .unwrap_or(false);
    let broker_health: Vec<BrokerHealth> = state.brokers.health_all();
    let broker_configured = broker_health.iter().any(|h| h.connected);
    let ocr_ready = ocr.pp_ocr_v5_available || ocr.paddle_vl_1_6_available;
    let overall = match (uia || cdp, ocr_ready) {
        (true, _) => "healthy".to_string(),
        (false, true) => "partial".to_string(),
        (false, false) => "degraded".to_string(),
    };
    Ok(RouterHealthReport {
        overall,
        uia,
        cdp,
        ocr,
        broker_configured,
        broker_health,
    })
}

// ---- 2. check_uia --------------------------------------------------------

#[tauri::command]
pub fn check_uia() -> Result<bool, String> {
    let state = shared_state();
    // Mirror check_cdp: an unavailable / unsupported platform is
    // reported as `false`, not as a thrown error, so the UI can
    // gracefully degrade the UIA affordance on macOS / Linux.
    match state.router.uia.get_focused_window() {
        Ok(opt) => Ok(opt.is_some()),
        Err(_) => Ok(false),
    }
}

// ---- 3. check_cdp --------------------------------------------------------

#[tauri::command]
pub fn check_cdp() -> Result<bool, String> {
    let state = shared_state();
    match state.router.cdp.attach_or_launch(None) {
        Ok(_) => Ok(true),
        Err(_) => Ok(false),
    }
}

// ---- 3b. launch_cdp_browser ---------------------------------------------
// Auto-launch a Chromium browser with CDP enabled on port 9222-9230.
// Returns the browser type that was launched (or error).
#[tauri::command]
pub async fn launch_cdp_browser(browser_type: Option<String>) -> Result<String, String> {
    use crate::automation::browser::{detect_installed_browsers, start_session};
    use std::net::TcpListener;

    // 1) 优先用户指定；2) 回退到已安装的 Chrome/Edge/Brave
    let preferred = browser_type.as_deref().unwrap_or("").to_lowercase();
    let browsers = detect_installed_browsers();
    let target = if !preferred.is_empty() {
        browsers.iter().find(|b| b.browser_type.to_lowercase() == preferred)
    } else {
        // 优先级：Chrome > Edge > Brave > Chromium
        let order = ["chrome", "google chrome", "edge", "microsoft edge", "brave", "chromium"];
        order.iter().find_map(|name| browsers.iter().find(|b| b.browser_type.to_lowercase() == *name))
    };

    let Some(browser_info) = target else {
        return Err("未检测到可用的 Chromium 浏览器（Chrome/Edge/Brave/Chromium）".to_string());
    };

    // 找一个 9222-9230 空闲端口，保证 WebSocketCdpBackend 能发现
    let port = (9222..=9230).find(|p| TcpListener::bind(("127.0.0.1", *p)).is_ok())
        .ok_or_else(|| "9222-9230 端口均被占用，无法启动 CDP 浏览器".to_string())?;

    // 启动浏览器（指定固定 CDP 端口）
    let session = start_session(&browser_info.browser_type, Some(port)).await
        .map_err(|e| format!("启动浏览器失败: {}", e))?;

    // 保持 session 活着：存入全局 map，防止被 drop 关闭
    let state = shared_state();
    let mut map = state.browser_sessions.lock().await;
    let id = format!("auto-cdp-{}", uuid::Uuid::new_v4());
    map.insert(id.clone(), session);

    Ok(format!("{} (CDP port {})", browser_info.browser_type, port))
}

// ---- 4. check_ocr --------------------------------------------------------

#[tauri::command]
pub fn check_ocr() -> Result<OcrHealth, String> {
    let state = shared_state();
    state.router.ocr.health()
}

// ---- 5. check_broker -----------------------------------------------------

#[tauri::command]
pub fn check_broker(broker_id: String) -> Result<bool, String> {
    let state = shared_state();
    Ok(state.brokers.adapter(&broker_id).is_some())
}

// ---- 6. list_brokers -----------------------------------------------------

#[tauri::command]
pub fn list_brokers(app: tauri::AppHandle) -> Result<Vec<BrokerSummary>, String> {
    let state = shared_state();
    let mut summaries: Vec<BrokerSummary> = state
        .brokers
        .health_all()
        .into_iter()
        .map(BrokerSummary::from)
        .collect();
    // 合并用户通过 configure_broker 落盘、但还没有运行时 adapter 的券商，
    // 让前端能看到「已登记但待接入」的条目（connected=false）。
    let known: HashSet<String> = summaries.iter().map(|s| s.broker_id.clone()).collect();
    for ub in load_user_brokers(&app) {
        if !known.contains(&ub.broker_id) {
            summaries.push(BrokerSummary {
                broker_id: ub.broker_id,
                connected: false,
                latency_ms: 0,
                last_error: Some("尚未连接（adapter 待接入）".to_string()),
            });
        }
    }
    Ok(summaries)
}

// ---- 7. configure_broker -------------------------------------------------

#[allow(dead_code)]
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct BrokerRegistration {
    pub broker_id: String,
    pub display_name: String,
    pub api_base: String,
    pub account: String,
}

fn brokers_path(app: &tauri::AppHandle) -> Option<PathBuf> {
    app.path()
        .app_data_dir()
        .ok()
        .map(|d| d.join("brokers.json"))
}

fn load_user_brokers(app: &tauri::AppHandle) -> Vec<BrokerRegistration> {
    if let Some(path) = brokers_path(app) {
        if let Ok(bytes) = std::fs::read(&path) {
            if let Ok(v) = serde_json::from_slice::<Vec<BrokerRegistration>>(&bytes) {
                return v;
            }
        }
    }
    Vec::new()
}

fn save_user_brokers(app: &tauri::AppHandle, brokers: &[BrokerRegistration]) {
    if let Some(path) = brokers_path(app) {
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        if let Ok(bytes) = serde_json::to_vec_pretty(brokers) {
            let tmp = path.with_extension("json.tmp");
            if std::fs::write(&tmp, &bytes).is_ok() {
                let _ = std::fs::rename(&tmp, &path);
            }
        }
    }
}

/// 登记 / 更新一个券商。
///
/// 内置 adapter（CTP / OpenD / iFinD 等）直接接受；未知 id 也允许落盘
/// （视为待接 adapter），不再像旧实现那样对未知 id 抛错。配置整体持久化
/// 到 `app_data_dir/brokers.json`，进程重启后 `list_brokers` 仍可见。
#[tauri::command]
pub fn configure_broker(app: tauri::AppHandle, config: BrokerRegistration) -> Result<(), String> {
    if config.broker_id.trim().is_empty() {
        return Err("broker_id is required".to_string());
    }
    let mut brokers = load_user_brokers(&app);
    if let Some(existing) = brokers.iter_mut().find(|b| b.broker_id == config.broker_id) {
        *existing = config;
    } else {
        brokers.push(config);
    }
    save_user_brokers(&app, &brokers);
    Ok(())
}

// ---- 8. set_app_profile --------------------------------------------------

#[tauri::command]
pub fn set_app_profile(profile_id: String) -> Result<(), String> {
    if find_profile(&profile_id).is_none() {
        return Err(format!("unknown app profile: {}", profile_id));
    }
    // The profile id is informational; the router uses it via
    // `PcStep::app_profile` to bias tier ordering on the next
    // `execute_step` call. No state needs to be held between
    // calls.
    Ok(())
}

// ---- 9. list_app_profiles ------------------------------------------------

#[tauri::command]
pub fn list_app_profiles() -> Result<Vec<crate::pc_automation::apps::AppProfile>, String> {
    // AppProfile is `Copy`, so cloning out of the static array is free.
    Ok(ALL_PROFILES.iter().map(|p| **p).collect())
}

// ---- 10. get_app_profile -------------------------------------------------

#[tauri::command]
pub fn get_app_profile(profile_id: String) -> Result<crate::pc_automation::apps::AppProfile, String> {
    find_profile(&profile_id)
        .copied()
        .ok_or_else(|| format!("unknown app profile: {}", profile_id))
}

// ---- 11. parse_selector --------------------------------------------------

#[allow(dead_code)]
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(tag = "kind", rename_all = "lowercase")]
pub enum ParsedSelector {
    Uia(crate::pc_automation::uia::types::UiaSelector),
    Cdp(crate::pc_automation::cdp::types::CdpSelector),
    Ocr(crate::pc_automation::ocr::types::OcrAnchor),
}

#[tauri::command]
pub fn parse_selector(selector: String) -> Result<ParsedSelector, String> {
    if selector.starts_with("uia:") {
        crate::pc_automation::uia::types::parse_uia_selector(&selector)
            .map(ParsedSelector::Uia)
            .map_err(|e| e.to_string())
    } else if selector.starts_with("cdp:") {
        crate::pc_automation::cdp::types::parse_cdp_selector(&selector)
            .map(ParsedSelector::Cdp)
            .map_err(|e| e.to_string())
    } else if selector.starts_with("ocr:") {
        crate::pc_automation::ocr::types::parse_ocr_anchor(&selector)
            .map(ParsedSelector::Ocr)
            .map_err(|e| e.to_string())
    } else {
        Err(ParseError::InvalidPrefix(
            selector.chars().take(4).collect::<String>(),
        )
        .to_string())
    }
}

// ---- 12. parse_step ------------------------------------------------------

#[tauri::command]
pub fn parse_step(
    step_id: String,
    description: String,
    strategy: String,
    primary_selector: String,
    app_profile: Option<String>,
) -> Result<PcStepView, String> {
    let strat = match strategy.as_str() {
        "uia" => StepStrategy::Uia,
        "cdp" => StepStrategy::Cdp,
        "ocr" => StepStrategy::Ocr,
        other => return Err(format!("unknown strategy: {}", other)),
    };
    // `app_profile` is forwarded as-is so a `parse_step` → `execute_step`
    // roundtrip preserves the bound application profile. The frontend
    // wrapper defaults to `null` when the caller omits it.
    let step = PcStep {
        id: step_id,
        description,
        app_profile,
        strategy: strat,
        primary_selector,
        fallback_selectors: vec![],
        recorded_coords: None,
    };
    Ok(PcStepView::from(&step))
}

// ---- 13. select_strategy -------------------------------------------------

#[tauri::command]
pub fn select_strategy(app_profile: Option<String>) -> Result<String, String> {
    if let Some(id) = app_profile.as_deref() {
        if let Some(p) = find_profile(id) {
            return Ok(match p.preferred_route {
                crate::pc_automation::apps::RoutePreference::UiaFirst => "uia".to_string(),
                crate::pc_automation::apps::RoutePreference::CdpFirst => "cdp".to_string(),
                crate::pc_automation::apps::RoutePreference::OcrFirst => "ocr".to_string(),
            });
        }
    }
    Ok("uia".to_string())
}

// ---- 14. execute_step ----------------------------------------------------

#[tauri::command]
pub async fn execute_step(step: PcStepView) -> Result<StepResult, String> {
    let state = shared_state();
    let strategy = match step.strategy.as_str() {
        "uia" => StepStrategy::Uia,
        "cdp" => StepStrategy::Cdp,
        "ocr" => StepStrategy::Ocr,
        "vlm" => StepStrategy::Vlm,
        other => {
            return Ok(StepResult {
                step_id: step.id,
                ok: false,
                outcome: None,
                error: Some(format!("unknown strategy: {}", other)),
            });
        }
    };
    let pc_step = PcStep {
        id: step.id.clone(),
        description: step.description.clone(),
        app_profile: step.app_profile.clone(),
        strategy,
        primary_selector: step.primary_selector.clone(),
        fallback_selectors: step.fallback_selectors.clone(),
        recorded_coords: step.recorded_coords,
    };
    match state.router.execute_step(&pc_step).await {
        Ok(outcome) => Ok(StepResult {
            step_id: pc_step.id,
            ok: true,
            outcome: Some(outcome),
            error: None,
        }),
        Err(e) => Ok(StepResult {
            step_id: pc_step.id,
            ok: false,
            outcome: None,
            error: Some(e.to_string()),
        }),
    }
}

// ---- 15. no_broker_available ---------------------------------------------

#[tauri::command]
pub fn no_broker_available() -> Result<bool, String> {
    let state = shared_state();
    let any_connected = state.brokers.health_all().iter().any(|h| h.connected);
    Ok(!any_connected)
}

// ---- 16. broker_only -----------------------------------------------------

#[tauri::command]
pub fn broker_only(action: String) -> Result<bool, String> {
    // "下单" / "trade" / "submit_order" style actions must go through
    // the broker API; UI automation is allowed for monitoring and
    // navigation only. Returns a plain bool so callers can use it to
    // gate UI affordances without exception handling.
    Ok(matches!(
        action.to_ascii_lowercase().as_str(),
        "submit_order" | "place_order" | "trade" | "buy" | "sell" | "cancel_order"
    ))
}

// ---- 17. parse_screen ----------------------------------------------------

/// DTO the front-end sends to the `parse_screen` Tauri command.
/// Mirrors `screen_parser::types::ParseRequest` field-for-field
/// so the wire format is the same shape the trait consumes
/// (we just re-tag with camelCase for the IPC layer).
#[allow(dead_code)]
#[derive(Serialize, Deserialize, Debug, Clone, Default)]
#[serde(rename_all = "camelCase")]
pub struct ParseScreenRequest {
    pub region: Option<crate::pc_automation::screen_parser::types::ScreenRect>,
    pub include_ocr: Option<bool>,
    pub min_confidence: Option<f32>,
}

#[tauri::command]
pub fn parse_screen(
    request: Option<ParseScreenRequest>,
) -> Result<Vec<crate::pc_automation::screen_parser::types::ScreenElement>, String> {
    let state = shared_state();
    let req = request.unwrap_or_default();
    let parsed = crate::pc_automation::screen_parser::types::ParseRequest {
        region: req.region,
        // Default to including OCR — that's the whole point of
        // the composer, the user explicitly opted in to this
        // command (it costs an extra OCR pass when no UIA name
        // is found).
        include_ocr: req.include_ocr.unwrap_or(true),
        // 0.5 mirrors the VLM rescue threshold and is the
        // sweet spot for "kept by the front-end Inspector".
        min_confidence: req.min_confidence.unwrap_or(0.5),
    };
    state.screen_parser.parse(parsed)
}

// ---- 18. check_screen_parser --------------------------------------------

#[tauri::command]
pub fn check_screen_parser(
) -> Result<crate::pc_automation::screen_parser::backend::ScreenParserHealth, String> {
    let state = shared_state();
    state.screen_parser.health()
}

// ---- 19-22. UIA direct surface (悬浮窗控制执行) ──────────────────
//
// 4 个直通命令让前端 skillBridge.cap.uia 不再是空实现：
//   19. uia_get_focused_window  — 当前焦点窗口的 UiaNode（含子树）
//   20. uia_find                — 按 selector 查找元素
//   21. uia_click               — 点击 UiaNode 对应的元素
//   22. uia_type                — 在 UiaNode 对应的元素中输入文本
//
// 调用链：AutomationPage 点「执行」→ skillBridge.callSkill('execute')
// → 技能代码调 cap.uia.find / click / type → invoke('uia_find' / ...)
// → shared_state().router.uia → WindowsUiaBackend
//
// 设计：与 cap.cdp 走 8642 gateway 平级；UIA 失败时 cap.recognize.chain
// 自动降级到下一个 tier（OCR / VLM）。

#[tauri::command]
pub fn uia_get_focused_window() -> Result<Option<UiaNodeView>, String> {
    let state = shared_state();
    state
        .router
        .uia
        .get_focused_window()
        .map(|opt| opt.map(UiaNodeView::from))
}

#[tauri::command]
pub fn uia_find(selector: UiaSelectorView) -> Result<Option<UiaNodeView>, String> {
    let state = shared_state();
    let sel: crate::pc_automation::uia::types::UiaSelector = selector.into();
    state
        .router
        .uia
        .find_by(&sel)
        .map(|opt| opt.map(UiaNodeView::from))
}

#[tauri::command]
pub fn uia_click(node: UiaNodeView) -> Result<(), String> {
    let state = shared_state();
    let n: crate::pc_automation::uia::types::UiaNode = node.into();
    state.router.uia.click(&n)
}

#[tauri::command]
pub fn uia_type(node: UiaNodeView, text: String) -> Result<(), String> {
    let state = shared_state();
    let n: crate::pc_automation::uia::types::UiaNode = node.into();
    state.router.uia.type_text(&n, &text)
}

// ── IPC views (camelCase wire format) ────────────────────────────
//
// `UiaNode` / `UiaSelector` 已经是 `#[serde(rename_all = "camelCase")]`,
// 但它们 lives in `pc_automation::uia::types` —— 前端 invoke 时 Tauri
// 会反序列化 JSON 到 Rust struct,这里显式定义 IPC view 是为了:
//   1. 让 commands 模块的公共 surface 集中可见(不用挖到 types 子模块)
//   2. 提供 `From` / `Into` 转换,避免 `serde_json::Value` 走 dynamic dispatch
//   3. 未来加字段时可在 IPC 层做兼容(default 字段),不破坏 backend trait

#[derive(Serialize, Deserialize, Debug, Clone, Default)]
#[serde(rename_all = "camelCase")]
pub struct UiaNodeView {
    pub name: String,
    pub class_name: String,
    pub automation_id: String,
    pub control_type: String,
    pub bounding_rect: (i32, i32, u32, u32),
    #[serde(default)]
    pub children: Vec<UiaNodeView>,
    #[serde(default)]
    pub runtime_id: Option<i64>,
}

impl From<crate::pc_automation::uia::types::UiaNode> for UiaNodeView {
    fn from(n: crate::pc_automation::uia::types::UiaNode) -> Self {
        UiaNodeView {
            name: n.name,
            class_name: n.class_name,
            automation_id: n.automation_id,
            control_type: n.control_type,
            bounding_rect: n.bounding_rect,
            children: n.children.into_iter().map(UiaNodeView::from).collect(),
            runtime_id: n.runtime_id,
        }
    }
}

impl From<UiaNodeView> for crate::pc_automation::uia::types::UiaNode {
    fn from(v: UiaNodeView) -> Self {
        crate::pc_automation::uia::types::UiaNode {
            name: v.name,
            class_name: v.class_name,
            automation_id: v.automation_id,
            control_type: v.control_type,
            bounding_rect: v.bounding_rect,
            children: v.children.into_iter().map(Into::into).collect(),
            runtime_id: v.runtime_id,
        }
    }
}

#[derive(Serialize, Deserialize, Debug, Clone, Default)]
#[serde(rename_all = "camelCase")]
pub struct UiaSelectorView {
    pub control_type: Option<String>,
    pub name: Option<String>,
    pub name_contains: Option<String>,
    pub automation_id: Option<String>,
    pub class_name: Option<String>,
    #[serde(default)]
    pub path: Vec<UiaSelectorView>,
}

impl From<UiaSelectorView> for crate::pc_automation::uia::types::UiaSelector {
    fn from(v: UiaSelectorView) -> Self {
        crate::pc_automation::uia::types::UiaSelector {
            control_type: v.control_type,
            name: v.name,
            name_contains: v.name_contains,
            automation_id: v.automation_id,
            class_name: v.class_name,
            path: v.path.into_iter().map(Into::into).collect(),
        }
    }
}

// ---- 23-26. Cua Driver surface ──────────────────────────────
//
// Cua Driver sidecar 集成命令：
//   23. check_cua_driver         — sidecar 健康状态
//   24. cua_driver_click         — 通过 Cua Driver 执行点击
//   25. cua_driver_type_text     — 通过 Cua Driver 输入文本
//   26. cua_driver_invoke        — 直接调用 Cua Driver 工具
//
// Cua Driver 通过 MCP JSON-RPC 2.0 over stdio 与 sidecar 进程通信，
// 替代 enigo 作为主要输入路径。当 Cua Driver 不可用时自动降级到 enigo。

/// Cua Driver sidecar 健康状态。
#[allow(dead_code)]
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct CuaDriverHealthView {
    pub available: bool,
    pub connected: bool,
    pub binary_path: Option<String>,
    pub version: Option<String>,
    pub tools_count: Option<usize>,
    pub last_error: Option<String>,
}

impl From<crate::pc_automation::cua_driver::CuaDriverHealth> for CuaDriverHealthView {
    fn from(h: crate::pc_automation::cua_driver::CuaDriverHealth) -> Self {
        CuaDriverHealthView {
            available: h.available,
            connected: h.connected,
            binary_path: h.binary_path,
            version: h.version,
            tools_count: h.tools_count,
            last_error: h.last_error,
        }
    }
}

// ---- 23. check_cua_driver ------------------------------------------------

#[tauri::command]
pub async fn check_cua_driver() -> Result<CuaDriverHealthView, String> {
    let cua = crate::pc_automation::cua_driver::CuaDriverClient::shared();
    let health = cua.health().await;
    Ok(CuaDriverHealthView::from(health))
}

// ---- 24. cua_driver_click ------------------------------------------------

#[tauri::command]
pub async fn cua_driver_click(x: i32, y: i32) -> Result<(), String> {
    let cua = crate::pc_automation::cua_driver::CuaDriverClient::shared();
    cua.click(x, y).await
}

// ---- 25. cua_driver_type_text --------------------------------------------

#[tauri::command]
pub async fn cua_driver_type_text(text: String) -> Result<(), String> {
    let cua = crate::pc_automation::cua_driver::CuaDriverClient::shared();
    cua.type_text(&text).await
}

// ---- 26. cua_driver_invoke -----------------------------------------------

/// 直接调用 Cua Driver 的 MCP 工具。
/// `tool_name` 如 "click", "type_text", "press_key", "hotkey", "scroll",
/// "get_accessibility_tree", "get_window_state" 等。
/// `arguments` 为工具参数的 JSON 对象。
#[tauri::command]
pub async fn cua_driver_invoke(
    tool_name: String,
    arguments: serde_json::Value,
) -> Result<serde_json::Value, String> {
    let cua = crate::pc_automation::cua_driver::CuaDriverClient::shared();
    cua.invoke_tool(&tool_name, arguments).await
}
