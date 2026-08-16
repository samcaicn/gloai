// Copyright (c) 2026 AIMarketing
//
// ServerEval: lightweight de-duplication
// index for skill proposals.
//
// We use plain token-set Jaccard similarity (no embedding model). For
// the proposal volumes AIMarketing is expected to see (single-digit per
// session, low thousands total) Jaccard over whitespace + symbol
// tokens is a defensible, deterministic, zero-dependency proxy for
// "are these two skills doing the same thing".
//
// The index lives in memory. Persistence to `~/.hermes/skill-dedup.db`
// is best-effort and only attempted when the `dirs` crate can resolve
// a writable HOME — if the path is unavailable we silently fall back
// to in-memory only.

use std::collections::HashSet;
use std::path::PathBuf;

/// One entry in the dedup index. The raw `skill_md` is intentionally
/// NOT kept around (memory + privacy) — we only retain the normalized
/// token set, the skill identifier, and a timestamp.
#[derive(Debug, Clone)]
pub struct DedupEntry {
    pub skill_id: String,
    pub tokens: HashSet<String>,
    pub added_at_unix: i64,
}

/// In-memory Jaccard index with optional JSON persistence.
#[derive(Debug, Default)]
pub struct DedupIndex {
    entries: Vec<DedupEntry>,
    /// Optional backing file. `None` means "in-memory only".
    persist_path: Option<PathBuf>,
}

impl DedupIndex {
    /// Create a fresh in-memory index.
    pub fn new() -> Self {
        Self::default()
    }

    /// Create an index that lazily persists to `~/.hermes/skill-dedup.db`.
    /// If the home directory cannot be determined the path is left as
    /// `None` and the index runs in-memory only — callers must not
    /// rely on `persist()` succeeding.
    pub fn with_default_path() -> Self {
        let persist_path = dirs::home_dir().map(|h| h.join(".hermes").join("skill-dedup.db"));
        Self { entries: Vec::new(), persist_path }
    }

    /// Add a skill's tokens. `skill_id` should be a stable identifier
    /// (e.g. `proposal_id` or a `<name>@<version>` pair).
    pub fn insert(&mut self, skill_id: &str, tokens: HashSet<String>) {
        let now = chrono::Utc::now().timestamp();
        self.entries.push(DedupEntry {
            skill_id: skill_id.to_string(),
            tokens,
            added_at_unix: now,
        });
    }

    /// Tokenize a raw `skill_md` body into a normalized token set.
    /// Lowercase ASCII, drop punctuation, collapse whitespace.
    ///
    /// CJK 文本无空格分词, 整段中文会被当作单个 token → Jaccard 恒为 0,
    /// 任何中文差异都判为"完全不同"。这里对含 CJK 字符的 token 拆成
    /// 字符级 unigram, 让相似度计算对中文有意义 (与 bigram 相比 unigram
    /// 更宽松, 适合"部分重叠即合并"的记忆去重场景)。
    pub fn tokenize(skill_md: &str) -> HashSet<String> {
        skill_md
            .split(|c: char| !c.is_alphanumeric())
            .filter(|w| !w.is_empty())
            .flat_map(|w| {
                let lower = w.to_lowercase();
                if lower.chars().any(is_cjk) {
                    // 含 CJK → 拆成字符级 unigram (跳过非 CJK 字符如数字/拉丁)
                    lower
                        .chars()
                        .filter(|c| c.is_alphanumeric())
                        .map(|c| c.to_string())
                        .collect::<Vec<_>>()
                } else {
                    vec![lower]
                }
            })
            .collect()
    }

    /// Highest Jaccard similarity between `tokens` and any existing
    /// entry, plus the matching `skill_id` (or `None` for an empty
    /// index). Returns `(best_score, best_match)`.
    pub fn best_match(&self, tokens: &HashSet<String>) -> (f32, Option<String>) {
        if self.entries.is_empty() || tokens.is_empty() {
            return (0.0, None);
        }
        let mut best: f32 = 0.0;
        let mut best_id: Option<String> = None;
        for entry in &self.entries {
            let score = jaccard(tokens, &entry.tokens);
            if score > best {
                best = score;
                best_id = Some(entry.skill_id.clone());
            }
        }
        (best, best_id)
    }

    /// Convenience: tokenize + score in one call. Returns the
    /// de-duplication score in `[0.0, 1.0]` where `1.0` means
    /// "identical" and `0.0` means "no overlap".
    pub fn score_against(&self, skill_md: &str) -> f32 {
        let tokens = Self::tokenize(skill_md);
        self.best_match(&tokens).0
    }

    /// Total number of stored entries (mostly useful for tests / UI).
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    /// True if no skills have been recorded.
    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// Best-effort JSON persistence. We never fail loudly — losing a
    /// dedup record is much less important than blocking the
    /// evaluation pipeline.
    #[allow(dead_code)]
    pub fn persist(&self) -> Result<(), String> {
        let Some(path) = &self.persist_path else { return Ok(()); };
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        let serializable: Vec<(String, Vec<String>, i64)> = self
            .entries
            .iter()
            .map(|e| (e.skill_id.clone(), e.tokens.iter().cloned().collect(), e.added_at_unix))
            .collect();
        let body = serde_json::to_string(&serializable).map_err(|e| e.to_string())?;
        std::fs::write(path, body).map_err(|e| e.to_string())
    }
}

/// Jaccard similarity = |A ∩ B| / |A ∪ B|.
/// Returns `0.0` when both sets are empty.
pub fn jaccard(a: &HashSet<String>, b: &HashSet<String>) -> f32 {
    if a.is_empty() && b.is_empty() {
        return 0.0;
    }
    let inter = a.intersection(b).count() as f32;
    let union = a.union(b).count() as f32;
    if union == 0.0 {
        0.0
    } else {
        inter / union
    }
}

/// 判断字符是否为 CJK 表意文字 (中日韩统一表意文字 + 扩展A + 兼容区)。
/// 用于 `tokenize` 时把无空格分词的 CJK 文本拆成字符级 unigram。
fn is_cjk(c: char) -> bool {
    matches!(c,
        '\u{4E00}'..='\u{9FFF}'   // CJK Unified Ideographs
        | '\u{3400}'..='\u{4DBF}'  // CJK Extension A
        | '\u{F900}'..='\u{FAFF}'  // CJK Compatibility Ideographs
        | '\u{3040}'..='\u{30FF}'  // Hiragana + Katakana
    )
}

/// Map a raw Jaccard score to a 0-1 de-duplication *credit*: identical
/// skills get 0 credit, completely disjoint skills get 1.
/// `jaccard` is in `[0.0, 1.0]`.
pub fn jaccard_to_dedup_credit(jaccard: f32) -> f32 {
    (1.0 - jaccard.clamp(0.0, 1.0)).max(0.0)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tokens(words: &[&str]) -> HashSet<String> {
        words.iter().map(|w| w.to_lowercase()).map(String::from).collect()
    }

    #[test]
    fn jaccard_identical_sets_is_one() {
        let a = tokens(&["open", "notepad", "type", "hello"]);
        let b = tokens(&["open", "notepad", "type", "hello"]);
        assert!((jaccard(&a, &b) - 1.0).abs() < 1e-6);
    }

    #[test]
    fn jaccard_disjoint_sets_is_zero() {
        let a = tokens(&["open", "notepad"]);
        let b = tokens(&["resize", "image"]);
        assert_eq!(jaccard(&a, &b), 0.0);
    }

    #[test]
    fn jaccard_partial_overlap() {
        // {launch, notepad, type} ∩ {launch, notepad, save} = {launch, notepad} (2)
        // {launch, notepad, type} ∪ {launch, notepad, save} = {launch, notepad, type, save} (4)
        let a = tokens(&["launch", "notepad", "type"]);
        let b = tokens(&["launch", "notepad", "save"]);
        assert!((jaccard(&a, &b) - 0.5).abs() < 1e-6);
    }

    #[test]
    fn empty_inputs_yield_zero() {
        let a: HashSet<String> = HashSet::new();
        let b = tokens(&["hello"]);
        assert_eq!(jaccard(&a, &b), 0.0);
        assert_eq!(jaccard(&a, &a), 0.0);
    }

    #[test]
    fn best_match_finds_highest_overlap() {
        let mut idx = DedupIndex::new();
        idx.insert("A", tokens(&["open", "notepad", "type", "hello"]));
        idx.insert("B", tokens(&["resize", "image", "save"]));
        let probe = tokens(&["open", "notepad", "type", "world"]);
        let (score, id) = idx.best_match(&probe);
        assert!(score > 0.5, "expected >0.5 overlap, got {}", score);
        assert_eq!(id.as_deref(), Some("A"));
    }

    #[test]
    fn dedup_credit_is_inverse_of_jaccard() {
        assert!((jaccard_to_dedup_credit(1.0) - 0.0).abs() < 1e-6);
        assert!((jaccard_to_dedup_credit(0.0) - 1.0).abs() < 1e-6);
        assert!((jaccard_to_dedup_credit(0.7) - 0.3).abs() < 1e-6);
    }

    #[test]
    fn tokenize_handles_punctuation_and_case() {
        let t = DedupIndex::tokenize("# Open *NOTEPAD*\nType: Hello, World!");
        // We just check a few high-confidence tokens survived.
        assert!(t.contains("open"));
        assert!(t.contains("notepad"));
        assert!(t.contains("type"));
        assert!(t.contains("hello"));
        assert!(t.contains("world"));
    }
}
