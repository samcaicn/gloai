
//
// A tiny helper that tracks the current working directory plus a stack of
// previous directories. The TypeScript version was used by the agent
// runtime to temporarily enter a workspace and restore the original cwd
// afterwards. The Rust port wraps `std::env` and is intentionally not
// `Send` + `Sync` so it can be held in a single task at a time.

use std::path::{Path, PathBuf};
use serde::{Deserialize, Serialize};

#[derive(Debug)]
pub struct WorkingDirectory {
    stack: Vec<PathBuf>,
}

impl Default for WorkingDirectory {
    fn default() -> Self {
        Self { stack: vec![std::env::current_dir().unwrap_or_else(|_| PathBuf::from("."))] }
    }
}

impl WorkingDirectory {
    pub fn new() -> Self { Self::default() }
    pub fn current(&self) -> PathBuf { self.stack.last().cloned().unwrap_or_else(|| PathBuf::from(".")) }

    pub fn push(&mut self, dir: impl AsRef<Path>) -> std::io::Result<PathBuf> {
        let dir = dir.as_ref();
        // NOTE: 不再调用 `std::env::set_current_dir(dir)`,因为这
        // 会改 process 全局 cwd,影响其它 task / thread。
        // 改为只 push 到 stack,caller 需要时再切(单线程内使用)。
        // 调用方应保证 `pop` / 切回不要跨 thread 跑。
        let abs = std::fs::canonicalize(dir)?;
        self.stack.push(abs.clone());
        Ok(abs)
    }

    pub fn pop(&mut self) -> std::io::Result<PathBuf> {
        let popped = self.stack.pop();
        popped.ok_or_else(|| std::io::Error::other("stack underflow"))
    }

    pub fn resolve(&self, rel: impl AsRef<Path>) -> PathBuf {
        self.current().join(rel)
    }
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct DirSnapshot {
    pub cwd: String,
    pub depth: usize,
}

impl WorkingDirectory {
    pub fn snapshot(&self) -> DirSnapshot {
        DirSnapshot { cwd: self.current().to_string_lossy().to_string(), depth: self.stack.len() }
    }
}
