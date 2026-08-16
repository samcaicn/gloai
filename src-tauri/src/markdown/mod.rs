
//
// Top-level barrel file for the `markdown-ir` package. The Rust port
// exposes the IR, renderer, escape helpers, and per-platform
// adapters through a single module.

pub mod ir;
pub mod render;
pub mod escape;
pub mod platforms;
