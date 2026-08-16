
//
// LLM service façade. The TypeScript version aggregated per-provider
// adapters (OpenAI-compatible, Anthropic, local llama.cpp) behind a
// single `LLMService.complete()` interface. The Rust port exposes
// the same surface and uses `reqwest` to talk to the providers.
//
// v5.1 — adds `complete_stream` and `complete_stream_bytes` so the
// in-process embedded gateway (`hermes::embedded_server`) can pipe
// the upstream provider's SSE bytes back to the webview one chunk
// at a time. We deliberately do NOT synthesize fake tokens; if the
// provider doesn't support streaming, the caller can fall back to
// `complete()` and stream the result themselves.

use std::time::Duration;
use bytes::Bytes;
use futures::Stream;
use reqwest::Client as HttpClient;
use serde::{Deserialize, Serialize};

use super::types::*;

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct LLMServiceConfig {
    pub provider: String,
    pub api_url: String,
    pub api_key: Option<String>,
    pub model: String,
    #[serde(default = "default_temperature")]
    pub temperature: f32,
    #[serde(default = "default_max_tokens")]
    pub max_tokens: u32,
}

fn default_temperature() -> f32 { 0.7 }
fn default_max_tokens() -> u32 { 4096 }

pub struct LLMService {
    cfg: LLMServiceConfig,
    http: HttpClient,
}

impl LLMService {
    pub fn new(cfg: LLMServiceConfig) -> Result<Self, String> {
        let http = HttpClient::builder()
            .no_proxy()
            .timeout(Duration::from_secs(120))
            .build()
            .map_err(|e| format!("failed to build http client: {}", e))?;
        Ok(Self { cfg, http })
    }

    /// Construct an `LLMService` reusing an existing `reqwest::Client`.
    /// Lets the embedded gateway build one client at boot and share it
    /// across chat / cron / SSE handlers instead of rebuilding the
    /// connection pool on every call.
    pub fn with_client(cfg: LLMServiceConfig, client: HttpClient) -> Self {
        Self { cfg, http: client }
    }

    pub fn provider(&self) -> &str { &self.cfg.provider }

    pub fn config(&self) -> &LLMServiceConfig { &self.cfg }

    pub async fn complete(&self, messages: Vec<VLMMessage>, tools: Option<Vec<serde_json::Value>>) -> Result<VLMResponse, String> {
        // 这里服务的是 self-hosted / on-prem 的 OpenAI 兼容提供商
        // (api_url 可配置，例如本地 llama.cpp / vllm)。注意：云端
        // LLM 会话已不再走本适配器——统一经 MCP action `llm.stream_request`
        // (POST /api/v2/mcp) 发起。即便 caller 走非流式 `complete()`
        // (比如 notebook AI popover 的 sendChat)，我们也走 streaming
        // 通道，把所有 delta.content 拼起来再返回。
        match self.cfg.provider.as_str() {
            "openai" | "openai-compatible" | "vllm" | "llamacpp" => {
                self.openai_complete_collect(messages, tools).await
            }
            "anthropic" => self.anthropic_complete(messages, tools).await,
            other => Err(format!("unsupported provider: {}", other)),
        }
    }

    /// Open an upstream SSE stream for the chat. Returns a boxed
    /// stream of raw bytes so the caller can forward them to the
    /// webview verbatim (no re-serialization, no token coalescing).
    ///
    /// We attach `stream: true` to the body and ask the provider for
    /// `text/event-stream`. The webview parses the SSE frames the
    /// same way it parsed the previous `hermes-cli.cjs` stub, so the
    /// front-end does not need any change.
    pub async fn complete_stream_bytes(
        &self,
        messages: Vec<VLMMessage>,
    ) -> Result<Box<dyn Stream<Item = Result<Bytes, String>> + Send + Unpin>, String> {
        match self.cfg.provider.as_str() {
            "openai" | "openai-compatible" | "vllm" | "llamacpp" => {
                self.openai_stream_bytes(messages, None).await
            }
            "anthropic" => self.anthropic_stream_bytes(messages).await,
            other => Err(format!("unsupported provider: {}", other)),
        }
    }

    /// OpenAI-compatible non-streaming entrypoint used by `complete()`.
    /// We open the same streaming endpoint and just buffer the body
    /// server-side until `[DONE]`, then hand the assembled `VLMResponse`
    /// back. The translated stream is consumed here (not exposed to the
    /// caller) because `complete()` callers want a single struct, not SSE.
    /// (云端 LLM 不走这里——见 `complete()` 注释，统一经 MCP。)
async fn openai_complete_collect(
&self,
messages: Vec<VLMMessage>,
tools: Option<Vec<serde_json::Value>>,
) -> Result<VLMResponse, String> {
use futures::StreamExt;
let mut translated = self.openai_stream_bytes(messages, tools).await?;
        let mut content_buf = String::new();
        let mut tool_calls: Vec<VLMToolCall> = Vec::new();
        let mut finish_reason: Option<String> = None;
        while let Some(item) = translated.next().await {
            let chunk = item.map_err(|e| format!("upstream stream error: {e}"))?;
            // chunk 是 Hermes Responses API SSE 字节流。解析出
            // 我们的语义事件:response.output_text.delta 累积到
            // content_buf,response.output_item.added 累积到
            // tool_calls,response.completed 拿到 finish_reason。
            for event in parse_responses_sse_events(&chunk) {
                match event {
                    ResponsesEvent::OutputTextDelta { delta } => {
                        content_buf.push_str(&delta);
                    }
                    ResponsesEvent::OutputItemAdded { item } => {
                        if item.get("type").and_then(|v| v.as_str())
                            == Some("function_call")
                        {
                            if let Ok(tc) =
                                serde_json::from_value::<VLMToolCall>(item.clone())
                            {
                                tool_calls.push(tc);
                            }
                        }
                    }
                    ResponsesEvent::Completed { response } => {
                        finish_reason = response
                            .get("finish_reason")
                            .and_then(|v| v.as_str())
                            .map(str::to_string);
                    }
                    ResponsesEvent::Failed { .. } => {
                        // 错误已经在 stream 过程中以 Result::Err
                        // 形式冒出,这里不用处理。
                    }
                }
            }
        }
        if content_buf.is_empty() && tool_calls.is_empty() && finish_reason.is_none() {
            return Err("upstream returned an empty body".to_string());
        }
        Ok(VLMResponse {
            content: if content_buf.is_empty() {
                None
            } else {
                Some(content_buf)
            },
            tool_calls: if tool_calls.is_empty() {
                None
            } else {
                Some(tool_calls)
            },
            finish_reason,
            usage: None,
        })
    }

    async fn anthropic_complete(&self, messages: Vec<VLMMessage>, _tools: Option<Vec<serde_json::Value>>) -> Result<VLMResponse, String> {
        // Anthropic Messages API expects `system`/`messages` separately; we
        // perform a minimal translation so OpenAI-style messages keep
        // working with the Anthropic backend.
        let url = format!("{}/v1/messages", self.cfg.api_url.trim_end_matches('/'));
        let mut system_text = String::new();
        let mut msgs: Vec<serde_json::Value> = Vec::new();
        for m in messages {
            if m.role == "system" {
                system_text.push_str(&m.content);
                system_text.push('\n');
            } else {
                msgs.push(serde_json::json!({"role": m.role, "content": m.content}));
            }
        }
        let mut body = serde_json::json!({
            "model": self.cfg.model,
            "max_tokens": self.cfg.max_tokens,
            "temperature": self.cfg.temperature,
            "messages": msgs,
        });
        if !system_text.is_empty() { body["system"] = serde_json::Value::String(system_text); }
        let mut req = self.http.post(&url).json(&body);
        if let Some(k) = &self.cfg.api_key {
            req = req.header("x-api-key", k);
            req = req.header("anthropic-version", "2023-06-01");
        }
        let resp = req.send().await.map_err(|e| e.to_string())?;
        if !resp.status().is_success() { return Err(format!("http {}", resp.status())); }
        let v: serde_json::Value = resp.json().await.map_err(|e| e.to_string())?;
        let content = v.get("content").and_then(|c| c.get(0))
            .and_then(|c| c.get("text")).and_then(|t| t.as_str()).map(String::from);
        Ok(VLMResponse { content, tool_calls: None, finish_reason: None, usage: None })
    }

    /// OpenAI-compatible streaming. We send `stream: true` and pipe
    /// the upstream `text/event-stream` response back to the caller
    /// as raw bytes. We deliberately do NOT decode / re-emit the
    /// chunks — the embedded server forwards them verbatim to the
    /// webview, so any provider-specific delta framing is preserved.
    ///
    /// v5.1: starting in this revision the returned bytes are
    /// already in **Hermes Responses API SSE** shape (translated
    /// from OpenAI chat-completions SSE by
    /// `OpenAIToResponsesTranslator`). The webview's `sendChatStream`
    /// parses Responses API events
    /// (`response.output_text.delta` / `response.completed` / …),
    /// so the translation has to happen in this gateway layer.
    ///
    /// 注意：这是嵌入式服务器（self-hosted / on-prem OpenAI 兼容
    /// 提供商）内部 `/v1/responses` 路由的实现。云端 LLM 会话已统一
    /// 经 MCP `llm.stream_request`（POST /api/v2/mcp）发起，前端
    /// `llmStreamChat` 不再直连此处。
async fn openai_stream_bytes(
&self,
messages: Vec<VLMMessage>,
tools: Option<Vec<serde_json::Value>>,
) -> Result<Box<dyn Stream<Item = Result<Bytes, String>> + Send + Unpin>, String> {
use futures::StreamExt;
// 通用 OpenAI 兼容路径（self-hosted / on-prem 提供商，由 cfg.api_url
// 配置）。注意：这不是云端 `ai.tuptup.top/v1/chat/completions`——
// 云端端点已下线，云端 LLM 统一经 MCP `llm.stream_request` 发起。
let base = self.cfg.api_url.trim_end_matches('/').trim_end_matches("/v1");
let url = format!("{}/v1/chat/completions", base);
let mut body = serde_json::json!({
"model": self.cfg.model,
"messages": messages,
"max_tokens": self.cfg.max_tokens,
"temperature": self.cfg.temperature,
"stream": true,
});
// ← 把 tools 真实传入 body（之前被忽略为 _tools）
if let Some(ts) = tools {
body["tools"] = serde_json::Value::Array(ts);
}
        let mut req = self.http.post(&url).json(&body);
        if let Some(k) = &self.cfg.api_key {
            req = req.bearer_auth(k);
        }
        req = req.header("x-claw-timestamp", std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs().to_string());
        req = req.header("x-claw-nonce", uuid::Uuid::new_v4().to_string());
        let resp = req
            .send()
            .await
            .map_err(|e| format!("upstream chat-completions connect failed: {e}"))?;
        let status = resp.status();
        if !status.is_success() {
            let body = resp.text().await.unwrap_or_default();
            return Err(format!(
                "upstream chat-completions returned HTTP {}: {}",
                status,
                body.chars().take(400).collect::<String>()
            ));
        }

        // Wrap the upstream byte stream. axum's `Body` wants an
        // `impl Stream<Item = Result<Bytes, _>>`. We map the reqwest
        // error into a String to satisfy the trait.
        let raw = resp
            .bytes_stream()
            .map(|item| item.map_err(|e| format!("upstream stream error: {e}")));
        // Translate the OpenAI chat-completions SSE framing into
        // Hermes Responses API SSE framing so the webview can keep
        // parsing the event names it expects
        // (`response.output_text.delta`, `response.completed`, …).
        Ok(Box::new(OpenAIToResponsesTranslator::new(
            Box::new(raw),
            self.cfg.model.clone(),
        )))
    }

    /// Anthropic Messages API streaming. We translate the message
    /// shape the same way `anthropic_complete` does, add
    /// `"stream": true`, and pipe back the `text/event-stream`
    /// bytes unchanged.
    async fn anthropic_stream_bytes(
        &self,
        messages: Vec<VLMMessage>,
    ) -> Result<Box<dyn Stream<Item = Result<Bytes, String>> + Send + Unpin>, String> {
        use futures::StreamExt;
        let url = format!("{}/v1/messages", self.cfg.api_url.trim_end_matches('/'));
        let mut system_text = String::new();
        let mut msgs: Vec<serde_json::Value> = Vec::new();
        for m in messages {
            if m.role == "system" {
                system_text.push_str(&m.content);
                system_text.push('\n');
            } else {
                msgs.push(serde_json::json!({"role": m.role, "content": m.content}));
            }
        }
        let mut body = serde_json::json!({
            "model": self.cfg.model,
            "max_tokens": self.cfg.max_tokens,
            "temperature": self.cfg.temperature,
            "messages": msgs,
            "stream": true,
        });
        if !system_text.is_empty() {
            body["system"] = serde_json::Value::String(system_text);
        }
        let mut req = self.http.post(&url).json(&body);
        if let Some(k) = &self.cfg.api_key {
            req = req.header("x-api-key", k);
            req = req.header("anthropic-version", "2023-06-01");
        }
        let resp = req
            .send()
            .await
            .map_err(|e| format!("upstream anthropic-messages connect failed: {e}"))?;
        let status = resp.status();
        if !status.is_success() {
            let body = resp.text().await.unwrap_or_default();
            return Err(format!(
                "upstream anthropic-messages returned HTTP {}: {}",
                status,
                body.chars().take(400).collect::<String>()
            ));
        }
        let s = resp
            .bytes_stream()
            .map(|item| item.map_err(|e| format!("upstream stream error: {e}")));
        // Anthropic SSE uses `event: content_block_delta` /
        // `event: message_delta` etc. — translate it to the same
        // Hermes Responses API shape the OpenAI translator emits.
        Ok(Box::new(AnthropicToResponsesTranslator::new(
            Box::new(s),
            self.cfg.model.clone(),
        )))
    }
}

// ─────────────────────────────────────────────────────────────────────
// SSE translators (OpenAI Chat Completions ↔ Hermes Responses API)
// ─────────────────────────────────────────────────────────────────────

/// Subset of the Hermes Responses API SSE event surface that we
/// actually need to consume / emit. Used both by the translator
/// stream (which emits these into translated SSE bytes) and by
/// the in-process consumer in `openai_complete_collect` (which
/// reads these off the translated stream). Keeping a single enum
/// means the two paths can't drift apart on event names.
#[derive(Debug, Clone)]
enum ResponsesEvent {
    OutputTextDelta { delta: String },
    OutputItemAdded { item: serde_json::Value },
    Completed {
        response: serde_json::Value,
    },
    /// Carries the upstream error payload so future callers can
    /// surface it (e.g. a CLI flag to dump the raw error). Today
    /// the only consumer (`openai_complete_collect`) treats
    /// `Failed` as a no-op because the underlying stream will
    /// already have raised a `Result::Err` on the wrapper stream
    /// by the time we see this event.
    #[allow(dead_code)]
    Failed {
        response: serde_json::Value,
    },
}

/// Parse a Hermes Responses API SSE byte chunk (the OUTPUT of the
/// translators) into zero or more `ResponsesEvent`s. One byte chunk
/// may carry more than one event because the upstream byte stream
/// doesn't align to SSE block boundaries.
fn parse_responses_sse_events(chunk: &Bytes) -> Vec<ResponsesEvent> {
    let text = String::from_utf8_lossy(chunk);
    let mut out: Vec<ResponsesEvent> = Vec::new();
    // Walk through complete SSE blocks (separated by \n\n). We
    // don't bother with cross-chunk buffering here because the
    // translator emits each event as one self-contained `event:` /
    // `data:` block terminated by a single `\n\n`.
    for block in text.split("\n\n") {
        let block = block.trim_end_matches('\r');
        if block.is_empty() {
            continue;
        }
        let mut event_name: Option<String> = None;
        let mut data_payload: Option<String> = None;
        for raw_line in block.split('\n') {
            let line = raw_line.trim_end_matches('\r');
            if let Some(rest) = line.strip_prefix("event: ") {
                event_name = Some(rest.to_string());
            } else if let Some(rest) = line.strip_prefix("data: ") {
                data_payload = Some(rest.to_string());
            } else if let Some(rest) = line.strip_prefix("data:") {
                // tolerate "data:" with no space (OpenAI sometimes does)
                data_payload = Some(rest.to_string());
            }
        }
        let data_payload = match data_payload {
            Some(d) if d == "[DONE]" => continue,
            Some(d) => d,
            None => continue,
        };
        let json: serde_json::Value = match serde_json::from_str(&data_payload) {
            Ok(v) => v,
            Err(_) => continue,
        };
        match event_name.as_deref() {
            Some("response.output_text.delta") => {
                if let Some(delta) = json.get("delta").and_then(|v| v.as_str()) {
                    out.push(ResponsesEvent::OutputTextDelta {
                        delta: delta.to_string(),
                    });
                }
            }
            Some("response.output_item.added") => {
                if let Some(item) = json.get("item").cloned() {
                    out.push(ResponsesEvent::OutputItemAdded { item });
                }
            }
            Some("response.completed") => {
                let response = json
                    .get("response")
                    .cloned()
                    .unwrap_or_else(|| serde_json::Value::Object(Default::default()));
                out.push(ResponsesEvent::Completed { response });
            }
            Some("response.failed") => {
                let response = json
                    .get("response")
                    .cloned()
                    .unwrap_or_else(|| serde_json::Value::Object(Default::default()));
                out.push(ResponsesEvent::Failed { response });
            }
            _ => {
                // Unknown event names are ignored — the front-end
                // ignores them too (chat.js only handles the four
                // names above).
            }
        }
    }
    out
}

/// Stateful async stream that converts an upstream OpenAI Chat
/// Completions SSE byte stream into a Hermes Responses API SSE
/// byte stream. The translation is line-buffered across the
/// reqwest chunks because a single SSE block can straddle two
/// `Bytes` items.
///
/// Mapping (input → output):
///   `data: {"choices":[{"delta":{"content":"x"}}]}` →
///       `event: response.output_text.delta\ndata: {"delta":"x"}`
///   `data: {"choices":[{"delta":{"tool_calls":[{...}]}}]}` →
///       `event: response.output_item.added\ndata: {"item":{...}}`
///   `data: {"choices":[{"finish_reason":"stop"}]}` →
///       `event: response.completed\ndata: {"response":{"id":...}}`
///   `data: [DONE]` → end of stream, no event emitted
///   Upstream error → result of the same `Err` (translated stream
///       terminates and the embedded server returns 502).
struct OpenAIToResponsesTranslator {
    inner: Box<dyn Stream<Item = Result<Bytes, String>> + Send + Unpin>,
    /// Sticky state for SSE block reassembly. Cleared whenever we
    /// successfully extract a complete block.
    buffer: Vec<u8>,
    /// `id` from the first upstream chunk; reused when emitting
    /// `response.completed` because some OpenAI-compatible servers
    /// (vllm, llamacpp) emit a separate empty `data:` block as
    /// their final frame, with no `id` attached.
    response_id: Option<String>,
    /// `model` echoed back into `response.completed` so the
    /// webview's debug panel can show which model answered.
    model: String,
}

impl OpenAIToResponsesTranslator {
    fn new(
        inner: Box<dyn Stream<Item = Result<Bytes, String>> + Send + Unpin>,
        model: String,
    ) -> Self {
        Self {
            inner,
            buffer: Vec::with_capacity(4096),
            response_id: None,
            model,
        }
    }

    /// Try to extract one translated SSE block from the buffer.
    /// Returns `Some((Bytes, consumed))` if a `\n\n` was found and
    /// the block was successfully translated; `None` if we still
    /// need more upstream data. Errors are returned as
    /// `Some(Err(_))`.
    fn try_translate(&mut self) -> Option<Result<Bytes, String>> {
        // Find the next blank-line terminator.
        let needle: &[u8] = b"\n\n";
        let end = find_subseq(&self.buffer, needle)?;
        let block_bytes: Vec<u8> = self.buffer.drain(..end + needle.len()).collect();
        let block = match std::str::from_utf8(&block_bytes) {
            Ok(s) => s,
            Err(_) => return Some(Err("upstream SSE contained invalid UTF-8".to_string())),
        };
        // Drop the trailing \n\n that we already used to find
        // the block boundary; everything before is the actual
        // SSE block content.
        let block = block.trim_end_matches('\n');
        // Walk the block looking for `data: ...` lines. We don't
        // care about event names — the upstream is OpenAI, so
        // every meaningful line is a `data:` line.
        let mut data_payload: Option<String> = None;
        for raw_line in block.split('\n') {
            let line = raw_line.trim_end_matches('\r');
            if let Some(rest) = line.strip_prefix("data: ") {
                data_payload = Some(rest.to_string());
            } else if let Some(rest) = line.strip_prefix("data:") {
                if !rest.is_empty() {
                    data_payload = Some(rest.to_string());
                }
            }
        }
        let data = match data_payload {
            Some(d) if d == "[DONE]" => return Some(Ok(Bytes::new())), // signal end-of-stream
            Some(d) => d,
            None => return Some(Ok(Bytes::new())), // blank block, skip silently
        };
        let json: serde_json::Value = match serde_json::from_str(&data) {
            Ok(v) => v,
            Err(_) => return Some(Ok(Bytes::new())), // skip malformed frames
        };
        // Remember the response id the first time we see it.
        if self.response_id.is_none() {
            if let Some(id) = json.get("id").and_then(|v| v.as_str()) {
                self.response_id = Some(id.to_string());
            }
        }
        let translated = translate_openai_data(&json, &self.model, self.response_id.as_deref());
        match translated {
            Some(bytes) => Some(Ok(bytes)),
            None => Some(Ok(Bytes::new())), // drop, emit nothing
        }
    }
}

impl Stream for OpenAIToResponsesTranslator {
    type Item = Result<Bytes, String>;

    fn poll_next(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<Option<Self::Item>> {
        use std::task::Poll;
        // First, drain anything we already have buffered.
        loop {
            // If we already have a complete block, emit it.
            if let Some(result) = self.try_translate() {
                return Poll::Ready(Some(result));
            }
            // Otherwise pull one more upstream chunk and append.
            match std::pin::Pin::new(&mut self.inner).poll_next(cx) {
                Poll::Ready(Some(Ok(chunk))) => {
                    self.buffer.extend_from_slice(&chunk);
                }
                Poll::Ready(Some(Err(e))) => {
                    return Poll::Ready(Some(Err(e)));
                }
                Poll::Ready(None) => {
                    // Upstream closed. Flush any partial buffer
                    // (rare, but possible if the provider drops
                    // mid-event without a terminating \n\n).
                    if self.buffer.is_empty() {
                        return Poll::Ready(None);
                    }
                    self.buffer.clear();
                    return Poll::Ready(None);
                }
                Poll::Pending => return Poll::Pending,
            }
        }
    }
}

/// Convert one OpenAI Chat Completions data frame (the inner
/// JSON of a `data: ...` line) into zero or more Hermes Responses
/// API SSE blocks. Returning `None` means "no event to emit for
/// this frame" (e.g. role-only chunk, keep-alive ping). Returning
/// `Some(bytes)` with concatenated blocks means the frame produced
/// multiple events (e.g. content + tool call in the same chunk);
/// all of them are emitted as one downstream chunk so the
/// webview's parser still sees the same `event:`/`data:` boundaries.
fn translate_openai_data(
    json: &serde_json::Value,
    model: &str,
    response_id: Option<&str>,
) -> Option<Bytes> {
    let choices = json.get("choices").and_then(|v| v.as_array())?;
    let mut out = String::new();
    for choice in choices {
        // 1) delta.content → response.output_text.delta
        if let Some(content) = choice
            .get("delta")
            .and_then(|d| d.get("content"))
            .and_then(|c| c.as_str())
        {
            if !content.is_empty() {
                let payload = serde_json::json!({ "delta": content });
                push_sse_block(&mut out, "response.output_text.delta", &payload);
            }
        }
        // 2) delta.tool_calls[*] → response.output_item.added
        //    (one event per tool call entry; the webview only
        //    needs `name` / `call_id` / `arguments` for tool UI
        //    and the OpenAI format already provides those.)
        if let Some(tool_calls) = choice
            .get("delta")
            .and_then(|d| d.get("tool_calls"))
            .and_then(|tc| tc.as_array())
        {
            for tc in tool_calls {
                // OpenAI puts the tool call's stable id in `id`
                // (string). Some OpenAI-compatible servers
                // (llama.cpp's server, older vllm) use a numeric
                // index instead. Accept both.
                let call_id: String = tc
                    .get("id")
                    .and_then(|v| v.as_str())
                    .map(str::to_string)
                    .or_else(|| {
                        tc.get("id")
                            .and_then(|v| v.as_i64())
                            .map(|n| n.to_string())
                    })
                    .unwrap_or_default();
                let name = tc
                    .get("function")
                    .and_then(|f| f.get("name"))
                    .and_then(|v| v.as_str())
                    .unwrap_or_default();
                let arguments = tc
                    .get("function")
                    .and_then(|f| f.get("arguments"))
                    .and_then(|v| v.as_str())
                    .unwrap_or_default();
                let item = serde_json::json!({
                    "type": "function_call",
                    "id": call_id,
                    "call_id": call_id,
                    "name": name,
                    "arguments": arguments,
                    "status": "in_progress",
                });
                let payload = serde_json::json!({ "item": item });
                push_sse_block(&mut out, "response.output_item.added", &payload);
            }
        }
        // 3) finish_reason → response.completed
        if let Some(fr) = choice
            .get("finish_reason")
            .and_then(|v| v.as_str())
        {
            if !fr.is_empty() && fr != "null" {
                let response = serde_json::json!({
                    "id": response_id.unwrap_or(""),
                    "model": model,
                    "finish_reason": fr,
                });
                let payload = serde_json::json!({ "response": response });
                push_sse_block(&mut out, "response.completed", &payload);
            }
        }
    }
    if out.is_empty() {
        None
    } else {
        Some(Bytes::from(out))
    }
}

/// Append one Hermes Responses API SSE block to an in-progress
/// buffer. Companion to `emit_sse_block` for the case where one
/// input frame produces multiple output events.
fn push_sse_block(buf: &mut String, event: &str, payload: &serde_json::Value) {
    buf.push_str("event: ");
    buf.push_str(event);
    buf.push('\n');
    buf.push_str("data: ");
    buf.push_str(&payload.to_string());
    buf.push_str("\n\n");
}

/// Find the first occurrence of `needle` inside `haystack`.
/// Used to locate SSE block terminators without dragging in a
/// full search library. Returns the byte offset of the first
/// match.
fn find_subseq(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    if needle.is_empty() || haystack.len() < needle.len() {
        return None;
    }
    haystack
        .windows(needle.len())
        .position(|window| window == needle)
}

/// Anthropic Messages API → Hermes Responses API SSE translator.
/// Same shape as the OpenAI one: a stateful Stream wrapper that
/// buffers and re-emits.
struct AnthropicToResponsesTranslator {
    inner: Box<dyn Stream<Item = Result<Bytes, String>> + Send + Unpin>,
    buffer: Vec<u8>,
    response_id: Option<String>,
    model: String,
}

impl AnthropicToResponsesTranslator {
    fn new(
        inner: Box<dyn Stream<Item = Result<Bytes, String>> + Send + Unpin>,
        model: String,
    ) -> Self {
        Self {
            inner,
            buffer: Vec::with_capacity(4096),
            response_id: None,
            model,
        }
    }

    fn try_translate(&mut self) -> Option<Result<Bytes, String>> {
        let end = find_subseq(&self.buffer, b"\n\n")?;
        let block_bytes: Vec<u8> = self.buffer.drain(..end + 2).collect();
        let block = match std::str::from_utf8(&block_bytes) {
            Ok(s) => s.trim_end_matches("\n\n"),
            Err(_) => return Some(Err("upstream SSE contained invalid UTF-8".to_string())),
        };
        let mut event_name: Option<String> = None;
        let mut data_payload: Option<String> = None;
        for raw_line in block.split('\n') {
            let line = raw_line.trim_end_matches('\r');
            if let Some(rest) = line.strip_prefix("event: ") {
                event_name = Some(rest.to_string());
            } else if let Some(rest) = line.strip_prefix("data: ") {
                data_payload = Some(rest.to_string());
            } else if let Some(rest) = line.strip_prefix("data:") {
                if !rest.is_empty() {
                    data_payload = Some(rest.to_string());
                }
            }
        }
        let data = match data_payload {
            Some(d) => d,
            None => return Some(Ok(Bytes::new())),
        };
        let json: serde_json::Value = match serde_json::from_str(&data) {
            Ok(v) => v,
            Err(_) => return Some(Ok(Bytes::new())),
        };
        if self.response_id.is_none() {
            if let Some(id) = json.get("message").and_then(|m| m.get("id")).and_then(|v| v.as_str()) {
                self.response_id = Some(id.to_string());
            }
        }
        let translated = translate_anthropic_data(
            event_name.as_deref().unwrap_or(""),
            &json,
            &self.model,
            self.response_id.as_deref(),
        );
        match translated {
            Some(bytes) => Some(Ok(bytes)),
            None => Some(Ok(Bytes::new())),
        }
    }
}

impl Stream for AnthropicToResponsesTranslator {
    type Item = Result<Bytes, String>;

    fn poll_next(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<Option<Self::Item>> {
        use std::task::Poll;
        loop {
            if let Some(result) = self.try_translate() {
                return Poll::Ready(Some(result));
            }
            match std::pin::Pin::new(&mut self.inner).poll_next(cx) {
                Poll::Ready(Some(Ok(chunk))) => {
                    self.buffer.extend_from_slice(&chunk);
                }
                Poll::Ready(Some(Err(e))) => return Poll::Ready(Some(Err(e))),
                Poll::Ready(None) => {
                    if self.buffer.is_empty() {
                        return Poll::Ready(None);
                    }
                    self.buffer.clear();
                    return Poll::Ready(None);
                }
                Poll::Pending => return Poll::Pending,
            }
        }
    }
}

fn translate_anthropic_data(
    event: &str,
    json: &serde_json::Value,
    model: &str,
    response_id: Option<&str>,
) -> Option<Bytes> {
    let mut out = String::new();
    match event {
        "content_block_delta" => {
            let text = json
                .get("delta")
                .and_then(|d| d.get("text"))
                .and_then(|v| v.as_str())
                .unwrap_or("");
            if text.is_empty() {
                return None;
            }
            let payload = serde_json::json!({ "delta": text });
            push_sse_block(&mut out, "response.output_text.delta", &payload);
        }
        "message_delta" => {
            let stop_reason = json
                .get("delta")
                .and_then(|d| d.get("stop_reason"))
                .and_then(|v| v.as_str())
                .unwrap_or("end_turn");
            let response = serde_json::json!({
                "id": response_id.unwrap_or(""),
                "model": model,
                "finish_reason": stop_reason,
            });
            let payload = serde_json::json!({ "response": response });
            push_sse_block(&mut out, "response.completed", &payload);
        }
        "message_stop" => {
            // Final marker. Some clients wait for this; the
            // webview's chat.js handles `response.completed`
            // directly, so emit a duplicate completed with an
            // empty finish_reason to flush any pending state.
            let response = serde_json::json!({
                "id": response_id.unwrap_or(""),
                "model": model,
                "finish_reason": "end_turn",
            });
            let payload = serde_json::json!({ "response": response });
            push_sse_block(&mut out, "response.completed", &payload);
        }
        "error" => {
            let response = serde_json::json!({
                "id": response_id.unwrap_or(""),
                "model": model,
                "error": json,
            });
            let payload = serde_json::json!({ "response": response });
            push_sse_block(&mut out, "response.failed", &payload);
        }
        _ => return None,
    }
    if out.is_empty() {
        None
    } else {
        Some(Bytes::from(out))
    }
}

#[tauri::command]
pub async fn hermes_llm_complete(cfg: LLMServiceConfig, messages: Vec<VLMMessage>, tools: Option<Vec<serde_json::Value>>) -> Result<VLMResponse, String> {
    LLMService::new(cfg)?.complete(messages, tools).await
}

/// Simple LLM completion for the automation engine.
///
/// Takes a plain-text prompt, sends it to the cloud LLM via MCP
/// `llm.stream_request`, and returns the generated text. Used by
/// `AutomationEngine::resolve_llm_prompt` to fill input fields
/// (e.g. "type LLM-generated text into a form field").
///
/// This function does NOT require a local provider config — it
/// reuses the same cloud MCP path as the front-end's chat and the
/// cron scheduler's `run_cron_prompt`.
///
/// **Device token**: The token is stored in the front-end's
/// `localStorage` (`trae_device_token`) and is not directly
/// accessible from Rust. The MCP call will proceed without a
/// token; if the server requires auth, it will return an error
/// that we propagate to the user. The front-end can pre-register
/// the token via `mcp_call_v2` before triggering automation.
pub async fn hermes_llm_simple_complete(prompt: String) -> Result<String, String> {
    use std::time::Duration;

    // Build HTTP client with generous timeout (LLM can take a while).
    let client = HttpClient::builder()
        .no_proxy()
        .timeout(Duration::from_secs(120))
        .build()
        .map_err(|e| format!("LLM HTTP client build failed: {}", e))?;

    let session_id = format!("auto-llm-{}", uuid::Uuid::new_v4());

    let params = serde_json::json!({
        "session_id": session_id,
        "messages": [ { "role": "user", "content": prompt } ],
        "stream": true,
    });

    // Call MCP without token — the server may or may not require auth.
    // If auth is required, the error will be propagated clearly.
    let resp = crate::commands::mcp_proxy::mcp_call_v2_inner(
        &client,
        "llm.stream_request",
        params,
        None,
    )
    .await?;

    // Parse response: { ok: true, data: { content: "..." } }
    let content = resp
        .get("data")
        .and_then(|d| d.get("content"))
        .and_then(|c| c.as_str())
        .unwrap_or("")
        .trim()
        .to_string();

    if content.is_empty() {
        // Check for error details
        if let Some(e) = resp.get("error") {
            let msg = e
                .get("message")
                .and_then(|m| m.as_str())
                .or_else(|| e.as_str())
                .unwrap_or("LLM 调用失败");
            return Err(format!("LLM 调用失败：{}", msg));
        }
        return Err("LLM 返回空内容".to_string());
    }

    Ok(content)
}

/// MCP-based LLM completion accepting a full messages vec (system + user).
///
/// Mirrors `hermes_llm_simple_complete` but allows a system prompt + multi-turn
/// context. **No `LLMServiceConfig` needed** — routes through the same MCP
/// `llm.stream_request` action that the frontend chat uses, so it works whenever
/// the embedded gateway / MCP proxy is up.
///
/// Used by the Phase 1 self-evolution path (`SessionAnalyzer::analyze_window`
/// + `EvolutionGate::generate_skill_md`) so the orchestrator never has to plumb
/// an `LLMServiceConfig` — the LLM is "always on" via MCP, and an MCP failure
/// surfaces as `Err` which the caller treats as degraded-heuristic fallback.
///
/// Returns just the assistant content string (no tool-call metadata).
pub async fn hermes_llm_complete_messages(messages: Vec<VLMMessage>) -> Result<String, String> {
    use std::time::Duration;

    let client = HttpClient::builder()
        .no_proxy()
        .timeout(Duration::from_secs(120))
        .build()
        .map_err(|e| format!("LLM HTTP client build failed: {}", e))?;

    let session_id = format!("evo-llm-{}", uuid::Uuid::new_v4());

    // Project messages to the {role, content} shape MCP expects. Drop other
    // VLMMessage fields (images/tools are irrelevant for evolution analysis).
    let msgs: Vec<serde_json::Value> = messages
        .into_iter()
        .map(|m| {
            serde_json::json!({
                "role": m.role,
                "content": m.content,
            })
        })
        .collect();

    let params = serde_json::json!({
        "session_id": session_id,
        "messages": msgs,
        "stream": true,
    });

    let resp = crate::commands::mcp_proxy::mcp_call_v2_inner(
        &client,
        "llm.stream_request",
        params,
        None,
    )
    .await?;

    let content = resp
        .get("data")
        .and_then(|d| d.get("content"))
        .and_then(|c| c.as_str())
        .unwrap_or("")
        .trim()
        .to_string();

    if content.is_empty() {
        if let Some(e) = resp.get("error") {
            let msg = e
                .get("message")
                .and_then(|m| m.as_str())
                .or_else(|| e.as_str())
                .unwrap_or("LLM 调用失败");
            return Err(format!("LLM 调用失败：{}", msg));
        }
        return Err("LLM 返回空内容".to_string());
    }

    Ok(content)
}
