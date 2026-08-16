// Copyright (c) 2026 tupAI
//
// SkillProposal schema.
//
// `SkillProposal` is the unified "candidate" payload that every
// skill source (manual teaching, auto-healing, recorder,
// monitoring, community feed, …) hands to the
// `127.0.0.1:8642` server evaluator and the front-end inbox UI.
//
// All structs in this file are tagged with
// `#[serde(rename_all = "camelCase")]` so they serialise directly
// to the Tauri IPC bridge without a custom converter; the
// `proposal_id` and `created_at` fields stay snake_case on the
// wire because they are not part of the camelCase rename (they
// are single words and "id" / "at" don't need to change).
//
// NOTE on `proposal_id`: the spec calls for a ULID (sortable,
// 26 chars, time-embedded).  We use `uuid::Uuid::new_v4().to_string()`
// for now because adding the `ulid` crate requires touching
// `src-tauri/Cargo.toml` which is on the main-thread reserved
// list.  The shape is identical (string) so swapping the generator
// later is a one-line change in `SkillProposal::new`.

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

/// Where a `SkillProposal` came from.  The lowercase serialisation
/// matches the SQL `source` column in `skill_proposals` and the
/// string accepted by `proposal_store::list`.
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "lowercase")]
#[derive(Default)]
pub enum ProposalSource {
    /// User-driven manual teaching flow (commands::teaching::stop_recording).
    Teaching,
    /// Self-healing engine fix (commands::teaching::attempt_heal).
    Healing,
    /// Low-level recorder output (Recorder::finalize_into_proposal).
    /// Distinct from `Teaching` so a future automated monitor that
    /// records user actions without explicit teaching can land here.
    Recorder,
    /// Background monitoring/observer (out of scope for this PR).
    Monitoring,
    /// Pulled from a community skill feed.
    Community,
    /// Hand-typed skill.md (front-end inbox UI).
    #[default]
    Manual,
}

impl ProposalSource {
    /// Stable lowercase string used in SQL & on the wire.  Useful
    /// for the `list(source: …)` filter that wants a `&str`
    /// rather than an enum value.
    pub fn as_str(&self) -> &'static str {
        match self {
            ProposalSource::Teaching => "teaching",
            ProposalSource::Healing => "healing",
            ProposalSource::Recorder => "recorder",
            ProposalSource::Monitoring => "monitoring",
            ProposalSource::Community => "community",
            ProposalSource::Manual => "manual",
        }
    }
}


/// Lineage metadata: where this proposal came from in the skill
/// family tree.  `parent_skill_id` + `parent_version` identify
/// the parent skill (if any) so the evaluator can score the
/// "version graph" dimension and the registry can wire the new
/// version next to the previous one.
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct SkillLineage {
    pub parent_skill_id: Option<String>,
    pub parent_version: Option<u32>,
    pub derivation_note: Option<String>,
}

/// Telemetry from the source system that produced the proposal.
/// The evaluator uses this as the *prior* signal for the success-rate
/// score (its 50-round dry-run is the *posterior*).
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct ProposalTelemetry {
    /// 0.0 — 1.0.  1.0 means the source itself reports 100 %
    /// success (recorder finished, heal succeeded, teaching
    /// compiled, …).  0.0 means the source did not provide a
    /// meaningful number.
    pub source_success_rate: f32,
    /// Average execution latency of the source in milliseconds.
    /// 0 when the source has no real execution (manual teaching,
    /// community import).
    pub avg_latency_ms: u32,
    /// Number of samples the telemetry is computed over.  For
    /// recorder it is the number of captured events; for healing
    /// the number of attempts; for teaching the number of compiled
    /// MCP steps.
    pub sample_size: u32,
}

/// Unified skill candidate.  This is the schema both the
/// front-end (`src/api/skill-source.js`) and the evaluator
/// consume.  See the module-level note for the `proposal_id`
/// generator.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SkillProposal {
    pub proposal_id: String,
    pub source: ProposalSource,
    pub skill_md: String,
    pub lineage: SkillLineage,
    pub telemetry: ProposalTelemetry,
    pub created_at: DateTime<Utc>,
}

impl SkillProposal {
    /// Build a proposal with a freshly generated id and the
    /// current UTC timestamp.  See the module-level note on the
    /// ULID vs UUID choice.
    pub fn new(
        source: ProposalSource,
        skill_md: String,
        lineage: SkillLineage,
        telemetry: ProposalTelemetry,
    ) -> Self {
        Self {
            proposal_id: Uuid::new_v4().to_string(),
            source,
            skill_md,
            lineage,
            telemetry,
            created_at: Utc::now(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn proposal_id_is_unique() {
        let p1 = SkillProposal::new(
            ProposalSource::Teaching,
            "name: a".into(),
            SkillLineage::default(),
            ProposalTelemetry::default(),
        );
        let p2 = SkillProposal::new(
            ProposalSource::Teaching,
            "name: a".into(),
            SkillLineage::default(),
            ProposalTelemetry::default(),
        );
        assert_ne!(p1.proposal_id, p2.proposal_id);
    }

    #[test]
    fn source_serialises_lowercase() {
        let v = serde_json::to_string(&ProposalSource::Healing).unwrap();
        assert_eq!(v, "\"healing\"");
    }

    #[test]
    fn telemetry_uses_camel_case() {
        let t = ProposalTelemetry {
            source_success_rate: 0.5,
            avg_latency_ms: 100,
            sample_size: 4,
        };
        let v = serde_json::to_string(&t).unwrap();
        assert!(v.contains("sourceSuccessRate"), "got {}", v);
        assert!(v.contains("avgLatencyMs"), "got {}", v);
        assert!(v.contains("sampleSize"), "got {}", v);
    }

    #[test]
    fn lineage_uses_camel_case() {
        let l = SkillLineage {
            parent_skill_id: Some("x".into()),
            parent_version: Some(2),
            derivation_note: Some("patch".into()),
        };
        let v = serde_json::to_string(&l).unwrap();
        assert!(v.contains("parentSkillId"), "got {}", v);
        assert!(v.contains("parentVersion"), "got {}", v);
        assert!(v.contains("derivationNote"), "got {}", v);
    }
}
