// Copyright (c) 2026 MeeJoy
//
// tupAI P1 — 本地定时任务 store + 调度器
//
// 设计目标: 应用进程内自管 cron 任务, 不依赖远端 Dashboard
//   - 任务 + 执行历史持久化到 `<app_data>/tupai/cron/` (jobs.json + runs/<id>.jsonl)
//   - 启动时从磁盘加载, 启动后台调度 tokio task
//   - 调度器每 30s tick 一次, 到期任务 spawn LLM run
//   - LLM 调用经 MCP `llm.stream_request`（与前端 llm.ts 完全相同的路径），
//     服务器据此自动匹配模型；客户端不配置 provider / api_key / model，
//     device_token 由前端通过 `cron_local_set_token` 透传做 Bearer 鉴权。
//   - 8 个 Tauri 命令: list / create / pause / resume / trigger / delete /
//     get_runs / clear_runs / set_token
//   - cron 表达式按本机**本地时区**解释（前端输入的是本地墙钟，
//     落盘转 UTC 瞬间，显示时再按本机时区还原）
//
// 与 `hermes::cron::CronScheduler` 的区别:
//   - 旧的 CronScheduler 是 axum 调用层的占位, 不落盘, 不实际跑 prompt
//   - 本模块是真实可用的端到端 store, 包含磁盘 + 调度 + LLM 执行

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use chrono::{DateTime, Datelike, Local, Timelike, Utc, Weekday};
use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Emitter, Manager};
use tokio::sync::Mutex;
use tokio::time::interval;
use uuid::Uuid;

use crate::commands::mcp_proxy::mcp_call_v2_inner;
use crate::hermes::agent_loop::AgentLoop;
use crate::hermes::types::VLMMessage;

// ========================
// 公共类型 (camelCase)
// ========================

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct CronJob {
    pub id: String,
    pub name: Option<String>,
    pub prompt: String,
    pub schedule: CronSchedule,
    pub schedule_display: String,
    pub enabled: bool,
    /// `idle` | `running` | `error` | `paused` | `completed`
    pub state: String,
    pub deliver: Option<String>,
    pub last_run_at: Option<String>,
    pub next_run_at: Option<String>,
    pub last_error: Option<String>,
    /// 累计成功 / 失败 / 总数 (启动时从 runs.jsonl 重算)
    pub total_runs: u64,
    pub successful_runs: u64,
    pub failed_runs: u64,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct CronSchedule {
    pub kind: String,
    pub expr: String,
    pub display: String,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct CronRun {
    pub id: String,
    pub job_id: String,
    pub started_at: String,
    pub finished_at: Option<String>,
    pub state: String,
    /// LLM 原始回复 (markdown / 文本)
    pub output: Option<String>,
    /// 错误信息 (state=error 时填)
    pub error: Option<String>,
    /// 触发来源: `manual` | `schedule`
    pub trigger: String,
    /// 跑完后投递给 IM 的状态 (未实现, 留接口)
    pub delivery: Option<String>,
    /// 单次耗时 (ms)
    pub duration_ms: Option<u64>,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
#[serde(rename_all = "camelCase")]
pub struct CreateCronJobInput {
    pub prompt: String,
    pub schedule: String,
    pub name: Option<String>,
    pub deliver: Option<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct TriggerCronJobInput {
    pub id: String,
    /// 设备 token (device_token)，用于经 MCP `llm.stream_request` 调用 LLM。
    /// 服务器据此自动匹配合适的模型，客户端无需配置 provider / api_key / model。
    /// 不传时回落到 `CronLocalState` 最近一次从前端刷新的 token。
    pub token: Option<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct CronActionResult {
    pub ok: bool,
}

// ========================
// 磁盘布局
// ========================

/// `jobs.json` 文件根结构
#[derive(Serialize, Deserialize, Default, Debug, Clone)]
struct JobsFile {
    jobs: Vec<CronJob>,
}

/// 全局共享 state. `Mutex` 保证 jobs HashMap 的并发安全
/// (注册到 Tauri 用 `app.manage`)
pub struct CronLocalState {
    inner: Arc<Mutex<CronInner>>,
    base_dir: PathBuf,
    /// 共享 reqwest client, no_proxy + 120s timeout
    llm_http: reqwest::Client,
    /// AppHandle 用于访问 AgentLoop（ReAct tooling call）和 emit 事件给前端。
    app: AppHandle,
    /// 最近一次从前端刷新的 device_token。定时任务经 MCP `llm.stream_request`
    /// 调 LLM 时用它做 Bearer 鉴权（服务器据此自动匹配模型）。
    token: Arc<Mutex<Option<String>>>,
}

struct CronInner {
    jobs: HashMap<String, CronJob>,
    /// 正在跑的任务, 防止重复 trigger
    running: std::collections::HashSet<String>,
}

impl CronLocalState {
    pub fn new(app: AppHandle, base_dir: PathBuf) -> Self {
        let llm_http = reqwest::Client::builder()
            .no_proxy()
            .timeout(Duration::from_secs(120))
            .build()
            .expect("llm http client");
        let me = Self {
            inner: Arc::new(Mutex::new(CronInner {
                jobs: HashMap::new(),
                running: std::collections::HashSet::new(),
            })),
            base_dir,
            llm_http,
            app,
            token: Arc::new(Mutex::new(None)),
        };
        me.bootstrap();
        me
    }

    /// 刷新 device_token（供 `cron_local_set_token` 命令调用）。
    /// 空串视为清除（None），避免落一个无效空串。
    pub async fn set_token(&self, token: Option<String>) {
        let mut g = self.token.lock().await;
        *g = token.filter(|s| !s.trim().is_empty());
    }

    fn jobs_path(&self) -> PathBuf {
        self.base_dir.join("jobs.json")
    }
    fn runs_dir(&self) -> PathBuf {
        self.base_dir.join("runs")
    }
    fn runs_path(&self, job_id: &str) -> PathBuf {
        self.runs_dir().join(format!("{}.jsonl", job_id))
    }

    /// 启动时从磁盘加载 jobs.json, 创建必要目录.
    /// 不存在的文件视为空, 不报错 (首次启动).
    fn bootstrap(&self) {
        if let Err(e) = std::fs::create_dir_all(&self.base_dir) {
            log::error!("[cron_local] create base dir failed: {}", e);
            return;
        }
        if let Err(e) = std::fs::create_dir_all(self.runs_dir()) {
            log::error!("[cron_local] create runs dir failed: {}", e);
        }
        let path = self.jobs_path();
        if !path.exists() {
            return;
        }
        match std::fs::read_to_string(&path) {
            Ok(text) => match serde_json::from_str::<JobsFile>(&text) {
                Ok(file) => {
                    let mut inner = self.inner.blocking_lock();
                    for mut job in file.jobs {
                        // 启动时把"running"全部降级为"idle", 避免前次崩溃留下卡住状态
                        if job.state == "running" {
                            job.state = "idle".to_string();
                            job.last_error = Some("previous run did not finish (app restart)".into());
                        }
                        // 重新计算 next_run_at, 让调度器立即知道下一个时间点
                        // （按本机本地时区计算墙钟时间，再以 UTC 瞬间落盘；
                        // 前端用 toLocaleString 显示时与本机时区一致）
                        if job.enabled {
                            if let Ok(next) = compute_next_fire(&job.schedule.expr, Local::now()) {
                                job.next_run_at = Some(next.with_timezone(&Utc).to_rfc3339());
                            }
                        }
                        inner.jobs.insert(job.id.clone(), job);
                    }
                    log::info!(
                        "[cron_local] loaded {} jobs from {}",
                        inner.jobs.len(),
                        path.display()
                    );
                }
                Err(e) => log::warn!("[cron_local] jobs.json parse failed (ignored): {}", e),
            },
            Err(e) => log::warn!("[cron_local] jobs.json read failed: {}", e),
        }
    }

    /// 持久化 jobs.json (原子写: 临时文件 + rename)
    async fn persist_jobs(&self, jobs: &[CronJob]) -> Result<(), String> {
        let path = self.jobs_path();
        let tmp = path.with_extension("json.tmp");
        let text = serde_json::to_string_pretty(&JobsFile { jobs: jobs.to_vec() })
            .map_err(|e| format!("serialize jobs: {}", e))?;
        // tokio::fs 写文件, 避免阻塞 tokio worker
        tokio::fs::write(&tmp, text)
            .await
            .map_err(|e| format!("write tmp: {}", e))?;
        tokio::fs::rename(&tmp, &path)
            .await
            .map_err(|e| format!("rename: {}", e))?;
        Ok(())
    }

    /// 追加一行 run 到 `<runs_dir>/<job_id>.jsonl`
    async fn append_run(&self, run: &CronRun) -> Result<(), String> {
        let path = self.runs_path(&run.job_id);
        let mut line = serde_json::to_string(run).map_err(|e| format!("ser run: {}", e))?;
        line.push('\n');
        use tokio::io::AsyncWriteExt;
        let mut f = tokio::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(&path)
            .await
            .map_err(|e| format!("open runs: {}", e))?;
        f.write_all(line.as_bytes())
            .await
            .map_err(|e| format!("write run: {}", e))?;
        Ok(())
    }

    /// 读取最近 N 条 run (默认 200, 启动预览)
    pub async fn read_runs(&self, job_id: &str, limit: usize) -> Vec<CronRun> {
        let path = self.runs_path(job_id);
        let Ok(text) = tokio::fs::read_to_string(&path).await else {
            return vec![];
        };
        let mut out: Vec<CronRun> = Vec::new();
        for line in text.lines() {
            if line.trim().is_empty() {
                continue;
            }
            if let Ok(r) = serde_json::from_str::<CronRun>(line) {
                out.push(r);
            }
        }
        // 只保留最近 limit 条
        if out.len() > limit {
            let start = out.len() - limit;
            out.drain(..start);
        }
        out
    }

    pub async fn list(&self) -> Vec<CronJob> {
        let inner = self.inner.lock().await;
        let mut out: Vec<CronJob> = inner.jobs.values().cloned().collect();
        // 排序: 启用在前, 暂停在后; 启用内 next_run_at 升序
        out.sort_by(|a, b| {
            b.enabled
                .cmp(&a.enabled)
                .then_with(|| a.next_run_at.cmp(&b.next_run_at))
        });
        out
    }

    pub async fn create(&self, input: CreateCronJobInput) -> Result<CronJob, String> {
        let expr = input.schedule.trim().to_string();
        if expr.is_empty() {
            return Err("schedule 不能为空".into());
        }
        // 立即算一次 next_run_at 校验 expr 合法（按本机本地时区）
        let next = compute_next_fire(&expr, Local::now())
            .map_err(|e| format!("cron 表达式无效: {}", e))?;
        let id = format!("cron-{}", Uuid::new_v4().simple());
        let job = CronJob {
            id: id.clone(),
            name: input.name.filter(|s| !s.trim().is_empty()),
            prompt: input.prompt,
            schedule: CronSchedule {
                kind: "cron".into(),
                expr: expr.clone(),
                display: cron_display(&expr),
            },
            schedule_display: cron_display(&expr),
            enabled: true,
            state: "idle".into(),
            deliver: input.deliver.filter(|s| !s.trim().is_empty()),
            last_run_at: None,
            next_run_at: Some(next.with_timezone(&Utc).to_rfc3339()),
            last_error: None,
            total_runs: 0,
            successful_runs: 0,
            failed_runs: 0,
        };
        {
            let mut inner = self.inner.lock().await;
            inner.jobs.insert(id.clone(), job.clone());
        }
        self.persist_jobs(&self.snapshot().await)
            .await
            .map_err(|e| {
                log::error!("[cron_local] persist_jobs failed after create: {}", e);
                e
            })?;
        Ok(job)
    }

    pub async fn set_enabled(&self, id: &str, enabled: bool) -> Result<bool, String> {
        let mut inner = self.inner.lock().await;
        let job = inner
            .jobs
            .get_mut(id)
            .ok_or_else(|| format!("job {} not found", id))?;
        job.enabled = enabled;
        if !enabled {
            job.state = "paused".into();
        } else if job.state == "paused" {
            job.state = "idle".into();
            // 重新算 next_run_at（按本机本地时区）
            if let Ok(next) = compute_next_fire(&job.schedule.expr, Local::now()) {
                job.next_run_at = Some(next.with_timezone(&Utc).to_rfc3339());
            }
        }
        let snapshot: Vec<CronJob> = inner.jobs.values().cloned().collect();
        drop(inner);
        self.persist_jobs(&snapshot).await?;
        Ok(true)
    }

    pub async fn delete(&self, id: &str) -> Result<bool, String> {
        let mut inner = self.inner.lock().await;
        let removed = inner.jobs.remove(id).is_some();
        if removed {
            let snapshot: Vec<CronJob> = inner.jobs.values().cloned().collect();
            drop(inner);
            self.persist_jobs(&snapshot)
                .await
                .map_err(|e| {
                    log::error!("[cron_local] persist_jobs failed after delete: {}", e);
                    e
                })?;
            // runs 历史保留, 不删; 用户可在前端 clear
        }
        Ok(removed)
    }

    pub async fn clear_runs(&self, id: &str) -> Result<bool, String> {
        let path = self.runs_path(id);
        match tokio::fs::remove_file(&path).await {
            Ok(()) => Ok(true),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(false),
            Err(e) => Err(format!("clear runs failed: {}", e)),
        }
    }

    /// 启动一个 run (manual 或 schedule). 立即 spawn LLM task.
    /// 返回触发的 run_id (用于前端后续查看结果).
    pub async fn trigger(
        self: Arc<Self>,
        id: &str,
        trigger_kind: &str,
        token: Option<String>,
    ) -> Result<String, String> {
        // 防重入
        {
            let mut inner = self.inner.lock().await;
            if inner.running.contains(id) {
                return Err("job already running".into());
            }
            inner.running.insert(id.to_string());
        }
        let run_id = format!("run-{}", Uuid::new_v4().simple());
        // 克隆一份用于函数返回值：下方 tokio::spawn 的 async move 闭包会
        // 按值捕获 run_id（其内部以 &run_id 借用），若不另存副本，
        // 函数末尾 `Ok(run_id)` 会与闭包的捕获产生 move 冲突（E0382）。
        let returned_run_id = run_id.clone();
        let job_id = id.to_string();
        let job_snapshot = {
            let inner = self.inner.lock().await;
            inner.jobs.get(id).cloned()
        };
        let Some(mut job) = job_snapshot else {
            let mut inner = self.inner.lock().await;
            inner.running.remove(id);
            return Err(format!("job {} not found", id));
        };
        // 先把 state=running 落盘
        let started = Utc::now();
        job.state = "running".into();
        job.last_run_at = Some(started.to_rfc3339());
        {
            let mut inner = self.inner.lock().await;
            inner.jobs.insert(job.id.clone(), job.clone());
        }
        let snapshot = self.snapshot().await;
        if let Err(e) = self.persist_jobs(&snapshot).await {
            log::error!("[cron_local] persist_jobs failed (running): {}", e);
        }
        let run = CronRun {
            id: run_id.clone(),
            job_id: job_id.clone(),
            started_at: started.to_rfc3339(),
            finished_at: None,
            state: "running".into(),
            output: None,
            error: None,
            trigger: trigger_kind.to_string(),
            delivery: None,
            duration_ms: None,
        };
        if let Err(e) = self.append_run(&run).await {
            log::error!("[cron_local] append_run failed (start): {}", e);
        }
        // 解析最终生效的 token：显式优先，否则用本进程缓存的 device_token。
        // 在 spawn 之前算好（异步锁），避免在工作线程里用 blocking_lock。
        let effective_token = {
            let cached = self.token.lock().await;
            token.or_else(|| cached.clone())
        };
        // 异步跑 ReAct 循环（经 AgentLoop，支持 tooling call）。
        let me = self.clone();
        let prompt = job.prompt.clone();
        let app = me.app.clone();
        tokio::spawn(async move {
            let outcome = run_cron_react(&app, &me.llm_http, &prompt, effective_token, &run_id).await;
            let now = Utc::now();
            let duration_ms = (now - started).num_milliseconds().max(0) as u64;
            let (final_state, output, error) = match outcome {
                Ok(content) => {
                    if content.trim().is_empty() {
                        ("error".to_string(), None, Some("upstream returned empty content".into()))
                    } else {
                        ("completed".to_string(), Some(content), None)
                    }
                }
                Err(e) => ("error".to_string(), None, Some(e)),
            };
            // 更新 job 状态
            {
                let mut inner = me.inner.lock().await;
                if let Some(j) = inner.jobs.get_mut(&job_id) {
                    j.state = if j.enabled { final_state.clone() } else { "paused".into() };
                    j.last_error = error.clone();
                    j.total_runs = j.total_runs.saturating_add(1);
                    if final_state == "completed" {
                        j.successful_runs = j.successful_runs.saturating_add(1);
                    } else if final_state == "error" {
                        j.failed_runs = j.failed_runs.saturating_add(1);
                    }
                    // 算下次（按本机本地时区）
                    if j.enabled {
                        if let Ok(next) = compute_next_fire(&j.schedule.expr, Local::now()) {
                            j.next_run_at = Some(next.with_timezone(&Utc).to_rfc3339());
                        }
                    }
                }
                inner.running.remove(&job_id);
            }
            // 持久化 jobs
            let snapshot = me.snapshot().await;
            if let Err(e) = me.persist_jobs(&snapshot).await {
                log::error!("[cron_local] persist_jobs failed (done): {}", e);
            }
            // 追加完成 run
            let mut final_run = run;
            final_run.finished_at = Some(now.to_rfc3339());
            final_run.state = final_state;
            final_run.output = output;
            final_run.error = error;
            final_run.duration_ms = Some(duration_ms);
            if let Err(e) = me.append_run(&final_run).await {
                log::error!("[cron_local] append_run failed (done): {}", e);
            }
        });
        Ok(returned_run_id)
    }

    /// 当前所有 jobs 的快照 (用于持久化)
    async fn snapshot(&self) -> Vec<CronJob> {
        let inner = self.inner.lock().await;
        inner.jobs.values().cloned().collect()
    }

    /// 启动后台调度循环. 每 30s 扫描一次到期任务.
    /// `shutdown` 是 tokio 同步 watch, 用于 setup hook 关停时优雅退出.
    pub fn spawn_scheduler(self: Arc<Self>, mut shutdown: tokio::sync::watch::Receiver<bool>) {
        // 使用 tauri::async_runtime::spawn 而非 tokio::spawn，
        // 因为本方法在 Tauri setup hook 中同步调用，此时当前线程
        // 不在 Tokio runtime 上下文中，直接 tokio::spawn 会 panic。
        tauri::async_runtime::spawn(async move {
            let mut tick = interval(Duration::from_secs(30));
            tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
            loop {
                tokio::select! {
                    _ = tick.tick() => {
                        if let Err(e) = self.run_due_jobs().await {
                            log::warn!("[cron_local] scheduler tick error: {}", e);
                        }
                    }
                    _ = shutdown.changed() => {
                        if *shutdown.borrow() {
                            log::info!("[cron_local] scheduler shutdown");
                            return;
                        }
                    }
                }
            }
        });
    }

    /// 检查所有 enabled 任务, 触发到期的
    async fn run_due_jobs(self: &Arc<Self>) -> Result<(), String> {
        let now = Utc::now();
        let due: Vec<String> = {
            let inner = self.inner.lock().await;
            inner
                .jobs
                .values()
                .filter(|j| j.enabled)
                .filter(|j| !inner.running.contains(&j.id))
                .filter_map(|j| {
                    let next = j
                        .next_run_at
                        .as_ref()
                        .and_then(|s| DateTime::parse_from_rfc3339(s).ok())
                        .map(|d| d.with_timezone(&Utc));
                    if let Some(n) = next {
                        if n <= now {
                            return Some(j.id.clone());
                        }
                    }
                    None
                })
                .collect()
        };
        for id in due {
            log::info!("[cron_local] firing scheduled job {}", id);
            // token 传 None：trigger 内部会回落到本进程缓存的 device_token
            // （由前端 cron_local_set_token 维护）。手动触发走显式传 token。
            if let Err(e) = self.clone().trigger(&id, "schedule", None).await {
                log::warn!("[cron_local] trigger {} failed: {}", id, e);
            }
            // 错位一点再触发下一个，避免应用长时间挂起后恢复时
            // 大量到期任务在同一 tick 内同时打出去（惊群 / 瞬时打满并发）。
            tokio::time::sleep(Duration::from_millis(300)).await;
        }
        Ok(())
    }
}

// ========================
// cron 表达式求值
// ========================

fn parse_field(field: &str, min: i32, max: i32) -> Result<Vec<i32>, String> {
    if field == "*" {
        return Ok((min..=max).collect());
    }
    let mut out = Vec::new();
    for part in field.split(',') {
        if part.contains('/') {
            let mut s = part.splitn(2, '/');
            let range_str = s
                .next()
                .ok_or_else(|| "missing range in step field".to_string())?;
            let step: i32 = s
                .next()
                .ok_or_else(|| "missing step".to_string())?
                .parse()
                .map_err(|_| "bad step".to_string())?;
            let (lo, hi) = if range_str == "*" {
                (min, max)
            } else {
                parse_range(range_str, min, max)?
            };
            let mut v = lo;
            while v <= hi {
                out.push(v);
                v += step;
            }
        } else if part.contains('-') {
            let (lo, hi) = parse_range(part, min, max)?;
            for v in lo..=hi {
                out.push(v);
            }
        } else {
            let v: i32 = part
                .parse()
                .map_err(|_| format!("bad value: {}", part))?;
            if v < min || v > max {
                return Err(format!("value {} out of range {}-{}", v, min, max));
            }
            out.push(v);
        }
    }
    out.sort();
    out.dedup();
    Ok(out)
}

fn parse_range(s: &str, min: i32, max: i32) -> Result<(i32, i32), String> {
    let mut parts = s.split('-');
    let lo: i32 = parts
        .next()
        .ok_or_else(|| "missing range start".to_string())?
        .parse()
        .map_err(|_| "bad lo".to_string())?;
    let hi: i32 = match parts.next() {
        Some(p) => p.parse().map_err(|_| "bad hi".to_string())?,
        None => lo,
    };
    if lo < min || hi > max {
        return Err(format!("range {}-{} out of bounds {}-{}", lo, hi, min, max));
    }
    Ok((lo, hi))
}

fn iso_dow(w: Weekday) -> u32 {
    match w {
        Weekday::Mon => 1,
        Weekday::Tue => 2,
        Weekday::Wed => 3,
        Weekday::Thu => 4,
        Weekday::Fri => 5,
        Weekday::Sat => 6,
        Weekday::Sun => 0,
    }
}

/// 按本机**本地时区**的墙钟时间计算下一次触发点。
///
/// 前端让用户输入的是本地墙钟（如 `0 8 * * *` = 每天本地 8 点），
/// 落盘时调用方会 `.with_timezone(&Utc).to_rfc3339()` 把本地墙钟转成
/// UTC 瞬间存储；前端 `toLocaleString` 显示时再按本机时区还原，
/// 因此显示的"下次运行"与用户设定一致。
///
/// 注意：以计算时刻的本机 UTC 偏移为准（对无夏令时的地区如中国
/// 完全稳定；跨夏令时切换当天可能有至多一次 ±1h 抖动，桌面端可接受）。
fn compute_next_fire(expr: &str, from: DateTime<Local>) -> Result<DateTime<Local>, String> {
    let parts: Vec<&str> = expr.split_whitespace().collect();
    if parts.len() != 5 {
        return Err("cron expression must have 5 fields".into());
    }
    let minute = parse_field(parts[0], 0, 59)?;
    let hour = parse_field(parts[1], 0, 23)?;
    let dom = parse_field(parts[2], 1, 31)?;
    let month = parse_field(parts[3], 1, 12)?;
    let dow = parse_field(parts[4], 0, 6)?;
    let dom_restricted = parts[2] != "*";
    let dow_restricted = parts[4] != "*";
    let mut candidate = from + chrono::Duration::minutes(1);
    for _ in 0..366 * 24 * 60 {
        if !minute.contains(&(candidate.minute() as i32)) {
            candidate += chrono::Duration::minutes(1);
            continue;
        }
        if !hour.contains(&(candidate.hour() as i32)) {
            candidate += chrono::Duration::minutes(1);
            continue;
        }
        if !month.contains(&(candidate.month() as i32)) {
            candidate += chrono::Duration::minutes(1);
            continue;
        }
        let dom_match = dom.contains(&(candidate.day() as i32));
        let dow_match = dow.contains(&(iso_dow(candidate.weekday()) as i32));
        let day_match = if dom_restricted && dow_restricted {
            dom_match || dow_match
        } else {
            dom_match && dow_match
        };
        if day_match {
            return Ok(candidate);
        }
        candidate += chrono::Duration::minutes(1);
    }
    Err("could not schedule within a year".into())
}

fn cron_display(expr: &str) -> String {
    expr.to_string()
}

/// 经 AgentLoop 跑 ReAct 循环（与 IM auto_reply 走相同路径）。
///
/// 服务器下发指令 / 定时任务走完整 ReAct 循环：LLM 返回 tool_calls →
/// AgentLoop 经 ToolRegistry2 并行执行（execute_skill / mcp_call /
/// memory_search / vlm_query 等）→ 结果以 role="tool" 回填 → 继续迭代
/// 直到纯文本回复。这样服务器下发指令和 IM 发起会话一样流畅，可以在
/// 客户端本地发起并完善的发起会话 tooling call 搜索调用技能执行任务。
///
/// AgentLoop 未注册时降级到原来的 `mcp_call_v2_inner` 简单 LLM 调用路径。
async fn run_cron_react(
    app: &AppHandle,
    http: &reqwest::Client,
    prompt: &str,
    token: Option<String>,
    session_id: &str,
) -> Result<String, String> {
    let token = token.ok_or_else(|| {
        "设备未登录/未绑定：定时任务需要 device_token 才能经 MCP 调用 LLM（请在设置中完成设备注册）".to_string()
    })?;

    // 优先走 AgentLoop ReAct 循环（支持 tooling call）。
    if let Some(agent_loop) = app.try_state::<Arc<AgentLoop>>() {
        let agent_loop = agent_loop.inner().clone();
        let system_prompt = "你是 tupAI 桌面助手，正在执行定时任务。请根据指令内容判断是否需要调用工具（execute_skill 执行技能 / mcp_call 调用 MCP / memory_search 搜索记忆 / vlm_query 视觉查询等）。如果指令需要执行技能或查询信息，请主动调用工具完成；如果是纯文本回复，直接回答。";
        let mut messages = vec![
            VLMMessage {
                role: "system".to_string(),
                content: system_prompt.to_string(),
                ..Default::default()
            },
            VLMMessage {
                role: "user".to_string(),
                content: prompt.to_string(),
                ..Default::default()
            },
        ];
        match agent_loop.run(&mut messages, session_id, Some(&token)).await {
            Ok(reply) => {
                let _ = app.emit("cron_local_react_done", serde_json::json!({
                    "session_id": session_id,
                    "reply_length": reply.len(),
                    "tool_calls_made": messages.iter().filter(|m| m.role == "tool").count(),
                }));
                return Ok(reply);
            }
            Err(e) => {
                log::warn!("[cron_local] AgentLoop.run failed, falling back to simple LLM: {}", e);
            }
        }
    }

    // 降级路径：直接 mcp_call_v2_inner 简单 LLM 调用（无 tooling call）。
    let params = serde_json::json!({
        "session_id": session_id,
        "messages": [ { "role": "user", "content": prompt } ],
        "stream": true,
    });
    let resp = mcp_call_v2_inner(http, "llm.stream_request", params, Some(&token)).await?;
    let content = resp
        .get("data")
        .and_then(|d| d.get("content"))
        .and_then(|c| c.as_str())
        .unwrap_or("");
    let content = content.trim();
    if content.is_empty() {
        if let Some(e) = resp.get("error") {
            let msg = e
                .get("message")
                .and_then(|m| m.as_str())
                .or_else(|| e.as_str())
                .unwrap_or("LLM 调用失败");
            return Err(format!("LLM 调用失败：{}", msg));
        }
        if resp.get("ok").and_then(|v| v.as_bool()) == Some(false) {
            return Err(
                "LLM 调用失败（服务器返回 ok=false，可能是鉴权失败 / 限流 / 模型未配置）".to_string(),
            );
        }
        return Err(
            "LLM 返回内容为空（可能是鉴权失败 / 限流 / 模型未配置）".to_string(),
        );
    }
    Ok(content.to_string())
}

// ========================
// Tauri 命令
// ========================

#[tauri::command]
pub async fn cron_local_list(
    state: tauri::State<'_, Arc<CronLocalState>>,
) -> Result<Vec<CronJob>, String> {
    Ok(state.list().await)
}

#[tauri::command]
pub async fn cron_local_create(
    state: tauri::State<'_, Arc<CronLocalState>>,
    input: CreateCronJobInput,
) -> Result<CronJob, String> {
    state.create(input).await
}

#[tauri::command]
pub async fn cron_local_pause(
    state: tauri::State<'_, Arc<CronLocalState>>,
    id: String,
) -> Result<CronActionResult, String> {
    state.set_enabled(&id, false).await?;
    Ok(CronActionResult { ok: true })
}

#[tauri::command]
pub async fn cron_local_resume(
    state: tauri::State<'_, Arc<CronLocalState>>,
    id: String,
) -> Result<CronActionResult, String> {
    state.set_enabled(&id, true).await?;
    Ok(CronActionResult { ok: true })
}

#[tauri::command]
pub async fn cron_local_trigger(
    state: tauri::State<'_, Arc<CronLocalState>>,
    input: TriggerCronJobInput,
) -> Result<CronActionResult, String> {
    let me: Arc<CronLocalState> = (*state).clone();
    me.trigger(&input.id, "manual", input.token)
        .await?;
    Ok(CronActionResult { ok: true })
}

/// 前端把当前 device_token 透传给后端定时任务调度器。
/// 定时任务经 MCP `llm.stream_request` 调 LLM 时需要它做 Bearer 鉴权，
/// 服务器据此自动匹配模型（客户端不配置 provider / api_key / model）。
///
/// 调用时机：进入定时任务面板、手动触发、以及窗口聚焦 / 可见性变化时
/// 刷新。进程内存态、非持久化；应用重启后首刷前为空（届时触发会给出
/// "设备未登录/未绑定"的明确错误，而非静默失败）。
#[tauri::command]
pub async fn cron_local_set_token(
    state: tauri::State<'_, Arc<CronLocalState>>,
    token: String,
) -> Result<CronActionResult, String> {
    state.set_token(if token.trim().is_empty() { None } else { Some(token) }).await;
    Ok(CronActionResult { ok: true })
}

#[tauri::command]
pub async fn cron_local_delete(
    state: tauri::State<'_, Arc<CronLocalState>>,
    id: String,
) -> Result<CronActionResult, String> {
    state.delete(&id).await?;
    Ok(CronActionResult { ok: true })
}

#[tauri::command]
pub async fn cron_local_get_runs(
    state: tauri::State<'_, Arc<CronLocalState>>,
    id: String,
    limit: Option<usize>,
) -> Result<Vec<CronRun>, String> {
    Ok(state.read_runs(&id, limit.unwrap_or(200)).await)
}

#[tauri::command]
pub async fn cron_local_clear_runs(
    state: tauri::State<'_, Arc<CronLocalState>>,
    id: String,
) -> Result<CronActionResult, String> {
    state.clear_runs(&id).await?;
    Ok(CronActionResult { ok: true })
}

// 避免 IDE 报 unused 警告 (CronActionResult 是命令返回类型但 unused_imports 不会报 fn-level)
#[allow(dead_code)]
fn _ensure_types_used(_: CronActionResult) {}
