// DSH Skill Platform — shared core library
//
// This crate contains the functionality shared between the desktop app (Tauri),
// the CLI binary, and other DSH tools.
//
// 【铁律】所有业务逻辑已提取为独立 Cordis 插件，core 不再包含业务模块。
// 插件列表：
//   - dsh-plugin-autoskill  (自进化引擎)
//   - dsh-plugin-evolution  (进化追踪)
//   - dsh-plugin-memory     (记忆系统)
//   - dsh-plugin-skill      (技能系统)
//   - dsh-plugin-storage    (数据存储)
//   - dsh-plugin-watermark  (去水印)

pub const VERSION: &str = env!("CARGO_PKG_VERSION");
