// Copyright (c) 2026 MeeJoy
//
// IM 后端自动回复循环。
//
// 目标：让 IM 通信真正双向，且不依赖前端窗口挂载。传统实现里入站消息
// 由 `spawn_inbound_forwarder` 转发给前端 `TupaiChatScene`，前端再驱动
// LLM 并回发——如果主窗口隐藏 / 关闭 / 会话场景未挂载，入站消息就被丢弃。
//
// 本模块在 Rust 后端直接订阅 adapter 的入站广播，收到 `kind == "message"`
// 的事件后：
//   1. 解析 payload 里的 target/text；
//   2. 回声去重（避免回复自己的回显消息导致死循环）；
//   3. 并发守卫（同一 channel:target 串行处理，避免多个 LLM 并发回复）；
//   4. 调云端 LLM（`mcp_call_v2_inner` 走 `llm.stream_request`，与前端同路径）；
//   5. 通过 adapter 回发到原 target；
//   6. 额外 emit `im_adapter_event`（kind = "backend_reply"）让前端把回复
//      渲染进会话（若前端恰好挂载），同时前端用该事件识别后端已处理，
//      跳过自己的自动回复避免双回复。
//
// 与 `spawn_inbound_forwarder` 的关系：forwarder 仍负责把原始入站消息推给
// 前端做 UI 镜像 / 桥接会话展示；auto_reply loop 负责「后端直接回」。
// 两者各自订阅 broadcast，互不干扰。前端通过 `im_status` 的
// `backend_auto_reply` 字段知道哪些渠道由后端回复，从而跳过自己的回复。

use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use chrono::Utc;
use tauri::{AppHandle, Emitter, Manager};

use super::adapter_base::{IMAdapter, IMAdapterEvent};
use crate::hermes::agent_loop::AgentLoop;
use crate::hermes::types::VLMMessage;

/// 回声去重窗口：5s 内发过的 (channel,target,text) 视为回显，忽略入站。
const ECHO_WINDOW_SECS: u64 = 5;
/// 每会话最多保留的 LLM 上下文轮数（消息条数，超限丢最旧的）。
const MAX_CONTEXT_TURNS: usize = 20;

/// system prompt：提示 agent 处于 IM 场景，可按会话内容意图调用通用工具
/// （execute_skill / mcp_call / memory_search / vlm_query 等，由 ToolRegistry2 注册）。
const SYSTEM_PROMPT: &str = "\
你是 AIMarketing 桌面助手，通过 IM 与用户对话。请直接、简洁地回答用户的问题。\
如果你判断用户的问题需要执行技能、查询记忆、调用系统能力，可以调用下方提供的工具来达成目标，\
不要凭猜测编造结果。纯聊天问题时直接回答。";

/// 全局回声去重表：(channel:target, text) → 最近发送时刻。
/// 跨 adapter 实例共享，避免同一文本在替换 adapter 后仍触发回复。
static ECHO_STORE: Mutex<Option<Arc<Mutex<HashMap<String, VecDeque<(String, Instant)>>>>>> =
    Mutex::new(None);

fn echo_store() -> Arc<Mutex<HashMap<String, VecDeque<(String, Instant)>>>> {
    let mut guard = ECHO_STORE.lock().unwrap();
    if let Some(s) = guard.as_ref() {
        return s.clone();
    }
    let store = Arc::new(Mutex::new(HashMap::new()));
    *guard = Some(store.clone());
    store
}

/// 记录一次「我们发出的消息」，供回声去重。
pub fn record_outbound(channel_id: &str, target: &str, text: &str) {
    let key = format!("{}:{}", channel_id, target);
    let store = echo_store();
    let mut guard = store.lock().unwrap();
    let q = guard.entry(key).or_default();
    q.push_back((text.to_string(), Instant::now()));
    // 只保留窗口内最近 32 条，防无限增长。
    while q.len() > 32 {
        q.pop_front();
    }
    let now = Instant::now();
    q.retain(|(_, ts)| now.duration_since(*ts) < Duration::from_secs(ECHO_WINDOW_SECS));
}

/// 判断是否最近刚发过这条 (channel,target,text) —— 是则视为回显。
fn is_recent_outbound(channel_id: &str, target: &str, text: &str) -> bool {
    let key = format!("{}:{}", channel_id, target);
    let store = echo_store();
    let guard = store.lock().unwrap();
    let Some(q) = guard.get(&key) else { return false };
    let now = Instant::now();
    q.iter()
        .any(|(t, ts)| t == text && now.duration_since(*ts) < Duration::from_secs(ECHO_WINDOW_SECS))
}

/// 每会话并发守卫：正在回复的 (channel:target) 集合。
struct ReplyGuard {
    active: Mutex<HashSet<String>>,
}

impl ReplyGuard {
    fn new() -> Self {
        Self {
            active: Mutex::new(HashSet::new()),
        }
    }

    fn try_acquire(&self, conv_key: &str) -> bool {
        let mut guard = self.active.lock().unwrap();
        guard.insert(conv_key.to_string())
    }

    fn release(&self, conv_key: &str) {
        self.active.lock().unwrap().remove(conv_key);
    }
}

/// 每会话 LLM 上下文（简单内存窗口）。存 `VLMMessage` 以便直接喂给
/// `AgentLoop::run()` 做 ReAct（assistant+tool 消息会回填进历史）。
#[derive(Default)]
struct ConvContext {
    messages: VecDeque<VLMMessage>,
}

impl ConvContext {
    fn push_user(&mut self, text: &str) {
        self.messages.push_back(VLMMessage {
            role: "user".to_string(),
            content: text.to_string(),
            ..Default::default()
        });
        self.trim();
    }

    /// 把 ReAct 循环产出的完整历史写回（可能是空——LLM 只发 tool_call 时）。
    fn set_history(&mut self, history: Vec<VLMMessage>) {
        let mut q: VecDeque<VLMMessage> = history.into();
        while q.len() > MAX_CONTEXT_TURNS {
            q.pop_front();
        }
        self.messages = q;
    }

    fn trim(&mut self) {
        while self.messages.len() > MAX_CONTEXT_TURNS {
            self.messages.pop_front();
        }
    }
}

struct ReplyLoopState {
    guard: ReplyGuard,
    /// channel:target → 上下文
    contexts: Mutex<HashMap<String, ConvContext>>,
}

impl ReplyLoopState {
    fn new() -> Self {
        Self {
            guard: ReplyGuard::new(),
            contexts: Mutex::new(HashMap::new()),
        }
    }
}

/// 从入站消息事件里解析出 (target, text, from_label)。
/// payload 兼容多种字段名（与前端 TupaiChatScene 解析一致）。
fn parse_inbound(payload: &serde_json::Value) -> Option<(String, String, String)> {
    let target = payload
        .get("target")
        .or_else(|| payload.get("from"))
        .or_else(|| payload.get("sender"))
        .and_then(|v| v.as_str())
        .map(str::to_string)?;
    let text = payload
        .get("text")
        .or_else(|| payload.get("content"))
        .or_else(|| payload.get("message"))
        .and_then(|v| v.as_str())
        .map(str::to_string)
        .unwrap_or_default();
    if text.is_empty() {
        return None;
    }
    let from_label = payload
        .get("from_name")
        .or_else(|| payload.get("sender_name"))
        .or_else(|| payload.get("nickname"))
        .and_then(|v| v.as_str())
        .map(str::to_string)
        .unwrap_or_else(|| target.clone());
    Some((target, text, from_label))
}

/// 用全局 `AgentLoop`（注册了 execute_skill / mcp_call / memory_search 等通用工具）
/// 跑 ReAct 循环，返回最终文本回复。
///
/// - 按会话内容意图触发 tooling call：LLM 返回 tool_calls → AgentLoop 经
///   `ToolRegistry2` 并行执行 → 结果以 `role="tool"` 回填 → 继续迭代直到纯文本。
/// - `device_token` 每次读取最新值，避免 token 过期后用旧值。
async fn run_react(
    app: &AppHandle,
    channel_id: &str,
    target: &str,
    messages: &mut Vec<VLMMessage>,
    device_token: Option<&str>,
) -> Result<String, String> {
    let agent_loop: Arc<AgentLoop> = app
        .try_state::<Arc<AgentLoop>>()
        .map(|s| s.inner().clone())
        .ok_or_else(|| "AgentLoop not managed".to_string())?;
    let session_id = format!("im:{}:{}", channel_id, target);
    agent_loop
        .run(messages, &session_id, device_token)
        .await
        .map_err(|e| e.to_string())
}

/// Spawn 一个后端自动回复循环，订阅 adapter 入站广播。
///
/// 与 `spawn_inbound_forwarder` 并行存在：forwarder 负责把原始入站推给前端
/// 做镜像展示；本循环负责「后端直接调 LLM 并回发」，实现不依赖前端的双向。
///
/// - `app`：用于 emit `im_adapter_event`（kind = "backend_reply"）给前端展示。
/// - `adapter`：回发消息用（内部不 clone 出 task，通过 subscribe 拿 receiver
///   后立即 drop adapter，与 forwarder 一致，避免 task 持有 Arc 泄漏）。
/// - `channel_id`：渠道 id（log 与事件用）。
/// - `device_token`：HermesAppState 的 device_token（Arc<RwLock<Option<String>>>），
///   每次调用 LLM 前读取最新值，避免 token 过期后一直用旧值。
///
/// 循环退出条件：adapter 被销毁（broadcast Sender drop → Closed）。与
/// forwarder 相同的生命周期管理。
pub fn spawn_inbound_reply_loop(
    app: AppHandle,
    adapter: Arc<dyn IMAdapter>,
    channel_id: String,
    device_token: Arc<std::sync::RwLock<Option<String>>>,
) {
    let mut rx = adapter.subscribe();
    drop(adapter);
    let state = Arc::new(ReplyLoopState::new());
    tauri::async_runtime::spawn(async move {
        loop {
            let ev: IMAdapterEvent = match rx.recv().await {
                Ok(ev) => ev,
                Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
            };
            if ev.kind != "message" {
                continue;
            }
            // 只处理本渠道的事件（binding_id 即 channel_id）。
            if ev.binding_id != channel_id {
                continue;
            }
            let Some((target, text, _from_label)) = parse_inbound(&ev.payload) else {
                continue;
            };
            if target.is_empty() || text.is_empty() {
                continue;
            }

            // 回声去重：这条文本是我们刚发的回显 → 跳过，防死循环。
            if is_recent_outbound(&channel_id, &target, &text) {
                continue;
            }

            // 桥接渠道跳过：前端 TupaiChatScene 勾选的渠道由前端 runMainLLM
            // （带 skillPrompt 技能上下文）驱动回复并回发 IM。后端再回复会
            // 造成双回复且丢失技能会话内容，因此这里直接跳过。
            if is_bridged_channel(&app, &channel_id) {
                continue;
            }

            let conv_key = format!("{}:{}", channel_id, target);
            // 并发守卫：同一会话同时只允许一个 LLM 请求。
            if !state.guard.try_acquire(&conv_key) {
                continue;
            }

            // 取出上下文快照（VLMMessage），在锁外跑 ReAct（避免持锁 await）。
            let context_snapshot = {
                let mut ctx = state.contexts.lock().unwrap();
                let conv = ctx.entry(conv_key.clone()).or_default();
                conv.push_user(&text);
                ConvContext {
                    messages: conv.messages.clone(),
                }
            };
            // 加一条 system 提示，说明可通过工具按意图执行技能（通用工具）。
            // 前缀插入，保证 agent 知道 IM 场景 + 工具可用。
            let mut react_messages: Vec<VLMMessage> = Vec::with_capacity(context_snapshot.messages.len() + 1);
            react_messages.push(VLMMessage {
                role: "system".to_string(),
                content: SYSTEM_PROMPT.to_string(),
                ..Default::default()
            });
            react_messages.extend(context_snapshot.messages.iter().cloned());

            let token = device_token
                .read()
                .map(|t| t.clone())
                .unwrap_or(None);

            // ReAct 循环：按会话内容意图触发 tooling call，直到 LLM 输出纯文本。
            let result = run_react(&app, &channel_id, &target, &mut react_messages, token.as_deref()).await;

            match result {
                Ok(reply) => {
                    // 回写 ReAct 完整历史（assistant+tool 消息），超窗口丢最旧。
                    {
                        let mut ctx = state.contexts.lock().unwrap();
                        if let Some(conv) = ctx.get_mut(&conv_key) {
                            conv.set_history(react_messages.clone());
                        }
                    }
                    // 记录回发（回声去重），回发到原 target。
                    record_outbound(&channel_id, &target, &reply);
                    if let Some(adapter) = pool_get_adapter(&app, &channel_id).await {
                        match adapter.send(&target, &reply).await {
                            Ok(_) => {
                                // emit backend_reply 事件给前端渲染。
                                let _ = app.emit(
                                    "im_adapter_event",
                                    IMAdapterEvent {
                                        binding_id: channel_id.clone(),
                                        kind: "backend_reply".to_string(),
                                        payload: serde_json::json!({
                                            "channelId": channel_id,
                                            "target": target,
                                            "content": reply,
                                        }),
                                        ts: Utc::now().timestamp_millis(),
                                    },
                                );
                            }
                            Err(e) => {
                                tracing::warn!("[im_auto_reply] send failed channel={} target={}: {}", channel_id, target, e);
                            }
                        }
                    }
                }
                Err(e) => {
                    tracing::warn!("[im_auto_reply] react failed channel={} target={}: {}", channel_id, target, e);
                }
            }
            state.guard.release(&conv_key);
        }
    });
}

/// 从 AdapterPool 取出已连接 adapter（用于回发）。失败返回 None。
async fn pool_get_adapter(app: &AppHandle, channel_id: &str) -> Option<Arc<dyn IMAdapter>> {
    use tauri::Manager;
    let pool = app.state::<super::channel_registry::SharedAdapterPool>();
    pool.get(channel_id).await
}

/// 判断渠道是否已被前端桥接（前端 TupaiChatScene 勾选，由其驱动回复）。
/// 读取 `im_set_bridged` 维护的共享集合。state 未初始化 / 心跳过期时视为未桥接。
fn is_bridged_channel(app: &AppHandle, channel_id: &str) -> bool {
    use tauri::Manager;
    let Some(shared) = app.try_state::<crate::commands::im_config::SharedBridgedChannels>() else {
        return false;
    };
    let guard = match shared.read() {
        Ok(g) => g,
        Err(_) => return false,
    };
    // 心跳 TTL 内才视为桥接；超过 TTL（前端可能已挂/窗口关闭）视为未桥接，
    // 让后端自动回复恢复接管，避免渠道永久静默。
    let Some(last_heartbeat) = guard.get(channel_id) else {
        return false;
    };
    last_heartbeat.elapsed()
        < std::time::Duration::from_secs(crate::commands::im_config::BRIDGE_HEARTBEAT_TTL_SECS)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_inbound_handles_multiple_field_shapes() {
        // { target, text } 形态（中继网关）
        let p1 = serde_json::json!({ "target": "user1", "text": "hello", "from_name": "Alice" });
        let (t1, x1, l1) = parse_inbound(&p1).unwrap();
        assert_eq!(t1, "user1");
        assert_eq!(x1, "hello");
        assert_eq!(l1, "Alice");

        // { from, content } 形态（部分适配器）
        let p2 = serde_json::json!({ "from": "user2", "content": "hi" });
        let (t2, x2, _) = parse_inbound(&p2).unwrap();
        assert_eq!(t2, "user2");
        assert_eq!(x2, "hi");

        // 空文本 → None（不触发 LLM）
        assert!(parse_inbound(&serde_json::json!({ "target": "u", "text": "" })).is_none());
        assert!(parse_inbound(&serde_json::json!({ "text": "no-target" })).is_none());
    }

    #[test]
    fn echo_dedup_blocks_recent_outbound() {
        record_outbound("ch", "target", "我是回复");
        assert!(is_recent_outbound("ch", "target", "我是回复"));
        // 不同文本 / 不同 target 不误伤
        assert!(!is_recent_outbound("ch", "target", "其他文本"));
        assert!(!is_recent_outbound("ch", "other", "我是回复"));
        // 清空后不再命中
        ECHO_STORE.lock().unwrap().take();
        assert!(!is_recent_outbound("ch", "target", "我是回复"));
    }

    #[test]
    fn conv_context_trims_to_budget() {
        let mut c = ConvContext::default();
        for i in 0..40 {
            c.push_user(&format!("msg-{}", i));
        }
        // 40 条 user 超过 MAX_CONTEXT_TURNS=20 → 只保留最后 20 条
        assert!(c.messages.len() <= MAX_CONTEXT_TURNS);
        let first = c.messages.front().unwrap();
        assert_eq!(first.content, "msg-20");
    }

    #[test]
    fn conv_context_set_history_replaces_and_trims() {
        let mut c = ConvContext::default();
        c.push_user("seed");
        // 模拟 ReAct 回填 30 条（assistant + tool）→ 应裁剪到窗口。
        let mut history: Vec<VLMMessage> = (0..30)
            .map(|i| VLMMessage {
                role: if i % 2 == 0 { "assistant".into() } else { "tool".into() },
                content: format!("h{}", i),
                ..Default::default()
            })
            .collect();
        c.set_history(history.clone());
        assert!(c.messages.len() <= MAX_CONTEXT_TURNS);
        // 最后一个元素应是历史末尾
        let last = c.messages.back().unwrap();
        assert_eq!(last.content, "h29");
        // set_history 替换而非追加：再 set 一次短历史应整体覆盖
        history.truncate(2);
        c.set_history(history);
        assert_eq!(c.messages.len(), 2);
    }

    #[test]
    fn reply_guard_excludes_concurrent_conv() {
        let g = ReplyGuard::new();
        assert!(g.try_acquire("ch:target"));
        assert!(!g.try_acquire("ch:target"));
        assert!(g.try_acquire("ch:other"));
        g.release("ch:target");
        assert!(g.try_acquire("ch:target"));
    }
}
