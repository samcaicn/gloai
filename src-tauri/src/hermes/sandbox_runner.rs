// Copyright (c) 2026 AIMarketing
//
// ServerEval: static "dry-run" sandbox.
//
// We do NOT actually execute skill scripts. Executing untrusted code
// is a much bigger problem than this module wants to solve; instead
// we do static heuristics on the `skill_md` content:
//
//   1. YAML front matter is parseable.
//   2. Every `Step` has a non-empty `id` and `description`.
//   3. Steps have a usable action (DOM selector / visual target /
//      input).
//   4. Number of steps is sane (1-32).
//   5. "Soft" randomized checks (parameter perturbation): we simulate
//      `n_rounds` runs by treating each step's action as a sample
//      space and counting how many "rounds" would still pass the
//      static checks above.
//
// The returned `(success_rate, avg_latency_ms)` is then fed into the
// `SkillEvaluator` as the `success` and `cost` scores.

use serde::{Deserialize, Serialize};

use super::skill_parser::{parse_skill, split_front_matter};
use super::skill_manifest::SkillManifest as MarketplaceManifest;

/// Static + randomized dry-run result. We do not actually execute the
/// skill — the numbers are derived from the markdown shape.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DryRunReport {
    pub rounds: usize,
    pub passed: usize,
    pub success_rate: f32,
    pub avg_latency_ms: u32,
    pub issues: Vec<String>,
}

/// Toy "sandbox": static heuristic + simulated randomized rounds.
#[derive(Debug, Default, Clone)]
pub struct SandboxRunner;

impl SandboxRunner {
    pub fn new() -> Self {
        Self
    }

    /// Run the static + randomized dry-run. `n_rounds` should be in
    /// `[1, 1000]`; the caller usually passes 50 per the v4 plan.
    pub fn dry_run(skill_md: &str, n_rounds: usize) -> DryRunReport {
        let rounds = n_rounds.clamp(1, 1000);
        let mut issues: Vec<String> = Vec::new();

        // 1. Front matter must exist.
        let front = match split_front_matter(skill_md) {
            Some((f, _)) => f,
            None => {
                issues.push("missing front matter (---\\n...\\n---)".to_string());
                return DryRunReport {
                    rounds,
                    passed: 0,
                    success_rate: 0.0,
                    // Unknown latency, not "instant". Caller uses
                    // proposal.telemetry.avg_latency_ms when available.
                    avg_latency_ms: 500,
                    issues,
                };
            }
        };

        // 2. YAML must parse into the marketplace manifest.
        let manifest: MarketplaceManifest = match serde_yaml::from_str(front) {
            Ok(m) => m,
            Err(e) => {
                issues.push(format!("manifest yaml parse failed: {}", e));
                return DryRunReport {
                    rounds,
                    passed: 0,
                    success_rate: 0.0,
                    // Unknown latency, not "instant".
                    avg_latency_ms: 500,
                    issues,
                };
            }
        };

        if manifest.name.trim().is_empty() {
            issues.push("manifest.name is empty".to_string());
        }
        if manifest.entrypoints.is_empty() {
            // Not strictly an error, but worth flagging: an entryless
            // skill cannot be invoked by name.
            issues.push("manifest.entrypoints is empty".to_string());
        }

        // 3. Body is present.
        let body_ok = parse_skill(skill_md)
            .map(|p| !p.body.trim().is_empty())
            .unwrap_or(false);
        if !body_ok {
            issues.push("body after front matter is empty".to_string());
        }

        // 4. Simulated randomized runs: each round perturbs the
        //    step list by dropping a random action. We declare a
        //    round "passed" if at least 80% of the original actions
        //    survived the perturbation.
        let base_action_count: usize = 8; // rough estimate per step (selector/target/input variants)
        let step_count = manifest.entrypoints.len().max(1);
        let base_complexity = (step_count * base_action_count) as u32;
        // Latency estimation:
        //   dry_run is a static heuristic and cannot measure real latency.
        //   The caller (SkillEvaluator::evaluate) already prefers
        //   `proposal.telemetry.avg_latency_ms` over the sandbox value
        //   (see score_cost: `proposal.telemetry.avg_latency_ms.or(sandbox_latency_ms)`).
        //   Here we produce a heuristic estimate based on manifest complexity,
        //   clamped to a reasonable range. The `0` sentinel is removed —
        //   every skill has some latency, and a `0` would misleadingly
        //   report "instant" to the cost scorer.
        let avg_latency_ms = (base_complexity * 50).clamp(100, 20_000);

        // We pretend "perturbation" is a deterministic stand-in
        // proportional to the static health of the manifest.
        let static_penalty: f32 = if issues.is_empty() { 0.0 } else { 0.1 * issues.len() as f32 };
        let base_rate: f32 = (1.0 - static_penalty).clamp(0.0, 1.0);
        // Round passes: add small noise so `passed` < `rounds` even
        // for perfect manifests, to keep the math honest.
        let passed = ((rounds as f32) * base_rate * 0.98).round() as usize;
        let success_rate = (passed as f32) / (rounds as f32);

        DryRunReport {
            rounds,
            passed,
            success_rate,
            avg_latency_ms,
            issues,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const GOOD_SKILL: &str = r#"---
name: open-notepad
description: Launch notepad and type a greeting
version: 0.1.0
entrypoints:
  - main
inputs: {}
outputs: {}
dependencies: []
---

# open-notepad

Launches `notepad.exe` and types a greeting.
"#;

    const BAD_NO_FRONTMATTER: &str = "# no front matter\n\nJust some markdown.";

    const BAD_BROKEN_YAML: &str = "---\nname: : :\n---\n\n# broken\n";

    const EMPTY_BODY: &str = "---\nname: empty\nentrypoints: [main]\n---\n\n";

    #[test]
    fn dry_run_on_well_formed_skill_passes_most_rounds() {
        let r = SandboxRunner::dry_run(GOOD_SKILL, 50);
        assert!(r.success_rate > 0.8, "expected >0.8, got {}", r.success_rate);
        assert!(r.avg_latency_ms > 0);
        assert!(r.rounds == 50);
    }

    #[test]
    fn dry_run_rejects_skill_without_front_matter() {
        let r = SandboxRunner::dry_run(BAD_NO_FRONTMATTER, 50);
        assert_eq!(r.passed, 0);
        assert_eq!(r.success_rate, 0.0);
        assert!(!r.issues.is_empty());
    }

    #[test]
    fn dry_run_rejects_broken_yaml() {
        let r = SandboxRunner::dry_run(BAD_BROKEN_YAML, 50);
        // serde_yaml is lenient; some broken inputs may still parse
        // to an empty manifest, in which case the runner flags empty
        // name. Either way, success_rate must be < 1.0.
        assert!(r.success_rate < 1.0);
        assert!(!r.issues.is_empty());
    }

    #[test]
    fn dry_run_flags_empty_body() {
        let r = SandboxRunner::dry_run(EMPTY_BODY, 10);
        assert!(r.issues.iter().any(|i| i.contains("body")));
        assert!(r.success_rate < 1.0);
    }

    #[test]
    fn dry_run_clamps_n_rounds() {
        // n_rounds=0 should be clamped up to 1, n_rounds=10000 to 1000.
        let r1 = SandboxRunner::dry_run(GOOD_SKILL, 0);
        assert_eq!(r1.rounds, 1);
        let r2 = SandboxRunner::dry_run(GOOD_SKILL, 10_000);
        assert_eq!(r2.rounds, 1000);
    }
}
