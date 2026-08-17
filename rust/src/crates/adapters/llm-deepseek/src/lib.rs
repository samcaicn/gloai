//! DeepSeek chat-completions adapter: serialize, SSE, translate, HTTP stream.

pub mod serialize;
pub mod sse;
pub mod translate;
pub mod types;

mod adapter;

pub use adapter::{
    http_error_code, DeepSeekAdapter, DeepSeekAdapterOptions, DeepSeekCatalogModel,
    DeepSeekConnectionOptions, DEFAULT_CONTEXT_WINDOW, DEFAULT_MAX_TOKENS,
    DEFAULT_STREAM_IDLE_TIMEOUT_MS,
};
pub use serialize::{serialize_request, RequestDefaults};
pub use sse::{parse_sse, DONE};
pub use translate::{map_finish_reason, map_usage, translate};
