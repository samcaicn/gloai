// Copyright (c) 2026 tupAI
//
// Preset packages — the dsh-equivalent portable agent mechanism.
//
// safeopcAPP "everything is a plugin" needs a *shareable* unit. dsh-desktop
// ships custom Agent presets as `.dshpreset` ZIP files (manifest.json + a
// `preset/` composition directory). This module replicates that format and its
// safety model so custom agents/presets can be exported, imported, and shared
// — exactly the capability dsh's `preset-square` is built around:
//
//   • validate strictly on import (reject absolute paths, `..` traversal,
//     backslash paths, oversized packages, missing manifest.json, missing
//     `preset/` composition);
//   • two-step import: preview (manifest + file count + warnings) → confirmed
//     atomic install that NEVER overwrites an existing preset id;
//   • export any local preset back to a `.dshpreset` (STORED-method zip — no
//     external writer version-API risk, readable by any unzip / by dsh);
//   • list / delete local presets.
//
// The zip crate is used ONLY for reading (so we can inflate deflate-compressed
// packages from dsh); exporting uses a hand-rolled STORED-method writer so we
// don't depend on zip's writer API surface across versions.

use std::io::{Cursor, Read};
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Emitter, Manager};
use uuid::Uuid;
use walkdir::WalkDir;
use zip::ZipArchive;

// ── constants ──────────────────────────────────────────────────────────────

const FORMAT: &str = "dsh-preset"; // compatible with dsh's manifest.format
const MANIFEST_VERSION: u32 = 1; // highest manifest.version we accept
const MAX_PACKAGE_BYTES: u64 = 50 * 1024 * 1024; // compressed package size cap
const MAX_UNCOMPRESSED_BYTES: u64 = 200 * 1024 * 1024; // inflated size cap
const MAX_FILES: usize = 2000;
const MAX_SCAN_BYTES: usize = 65_536; // only scan small files for warnings
const ID_MAX_LEN: usize = 64;

/// Substrings that, inside a small text file, suggest the package may carry
/// secrets. Mirrors dsh's `importWarningPossibleSecrets`.
const SECRET_PATTERNS: &[&str] = &[
    "sk-", "api_key", "apikey", "api-key", "secret", "token", "akia", "password",
    "bearer ", "authorization",
];

// ── wire types ───────────────────────────────────────────────────────────────

/// On-disk + in-package manifest (compatible with dsh's `manifest.json` v1).
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PresetManifest {
    pub format: String,
    pub version: u32,
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub source_dsh_version: String,
    #[serde(default)]
    pub exported_at: String,
}

/// A non-fatal warning surfaced in the import preview so the user can review
/// before installing. Mirrors dsh's import warnings.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PresetWarning {
    /// `"possibleSecrets"` | `"absolutePaths"` | `"versionMismatch"`
    pub warning_type: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub package_version: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub app_version: Option<String>,
}

/// Result of validating a package without installing it.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PackagePreview {
    pub manifest: PresetManifest,
    pub file_count: usize,
    pub total_bytes: u64,
    pub warnings: Vec<PresetWarning>,
    /// safe default identifier to propose in the UI (manifest.id sanitized).
    pub suggested_target_id: String,
}

/// A locally installed preset (one directory under `<app_data>/presets/<id>`).
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PresetInfo {
    pub id: String,
    pub name: String,
    pub description: String,
    pub source_dsh_version: String,
    pub exported_at: String,
    pub file_count: usize,
    pub path: String,
}

// ── path / id helpers ─────────────────────────────────────────────────────────

fn preset_root(app_data: &Path) -> PathBuf {
    let root = app_data.join("presets");
    let _ = std::fs::create_dir_all(&root);
    root
}

/// Local preset identifier: alphanumeric + `- _ .`, non-empty, <= 64, not dot-led.
fn validate_target_id(id: &str) -> Result<(), String> {
    if id.is_empty() || id.len() > ID_MAX_LEN {
        return Err("预设标识符长度无效（1-64 个字符）".into());
    }
    if id.starts_with('.') {
        return Err("预设标识符不能以点开头".into());
    }
    for c in id.chars() {
        if !(c.is_ascii_alphanumeric() || c == '-' || c == '_' || c == '.') {
            return Err("预设标识符只能包含字母、数字、- _ .".into());
        }
    }
    Ok(())
}

/// Turn an arbitrary string into a safe preset id (never empty).
fn sanitize_id(id: &str) -> String {
    let mut s: String = id
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '-' || c == '_' || c == '.' {
                c
            } else {
                '-'
            }
        })
        .collect();
    s = s.trim_matches('-').to_string();
    if s.is_empty() {
        s = "imported-preset".into();
    }
    s.truncate(ID_MAX_LEN);
    s
}

/// Reject absolute paths, backslashes, and `..` traversal in a zip entry name.
fn validate_entry_name(name: &str) -> Result<(), String> {
    if name.is_empty() {
        return Err("压缩包包含空路径条目".into());
    }
    if name.starts_with('/') || name.contains('\\') {
        return Err("压缩包包含绝对路径或反斜杠路径".into());
    }
    for part in name.split('/') {
        if part == ".." {
            return Err("压缩包包含路径穿越 (..)".into());
        }
    }
    Ok(())
}

/// Join `dest` with the (already validated) `/`-separated entry name, refusing
/// any component that would escape `dest`.
fn safe_join(dest: &Path, name: &str) -> Result<PathBuf, String> {
    let mut out = dest.to_path_buf();
    for comp in name.split('/') {
        if comp.is_empty() || comp == "." {
            continue;
        }
        if comp == ".." {
            return Err("路径穿越".into());
        }
        out = out.join(comp);
    }
    if !out.starts_with(dest) {
        return Err("压缩包条目试图逃离目标目录".into());
    }
    Ok(out)
}

fn count_files(dir: &Path) -> usize {
    WalkDir::new(dir)
        .into_iter()
        .filter_map(|e| e.ok())
        .filter(|e| e.file_type().is_file())
        .count()
}

// ── package validation / preview ────────────────────────────────────────────

/// Read a package fully, validate it, and produce a preview. Does NOT install.
/// Used both by the preview command and as the first step of import.
pub fn preview_package(bytes: &[u8], app_version: &str) -> Result<PackagePreview, String> {
    if bytes.len() as u64 > MAX_PACKAGE_BYTES {
        return Err("压缩包过大（超过 50MB）".into());
    }

    let reader = Cursor::new(bytes.to_vec());
    let mut archive =
        ZipArchive::new(reader).map_err(|e| format!("无法读取压缩包: {}", e))?;
    let len = archive.len();

    let mut manifest_bytes: Option<Vec<u8>> = None;
    let mut has_preset = false;
    let mut total_uncompressed: u64 = 0;
    let mut file_count = 0;
    let mut secrets = false;
    let mut absolute = false;

    for i in 0..len {
        let mut file = archive
            .by_index(i)
            .map_err(|e| format!("读取压缩包条目失败: {}", e))?;
        let raw_name = file.name().to_string();
        validate_entry_name(&raw_name)?;

        if file.is_dir() {
            continue;
        }

        let uncompressed = file.size();
        total_uncompressed += uncompressed;
        if total_uncompressed > MAX_UNCOMPRESSED_BYTES {
            return Err("压缩包解压后过大（超过 200MB）".into());
        }

        let mut buf = Vec::with_capacity(uncompressed as usize);
        file.read_to_end(&mut buf)
            .map_err(|e| format!("读取压缩包内容失败: {}", e))?;

        if raw_name == "manifest.json" {
            manifest_bytes = Some(buf.clone());
        }
        if raw_name.starts_with("preset/") && raw_name != "preset/" {
            has_preset = true;
        }

        if buf.len() <= MAX_SCAN_BYTES {
            let text = String::from_utf8_lossy(&buf).to_ascii_lowercase();
            if !secrets && SECRET_PATTERNS.iter().any(|p| text.contains(p)) {
                secrets = true;
            }
            if !absolute
                && (text.contains("c:\\")
                    || text.contains("/users/")
                    || text.contains("/home/")
                    || text.contains(":\\"))
            {
                absolute = true;
            }
        }

        file_count += 1;
        if file_count > MAX_FILES {
            return Err("压缩包文件数量过多（超过 2000）".into());
        }
    }

    let manifest_bytes =
        manifest_bytes.ok_or_else(|| "压缩包缺少 manifest.json".to_string())?;
    if !has_preset {
        return Err("压缩包缺少 preset/ 目录（无效的可执行配置组合）".into());
    }

    let manifest: PresetManifest = serde_json::from_slice(&manifest_bytes)
        .map_err(|e| format!("manifest.json 解析失败: {}", e))?;
    if manifest.format != FORMAT {
        return Err(format!("未知包格式: {}（期望 {}）", manifest.format, FORMAT));
    }
    if manifest.version > MANIFEST_VERSION {
        return Err(format!("不支持的包版本: {}", manifest.version));
    }
    if manifest.id.trim().is_empty() {
        return Err("manifest.json 缺少 id".into());
    }

    let mut warnings: Vec<PresetWarning> = Vec::new();
    if secrets {
        warnings.push(PresetWarning {
            warning_type: "possibleSecrets".into(),
            package_version: None,
            app_version: None,
        });
    }
    if absolute {
        warnings.push(PresetWarning {
            warning_type: "absolutePaths".into(),
            package_version: None,
            app_version: None,
        });
    }
    if !manifest.source_dsh_version.is_empty() && manifest.source_dsh_version != app_version {
        warnings.push(PresetWarning {
            warning_type: "versionMismatch".into(),
            package_version: Some(manifest.source_dsh_version.clone()),
            app_version: Some(app_version.to_string()),
        });
    }

    let suggested_target_id = sanitize_id(&manifest.id);

    Ok(PackagePreview {
        manifest,
        file_count,
        total_bytes: total_uncompressed,
        warnings,
        suggested_target_id,
    })
}

// ── extraction ────────────────────────────────────────────────────────────────

fn extract_zip(bytes: &[u8], dest: &Path) -> Result<(), String> {
    let reader = Cursor::new(bytes.to_vec());
    let mut archive =
        ZipArchive::new(reader).map_err(|e| format!("无法读取压缩包: {}", e))?;
    let len = archive.len();

    for i in 0..len {
        let mut file = archive
            .by_index(i)
            .map_err(|e| format!("读取压缩包条目失败: {}", e))?;
        let raw_name = file.name().to_string();
        validate_entry_name(&raw_name)?;

        let out_path = safe_join(dest, &raw_name)?;
        if file.is_dir() {
            std::fs::create_dir_all(&out_path)
                .map_err(|e| format!("创建目录失败: {}", e))?;
            continue;
        }
        if let Some(parent) = out_path.parent() {
            std::fs::create_dir_all(parent)
                .map_err(|e| format!("创建目录失败: {}", e))?;
        }
        let mut buf = Vec::with_capacity(file.size() as usize);
        file.read_to_end(&mut buf)
            .map_err(|e| format!("读取压缩包内容失败: {}", e))?;
        std::fs::write(&out_path, &buf)
            .map_err(|e| format!("写入文件失败: {}", e))?;
    }
    Ok(())
}

// ── public engine API ─────────────────────────────────────────────────────────

/// Validate + atomically install a package. Never overwrites an existing id.
pub fn import_package(
    bytes: &[u8],
    target_id: &str,
    app_data: &Path,
    app_version: &str,
) -> Result<PresetInfo, String> {
    validate_target_id(target_id)?;

    let root = preset_root(app_data);
    let final_dir = root.join(target_id);
    if final_dir.exists() {
        return Err(format!(
            "预设 '{}' 已存在，请选择其他标识符（绝不覆盖现有预设）",
            target_id
        ));
    }

    // Full re-validation (defense in depth; import must not trust a preview).
    let _preview = preview_package(bytes, app_version)?;

    let tmp = root.join(format!(".import-{}-{}", target_id, Uuid::new_v4()));
    std::fs::create_dir_all(&tmp).map_err(|e| format!("创建临时目录失败: {}", e))?;
    let result = extract_zip(bytes, &tmp);
    if let Err(e) = result {
        let _ = std::fs::remove_dir_all(&tmp);
        return Err(e);
    }

    // Atomic move into place (same volume as `root` → cheap rename).
    std::fs::rename(&tmp, &final_dir).map_err(|e| {
        let _ = std::fs::remove_dir_all(&tmp);
        format!("安装预设失败: {}", e)
    })?;

    read_preset_info(&final_dir, target_id)
}

/// Export a local preset directory back to a `.dshpreset` (STORED-method zip).
pub fn export_preset(preset_id: &str, app_data: &Path) -> Result<Vec<u8>, String> {
    validate_target_id(preset_id)?;
    let root = preset_root(app_data);
    let dir = root.join(preset_id);
    if !dir.is_dir() {
        return Err(format!("预设 '{}' 不存在", preset_id));
    }
    let manifest_path = dir.join("manifest.json");
    if !manifest_path.is_file() {
        return Err(format!("预设 '{}' 缺少 manifest.json", preset_id));
    }

    let mut entries: Vec<(String, Vec<u8>)> = Vec::new();
    for entry in WalkDir::new(&dir).into_iter().filter_map(|e| e.ok()) {
        if !entry.file_type().is_file() {
            continue;
        }
        if entry.path_is_symlink() {
            continue; // never follow/emit symlinks
        }
        let path = entry.path();
        let rel = path
            .strip_prefix(&dir)
            .map_err(|e| format!("路径处理失败: {}", e))?;
        let rel_str = rel.to_string_lossy().replace('\\', "/");
        let base = entry.file_name().to_string_lossy().to_ascii_lowercase();
        if base == ".ds_store" || base == "thumbs.db" || base == "desktop.ini" {
            continue; // omit OS metadata
        }
        let data = std::fs::read(path).map_err(|e| format!("读取文件失败: {}", e))?;
        entries.push((rel_str, data));
    }
    if entries.is_empty() {
        return Err("预设目录为空".into());
    }

    Ok(write_zip_stored(&entries))
}

/// List locally installed presets.
pub fn list_presets(app_data: &Path) -> Vec<PresetInfo> {
    let root = preset_root(app_data);
    let mut out: Vec<PresetInfo> = Vec::new();
    if let Ok(rd) = std::fs::read_dir(&root) {
        for e in rd.flatten() {
            let p = e.path();
            if !p.is_dir() {
                continue;
            }
            let id = e.file_name().to_string_lossy().to_string();
            if id.starts_with('.') {
                continue;
            }
            if let Ok(info) = read_preset_info(&p, &id) {
                out.push(info);
            }
        }
    }
    out.sort_by(|a, b| a.id.cmp(&b.id));
    out
}

/// Delete a local preset by id.
pub fn delete_preset(preset_id: &str, app_data: &Path) -> Result<(), String> {
    validate_target_id(preset_id)?;
    let root = preset_root(app_data);
    let dir = root.join(preset_id);
    if !dir.is_dir() {
        return Err(format!("预设 '{}' 不存在", preset_id));
    }
    std::fs::remove_dir_all(&dir).map_err(|e| format!("删除预设失败: {}", e))?;
    Ok(())
}

/// Read a preset directory's manifest + metadata into a `PresetInfo`.
fn read_preset_info(dir: &Path, id: &str) -> Result<PresetInfo, String> {
    let manifest_path = dir.join("manifest.json");
    let bytes = std::fs::read(&manifest_path)
        .map_err(|_| format!("预设 '{}' 缺少 manifest.json", id))?;
    let m: PresetManifest =
        serde_json::from_slice(&bytes).map_err(|e| format!("预设 '{}' manifest 解析失败: {}", id, e))?;
    Ok(PresetInfo {
        id: id.to_string(),
        name: m.name,
        description: m.description,
        source_dsh_version: m.source_dsh_version,
        exported_at: m.exported_at,
        file_count: count_files(dir),
        path: dir.to_string_lossy().to_string(),
    })
}

// ── hand-rolled STORED-method ZIP writer ────────────────────────────────────
//
// No external zip-writer dependency → no version-API drift risk. Emits a valid
// ZIP (local headers + central directory + EOCD) with method 0 (stored). Any
// compliant unzip — including dsh's — reads it.

fn crc32(data: &[u8]) -> u32 {
    let mut crc: u32 = 0xFFFF_FFFF;
    for &b in data {
        crc ^= b as u32;
        for _ in 0..8 {
            crc = if crc & 1 != 0 {
                (crc >> 1) ^ 0xEDB8_8320
            } else {
                crc >> 1
            };
        }
    }
    !crc
}

fn write_zip_stored(entries: &[(String, Vec<u8>)]) -> Vec<u8> {
    let mut out: Vec<u8> = Vec::new();
    let mut central: Vec<u8> = Vec::new();
    let mut offset: u32 = 0;

    for (name, data) in entries {
        let name_bytes = name.as_bytes();
        let crc = crc32(data);
        let size = data.len() as u32;

        // ── local file header ──
        out.extend_from_slice(&0x0403_4B50u32.to_le_bytes()); // signature
        out.extend_from_slice(&20u16.to_le_bytes()); // version needed
        out.extend_from_slice(&0u16.to_le_bytes()); // flags
        out.extend_from_slice(&0u16.to_le_bytes()); // method = stored
        out.extend_from_slice(&0u16.to_le_bytes()); // mod time
        out.extend_from_slice(&0u16.to_le_bytes()); // mod date
        out.extend_from_slice(&crc.to_le_bytes());
        out.extend_from_slice(&size.to_le_bytes()); // compressed size
        out.extend_from_slice(&size.to_le_bytes()); // uncompressed size
        out.extend_from_slice(&(name_bytes.len() as u16).to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes()); // extra len
        out.extend_from_slice(name_bytes);
        out.extend_from_slice(data);

        // ── central directory record ──
        central.extend_from_slice(&0x0201_4B50u32.to_le_bytes());
        central.extend_from_slice(&20u16.to_le_bytes()); // version made by
        central.extend_from_slice(&20u16.to_le_bytes()); // version needed
        central.extend_from_slice(&0u16.to_le_bytes()); // flags
        central.extend_from_slice(&0u16.to_le_bytes()); // method
        central.extend_from_slice(&0u16.to_le_bytes()); // time
        central.extend_from_slice(&0u16.to_le_bytes()); // date
        central.extend_from_slice(&crc.to_le_bytes());
        central.extend_from_slice(&size.to_le_bytes());
        central.extend_from_slice(&size.to_le_bytes());
        central.extend_from_slice(&(name_bytes.len() as u16).to_le_bytes());
        central.extend_from_slice(&0u16.to_le_bytes()); // extra
        central.extend_from_slice(&0u16.to_le_bytes()); // comment
        central.extend_from_slice(&0u16.to_le_bytes()); // disk number
        central.extend_from_slice(&0u16.to_le_bytes()); // internal attrs
        central.extend_from_slice(&0u32.to_le_bytes()); // external attrs
        central.extend_from_slice(&offset.to_le_bytes()); // local header offset
        central.extend_from_slice(name_bytes);

        offset += (30 + name_bytes.len() as u32) + data.len() as u32;
    }

    let central_offset = offset;
    let central_size = central.len() as u32;
    let count = entries.len() as u16;

    out.extend_from_slice(&central);
    out.extend_from_slice(&0x0605_4B50u32.to_le_bytes()); // EOCD signature
    out.extend_from_slice(&0u16.to_le_bytes()); // disk
    out.extend_from_slice(&0u16.to_le_bytes()); // disk
    out.extend_from_slice(&count.to_le_bytes()); // entries this disk
    out.extend_from_slice(&count.to_le_bytes()); // total entries
    out.extend_from_slice(&central_size.to_le_bytes());
    out.extend_from_slice(&central_offset.to_le_bytes());
    out.extend_from_slice(&0u16.to_le_bytes()); // comment len

    out
}

// ── Tauri command layer ──────────────────────────────────────────────────────

fn app_data_dir(app: &AppHandle) -> Result<PathBuf, String> {
    app.path()
        .app_data_dir()
        .map_err(|e| format!("app_data_dir: {}", e))
}

fn notify_presets_changed(app: &AppHandle) {
    // WebView-facing refresh (same channel the PluginMarketScene subscribes to).
    let _ = app.emit("plugins-changed", serde_json::json!({ "kind": "preset" }));
}

/// List locally installed presets.
#[tauri::command]
pub async fn preset_list(app: AppHandle) -> Vec<PresetInfo> {
    let dir = app_data_dir(&app).unwrap_or_else(|_| PathBuf::from("."));
    list_presets(&dir)
}

/// Validate a package and return a preview (manifest, file count, warnings).
#[tauri::command]
pub async fn preset_preview(bytes: Vec<u8>) -> Result<PackagePreview, String> {
    let app_version = env!("CARGO_PKG_VERSION");
    preview_package(&bytes, app_version)
}

/// Validate + atomically install a package under `target_id`.
#[tauri::command]
pub async fn preset_import(
    app: AppHandle,
    bytes: Vec<u8>,
    target_id: String,
) -> Result<PresetInfo, String> {
    let dir = app_data_dir(&app)?;
    let app_version = env!("CARGO_PKG_VERSION");
    let info = import_package(&bytes, &target_id, &dir, app_version)?;
    notify_presets_changed(&app);
    Ok(info)
}

/// Export a local preset to a `.dshpreset` byte blob.
#[tauri::command]
pub async fn preset_export(app: AppHandle, preset_id: String) -> Result<Vec<u8>, String> {
    let dir = app_data_dir(&app)?;
    export_preset(&preset_id, &dir)
}

/// Delete a local preset by id.
#[tauri::command]
pub async fn preset_delete(app: AppHandle, preset_id: String) -> Result<(), String> {
    let dir = app_data_dir(&app)?;
    delete_preset(&preset_id, &dir)?;
    notify_presets_changed(&app);
    Ok(())
}
