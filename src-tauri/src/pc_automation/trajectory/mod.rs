// Copyright (c) 2026 AIMarketing
//
// AIMarketing v5 — `trajectory::jsonl` 导出 (UI-TARS 训练数据格式)。
//
// 父模块: `pc_automation::trajectory`
// 子模块:
//   * `message` — `UiTarsMessage` + `TrajectoryEvent`
//   * `export`  — 转换函数 + JSONL 写入
//
// 用法:
//   ```ignore
//   use crate::pc_automation::trajectory::{build_trajectory, export_jsonl, TrajectoryEvent};
//   let msgs = build_trajectory(&[...]);
//   let mut f = std::fs::File::create("out.jsonl")?;
//   export_jsonl(&msgs, &mut f)?;
//   ```
//
// 公开 API(`pub use`):
//   * `UiTarsMessage`           — 一行训练样本
//   * `TrajectoryEvent`         — 半结构化事件
//   * `build_trajectory`        — 事件 → 消息
//   * `export_jsonl`            — 消息 → JSONL writer
//   * `from_episodic`           — EpRecord[] → 消息
//   * `from_receipt`            — (ExecutionReceipt, Skill) → 消息
//   * 模板常量(SYSTEM_PROMPT_DEFAULT / *_TEMPLATE)

pub mod export;
pub mod message;

pub use export::from_episodic;
pub use message::UiTarsMessage;
