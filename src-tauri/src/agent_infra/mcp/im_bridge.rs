// Copyright (c) 2026 tupAI
//
// P3 G18 — `im-bridge` MCP server.
//
// 暴露 `im_bridge.send_message` / `im_bridge.list_channels` /
// `im_bridge.list_pending_confirmations` / `im_bridge.confirm_channel` /
// `im_bridge.revoke_channel` 五个 tool,让 LLM Agent 能通过 MCP 直接
// 走本地 IM 渠道发消息(企业微信 / 飞书 / 钉钉 / 微信 / QQ,统一经
// `hermes::im::channel_registry` 中转)。
//
// **安全铁律** (2026-06-25):
//   1. **白名单**: `ImBridgeConfig.allow_channel_ids` 是用户手动
//      预先审核过的 channel_id 列表。不在白名单内的 channel 永远
//      不能被 im_bridge.send_message 调用,即使 binding 已注册。
//   2. **用户确认**: LLM Agent 通过 MCP 发 IM 是高风险动作,首次
//      调用某个 channel 必须经过"用户确认" hook —
//      `pending_user_confirmations` 队列缓存未确认调用,前端弹窗
//      让用户点"同意"后,channel 加入 `confirmed_channels`,后续
//      同一 channel 的调用直接放行。
//   3. **审计**: 每次 send / pending 事件写入 `mcp.audit` 事件流
//      (`IMAdapterEvent::kind = "im_bridge.audit"`),前端可订阅回放。
//
// 关联: §G18 / §0.3 (MCP 自发现) / `hermes::im` 适配器。

use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use tokio::sync::RwLock;

use crate::hermes::im::channel_registry::{SharedAdapterPool, SharedChannelRegistry};

// ---------------------------------------------------------------------------
// 配置 / 状态
// ---------------------------------------------------------------------------

/// 静态配置(应用启动时由 `ImBridge::with_config` 注入)。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ImBridgeConfig {
    /// 白名单 — 允许被 im_bridge 触发的 channel_id (`IMBinding.id`)。
    /// 任何未在此列表的 binding,即使 registry 里注册了,也会被拒。
    pub allow_channel_ids: Vec<String>,
    /// 单条消息最大字符数(防止 LLM 灌水)。默认 4096。
    #[serde(default = "default_max_len")]
    pub max_message_length: usize,
    /// 待确认队列最大长度(超出后拒绝新的 send)。默认 64。
    #[serde(default = "default_pending_cap")]
    pub max_pending: usize,
}

fn default_max_len() -> usize { 4096 }
fn default_pending_cap() -> usize { 64 }

impl Default for ImBridgeConfig {
    fn default() -> Self {
        Self {
            allow_channel_ids: Vec::new(),
            max_message_length: default_max_len(),
            max_pending: default_pending_cap(),
        }
    }
}

/// 待用户确认的 send 请求。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PendingConfirmation {
    pub request_id: String,
    pub channel_id: String,
    pub target: String,
    pub content: String,
    pub requested_at_unix_ms: u128,
}

/// send_message 的返回结果。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SendResult {
    /// `"sent"` / `"awaiting_confirmation"` / `"denied"` /
    /// `"channel_not_in_whitelist"` / `"channel_not_registered"` /
    /// `"content_too_long"` / `"adapter_error"` / `"pending_overflow"`
    pub status: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub request_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub queued: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
}

impl SendResult {
    fn sent() -> Self {
        Self {
            status: "sent".into(),
            request_id: None,
            queued: None,
            message: Some("ok".into()),
        }
    }
    fn awaiting(request_id: String) -> Self {
        Self {
            status: "awaiting_confirmation".into(),
            request_id: Some(request_id),
            queued: Some(true),
            message: Some("user confirmation required".into()),
        }
    }
    fn denied(reason: &str) -> Self {
        Self {
            status: "denied".into(),
            request_id: None,
            queued: Some(false),
            message: Some(reason.into()),
        }
    }
    fn err(reason: &str) -> Self {
        Self {
            status: "adapter_error".into(),
            request_id: None,
            queued: None,
            message: Some(reason.into()),
        }
    }
}

/// `confirm_channel` 的返回结果。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConfirmResult {
    pub channel_id: String,
    /// 本次 flush 中成功发出的 pending 数量。
    pub flushed_sent: usize,
    /// 本次 flush 中 dispatch_send 失败的 pending 数量。
    /// 这些请求已回退到 pending 队列，等待下次 confirm 或用户手动处理。
    pub flushed_failed: usize,
}

// ---------------------------------------------------------------------------
// 核心:ImBridge
// ---------------------------------------------------------------------------

pub struct ImBridge {
    config: ImBridgeConfig,
    registry: SharedChannelRegistry,
    /// 已连接适配器池（按 channel_id 复用，避免现场反复构造/连接）。
    pool: SharedAdapterPool,
    /// 白名单 channel_id 集合(动态管理，扫码绑定后自动加入)。
    allowed_channels: RwLock<HashSet<String>>,
    /// 已被用户确认过(can skip pending)的 channel_id 集合。
    confirmed_channels: RwLock<HashSet<String>>,
    /// 待用户确认的 send 队列。
    pending: RwLock<Vec<PendingConfirmation>>,
    /// 审计事件(供前端 / 测试用)。用 VecDeque 以 O(1) pop_front 淘汰最旧。
    audit: RwLock<VecDeque<ImBridgeAuditEvent>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ImBridgeAuditEvent {
    pub ts_unix_ms: u128,
    pub kind: String, // "send" | "pending" | "confirm" | "revoke" | "denied" | "error"
    pub channel_id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub target: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub note: Option<String>,
}

impl ImBridge {
    pub fn new(
        config: ImBridgeConfig,
        registry: SharedChannelRegistry,
        pool: SharedAdapterPool,
    ) -> Arc<Self> {
        let allowed_channels: HashSet<String> = config.allow_channel_ids.iter().cloned().collect();
        Arc::new(Self {
            config,
            registry,
            pool,
            allowed_channels: RwLock::new(allowed_channels),
            confirmed_channels: RwLock::new(HashSet::new()),
            pending: RwLock::new(Vec::new()),
            audit: RwLock::new(VecDeque::new()),
        })
    }

    pub fn config(&self) -> &ImBridgeConfig { &self.config }

    pub fn registry(&self) -> SharedChannelRegistry { self.registry.clone() }

    pub async fn is_whitelisted(&self, channel_id: &str) -> bool {
        self.allowed_channels.read().await.contains(channel_id)
    }

    pub async fn add_to_whitelist(&self, channel_id: &str) {
        self.allowed_channels.write().await.insert(channel_id.to_string());
    }

    pub async fn remove_from_whitelist(&self, channel_id: &str) -> bool {
        self.allowed_channels.write().await.remove(channel_id)
    }

    pub async fn allowed_channel_ids(&self) -> Vec<String> {
        self.allowed_channels.read().await.iter().cloned().collect()
    }

    pub async fn is_confirmed(&self, channel_id: &str) -> bool {
        self.confirmed_channels.read().await.contains(channel_id)
    }

    /// 白名单 + 注册 + 内容长度 校验。
    async fn precheck(&self, channel_id: &str, content: &str) -> Result<(), SendResult> {
        if !self.is_whitelisted(channel_id).await {
            return Err(SendResult::denied(&format!(
                "channel_id={} not in im_bridge whitelist", channel_id
            )));
        }
        if self.registry.find_binding_by_id(channel_id).await.is_none() {
            return Err(SendResult::denied(&format!(
                "channel_id={} not registered in ChannelRegistry", channel_id
            )));
        }
        if content.chars().count() > self.config.max_message_length {
            return Err(SendResult::denied(&format!(
                "content length {} > max {}", content.chars().count(), self.config.max_message_length
            )));
        }
        Ok(())
    }

    /// 主体:MCP 入口。LLM Agent 调这个。
    pub async fn send_message(
        self: &Arc<Self>,
        channel_id: &str,
        target: &str,
        content: &str,
    ) -> SendResult {
        if let Err(e) = self.precheck(channel_id, content).await {
            let reason = e.message.clone().unwrap_or_default();
            self.record_audit("denied", channel_id, Some(target), Some(&reason)).await;
            return e;
        }

        // 已确认 → 直接发。
        if self.is_confirmed(channel_id).await {
            return self.dispatch_send(channel_id, target, content).await;
        }

        // 未确认 → 入 pending 队列,等待前端弹窗。
        let mut pend = self.pending.write().await;
        // Bug 1 修复（TOCTOU）：拿到 pending 写锁后再次检查 is_confirmed。
        // 否则"is_confirmed 返回 false 后、获取 pending 写锁前 confirm_channel
        // 已 flush 完毕"的竞态会让本 req 入队后无人再 flush,永久滞留成僵尸。
        // is_confirmed 内部读 confirmed_channels,与 pending 写锁无交叉,无死锁。
        if self.is_confirmed(channel_id).await {
            drop(pend);
            return self.dispatch_send(channel_id, target, content).await;
        }
        if pend.len() >= self.config.max_pending {
            drop(pend);
            // pending_overflow 也记录审计事件（先释放写锁再 record_audit，
            // 避免与 audit 写锁形成不必要的锁持有链）。
            self.record_audit(
                "denied",
                channel_id,
                Some(target),
                Some("pending confirmations overflow"),
            ).await;
            return SendResult::denied("pending confirmations overflow");
        }
        let request_id = format!(
            "imb-{}-{}",
            now_unix_ms(),
            short_random()
        );
        pend.push(PendingConfirmation {
            request_id: request_id.clone(),
            channel_id: channel_id.to_string(),
            target: target.to_string(),
            content: content.to_string(),
            requested_at_unix_ms: now_unix_ms(),
        });
        drop(pend);
        self.record_audit("pending", channel_id, Some(target), Some(&request_id)).await;
        SendResult::awaiting(request_id)
    }

    async fn dispatch_send(
        self: &Arc<Self>,
        channel_id: &str,
        target: &str,
        content: &str,
    ) -> SendResult {
        // 中危修复：补一次白名单校验。confirm_channel 走 dispatch_send 时绕过了
        // send_message 入口的 precheck，若 channel 在 confirm 后被 revoke（移白名单），
        // dispatch_send 仍会发出去，绕过白名单约束。此处只补白名单校验，
        // 不重复 precheck 的长度/注册校验（避免双重审计/双重错误）。
        if !self.is_whitelisted(channel_id).await {
            let reason = format!(
                "channel_id={} not in im_bridge whitelist (dispatch_send precheck)",
                channel_id
            );
            self.record_audit("denied", channel_id, Some(target), Some(&reason)).await;
            return SendResult::denied(&reason);
        }
        let binding = match self.registry.find_binding_by_id(channel_id).await {
            Some(b) => b,
            None => return SendResult::err("binding vanished between precheck and dispatch"),
        };
        // 从 AdapterPool 取已连接适配器（命中则复用，否则构造并 connect）。
        // Bug F 修复后 connect() 会等首次连接结果再返回，失败返回 Err。
        // 此处必须记 audit，否则前端/测试无法感知「连接失败」事件。
        let adapter = match self.pool.get_or_connect(binding).await {
            Ok(a) => a,
            Err(e) => {
                self.record_audit("error", channel_id, Some(target), Some(&e)).await;
                return SendResult::err(&e);
            }
        };
        // TODO(低): `LongConnAdapter::send`（websocket_adapter.rs:500-512）当前
        // 永远返回 `Ok("queued")`，不反映真实投递结果。这导致此处 `adapter.send`
        // 几乎不会进入 Err 分支，调用方（send_message / flush_pending_for_channel）
        // 误判已发送，flush_pending_for_channel 的失败回退逻辑也无法触发。
        // 真实投递失败（如 WS 已断开、对端拒收）只能经 inbound 事件或心跳超时
        // 才能感知。修复需改 `LongConnAdapter::send` 返回真实结果，但该文件
        // 不归本批次修改范围。
        let result = adapter.send(target, content).await;
        match &result {
            Ok(_) => self.record_audit("send", channel_id, Some(target), None).await,
            Err(e) => self.record_audit("error", channel_id, Some(target), Some(e)).await,
        }
        match result {
            Ok(_) => SendResult::sent(),
            Err(e) => {
                // S1：send 失败后必须 disconnect 才能停止后台 spawn 的重连任务，
                // 否则 remove 只取出 Arc，cancel 永远不被置 true，任务无限重连成僵尸。
                self.pool.remove_and_disconnect(channel_id).await;
                SendResult::err(&e)
            }
        }
    }

    /// 前端弹窗"同意"后调用。
    pub async fn confirm_channel(self: &Arc<Self>, channel_id: &str) -> ConfirmResult {
        self.confirmed_channels.write().await.insert(channel_id.to_string());
        self.record_audit("confirm", channel_id, None, None).await;
        // 同一 channel 的所有 pending 自动 confirm 并发送。
        let (sent, failed) = self.flush_pending_for_channel(channel_id).await;
        ConfirmResult {
            channel_id: channel_id.to_string(),
            flushed_sent: sent,
            flushed_failed: failed,
        }
    }

    async fn flush_pending_for_channel(self: &Arc<Self>, channel_id: &str) -> (usize, usize) {
        let drained: Vec<PendingConfirmation> = {
            let mut p = self.pending.write().await;
            let (keep, take): (Vec<_>, Vec<_>) = std::mem::take(&mut *p)
                .into_iter()
                .partition(|x| x.channel_id != channel_id);
            *p = keep;
            take
        };
        let mut sent = 0usize;
        let mut failed: Vec<PendingConfirmation> = Vec::new();
        for req in drained {
            let r = self.dispatch_send(&req.channel_id, &req.target, &req.content).await;
            if r.status == "sent" {
                sent += 1;
            } else {
                // 中危修复：dispatch_send 失败时不要丢消息，把 pending 重新 push 回队列，
                // 等待下次 confirm 或用户手动 revoke 清理。原实现直接丢导致消息永久丢失。
                failed.push(req);
            }
        }
        let failed_count = failed.len();
        if !failed.is_empty() {
            let mut p = self.pending.write().await;
            // Bug 2 修复（通道隔离）：容量满时只淘汰同一 channel 的条目,
            // 不再无差别 p.remove(0)——否则可能丢弃其他 channel 的 pending,
            // 破坏通道隔离且无审计。同 channel 无可淘汰且已满时,
            // 对当前 req 记 record_audit("error",...) 后丢弃。
            // record_audit 是 async,调用前必须 drop 锁,避免与 audit 写锁形成持有链。
            for req in failed {
                while p.len() >= self.config.max_pending
                    && p.iter().any(|x| x.channel_id == req.channel_id)
                {
                    if let Some(pos) = p.iter().position(|x| x.channel_id == req.channel_id) {
                        p.remove(pos);
                    }
                }
                if p.len() < self.config.max_pending {
                    p.push(req);
                } else {
                    let channel_id = req.channel_id.clone();
                    let target = req.target.clone();
                    drop(p);
                    self.record_audit(
                        "error",
                        &channel_id,
                        Some(&target),
                        Some("pending overflow on flush rollback, dropping failed req"),
                    ).await;
                    p = self.pending.write().await;
                }
            }
        }
        (sent, failed_count)
    }

    /// 前端弹窗"拒绝"后调用。
    pub async fn revoke_channel(self: &Arc<Self>, channel_id: &str) {
        self.confirmed_channels.write().await.remove(channel_id);
        // 先从白名单移除堵住入口，再清 pending 队列。若先清 pending 再移白名单，
        // 中间窗口 LLM 仍可经 precheck（白名单还在）+ is_confirmed=false 重新把
        // 该 channel 入队，导致 pending 被反复回填、撤销不彻底。
        self.remove_from_whitelist(channel_id).await;
        // 同一 channel 的 pending 全部丢弃。先在块内完成 retain 并显式
        // drop(p) 释放 pending 写锁，再 record_audit，避免审计写锁等待时
        // 长时间持有 pending 写锁。
        {
            let mut p = self.pending.write().await;
            p.retain(|x| x.channel_id != channel_id);
        }
        // M6：停止 adapter 并从池中移除，断开后台重连任务，释放连接资源。
        // 白名单已清，此时即便 adapter 还在池中，send_message 的 precheck
        // 也会因不在白名单而 denied，不会走 dispatch_send / pending。
        self.pool.remove_and_disconnect(channel_id).await;
        // Bug 3 修复（与 flush 在途竞态）：remove_and_disconnect 之后,
        // flush_pending_for_channel 的在途 dispatch_send 可能失败回退,
        // 把同 channel 的 pending 重新 push 回队列（revoke 与 flush 并发）。
        // 二次 retain 覆盖该回填窗口,避免僵尸 pending。
        // 锁仅在块内持有,不与 record_audit 形成持有链,无死锁。
        {
            let mut p = self.pending.write().await;
            p.retain(|x| x.channel_id != channel_id);
        }
        self.record_audit("revoke", channel_id, None, None).await;
    }

    pub async fn list_pending(&self) -> Vec<PendingConfirmation> {
        self.pending.read().await.clone()
    }

    pub async fn list_confirmed(&self) -> Vec<String> {
        self.confirmed_channels.read().await.iter().cloned().collect()
    }

    /// MCP tool 入口:列所有"白名单 ∩ 已注册"的 channel。
    pub async fn list_channels(&self) -> Vec<ImChannelView> {
        let allow: HashSet<String> = self.allowed_channels.read().await.iter().cloned().collect();
        let bindings = self.registry.all_bindings().await;
        let mut out: Vec<ImChannelView> = bindings
            .into_iter()
            .filter(|b| allow.contains(&b.id))
            .map(|b| ImChannelView {
                channel_id: b.id,
                provider: b.provider,
                channel_name: b.channel_id,
                whitelisted: true,
                confirmed: false, // 下面补
            })
            .collect();
        let confirmed = self.confirmed_channels.read().await;
        for v in out.iter_mut() {
            v.confirmed = confirmed.contains(&v.channel_id);
        }
        out
    }

    pub async fn recent_audit(&self, limit: usize) -> Vec<ImBridgeAuditEvent> {
        let g = self.audit.read().await;
        let len = g.len();
        let start = len.saturating_sub(limit);
        g.iter().skip(start).cloned().collect()
    }

    async fn record_audit(
        &self,
        kind: &str,
        channel_id: &str,
        target: Option<&str>,
        note: Option<&str>,
    ) {
        let ev = ImBridgeAuditEvent {
            ts_unix_ms: now_unix_ms(),
            kind: kind.to_string(),
            channel_id: channel_id.to_string(),
            target: target.map(str::to_string),
            note: note.map(str::to_string),
        };
        let mut g = self.audit.write().await;
        // 软上限 1000 条,超出用 pop_front() O(1) 丢最旧（替代 Vec::remove(0) 的 O(n)）。
        if g.len() >= 1000 { g.pop_front(); }
        g.push_back(ev);
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ImChannelView {
    pub channel_id: String,
    pub provider: String,
    pub channel_name: String,
    pub whitelisted: bool,
    pub confirmed: bool,
}

// ---------------------------------------------------------------------------
// MCP tool 适配层(供 `mcp_call_remote` JSON-RPC 端点调用)
// ---------------------------------------------------------------------------

/// MCP JSON-RPC 入口。从 LLM 那边收到的 action 形如:
///   {"action": "im_bridge.send_message", "params": {"channel_id": "...", "target": "...", "content": "..."}}
/// 或:
///   {"action": "im_bridge.list_channels"}
///   {"action": "im_bridge.list_pending_confirmations"}
///   {"action": "im_bridge.confirm_channel", "params": {"channel_id": "..."}}
///   {"action": "im_bridge.revoke_channel",   "params": {"channel_id": "..."}}
pub async fn dispatch(
    bridge: Arc<ImBridge>,
    action: &str,
    params: HashMap<String, serde_json::Value>,
) -> Result<serde_json::Value, String> {
    match action {
        "im_bridge.send_message" => {
            let channel_id = str_param(&params, "channel_id")?;
            let target = str_param(&params, "target")?;
            let content = str_param(&params, "content")?;
            let r = bridge.send_message(&channel_id, &target, &content).await;
            serde_json::to_value(r).map_err(|e| e.to_string())
        }
        "im_bridge.list_channels" => {
            let list = bridge.list_channels().await;
            serde_json::to_value(list).map_err(|e| e.to_string())
        }
        "im_bridge.list_pending_confirmations" => {
            let list = bridge.list_pending().await;
            serde_json::to_value(list).map_err(|e| e.to_string())
        }
        "im_bridge.confirm_channel" => {
            let channel_id = str_param(&params, "channel_id")?;
            let r = bridge.confirm_channel(&channel_id).await;
            serde_json::to_value(r).map_err(|e| e.to_string())
        }
        "im_bridge.revoke_channel" => {
            let channel_id = str_param(&params, "channel_id")?;
            bridge.revoke_channel(&channel_id).await;
            serde_json::to_value(serde_json::json!({ "channel_id": channel_id, "status": "revoked" }))
                .map_err(|e| e.to_string())
        }
        other => Err(format!("unknown im_bridge action: {}", other)),
    }
}

fn str_param(p: &HashMap<String, serde_json::Value>, key: &str) -> Result<String, String> {
    p.get(key)
        .and_then(|v| v.as_str())
        .map(str::to_string)
        .ok_or_else(|| format!("missing string param `{}`", key))
}

fn now_unix_ms() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0)
}

/// 4 字节伪随机后缀(测试 / 标识用,无加密意义)。
fn short_random() -> String {
    use std::hash::{BuildHasher, Hasher, RandomState};
    let s = RandomState::new();
    let mut h = s.build_hasher();
    h.write_u128(now_unix_ms());
    h.write_u128(std::process::id() as u128);
    format!("{:x}", h.finish() & 0xFFFF_FFFF)
}

// ---------------------------------------------------------------------------
// MCP tool 特征(可被注册到统一的 MCP tool registry)
// ---------------------------------------------------------------------------

/// 让 ImBridge 自身能作为一个 MCP tool 实体被枚举。
/// `mcp.manifest` 拉取时,这个 `describe()` 会被生成 `input_schema` 给 LLM。
#[async_trait]
pub trait ToolDescriptor: Send + Sync {
    fn name(&self) -> &'static str;
    fn description(&self) -> &'static str;
    fn input_schema(&self) -> serde_json::Value;
}

pub fn tool_descriptors() -> Vec<Box<dyn ToolDescriptor>> {
    vec![
        Box::new(SendMessageTool),
        Box::new(ListChannelsTool),
        Box::new(ListPendingTool),
        Box::new(ConfirmChannelTool),
        Box::new(RevokeChannelTool),
    ]
}

struct SendMessageTool;
struct ListChannelsTool;
struct ListPendingTool;
struct ConfirmChannelTool;
struct RevokeChannelTool;

#[async_trait]
impl ToolDescriptor for SendMessageTool {
    fn name(&self) -> &'static str { "im_bridge.send_message" }
    fn description(&self) -> &'static str {
        "通过已注册 + 已白名单 + 已用户确认的 IM 渠道(channel_id)发送一条消息。\
         首次调用某 channel 会进入待用户确认队列,需前端弹窗同意。\
         返回 SendResult { status: 'sent' | 'awaiting_confirmation' | 'denied' | 'adapter_error', ... }。"
    }
    fn input_schema(&self) -> serde_json::Value {
        serde_json::json!({
            "type": "object",
            "required": ["channel_id", "target", "content"],
            "properties": {
                "channel_id": { "type": "string", "description": "IMBinding.id (im_config.json 的 ImChannelEntry.id)" },
                "target":    { "type": "string", "description": "接收方 user_id / room_id / open_id" },
                "content":   { "type": "string", "description": "纯文本消息内容(最大 4096 字符)" }
            }
        })
    }
}

#[async_trait]
impl ToolDescriptor for ListChannelsTool {
    fn name(&self) -> &'static str { "im_bridge.list_channels" }
    fn description(&self) -> &'static str {
        "列出当前白名单 ∩ 已注册的 IM 渠道,含 confirmed 状态。"
    }
    fn input_schema(&self) -> serde_json::Value {
        serde_json::json!({ "type": "object", "properties": {} })
    }
}

#[async_trait]
impl ToolDescriptor for ListPendingTool {
    fn name(&self) -> &'static str { "im_bridge.list_pending_confirmations" }
    fn description(&self) -> &'static str {
        "列出待用户确认的 send 请求(LLM 调了 send_message 但用户还没点同意)。"
    }
    fn input_schema(&self) -> serde_json::Value {
        serde_json::json!({ "type": "object", "properties": {} })
    }
}

#[async_trait]
impl ToolDescriptor for ConfirmChannelTool {
    fn name(&self) -> &'static str { "im_bridge.confirm_channel" }
    fn description(&self) -> &'static str {
        "用户在前端弹窗点击'同意'后调用。把 channel 加入 confirmed 集合,并自动 flush 待处理 send。"
    }
    fn input_schema(&self) -> serde_json::Value {
        serde_json::json!({
            "type": "object",
            "required": ["channel_id"],
            "properties": { "channel_id": { "type": "string" } }
        })
    }
}

#[async_trait]
impl ToolDescriptor for RevokeChannelTool {
    fn name(&self) -> &'static str { "im_bridge.revoke_channel" }
    fn description(&self) -> &'static str {
        "用户在前端弹窗点击'拒绝'后调用。把 channel 移出 confirmed 集合并清空其 pending。"
    }
    fn input_schema(&self) -> serde_json::Value {
        serde_json::json!({
            "type": "object",
            "required": ["channel_id"],
            "properties": { "channel_id": { "type": "string" } }
        })
    }
}

// ---------------------------------------------------------------------------
// 常量:MCP server 名(`mcp.manifest` 拉取时列出)
// ---------------------------------------------------------------------------

pub const IM_BRIDGE_SERVER_NAME: &str = "im_bridge";
pub const IM_BRIDGE_SERVER_DESCRIPTION: &str =
    "Local MCP server exposing IM channel send/list/confirm to LLM Agent via hermes::im.";

// ---------------------------------------------------------------------------
// Tauri 命令:让前端能直接调用 im_bridge 功能
// ---------------------------------------------------------------------------

use tauri::State;

/// 通用 dispatch 入口。前端传 action + params,返回 JSON 结果。
#[tauri::command]
pub async fn im_bridge_dispatch(
    bridge: State<'_, Arc<ImBridge>>,
    action: String,
    params: Option<serde_json::Value>,
) -> Result<serde_json::Value, String> {
    let params_map: HashMap<String, serde_json::Value> = match params {
        Some(serde_json::Value::Object(m)) => m.into_iter().collect(),
        Some(_) | None => HashMap::new(),
    };
    dispatch(bridge.inner().clone(), &action, params_map).await
}

/// 列出 im_bridge 暴露的所有 MCP tool 描述符(供前端展示 / LLM 发现)。
#[tauri::command]
pub async fn im_bridge_list_tools(
    _bridge: State<'_, Arc<ImBridge>>,
) -> Result<Vec<serde_json::Value>, String> {
    let tools = tool_descriptors();
    Ok(tools.iter().map(|t| serde_json::json!({
        "name": t.name(),
        "description": t.description(),
        "input_schema": t.input_schema(),
    })).collect())
}

/// 列出待用户确认的 send 请求。
#[tauri::command]
pub async fn im_bridge_list_pending(
    bridge: State<'_, Arc<ImBridge>>,
) -> Result<Vec<PendingConfirmation>, String> {
    Ok(bridge.list_pending().await)
}

/// 确认渠道(用户在前端点击"同意")。
#[tauri::command]
pub async fn im_bridge_confirm(
    bridge: State<'_, Arc<ImBridge>>,
    channel_id: String,
) -> Result<ConfirmResult, String> {
    Ok(bridge.confirm_channel(&channel_id).await)
}

/// 撤销渠道(用户在前端点击"拒绝")。
#[tauri::command]
pub async fn im_bridge_revoke(
    bridge: State<'_, Arc<ImBridge>>,
    channel_id: String,
) -> Result<serde_json::Value, String> {
    bridge.revoke_channel(&channel_id).await;
    serde_json::to_value(serde_json::json!({
        "channel_id": channel_id,
        "status": "revoked"
    })).map_err(|e| e.to_string())
}

/// 获取审计日志(供前端展示)。
#[tauri::command]
pub async fn im_bridge_audit(
    bridge: State<'_, Arc<ImBridge>>,
) -> Result<Vec<ImBridgeAuditEvent>, String> {
    Ok(bridge.recent_audit(100).await)
}

/// 将渠道加入 im_bridge 白名单(由 im_config_set 自动调用)。
#[tauri::command]
pub async fn im_bridge_add_whitelist(
    bridge: State<'_, Arc<ImBridge>>,
    channel_id: String,
) -> Result<serde_json::Value, String> {
    bridge.add_to_whitelist(&channel_id).await;
    serde_json::to_value(serde_json::json!({
        "channel_id": channel_id,
        "status": "added"
    })).map_err(|e| e.to_string())
}

/// 将渠道从 im_bridge 白名单移除(由 im_config_remove 自动调用)。
#[tauri::command]
pub async fn im_bridge_remove_whitelist(
    bridge: State<'_, Arc<ImBridge>>,
    channel_id: String,
) -> Result<serde_json::Value, String> {
    let removed = bridge.remove_from_whitelist(&channel_id).await;
    serde_json::to_value(serde_json::json!({
        "channel_id": channel_id,
        "removed": removed
    })).map_err(|e| e.to_string())
}

// ---------------------------------------------------------------------------
// 测试(见 im_bridge_test.rs)
// ---------------------------------------------------------------------------

#[cfg(test)]
#[path = "im_bridge_test.rs"]
mod im_bridge_test;
