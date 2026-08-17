//! Persisted desktop settings (non-secret). API keys prefer the OS keyring.

use std::fs;
use std::path::PathBuf;

use serde::{Deserialize, Serialize};

use crate::launch::{KEYRING_SERVICE, KEYRING_USER};

const SETTINGS_DIR: &str = "deepseek-harness-gui";
const SETTINGS_FILE: &str = "settings.json";

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct RecentWorkspace {
    pub path: String,
    pub name: String,
    pub opened_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct AppSettings {
    #[serde(default)]
    pub harness_command: String,
    #[serde(default)]
    pub harness_args: Vec<String>,
    #[serde(default = "default_theme")]
    pub theme: String,
    #[serde(default = "default_locale")]
    pub locale: String,
    #[serde(default)]
    pub close_to_tray: bool,
    #[serde(default)]
    pub recent_workspaces: Vec<RecentWorkspace>,
    /// Used only when the OS keyring is unavailable.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub api_key_fallback: Option<String>,
}

fn default_theme() -> String {
    "dark".into()
}

fn default_locale() -> String {
    "zh".into()
}

impl Default for AppSettings {
    fn default() -> Self {
        Self {
            harness_command: String::new(),
            harness_args: Vec::new(),
            theme: default_theme(),
            locale: default_locale(),
            close_to_tray: false,
            recent_workspaces: Vec::new(),
            api_key_fallback: None,
        }
    }
}

pub fn settings_path() -> Result<PathBuf, String> {
    let dir = dirs::config_dir()
        .ok_or_else(|| "无法解析用户配置目录".to_string())?
        .join(SETTINGS_DIR);
    fs::create_dir_all(&dir).map_err(|error| format!("无法创建配置目录: {error}"))?;
    Ok(dir.join(SETTINGS_FILE))
}

pub fn load_settings() -> Result<AppSettings, String> {
    let path = settings_path()?;
    if !path.exists() {
        return Ok(AppSettings::default());
    }
    let raw = fs::read_to_string(&path).map_err(|error| format!("读取设置失败: {error}"))?;
    serde_json::from_str(&raw).map_err(|error| format!("设置文件损坏: {error}"))
}

pub fn save_settings(settings: &AppSettings) -> Result<(), String> {
    let path = settings_path()?;
    let raw = serde_json::to_string_pretty(settings)
        .map_err(|error| format!("序列化设置失败: {error}"))?;
    fs::write(&path, raw).map_err(|error| format!("写入设置失败: {error}"))
}

pub fn get_api_key(settings: &AppSettings) -> Result<Option<String>, String> {
    if let Ok(value) = std::env::var("DEEPSEEK_API_KEY") {
        if !value.is_empty() {
            return Ok(Some(value));
        }
    }
    match keyring::Entry::new(KEYRING_SERVICE, KEYRING_USER) {
        Ok(entry) => match entry.get_password() {
            Ok(value) if !value.is_empty() => Ok(Some(value)),
            Ok(_) => Ok(settings.api_key_fallback.clone().filter(|value| !value.is_empty())),
            Err(keyring::Error::NoEntry) => {
                Ok(settings.api_key_fallback.clone().filter(|value| !value.is_empty()))
            }
            Err(error) => {
                if let Some(fallback) = settings
                    .api_key_fallback
                    .clone()
                    .filter(|value| !value.is_empty())
                {
                    Ok(Some(fallback))
                } else {
                    Err(format!("读取钥匙串失败: {error}"))
                }
            }
        },
        Err(_) => Ok(settings.api_key_fallback.clone().filter(|value| !value.is_empty())),
    }
}

pub fn set_api_key(settings: &mut AppSettings, key: Option<String>) -> Result<bool, String> {
    let uses_keyring = match keyring::Entry::new(KEYRING_SERVICE, KEYRING_USER) {
        Ok(entry) => {
            match &key {
                Some(value) if !value.is_empty() => entry
                    .set_password(value)
                    .map_err(|error| format!("写入钥匙串失败: {error}"))?,
                _ => match entry.delete_credential() {
                    Ok(()) | Err(keyring::Error::NoEntry) => {}
                    Err(error) => return Err(format!("删除钥匙串条目失败: {error}")),
                },
            }
            settings.api_key_fallback = None;
            true
        }
        Err(_) => {
            settings.api_key_fallback = key.filter(|value| !value.is_empty());
            false
        }
    };
    save_settings(settings)?;
    Ok(uses_keyring)
}

pub fn touch_recent_workspace(settings: &mut AppSettings, path: &str) {
    let name = std::path::Path::new(path)
        .file_name()
        .and_then(|value| value.to_str())
        .unwrap_or(path)
        .to_string();
    settings.recent_workspaces.retain(|item| item.path != path);
    settings.recent_workspaces.insert(
        0,
        RecentWorkspace {
            path: path.to_string(),
            name,
            opened_at: now_rfc3339(),
        },
    );
    settings.recent_workspaces.truncate(12);
}

fn now_rfc3339() -> String {
    use std::time::{SystemTime, UNIX_EPOCH};
    let secs = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .unwrap_or(0);
    format!("{secs}")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn recent_workspace_moves_to_front_and_caps() {
        let mut settings = AppSettings::default();
        for index in 0..15 {
            touch_recent_workspace(&mut settings, &format!("/tmp/ws-{index}"));
        }
        assert_eq!(settings.recent_workspaces.len(), 12);
        assert_eq!(settings.recent_workspaces[0].path, "/tmp/ws-14");
        touch_recent_workspace(&mut settings, "/tmp/ws-10");
        assert_eq!(settings.recent_workspaces[0].path, "/tmp/ws-10");
        assert_eq!(
            settings
                .recent_workspaces
                .iter()
                .filter(|item| item.path == "/tmp/ws-10")
                .count(),
            1
        );
    }
}
