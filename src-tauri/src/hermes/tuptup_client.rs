// Copyright (c) 2026 MeeJoy
//

use std::time::Duration;
use reqwest::Client as HttpClient;
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct TuptupConfig {
    pub base_url: String,
    pub api_key: Option<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct TuptupPersona {
    pub id: String,
    pub name: String,
    pub description: Option<String>,
    pub tags: Vec<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct TuptupModel {
    pub id: String,
    pub name: String,
    pub context_window: u32,
    pub capabilities: Vec<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct TuptupCatalog {
    pub personas: Vec<TuptupPersona>,
    pub models: Vec<TuptupModel>,
}

pub struct TuptupClient {
    cfg: TuptupConfig,
    http: HttpClient,
}

impl TuptupClient {
    pub fn new(cfg: TuptupConfig) -> Self {
        let http = HttpClient::builder().no_proxy().timeout(Duration::from_secs(30)).build().unwrap_or_default();
        Self { cfg, http }
    }

    pub async fn get_catalog(&self) -> Result<TuptupCatalog, String> {
        let url = format!("{}/api/catalog", self.cfg.base_url.trim_end_matches('/'));
        let mut req = self.http.get(&url);
        if let Some(k) = &self.cfg.api_key { req = req.bearer_auth(k); }
        let resp = req.send().await.map_err(|e| e.to_string())?;
        if !resp.status().is_success() { return Err(format!("http {}", resp.status())); }
        resp.json::<TuptupCatalog>().await.map_err(|e| e.to_string())
    }
}
