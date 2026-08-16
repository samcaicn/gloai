
//
// "Evolution" tracking: monitor whether the agent's success rate or
// user-rating has improved across a sliding window of runs. The
// TypeScript module kept a small ring buffer. The Rust port mirrors
// that with a fixed-capacity `VecDeque`.

use std::collections::VecDeque;
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct RunRecord {
    pub run_id: String,
    pub agent_id: String,
    pub success: bool,
    pub user_rating: Option<u8>,
    pub ts: i64,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct EvolutionReport {
    pub window: usize,
    pub success_rate: f32,
    pub average_rating: Option<f32>,
    pub trend: f32,
}

pub struct EvolutionTracker {
    window: usize,
    records: VecDeque<RunRecord>,
}

impl EvolutionTracker {
    pub fn new(window: usize) -> Self { Self { window, records: VecDeque::new() } }

    pub fn push(&mut self, r: RunRecord) {
        if self.records.len() == self.window { self.records.pop_front(); }
        self.records.push_back(r);
    }

    pub fn report(&self) -> EvolutionReport {
        let total = self.records.len();
        if total == 0 {
            return EvolutionReport { window: 0, success_rate: 0.0, average_rating: None, trend: 0.0 };
        }
        let success = self.records.iter().filter(|r| r.success).count() as f32 / total as f32;
        let ratings: Vec<f32> = self.records.iter().filter_map(|r| r.user_rating.map(|x| x as f32)).collect();
        let avg = if ratings.is_empty() { None } else { Some(ratings.iter().sum::<f32>() / ratings.len() as f32) };
        let trend = self.trend();
        EvolutionReport { window: total, success_rate: success, average_rating: avg, trend }
    }

    fn trend(&self) -> f32 {
        let n = self.records.len();
        if n < 4 { return 0.0; }
        let half = n / 2;
        let first_success: f32 = self.records.iter().take(half).filter(|r| r.success).count() as f32 / half as f32;
        let second_success: f32 = self.records.iter().skip(half).filter(|r| r.success).count() as f32 / (n - half) as f32;
        second_success - first_success
    }
}
