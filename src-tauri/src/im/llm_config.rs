use serde::{Deserialize, Serialize};
use std::sync::Mutex;
use std::collections::HashMap;

// LLM配置
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LLMConfig {
    pub enabled: bool,
    pub model: String,
    pub api_key: String,
    pub base_url: String,
    pub temperature: f32,
    pub max_tokens: u32,
    pub skill_prompts: HashMap<String, String>, // 技能提示
}

impl Default for LLMConfig {
    fn default() -> Self {
        LLMConfig {
            enabled: false,
            model: "gpt-3.5-turbo".to_string(),
            api_key: "".to_string(),
            base_url: "https://api.openai.com/v1".to_string(),
            temperature: 0.7,
            max_tokens: 1024,
            skill_prompts: HashMap::new(),
        }
    }
}

// LLM管理器
pub struct LLMManager {
    config: Mutex<LLMConfig>,
}

impl LLMManager {
    pub fn new(config: LLMConfig) -> Self {
        Self {
            config: Mutex::new(config),
        }
    }
    
    pub fn new_default() -> Self {
        Self::new(LLMConfig::default())
    }
    
    pub fn set_config(&self, config: LLMConfig) {
        *self.config.lock().unwrap() = config;
    }
    
    pub fn get_config(&self) -> LLMConfig {
        self.config.lock().unwrap().clone()
    }
    
    // 添加技能提示
    pub fn add_skill_prompt(&self, skill_name: &str, prompt: &str) {
        let mut config = self.config.lock().unwrap();
        config.skill_prompts.insert(skill_name.to_string(), prompt.to_string());
    }
    
    // 获取技能提示
    pub fn get_skill_prompt(&self, skill_name: &str) -> Option<String> {
        let config = self.config.lock().unwrap();
        config.skill_prompts.get(skill_name).cloned()
    }
    
    // 删除技能提示
    pub fn remove_skill_prompt(&self, skill_name: &str) {
        let mut config = self.config.lock().unwrap();
        config.skill_prompts.remove(skill_name);
    }
    
    // 获取所有技能提示
    pub fn get_all_skill_prompts(&self) -> HashMap<String, String> {
        let config = self.config.lock().unwrap();
        config.skill_prompts.clone()
    }
    
    // 检查LLM配置是否有效
    pub fn is_config_valid(&self) -> bool {
        let config = self.config.lock().unwrap();
        config.enabled && !config.api_key.is_empty() && !config.model.is_empty()
    }
}