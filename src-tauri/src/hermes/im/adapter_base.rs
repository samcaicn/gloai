// Copyright (c) 2026 MeeJoy
//
// IM 适配器基础接口与数据模型。
//
// 全部 IM 渠道 (企业微信/飞书/钉钉/微信/QQ) 统一抽象为
// `IMProvider::LongConn` 长连接变体：客户端作为 WS 客户端，**直连**到
// 用户配置的目标 IM 服务器（企业微信长连接网关 / 飞书开放平台 / 钉钉
// Stream / 微信协议网关 / QQ Bot / 用户自建的中继）。铁律：客户端→IM
// 服务器之间不经过我们（AIMarketing）平台任何中转，endpoint URL 完全由
// 用户在配置里提供。**禁止使用一次性 HTTP POST (Webhook)**，所有渠道
// 必须通过长连接收发。

use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use tokio::sync::broadcast;

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct IMMessage {
    pub id: String,
    pub binding_id: String,
    pub channel_id: String,
    pub author: String,
    pub content: String,
    pub ts: i64,
    #[serde(default)]
    pub is_mention: bool,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct IMBinding {
    pub id: String,
    pub provider: String,
    pub channel_id: String,
    pub metadata: serde_json::Value,
}

/// 平台/通道类型。
///
/// 所有渠道统一通过 `endpoint` 字段（WS URL）建立长连接，连接到 AIMarketing IM 中继网关。
/// 不同平台变体（WeCom/Feishu/DingTalk）用于让中继网关识别目标平台并做相应路由。
/// **禁止使用一次性 HTTP POST (Webhook)**。
#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum IMProvider {
    /// 通用长连接渠道。`endpoint` 是 AIMarketing IM 中继网关的 WS URL。
    LongConn {
        endpoint: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        secret: Option<String>,
    },
    /// 兼容历史数据：直接当作 LongConn 处理。
    WebSocket { url: String },
    /// 企业微信渠道。通过中继网关连接到企业微信。
    #[serde(rename = "wecom")]
    WeCom {
        endpoint: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        secret: Option<String>,
    },
    /// 飞书渠道。通过中继网关连接到飞书。
    #[serde(rename = "feishu")]
    Feishu {
        endpoint: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        secret: Option<String>,
    },
    /// 飞书 / Lark 国际版渠道。通过中继网关连接到 Lark 国际版。
    #[serde(rename = "feishu_lark")]
    FeishuLark {
        endpoint: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        secret: Option<String>,
    },
    /// 钉钉渠道。通过中继网关连接到钉钉。
    #[serde(rename = "dingtalk")]
    DingTalk {
        endpoint: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        secret: Option<String>,
    },
    /// 微信渠道（iLink）。通过中继网关连接到微信。
    #[serde(rename = "weixin")]
    Weixin {
        endpoint: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        secret: Option<String>,
    },
    /// QQ Bot 渠道。通过中继网关连接到 QQ Bot。
    #[serde(rename = "qqbot")]
    QqBot {
        endpoint: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        secret: Option<String>,
    },
    /// Telegram Bot 渠道。使用 Bot API long polling 收发消息。
    /// secret = bot_token (从 @BotFather 获取)。
    #[serde(rename = "telegram")]
    Telegram {
        endpoint: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        secret: Option<String>,
    },
    /// 兼容历史数据：仅占位。`im_config` 不再接受新建。
    #[serde(other)]
    Legacy,
}

impl Default for IMProvider {
    fn default() -> Self {
        IMProvider::LongConn {
            endpoint: String::new(),
            secret: None,
        }
    }
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct IMAdapterEvent {
    pub binding_id: String,
    pub kind: String,
    pub payload: serde_json::Value,
    pub ts: i64,
}

pub type IMAdapterHandler = Box<dyn Fn(IMMessage) -> futures::future::BoxFuture<'static, ()> + Send + Sync + 'static>;

#[async_trait]
pub trait IMAdapter: Send + Sync {
    fn provider(&self) -> &IMProvider;
    /// 建立并保持长连接（tokio 后台任务：重连 + 心跳 + 收发）。
    async fn connect(&self) -> Result<(), String>;
    async fn disconnect(&self) -> Result<(), String>;
    /// 通过已建立的长连接发送一条消息。
    async fn send(&self, target: &str, content: &str) -> Result<String, String>;
    fn subscribe(&self) -> broadcast::Receiver<IMAdapterEvent>;
}
