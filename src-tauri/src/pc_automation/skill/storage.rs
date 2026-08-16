// Copyright (c) 2026 tupAI
//
// UIRPA local encrypted skill store.
//
// Stores skills as one AES-256-GCM encrypted file per skill at
//   `<app_data_dir>/skills/<skill_id>.enc`
// using the on-disk format:
//
//   ┌─ 4 bytes : format version (LE u32, = 1)
//   ├─ 12 bytes: AES-256-GCM nonce
//   ├─ 16 bytes: GCM authentication tag
//   └─ N bytes : ciphertext (JSON of the `Skill` struct)
//
// The 32-byte AES key is derived from the user-supplied
// `password` via Argon2id with a per-install salt stored as a
// sidecar file `<app_data_dir>/skills/.salt`. That keeps the
// "wrong password" surface local — re-deriving the key against
// the same salt lets us reject bad passwords at the *ciphertext*
// layer (AES-GCM tag verification) rather than having to
// pre-parse the plaintext JSON.
//
// API surface:
//   * `new(app_data_dir)`        — bind a storage dir
//   * `store(skill, password)`   — encrypt + write
//   * `load(path, password)`     — read + decrypt + parse
//   * `list()`                   — metadata only, no body decrypt
//   * `delete(skill_id)`         — remove the .enc file
//
// All errors are `Result<_, String>` per the project convention
// (`commands::*` ferries them to the front-end verbatim).

use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

use argon2::Argon2;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use zeroize::Zeroize;

use crate::pc_automation::skill::decryptor::SkillDecryptor;
use crate::pc_automation::skill::export::{from_skill_md, to_skill_md};
use crate::pc_automation::skill::types::{Skill, SkillMeta};

/// On-disk envelope.
const FILE_VERSION: u32 = 1;
const VERSION_LEN: usize = 4;
const NONCE_LEN: usize = 12;
const TAG_LEN: usize = 16;
const HEADER_LEN: usize = VERSION_LEN + NONCE_LEN + TAG_LEN;
/// Default Argon2id salt length. 16 bytes is the recommended
/// minimum per RFC 9106 §3.1.
const SALT_LEN: usize = 16;

/// Filename suffix for the encrypted skill file.
const SKILL_EXT: &str = "enc";
/// Filename suffix for the per-install salt.
const SALT_FILE: &str = ".salt";

/// Metadata sidecar — the only fields we need to render the
/// skill list. This is stored *inside* the encrypted file, so
/// `list()` has to decrypt *something*. The contract from the
/// task spec is "only metadata, do not body-decrypt", which
/// means: we accept the cost of one decrypt per listed file
/// to pull out the five fields, and we never return the full
/// `Skill`. That keeps the list cheap (no parameter / step
/// tree to walk) and keeps the plaintext payload off the wire.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct EncryptedSkillFile {
    pub meta: SkillMeta,
    pub body: serde_json::Value,
}

/// File-bound store. The `app_data_dir` is the only state — the
/// on-disk salt is the *master* secret material; per-call keys
/// are derived from `password + salt` and zeroized on drop.
pub struct LocalSkillStorage {
    app_data_dir: PathBuf,
    skills_dir: PathBuf,
}

impl LocalSkillStorage {
    /// Bind a storage handle to `<app_data_dir>/skills`. The
    /// directory is created on demand; the per-install salt is
    /// generated on first call and reused thereafter.
    pub fn new(app_data_dir: &Path) -> Self {
        let skills_dir = app_data_dir.join("skills");
        Self {
            app_data_dir: app_data_dir.to_path_buf(),
            skills_dir,
        }
    }

    /// Convenience: the directory the encrypted files live in.
    pub fn skills_dir(&self) -> &Path {
        &self.skills_dir
    }

    /// Resolve the on-disk path for a given `skill_id`. The
    /// caller can use this to check existence / to feed into
    /// `load`。
    ///
    /// `skill_id` 会被清洗:只允许字母、数字、`-`、`_`、`.`,
    /// 其他字符(包括路径分隔符 `/` `\` 与 `..`)会被替换为 `_`。
    /// 这避免恶意 skill_id 通过 `../../etc/passwd` 之类的输入
    /// 逃逸 skills_dir,造成 path traversal。
    pub fn path_for(&self, skill_id: &str) -> PathBuf {
        let sanitized = sanitize_skill_id(skill_id);
        self.skills_dir.join(format!("{}.{}", sanitized, SKILL_EXT))
    }

    /// Persist a `Skill` to `<skills_dir>/<skill_id>.enc`. The
    /// `password` is the user's unlock secret — it is used with
    /// Argon2id + the per-install salt to derive a 32-byte AES
    /// key, which is then wiped from memory as soon as the
    /// encrypt finishes.
    ///
    /// Returns the absolute path of the written file.
    pub fn store(&self, skill: &Skill, password: &[u8]) -> Result<PathBuf, String> {
        if skill.skill_id.trim().is_empty() {
            return Err("skill_id is required".to_string());
        }
        // 二次防线:sanitize 后如果与原 skill_id 不同,说明含非法字符,
        // 拒绝写入而不是默默清洗,避免用户困惑(以为存了 A 实际存了 A_)。
        if sanitize_skill_id(&skill.skill_id) != skill.skill_id {
            return Err(format!(
                "skill_id contains illegal characters (allowed: A-Z a-z 0-9 - _ .): {}",
                skill.skill_id
            ));
        }
        if password.is_empty() {
            return Err("password is required".to_string());
        }

        fs::create_dir_all(&self.skills_dir)
            .map_err(|e| format!("create skills dir: {}", e))?;

        let salt = self.load_or_create_salt()?;
        let mut key = derive_key(password, &salt)?;

        // Build the file payload. We embed a minimal metadata
        // sidecar so `list()` can pick it up without touching
        // `body`; the body itself is a full JSON copy of the
        // `Skill`.
        let meta = SkillMeta {
            skill_id: skill.skill_id.clone(),
            version: skill.version.clone(),
            intent: skill.intent.clone(),
            updated_at: skill.updated_at,
            success_rate: skill.success_rate,
        };
        let file = EncryptedSkillFile {
            meta,
            body: serde_json::to_value(skill)
                .map_err(|e| format!("serialize skill: {}", e))?,
        };
        let plaintext = serde_json::to_vec(&file)
            .map_err(|e| format!("serialize envelope: {}", e))?;

        // Encrypt.
        let decryptor = SkillDecryptor::new(key);
        let (ciphertext, nonce, tag) = decryptor
            .encrypt(&plaintext)
            .map_err(|e| format!("encrypt skill: {}", e))?;

        // Lay out the framed file.
        let mut framed = Vec::with_capacity(HEADER_LEN + ciphertext.len());
        framed.extend_from_slice(&FILE_VERSION.to_le_bytes());
        framed.extend_from_slice(&nonce);
        framed.extend_from_slice(&tag);
        framed.extend_from_slice(&ciphertext);

        let target = self.path_for(&skill.skill_id);
        // Atomic-ish write: write to .tmp, then rename. Avoids
        // leaving a half-written .enc on disk if the process is
        // killed mid-write。
        let tmp = target.with_extension("enc.tmp");
        // 用 RAII guard 保证任何错误路径都清理 tmp 文件,
        // 避免 .enc.tmp 残留累积(此前只在 rename 失败时清理)。
        struct TmpGuard<'a>(&'a Path, bool);
        impl<'a> Drop for TmpGuard<'a> {
            fn drop(&mut self) {
                if self.1 { let _ = fs::remove_file(self.0); }
            }
        }
        let mut guard = TmpGuard(&tmp, true);
        {
            let mut f = fs::File::create(&tmp)
                .map_err(|e| format!("create tmp file: {}", e))?;
            if let Err(e) = f.write_all(&framed) {
                return Err(format!("write tmp file: {}", e));
            }
            if let Err(e) = f.sync_all() {
                return Err(format!("sync tmp file: {}", e));
            }
        }
        fs::rename(&tmp, &target).map_err(|e| {
            format!("rename tmp file: {}", e)
        })?;
        // rename 成功,tmp 已不存在,关闭 guard 清理。
        guard.1 = false;

        // Wipe the key as soon as we are done.
        key.zeroize();
        Ok(target)
    }

    /// Read + decrypt + parse a `.enc` file. Returns the full
    /// `Skill`. The `password` is verified at the GCM tag
    /// layer; a wrong password returns an opaque "decrypt
    /// failed" error so the attacker doesn't learn whether
    /// the file format is intact.
    pub fn load(&self, path: &Path, password: &[u8]) -> Result<Skill, String> {
        if password.is_empty() {
            return Err("password is required".to_string());
        }
        let bytes = fs::read(path).map_err(|e| format!("read skill file: {}", e))?;
        if bytes.len() < HEADER_LEN {
            return Err(format!(
                "skill file too short: {} bytes (< {} header)",
                bytes.len(),
                HEADER_LEN
            ));
        }

        let mut version_bytes = [0u8; VERSION_LEN];
        version_bytes.copy_from_slice(&bytes[..VERSION_LEN]);
        let version = u32::from_le_bytes(version_bytes);
        if version != FILE_VERSION {
            return Err(format!(
                "unsupported skill file version: {} (expected {})",
                version, FILE_VERSION
            ));
        }

        let mut nonce = [0u8; NONCE_LEN];
        nonce.copy_from_slice(&bytes[VERSION_LEN..VERSION_LEN + NONCE_LEN]);
        let mut tag = [0u8; TAG_LEN];
        tag.copy_from_slice(
            &bytes[VERSION_LEN + NONCE_LEN..VERSION_LEN + NONCE_LEN + TAG_LEN],
        );
        let ciphertext = &bytes[HEADER_LEN..];

        let salt = self.load_salt()?;
        let mut key = derive_key(password, &salt)?;
        let decryptor = SkillDecryptor::new(key);
        let plaintext = decryptor
            .decrypt(ciphertext, &nonce, &tag)
            .map_err(|_| "decrypt failed: wrong password or corrupt file".to_string())?;
        key.zeroize();

        let envelope: EncryptedSkillFile = serde_json::from_slice(&plaintext)
            .map_err(|e| format!("parse skill envelope: {}", e))?;
        let skill: Skill = serde_json::from_value(envelope.body)
            .map_err(|e| format!("parse skill body: {}", e))?;
        Ok(skill)
    }

    /// Enumerate every `.enc` file under `<skills_dir>`. For
    /// each, we *do* need to decrypt the metadata block (it
    /// lives inside the envelope), but we throw away the body
    /// before returning — so the cost is one decrypt per file,
    /// and the front-end never sees the steps / parameters.
    pub fn list(&self, password: &[u8]) -> Result<Vec<SkillMeta>, String> {
        if !self.skills_dir.exists() {
            return Ok(Vec::new());
        }
        if password.is_empty() {
            return Err("password is required".to_string());
        }

        let salt = self.load_salt()?;
        let mut key = derive_key(password, &salt)?;
        let decryptor = SkillDecryptor::new(key);
        let mut out: Vec<SkillMeta> = Vec::new();
        for entry in fs::read_dir(&self.skills_dir)
            .map_err(|e| format!("read skills dir: {}", e))?
        {
            let entry = entry.map_err(|e| format!("entry: {}", e))?;
            let path = entry.path();
            if !path.is_file() {
                continue;
            }
            if path.extension().and_then(|e| e.to_str()) != Some(SKILL_EXT) {
                continue;
            }
            let bytes = match fs::read(&path) {
                Ok(b) => b,
                Err(_) => continue,
            };
            if bytes.len() < HEADER_LEN {
                continue;
            }
            let mut nonce = [0u8; NONCE_LEN];
            nonce.copy_from_slice(&bytes[VERSION_LEN..VERSION_LEN + NONCE_LEN]);
            let mut tag = [0u8; TAG_LEN];
            tag.copy_from_slice(
                &bytes[VERSION_LEN + NONCE_LEN..VERSION_LEN + NONCE_LEN + TAG_LEN],
            );
            let ciphertext = &bytes[HEADER_LEN..];
            let plaintext = match decryptor.decrypt(ciphertext, &nonce, &tag) {
                Ok(p) => p,
                Err(_) => continue, // skip corrupt / wrong-pw files silently
            };
            let envelope: EncryptedSkillFile = match serde_json::from_slice(&plaintext) {
                Ok(e) => e,
                Err(_) => continue,
            };
            out.push(envelope.meta);
        }
        key.zeroize();
        // Newest first — the front-end typically shows recency.
        out.sort_by_key(|b| std::cmp::Reverse(b.updated_at));
        Ok(out)
    }

    /// Remove the encrypted file for `skill_id`. Returns
    /// `Ok(())` even if the file did not exist — `delete` is
    /// idempotent.
    pub fn delete(&self, skill_id: &str) -> Result<(), String> {
        if skill_id.trim().is_empty() {
            return Err("skill_id is required".to_string());
        }
        // 同 store,拒绝含非法字符的 skill_id,避免删除任意文件。
        if sanitize_skill_id(skill_id) != skill_id {
            return Err(format!(
                "skill_id contains illegal characters (allowed: A-Z a-z 0-9 - _ .): {}",
                skill_id
            ));
        }
        let target = self.path_for(skill_id);
        match fs::remove_file(&target) {
            Ok(()) => Ok(()),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(e) => Err(format!("delete skill file: {}", e)),
        }
    }

    /// 将 Skill 渲染成 SKILL.md(YAML frontmatter + Markdown body)。
    /// 这是 Anthropic Agent Skills 2025-12 开放标准的导出入口,
    /// 与 `.enc` 加密落盘互不冲突 —— 调用方可选择导出 SKILL.md
    /// 用于分享 / 走查,本地仍以 `.enc` 为权威存储。
    pub fn export_skill_md(skill: &Skill) -> String {
        to_skill_md(skill)
    }

    /// 从 SKILL.md 文本反序列化回 Skill。不触碰 `.enc` 落盘格式;
    /// 解析成功后再由调用方决定是否 `store()`。
    pub fn import_skill_md(content: &str) -> Result<Skill, String> {
        from_skill_md(content)
    }

    // --- internal helpers --------------------------------------

    fn salt_path(&self) -> PathBuf {
        self.skills_dir.join(SALT_FILE)
    }

    fn load_or_create_salt(&self) -> Result<[u8; SALT_LEN], String> {
        fs::create_dir_all(&self.skills_dir)
            .map_err(|e| format!("create skills dir: {}", e))?;
        let p = self.salt_path();
        if p.exists() {
            return self.load_salt();
        }
        let mut salt = [0u8; SALT_LEN];
        getrandom::getrandom(&mut salt)
            .map_err(|e| format!("getrandom for salt: {}", e))?;
        fs::write(&p, salt).map_err(|e| format!("write salt: {}", e))?;
        Ok(salt)
    }

    fn load_salt(&self) -> Result<[u8; SALT_LEN], String> {
        let bytes = fs::read(self.salt_path())
            .map_err(|e| format!("read salt: {} (have you called store() at least once?)", e))?;
        if bytes.len() != SALT_LEN {
            return Err(format!(
                "salt file has wrong length: {} (expected {})",
                bytes.len(),
                SALT_LEN
            ));
        }
        let mut salt = [0u8; SALT_LEN];
        salt.copy_from_slice(&bytes);
        Ok(salt)
    }
}

/// Derive a 32-byte AES key from a password + per-install salt
/// using Argon2id with default parameters. The result is held
/// in a fixed-size array so the caller can `zeroize` it.
fn derive_key(password: &[u8], salt: &[u8]) -> Result<[u8; 32], String> {
    let mut key = [0u8; 32];
    Argon2::default()
        .hash_password_into(password, salt, &mut key)
        .map_err(|e| format!("argon2 derive: {}", e))?;
    Ok(key)
}

/// Re-export the timestamp helper.
pub fn now_utc() -> DateTime<Utc> {
    Utc::now()
}

/// Sanitize a skill_id to be safe as a filename component。
///
/// 允许的字符:A-Z a-z 0-9 `-` `_` `.`
/// 其他字符(包括路径分隔符 `/` `\` 与 `..`)被替换为 `_`。
/// 输入空字符串返回空字符串。
pub fn sanitize_skill_id(skill_id: &str) -> String {
    skill_id
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '-' || c == '_' || c == '.' {
                c
            } else {
                '_'
            }
        })
        .collect()
}
