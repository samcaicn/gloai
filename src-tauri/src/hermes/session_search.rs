
//
// Full-text search over session transcripts. The TypeScript module used
// `flexsearch` for indexing. The Rust port uses a simple inverted
// index on lowercased tokens; this is sufficient for the chat history
// pane in the front-end.

use std::collections::{HashMap, HashSet};
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct IndexedSession {
    pub id: String,
    pub title: String,
    pub body: String,
}

#[derive(Default)]
pub struct SessionSearch {
    docs: Vec<IndexedSession>,
    index: HashMap<String, HashSet<usize>>,
}

impl SessionSearch {
    pub fn new() -> Self { Self::default() }

    pub fn upsert(&mut self, session: IndexedSession) {
        let id = session.id.clone();
        // 之前先 `self.docs.push(session.clone())` 再
        // `self.docs[pos] = session` → 在新 id 路径上 session
        // 被 clone 然后立即被覆盖,多余一次堆分配。改为:命中
        // 旧 id → 覆盖,未命中 → push,两种路径只走一次 store。
        match self.docs.iter().position(|d| d.id == id) {
            Some(p) => { self.docs[p] = session; }
            None => { self.docs.push(session); }
        }
        self.rebuild();
    }

    pub fn remove(&mut self, id: &str) -> bool {
        let before = self.docs.len();
        self.docs.retain(|d| d.id != id);
        if self.docs.len() != before { self.rebuild(); true } else { false }
    }

    fn rebuild(&mut self) {
        self.index.clear();
        for (i, doc) in self.docs.iter().enumerate() {
            for token in tokenize(&format!("{} {}", doc.title, doc.body)) {
                self.index.entry(token).or_default().insert(i);
            }
        }
    }

    pub fn search(&self, query: &str, limit: usize) -> Vec<IndexedSession> {
        let tokens = tokenize(query);
        if tokens.is_empty() { return self.docs.iter().take(limit).cloned().collect(); }
        let mut scores: HashMap<usize, usize> = HashMap::new();
        for t in &tokens {
            if let Some(hits) = self.index.get(t) {
                for h in hits { *scores.entry(*h).or_insert(0) += 1; }
            }
        }
        let mut ranked: Vec<(usize, usize)> = scores.into_iter().collect();
        ranked.sort_by_key(|b| std::cmp::Reverse(b.1));
        ranked.into_iter().take(limit).filter_map(|(i, _)| self.docs.get(i).cloned()).collect()
    }
}

fn tokenize(s: &str) -> Vec<String> {
    s.split(|c: char| !c.is_alphanumeric())
        .filter(|t| !t.is_empty())
        .map(|t| t.to_lowercase())
        .collect()
}
