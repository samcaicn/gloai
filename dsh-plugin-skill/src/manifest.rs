// Skill manifest — adapted from safeopcapp skill::manifest.
//
// Describes a skill's metadata and execution steps. Can be serialized
// to/from YAML (SKILL.md body format) and validated.
//
// Two action types coexist:
//   - InputAction  : UI automation (click/type/hotkey/wait) for browser/software control
//   - ExecAction   : Programmatic execution (shell/file/wait) for the execution engine

use serde::{Deserialize, Serialize};

/// Permission categories for skill sandboxing.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum Permission {
    #[default]
    Shell,
    FileRead,
    FileWrite,
    FileSearch,
    FileReplace,
    DirList,
    HttpGet,
    HttpFetch,
}

/// Skill category for organization.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum SkillCategory {
    #[default]
    Web,
    Desktop,
    Mobile,
    Data,
    Misc,
}

impl std::fmt::Display for SkillCategory {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SkillCategory::Web => write!(f, "web"),
            SkillCategory::Desktop => write!(f, "desktop"),
            SkillCategory::Mobile => write!(f, "mobile"),
            SkillCategory::Data => write!(f, "data"),
            SkillCategory::Misc => write!(f, "misc"),
        }
    }
}

/// How the automation engine should drive a step.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum ExecutionType {
    #[default]
    SystemSoftware,
    Browser,
}

/// A single automation step.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Step {
    pub id: String,
    #[serde(default)]
    pub description: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub dom_selector: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub visual_target: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub input: Option<InputAction>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub exec: Option<ExecAction>,
}

/// UI automation action (for browser/software control).
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum InputAction {
    Click { x: i32, y: i32 },
    Type { text: String },
    Hotkey { keys: String },
    Wait { ms: u64 },
}

impl Default for InputAction {
    fn default() -> Self {
        InputAction::Wait { ms: 0 }
    }
}

/// Programmatic execution action (for the execution engine).
///
/// These actions can be executed directly by the Rust engine without
/// needing UI automation. This is what makes SKILL.md "actually run".
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ExecAction {
    /// Execute a shell command with arguments.
    Shell {
        command: String,
        #[serde(default)]
        args: Vec<String>,
        #[serde(default)]
        cwd: Option<String>,
    },
    /// Read file content.
    FileRead { path: String },
    /// Write content to a file (creates parent dirs if needed).
    FileWrite { path: String, content: String },
    /// Search for a regex pattern in a file, return matching lines.
    FileSearch { path: String, pattern: String },
    /// Replace text in a file.
    FileReplace {
        path: String,
        from: String,
        #[serde(default)]
        to: Option<String>,
    },
    /// List directory contents.
    DirList {
        path: String,
        #[serde(default)]
        recursive: bool,
    },
    /// Wait for a specified duration.
    Wait { ms: u64 },
    /// Make an HTTP GET request.
    HttpGet { url: String },
    /// Print a message (for debugging / user feedback).
    Echo { message: String },
}

impl Default for ExecAction {
    fn default() -> Self {
        ExecAction::Wait { ms: 0 }
    }
}

/// A capability this skill provides (safeopcapp-inspired).
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Capability {
    pub id: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub description: String,
}

/// Runtime requirements for this skill (safeopcapp-inspired).
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Runtime {
    /// Engine that executes this skill: "yaml" (native) | "js" | "wasm".
    #[serde(default = "default_engine")]
    pub engine: String,
    /// Required capabilities (cap.* names from safeopcapp).
    #[serde(default)]
    pub caps: Vec<String>,
}

fn default_engine() -> String {
    "yaml".to_string()
}

/// The full skill manifest (aligned with safeopcapp SKILL.md standard).
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct SkillManifest {
    /// Unique identifier (reverse domain, e.g. "com.dsh.skills.echo-sample").
    #[serde(default)]
    pub id: Option<String>,
    pub name: String,
    #[serde(default)]
    pub description: Option<String>,
    /// Semantic version.
    #[serde(default)]
    pub version: Option<String>,
    /// Skill category for organization.
    #[serde(default)]
    pub category: SkillCategory,
    /// Tags for search.
    #[serde(default)]
    pub tags: Vec<String>,
    pub preferred_execution_type: ExecutionType,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub software_name: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub browser_url: Option<String>,
    /// Permissions required by this skill.
    #[serde(default)]
    pub permissions: Vec<Permission>,
    /// Capabilities this skill provides.
    #[serde(default)]
    pub capabilities: Vec<Capability>,
    /// Runtime requirements.
    #[serde(default)]
    pub runtime: Runtime,
    /// What this skill can open (web URLs, app names) — safeopcapp inspired.
    #[serde(default)]
    pub opens: Vec<String>,
    pub steps: Vec<Step>,
}

impl SkillManifest {
    /// Derive id from name if not explicitly set.
    pub fn canonical_id(&self) -> String {
        self.id.clone().unwrap_or_else(|| {
            let slug = self.name.to_lowercase()
                .replace(|c: char| !c.is_alphanumeric() && c != ' ', "")
                .replace(' ', "-");
            format!("com.dsh.skills.{}", slug)
        })
    }

    /// Derive permissions from exec actions if not explicitly declared.
    pub fn effective_permissions(&self) -> Vec<Permission> {
        if !self.permissions.is_empty() {
            return self.permissions.clone();
        }
        let mut perms = Vec::new();
        for step in &self.steps {
            if let Some(exec) = &step.exec {
                let perm = match exec {
                    ExecAction::Shell { .. } => Permission::Shell,
                    ExecAction::FileRead { .. } => Permission::FileRead,
                    ExecAction::FileWrite { .. } => Permission::FileWrite,
                    ExecAction::FileSearch { .. } => Permission::FileSearch,
                    ExecAction::FileReplace { .. } => Permission::FileReplace,
                    ExecAction::DirList { .. } => Permission::DirList,
                    ExecAction::HttpGet { .. } => Permission::HttpGet,
                    _ => continue,
                };
                if !perms.contains(&perm) {
                    perms.push(perm);
                }
            }
        }
        perms
    }

    /// Validate cross-field invariants.
    pub fn validate(&self) -> Result<(), String> {
        if self.name.trim().is_empty() {
            return Err("skill name is required".to_string());
        }
        if self.steps.is_empty() {
            return Err(format!("skill '{}' has no steps", self.name));
        }
        match self.preferred_execution_type {
            ExecutionType::SystemSoftware => {
                if self.software_name.as_deref().unwrap_or("").is_empty() {
                    return Err(format!(
                        "skill '{}' is SystemSoftware but software_name is missing",
                        self.name
                    ));
                }
            }
            ExecutionType::Browser => {
                if self.browser_url.as_deref().unwrap_or("").is_empty() {
                    return Err(format!(
                        "skill '{}' is Browser but browser_url is missing",
                        self.name
                    ));
                }
            }
        }
        Ok(())
    }

    /// Parse from a YAML string (SKILL.md body).
    pub fn from_yaml(source: &str) -> Result<Self, String> {
        serde_yaml::from_str::<SkillManifest>(source)
            .map_err(|e| format!("invalid skill YAML: {}", e))
    }

    /// Serialize to canonical YAML.
    pub fn to_yaml(&self) -> Result<String, String> {
        serde_yaml::to_string(self).map_err(|e| format!("yaml serialize failed: {}", e))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn validate_rejects_empty_steps() {
        let m = SkillManifest {
            name: "test".into(),
            steps: vec![],
            ..Default::default()
        };
        assert!(m.validate().is_err());
    }

    #[test]
    fn yaml_round_trip() {
        let m = SkillManifest {
            name: "open-notepad".into(),
            description: Some("Launch notepad".into()),
            preferred_execution_type: ExecutionType::SystemSoftware,
            software_name: Some("notepad.exe".into()),
            steps: vec![Step {
                id: "launch".into(),
                description: "Launch notepad".into(),
                ..Default::default()
            }],
            ..Default::default()
        };
        let yaml = m.to_yaml().unwrap();
        let m2 = SkillManifest::from_yaml(&yaml).unwrap();
        assert_eq!(m2.name, m.name);
        assert_eq!(m2.preferred_execution_type, m.preferred_execution_type);
    }

    #[test]
    fn exec_action_shell_round_trip() {
        let yaml = r#"
name: test-shell
description: Test shell execution
preferred_execution_type: system_software
software_name: cmd.exe
steps:
  - id: run-echo
    description: Run echo command
    exec:
      type: shell
      command: echo
      args: ["hello", "world"]
"#;
        let m = SkillManifest::from_yaml(yaml).unwrap();
        assert_eq!(m.steps.len(), 1);
        let exec = m.steps[0].exec.as_ref().unwrap();
        match exec {
            ExecAction::Shell { command, args, .. } => {
                assert_eq!(command, "echo");
                assert_eq!(args, &vec!["hello".to_string(), "world".to_string()]);
            }
            _ => panic!("expected Shell action"),
        }
    }

    #[test]
    fn exec_action_file_round_trip() {
        let yaml = r#"
name: test-file
description: Test file operations
preferred_execution_type: system_software
software_name: notepad.exe
steps:
  - id: read-config
    description: Read config file
    exec:
      type: file_read
      path: /tmp/config.txt
  - id: write-output
    description: Write output
    exec:
      type: file_write
      path: /tmp/output.txt
      content: "hello"
"#;
        let m = SkillManifest::from_yaml(yaml).unwrap();
        assert_eq!(m.steps.len(), 2);
        match m.steps[0].exec.as_ref().unwrap() {
            ExecAction::FileRead { path } => assert_eq!(path, "/tmp/config.txt"),
            _ => panic!("expected FileRead"),
        }
        match m.steps[1].exec.as_ref().unwrap() {
            ExecAction::FileWrite { path, content } => {
                assert_eq!(path, "/tmp/output.txt");
                assert_eq!(content, "hello");
            }
            _ => panic!("expected FileWrite"),
        }
    }
}
