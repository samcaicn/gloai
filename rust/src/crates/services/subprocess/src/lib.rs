//! Local process spawn with timeout and cancellation.

use std::process::Stdio;
use std::time::Duration;

use async_trait::async_trait;
use dsh_runtime_ports::{
    PortError, PortErrorKind, PortResult, SubprocessPort, SubprocessRequest, SubprocessResult,
};
use tokio::io::AsyncWriteExt;
use tokio::process::Command;
use tokio::time::timeout;

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
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        if request.stdin.is_some() {
            command.stdin(Stdio::piped());
        }
        let mut child = command
            .spawn()
            .map_err(|error| PortError::new(PortErrorKind::Backend, error.to_string()))?;
        if let Some(stdin_text) = request.stdin {
            if let Some(mut stdin) = child.stdin.take() {
                stdin
                    .write_all(stdin_text.as_bytes())
                    .await
                    .map_err(|error| PortError::new(PortErrorKind::Backend, error.to_string()))?;
            }
        }
        match timeout(
            Duration::from_millis(request.timeout_ms),
            child.wait_with_output(),
        )
        .await
        {
            Ok(Ok(output)) => Ok(SubprocessResult {
                exit_code: output.status.code().unwrap_or(-1),
                stdout: String::from_utf8_lossy(&output.stdout).into_owned(),
                stderr: String::from_utf8_lossy(&output.stderr).into_owned(),
                timed_out: false,
            }),
            Ok(Err(error)) => Err(PortError::new(PortErrorKind::Backend, error.to_string())),
            Err(_) => Ok(SubprocessResult {
                exit_code: -1,
                stdout: String::new(),
                stderr: format!("killed after {}ms", request.timeout_ms),
                timed_out: true,
            }),
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
