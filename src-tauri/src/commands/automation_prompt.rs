// Copyright (c) 2026 AIMarketing
//
// Track F — Tauri command surface for interactive prompts.
//
// The executor emits `automation:ask_user` and blocks on
// `prompt_registry::register(correlation_id)`. The front-end, after
// rendering the prompt modal, calls back into one of these two
// commands to deliver the answer (or signal cancellation):
//
//   * `automation_answer_prompt(answer)` — fulfills the oneshot the
//     executor is awaiting. `answer.value` carries the user input
//     (text / choice.id / "true"|"false"); `answer.cancelled`
//     flags a dismiss.
//   * `automation_cancel_prompt(correlation_id)` — drops the
//     pending sender so the executor's `recv()` returns
//     `Err(RecvError)`; its timeout match then falls back to
//     `default_value`.
//
// NOTE: These two commands are registered in `lib.rs`'s
// `invoke_handler!`, so the front-end can deliver answers /
// cancellations directly via Tauri IPC.

use tauri::AppHandle;

use crate::pc_automation::executor::prompt_registry;
use crate::pc_automation::skill::types::PromptAnswer;

/// Deliver the user's answer to a pending `automation:ask_user`
/// prompt. The `correlation_id` inside `answer` must match the id
/// the executor emitted. Returns `Err` if no pending prompt exists
/// for that id (already timed out / cancelled / unknown).
#[tauri::command]
pub async fn automation_answer_prompt(answer: PromptAnswer, _app: AppHandle) -> Result<(), String> {
    prompt_registry::deliver(answer)
}

/// Cancel a pending prompt by `correlation_id`. Best-effort: no-op
/// if the prompt was never registered or already fulfilled. The
/// executor's await will then fall through to `default_value`.
#[tauri::command]
pub fn automation_cancel_prompt(correlation_id: String, _app: AppHandle) -> Result<(), String> {
    prompt_registry::cancel(&correlation_id);
    Ok(())
}
