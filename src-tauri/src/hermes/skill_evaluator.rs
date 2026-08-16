// Copyright (c) 2026 AIMarketing
//
// ServerEval: five-dimensional skill
// evaluation engine.
//
// The evaluator produces a `SkillEvaluation` from a `SkillProposal`
// in 5 dimensions (safety / success / generalization / dedup / cost),
// applies a fixed weighted total, and decides an `EvalVerdict`.
//
// (SkillSource) in `crate::skill::proposal`. At the time this module
// was first written, the file was not yet present, so the
// proposal type is duplicated verbatim here as a `pub mod proposal`
// so the evaluator compiles in isolation. Once the
// canonical file, the duplication should be deleted and replaced
// with a `pub use crate::skill::proposal::*;`.
//
// re-export from crate::skill::proposal
// ↓ ↓ ↓
pub mod proposal {
    use serde::{Deserialize, Serialize};
    use chrono::{DateTime, Utc};

    /// Where the proposal came from. Used by the inbox UI to colour the
    /// row and by the evaluator to weight lineage.
    #[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
    #[serde(rename_all = "lowercase")]
    #[derive(Default)]
    pub enum ProposalSource {
        Teaching,
        Healing,
        Recorder,
        Monitoring,
        /// Anything else (manual, community import, …).
        Community,
        #[default]
        Manual,
    }

    

    /// Light-weight lineage pointer. An empty `parent_skill_id` means
    /// "this is a brand-new skill, no ancestor".
    #[derive(Debug, Clone, Default, Serialize, Deserialize)]
    #[serde(rename_all = "camelCase")]
    pub struct SkillLineage {
        pub parent_skill_id: String,
        #[serde(default)]
        pub parent_version: Option<u32>,
    }

    /// Original signal from the source. `sample_size` is the number of
    /// successful runs the source collected; `avg_latency_ms` is the
    /// mean. Both may be unset when the source couldn't measure them
    /// (e.g. one-shot teaching).
    #[derive(Debug, Clone, Default, Serialize, Deserialize)]
    #[serde(rename_all = "camelCase")]
    pub struct ProposalTelemetry {
        pub sample_size: u32,
        pub avg_latency_ms: Option<u32>,
        #[serde(default)]
        pub user_rating: Option<u8>,
    }

    /// A single candidate skill waiting to be evaluated and adopted.
    #[derive(Debug, Clone, Serialize, Deserialize)]
    #[serde(rename_all = "camelCase")]
    pub struct SkillProposal {
        pub proposal_id: String,
        pub source: ProposalSource,
        /// Raw `skill.md` content (front matter + body).
        pub skill_md: String,
        pub lineage: SkillLineage,
        pub telemetry: ProposalTelemetry,
        pub created_at: DateTime<Utc>,
    }

    impl SkillProposal {
        /// Convenience constructor for tests / synthetic proposals.
        pub fn new(
            proposal_id: impl Into<String>,
            source: ProposalSource,
            skill_md: impl Into<String>,
        ) -> Self {
            Self {
                proposal_id: proposal_id.into(),
                source,
                skill_md: skill_md.into(),
                lineage: SkillLineage::default(),
                telemetry: ProposalTelemetry::default(),
                created_at: Utc::now(),
            }
        }
    }
}

use serde::{Deserialize, Serialize};
use chrono::Utc;

use super::sandbox_runner::SandboxRunner;
use super::dedup_index::{jaccard_to_dedup_credit, DedupIndex};
// `SkillProposal` lives in the nested `proposal` module below (the
// canonical shape; see module-level doc comment). Import it
// here so the impl block on line 195 can write `&SkillProposal`
// rather than `&proposal::SkillProposal` — matches the upstream
// TypeScript surface.
use self::proposal::SkillProposal;
use crate::skill::memory::{save_evaluation, SkillDb, SkillEvaluationRecord};

/// Per-dimension scores, all in `[0.0, 1.0]`.
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct EvalScores {
    /// 0.0 = dangerous, 1.0 = safe.
    pub safety: f32,
    /// Estimated success rate from dry-run.
    pub success: f32,
    /// How well the skill generalises beyond its training slice.
    pub generalization: f32,
    /// 0.0 = exact duplicate, 1.0 = fully novel.
    pub dedup: f32,
    /// 1.0 = cheap (sub-second), 0.0 = prohibitively expensive.
    pub cost: f32,
}

/// Final accept / review / reject decision surfaced to the inbox UI.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum EvalVerdict {
    Accept,
    NeedsReview,
    Reject,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum IssueSeverity {
    Info,
    Warning,
    Error,
}

/// A single flagged finding attached to a `SkillEvaluation`.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EvalIssue {
    /// Stable machine code, e.g. `"RISKY_SHELL"`, `"DUPLICATE_HIGH"`.
    pub code: String,
    pub severity: IssueSeverity,
    pub message: String,
    pub suggestion: Option<String>,
}

/// Final server-side evaluation result.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SkillEvaluation {
    pub proposal_id: String,
    pub scores: EvalScores,
    /// Weighted total: safety 0.30 + success 0.30 + generalization 0.20
    /// + dedup 0.10 + cost 0.10.
    pub total: f32,
    pub verdict: EvalVerdict,
    pub issues: Vec<EvalIssue>,
    pub evaluated_at: chrono::DateTime<Utc>,
    /// `true` when the upstream server (127.0.0.1:8642) was not
    /// reachable and we ran a local heuristic pass.
    pub degraded: bool,
}

/// The configured shape for the weighted total. Exposed so tests /
/// UI can recompute totals after the fact.
pub const WEIGHTS: (f32, f32, f32, f32, f32) = (0.30, 0.30, 0.20, 0.10, 0.10);

pub fn weighted_total(scores: &EvalScores) -> f32 {
    let (ws, wsu, wg, wd, wc) = WEIGHTS;
    (scores.safety * ws
        + scores.success * wsu
        + scores.generalization * wg
        + scores.dedup * wd
        + scores.cost * wc)
        .clamp(0.0, 1.0)
}

/// Stateless evaluator. The dedup index is injected because it
/// carries the corpus of previously seen skills; everything else is
/// pure. When `skill_db` is `Some`, `evaluate()` also persists the
/// result into the `skill_evaluations` sqlite table.
pub struct SkillEvaluator<'a> {
    pub dedup: &'a DedupIndex,
    /// Optional handle to the `SkillDb`. When set, every
    /// `evaluate()` call writes a `SkillEvaluationRecord` row.
    pub skill_db: Option<&'a SkillDb>,
    /// `skill_id` / `version` to attach to the persisted record.
    /// When `None`, the proposal_id is used as skill_id and
    /// version defaults to 0.
    pub skill_id: Option<&'a str>,
    pub version: Option<u32>,
}

impl<'a> SkillEvaluator<'a> {
    pub fn new(dedup: &'a DedupIndex) -> Self {
        Self { dedup, skill_db: None, skill_id: None, version: None }
    }

    /// Attach a `SkillDb` so `evaluate()` persists the verdict.
    /// `skill_id` / `version` identify which skill version this
    /// evaluation belongs to; if omitted, `proposal_id` / `0` are
    /// used as fallbacks.
    pub fn with_skill_db(
        mut self,
        db: &'a SkillDb,
        skill_id: Option<&'a str>,
        version: Option<u32>,
    ) -> Self {
        self.skill_db = Some(db);
        self.skill_id = skill_id;
        self.version = version;
        self
    }

    /// Run the full 5-dimensional evaluation. If `degraded` is
    /// passed as `true`, the `success` dimension is forced to `0.5`
    /// and the `success_rate` from the sandbox is ignored (this is
    /// the fallback path when `127.0.0.1:8642` is unreachable and we
    /// want a deterministic local-only answer).
    pub fn evaluate(&self, proposal: &SkillProposal, degraded: bool) -> SkillEvaluation {
        let mut issues: Vec<EvalIssue> = Vec::new();

        // --- safety ---
        let safety = self.score_safety(&proposal.skill_md, &mut issues);

        // --- success ---
        let (success, sandbox_latency_ms) = if degraded {
            issues.push(EvalIssue {
                code: "DEGRADED_LOCAL_HEURISTIC".to_string(),
                severity: IssueSeverity::Warning,
                message: "evaluation server (127.0.0.1:8642) unreachable; using local heuristic for `success`".to_string(),
                suggestion: Some("start the hermes evaluation server and re-evaluate".to_string()),
            });
            (0.5_f32, None)
        } else {
            let report = SandboxRunner::dry_run(&proposal.skill_md, 50);
            for s in &report.issues {
                issues.push(EvalIssue {
                    code: "DRYRUN_FINDING".to_string(),
                    severity: IssueSeverity::Warning,
                    message: s.clone(),
                    suggestion: None,
                });
            }
            (report.success_rate, Some(report.avg_latency_ms))
        };

        // --- generalization ---
        let generalization = self.score_generalization(proposal, &mut issues);

        // --- dedup ---
        let dedup_score = self.score_dedup(&proposal.skill_md, &mut issues);

        // --- cost ---
        let cost = self.score_cost(
            proposal.telemetry.avg_latency_ms.or(sandbox_latency_ms),
            &mut issues,
        );

        let scores = EvalScores {
            safety,
            success,
            generalization,
            dedup: dedup_score,
            cost,
        };
        let total = weighted_total(&scores);
        let verdict = decide_verdict(total, &scores, &issues);

        let evaluation = SkillEvaluation {
            proposal_id: proposal.proposal_id.clone(),
            scores,
            total,
            verdict,
            issues,
            evaluated_at: Utc::now(),
            degraded,
        };

        // Persist to the `skill_evaluations` sqlite table when a
        // `SkillDb` was attached via `with_skill_db`. Best-effort:
        // a failed write is logged but doesn't fail the evaluation.
        if let Some(db) = self.skill_db {
            let record = evaluation_to_record(
                &evaluation,
                self.skill_id.unwrap_or(&proposal.proposal_id),
                self.version.unwrap_or(0),
            );
            if let Err(e) = save_evaluation(db, &record) {
                log::warn!("[skill_evaluator] failed to persist evaluation: {}", e);
            }
        }

        evaluation
    }

    // ------------------------------------------------------------------
    // Per-dimension scoring helpers
    // ------------------------------------------------------------------

    /// Scan `skill_md` for dangerous shell / SQL / PowerShell
    /// patterns. Each hit subtracts 0.2 from a base of 1.0.
    fn score_safety(&self, skill_md: &str, issues: &mut Vec<EvalIssue>) -> f32 {
        const PATTERNS: &[(&str, &str)] = &[
            ("rm -rf", "RISKY_SHELL"),
            ("rm -fr", "RISKY_SHELL"),
            ("DROP TABLE", "RISKY_SQL"),
            ("DROP DATABASE", "RISKY_SQL"),
            ("format C:", "RISKY_FS"),
            ("Invoke-WebRequest", "RISKY_NET"),
            ("iwr ", "RISKY_NET"),
            ("Invoke-Expression", "RISKY_SHELL"),
            ("curl|sh", "RISKY_PIPE"),
            ("curl|bash", "RISKY_PIPE"),
            ("wget|sh", "RISKY_PIPE"),
            ("del /f /s /q", "RISKY_FS"),
            ("Remove-Item -Recurse -Force", "RISKY_FS"),
        ];
        let lower = skill_md.to_lowercase();
        let mut hits: u32 = 0;
        for (needle, code) in PATTERNS {
            if lower.contains(&needle.to_lowercase()) {
                hits += 1;
                issues.push(EvalIssue {
                    code: (*code).to_string(),
                    severity: IssueSeverity::Error,
                    message: format!("matched dangerous pattern `{}`", needle),
                    suggestion: Some("require explicit user approval before execution".to_string()),
                });
            }
        }
        let score = 1.0_f32 - 0.2_f32 * (hits as f32);
        score.clamp(0.0, 1.0)
    }

    /// Generalisation depends on (a) whether the proposal has a
    /// lineage parent (better) and (b) the original telemetry sample
    /// size. Small samples are penalised.
    fn score_generalization(
        &self,
        proposal: &SkillProposal,
        issues: &mut Vec<EvalIssue>,
    ) -> f32 {
        let mut score: f32 = 0.5;
        let lineage = &proposal.lineage;
        if !lineage.parent_skill_id.trim().is_empty() {
            score += 0.3;
        }
        let sample = proposal.telemetry.sample_size;
        if sample >= 50 {
            score += 0.2;
        } else if sample >= 10 {
            score += 0.1;
        } else if sample < 5 {
            score -= 0.2;
            issues.push(EvalIssue {
                code: "LOW_SAMPLE".to_string(),
                severity: IssueSeverity::Warning,
                message: format!(
                    "telemetry.sample_size={} (< 5); generalisation estimate is unreliable",
                    sample
                ),
                suggestion: Some("collect at least 5 successful runs before proposing".to_string()),
            });
        }
        score.clamp(0.0, 1.0)
    }

    /// De-duplication: 1.0 - max Jaccard with the existing index.
    fn score_dedup(&self, skill_md: &str, issues: &mut Vec<EvalIssue>) -> f32 {
        let tokens = DedupIndex::tokenize(skill_md);
        let (jacc, match_id) = self.dedup.best_match(&tokens);
        let credit = jaccard_to_dedup_credit(jacc);
        if jacc >= 0.8 {
            issues.push(EvalIssue {
                code: "DUPLICATE_HIGH".to_string(),
                severity: IssueSeverity::Error,
                message: format!(
                    "near-duplicate of skill `{}` (jaccard={:.2})",
                    match_id.clone().unwrap_or_default(),
                    jacc
                ),
                suggestion: Some("merge into the existing skill or differentiate the description".to_string()),
            });
        } else if jacc >= 0.5 {
            issues.push(EvalIssue {
                code: "DUPLICATE_MEDIUM".to_string(),
                severity: IssueSeverity::Warning,
                message: format!(
                    "partial overlap with `{}` (jaccard={:.2})",
                    match_id.clone().unwrap_or_default(),
                    jacc
                ),
                suggestion: None,
            });
        }
        credit
    }

    /// Latency → cost credit. < 1s = 1.0, > 5s = 0.0, linear in
    /// between. If latency is unknown we assume 0.6 (a moderate
    /// default that neither blesses nor punishes).
    fn score_cost(
        &self,
        avg_latency_ms: Option<u32>,
        issues: &mut Vec<EvalIssue>,
    ) -> f32 {
        let Some(ms) = avg_latency_ms else {
            return 0.6;
        };
        if ms <= 1_000 {
            return 1.0;
        }
        if ms >= 5_000 {
            issues.push(EvalIssue {
                code: "HIGH_TOKEN".to_string(),
                severity: IssueSeverity::Warning,
                message: format!("avg_latency_ms={} (>5s) — high cost", ms),
                suggestion: Some("consider reducing step count or splitting into smaller skills".to_string()),
            });
            return 0.0;
        }
        // 1-5s linear: 1.0 at 1000ms, 0.0 at 5000ms.
        let score = 1.0 - (ms as f32 - 1_000.0) / 4_000.0;
        score.clamp(0.0, 1.0)
    }
}

fn decide_verdict(total: f32, _scores: &EvalScores, issues: &[EvalIssue]) -> EvalVerdict {
    // Any Error-level issue is an automatic reject.
    if issues.iter().any(|i| i.severity == IssueSeverity::Error) {
        return EvalVerdict::Reject;
    }
    // Any Warning-level dedup issue (DUPLICATE_MEDIUM) prevents auto-Accept
    // and forces NeedsReview, so partially duplicate skills are not silently
    // accepted without human oversight.
    let has_medium_dedup = issues.iter().any(|i| {
        i.severity == IssueSeverity::Warning && i.code.starts_with("DUPLICATE_")
    });
    if total >= 0.85 && !has_medium_dedup {
        EvalVerdict::Accept
    } else if total >= 0.60 {
        EvalVerdict::NeedsReview
    } else {
        EvalVerdict::Reject
    }
}

/// Convert the hermes `SkillEvaluation` into the persistence-layer
/// `SkillEvaluationRecord` so it can be written to the
/// `skill_evaluations` sqlite table. `skill_id` / `version` are
/// supplied by the caller (the evaluator itself only knows the
/// `proposal_id`).
fn evaluation_to_record(
    eval: &SkillEvaluation,
    skill_id: &str,
    version: u32,
) -> SkillEvaluationRecord {
    let verdict_str = match eval.verdict {
        EvalVerdict::Accept => "accept",
        EvalVerdict::NeedsReview => "needs_review",
        EvalVerdict::Reject => "reject",
    };
    let issues_json = serde_json::to_string(&eval.issues).ok();
    let eval_id = format!("{}-{}", eval.proposal_id, eval.evaluated_at.timestamp_millis());
    SkillEvaluationRecord {
        eval_id,
        proposal_id: eval.proposal_id.clone(),
        skill_id: skill_id.to_string(),
        version,
        total_score: eval.total,
        safety_score: Some(eval.scores.safety),
        success_score: Some(eval.scores.success),
        gen_score: Some(eval.scores.generalization),
        dedup_score: Some(eval.scores.dedup),
        cost_score: Some(eval.scores.cost),
        verdict: verdict_str.to_string(),
        issues_json,
        degraded: eval.degraded,
        evaluated_at: eval.evaluated_at.to_rfc3339(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use proposal::{ProposalSource, ProposalTelemetry, SkillLineage};

    fn empty_proposal(skill_md: &str) -> SkillProposal {
        SkillProposal {
            proposal_id: "01HZZZ".to_string(),
            source: ProposalSource::Teaching,
            skill_md: skill_md.to_string(),
            lineage: SkillLineage::default(),
            telemetry: ProposalTelemetry::default(),
            created_at: Utc::now(),
        }
    }

    const GOOD: &str = r#"---
name: open-notepad
description: Launch notepad and type a greeting
version: 0.1.0
entrypoints: [main]
inputs: {}
outputs: {}
dependencies: []
---

# open-notepad

Launches `notepad.exe` and types a greeting.
"#;

    const DANGEROUS: &str = r#"---
name: wipe
description: Format the disk
entrypoints: [main]
---

# wipe

Run `rm -rf /` and then `format C:`. Also `Invoke-WebRequest -OutFile evil.exe`.
"#;

    const DUPLICATE: &str = r#"---
name: open-notepad-copy
description: Launch notepad and type a greeting
version: 0.1.0
entrypoints: [main]
inputs: {}
outputs: {}
dependencies: []
---

# open-notepad-copy

Launches `notepad.exe` and types a greeting. Same exact wording on purpose.
"#;

    #[test]
    fn good_skill_is_accepted_with_low_duplicate_credit() {
        let idx = DedupIndex::new();
        let eval = SkillEvaluator::new(&idx).evaluate(&empty_proposal(GOOD), false);
        assert!(eval.total > 0.7, "expected > 0.7, got {}", eval.total);
        assert!(!eval.degraded);
        // Accept or NeedsReview are both acceptable; we only require
        // NOT Reject for a well-formed, safe skill.
        assert_ne!(eval.verdict, EvalVerdict::Reject);
    }

    #[test]
    fn dangerous_skill_is_rejected() {
        let idx = DedupIndex::new();
        let eval = SkillEvaluator::new(&idx).evaluate(&empty_proposal(DANGEROUS), false);
        assert_eq!(eval.verdict, EvalVerdict::Reject);
        assert!(eval.issues.iter().any(|i| i.code == "RISKY_SHELL"));
        assert!(eval.issues.iter().any(|i| i.code == "RISKY_FS"));
    }

    #[test]
    fn duplicate_skill_is_flagged() {
        let mut idx = DedupIndex::new();
        idx.insert("orig", DedupIndex::tokenize(GOOD));
        let eval = SkillEvaluator::new(&idx).evaluate(&empty_proposal(DUPLICATE), false);
        assert!(eval
            .issues
            .iter()
            .any(|i| i.code == "DUPLICATE_HIGH" || i.code == "DUPLICATE_MEDIUM"));
        // dedup credit must be noticeably lower than 1.0.
        assert!(
            eval.scores.dedup < 0.5,
            "dedup credit too high: {}",
            eval.scores.dedup
        );
    }

    #[test]
    fn degraded_path_forces_success_to_05() {
        let idx = DedupIndex::new();
        let eval = SkillEvaluator::new(&idx).evaluate(&empty_proposal(GOOD), true);
        assert!(eval.degraded);
        assert!((eval.scores.success - 0.5).abs() < 1e-6);
        assert!(eval
            .issues
            .iter()
            .any(|i| i.code == "DEGRADED_LOCAL_HEURISTIC"));
    }

    #[test]
    fn weighted_total_respects_weights() {
        let s = EvalScores {
            safety: 1.0,
            success: 1.0,
            generalization: 1.0,
            dedup: 1.0,
            cost: 1.0,
        };
        assert!((weighted_total(&s) - 1.0).abs() < 1e-6);
        let s = EvalScores {
            safety: 0.0,
            success: 0.0,
            generalization: 0.0,
            dedup: 0.0,
            cost: 0.0,
        };
        assert_eq!(weighted_total(&s), 0.0);
    }

    #[test]
    fn verdict_rejects_when_total_below_threshold() {
        let s = EvalScores {
            safety: 0.5,
            success: 0.5,
            generalization: 0.5,
            dedup: 0.5,
            cost: 0.5,
        };
        assert!(matches!(
            decide_verdict(weighted_total(&s), &s, &[]),
            EvalVerdict::Reject
        ));
    }

    #[test]
    fn low_telemetry_sample_lowers_generalization() {
        let mut p = empty_proposal(GOOD);
        p.telemetry.sample_size = 2;
        let idx = DedupIndex::new();
        let eval = SkillEvaluator::new(&idx).evaluate(&p, false);
        assert!(eval.issues.iter().any(|i| i.code == "LOW_SAMPLE"));
        assert!(eval.scores.generalization < 0.5);
    }
}
