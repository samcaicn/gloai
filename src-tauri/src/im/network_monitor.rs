use super::Gateway;
use std::net::TcpStream;
use std::sync::{Arc, Mutex};
use tokio::time::{interval, Duration};

// 网络监控配置
#[derive(Debug, Clone)]
pub struct NetworkMonitorConfig {
    pub enabled: bool,
    pub check_interval: Duration,
    pub reconnect_interval: Duration,
}

impl Default for NetworkMonitorConfig {
    fn default() -> Self {
        NetworkMonitorConfig {
            enabled: true,
            check_interval: Duration::from_secs(30),
            reconnect_interval: Duration::from_secs(10),
        }
    }
}

// 网络状态
#[derive(Debug, Clone, PartialEq)]
pub enum NetworkStatus {
    Connected,
    Disconnected,
}

// 网关回调类型
pub type GatewayCallback = Arc<dyn Fn() -> Vec<Arc<dyn Gateway + Send + Sync>> + Send + Sync>;

// 网络监控器
pub struct NetworkMonitor {
    config: Mutex<NetworkMonitorConfig>,
    gateways_callback: Mutex<Option<GatewayCallback>>,
    network_status: Mutex<NetworkStatus>,
    is_running: Mutex<bool>,
}

impl NetworkMonitor {
    pub fn new(config: NetworkMonitorConfig) -> Self {
        Self {
            config: Mutex::new(config),
            gateways_callback: Mutex::new(None),
            network_status: Mutex::new(NetworkStatus::Disconnected),
            is_running: Mutex::new(false),
        }
    }

    pub fn new_default() -> Self {
        Self::new(NetworkMonitorConfig::default())
    }

    pub fn set_config(&self, config: NetworkMonitorConfig) {
        *self.config.lock().unwrap() = config;
    }

    pub fn get_config(&self) -> NetworkMonitorConfig {
        self.config.lock().unwrap().clone()
    }

    pub fn set_gateways_callback(&self, callback: Option<GatewayCallback>) {
        *self.gateways_callback.lock().unwrap() = callback;
    }

    // 检查网络连接状态
    pub fn check_network_status(&self) -> NetworkStatus {
        match TcpStream::connect("8.8.8.8:53") {
            Ok(_) => NetworkStatus::Connected,
            Err(_) => NetworkStatus::Disconnected,
        }
    }

    // 启动网络监控
    pub async fn start(&self) {
        {
            let mut is_running = self.is_running.lock().unwrap();
            if *is_running {
                return;
            }
            *is_running = true;
        }

        println!("网络监控启动");

        let config = self.config.lock().unwrap().clone();
        let mut interval = interval(config.check_interval);

        loop {
            interval.tick().await;

            // 检查是否启用
            let enabled = {
                let config = self.config.lock().unwrap();
                config.enabled
            };

            if !enabled {
                break;
            }

            let current_status = self.check_network_status();
            let (status_changed, old_status) = {
                let mut network_status = self.network_status.lock().unwrap();
                let status_changed =
                    *network_status != current_status && current_status == NetworkStatus::Connected;
                let old_status = network_status.clone();
                *network_status = current_status.clone();
                (status_changed, old_status)
            };

            if status_changed {
                println!("网络状态变化: {:?} -> {:?}", old_status, current_status);
                self.reconnect_all_gateways().await;
            }

            // 定期检查并重连断开的网关
            self.check_and_reconnect_gateways().await;
        }

        *self.is_running.lock().unwrap() = false;
        println!("网络监控停止");
    }

    // 停止网络监控
    pub fn stop(&self) {
        let mut config = self.config.lock().unwrap();
        config.enabled = false;
    }

    // 重连所有网关
    async fn reconnect_all_gateways(&self) {
        let callback_opt = { self.gateways_callback.lock().unwrap().clone() };

        if let Some(callback) = callback_opt {
            println!("网络恢复，尝试重连所有网关");

            let gateways = callback();

            for gateway in gateways.iter() {
                if !gateway.is_connected() {
                    println!("尝试重连网关");
                    match gateway.reconnect_if_needed().await {
                        Ok(_) => println!("网关重连成功"),
                        Err(e) => println!("网关重连失败: {}", e),
                    }
                }
            }
        }
    }

    // 检查并重连断开的网关
    async fn check_and_reconnect_gateways(&self) {
        let callback_opt = { self.gateways_callback.lock().unwrap().clone() };

        let network_connected = {
            let network_status = self.network_status.lock().unwrap();
            *network_status == NetworkStatus::Connected
        };

        if let Some(callback) = callback_opt {
            if network_connected {
                let gateways = callback();

                for gateway in gateways.iter() {
                    if !gateway.is_connected() {
                        println!("检查到网关断开连接，尝试重连");
                        match gateway.reconnect_if_needed().await {
                            Ok(_) => println!("网关重连成功"),
                            Err(e) => println!("网关重连失败: {}", e),
                        }
                    }
                }
            }
        }
    }

    // 获取当前网络状态
    pub fn get_network_status(&self) -> NetworkStatus {
        self.network_status.lock().unwrap().clone()
    }

    // 是否正在运行
    pub fn is_running(&self) -> bool {
        *self.is_running.lock().unwrap()
    }
}
