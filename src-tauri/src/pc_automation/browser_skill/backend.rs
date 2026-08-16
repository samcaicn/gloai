// Copyright (c) 2026 tupAI
//
// BrowserSkill backend trait + `bsk` CLI implementation.
//
// `BskCliBackend` shells out to the `bsk` executable (the BrowserSkill
// CLI) and parses its stdout/stderr/exit code into a `BrowserSkillResult`.
// It is a subprocess backend, deliberately NOT a `CdpBackend`
// replacement: it drives the user's real logged-in browser through the
// BrowserSkill extension bridge, not an arbitrary Electron/Chromium
// DevTools endpoint.
//
// Auto-install: the `bsk` CLI is not shipped with the app. `ensure_installed`
// runs the official one-line installer when the binary is missing, and
// `status` reports CLI / daemon / extension state so the front-end can
// drive onboarding. The browser extension itself CANNOT be auto-installed
// (Chrome/Edge block silent injection) — only detected + deep-linked.

use crate::pc_automation::browser_skill::types::{
    BrowserSkillAction, BrowserSkillResult, BrowserSkillStatus,
};
use std::path::PathBuf;
use std::process::Command;
use std::time::Instant;

/// Backend contract for driving the user's browser via BrowserSkill.
pub trait BrowserSkillBackend: Send + Sync {
    /// Check whether the `bsk` CLI is installed and runnable.
    /// Returns the version string on success.
    fn health(&self) -> Result<String, String>;

    /// Dispatch a single action to `bsk` and wait for it to finish.
    fn exec(&self, action: BrowserSkillAction) -> Result<BrowserSkillResult, String>;

    /// Ensure the `bsk` CLI is installed; if missing, run the official
    /// one-line installer for the current platform. Idempotent: returns
    /// `Ok(())` if already runnable. The installer drops `bsk` into
    /// `~/.local/bin`.
    fn ensure_installed(&self) -> Result<(), String>;

    /// Aggregate runtime status (CLI version, daemon liveness, extension
    /// connection) used to drive front-end onboarding. Non-destructive:
    /// only runs `bsk --version` + `bsk doctor`.
    fn status(&self) -> BrowserSkillStatus;
}

/// Default name of the BrowserSkill CLI executable (resolved via PATH).
const BSK_BIN: &str = "bsk";

/// Official one-line installers (same as the project README). We shell
/// out to the platform shell so we don't re-implement release-asset
/// discovery / signature trust.
#[cfg(windows)]
const BSK_INSTALL_COMMAND: &str =
    "irm https://raw.githubusercontent.com/Tencent/BrowserSkill/main/install.ps1 | iex";
#[cfg(not(windows))]
const BSK_INSTALL_COMMAND: &str =
    "curl -fsSL https://raw.githubusercontent.com/Tencent/BrowserSkill/main/install.sh | sh";

/// Chrome Web Store page for the BrowserSkill extension. The desktop app
/// cannot silently inject a browser extension; we deep-link here and ask
/// the user to click "Add to Chrome/Edge". Once installed the extension
/// auto-connects to the local daemon — no further steps.
pub const BSK_EXTENSION_STORE_URL: &str =
    "https://chromewebstore.google.com/detail/hhcmgoofomhgciiibhipgmgkgnoenaoi";

/// Resolve the user's home directory without pulling in an extra crate.
fn home_dir() -> Option<PathBuf> {
    #[cfg(windows)]
    return std::env::var("USERPROFILE").ok().map(PathBuf::from);
    #[cfg(not(windows))]
    return std::env::var("HOME").ok().map(PathBuf::from);
}

/// Real backend that invokes the `bsk` CLI.
pub struct BskCliBackend {
    /// Optional explicit path to the `bsk` binary. When `None`, the
    /// binary is resolved from the managed `~/.local/bin` location first,
    /// then PATH. Allows pinning a sidecar / specific release.
    bin: Option<String>,
}

impl BskCliBackend {
    pub fn new() -> Self {
        Self { bin: None }
    }

    /// Override the resolved binary path (e.g. a sidecar / pinned
    /// release). Returns `self` for chaining in tests / setup.
    #[allow(dead_code)]
    pub fn with_bin(mut self, bin: impl Into<String>) -> Self {
        self.bin = Some(bin.into());
        self
    }

    /// Resolve the binary to invoke. Preference order:
    ///   1. explicit `bin` override
    ///   2. managed `~/.local/bin/bsk(.exe)` (where the official installer puts it)
    ///   3. bare `bsk` resolved via PATH
    fn resolve_bin(&self) -> String {
        if let Some(bin) = &self.bin {
            return bin.clone();
        }
        if let Some(home) = home_dir() {
            let mut p = home.join(".local").join("bin");
            #[cfg(windows)]
            p.push("bsk.exe");
            #[cfg(not(windows))]
            p.push("bsk");
            if p.exists() {
                return p.to_string_lossy().into_owned();
            }
        }
        BSK_BIN.to_string()
    }

    /// Build a `Command` for `bsk`, applying binary resolution.
    fn bsk_command(&self, args: &[String]) -> Command {
        let mut cmd = Command::new(self.resolve_bin());
        cmd.args(args);
        cmd
    }

    /// Run `bsk doctor` and parse daemon / extension connection status.
    /// `bsk doctor` emits lines like `ok    daemon running` and
    /// `FAIL  extension connected`; we treat a line as healthy only when
    /// it contains `ok` (and not `fail`) AND the relevant keyword.
    fn doctor(&self) -> (bool, bool) {
        let mut daemon = false;
        let mut extension = false;
        if let Ok(o) = self.bsk_command(&["doctor".to_string()]).output() {
            let text = String::from_utf8_lossy(&o.stdout);
            for line in text.lines() {
                let lower = line.to_lowercase();
                let ok = lower.contains("ok") && !lower.contains("fail");
                if ok && lower.contains("daemon") {
                    daemon = true;
                }
                if ok && lower.contains("extension") {
                    extension = true;
                }
            }
        }
        (daemon, extension)
    }
}

impl Default for BskCliBackend {
    fn default() -> Self {
        Self::new()
    }
}

impl BrowserSkillBackend for BskCliBackend {
    fn health(&self) -> Result<String, String> {
        let _start = Instant::now();
        let output = self
            .bsk_command(&["--version".to_string()])
            .output()
            .map_err(|e| format!("bsk 不可用 (未安装或未在 PATH 中): {}", e))?;
        if !output.status.success() {
            return Err(format!(
                "bsk --version 失败 ({}): {}",
                output.status,
                String::from_utf8_lossy(&output.stderr)
            ));
        }
        let version = String::from_utf8_lossy(&output.stdout).trim().to_string();
        Ok(version)
    }

    fn exec(&self, action: BrowserSkillAction) -> Result<BrowserSkillResult, String> {
        let args = action.to_bsk_args();
        let start = Instant::now();
        let output = self
            .bsk_command(&args)
            .output()
            .map_err(|e| format!("bsk 执行失败: {}", e))?;
        let latency_ms = start.elapsed().as_millis() as u64;
        let stdout = String::from_utf8_lossy(&output.stdout).to_string();
        let stderr = String::from_utf8_lossy(&output.stderr).to_string();
        let exit_code = output.status.code().unwrap_or(-1);
        Ok(BrowserSkillResult {
            success: output.status.success(),
            stdout,
            stderr,
            exit_code,
            latency_ms,
        })
    }

    fn ensure_installed(&self) -> Result<(), String> {
        // Already runnable? Skip the network installer entirely.
        if self.health().is_ok() {
            return Ok(());
        }
        // Run the official one-line installer via the platform shell.
        #[cfg(windows)]
        let status = Command::new("powershell")
            .args([
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-Command",
                BSK_INSTALL_COMMAND,
            ])
            .status();
        #[cfg(not(windows))]
        let status = Command::new("sh")
            .arg("-c")
            .arg(BSK_INSTALL_COMMAND)
            .status();

        match status {
            Ok(s) if s.success() => {}
            Ok(s) => return Err(format!("bsk 安装脚本退出码非零: {}", s)),
            Err(e) => return Err(format!("无法运行 bsk 安装脚本: {}", e)),
        }
        // Re-check now that the installer has run.
        self.health()
            .map(|_| ())
            .map_err(|e| format!("bsk 安装后仍不可用: {}", e))
    }

    fn status(&self) -> BrowserSkillStatus {
        match self.health() {
            Ok(version) => {
                let (daemon, extension) = self.doctor();
                BrowserSkillStatus {
                    cli_installed: true,
                    cli_version: Some(version),
                    daemon_running: daemon,
                    extension_connected: extension,
                    needs_setup: false,
                    needs_extension: !extension,
                    extension_store_url: BSK_EXTENSION_STORE_URL.to_string(),
                    error: None,
                }
            }
            Err(e) => BrowserSkillStatus {
                cli_installed: false,
                cli_version: None,
                daemon_running: false,
                extension_connected: false,
                needs_setup: true,
                needs_extension: false,
                extension_store_url: BSK_EXTENSION_STORE_URL.to_string(),
                error: Some(e),
            },
        }
    }
}

#[cfg(test)]
mod tests {
    use crate::pc_automation::browser_skill::types::BrowserSkillAction;

    #[test]
    fn maps_navigate_to_bsk_args() {
        let a = BrowserSkillAction::Navigate {
            url: "https://example.com".into(),
        };
        assert_eq!(a.to_bsk_args(), vec!["navigate", "--url", "https://example.com"]);
    }

    #[test]
    fn maps_type_to_bsk_args() {
        let a = BrowserSkillAction::Type {
            selector: ".login".into(),
            text: "hello".into(),
        };
        assert_eq!(
            a.to_bsk_args(),
            vec!["input", "--selector", ".login", "--value", "hello"]
        );
    }

    #[test]
    fn maps_run_skill_with_params() {
        let mut params = std::collections::HashMap::new();
        params.insert("title".to_string(), "demo".to_string());
        let a = BrowserSkillAction::RunSkill {
            name: "xhs-auto-post".into(),
            params,
        };
        assert_eq!(a.to_bsk_args(), vec!["run", "xhs-auto-post", "--title", "demo"]);
    }

    #[test]
    fn raw_passthrough_is_verbatim() {
        let a = BrowserSkillAction::Raw {
            args: vec!["custom".into(), "--flag".into()],
        };
        assert_eq!(a.to_bsk_args(), vec!["custom", "--flag"]);
    }
}
