// Copyright (c) 2026 tupAI
//
// Portable `which` + provider specs. Mirrors Multica's daemon auto-detect:
// scan PATH for known coding-agent CLIs and report availability.

use std::path::{Path, PathBuf};

use crate::runtime_registry::{ProviderSpec, RuntimeInstance, RuntimeKind};

/// Known, detectable providers. Extend this table to add a new CLI.
///
/// CLI call syntax calibrated against the real tools (2026-08):
///   - Kimi Code CLI: non-interactive single prompt is `kimi -p "..."`
///     (`kimi --prompt`); `--output-format stream-json` for scripting.
///   - Trae Agent CLI (bytedance/trae-agent): `trae-cli run "..."`
///     with optional `--working-dir <dir>`. The published binary is
///     `trae-cli`; some installs expose it as `trae`, so both are probed.
pub fn builtin_provider_specs() -> Vec<ProviderSpec> {
    vec![
        ProviderSpec { id: "opencode", binary: "opencode", aliases: &[], display_name: "OpenCode", kind: RuntimeKind::Acp, acp_client_id: Some("opencode"), cli_args_template: &[] },
        ProviderSpec { id: "claude",   binary: "claude",   aliases: &[], display_name: "Claude Code", kind: RuntimeKind::Acp, acp_client_id: Some("claude-code"), cli_args_template: &[] },
        ProviderSpec { id: "codex",    binary: "codex",    aliases: &[], display_name: "Codex", kind: RuntimeKind::Acp, acp_client_id: Some("codex"), cli_args_template: &[] },
        ProviderSpec { id: "kimi",     binary: "kimi",     aliases: &[], display_name: "Kimi", kind: RuntimeKind::CliRun, acp_client_id: None, cli_args_template: &["-p", "{prompt}"] },
        ProviderSpec { id: "trae",     binary: "trae",     aliases: &["trae-cli"], display_name: "Trae", kind: RuntimeKind::CliRun, acp_client_id: None, cli_args_template: &["run", "{prompt}", "--working-dir", "{cwd}"] },
    ]
}

/// Resolve the first existing binary among candidate names using PATH.
/// Windows-aware: honours PATHEXT so `claude` finds `claude.cmd`/`.exe`.
pub fn resolve_binary(names: &[&str]) -> Option<PathBuf> {
    let path_var = std::env::var("PATH").ok()?;
    let dirs: Vec<String> = std::env::split_paths(&path_var)
        .map(|p| p.to_string_lossy().to_string())
        .collect();
    let extensions = candidate_extensions();
    for name in names {
        for dir in &dirs {
            for ext in &extensions {
                let candidate = if ext.is_empty() {
                    Path::new(dir).join(name)
                } else {
                    Path::new(dir).join(format!("{name}{ext}"))
                };
                if candidate.is_file() {
                    return Some(candidate);
                }
            }
        }
    }
    None
}

fn candidate_extensions() -> Vec<String> {
    if cfg!(windows) {
        let pathext =
            std::env::var("PATHEXT").unwrap_or_else(|_| ".EXE;.CMD;.BAT;.COM".into());
        pathext.split(';').map(|s| s.to_lowercase()).collect()
    } else {
        vec![String::new()]
    }
}

/// Best-effort version probe (`--version`). Never fatal.
pub fn probe_version(bin: &Path) -> Option<String> {
    let out = std::process::Command::new(bin).arg("--version").output().ok()?;
    if out.status.success() {
        let s = String::from_utf8_lossy(&out.stdout).trim().to_string();
        if s.is_empty() {
            None
        } else {
            Some(s.lines().next().unwrap_or(&s).to_string())
        }
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builtin_specs_have_expected_kinds_and_templates() {
        let specs = builtin_provider_specs();
        assert_eq!(specs.len(), 5);
        let by_id: std::collections::HashMap<&str, &ProviderSpec> =
            specs.iter().map(|s| (s.id, s)).collect();

        assert_eq!(by_id["opencode"].kind, RuntimeKind::Acp);
        assert_eq!(by_id["claude"].kind, RuntimeKind::Acp);
        assert_eq!(by_id["codex"].kind, RuntimeKind::Acp);
        assert_eq!(by_id["kimi"].kind, RuntimeKind::CliRun);
        assert_eq!(by_id["trae"].kind, RuntimeKind::CliRun);

        // Kimi: non-interactive single prompt is `kimi -p "..."`.
        assert_eq!(by_id["kimi"].cli_args_template, &["-p", "{prompt}"]);
        // Trae: `trae-cli run "..." --working-dir <dir>`; binary alias trae-cli.
        assert_eq!(
            by_id["trae"].cli_args_template,
            &["run", "{prompt}", "--working-dir", "{cwd}"]
        );
        assert!(by_id["trae"].aliases.contains(&"trae-cli"));
    }
}

/// Scan all built-in providers and return detected runtime instances.
pub fn detect_builtins() -> Vec<RuntimeInstance> {
    let mut out = Vec::new();
    for spec in builtin_provider_specs() {
        let candidates: Vec<&str> = std::iter::once(spec.binary)
            .chain(spec.aliases.iter().copied())
            .collect();
        let path = resolve_binary(&candidates);
        let (installed, endpoint, version) = match &path {
            Some(p) => (true, p.to_string_lossy().to_string(), probe_version(p)),
            None => (false, String::new(), None),
        };
        out.push(RuntimeInstance {
            id: format!("rt-{}", spec.id),
            provider_id: spec.id.to_string(),
            kind: spec.kind,
            display_name: spec.display_name.to_string(),
            endpoint,
            installed,
            version,
            model: None,
            has_api_key: false,
            cli_args_template: Some(
                spec.cli_args_template.iter().map(|s| s.to_string()).collect(),
            ),
        });
    }
    out
}
