
//
// Per-platform markdown renderers. The TypeScript port re-exported
// each platform's `render*` helper; the Rust port does the same so
// downstream callers can `use markdown::platforms::discord::render`.

pub mod discord;
pub mod feishu;
pub mod qq;
pub mod telegram;
pub mod wecom;
pub mod weixin;
