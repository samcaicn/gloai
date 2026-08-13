//! Local process spawn with timeout and cancellation.

use async_trait::async_trait;
use dsh_runtime_ports::{
    PortError, PortErrorKind, PortResult, SubprocessPort, SubprocessRequest, SubprocessResult,
};
use tokio::io::AsyncReadExt;
use tokio::process::Command;
use tokio::time::timeout;
use std::time::Duration;

#[derive(Clone, Default)]
pub struct LocalSubprocess;

#[async_trait]
impl SubprocessPort for LocalSubprocess {
    async fn run(&self, request: SubprocessRequest) -> PortResult<SubprocessResult> {
        let mut command = Command::new(&request.program);
        command
            .args(&request.args)
            .current_dir(&request.cwd)
            .kill_on_drop(true)
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped());
        if request.stdin.is_some() {
            command.stdin(std::process::Stdio::piped());
        }
        let mut child = command.spawn().map_err(|error| {
            PortError::new(PortErrorKind::Backend, error.to_string())
        })?;
        if let Some(stdin_text) = request.stdin {
            if let Some(mut stdin) = child.stdin.take() {
                use tokio::io::AsyncWriteExt;
                stdin
                    .write_all(stdin_text.as_bytes())
                    .await
                    .map_err(|error| PortError::new(PortErrorKind::Backend, error.to_string()))?;
            }
        }
        let wait = async {
            let status = child.wait().await.map_err(|error| {
                PortError::new(PortErrorKind::Backend, error.to_string())
            })?;
            let mut stdout = String::new();
            let mut stderr = String::new();
            if let Some(mut out) = child.stdout.take() {
                let mut buf = Vec::new();
                out.read_to_end(&mut buf).await.map_err(|error| {
                    PortError::new(PortErrorKind::Backend, error.to_string())
                })?;
                stdout = String::from_utf8_lossy(&buf).into_owned();
            }
            if let Some(mut err) = child.stderr.take() {
                let mut buf = Vec::new();
                err.read_to_end(&mut buf).await.map_err(|error| {
                    PortError::new(PortErrorKind::Backend, error.to_string())
                })?;
                stderr = String::from_utf8_lossy(&buf).into_owned();
            }
            Ok::<_, PortError>(SubprocessResult {
                exit_code: status.code().unwrap_or(-1),
                stdout,
                stderr,
                timed_out: false,
            })
        };
        match timeout(Duration::from_millis(request.timeout_ms), wait).await {
            Ok(result) => result,
            Err(_) => {
                let _ = child.start_kill();
                Ok(SubprocessResult {
                    exit_code: -1,
                    stdout: String::new(),
                    stderr: format!("killed after {}ms", request.timeout_ms),
                    timed_out: true,
                })
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn echoes_stdout() {
        let port = LocalSubprocess;
        let result = port
            .run(SubprocessRequest {
                program: "echo".into(),
                args: vec!["hello-dsh".into()],
                cwd: std::env::temp_dir(),
                timeout_ms: 5_000,
                stdin: None,
            })
            .await
            .unwrap();
        assert_eq!(result.exit_code, 0);
        assert!(result.stdout.contains("hello-dsh"));
        assert!(!result.timed_out);
    }
}
