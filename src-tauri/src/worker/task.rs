// Copyright (c) 2026 MeeJoy
//
// 任务类型定义
//
// TaskRequest / TaskResult / TaskEvent 是 Worker 引擎与前端 / 执行器之间
// 的数据契约。TaskEvent 通过 broadcast channel 推送给所有订阅者。

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

/// 任务优先级（数值越大优先级越高，BinaryHeap max-heap 先出）
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub enum TaskPriority {
    Low = 0,
    Normal = 1,
    High = 2,
    Urgent = 3,
}

/// 任务类型：轻量 / 重型（决定使用哪个信号量）
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum TaskType {
    /// 文档检索、简单文件操作等
    Lightweight,
    /// 示教录制、批量办公处理等
    Heavyweight,
}

/// 任务状态
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum TaskStatus {
    Queued,
    Running,
    Retrying,
    Succeeded,
    Failed,
    Cancelled,
}

/// 任务请求（客户端构造后提交给 WorkerEngine）
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TaskRequest {
    /// 客户端生成的 UUID
    pub task_id: String,
    /// 场景：work / personal / hobby
    pub scene: String,
    pub task_type: TaskType,
    pub priority: TaskPriority,
    pub skill_id: Option<String>,
    pub skill_version: Option<String>,
    pub params: serde_json::Value,
    /// 执行器名称：注册到 Scheduler 的 executor key
    pub executor: String,
    pub max_retries: u32,
}

/// 任务结果（终态快照）
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TaskResult {
    pub task_id: String,
    pub status: TaskStatus,
    pub result: Option<serde_json::Value>,
    pub error: Option<String>,
    pub retry_count: u32,
    pub started_at: Option<DateTime<Utc>>,
    pub finished_at: Option<DateTime<Utc>>,
}

/// 任务事件（通过 broadcast channel 推送给订阅者 / 前端）
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "event")]
pub enum TaskEvent {
    #[serde(rename = "queued")]
    Queued { task_id: String, priority: TaskPriority },
    #[serde(rename = "started")]
    Started { task_id: String },
    #[serde(rename = "progress")]
    Progress { task_id: String, message: String },
    #[serde(rename = "retrying")]
    Retrying {
        task_id: String,
        attempt: u32,
        next_retry_at: String,
    },
    #[serde(rename = "succeeded")]
    Succeeded {
        task_id: String,
        result: serde_json::Value,
        duration_ms: i64,
    },
    #[serde(rename = "failed")]
    Failed { task_id: String, error: String },
    #[serde(rename = "cancelled")]
    Cancelled { task_id: String },
}
