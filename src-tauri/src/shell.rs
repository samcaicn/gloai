use std::process::{Command, Stdio};
use tokio::process::Command as AsyncCommand;

pub struct ShellExecutor;

impl ShellExecutor {
    pub fn new() -> Self {
        Self
    }

    pub async fn execute_command_async(
        &self,
        command: &str,
        args: &[&str],
        working_dir: Option<&str>,
    ) -> anyhow::Result<(String, String, i32)> {
        let mut cmd = AsyncCommand::new(command);
        cmd.args(args)
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());

        if let Some(dir) = working_dir {
            cmd.current_dir(dir);
        }

        let output = cmd.output().await?;
        let stdout = String::from_utf8_lossy(&output.stdout).to_string();
        let stderr = String::from_utf8_lossy(&output.stderr).to_string();
        let exit_code = output.status.code().unwrap_or(-1);

        Ok((stdout, stderr, exit_code))
    }

    pub fn execute_command_sync(
        &self,
        command: &str,
        args: &[&str],
        working_dir: Option<&str>,
    ) -> anyhow::Result<(String, String, i32)> {
        let mut cmd = Command::new(command);
        cmd.args(args)
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());

        if let Some(dir) = working_dir {
            cmd.current_dir(dir);
        }

        let output = cmd.output()?;
        let stdout = String::from_utf8_lossy(&output.stdout).to_string();
        let stderr = String::from_utf8_lossy(&output.stderr).to_string();
        let exit_code = output.status.code().unwrap_or(-1);

        Ok((stdout, stderr, exit_code))
    }

    pub async fn start_process_detached(
        &self,
        command: &str,
        args: &[&str],
        working_dir: Option<&str>,
    ) -> anyhow::Result<()> {
        let mut cmd = Command::new(command);
        cmd.args(args);

        if let Some(dir) = working_dir {
            cmd.current_dir(dir);
        }

        #[cfg(target_os = "windows")]
        {
            use std::os::windows::process::CommandExt;
            cmd.creation_flags(0x08000000); // CREATE_NO_WINDOW
        }

        #[cfg(target_os = "linux")]
        {
            cmd.stdout(Stdio::null());
            cmd.stderr(Stdio::null());
        }

        #[cfg(target_os = "macos")]
        {
            cmd.stdout(Stdio::null());
            cmd.stderr(Stdio::null());
        }

        cmd.spawn()?;
        Ok(())
    }
}

impl Default for ShellExecutor {
    fn default() -> Self {
        Self::new()
    }
}
