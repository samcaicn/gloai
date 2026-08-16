// Copyright (c) 2026 AIMarketing
//
// Module-scoped logger for pc_automation. Thin facade over the
// `log` crate so callers do not have to spell out the target /
// category on every call site. Keeping it isolated makes it easy to
// later redirect to `tracing` or to a dedicated sink without touching
// the call sites.

pub fn info(msg: &str) {
    log::info!(target: "pc_automation", "{}", msg);
}

pub fn warn(msg: &str) {
    log::warn!(target: "pc_automation", "{}", msg);
}

pub fn error(msg: &str) {
    log::error!(target: "pc_automation", "{}", msg);
}

pub fn debug(msg: &str) {
    log::debug!(target: "pc_automation", "{}", msg);
}

pub fn trace(msg: &str) {
    log::trace!(target: "pc_automation", "{}", msg);
}
