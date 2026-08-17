//! Ordered system sections, dynamic context, tool schemas, and prompt variables.

use std::collections::HashMap;
use std::sync::Arc;

use dsh_core_types::ToolSchema;
use dsh_events::Disposer;
use parking_lot::RwLock;
use thiserror::Error;

pub const IDENTITY: &str = "You are an AI agent powered by DeepSeek Harness.";
pub const IDENTITY_ORDER: i32 = -100;
pub const PERSONA_ORDER: i32 = 0;

#[derive(Debug, Error)]
pub enum PromptError {
    #[error("duplicate prompt section `{0}`")]
    DuplicateSection(String),
    #[error("unknown prompt variable `{0}` in {1}")]
    UnknownVariable(String, &'static str),
    #[error("malformed prompt variable in {0}")]
    MalformedVariable(&'static str),
    #[error("more than one complete system-prompt section is registered")]
    MultipleComplete,
}

#[derive(Clone)]
pub struct PromptSection {
    pub name: String,
    pub order: i32,
    pub text: String,
    pub complete: bool,
}

#[derive(Clone)]
pub struct PromptContext {
    pub name: String,
    pub order: i32,
    pub text: String,
}

#[derive(Clone, Default)]
pub struct PromptAssembly {
    pub sections: Vec<PromptSection>,
    pub contexts: Vec<PromptContext>,
    pub tools: Vec<ToolSchema>,
    pub variables: HashMap<String, String>,
}

#[derive(Default)]
struct Inner {
    sections: Vec<PromptSection>,
    contexts: Vec<PromptContext>,
    tools: Vec<ToolSchema>,
    variables: HashMap<String, String>,
}

#[derive(Clone, Default)]
pub struct SystemPrompt {
    inner: Arc<RwLock<Inner>>,
}

impl SystemPrompt {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn with_identity_and_persona(persona: impl Into<String>) -> Self {
        let prompt = Self::new();
        let mut inner = prompt.inner.write();
        inner.sections.push(PromptSection {
            name: "identity".into(),
            order: IDENTITY_ORDER,
            text: IDENTITY.into(),
            complete: false,
        });
        inner.sections.push(PromptSection {
            name: "persona".into(),
            order: PERSONA_ORDER,
            text: persona.into(),
            complete: false,
        });
        drop(inner);
        prompt
    }

    pub fn section(&self, section: PromptSection) -> Result<Disposer, PromptError> {
        let mut inner = self.inner.write();
        if inner.sections.iter().any(|s| s.name == section.name) {
            return Err(PromptError::DuplicateSection(section.name));
        }
        let name = section.name.clone();
        inner.sections.push(section);
        let inner_ref = Arc::clone(&self.inner);
        Ok(Disposer::new(move || {
            inner_ref.write().sections.retain(|s| s.name != name);
        }))
    }

    pub fn context(&self, context: PromptContext) -> Disposer {
        let mut inner = self.inner.write();
        let name = context.name.clone();
        inner.contexts.retain(|c| c.name != name);
        inner.contexts.push(context);
        let inner_ref = Arc::clone(&self.inner);
        Disposer::new(move || {
            inner_ref.write().contexts.retain(|c| c.name != name);
        })
    }

    pub fn set_tools(&self, tools: Vec<ToolSchema>) {
        self.inner.write().tools = tools;
    }

    pub fn variable(&self, name: impl Into<String>, value: impl Into<String>) -> Disposer {
        let name = name.into();
        self.inner
            .write()
            .variables
            .insert(name.clone(), value.into());
        let inner_ref = Arc::clone(&self.inner);
        Disposer::new(move || {
            inner_ref.write().variables.remove(&name);
        })
    }

    pub fn assemble(&self) -> Result<PromptAssembly, PromptError> {
        let inner = self.inner.read();
        let mut sections = inner.sections.clone();
        sections.sort_by_key(|s| (s.order, s.name.clone()));
        let complete: Vec<_> = sections.iter().filter(|s| s.complete).cloned().collect();
        if complete.len() > 1 {
            return Err(PromptError::MultipleComplete);
        }
        if let Some(only) = complete.into_iter().next() {
            sections = vec![only];
        }
        let mut contexts = inner.contexts.clone();
        contexts.sort_by_key(|c| (c.order, c.name.clone()));
        Ok(PromptAssembly {
            sections,
            contexts,
            tools: inner.tools.clone(),
            variables: inner.variables.clone(),
        })
    }
}

pub fn render_prompt(assembly: &PromptAssembly) -> Result<String, PromptError> {
    let mut parts = Vec::new();
    for section in &assembly.sections {
        let text = interpolate(&section.text, &assembly.variables, "section")?;
        if !text.is_empty() {
            parts.push(text);
        }
    }
    Ok(parts.join("\n\n"))
}

pub fn render_context_sections(
    assembly: &PromptAssembly,
) -> Result<Vec<(String, String)>, PromptError> {
    let mut out = Vec::new();
    for context in &assembly.contexts {
        let text = interpolate(&context.text, &assembly.variables, "context")?;
        if !text.is_empty() {
            out.push((context.name.clone(), text));
        }
    }
    Ok(out)
}

pub fn join_context_sections(sections: &[(String, String)]) -> String {
    sections
        .iter()
        .map(|(name, text)| format!("<{name}>\n{text}\n</{name}>"))
        .collect::<Vec<_>>()
        .join("\n\n")
}

fn interpolate(
    text: &str,
    variables: &HashMap<String, String>,
    where_: &'static str,
) -> Result<String, PromptError> {
    let mut out = String::new();
    let mut rest = text;
    while let Some(start) = rest.find("{{") {
        let after = &rest[start + 2..];
        if let Some(end) = after.find("}}") {
            out.push_str(&rest[..start]);
            let key = &after[..end];
            if key.is_empty() || key.contains('{') {
                return Err(PromptError::MalformedVariable(where_));
            }
            let value = variables
                .get(key)
                .ok_or_else(|| PromptError::UnknownVariable(key.to_string(), where_))?;
            out.push_str(value);
            rest = &after[end + 2..];
        } else {
            out.push_str(rest);
            return Ok(out);
        }
    }
    out.push_str(rest);
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn identity_then_persona_then_tool_guidance() {
        let prompt = SystemPrompt::with_identity_and_persona("You are DeepSeek Harness.");
        let _keep = prompt
            .section(PromptSection {
                name: "tool:read".into(),
                order: 100,
                text: "Use read.".into(),
                complete: false,
            })
            .unwrap();
        let rendered = render_prompt(&prompt.assemble().unwrap()).unwrap();
        assert_eq!(
            rendered,
            format!("{IDENTITY}\n\nYou are DeepSeek Harness.\n\nUse read.")
        );
    }

    #[test]
    fn unknown_variable_fails_loud() {
        let prompt = SystemPrompt::new();
        let _keep = prompt
            .section(PromptSection {
                name: "x".into(),
                order: 0,
                text: "cwd: {{cwd}}".into(),
                complete: false,
            })
            .unwrap();
        let err = render_prompt(&prompt.assemble().unwrap()).unwrap_err();
        assert!(matches!(err, PromptError::UnknownVariable(name, _) if name == "cwd"));
    }
}
