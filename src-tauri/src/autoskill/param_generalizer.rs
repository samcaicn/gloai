// Copyright (c) 2026 MeeJoy
//
// 参数泛化：将硬编码值替换为参数化占位符。
//
// 检测规则（regex）：
//   - 文件路径（C:\ / D:\ / /开头）→ file_path
//   - 日期（YYYY-MM-DD）→ date
//   - 纯数字 → number
//   - URL（http:// / https://）→ url
//
// 泛化后的步骤 value 替换为 {{param_name}} 占位符，
// 同时产出 ParamDef 列表供 SKILL.md 参数章节使用。

use regex::Regex;
use serde::{Deserialize, Serialize};

pub struct ParamGeneralizer;

impl ParamGeneralizer {
    pub fn new() -> Self {
        Self
    }

    /// 将硬编码值替换为参数化占位符。
    ///
    /// 返回 (泛化后的步骤列表, 参数定义列表)。
    pub fn generalize(
        &self,
        steps: &[super::pattern_miner::TraceStep],
    ) -> (Vec<GeneralizedStep>, Vec<ParamDef>) {
        let mut generalized = Vec::new();
        let mut params = Vec::new();

        for step in steps.iter() {
            let mut g_step = GeneralizedStep {
                action: step.action.clone(),
                target: step.target.clone(),
                value: step.value.clone(),
                param_refs: Vec::new(),
            };

            // 检测 value 中的硬编码
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

    /// 检测硬编码值：路径、日期、数字、URL。
    ///
    /// 命中则返回参数名，未命中返回 None。
    fn detect_hardcoded(&self, value: &str) -> Option<String> {
        // 文件路径
        if value.starts_with("C:\\") || value.starts_with("D:\\") || value.starts_with('/') {
            return Some("file_path".into());
        }
        // 日期
        if let Ok(re) = Regex::new(r"^\d{4}-\d{2}-\d{2}$") {
            if re.is_match(value) {
                return Some("date".into());
            }
        }
        // 纯数字
        if value.parse::<f64>().is_ok() {
            return Some("number".into());
        }
        // URL
        if value.starts_with("http://") || value.starts_with("https://") {
            return Some("url".into());
        }
        None
    }
}

/// 泛化后的执行步骤。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GeneralizedStep {
    pub action: String,
    pub target: String,
    pub value: Option<String>,
    pub param_refs: Vec<String>,
}

/// 参数定义。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ParamDef {
    pub name: String,
    pub description: String,
    pub required: bool,
    pub default_value: Option<String>,
}
