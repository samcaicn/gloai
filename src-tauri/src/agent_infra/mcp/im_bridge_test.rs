// Copyright (c) 2026 tupAI
//
// P3 G18 — im_bridge 单元测试。
//
// 覆盖:
//   1. 白名单拦截: 未在 allow_channel_ids 的 channel → 立即 denied
//   2. 未注册拦截: registry 里没有的 channel_id → denied
//   3. 长度限制: 内容超过 max_message_length → denied
//   4. 首次调用进入 pending,不会直接发
//   5. confirm_channel → 后续调用直接放行(且 flush pending 队列)
//   6. revoke_channel → 拒绝并清空该 channel 的 pending
//   7. pending 容量上限
//   8. list_channels 只列"白名单 ∩ 已注册" ∩ confirmed 状态正确
//   9. dispatch() JSON-RPC 入口的 action 路由
//  10. audit 事件流被记录
//  11. 跨 channel 隔离: confirm A 不影响 B

use std::collections::HashMap;
use std::sync::Arc;

use crate::agent_infra::mcp::im_bridge::{dispatch, tool_descriptors, ImBridge, ImBridgeConfig};
use crate::hermes::im::adapter_base::{IMBinding, IMProvider};
use crate::hermes::im::channel_registry::{
    build_adapter_from_binding, AdapterPool, ChannelRegistry, SharedChannelRegistry,
};

fn make_binding(id: &str, channel_name: &str, endpoint: &str) -> IMBinding {
    IMBinding {
        id: id.to_string(),
        provider: "long_conn".to_string(),
        channel_id: channel_name.to_string(),
        metadata: serde_json::json!({ "endpoint": endpoint }),
    }
}

fn fresh_registry() -> SharedChannelRegistry {
    Arc::new(ChannelRegistry::new())
}

fn build_bridge(
    config: ImBridgeConfig,
    registry: SharedChannelRegistry,
) -> Arc<ImBridge> {
    let pool = Arc::new(AdapterPool::new());
    ImBridge::new(config, registry, pool)
}

fn default_config_with(allow: Vec<&str>) -> ImBridgeConfig {
    ImBridgeConfig {
        allow_channel_ids: allow.into_iter().map(String::from).collect(),
        max_message_length: 4096,
        max_pending: 64,
    }
}

// ---------------------------------------------------------------------------
// 1. 白名单拦截
// ---------------------------------------------------------------------------

#[tokio::test]
async fn non_whitelisted_channel_is_denied() {
    let registry = fresh_registry();
    let b = build_bridge(default_config_with(vec![]), registry.clone());
    let r = b.send_message("c1", "user-x", "hi").await;
    assert_eq!(r.status, "denied");
    assert!(r.message.unwrap().contains("not in im_bridge whitelist"));
}

// ---------------------------------------------------------------------------
// 2. 未注册拦截
// ---------------------------------------------------------------------------

#[tokio::test]
async fn whitelisted_but_not_registered_is_denied() {
    let registry = fresh_registry();
    let b = build_bridge(default_config_with(vec!["c1"]), registry.clone());
    let r = b.send_message("c1", "user-x", "hi").await;
    assert_eq!(r.status, "denied");
    assert!(r.message.unwrap().contains("not registered in ChannelRegistry"));
}

// ---------------------------------------------------------------------------
// 3. 长度限制
// ---------------------------------------------------------------------------

#[tokio::test]
async fn oversized_content_is_denied() {
    let registry = fresh_registry();
    registry
        .bind("default", make_binding("c1", "ops", "ws://localhost:7000/test"))
        .await;
    let cfg = ImBridgeConfig {
        allow_channel_ids: vec!["c1".into()],
        max_message_length: 8,
        max_pending: 64,
    };
    let b = build_bridge(cfg, registry.clone());
    let r = b.send_message("c1", "user-x", "123456789").await;
    assert_eq!(r.status, "denied");
    assert!(r.message.unwrap().contains("content length"));
}

// ---------------------------------------------------------------------------
// 4. 首次调用进入 pending
// ---------------------------------------------------------------------------

#[tokio::test]
async fn first_call_to_channel_awaits_confirmation() {
    let registry = fresh_registry();
    registry
        .bind("default", make_binding("c1", "ops", "ws://localhost:7000/test"))
        .await;
    let b = build_bridge(default_config_with(vec!["c1"]), registry.clone());

    let r = b.send_message("c1", "u1", "hello").await;
    assert_eq!(r.status, "awaiting_confirmation");
    assert!(r.request_id.is_some());
    assert_eq!(r.queued, Some(true));

    let pending = b.list_pending().await;
    assert_eq!(pending.len(), 1);
    assert_eq!(pending[0].channel_id, "c1");
    assert_eq!(pending[0].target, "u1");
    assert_eq!(pending[0].content, "hello");
}

// ---------------------------------------------------------------------------
// 5. confirm 后直接放行
// ---------------------------------------------------------------------------

#[tokio::test]
async fn confirm_channel_flushes_pending_and_subsequent_sends_directly() {
    let registry = fresh_registry();
    registry
        .bind("default", make_binding("c1", "ops", "ws://127.0.0.1:1/never"))
        .await;
    let b = build_bridge(default_config_with(vec!["c1"]), registry.clone());

    // 1) 首次 send → pending
    let r1 = b.send_message("c1", "u1", "first").await;
    assert_eq!(r1.status, "awaiting_confirmation");
    assert_eq!(b.list_pending().await.len(), 1);

    // 2) confirm → flush pending (会尝试 dispatch,endpoint 不通会返 adapter_error;
    //    修复后的 flush_pending_for_channel 在 dispatch_send 失败时会把 pending
    //    重新 push 回队列,故 pending 长度仍为 1,而非 0。这里主要测 confirmed 集合就位)
    let flushed = b.confirm_channel("c1").await;
    // flushed 计数仅统计 status=sent 的;长连接不通时为 0
    let _ = flushed; // 适配器真实发送不在本测试范围
    assert!(b.is_confirmed("c1").await);
    assert_eq!(b.list_pending().await.len(), 1, "flush 失败的 pending 应回退到队列");

    // 3) 后续 send → 不再 pending(此处走 dispatch_send 路径,
    //    WS 不通会返 adapter_error,但关键是 status 不是 awaiting_confirmation)
    let r2 = b.send_message("c1", "u2", "second").await;
    assert_ne!(r2.status, "awaiting_confirmation", "confirmed channel 不应再入 pending");
    // WS endpoint 不可达 → adapter_error;这是预期,见测试 6
    assert!(
        r2.status == "adapter_error" || r2.status == "sent",
        "got status={}", r2.status
    );
}

// ---------------------------------------------------------------------------
// 6. revoke 清空 pending + 移除 confirmed
// ---------------------------------------------------------------------------

#[tokio::test]
async fn revoke_channel_clears_pending_and_confirmed() {
    let registry = fresh_registry();
    registry
        .bind("default", make_binding("c1", "ops", "ws://localhost:7000"))
        .await;
    let b = build_bridge(default_config_with(vec!["c1"]), registry.clone());

    // 1) 累积 2 个 pending
    b.send_message("c1", "u1", "a").await;
    b.send_message("c1", "u2", "b").await;
    assert_eq!(b.list_pending().await.len(), 2);

    // 2) confirm
    b.confirm_channel("c1").await;
    assert!(b.is_confirmed("c1").await);

    // 3) revoke
    b.revoke_channel("c1").await;
    assert!(!b.is_confirmed("c1").await);
    // 4) revoke 后白名单已移除,新 send 在 precheck 即被 is_whitelisted 拦截返回 denied,
    //    不进 pending 队列;故 pending 仍为 0。再 revoke 一次仍保持 0(幂等)。
    b.send_message("c1", "u3", "c").await;
    assert_eq!(b.list_pending().await.len(), 0, "revoke 后白名单已移除，新 send 应被拒，不进 pending");
    b.revoke_channel("c1").await;
    assert_eq!(b.list_pending().await.len(), 0);
}

// ---------------------------------------------------------------------------
// 7. pending 容量上限
// ---------------------------------------------------------------------------

#[tokio::test]
async fn pending_overflow_is_rejected() {
    let registry = fresh_registry();
    registry
        .bind("default", make_binding("c1", "ops", "ws://localhost:7000"))
        .await;
    let cfg = ImBridgeConfig {
        allow_channel_ids: vec!["c1".into()],
        max_message_length: 4096,
        max_pending: 2,
    };
    let b = build_bridge(cfg, registry.clone());

    let r1 = b.send_message("c1", "u1", "a").await;
    let r2 = b.send_message("c1", "u1", "b").await;
    let r3 = b.send_message("c1", "u1", "c").await;
    assert_eq!(r1.status, "awaiting_confirmation");
    assert_eq!(r2.status, "awaiting_confirmation");
    assert_eq!(r3.status, "denied", "超过 max_pending 应被拒");
    assert!(r3.message.unwrap().contains("pending confirmations overflow"));
}

// ---------------------------------------------------------------------------
// 8. list_channels 只列"白名单 ∩ 已注册"
// ---------------------------------------------------------------------------

#[tokio::test]
async fn list_channels_only_shows_whitelisted_and_registered() {
    let registry = fresh_registry();
    registry.bind("default", make_binding("c1", "ops", "ws://x")).await;
    registry.bind("default", make_binding("c2", "dev", "ws://y")).await;
    registry.bind("default", make_binding("c3", "qa",  "ws://z")).await;
    // c1, c2 在白名单,c3 不在
    let b = build_bridge(default_config_with(vec!["c1", "c2"]), registry.clone());

    let list = b.list_channels().await;
    let ids: Vec<String> = list.iter().map(|v| v.channel_id.clone()).collect();
    assert_eq!(list.len(), 2);
    assert!(ids.contains(&"c1".to_string()));
    assert!(ids.contains(&"c2".to_string()));
    assert!(!ids.contains(&"c3".to_string()));
    for v in &list {
        assert!(v.whitelisted);
        assert!(!v.confirmed);
    }

    b.confirm_channel("c1").await;
    let list2 = b.list_channels().await;
    let c1 = list2.iter().find(|v| v.channel_id == "c1").unwrap();
    assert!(c1.confirmed);
    let c2 = list2.iter().find(|v| v.channel_id == "c2").unwrap();
    assert!(!c2.confirmed);
}

// ---------------------------------------------------------------------------
// 9. dispatch() JSON-RPC action 路由
// ---------------------------------------------------------------------------

#[tokio::test]
async fn dispatch_routes_to_correct_tool() {
    let registry = fresh_registry();
    registry.bind("default", make_binding("c1", "ops", "ws://x")).await;
    let b = build_bridge(default_config_with(vec!["c1"]), registry.clone());

    // send_message
    let mut params = HashMap::new();
    params.insert("channel_id".into(), serde_json::json!("c1"));
    params.insert("target".into(), serde_json::json!("u1"));
    params.insert("content".into(), serde_json::json!("hi"));
    let v = dispatch(b.clone(), "im_bridge.send_message", params).await.unwrap();
    assert_eq!(v["status"], "awaiting_confirmation");

    // list_pending_confirmations
    let v = dispatch(b.clone(), "im_bridge.list_pending_confirmations", HashMap::new())
        .await.unwrap();
    assert!(v.is_array());
    assert_eq!(v.as_array().unwrap().len(), 1);

    // confirm_channel
    let mut params = HashMap::new();
    params.insert("channel_id".into(), serde_json::json!("c1"));
    let v = dispatch(b.clone(), "im_bridge.confirm_channel", params).await.unwrap();
    assert_eq!(v["channel_id"], "c1");

    // list_channels
    let v = dispatch(b.clone(), "im_bridge.list_channels", HashMap::new()).await.unwrap();
    assert!(v.is_array());

    // revoke_channel
    let mut params = HashMap::new();
    params.insert("channel_id".into(), serde_json::json!("c1"));
    let v = dispatch(b.clone(), "im_bridge.revoke_channel", params).await.unwrap();
    assert_eq!(v["status"], "revoked");

    // unknown action
    let r = dispatch(b.clone(), "im_bridge.nope", HashMap::new()).await;
    assert!(r.is_err());
    assert!(r.unwrap_err().contains("unknown im_bridge action"));
}

// ---------------------------------------------------------------------------
// 10. audit 事件流
// ---------------------------------------------------------------------------

#[tokio::test]
async fn audit_log_records_send_lifecycle() {
    let registry = fresh_registry();
    registry.bind("default", make_binding("c1", "ops", "ws://x")).await;
    let b = build_bridge(default_config_with(vec!["c1"]), registry.clone());

    b.send_message("c1", "u1", "x").await; // pending
    b.send_message("c1", "u1", "y").await; // pending
    b.confirm_channel("c1").await;        // confirm + flush
    b.send_message("c1", "u1", "z").await; // dispatch(可能 adapter_error)
    b.revoke_channel("c1").await;          // revoke

    let audit = b.recent_audit(64).await;
    let kinds: Vec<String> = audit.iter().map(|e| e.kind.clone()).collect();
    // 至少应该看到: 2x pending, 1x confirm, 1x send|error, 1x revoke
    let pending_n = kinds.iter().filter(|k| *k == "pending").count();
    assert!(pending_n >= 2, "got kinds={:?}", kinds);
    assert!(kinds.iter().any(|k| k == "confirm"));
    assert!(kinds.iter().any(|k| k == "revoke"));
    // send 或 error 至少有其一
    assert!(kinds.iter().any(|k| k == "send" || k == "error"));
}

// ---------------------------------------------------------------------------
// 11. 跨 channel 隔离
// ---------------------------------------------------------------------------

#[tokio::test]
async fn confirm_one_channel_does_not_affect_others() {
    let registry = fresh_registry();
    registry.bind("default", make_binding("c1", "a", "ws://x")).await;
    registry.bind("default", make_binding("c2", "b", "ws://y")).await;
    let b = build_bridge(default_config_with(vec!["c1", "c2"]), registry.clone());

    // c1 → confirm
    b.confirm_channel("c1").await;
    assert!(b.is_confirmed("c1").await);
    assert!(!b.is_confirmed("c2").await, "c2 不应被 c1 的 confirm 污染");

    // c2 第一次调 → 仍 pending
    let r = b.send_message("c2", "u", "x").await;
    assert_eq!(r.status, "awaiting_confirmation");
}

// ---------------------------------------------------------------------------
// 12. build_adapter_from_binding 工厂
// ---------------------------------------------------------------------------

#[test]
fn build_adapter_from_binding_routes_provider_tags() {
    let wecom = make_binding("c1", "ops", "ws://x");
    let wecom = IMBinding { provider: "wecom".into(), ..wecom };
    assert!(build_adapter_from_binding(&wecom).is_some());

    let feishu = make_binding("c2", "ops", "ws://x");
    let feishu = IMBinding { provider: "feishu".into(), ..feishu };
    assert!(build_adapter_from_binding(&feishu).is_some());

    let dingtalk = make_binding("c3", "ops", "ws://x");
    let dingtalk = IMBinding { provider: "dingtalk".into(), ..dingtalk };
    assert!(build_adapter_from_binding(&dingtalk).is_some());

    let lc = make_binding("c4", "ops", "ws://x"); // provider = "long_conn"
    assert!(build_adapter_from_binding(&lc).is_some());

    // 未知 provider 标签
    let unknown = IMBinding {
        id: "c5".into(),
        provider: "mystery".into(),
        channel_id: "x".into(),
        metadata: serde_json::json!({}),
    };
    assert!(build_adapter_from_binding(&unknown).is_none());
}

// ---------------------------------------------------------------------------
// 13. tool_descriptors 返回 5 个 tool,带 name + description + input_schema
// ---------------------------------------------------------------------------

#[test]
fn tool_descriptors_expose_five_tools() {
    let descs = tool_descriptors();
    assert_eq!(descs.len(), 5);
    let names: Vec<&str> = descs.iter().map(|d| d.name()).collect();
    assert!(names.contains(&"im_bridge.send_message"));
    assert!(names.contains(&"im_bridge.list_channels"));
    assert!(names.contains(&"im_bridge.list_pending_confirmations"));
    assert!(names.contains(&"im_bridge.confirm_channel"));
    assert!(names.contains(&"im_bridge.revoke_channel"));

    // schema 至少是 object + 含 properties
    for d in &descs {
        let s = d.input_schema();
        assert_eq!(s["type"], "object", "tool {} schema 缺 type", d.name());
    }

    // send_message 的 input_schema 必填字段
    let send = descs.iter().find(|d| d.name() == "im_bridge.send_message").unwrap();
    let s = send.input_schema();
    let required = s["required"].as_array().expect("send_message 必填 required[]");
    assert!(required.iter().any(|v| v == "channel_id"));
    assert!(required.iter().any(|v| v == "target"));
    assert!(required.iter().any(|v| v == "content"));
}

// ---------------------------------------------------------------------------
// 14. IMProvider::LongConn 与 IMBinding 解耦
// ---------------------------------------------------------------------------

#[test]
fn im_provider_longconn_serializes_with_endpoint() {
    // 反向验证:确保 im_bridge 不依赖某个特定 provider,
    // 它只看 IMBinding.provider tag (string) — 适配器工厂分发。
    let p = IMProvider::LongConn {
        endpoint: "ws://x".into(),
        secret: None,
    };
    let tag = serde_json::to_value(&p)
        .ok()
        .and_then(|v| v.get("type").and_then(|t| t.as_str().map(str::to_string)))
        .unwrap_or_default();
    assert_eq!(tag, "long_conn");
}
