//
// Model catalog. The TypeScript module bundled a list of well-known
// models (OpenAI, Anthropic, llama.cpp variants) and a `find()`
// helper. The Rust port keeps the same data shape. The data is held
// in a `Lazy<Vec<…>>` rather than a `const &[…]` because the model
// entries own `String` fields and string allocation is not a const
// operation.
//
// v5.5 — `models_by_provider` flattens the catalog into a
// `{ provider_id, label, models: [id, id, ...] }` shape so the
// embedded server's `/api/model/options` can ship the full list of
// well-known models per provider back to the front-end. The
// front-end then renders the dropdown straight from that list
// instead of asking the user to type a model ID by hand.

use once_cell::sync::Lazy;
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct ModelEntry {
    pub id: String,
    pub provider: String,
    pub name: String,
    pub context_window: u32,
    pub supports_vision: bool,
    pub supports_tools: bool,
    pub default: bool,
}

pub static MODELS: Lazy<Vec<ModelEntry>> = Lazy::new(|| {
    vec![
        // OpenAI
        ModelEntry { id: "gpt-4o".to_string(), provider: "openai".to_string(), name: "GPT-4o".to_string(), context_window: 128_000, supports_vision: true, supports_tools: true, default: false },
        ModelEntry { id: "gpt-4o-mini".to_string(), provider: "openai".to_string(), name: "GPT-4o mini".to_string(), context_window: 128_000, supports_vision: true, supports_tools: true, default: true },
        ModelEntry { id: "gpt-4.1".to_string(), provider: "openai".to_string(), name: "GPT-4.1".to_string(), context_window: 1_000_000, supports_vision: true, supports_tools: true, default: false },
        ModelEntry { id: "gpt-4.1-mini".to_string(), provider: "openai".to_string(), name: "GPT-4.1 mini".to_string(), context_window: 1_000_000, supports_vision: true, supports_tools: true, default: false },
        ModelEntry { id: "o3-mini".to_string(), provider: "openai".to_string(), name: "o3-mini".to_string(), context_window: 200_000, supports_vision: false, supports_tools: true, default: false },
        ModelEntry { id: "o4-mini".to_string(), provider: "openai".to_string(), name: "o4-mini".to_string(), context_window: 200_000, supports_vision: true, supports_tools: true, default: false },
        // Anthropic
        ModelEntry { id: "claude-3-5-sonnet".to_string(), provider: "anthropic".to_string(), name: "Claude 3.5 Sonnet".to_string(), context_window: 200_000, supports_vision: true, supports_tools: true, default: false },
        ModelEntry { id: "claude-3-5-haiku".to_string(), provider: "anthropic".to_string(), name: "Claude 3.5 Haiku".to_string(), context_window: 200_000, supports_vision: true, supports_tools: true, default: false },
        ModelEntry { id: "claude-3-haiku".to_string(), provider: "anthropic".to_string(), name: "Claude 3 Haiku".to_string(), context_window: 200_000, supports_vision: true, supports_tools: true, default: false },
        ModelEntry { id: "claude-sonnet-4-20250514".to_string(), provider: "anthropic".to_string(), name: "Claude Sonnet 4".to_string(), context_window: 200_000, supports_vision: true, supports_tools: true, default: false },
        // DeepSeek
        ModelEntry { id: "deepseek-chat".to_string(), provider: "deepseek".to_string(), name: "DeepSeek Chat".to_string(), context_window: 64_000, supports_vision: false, supports_tools: true, default: true },
        ModelEntry { id: "deepseek-reasoner".to_string(), provider: "deepseek".to_string(), name: "DeepSeek Reasoner".to_string(), context_window: 64_000, supports_vision: false, supports_tools: true, default: false },
        // DashScope (Qwen)
        ModelEntry { id: "qwen-plus".to_string(), provider: "dashscope".to_string(), name: "Qwen Plus".to_string(), context_window: 131_072, supports_vision: false, supports_tools: true, default: false },
        ModelEntry { id: "qwen-max".to_string(), provider: "dashscope".to_string(), name: "Qwen Max".to_string(), context_window: 32_768, supports_vision: false, supports_tools: true, default: false },
        ModelEntry { id: "qwen-turbo".to_string(), provider: "dashscope".to_string(), name: "Qwen Turbo".to_string(), context_window: 1_000_000, supports_vision: false, supports_tools: true, default: true },
        ModelEntry { id: "qwen2.5-72b-instruct".to_string(), provider: "dashscope".to_string(), name: "Qwen 2.5 72B Instruct".to_string(), context_window: 131_072, supports_vision: false, supports_tools: true, default: false },
        // Google (Gemini)
        ModelEntry { id: "gemini-2.5-pro".to_string(), provider: "google".to_string(), name: "Gemini 2.5 Pro".to_string(), context_window: 1_000_000, supports_vision: true, supports_tools: true, default: false },
        ModelEntry { id: "gemini-2.5-flash".to_string(), provider: "google".to_string(), name: "Gemini 2.5 Flash".to_string(), context_window: 1_000_000, supports_vision: true, supports_tools: true, default: true },
        ModelEntry { id: "gemini-2.0-flash".to_string(), provider: "google".to_string(), name: "Gemini 2.0 Flash".to_string(), context_window: 1_000_000, supports_vision: true, supports_tools: true, default: false },
        // GLM / Z.AI
        ModelEntry { id: "glm-4.6".to_string(), provider: "glm".to_string(), name: "GLM-4.6".to_string(), context_window: 200_000, supports_vision: false, supports_tools: true, default: true },
        ModelEntry { id: "glm-4.5".to_string(), provider: "glm".to_string(), name: "GLM-4.5".to_string(), context_window: 128_000, supports_vision: false, supports_tools: true, default: false },
        ModelEntry { id: "glm-4-flash".to_string(), provider: "glm".to_string(), name: "GLM-4 Flash".to_string(), context_window: 128_000, supports_vision: false, supports_tools: true, default: false },
        // Moonshot (Kimi)
        ModelEntry { id: "moonshot-v1-128k".to_string(), provider: "kimi".to_string(), name: "Moonshot v1 128K".to_string(), context_window: 128_000, supports_vision: false, supports_tools: true, default: true },
        ModelEntry { id: "moonshot-v1-32k".to_string(), provider: "kimi".to_string(), name: "Moonshot v1 32K".to_string(), context_window: 32_000, supports_vision: false, supports_tools: true, default: false },
        ModelEntry { id: "moonshot-v1-8k".to_string(), provider: "kimi".to_string(), name: "Moonshot v1 8K".to_string(), context_window: 8_000, supports_vision: false, supports_tools: true, default: false },
        // MiniMax
        ModelEntry { id: "MiniMax-Text-01".to_string(), provider: "MiniMax".to_string(), name: "MiniMax-Text-01".to_string(), context_window: 1_000_000, supports_vision: false, supports_tools: true, default: false },
        ModelEntry { id: "MiniMax-M2.7".to_string(), provider: "MiniMax".to_string(), name: "MiniMax-M2.7".to_string(), context_window: 200_000, supports_vision: false, supports_tools: true, default: true },
        // xAI
        ModelEntry { id: "grok-3".to_string(), provider: "xai".to_string(), name: "Grok 3".to_string(), context_window: 131_072, supports_vision: true, supports_tools: true, default: false },
        ModelEntry { id: "grok-3-mini".to_string(), provider: "xai".to_string(), name: "Grok 3 mini".to_string(), context_window: 131_072, supports_vision: false, supports_tools: true, default: true },
        // Xiaomi MiMo
        ModelEntry { id: "mimo-v2-flash".to_string(), provider: "xiaomi".to_string(), name: "MiMo v2 Flash".to_string(), context_window: 128_000, supports_vision: false, supports_tools: true, default: true },
        // StepFun
        ModelEntry { id: "step-1v-8k".to_string(), provider: "stepfun".to_string(), name: "Step-1V 8K".to_string(), context_window: 8_000, supports_vision: true, supports_tools: false, default: true },
        // Local / llama.cpp / vLLM (Ollama tags are user-defined, but
        // we surface a couple of well-known ones so the dropdown
        // isn't completely empty for first-time local users).
        ModelEntry { id: "llama-3.1-8b".to_string(), provider: "llamacpp".to_string(), name: "Llama 3.1 8B (local)".to_string(), context_window: 8_192, supports_vision: false, supports_tools: false, default: false },
        ModelEntry { id: "qwen2.5-vl-7b".to_string(), provider: "vllm".to_string(), name: "Qwen 2.5 VL 7B (local)".to_string(), context_window: 32_768, supports_vision: true, supports_tools: false, default: false },
    ]
});

/// Provider labels surfaced to the front-end. Kept in sync with
/// `src/components/model-config-utils.js` `PROVIDER_DEFINITIONS`. We
/// duplicate the provider IDs here so the embedded server can build
/// the dropdown without a second round-trip to the dashboard.
pub static PROVIDER_LABELS: Lazy<Vec<(&'static str, &'static str)>> = Lazy::new(|| {
    vec![
        ("nous", "Nous Portal"),
        ("openai", "OpenAI"),
        ("anthropic", "Anthropic"),
        ("dashscope", "DashScope (Qwen)"),
        ("hermes-qwen", "DashScope (Qwen)"),
        ("deepseek", "DeepSeek"),
        ("google", "Gemini"),
        ("gemini", "Gemini"),
        ("glm", "GLM / Z.AI"),
        ("zai", "GLM / Z.AI"),
        ("z-ai", "GLM / Z.AI"),
        ("hf", "Hugging Face"),
        ("kimi", "Kimi / Moonshot"),
        ("MiniMax-cn", "MiniMax (China)"),
        ("MiniMax", "MiniMax"),
        ("openrouter", "OpenRouter"),
        ("xiaomi", "Xiaomi MiMo"),
        ("xai", "xAI"),
        ("stepfun", "StepFun"),
        ("llamacpp", "llama.cpp (local)"),
        ("vllm", "vLLM (local)"),
    ]
});

pub fn find(id: &str) -> Option<&'static ModelEntry> { MODELS.iter().find(|m| m.id == id) }
pub fn default() -> &'static ModelEntry { MODELS.iter().find(|m| m.default).unwrap_or(&MODELS[0]) }

fn provider_label(provider: &str) -> String {
    PROVIDER_LABELS
        .iter()
        .find(|(id, _)| *id == provider)
        .map(|(_, label)| (*label).to_string())
        .unwrap_or_else(|| {
            // Unknown provider: fall back to the raw ID so the
            // dropdown still has something readable. We allocate a
            // fresh `String` here instead of `Box::leak` so the call
            // is leak-free.
            provider.to_string()
        })
}

/// Flatten the catalog into a list of `{ provider, label, model_ids }`.
/// Used by `embedded_server::list_models` /
/// `get_model_options_legacy` to ship a "full" model list back to
/// the front-end without forcing a per-provider HTTP probe.
pub fn models_by_provider() -> Vec<(&'static str, String, Vec<&'static str>)> {
    use std::collections::BTreeMap;
    let mut grouped: BTreeMap<&'static str, Vec<&'static str>> = BTreeMap::new();

    for entry in MODELS.iter() {
        let provider = entry.provider.as_str();
        // The IDs below are borrowed directly from the static
        // `MODELS` vec, so they're already `&'static str`.
        grouped
            .entry(provider)
            .or_default()
            .push(entry.id.as_str());
    }

    grouped
        .into_iter()
        .map(|(provider, model_ids)| (provider, provider_label(provider), model_ids))
        .collect()
}

/// Convenience: returns true if a model id appears in the catalog
/// for any provider.
pub fn is_known_model(id: &str) -> bool {
    MODELS.iter().any(|m| m.id == id)
}
