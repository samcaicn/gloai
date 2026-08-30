// EvolutionTracker — sliding window trend analysis.
//
// Mirrors the TypeScript ring-buffer implementation with a fixed-capacity
// VecDeque. Tracks success rate, user rating, and trend over a window.

use std::collections::VecDeque;
use serde::{Deserialize, Serialize};

/// A single run record in the evolution history.
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct RunRecord {
    pub run_id: String,
    pub agent_id: String,
    pub success: bool,
    pub user_rating: Option<u8>,
    pub ts: i64,
}

/// Report summarizing the current evolution window.
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct EvolutionReport {
    pub window: usize,
    pub success_rate: f32,
    pub average_rating: Option<f32>,
    pub trend: f32,
}

impl EvolutionReport {
    /// Whether the trend is positive (improving).
    pub fn is_improving(&self) -> bool {
        self.trend > 0.01
    }

    /// Whether the trend is negative (degrading).
    pub fn is_degrading(&self) -> bool {
        self.trend < -0.01
    }
}

/// Sliding-window evolution tracker.
pub struct EvolutionTracker {
    window: usize,
    records: VecDeque<RunRecord>,
}

impl EvolutionTracker {
    /// Create a new tracker with the given window size.
    pub fn new(window: usize) -> Self {
        Self {
            window,
            records: VecDeque::new(),
        }
    }

    /// Push a new run record. If the window is full, evict the oldest.
    pub fn push(&mut self, r: RunRecord) {
        if self.records.len() == self.window {
            self.records.pop_front();
        }
        self.records.push_back(r);
    }

    /// Generate a report over the current window.
    pub fn report(&self) -> EvolutionReport {
        let total = self.records.len();
        if total == 0 {
            return EvolutionReport {
                window: 0,
                success_rate: 0.0,
                average_rating: None,
                trend: 0.0,
            };
        }
        let success =
            self.records.iter().filter(|r| r.success).count() as f32 / total as f32;
        let ratings: Vec<f32> = self
            .records
            .iter()
            .filter_map(|r| r.user_rating.map(|x| x as f32))
            .collect();
        let avg = if ratings.is_empty() {
            None
        } else {
            Some(ratings.iter().sum::<f32>() / ratings.len() as f32)
        };
        let trend = self.compute_trend();
        EvolutionReport {
            window: total,
            success_rate: success,
            average_rating: avg,
            trend,
        }
    }

    /// Trend = success rate of second half minus first half.
    fn compute_trend(&self) -> f32 {
        let n = self.records.len();
        if n < 4 {
            return 0.0;
        }
        let half = n / 2;
        let first_success: f32 = self.records.iter().take(half).filter(|r| r.success).count()
            as f32
            / half as f32;
        let second_success: f32 = self.records.iter().skip(half).filter(|r| r.success).count()
            as f32
            / (n - half) as f32;
        second_success - first_success
    }

    /// Get the current window size (number of records).
    pub fn len(&self) -> usize {
        self.records.len()
    }

    /// Whether the tracker is empty.
    pub fn is_empty(&self) -> bool {
        self.records.is_empty()
    }

    /// Clear all records.
    pub fn clear(&mut self) {
        self.records.clear()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_tracker() {
        let t = EvolutionTracker::new(10);
        let r = t.report();
        assert_eq!(r.window, 0);
        assert_eq!(r.success_rate, 0.0);
        assert!(r.average_rating.is_none());
    }

    #[test]
    fn trend_improving() {
        let mut t = EvolutionTracker::new(10);
        // First half: failures
        for _ in 0..5 {
            t.push(RunRecord {
                run_id: "r".into(),
                agent_id: "a".into(),
                success: false,
                user_rating: Some(2),
                ts: 0,
            });
        }
        // Second half: successes
        for _ in 0..5 {
            t.push(RunRecord {
                run_id: "r".into(),
                agent_id: "a".into(),
                success: true,
                user_rating: Some(5),
                ts: 0,
            });
        }
        let r = t.report();
        assert!(r.trend > 0.0); // Improving
        assert!(r.is_improving());
    }

    #[test]
    fn trend_degrading() {
        let mut t = EvolutionTracker::new(10);
        // First half: successes
        for _ in 0..5 {
            t.push(RunRecord {
                run_id: "r".into(),
                agent_id: "a".into(),
                success: true,
                user_rating: Some(5),
                ts: 0,
            });
        }
        // Second half: failures
        for _ in 0..5 {
            t.push(RunRecord {
                run_id: "r".into(),
                agent_id: "a".into(),
                success: false,
                user_rating: Some(1),
                ts: 0,
            });
        }
        let r = t.report();
        assert!(r.trend < 0.0); // Degrading
        assert!(r.is_degrading());
    }
}
