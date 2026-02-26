use super::gateway::IMMessage;
use super::dingtalk::{DingTalkGateway, DingTalkConfig};
use super::feishu::{FeishuGateway, FeishuConfig};
use super::telegram::{TelegramGateway, TelegramConfig};
use super::discord::{DiscordGateway, DiscordConfig};
use super::wework::{WeWorkGateway, WeWorkConfig};
use super::whatsapp::{WhatsAppGateway, WhatsAppConfig};
use super::message_handler::{MessageHandler, MessageMode};
use super::llm_config::{LLMManager, LLMConfig};
use super::connectivity_test::ConnectivityTester;
use super::IMManager;
use std::sync::Arc;
use tokio::test;

// 测试钉钉网关
#[test]
async fn test_dingtalk_gateway() {
    let config = DingTalkConfig {
        enabled: true,
        client_id: "test_client_id".to_string(),
        client_secret: "test_client_secret".to_string(),
        agent_id: None,
        robot_code: None,
        message_type: Some("markdown".to_string()),
        media_download_path: None,
        debug: Some(false),
    };
    
    let gateway = DingTalkGateway::new(config);
    
    // 测试获取配置
    let retrieved_config = gateway.get_config();
    assert_eq!(retrieved_config.enabled, true);
    assert_eq!(retrieved_config.client_id, "test_client_id");
    assert_eq!(retrieved_config.client_secret, "test_client_secret");
}

// 测试飞书网关
#[test]
async fn test_feishu_gateway() {
    let config = FeishuConfig {
        enabled: true,
        app_id: "test_app_id".to_string(),
        app_secret: "test_app_secret".to_string(),
        domain: Some("feishu".to_string()),
        encrypt_key: None,
        verification_token: None,
        render_mode: Some("text".to_string()),
        media_download_path: None,
        debug: Some(false),
    };
    
    let gateway = FeishuGateway::new(config);
    
    // 测试获取配置
    let retrieved_config = gateway.get_config();
    assert_eq!(retrieved_config.enabled, true);
    assert_eq!(retrieved_config.app_id, "test_app_id");
    assert_eq!(retrieved_config.app_secret, "test_app_secret");
    assert_eq!(retrieved_config.domain, Some("feishu".to_string()));
}

// 测试Telegram网关
#[test]
async fn test_telegram_gateway() {
    let config = TelegramConfig {
        enabled: true,
        bot_token: "test_bot_token".to_string(),
        media_download_path: None,
        debug: Some(false),
    };
    
    let gateway = TelegramGateway::new(config);
    
    // 测试获取配置
    let retrieved_config = gateway.get_config();
    assert_eq!(retrieved_config.enabled, true);
    assert_eq!(retrieved_config.bot_token, "test_bot_token");
}

// 测试Discord网关
#[test]
async fn test_discord_gateway() {
    let config = DiscordConfig {
        enabled: true,
        bot_token: "test_bot_token".to_string(),
        debug: Some(false),
    };
    
    let gateway = DiscordGateway::new(config);
    
    // 测试获取配置
    let retrieved_config = gateway.get_config();
    assert_eq!(retrieved_config.enabled, true);
    assert_eq!(retrieved_config.bot_token, "test_bot_token");
}

// 测试企业微信网关
#[test]
async fn test_wework_gateway() {
    let config = WeWorkConfig {
        enabled: true,
        corp_id: "test_corp_id".to_string(),
        agent_id: "test_agent_id".to_string(),
        secret: "test_secret".to_string(),
        token: None,
        encoding_aes_key: None,
        webhook_port: Some(8080),
        debug: Some(false),
        media_download_path: None,
    };
    
    let gateway = WeWorkGateway::new(config);
    
    // 测试获取配置
    let retrieved_config = gateway.get_config();
    assert_eq!(retrieved_config.enabled, true);
    assert_eq!(retrieved_config.corp_id, "test_corp_id");
    assert_eq!(retrieved_config.agent_id, "test_agent_id");
    assert_eq!(retrieved_config.secret, "test_secret");
}

// 测试WhatsApp网关
#[test]
async fn test_whatsapp_gateway() {
    let config = WhatsAppConfig {
        enabled: true,
        phone_number_id: Some("test_phone_number_id".to_string()),
        access_token: Some("test_access_token".to_string()),
        debug: Some(false),
        media_download_path: None,
    };
    
    let gateway = WhatsAppGateway::new(config);
    
    // 测试获取配置
    let retrieved_config = gateway.get_config();
    assert_eq!(retrieved_config.enabled, true);
    assert_eq!(retrieved_config.phone_number_id, Some("test_phone_number_id".to_string()));
    assert_eq!(retrieved_config.access_token, Some("test_access_token".to_string()));
}

// 测试消息处理器
#[test]
async fn test_message_handler() {
    let config = super::message_handler::MessageHandlerConfig {
        mode: MessageMode::Chat,
        enabled: true,
    };
    
    let handler = MessageHandler::new(config);
    
    // 测试普通聊天消息
    let chat_message = IMMessage {
        id: "test_id".to_string(),
        platform: "TestPlatform".to_string(),
        channel_id: "test_channel".to_string(),
        user_id: "test_user".to_string(),
        user_name: "Test User".to_string(),
        content: "你好".to_string(),
        timestamp: 1234567890,
        is_mention: false,
    };
    
    let chat_result = handler.handle_message(chat_message);
    assert!(chat_result.is_ok());
    assert!(chat_result.unwrap().contains("你好"));
    
    // 测试Cowork模式消息
    let cowork_config = super::message_handler::MessageHandlerConfig {
        mode: MessageMode::Cowork,
        enabled: true,
    };
    
    let cowork_handler = MessageHandler::new(cowork_config);
    
    let cowork_message = IMMessage {
        id: "test_id".to_string(),
        platform: "TestPlatform".to_string(),
        channel_id: "test_channel".to_string(),
        user_id: "test_user".to_string(),
        user_name: "Test User".to_string(),
        content: "任务测试任务".to_string(),
        timestamp: 1234567890,
        is_mention: false,
    };
    
    let cowork_result = cowork_handler.handle_message(cowork_message);
    assert!(cowork_result.is_ok());
    assert!(cowork_result.unwrap().contains("已创建任务"));
}

// 测试LLM管理器
#[test]
async fn test_llm_manager() {
    let config = LLMConfig {
        enabled: true,
        model: "gpt-3.5-turbo".to_string(),
        api_key: "test_api_key".to_string(),
        base_url: "https://api.openai.com/v1".to_string(),
        temperature: 0.7,
        max_tokens: 1024,
        skill_prompts: Default::default(),
    };
    
    let manager = LLMManager::new(config);
    
    // 测试获取配置
    let retrieved_config = manager.get_config();
    assert_eq!(retrieved_config.enabled, true);
    assert_eq!(retrieved_config.model, "gpt-3.5-turbo");
    assert_eq!(retrieved_config.api_key, "test_api_key");
    
    // 测试添加技能提示
    manager.add_skill_prompt("test_skill", "test_prompt");
    let skill_prompt = manager.get_skill_prompt("test_skill");
    assert!(skill_prompt.is_some());
    assert_eq!(skill_prompt.unwrap(), "test_prompt");
    
    // 测试删除技能提示
    manager.remove_skill_prompt("test_skill");
    let removed_skill = manager.get_skill_prompt("test_skill");
    assert!(removed_skill.is_none());
    
    // 测试配置有效性
    assert!(manager.is_config_valid());
}

// 测试连通性测试器
#[test]
async fn test_connectivity_tester() {
    let tester = ConnectivityTester::new();
    
    // 测试钉钉网关
    let dingtalk_config = DingTalkConfig {
        enabled: true,
        client_id: "test_client_id".to_string(),
        client_secret: "test_client_secret".to_string(),
        agent_id: None,
        robot_code: None,
        message_type: Some("markdown".to_string()),
        media_download_path: None,
        debug: Some(false),
    };
    let dingtalk_gateway = DingTalkGateway::new(dingtalk_config);
    
    let dingtalk_result = tester.test_dingtalk(&dingtalk_gateway).await;
    assert_eq!(dingtalk_result.platform, "DingTalk");
    assert_eq!(dingtalk_result.verdict, "fail"); // 因为配置是测试数据，鉴权会失败
    
    // 测试飞书网关
    let feishu_config = FeishuConfig {
        enabled: true,
        app_id: "test_app_id".to_string(),
        app_secret: "test_app_secret".to_string(),
        domain: Some("feishu".to_string()),
        encrypt_key: None,
        verification_token: None,
        render_mode: Some("text".to_string()),
        media_download_path: None,
        debug: Some(false),
    };
    let feishu_gateway = FeishuGateway::new(feishu_config);
    
    let feishu_result = tester.test_feishu(&feishu_gateway).await;
    assert_eq!(feishu_result.platform, "Feishu");
    assert_eq!(feishu_result.verdict, "fail"); // 因为配置是测试数据，鉴权会失败
    
    // 测试WhatsApp网关
    let whatsapp_config = WhatsAppConfig {
        enabled: true,
        phone_number_id: Some("test_phone_number_id".to_string()),
        access_token: Some("test_access_token".to_string()),
        debug: Some(false),
        media_download_path: None,
    };
    let whatsapp_gateway = WhatsAppGateway::new(whatsapp_config);
    
    let whatsapp_result = tester.test_whatsapp(&whatsapp_gateway).await;
    assert_eq!(whatsapp_result.platform, "WhatsApp");
    assert_eq!(whatsapp_result.verdict, "fail"); // 因为配置是测试数据，鉴权会失败
}

// 测试IM管理器
#[test]
async fn test_im_manager() {
    let manager = IMManager::new_default();
    
    // 测试添加网关
    let dingtalk_config = DingTalkConfig {
        enabled: true,
        client_id: "test_client_id".to_string(),
        client_secret: "test_client_secret".to_string(),
        agent_id: None,
        robot_code: None,
        message_type: Some("markdown".to_string()),
        media_download_path: None,
        debug: Some(false),
    };
    let dingtalk_gateway = DingTalkGateway::new(dingtalk_config);
    
    manager.add_gateway("dingtalk", Arc::new(dingtalk_gateway));
    
    // 验证网关已添加
    let gateway_names = manager.get_gateway_names();
    assert!(gateway_names.contains(&"dingtalk".to_string()));
    
    // 测试获取网关
    let gateway = manager.get_gateway("dingtalk");
    assert!(gateway.is_some());
}
