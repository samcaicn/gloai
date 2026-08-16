// Copyright (c) 2026 tupAI
//
// Broker integration sub-module. This is the *real money* tier:
// every type here is part of the trading surface area, and the
// invariants enforced by `router.rs` (no UI automation may call
// `place_order`) are load-bearing for compliance. Stub adapters
// are present so the binary compiles, but the guard rails
// (`assert_broker_only_context`, `mark_called_from_ui_automation`)
// are real and immediate.

pub mod adapter;
pub mod router;
pub mod stubs;
pub mod types;

#[allow(unused_imports)]  // re-export used by #[cfg(test)] modules
pub use adapter::BrokerAdapter;
pub use router::BrokerRouter;
