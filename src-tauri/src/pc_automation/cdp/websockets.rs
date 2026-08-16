// Copyright (c) 2026 tupAI
//
// CDP backend — real implementation over the Chrome DevTools
// Protocol. Talks raw WebSocket to a Chromium-based browser
// exposing `--remote-debugging-port` (the same wire shape the
// Node sidecar `traeautocdp-server.cjs` already uses against
// Trae IDE on ports 9222-9230). Replaces the `not yet wired`
// stub that the v5 skeleton shipped with.
//
// Why raw WebSocket and not `chromiumoxide` (already in
// Cargo.lock)?
//   * `chromiumoxide` *launches* a browser, which conflicts
//     with the design constraint that the CDP tier is for
//     *attaching* to an already-running Electron / Chromium
//     surface (Trae IDE, the user's own Chrome window, ...).
//   * The Node sidecar is the production reference; the Rust
//     port should be wire-compatible so a future sidecar swap
//     is a no-op.
//   * The router trait is sync; `chromiumoxide` is async-first
//     and would force a `Handle::block_on` per call. Doing the
//     WS dance by hand keeps the hot path on a dedicated
//     single-threaded runtime that's a one-time setup cost.
//
// Architecture:
//   * `WebSocketCdpBackend` owns (a) an attached `target_id`,
//     (b) a single-shot `tokio::runtime::Runtime` used to drive
//     the async WS client, and (c) a `Mutex<CdpConnection>`
//     guard around the live stream. The runtime is built on
//     first use via `OnceLock` so command cold-start is free.
//   * `attach_or_launch` resolves the browser over the standard
//     CDP port list (9222-9230, +--headless / devtools) and
//     stashes the chosen target's `webSocketDebuggerUrl`.
//   * `send` blocks on the runtime to drive `connect ->
//     request -> response`, returning a `CdpResult` envelope.
//   * `detach` is a no-op for the lazy attach path; the next
//     `attach_or_launch` simply re-uses the live target.

use crate::pc_automation::cdp::backend::{CdpBackend, CdpResult};
use crate::pc_automation::cdp::types::{CdpAction, CdpMouseButton, CdpSelector};

use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

use futures::{SinkExt, StreamExt};
use serde::Deserialize;
use serde_json::{json, Value};
use tokio::runtime::Runtime;
use tokio_tungstenite::tungstenite::Message;

/// 复用的 WebSocket 流类型 — `connect_async` 的返回类型。
/// 在 `AttachedTarget::ws` 中缓存,避免每次 `round_trip` 都新建连接。
type WsStream = tokio_tungstenite::WebSocketStream<
    tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>,
>;

/// Standard CDP remote-debugging port range. Mirrors
/// `sidecar/traeautocdp-server.cjs` `CDP_PORTS` so the Rust
/// backend and the Node sidecar agree on which port the user's
/// running Trae IDE is exposing.
const CDP_PORTS: &[u16] = &[
    9222, 9223, 9224, 9225, 9226, 9227, 9228, 9229, 9230,
];

/// Cap any single CDP round-trip at 8s. The sidecar caps at 5s;
/// we leave a little more headroom for cold-start of the target
/// page (a freshly opened tab can take 6-7s to reply to its
/// first `Runtime.evaluate` on a slow CPU).
const CDP_REQUEST_TIMEOUT: Duration = Duration::from_secs(8);

/// One CDP target as returned by `GET /json/list`. Only the
/// fields we actually consume are deserialised; the rest flow
/// through as a JSON blob in `raw` so future callers can read
/// `webSocketDebuggerUrl` / `type` / `url` without us having to
/// extend this struct. Fields are `pub` so the monitor_status
/// route can read `id` / `web_socket_debugger_url` / `url`
/// without us having to expose per-field getters.
#[derive(Debug, Clone, Deserialize)]
pub struct CdpTarget {
    pub id: String,
    #[serde(rename = "type")]
    pub target_type: String,
    pub url: String,
    #[serde(rename = "webSocketDebuggerUrl")]
    pub web_socket_debugger_url: String,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub raw: Value,
}

pub struct WebSocketCdpBackend {
    inner: Mutex<Option<AttachedTarget>>,
}

struct AttachedTarget {
    target_id: String,
    ws_url: String,
    /// 复用的 WebSocket 连接。
    /// `round_trip` 取出使用,正常返回时放回;连接断开/出错时置 None,下次重建。
    /// 用 `std::sync::Mutex` 而非 `tokio::sync::Mutex` 因为锁仅在 take/put 时
    /// 短暂持有,不跨 await 点。
    ws: Mutex<Option<WsStream>>,
}

impl WebSocketCdpBackend {
    pub fn new() -> Self {
        Self {
            inner: Mutex::new(None),
        }
    }

    /// Lazily build a single-threaded tokio runtime on the
    /// calling thread. `OnceLock` keeps the cost off the hot
    /// path: the runtime is constructed the first time the
    /// router needs to bridge a sync `CdpBackend::send` call
    /// into the async WS client and re-used afterwards.
    fn runtime() -> &'static Runtime {
        static RT: OnceLock<Runtime> = OnceLock::new();
        RT.get_or_init(|| {
            tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .worker_threads(1)
                .thread_name("cdp-bridge")
                .build()
                .expect("cdp-bridge runtime build")
        })
    }

    /// Async core of `discover_target`. Returns *all* page-type
    /// targets visible on the standard port range. Public so the
    /// monitor_status HTTP handler can enumerate every Trace
    /// window (one per CDP target) without going through the
    /// trait's single-target `attach_or_launch` path. The sync
    /// wrapper below (`discover_target`) keeps the trait API
    /// unchanged.
    pub async fn list_all_page_targets_async() -> Result<Vec<CdpTarget>, String> {
        // 连 127.0.0.1 本地 CDP 端口；.no_proxy() 避免残留代理环境
        // 变量把回环请求也送进死代理（os error 10061）。
        // 双重 timeout：reqwest client timeout(500ms) + 外层 tokio::time::timeout(800ms)
        // 兜底。曾经出现过 reqwest 内部 connect 阶段挂起导致 9 端口扫描整体卡死，
        // 整个 monitor_status / cdp_eval_route 请求被拖住，主页搜索也受影响。
        let client = reqwest::Client::builder()
            .no_proxy()
            .connect_timeout(Duration::from_millis(300))
            .timeout(Duration::from_millis(500))
            .build()
            .map_err(|e| format!("reqwest build: {}", e))?;
        let mut out = Vec::new();
        for &port in CDP_PORTS {
            let url = format!("http://127.0.0.1:{}/json/list", port);
            // 外层 tokio::time::timeout 兜底，防止 reqwest 内部 connect 阶段挂起
            let resp = match tokio::time::timeout(
                Duration::from_millis(800),
                client.get(&url).send(),
            ).await {
                Ok(Ok(r)) => r,
                _ => continue,
            };
            if !resp.status().is_success() {
                continue;
            }
            let text = match resp.text().await {
                Ok(t) => t,
                Err(_) => continue,
            };
            let mut targets: Vec<CdpTarget> = match serde_json::from_str(&text) {
                Ok(v) => v,
                Err(_) => continue,
            };
            targets.retain(|t| t.target_type == "page");
            out.extend(targets);
        }
        if out.is_empty() {
            return Err("no CDP target found on ports 9222-9230".to_string());
        }
        Ok(out)
    }

    /// Probe `127.0.0.1:port/json/list` until a CDP target list
    /// comes back. The first port that responds wins, matching
    /// the sidecar's "active port sticks" caching behaviour.
    /// Uses the bridge runtime so the sync trait method can
    /// drive the async reqwest client without pulling in
    /// `reqwest::blocking` (and the extra `rustls` features it
    /// would force on the existing async `reqwest` dep).
    fn discover_target() -> Result<CdpTarget, String> {
        let rt = Self::runtime();
        rt.block_on(async {
            Self::list_all_page_targets_async()
                .await
                .and_then(|ts| {
                    ts.into_iter().next().ok_or_else(|| {
                        "no page target in /json/list".to_string()
                    })
                })
        })
    }

    /// Run a single `Runtime.evaluate` against an already-known
    /// target's `webSocketDebuggerUrl`. Used by the monitor
    /// route to peek at the live DOM (turn counts, last AI text,
    /// model name) without owning the backend's attached-target
    /// state. Returns the stringified `returnByValue` payload.
    pub async fn evaluate_on_target_async(
        ws_url: &str,
        js: &str,
    ) -> Result<String, String> {
        let (_success, return_value, error, _latency, _ws_back) = Self::round_trip(
            ws_url,
            "Runtime.evaluate",
            json!({
                "expression": js,
                "returnByValue": true,
                "awaitPromise": true,
            }),
            None,
            None,
        )
        .await?;
        if !_success {
            return Err(error.unwrap_or_else(|| "cdp: eval failed".to_string()));
        }
        Ok(return_value.unwrap_or_else(|| "null".to_string()))
    }

    /// Build a JS expression that resolves a `CdpSelector` to a
    /// single DOM element (or `null`). Mirrors the pattern the
    /// Node sidecar uses for Trae-window introspection so the
    /// recipes that work over the sidecar port straight to
    /// Rust.
    fn selector_to_js(sel: &CdpSelector) -> String {
        // The selector fields are all `Option<String>`; we emit
        // a guard at the top of the IIFE so an all-None selector
        // matches *any* element (i.e. the page itself). This
        // matches the existing `CdpSelector` parser behaviour.
        let page_url_glob = sel
            .page_url_glob
            .as_deref()
            .map(|g| {
                // 修复:之前用 `location.href.includes({})` 做 substring 匹配,
                // 但字段命名 `page_url_glob` 暗示支持 glob 通配符。
                // recipe 编写者会按 glob 语义写 `https://*.xueqiu.com/*`,
                // substring 永远不命中。改为真正的 glob 匹配:
                //   *  -> .*
                //   ?  -> .
                //   其他正则元字符转义。
                // 用 try/catch 兜底:正则编译失败时退化到 includes。
                //
                // 注:IIFE 返回 boolean(是否应跳过),外层 if 命中时 `return null`
                // 直接退出 selector_to_js 生成的外层函数。之前版本写 IIFE 内
                // `return null` 只从 IIFE 返回,返回值被丢弃,URL 守卫完全失效。
                let js_pattern = glob_to_regex_pattern(g);
                format!(
                    "if ((function() {{ try {{ return !(new RegExp({pattern})).test(location.href); }} catch(e) {{ return !location.href.includes({literal}); }} }})()) return null;",
                    pattern = js_str(&js_pattern),
                    literal = js_str(g),
                )
            })
            .unwrap_or_default();
        let css = sel.css.as_deref();
        let xpath = sel.xpath.as_deref();
        let text = sel.text.as_deref();

        let mut clauses: Vec<String> = Vec::new();
        if let Some(c) = css {
            // 优先级回退:用 `el = el || ...` 语义,css 失败才尝试 xpath/text。
            // 之前用 `el = ...` 无条件覆盖,xpath 返回 null 会覆盖 css 的成功结果。
            clauses.push(format!("el = el || document.querySelector({})", js_str(c)));
        }
        if let Some(x) = xpath {
            clauses.push(format!(
                "el = el || (function() {{ const r = document.evaluate({}, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null); return r.singleNodeValue; }})()",
                js_str(x)
            ));
        }
        if let Some(t) = text {
            // Match by visible text. We use a `TreeWalker` so the
            // search is depth-first and bounded; the
            // `closest('*')` trick promotes the deepest matching
            // text node up to its first element ancestor.
            clauses.push(format!(
                "el = el || (function() {{ const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT); let n; while ((n = walker.nextNode())) {{ if ((n.nodeValue || '').includes({})) {{ return n.parentElement; }} }} return null; }})()",
                js_str(t)
            ));
        }

        let body = if clauses.is_empty() {
            String::from("document.body")
        } else {
            clauses.join("; ")
        };

        format!(
            "(function() {{ {page} let el = null; {body}; return el; }})()",
            page = page_url_glob,
            body = body,
        )
    }

    /// Map the typed `CdpAction` enum onto a CDP `method` +
    /// `params` pair. Keeping the mapping here (rather than in
    /// the router) means a future action variant can be added
    /// without touching the WS plumbing.
    fn action_to_cdp(action: CdpAction) -> (String, Value) {
        match action {
            CdpAction::Navigate(url) => (
                "Page.navigate".to_string(),
                json!({ "url": url }),
            ),
            CdpAction::Click { sel, button } => {
                let expr = format!(
                    "(function() {{ const el = {resolve}; if (!el) return false; \
                     const r = el.getBoundingClientRect(); \
                     const opts = {{ bubbles: true, cancelable: true, \
                     clientX: r.left + r.width / 2, clientY: r.top + r.height / 2, \
                     button: {button_num} }}; \
                     el.dispatchEvent(new MouseEvent('mousedown', opts)); \
                     el.dispatchEvent(new MouseEvent('mouseup', opts)); \
                     el.dispatchEvent(new MouseEvent('click', opts)); \
                     return true; }})()",
                    resolve = Self::selector_to_js(&sel),
                    button_num = match button {
                        CdpMouseButton::Left => 0,
                        CdpMouseButton::Middle => 1,
                        CdpMouseButton::Right => 2,
                    },
                );
                (
                    "Runtime.evaluate".to_string(),
                    json!({
                        "expression": expr,
                        "returnByValue": true,
                        "awaitPromise": true,
                    }),
                )
            }
            CdpAction::Type { sel, text } => {
                // 修复:之前 `el.value = {text}` / `el.textContent = {text}` 直接赋值,
                // 覆盖输入框原有内容,违背 "Type = 键入文本(追加)" 语义。
                // 改为追加 + 触发 input/change 事件,React/Vue 等框架才能感知。
                //
                // 注1:`selectionStart ?? el.value.length` 而非 `||`:
                //     位置 0 在 `||` 下被当假值,光标在开头时文本会被错插到末尾。
                //     `??` 只在 null/undefined 时退化(<input type=email> 等类型
                //     的 selectionStart 为 null,此时退化是期望的)。
                // 注2:`text.encode_utf16().count()` 而非 `chars().count()`:
                //     JS 字符串索引是 UTF-16 码元,而 Rust `chars().count()` 数
                //     Unicode 标量值。emoji(如 😀)是 1 个标量值但 2 个 UTF-16 码元,
                //     用 chars().count() 会让 setSelectionRange 把光标放到代理对中间。
                let expr = format!(
                    "(function() {{ const el = {resolve}; \
                     if (!el || !el.isContentEditable && el.tagName !== 'INPUT' && el.tagName !== 'TEXTAREA') return false; \
                     el.focus(); \
                     if (el.isContentEditable) {{ \
                         const sel = window.getSelection(); \
                         sel && sel.selectAllChildren(el); \
                         sel && sel.collapseToEnd(); \
                         document.execCommand('insertText', false, {text}); \
                     }} else {{ \
                         const start = el.selectionStart ?? el.value.length; \
                         const end = el.selectionEnd ?? el.value.length; \
                         const before = el.value.slice(0, start); \
                         const after = el.value.slice(end); \
                         el.value = before + {text} + after; \
                         const pos = start + {text_len}; \
                         el.setSelectionRange(pos, pos); \
                     }} \
                     el.dispatchEvent(new InputEvent('input', {{ bubbles: true, inputType: 'insertText', data: {text} }})); \
                     el.dispatchEvent(new Event('change', {{ bubbles: true }})); \
                     return true; }})()",
                    resolve = Self::selector_to_js(&sel),
                    text = js_str(&text),
                    text_len = text.encode_utf16().count(),
                );
                (
                    "Runtime.evaluate".to_string(),
                    json!({
                        "expression": expr,
                        "returnByValue": true,
                        "awaitPromise": true,
                    }),
                )
            }
            CdpAction::Wait { sel, timeout_ms } => {
                // Poll for the element every 100ms until it
                // resolves or the per-action budget expires.
                //
                // 修复:之前 Wait 内部 JS 轮询最长可达 timeout_ms(常见 30s),
                // 但 round_trip 用 CDP_REQUEST_TIMEOUT=8s 强行截断,
                // 导致任何 timeout_ms > 8000 的 Wait 必然失败且错误信息 misleading。
                // 现在 round_trip 接受自定义超时,Wait 用 timeout_ms + 2s 余量。
                let expr = format!(
                    "(async function() {{ \
                     const deadline = Date.now() + {timeout}; \
                     while (Date.now() < deadline) {{ \
                     const el = {resolve}; \
                     if (el) return true; \
                     await new Promise(r => setTimeout(r, 100)); \
                     }} \
                     return false; \
                     }})()",
                    resolve = Self::selector_to_js(&sel),
                    timeout = timeout_ms,
                );
                (
                    "Runtime.evaluate".to_string(),
                    json!({
                        "expression": expr,
                        "returnByValue": true,
                        "awaitPromise": true,
                        // 用 timeout_ms + 2s 作为 CDP 通道超时,
                        // 让 JS 内部的轮询有足够时间完成。
                        // round_trip 会优先读取此字段,缺省时退化到 CDP_REQUEST_TIMEOUT。
                        "__round_trip_timeout_ms": timeout_ms.saturating_add(2_000),
                    }),
                )
            }
            CdpAction::Evaluate(expr) => (
                "Runtime.evaluate".to_string(),
                json!({
                    "expression": expr,
                    "returnByValue": true,
                    "awaitPromise": true,
                }),
            ),
        }
    }

    /// Drive one round-trip on the live WS connection. Returns
    /// the `result` field of the matching response, or an error
    /// if the connection fails / times out. The `id` is unique
    /// per process invocation (monotonic clock + atomic
    /// counter) to keep us safe against any sidecar that
    /// multiplexes over the same target.
    ///
    /// 连接复用:`ws_in` 为 Some 时直接复用,避免 `connect_async` 的开销;
    /// 为 None 时新建。返回时,正常完成会把连接作为 `Some` 返回供下次复用,
    /// 出错时连接已 close 并 drop,返回 `None`。
    async fn round_trip(
        ws_url: &str,
        method: &str,
        params: Value,
        custom_timeout: Option<Duration>,
        mut ws_in: Option<WsStream>,
    ) -> Result<(bool, Option<String>, Option<String>, u128, Option<WsStream>), String> {
        let id = next_msg_id();
        if ws_in.is_none() {
            // connect_async 缺外层 timeout 时,TCP 已连但 HTTP upgrade 永不返回会永久挂起,
            // 拖住整个 CDP 链路。这里加 3s 外层 timeout 兜底。
            let (new_ws, _resp) = tokio::time::timeout(
                Duration::from_secs(3),
                tokio_tungstenite::connect_async(ws_url),
            )
            .await
            .map_err(|_| format!("connect {} timeout (3s)", ws_url))?
            .map_err(|e| format!("connect {}: {}", ws_url, e))?;
            ws_in = Some(new_ws);
        }
        // 安全:上面已确保 ws_in 为 Some
        let stream = ws_in.as_mut().expect("ws_in guaranteed Some");
        let payload = json!({ "id": id, "method": method, "params": params });
        stream.send(Message::Text(payload.to_string()))
            .await
            .map_err(|e| format!("ws send: {}", e))?;

        let started = Instant::now();
        // 允许调用方覆盖默认 8s 超时(Wait 动作可能需要 30s+)。
        let timeout = custom_timeout.unwrap_or(CDP_REQUEST_TIMEOUT);
        let deadline = started + timeout;
        // 内部 loop 把结果收集出来,外层根据成功/失败决定是否把 ws 放回。
        let inner_result: Result<(bool, Option<String>, Option<String>, u128), String> = async {
            loop {
                let remaining = deadline.saturating_duration_since(Instant::now());
                if remaining.is_zero() {
                    return Err(format!("cdp: timeout waiting for id={}", id));
                }
                let frame = tokio::time::timeout(remaining, stream.next())
                    .await
                    .map_err(|_| format!("cdp: timeout waiting for id={}", id))?;
                let frame = match frame {
                    Some(Ok(Message::Text(t))) => t,
                    Some(Ok(Message::Binary(b))) => {
                        String::from_utf8(b).map_err(|e| format!("ws utf8: {}", e))?
                    }
                    Some(Ok(Message::Close(_))) | None => {
                        return Err(format!("cdp: socket closed before id={}", id));
                    }
                    Some(Ok(_)) => continue,
                    Some(Err(e)) => return Err(format!("ws recv: {}", e)),
                };
                let resp: Value = serde_json::from_str(&frame)
                    .map_err(|e| format!("ws parse: {}", e))?;
                if resp.get("id").and_then(|v| v.as_i64()) == Some(id as i64) {
                    let latency = started.elapsed().as_millis();
                    if let Some(err) = resp.get("error") {
                        return Ok((false, None, Some(err.to_string()), latency));
                    }
                    let result = resp
                        .get("result")
                        .cloned()
                        .unwrap_or(Value::Null);
                    let return_value = result
                        .get("result")
                        .and_then(|r| r.get("value"))
                        .map(|v| v.to_string());
                    return Ok((true, return_value, None, latency));
                }
                // Not our message — keep draining.
            }
        }
        .await;
        match inner_result {
            Ok((success, return_value, error, latency_ms)) => {
                // 成功 — 不 close,把连接放回供下次复用
                Ok((success, return_value, error, latency_ms, ws_in))
            }
            Err(e) => {
                // 出错 — close 并丢弃连接,下次重建
                if let Some(mut s) = ws_in {
                    let _ = s.close(None).await;
                }
                Err(e)
            }
        }
    }
}

impl Default for WebSocketCdpBackend {
    fn default() -> Self {
        Self::new()
    }
}

impl CdpBackend for WebSocketCdpBackend {
    fn attach_or_launch(&self, _url: Option<&str>) -> Result<String, String> {
        // We never *launch* a browser here — the CDP tier is for
        // attaching to an already-running Chromium surface
        // (Trae, the user's own Chrome, ...). The `url`
        // parameter is accepted for trait symmetry but ignored;
        // a future "find a target whose `url` matches this glob"
        // enhancement can plug in here.
        let target = Self::discover_target()?;
        let mut guard = self
            .inner
            .lock()
            .map_err(|e| format!("inner lock poisoned: {}", e))?;
        // 同一 target — 复用现有 ws 连接,避免每次 attach 都新建。
        // 这是 WebSocket 连接复用 的关键:attach_or_launch 是 hot path,
        // 每个步骤都会调一次(见 router.rs::try_cdp),若每次都重建
        // AttachedTarget 会丢弃 ws 缓存。
        if let Some(existing) = guard.as_ref() {
            if existing.target_id == target.id
                && existing.ws_url == target.web_socket_debugger_url
            {
                return Ok(target.id);
            }
        }
        *guard = Some(AttachedTarget {
            target_id: target.id.clone(),
            ws_url: target.web_socket_debugger_url,
            ws: Mutex::new(None),
        });
        Ok(target.id)
    }

    fn send(&self, action: CdpAction) -> Result<CdpResult, String> {
        // Build the CDP method/params from the typed action, then
        // drive the round-trip on the dedicated bridge runtime.
        // The `Mutex<Option<...>>` round-trip is the only piece
        // of state we own; it's held only for the duration of
        // the read so concurrent commands from the executor
        // don't fight over the same target.
        let (method, mut params) = Self::action_to_cdp(action);
        // action_to_cdp 可能在 params 中塞入 `__round_trip_timeout_ms`
        // 用于覆盖默认 8s 超时(例如 Wait 动作可能轮询 30s)。
        // 提取出来传给 round_trip,并从 params 中移除,避免污染 CDP 协议。
        let custom_timeout = params
            .get("__round_trip_timeout_ms")
            .and_then(|v| v.as_u64())
            .map(Duration::from_millis);
        if custom_timeout.is_some() {
            if let Some(obj) = params.as_object_mut() {
                obj.remove("__round_trip_timeout_ms");
            }
        }
        // 取出 ws_url 和缓存的 ws 连接(若存在),立即释放 inner 锁。
        // 注意:ws 锁只在这里和 attach_or_launch 中短暂持有,不跨 await 点,
        // 因此用 std::sync::Mutex 即可。
        let (ws_url, ws_in) = {
            let guard = self
                .inner
                .lock()
                .map_err(|e| format!("inner lock poisoned: {}", e))?;
            let attached = guard
                .as_ref()
                .ok_or_else(|| "cdp: not attached — call attach_or_launch first".to_string())?;
            let ws = attached
                .ws
                .lock()
                .map_err(|e| format!("ws lock poisoned: {}", e))?
                .take();
            (attached.ws_url.clone(), ws)
        };
        let rt = Self::runtime();
        let round_trip_result = rt.block_on(Self::round_trip(
            &ws_url, &method, params, custom_timeout, ws_in,
        ));
        // 不论成功失败,都要把 ws 状态写回(成功时为 Some 复用,失败时为 None 重建)。
        // 锁中毒时静默忽略 — 上层会拿到 round_trip 的错误,连接丢失不影响下次 attach。
        let (success, return_value, error, latency_ms) = match round_trip_result {
            Ok((success, return_value, error, latency_ms, ws_out)) => {
                if let Ok(guard) = self.inner.lock() {
                    if let Some(attached) = guard.as_ref() {
                        if let Ok(mut ws_guard) = attached.ws.lock() {
                            *ws_guard = ws_out;
                        }
                    }
                }
                (success, return_value, error, latency_ms)
            }
            Err(e) => {
                // round_trip 出错时连接已被 close+drop,显式置 None 供下次重建。
                if let Ok(guard) = self.inner.lock() {
                    if let Some(attached) = guard.as_ref() {
                        if let Ok(mut ws_guard) = attached.ws.lock() {
                            *ws_guard = None;
                        }
                    }
                }
                return Err(e);
            }
        };
        Ok(CdpResult {
            success,
            return_value,
            error,
            latency_ms: latency_ms as u64,
        })
    }

    fn detach(&self) -> Result<(), String> {
        let mut guard = self
            .inner
            .lock()
            .map_err(|e| format!("inner lock poisoned: {}", e))?;
        *guard = None;
        Ok(())
    }
}

/// Encode a Rust string as a JS string literal (double-quoted,
/// JSON-style escapes). Used by `selector_to_js` / `action_to_cdp`
/// to avoid building a JS expression by hand and risk a syntax
/// error from an unescaped quote in a user-supplied selector.
fn js_str(s: &str) -> String {
    serde_json::to_string(s).unwrap_or_else(|_| "\"\"".to_string())
}

/// Convert a glob pattern into a JS-compatible regex source string.
///   `*`  -> `.*`
///   `?`  -> `.`
///   其他正则元字符(`\`, `+`, `(`, `)`, `[`, `]`, `{`, `}`, `.`, `^`, `$`,
///   `|`, 等)被转义。
/// 结果是 JS `new RegExp(source)` 可直接消费的字符串。
fn glob_to_regex_pattern(glob: &str) -> String {
    let mut out = String::with_capacity(glob.len() * 2);
    for c in glob.chars() {
        match c {
            '*' => out.push_str(".*"),
            '?' => out.push('.'),
            // 转义所有正则元字符
            '\\' | '+' | '(' | ')' | '[' | ']' | '{' | '}' | '.' | '^' | '$' | '|' => {
                out.push('\\');
                out.push(c);
            }
            _ => out.push(c),
        }
    }
    // 用 ^ $ 锚定整串,避免 `*.x.com/*` 误匹配 `xyz.x.com.evil`。
    // 但只在用户没显式加锚的情况下加。
    if !out.starts_with('^') {
        out = format!("^{}", out);
    }
    if !out.ends_with('$') {
        out = format!("{}$", out);
    }
    out
}

/// Monotonic per-process message id. CDP responses are matched
/// on `id`; a 64-bit counter + process-unique prefix keeps us
/// safe even if a future bridge spawns extra workers.
fn next_msg_id() -> u64 {
    static COUNTER: OnceLock<Mutex<u64>> = OnceLock::new();
    let mut c = COUNTER
        .get_or_init(|| Mutex::new(0))
        .lock()
        .expect("cdp id counter");
    *c = c.wrapping_add(1).max(1);
    *c
}
