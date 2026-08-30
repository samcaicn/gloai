// Skill execution engine — makes SKILL.md actually runnable.
//
// Executes the `exec` actions in each step:
//   - Shell    : spawn process, capture stdout/stderr
//   - FileRead : read file content to string
//   - FileWrite: write string to file (creates parent dirs)
//   - FileSearch: regex search in file
//   - FileReplace: find-and-replace in file
//   - DirList  : list directory entries
//   - Wait     : sleep for N milliseconds
//   - HttpGet  : HTTP GET request
//   - Echo     : no-op debug output
//
// Records:
//   - Per-step results (action/target/value) → JSON in worker_task_log.result
//   - Full execution log → worker_task_log (consumed by AutoSkill LogMiner)

use std::fs;
use std::path::Path;
use std::process::Command;
use std::sync::Arc;
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};

use crate::skill::manifest::{ExecAction, SkillManifest, Step};
use crate::skill::sandbox::Sandbox;
use crate::storage::{ExecutionLog, Storage};

/// Errors that can occur during skill execution.
#[derive(Debug, thiserror::Error)]
pub enum ExecError {
    #[error("Storage error: {0}")]
    Storage(#[from] crate::storage::StorageError),

    #[error("SQLite error: {0}")]
    Sqlite(#[from] rusqlite::Error),

    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),

    #[error("Step '{id}' failed: {reason}")]
    StepFailed { id: String, reason: String },

    #[error("All steps have no exec actions — nothing to run")]
    NoExecutableSteps,

    #[error("HTTP error: {0}")]
    Http(#[from] reqwest::Error),

    #[error("Permission denied: {0}")]
    PermissionDenied(#[from] crate::skill::sandbox::SandboxError),
}

pub type Result<T> = std::result::Result<T, ExecError>;

/// Result of executing a single step.
///
/// Serializable to JSON and stored in worker_task_log.result
/// (consumed by AutoSkill LogMiner as TraceStep).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StepResult {
    /// Action type: "shell" | "file_read" | "file_write" | etc.
    pub action: String,
    /// Target: command name, file path, URL, etc.
    pub target: String,
    /// Optional value: output, content, etc.
    pub value: Option<String>,
    /// Whether this step succeeded.
    pub success: bool,
    /// Execution duration in milliseconds.
    pub duration_ms: u64,
    /// Error message if failed.
    pub error: Option<String>,
}

/// Full execution result for a skill run.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExecutionResult {
    pub task_id: String,
    pub skill_id: String,
    pub scene: String,
    pub status: String, // "succeeded" | "failed"
    pub steps: Vec<StepResult>,
    pub total_duration_ms: u64,
    pub started_at: String,
    pub finished_at: String,
}

/// The skill execution engine.
pub struct SkillExecutor {
    storage: Arc<Storage>,
    /// Default working directory for shell commands.
    default_cwd: Option<String>,
    /// HTTP client for HttpGet actions.
    http_client: Option<reqwest::Client>,
    /// Permission sandbox (None = no checks).
    sandbox: Option<Sandbox>,
}

impl SkillExecutor {
    /// Create a new executor with storage backend.
    pub fn new(storage: Arc<Storage>) -> Self {
        Self {
            storage,
            default_cwd: None,
            http_client: None,
            sandbox: None,
        }
    }

    /// Set the default working directory for shell commands.
    pub fn with_default_cwd(mut self, cwd: String) -> Self {
        self.default_cwd = Some(cwd);
        self
    }

    /// Enable HTTP support (for HttpGet actions).
    pub fn with_http(mut self) -> Self {
        self.http_client = Some(reqwest::Client::new());
        self
    }

    /// Set the permission sandbox for this executor.
    pub fn with_sandbox(mut self, sandbox: Sandbox) -> Self {
        self.sandbox = Some(sandbox);
        self
    }

    /// Execute a full skill manifest and record the log.
    pub async fn execute(
        &self,
        scene: &str,
        manifest: &SkillManifest,
    ) -> Result<ExecutionResult> {
        let started_at = chrono::Utc::now();
        let task_id = uuid::Uuid::new_v4().to_string();
        let skill_id = manifest.name.clone();

        // Check that at least one step has an exec action
        let has_exec = manifest.steps.iter().any(|s| s.exec.is_some());
        if !has_exec {
            return Err(ExecError::NoExecutableSteps);
        }

        // Validate permissions against sandbox (if configured)
        if let Some(sandbox) = &self.sandbox {
            sandbox.validate_manifest(manifest).map_err(ExecError::PermissionDenied)?;
        }

        let mut step_results: Vec<StepResult> = Vec::new();
        let start = Instant::now();

        for step in &manifest.steps {
            let result = self.execute_step(step).await;
            let success = result.success;
            step_results.push(result);
            if !success {
                // Record failed execution and return early
                let exec_result = self.build_result(
                    &task_id,
                    &skill_id,
                    scene,
                    &step_results,
                    start.elapsed(),
                    &started_at,
                );
                self.record_log(&exec_result)?;
                return Err(ExecError::StepFailed {
                    id: step.id.clone(),
                    reason: step_results.last().unwrap().error.clone().unwrap_or_else(|| "unknown error".into()),
                });
            }
        }

        let exec_result = self.build_result(
            &task_id,
            &skill_id,
            scene,
            &step_results,
            start.elapsed(),
            &started_at,
        );
        self.record_log(&exec_result)?;

        Ok(exec_result)
    }

    /// Execute a single step's `exec` action.
    pub async fn execute_step(&self, step: &Step) -> StepResult {
        let exec = match &step.exec {
            Some(e) => e,
            None => {
                return StepResult {
                    action: "skip".into(),
                    target: step.id.clone(),
                    value: None,
                    success: true,
                    duration_ms: 0,
                    error: None,
                };
            }
        };

        let start = Instant::now();
        let result = match exec {
            ExecAction::Shell { command, args, cwd } => self.exec_shell(command, args, cwd.as_deref()),
            ExecAction::FileRead { path } => self.exec_file_read(path),
            ExecAction::FileWrite { path, content } => self.exec_file_write(path, content),
            ExecAction::FileSearch { path, pattern } => self.exec_file_search(path, pattern),
            ExecAction::FileReplace { path, from, to } => {
                self.exec_file_replace(path, from, to.as_deref().unwrap_or(""))
            }
            ExecAction::DirList { path, recursive } => self.exec_dir_list(path, *recursive),
            ExecAction::Wait { ms } => self.exec_wait(*ms),
            ExecAction::HttpGet { url } => self.exec_http_get(url).await,
            ExecAction::Echo { message } => self.exec_echo(message),
        };

        let duration_ms = start.elapsed().as_millis() as u64;
        match result {
            Ok((target, value)) => StepResult {
                action: exec.action_name().into(),
                target,
                value,
                success: true,
                duration_ms,
                error: None,
            },
            Err(e) => StepResult {
                action: exec.action_name().into(),
                target: step.id.clone(),
                value: None,
                success: false,
                duration_ms,
                error: Some(e.to_string()),
            },
        }
    }

    // ============================================================
    // Action implementations
    // ============================================================

    fn exec_shell(
        &self,
        command: &str,
        args: &[String],
        cwd: Option<&str>,
    ) -> std::result::Result<(String, Option<String>), ExecError> {
        let mut cmd = Command::new(command);
        cmd.args(args);
        cmd.stdout(std::process::Stdio::piped());
        cmd.stderr(std::process::Stdio::piped());

        // Set working directory: step-level > executor default > unset
        if let Some(dir) = cwd.or(self.default_cwd.as_deref()) {
            cmd.current_dir(dir);
        }

        let output = cmd.output()?;
        let stdout = String::from_utf8_lossy(&output.stdout).trim().to_string();
        let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();

        let target = format!("{} {}", command, args.join(" "));
        if output.status.success() {
            let value = if stdout.is_empty() {
               Some(stderr).filter(|s| !s.is_empty())
            } else {
                Some(stdout)
            };
            Ok((target, value))
        } else {
            let err_msg = if stderr.is_empty() {
                format!("exit code: {:?}", output.status.code())
            } else {
                stderr
            };
            Err(ExecError::StepFailed {
                id: target,
                reason: err_msg,
            })
        }
    }

    fn exec_file_read(&self, path: &str) -> std::result::Result<(String, Option<String>), ExecError> {
        let content = fs::read_to_string(path)?;
        Ok((path.to_string(), Some(content)))
    }

    fn exec_file_write(
        &self,
        path: &str,
        content: &str,
    ) -> std::result::Result<(String, Option<String>), ExecError> {
        // Create parent directories if needed
        if let Some(parent) = Path::new(path).parent() {
            fs::create_dir_all(parent)?;
        }
        fs::write(path, content)?;
        Ok((path.to_string(), Some(format!("{} bytes written", content.len()))))
    }

    fn exec_file_search(
        &self,
        path: &str,
        pattern: &str,
    ) -> std::result::Result<(String, Option<String>), ExecError> {
        let content = fs::read_to_string(path)?;
        let re = regex::Regex::new(pattern)
            .map_err(|e| ExecError::StepFailed {
                id: path.to_string(),
                reason: format!("invalid regex: {}", e),
            })?;
        let matches: Vec<String> = content
            .lines()
            .enumerate()
            .filter_map(|(i, line)| {
                if re.is_match(line) {
                    Some(format!("{}: {}", i + 1, line))
                } else {
                    None
                }
            })
            .collect();
        Ok((path.to_string(), Some(matches.join("\n"))))
    }

    fn exec_file_replace(
        &self,
        path: &str,
        from: &str,
        to: &str,
    ) -> std::result::Result<(String, Option<String>), ExecError> {
        let content = fs::read_to_string(path)?;
        let new_content = content.replace(from, to);
        fs::write(path, &new_content)?;
        let count = content.matches(from).count();
        Ok((path.to_string(), Some(format!("{} replacements", count))))
    }

    fn exec_dir_list(
        &self,
        path: &str,
        recursive: bool,
    ) -> std::result::Result<(String, Option<String>), ExecError> {
        let entries = if recursive {
            self.collect_dir_recursive(path, 0)?
        } else {
            let mut entries = Vec::new();
            for entry in fs::read_dir(path)? {
                let entry = entry?;
                let name = entry.file_name().to_string_lossy().into_owned();
                let kind = if entry.file_type()?.is_dir() { "dir" } else { "file" };
                entries.push(format!("[{}] {}", kind, name));
            }
            entries
        };
        Ok((path.to_string(), Some(entries.join("\n"))))
    }

    fn collect_dir_recursive(
        &self,
        path: &str,
        depth: usize,
    ) -> std::result::Result<Vec<String>, std::io::Error> {
        let mut entries = Vec::new();
        for entry in fs::read_dir(path)? {
            let entry = entry?;
            let name = entry.file_name().to_string_lossy().into_owned();
            let indent = "  ".repeat(depth);
            if entry.file_type()?.is_dir() {
                entries.push(format!("{}[dir]  {}/", indent, name));
                entries.extend(self.collect_dir_recursive(
                    &format!("{}/{}", path.trim_end_matches('/'), name),
                    depth + 1,
                )?);
            } else {
                let size = entry.metadata().map(|m| m.len()).unwrap_or(0);
                entries.push(format!("{}[file] {} ({} bytes)", indent, name, size));
            }
        }
        Ok(entries)
    }

    fn exec_wait(&self, ms: u64) -> std::result::Result<(String, Option<String>), ExecError> {
        std::thread::sleep(Duration::from_millis(ms));
        Ok((format!("wait {}ms", ms), None))
    }

    async fn exec_http_get(
        &self,
        url: &str,
    ) -> std::result::Result<(String, Option<String>), ExecError> {
        let client = self.http_client.as_ref().ok_or_else(|| ExecError::StepFailed {
            id: url.to_string(),
            reason: "HTTP client not enabled — call .with_http() first".into(),
        })?;
        let resp = client.get(url).send().await.map_err(|e| ExecError::StepFailed {
            id: url.to_string(),
            reason: format!("request failed: {}", e),
        })?;
        let status = resp.status().as_u16();
        let body = resp.text().await.map_err(|e| ExecError::StepFailed {
            id: url.to_string(),
            reason: format!("read body failed: {}", e),
        })?;
        Ok((url.to_string(), Some(format!("HTTP {}\n{}", status, body))))
    }

    fn exec_echo(
        &self,
        message: &str,
    ) -> std::result::Result<(String, Option<String>), ExecError> {
        log::info!("[skill:echo] {}", message);
        Ok(("echo".to_string(), Some(message.to_string())))
    }

    // ============================================================
    // Internal helpers
    // ============================================================

    fn build_result(
        &self,
        task_id: &str,
        skill_id: &str,
        scene: &str,
        steps: &[StepResult],
        duration: Duration,
        started_at: &chrono::DateTime<chrono::Utc>,
    ) -> ExecutionResult {
        let finished_at = chrono::Utc::now();
        let all_success = steps.iter().all(|s| s.success);
        ExecutionResult {
            task_id: task_id.to_string(),
            skill_id: skill_id.to_string(),
            scene: scene.to_string(),
            status: if all_success { "succeeded".into() } else { "failed".into() },
            steps: steps.to_vec(),
            total_duration_ms: duration.as_millis() as u64,
            started_at: started_at.to_rfc3339(),
            finished_at: finished_at.to_rfc3339(),
        }
    }

    /// Record the execution result into worker_task_log.
    fn record_log(&self, result: &ExecutionResult) -> Result<()> {
        let log = ExecutionLog {
            id: result.task_id.clone(),
            scene: result.scene.clone(),
            skill_id: result.skill_id.clone(),
            status: result.status.clone(),
            params: None,
            duration_ms: result.total_duration_ms as i64,
            result: Some(serde_json::to_string(result).unwrap_or_default()),
            user_rating: None,
            created_at: result.finished_at.clone(),
        };

        let conn = self.storage.conn();
        conn.execute(
            "INSERT INTO worker_task_log (id, scene, skill_id, status, params, duration_ms, result, user_rating, created_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
            rusqlite::params![
                &log.id,
                &log.scene,
                &log.skill_id,
                &log.status,
                &log.params,
                log.duration_ms,
                &log.result,
                &log.user_rating,
                &log.created_at,
            ],
        )?;
        Ok(())
    }
}

impl ExecAction {
    /// Returns the action type name for logging.
    pub fn action_name(&self) -> &'static str {
        match self {
            ExecAction::Shell { .. } => "shell",
            ExecAction::FileRead { .. } => "file_read",
            ExecAction::FileWrite { .. } => "file_write",
            ExecAction::FileSearch { .. } => "file_search",
            ExecAction::FileReplace { .. } => "file_replace",
            ExecAction::DirList { .. } => "dir_list",
            ExecAction::Wait { .. } => "wait",
            ExecAction::HttpGet { .. } => "http_get",
            ExecAction::Echo { .. } => "echo",
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::storage::Storage;

    fn test_storage() -> Arc<Storage> {
        Arc::new(Storage::open_in_memory().unwrap())
    }

    fn simple_manifest() -> SkillManifest {
        SkillManifest {
            name: "test-skill".into(),
            description: Some("A test skill".into()),
            preferred_execution_type: crate::skill::manifest::ExecutionType::SystemSoftware,
            software_name: Some("cmd".into()),
            steps: vec![
                Step {
                    id: "echo".into(),
                    description: "Echo hello".into(),
                    exec: Some(ExecAction::Echo { message: "hello".into() }),
                    ..Default::default()
                },
                Step {
                    id: "wait".into(),
                    description: "Wait 10ms".into(),
                    exec: Some(ExecAction::Wait { ms: 10 }),
                    ..Default::default()
                },
            ],
            ..Default::default()
        }
    }

    #[tokio::test]
    async fn execute_echo_and_wait() {
        let storage = test_storage();
        let executor = SkillExecutor::new(storage);
        let manifest = simple_manifest();
        let result = executor.execute("default", &manifest).await.unwrap();
        assert_eq!(result.status, "succeeded");
        assert_eq!(result.steps.len(), 2);
        assert_eq!(result.steps[0].action, "echo");
        assert_eq!(result.steps[1].action, "wait");
    }

    #[tokio::test]
    async fn execute_shell_echo() {
        let storage = test_storage();
        let executor = SkillExecutor::new(storage);
        let manifest = SkillManifest {
            name: "shell-test".into(),
            preferred_execution_type: crate::skill::manifest::ExecutionType::SystemSoftware,
            software_name: Some("cmd".into()),
            steps: vec![Step {
                id: "run".into(),
                description: "Run echo".into(),
                exec: Some(ExecAction::Shell {
                    command: if cfg!(windows) { "cmd".to_string() } else { "echo".to_string() },
                    args: if cfg!(windows) {
                        vec!["/c".into(), "echo hello".into()]
                    } else {
                        vec!["hello".into()]
                    },
                    cwd: None,
                }),
                ..Default::default()
            }],
            ..Default::default()
        };
        let result = executor.execute("default", &manifest).await.unwrap();
        assert_eq!(result.status, "succeeded");
        assert!(result.steps[0].value.as_ref().unwrap().contains("hello"));
    }

    #[tokio::test]
    async fn execute_file_write_and_read() {
        let storage = test_storage();
        let executor = SkillExecutor::new(storage);

        // Use a temp path that works on the platform
        let tmp_dir = std::env::temp_dir();
        let test_file = tmp_dir.join("dsh_executor_test.txt");
        let _ = fs::remove_file(&test_file); // Clean up if exists
        let path_str = test_file.to_string_lossy().to_string();

        let manifest = SkillManifest {
            name: "file-test".into(),
            preferred_execution_type: crate::skill::manifest::ExecutionType::SystemSoftware,
            software_name: Some("cmd".into()),
            steps: vec![
                Step {
                    id: "write".into(),
                    description: "Write file".into(),
                    exec: Some(ExecAction::FileWrite {
                        path: path_str.clone(),
                        content: "DSH test content".into(),
                    }),
                    ..Default::default()
                },
                Step {
                    id: "read".into(),
                    description: "Read file".into(),
                    exec: Some(ExecAction::FileRead {
                        path: path_str.clone(),
                    }),
                    ..Default::default()
                },
            ],
            ..Default::default()
        };

        let result = executor.execute("default", &manifest).await.unwrap();
        assert_eq!(result.status, "succeeded");
        assert!(result.steps[1].value.as_ref().unwrap().contains("DSH test content"));

        // Cleanup
        let _ = fs::remove_file(&test_file);
    }

    #[tokio::test]
    async fn execute_no_exec_actions_fails() {
        let storage = test_storage();
        let executor = SkillExecutor::new(storage);
        let manifest = SkillManifest {
            name: "empty".into(),
            preferred_execution_type: crate::skill::manifest::ExecutionType::SystemSoftware,
            software_name: Some("cmd".into()),
            steps: vec![Step {
                id: "no-op".into(),
                description: "No exec action".into(),
                ..Default::default()
            }],
            ..Default::default()
        };
        let result = executor.execute("default", &manifest).await;
        assert!(matches!(result, Err(ExecError::NoExecutableSteps)));
    }

    #[tokio::test]
    async fn execute_records_log_to_db() {
        let storage = test_storage();
        let executor = SkillExecutor::new(storage.clone());
        let manifest = simple_manifest();
        let result = executor.execute("test-scene", &manifest).await.unwrap();

        // Verify the log was written to worker_task_log
        let conn = storage.conn();
        let count: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM worker_task_log WHERE id = ?1",
                rusqlite::params![&result.task_id],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(count, 1);

        // Verify result JSON contains steps
        let result_json: String = conn
            .query_row(
                "SELECT result FROM worker_task_log WHERE id = ?1",
                rusqlite::params![&result.task_id],
                |row| row.get(0),
            )
            .unwrap();
        let parsed: ExecutionResult = serde_json::from_str(&result_json).unwrap();
        assert_eq!(parsed.steps.len(), 2);
    }
}
