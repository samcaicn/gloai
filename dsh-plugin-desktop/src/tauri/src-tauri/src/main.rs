// DSH Skill Platform — Tauri 2 desktop client main entry point.

// Prevents additional console window on Windows in release.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    dsh_desktop_lib::run();
}
