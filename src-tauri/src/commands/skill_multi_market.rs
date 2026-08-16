use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::PathBuf;
use tauri::{AppHandle, Manager};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub enum MarketSource {
    LinkFox,
    SkillsSh,
    ClawHub,
    SkillStore,
    Noique,
    SkillBank,
}

impl MarketSource {
    fn label(&self) -> &'static str {
        match self {
            MarketSource::LinkFox => "LinkFox Skills",
            MarketSource::SkillsSh => "Nexscope / Skills.sh",
            MarketSource::ClawHub => "ClawHub",
            MarketSource::SkillStore => "SkillStore",
            MarketSource::Noique => "Noique / cross-border-ecommerce-skills",
            MarketSource::SkillBank => "SkillBank.app",
        }
    }

    fn download_type(&self) -> &'static str {
        match self {
            MarketSource::LinkFox => "cli",
            MarketSource::SkillsSh => "curl",
            MarketSource::ClawHub => "cli",
            MarketSource::SkillStore => "cli",
            MarketSource::Noique => "curl",
            MarketSource::SkillBank => "curl",
        }
    }

    fn download_command_template(&self, skill_id: &str) -> String {
        match self {
            MarketSource::LinkFox => format!("npx linkfoxskill init {}", skill_id),
            MarketSource::SkillsSh => {
                // nexscope-ai/eCommerce-Skills 仓库结构: <skill-name>/SKILL.md
                // skill_id 格式: "eCommerce-Skills/<skill-name>"
                let parts: Vec<&str> = skill_id.splitn(2, '/').collect();
                let skill_name = parts.get(1).unwrap_or(&skill_id);
                format!("curl -fsSL https://raw.githubusercontent.com/nexscope-ai/eCommerce-Skills/main/{}/SKILL.md -o {}.md", skill_name, skill_name)
            }
            MarketSource::ClawHub => format!("npx clawhub@latest install {}", skill_id),
            MarketSource::SkillStore => format!("npx skillstore add {}", skill_id),
            MarketSource::Noique => {
                // noique/cross-border-ecommerce-skills 仓库结构: <category>/<skill-name>.md
                // skill_id 格式: "noique/<category>/<skill-name>"
                let parts: Vec<&str> = skill_id.splitn(3, '/').collect();
                let category = parts.get(1).unwrap_or(&"amazon");
                let file_name = parts.get(2).unwrap_or(&"amazon-product-selection");
                format!("curl -fsSL https://raw.githubusercontent.com/noique/cross-border-ecommerce-skills/main/{}/{}.md -o {}.md", category, file_name, file_name)
            }
            MarketSource::SkillBank => format!("https://skillbank.app/api/v1/skills/{}/download?tier=simple", skill_id),
        }
    }

    fn catalog(&self) -> Vec<MarketCatalogEntry> {
        match self {
            MarketSource::LinkFox => vec![
                catalog_entry("linkfox/auto-campaign", "Auto Campaign", "自动广告活动创建与优化", "LinkFox", self),
                catalog_entry("linkfox/keyword-research", "Keyword Research", "关键词研究与拓词工具", "LinkFox", self),
                catalog_entry("linkfox/listing-optimizer", "Listing Optimizer", "Listing 标题/五点/描述 AI 优化", "LinkFox", self),
                catalog_entry("linkfox/product-analysis", "Product Analysis", "商品数据分析与趋势洞察", "LinkFox", self),
                catalog_entry("linkfox/competitor-tracker", "Competitor Tracker", "竞品动态追踪与监控", "LinkFox", self),
            ],
            MarketSource::SkillsSh => vec![
                // nexscope-ai/eCommerce-Skills 仓库实际目录名
                catalog_entry("eCommerce-Skills/affiliate-marketing-strategy", "Affiliate Marketing Strategy", "联盟营销策略", "Nexscope", self),
                catalog_entry("eCommerce-Skills/brand-monitoring", "Brand Monitoring", "品牌监控", "Nexscope", self),
                catalog_entry("eCommerce-Skills/competitive-pricing-strategy", "Competitive Pricing Strategy", "竞争定价策略", "Nexscope", self),
                catalog_entry("eCommerce-Skills/social-media-monitor", "Social Media Monitor", "社交媒体监控", "Nexscope", self),
                catalog_entry("eCommerce-Skills/supply-chain-optimization", "Supply Chain Optimization", "供应链优化", "Nexscope", self),
                catalog_entry("eCommerce-Skills/shoppable-video", "Shoppable Video", "可购物视频", "Nexscope", self),
            ],
            MarketSource::ClawHub => vec![
                catalog_entry("clawhub/product-scraper", "Product Scraper", "多平台商品信息采集", "ClawHub", self),
                catalog_entry("clawhub/price-monitor", "Price Monitor", "价格监控与调价提醒", "ClawHub", self),
                catalog_entry("clawhub/review-analyzer", "Review Analyzer", "评论分析与情感洞察", "ClawHub", self),
                catalog_entry("clawhub/auto-pricer", "Auto Pricer", "自动调价策略引擎", "ClawHub", self),
                catalog_entry("clawhub/supply-finder", "Supply Finder", "供应链寻源与供应商匹配", "ClawHub", self),
            ],
            MarketSource::SkillStore => vec![
                catalog_entry("skillstore/cross-border-tax", "Cross Border Tax", "跨境税务计算与合规指南", "SkillStore", self),
                catalog_entry("skillstore/shipping-optimizer", "Shipping Optimizer", "物流方案优化与运费计算", "SkillStore", self),
                catalog_entry("skillstore/return-manager", "Return Manager", "退货管理与逆向物流", "SkillStore", self),
                catalog_entry("skillstore/translation-pro", "Translation Pro", "多语言 Listing 翻译与本地化", "SkillStore", self),
            ],
            MarketSource::Noique => vec![
                // noique/cross-border-ecommerce-skills 仓库: amazon/*.md 等
                catalog_entry("noique/amazon/amazon-product-selection", "Amazon Product Selection", "亚马逊选品分析", "Noique", self),
                catalog_entry("noique/amazon/amazon-listing-copywriter", "Amazon Listing Copywriter", "亚马逊 Listing 文案", "Noique", self),
                catalog_entry("noique/amazon/amazon-keyword-research", "Amazon Keyword Research", "亚马逊关键词研究", "Noique", self),
                catalog_entry("noique/amazon/amazon-market-research", "Amazon Market Research", "亚马逊市场调研", "Noique", self),
                catalog_entry("noique/amazon/amazon-ad-diagnosis", "Amazon Ad Diagnosis", "亚马逊广告诊断", "Noique", self),
            ],
            MarketSource::SkillBank => vec![
                catalog_entry("cross-reference-claims", "Cross-Reference Claims", "Cross-reference a claim against multiple independent sources", "SkillBank.app", self),
                catalog_entry("explain-with-analogy", "Explain with Analogy", "Explain a complex concept using analogy", "SkillBank.app", self),
                catalog_entry("apply-framework-to-new-domain", "Apply Framework to New Domain", "Adapt a framework from one domain to another", "SkillBank.app", self),
                catalog_entry("generate-interdisciplinary-questions", "Generate Interdisciplinary Questions", "Generate research questions at field intersections", "SkillBank.app", self),
                catalog_entry("identify-misinformation-patterns", "Identify Misinformation Patterns", "Identify rhetorical patterns of misinformation", "SkillBank.app", self),
            ],
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MarketCatalogEntry {
    pub id: String,
    pub name: String,
    pub description: String,
    pub author: String,
    pub source: MarketSource,
    pub source_label: String,
    pub version: String,
    pub tags: Vec<String>,
    pub download_command: String,
    pub download_type: String,
}

fn catalog_entry(id: &str, name: &str, description: &str, author: &str, source: &MarketSource) -> MarketCatalogEntry {
    MarketCatalogEntry {
        id: id.to_string(),
        name: name.to_string(),
        description: description.to_string(),
        author: author.to_string(),
        source: source.clone(),
        source_label: source.label().to_string(),
        version: "1.0.0".to_string(),
        tags: vec![],
        download_command: source.download_command_template(id),
        download_type: source.download_type().to_string(),
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MarketSearchResult {
    pub id: String,
    pub name: String,
    pub description: String,
    pub source: String,
    pub source_label: String,
    pub version: String,
    pub tags: Vec<String>,
    pub author: String,
    pub download_command: String,
    pub download_type: String,
    pub installed: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DownloadResult {
    pub success: bool,
    pub skill_id: String,
    pub local_path: Option<String>,
    pub stdout: String,
    pub stderr: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DownloadedSkillInfo {
    pub id: String,
    pub name: String,
    pub source: String,
    pub source_label: String,
    pub local_path: String,
    pub downloaded_at: String,
    pub file_size: u64,
}

fn market_dir(app: &AppHandle) -> PathBuf {
    let mut dir = app.path().app_data_dir().unwrap_or_else(|_| PathBuf::from("."));
    dir.push("skills_market");
    dir
}

fn read_installed_map(app: &AppHandle) -> HashMap<String, DownloadedSkillInfo> {
    let dir = market_dir(app);
    let index_path = dir.join("_index.json");
    if !index_path.exists() {
        return HashMap::new();
    }
    match std::fs::read_to_string(&index_path) {
        Ok(content) => serde_json::from_str(&content).unwrap_or_default(),
        Err(_) => HashMap::new(),
    }
}

fn write_installed_map(app: &AppHandle, map: &HashMap<String, DownloadedSkillInfo>) {
    let dir = market_dir(app);
    let _ = std::fs::create_dir_all(&dir);
    let index_path = dir.join("_index.json");
    if let Ok(content) = serde_json::to_string_pretty(map) {
        let _ = std::fs::write(&index_path, &content);
    }
}

fn match_query(text: &str, query: &str) -> bool {
    if query.is_empty() {
        return true;
    }
    let lower = query.to_lowercase();
    text.to_lowercase().contains(&lower)
}

// ── 本地索引缓存 ──────────────────────────────────────────────────
// 后台定期刷新各源的 catalog 到本地文件，搜索时先读缓存再异步刷新，
// 大幅加速首次搜索响应（避免每次都调 CLI/HTTP）。

fn index_cache_path(app: &AppHandle) -> PathBuf {
    market_dir(app).join("_catalog_cache.json")
}

/// 读取本地缓存的 catalog。返回 (entries, timestamp)。
fn read_catalog_cache(app: &AppHandle) -> Option<(Vec<MarketCatalogEntry>, i64)> {
    let path = index_cache_path(app);
    let content = std::fs::read_to_string(&path).ok()?;
    let json: serde_json::Value = serde_json::from_str(&content).ok()?;
    let ts = json["cached_at"].as_i64().unwrap_or(0);
    let entries = json["entries"].as_array()?;
    let mut result = Vec::new();
    for entry in entries {
        let source_str = entry["source"].as_str()?;
        let source = match source_str {
            "LinkFox" => MarketSource::LinkFox,
            "SkillsSh" => MarketSource::SkillsSh,
            "ClawHub" => MarketSource::ClawHub,
            "SkillStore" => MarketSource::SkillStore,
            "Noique" => MarketSource::Noique,
            "SkillBank" => MarketSource::SkillBank,
            _ => return None,
        };
        result.push(MarketCatalogEntry {
            id: entry["id"].as_str()?.to_string(),
            name: entry["name"].as_str()?.to_string(),
            description: entry["description"].as_str().unwrap_or("").to_string(),
            author: entry["author"].as_str().unwrap_or("").to_string(),
            source,
            source_label: entry["source_label"].as_str().unwrap_or("").to_string(),
            version: entry["version"].as_str().unwrap_or("1.0.0").to_string(),
            tags: entry["tags"].as_array()
                .map(|a| a.iter().filter_map(|v| v.as_str().map(String::from)).collect())
                .unwrap_or_default(),
            download_command: entry["download_command"].as_str().unwrap_or("").to_string(),
            download_type: entry["download_type"].as_str().unwrap_or("").to_string(),
        });
    }
    Some((result, ts))
}

/// 写入 catalog 缓存。
fn write_catalog_cache(app: &AppHandle, entries: &[MarketCatalogEntry]) {
    let dir = market_dir(app);
    let _ = std::fs::create_dir_all(&dir);
    let now = chrono::Utc::now().timestamp();
    let json = serde_json::json!({
        "cached_at": now,
        "entries": entries.iter().map(|e| serde_json::json!({
            "id": e.id,
            "name": e.name,
            "description": e.description,
            "author": e.author,
            "source": format!("{:?}", e.source),
            "source_label": e.source_label,
            "version": e.version,
            "tags": e.tags,
            "download_command": e.download_command,
            "download_type": e.download_type,
        })).collect::<Vec<_>>(),
    });
    if let Ok(content) = serde_json::to_string_pretty(&json) {
        let _ = std::fs::write(index_cache_path(app), &content);
    }
}

/// 后台刷新 catalog 缓存（非阻塞，失败静默）。
pub fn refresh_catalog_cache_async(app: &AppHandle) {
    let app_clone = app.clone();
    tauri::async_runtime::spawn(async move {
        let mut all_entries = Vec::new();
        let sources = vec![
            MarketSource::LinkFox,
            MarketSource::SkillsSh,
            MarketSource::ClawHub,
            MarketSource::SkillStore,
            MarketSource::Noique,
            MarketSource::SkillBank,
        ];
        for source in &sources {
            let mut entries = source.catalog();
            // 尝试实时搜索补充（在阻塞线程池执行，避免阻塞 tokio worker）
            let live = live_search_async(source, "").await;
            for e in live {
                if !entries.iter().any(|x| x.id == e.id) {
                    entries.push(e);
                }
            }
            all_entries.extend(entries);
        }
        write_catalog_cache(&app_clone, &all_entries);
        tracing::info!("[skill_market] catalog cache refreshed: {} entries", all_entries.len());
    });
}

/// 后台线程执行阻塞的实时搜索（CLI 进程 / blocking HTTP），
/// 避免阻塞 async 命令的 tokio 工作线程。失败静默返回空。
async fn live_search_async(source: &MarketSource, query: &str) -> Vec<MarketCatalogEntry> {
    let source = source.clone();
    let query = query.to_string();
    tauri::async_runtime::spawn_blocking(move || match &source {
        MarketSource::SkillBank => try_skillbank_api_search(&query),
        _ => try_cli_search(&source, &query),
    })
    .await
    .unwrap_or_default()
}

fn try_cli_search(source: &MarketSource, query: &str) -> Vec<MarketCatalogEntry> {
    let cmd = match source {
        MarketSource::LinkFox => Some(("npx", vec!["linkfoxskill", "search", query])),
        MarketSource::ClawHub => Some(("npx", vec!["clawhub", "search", query])),
        MarketSource::SkillStore => Some(("npx", vec!["skillstore", "search", query])),
        _ => None,
    };
    if let Some((program, args)) = cmd {
        let result = std::process::Command::new(program).args(&args).output();
        if let Ok(output) = result {
            if output.status.success() {
                let stdout = String::from_utf8_lossy(&output.stdout);
                return parse_cli_search_output(source, &stdout);
            }
        }
    }
    vec![]
}

fn parse_cli_search_output(source: &MarketSource, stdout: &str) -> Vec<MarketCatalogEntry> {
    let mut results = vec![];
    for line in stdout.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') || line.starts_with("---") {
            continue;
        }
        let parts: Vec<&str> = line.splitn(3, '|').collect();
        if parts.len() >= 2 {
            let id = parts[0].trim();
            let name = if parts.len() >= 2 { parts[1].trim() } else { id };
            let desc = if parts.len() >= 3 { parts[2].trim() } else { "" };
            results.push(MarketCatalogEntry {
                id: format!("{:?}/{}", source, id).to_lowercase(),
                name: name.to_string(),
                description: desc.to_string(),
                author: format!("{:?}", source),
                source: source.clone(),
                source_label: source.label().to_string(),
                version: "1.0.0".to_string(),
                tags: vec![],
                download_command: source.download_command_template(id),
                download_type: source.download_type().to_string(),
            });
        }
    }
    results
}

fn try_skillbank_api_search(query: &str) -> Vec<MarketCatalogEntry> {
    let url = format!("https://skillbank.app/api/v1/skills?search={}&limit=20", urlencoding(query));
    let client = reqwest::blocking::Client::new();
    let resp = client.get(&url).timeout(std::time::Duration::from_secs(10)).send();
    let response = match resp {
        Ok(r) => r,
        Err(_) => return vec![],
    };
    let body = match response.text() {
        Ok(b) => b,
        Err(_) => return vec![],
    };
    let parsed = match serde_json::from_str::<serde_json::Value>(&body) {
        Ok(p) => p,
        Err(_) => return vec![],
    };
    if let Some(skills) = parsed["skills"].as_array() {
        let source = MarketSource::SkillBank;
        let mut results = Vec::new();
        for skill in skills {
            let id = skill["skill_id"].as_str().unwrap_or("").to_string();
            if id.is_empty() {
                continue;
            }
            let name = skill["name"].as_str().unwrap_or("").to_string();
            let description = skill["description"].as_str().unwrap_or("").to_string();
            let tags: Vec<String> = skill["tags"].as_array()
                .map(|a| a.iter().filter_map(|v| v.as_str().map(String::from)).collect())
                .unwrap_or_default();
            results.push(MarketCatalogEntry {
                id,
                name,
                description,
                author: "SkillBank.app".to_string(),
                source: source.clone(),
                source_label: source.label().to_string(),
                version: skill["version"].as_str().unwrap_or("1.0.0").to_string(),
                tags,
                download_command: source.download_command_template(skill["skill_id"].as_str().unwrap_or("")),
                download_type: source.download_type().to_string(),
            });
        }
        return results;
    }
    vec![]
}

fn urlencoding(s: &str) -> String {
    let mut encoded = String::new();
    for byte in s.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => encoded.push(byte as char),
            b' ' => encoded.push_str("%20"),
            _ => {
                encoded.push_str(&format!("%{:02X}", byte));
            }
        }
    }
    encoded
}

#[tauri::command]
pub async fn search_multi_market(
    app: AppHandle,
    query: String,
    sources: Option<Vec<String>>,
) -> Result<Vec<MarketSearchResult>, String> {
    let installed = read_installed_map(&app);
    let source_filter: Option<Vec<MarketSource>> = sources.map(|v| {
        v.iter().filter_map(|s| match s.to_lowercase().as_str() {
            "linkfox" => Some(MarketSource::LinkFox),
            "skillssh" | "skills.sh" | "nexscope" => Some(MarketSource::SkillsSh),
            "clawhub" => Some(MarketSource::ClawHub),
            "skillstore" => Some(MarketSource::SkillStore),
            "noique" => Some(MarketSource::Noique),
            "skillbank" => Some(MarketSource::SkillBank),
            _ => None
        }).collect()
    });

    let all_sources = vec![
        MarketSource::LinkFox,
        MarketSource::SkillsSh,
        MarketSource::ClawHub,
        MarketSource::SkillStore,
        MarketSource::Noique,
        MarketSource::SkillBank,
    ];

    // 1) 先尝试读本地缓存（加速搜索响应）
    let cached = read_catalog_cache(&app);
    let now = chrono::Utc::now().timestamp();
    let cache_stale = cached.as_ref().map(|(_, ts)| now - *ts > 600).unwrap_or(true);

    // 2) 如果缓存过期或不存在，后台异步刷新（不阻塞当前搜索）
    if cache_stale {
        refresh_catalog_cache_async(&app);
    }

    let mut results: Vec<MarketSearchResult> = vec![];
    let mut all_entries: Vec<MarketCatalogEntry> = Vec::new();

    for source in &all_sources {
        if let Some(ref filter) = source_filter {
            if !filter.contains(source) {
                continue;
            }
        }

        let mut entries = source.catalog();
        if !query.is_empty() {
            let live = live_search_async(source, &query).await;
            for e in live {
                if !entries.iter().any(|x| x.id == e.id) {
                    entries.push(e);
                }
            }
        }
        all_entries.extend(entries);
    }

    // 3) 如果实时搜索结果为空且有缓存，使用缓存结果
    if all_entries.is_empty() {
        if let Some((cache_entries, _)) = cached {
            all_entries = cache_entries;
        }
    }

    for entry in all_entries {
        if entry.download_command.is_empty() {
            continue;
        }
        if !query.is_empty() && !match_query(&entry.name, &query) && !match_query(&entry.description, &query) {
            continue;
        }
        let installed = installed.contains_key(&entry.id);
        results.push(MarketSearchResult {
            id: entry.id,
            name: entry.name,
            description: entry.description,
            source: format!("{:?}", entry.source),
            source_label: entry.source_label,
            version: entry.version,
            tags: entry.tags,
            author: entry.author,
            download_command: entry.download_command,
            download_type: entry.download_type,
            installed,
        });
    }
    results.sort_by(|a, b| a.name.cmp(&b.name));
    Ok(results)
}

#[tauri::command]
pub async fn download_market_skill(
    app: AppHandle,
    source: String,
    skill_id: String,
    download_command: String,
) -> Result<DownloadResult, String> {
    let dir = market_dir(&app);
    let skill_dir = dir.join(skill_id.replace(['/', '\\'], "_"));
    let _ = std::fs::create_dir_all(&skill_dir);

    let download_type = if download_command.starts_with("curl") || download_command.starts_with("http") { "curl" } else { "cli" };

    let result = if download_type == "curl" {
        let skill_md_path = skill_dir.join("SKILL.md");
        let url = download_command
            .split_whitespace()
            .find(|p| p.starts_with("http"))
            .unwrap_or(&download_command);
        let client = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(30))
            .build()
            .map_err(|e| format!("Failed to create HTTP client: {}", e))?;
        let response = client
            .get(url)
            .send()
            .await
            .map_err(|e| format!("HTTP request failed: {}", e))?;
        let status = response.status();
        if !status.is_success() {
            return Err(format!("HTTP {} for URL: {}", status, url));
        }
        let text = response.text().await.map_err(|e| format!("Failed to read response: {}", e))?;
        std::fs::write(&skill_md_path, &text).map_err(|e| format!("Failed to write file: {}", e))?;
        DownloadResult {
            success: true,
            skill_id: skill_id.clone(),
            local_path: Some(skill_md_path.to_string_lossy().to_string()),
            stdout: format!("Downloaded SKILL.md ({} bytes)", text.len()),
            stderr: String::new(),
        }
    } else {
        // CLI 下载是阻塞子进程调用，放到阻塞线程池执行，避免卡住 async 命令
        let skill_dir_clone = skill_dir.clone();
        let command_clone = download_command.clone();
        let (status_code, stdout, stderr) = tauri::async_runtime::spawn_blocking(move || {
            let output = if cfg!(target_os = "windows") {
                let mut cmd_parts: Vec<&str> = command_clone.split_whitespace().collect();
                let program = cmd_parts.remove(0);
                std::process::Command::new("cmd")
                    .args(["/C", program])
                    .args(&cmd_parts)
                    .current_dir(&skill_dir_clone)
                    .output()
                    .map_err(|e| format!("Failed to execute command: {}", e))?
            } else {
                std::process::Command::new("sh")
                    .args(["-c", &command_clone])
                    .current_dir(&skill_dir_clone)
                    .output()
                    .map_err(|e| format!("Failed to execute command: {}", e))?
            };
            Ok::<_, String>((output.status, output.stdout, output.stderr))
        })
        .await
        .map_err(|e| format!("CLI download task failed: {}", e))??;

        let stdout = String::from_utf8_lossy(&stdout).to_string();
        let stderr = String::from_utf8_lossy(&stderr).to_string();
        let success = status_code.success();

        if !success {
            return Ok(DownloadResult {
                success: false,
                skill_id: skill_id.clone(),
                local_path: None,
                stdout,
                stderr,
            });
        }
        DownloadResult {
            success: true,
            skill_id: skill_id.clone(),
            local_path: Some(skill_dir.to_string_lossy().to_string()),
            stdout,
            stderr,
        }
    };

    if result.success {
        let mut installed = read_installed_map(&app);
        installed.insert(skill_id.clone(), DownloadedSkillInfo {
            id: skill_id.clone(),
            name: skill_id.split('/').next_back().unwrap_or(&skill_id).replace('-', " ").to_string(),
            source: source.clone(),
            source_label: source.clone(),
            local_path: result.local_path.clone().unwrap_or_default(),
            downloaded_at: chrono::Utc::now().to_rfc3339(),
            file_size: result.local_path.as_ref().and_then(|p| std::fs::metadata(p).ok()).map(|m| m.len()).unwrap_or(0),
        });
        write_installed_map(&app, &installed);
    }

    Ok(result)
}

#[tauri::command]
pub async fn list_downloaded_market_skills(
    app: AppHandle,
) -> Result<Vec<DownloadedSkillInfo>, String> {
    let installed = read_installed_map(&app);
    let mut skills: Vec<DownloadedSkillInfo> = installed.into_values().collect();
    skills.sort_by(|a, b| a.name.cmp(&b.name));
    Ok(skills)
}

#[tauri::command]
pub async fn delete_downloaded_market_skill(
    app: AppHandle,
    skill_id: String,
) -> Result<bool, String> {
    let mut installed = read_installed_map(&app);
    if installed.remove(&skill_id).is_some() {
        let dir = market_dir(&app).join(skill_id.replace(['/', '\\'], "_"));
        let _ = std::fs::remove_dir_all(&dir);
        let md_path = market_dir(&app).join(format!("{}.md", skill_id.replace('/', "_")));
        let _ = std::fs::remove_file(&md_path);
        write_installed_map(&app, &installed);
        Ok(true)
    } else {
        Ok(false)
    }
}
