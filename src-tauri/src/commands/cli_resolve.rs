// Copyright (c) 2026 tupAI
//
// Tauri commands for CLI tool resolution.

use tauri::AppHandle;

use crate::hermes::cli_resolver::{self, CliDep};

/// Resolve a single CLI tool by name.
#[tauri::command]
pub fn resolve_cli_tool(
    _app: AppHandle,
    name: String,
) -> Result<cli_resolver::CliToolInfo, String> {
    Ok(cli_resolver::resolve_cli_tool(&name))
}

/// Resolve multiple CLI tools in batch.
#[tauri::command]
pub fn resolve_cli_batch(
    _app: AppHandle,
    names: Vec<String>,
) -> Result<Vec<cli_resolver::CliToolInfo>, String> {
    let name_refs: Vec<&str> = names.iter().map(|n| n.as_str()).collect();
    Ok(cli_resolver::resolve_cli_batch(&name_refs))
}

/// Check CLI dependencies for a skill. Returns list with resolved status.
#[tauri::command]
pub fn check_skill_cli_deps(
    _app: AppHandle,
    cli_deps: Vec<CliDep>,
) -> Result<Vec<cli_resolver::CliToolInfo>, String> {
    Ok(cli_resolver::check_skill_cli_deps(&cli_deps))
}
