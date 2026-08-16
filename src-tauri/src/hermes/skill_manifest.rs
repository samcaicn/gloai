
//
// `SkillManifest` data type. The TypeScript module defined the YAML
// schema for a `SKILL.md` front matter. The Rust port uses
// `serde_yaml` to round-trip the same shape.

use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct SkillManifest {
    pub name: String,
    #[serde(default)]
    pub description: Option<String>,
    #[serde(default)]
    pub version: Option<String>,
    #[serde(default)]
    pub author: Option<String>,
    #[serde(default)]
    pub tags: Vec<String>,
    #[serde(default)]
    pub entrypoints: Vec<String>,
    #[serde(default)]
    pub inputs: serde_json::Value,
    #[serde(default)]
    pub outputs: serde_json::Value,
    #[serde(default)]
    pub dependencies: Vec<String>,
}

pub fn parse(text: &str) -> Result<SkillManifest, String> {
    serde_yaml::from_str::<SkillManifest>(text).map_err(|e| e.to_string())
}
