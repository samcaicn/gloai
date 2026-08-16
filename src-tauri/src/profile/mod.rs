// Copyright (c) 2026 AIMarketing
//
// Profile patch layer (deepseek-harness style "bundle + user patch").
//
// A Profile bundles three runtime-switchable axes:
//   * `display_brand`   — UI label (does NOT change the build-time binary
//                         brand or the upgrade endpoint; those stay derived
//                         from tauri.conf's productName).
//   * `enabled_skills`  — allow-list of skill ids; None = "all built-in".
//   * `disabled_skills` — deny-list; wins over `enabled_skills`.
//   * `config_overrides`— arbitrary config key/value overrides applied on
//                         top of built-in defaults.
//
// Profiles compose three layers: built-in default < profile bundle <
// user patch. The active profile is persisted to
// `<app_data_dir>/profile.json` and can be switched at runtime without
// recompiling the binary.

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::Path;

#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq)]
pub struct Profile {
    pub id: String,
    #[serde(default)]
    pub display_brand: String,
    /// If set, only these skill ids are enabled (allow-list).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub enabled_skills: Option<Vec<String>>,
    /// Skill ids explicitly disabled (deny-list); wins over `enabled_skills`.
    #[serde(default)]
    pub disabled_skills: Vec<String>,
    /// Config overrides applied on top of built-in defaults.
    #[serde(default)]
    pub config_overrides: HashMap<String, serde_json::Value>,
}

#[derive(Debug, Serialize, Deserialize, Default)]
pub struct ProfileStore {
    #[serde(default)]
    pub active: String,
    #[serde(default)]
    pub profiles: HashMap<String, Profile>,
}

impl ProfileStore {
    /// Two built-in bundles. The `active` profile defaults to `tupai`.
    pub fn builtin_default() -> Self {
        let mut profiles: HashMap<String, Profile> = HashMap::new();

        profiles.insert(
            "tupai".to_string(),
            Profile {
                id: "tupai".to_string(),
                display_brand: "tupai".to_string(),
                enabled_skills: None,
                disabled_skills: vec![],
                config_overrides: HashMap::new(),
            },
        );

        let mut safeopc_overrides = HashMap::new();
        safeopc_overrides.insert("oem".to_string(), serde_json::json!(true));
        profiles.insert(
            "safeopc".to_string(),
            Profile {
                id: "safeopc".to_string(),
                display_brand: "safeopc".to_string(),
                enabled_skills: None,
                disabled_skills: vec![],
                config_overrides: safeopc_overrides,
            },
        );

        ProfileStore {
            active: "tupai".to_string(),
            profiles,
        }
    }

    pub fn load(dir: &Path) -> Result<Self, String> {
        let path = dir.join("profile.json");
        if !path.exists() {
            return Err("profile.json not found".into());
        }
        let s = std::fs::read_to_string(&path).map_err(|e| e.to_string())?;
        serde_json::from_str(&s).map_err(|e| e.to_string())
    }

    pub fn save(&self, dir: &Path) -> Result<(), String> {
        std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
        let s = serde_json::to_string_pretty(self).map_err(|e| e.to_string())?;
        std::fs::write(dir.join("profile.json"), s).map_err(|e| e.to_string())
    }

    pub fn active_profile(&self) -> &Profile {
        self.profiles
            .get(&self.active)
            .or_else(|| self.profiles.get("tupai"))
            .expect("builtin tupai profile must always exist")
    }

    /// Resolve whether a skill is enabled under the active profile.
    /// `builtin_enabled` is the skill's default-enabled flag (used when the
    /// profile does not pin an allow-list).
    pub fn is_skill_enabled(&self, skill_id: &str, builtin_enabled: bool) -> bool {
        let p = self.active_profile();
        if p.disabled_skills.iter().any(|s| s == skill_id) {
            return false;
        }
        match &p.enabled_skills {
            Some(list) => list.iter().any(|s| s == skill_id),
            None => builtin_enabled,
        }
    }

    /// Resolve a config key, returning the profile override if present,
    /// otherwise the built-in default.
    pub fn resolve_config(&self, key: &str, builtin: serde_json::Value) -> serde_json::Value {
        self.active_profile()
            .config_overrides
            .get(key)
            .cloned()
            .unwrap_or(builtin)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builtin_has_two_profiles() {
        let s = ProfileStore::builtin_default();
        assert_eq!(s.active, "tupai");
        assert!(s.profiles.contains_key("tupai"));
        assert!(s.profiles.contains_key("safeopc"));
    }

    #[test]
    fn disabled_wins_over_allow_list() {
        let mut s = ProfileStore::builtin_default();
        let mut p = Profile {
            id: "custom".into(),
            display_brand: "custom".into(),
            enabled_skills: Some(vec!["a".into(), "b".into()]),
            disabled_skills: vec!["b".into()],
            config_overrides: HashMap::new(),
        };
        s.profiles.insert("custom".into(), p);
        s.active = "custom".into();
        assert!(s.is_skill_enabled("a", true));
        assert!(!s.is_skill_enabled("b", true)); // disabled wins
        assert!(!s.is_skill_enabled("c", true)); // not in allow-list
        assert!(s.is_skill_enabled("c", false)); // builtin default off, still off
    }

    #[test]
    fn none_allow_list_falls_back_to_builtin() {
        let s = ProfileStore::builtin_default(); // tupai: enabled_skills = None
        assert!(s.is_skill_enabled("anything", true));
        assert!(!s.is_skill_enabled("anything", false));
    }

    #[test]
    fn config_override_wins() {
        let mut s = ProfileStore::builtin_default();
        let mut p = Profile {
            id: "custom".into(),
            display_brand: "custom".into(),
            enabled_skills: None,
            disabled_skills: vec![],
            config_overrides: HashMap::new(),
        };
        p.config_overrides
            .insert("max_tokens".into(), serde_json::json!(4096));
        s.profiles.insert("custom".into(), p);
        s.active = "custom".into();
        assert_eq!(
            s.resolve_config("max_tokens", serde_json::json!(2048)),
            serde_json::json!(4096)
        );
        assert_eq!(
            s.resolve_config("other", serde_json::json!(1)),
            serde_json::json!(1)
        );
    }

    #[test]
    fn save_load_roundtrip() {
        let dir = std::env::temp_dir().join(format!(
            "tupai_profile_test_{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let mut s = ProfileStore::builtin_default();
        s.active = "safeopc".into();
        s.save(&dir).unwrap();
        let loaded = ProfileStore::load(&dir).unwrap();
        assert_eq!(loaded.active, "safeopc");
        assert!(loaded.profiles.contains_key("safeopc"));
        let _ = std::fs::remove_dir_all(&dir);
    }
}
