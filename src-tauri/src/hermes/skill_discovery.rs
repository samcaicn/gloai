
//
// Skill discovery: scan a directory for SKILL.md files and load their
// manifests. The TypeScript module exposed `discover(dir)` and an
// in-memory index. The Rust port walks the directory with `walkdir`
// and parses YAML front-matter.

use std::path::Path;
use serde::{Deserialize, Serialize};
use walkdir::WalkDir;

use super::skill_manifest::SkillManifest;

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct DiscoveryReport {
    pub scanned: usize,
    pub loaded: Vec<SkillManifest>,
    pub errors: Vec<String>,
}

pub fn discover(root: &Path) -> DiscoveryReport {
    let mut report = DiscoveryReport::default();
    for entry in WalkDir::new(root).max_depth(4).into_iter().filter_map(|e| e.ok()) {
        if entry.file_name() != "SKILL.md" { continue; }
        report.scanned += 1;
        let path = entry.path();
        // 先 metadata 检查大小，超过 4MB 跳过，避免 read_to_string 大文件 OOM
        if let Ok(m) = std::fs::metadata(path) {
            if m.len() > 4 * 1024 * 1024 {
                report.errors.push(format!("{:?}: too large ({} bytes), skipped", path, m.len()));
                continue;
            }
        }
        match std::fs::read_to_string(path) {
            Ok(text) => match super::skill_manifest::parse(&text) {
                Ok(m) => report.loaded.push(m),
                Err(e) => report.errors.push(format!("{:?}: {}", path, e)),
            },
            Err(e) => report.errors.push(format!("{:?}: {}", path, e)),
        }
    }
    report
}
