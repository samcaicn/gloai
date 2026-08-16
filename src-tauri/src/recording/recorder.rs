// Copyright (c) 2026 AIMarketing
//
// 录制器核心实现
//
// 自动发现支持CDP的软件 + UIA 窗口焦点轮询，监听用户操作事件，
// 每5秒去重后存储到本地。
//
// 实现策略:
//   * CDP: 通过/Runtime.evaluate和DOM事件监听 (1s 轮询，复用持久 WebSocket)
//   * UIA: 通过 Win32 GetForegroundWindow 轮询窗口焦点变化 (2s 间隔)
//   * 5秒周期: 使用tokio interval定时收集并存储
//   * 缓冲区上限: 每个app最多 1000 条待写入动作，防止内存溢出

use std::collections::{HashMap, VecDeque};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use tauri::{AppHandle, Wry};
use tokio::runtime::Runtime;
use tokio::time::interval;

use crate::pc_automation::cdp::websockets::{WebSocketCdpBackend, CdpTarget};
use crate::recording::action::{ActionType, ElementSelector, RecordedAction, RecordingBatch};
use crate::recording::store;
use crate::recording::is_recording_enabled;

/// 每个app的缓冲区上限，防止内存无限增长
const MAX_BUFFER_PER_APP: usize = 1000;

/// 最多并行监测的 CDP target 数量。
/// 限制为 3 个避免同时 WebSocket 连接过多拖慢系统；
/// 优先选 title 最长（最活跃）的页面。
const MAX_PARALLEL_TARGETS: usize = 3;

/// CDP 事件轮询间隔：1000ms
/// 用户操作是人类速度（秒级），无需亚秒轮询。1s 足够捕获所有操作，
/// 同时大幅降低 CPU/网络开销。
const CDP_POLL_INTERVAL_MS: u64 = 1000;

/// 全局录制器状态
static RECORDER_RUNNING: AtomicBool = AtomicBool::new(false);

/// 录制器实例
/// 使用 Mutex<Option<...>> 而非 OnceLock，以便 stop_recording 可以 take() 清空，
/// 让后续 start_recording 能重新创建 recorder（OnceLock::set 只能成功一次，
/// 会导致暂停/启动切换后录制永久失效）。
static RECORDER: Mutex<Option<Arc<Recorder>>> = Mutex::new(None);

/// 录制器内部状态
struct Recorder {
    /// 收集的动作缓冲区(按app_name分组)
    actions: Mutex<HashMap<String, VecDeque<RecordedAction>>>,
    /// 当前连接的CDP targets
    targets: Mutex<HashMap<String, CdpTarget>>,
    /// tokio runtime
    runtime: Runtime,
    /// 停止信号
    stop_signal: Arc<AtomicBool>,
}

impl Recorder {
    fn new() -> Result<Self, String> {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(3)
            .thread_name("recording-worker")
            .enable_all()
            .build()
            .map_err(|e| format!("recording runtime build failed: {}", e))?;
        Ok(Self {
            actions: Mutex::new(HashMap::new()),
            targets: Mutex::new(HashMap::new()),
            runtime,
            stop_signal: Arc::new(AtomicBool::new(false)),
        })
    }

    /// 发现所有CDP targets并订阅事件
    /// 改为 async，由 start_recording 在 runtime 上 spawn 调度，
    /// 避免 block_on 顺序探测 9 个端口时阻塞 Tauri 主线程导致 UI 卡死。
    ///
    /// 最多只并行订阅 MAX_PARALLEL_TARGETS 个 target，优先选 title 最长
    /// （最活跃）的页面，避免同时维护过多 WebSocket 连接拖慢系统。
    async fn discover_and_subscribe(&self) {
        // 发现所有CDP page targets
        let mut targets = match WebSocketCdpBackend::list_all_page_targets_async().await {
            Ok(t) => t,
            Err(e) => {
                eprintln!("[recorder] discover targets failed: {}", e);
                return;
            }
        };

        // 按 title 长度降序排列，优先订阅活跃页面（title 长 = 有内容）
        targets.sort_by_key(|b| std::cmp::Reverse(b.title.len()));
        // 只取前 MAX_PARALLEL_TARGETS 个
        let targets: Vec<CdpTarget> = targets.into_iter().take(MAX_PARALLEL_TARGETS).collect();

        log::info!(
            "[recorder] 发现 {} 个 CDP target，订阅前 {} 个",
            targets.len(),
            targets.len()
        );

        // 更新targets列表
        {
            let mut guard = self.targets.lock().unwrap_or_else(|e| e.into_inner());
            guard.clear();
            for target in &targets {
                guard.insert(target.id.clone(), target.clone());
            }
        }

        // 为每个target订阅事件监听
        for target in targets {
            self.subscribe_to_target(target);
        }
    }

    /// 订阅单个CDP target的输入事件
    ///
    /// **性能优化**：维护单一持久 WebSocket 连接，复用连接进行脚本注入和事件收集。
    /// 旧版每个轮询周期都创建新 TCP+WS 连接（300ms x 3 targets ≈ 10 次握手/秒），
    /// 是系统卡死的主要原因。新版一次连接，后续复用，连接断开时自动重建。
    fn subscribe_to_target(&self, target: CdpTarget) {
        let ws_url = target.web_socket_debugger_url.clone();
        let title = target.title.clone();
        let url = target.url.clone();
        let app_name = extract_app_name(&title, &url);
        let stop_signal = self.stop_signal.clone();

        self.runtime.spawn(async move {
            use futures::{SinkExt, StreamExt};
            use serde_json::json;
            use tokio_tungstenite::tungstenite::Message;

            type WsStream = tokio_tungstenite::WebSocketStream<
                tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>,
            >;

            let mut poll_interval = interval(Duration::from_millis(CDP_POLL_INTERVAL_MS));
            let mut consecutive_failures: u32;
            const MAX_CONSECUTIVE_FAILURES: u32 = 10;
            let mut ws: Option<WsStream>;
            let mut msg_id: u64 = 0;

            async fn cdp_eval(
                ws: &mut WsStream,
                msg_id: &mut u64,
                js: &str,
            ) -> Result<String, String> {
                *msg_id += 1;
                let id = *msg_id;
                let payload = json!({
                    "id": id,
                    "method": "Runtime.evaluate",
                    "params": {
                        "expression": js,
                        "returnByValue": true,
                        "awaitPromise": true,
                    }
                });
                ws.send(Message::Text(payload.to_string()))
                    .await
                    .map_err(|e| format!("ws send: {}", e))?;

                let deadline = tokio::time::Instant::now() + Duration::from_secs(5);
                loop {
                    let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
                    if remaining.is_zero() {
                        return Err(format!("cdp timeout waiting for id={}", id));
                    }
                    let frame = tokio::time::timeout(remaining, ws.next())
                        .await
                        .map_err(|_| format!("cdp timeout waiting for id={}", id))?;
                    let frame = match frame {
                        Some(Ok(Message::Text(t))) => t,
                        Some(Ok(Message::Binary(b))) => {
                            String::from_utf8(b).map_err(|e| format!("ws utf8: {}", e))?
                        }
                        Some(Ok(Message::Close(_))) | None => {
                            return Err("ws closed".to_string());
                        }
                        Some(Ok(_)) => continue,
                        Some(Err(e)) => return Err(format!("ws recv: {}", e)),
                    };
                    let resp: serde_json::Value = serde_json::from_str(&frame)
                        .map_err(|e| format!("ws parse: {}", e))?;
                    if resp.get("id").and_then(|v| v.as_i64()) == Some(id as i64) {
                        if let Some(err) = resp.get("error") {
                            return Err(err.to_string());
                        }
                        return resp
                            .get("result")
                            .and_then(|r| r.get("result"))
                            .and_then(|r| r.get("value"))
                            .map(|v| v.to_string())
                            .ok_or_else(|| "no value in result".to_string());
                    }
                }
            }

            async fn connect_and_inject(
                ws_url: &str,
                script: &str,
                msg_id: &mut u64,
            ) -> Result<WsStream, String> {
                // connect_async 缺外层 timeout 时,TCP 已连但 HTTP upgrade 不返回会永久挂起,
                // 单 target 卡死会占住 recording runtime 的 worker thread,3 个 target
                // 全卡死时录制器整体瘫痪(无显式错误日志)。这里加 3s 外层 timeout 兜底。
                let (mut ws_stream, _) = tokio::time::timeout(
                    Duration::from_secs(3),
                    tokio_tungstenite::connect_async(ws_url),
                )
                .await
                .map_err(|_| format!("connect {} timeout (3s)", ws_url))?
                .map_err(|e| format!("connect {}: {}", ws_url, e))?;
                cdp_eval(&mut ws_stream, msg_id, script).await?;
                Ok(ws_stream)
            }

            let script = generate_event_listener_script();
            let collect_script = collect_events_script();

            match connect_and_inject(&ws_url, &script, &mut msg_id).await {
                Ok(stream) => {
                    ws = Some(stream);
                    consecutive_failures = 0;
                }
                Err(e) => {
                    eprintln!("[recorder] inject script failed for {}: {}", title, e);
                    return;
                }
            }

            while !stop_signal.load(Ordering::SeqCst) && is_recording_enabled() {
                poll_interval.tick().await;

                if ws.is_none() {
                    match connect_and_inject(&ws_url, &script, &mut msg_id).await {
                        Ok(stream) => {
                            ws = Some(stream);
                            consecutive_failures = 0;
                        }
                        Err(e) => {
                            consecutive_failures += 1;
                            log::warn!(
                                "[recorder] target \"{}\" reconnect failed: {}",
                                title, e
                            );
                            if consecutive_failures >= MAX_CONSECUTIVE_FAILURES {
                                log::warn!(
                                    "[recorder] target \"{}\" 断开订阅",
                                    title
                                );
                                break;
                            }
                            continue;
                        }
                    }
                }

                let ws_stream = match ws.as_mut() {
                    Some(s) => s,
                    None => {
                        log::warn!("[recorder] target \"{}\" ws unexpectedly None, skipping", title);
                        continue;
                    }
                };
                match cdp_eval(ws_stream, &mut msg_id, &collect_script).await {
                    Ok(events_json) => {
                        match serde_json::from_str::<Vec<JsEvent>>(&events_json) {
                            Ok(events) => {
                                consecutive_failures = 0;
                                for event in events {
                                    let action = convert_js_event_to_action(&event, &app_name, &url);
                                    if let Some(action) = action {
                                        add_action_to_buffer(action);
                                    }
                                }
                            }
                            Err(_) => {
                                consecutive_failures += 1;
                                if consecutive_failures >= MAX_CONSECUTIVE_FAILURES {
                                    log::warn!(
                                        "[recorder] target \"{}\" 连续失败 {} 次，断开订阅",
                                        title,
                                        consecutive_failures
                                    );
                                    break;
                                }
                            }
                        }
                    }
                    Err(_) => {
                        ws.take();
                        consecutive_failures += 1;
                        if consecutive_failures >= MAX_CONSECUTIVE_FAILURES {
                            log::warn!(
                                "[recorder] target \"{}\" 连续失败 {} 次，断开订阅",
                                title,
                                consecutive_failures
                            );
                            break;
                        }
                    }
                }
            }
        });
    }
}

/// 从target title/url提取软件名称
fn extract_app_name(title: &str, url: &str) -> String {
    // 优先从title提取
    // 例如: "同花顺 iFinD - ..." -> "同花顺 iFinD"
    // 例如: "AIMarketing - AIMarketing" -> "AIMarketing"
    if !title.is_empty() {
        // 取第一个"-"之前的部分
        let parts: Vec<&str> = title.splitn(2, '-').collect();
        let name = parts[0].trim();

        if !name.is_empty() {
            return name.to_string();
        }
    }

    // 从URL提取域名作为fallback
    if !url.is_empty() {
        // 解析URL获取域名
        if let Ok(parsed) = url::Url::parse(url) {
            if let Some(host) = parsed.host_str() {
                // 去掉www.前缀
                let host = host.strip_prefix("www.").unwrap_or(host);
                return host.to_string();
            }
        }
    }

    "unknown_app".to_string()
}

/// JavaScript事件对象
#[derive(Debug, Clone, serde::Deserialize)]
struct JsEvent {
    #[serde(rename = "type")]
    event_type: String,
    /// 主选择器值
    target_selector: Option<String>,
    /// 主选择器类型（css / xpath / text）
    target_selector_type: Option<String>,
    /// XPath（从 fallbacks 中提取）
    target_xpath: Option<String>,
    /// 备选选择器列表
    target_fallbacks: Vec<JsFallback>,
    target_text: Option<String>,
    target_tag: Option<String>,
    target_id: Option<String>,
    target_class: Option<String>,
    #[serde(rename = "targetX")]
    target_x: Option<i32>,
    #[serde(rename = "targetY")]
    target_y: Option<i32>,
    #[serde(rename = "targetW")]
    target_w: Option<i32>,
    #[serde(rename = "targetH")]
    target_h: Option<i32>,
    data: Option<String>,
    timestamp: Option<i64>,
}

/// JS 传入的备选选择器
#[derive(Debug, Clone, serde::Deserialize)]
struct JsFallback {
    #[serde(rename = "type")]
    sel_type: String,
    value: String,
}

/// 转换JsEvent到RecordedAction
fn convert_js_event_to_action(
    event: &JsEvent,
    app_name: &str,
    context: &str,
) -> Option<RecordedAction> {
    // 映射事件类型到ActionType
    let action_type = match event.event_type.as_str() {
        "click" => ActionType::Click,
        "dblclick" => ActionType::DoubleClick,
        "contextmenu" => ActionType::RightClick,
        "input" | "change" => ActionType::Type,
        "keydown" => ActionType::KeyDown,
        "scroll" => ActionType::Scroll,
        "focus" => ActionType::Focus,
        "select" | "selectionchange" => ActionType::Select,
        _ => return None, // 忽略其他事件
    };

    // 构建选择器
    let selector = build_selector_from_js_event(event);

    let mut action = RecordedAction::new(app_name, context, action_type, "cdp");

    if let Some(sel) = selector {
        action = action.with_target(sel);
    }

    if let Some(data) = &event.data {
        action = action.with_data(data);
    }

    Some(action)
}

/// 从JsEvent构建ElementSelector
/// 主选择器由JS端的稳定属性策略决定，fallback_selectors 存储备选定位策略
/// 不再包含坐标定位（bounds），仅使用元素选择器
fn build_selector_from_js_event(event: &JsEvent) -> Option<ElementSelector> {
    // 不再使用 bounds，仅保留元素选择器

    // 构建备选选择器列表（从 JS fallbacks 转换）
    let fallbacks: Vec<crate::recording::action::FallbackSelector> = event
        .target_fallbacks
        .iter()
        .filter(|f| !f.value.is_empty() && f.sel_type != "bounds")
        .map(|f| crate::recording::action::FallbackSelector {
            selector_type: f.sel_type.clone(),
            value: f.value.clone(),
        })
        .collect();

    // 主选择器：优先用 JS 端确定的 primary selector
    let (selector_type, value) = if let (Some(sel), Some(stype)) = (&event.target_selector, &event.target_selector_type) {
        if !sel.is_empty() {
            (stype.clone(), sel.clone())
        } else {
            // primary 为空，从 fallbacks 中取第一个
            if let Some(first) = fallbacks.first() {
                let ft = first.selector_type.clone();
                let fv = first.value.clone();
                (ft, fv)
            } else {
                return None;
            }
        }
    } else if let Some(sel) = &event.target_selector {
        if !sel.is_empty() {
            ("css".to_string(), sel.clone())
        } else {
            return None;
        }
    } else {
        // 无 primary，尝试 text
        if let Some(text) = &event.target_text {
            if !text.is_empty() {
                return Some(ElementSelector {
                    selector_type: "text".to_string(),
                    value: text.clone(),
                    text_content: Some(text.clone()),
                    bounds: None, // 不再记录坐标
                    fallback_selectors: fallbacks,
                });
            }
        }
        return None;
    };

    Some(ElementSelector {
        selector_type,
        value,
        text_content: event.target_text.clone(),
        bounds: None, // 不再记录坐标
        fallback_selectors: fallbacks,
    })
}

/// 生成事件监听脚本
/// 注入到页面后，监听用户操作并存储到window.__tupaiEvents数组
fn generate_event_listener_script() -> String {
    r##"
(function() {
    if (window.__tupaiEventsInitialized) return;
    window.__tupaiEventsInitialized = true;
    window.__tupaiEvents = [];
    window.__tupaiEventsMax = 100;

    // CSS.escape polyfill（旧版 WebView2 / Chromium 可能不支持）
    if (typeof CSS === 'undefined' || typeof CSS.escape !== 'function') {
        window.CSS = window.CSS || {};
        CSS.escape = function(val) {
            if (!val) return '';
            return String(val).replace(/[!"#$%&'()*+,.\/:;<=>?@[\\\]^`{|}~]/g, '\\$&');
        };
    }

    // 验证选择器语法是否合法
    function isValidSelector(sel) {
        try { document.querySelector(sel); return true; } catch(e) { return false; }
    }

    // 验证选择器是否唯一匹配
    function isUniqueSelector(sel) {
        try { return document.querySelectorAll(sel).length === 1; } catch(e) { return false; }
    }

    // 尝试用稳定属性直接定位（优先级最高，与DOM位置无关）
    // 增强版：支持更多测试属性、语义属性、placeholder、title等
    function tryStableSelector(el) {
        // 1. 测试标识属性 — 开发者测试标识，最稳定（多形态支持）
        var testAttrs = ['data-testid', 'data-test-id', 'data-cy', 'data-e2e', 'data-automation-id', 'data-qa'];
        for (var i = 0; i < testAttrs.length; i++) {
            var val = el.getAttribute(testAttrs[i]);
            if (val) {
                var sel = '[' + testAttrs[i] + '="' + CSS.escape(val) + '"]';
                if (isUniqueSelector(sel)) return { type: 'css', value: sel, attr: testAttrs[i] };
            }
        }

        // 2. aria-label — 无障碍标签，语义明确
        var ariaLabel = el.getAttribute('aria-label');
        if (ariaLabel) {
            var sel2 = '[aria-label="' + CSS.escape(ariaLabel) + '"]';
            if (isUniqueSelector(sel2)) return { type: 'css', value: sel2 };
        }

        // 3. role + accessible name（增强版）
        var role = el.getAttribute('role');
        if (role) {
            // 优先 aria-label，其次 aria-labelledby 引用元素的文本，其次 name 属性，其次 innerText
            var accName = ariaLabel;
            if (!accName && el.getAttribute('aria-labelledby')) {
                try {
                    var labelEl = document.getElementById(el.getAttribute('aria-labelledby'));
                    if (labelEl) accName = (labelEl.innerText || '').trim().slice(0, 30);
                } catch(e) {}
            }
            if (!accName) accName = el.getAttribute('name');
            if (!accName && el.innerText) accName = el.innerText.trim().slice(0, 30);
            if (accName) {
                var sel3 = '[role="' + CSS.escape(role) + '"]';
                // 添加 accessible name 作为文本匹配（更稳定）
                sel3 += ':has-text("' + CSS.escape(accName.slice(0, 20)) + '")';
                if (isUniqueSelector(sel3)) return { type: 'css', value: sel3 };
            }
        }

        // 4. placeholder — 输入框常用，语义明确
        var placeholder = el.getAttribute('placeholder');
        if (placeholder && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) {
            var sel4 = el.tagName.toLowerCase() + '[placeholder="' + CSS.escape(placeholder) + '"]';
            if (isUniqueSelector(sel4)) return { type: 'css', value: sel4 };
        }

        // 5. title — 提示文本属性
        var titleAttr = el.getAttribute('title');
        if (titleAttr) {
            var sel5 = '[title="' + CSS.escape(titleAttr) + '"]';
            if (isUniqueSelector(sel5)) return { type: 'css', value: sel5 };
        }

        // 6. alt — 图像替代文本
        var alt = el.getAttribute('alt');
        if (alt && (el.tagName === 'IMG' || el.tagName === 'AREA')) {
            var sel6 = el.tagName.toLowerCase() + '[alt="' + CSS.escape(alt) + '"]';
            if (isUniqueSelector(sel6)) return { type: 'css', value: sel6 };
        }

        // 7. type + name — 表单元素组合定位
        var inputType = el.getAttribute('type');
        var nameProp = el.getAttribute('name');
        if (el.tagName === 'INPUT' && inputType && nameProp) {
            var sel7 = 'input[type="' + CSS.escape(inputType) + '"][name="' + CSS.escape(nameProp) + '"]';
            if (isUniqueSelector(sel7)) return { type: 'css', value: sel7 };
        }

        // 8. name 属性（表单元素常用）— 单独使用
        if (nameProp) {
            var sel8 = el.tagName.toLowerCase() + '[name="' + CSS.escape(nameProp) + '"]';
            if (isUniqueSelector(sel8)) return { type: 'css', value: sel8 };
        }

        // 9. for 属性 — label 元素绑定
        var forAttr = el.getAttribute('for');
        if (forAttr && el.tagName === 'LABEL') {
            var sel9 = 'label[for="' + CSS.escape(forAttr) + '"]';
            if (isUniqueSelector(sel9)) return { type: 'css', value: sel9 };
        }

        // 10. href 片段 — 链接锚点
        var href = el.getAttribute('href');
        if (href && el.tagName === 'A' && href.startsWith('#') && href.length > 1) {
            var sel10 = 'a[href="' + CSS.escape(href) + '"]';
            if (isUniqueSelector(sel10)) return { type: 'css', value: sel10 };
        }

        // 11. id — 唯一但可能动态生成（最后尝试）
        // 检测动态ID：包含随机字符串（如数字串、uuid片段）
        if (el.id) {
            var isDynamicId = /^[0-9]+$/.test(el.id) || /^[a-f0-9]{8}/i.test(el.id) || /_[a-f0-9]{6,}$/i.test(el.id);
            if (!isDynamicId) {
                var sel11 = '#' + CSS.escape(el.id);
                if (isUniqueSelector(sel11)) return { type: 'css', value: sel11 };
            }
        }

        // 12. 文本内容精确匹配 — 按钮/链接常用
        // 注意:原实现用 Playwright 私有伪类 `:text-is()`,querySelectorAll
        // 不识别会抛错被吞,该级永远不命中。改为直接返回文本 selector,
        // 由回放端用 TreeWalker/XPath 按 text()='...' 精确匹配。
        var text = el.tagName === 'BUTTON' || el.tagName === 'A' ? (el.innerText || '').trim().slice(0, 30) : '';
        if (text && el.innerText) {
            // 校验在相同 tag 中该文本唯一(用 TreeWalker,绕开 querySelectorAll 的伪类限制)
            var tagLower = el.tagName.toLowerCase();
            var sameTagCount = 0;
            try {
                var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_ELEMENT, {
                    acceptNode: function(node) {
                        if (node.tagName === el.tagName && (node.innerText || '').trim() === text) {
                            sameTagCount++;
                            if (sameTagCount > 1) return NodeFilter.FILTER_REJECT;
                            return NodeFilter.FILTER_ACCEPT;
                        }
                        return NodeFilter.FILTER_SKIP;
                    }
                });
                while (walker.nextNode()) { /* 已在 acceptNode 中计数 */ }
            } catch(e) { /* 忽略,降级为不校验唯一性 */ }
            if (sameTagCount === 1) {
                return { type: 'text', value: text, exact: true, tag: tagLower };
            }
        }

        return null;
    }

    // 生成从 el 到 document 的唯一 CSS 选择器路径（增强版）
    // 优化策略：优先找稳定父节点锚点，减少位置依赖
    function generateCssPath(el) {
        if (!el || el === document.body || el === document.documentElement) return null;
        var parts = [];
        var current = el;
        var foundAnchor = false;

        while (current && current !== document.body && current !== document.documentElement) {
            // 找到稳定锚点（有id或稳定class组合）就停止
            if (current.id && !/^[0-9]+$/.test(current.id) && !/^[a-f0-9]{8}/i.test(current.id)) {
                parts.unshift('#' + CSS.escape(current.id));
                foundAnchor = true;
                break;
            }

            // 尝试用稳定class组合（不包含随机字符串）
            var classes = (current.className || '').split(/\s+/).filter(function(c) {
                return c && !/^[0-9]+$/.test(c) && !/[a-f0-9]{6,}/i.test(c) && !/css-/.test(c) && !/sc-/.test(c);
            });
            if (classes.length > 0) {
                // 用语义化的class（如btn-primary, nav-item等）
                var semanticClasses = classes.filter(function(c) {
                    return /^(btn|nav|menu|form|input|card|modal|dialog|tab|panel|container|wrapper|header|footer|section|article|main|sidebar)/.test(c);
                });
                if (semanticClasses.length > 0) {
                    var sel = current.tagName.toLowerCase() + '.' + semanticClasses[0];
                    // 检查这个选择器是否在父级唯一
                    var parent = current.parentElement;
                    if (parent) {
                        var matches = parent.querySelectorAll(sel);
                        if (matches.length === 1 && matches[0] === current) {
                            parts.unshift(sel);
                            continue;
                        }
                    }
                }
            }

            // fallback: 使用位置选择器（但尽量避免）
            var selector = current.tagName.toLowerCase();
            var parent = current.parentElement;
            if (parent) {
                var siblings = Array.from(parent.children).filter(function(s) { return s.tagName === current.tagName; });
                if (siblings.length > 1) {
                    var idx = siblings.indexOf(current) + 1;
                    selector += ':nth-of-type(' + idx + ')';
                }
            }
            parts.unshift(selector);
            current = current.parentElement;
        }

        if (parts.length === 0) return null;
        var path = parts.join(' > ');
        return isValidSelector(path) ? path : null;
    }

    // 生成 XPath（增强版：支持文本contains匹配，减少位置依赖）
    function generateXPath(el) {
        if (!el || el === document.body || el === document.documentElement) return null;
        var parts = [];
        var current = el;

        while (current && current !== document.body && current !== document.documentElement) {
            // 找到ID锚点就停止
            if (current.id && !/^[0-9]+$/.test(current.id)) {
                parts.unshift('/*[@id="' + current.id + '"]');
                break;
            }

            var tag = current.tagName.toLowerCase();

            // 优先用文本内容定位（按钮/链接/标签）
            var text = (current.innerText || current.textContent || '').trim();
            if (text && text.length > 0 && text.length < 50) {
                // 清理文本：移除多余空白
                var cleanText = text.replace(/\s+/g, ' ').trim();
                if (cleanText.length > 0 && cleanText.length < 30) {
                    // 尝试精确文本匹配
                    var textXPath = tag + '[text()="' + cleanText + '"]';
                    var parent = current.parentElement;
                    if (parent) {
                        try {
                            var result = document.evaluate(textXPath, parent, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
                            if (result.snapshotLength === 1 && result.snapshotItem(0) === current) {
                                parts.unshift(textXPath);
                                current = current.parentElement;
                                continue;
                            }
                        } catch(e) {}
                    }
                    // 尝试contains匹配（更宽松）
                    if (cleanText.length > 2) {
                        var containsXPath = tag + '[contains(text(), "' + cleanText.slice(0, 20) + '")]';
                        try {
                            var result2 = document.evaluate(containsXPath, parent, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
                            if (result2.snapshotLength === 1 && result2.snapshotItem(0) === current) {
                                parts.unshift(containsXPath);
                                current = current.parentElement;
                                continue;
                            }
                        } catch(e) {}
                    }
                }
            }

            // fallback: 使用位置
            var parent = current.parentElement;
            if (parent) {
                var siblings = Array.from(parent.children).filter(function(s) { return s.tagName === current.tagName; });
                if (siblings.length > 1) {
                    var idx = siblings.indexOf(current) + 1;
                    parts.unshift(tag + '[' + idx + ']');
                } else {
                    parts.unshift(tag);
                }
            } else {
                parts.unshift(tag);
            }
            current = current.parentElement;
        }

        if (parts.length === 0) return null;
        return '/' + parts.join('/');
    }

    // 综合选择器策略：主选择器 + 备选列表。元素选择器为主,坐标作为最后兜底。
    function buildSelectors(el) {
        var result = { primary: null, fallbacks: [] };

        // 优先使用稳定属性选择器
        var stable = tryStableSelector(el);
        if (stable) {
            result.primary = stable;
        }

        // CSS 路径（位置依赖，但可靠）
        var cssPath = generateCssPath(el);
        if (cssPath) {
            if (!result.primary) {
                result.primary = { type: 'css', value: cssPath };
            } else if (cssPath !== result.primary.value) {
                result.fallbacks.push({ type: 'css', value: cssPath });
            }
        }

        // XPath
        var xpath = generateXPath(el);
        if (xpath) {
            if (!result.primary) {
                result.primary = { type: 'xpath', value: xpath };
            } else {
                result.fallbacks.push({ type: 'xpath', value: xpath });
            }
        }

        // 文本定位（最宽松的 fallback）
        var text = (el.tagName === 'BUTTON' || el.tagName === 'A' || el.tagName === 'INPUT')
            ? (el.innerText || el.value || el.placeholder || '').trim().slice(0, 30)
            : (el.textContent || '').trim().slice(0, 50);
        if (text && result.primary && result.primary.type !== 'text') {
            result.fallbacks.push({ type: 'text', value: text });
        }

        // 坐标 fallback (last resort):元素查找全失败时,用录制时坐标点击。
        // 项目硬约束:录制端同时记录元素选择器和坐标信息。
        // 用 getBoundingClientRect 中心点(屏幕绝对坐标 = clientX + window.screenX + window.innerWidth 偏移)。
        // 注意:此处用 viewport 相对坐标,回放端 enigo_click 需要屏幕绝对坐标,转换由调用方做。
        // 这里以 rect 中心作为 Coordinate selector 的 value (格式 "x,y")。
        try {
            var rect = el.getBoundingClientRect();
            if (rect.width > 0 && rect.height > 0) {
                var cx = Math.round(rect.left + rect.width / 2);
                var cy = Math.round(rect.top + rect.height / 2);
                result.fallbacks.push({ type: 'coordinate', value: cx + ',' + cy });
            }
        } catch(e) { /* 忽略,坐标兜底非必须 */ }

        return result;
    }

    function getElementInfo(el) {
        if (!el || el === document.body) return null;
        var rect = el.getBoundingClientRect();
        var selectors = buildSelectors(el);
        return {
            id: el.id || null,
            class: (typeof el.className === 'string') ? el.className : '',
            tag: el.tagName || null,
            text: (el.textContent || el.value || '').trim().slice(0, 50),
            shortText: (el.tagName === 'BUTTON' || el.tagName === 'A' || el.tagName === 'INPUT')
                ? (el.innerText || el.value || el.placeholder || '').trim().slice(0, 30)
                : '',
            x: rect.left,
            y: rect.top,
            w: rect.width,
            h: rect.height,
            primarySelector: selectors.primary,
            fallbackSelectors: selectors.fallbacks
        };
    }

    function pushEvent(type, el, data) {
        if (window.__tupaiEvents.length >= window.__tupaiEventsMax) {
            window.__tupaiEvents.shift();
        }
        var info = getElementInfo(el);
        window.__tupaiEvents.push({
            type: type,
            target_selector: info && info.primarySelector ? info.primarySelector.value : null,
            target_selector_type: info && info.primarySelector ? info.primarySelector.type : null,
            target_xpath: info ? (info.fallbackSelectors.find(function(f) { return f.type === 'xpath'; }) || {}).value || null : null,
            target_fallbacks: info ? info.fallbackSelectors : [],
            target_text: info ? (info.shortText || info.text) : null,
            target_tag: info ? info.tag : null,
            target_id: info ? info.id : null,
            target_class: info ? info.class : null,
            targetX: info ? info.x : null,
            targetY: info ? info.y : null,
            targetW: info ? info.w : null,
            targetH: info ? info.h : null,
            data: data,
            timestamp: Date.now()
        });
    }

    // 监听点击
    document.addEventListener('click', function(e) {
        pushEvent('click', e.target, null);
    }, true);

    // 监听双击
    document.addEventListener('dblclick', function(e) {
        pushEvent('dblclick', e.target, null);
    }, true);

    // 监听右键
    document.addEventListener('contextmenu', function(e) {
        pushEvent('contextmenu', e.target, null);
    }, true);

    // 监听输入
    document.addEventListener('input', function(e) {
        var data = e.target.value || e.target.textContent || '';
        pushEvent('input', e.target, data.slice(0, 100));
    }, true);

    // 监听变化
    document.addEventListener('change', function(e) {
        var data = e.target.value || '';
        pushEvent('change', e.target, data.slice(0, 100));
    }, true);

    // 监听按键
    document.addEventListener('keydown', function(e) {
        if (e.key === 'Enter' || e.key === 'Tab' || e.key === 'Escape' || e.key === 'Backspace') {
            pushEvent('keydown', e.target, e.key);
        }
    }, true);

    // 监听滚动
    document.addEventListener('scroll', function(e) {
        var target = e.target === document ? document.body : e.target;
        pushEvent('scroll', target, Math.round(target.scrollTop));
    }, true);

    // 监听焦点
    document.addEventListener('focus', function(e) {
        pushEvent('focus', e.target, null);
    }, true);

    // 监听选择
    document.addEventListener('select', function(e) {
        pushEvent('select', e.target, e.target.value);
    }, true);
})();
"##.to_string()
}

/// 收集事件脚本
/// 直接 return events 数组，让 CDP 的 returnByValue 负责序列化为 JSON。
/// 若在此处用 JSON.stringify(events)，CDP returnByValue 会把结果作为
/// Value::String 返回，websockets.rs 再用 v.to_string() 序列化一次，
/// 得到带引号的 JSON 字符串字面量，serde_json::from_str 解析时第一个
/// 字符是 '"' 必然失败，导致 JS 事件永远不会被解析。
fn collect_events_script() -> String {
    r#"
(function() {
    var events = window.__tupaiEvents || [];
    window.__tupaiEvents = [];
    return events;
})();
"#.to_string()
}

/// 添加动作到全局缓冲区
/// 公开给 UIA poller 调用。每个 app 缓冲区上限 MAX_BUFFER_PER_APP，
/// 超出时丢弃最旧的动作（FIFO 淘汰），防止内存无限增长。
pub fn add_action_to_buffer(action: RecordedAction) {
    let recorder = {
        let guard = RECORDER.lock().unwrap_or_else(|p| p.into_inner());
        guard.clone()
    };
    if let Some(recorder) = recorder {
        let mut guard = recorder.actions.lock().unwrap_or_else(|e| e.into_inner());
        let buf = guard
            .entry(action.app_name.clone())
            .or_insert_with(VecDeque::new);
        buf.push_back(action);
        // 缓冲区上限：VecDeque::drain front 是 O(1)（无需移动剩余元素）
        if buf.len() > MAX_BUFFER_PER_APP {
            let drop_count = buf.len() - MAX_BUFFER_PER_APP;
            buf.drain(..drop_count);
        }
    }
}

/// 启动录制
pub fn start_recording(_app: AppHandle<Wry>) {
    // 锁内创建并插入 recorder，锁外再 spawn 任务，
    // 避免持锁 spawn / 持锁 await 造成死锁。
    let recorder = {
        let mut guard = RECORDER.lock().unwrap_or_else(|p| p.into_inner());
        if guard.is_some() {
            return; // 已在运行
        }
        let r = match Recorder::new() {
            Ok(rec) => Arc::new(rec),
            Err(e) => {
                log::error!("[recorder] {}", e);
                return;
            }
        };
        guard.replace(r.clone());
        r
    };

    RECORDER_RUNNING.store(true, Ordering::SeqCst);

    // 发现并订阅 CDP targets（异步调度，不 block_on 阻塞调用线程）
    let recorder_for_discover = recorder.clone();
    recorder
        .runtime
        .spawn(async move { recorder_for_discover.discover_and_subscribe().await });

    // 启动 UIA 窗口焦点轮询（在同一 runtime 上 spawn，不阻塞主进程）
    #[cfg(windows)]
    {
        let stop_for_uia = recorder.stop_signal.clone();
        recorder.runtime.spawn(async move {
            crate::recording::uia_poller::run_uia_poller(stop_for_uia).await;
        });
    }

    // 启动5秒周期的存储任务
    // 关键：flush_and_save 内部做同步文件 I/O，必须用 spawn_blocking
    // 调度到 tokio blocking pool，否则会阻塞 worker thread。
    // 之前在普通 spawn 的 async 闭包里直接调同步文件 I/O，runtime
    // 只有 2 个 worker threads，flush 卡顿会让其他 CDP/UIA 任务排队，
    // 严重时整个 runtime 卡死 → 系统卡死。
    recorder.runtime.spawn(async move {
        let mut flush_interval = interval(Duration::from_secs(5));

        while RECORDER_RUNNING.load(Ordering::SeqCst) && is_recording_enabled() {
            flush_interval.tick().await;

            // 锁内 clone Arc，锁外 spawn_blocking 把文件 I/O 隔离到 blocking pool
            let recorder_for_flush = {
                let guard = RECORDER.lock().unwrap_or_else(|p| p.into_inner());
                guard.clone()
            };
            if let Some(rec) = recorder_for_flush {
                // 文件 I/O 移到 blocking pool，worker thread 不被阻塞
                let _ = tokio::task::spawn_blocking(move || {
                    if let Err(e) = flush_recorder(&rec) {
                        eprintln!("[recorder] flush error: {}", e);
                    }
                })
                .await;
            }
        }
    });
}

/// 收集并存储动作
fn flush_and_save() {
    // 锁内 clone 出 Arc 并立即释放 RECORDER 锁。
    let recorder = {
        let guard = RECORDER.lock().unwrap_or_else(|p| p.into_inner());
        guard.clone()
    };
    if let Some(recorder) = recorder {
        if let Err(e) = flush_recorder(&recorder) {
            eprintln!("[recorder] flush error: {}", e);
        }
    }
}

/// 对指定 recorder 的缓冲区做序列化与文件写入。
/// 锁内只做 take 取出 Vec，立即释放 actions 锁；锁外做序列化和文件 I/O，
/// 避免持锁做文件 I/O 阻塞其他订阅任务写入缓冲区。
///
/// 保存失败时把去重后的动作回填到缓冲区 (extend 而非 replace, 因为 flush
/// 期间可能有新动作追加), 下次 flush 再试, 避免数据丢失。返回 Err 让调用方
/// 知道有批次失败 (数据已回填, 不会丢)。
fn flush_recorder(recorder: &Recorder) -> Result<(), String> {
    // 锁内仅 take 取出，立即释放锁
    let groups: Vec<(String, VecDeque<RecordedAction>)> = {
        let mut guard = recorder.actions.lock().unwrap_or_else(|e| e.into_inner());
        let mut out = Vec::new();
        for (app_name, actions) in guard.iter_mut() {
            if actions.is_empty() {
                continue;
            }
            let taken = std::mem::take(actions);
            out.push((app_name.clone(), taken));
        }
        out
    };

    // 锁外做序列化和文件写入
    let mut all_ok = true;
    for (app_name, actions_to_save) in groups {
        // VecDeque -> Vec（RecordingBatch::new 接受 Vec）
        let actions_vec: Vec<RecordedAction> = actions_to_save.into();
        let batch = RecordingBatch::new(&app_name, actions_vec);

        match store::save_batch(&batch) {
            Ok(_) => {
                println!(
                    "[recorder] saved {} actions for {} (raw: {}, dedup: {})",
                    batch.dedup_count,
                    app_name,
                    batch.raw_count,
                    batch.dedup_count
                );
            }
            Err(e) => {
                eprintln!("[recorder] save batch failed for {}: {}", app_name, e);
                let mut guard = recorder.actions.lock().unwrap_or_else(|p| p.into_inner());
                let buf = guard
                    .entry(app_name)
                    .or_default();
                for action in batch.actions {
                    buf.push_back(action);
                }
                all_ok = false;
            }
        }
    }
    if all_ok {
        Ok(())
    } else {
        Err("one or more recording batches failed to save".to_string())
    }
}

/// 停止录制
pub fn stop_recording() {
    RECORDER_RUNNING.store(false, Ordering::SeqCst);

    // 锁内 take 出 recorder，锁外做 flush。
    //
    // 注意:不再显式 `drop(recorder)`!
    // `Recorder` 内持有 multi-thread tokio runtime,在 async 上下文
    // (如 Tauri command handler, ExitRequested 回调等)中 drop runtime
    // 会 panic "Cannot drop a runtime in a context where blocking is not allowed",
    // 导致软件闪退。
    //
    // 复用 `flush_for_exit` 的安全策略:
    //   1. take 出 Arc(让 RECORDER 全局为 None,新调用 start_recording 可重建)
    //   2. 只 flush,不 drop
    //   3. 用 `Box::leak` 把 Arc 永久挂在堆上 — Arc 引用计数永远 ≥1,
    //      Recorder(含 runtime)永远不会被 drop,进程退出时 OS 回收。
    //      trade-off:leak 一个 Arc 的内存(几十字节),换取不 panic。
    let old = {
        let mut guard = RECORDER.lock().unwrap_or_else(|p| p.into_inner());
        guard.take()
    };

    if let Some(recorder) = old {
        recorder.stop_signal.store(true, Ordering::SeqCst);

        // 保存剩余动作
        if let Err(e) = flush_recorder(&recorder) {
            eprintln!("[recorder] final flush error: {}", e);
        }

        // 把 Arc leak 到堆上,避免 drop runtime panic。
        // Box::leak 返回 &'static,永不回收;Arc 的强引用永远 ≥1,Recorder 不会被 drop。
        let _leaked: &'static std::sync::Arc<Recorder> = Box::leak(Box::new(recorder));
    }
}

/// 应用退出时的安全 flush — 只把缓冲区数据落盘，不 drop runtime。
/// `stop_recording()` 中的 `drop(recorder)` 会 drop 多线程 tokio runtime，
/// 在 ExitRequested 回调上下文中会 panic "Cannot drop a runtime in a
/// context where blocking is not allowed"，导致软件闪退。
/// 这里只取 Arc 引用做 flush，不 take 不 drop，进程退出后 OS 自动回收。
pub fn flush_for_exit() {
    RECORDER_RUNNING.store(false, Ordering::SeqCst);

    // clone Arc 引用，不 take 出来（避免 drop）
    let recorder = {
        let guard = RECORDER.lock().unwrap_or_else(|p| p.into_inner());
        guard.clone()
    };

    if let Some(recorder) = recorder {
        recorder.stop_signal.store(true, Ordering::SeqCst);
        if let Err(e) = flush_recorder(&recorder) {
            eprintln!("[recorder] exit flush error: {}", e);
        }
        // 故意不 drop recorder — 避免 drop runtime 导致 panic
    }
}