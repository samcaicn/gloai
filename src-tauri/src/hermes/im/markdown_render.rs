// Copyright (c) 2026 MeeJoy
//

use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct RenderOptions {
    pub platform: String,
    pub max_heading_level: u8,
}

impl Default for RenderOptions {
    fn default() -> Self { Self { platform: "generic".into(), max_heading_level: 3 } }
}

pub fn render(markdown: &str, opts: &RenderOptions) -> String {
    use crate::markdown::render::render_to;
    render_to(markdown, &opts.platform)
}
