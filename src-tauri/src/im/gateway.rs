use serde::{Deserialize, Serialize};
use std::any::Any;

// 基础网关接口
#[async_trait::async_trait]
pub trait Gateway: Send + Sync + Any {
    // 用于向下转型
    fn as_any(&self) -> &dyn Any;

    // 启动网关
    async fn start(&self) -> Result<(), String>;

    // 停止网关
    async fn stop(&self) -> Result<(), String>;

    // 检查是否连接
    fn is_connected(&self) -> bool;

    // 获取状态
    fn get_status(&self) -> GatewayStatus;

    // 发送通知到最后一个会话
    async fn send_notification(&self, text: &str) -> Result<bool, String>;

    // 发送消息到指定会话 (conversation_id, text)
    async fn send_message(&self, conversation_id: &str, text: &str) -> Result<bool, String>;

    // 发送媒体消息到指定会话
    async fn send_media_message(
        &self,
        conversation_id: &str,
        file_path: &str,
    ) -> Result<bool, String>;

    // 编辑已发送的消息
    async fn edit_message(
        &self,
        conversation_id: &str,
        message_id: &str,
        new_text: &str,
    ) -> Result<bool, String>;

    // 删除消息
    async fn delete_message(&self, conversation_id: &str, message_id: &str)
        -> Result<bool, String>;

    // 获取聊天历史
    async fn get_message_history(
        &self,
        conversation_id: &str,
        limit: u32,
    ) -> Result<Vec<IMMessage>, String>;

    // 重连（如果需要）
    async fn reconnect_if_needed(&self) -> Result<(), String>;

    // 设置事件回调
    fn set_event_callback(&self, callback: Option<EventCallback>);
}

// 网关状态
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GatewayStatus {
    pub enabled: bool,
    pub connected: bool,
    pub starting: bool,
    pub error: Option<String>,
    pub started_at: Option<i64>,
    pub last_inbound_at: Option<i64>,
    pub last_outbound_at: Option<i64>,
    pub last_error: Option<String>,
}

impl Default for GatewayStatus {
    fn default() -> Self {
        GatewayStatus {
            enabled: false,
            connected: false,
            starting: false,
            error: None,
            started_at: None,
            last_inbound_at: None,
            last_outbound_at: None,
            last_error: None,
        }
    }
}

// 事件类型
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum GatewayEvent {
    // 状态变更事件
    StatusChanged(GatewayStatus),
    // 错误事件
    Error(String),
    // 消息事件
    Message(IMMessage),
    // 连接事件
    Connected,
    // 断开连接事件
    Disconnected,
}

// 事件回调类型
pub type EventCallback = Box<dyn Fn(GatewayEvent) + Send + Sync>;

// IM消息
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IMMessage {
    pub id: String,
    pub platform: String,
    pub channel_id: String,
    pub user_id: String,
    pub user_name: String,
    pub content: String,
    pub timestamp: i64,
    pub is_mention: bool,
}

// 消息去重缓存
#[derive(Default)]
pub struct MessageDeduplicationCache {
    pub processed_messages: std::collections::HashMap<String, i64>,
}

impl MessageDeduplicationCache {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn check_and_mark(&mut self, message_id: &str, timestamp: i64, ttl_seconds: i64) -> bool {
        let now = chrono::Utc::now().timestamp();

        if let Some(existing_time) = self.processed_messages.get(message_id) {
            if now - *existing_time < ttl_seconds {
                return true;
            }
        }

        self.processed_messages.insert(message_id.to_string(), now);
        false
    }

    pub fn cleanup(&mut self) {
        let now = chrono::Utc::now().timestamp();
        let ttl: i64 = 300;

        self.processed_messages
            .retain(|_, &mut time| now - time < ttl);
    }
}

// 消息确认状态
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum MessageAckStatus {
    Pending,
    Sent,
    Delivered,
    Read,
    Failed(String),
}

// 网关配置基础结构
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GatewayConfig {
    pub enabled: bool,
}

impl Default for GatewayConfig {
    fn default() -> Self {
        GatewayConfig { enabled: false }
    }
}
