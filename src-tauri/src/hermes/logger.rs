
//
// Internal logger. The TypeScript module used `winston`; the Rust port
// wraps the `tracing` crate so it integrates with the rest of the
// hermes code.

use tracing::{info, warn, error, debug};

pub fn info_msg(msg: &str) { info!("{}", msg); }
pub fn warn_msg(msg: &str) { warn!("{}", msg); }
pub fn error_msg(msg: &str) { error!("{}", msg); }
pub fn debug_msg(msg: &str) { debug!("{}", msg); }

pub struct HermesLogger;

impl HermesLogger {
    pub fn init() {
        let _ = tracing_subscriber::fmt()
            .with_env_filter(tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info,hermes=debug")))
            .try_init();
    }
}
