// Barrel for the `im` (instant-messaging) submodule tree.
//
// Mirrors the structure of the TypeScript port: each adapter/platform
// gets its own file (e.g. `websocket_adapter.rs`, `channel_registry.rs`),
// and a single `mod.rs` exposes the public surface.

pub mod adapter_base;
pub mod auto_reply;
pub mod channel_registry;
pub mod im_endpoints;
pub mod markdown_render;
pub mod websocket_adapter;
// IM 渠道适配器(企业微信/飞书/钉钉/Telegram)。所有平台统一走长连接中继
// (LongConnAdapter),provider 标签区分路由。从 tupauto 分支恢复。
pub mod dingtalk_adapter;
pub mod feishu_adapter;
pub mod telegram_adapter;
pub mod wecom_adapter;
