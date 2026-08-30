// Parameter generalization — adapted from safeopcapp.
//
// Replace hardcoded values with parameterized placeholders.
// Detection rules (regex):
//   - File paths (C:\, D:\, /) → {{file_path}}
//   - Dates (YYYY-MM-DD) → {{date}}
//   - Pure numbers → {{number}}
//   - URLs (http://, https://) → {{url}}

use regex::Regex;
use serde::{Deserialize, Serialize};

use super::pattern_miner::TraceStep;

pub struct ParamGeneralizer;

impl ParamGeneralizer {
    pub fn new() -> Self {
        Self
    }

    /// Generalize hardcoded values into parameterized placeholders.
    /// Returns (generalized steps, parameter definitions).
    pub fn generalize(&self, steps: &[TraceStep]) -> (Vec<GeneralizedStep>, Vec<ParamDef>) {
        let mut generalized = Vec::new();
        let mut params = Vec::new();

        for step in steps.iter() {
            let mut g_step = GeneralizedStep {
                action: step.action.clone(),
                target: step.target.clone(),
                value: step.value.clone(),
                param_refs: Vec::new(),
            };

            if let Some(val) = &step.value {
                if let Some(param_name) = self.detect_hardcoded(val) {
                    g_step.value = Some(format!("{{{{{}}}}}", param_name));
                    g_step.param_refs.push(param_name.clone());
                    params.push(ParamDef {
                        name: param_name,
                        description: String::new(),
                        required: true,
                        default_value: Some(val.clone()),
                    });
                }
            }

            generalized.push(g_step);
        }

        (generalized, params)
    }

    fn detect_hardcoded(&self, value: &str) -> Option<String> {
        if value.starts_with("C:\\") || value.starts_with("D:\\") || value.starts_with('/') {
            return Some("file_path".into());
        }
        if let Ok(re) = Regex::new(r"^\d{4}-\d{2}-\d{2}$") {
            if re.is_match(value) {
                return Some("date".into());
            }
        }
        if value.parse::<f64>().is_ok() {
            return Some("number".into());
        }
        if value.starts_with("http://") || value.starts_with("https://") {
            return Some("url".into());
        }
        None
    }
}

/// A generalized execution step.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GeneralizedStep {
    pub action: String,
    pub target: String,
    pub value: Option<String>,
    pub param_refs: Vec<String>,
}

/// A parameter definition.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ParamDef {
    pub name: String,
    pub description: String,
    pub required: bool,
    pub default_value: Option<String>,
}
