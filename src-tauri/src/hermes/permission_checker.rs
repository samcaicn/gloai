
//
// Permission checker. The TypeScript module maintained a per-tool
// permission matrix (allow / deny / ask) and resolved the effective
// decision by walking the matrix. The Rust port supports the same
// decisions and exposes `check()`.

use std::collections::HashMap;
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq, Hash)]
#[serde(rename_all = "snake_case")]
#[derive(Default)]
pub enum Permission {
    Allow,
    Deny,
    #[default]
    Ask,
}


#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct PermissionDecision {
    pub tool: String,
    pub permission: Permission,
    pub reason: Option<String>,
}

#[derive(Default)]
pub struct PermissionChecker {
    matrix: HashMap<String, Permission>,
}

impl PermissionChecker {
    pub fn new() -> Self { Self::default() }

    pub fn grant(&mut self, tool: impl Into<String>, p: Permission) {
        self.matrix.insert(tool.into(), p);
    }

    pub fn revoke(&mut self, tool: &str) { self.matrix.remove(tool); }

    pub fn check(&self, tool: &str) -> PermissionDecision {
        let p = *self.matrix.get(tool).unwrap_or(&Permission::Ask);
        PermissionDecision { tool: tool.to_string(), permission: p, reason: None }
    }

    pub fn check_with_reason(&self, tool: &str, reason: impl Into<String>) -> PermissionDecision {
        let p = *self.matrix.get(tool).unwrap_or(&Permission::Ask);
        PermissionDecision { tool: tool.to_string(), permission: p, reason: Some(reason.into()) }
    }

    pub fn snapshot(&self) -> HashMap<String, Permission> { self.matrix.clone() }
}
