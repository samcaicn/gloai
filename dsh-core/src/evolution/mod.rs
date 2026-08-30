// Evolution tracking — adapted from safeopcapp hermes/evolution.
//
// Sliding-window tracker that monitors whether the agent's success rate
// or user-rating has improved over recent runs.

pub mod tracker;

pub use tracker::{EvolutionReport, EvolutionTracker, RunRecord};
