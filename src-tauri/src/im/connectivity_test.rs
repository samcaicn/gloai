use super::gateway::Gateway;
use super::dingtalk::DingTalkGateway;
use super::feishu::FeishuGateway;
use super::telegram::TelegramGateway;
use super::discord::DiscordGateway;
use super::wework::WeWorkGateway;
use super::whatsapp::WhatsAppGateway;
use reqwest::Client;
use serde::{Deserialize, Serialize};
use std::sync::Mutex;
use chrono::{Utc, DateTime};

// 连通性测试结果
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConnectivityTestResult {
    pub platform: String,
    pub tested_at: i64,
    pub verdict: String,
    pub checks: Vec<ConnectivityCheck>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConnectivityCheck {
    pub code: String,
    pub level: String,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub suggestion: Option<String>,
}

// 连通性测试器
pub struct ConnectivityTester {
    client: Mutex<Client>,
}

impl ConnectivityTester {
    pub fn new() -> Self {
        Self {
            client: Mutex::new(Client::new()),
        }
    }
    
    // 测试钉钉网关连接
    pub async fn test_dingtalk(&self, gateway: &DingTalkGateway) -> ConnectivityTestResult {
        let tested_at = Utc::now().timestamp_millis();
        let mut checks: Vec<ConnectivityCheck> = Vec::new();

        let config = gateway.get_config();
        if !config.enabled {
            return ConnectivityTestResult {
                platform: "DingTalk".to_string(),
                tested_at,
                verdict: "fail".to_string(),
                checks: vec![ConnectivityCheck {
                    code: "gateway_enabled".to_string(),
                    level: "fail".to_string(),
                    message: "网关未启用".to_string(),
                    suggestion: Some("请启用该网关".to_string()),
                }],
            };
        }

        // 检查必要的配置
        if config.client_id.is_empty() || config.client_secret.is_empty() {
            return ConnectivityTestResult {
                platform: "DingTalk".to_string(),
                tested_at,
                verdict: "fail".to_string(),
                checks: vec![ConnectivityCheck {
                    code: "missing_credentials".to_string(),
                    level: "fail".to_string(),
                    message: "缺少必要配置项: client_id, client_secret".to_string(),
                    suggestion: Some("请补全配置后重新测试连通性".to_string()),
                }],
            };
        }

        // 尝试鉴权
        match gateway.get_access_token().await {
            Ok(_) => {
                checks.push(ConnectivityCheck {
                    code: "auth_check".to_string(),
                    level: "pass".to_string(),
                    message: "钉钉鉴权通过".to_string(),
                    suggestion: None,
                });
            }
            Err(e) => {
                checks.push(ConnectivityCheck {
                    code: "auth_check".to_string(),
                    level: "fail".to_string(),
                    message: format!("鉴权失败: {}", e),
                    suggestion: Some("请检查 ID/Secret 是否正确，且机器人权限已开通".to_string()),
                });
                return ConnectivityTestResult {
                    platform: "DingTalk".to_string(),
                    tested_at,
                    verdict: "fail".to_string(),
                    checks,
                };
            }
        }

        // 检查连接状态
        let status = gateway.get_status();
        if status.connected {
            checks.push(ConnectivityCheck {
                code: "gateway_running".to_string(),
                level: "pass".to_string(),
                message: "IM 渠道已启用且运行正常".to_string(),
                suggestion: None,
            });
        } else {
            checks.push(ConnectivityCheck {
                code: "gateway_running".to_string(),
                level: "warn".to_string(),
                message: "IM 渠道已启用但当前未连接".to_string(),
                suggestion: Some("请检查网络、机器人配置和平台侧事件开关".to_string()),
            });
        }

        // 提示信息
        checks.push(ConnectivityCheck {
            code: "dingtalk_bot_membership_hint".to_string(),
            level: "info".to_string(),
            message: "钉钉机器人需被加入目标会话并具备发言权限".to_string(),
            suggestion: Some("请确认机器人在目标会话中，且企业权限配置允许收发消息".to_string()),
        });

        ConnectivityTestResult {
            platform: "DingTalk".to_string(),
            tested_at,
            verdict: self.calculate_verdict(&checks),
            checks,
        }
    }
    
    // 测试飞书网关连接
    pub async fn test_feishu(&self, gateway: &FeishuGateway) -> ConnectivityTestResult {
        let tested_at = Utc::now().timestamp_millis();
        let mut checks: Vec<ConnectivityCheck> = Vec::new();

        let config = gateway.get_config();
        if !config.enabled {
            return ConnectivityTestResult {
                platform: "Feishu".to_string(),
                tested_at,
                verdict: "fail".to_string(),
                checks: vec![ConnectivityCheck {
                    code: "gateway_enabled".to_string(),
                    level: "fail".to_string(),
                    message: "网关未启用".to_string(),
                    suggestion: Some("请启用该网关".to_string()),
                }],
            };
        }

        // 检查必要的配置
        if config.app_id.is_empty() || config.app_secret.is_empty() {
            return ConnectivityTestResult {
                platform: "Feishu".to_string(),
                tested_at,
                verdict: "fail".to_string(),
                checks: vec![ConnectivityCheck {
                    code: "missing_credentials".to_string(),
                    level: "fail".to_string(),
                    message: "缺少必要配置项: app_id, app_secret".to_string(),
                    suggestion: Some("请补全配置后重新测试连通性".to_string()),
                }],
            };
        }

        // 尝试鉴权
        match gateway.get_access_token().await {
            Ok(_) => {
                checks.push(ConnectivityCheck {
                    code: "auth_check".to_string(),
                    level: "pass".to_string(),
                    message: "飞书鉴权通过".to_string(),
                    suggestion: None,
                });
            }
            Err(e) => {
                checks.push(ConnectivityCheck {
                    code: "auth_check".to_string(),
                    level: "fail".to_string(),
                    message: format!("鉴权失败: {}", e),
                    suggestion: Some("请检查 ID/Secret 是否正确，且机器人权限已开通".to_string()),
                });
                return ConnectivityTestResult {
                    platform: "Feishu".to_string(),
                    tested_at,
                    verdict: "fail".to_string(),
                    checks,
                };
            }
        }

        // 检查连接状态
        let status = gateway.get_status();
        if status.connected {
            checks.push(ConnectivityCheck {
                code: "gateway_running".to_string(),
                level: "pass".to_string(),
                message: "IM 渠道已启用且运行正常".to_string(),
                suggestion: None,
            });
        } else {
            checks.push(ConnectivityCheck {
                code: "gateway_running".to_string(),
                level: "warn".to_string(),
                message: "IM 渠道已启用但当前未连接".to_string(),
                suggestion: Some("请检查网络、机器人配置和平台侧事件开关".to_string()),
            });
        }

        checks.push(ConnectivityCheck {
            code: "feishu_group_requires_mention".to_string(),
            level: "info".to_string(),
            message: "飞书群聊中仅响应 @机器人的消息".to_string(),
            suggestion: Some("请在群聊中使用 @机器人 + 内容触发对话".to_string()),
        });

        checks.push(ConnectivityCheck {
            code: "feishu_event_subscription_required".to_string(),
            level: "info".to_string(),
            message: "飞书需要开启消息事件订阅（im.message.receive_v1）才能收消息".to_string(),
            suggestion: Some("请在飞书开发者后台确认事件订阅、权限和发布状态".to_string()),
        });

        ConnectivityTestResult {
            platform: "Feishu".to_string(),
            tested_at,
            verdict: self.calculate_verdict(&checks),
            checks,
        }
    }
    
    // 测试Telegram网关连接
    pub async fn test_telegram(&self, gateway: &TelegramGateway) -> ConnectivityTestResult {
        let tested_at = Utc::now().timestamp_millis();
        let mut checks: Vec<ConnectivityCheck> = Vec::new();

        let config = gateway.get_config();
        if !config.enabled {
            return ConnectivityTestResult {
                platform: "Telegram".to_string(),
                tested_at,
                verdict: "fail".to_string(),
                checks: vec![ConnectivityCheck {
                    code: "gateway_enabled".to_string(),
                    level: "fail".to_string(),
                    message: "网关未启用".to_string(),
                    suggestion: Some("请启用该网关".to_string()),
                }],
            };
        }

        // 检查必要的配置
        if config.bot_token.is_empty() {
            return ConnectivityTestResult {
                platform: "Telegram".to_string(),
                tested_at,
                verdict: "fail".to_string(),
                checks: vec![ConnectivityCheck {
                    code: "missing_credentials".to_string(),
                    level: "fail".to_string(),
                    message: "缺少必要配置项: bot_token".to_string(),
                    suggestion: Some("请补全配置后重新测试连通性".to_string()),
                }],
            };
        }

        // 尝试调用 getMe API
        let url = format!("https://api.telegram.org/bot{}/getMe", config.bot_token);
        let client = self.client.lock().unwrap();
        
        match client.get(&url).send().await {
            Ok(response) => {
                if response.status().is_success() {
                    checks.push(ConnectivityCheck {
                        code: "auth_check".to_string(),
                        level: "pass".to_string(),
                        message: "Telegram 鉴权通过".to_string(),
                        suggestion: None,
                    });
                } else {
                    checks.push(ConnectivityCheck {
                        code: "auth_check".to_string(),
                        level: "fail".to_string(),
                        message: format!("鉴权失败: HTTP {}", response.status()),
                        suggestion: Some("请检查 bot_token 是否正确".to_string()),
                    });
                }
            }
            Err(e) => {
                checks.push(ConnectivityCheck {
                    code: "auth_check".to_string(),
                    level: "fail".to_string(),
                    message: format!("鉴权失败: {}", e),
                    suggestion: Some("请检查网络连接和 bot_token".to_string()),
                });
            }
        }

        // 检查连接状态
        let status = gateway.get_status();
        if status.connected {
            checks.push(ConnectivityCheck {
                code: "gateway_running".to_string(),
                level: "pass".to_string(),
                message: "IM 渠道已启用且运行正常".to_string(),
                suggestion: None,
            });
        } else {
            checks.push(ConnectivityCheck {
                code: "gateway_running".to_string(),
                level: "warn".to_string(),
                message: "IM 渠道已启用但当前未连接".to_string(),
                suggestion: Some("请检查网络连接".to_string()),
            });
        }

        checks.push(ConnectivityCheck {
            code: "telegram_privacy_mode_hint".to_string(),
            level: "info".to_string(),
            message: "Telegram 可能受 Bot Privacy Mode 影响".to_string(),
            suggestion: Some("若群聊中不响应，请在 @BotFather 检查 Privacy Mode 配置".to_string()),
        });

        ConnectivityTestResult {
            platform: "Telegram".to_string(),
            tested_at,
            verdict: self.calculate_verdict(&checks),
            checks,
        }
    }
    
    // 测试Discord网关连接
    pub async fn test_discord(&self, gateway: &DiscordGateway) -> ConnectivityTestResult {
        let tested_at = Utc::now().timestamp_millis();
        let mut checks: Vec<ConnectivityCheck> = Vec::new();

        let config = gateway.get_config();
        if !config.enabled {
            return ConnectivityTestResult {
                platform: "Discord".to_string(),
                tested_at,
                verdict: "fail".to_string(),
                checks: vec![ConnectivityCheck {
                    code: "gateway_enabled".to_string(),
                    level: "fail".to_string(),
                    message: "网关未启用".to_string(),
                    suggestion: Some("请启用该网关".to_string()),
                }],
            };
        }

        // 检查必要的配置
        if config.bot_token.is_empty() {
            return ConnectivityTestResult {
                platform: "Discord".to_string(),
                tested_at,
                verdict: "fail".to_string(),
                checks: vec![ConnectivityCheck {
                    code: "missing_credentials".to_string(),
                    level: "fail".to_string(),
                    message: "缺少必要配置项: bot_token".to_string(),
                    suggestion: Some("请补全配置后重新测试连通性".to_string()),
                }],
            };
        }

        // 尝试调用 GET /api/users/@me
        let url = "https://discord.com/api/v10/users/@me";
        let client = self.client.lock().unwrap();

        match client.get(url)
            .header("Authorization", format!("Bot {}", config.bot_token))
            .send()
            .await
        {
            Ok(response) => {
                if response.status().is_success() {
                    checks.push(ConnectivityCheck {
                        code: "auth_check".to_string(),
                        level: "pass".to_string(),
                        message: "Discord 鉴权通过".to_string(),
                        suggestion: None,
                    });
                } else {
                    checks.push(ConnectivityCheck {
                        code: "auth_check".to_string(),
                        level: "fail".to_string(),
                        message: format!("鉴权失败: HTTP {}", response.status()),
                        suggestion: Some("请检查 bot_token 是否正确".to_string()),
                    });
                }
            }
            Err(e) => {
                checks.push(ConnectivityCheck {
                    code: "auth_check".to_string(),
                    level: "fail".to_string(),
                    message: format!("鉴权失败: {}", e),
                    suggestion: Some("请检查网络连接和 bot_token".to_string()),
                });
            }
        }

        // 检查连接状态
        let status = gateway.get_status();
        if status.connected {
            checks.push(ConnectivityCheck {
                code: "gateway_running".to_string(),
                level: "pass".to_string(),
                message: "IM 渠道已启用且运行正常".to_string(),
                suggestion: None,
            });
        } else {
            checks.push(ConnectivityCheck {
                code: "gateway_running".to_string(),
                level: "warn".to_string(),
                message: "IM 渠道已启用但当前未连接".to_string(),
                suggestion: Some("请检查网络连接".to_string()),
            });
        }

        checks.push(ConnectivityCheck {
            code: "discord_group_requires_mention".to_string(),
            level: "info".to_string(),
            message: "Discord 群聊中仅响应 @机器人的消息".to_string(),
            suggestion: Some("请在频道中使用 @机器人 + 内容触发对话".to_string()),
        });

        ConnectivityTestResult {
            platform: "Discord".to_string(),
            tested_at,
            verdict: self.calculate_verdict(&checks),
            checks,
        }
    }
    
    // 测试企业微信网关连接
    pub async fn test_wework(&self, gateway: &WeWorkGateway) -> ConnectivityTestResult {
        let tested_at = Utc::now().timestamp_millis();
        let mut checks: Vec<ConnectivityCheck> = Vec::new();

        let config = gateway.get_config();
        if !config.enabled {
            return ConnectivityTestResult {
                platform: "WeWork".to_string(),
                tested_at,
                verdict: "fail".to_string(),
                checks: vec![ConnectivityCheck {
                    code: "gateway_enabled".to_string(),
                    level: "fail".to_string(),
                    message: "网关未启用".to_string(),
                    suggestion: Some("请启用该网关".to_string()),
                }],
            };
        }

        // 检查必要的配置
        if config.webhook_url.is_empty() {
            return ConnectivityTestResult {
                platform: "WeWork".to_string(),
                tested_at,
                verdict: "fail".to_string(),
                checks: vec![ConnectivityCheck {
                    code: "missing_webhook_url".to_string(),
                    level: "fail".to_string(),
                    message: "缺少必要配置项: webhook_url".to_string(),
                    suggestion: Some("请补全配置后重新测试连通性".to_string()),
                }],
            };
        }

        // 企业微信需要 Webhook 接收消息，这里只检查配置
        checks.push(ConnectivityCheck {
            code: "config_check".to_string(),
            level: "pass".to_string(),
            message: "企业微信配置完整".to_string(),
            suggestion: None,
        });

        // 检查连接状态
        let status = gateway.get_status();
        if status.connected {
            checks.push(ConnectivityCheck {
                code: "gateway_running".to_string(),
                level: "pass".to_string(),
                message: "IM 渠道已启用且运行正常".to_string(),
                suggestion: None,
            });
        } else {
            checks.push(ConnectivityCheck {
                code: "gateway_running".to_string(),
                level: "warn".to_string(),
                message: "IM 渠道已启用但当前未连接".to_string(),
                suggestion: Some("请检查 Webhook 服务器是否正常运行".to_string()),
            });
        }

        ConnectivityTestResult {
            platform: "WeWork".to_string(),
            tested_at,
            verdict: self.calculate_verdict(&checks),
            checks,
        }
    }
    
    // 测试WhatsApp网关连接
    pub async fn test_whatsapp(&self, gateway: &WhatsAppGateway) -> ConnectivityTestResult {
        let tested_at = Utc::now().timestamp_millis();
        let mut checks: Vec<ConnectivityCheck> = Vec::new();

        let config = gateway.get_config();
        if !config.enabled {
            return ConnectivityTestResult {
                platform: "WhatsApp".to_string(),
                tested_at,
                verdict: "fail".to_string(),
                checks: vec![ConnectivityCheck {
                    code: "gateway_enabled".to_string(),
                    level: "fail".to_string(),
                    message: "网关未启用".to_string(),
                    suggestion: Some("请启用该网关".to_string()),
                }],
            };
        }

        // 检查必要的配置
        if config.phone_number_id.is_none() || config.access_token.is_none() {
            return ConnectivityTestResult {
                platform: "WhatsApp".to_string(),
                tested_at,
                verdict: "fail".to_string(),
                checks: vec![ConnectivityCheck {
                    code: "missing_credentials".to_string(),
                    level: "fail".to_string(),
                    message: "缺少必要配置项: phone_number_id, access_token".to_string(),
                    suggestion: Some("请补全配置后重新测试连通性".to_string()),
                }],
            };
        }

        // WhatsApp 使用 Webhook，这里只检查配置
        checks.push(ConnectivityCheck {
            code: "config_check".to_string(),
            level: "pass".to_string(),
            message: "WhatsApp 配置完整".to_string(),
            suggestion: None,
        });

        // 检查连接状态
        let status = gateway.get_status();
        if status.connected {
            checks.push(ConnectivityCheck {
                code: "gateway_running".to_string(),
                level: "pass".to_string(),
                message: "IM 渠道已启用且运行正常".to_string(),
                suggestion: None,
            });
        } else {
            checks.push(ConnectivityCheck {
                code: "gateway_running".to_string(),
                level: "warn".to_string(),
                message: "IM 渠道已启用但当前未连接".to_string(),
                suggestion: Some("请检查 Webhook 服务器是否正常运行".to_string()),
            });
        }

        ConnectivityTestResult {
            platform: "WhatsApp".to_string(),
            tested_at,
            verdict: self.calculate_verdict(&checks),
            checks,
        }
    }

    // 计算测试结果判定
    fn calculate_verdict(&self, checks: &[ConnectivityCheck]) -> String {
        if checks.iter().any(|c| c.level == "fail") {
            return "fail".to_string();
        }
        if checks.iter().any(|c| c.level == "warn") {
            return "warn".to_string();
        }
        "pass".to_string()
    }
}