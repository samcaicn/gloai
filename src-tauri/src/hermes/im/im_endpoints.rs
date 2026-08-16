// Copyright (c) 2026 MeeJoy
//
// 【铁律】所有 IM 渠道的直连 endpoint 写死在这里，不允许用户填、也不允许
// 走任何"中继"。所有 URL 抄自 openclaw (https://github.com/openclaw/openclaw)
// 和官方 SDK 源码 (larksuite/oapi-sdk-go / dingtalk SDK)。
//
// 飞书 / Lark 协议 (抄自 oapi-sdk-go v3/ws/client.go)：
//   1) HTTP POST 引导接口拿 WSS：
//        国内  https://open.feishu.cn/open-apis/connection/v1/connect
//        国际  https://open.larksuite.com/open-apis/connection/v1/connect
//      请求体: {"AppID":"cli_xxx","AppSecret":"xxx"}
//      响应:   {"code":0,"data":{"url":"wss://...?service_id=...&conn_id=..."}}
//   2) Dial 返回的 WSS URL，gorilla/websocket 默认设置
//   3) 服务端发 binary frame；客户端定时（默认 2 分钟）发 ping
//      frame（method:"ping",headers:{type:"ping"}）保活
//   4) 数据 frame method:"data", headers.type === "event" 时 payload 是
//      事件 JSON
//
// 钉钉 Stream 协议 (抄自 @soimy/openclaw-channel-dingtalk)：
//   直连 WSS: wss://wss-open-connection.dingtalk.com/connect
//   URL 携带 dingtalkAppKey (ClientID)，鉴权放在 WSS upgrade header
//   `access_token` (Basic auth: ClientID:ClientSecret base64) — 由钉钉
//   SDK 处理，握手成功后进入 subscribe 阶段（按机器人类型注册回调）。
//
// 企业微信 (WeCom) 智能机器人长连接 (aibot_subscribe 协议)：
//   抄自企微官方文档 https://developer.work.weixin.qq.com/document/path/101463
//   直连 WSS: wss://openws.work.weixin.qq.com
//   帧结构（JSON 文本帧）：
//     1) 鉴权：{"cmd":"aibot_subscribe","headers":{"req_id":"..."},"body":{"bot_id":"...","secret":"..."}}
//        响应：{"headers":{"req_id":"..."},"errcode":0,"errmsg":"ok"}
//     2) 消息回调：{"cmd":"aibot_msg_callback","headers":{"req_id":"..."},"body":{...}}
//     3) 事件回调：{"cmd":"aibot_event_callback","headers":{"req_id":"..."},"body":{...}}
//     4) 主动推送：{"cmd":"aibot_send_msg","headers":{"req_id":"..."},"body":{...}}
//     5) 流式回复：{"cmd":"aibot_respond_msg","headers":{"req_id":"..."},"body":{"stream":{"id":"...","finish":false},"text":{...}}}
//     6) 心跳：发送文本帧 "ping"（30s 间隔），服务端回文本帧 "pong"
//   连接限制：每个 bot 同一时间只能保持一个有效长连接，新连接会踢掉旧连接
//   （旧连接收到 aibot_event_callback/disconnected_event 后被服务端主动断开）。
//
// 微信 (iLink) / QQ Bot / WhatsApp：
//   没有官方直连，endpoint 由用户自建网关提供（不写死）。
//
// 参考项目：Hermes-CN-Desktop (https://github.com/Eynzof/Hermes-CN-Desktop)
//   其 `src/commands/im_onboarding.rs` 也使用相同的硬编码 URL（仅做
//   OAuth / QR 流程，不直连 WS）。本项目与之共享 URL 常量：
//     FEISHU_ACCOUNTS_BASE = "https://accounts.feishu.cn"
//     FEISHU_OPEN_BASE     = "https://open.feishu.cn"
//     LARK_ACCOUNTS_BASE   = "https://accounts.larksuite.com"
//     LARK_OPEN_BASE       = "https://open.larksuite.com"
//     WEIXIN_BASE_URL      = "https://ilinkai.weixin.qq.com"
//     WEIXIN_CDN_BASE_URL  = "https://novac2c.cdn.weixin.qq.com/c2c"

use serde::{Deserialize, Serialize};

/// IM 渠道类型（与 settings 端 channelType 字段对齐）。
///
/// 注意：所有 variant 的 endpoint 都是编译期常量，用户无法修改。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ImChannelKind {
    /// 飞书 / Lark 国内版。bootstrap = `https://open.feishu.cn/open-apis/connection/v1/connect`
    Feishu,
    /// 飞书 / Lark 国际版。bootstrap = `https://open.larksuite.com/open-apis/connection/v1/connect`
    FeishuLark,
    /// 钉钉企业内部应用 Stream 模式。直连 WSS = `wss://wss-open-connection.dingtalk.com/connect`
    DingTalk,
    /// 企业微信智能机器人长连接 (aibot_subscribe 协议)。
    /// 直连 WSS = `wss://openws.work.weixin.qq.com`
    WeCom,
    /// 通用长连接：endpoint 由用户在设置里填（这是唯一的"开放"渠道，
    /// 因为官方没提供直连；用户必须自己跑一个 openclaw / clawbot 兼容的网关）。
    LongConn,
    /// 微信（iLink/ClawBot 协议）。endpoint 由用户自建网关提供。
    Weixin,
    /// QQ Bot（官方开放平台）。endpoint 由用户自建网关提供。
    QqBot,
    /// WhatsApp Business。endpoint 由用户自建网关提供。
    WhatsApp,
    /// Telegram Bot。使用 Bot API long polling，无 WS 长连接。
    /// token 格式: bot_token (从 @BotFather 获取)。
    Telegram,
}

impl ImChannelKind {
    /// 把 settings 端填的 channelType 字符串解析成 ImChannelKind。
    pub fn parse(s: &str) -> Option<Self> {
        match s.trim().to_ascii_lowercase().as_str() {
            "feishu" | "lark" | "feishu_cn" => Some(Self::Feishu),
            "feishu_lark" | "lark_intl" | "larksuite" => Some(Self::FeishuLark),
            "dingtalk" | "ding_talk" | "ding" => Some(Self::DingTalk),
            "wecom" | "wecom_bot" | "wechat_work" => Some(Self::WeCom),
            "long_conn" | "long-conn" | "websocket" | "web_socket" => Some(Self::LongConn),
            "weixin" | "wechat" => Some(Self::Weixin),
            "qqbot" | "qq_bot" | "qq" => Some(Self::QqBot),
            "whatsapp" | "whats_app" | "wa" => Some(Self::WhatsApp),
            "telegram" | "tg" => Some(Self::Telegram),
            _ => None,
        }
    }

    /// 飞书 / Lark 专属：HTTP 引导接口 URL（POST 后返回动态 WSS URL）。
    /// 抄自 larksuite/oapi-sdk-go v3/ws/client.go: getConnURL()
    ///   requestURL := strings.TrimRight(c.domain, "/") + GenEndpointUri
    ///   // GenEndpointUri = "/open-apis/connection/v1/connect"
    pub fn feishu_bootstrap_url(&self) -> Option<&'static str> {
        match self {
            Self::Feishu => Some("https://open.feishu.cn/open-apis/connection/v1/connect"),
            Self::FeishuLark => Some("https://open.larksuite.com/open-apis/connection/v1/connect"),
            _ => None,
        }
    }

    /// 飞书 / Lark 专属：获取 app_access_token 的接口 URL（企业自建应用内部调用）。
    /// 抄自飞书开放平台文档：
    ///   https://open.feishu.cn/document/ukTMukTMukTM/ukDNz4SO0MjL5QzM/auth-v3/auth/app_access_token_internal
    ///   请求: POST {"app_id":"cli_xxx","app_secret":"xxx"}
    ///   响应: {"code":0,"app_access_token":"a-xxx","expire":7200}
    /// token 有效期 2 小时（expire=7200），过期后需用 app_secret 重新获取。
    pub fn feishu_app_access_token_url(&self) -> Option<&'static str> {
        match self {
            Self::Feishu => Some("https://open.feishu.cn/open-apis/auth/v3/app_access_token/internal"),
            Self::FeishuLark => Some("https://open.larksuite.com/open-apis/auth/v3/app_access_token/internal"),
            _ => None,
        }
    }

    /// 钉钉 Stream 直连 WSS URL（域名部分，路径由 SDK 动态返回）。
    /// 抄自钉钉 Stream 官方文档：
    ///   https://opensource.dingtalk.com/developerpedia/docs/learn/stream/protocol/
    pub fn dingtalk_wss_url(&self) -> Option<&'static str> {
        match self {
            Self::DingTalk => Some("wss://wss-open-connection.dingtalk.com/connect"),
            _ => None,
        }
    }

    /// 钉钉 API 网关基址（HTTP 引导拿 ticket 用的）。
    /// 抄自 dingtalk-stream-sdk-java `StreamClient.java`:
    ///   host = "api.dingtalk.com"
    pub fn dingtalk_api_url(&self) -> Option<&'static str> {
        match self {
            Self::DingTalk => Some("https://api.dingtalk.com/v1.0/gateway/connections/open"),
            _ => None,
        }
    }

    /// 企业微信智能机器人长连接直连 WSS URL。
    /// 抄自企微官方文档：
    ///   https://developer.work.weixin.qq.com/document/path/101463#websocket-%E8%BF%9E%E6%8E%A5%E5%9C%B0%E5%9D%80
    /// 协议：aibot_subscribe (鉴权) → aibot_msg_callback (消息) → aibot_send_msg (推送) → ping (心跳)
    pub fn wecom_wss_url(&self) -> Option<&'static str> {
        match self {
            Self::WeCom => Some("wss://openws.work.weixin.qq.com"),
            _ => None,
        }
    }

    /// 飞书 / Lark 引导接口路径（拼在 `feishu_domain()` 后）。
    /// 抄自 larksuite/oapi-sdk-go v3/ws/client.go `GenEndpointUri`。
    pub fn feishu_endpoint_path(&self) -> Option<&'static str> {
        match self {
            Self::Feishu | Self::FeishuLark => Some("/open-apis/connection/v1/connect"),
            _ => None,
        }
    }

    /// 飞书 / Lark 的 domain（拼 endpoint 路径用）。
    /// 抄自 larksuite/oapi-sdk-go v3/ws/client.go `lark.FeishuBaseUrl`。
    pub fn feishu_domain(&self) -> Option<&'static str> {
        match self {
            Self::Feishu => Some("https://open.feishu.cn"),
            Self::FeishuLark => Some("https://open.larksuite.com"),
            _ => None,
        }
    }

    // ------------------------------------------------------------------
    // 硬编码 OAuth / 配套 URL（与 Hermes-CN-Desktop `im_onboarding.rs`
    // 完全一致；用于 Feishu/Lark OAuth device flow 以及 WeChat iLink）。
    // ------------------------------------------------------------------

    /// 飞书 OAuth 入口（device flow 设备码换取 App 凭据）。
    /// 抄自 Hermes-CN-Desktop `src/commands/im_onboarding.rs::FEISHU_ACCOUNTS_BASE`。
    pub fn feishu_oauth_url(&self) -> Option<&'static str> {
        match self {
            Self::Feishu => Some("https://accounts.feishu.cn/oauth/v1/app/registration"),
            Self::FeishuLark => Some("https://accounts.larksuite.com/oauth/v1/app/registration"),
            _ => None,
        }
    }

    /// 微信 iLink 网关基址。
    /// iLink 协议：GET /ilink/bot/get_bot_qrcode → QR 码;
    /// GET /ilink/bot/get_qrcode_status → 轮询扫码状态。
    /// 抄自 @tencent-weixin/openclaw-weixin + BitFun weixin.rs。
    pub fn weixin_ilink_base_url() -> Option<&'static str> {
        Some("https://ilinkai.weixin.qq.com")
    }

    /// 微信 iLink 扫码登录入口。
    /// GET {base}/ilink/bot/get_bot_qrcode?bot_type=3 → {qrcode, qrcode_img_content}
    pub fn weixin_qr_code_url(&self) -> Option<&'static str> {
        match self {
            Self::Weixin => Some("https://ilinkai.weixin.qq.com/ilink/bot/get_bot_qrcode?bot_type=3"),
            _ => None,
        }
    }

    /// 企业微信扫码登录入口 (企微 SSO QR Connect)。
    /// 抄自企业微信开放平台文档:
    ///   https://developer.work.weixin.qq.com/document/path/91022
    /// 用户扫码后获取 corpid + user_id,用于创建企微应用渠道。
    pub fn wecom_qr_login_url(&self) -> Option<&'static str> {
        match self {
            Self::WeCom => Some("https://login.work.weixin.qq.com/wwlogin/sso/login"),
            _ => None,
        }
    }

    /// 企业微信智能机器人扫码绑定 — 获取二维码 API。
    /// 抄自 @wecom/wecom-openclaw-cli (ISC) dist/utils/qrcode.js:
    ///   GET https://work.weixin.qq.com/ai/qc/generate?source=wecom-cli&plat={1|2|3}
    ///   响应: {"data":{"scode":"...","auth_url":"..."}}
    /// auth_url 即二维码内容，用 QRCodeSVG 渲染。用户用企微 APP 扫码后
    /// 在手机端点"一键创建机器人"→ 确认 → 轮询 query_result 拿 BotID+Secret。
    pub fn wecom_qr_generate_url(&self) -> Option<&'static str> {
        match self {
            Self::WeCom => Some("https://work.weixin.qq.com/ai/qc/generate"),
            _ => None,
        }
    }

    /// 企业微信智能机器人扫码绑定 — 轮询扫码结果 API。
    /// 抄自 @wecom/wecom-openclaw-cli (ISC) dist/utils/qrcode.js:
    ///   GET https://work.weixin.qq.com/ai/qc/query_result?scode=...
    ///   响应 (等待): {"data":{"status":"..."}}
    ///   响应 (成功): {"data":{"status":"success","bot_info":{"botid":"...","secret":"..."}}}
    pub fn wecom_qr_query_url(&self) -> Option<&'static str> {
        match self {
            Self::WeCom => Some("https://work.weixin.qq.com/ai/qc/query_result"),
            _ => None,
        }
    }

    /// QQ Bot 扫码登录入口 (NTQQ 协议)。
    /// 抄自 Lagrange.Core / NapCat 开源实现:
    ///   GET https://ssl.ptlogin2.qq.com/ptqrshow → QR 图片
    ///   GET https://ssl.ptlogin2.qq.com/ptqrlogin → 轮询状态
    pub fn qqbot_qr_show_url(&self) -> Option<&'static str> {
        match self {
            Self::QqBot => Some("https://ssl.ptlogin2.qq.com/ptqrshow"),
            _ => None,
        }
    }

    /// Telegram Bot API 基址。
    /// 抄自 Telegram Bot API 文档: https://core.telegram.org/bots/api
    ///   POST https://api.telegram.org/bot{token}/{method}
    pub fn telegram_api_base(&self) -> Option<&'static str> {
        match self {
            Self::Telegram => Some("https://api.telegram.org"),
            _ => None,
        }
    }

    /// 微信 iLink CDN 基址（iLink 协议用）。
    /// 抄自 Hermes-CN-Desktop `src/commands/im_onboarding.rs::WEIXIN_CDN_BASE_URL`。
    pub fn weixin_ilink_cdn_url() -> Option<&'static str> {
        Some("https://novac2c.cdn.weixin.qq.com/c2c")
    }

    /// 这个渠道是否由"硬编码 endpoint"支持（即不需要用户填 URL）。
    pub fn is_hardcoded(&self) -> bool {
        self.feishu_bootstrap_url().is_some()
            || self.dingtalk_wss_url().is_some()
            || self.wecom_wss_url().is_some()
            || self.telegram_api_base().is_some()
    }

    /// 显示用名字（中英混合，方便日志/UI）。
    pub fn display_name(&self) -> &'static str {
        match self {
            Self::Feishu => "飞书 (Feishu 国内版)",
            Self::FeishuLark => "飞书 (Lark 国际版)",
            Self::DingTalk => "钉钉 (DingTalk Stream)",
            Self::WeCom => "企业微信智能机器人 (WebSocket 长连接)",
            Self::LongConn => "通用长连接 (用户自建网关)",
            Self::Weixin => "微信 (用户自建协议网关)",
            Self::QqBot => "QQ Bot (用户自建网关)",
            Self::WhatsApp => "WhatsApp (用户自建网关)",
            Self::Telegram => "Telegram (Bot API long polling)",
        }
    }

    /// 用户需要填写的鉴权凭据字段说明（用于设置页 placeholder/帮助）。
    pub fn credential_hint(&self) -> &'static str {
        match self {
            Self::Feishu | Self::FeishuLark => "App ID (cli_xxx) + App Secret",
            Self::DingTalk => "ClientID (dingXXX) + ClientSecret (AppSecret)",
            Self::WeCom => "BotID + Secret（WebSocket 长连接）",
            Self::LongConn => "网关鉴权 Token",
            Self::Weixin => "iLink / ClawBot 协议凭据",
            Self::QqBot => "QQ Bot AppID + ClientSecret",
            Self::WhatsApp => "WhatsApp Business API Token",
            Self::Telegram => "Telegram Bot Token (从 @BotFather 获取)",
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_aliases() {
        assert_eq!(ImChannelKind::parse("feishu"), Some(ImChannelKind::Feishu));
        assert_eq!(ImChannelKind::parse("lark"), Some(ImChannelKind::Feishu));
        assert_eq!(ImChannelKind::parse("dingtalk"), Some(ImChannelKind::DingTalk));
        assert_eq!(ImChannelKind::parse("DingTalk"), Some(ImChannelKind::DingTalk));
        assert_eq!(ImChannelKind::parse("wecom_bot"), Some(ImChannelKind::WeCom));
        assert_eq!(ImChannelKind::parse("long_conn"), Some(ImChannelKind::LongConn));
        assert_eq!(ImChannelKind::parse("weixin"), Some(ImChannelKind::Weixin));
        assert_eq!(ImChannelKind::parse("unknown"), None);
    }

    #[test]
    fn feishu_bootstrap_url_matches_oapi_sdk_go() {
        // 抄自 larksuite/oapi-sdk-go v3/ws/client.go: domain + GenEndpointUri
        // GenEndpointUri = "/open-apis/connection/v1/connect"
        // domain (国内) = lark.FeishuBaseUrl  = "https://open.feishu.cn"
        // domain (国际) = "https://open.larksuite.com"
        assert_eq!(
            ImChannelKind::Feishu.feishu_bootstrap_url(),
            Some("https://open.feishu.cn/open-apis/connection/v1/connect")
        );
        assert_eq!(
            ImChannelKind::FeishuLark.feishu_bootstrap_url(),
            Some("https://open.larksuite.com/open-apis/connection/v1/connect")
        );
        // 非飞书/飞书国际版：返回 None
        assert_eq!(ImChannelKind::DingTalk.feishu_bootstrap_url(), None);
        assert_eq!(ImChannelKind::LongConn.feishu_bootstrap_url(), None);
    }

    #[test]
    fn dingtalk_wss_url_matches_official_docs() {
        // 抄自钉钉 Stream 官方文档：
        // https://open.dingtalk.com/document/orgapp/stream
        // WSS 直连端点：wss://wss-open-connection.dingtalk.com/connect
        assert_eq!(
            ImChannelKind::DingTalk.dingtalk_wss_url(),
            Some("wss://wss-open-connection.dingtalk.com/connect")
        );
        // 非钉钉：返回 None
        assert_eq!(ImChannelKind::Feishu.dingtalk_wss_url(), None);
    }
}
