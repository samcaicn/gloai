use directories_next::ProjectDirs;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AppConfig {
    pub theme: Option<String>,
    pub language: Option<String>,
    pub api_configs: HashMap<String, serde_json::Value>,
}

impl Default for AppConfig {
    fn default() -> Self {
        Self {
            theme: Some("system".to_string()),
            language: Some("zh".to_string()),
            api_configs: HashMap::new(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TuptupConfig {
    pub api_key: Option<String>,
    pub api_secret: Option<String>,
    pub user_id: Option<String>,
}

impl Default for TuptupConfig {
    fn default() -> Self {
        Self {
            api_key: None,
            api_secret: None,
            user_id: None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct KvStoreData {
    data: HashMap<String, String>,
}

impl Default for KvStoreData {
    fn default() -> Self {
        KvStoreData {
            data: HashMap::new(),
        }
    }
}

#[derive(Clone)]
pub struct KvStore {
    path: PathBuf,
    data: Arc<Mutex<KvStoreData>>,
}

impl KvStore {
    pub fn new(path: PathBuf) -> anyhow::Result<Self> {
        let data = if path.exists() {
            let content = fs::read_to_string(&path)?;
            serde_json::from_str(&content).unwrap_or_else(|_| KvStoreData::default())
        } else {
            KvStoreData::default()
        };

        Ok(KvStore {
            path,
            data: Arc::new(Mutex::new(data)),
        })
    }

    pub fn get(&self, key: &str) -> anyhow::Result<Option<String>> {
        let data = self.data.lock().unwrap();
        Ok(data.data.get(key).cloned())
    }

    pub fn set(&self, key: &str, value: &str) -> anyhow::Result<()> {
        {
            let mut data = self.data.lock().unwrap();
            data.data.insert(key.to_string(), value.to_string());
        }
        self.save()?;
        Ok(())
    }

    pub fn remove(&self, key: &str) -> anyhow::Result<()> {
        {
            let mut data = self.data.lock().unwrap();
            data.data.remove(key);
        }
        self.save()?;
        Ok(())
    }

    fn save(&self) -> anyhow::Result<()> {
        let data = self.data.lock().unwrap();
        let content = serde_json::to_string_pretty(&*data)?;
        fs::write(&self.path, content)?;
        Ok(())
    }
}

pub struct Storage {
    data_dir: PathBuf,
    config_dir: PathBuf,
}

impl Storage {
    pub fn new() -> anyhow::Result<Self> {
        println!("[storage] Initializing Storage...");

        let proj_dirs = ProjectDirs::from("com", "ggai", "app")
            .ok_or_else(|| anyhow::anyhow!("Failed to get project directories"))?;

        let data_dir = proj_dirs.data_dir().to_path_buf();
        let config_dir = proj_dirs.config_dir().to_path_buf();

        println!("[storage] Data dir: {:?}", data_dir);
        println!("[storage] Config dir: {:?}", config_dir);

        fs::create_dir_all(&data_dir)?;
        fs::create_dir_all(&config_dir)?;

        println!("[storage] Storage initialized successfully");

        Ok(Self {
            data_dir,
            config_dir,
        })
    }

    pub fn get_app_config_path(&self) -> PathBuf {
        self.config_dir.join("config.json")
    }

    pub fn get_tuptup_config_path(&self) -> PathBuf {
        self.config_dir.join("tuptup.json")
    }

    pub fn get_skills_dir(&self) -> PathBuf {
        self.data_dir.join("SKILLs")
    }

    pub fn get_skills_config_path(&self) -> PathBuf {
        self.config_dir.join("skills.json")
    }

    pub fn get_kv_store_path(&self) -> PathBuf {
        self.data_dir.join("kv_store.json")
    }

    pub fn get_logs_dir(&self) -> PathBuf {
        self.data_dir.join("logs")
    }

    pub fn load_app_config(&self) -> anyhow::Result<AppConfig> {
        let path = self.get_app_config_path();
        if path.exists() {
            let content = fs::read_to_string(path)?;
            Ok(serde_json::from_str(&content)?)
        } else {
            Ok(AppConfig::default())
        }
    }

    pub fn save_app_config(&self, config: &AppConfig) -> anyhow::Result<()> {
        let path = self.get_app_config_path();
        let content = serde_json::to_string_pretty(config)?;
        fs::write(path, content)?;
        Ok(())
    }

    pub fn load_tuptup_config(&self) -> anyhow::Result<TuptupConfig> {
        let path = self.get_tuptup_config_path();
        if path.exists() {
            let content = fs::read_to_string(path)?;
            Ok(serde_json::from_str(&content)?)
        } else {
            Ok(TuptupConfig::default())
        }
    }

    pub fn save_tuptup_config(&self, config: &TuptupConfig) -> anyhow::Result<()> {
        let path = self.get_tuptup_config_path();
        let content = serde_json::to_string_pretty(config)?;
        fs::write(path, content)?;
        Ok(())
    }
}
