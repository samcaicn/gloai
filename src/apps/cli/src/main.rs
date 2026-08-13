//! `dsh` binary entry. Logging goes to stderr so ACP stdio stays clean.

use dsh_cli::run;

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::from_default_env()
                .add_directive(tracing_subscriber::filter::LevelFilter::WARN.into()),
        )
        .with_writer(std::io::stderr)
        .init();
    let code = run(std::env::args().skip(1).collect()).await;
    std::process::exit(code);
}
