//! `dsh` launcher: headless task, config dump, and ACP stdio.

use std::path::PathBuf;
use std::sync::Arc;

use clap::{Parser, Subcommand};
use dsh_acp::serve_stdio;
use dsh_core::{DeliveryProfile, LlmBackend, ProductRuntime, RuntimeRequest};

#[derive(Parser, Debug)]
#[command(name = "dsh", version, about = "DeepSeek Harness (Rust)")]
struct Cli {
    /// Delivery profile: headless, acp, or test.
    #[arg(long, default_value = "headless")]
    profile: String,
    /// LLM backend: deepseek or mock.
    #[arg(long)]
    llm: Option<String>,
    /// Print the assembled runtime and exit. Does not call a model.
    #[arg(long)]
    dump_config: bool,
    #[arg(long)]
    provider: Option<String>,
    #[arg(long)]
    model: Option<String>,
    #[arg(long)]
    home: Option<PathBuf>,
    #[arg(long)]
    workspace: Option<PathBuf>,
    #[command(subcommand)]
    command: Option<Command>,
    /// User task for a headless turn.
    task: Option<String>,
}

#[derive(Subcommand, Debug)]
enum Command {
    /// Agent Client Protocol server over stdio.
    Acp,
}

/// Parse `args` (without argv0) and run. Returns a process exit code.
pub async fn run(args: Vec<String>) -> i32 {
    let cli = match Cli::try_parse_from(std::iter::once("dsh".to_string()).chain(args)) {
        Ok(cli) => cli,
        Err(error) => {
            let _ = error.print();
            return if error.use_stderr() { 2 } else { 0 };
        }
    };
    match run_cli(cli).await {
        Ok(code) => code,
        Err(error) => {
            eprintln!("{error:#}");
            1
        }
    }
}

async fn run_cli(cli: Cli) -> anyhow::Result<i32> {
    let profile = if matches!(cli.command, Some(Command::Acp)) {
        DeliveryProfile::Acp
    } else {
        cli.profile.parse()?
    };
    let llm = match cli.llm {
        Some(value) => value.parse()?,
        None => match profile {
            DeliveryProfile::Test => LlmBackend::Mock,
            DeliveryProfile::Headless | DeliveryProfile::Acp => LlmBackend::DeepSeek,
        },
    };
    let request = RuntimeRequest {
        profile: Some(profile),
        llm: Some(llm),
        provider: cli.provider,
        model: cli.model,
        home: cli.home,
        workspace: cli.workspace,
        mock_turns: Vec::new(),
        ..RuntimeRequest::default()
    };
    let runtime = ProductRuntime::resolve(request)?.boot()?;
    if cli.dump_config {
        println!("{}", serde_json::to_string_pretty(&runtime.dump_config())?);
        return Ok(0);
    }
    if matches!(cli.command, Some(Command::Acp)) || profile == DeliveryProfile::Acp {
        serve_stdio(Arc::new(runtime)).await?;
        return Ok(0);
    }
    let task = cli.task.ok_or_else(|| {
        anyhow::anyhow!("provide a task string, --dump-config, or the acp subcommand")
    })?;
    let outcome = runtime.run_task(&task).await?;
    if !outcome.text.is_empty() {
        println!("{}", outcome.text);
    }
    Ok(outcome.exit_code())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn dump_config_exits_zero_without_a_key() {
        let workspace = tempfile::tempdir().unwrap();
        let home = tempfile::tempdir().unwrap();
        let code = run(vec![
            "--profile".into(),
            "test".into(),
            "--llm".into(),
            "mock".into(),
            "--workspace".into(),
            workspace.path().display().to_string(),
            "--home".into(),
            home.path().display().to_string(),
            "--dump-config".into(),
        ])
        .await;
        assert_eq!(code, 0);
    }

    #[tokio::test]
    async fn mock_headless_task_exits_zero() {
        let workspace = tempfile::tempdir().unwrap();
        let home = tempfile::tempdir().unwrap();
        let code = run(vec![
            "--profile".into(),
            "test".into(),
            "--llm".into(),
            "mock".into(),
            "--workspace".into(),
            workspace.path().display().to_string(),
            "--home".into(),
            home.path().display().to_string(),
            "hello".into(),
        ])
        .await;
        assert_eq!(code, 0);
    }
}
