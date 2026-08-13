//! Shared launch-spec helpers for locating and spawning `dsh web`.

use std::env;
use std::path::{Path, PathBuf};

use crate::settings::AppSettings;

pub const DEFAULT_DSH_PACKAGE: &str = "@deepseek-ai/dsh@^0.1.0-rc.6";
pub const KEYRING_SERVICE: &str = "ai.deepseek.harness.gui";
pub const KEYRING_USER: &str = "deepseek-api-key";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LaunchSpec {
    pub program: String,
    pub args: Vec<String>,
    pub source: LaunchSource,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LaunchSource {
    Configured,
    PathDsh,
    Npx,
}

/// Parse the `dsh web:` announcement line printed after the webserver binds.
///
/// Only loopback http URLs are accepted. A LAN suffix is ignored.
pub fn parse_dsh_web_url(line: &str) -> Option<String> {
    let rest = line.trim().strip_prefix("dsh web:")?.trim();
    let url = rest.split_whitespace().next()?;
    if is_loopback_http(url) {
        Some(url.to_string())
    } else {
        None
    }
}

pub fn is_loopback_http(url: &str) -> bool {
    url.starts_with("http://127.0.0.1:") || url.starts_with("http://localhost:")
}

pub fn path_separator() -> char {
    if cfg!(windows) { ';' } else { ':' }
}

pub fn augmented_path() -> String {
    let sep = path_separator();
    let current = env::var("PATH").unwrap_or_default();
    let mut parts: Vec<String> = current
        .split(sep)
        .filter(|part| !part.is_empty())
        .map(str::to_string)
        .collect();

    let mut extras: Vec<String> = Vec::new();
    if let Some(home) = dirs::home_dir() {
        extras.push(home.join(".local/bin").display().to_string());
        extras.push(home.join(".local/share/pnpm").display().to_string());
        extras.push(home.join("Library/pnpm").display().to_string());
        extras.push(home.join(".npm-global/bin").display().to_string());
        extras.push(home.join("AppData/Roaming/npm").display().to_string());
        extras.push(home.join("AppData/Local/pnpm").display().to_string());
    }
    extras.extend([
        "/opt/homebrew/bin".into(),
        "/opt/homebrew/sbin".into(),
        "/usr/local/bin".into(),
        "/usr/bin".into(),
        "/bin".into(),
    ]);

    for extra in extras {
        if !parts.iter().any(|part| part == &extra) {
            parts.insert(0, extra);
        }
    }
    parts.join(&sep.to_string())
}

pub fn find_in_path(name: &str, path_var: &str) -> Option<PathBuf> {
    let sep = path_separator();
    let candidates = executable_names(name);
    for dir in path_var.split(sep).filter(|part| !part.is_empty()) {
        for file_name in &candidates {
            let candidate = Path::new(dir).join(file_name);
            if candidate.is_file() {
                return Some(candidate);
            }
        }
    }
    None
}

fn executable_names(name: &str) -> Vec<String> {
    if cfg!(windows) {
        vec![
            format!("{name}.cmd"),
            format!("{name}.exe"),
            format!("{name}.bat"),
            name.to_string(),
        ]
    } else {
        vec![name.to_string()]
    }
}

pub fn resolve_launch(settings: &AppSettings, path_var: &str) -> LaunchSpec {
    let args = if settings.harness_args.is_empty() {
        default_harness_args()
    } else {
        settings.harness_args.clone()
    };

    let configured = settings.harness_command.trim();
    if !configured.is_empty() {
        return LaunchSpec {
            program: configured.to_string(),
            args,
            source: LaunchSource::Configured,
        };
    }

    if let Some(dsh) = find_in_path("dsh", path_var) {
        return LaunchSpec {
            program: dsh.display().to_string(),
            args,
            source: LaunchSource::PathDsh,
        };
    }

    let npx = find_in_path("npx", path_var)
        .map(|path| path.display().to_string())
        .unwrap_or_else(|| "npx".to_string());
    let mut npx_args = vec!["--yes".into(), DEFAULT_DSH_PACKAGE.into()];
    npx_args.extend(args);
    LaunchSpec {
        program: npx,
        args: npx_args,
        source: LaunchSource::Npx,
    }
}

pub fn default_harness_args() -> Vec<String> {
    vec![
        "web".into(),
        "--host".into(),
        "127.0.0.1".into(),
        "--port".into(),
        "0".into(),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;
    use pretty_assertions::assert_eq;

    #[test]
    fn parse_plain_loopback_url() {
        assert_eq!(
            parse_dsh_web_url("dsh web: http://127.0.0.1:4567"),
            Some("http://127.0.0.1:4567".into())
        );
    }

    #[test]
    fn parse_url_with_lan_suffix() {
        assert_eq!(
            parse_dsh_web_url(
                "dsh web: http://127.0.0.1:4567 (LAN: http://192.168.1.5:4567)"
            ),
            Some("http://127.0.0.1:4567".into())
        );
    }

    #[test]
    fn parse_localhost_url() {
        assert_eq!(
            parse_dsh_web_url("  dsh web: http://localhost:3080  "),
            Some("http://localhost:3080".into())
        );
    }

    #[test]
    fn reject_non_loopback_url() {
        assert_eq!(
            parse_dsh_web_url("dsh web: http://192.168.1.5:4567"),
            None
        );
        assert_eq!(parse_dsh_web_url("ready on http://127.0.0.1:4567"), None);
        assert_eq!(parse_dsh_web_url("dsh web: https://127.0.0.1:4567"), None);
    }

    #[test]
    fn resolve_configured_command() {
        let settings = AppSettings {
            harness_command: "/opt/dsh".into(),
            harness_args: vec!["web".into()],
            ..AppSettings::default()
        };
        let spec = resolve_launch(&settings, "");
        assert_eq!(spec.program, "/opt/dsh");
        assert_eq!(spec.args, vec!["web"]);
        assert_eq!(spec.source, LaunchSource::Configured);
    }

    #[test]
    fn resolve_npx_when_dsh_missing() {
        let settings = AppSettings::default();
        let spec = resolve_launch(&settings, "/tmp/does-not-exist-dsh-gui");
        assert_eq!(spec.source, LaunchSource::Npx);
        assert!(spec.args.starts_with(&["--yes".into(), DEFAULT_DSH_PACKAGE.into()]));
        assert!(spec.args.windows(2).any(|pair| pair == ["--host", "127.0.0.1"]));
    }
}
