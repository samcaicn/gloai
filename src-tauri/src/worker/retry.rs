// Copyright (c) 2026 MeeJoy
//
// 重试策略：指数退避 + jitter
//
// 基础 500ms，按 2^attempt 指数增长，上限 60s，叠加 0-500ms 随机 jitter
// 避免重试风暴。

use std::time::Duration;

use rand::Rng;

/// 计算第 `attempt` 次重试的等待时长（attempt 从 1 开始）。
pub fn retry_delay(attempt: u32) -> Duration {
    let base = 500u64;
    let max = 60_000u64;
    // 2^attempt * base，溢出或超限时封顶为 max
    let exp = base.checked_shl(attempt).unwrap_or(max);
    let delay = exp.min(max);
    // 0-500ms 随机 jitter
    let jitter = rand::thread_rng().gen_range(0..=500);
    Duration::from_millis(delay + jitter)
}
