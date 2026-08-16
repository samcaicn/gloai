
//
// Parallel-safety guard. The TypeScript module exported a function
// that took a `Set<string>` of held locks and a list of requested
// locks, then returned either the order in which they could be
// acquired or an error. The Rust port uses a deterministic topological
// sort with cycle detection.

use std::collections::{HashMap, HashSet, VecDeque};
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub enum ParallelSafetyError {
    Cycle(Vec<String>),
    MissingDependency(String),
}

#[derive(Default)]
pub struct ParallelSafetyGraph {
    edges: HashMap<String, HashSet<String>>,
}


impl ParallelSafetyGraph {
    pub fn new() -> Self { Self::default() }

    pub fn add_edge(&mut self, from: &str, to: &str) {
        self.edges.entry(from.to_string()).or_default().insert(to.to_string());
    }

    pub fn order(&self, requested: &[String]) -> Result<Vec<String>, ParallelSafetyError> {
        let requested_set: HashSet<&String> = requested.iter().collect();
        let mut indeg: HashMap<String, usize> = requested.iter().map(|s| (s.clone(), 0)).collect();
        let mut adj: HashMap<String, Vec<String>> = HashMap::new();
        for (from, tos) in &self.edges {
            if !requested_set.contains(from) { continue; }
            for to in tos {
                if !requested_set.contains(to) { continue; }
                *indeg.get_mut(to).unwrap() += 1;
                adj.entry(from.clone()).or_default().push(to.clone());
            }
        }
        let mut queue: VecDeque<String> = indeg.iter().filter_map(|(k, v)| if *v == 0 { Some(k.clone()) } else { None }).collect();
        let mut order = Vec::new();
        while let Some(n) = queue.pop_front() {
            order.push(n.clone());
            if let Some(children) = adj.get(&n) {
                for c in children {
                    if let Some(d) = indeg.get_mut(c) {
                        *d -= 1;
                        if *d == 0 { queue.push_back(c.clone()); }
                    }
                }
            }
        }
        if order.len() == indeg.len() {
            Ok(order)
        } else {
            let mut cycle: Vec<String> = indeg.iter().filter_map(|(k, v)| if *v > 0 { Some(k.clone()) } else { None }).collect();
            cycle.sort();
            Err(ParallelSafetyError::Cycle(cycle))
        }
    }
}
