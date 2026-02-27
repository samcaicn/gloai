use crate::database::Database;
use chrono::Local;
use cron::Schedule;
use serde::{Deserialize, Serialize};
use std::str::FromStr;
use std::sync::{Arc, Mutex as StdMutex};
use std::thread;
use std::time::{Duration, Instant};
use tokio::sync::Mutex as TokioMutex;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScheduledTask {
    pub id: String,
    pub name: String,
    pub description: Option<String>,
    pub cron_expression: String,
    pub enabled: bool,
    pub created_at: i64,
    pub updated_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TaskRun {
    pub id: String,
    pub task_id: String,
    pub status: String,
    pub start_time: i64,
    pub end_time: Option<i64>,
    pub output: Option<String>,
    pub error: Option<String>,
}

pub struct Scheduler {
    database: Arc<TokioMutex<Database>>,
    running: Arc<StdMutex<bool>>,
    shutdown: Arc<StdMutex<bool>>,
}

impl Scheduler {
    pub fn new(database: Arc<TokioMutex<Database>>) -> Self {
        Scheduler {
            database,
            running: Arc::new(StdMutex::new(false)),
            shutdown: Arc::new(StdMutex::new(false)),
        }
    }

    pub async fn start(&self) -> Result<(), String> {
        let mut running = self.running.lock().unwrap();
        if *running {
            return Ok(());
        }

        *running = true;
        let mut shutdown = self.shutdown.lock().unwrap();
        *shutdown = false;

        let database_clone = self.database.clone();
        let shutdown_clone = self.shutdown.clone();

        thread::spawn(move || {
            let mut last_check = Instant::now();

            while !*shutdown_clone.lock().unwrap() {
                if last_check.elapsed() >= Duration::from_secs(60) {
                    Self::check_and_execute_tasks(&database_clone);
                    last_check = Instant::now();
                }

                thread::sleep(Duration::from_secs(1));
            }
        });

        Ok(())
    }

    pub async fn stop(&self) -> Result<(), String> {
        let mut shutdown = self.shutdown.lock().unwrap();
        *shutdown = true;

        let mut running = self.running.lock().unwrap();
        *running = false;

        Ok(())
    }

    pub fn is_running(&self) -> bool {
        *self.running.lock().unwrap()
    }

    pub fn list_tasks(&self) -> Result<Vec<ScheduledTask>, String> {
        let db = self.database.blocking_lock();
        match db.scheduled_tasks_list() {
            Ok(tasks) => {
                let tasks: Vec<ScheduledTask> = tasks
                    .into_iter()
                    .filter_map(|task| serde_json::from_value(task).ok())
                    .collect();
                Ok(tasks)
            }
            Err(e) => Err(e.to_string()),
        }
    }

    pub fn create_task(&self, id: &str, name: &str, cron_expression: &str) -> Result<(), String> {
        if let Err(e) = Schedule::from_str(cron_expression) {
            return Err(format!("Invalid cron expression: {}", e));
        }

        let db = self.database.blocking_lock();
        db.scheduled_task_create(id, name, cron_expression)
            .map_err(|e| e.to_string())
    }

    pub fn delete_task(&self, task_id: &str) -> Result<(), String> {
        let db = self.database.blocking_lock();
        db.scheduled_task_delete(task_id).map_err(|e| e.to_string())
    }

    pub fn update_task(&self, task_id: &str, enabled: bool) -> Result<(), String> {
        let db = self.database.blocking_lock();
        db.scheduled_task_update_enabled(task_id, enabled)
            .map_err(|e| e.to_string())
    }

    pub fn list_task_runs(&self, task_id: Option<&str>) -> Result<Vec<TaskRun>, String> {
        let db = self.database.blocking_lock();
        match db.task_runs_list(task_id) {
            Ok(runs) => {
                let runs: Vec<TaskRun> = runs
                    .into_iter()
                    .filter_map(|run| serde_json::from_value(run).ok())
                    .collect();
                Ok(runs)
            }
            Err(e) => Err(e.to_string()),
        }
    }

    pub fn execute_task(&self, task_id: &str) -> Result<String, String> {
        let run_id = format!("run_{}_{}", task_id, Local::now().timestamp_millis());

        let db = self.database.blocking_lock();
        db.task_run_create(&run_id, task_id)
            .map_err(|e| e.to_string())?;
        drop(db);

        let database = self.database.clone();
        let run_id_clone = run_id.clone();

        thread::spawn(move || {
            thread::sleep(Duration::from_secs(2));

            let db = database.blocking_lock();
            db.task_run_complete(&run_id_clone, "completed", Some("任务执行成功"), None)
                .unwrap_or_else(|e| println!("Failed to complete task run: {}", e));
        });

        Ok(run_id)
    }

    fn check_and_execute_tasks(database: &Arc<TokioMutex<Database>>) {
        println!("[Scheduler] Checking tasks at: {}", Local::now());

        let db = database.blocking_lock();
        let tasks = match db.scheduled_tasks_list() {
            Ok(tasks) => tasks,
            Err(e) => {
                println!("[Scheduler] Error listing tasks: {}", e);
                return;
            }
        };
        drop(db);

        let now = Local::now();

        for task_value in tasks {
            let task_result: Result<ScheduledTask, _> = serde_json::from_value(task_value.clone());
            if let Ok(task) = task_result {
                if !task.enabled {
                    continue;
                }

                if let Ok(schedule) = Schedule::from_str(&task.cron_expression) {
                    let next_time = schedule.upcoming(Local).next();

                    if let Some(next) = next_time {
                        let time_diff = next - now;

                        if time_diff.num_seconds() <= 60 && time_diff.num_seconds() >= 0 {
                            println!("[Scheduler] Executing task: {} ({})", task.name, task.id);

                            let run_id =
                                format!("run_{}_{}", task.id, Local::now().timestamp_millis());

                            let db = database.blocking_lock();
                            if let Err(e) = db.task_run_create(&run_id, &task.id) {
                                println!("[Scheduler] Error creating task run: {}", e);
                                continue;
                            }
                            drop(db);

                            let db_clone = database.clone();
                            let run_id_clone = run_id.clone();

                            thread::spawn(move || {
                                thread::sleep(Duration::from_secs(2));

                                let db = db_clone.blocking_lock();
                                let _ = db.task_run_complete(
                                    &run_id_clone,
                                    "completed",
                                    Some("Scheduled task executed successfully"),
                                    None,
                                );
                            });
                        }
                    }
                }
            }
        }
    }
}
