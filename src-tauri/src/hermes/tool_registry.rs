
//
// Tool registry used by the agent runtime. This is a slightly higher
// level module than `agent_tools.rs` — it adds an `invoke(name, args)`
// method, tool permissions, and a `ToolStats` snapshot.

use std::collections::HashMap;
use serde::{Deserialize, Serialize};

use super::agent_tools::{ToolFn, ToolResult, ToolSpec};

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct ToolStats {
    pub total: usize,
    pub calls: u64,
    pub errors: u64,
    pub by_name: HashMap<String, u64>,
}

#[derive(Default)]
pub struct ToolRegistry2 {
    specs: Vec<ToolSpec>,
    fns: HashMap<String, ToolFn>,
    allowed: HashMap<String, bool>,
    calls: u64,
    errors: u64,
    by_name: HashMap<String, u64>,
}


impl ToolRegistry2 {
    pub fn new() -> Self { Self::default() }

    pub fn register<F, Fut>(&mut self, spec: ToolSpec, handler: F, allowed: bool)
    where
        F: Fn(serde_json::Value) -> Fut + Send + Sync + 'static,
        Fut: std::future::Future<Output = ToolResult> + Send + 'static,
    {
        let f: ToolFn = Arc::new(move |args| Box::pin(handler(args)));
        self.specs.push(spec.clone());
        self.fns.insert(spec.name.clone(), f);
        self.allowed.insert(spec.name, allowed);
    }

    pub fn list(&self) -> Vec<ToolSpec> { self.specs.clone() }

    /// 获取指定工具的函数引用（Arc clone），用于在锁外安全调用。
    /// 返回 None 表示工具未注册。
    pub fn get_fn(&self, name: &str) -> Option<ToolFn> {
        self.fns.get(name).cloned()
    }

    pub fn is_allowed(&self, name: &str) -> bool { *self.allowed.get(name).unwrap_or(&false) }

    pub fn set_allowed(&mut self, name: &str, allowed: bool) {
        self.allowed.insert(name.to_string(), allowed);
    }

    pub async fn invoke(&mut self, name: &str, args: serde_json::Value) -> ToolResult {
        self.calls += 1;
        *self.by_name.entry(name.to_string()).or_insert(0) += 1;
        let f = match self.fns.get(name) {
            Some(f) => f.clone(),
            None => { self.errors += 1; return Err(format!("tool not found: {}", name)); }
        };
        match f(args).await {
            Ok(v) => Ok(v),
            Err(e) => { self.errors += 1; Err(e) }
        }
    }

    pub fn stats(&self) -> ToolStats {
        ToolStats { total: self.specs.len(), calls: self.calls, errors: self.errors, by_name: self.by_name.clone() }
    }
}

use std::sync::Arc;
