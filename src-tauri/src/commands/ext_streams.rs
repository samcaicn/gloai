// Copyright (c) 2026 AIMarketing
//
// 扩展流式 / 整体执行 / IM 连接状态命令。
//
// 本模块补齐前端桥接层调用但后端缺失的 4 个 Tauri 命令：
//   1. `mcp_stream`          — 流式 MCP 调用（Tauri 2 Channel API 推送 SSE 增量）
//   2. `automation_execute`  — 整体执行流程图（遍历 nodes + connections）
//   3. `im_connect`          — 手动连接指定 IM 渠道
//   4. `im_status`           — 查询所有 IM 渠道连接状态
//
// 设计要点（项目 memory 教训）：
//   * MCP / LLM 前端请求必须经 Tauri 代理，不能 WebView2 直连 fetch。
//   * reqwest 必须加 `.no_proxy()`（用户机器可能挂了 Clash 环境变量但代理未运行）。
//   * 调用 trait method（reqwest::Error::source）必须 `use std::error::Error as _;`。
//   * IM 渠道经 LongConnAdapter 走 relay gateway（wss://ai.tuptup.top/im/relay/...），
//     AdapterPool 按 channel_id 缓存已连接适配器，复用 `get_or_connect`。
//
// 注意：本模块命令暂未在 `lib.rs::invoke_handler!` 中注册（主线程保留注册动作）。
// 命令实现完整、可编译，注册后即可被前端调用。

use std::time::Duration;

use serde::Serialize;
use tauri::ipc::Channel;
use tauri::State;

// 引入 std::error::Error trait 让 `reqwest::Error::source()` 可调用。
use std::error::Error as _;

use crate::hermes::im::channel_registry::{SharedAdapterPool, SharedChannelRegistry};
use crate::skill::manifest::{InputAction, Step};
use crate::automation::engine::{humanized_delay_ms, perform_step_input};
use crate::commands::im_config::spawn_inbound_handlers;
use tokio::time::sleep;

// =============================================================
// mcp_stream — 流式 MCP 调用
// =============================================================
//
// 参考 `commands::mcp_proxy::mcp_call_v2`，但改为真正的流式：
//   * 用 reqwest `bytes_stream()` 增量读取上游 SSE 响应；
//   * 逐行解析 `data:` 帧，提取 delta.content 增量；
//   * 通过 Tauri 2 `Channel<McpStreamChunk>` 把每个增量推给前端。
//
// 上游 URL / 鉴权头与 `mcp_call_v2` 完全一致，保证行为可替换。
// `parse_sse_content`（mcp_proxy.rs）是私有且面向「整包文本」的，无法直接复用，
// 这里实现面向单行的 `extract_delta`，逻辑与 `parse_sse_content` 的 per-line 分支对齐。

const UPSTREAM_BASE: &str = "https://ai.tuptup.top";
const STREAM_CONNECT_TIMEOUT_SECS: u64 = 12;
/// 流式响应整体超时。SSE 流可能持续较久（LLM 逐 token 输出），给到 10 分钟。
const STREAM_REQUEST_TIMEOUT_SECS: u64 = 600;

/// 推给前端的单个流式分片。
///
/// `kind` 取值：
///   * `"content"` — `data` 形如 `{ "content": "<delta 文本>" }`
///   * `"error"`   — `data` 形如 `{ "message": "<错误描述>" }`
///   * `"done"`    — `data` 为 `null`，表示流正常结束
#[derive(Serialize, Clone)]
pub struct McpStreamChunk {
    #[serde(rename = "type")]
    pub kind: String,
    pub data: serde_json::Value,
}

fn build_stream_client() -> Result<reqwest::Client, String> {
    reqwest::Client::builder()
        .no_proxy()
        .connect_timeout(Duration::from_secs(STREAM_CONNECT_TIMEOUT_SECS))
        .timeout(Duration::from_secs(STREAM_REQUEST_TIMEOUT_SECS))
        .user_agent(concat!("AIMarketing-Tauri/", env!("CARGO_PKG_VERSION")))
        .build()
        .map_err(|e| format!("reqwest client build failed: {e}"))
}

/// 从单条 SSE `data:` 负载里提取文本增量。覆盖 mcp_proxy.rs::parse_sse_content
/// 支持的全部上游格式（OpenAI Chat / Responses / Anthropic / 纯文本）。
fn extract_delta(data: &str) -> Option<String> {
    if data.is_empty() || data == "[DONE]" {
        return None;
    }
    if let Ok(v) = serde_json::from_str::<serde_json::Value>(data) {
        // OpenAI Chat Completions: choices[0].delta.content
        if let Some(c) = v
            .get("choices")
            .and_then(|c| c.get(0))
            .and_then(|c| c.get("delta"))
            .and_then(|d| d.get("content"))
            .and_then(|c| c.as_str())
        {
            return Some(c.to_string());
        }
        // Anthropic: delta.text (content_block_delta / text_delta)
        if let Some(t) = v
            .get("delta")
            .and_then(|d| d.get("text"))
            .and_then(|c| c.as_str())
        {
            return Some(t.to_string());
        }
        // Anthropic 变体: delta.content
        if let Some(c) = v
            .get("delta")
            .and_then(|d| d.get("content"))
            .and_then(|c| c.as_str())
        {
            return Some(c.to_string());
        }
        // OpenAI Responses API: { type: "response.output_text.delta", delta: "..." }
        if let Some(d) = v.get("delta").and_then(|d| d.as_str()) {
            return Some(d.to_string());
        }
        // 部分服务把 content 放在顶层
        if let Some(c) = v.get("content").and_then(|c| c.as_str()) {
            return Some(c.to_string());
        }
        return None;
    }
    // 非 JSON —— 当作纯文本追加（兼容 legacy `data: hello`）
    Some(data.to_string())
}

/// 增量消费上游 SSE 字节流，逐行解析 `data:` 帧并通过 Channel 推送。
async fn stream_sse_to_channel(
    resp: reqwest::Response,
    on_event: &Channel<McpStreamChunk>,
) -> Result<(), String> {
    use futures_util::StreamExt;
    let status = resp.status();
    let content_type = resp
        .headers()
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_lowercase();
    // 非 2xx：把状态码 + 正文摘要作为 error 推给前端，再结束。
    if !status.is_success() {
        let body = resp.text().await.unwrap_or_default();
        let preview: String = body.chars().take(400).collect();
        let _ = on_event.send(McpStreamChunk {
            kind: "error".into(),
            data: serde_json::json!({
                "code": "upstream_http_error",
                "message": format!("MCP stream returned HTTP {}", status),
                "upstream_body": preview,
            }),
        });
        let _ = on_event.send(McpStreamChunk {
            kind: "done".into(),
            data: serde_json::Value::Null,
        });
        return Err(format!("MCP stream returned HTTP {}", status));
    }

    // 服务器已被要求流式（请求头 `Accept: text/event-stream`），正常会返回
    // `text/event-stream` 并逐帧推送。这里只把 `text/event-stream` 当作 SSE
    // 增量流；其余 content-type（text/plain / application/json 等，常见于代理
    // 改写 content-type 或缓冲整段响应）一律走「整包」分支：一次性读全 body。
    //
    // 设计目标——简单健壮：前端只需处理两种状态：等待态（已实现）与「有内容即
    // 渲染」。无论上游是真流式还是被中转缓冲成一整段，内容都不丢。
    let is_sse = content_type.contains("text/event-stream");
    if !is_sse {
        log::info!(
            "[mcp_stream] non-SSE branch: content_type={}, reading full body",
            content_type
        );
        let text = resp.text().await.unwrap_or_default();
        log::info!(
            "[mcp_stream] non-SSE body len={}, preview={:?}",
            text.len(),
            text.chars().take(200).collect::<String>()
        );
        // 若整包其实是 SSE 帧（代理只改了 content-type 没改 body），仍按 `data:`
        // 行解析提取内容，避免把原始 `data: {...}` 帧文本直接展示给用户；否则把
        // 整段文本作为单个 content 分片推送（与 mcp_call_v2 的标准响应分支一致）。
        let looks_like_sse = text.lines().any(|l| l.starts_with("data:"));
        if looks_like_sse {
            for line in text.lines() {
                if let Some(rest) = line.strip_prefix("data:") {
                    if let Some(delta) = extract_delta(rest.trim_start()) {
                        let _ = on_event.send(McpStreamChunk {
                            kind: "content".into(),
                            data: serde_json::json!({ "content": delta }),
                        });
                    }
                }
            }
        } else if !text.is_empty() {
            // 识别 MCP v2 标准信封，避免把错误/结构化响应当 LLM 回复正文展示：
            //   { ok:false, error:{ code, message } } → error chunk（服务器故障可见）
            //   { ok:true,  data:{ content } }        → content chunk（标准非流式回复）
            //   其他 / JSON 解析失败                   → 退化为纯文本 content（兼容旧行为）
            match serde_json::from_str::<serde_json::Value>(&text) {
                Ok(v)
                    if v.get("ok").and_then(|x| x.as_bool()) == Some(false)
                        || v.get("error").is_some() =>
                {
                    let err = v.get("error").cloned().unwrap_or(serde_json::Value::Null);
                    let code = err
                        .get("code")
                        .and_then(|x| x.as_str())
                        .unwrap_or("upstream_error");
                    let msg = err
                        .get("message")
                        .and_then(|x| x.as_str())
                        .map(String::from)
                        .unwrap_or_else(|| text.chars().take(200).collect());
                    log::warn!(
                        "[mcp_stream] non-SSE error envelope: code={}, message={}",
                        code, msg
                    );
                    let _ = on_event.send(McpStreamChunk {
                        kind: "error".into(),
                        data: serde_json::json!({
                            "code": code,
                            "message": format!("{}: {}", code, msg),
                        }),
                    });
                }
                Ok(v) => {
                    // ok=true 或无 ok 字段：优先提取 data.content；找不到则原文兜底。
                    let content = v
                        .get("data")
                        .and_then(|d| d.get("content"))
                        .and_then(|x| x.as_str())
                        .map(String::from)
                        .unwrap_or_else(|| text.clone());
                    let _ = on_event.send(McpStreamChunk {
                        kind: "content".into(),
                        data: serde_json::json!({ "content": content }),
                    });
                }
                Err(_) => {
                    // 非 JSON：当纯文本 content（与旧行为一致）。
                    let _ = on_event.send(McpStreamChunk {
                        kind: "content".into(),
                        data: serde_json::json!({ "content": text }),
                    });
                }
            }
        }
        let _ = on_event.send(McpStreamChunk {
            kind: "done".into(),
            data: serde_json::Value::Null,
        });
        return Ok(());
    }

    // SSE 流式：逐 chunk 累积到缓冲区，按 `\n` 切行处理。
    // 诊断日志：记录 content_type / 每个 bytes_stream chunk 的到达时间与大小，
    // 用于判断上游是否真正逐帧推送（真流式）还是一次性返回完整 SSE（伪流式）。
    log::info!(
        "[mcp_stream] SSE stream start: content_type={}, status={}",
        content_type, status
    );
    let stream_start = std::time::Instant::now();
    let mut stream = resp.bytes_stream();
    let mut buf = String::new();
    let mut content_count = 0;
    let mut bytes_chunk_count: u32 = 0;
    let mut first_bytes_logged = false;
    while let Some(chunk_res) = stream.next().await {
        let chunk = chunk_res
            .map_err(|e| format!("stream read failed: {}", format_reqwest_error(&e)))?;
        bytes_chunk_count += 1;
        if !first_bytes_logged {
            first_bytes_logged = true;
            log::info!(
                "[mcp_stream] TTFB: first bytes_stream chunk after {:?} ({} bytes)",
                stream_start.elapsed(),
                chunk.len()
            );
        } else {
            log::info!(
                "[mcp_stream] bytes_stream chunk #{}: {} bytes at {:?}",
                bytes_chunk_count,
                chunk.len(),
                stream_start.elapsed()
            );
        }
        buf.push_str(&String::from_utf8_lossy(&chunk));
        while let Some(idx) = buf.find('\n') {
            let line: String = buf[..idx].trim_end_matches('\r').to_string();
            buf.drain(..=idx);
            if let Some(rest) = line.strip_prefix("data:") {
                let data = rest.trim_start();
                if let Some(delta) = extract_delta(data) {
                    content_count += 1;
                    log::info!("[mcp_stream] Sending content chunk #{}: {}", content_count, delta);
                    let _ = on_event.send(McpStreamChunk {
                        kind: "content".into(),
                        data: serde_json::json!({ "content": delta }),
                    });
                }
            }
            // 非 data: 行（event:/id:/注释/空行）忽略，与 parse_sse_content 一致。
        }
    }
    // 刷新缓冲区中最后一条不带换行的残余
    let trailing = buf.trim_end_matches('\r');
    if let Some(rest) = trailing.strip_prefix("data:") {
        let data = rest.trim_start();
        if let Some(delta) = extract_delta(data) {
            let _ = on_event.send(McpStreamChunk {
                kind: "content".into(),
                data: serde_json::json!({ "content": delta }),
            });
        }
    }
    let _ = on_event.send(McpStreamChunk {
        kind: "done".into(),
        data: serde_json::Value::Null,
    });
    log::info!(
        "[mcp_stream] SSE stream end: {} content chunks, {} bytes_stream chunks, total {:?}",
        content_count,
        bytes_chunk_count,
        stream_start.elapsed()
    );
    Ok(())
}

/// 遍历 `reqwest::Error` 的 source 链拼成完整描述，避免丢失底层 os error / TLS 细节。
/// 与 mcp_proxy.rs::format_reqwest_error 同构（该函数私有，无法复用）。
fn format_reqwest_error(err: &reqwest::Error) -> String {
    let mut parts: Vec<String> = vec![err.to_string()];
    let mut source: Option<&dyn std::error::Error> = err.source();
    while let Some(s) = source {
        parts.push(s.to_string());
        source = s.source();
    }
    parts.join(" · caused by: ")
}

/// 流式 MCP 调用。
///
/// 前端通过 Tauri 2 `Channel` API 接收 `McpStreamChunk` 流：
/// ```ignore
/// const ch = new Channel();
/// ch.onmessage = (msg) => { ... };
/// await invoke('mcp_stream', { action, params, onEvent: ch, token });
/// ```
///
/// `action` / `params` 与 `mcp_call_v2` 同义；`token` 为可选 device_token。
#[tauri::command]
pub async fn mcp_stream(
    app: tauri::AppHandle,
    action: String,
    params: serde_json::Value,
    on_event: Channel<McpStreamChunk>,
    token: Option<String>,
) -> Result<(), String> {
    let url = format!("{UPSTREAM_BASE}/api/v2/mcp");
    let client = build_stream_client()?;
    let body = serde_json::json!({ "action": action, "params": params });

    log::info!("[mcp_stream] action={} upstream={}", action, url);
    let _ = &app; // 预留：未来可经 app.emit 推送旁路事件

    let mut req = client
        .post(&url)
        .header("Content-Type", "application/json")
        .header("Accept", "text/event-stream")
        .header(
            "x-claw-timestamp",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs()
                .to_string(),
        )
        .header("x-claw-nonce", uuid::Uuid::new_v4().to_string())
        .json(&body);
    if let Some(t) = token.as_deref().filter(|s| !s.is_empty()) {
        req = req.bearer_auth(t);
    }

    let resp = match req.send().await {
        Ok(r) => r,
        Err(e) => {
            let detail = format_reqwest_error(&e);
            let _ = on_event.send(McpStreamChunk {
                kind: "error".into(),
                data: serde_json::json!({
                    "code": "upstream_connect_failed",
                    "message": format!("MCP stream {} failed: {}", action, detail),
                    "upstream": url,
                }),
            });
            let _ = on_event.send(McpStreamChunk {
                kind: "done".into(),
                data: serde_json::Value::Null,
            });
            return Err(format!(
                "{{\"code\":\"upstream_connect_failed\",\"message\":\"MCP stream {} failed: {}\",\"upstream\":\"{}\"}}",
                action, detail, url
            ));
        }
    };

    // Check HTTP status before attempting SSE parsing
    if !resp.status().is_success() {
        let status = resp.status().as_u16();
        let err_text = resp.text().await.unwrap_or_else(|_| "unknown error".to_string());
        log::warn!("[mcp_stream] upstream returned error status {}: {}", status, err_text);
        let _ = on_event.send(McpStreamChunk {
            kind: "error".into(),
            data: serde_json::json!({
                "code": "upstream_error",
                "message": format!("MCP stream {} failed (HTTP {}): {}", action, status, err_text),
                "upstream": url,
            }),
        });
        let _ = on_event.send(McpStreamChunk {
            kind: "done".into(),
            data: serde_json::Value::Null,
        });
        return Err(format!(
            "{{\"code\":\"upstream_error\",\"message\":\"MCP stream {} failed (HTTP {}): {}\",\"upstream\":\"{}\"}}",
            action, status, err_text, url
        ));
    }

    stream_sse_to_channel(resp, &on_event).await
}

// =============================================================
// automation_execute — 整体执行流程图
// =============================================================
//
// 前端 `automationExecute(flowchart)` 传入流程图 JSON（结构见
// `src/flowchart/flowchartAdapter.js`）：
//   { nodes: [{ id, type, label, action?, ... }], connections: [{ from, to, label? }] }
//
// 后端无现成的「整体执行流程图」命令，这里实现遍历逻辑：
//   1. 从 `start` 节点出发，按 `connections` 构建的邻接表顺序游走；
//   2. `process` / `io` / `decision` 节点计入 `steps_executed`；
//   3. `decision` 默认走第一条出边（前端流程图已按 yes/no 排序）；
//   4. 遇到 `end` 或无出边或成环时停止；
//   5. 未知节点类型记入 `errors`。
//
// TODO(中): 当前遍历只做拓扑推进 + 计数，未真正 dispatch 到
// `commands::pc_automation::execute_step`（需把 flowchart node 转成
// `PcStepView`，且 execute_step 依赖 `shared_state()` 全局状态）。
// 真正的逐步执行待 PcStepView 转换层就绪后接入；本实现保证编译通过
// 且拓扑遍历正确，命令注册后即可被前端调用拿到 steps_executed / errors。

/// `automation_execute` 的返回结构。
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AutomationResult {
    pub success: bool,
    pub steps_executed: u32,
    pub errors: Vec<String>,
}

#[tauri::command]
pub async fn automation_execute(
    flowchart: serde_json::Value,
) -> Result<AutomationResult, String> {
    let nodes = flowchart
        .get("nodes")
        .and_then(|v| v.as_array())
        .ok_or_else(|| "flowchart.nodes missing or not an array".to_string())?;
    let connections: Vec<serde_json::Value> = flowchart
        .get("connections")
        .and_then(|v| v.as_array())
        .cloned()
        .unwrap_or_default();

    // 邻接表：from -> [to, ...]（保持 connections 中的顺序，decision 默认走第一条）
    let mut adj: std::collections::HashMap<String, Vec<String>> =
        std::collections::HashMap::new();
    for conn in &connections {
        let from = conn
            .get("from")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        let to = conn
            .get("to")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        if !from.is_empty() && !to.is_empty() {
            adj.entry(from).or_default().push(to);
        }
    }

    // 节点 id -> type
    let node_type: std::collections::HashMap<String, String> = nodes
        .iter()
        .filter_map(|n| {
            let id = n.get("id").and_then(|v| v.as_str())?.to_string();
            let t = n
                .get("type")
                .and_then(|v| v.as_str())
                .unwrap_or("process")
                .to_string();
            Some((id, t))
        })
        .collect();

    // 起点：优先 type=start，否则取首个节点
    let start_id = nodes
        .iter()
        .find_map(|n| {
            let t = n.get("type").and_then(|v| v.as_str()).unwrap_or("");
            if t == "start" {
                n.get("id").and_then(|v| v.as_str()).map(String::from)
            } else {
                None
            }
        })
        .or_else(|| {
            nodes
                .first()
                .and_then(|n| n.get("id").and_then(|v| v.as_str()).map(String::from))
        });

    let mut steps_executed: u32 = 0;
    let mut errors: Vec<String> = Vec::new();
    let mut visited: std::collections::HashSet<String> = std::collections::HashSet::new();

    let mut cursor = match start_id {
        Some(s) => s,
        None => {
            return Ok(AutomationResult {
                success: true,
                steps_executed,
                errors,
            })
        }
    };

    loop {
        if !visited.insert(cursor.clone()) {
            // 成环：停止游走，避免死循环
            break;
        }
        let ntype = node_type
            .get(&cursor)
            .cloned()
            .unwrap_or_else(|| "process".to_string());
        match ntype.as_str() {
            "start" | "end" | "connector" => {}
            "process" | "io" | "decision" => {
                // 真实回放：把录制节点转成 Step 并用 enigo 注入输入。
                if let Some(node) = nodes.iter().find(|n| {
                    n.get("id").and_then(|v| v.as_str()) == Some(cursor.as_str())
                }) {
                    if let Some(step) = flowchart_node_to_step(node) {
                        match perform_step_input(&step) {
                            Ok(()) => {
                                steps_executed += 1;
                            }
                            Err(e) => errors.push(format!("步骤 '{}' 执行失败: {}", step.id, e)),
                        }
                        // 步骤间拟人化间隔（替代原固定 300ms），模拟人工操作节奏
                        let step_gap = humanized_delay_ms(300, 40, 120, 900);
                        sleep(Duration::from_millis(step_gap)).await;
                    }
                }
            }
            other => {
                errors.push(format!("unknown node type `{}` at `{}`", other, cursor));
            }
        }
        if ntype == "end" {
            break;
        }
        match adj.get(&cursor).and_then(|v| v.first()) {
            Some(next) => cursor = next.clone(),
            None => break, // 无出边，结束游走
        }
    }

    Ok(AutomationResult {
        success: errors.is_empty(),
        steps_executed,
        errors,
    })
}

/// 把流程图节点（录制产物）转换为可回放的 `Step`。
/// 节点 `action` 字段决定回放方式：click / type / hotkey；
/// 坐标 / 文本 / 组合键从 `meta` 读取。start/end/connector 返回 None。
fn flowchart_node_to_step(node: &serde_json::Value) -> Option<Step> {
    let action = node.get("action").and_then(|v| v.as_str()).unwrap_or("");
    let meta = node.get("meta");
    let input = match action {
        "click" => {
            let x = meta.and_then(|m| m.get("x")).and_then(|v| v.as_i64()).unwrap_or(0) as i32;
            let y = meta.and_then(|m| m.get("y")).and_then(|v| v.as_i64()).unwrap_or(0) as i32;
            Some(InputAction::Click { x, y })
        }
        "type" => {
            let text = meta
                .and_then(|m| m.get("text"))
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string();
            Some(InputAction::Type { text })
        }
        "hotkey" => {
            let keys = meta
                .and_then(|m| m.get("keys"))
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string();
            Some(InputAction::Hotkey { keys })
        }
        _ => None,
    };
    let id = node.get("id").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let description = node.get("label").and_then(|v| v.as_str()).unwrap_or("").to_string();
    // 从 flowchart node 的 meta 透传 delayMs、mouseTrajectory 和 llmPrompt 到 Step
    let delay_ms = meta
        .and_then(|m| m.get("delayMs"))
        .and_then(|v| v.as_u64());
    let mouse_trajectory: Option<Vec<Vec<i32>>> = meta
        .and_then(|m| m.get("mouseTrajectory"))
        .and_then(|v| v.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|point| {
                    let coords = point.as_array()?;
                    if coords.len() >= 2 {
                        Some(vec![coords[0].as_i64()? as i32, coords[1].as_i64()? as i32])
                    } else {
                        None
                    }
                })
                .collect::<Vec<_>>()
        });
    let llm_prompt = meta
        .and_then(|m| m.get("llmPrompt"))
        .and_then(|v| v.as_str())
        .map(|s| s.to_string());
    Some(Step { id, description, input, delay_ms, mouse_trajectory, llm_prompt, ..Default::default() })
}

/// 执行单个流程图节点（来自执行悬浮窗的单步按钮）。
/// 直接调用真实回放引擎（enigo 注入），立即返回执行结果。
#[tauri::command]
pub async fn execute_flowchart_step(
    node: serde_json::Value,
) -> Result<serde_json::Value, String> {
    let step_id = node.get("id").and_then(|v| v.as_str()).unwrap_or("").to_string();
    match flowchart_node_to_step(&node) {
        Some(step) => match perform_step_input(&step) {
            Ok(()) => Ok(serde_json::json!({ "ok": true, "stepId": step.id })),
            Err(e) => Ok(serde_json::json!({ "ok": false, "stepId": step.id, "error": e })),
        },
        None => Ok(serde_json::json!({
            "ok": false,
            "stepId": step_id,
            "error": "该节点不可回放（start/end/connector 或无 action）"
        })),
    }
}

// =============================================================
// im_connect — 手动连接指定 IM 渠道
// =============================================================
//
// 前端 `imConnect(channelId)`。AdapterPool 按 channel_id 缓存适配器，
// `get_or_connect` 命中则复用，否则构造 LongConnAdapter 并 connect()。
//
// `get_or_connect` 接收 `IMBinding`（不是 channel_id），因此先经
// ChannelRegistry.find_binding_by_id 把 channel_id 解析成 binding。
//
// cooldown：AdapterPool / LongConnAdapter 内部有 circuit breaker
// （websocket_adapter.rs CIRCUIT_BREAKER_THRESHOLD=3），connect 失败会
// 被熔断并快速返回 Err。本命令直接透传该错误，前端据此提示「渠道冷却中」。
// AdapterPool 未暴露公开的 cooldown 查询 API（受约束不改 im_bridge.rs /
// channel_registry.rs），故无法在连接前预判冷却状态。

#[tauri::command]
pub async fn im_connect(
    app: tauri::AppHandle,
    registry: State<'_, SharedChannelRegistry>,
    pool: State<'_, SharedAdapterPool>,
    channel_id: String,
) -> Result<(), String> {
    let binding = registry
        .find_binding_by_id(&channel_id)
        .await
        .ok_or_else(|| format!("channel {} not registered in ChannelRegistry", channel_id))?;
    let adapter = pool.replace(binding)
        .await
        .map_err(|e| format!("im connect failed for channel {}: {}", channel_id, e))?;
    // 读取持久化配置决定是否启用后端自动回复（默认开）。
    let config = crate::commands::im_config::load_config(&app).await;
    let auto_reply = config
        .channels
        .iter()
        .find(|c| c.id == channel_id)
        .map(|c| c.auto_reply)
        .unwrap_or(true);
    spawn_inbound_handlers(app, adapter, channel_id, auto_reply);
    Ok(())
}

// =============================================================
// im_status — 查询所有 IM 渠道连接状态
// =============================================================
//
// 前端 `imStatus()` 返回所有已注册渠道的连接状态快照。
//
// AdapterPool 没有公开的 status 快照方法（受约束不改 channel_registry.rs），
// 这里用现有公开 API 尽力而为：
//   * channel_id 列表来自 ChannelRegistry.all_bindings()；
//   * `connected` = pool.get(channel_id) 命中（存在已缓存适配器）；
//   * `last_error` / `cooldown_until` 暂为 None —— AdapterPool 不记录这些。
//
// TODO(低): 真实的 last_error / cooldown_until 需在 AdapterPool /
// LongConnAdapter 加状态字段并暴露 `status_snapshot()`，不属本批次范围。

/// 单个 IM 渠道的状态快照。
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ImChannelStatus {
    pub channel_id: String,
    pub connected: bool,
    pub last_error: Option<String>,
    pub cooldown_until: Option<i64>,
    /// 后端自动回复开关（读自 im_config.json 的 entry.auto_reply）。
    /// 前端据此跳过自己的自动回复，避免与后端双回复。
    pub backend_auto_reply: bool,
}

#[tauri::command]
pub async fn im_status(
    app: tauri::AppHandle,
    registry: State<'_, SharedChannelRegistry>,
    pool: State<'_, SharedAdapterPool>,
) -> Result<Vec<ImChannelStatus>, String> {
    let bindings = registry.all_bindings().await;
    // 读取持久化配置以获取每渠道 auto_reply 开关。配置读取失败时
    // 默认全部返回 true（与 entry 默认一致，保证后端自动回复生效）。
    let config = crate::commands::im_config::load_config(&app).await;
    let mut out = Vec::with_capacity(bindings.len());
    for b in bindings {
        let connected = pool.get(&b.id).await.is_some();
        let backend_auto_reply = config
            .channels
            .iter()
            .find(|c| c.id == b.id)
            .map(|c| c.auto_reply)
            .unwrap_or(true);
        out.push(ImChannelStatus {
            channel_id: b.id,
            connected,
            last_error: None,
            cooldown_until: None,
            backend_auto_reply,
        });
    }
    Ok(out)
}
