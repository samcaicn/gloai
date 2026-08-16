
//
// Hook system. The TypeScript module exposed a `registerHook(name,
// fn)` API and a `runHooks(name, args)` function that awaited all
// registered callbacks in order. The Rust port mirrors that with a
// `RwLock<Vec<Box<dyn Fn ... >>>`.

use std::sync::Arc;
use tokio::sync::RwLock;
use serde::{Deserialize, Serialize};

pub type HookFn = Arc<dyn Fn(serde_json::Value) -> futures::future::BoxFuture<'static, serde_json::Value> + Send + Sync + 'static>;

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct HookRunResult {
    pub name: String,
    pub results: Vec<serde_json::Value>,
}

pub struct HookRegistry {
    inner: RwLock<Vec<(String, HookFn)>>,
}

impl Default for HookRegistry {
    fn default() -> Self { Self { inner: RwLock::new(Vec::new()) } }
}

impl HookRegistry {
    pub fn new() -> Self { Self::default() }

    pub async fn register<F, Fut>(&self, name: impl Into<String>, f: F)
    where
        F: Fn(serde_json::Value) -> Fut + Send + Sync + 'static,
        Fut: std::future::Future<Output = serde_json::Value> + Send + 'static,
    {
        let f: HookFn = Arc::new(move |args| Box::pin(f(args)));
        self.inner.write().await.push((name.into(), f));
    }

    pub async fn run(&self, name: &str, args: serde_json::Value) -> HookRunResult {
        let snapshot: Vec<HookFn> = {
            let g = self.inner.read().await;
            g.iter().filter(|(n, _)| n == name).map(|(_, f)| f.clone()).collect()
        };
        let mut out = Vec::new();
        for f in snapshot {
            let r = f(args.clone()).await;
            out.push(r);
        }
        HookRunResult { name: name.to_string(), results: out }
    }

    pub async fn clear(&self, name: &str) {
        self.inner.write().await.retain(|(n, _)| n != name);
    }
}
