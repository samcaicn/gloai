// Copyright (c) 2026 tupAI
//
// Per-application profiles. Each profile tells the router which
// tier to prefer and what string to match against when the
// focused window is being attributed. Profiles are pure data —
// the only logic lives in `find_profile` (lookup by id).

pub mod profiles;
pub mod types;

pub use profiles::{find_profile, ALL_PROFILES};
pub use types::{AppProfile, RendererType, RoutePreference};
