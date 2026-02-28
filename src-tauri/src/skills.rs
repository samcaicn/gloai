use lazy_static::lazy_static;
use regex::Regex;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::SystemTime;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Skill {
    pub id: String,
    pub name: String,
    pub description: String,
    pub enabled: bool,
    pub is_official: bool,
    pub is_built_in: bool,
    pub updated_at: i64,
    pub prompt: String,
    pub skill_path: String,
    pub order: i32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SkillConfig {
    pub enabled: bool,
    pub settings: Option<serde_json::Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SkillDefaultConfig {
    pub order: Option<i32>,
    pub enabled: Option<bool>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SkillsConfig {
    pub version: Option<i32>,
    pub description: Option<String>,
    pub defaults: HashMap<String, SkillDefaultConfig>,
}

#[derive(Debug, Clone)]
struct ParsedFrontmatter {
    name: Option<String>,
    description: Option<String>,
    official: Option<bool>,
}

#[derive(Clone)]
pub struct SkillsManager {
    skills_dir: PathBuf,
    config_path: PathBuf,
    bundled_skills_dir: Option<PathBuf>,
    skills_cache: Arc<Mutex<Option<Vec<Skill>>>>,
    config_cache: Arc<Mutex<Option<HashMap<String, SkillConfig>>>>,
    #[allow(dead_code)]
    skills_defaults_cache: Arc<Mutex<Option<HashMap<String, SkillDefaultConfig>>>>,
}

const SKILL_FILE_NAME: &str = "SKILL.md";
const SKILLS_CONFIG_FILE: &str = "skills.config.json";

lazy_static! {
    static ref FRONTMATTER_RE: Regex = Regex::new(r"^---\r?\n([\s\S]*?)\r?\n---\r?\n?").unwrap();
}

impl SkillsManager {
    pub fn new(skills_dir: PathBuf, config_path: PathBuf) -> Self {
        Self {
            skills_dir,
            config_path,
            bundled_skills_dir: None,
            skills_cache: Arc::new(Mutex::new(None)),
            config_cache: Arc::new(Mutex::new(None)),
            skills_defaults_cache: Arc::new(Mutex::new(None)),
        }
    }

    pub fn with_bundled_skills(mut self, bundled_dir: PathBuf) -> Self {
        self.bundled_skills_dir = Some(bundled_dir);
        self
    }

    pub fn get_skills_dir(&self) -> &Path {
        &self.skills_dir
    }

    /// 同步内置技能到用户数据目录
    pub fn sync_bundled_skills(&self) -> anyhow::Result<()> {
        let bundled_dir = match &self.bundled_skills_dir {
            Some(dir) => dir,
            None => {
                println!("[skills] No bundled skills directory configured");
                return Ok(());
            }
        };

        println!("[skills] Platform: {}", std::env::consts::OS);
        println!("[skills] Bundled skills directory: {:?}", bundled_dir);
        println!("[skills] User skills directory: {:?}", self.skills_dir);

        if !bundled_dir.exists() {
            println!(
                "[skills] Bundled skills directory not found: {:?}",
                bundled_dir
            );
            // 列出父目录内容以便调试
            if let Some(parent) = bundled_dir.parent() {
                println!("[skills] Parent directory: {:?}", parent);
                if let Ok(entries) = fs::read_dir(parent) {
                    println!("[skills] Parent directory contents:");
                    for entry in entries.flatten() {
                        println!("  - {:?}", entry.file_name());
                    }
                }
            }
            return Ok(());
        }

        // 确保用户技能目录存在
        if !self.skills_dir.exists() {
            println!("[skills] Creating user skills directory");
            fs::create_dir_all(&self.skills_dir)?;
        }

        // 读取内置技能目录
        let bundled_skills = self.list_skill_dirs(bundled_dir)?;
        println!("[skills] Found {} bundled skills", bundled_skills.len());

        let mut synced_count = 0;
        let mut skipped_count = 0;

        for skill_dir in bundled_skills {
            let skill_id = skill_dir
                .file_name()
                .and_then(|n| n.to_str())
                .unwrap_or("unknown");

            let target_dir = self.skills_dir.join(skill_id);

            // 如果技能已存在，跳过
            if target_dir.exists() {
                skipped_count += 1;
                continue;
            }

            // 复制内置技能到用户目录
            match self.copy_skill_dir(&skill_dir, &target_dir) {
                Ok(_) => {
                    println!("[skills] Synced bundled skill: {}", skill_id);
                    synced_count += 1;
                }
                Err(e) => println!("[skills] Failed to sync skill {}: {}", skill_id, e),
            }
        }

        println!(
            "[skills] Sync complete: {} synced, {} skipped",
            synced_count, skipped_count
        );

        // 同步 skills.config.json
        let bundled_config = bundled_dir.join(SKILLS_CONFIG_FILE);
        let target_config = self.skills_dir.join(SKILLS_CONFIG_FILE);

        if bundled_config.exists() && !target_config.exists() {
            match fs::copy(&bundled_config, &target_config) {
                Ok(_) => println!("[skills] Synced skills.config.json"),
                Err(e) => println!("[skills] Failed to sync skills.config.json: {}", e),
            }
        }

        Ok(())
    }

    /// 列出目录中的所有技能
    fn list_skill_dirs(&self, root: &Path) -> anyhow::Result<Vec<PathBuf>> {
        let mut skill_dirs = Vec::new();

        if !root.exists() {
            return Ok(skill_dirs);
        }

        for entry in fs::read_dir(root)? {
            let entry = entry?;
            let path = entry.path();

            if path.is_dir() {
                let skill_file = path.join(SKILL_FILE_NAME);
                if skill_file.exists() {
                    skill_dirs.push(path);
                }
            }
        }

        Ok(skill_dirs)
    }

    /// 复制技能目录
    fn copy_skill_dir(&self, source: &Path, target: &Path) -> anyhow::Result<()> {
        fs::create_dir_all(target)?;

        for entry in fs::read_dir(source)? {
            let entry = entry?;
            let source_path = entry.path();
            let target_path = target.join(entry.file_name());

            if source_path.is_dir() {
                self.copy_skill_dir(&source_path, &target_path)?;
            } else {
                fs::copy(&source_path, &target_path)?;
            }
        }

        Ok(())
    }

    /// 解析 SKILL.md 的前置元数据
    fn parse_frontmatter(&self, content: &str) -> ParsedFrontmatter {
        let normalized = content.strip_prefix('\u{FEFF}').unwrap_or(content);

        let frontmatter_text = match FRONTMATTER_RE.captures(normalized) {
            Some(caps) => caps.get(1).map(|m| m.as_str()).unwrap_or(""),
            None => {
                return ParsedFrontmatter {
                    name: None,
                    description: None,
                    official: None,
                }
            }
        };

        let mut name = None;
        let mut description = None;
        let mut official = None;

        for line in frontmatter_text.lines() {
            let trimmed = line.trim();
            if trimmed.is_empty() || trimmed.starts_with('#') {
                continue;
            }

            if let Some(colon_pos) = trimmed.find(':') {
                let key = trimmed[..colon_pos].trim();
                let value = trimmed[colon_pos + 1..]
                    .trim()
                    .trim_matches('"')
                    .trim_matches('\'');

                match key {
                    "name" => name = Some(value.to_string()),
                    "description" => description = Some(value.to_string()),
                    "official" | "isOfficial" => {
                        official = Some(self.is_truthy(value));
                    }
                    _ => {}
                }
            }
        }

        ParsedFrontmatter {
            name,
            description,
            official,
        }
    }

    /// 检查字符串是否为真值
    fn is_truthy(&self, value: &str) -> bool {
        let normalized = value.trim().to_lowercase();
        normalized == "true" || normalized == "yes" || normalized == "1"
    }

    /// 从内容中提取描述（第一行非空文本）
    fn extract_description(&self, content: &str) -> String {
        for line in content.lines() {
            let trimmed = line.trim();
            if !trimmed.is_empty() {
                return trimmed.trim_start_matches('#').trim().to_string();
            }
        }
        String::new()
    }

    /// 从指定根目录加载技能默认配置
    fn load_skills_defaults_from_root(
        &self,
        root: &Path,
    ) -> anyhow::Result<HashMap<String, SkillDefaultConfig>> {
        let mut defaults = HashMap::new();
        let config_path = root.join(SKILLS_CONFIG_FILE);

        if config_path.exists() {
            if let Ok(content) = fs::read_to_string(&config_path) {
                if let Ok(config) = serde_json::from_str::<SkillsConfig>(&content) {
                    defaults = config.defaults;
                    println!(
                        "[skills] Loaded {} defaults from {:?}",
                        defaults.len(),
                        config_path
                    );
                }
            }
        }

        Ok(defaults)
    }

    /// 加载技能默认配置（从所有可能的根目录合并）
    #[allow(dead_code)]
    fn load_skills_defaults(&self) -> HashMap<String, SkillDefaultConfig> {
        // 检查缓存
        if let Ok(cache) = self.skills_defaults_cache.lock() {
            if let Some(defaults) = cache.as_ref() {
                return defaults.clone();
            }
        }

        let mut defaults = HashMap::new();

        // 先从内置技能目录加载，然后从用户目录加载（用户目录覆盖内置）
        if let Some(bundled_dir) = &self.bundled_skills_dir {
            if let Ok(bundled_defaults) = self.load_skills_defaults_from_root(bundled_dir) {
                for (id, config) in bundled_defaults {
                    defaults.insert(id, config);
                }
            }
        }

        if let Ok(user_defaults) = self.load_skills_defaults_from_root(&self.skills_dir) {
            for (id, config) in user_defaults {
                defaults.insert(id, config);
            }
        }

        // 更新缓存
        if let Ok(mut cache) = self.skills_defaults_cache.lock() {
            *cache = Some(defaults.clone());
        }

        defaults
    }

    pub async fn load_skills(&self) -> anyhow::Result<Vec<Skill>> {
        // 首先同步内置技能
        self.sync_bundled_skills()?;

        let mut skills = Vec::new();
        let mut skill_map = std::collections::HashMap::new();

        // 获取所有技能根目录（内置技能目录在前，用户目录在后，后者覆盖前者）
        let mut roots = Vec::new();
        if let Some(bundled_dir) = &self.bundled_skills_dir {
            roots.push(bundled_dir.clone());
        }
        roots.push(self.skills_dir.clone());

        println!("[skills] Loading skills from roots: {:?}", roots);

        for root in roots {
            if !root.exists() {
                continue;
            }

            let skill_dirs = self.list_skill_dirs(&root)?;
            let defaults = self.load_skills_defaults_from_root(&root)?;
            let config = self.load_config().await?;

            for skill_dir in skill_dirs {
                if let Ok(skill) = self.load_skill_from_dir(&skill_dir, &defaults, &config) {
                    // 用户目录的技能会覆盖内置技能
                    skill_map.insert(skill.id.clone(), skill);
                }
            }
        }

        skills = skill_map.into_values().collect();

        // 按 order 排序，然后按名称排序
        skills.sort_by(|a, b| {
            let order_cmp = a.order.cmp(&b.order);
            if order_cmp != std::cmp::Ordering::Equal {
                return order_cmp;
            }
            a.name.cmp(&b.name)
        });

        println!("[skills] Loaded {} skills", skills.len());

        // 更新缓存
        let mut cache = self.skills_cache.lock().unwrap();
        *cache = Some(skills.clone());

        Ok(skills)
    }

    fn load_skill_from_dir(
        &self,
        dir: &Path,
        defaults: &HashMap<String, SkillDefaultConfig>,
        config: &HashMap<String, SkillConfig>,
    ) -> anyhow::Result<Skill> {
        let skill_md_path = dir.join(SKILL_FILE_NAME);

        let id = dir
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("unknown")
            .to_string();

        // 读取 SKILL.md
        let (name, description, prompt, is_official, updated_at) = if skill_md_path.exists() {
            let content = fs::read_to_string(&skill_md_path)?;
            let frontmatter = self.parse_frontmatter(&content);

            let name = frontmatter.name.unwrap_or_else(|| id.clone());
            let description = frontmatter
                .description
                .or_else(|| Some(self.extract_description(&content)))
                .unwrap_or_else(|| name.clone());

            let is_official = frontmatter.official.unwrap_or(false);

            let updated_at = fs::metadata(&skill_md_path)?
                .modified()?
                .duration_since(SystemTime::UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs() as i64;

            (name, description, content, is_official, updated_at)
        } else {
            (
                id.clone(),
                id.clone(),
                String::new(),
                false,
                chrono::Utc::now().timestamp(),
            )
        };

        // 获取默认配置
        let default_config = defaults.get(&id);
        let default_enabled = default_config.and_then(|c| c.enabled).unwrap_or(true);
        let order = default_config.and_then(|c| c.order).unwrap_or(999);

        // 获取用户配置
        let enabled = config
            .get(&id)
            .map(|c| c.enabled)
            .unwrap_or(default_enabled);

        // 检查是否为内置技能
        let is_built_in = self
            .bundled_skills_dir
            .as_ref()
            .map(|dir| dir.join(&id).exists())
            .unwrap_or(false);

        Ok(Skill {
            id: id.clone(),
            name,
            description,
            enabled,
            is_official,
            is_built_in,
            updated_at,
            prompt,
            skill_path: skill_md_path.to_string_lossy().into(),
            order,
        })
    }

    pub async fn load_config(&self) -> anyhow::Result<HashMap<String, SkillConfig>> {
        let config_cache = self.config_cache.lock().unwrap();
        if let Some(config) = config_cache.as_ref() {
            return Ok(config.clone());
        }
        drop(config_cache);

        let config = if self.config_path.exists() {
            let content = fs::read_to_string(&self.config_path)?;
            serde_json::from_str(&content).unwrap_or_default()
        } else {
            HashMap::new()
        };

        let mut cache = self.config_cache.lock().unwrap();
        *cache = Some(config.clone());

        Ok(config)
    }

    pub async fn save_config(&self, config: &HashMap<String, SkillConfig>) -> anyhow::Result<()> {
        if let Some(parent) = self.config_path.parent() {
            fs::create_dir_all(parent)?;
        }

        let content = serde_json::to_string_pretty(config)?;
        fs::write(&self.config_path, content)?;

        let mut cache = self.config_cache.lock().unwrap();
        *cache = Some(config.clone());

        Ok(())
    }

    pub async fn set_enabled(&self, skill_id: &str, enabled: bool) -> anyhow::Result<()> {
        let mut config = self.load_config().await?;

        let entry = config.entry(skill_id.to_string()).or_insert(SkillConfig {
            enabled: false,
            settings: None,
        });
        entry.enabled = enabled;

        self.save_config(&config).await?;

        let mut cache = self.skills_cache.lock().unwrap();
        if let Some(skills) = cache.as_mut() {
            for skill in skills {
                if skill.id == skill_id {
                    skill.enabled = enabled;
                    break;
                }
            }
        }

        Ok(())
    }

    pub async fn delete_skill(&self, skill_id: &str) -> anyhow::Result<()> {
        // 检查是否为内置技能
        let skill_path = self.skills_dir.join(skill_id);

        // 读取技能信息检查是否为内置技能
        let skill_md_path = skill_path.join(SKILL_FILE_NAME);
        if skill_md_path.exists() {
            if let Ok(content) = fs::read_to_string(&skill_md_path) {
                let frontmatter = self.parse_frontmatter(&content);
                if frontmatter.official.unwrap_or(false) {
                    return Err(anyhow::anyhow!("Built-in skills cannot be deleted"));
                }
            }
        }

        if skill_path.exists() {
            fs::remove_dir_all(&skill_path)?;
        }

        let mut config = self.load_config().await?;
        config.remove(skill_id);
        self.save_config(&config).await?;

        let mut cache = self.skills_cache.lock().unwrap();
        *cache = None;

        Ok(())
    }

    pub async fn get_skill_config(
        &self,
        skill_id: &str,
    ) -> anyhow::Result<Option<serde_json::Value>> {
        let config = self.load_config().await?;
        Ok(config.get(skill_id).and_then(|c| c.settings.clone()))
    }

    pub async fn set_skill_config(
        &self,
        skill_id: &str,
        settings: serde_json::Value,
    ) -> anyhow::Result<()> {
        let mut config = self.load_config().await?;

        let entry = config.entry(skill_id.to_string()).or_insert(SkillConfig {
            enabled: false,
            settings: None,
        });
        entry.settings = Some(settings);

        self.save_config(&config).await?;
        Ok(())
    }

    pub async fn build_auto_routing_prompt(&self) -> anyhow::Result<String> {
        let skills = self.load_skills().await?;
        let enabled_skills: Vec<_> = skills.into_iter().filter(|s| s.enabled).collect();

        if enabled_skills.is_empty() {
            return Ok(String::new());
        }

        let skill_entries: Vec<String> = enabled_skills
            .iter()
            .map(|s| format!(
                "  <skill><id>{}</id><name>{}</name><description>{}</description><location>{}</location></skill>",
                s.id,
                s.name,
                s.description,
                s.skill_path
            ))
            .collect();

        let prompt = vec![
            "## Skills (mandatory)".to_string(),
            "Before replying: scan <available_skills> <description> entries.".to_string(),
            "- If exactly one skill clearly applies: read its SKILL.md at <location> with the Read tool, then follow it.".to_string(),
            "- If multiple could apply: choose the most specific one, then read/follow it.".to_string(),
            "- If none clearly apply: do not read any SKILL.md.".to_string(),
            "- For the selected skill, treat <location> as the canonical SKILL.md path.".to_string(),
            "- Resolve relative paths mentioned by that SKILL.md against its directory (dirname(<location>)), not the workspace root.".to_string(),
            "Constraints: never read more than one skill up front; only read additional skills if the first one explicitly references them.".to_string(),
            "".to_string(),
            "<available_skills>".to_string(),
            skill_entries.join("\n"),
            "</available_skills>".to_string(),
        ].join("\n");

        Ok(prompt)
    }

    pub fn handle_working_directory_change(&self) {
        let mut cache = self.skills_cache.lock().unwrap();
        *cache = None;
    }

    /// 获取内置技能目录
    pub fn get_bundled_skills_dir(&self) -> Option<&Path> {
        self.bundled_skills_dir.as_deref()
    }

    /// 设置内置技能目录
    pub fn set_bundled_skills_dir(&mut self, dir: PathBuf) {
        self.bundled_skills_dir = Some(dir);
    }
}
