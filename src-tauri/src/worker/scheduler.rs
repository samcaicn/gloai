// Copyright (c) 2026 MeeJoy
//
// Scheduler —— 任务调度核心
//
// 从优先级队列（BinaryHeap）取出任务，按 task_type 分配到对应信号量
// （轻量 8 / 重型 2），在独立 tokio task 中执行。
//
// 取消信号通过 `oneshot` channel 传递，存储在 `cancel_signals` HashMap 中：
//   - 任务在队列中：cancel 时直接从队列移除 + 发送 Cancelled 事件
//   - 任务运行中：cancel 时通过 oneshot 发送信号，task 的 select! 响应
//
// 事件通过 `broadcast` channel 广播，允许多个订阅者（如 Tauri event
// 转发器）同时接收。

use std::cmp::Ordering;
use std::collections::{BinaryHeap, HashMap};
use std::sync::Arc;

use chrono::Utc;
use tokio::sync::{broadcast, mpsc, oneshot, Mutex, Semaphore};
use tokio::task::JoinHandle;

use crate::worker::retry::retry_delay;
use crate::worker::task::*;
use crate::worker::WorkerError;

/// 已注册的任务执行器 trait。
///
/// `execute` 在调度器 spawn 的独立 task 中运行，通过 `progress` channel
/// 上报进度消息，返回 `Ok(value)` 表示成功，`Err(msg)` 表示失败（触发重试）。
#[async_trait::async_trait]
pub trait TaskExecutor: Send + Sync {
    async fn execute(
        &self,
        params: serde_json::Value,
        progress: mpsc::Sender<String>,
    ) -> Result<serde_json::Value, String>;
}

/// 优先级队列元素
struct TaskEntry {
    priority: TaskPriority,
    submitted_at: chrono::DateTime<Utc>,
    request: TaskRequest,
    cancel_rx: oneshot::Receiver<()>,
}

// BinaryHeap 是 max-heap：高优先级先出，同优先级先提交先出。
impl PartialEq for TaskEntry {
    fn eq(&self, other: &Self) -> bool {
        self.priority == other.priority && self.submitted_at == other.submitted_at
    }
}
impl Eq for TaskEntry {}
impl PartialOrd for TaskEntry {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}
impl Ord for TaskEntry {
    fn cmp(&self, other: &Self) -> Ordering {
        // 高优先级先出；同优先级先提交先出（submitted_at 小的更大 → 先出）
        self.priority
            .cmp(&other.priority)
            .then_with(|| other.submitted_at.cmp(&self.submitted_at))
    }
}

pub struct Scheduler {
    /// 轻量任务信号量（8 并发）
    light_sem: Arc<Semaphore>,
    /// 重型任务信号量（2 并发）
    heavy_sem: Arc<Semaphore>,
    /// 待执行优先级队列
    queue: Arc<Mutex<BinaryHeap<TaskEntry>>>,
    /// 已注册执行器
    executors: Arc<Mutex<HashMap<String, Arc<dyn TaskExecutor>>>>,
    /// 事件广播（多订阅者）
    event_tx: broadcast::Sender<TaskEvent>,
    /// 取消信号表：task_id → oneshot Sender
    cancel_signals: Arc<Mutex<HashMap<String, oneshot::Sender<()>>>>,
    /// 消费循环 JoinHandle（保活，避免被回收）
    queue_handle: std::sync::Mutex<Option<JoinHandle<()>>>,
}

impl Scheduler {
    pub fn new() -> Self {
        // broadcast 容量 256：覆盖前端订阅者短暂滞后，溢出时旧事件被丢弃
        let (event_tx, _event_rx) = broadcast::channel::<TaskEvent>(256);
        let scheduler = Self {
            light_sem: Arc::new(Semaphore::new(8)),
            heavy_sem: Arc::new(Semaphore::new(2)),
            queue: Arc::new(Mutex::new(BinaryHeap::new())),
            executors: Arc::new(Mutex::new(HashMap::new())),
            event_tx,
            cancel_signals: Arc::new(Mutex::new(HashMap::new())),
            queue_handle: std::sync::Mutex::new(None),
        };
        scheduler.start_consumer();
        scheduler
    }

    /// 启动队列消费循环（detach 运行，handle 存入 queue_handle 保活）
    fn start_consumer(&self) {
        let queue = self.queue.clone();
        let light_sem = self.light_sem.clone();
        let heavy_sem = self.heavy_sem.clone();
        let executors = self.executors.clone();
        let event_tx = self.event_tx.clone();
        let cancel_signals = self.cancel_signals.clone();

        let handle = tokio::spawn(async move {
            loop {
                // 从队列取任务（短暂持锁，取完即释放）
                let entry = {
                    let mut q = queue.lock().await;
                    q.pop()
                };

                let entry = match entry {
                    Some(e) => e,
                    None => {
                        // 队列空，短暂休眠避免空转
                        tokio::time::sleep(tokio::time::Duration::from_millis(50)).await;
                        continue;
                    }
                };

                // 按 task_type 选择信号量
                let sem = if entry.request.task_type == TaskType::Heavyweight {
                    heavy_sem.clone()
                } else {
                    light_sem.clone()
                };

                // acquire_owned 返回 'static permit，可 move 到 spawned task
                let permit = match sem.acquire_owned().await {
                    Ok(p) => p,
                    Err(_) => {
                        // 信号量关闭（仅在 Scheduler 被 drop 时发生）
                        let _ = event_tx.send(TaskEvent::Failed {
                            task_id: entry.request.task_id.clone(),
                            error: "调度器信号量已关闭".to_string(),
                        });
                        cancel_signals
                            .lock()
                            .await
                            .remove(&entry.request.task_id);
                        continue;
                    }
                };

                // 发送 started 事件
                let _ = event_tx.send(TaskEvent::Started {
                    task_id: entry.request.task_id.clone(),
                });

                let executors = executors.clone();
                let event_tx = event_tx.clone();
                let cancel_signals = cancel_signals.clone();

                tokio::spawn(async move {
                    // permit 持有至 task 结束，控制并发
                    let _permit = permit;

                    let task_id = entry.request.task_id.clone();
                    let executor_key = entry.request.executor.clone();
                    let max_retries = entry.request.max_retries;
                    let mut cancel_rx = entry.cancel_rx;

                    // 查找执行器
                    let executor = {
                        let execs = executors.lock().await;
                        execs.get(&executor_key).cloned()
                    };

                    let executor = match executor {
                        Some(e) => e,
                        None => {
                            let _ = event_tx.send(TaskEvent::Failed {
                                task_id: task_id.clone(),
                                error: format!("执行器未注册: {}", executor_key),
                            });
                            cancel_signals.lock().await.remove(&task_id);
                            return;
                        }
                    };

                    let started = Utc::now();
                    let mut retry_count = 0u32;

                    loop {
                        // 每次尝试创建新的 progress channel
                        let (progress_tx, mut progress_rx) = mpsc::channel::<String>(16);
                        let exec = executor.clone();
                        let params = entry.request.params.clone();
                        let exec_fut = exec.execute(params, progress_tx);
                        tokio::pin!(exec_fut);

                        // 同时监听执行结果、进度上报、取消信号
                        let mut cancelled = false;
                        let exec_result: Result<serde_json::Value, String> = loop {
                            tokio::select! {
                                biased; // 优先响应取消
                                _ = &mut cancel_rx => {
                                    cancelled = true;
                                    break Err("任务已取消".to_string());
                                }
                                msg = progress_rx.recv() => {
                                    if let Some(m) = msg {
                                        let _ = event_tx.send(TaskEvent::Progress {
                                            task_id: task_id.clone(),
                                            message: m,
                                        });
                                    }
                                }
                                r = &mut exec_fut => {
                                    break r;
                                }
                            }
                        };

                        if cancelled {
                            let _ = event_tx.send(TaskEvent::Cancelled {
                                task_id: task_id.clone(),
                            });
                            cancel_signals.lock().await.remove(&task_id);
                            return;
                        }

                        match exec_result {
                            Ok(val) => {
                                let duration = (Utc::now() - started).num_milliseconds();
                                let _ = event_tx.send(TaskEvent::Succeeded {
                                    task_id: task_id.clone(),
                                    result: val,
                                    duration_ms: duration,
                                });
                                cancel_signals.lock().await.remove(&task_id);
                                return;
                            }
                            Err(err) => {
                                if retry_count < max_retries {
                                    retry_count += 1;
                                    let delay = retry_delay(retry_count);
                                    let _ = event_tx.send(TaskEvent::Retrying {
                                        task_id: task_id.clone(),
                                        attempt: retry_count,
                                        next_retry_at: (Utc::now()
                                            + chrono::Duration::from_std(delay)
                                                .unwrap_or(chrono::Duration::seconds(60)))
                                        .to_rfc3339(),
                                    });

                                    // 重试等待期间也响应取消信号
                                    let cancelled_during_sleep = tokio::select! {
                                        biased;
                                        _ = &mut cancel_rx => true,
                                        _ = tokio::time::sleep(delay) => false,
                                    };
                                    if cancelled_during_sleep {
                                        let _ = event_tx.send(TaskEvent::Cancelled {
                                            task_id: task_id.clone(),
                                        });
                                        cancel_signals.lock().await.remove(&task_id);
                                        return;
                                    }

                                    let _ = event_tx.send(TaskEvent::Started {
                                        task_id: task_id.clone(),
                                    });
                                    continue;
                                }

                                // 重试耗尽，标记失败
                                let _ = event_tx.send(TaskEvent::Failed {
                                    task_id: task_id.clone(),
                                    error: err,
                                });
                                cancel_signals.lock().await.remove(&task_id);
                                return;
                            }
                        }
                    }
                });
            }
        });

        // 保活 handle（JoinHandle drop 不会中止 task，但显式持有更清晰）
        *self.queue_handle.lock().unwrap() = Some(handle);
    }

    /// 注册执行器
    pub async fn register_executor(&self, name: String, executor: Arc<dyn TaskExecutor>) {
        self.executors.lock().await.insert(name, executor);
    }

    /// 提交任务到队列
    pub async fn submit(&self, req: TaskRequest) -> Result<String, WorkerError> {
        let task_id = req.task_id.clone();
        let priority = req.priority;

        // 创建取消信号并登记
        let (cancel_tx, cancel_rx) = oneshot::channel::<()>();
        self.cancel_signals
            .lock()
            .await
            .insert(task_id.clone(), cancel_tx);

        let entry = TaskEntry {
            priority,
            submitted_at: Utc::now(),
            request: req,
            cancel_rx,
        };

        self.queue.lock().await.push(entry);

        let _ = self.event_tx.send(TaskEvent::Queued {
            task_id: task_id.clone(),
            priority,
        });

        Ok(task_id)
    }

    /// 取消任务：先尝试从队列移除，再尝试向运行中的任务发送取消信号。
    pub async fn cancel(&self, task_id: &str) -> Result<(), WorkerError> {
        // 1. 尝试从队列移除
        let was_queued = {
            let mut q = self.queue.lock().await;
            let before = q.len();
            q.retain(|e| e.request.task_id != task_id);
            before > q.len()
        };

        if was_queued {
            // 队列中的任务：移除 cancel signal，发送 Cancelled 事件
            self.cancel_signals.lock().await.remove(task_id);
            let _ = self.event_tx.send(TaskEvent::Cancelled {
                task_id: task_id.to_string(),
            });
            return Ok(());
        }

        // 2. 不在队列，尝试向运行中的任务发送取消信号
        let sender = self.cancel_signals.lock().await.remove(task_id);
        match sender {
            Some(tx) => {
                // send 成功 = 运行中的任务收到信号，由任务自身发送 Cancelled 事件
                // send 失败 = 任务刚结束（receiver 已 drop），视为已完成
                let _ = tx.send(());
                Ok(())
            }
            None => Err(WorkerError::NotFound(task_id.to_string())),
        }
    }

    /// 订阅事件广播
    pub fn subscribe(&self) -> broadcast::Receiver<TaskEvent> {
        self.event_tx.subscribe()
    }
}
