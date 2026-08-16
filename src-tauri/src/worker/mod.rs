// Copyright (c) 2026 MeeJoy
//
// Worker 异步任务引擎模块
//
// 双通道（轻量 / 重型）异步任务调度引擎：
//   - 优先级队列（BinaryHeap，高优先级 + 先提交先出）
//   - 双信号量并发控制（轻量 8 / 重型 2）
//   - 指数退避 + jitter 重试
//   - oneshot channel 取消信号（队列移除 / 运行中中断）
//   - broadcast channel 事件广播（多订阅者）
//
// 通过 `TaskExecutor` trait 注册执行器，`WorkerEngine::submit` 提交任务，
// `Scheduler::subscribe` 订阅事件流转（queued → started → progress →
// retrying → succeeded/failed/cancelled）。

use std::sync::Arc;

pub mod retry;
pub mod scheduler;
pub mod task;

pub use scheduler::Scheduler;
// 公共 API 再导出：供 crate 内其他模块通过 `worker::TaskXxx` 访问。
// 模块本身是 `mod worker;`（私有），这些 re-export 是类型访问入口，
// 当前尚未被 crate 内其他模块引用，故显式 allow unused_imports。
#[allow(unused_imports)]
pub use task::{TaskPriority, TaskRequest, TaskResult, TaskStatus};

/// Worker 引擎：双通道异步任务调度
pub struct WorkerEngine {
    scheduler: Arc<Scheduler>,
}

impl WorkerEngine {
    pub fn new() -> Self {
        let scheduler = Arc::new(Scheduler::new());
        Self { scheduler }
    }

    /// 提交任务，返回 task_id
    pub async fn submit(&self, req: TaskRequest) -> Result<String, WorkerError> {
        self.scheduler.submit(req).await
    }

    /// 取消任务
    pub async fn cancel(&self, task_id: &str) -> Result<(), WorkerError> {
        self.scheduler.cancel(task_id).await
    }

    /// 获取调度器引用（用于执行器注册与事件订阅）
    pub fn scheduler(&self) -> &Arc<Scheduler> {
        &self.scheduler
    }
}

#[derive(Debug, thiserror::Error)]
pub enum WorkerError {
    #[error("任务不存在: {0}")]
    NotFound(String),
    #[error("任务已取消: {0}")]
    Cancelled(String),
    #[error("任务执行失败: {0}")]
    ExecutionFailed(String),
    #[error("内部错误: {0}")]
    Internal(String),
}
