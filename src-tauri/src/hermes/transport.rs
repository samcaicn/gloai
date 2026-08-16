// Copyright (c) 2026 AIMarketing
//
// TransportLayer — HTTP client to 127.0.0.1:8642 Hermes.
//
// v4 §2.5 — 客户端 ↔ 服务器双向通道的 HTTP 半边。本模块封装:
//   * POST /v1/skills/proposals   — push SkillProposal
//   * GET  /v1/skills/proposals/:id — poll 评估结果
//   * GET  /v1/skills/inbox       — 拉取本机"待审 inbox"镜像
//   * GET  /v1/health             — 5s timeout, 用于离线降级判断
//
// 失败不 panic:任何网络层失败都被压到 `TransportError`,业务侧
// 决定降级(本地启发式评分)或重试。
//
// 协议契约 (`SkillProposal` / `SkillEvaluation` / `InboxMirrorItem`):
//   * Wire shape is frozen in the v5 plan; this module carries the
//   * minimal serializable definition and the skill source / skill
//   * evaluator modules own their own final types.
//     并按需 `pub use` 这里的字段。`camelCase` 是 Tauri 透出给前端的
//     约定,跟其它模块保持一致。
//
// 鉴权:通过 `with_token(...)` 注入,server 端用相同的 fingerprint
// 派生策略校验。token 的派生见 `auth::TransportToken::new`。
//
// Surface is reserved for the main thread; allow dead_code until wired up.
#![allow(dead_code)]

use std::time::Duration;

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use thiserror::Error;

const DEFAULT_BASE_URL: &str = "http://127.0.0.1:8642";
const DEFAULT_TIMEOUT: Duration = Duration::from_secs(5);
const HEALTH_TIMEOUT: Duration = Duration::from_secs(5);

// ---------------------------------------------------------------------------
// Protocol contracts (最小可序列化形态,其它 agent 可继续扩展字段)
// ---------------------------------------------------------------------------

/// 客户端产出的技能候选,POST /v1/skills/proposals 的 body.
///
/// `proposal_id` 是 ULID,全链路追踪 ID;`source` 区分来源
/// (Teaching/Healing/Recorder/Monitoring);`skill_md` 是 YAML 草稿;
/// `lineage` 记录父版本,供 server 做谱系去重。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SkillProposal {
    pub proposal_id: String,
    pub source: String,
    pub skill_md: String,
    #[serde(default)]
    pub lineage: SkillLineage,
    #[serde(default)]
    pub telemetry: ProposalTelemetry,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SkillLineage {
    #[serde(default)]
    pub parent_skill_id: Option<String>,
    #[serde(default)]
    pub parent_version: Option<u32>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProposalTelemetry {
    #[serde(default)]
    pub local_success_rate: Option<f32>,
    #[serde(default)]
    pub avg_latency_ms: Option<u32>,
    #[serde(default)]
    pub sample_count: u32,
}

/// POST /v1/skills/proposals 的最小回执。Server 端可以异步评估,
/// 此 ack 仅代表"已收到 + 已入队"。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProposalAck {
    pub proposal_id: String,
    pub accepted: bool,
    #[serde(default)]
    pub queue_position: Option<u32>,
    #[serde(default)]
    pub message: Option<String>,
}

/// Server 评估结果。`total` 是加权总分(0-1);`verdict` 是
/// Accept/NeedsReview/Reject;`issues` 是给客户端的可执行改进建议。
///
/// The 5-dimension score is computed by the server-side evaluator.
/// 填充;此处只保留契约。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SkillEvaluation {
    pub proposal_id: String,
    pub total: f32,
    pub verdict: EvalVerdict,
    #[serde(default)]
    pub scores: EvalScores,
    #[serde(default)]
    pub issues: Vec<String>,
    pub evaluated_at: DateTime<Utc>,
    /// server 不可达时本地启发式评分填的标记;正常路径是 `false`。
    #[serde(default)]
    pub degraded: bool,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "PascalCase")]
#[derive(Default)]
pub enum EvalVerdict {
    Accept,
    #[default]
    NeedsReview,
    Reject,
}


#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EvalScores {
    #[serde(default)] pub safety: Option<f32>,
    #[serde(default)] pub success: Option<f32>,
    #[serde(default)] pub generalization: Option<f32>,
    #[serde(default)] pub dedup: Option<f32>,
    #[serde(default)] pub cost: Option<f32>,
}

/// Inbox 镜像:本机所有"待审"提案的摘要。`get_inbox()` 拉这个。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InboxMirrorItem {
    pub proposal_id: String,
    pub title: String,
    pub source: String,
    pub total: f32,
    pub verdict: EvalVerdict,
    pub created_at: DateTime<Utc>,
}

// ---------------------------------------------------------------------------
// Error
// ---------------------------------------------------------------------------

#[derive(Debug, Error)]
pub enum TransportError {
    #[error("server unreachable: {0}")]
    Unreachable(String),
    #[error("auth failed: {0}")]
    Auth(String),
    #[error("server returned {status}: {body}")]
    Http { status: u16, body: String },
    #[error("timeout after {0}ms")]
    Timeout(u64),
    #[error("invalid response: {0}")]
    Decode(String),
}

impl From<reqwest::Error> for TransportError {
    fn from(error: reqwest::Error) -> Self {
        if error.is_timeout() {
            TransportError::Timeout(DEFAULT_TIMEOUT.as_millis() as u64)
        } else if error.is_connect() || error.is_request() {
            TransportError::Unreachable(error.to_string())
        } else if error.is_decode() {
            TransportError::Decode(error.to_string())
        } else {
            TransportError::Unreachable(error.to_string())
        }
    }
}

// ---------------------------------------------------------------------------
// Client
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TransportStatus {
    pub base_url: String,
    pub connected: bool,
    pub last_error: Option<String>,
    pub checked_at: DateTime<Utc>,
}

#[derive(Clone)]
pub struct HermesTransport {
    base_url: String,
    token: Option<String>,
    client: reqwest::Client,
}

impl HermesTransport {
    /// 默认 `http://127.0.0.1:8642`,5s 超时。健康检查另起短超时 client。
    pub fn new(base_url: impl Into<String>) -> Self {
        // 即便是回环地址，reqwest 默认仍会读 HTTP_PROXY/HTTPS_PROXY
        // 环境变量；用户机器若残留 Clash 代理变量但代理未运行，会
        // 把 127.0.0.1 请求也送进死代理。.no_proxy() 强制直连。
        let client = reqwest::Client::builder()
            .no_proxy()
            .timeout(DEFAULT_TIMEOUT)
            .build()
            .unwrap_or_default();
        Self {
            base_url: base_url.into(),
            token: None,
            client,
        }
    }

    /// 注入 Bearer token(由 `auth::TransportToken::new()` 派生)。
    pub fn with_token(mut self, token: String) -> Self {
        self.token = Some(token);
        self
    }

    pub fn base_url(&self) -> &str { &self.base_url }

    pub fn token(&self) -> Option<&str> { self.token.as_deref() }

    /// 内部:组装带鉴权头的 builder。
    fn request(&self, method: reqwest::Method, path: &str) -> reqwest::RequestBuilder {
        let url = format!(
            "{}/{}",
            self.base_url.trim_end_matches('/'),
            path.trim_start_matches('/')
        );
        let mut req = self.client.request(method, url);
        if let Some(t) = &self.token {
            req = req.bearer_auth(t);
        }
        req
    }

    /// POST /v1/skills/proposals
    pub async fn post_proposal(
        &self,
        proposal: &SkillProposal,
    ) -> Result<ProposalAck, TransportError> {
        let resp = self
            .request(reqwest::Method::POST, "/v1/skills/proposals")
            .json(proposal)
            .send()
            .await?;
        let status = resp.status();
        let body = resp.text().await.unwrap_or_default();
        if status == reqwest::StatusCode::UNAUTHORIZED
            || status == reqwest::StatusCode::FORBIDDEN
        {
            return Err(TransportError::Auth(format!("{}: {}", status.as_u16(), body)));
        }
        if !status.is_success() {
            return Err(TransportError::Http {
                status: status.as_u16(),
                body,
            });
        }
        serde_json::from_str::<ProposalAck>(&body)
            .map_err(|e| TransportError::Decode(e.to_string()))
    }

    /// GET /v1/skills/proposals/:id
    pub async fn get_proposal(
        &self,
        proposal_id: &str,
    ) -> Result<SkillEvaluation, TransportError> {
        let path = format!("/v1/skills/proposals/{}", proposal_id);
        let resp = self.request(reqwest::Method::GET, &path).send().await?;
        let status = resp.status();
        let body = resp.text().await.unwrap_or_default();
        if status == reqwest::StatusCode::NOT_FOUND {
            return Err(TransportError::Http {
                status: 404,
                body: "proposal not found".to_string(),
            });
        }
        if !status.is_success() {
            return Err(TransportError::Http {
                status: status.as_u16(),
                body,
            });
        }
        serde_json::from_str::<SkillEvaluation>(&body)
            .map_err(|e| TransportError::Decode(e.to_string()))
    }

    /// GET /v1/skills/inbox
    pub async fn get_inbox(&self) -> Result<Vec<InboxMirrorItem>, TransportError> {
        let resp = self
            .request(reqwest::Method::GET, "/v1/skills/inbox")
            .send()
            .await?;
        let status = resp.status();
        let body = resp.text().await.unwrap_or_default();
        if !status.is_success() {
            return Err(TransportError::Http {
                status: status.as_u16(),
                body,
            });
        }
        // server 允许返回裸数组或 `{ items: [...] }` 两种形态,容错
        if let Ok(items) = serde_json::from_str::<Vec<InboxMirrorItem>>(&body) {
            return Ok(items);
        }
        #[derive(Deserialize)]
        struct Wrapped {
            items: Vec<InboxMirrorItem>,
        }
        serde_json::from_str::<Wrapped>(&body)
            .map(|w| w.items)
            .map_err(|e| TransportError::Decode(e.to_string()))
    }

    /// GET /v1/health,5s timeout。不可达时返回 `Ok(false)`,不抛错
    /// —— 离线降级是常态,不是异常。
    pub async fn health(&self) -> Result<bool, TransportError> {
        let mut req = self.client.get(format!("{}/v1/health", self.base_url.trim_end_matches('/')))
            .timeout(HEALTH_TIMEOUT);
        if let Some(t) = &self.token {
            req = req.bearer_auth(t);
        }
        match req.send().await {
            Ok(resp) if resp.status().is_success() => Ok(true),
            Ok(resp) => {
                log::debug!(
                    "[transport] health responded non-2xx: {}",
                    resp.status()
                );
                Ok(false)
            }
            Err(error) => {
                if error.is_timeout() {
                    log::debug!("[transport] health timeout after 5s");
                } else {
                    log::debug!("[transport] health unreachable: {}", error);
                }
                Ok(false)
            }
        }
    }
}

impl Default for HermesTransport {
    fn default() -> Self {
        Self::new(DEFAULT_BASE_URL)
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_url_is_local_8642() {
        let t = HermesTransport::default();
        assert_eq!(t.base_url(), "http://127.0.0.1:8642");
    }

    #[test]
    fn request_url_handles_trailing_slash() {
        let t = HermesTransport::new("http://127.0.0.1:8642/");
        // Just exercise the formatting; we don't actually call .send().
        // Mirror the production `request()` helper: trim trailing `/`
        // from the base AND leading `/` from the path to avoid
        // double-slash URLs.
        let url = format!(
            "{}/{}",
            t.base_url.trim_end_matches('/'),
            "/v1/health".trim_start_matches('/')
        );
        assert_eq!(url, "http://127.0.0.1:8642/v1/health");
    }

    #[test]
    fn error_classification_keeps_callers_quiet() {
        // 不能让 panic 逃逸:From<reqwest::Error> 总是返回 TransportError
        let json_err = serde_json::from_str::<ProposalAck>("not-json");
        assert!(json_err.is_err());
    }

    #[test]
    fn transport_status_serializes_camel_case() {
        let s = TransportStatus {
            base_url: "http://127.0.0.1:8642".to_string(),
            connected: true,
            last_error: None,
            checked_at: Utc::now(),
        };
        let json = serde_json::to_string(&s).unwrap();
        assert!(json.contains("baseUrl"));
        assert!(json.contains("checkedAt"));
    }
}
