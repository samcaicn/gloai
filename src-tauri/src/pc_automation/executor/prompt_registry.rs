// Copyright (c) 2026 tupAI
//
// Track F — correlation registry for interactive prompts.
//
// The executor, when it hits a `SkillStep` carrying an
// `InteractionPrompt`, emits `automation:ask_user` and then needs
// to *wait* for the front-end to deliver the answer via the
// `automation_answer_prompt` Tauri command. The two sides are
// decoupled (the executor is an `async fn` on the Rust side; the
// answer arrives as a separate Tauri command invocation), so we
// bridge them with a process-local registry:
//
//   * `register(correlation_id)` — called by the executor *before*
//     emitting the event. Returns a `oneshot::Receiver<PromptAnswer>`
//     that the executor `await`s (wrapped in `tokio::time::timeout`).
//   * `deliver(answer)` — called by the `automation_answer_prompt`
//     command. Looks up the pending sender by `correlation_id` and
//     fulfills the receiver.
//   * `cancel(correlation_id)` — called by `automation_cancel_prompt`.
//     Drops the sender; the executor's `recv()` then returns
//     `Err(RecvError)` and the timeout match falls through to
//     `default_value`.
//
// The registry mirrors the `OnceLock<Mutex<Inner>>` pattern used by
// `crate::hermes::evolution_stats` — process-local, no persistence,
// reset on restart. Concurrency is bounded (one entry per in-flight
// prompt) so a plain `std::sync::Mutex` is fine; we never `await`
// while holding the guard.

use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

use tokio::sync::oneshot;

use crate::pc_automation::skill::types::PromptAnswer;

/// Inner state. `pending` maps `correlation_id` → the sender half
/// of the oneshot channel the executor is awaiting. Removing the
/// entry (deliver / cancel) drops the sender if the executor has
/// not yet been fulfilled, which is exactly the "cancel" signal.
struct Inner {
    pending: HashMap<String, oneshot::Sender<PromptAnswer>>,
}

static REGISTRY: OnceLock<Mutex<Inner>> = OnceLock::new();

fn inner() -> &'static Mutex<Inner> {
    REGISTRY.get_or_init(|| Mutex::new(Inner { pending: HashMap::new() }))
}

/// Register a pending prompt and return the receiver the executor
/// should `await`. Must be called *before* emitting
/// `automation:ask_user` so that a fast front-end answer cannot
/// race ahead of the registration (deliver would otherwise return
/// `Err("no pending prompt")` and the answer would be lost).
pub fn register(correlation_id: &str) -> oneshot::Receiver<PromptAnswer> {
    let (tx, rx) = oneshot::channel();
    // Mutex 中毒时用 into_inner 恢复 (dev unwinding 路径才可能中毒; release
    // panic=abort 不会中毒)。中毒后仍允许插入新 prompt, 避免整个执行器卡死。
    let mut g = inner().lock().unwrap_or_else(|e| e.into_inner());
    if g.pending.contains_key(correlation_id) {
        // 重复 correlation_id: 通常是 bug 或前端重复触发同一 prompt。原实现
        // 静默覆盖, 旧 Sender 被 drop → 旧 executor 的 rx 返回 Err → 走 default
        // 回退路径, 但毫无线索。这里记 warn 暴露冲突, 行为不变 (仍用新的覆盖
        // 旧的), 便于排查。
        log::warn!(
            "[prompt_registry] duplicate correlation_id overwriting pending prompt: {}",
            correlation_id
        );
    }
    g.pending.insert(correlation_id.to_string(), tx);
    rx
}

/// Deliver an answer to the executor waiting on `correlation_id`.
/// Returns `Err` if no pending prompt exists for that id (e.g. the
/// executor already timed out / cancelled, or the id is unknown).
pub fn deliver(answer: PromptAnswer) -> Result<(), String> {
    let mut g = inner().lock().unwrap_or_else(|e| e.into_inner());
    match g.pending.remove(&answer.correlation_id) {
        Some(tx) => match tx.send(answer) {
            Ok(()) => Ok(()),
            // `send` errors only when the receiver was dropped — i.e.
            // the executor already gave up (timeout / cancel)。原实现用
            // `let _ =` 静默吞掉这个错误并返回 Ok, 调用方 (automation_answer_prompt
            // 命令) 误以为答案已送达, 实际答案被丢弃。返回 Err 让调用方知道该
            // prompt 已不在等待。`Err` 携带回被拒收的 `PromptAnswer`, 从中取
            // correlation_id 拼错误信息 (注意 `answer` 已被 send 消费, 不能再用)。
            Err(dropped) => Err(format!(
                "prompt already timed out / receiver dropped: {}",
                dropped.correlation_id
            )),
        },
        None => Err(format!(
            "no pending prompt for correlation_id={}",
            answer.correlation_id
        )),
    }
}

/// Cancel a pending prompt. Drops the sender so the executor's
/// `recv()` returns `Err(RecvError)`, which its timeout match
/// treats as "use default_value". Best-effort — no-op if the
/// prompt was never registered or already fulfilled.
pub fn cancel(correlation_id: &str) {
    inner().lock().unwrap_or_else(|e| e.into_inner()).pending.remove(correlation_id);
}
