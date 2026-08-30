// Filesystem skill loader — hot-load skills from ~/.dsh/skills/ directory.
//
// Inspired by safeopcapp's skill market + local skill structure.
// Each skill is a directory with a SKILL.md file.

use std::fs;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::skill::manifest::SkillManifest;

/// A skill loaded from the filesystem.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FileSystemSkill {
    /// The slug/name used in the directory path (used for uninstall).
    pub name: String,
    /// Absolute path to the skill directory.
    pub path: PathBuf,
    /// Parsed manifest.
    pub manifest: SkillManifest,
    /// Raw YAML content.
    pub yaml: String,
}

/// Skill loader that scans ~/.dsh/skills/ for installed skills.
pub struct SkillLoader {
    skills_dir: PathBuf,
}

impl SkillLoader {
    /// Create a new loader with the default skills directory.
    pub fn new() -> Self {
        let home = dirs::home_dir().unwrap_or_else(|| PathBuf::from("."));
        Self {
            skills_dir: home.join(".dsh").join("skills"),
        }
    }

    /// Create with a custom path (for testing).
    pub fn with_dir(path: PathBuf) -> Self {
        Self { skills_dir: path }
    }

    /// Get the skills directory path.
    pub fn skills_dir(&self) -> &Path {
        &self.skills_dir
    }

    /// Ensure the skills directory exists.
    pub fn ensure_dir(&self) -> std::io::Result<()> {
        fs::create_dir_all(&self.skills_dir)
    }

    /// Scan and load all skills from the directory.
    pub fn load_all(&self) -> Vec<FileSystemSkill> {
        let mut skills = Vec::new();
        if !self.skills_dir.exists() {
            return skills;
        }

        if let Ok(entries) = fs::read_dir(&self.skills_dir) {
            for entry in entries.flatten() {
                let path = entry.path();
                if path.is_dir() {
                    if let Some(skill) = self.load_from_dir(&path) {
                        skills.push(skill);
                    }
                }
            }
        }
        skills
    }

    /// Load a single skill from a directory.
    fn load_from_dir(&self, dir: &Path) -> Option<FileSystemSkill> {
        // Look for SKILL.md or <dirname>.yaml or manifest.yaml
        let candidates = [
            dir.join("SKILL.md"),
            dir.join("manifest.yaml"),
            dir.join(format!("{}.yaml", dir.file_name()?.to_str()?)),
        ];

        for path in &candidates {
            if path.exists() {
                let name = dir.file_name()
                    .and_then(|n| n.to_str())
                    .unwrap_or("unknown")
                    .to_string();
                if let Some(skill) = self.load_from_file(path, dir, name) {
                    return Some(skill);
                }
            }
        }
        None
    }

    /// Load a skill from a specific YAML file.
    fn load_from_file(&self, file: &Path, dir: &Path, name: String) -> Option<FileSystemSkill> {
        let yaml = fs::read_to_string(file).ok()?;
        let manifest = SkillManifest::from_yaml(&yaml).ok()?;
        Some(FileSystemSkill {
            name,
            path: dir.to_path_buf(),
            manifest,
            yaml,
        })
    }

    /// Install a skill by writing YAML to a new directory.
    pub fn install(&self, name: &str, yaml: &str) -> std::io::Result<PathBuf> {
        self.ensure_dir()?;
        let slug = name.to_lowercase()
            .replace(|c: char| !c.is_alphanumeric() && c != ' ' && c != '-', "")
            .replace(' ', "-");
        let dir = self.skills_dir.join(&slug);
        fs::create_dir_all(&dir)?;
        fs::write(dir.join("SKILL.md"), yaml)?;
        Ok(dir)
    }

    /// Uninstall a skill by removing its directory.
    pub fn uninstall(&self, name: &str) -> std::io::Result<bool> {
        let slug = name.to_lowercase()
            .replace(|c: char| !c.is_alphanumeric() && c != ' ' && c != '-', "")
            .replace(' ', "-");
        let dir = self.skills_dir.join(&slug);
        if dir.exists() {
            fs::remove_dir_all(&dir)?;
            Ok(true)
        } else {
            Ok(false)
        }
    }
}

impl Default for SkillLoader {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    #[test]
    fn loader_with_temp_dir() {
        let temp = std::env::temp_dir().join("dsh-test-skills");
        let _ = fs::remove_dir_all(&temp);
        let loader = SkillLoader::with_dir(temp.clone());

        // Empty dir -> no skills
        let skills = loader.load_all();
        assert_eq!(skills.len(), 0);

        // Install a skill
        let yaml = r#"name: test-skill
description: Test
preferred_execution_type: system_software
software_name: cmd.exe
steps:
  - id: step1
    exec:
      type: echo
      message: "hello"
"#;
        let path = loader.install("test-skill", yaml).unwrap();
        assert!(path.exists());

        // Debug: verify file exists and can be read
        let skill_file = path.join("SKILL.md");
        assert!(skill_file.exists(), "SKILL.md should exist at {:?}", skill_file);
        let yaml_content = fs::read_to_string(&skill_file).unwrap();
        assert!(yaml_content.contains("test-skill"), "SKILL.md should contain skill name");

        // Try parsing directly to see if it works
        let parse_result = crate::skill::manifest::SkillManifest::from_yaml(&yaml_content);
        assert!(parse_result.is_ok(), "YAML should parse: {:?}", parse_result.err());

        // Load it back
        let skills = loader.load_all();
        assert_eq!(skills.len(), 1);
        assert_eq!(skills[0].manifest.name, "test-skill");

        // Uninstall
        assert!(loader.uninstall("test-skill").unwrap());
        let skills = loader.load_all();
        assert_eq!(skills.len(), 0);

        // Cleanup
        let _ = fs::remove_dir_all(&temp);
    }

    #[test]
    fn loader_nonexistent_dir() {
        let loader = SkillLoader::with_dir(PathBuf::from("/nonexistent/path/that/doesnt/exist"));
        let skills = loader.load_all();
        assert_eq!(skills.len(), 0);
    }
}
