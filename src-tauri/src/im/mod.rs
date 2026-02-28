pub mod connectivity_test;
pub mod dingtalk;
pub mod discord;
pub mod feishu;
pub mod gateway;
pub mod llm_config;
pub mod logging;
pub mod message_handler;
pub mod network_monitor;
pub mod telegram;
#[cfg(test)]
pub mod tests;
pub mod wework;
pub mod whatsapp;

use self::connectivity_test::ConnectivityTester;
use self::gateway::{Gateway, GatewayStatus};
use self::llm_config::LLMManager;
use self::logging::Logger;
use self::message_handler::MessageHandler;
use self::network_monitor::NetworkMonitor;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use std::sync::Mutex;

// IM管理器配置
#[allow(dead_code)]
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IMManagerConfig {
    pub enabled: bool,
}

impl Default for IMManagerConfig {
    fn default() -> Self {
        IMManagerConfig { enabled: false }
    }
}

// IM管理器
#[allow(dead_code)]
pub struct IMManager {
    config: Mutex<IMManagerConfig>,
    gateways: Arc<Mutex<HashMap<String, Arc<dyn Gateway + Send + Sync>>>>,
    message_handler: Mutex<Option<Arc<MessageHandler>>>,
    network_monitor: Mutex<Option<Arc<NetworkMonitor>>>,
    llm_manager: Mutex<Option<Arc<LLMManager>>>,
    logger: Mutex<Arc<Logger>>,
}

impl IMManager {
    pub fn new(config: IMManagerConfig) -> Self {
        Self {
            config: Mutex::new(config),
            gateways: Arc::new(Mutex::new(HashMap::new())),
            message_handler: Mutex::new(None),
            network_monitor: Mutex::new(None),
            llm_manager: Mutex::new(None),
            logger: Mutex::new(Arc::new(Logger::new_default())),
        }
    }

    pub fn new_default() -> Self {
        Self::new(IMManagerConfig::default())
    }

    pub fn set_config(&self, config: IMManagerConfig) {
        *self.config.lock().unwrap() = config;
    }

    pub fn get_config(&self) -> IMManagerConfig {
        self.config.lock().unwrap().clone()
    }

    // 设置消息处理器
    pub fn set_message_handler(&self, handler: MessageHandler) {
        *self.message_handler.lock().unwrap() = Some(Arc::new(handler));
        self.logger
            .lock()
            .unwrap()
            .info("IMManager", "消息处理器已设置");
    }

    // 获取消息处理器
    pub fn get_message_handler(&self) -> Option<Arc<MessageHandler>> {
        self.message_handler.lock().unwrap().clone()
    }

    // 设置网络监控器
    pub fn set_network_monitor(&self, monitor: NetworkMonitor) {
        let gateways_ref = self.gateways.clone();
        let callback: self::network_monitor::GatewayCallback = Arc::new(move || {
            let gateways = gateways_ref.lock().unwrap();
            gateways.values().cloned().collect()
        });
        monitor.set_gateways_callback(Some(callback));

        let mut network_monitor = self.network_monitor.lock().unwrap();
        *network_monitor = Some(Arc::new(monitor));
        self.logger
            .lock()
            .unwrap()
            .info("IMManager", "网络监控器已设置");
    }

    // 获取网络监控器
    pub fn get_network_monitor(&self) -> Option<Arc<NetworkMonitor>> {
        self.network_monitor.lock().unwrap().clone()
    }

    // 启动网络监控
    pub async fn start_network_monitor(&self) {
        if let Some(monitor) = &*self.network_monitor.lock().unwrap() {
            let monitor_clone = monitor.clone();
            tokio::spawn(async move {
                monitor_clone.start().await;
            });
            self.logger
                .lock()
                .unwrap()
                .info("IMManager", "网络监控已启动");
        }
    }

    // 停止网络监控
    pub fn stop_network_monitor(&self) {
        if let Some(monitor) = &*self.network_monitor.lock().unwrap() {
            monitor.stop();
            self.logger
                .lock()
                .unwrap()
                .info("IMManager", "网络监控已停止");
        }
    }

    // 设置LLM管理器
    pub fn set_llm_manager(&self, manager: LLMManager) {
        *self.llm_manager.lock().unwrap() = Some(Arc::new(manager));
        self.logger
            .lock()
            .unwrap()
            .info("IMManager", "LLM管理器已设置");
    }

    // 获取LLM管理器
    pub fn get_llm_manager(&self) -> Option<Arc<LLMManager>> {
        self.llm_manager.lock().unwrap().clone()
    }

    // 设置日志记录器
    pub fn set_logger(&self, logger: Logger) {
        *self.logger.lock().unwrap() = Arc::new(logger);
    }

    // 获取日志记录器
    pub fn get_logger(&self) -> Arc<Logger> {
        self.logger.lock().unwrap().clone()
    }

    // 添加网关
    pub fn add_gateway(&self, name: &str, gateway: Arc<dyn Gateway + Send + Sync>) {
        let mut gateways = self.gateways.lock().unwrap();
        let handler = self.message_handler.lock().unwrap().clone();

        // 设置事件回调
        if let Some(h) = handler {
            let h_clone = h.clone();
            gateway.set_event_callback(Some(Box::new(move |event| {
                h_clone.handle_event(event);
            })));
        }

        gateways.insert(name.to_string(), gateway);
        self.logger
            .lock()
            .unwrap()
            .info("IMManager", &format!("网关 {} 已添加", name));
    }

    // 获取网关
    pub fn get_gateway(&self, name: &str) -> Option<Arc<dyn Gateway + Send + Sync>> {
        let gateways = self.gateways.lock().unwrap();
        gateways.get(name).cloned()
    }

    // 获取所有网关名称
    pub fn get_gateway_names(&self) -> Vec<String> {
        let gateways = self.gateways.lock().unwrap();
        gateways.keys().cloned().collect()
    }

    // 启动所有网关
    pub async fn start_all(&self) -> Result<(), String> {
        let config = self.config.lock().unwrap();
        if !config.enabled {
            self.logger
                .lock()
                .unwrap()
                .info("IMManager", "IM管理器未启用，跳过启动网关");
            return Ok(());
        }

        let gateways: Vec<(String, Arc<dyn Gateway + Send + Sync>)> = {
            let gateways = self.gateways.lock().unwrap();
            gateways
                .iter()
                .map(|(k, v)| (k.clone(), v.clone()))
                .collect()
        };

        self.logger.lock().unwrap().info(
            "IMManager",
            &format!("开始启动所有网关，共 {} 个", gateways.len()),
        );

        for (name, gateway) in gateways.iter() {
            match gateway.start().await {
                Ok(_) => {
                    self.logger
                        .lock()
                        .unwrap()
                        .info("IMManager", &format!("网关 {} 启动成功", name));
                }
                Err(e) => {
                    self.logger
                        .lock()
                        .unwrap()
                        .error("IMManager", &format!("网关 {} 启动失败: {}", name, e));
                }
            }
        }

        Ok(())
    }

    // 停止所有网关
    pub async fn stop_all(&self) -> Result<(), String> {
        let gateways: Vec<(String, Arc<dyn Gateway + Send + Sync>)> = {
            let gateways = self.gateways.lock().unwrap();
            gateways
                .iter()
                .map(|(k, v)| (k.clone(), v.clone()))
                .collect()
        };

        self.logger.lock().unwrap().info(
            "IMManager",
            &format!("开始停止所有网关，共 {} 个", gateways.len()),
        );

        for (name, gateway) in gateways.iter() {
            match gateway.stop().await {
                Ok(_) => {
                    self.logger
                        .lock()
                        .unwrap()
                        .info("IMManager", &format!("网关 {} 停止成功", name));
                }
                Err(e) => {
                    self.logger
                        .lock()
                        .unwrap()
                        .error("IMManager", &format!("网关 {} 停止失败: {}", name, e));
                }
            }
        }

        Ok(())
    }

    // 启动指定网关
    pub async fn start_gateway(&self, name: &str) -> Result<(), String> {
        let gateway = self
            .get_gateway(name)
            .ok_or_else(|| format!("网关 {} 不存在", name))?;
        gateway.start().await
    }

    // 停止指定网关
    pub async fn stop_gateway(&self, name: &str) -> Result<(), String> {
        let gateway = self
            .get_gateway(name)
            .ok_or_else(|| format!("网关 {} 不存在", name))?;
        gateway.stop().await
    }

    // 发送通知到所有网关的最后一个会话
    pub async fn send_notification(&self, text: &str) -> Result<(), String> {
        let gateways: Vec<(String, Arc<dyn Gateway + Send + Sync>)> = {
            let gateways = self.gateways.lock().unwrap();
            gateways
                .iter()
                .map(|(k, v)| (k.clone(), v.clone()))
                .collect()
        };

        let mut success_count = 0;

        for (name, gateway) in gateways.iter() {
            if gateway.is_connected() {
                match gateway.send_notification(text).await {
                    Ok(_) => {
                        success_count += 1;
                        self.logger
                            .lock()
                            .unwrap()
                            .info("IMManager", &format!("网关 {} 发送通知成功", name));
                    }
                    Err(e) => {
                        self.logger
                            .lock()
                            .unwrap()
                            .error("IMManager", &format!("网关 {} 发送通知失败: {}", name, e));
                    }
                }
            }
        }

        if success_count == 0 {
            Err("没有网关可以发送通知".to_string())
        } else {
            Ok(())
        }
    }

    // 发送消息到指定网关的指定会话
    pub async fn send_message(
        &self,
        gateway_name: &str,
        conversation_id: &str,
        text: &str,
    ) -> Result<bool, String> {
        let gateway = self
            .get_gateway(gateway_name)
            .ok_or_else(|| format!("网关 {} 不存在", gateway_name))?;
        gateway.send_message(conversation_id, text).await
    }

    // 测试指定网关的连通性
    pub async fn test_gateway_connectivity(
        &self,
        gateway_name: &str,
    ) -> Result<serde_json::Value, String> {
        let gateway = self
            .get_gateway(gateway_name)
            .ok_or_else(|| format!("网关 {} 不存在", gateway_name))?;
        let tester = ConnectivityTester::new();

        let result = match gateway_name.to_lowercase().as_str() {
            "dingtalk" => {
                if let Some(g) = gateway
                    .as_ref()
                    .as_any()
                    .downcast_ref::<self::dingtalk::DingTalkGateway>()
                {
                    tester.test_dingtalk(g).await
                } else {
                    return Err("网关类型不匹配".to_string());
                }
            }
            "feishu" => {
                if let Some(g) = gateway
                    .as_ref()
                    .as_any()
                    .downcast_ref::<self::feishu::FeishuGateway>()
                {
                    tester.test_feishu(g).await
                } else {
                    return Err("网关类型不匹配".to_string());
                }
            }
            "telegram" => {
                if let Some(g) = gateway
                    .as_ref()
                    .as_any()
                    .downcast_ref::<self::telegram::TelegramGateway>()
                {
                    tester.test_telegram(g).await
                } else {
                    return Err("网关类型不匹配".to_string());
                }
            }
            "discord" => {
                if let Some(g) = gateway
                    .as_ref()
                    .as_any()
                    .downcast_ref::<self::discord::DiscordGateway>()
                {
                    tester.test_discord(g).await
                } else {
                    return Err("网关类型不匹配".to_string());
                }
            }
            "wework" => {
                if let Some(g) = gateway
                    .as_ref()
                    .as_any()
                    .downcast_ref::<self::wework::WeWorkGateway>()
                {
                    tester.test_wework(g).await
                } else {
                    return Err("网关类型不匹配".to_string());
                }
            }
            "whatsapp" => {
                if let Some(g) = gateway
                    .as_ref()
                    .as_any()
                    .downcast_ref::<self::whatsapp::WhatsAppGateway>()
                {
                    tester.test_whatsapp(g).await
                } else {
                    return Err("网关类型不匹配".to_string());
                }
            }
            _ => return Err(format!("未知的网关类型: {}", gateway_name)),
        };

        serde_json::to_value(result).map_err(|e| e.to_string())
    }

    // 获取所有网关的状态
    pub fn get_gateways_status(&self) -> HashMap<String, GatewayStatus> {
        let gateways: Vec<(String, Arc<dyn Gateway + Send + Sync>)> = {
            let gateways = self.gateways.lock().unwrap();
            gateways
                .iter()
                .map(|(k, v)| (k.clone(), v.clone()))
                .collect()
        };

        let mut status_map = HashMap::new();
        for (name, gateway) in gateways.iter() {
            status_map.insert(name.clone(), gateway.get_status());
        }
        status_map
    }
}
