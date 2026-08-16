// Copyright (c) 2026 AIMarketing
//
// Skill registry: atomically swap a running skill
// to a newer, server-evaluated version, with inbox buffering for
// "needs review" proposals and a rollback book for failed adopts.
//
// Scope (this file is intentionally narrow):
//   * In-memory `SkillRegistry` shared across the Tauri command
//     layer via `app.manage(...)`.
//   * Inbox = the list of `SkillEvaluation`s that scored in the
//     0.60–0.85 band, surfaced to the front-end as cards.
//   * Atomic swap = we keep *both* the running and fallback
//     version in `running_versions` so an in-flight request never
//     observes a half-applied manifest.
//
// Persistence (sqlite-backed `skill_versions` / `skill_runs`
// rows) lives in `crate::skill::memory`. We deliberately
// don't write to disk here so the registry can stay lock-free
// during the hot path; the daily evolution job in
// `crate::automation::evolution` is responsible for
// reconciling memory to disk.

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};

use crate::automation::adopt_policy::{classify, Decision};
use crate::automation::rollback::RollbackBook;

/// Five-dimension evaluation vector the server returns. Each
/// component is a `0.0..=1.0` score; the registry uses
/// `total_score` for the policy band lookup and surfaces the
/// vector to the inbox UI for the radar chart.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SkillEvaluation {
    /// 0.0..=1.0 — "is this skill going to do something dangerous?"
    pub safety: f32,
    /// 0.0..=1.0 — success rate in the server's 50-round dry-run.
    pub success_rate: f32,
    /// 0.0..=1.0 — does it cover the cases the proposer claims?
    pub generality: f32,
    /// 0.0..=1.0 — Jaccard distance to existing skills
    /// (1.0 == no duplicate, 0.0 == exact clone).
    pub uniqueness: f32,
    /// 0.0..=1.0 — lower cost scores higher.
    pub resource_cost: f32,
    /// Weighted average of the above. Picked by the server.
    pub total_score: f32,
    /// Free-form verdict the server sends (e.g. "approved with
    /// warnings"). Stored verbatim in the inbox card.
    #[serde(default)]
    pub verdict: String,
    /// `true` if the server was unreachable and the score was
    /// approximated locally. The UI shows a clear
    /// "offline score" badge when this is set.
    #[serde(default)]
    pub degraded: bool,
}

/// The on-the-wire shape of an inbox item. Mirrors
/// `InboxItem` in the front-end so we can serialise it with
/// `serde(rename_all = "camelCase")` and the JS side can read
/// it without a manual transform.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InboxItem {
    /// Stable id assigned by the transport layer. The
    /// registry treats it as opaque.
    pub proposal_id: String,
    /// `SkillManifest::name` (or `"unknown"` for proposals
    /// that don't compile).
    pub skill_id: String,
    pub skill_name: String,
    /// Raw `skill.md` source so the inbox card can render a
    /// "preview" button.
    pub skill_md: String,
    /// Where this proposal came from. Matches the
    /// `source` column on `skill_proposals`.
    pub source: String,
    pub evaluation: SkillEvaluation,
    /// Unix-seconds when we first received the evaluation.
    pub received_at: i64,
    /// Which band this proposal fell into. Computed by
    /// `classify(evaluation.total_score)`; we cache it here so
    /// `list_inbox` doesn't have to re-classify on every poll.
    pub decision: String,
}

/// Result of an `adopt_proposal` call. Returned to the front-end
/// so it can show a toast ("auto-accepted v3" / "needs your
/// review" / "rejected") without re-deriving the decision.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AdoptOutcome {
    pub proposal_id: String,
    pub skill_id: String,
    pub decision: String,
    pub score: f32,
    /// Version we are now running. `None` if the decision was
    /// `Reject` (we didn't bump anything).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub new_version: Option<u32>,
    /// Version we were running before. `None` on the first
    /// adopt of a brand-new skill.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub previous_version: Option<u32>,
    /// `true` if the score came from the offline fallback
    /// (`evaluation.degraded == true`). The front-end uses this
    /// to soften the success toast.
    pub degraded: bool,
}

/// Internal representation of an inbox row. We keep the raw
/// `InboxItem` so the `auto_accept` path can swap versions without
/// re-classifying (the `Decision` is recomputed from
/// `item.decision`).
#[derive(Clone)]
#[allow(dead_code)] // part of SkillRegistry public API; consumed by `adopt`/`user_accept`
struct InboxEntry {
    item: InboxItem,
}

/// The shared, mutex-guarded registry. Stored in
/// `app.manage(SkillRegistry::new())` and fetched from commands
/// via `tauri::State<'_, SkillRegistry>`.
#[allow(dead_code)] // part of ClientAdopt public API; commands::skill inbox
pub struct SkillRegistry {
    /// Skill id -> currently running version. The "new" version
    /// of an in-flight atomic swap also lives here alongside the
    /// previous one — see `running_versions_old`.
    running_versions: Mutex<HashMap<String, u32>>,
    /// Skill id -> version we keep as fallback during the watch
    /// window. We DON'T drop the old version immediately so a
    /// rollback doesn't have to re-load it from disk.
    running_versions_old: Mutex<HashMap<String, u32>>,
    /// Skill id -> the proposal id currently parked in the
    /// inbox. We only keep one in-flight proposal per skill id
    /// so the UI doesn't have to disambiguate.
    inbox: Mutex<HashMap<String, InboxEntry>>,
    /// Per-skill id monotonic version counter. We bump it on
    /// every successful adopt (not on the first one — first
    /// adopt starts at v1).
    version_counter: Mutex<HashMap<String, u32>>,
    /// Bounded history of adopt decisions. The front-end can
    /// poll it to draw an "evolution timeline" later. Capped at
    /// 256 entries to keep the registry light.
    history: Mutex<Vec<AdoptOutcome>>,
    /// Rollback book — guarded separately so the registry's
    /// own mutex stays short-lived.
    rollback: Mutex<RollbackBook>,
}

impl Default for SkillRegistry {
    fn default() -> Self {
        Self {
            running_versions: Mutex::new(HashMap::new()),
            running_versions_old: Mutex::new(HashMap::new()),
            inbox: Mutex::new(HashMap::new()),
            version_counter: Mutex::new(HashMap::new()),
            history: Mutex::new(Vec::new()),
            rollback: Mutex::new(RollbackBook::default()),
        }
    }
}

#[allow(dead_code)] // SkillRegistry public API; commands::skill inbox IPC + module tests
impl SkillRegistry {
    pub fn new() -> Self {
        Self::default()
    }

    /// Apply an evaluation. The three policy bands:
    ///
    /// * `>= HIGH_CONFIDENCE` — atomically swap to the new
    ///   version, keeping the previous one as fallback for the
    ///   watch window. Returns `AdoptOutcome` with the new
    ///   version number.
    /// * `[REVIEW_THRESHOLD, HIGH_CONFIDENCE)` — store the
    ///   evaluation in the inbox and return a "needs review"
    ///   outcome. The user accepts or dismisses from the UI.
    /// * `< REVIEW_THRESHOLD` — record the rejection in history
    ///   and return a "rejected" outcome. The inbox is not
    ///   touched (we don't want to nag the user about a skill
    ///   the server already said is bad).
    ///
    /// `skill_md` and `source` are required even for the
    /// reject path so we can write them to history (the
    /// memory layer's evolution loop uses them to retrain the
    /// proposal prompt).
    pub fn adopt(
        &self,
        proposal_id: &str,
        skill_id: &str,
        skill_name: &str,
        source: &str,
        skill_md: &str,
        evaluation: &SkillEvaluation,
    ) -> Result<AdoptOutcome, String> {
        let decision = classify(evaluation.total_score);
        let now = now_unix_secs();

        // Clean up expired rollback guards so the cooldown map
        // doesn't grow forever.
        {
            // 锁中毒时恢复而不是 panic,避免一次 panic 拖垮整个技能子系统。
            let mut rb = self.rollback.lock().unwrap_or_else(|p| p.into_inner());
            rb.gc_expired(now);
        }

        // Build the outcome early so the history tail sees the
        // final shape (including any version bump).
        let mut outcome = AdoptOutcome {
            proposal_id: proposal_id.to_string(),
            skill_id: skill_id.to_string(),
            decision: decision.as_str().to_string(),
            score: evaluation.total_score,
            new_version: None,
            previous_version: None,
            degraded: evaluation.degraded,
        };

        match decision {
            Decision::AutoAccept => {
                let new_version = self.swap(skill_id, evaluation, now)?;
                outcome.new_version = Some(new_version.0);
                outcome.previous_version = new_version.1;
                // Park the proposal in the inbox too, marked
                // "auto_accept", so the user can still see the
                // history of recently promoted skills in the UI.
                self.push_inbox(InboxEntry {
                    item: InboxItem {
                        proposal_id: proposal_id.to_string(),
                        skill_id: skill_id.to_string(),
                        skill_name: skill_name.to_string(),
                        skill_md: skill_md.to_string(),
                        source: source.to_string(),
                        evaluation: evaluation.clone(),
                        received_at: now,
                        decision: decision.as_str().to_string(),
                    },
                })?;
            }
            Decision::NeedsReview => {
                // The UI drives the user. We just buffer the
                // proposal; no version swap happens until the
                // user accepts from the inbox.
                self.push_inbox(InboxEntry {
                    item: InboxItem {
                        proposal_id: proposal_id.to_string(),
                        skill_id: skill_id.to_string(),
                        skill_name: skill_name.to_string(),
                        skill_md: skill_md.to_string(),
                        source: source.to_string(),
                        evaluation: evaluation.clone(),
                        received_at: now,
                        decision: decision.as_str().to_string(),
                    },
                })?;
            }
            Decision::Reject => {
                // No inbox entry, no version swap. The history
                // vector still gets the outcome so the evolution
                // loop can see "this kind of proposal keeps
                // failing".
            }
        }

        self.record_history(outcome.clone());
        Ok(outcome)
    }

    /// User manually accepts a `NeedsReview` proposal. Mirrors
    /// the auto-accept path minus the policy check (the user is
    /// the policy now). Returns `Err` if the proposal isn't in
    /// the inbox.
    pub fn user_accept(&self, proposal_id: &str) -> Result<AdoptOutcome, String> {
        let entry = {
            let inbox = self.inbox.lock().unwrap_or_else(|p| p.into_inner());
            inbox.get(proposal_id).cloned()
        }
        .ok_or_else(|| format!("proposal '{}' not in inbox", proposal_id))?;
        let now = now_unix_secs();
        let (new, prev) = self.swap(&entry.item.skill_id, &entry.item.evaluation, now)?;
        // Remove from inbox after a successful accept.
        {
            let mut inbox = self.inbox.lock().unwrap_or_else(|p| p.into_inner());
            inbox.remove(proposal_id);
        }
        let outcome = AdoptOutcome {
            proposal_id: proposal_id.to_string(),
            skill_id: entry.item.skill_id,
            decision: Decision::AutoAccept.as_str().to_string(),
            score: entry.item.evaluation.total_score,
            new_version: Some(new),
            previous_version: prev,
            degraded: entry.item.evaluation.degraded,
        };
        self.record_history(outcome.clone());
        Ok(outcome)
    }

    /// User dismisses a proposal (whether it was `NeedsReview`
    /// or already-`AutoAccept`d). Always succeeds; an unknown
    /// proposal id is treated as "already gone" and returns Ok.
    pub fn dismiss(&self, proposal_id: &str, _reason: &str) -> Result<(), String> {
        {
            let mut inbox = self.inbox.lock().unwrap_or_else(|p| p.into_inner());
            inbox.remove(proposal_id);
        }
        Ok(())
    }

    /// Snapshot the current inbox for `list_inbox`. Newest first
    /// (so the UI doesn't have to sort).
    pub fn list_inbox(&self) -> Vec<InboxItem> {
        let inbox = self.inbox.lock().unwrap_or_else(|p| p.into_inner());
        let mut items: Vec<InboxItem> = inbox.values().map(|e| e.item.clone()).collect();
        items.sort_by_key(|b| std::cmp::Reverse(b.received_at));
        items
    }

    /// Return the running version of a skill, if any.
    pub fn get_running(&self, skill_id: &str) -> Option<u32> {
        let map = self
            .running_versions
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        map.get(skill_id).copied()
    }

    /// Install a locally-persisted skill into the registry **without**
    /// going through the `adopt` / `swap` path.
    ///
    /// 为什么不调 `adopt`: `adopt` 会在 AutoAccept 分支调 `swap`,后者
    ///  * bump `version_counter` (导致每次重启 version 单调递增,但 skill_md
    ///    没变,版本号语义错乱)
    ///  * 把旧版本挪到 `running_versions_old` 并启动 `RollbackGuard` watch
    ///    (上一轮的 skill_md 和本轮完全一样,rollback 没有意义)
    ///  * 在 history push 一条假 adopt 记录 (会把真正的演进历史挤出 256
    ///    条窗口)
    ///
    /// 本方法直接 `insert` 到 `running_versions`,version=1,不碰 inbox /
    /// history / rollback。适合"加载本地缓存"的语义。
    ///
    /// `name` 参数当前未持久化到 registry 内存态 ——
    /// `execute_skill` 通过 `load_manifest_from_skill_id(skill_id)` 重新解析,
    /// 不查 registry 的 skill_md。保留 `name` 参数是为了未来扩展 (例如把
    /// 本地缓存的 skill_md 也存到 registry 让 UI 预览)。`skill_md` 参数已
    /// 移除: 旧实现把它读进内存只是为了传到这里被忽略, 大文件 OOM 风险,
    /// 且 `execute_skill` 根本不查 registry 的 skill_md。
    pub fn install_persisted(
        &self,
        skill_id: &str,
        _name: &str,
    ) -> Result<(), String> {
        let mut map = self
            .running_versions
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        // 只在不存在时插入, 避免覆盖运行中已被 adopt 升级过的版本号。
        // 如果 skill_id 已存在 (例如本轮启动后用户又通过 adopt 升级了),
        // 保留现有 version, 不回退。
        map.entry(skill_id.to_string()).or_insert(1);
        Ok(())
    }

    /// Remove a locally-persisted skill from the registry's running set.
    /// 幂等: skill_id 不存在不算错。
    ///
    /// 注意: 本方法只清 `running_versions`, 不清 inbox / history / rollback。
    /// inbox 里的 proposal 条目有自己的生命周期 (dismiss / user_accept),
    /// 不应被这里连带删除。
    pub fn remove_persisted(&self, skill_id: &str) -> Result<(), String> {
        let mut map = self
            .running_versions
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        map.remove(skill_id);
        Ok(())
    }

    /// Force a rollback of a skill. Called by the `RollbackGuard`
    /// when its failure budget blows, and by the manual "Roll
    /// back" button in the inbox UI. Returns the version we
    /// restored to (or `None` if nothing was running).
    pub fn rollback(&self, skill_id: &str, reason: &str) -> Result<Option<u32>, String> {
        let previous = {
            let mut rb = self.rollback.lock().unwrap_or_else(|p| p.into_inner());
            rb.force_rollback(skill_id, reason)
        };
        let restored = {
            let mut running = self
                .running_versions
                .lock()
                .unwrap_or_else(|p| p.into_inner());
            let mut old = self
                .running_versions_old
                .lock()
                .unwrap_or_else(|p| p.into_inner());
            if let Some(prev) = previous {
                if let Some(current) = running.get(skill_id).copied() {
                    old.insert(skill_id.to_string(), current);
                }
                running.insert(skill_id.to_string(), prev);
                Some(prev)
            } else if let Some(current) = running.get(skill_id).copied() {
                // No active guard — try the most recent "old" we
                // remember.
                if let Some(prev) = old.get(skill_id).copied() {
                    old.insert(skill_id.to_string(), current);
                    running.insert(skill_id.to_string(), prev);
                    Some(prev)
                } else {
                    None
                }
            } else {
                None
            }
        };
        Ok(restored)
    }

    // ---- private helpers -------------------------------------------------

    /// Atomic-ish swap. Bumps the version counter, installs the
    /// new version as "running", and parks the previous one in
    /// `running_versions_old` for the rollback window. Returns
    /// `(new_version, previous_version)`.
    fn swap(
        &self,
        skill_id: &str,
        evaluation: &SkillEvaluation,
        now: i64,
    ) -> Result<(u32, Option<u32>), String> {
        // Refuse to promote a skill whose score is below the
        // review band — this is the "user clicked accept" path
        // but the server might have sent a *stale* inbox entry
        // whose score has since been re-evaluated. The user
        // override is recorded on the outcome as
        // `decision = "auto_accept"`, but we still honour the
        // server's number.
        if !evaluation.total_score.is_finite() {
            return Err("cannot adopt a non-finite score".to_string());
        }

        let new_version = {
            let mut counter = self.version_counter.lock().unwrap_or_else(|p| p.into_inner());
            let entry = counter.entry(skill_id.to_string()).or_insert(0);
            *entry += 1;
            *entry
        };
        let previous = {
            let mut running = self
                .running_versions
                .lock()
                .unwrap_or_else(|p| p.into_inner());
            let prev = running.get(skill_id).copied();
            running.insert(skill_id.to_string(), new_version);
            prev
        };
        if let Some(prev) = previous {
            let mut old = self
                .running_versions_old
                .lock()
                .unwrap_or_else(|p| p.into_inner());
            old.insert(skill_id.to_string(), prev);
        }
        {
            let mut rb = self.rollback.lock().unwrap_or_else(|p| p.into_inner());
            // First adopt: no previous version exists, so skip the
            // watch window (the rollback book has nothing to fall
            // back to). Subsequent adopts install a real watch.
            if let Some(prev) = previous {
                rb.start_watch(skill_id, prev, new_version, now)?;
            }
        }
        Ok((new_version, previous))
    }

    /// Insert (or replace) an inbox entry. One entry per
    /// proposal id; re-pushing the same id just overwrites.
    fn push_inbox(&self, entry: InboxEntry) -> Result<(), String> {
        let mut inbox = self.inbox.lock().unwrap_or_else(|p| p.into_inner());
        inbox.insert(entry.item.proposal_id.clone(), entry);
        Ok(())
    }

    /// Append an outcome to the bounded history tail.
    fn record_history(&self, outcome: AdoptOutcome) {
        const LIMIT: usize = 256;
        let mut history = self.history.lock().unwrap_or_else(|p| p.into_inner());
        history.push(outcome);
        let overflow = history.len().saturating_sub(LIMIT);
        if overflow > 0 {
            history.drain(0..overflow);
        }
    }
}

#[allow(dead_code)] // helper for `SkillRegistry::adopt` / `user_accept` above
fn now_unix_secs() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

// (HIGH_CONFIDENCE / REVIEW_THRESHOLD are crate-private in
// `automation::adopt_policy`; if external callers need them later,
// re-export with `pub` upstream and re-add the `pub use` here.)

#[cfg(test)]
mod tests {
    use super::*;

    fn eval(score: f32) -> SkillEvaluation {
        SkillEvaluation {
            safety: score,
            success_rate: score,
            generality: score,
            uniqueness: score,
            resource_cost: score,
            total_score: score,
            verdict: "test".to_string(),
            degraded: false,
        }
    }

    #[test]
    fn auto_accept_bumps_version() {
        let reg = SkillRegistry::new();
        let out = reg
            .adopt("p1", "s1", "name", "teaching", "yaml", &eval(0.9))
            .unwrap();
        assert_eq!(out.decision, "auto_accept");
        assert_eq!(out.new_version, Some(1));
        assert_eq!(out.previous_version, None);
        assert_eq!(reg.get_running("s1"), Some(1));
    }

    #[test]
    fn second_auto_accept_keeps_previous() {
        let reg = SkillRegistry::new();
        reg.adopt("p1", "s1", "name", "teaching", "yaml", &eval(0.9))
            .unwrap();
        let out = reg
            .adopt("p2", "s1", "name", "teaching", "yaml", &eval(0.92))
            .unwrap();
        assert_eq!(out.new_version, Some(2));
        assert_eq!(out.previous_version, Some(1));
        assert_eq!(reg.get_running("s1"), Some(2));
    }

    #[test]
    fn review_band_buffers_in_inbox() {
        let reg = SkillRegistry::new();
        let out = reg
            .adopt("p1", "s1", "name", "teaching", "yaml", &eval(0.70))
            .unwrap();
        assert_eq!(out.decision, "needs_review");
        assert_eq!(out.new_version, None);
        assert_eq!(reg.list_inbox().len(), 1);
        assert_eq!(reg.get_running("s1"), None);
    }

    #[test]
    fn user_accept_promotes_reviewed_skill() {
        let reg = SkillRegistry::new();
        reg.adopt("p1", "s1", "name", "teaching", "yaml", &eval(0.70))
            .unwrap();
        let out = reg.user_accept("p1").unwrap();
        assert_eq!(out.decision, "auto_accept");
        assert_eq!(out.new_version, Some(1));
        assert!(reg.list_inbox().is_empty());
    }

    #[test]
    fn reject_does_not_touch_inbox_or_running() {
        let reg = SkillRegistry::new();
        let out = reg
            .adopt("p1", "s1", "name", "teaching", "yaml", &eval(0.30))
            .unwrap();
        assert_eq!(out.decision, "reject");
        assert!(reg.list_inbox().is_empty());
        assert_eq!(reg.get_running("s1"), None);
    }

    #[test]
    fn dismiss_removes_inbox_entry() {
        let reg = SkillRegistry::new();
        reg.adopt("p1", "s1", "name", "teaching", "yaml", &eval(0.70))
            .unwrap();
        reg.dismiss("p1", "user clicked").unwrap();
        assert!(reg.list_inbox().is_empty());
        // Dismissing a non-existent id is a no-op (idempotent).
        reg.dismiss("ghost", "user clicked").unwrap();
    }

    #[test]
    fn rollback_restores_previous() {
        let reg = SkillRegistry::new();
        reg.adopt("p1", "s1", "name", "teaching", "yaml", &eval(0.9))
            .unwrap();
        reg.adopt("p2", "s1", "name", "teaching", "yaml", &eval(0.9))
            .unwrap();
        assert_eq!(reg.get_running("s1"), Some(2));
        let restored = reg.rollback("s1", "test").unwrap();
        assert_eq!(restored, Some(1));
        assert_eq!(reg.get_running("s1"), Some(1));
    }
}
