
//
// A small wrapper around a function registry. Tools are async functions
// that take a JSON args object and return a JSON result. The TypeScript
// version stored tools as `Record<string, ToolHandler>`; the Rust port
// stores boxed async fn pointers keyed by string.

use std::collections::HashMap;
use std::sync::Arc;
use serde::{Deserialize, Serialize};

pub type ToolResult = Result<serde_json::Value, String>;
pub type ToolFn = Arc<dyn Fn(serde_json::Value) -> futures::future::BoxFuture<'static, ToolResult> + Send + Sync + 'static>;

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct ToolSpec {
    pub name: String,
    pub description: String,
    pub parameters: serde_json::Value,
}

#[derive(Default)]
pub struct ToolRegistry {
    specs: Vec<ToolSpec>,
    fns: HashMap<String, ToolFn>,
}

impl ToolRegistry {
    pub fn new() -> Self { Self::default() }

    pub fn register<F, Fut>(&mut self, spec: ToolSpec, handler: F)
    where
        F: Fn(serde_json::Value) -> Fut + Send + Sync + 'static,
        Fut: std::future::Future<Output = ToolResult> + Send + 'static,
    {
        let f: ToolFn = Arc::new(move |args| Box::pin(handler(args)));
        self.specs.push(spec.clone());
        self.fns.insert(spec.name, f);
    }

    pub fn list(&self) -> Vec<ToolSpec> { self.specs.clone() }

    pub async fn call(&self, name: &str, args: serde_json::Value) -> ToolResult {
        match self.fns.get(name) {
            Some(f) => f(args).await,
            None => Err(format!("tool not found: {}", name)),
        }
    }
}
