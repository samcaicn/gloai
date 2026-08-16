
//
// Original implementation backed by an HTTP LLM endpoint (axios) and an
// in-memory task scheduler. The Rust port keeps:
//   * `VLMClient` — thin `reqwest` wrapper that posts to `/chat/completions`
//   * `TaskScheduler` — priority queue + status map
//   * `HermesAgent` — orchestrates the two plus a shared conversation log

use std::collections::{BinaryHeap, HashMap};
use std::cmp::Reverse;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::sync::Mutex;
use reqwest::Client as HttpClient;
use serde::{Deserialize, Serialize};

use super::types::*;

#[derive(Clone)]
pub struct VLMClient {
    #[allow(dead_code)]
    http: HttpClient,
    pub api_url: String,
    pub api_key: Option<String>,
    pub model: String,
}

impl VLMClient {
    pub fn new(config: &HermesConfig) -> Self {
        let http = HttpClient::builder()
            .no_proxy()
            .timeout(std::time::Duration::from_secs(120))
            .build()
            .expect("http client builder");
        Self {
            http,
            api_url: config.api_url.clone(),
            api_key: config.api_key.clone(),
            model: config.model.clone(),
        }
    }

    pub async fn complete(&self, messages: Vec<VLMMessage>, tools: Option<Vec<serde_json::Value>>) -> Result<VLMResponse, String> {
        let url = format!("{}/chat/completions", self.api_url.trim_end_matches('/'));
        let mut body = serde_json::json!({
            "model": self.model,
            "messages": messages,
            "max_tokens": 4096u32,
        });
        if let Some(ts) = tools {
            body["tools"] = serde_json::Value::Array(ts);
        }
        let mut req = self.http.post(&url).json(&body);
        if let Some(key) = &self.api_key {
            req = req.bearer_auth(key);
        }
        let resp = req.send().await.map_err(|e| e.to_string())?;
        if !resp.status().is_success() {
            return Err(format!("vlm http {}", resp.status()));
        }
        let v: serde_json::Value = resp.json().await.map_err(|e| e.to_string())?;
        let choice = v.get("choices").and_then(|c| c.get(0)).cloned().unwrap_or_default();
        let message = choice.get("message").cloned().unwrap_or_default();
        let content = message.get("content").and_then(|x| x.as_str()).map(String::from);
        let tool_calls = message.get("tool_calls").and_then(|tc| serde_json::from_value::<Vec<VLMToolCall>>(tc.clone()).ok());
        let finish_reason = choice.get("finish_reason").and_then(|x| x.as_str()).map(String::from);
        let usage = v.get("usage").and_then(|u| serde_json::from_value::<VLMUsage>(u.clone()).ok());
        Ok(VLMResponse { content, tool_calls, finish_reason, usage })
    }
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct TaskSchedulerStats {
    pub total: usize,
    pub pending: usize,
    pub running: usize,
    pub completed: usize,
    pub failed: usize,
    pub counter: u64,
}

pub struct TaskScheduler {
    tasks: Mutex<HashMap<String, Task>>,
    queue: Mutex<BinaryHeap<Reverse<(u8, String)>>>,
    counter: Mutex<u64>,
}

impl Default for TaskScheduler {
    fn default() -> Self {
        Self {
            tasks: Mutex::new(HashMap::new()),
            queue: Mutex::new(BinaryHeap::new()),
            counter: Mutex::new(0),
        }
    }
}

impl TaskScheduler {
    pub fn new() -> Self { Self::default() }

    pub async fn add_task(&self, instruction: String, priority: u8) -> Task {
        let id = {
            let mut c = self.counter.lock().await;
            *c += 1;
            format!("task-{}", *c)
        };
        let now = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0);
        let task = Task {
            id,
            instruction,
            priority,
            status: "pending".to_string(),
            created_at: Some(now),
            ..Default::default()
        };
        let mut tasks = self.tasks.lock().await;
        tasks.insert(task.id.clone(), task.clone());
        // Cap at 1000 entries to prevent unbounded growth; evict oldest by created_at.
        // Queue is left untouched so queue behavior is preserved (pop_next skips evicted ids).
        if tasks.len() > 1000 {
            if let Some(oldest_id) = tasks
                .iter()
                .min_by_key(|(_, t)| t.created_at.unwrap_or(i64::MAX))
                .map(|(id, _)| id.clone())
            {
                tasks.remove(&oldest_id);
            }
        }
        drop(tasks);
        self.queue.lock().await.push(Reverse((priority, task.id.clone())));
        task
    }

    pub async fn get(&self, id: &str) -> Option<Task> {
        self.tasks.lock().await.get(id).cloned()
    }

    pub async fn list(&self) -> Vec<Task> {
        self.tasks.lock().await.values().cloned().collect()
    }

    pub async fn complete(&self, id: &str, result: Option<serde_json::Value>) {
        if let Some(t) = self.tasks.lock().await.get_mut(id) {
            t.status = "completed".into();
            t.result = result;
            t.completed_at = Some(SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0));
        }
    }

    pub async fn fail(&self, id: &str, error: impl Into<String>) {
        if let Some(t) = self.tasks.lock().await.get_mut(id) {
            t.status = "failed".into();
            t.error = Some(error.into());
            t.completed_at = Some(SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0));
        }
    }

    pub async fn pop_next(&self) -> Option<Task> {
        loop {
            let next = self.queue.lock().await.pop();
            match next {
                Some(Reverse((_, id))) => {
                    if let Some(t) = self.tasks.lock().await.get_mut(&id) {
                        if t.status == "pending" {
                            t.status = "running".into();
                            t.started_at = Some(SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0));
                            return Some(t.clone());
                        }
                    }
                }
                None => return None,
            }
        }
    }

    pub async fn stats(&self) -> TaskSchedulerStats {
        let counter = *self.counter.lock().await;
        let tasks = self.tasks.lock().await;
        let mut s = TaskSchedulerStats {
            total: tasks.len(),
            counter,
            ..Default::default()
        };
        for t in tasks.values() {
            match t.status.as_str() {
                "pending" => s.pending += 1,
                "running" => s.running += 1,
                "completed" => s.completed += 1,
                "failed" => s.failed += 1,
                _ => {}
            }
        }
        s
    }
}

#[derive(Default)]
pub struct HermesAgent {
    pub config: HermesConfig,
    pub scheduler: Arc<TaskScheduler>,
    pub conversations: Mutex<HashMap<String, Conversation>>,
    pub client: Mutex<Option<VLMClient>>,
}

impl HermesAgent {
    pub fn new(config: HermesConfig) -> Self {
        let client = VLMClient::new(&config);
        Self {
            config,
            scheduler: Arc::new(TaskScheduler::new()),
            conversations: Mutex::new(HashMap::new()),
            client: Mutex::new(Some(client)),
        }
    }

    pub async fn add_conversation(&self, conv: Conversation) {
        self.conversations.lock().await.insert(conv.id.clone(), conv);
    }

    pub async fn list_conversations(&self) -> Vec<Conversation> {
        self.conversations.lock().await.values().cloned().collect()
    }

    pub async fn call(&self, messages: Vec<VLMMessage>, tools: Option<Vec<serde_json::Value>>) -> Result<VLMResponse, String> {
        let client = {
            let guard = self.client.lock().await;
            guard.as_ref().ok_or_else(|| "vlm client not initialized".to_string())?.clone()
        };
        client.complete(messages, tools).await
    }
}

#[tauri::command]
pub async fn hermes_create_task(agent_state: tauri::State<'_, crate::hermes::HermesAppState>, instruction: String, priority: u8) -> Result<Task, String> {
    Ok(agent_state.agent.scheduler.add_task(instruction, priority).await)
}

#[tauri::command]
pub async fn hermes_list_tasks(agent_state: tauri::State<'_, crate::hermes::HermesAppState>) -> Result<Vec<Task>, String> {
    Ok(agent_state.agent.scheduler.list().await)
}

#[tauri::command]
pub async fn hermes_task_stats(agent_state: tauri::State<'_, crate::hermes::HermesAppState>) -> Result<TaskSchedulerStats, String> {
    Ok(agent_state.agent.scheduler.stats().await)
}
