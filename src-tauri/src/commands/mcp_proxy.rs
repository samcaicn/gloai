// Copyright (c) 2026 Trace Auto
//
// MCP v2 + API proxy commands. The webview used to call
// `https://api.tuptup.top/api/v2/mcp` and `https://api.tuptup.top/api/v1/...`
// directly with `fetch()`, which works in principle but is
// fragile:
//   * WebView2 blocks mixed content (HTTP page → HTTPS request)
//     on some Windows builds, surfacing a generic "Failed to
//     fetch".
//   * The Cloudflare edge in front of `api.tuptup.top` has been
//     returning `tlsv1 alert internal error` for some users
//     (their OpenSSL / Chromium TLS stack disagrees with the
//     server's offered cipher list). Even `reqwest` (rustls)
//     surfaces the same handshake failure.
//
// Routing through these Tauri commands gives us a single
// place to:
//   * Add a bounded retry loop (1 retry on transient connect
//     errors, no retry on 4xx/5xx).
//   * Convert the upstream error into a stable JSON shape the
//     front-end can branch on (`{ code, message, upstream, ... }`)
//     instead of the opaque "Failed to fetch" string.
//   * Stamp the device token (when present) without forcing the
//     webview to reach into `localStorage` for it.
//
// When the upstream TLS bug clears, these commands Just Work.
// While the bug is active, the user sees "上游 MCP 服务连接
// 失败" with a useful HTTP/TLS detail, not the misleading
// "网络请求失败，请检查网络连接或账号状态" string.

use reqwest::Client;
use serde::{Deserialize, Serialize};
use std::time::Duration;
use uuid;
// 引入 std::error::Error trait 让 `reqwest::Error::source()` 可调用（UFCS 不需要 trait import，但 method 调用语法需要）。
// `as _` 让 trait 名不进入 scope，避免与文件内其他 Error 类型冲突。
use std::error::Error as _;

const UPSTREAM_BASE: &str = "https://ai.tuptup.top";
const CONNECT_TIMEOUT_SECS: u64 = 12;
const REQUEST_TIMEOUT_SECS: u64 = 20;
/// LLM 调用需要更长的超时（流式 / 大模型生成可能 60-120s）
const LLM_REQUEST_TIMEOUT_SECS: u64 = 120;
/// One retry is enough — three is just amplifies the cost of a
/// genuinely-broken upstream. We only retry on transport errors
/// (connect failure, TLS alert, RST). HTTP 4xx/5xx pass through
/// immediately so the webview's status-code branch still fires.
const MAX_ATTEMPTS: u32 = 2;

fn build_client() -> Result<Client, String> {
    // ai.tuptup.top 是境内 IP，强制直连：用户机器可能设置了 Clash
    // 代理环境变量但代理软件未运行，导致 os error 10061 连接被拒。
    Client::builder()
        .no_proxy()
        .connect_timeout(Duration::from_secs(CONNECT_TIMEOUT_SECS))
        .timeout(Duration::from_secs(REQUEST_TIMEOUT_SECS))
        .user_agent(concat!("TraceAuto-Tauri/", env!("CARGO_PKG_VERSION")))
        .build()
        .map_err(|e| format!("reqwest client build failed: {e}"))
}

fn build_client_with_timeout(timeout_secs: u64) -> Result<Client, String> {
    Client::builder()
        .no_proxy()
        .connect_timeout(Duration::from_secs(CONNECT_TIMEOUT_SECS))
        .timeout(Duration::from_secs(timeout_secs))
        .user_agent(concat!("TraceAuto-Tauri/", env!("CARGO_PKG_VERSION")))
        .build()
        .map_err(|e| format!("reqwest client build failed: {e}"))
}

/// Front-end-friendly error envelope. `code` is a stable string
/// the webview can switch on; `message` is the human-readable
/// detail; `upstream` is included on connect/TLS failures so the
/// user can see which endpoint the failure came from.
#[derive(Serialize)]
struct ProxyError {
    code: &'static str,
    message: String,
    upstream: String,
}

fn classify(reqwest_err: &reqwest::Error) -> &'static str {
    if reqwest_err.is_timeout() {
        "upstream_timeout"
    } else if reqwest_err.is_connect() {
        "upstream_connect_failed"
    } else if reqwest_err.is_request() {
        "upstream_request_failed"
    } else {
        // reqwest::Error 的 to_string() 默认丢失底层原因（TLS
        // handshake / certificate 错误的真正描述藏在 source() 链
        // 里）。遍历整条链，命中 tls/handshake/certificate 字样就
        // 归到 connect_failed —— 这类错误的用户处置方式和 connect
        // 失败一致（检查代理 / 证书 / 网络），而不是泛泛的
        // "upstream_error"。
        let mut source: Option<&dyn std::error::Error> = reqwest_err.source();
        while let Some(s) = source {
            let msg = s.to_string().to_lowercase();
            if msg.contains("tls")
                || msg.contains("handshake")
                || msg.contains("certificate")
                || msg.contains("cert")
            {
                return "upstream_connect_failed";
            }
            source = s.source();
        }
        "upstream_error"
    }
}

/// 遍历 `std::error::Error::source()` 链，把每一层的原因拼成一条
/// 完整字符串。`reqwest::Error` 的默认 `to_string()` 只展示最外层
/// ("error sending request")，真正的 os error 10061 / TLS alert
/// description 在 source 链里，不遍历就丢给用户一个无信息的字符串。
fn format_reqwest_error(err: &reqwest::Error) -> String {
    let mut parts: Vec<String> = vec![err.to_string()];
    let mut source: Option<&dyn std::error::Error> = err.source();
    while let Some(s) = source {
        parts.push(s.to_string());
        source = s.source();
    }
    parts.join(" · caused by: ")
}

fn log_and_return_err(label: &str, upstream: &str, err: reqwest::Error) -> String {
    let code = classify(&err);
    let detail = format_reqwest_error(&err);
    let payload = ProxyError {
        code,
        message: format!("{label} {upstream} failed: {detail}"),
        upstream: upstream.to_string(),
    };
    // Serialise as a JSON string so the webview can `JSON.parse` the
    // thrown Error.message and branch on `code`.
    match serde_json::to_string(&payload) {
        Ok(s) => s,
        Err(_) => format!(
            "{{\"code\":\"serialise_failed\",\"message\":\"{label} {upstream} failed: {detail}\",\"upstream\":\"{upstream}\"}}"
        ),
    }
}

/// Parse an SSE (`text/event-stream`) body and concatenate all
/// `delta.content` fragments into a single string. Supports the
/// payload shapes the upstream may emit:
///
/// 1. OpenAI Chat Completions:
///    `data: {"choices":[{"delta":{"content":"hi"}}]}`
/// 2. OpenAI Responses API:
///    `data: {"type":"response.output_text.delta","delta":"hi"}`
/// 3. Anthropic Messages:
///    `data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"hi"}}`
/// 4. Plain text after `data: ` (no JSON)
///
/// The `[DONE]` sentinel terminates the stream. Anything that fails
/// to parse as JSON but isn't `[DONE]` is treated as plain text and
/// appended verbatim — this keeps us resilient to upstream format
/// drift instead of returning an empty string.
fn parse_sse_content(body: &str) -> String {
    let mut out = String::new();
    for raw_line in body.split('\n') {
        let line = raw_line.trim_end_matches('\r');
        // SSE frames start with `data:`; everything else (event:,
        // id:, comments, blank lines) is ignored.
        let data = if let Some(rest) = line.strip_prefix("data:") {
            rest.trim_start()
        } else {
            continue;
        };
        if data.is_empty() || data == "[DONE]" {
            continue;
        }
        // Try JSON first — covers OpenAI Chat + Responses + Anthropic.
        if let Ok(v) = serde_json::from_str::<serde_json::Value>(data) {
            // OpenAI Chat Completions: choices[0].delta.content
            if let Some(content) = v
                .get("choices")
                .and_then(|c| c.get(0))
                .and_then(|c| c.get("delta"))
                .and_then(|d| d.get("content"))
                .and_then(|c| c.as_str())
            {
                out.push_str(content);
                continue;
            }
            // OpenAI Chat Completions (some servers): choices[0].delta.content as object
            // (rare but seen with multi-modal)
            // Anthropic: delta.text (content_block_delta / text_delta)
            if let Some(text) = v
                .get("delta")
                .and_then(|d| d.get("text"))
                .and_then(|c| c.as_str())
            {
                out.push_str(text);
                continue;
            }
            // Anthropic variant: delta.content (some bridges)
            if let Some(content) = v
                .get("delta")
                .and_then(|d| d.get("content"))
                .and_then(|c| c.as_str())
            {
                out.push_str(content);
                continue;
            }
            // OpenAI Responses API: { type: "response.output_text.delta", delta: "..." }
            // `delta` as bare string.
            if let Some(delta) = v.get("delta").and_then(|d| d.as_str()) {
                out.push_str(delta);
                continue;
            }
            // Some servers put content at the top level.
            if let Some(content) = v.get("content").and_then(|c| c.as_str()) {
                out.push_str(content);
                continue;
            }
            // Anthropic `message_stop` / OpenAI `message.done` carry
            // no payload — skip silently.
            // Also skip unrecognised JSON frames to keep raw JSON
            // out of the chat output.
            continue;
        }
        // Not JSON — append as plain text (e.g. legacy `data: hello`).
        out.push_str(data);
    }
    out
}

/// 累积 OpenAI Chat Completions 流式 `tool_calls` 增量，返回完整的 tool_calls。
///
/// SSE 流里 tool_calls 按 index 分片推送，形如:
///   data: {"choices":[{"delta":{"tool_calls":[
///           {"index":0,"id":"call_abc","type":"function","function":{"name":"foo","arguments":""}}]}}]}
///   data: {"choices":[{"delta":{"tool_calls":[
///           {"index":0,"function":{"arguments":"{\"a\":"}}]}}]}
///
/// `id` / `type` / `function.name` 只出现在首片，`function.arguments` 跨片拼接。返回
/// `crate::hermes::types::VLMToolCall`（`{ id, type: "function", function: { name, arguments } }`）。
/// 供 AgentLoop / auto_reply 的 ReAct 循环消费。
fn parse_sse_tool_calls(body: &str) -> Vec<crate::hermes::types::VLMToolCall> {
    use std::collections::BTreeMap;
    use crate::hermes::types::{VLMToolCall, VLMToolFunction};

    // index → (id, type, function_name, arguments_accumulated)
    let mut acc: BTreeMap<u64, (Option<String>, Option<String>, Option<String>, String)> =
        BTreeMap::new();

    for raw_line in body.split('\n') {
        let line = raw_line.trim_end_matches('\r');
        let data = line.strip_prefix("data:").map(str::trim_start);
        let Some(data) = data else { continue };
        if data.is_empty() || data == "[DONE]" {
            continue;
        }
        let Ok(v) = serde_json::from_str::<serde_json::Value>(data) else {
            continue;
        };
        // OpenAI Chat Completions: choices[0].delta.tool_calls
        let Some(tcs) = v
            .get("choices")
            .and_then(|c| c.get(0))
            .and_then(|c| c.get("delta"))
            .and_then(|d| d.get("tool_calls"))
            .and_then(|t| t.as_array())
        else {
            continue;
        };
        for tc in tcs {
            let Some(index) = tc.get("index").and_then(|i| i.as_u64()) else { continue };
            let entry = acc.entry(index).or_insert_with(|| (None, None, None, String::new()));
            if entry.0.is_none() {
                entry.0 = tc.get("id").and_then(|i| i.as_str()).map(str::to_string);
            }
            if entry.1.is_none() {
                entry.1 = tc.get("type").and_then(|t| t.as_str()).map(str::to_string);
            }
            if entry.2.is_none() {
                entry.2 = tc
                    .get("function")
                    .and_then(|f| f.get("name"))
                    .and_then(|n| n.as_str())
                    .map(str::to_string);
            }
            if let Some(args) = tc
                .get("function")
                .and_then(|f| f.get("arguments"))
                .and_then(|a| a.as_str())
            {
                entry.3.push_str(args);
            }
        }
    }

    acc.into_iter()
        .filter_map(|(_, (id, ty, name, arguments))| {
            let id = id?;
            let kind = ty.unwrap_or_else(|| "function".to_string());
            let name = name.unwrap_or_default();
            if name.is_empty() {
                return None;
            }
            Some(VLMToolCall {
                id,
                kind,
                function: VLMToolFunction {
                    name,
                    arguments,
                },
            })
        })
        .collect()
}

// ─────────────────────────────────────────────────────────────────
// Commands
// ─────────────────────────────────────────────────────────────────

/// 内部可复用入口：直接对一个已构建好的 `reqwest::Client` 发起 MCP v2 调用。
///
/// 把 `mcp_call_v2` 命令的 HTTP 调用体抽到这里，是因为 `hermes::cron_local`
/// 等其它后端模块也需要经 MCP `llm.stream_request` 调 LLM（服务器自动匹配
/// 模型，不需要客户端配置 provider/api_key）。抽出后它们复用同一套
/// 重试 / SSE 解析 / 错误分类逻辑，不必各写一遍。
///
/// 注意：`action` / `params` / `token` 与 `mcp_call_v2` 命令同义；客户端
/// 的超时已体现在传入的 `client` 上（见 `build_client` / `build_client_with_timeout`）。
pub(crate) async fn mcp_call_v2_inner(
    client: &Client,
    action: &str,
    params: serde_json::Value,
    token: Option<&str>,
) -> Result<serde_json::Value, String> {
    let upstream = format!("{UPSTREAM_BASE}/api/v2/mcp");
    let url = upstream.clone();

    let body = serde_json::json!({ "action": action, "params": params });

    let mut last_err: Option<String> = None;
    for attempt in 1..=MAX_ATTEMPTS {
        let mut req = client
            .post(&url)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("x-claw-timestamp", std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs().to_string())
            .header("x-claw-nonce", uuid::Uuid::new_v4().to_string())
            .json(&body);

        // 添加 Authorization header
        if let Some(t) = token.filter(|s| !s.is_empty()) {
            req = req.bearer_auth(t);
        }

        let res = req.send().await;
        match res {
            Ok(resp) => {
                let status = resp.status();
                // Capture Content-Type before consuming the body —
                // `llm.stream_request` 返回 SSE (`text/event-stream`),
                // 不能当 JSON 解析。
                let content_type = resp
                    .headers()
                    .get(reqwest::header::CONTENT_TYPE)
                    .and_then(|v| v.to_str().ok())
                    .unwrap_or("")
                    .to_lowercase();
                let text = resp.text().await.unwrap_or_default();
                if !status.is_success() {
                    let body_preview: String = text.chars().take(400).collect();
                    let body_json = serde_json::to_string(&body_preview).unwrap_or_else(|_| "\"<serialize failed>\"".to_string());
                    let msg = serde_json::to_string(&format!("MCP {} returned HTTP {}", action, status)).unwrap_or_else(|_| "\"<error>\"".to_string());
                    let url_json = serde_json::to_string(&url).unwrap_or_else(|_| "\"<error>\"".to_string());
                    return Err(format!(
                        "{{\"code\":\"upstream_http_error\",\"message\":{},\"upstream\":{},\"upstream_body\":{}}}",
                        msg, url_json, body_json
                    ));
                }
                // 服务器只支持 SSE：llm.stream_request 返回
                // `text/event-stream`（甚至 `text/plain` 包着 SSE 帧）。
                // 解析 SSE 帧并把 delta.content 拼成完整文本，再包装成
                // `{ ok: true, data: { content: "..." } }` 让前端
                // mcpClient.llmStreamChat 走原有 text/plain 逐字输出路径。
                let is_sse = content_type.contains("text/event-stream")
                    || content_type.contains("text/plain")
                        && action == "llm.stream_request"
                        && text.contains("\ndata:");
                if is_sse {
                    let content = parse_sse_content(&text);
                    let tool_calls = if action == "llm.stream_request" {
                        let tcs = parse_sse_tool_calls(&text);
                        if tcs.is_empty() {
                            None
                        } else {
                            Some(serde_json::to_value(tcs).unwrap_or_default())
                        }
                    } else {
                        None
                    };
                    if content.is_empty() && tool_calls.is_none() {
                        // 服务器返回了 SSE 但没解析到任何内容——这通常是
                        // 上游 LLM 出错（auth / 限流 / 模型未配置）或者
                        // SSE 帧格式我们不认识。把原始响应记到日志，方便
                        // 排查；同时通过错误通道把摘要暴露给前端，避免
                        // 用户只看到空 `{"content":""}` 不知道为什么。
                        log::warn!(
                            "MCP llm.stream_request: SSE 响应解析为空内容, \
                             content_type={}, body_len={}, body_preview={:?}",
                            content_type,
                            text.len(),
                            text.chars().take(800).collect::<String>()
                        );
                        let preview: String = text.chars().take(300).collect();
                        let preview_json = serde_json::to_string(&preview)
                            .unwrap_or_else(|_| "\"<serialize failed>\"".to_string());
                        return Err(format!(
                            "{{\"code\":\"upstream_empty_sse\",\
                             \"message\":\"LLM 返回空内容（SSE 无 delta 帧），\
                             可能是上游鉴权失败 / 限流 / 模型未配置。\
                             原始响应前 300 字符: {}\",\
                             \"upstream\":\"{}\",\"content_type\":\"{}\"}}",
                            preview_json, url, content_type
                        ));
                    }
                    log::debug!(
                        "MCP llm.stream_request: SSE 解析成功, content_len={}, tool_calls={}",
                        content.len(),
                        tool_calls.as_ref().map(|t| t.as_array().map(|a| a.len()).unwrap_or(0)).unwrap_or(0)
                    );
                    let mut data = serde_json::json!({ "content": content });
                    if let Some(tcs) = tool_calls {
                        data["tool_calls"] = tcs;
                    }
                    return Ok(serde_json::json!({
                        "ok": true,
                        "data": data
                    }));
                }
                // Standard MCP response shape:
                // `{ ok: true, data: ... }` or `{ ok: false, error: ... }`.
                // Pass the whole object through; `mcpCall` decides what
                // to do with it.
                let parsed: serde_json::Value = match serde_json::from_str(&text) {
                    Ok(v) => v,
                    Err(e) => {
                        return Err(format!(
                            "{{\"code\":\"upstream_parse_error\",\"message\":\"MCP {action} returned non-JSON body: {e}\",\"upstream\":\"{}\"}}",
                            url
                        ));
                    }
                };
                return Ok(parsed);
            }
            Err(e) => {
                let msg = log_and_return_err("MCP", &url, e);
                last_err = Some(msg);
                if attempt < MAX_ATTEMPTS {
                    // 200ms backoff — Cloudflare edge glitches
                    // clear in a few hundred ms, no point waiting
                    // longer.
                    tokio::time::sleep(Duration::from_millis(200)).await;
                }
            }
        }
    }
    Err(last_err.unwrap_or_else(|| {
        "{\"code\":\"upstream_unknown_error\",\"message\":\"MCP call failed after retries\",\"upstream\":\"".to_string() + &url + "\"}"
    }))
}

/// POST `https://ai.tuptup.top/api/v2/mcp` with the JSON-RPC-ish
/// body the front-end sends. Authentication via `Authorization: Bearer <token>`
/// header (device_token from localStorage).
///
/// `timeout_secs`: 可选超时（秒）。LLM 流式调用需要更长超时
/// （默认 20s 不够），前端 llmStreamChat 传 timeoutMs → timeoutSecs。
#[tauri::command]
pub async fn mcp_call_v2(
    action: String,
    params: serde_json::Value,
    timeout_secs: Option<u64>,
    token: Option<String>,
) -> Result<serde_json::Value, String> {
    // Dev-mode: token 为 "dev-token-mock" 时返回 mock 响应
    #[cfg(debug_assertions)]
    if token.as_deref() == Some("dev-token-mock") {
        return mcp_call_v2_mock(&action, &params).await;
    }

    let client = match timeout_secs {
        Some(secs) => build_client_with_timeout(secs)?,
        None => build_client()?,
    };
    mcp_call_v2_inner(&client, &action, params, token.as_deref()).await
}

#[cfg(debug_assertions)]
async fn mcp_call_v2_mock(action: &str, params: &serde_json::Value) -> Result<serde_json::Value, String> {
    log::info!("[dev-mode] mcp_call_v2 mocked: action={}", action);
    match action {
        "llm.stream_request" => {
            // 模拟 SSE 流式响应：直接返回拼接后的完整文本
            let prompt = params.get("messages")
                .and_then(|m| m.as_array())
                .and_then(|a| a.last())
                .and_then(|m| m.get("content"))
                .and_then(|c| c.as_str())
                .unwrap_or("");
            Ok(serde_json::json!({
                "ok": true,
                "data": {
                    "content": format!("[dev-mode] 模拟 LLM 回复：收到提示词 \"{}\"", prompt)
                }
            }))
        }
        "skill.scene_tags" | "skill.top_by_tags" | "skill.search" => {
            Ok(serde_json::json!({ "ok": true, "data": { "skills": [] } }))
        }
        "task.poll_pending" | "task.complete" | "client.check_update" => {
            Ok(serde_json::json!({ "ok": true, "data": {} }))
        }
        _ => Ok(serde_json::json!({ "ok": true, "data": {} })),
    }
}

#[derive(Deserialize)]
pub struct ApiGetArgs {
    pub path: String,
    pub token: Option<String>,
}

#[tauri::command]
pub async fn mcp_api_get(args: ApiGetArgs) -> Result<serde_json::Value, String> {
    #[cfg(debug_assertions)]
    if args.token.as_deref() == Some("dev-token-mock") {
        return mcp_api_get_mock(&args.path).await;
    }

    let url = format!("{UPSTREAM_BASE}{}", args.path);
    let client = build_client()?;
    let mut last_err: Option<String> = None;
    for attempt in 1..=MAX_ATTEMPTS {
        let mut req = client.get(&url).header("Accept", "application/json");
        if let Some(t) = args.token.as_deref().filter(|s| !s.is_empty()) {
            req = req.bearer_auth(t);
        }
        match req.send().await {
            Ok(resp) => {
                let status = resp.status();
                let text = resp.text().await.unwrap_or_default();
                if !status.is_success() {
                    let body_preview: String = text.chars().take(400).collect();
                    let body_json = serde_json::to_string(&body_preview).unwrap_or_else(|_| "\"<serialize failed>\"".to_string());
                    let msg = serde_json::to_string(&format!("API GET {} returned HTTP {}", args.path, status)).unwrap_or_else(|_| "\"<error>\"".to_string());
                    let url_json = serde_json::to_string(&url).unwrap_or_else(|_| "\"<error>\"".to_string());
                    return Err(format!(
                        "{{\"code\":\"upstream_http_error\",\"message\":{},\"upstream\":{},\"upstream_body\":{}}}",
                        msg, url_json, body_json
                    ));
                }
                return serde_json::from_str(&text).map_err(|e| {
                    format!(
                        "{{\"code\":\"upstream_parse_error\",\"message\":\"API GET {} returned non-JSON: {e}\",\"upstream\":\"{}\"}}",
                        args.path, url
                    )
                });
            }
            Err(e) => {
                last_err = Some(log_and_return_err("API GET", &url, e));
                if attempt < MAX_ATTEMPTS {
                    tokio::time::sleep(Duration::from_millis(200)).await;
                }
            }
        }
    }
    Err(last_err.unwrap_or_else(|| {
        "{\"code\":\"upstream_unknown_error\",\"message\":\"API GET failed after retries\",\"upstream\":\"".to_string() + &url + "\"}"
    }))
}

#[derive(Deserialize)]
pub struct ApiPostArgs {
    pub path: String,
    pub body: serde_json::Value,
    pub token: Option<String>,
}

#[tauri::command]
pub async fn mcp_api_post(args: ApiPostArgs) -> Result<serde_json::Value, String> {
    #[cfg(debug_assertions)]
    if args.token.as_deref() == Some("dev-token-mock") {
        return mcp_api_post_mock(&args.path, &args.body).await;
    }

    let url = format!("{UPSTREAM_BASE}{}", args.path);
    let client = build_client()?;
    let mut last_err: Option<String> = None;
    for attempt in 1..=MAX_ATTEMPTS {
        let mut req = client
            .post(&url)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .json(&args.body);
        if let Some(t) = args.token.as_deref().filter(|s| !s.is_empty()) {
            req = req.bearer_auth(t);
        }
        match req.send().await {
            Ok(resp) => {
                let status = resp.status();
                let text = resp.text().await.unwrap_or_default();
                if !status.is_success() {
                    let body_preview: String = text.chars().take(400).collect();
                    let body_json = serde_json::to_string(&body_preview).unwrap_or_else(|_| "\"<serialize failed>\"".to_string());
                    let msg = serde_json::to_string(&format!("API POST {} returned HTTP {}", args.path, status)).unwrap_or_else(|_| "\"<error>\"".to_string());
                    let url_json = serde_json::to_string(&url).unwrap_or_else(|_| "\"<error>\"".to_string());
                    return Err(format!(
                        "{{\"code\":\"upstream_http_error\",\"message\":{},\"upstream\":{},\"upstream_body\":{}}}",
                        msg, url_json, body_json
                    ));
                }
                return serde_json::from_str(&text).map_err(|e| {
                    format!(
                        "{{\"code\":\"upstream_parse_error\",\"message\":\"API POST {} returned non-JSON: {e}\",\"upstream\":\"{}\"}}",
                        args.path, url
                    )
                });
            }
            Err(e) => {
                last_err = Some(log_and_return_err("API POST", &url, e));
                if attempt < MAX_ATTEMPTS {
                    tokio::time::sleep(Duration::from_millis(200)).await;
                }
            }
        }
    }
    Err(last_err.unwrap_or_else(|| {
        "{\"code\":\"upstream_unknown_error\",\"message\":\"API POST failed after retries\",\"upstream\":\"".to_string() + &url + "\"}"
    }))
}

#[cfg(debug_assertions)]
async fn mcp_api_get_mock(path: &str) -> Result<serde_json::Value, String> {
    log::info!("[dev-mode] mcp_api_get mocked: path={}", path);
    Ok(serde_json::json!({ "ok": true, "data": {} }))
}

#[cfg(debug_assertions)]
async fn mcp_api_post_mock(path: &str, _body: &serde_json::Value) -> Result<serde_json::Value, String> {
    log::info!("[dev-mode] mcp_api_post mocked: path={}", path);
    Ok(serde_json::json!({ "ok": true, "data": {} }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_sse_tool_calls_accumulates_split_frames() {
        // 模拟 OpenAI Chat Completions 流式 tool_calls：id/name 首片，arguments 跨片。
        let body = concat!(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"execute_skill\",\"arguments\":\"\"}}]}}]}\n",
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"skill_id\\\":\\\"trace\\\"\"}}]}}]}\n",
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"}\"}}]}}]}\n",
            "data: [DONE]\n",
        );
        let calls = parse_sse_tool_calls(body);
        assert_eq!(calls.len(), 1);
        assert_eq!(calls[0].id, "call_1");
        assert_eq!(calls[0].kind, "function");
        assert_eq!(calls[0].function.name, "execute_skill");
        assert_eq!(calls[0].function.arguments, "{\"skill_id\":\"trace\"}");
    }

    #[test]
    fn parse_sse_tool_calls_multiple_indices() {
        let body = concat!(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"memory_search\",\"arguments\":\"{\\\"query\\\":\\\"x\\\"}\"}}]}}]}\n",
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"id\":\"b\",\"type\":\"function\",\"function\":{\"name\":\"mcp_call\",\"arguments\":\"{}\"}}]}}]}\n",
        );
        let calls = parse_sse_tool_calls(body);
        assert_eq!(calls.len(), 2);
        assert_eq!(calls[0].id, "a");
        assert_eq!(calls[0].function.name, "memory_search");
        assert_eq!(calls[1].id, "b");
        assert_eq!(calls[1].function.name, "mcp_call");
    }

    #[test]
    fn parse_sse_tool_calls_ignores_text_only_stream() {
        let body = concat!(
            "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n",
            "data: [DONE]\n",
        );
        assert!(parse_sse_tool_calls(body).is_empty());
    }
}
