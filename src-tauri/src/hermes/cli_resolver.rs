// Copyright (c) 2026 tupAI
//
// CLI tool resolver — find local executables by name using multiple strategies.
//
// Resolution order:
//   1. PATH via `which` (Unix) / `where` (Windows)
//   2. Windows App Paths registry (HKLM/HKCU\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\)
//   3. Common install directories (Program Files, Program Files (x86), user-local appdata)
//   4. Package manager shim dirs (scoop, winget, choco)
//
// Results are cached in-memory; TTL controlled externally.

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::PathBuf;
use std::time::{Duration, Instant};

/// Result of resolving a CLI tool.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CliToolInfo {
    /// Canonical command name (e.g. "git", "ffmpeg")
    pub name: String,
    /// Resolved executable path
    pub path: Option<String>,
    /// Source of the resolution
    pub source: String,
    /// Minimized version string (best-effort)
    pub version: Option<String>,
    /// Installation hint for missing tools
    pub install_hint: Option<String>,
}

/// In-memory cache entry.
pub struct CacheEntry {
    info: CliToolInfo,
    expires_at: Instant,
}

/// Global resolver with caching.
pub struct CliResolver {
    pub cache: HashMap<String, CacheEntry>,
    pub ttl: Duration,
}

impl CliResolver {
    pub fn new(ttl: Duration) -> Self {
        Self {
            cache: HashMap::new(),
            ttl,
        }
    }

    pub fn default() -> Self {
        Self::new(Duration::from_secs(30 * 60)) // 30 minutes
    }

    /// Resolve a CLI tool by name. Checks cache first.
    pub fn resolve(&mut self, name: &str) -> CliToolInfo {
        // Check cache
        if let Some(entry) = self.cache.get(name) {
            if entry.expires_at.elapsed() < self.ttl {
                return entry.info.clone();
            }
        }

        let info = self.resolve_inner(name);

        // Cache result
        self.cache.insert(name.to_string(), CacheEntry {
            info: info.clone(),
            expires_at: Instant::now() + self.ttl,
        });

        info
    }

    /// Internal resolution logic (no cache).
    fn resolve_inner(&self, name: &str) -> CliToolInfo {
        let name_lower = name.to_lowercase();

        // Strategy 1: PATH lookup
        if let Some((path, source)) = self.lookup_path(&name_lower) {
            let version = self.try_get_version(&path, name);
            return CliToolInfo {
                name: name.to_string(),
                path: Some(path.to_string_lossy().to_string()),
                source,
                version,
                install_hint: Self::default_install_hint(name),
            };
        }

        // Strategy 2: Windows App Paths registry
        #[cfg(target_os = "windows")]
        if let Some(path) = self.lookup_app_paths(&name_lower) {
            let version = self.try_get_version(&path, name);
            return CliToolInfo {
                name: name.to_string(),
                path: Some(path.to_string_lossy().to_string()),
                source: "app_paths".to_string(),
                version,
                install_hint: Self::default_install_hint(name),
            };
        }

        // Strategy 3: Common install directories
        #[cfg(target_os = "windows")]
        if let Some(path) = self.lookup_common_dirs(&name_lower) {
            let version = self.try_get_version(&path, name);
            return CliToolInfo {
                name: name.to_string(),
                path: Some(path.to_string_lossy().to_string()),
                source: "common_dirs".to_string(),
                version,
                install_hint: Self::default_install_hint(name),
            };
        }

        // Not found
        CliToolInfo {
            name: name.to_string(),
            path: None,
            source: "not_found".to_string(),
            version: None,
            install_hint: Self::default_install_hint(name),
        }
    }

    /// Strategy 1: Look up in PATH.
    fn lookup_path(&self, name: &str) -> Option<(PathBuf, String)> {
        // On Windows, try both `name.exe` and `name`
        #[cfg(target_os = "windows")]
        {
            let candidates = [
                format!("{}.exe", name),
                name.to_string(),
                format!("{}.cmd", name),
                format!("{}.bat", name),
            ];
            for candidate in &candidates {
                let mut cmd = std::process::Command::new("where");
                crate::commands::legacy::apply_no_window(&mut cmd);
                if let Ok(output) = cmd.arg(candidate).output()
                {
                    if output.status.success() {
                        let stdout = String::from_utf8_lossy(&output.stdout);
                        for line in stdout.lines() {
                            let path = PathBuf::from(line.trim());
                            if path.exists() {
                                return Some((path, "path_where".to_string()));
                            }
                        }
                    }
                }
            }
        }

        // On Unix, use `which`
        #[cfg(not(target_os = "windows"))]
        {
            if let Ok(output) = std::process::Command::new("which")
                .arg(name)
                .output()
            {
                if output.status.success() {
                    let stdout = String::from_utf8_lossy(&output.stdout);
                    let path = PathBuf::from(stdout.trim());
                    if path.exists() {
                        return Some((path, "path_which".to_string()));
                    }
                }
            }
        }

        // Fallback: try spawning directly (uses OS PATH resolution)
        let mut fallback_cmd = std::process::Command::new(name);
        crate::commands::legacy::apply_no_window(&mut fallback_cmd);
        match fallback_cmd
            .arg("--version")
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .spawn()
        {
            Ok(mut child) => {
                let _ = child.kill();
                // If we got here, the OS found it in PATH.
                // We can't easily get the exact path without which/where,
                // so return a synthetic path.
                Some((PathBuf::from(format!("<PATH>/{}", name)), "path_spawn".to_string()))
            }
            Err(_) => None,
        }
    }

    /// Strategy 2 (Windows): App Paths registry.
    #[cfg(target_os = "windows")]
    fn lookup_app_paths(&self, name: &str) -> Option<PathBuf> {
        use winreg::enums::{HKEY_LOCAL_MACHINE, HKEY_CURRENT_USER};
        use winreg::RegKey;

        let hives = [HKEY_LOCAL_MACHINE, HKEY_CURRENT_USER];
        for hive in &hives {
            let root = RegKey::predef(*hive);
            let app_paths = match root.open_subkey("SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths") {
                Ok(k) => k,
                Err(_) => continue,
            };

            // Try exact name + .exe
            for suffix in &["", ".exe"] {
                let key_name = format!("{}{}", name, suffix);
                if let Ok(subkey) = app_paths.open_subkey(&key_name) {
                    if let Ok(default_val) = subkey.get_value::<String, _>("") {
                        let path = PathBuf::from(&default_val);
                        if path.exists() {
                            return Some(path);
                        }
                    }
                }
            }
        }
        None
    }

    /// Strategy 3 (Windows): Common install directories.
    #[cfg(target_os = "windows")]
    fn lookup_common_dirs(&self, name: &str) -> Option<PathBuf> {
        let program_files = std::env::var("PROGRAMFILES").ok();
        let program_files_x86 = std::env::var("PROGRAMFILES(X86)").ok();
        let local_appdata = std::env::var("LOCALAPPDATA").ok();

        let candidates = [
            program_files.as_deref(),
            program_files_x86.as_deref(),
            local_appdata.as_deref(),
        ];

        for dir in candidates.into_iter().flatten() {
            if dir.is_empty() { continue; }
            let base = PathBuf::from(dir);

            // Direct executable in dir (e.g. C:\Program Files\git\cmd\git.exe)
            let git_cmd = base.join("git").join("cmd").join(format!("{}.exe", name));
            if git_cmd.exists() { return Some(git_cmd); }

            let git_bin = base.join("git").join("bin").join(format!("{}.exe", name));
            if git_bin.exists() { return Some(git_bin); }

            // Common pattern: <dir>\<name>\bin\<name>.exe
            let bin_exe = base.join(name).join("bin").join(format!("{}.exe", name));
            if bin_exe.exists() { return Some(bin_exe); }

            // Common pattern: <dir>\<name>\<name>.exe
            let direct_exe = base.join(name).join(format!("{}.exe", name));
            if direct_exe.exists() { return Some(direct_exe); }
        }

        None
    }

    /// Best-effort version detection.
    fn try_get_version(&self, path: &PathBuf, cmd: &str) -> Option<String> {
        let mut version_cmd = std::process::Command::new(path);
        crate::commands::legacy::apply_no_window(&mut version_cmd);
        let output = version_cmd.arg("--version").output();

        if let Ok(out) = output {
            let stdout = String::from_utf8_lossy(&out.stdout);
            // Extract first line that looks like a version
            for line in stdout.lines() {
                let trimmed = line.trim();
                if !trimmed.is_empty() {
                    return Some(trimmed.to_string());
                }
            }
        }

        // Fallback: try `cmd /?` or `cmd -h`
        for arg in &["/?", "-h", "--help"] {
            let mut help_cmd = std::process::Command::new(cmd);
            crate::commands::legacy::apply_no_window(&mut help_cmd);
            if let Ok(out) = help_cmd.arg(arg).output()
            {
                let stderr = String::from_utf8_lossy(&out.stderr);
                let stdout = String::from_utf8_lossy(&out.stdout);
                let combined = format!("{}{}", stdout, stderr);
                for line in combined.lines() {
                    let trimmed = line.trim();
                    if trimmed.to_lowercase().contains("version") && !trimmed.is_empty() {
                        return Some(trimmed.to_string());
                    }
                }
            }
        }

        None
    }

    /// Default install hints per well-known tool.
    pub fn default_install_hint(name: &str) -> Option<String> {
        let hints: HashMap<&str, &str> = [
            ("git", "winget install Git.Git"),
            ("ffmpeg", "winget install FFmpeg.FFMpeg"),
            ("python", "winget install Python.Python.3.12"),
            ("node", "winget install OpenJS.NodeJS.LTS"),
            ("npm", "winget install OpenJS.NodeJS.LTS"),
            ("pip", "winget install Python.Python.3.12"),
            ("cargo", "winget install Rustlang.Rustup"),
            ("go", "winget install GoLang.Go"),
            ("docker", "winget install Docker.DockerDesktop"),
            ("curl", "winget install curl"),
            ("wget", "winget install GNUWin32.Wget"),
            ("7z", "winget install 7zip.7zip"),
            ("tar", "winget install GNUWin32.Tar"),
            ("make", "winget install GnuMake.GnuMake"),
            ("cmake", "winget install CMake.CMake"),
            ("java", "winget install AdoptOpenJDK.JRE"),
            ("dotnet", "winget install Microsoft.DotNet.SDK"),
            ("powershell", "winget install Microsoft.PowerShell"),
            ("code", "winget install Microsoft.VisualStudioCode"),
            ("robocopy", "Already included in Windows"),
            ("schtasks", "Already included in Windows"),
            ("reg", "Already included in Windows"),
            ("icacls", "Already included in Windows"),
        ]
        .into_iter()
        .collect();

        hints.get(name).map(|s| s.to_string())
    }
}

/// Resolve a single CLI tool. Convenience function using default resolver.
pub fn resolve_cli_tool(name: &str) -> CliToolInfo {
    let mut resolver = CliResolver::default();
    resolver.resolve(name)
}

/// Resolve multiple CLI tools (batch).
pub fn resolve_cli_batch(names: &[&str]) -> Vec<CliToolInfo> {
    let mut resolver = CliResolver::default();
    names.iter().map(|n| resolver.resolve(n)).collect()
}

/// Check if a skill's cli_deps are satisfied.
pub fn check_skill_cli_deps(cli_deps: &[CliDep]) -> Vec<CliToolInfo> {
    let mut resolver = CliResolver::default();
    cli_deps.iter().map(|dep| {
        let info = resolver.resolve(&dep.name);
        CliToolInfo {
            name: dep.name.clone(),
            path: info.path,
            source: info.source,
            version: info.version.or(dep.min_version.clone()),
            install_hint: dep.install_hint.clone().or(CliResolver::default_install_hint(&dep.name)),
        }
    }).collect()
}

/// Declared CLI dependency in skill manifest.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CliDep {
    pub name: String,
    pub min_version: Option<String>,
    pub install_hint: Option<String>,
}
