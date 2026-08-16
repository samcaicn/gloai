
//
// Translation table. The TypeScript module bundled a small key/value
// map used by the hermes settings page (English + Simplified
// Chinese). The Rust port is intentionally tiny — the front-end is
// expected to use the larger `src/locales/*.json` files. This file
// only covers the strings exposed via the `hermes_i18n_*` Tauri
// commands.

use std::collections::HashMap;
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct I18nBundle {
    pub locale: String,
    pub strings: HashMap<String, String>,
}

pub fn bundle_en() -> I18nBundle {
    let mut s = HashMap::new();
    s.insert("agent.idle".into(), "Idle".into());
    s.insert("agent.thinking".into(), "Thinking…".into());
    s.insert("agent.error".into(), "Error".into());
    s.insert("cron.fired".into(), "Cron task fired".into());
    s.insert("memory.saved".into(), "Memory saved".into());
    I18nBundle { locale: "en".into(), strings: s }
}

pub fn bundle_zh() -> I18nBundle {
    let mut s = HashMap::new();
    s.insert("agent.idle".into(), "空闲".into());
    s.insert("agent.thinking".into(), "思考中…".into());
    s.insert("agent.error".into(), "错误".into());
    s.insert("cron.fired".into(), "定时任务已触发".into());
    s.insert("memory.saved".into(), "记忆已保存".into());
    I18nBundle { locale: "zh-CN".into(), strings: s }
}
