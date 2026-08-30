// Permission sandbox — lightweight security for skill execution.
//
// Validates that a skill has the required permissions before executing
// each step. Inspired by safeopcapp's permissions frontmatter.

use crate::skill::manifest::{ExecAction, Permission, SkillManifest};

/// Permission violation error.
#[derive(Debug, thiserror::Error)]
pub enum SandboxError {
    #[error("Permission denied: skill '{skill}' requires '{permission:?}' but it is not granted")]
    Denied {
        skill: String,
        permission: Permission,
    },

    #[error("Blocked path: '{path}' is outside allowed directories")]
    PathBlocked { path: String },

    #[error("Network blocked: '{url}' is not in the allowlist")]
    NetworkBlocked { url: String },
}

pub type Result<T> = std::result::Result<T, SandboxError>;

/// Permission checker for skill execution.
pub struct Sandbox {
    /// Granted permissions (from user or default policy).
    granted: Vec<Permission>,
    /// Allowed URL prefixes for HTTP requests (empty = allow all).
    network_allowlist: Vec<String>,
    /// Allowed path prefixes for file operations (empty = allow all).
    path_allowlist: Vec<String>,
}

impl Sandbox {
    /// Create a sandbox with the given granted permissions.
    pub fn new(granted: Vec<Permission>) -> Self {
        Self {
            granted,
            network_allowlist: Vec::new(),
            path_allowlist: Vec::new(),
        }
    }

    /// Create a permissive sandbox (grant all permissions).
    pub fn permissive() -> Self {
        Self {
            granted: vec![
                Permission::Shell,
                Permission::FileRead,
                Permission::FileWrite,
                Permission::FileSearch,
                Permission::FileReplace,
                Permission::DirList,
                Permission::HttpGet,
                Permission::HttpFetch,
            ],
            network_allowlist: Vec::new(),
            path_allowlist: Vec::new(),
        }
    }

    /// Add a network allowlist prefix.
    pub fn allow_url(mut self, prefix: &str) -> Self {
        self.network_allowlist.push(prefix.to_string());
        self
    }

    /// Add a path allowlist prefix.
    pub fn allow_path(mut self, prefix: &str) -> Self {
        self.path_allowlist.push(prefix.to_string());
        self
    }

    /// Check if a permission is granted.
    pub fn has_permission(&self, perm: &Permission) -> bool {
        self.granted.contains(perm)
    }

    /// Validate a single step's permission requirement.
    pub fn check_step(&self, skill_name: &str, action: &ExecAction) -> Result<()> {
        let required = match action {
            ExecAction::Shell { .. } => Permission::Shell,
            ExecAction::FileRead { .. } => Permission::FileRead,
            ExecAction::FileWrite { .. } => Permission::FileWrite,
            ExecAction::FileSearch { .. } => Permission::FileSearch,
            ExecAction::FileReplace { .. } => Permission::FileReplace,
            ExecAction::DirList { .. } => Permission::DirList,
            ExecAction::HttpGet { .. } => Permission::HttpGet,
            ExecAction::Wait { .. } | ExecAction::Echo { .. } => return Ok(()),
        };

        if !self.has_permission(&required) {
            return Err(SandboxError::Denied {
                skill: skill_name.to_string(),
                permission: required,
            });
        }

        // Check network allowlist for HTTP actions.
        if let ExecAction::HttpGet { url } = action {
            if !self.network_allowlist.is_empty() {
                let allowed = self.network_allowlist.iter().any(|prefix| url.starts_with(prefix));
                if !allowed {
                    return Err(SandboxError::NetworkBlocked { url: url.clone() });
                }
            }
        }

        // Check path allowlist for file actions.
        match action {
            ExecAction::FileRead { path }
            | ExecAction::FileWrite { path, .. }
            | ExecAction::FileSearch { path, .. }
            | ExecAction::FileReplace { path, .. }
            | ExecAction::DirList { path, .. } => {
                if !self.path_allowlist.is_empty() {
                    let allowed = self.path_allowlist.iter().any(|prefix| path.starts_with(prefix));
                    if !allowed {
                        return Err(SandboxError::PathBlocked { path: path.clone() });
                    }
                }
            }
            _ => {}
        }

        Ok(())
    }

    /// Validate all steps in a manifest against granted permissions.
    pub fn validate_manifest(&self, manifest: &SkillManifest) -> Result<()> {
        for step in &manifest.steps {
            if let Some(exec) = &step.exec {
                self.check_step(&manifest.name, exec)?;
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::skill::manifest::SkillManifest;

    #[test]
    fn permissive_allows_all() {
        let sandbox = Sandbox::permissive();
        let yaml = r#"
name: test
preferred_execution_type: system_software
software_name: cmd.exe
steps:
  - id: s1
    exec:
      type: shell
      command: echo
      args: ["hello"]
  - id: s2
    exec:
      type: file_read
      path: /tmp/test.txt
  - id: s3
    exec:
      type: http_get
      url: "https://example.com"
"#;
        let m = SkillManifest::from_yaml(yaml).unwrap();
        assert!(sandbox.validate_manifest(&m).is_ok());
    }

    #[test]
    fn restricted_denies_shell() {
        let sandbox = Sandbox::new(vec![Permission::FileRead]);
        let yaml = r#"
name: test
preferred_execution_type: system_software
software_name: cmd.exe
steps:
  - id: s1
    exec:
      type: shell
      command: echo
      args: ["hello"]
"#;
        let m = SkillManifest::from_yaml(yaml).unwrap();
        assert!(sandbox.validate_manifest(&m).is_err());
    }

    #[test]
    fn network_allowlist_blocks() {
        let sandbox = Sandbox::permissive().allow_url("https://safe.com/");
        let yaml = r#"
name: test
preferred_execution_type: system_software
software_name: cmd.exe
steps:
  - id: s1
    exec:
      type: http_get
      url: "https://evil.com/data"
"#;
        let m = SkillManifest::from_yaml(yaml).unwrap();
        assert!(sandbox.validate_manifest(&m).is_err());
    }

    #[test]
    fn network_allowlist_allows() {
        let sandbox = Sandbox::permissive().allow_url("https://safe.com/");
        let yaml = r#"
name: test
preferred_execution_type: system_software
software_name: cmd.exe
steps:
  - id: s1
    exec:
      type: http_get
      url: "https://safe.com/api/data"
"#;
        let m = SkillManifest::from_yaml(yaml).unwrap();
        assert!(sandbox.validate_manifest(&m).is_ok());
    }
}
