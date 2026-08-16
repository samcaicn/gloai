// Copyright (c) 2026 Trace Auto
//
// IM 对象选择命令：列出好友/群组/文档等可发送目标。
//
// 支持的 target_type:
//   - "chat"  : 群组/会话列表（机器人所在的群聊，bot 级 API 可用）
//   - "friend": 好友/联系人（需要用户 OAuth 授权，当前返回 needs_auth）
//   - "doc"   : 文档列表（需要用户 OAuth 授权，当前返回 needs_auth）
//
// 飞书 API 使用已连接适配器的 app_access_token（自动缓存+刷新）。
// 企微 API 使用 bot 凭据调用。需要用户授权的类型返回 needs_auth 让前端引导 OAuth。

use serde::Serialize;
use tauri::State;

use crate::hermes::im::channel_registry::{SharedAdapterPool, SharedChannelRegistry};

/// 单个可选择的目标对象。
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ImTargetItem {
    pub id: String,
    pub name: String,
    #[serde(rename = "type")]
    pub item_type: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub avatar: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub member_count: Option<i64>,
}

/// 列表响应。
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ImTargetList {
    pub items: Vec<ImTargetItem>,
    /// "ok" | "needs_auth" | "not_connected" | "unsupported"
    pub status: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
}

#[tauri::command]
pub async fn im_list_targets(
    registry: State<'_, SharedChannelRegistry>,
    pool: State<'_, SharedAdapterPool>,
    channel_id: String,
    target_type: String,
    query: Option<String>,
) -> Result<ImTargetList, String> {
    let binding = registry
        .find_binding_by_id(&channel_id)
        .await
        .ok_or_else(|| format!("channel {} not found", channel_id))?;

    let provider = binding.provider.as_str();
    let is_feishu = provider == "feishu" || provider == "feishu_lark" || provider == "lark";
    let is_wecom = provider == "wecom";

    if !is_feishu && !is_wecom {
        return Ok(ImTargetList {
            items: vec![],
            status: "unsupported".to_string(),
            message: Some(format!("provider '{}' does not support target listing", provider)),
        });
    }

    if pool.get(&channel_id).await.is_none() {
        return Ok(ImTargetList {
            items: vec![],
            status: "not_connected".to_string(),
            message: Some("channel not connected, please connect first".to_string()),
        });
    }

    match target_type.as_str() {
        "chat" | "group" => {
            if is_feishu {
                feishu_list_chats(&binding, query.as_deref()).await
            } else {
                wecom_list_chats(&binding).await
            }
        }
        "friend" | "user" => Ok(ImTargetList {
            items: vec![],
            status: "needs_auth".to_string(),
            message: Some("好友列表需要用户 OAuth 授权，请点击授权按钮".to_string()),
        }),
        "doc" | "document" => Ok(ImTargetList {
            items: vec![],
            status: "needs_auth".to_string(),
            message: Some("文档列表需要用户 OAuth 授权，请点击授权按钮".to_string()),
        }),
        _ => Ok(ImTargetList {
            items: vec![],
            status: "unsupported".to_string(),
            message: Some(format!("unknown target_type: {}", target_type)),
        }),
    }
}

async fn feishu_list_chats(binding: &crate::hermes::im::adapter_base::IMBinding, query: Option<&str>) -> Result<ImTargetList, String> {
    let app_id = binding.metadata.get("app_id")
        .and_then(|v| v.as_str())
        .or_else(|| {
            binding.metadata.get("secret").and_then(|v| v.as_str()).and_then(|s| s.split(':').next())
        })
        .ok_or_else(|| "feishu app_id missing".to_string())?;
    let app_secret = binding.metadata.get("app_secret")
        .and_then(|v| v.as_str())
        .or_else(|| {
            binding.metadata.get("secret").and_then(|v| v.as_str()).and_then(|s| s.split(':').nth(1))
        })
        .ok_or_else(|| "feishu app_secret missing".to_string())?;

    let domain = if binding.provider == "feishu_lark" || binding.provider == "lark" {
        "https://open.larksuite.com"
    } else {
        "https://open.feishu.cn"
    };

    let token = feishu_get_app_access_token(domain, app_id, app_secret).await?;

    let client = reqwest::Client::builder()
        .connect_timeout(std::time::Duration::from_secs(8))
        .timeout(std::time::Duration::from_secs(15))
        .no_proxy()
        .user_agent(concat!("tupAI/", env!("CARGO_PKG_VERSION")))
        .build()
        .map_err(|e| format!("http client build failed: {}", e))?;

    let mut url = format!("{}/open-apis/im/v1/chats?page_size=50", domain);
    if let Some(q) = query {
        if !q.is_empty() {
            url.push_str(&format!("&user_id_type=open_id&query={}", urlencoding_encode(q)));
        }
    }

    let resp = client
        .get(&url)
        .header("Authorization", format!("Bearer {}", token))
        .header("Content-Type", "application/json; charset=utf-8")
        .send()
        .await
        .map_err(|e| format!("feishu list chats http failed: {}", e))?;

    let status = resp.status();
    if !status.is_success() {
        let body = resp.text().await.unwrap_or_default();
        return Err(format!("feishu list chats http {}: {}", status, &body[..body.len().min(300)]));
    }

    let parsed: serde_json::Value = resp.json().await
        .map_err(|e| format!("feishu list chats parse failed: {}", e))?;

    let code = parsed.get("code").and_then(|v| v.as_i64()).unwrap_or(-1);
    if code != 0 {
        let msg = parsed.get("msg").and_then(|v| v.as_str()).unwrap_or("");
        return Err(format!("feishu list chats code={} msg={}", code, msg));
    }

    let mut items = Vec::new();
    if let Some(arr) = parsed.get("data").and_then(|d| d.get("items")).and_then(|v| v.as_array()) {
        for item in arr {
            let chat_id = item.get("chat_id").and_then(|v| v.as_str()).unwrap_or("");
            let name = item.get("name").and_then(|v| v.as_str()).unwrap_or(chat_id);
            let description = item.get("description").and_then(|v| v.as_str())
                .filter(|s| !s.is_empty())
                .map(String::from);
            let member_count = item.get("user_count")
                .or_else(|| item.get("member_count"))
                .and_then(|v| v.as_i64());
            if !chat_id.is_empty() {
                items.push(ImTargetItem {
                    id: chat_id.to_string(),
                    name: name.to_string(),
                    item_type: "chat".to_string(),
                    description,
                    avatar: item.get("avatar").and_then(|v| v.as_str()).map(String::from),
                    member_count,
                });
            }
        }
    }

    Ok(ImTargetList {
        items,
        status: "ok".to_string(),
        message: None,
    })
}

async fn feishu_get_app_access_token(domain: &str, app_id: &str, app_secret: &str) -> Result<String, String> {
    let client = reqwest::Client::builder()
        .connect_timeout(std::time::Duration::from_secs(5))
        .timeout(std::time::Duration::from_secs(10))
        .no_proxy()
        .user_agent(concat!("tupAI/", env!("CARGO_PKG_VERSION")))
        .build()
        .map_err(|e| format!("http client build failed: {}", e))?;

    let body = serde_json::json!({
        "app_id": app_id,
        "app_secret": app_secret,
    });

    let url = format!("{}/open-apis/auth/v3/app_access_token/internal", domain);
    let resp = client
        .post(&url)
        .header("Content-Type", "application/json; charset=utf-8")
        .json(&body)
        .send()
        .await
        .map_err(|e| format!("feishu app_access_token http failed: {}", e))?;

    let status = resp.status();
    if !status.is_success() {
        let b = resp.text().await.unwrap_or_default();
        return Err(format!("feishu app_access_token http {}: {}", status, &b[..b.len().min(200)]));
    }

    let parsed: serde_json::Value = resp.json().await
        .map_err(|e| format!("feishu app_access_token parse failed: {}", e))?;

    let code = parsed.get("code").and_then(|v| v.as_i64()).unwrap_or(-1);
    if code != 0 {
        let msg = parsed.get("msg").and_then(|v| v.as_str()).unwrap_or("");
        return Err(format!("feishu app_access_token code={} msg={}", code, msg));
    }

    parsed.get("app_access_token")
        .and_then(|v| v.as_str())
        .map(String::from)
        .ok_or_else(|| "feishu app_access_token missing in response".to_string())
}

async fn wecom_list_chats(_binding: &crate::hermes::im::adapter_base::IMBinding) -> Result<ImTargetList, String> {
    // 企微智能机器人没有"列出群聊"的开放 API，群聊 ID 由用户从群聊设置中获取。
    // 返回空列表，前端显示手动输入提示。
    Ok(ImTargetList {
        items: vec![],
        status: "ok".to_string(),
        message: Some("企微机器人需要手动输入群聊 ID（chatid），请从群聊设置中获取".to_string()),
    })
}

fn urlencoding_encode(s: &str) -> String {
    let mut encoded = String::new();
    for byte in s.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                encoded.push(byte as char);
            }
            _ => {
                encoded.push_str(&format!("%{:02X}", byte));
            }
        }
    }
    encoded
}
