// Skill compiler — adapted from safeopcapp.
//
// Compiles a SKILL.md into a structured SkillManifest and validates it.
// Also provides decompilation for debugging.

use super::manifest::SkillManifest;

/// Errors that can occur during compilation.
#[derive(Debug, thiserror::Error)]
pub enum CompileError {
    #[error("YAML parse error: {0}")]
    Yaml(String),

    #[error("Validation error: {0}")]
    Validation(String),

    #[error("Missing field: {0}")]
    MissingField(String),
}

/// Compile a SKILL.md string into a validated SkillManifest.
pub fn compile(skill_md: &str) -> Result<SkillManifest, CompileError> {
    let manifest = SkillManifest::from_yaml(skill_md)
        .map_err(CompileError::Yaml)?;
    manifest.validate().map_err(CompileError::Validation)?;
    Ok(manifest)
}

/// Decompile a SkillManifest back to SKILL.md string.
pub fn decompile(manifest: &SkillManifest) -> Result<String, CompileError> {
    manifest.to_yaml().map_err(CompileError::Yaml)
}

/// Quick-validate a SKILL.md without full compilation.
pub fn validate(skill_md: &str) -> Result<(), CompileError> {
    let manifest = SkillManifest::from_yaml(skill_md)
        .map_err(CompileError::Yaml)?;
    manifest.validate().map_err(CompileError::Validation)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn compile_valid_skill() {
        let md = r#"
name: test-skill
description: A test skill
preferred_execution_type: system_software
software_name: notepad.exe
steps:
  - id: step1
    description: Do something
"#;
        let result = compile(md);
        assert!(result.is_ok());
        let manifest = result.unwrap();
        assert_eq!(manifest.name, "test-skill");
    }

    #[test]
    fn compile_invalid_skill_no_name() {
        let md = r#"
description: no name
preferred_execution_type: system_software
software_name: notepad.exe
steps:
  - id: s1
    description: x
"#;
        assert!(compile(md).is_err());
    }
}
