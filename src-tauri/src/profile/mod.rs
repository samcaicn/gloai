// Copyright (c) 2026 tupAI
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
    /// DSH upstream runtimes managed in the Settings UI (profile-scoped).
    /// Single source of truth = this profile; the runtime-registry is just a
    /// runtime view seeded from here (see `runtime_registry::sync_dsh_upstreams`).
    #[serde(default)]
    pub dsh: DshConfig,
}

/// A single DSH upstream runtime. DSH is an external runtime wired into the
/// runtime-registry via the `Upstream` seam (`adapters/upstream.rs`). Each
/// entry is profile-scoped, so switching profiles swaps the DSH config.
#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct DshUpstreamConfig {
    /// Stable id; also used as the sub-agent prefix (`dsh<id>`).
    pub id: String,
    pub display_name: String,
    /// http(s) URL (OpenAI-compatible /chat/completions) OR an existing binary path.
    pub endpoint: String,
    /// When `endpoint` is a binary, the subprocess argv template ({prompt}/{cwd}).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cli_args_template: Option<Vec<String>>,
    /// Optional model id; falls back to the instance default / "default".
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub model: Option<String>,
    /// Optional API key. Stored only in the local profile.json (never logged
    /// or re-serialized by the registry); injected at runtime via an env var.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub api_key: Option<String>,
    #[serde(default = "default_true")]
    pub enabled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct DshConfig {
    #[serde(default)]
    pub upstreams: Vec<DshUpstreamConfig>,
}

fn default_true() -> bool {
    true
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
                dsh: DshConfig::default(),
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
                dsh: DshConfig::default(),
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

    /// Mutable variant of `active_profile` (panics if the active or built-in
    /// `tupai` profile is missing, which should never happen).
    pub fn active_profile_mut(&mut self) -> &mut Profile {
        let active = self.active.clone();
        if self.profiles.contains_key(&active) {
            self.profiles.get_mut(&active).unwrap()
        } else {
            self.profiles
                .get_mut("tupai")
                .expect("builtin tupai profile must always exist")
        }
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

    /// DSH upstreams of the active profile (cloned for cheap sharing).
    pub fn dsh_upstreams(&self) -> Vec<DshUpstreamConfig> {
        self.active_profile().dsh.upstreams.clone()
    }

    /// Replace the active profile's DSH upstream list.
    pub fn set_dsh_upstreams(&mut self, upstreams: Vec<DshUpstreamConfig>) {
        self.active_profile_mut().dsh.upstreams = upstreams;
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
        let p = Profile {
            id: "custom".into(),
            display_brand: "custom".into(),
            enabled_skills: Some(vec!["a".into(), "b".into()]),
            disabled_skills: vec!["b".into()],
            config_overrides: HashMap::new(),
            dsh: DshConfig::default(),
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
            dsh: DshConfig::default(),
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
