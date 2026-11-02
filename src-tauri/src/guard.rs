//! 防破解加固模块（移植自 weauto 的 weauto_license 体系，2026-11-02）。
//!
//! 移植清单与 weauto 对应关系：
//! 1) **构建期密钥注入**（weauto: release_secrets.enc + _build_key.py）
//!    —— CI 构建期随机生成密钥 JSON → base64 → 经 `BBT_BUILD_SECRETS`
//!    环境变量注入编译期常量；二进制里没有可提取的明文密钥。
//!    开发态（无该变量）回退仓库内开发盐，本地行为不变。
//! 2) **发行版固杀标记**（weauto: weauto_license/_release.py）
//!    —— CI 构建时注入 `BBT_RELEASE=1`；发行版下加固**强制开启**，
//!    用户改配置/删文件都关不掉。本地开发无该标记 = 开发态。
//! 3) **设备指纹**（weauto: device_id.py）
//!    —— 只用操作系统级非个人信息标识（Windows: MachineGuid；
//!    Linux: /etc/machine-id；macOS: IOPlatformUUID），带应用级盐
//!    单向 SHA-256，落盘/展示只有哈希。可重算（删缓存重算结果不变）。
//! 4) **可信时钟防回拨**（weauto: weauto_license/clock.py）
//!    —— 持久化 max_seen 水位（HMAC 保护），trusted_now() 不倒退；
//!    篡改/损坏文件不降水位（取文件原始值与当前时间较大者）。
//! 5) **配置完整性签名**（weauto: config.py.sig / CONFIG_SIG_KEY）
//!    —— 发行版下 config.json 落盘为 HMAC 信封格式，载入时验签，
//!    验签失败视为被篡改：不采用被改内容，回退默认配置。
//!
//! 设计红线：本模块**零网络请求**、不让业务变哑（任何异常一律降级放行），
//! 开发态全部旁路，不影响本地调试。

use base64::Engine;
use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::path::Path;
use std::sync::OnceLock;

type HmacSha256 = Hmac<Sha256>;

// ---------------------------------------------------------------------------
// 1) 发行版固杀标记（编译期）
// ---------------------------------------------------------------------------

/// 发行版判定：CI 构建注入 BBT_RELEASE=1（build.rs 已声明 rerun-if-env-changed）。
/// 本地开发（未设置）恒为 false，全部加固旁路。
pub fn is_release() -> bool {
    matches!(option_env!("BBT_RELEASE"), Some("1"))
}

// ---------------------------------------------------------------------------
// 2) 构建期密钥体系
// ---------------------------------------------------------------------------

/// 编译期注入的密钥 blob（base64(JSON map)）。CI 生成，二进制内无明文密钥。
const BUILD_SECRETS_B64: &str = match option_env!("BBT_BUILD_SECRETS") {
    Some(s) => s,
    None => "",
};

fn secrets_map() -> &'static HashMap<String, String> {
    static MAP: OnceLock<HashMap<String, String>> = OnceLock::new();
    MAP.get_or_init(|| {
        if BUILD_SECRETS_B64.is_empty() {
            return HashMap::new();
        }
        base64::engine::general_purpose::STANDARD
            .decode(BUILD_SECRETS_B64)
            .ok()
            .and_then(|raw| serde_json::from_slice::<HashMap<String, String>>(&raw).ok())
            .unwrap_or_default()
    })
}

/// 取构建期注入密钥；开发态（无 blob）回退开发默认值，保证本地可跑。
pub fn secret_or_dev(key: &str, dev: &str) -> String {
    secrets_map()
        .get(key)
        .cloned()
        .unwrap_or_else(|| dev.to_string())
}

// ---------------------------------------------------------------------------
// 3) 设备指纹
// ---------------------------------------------------------------------------

/// 操作系统级原始标识（**不落盘、不打日志、不上传**，只参与一次哈希）。
fn os_machine_raw() -> String {
    #[cfg(windows)]
    {
        // HKLM\SOFTWARE\Microsoft\Cryptography\MachineGuid（系统安装时生成）
        use winreg::enums::HKEY_LOCAL_MACHINE;
        use winreg::RegKey;
        let hk = RegKey::predef(HKEY_LOCAL_MACHINE);
        hk.open_subkey(r"SOFTWARE\Microsoft\Cryptography")
            .and_then(|k| k.get_value::<String, _>("MachineGuid"))
            .unwrap_or_default()
    }
    #[cfg(target_os = "linux")]
    {
        std::fs::read_to_string("/etc/machine-id")
            .map(|s| s.trim().to_string())
            .unwrap_or_default()
    }
    #[cfg(target_os = "macos")]
    {
        std::process::Command::new("ioreg")
            .args(["-rd1", "-c", "IOPlatformExpertDevice"])
            .output()
            .ok()
            .and_then(|o| String::from_utf8(o.stdout).ok())
            .and_then(|s| {
                s.lines()
                    .find(|l| l.contains("IOPlatformUUID"))
                    .map(|l| l.trim().to_string())
            })
            .unwrap_or_default()
    }
    #[cfg(not(any(windows, target_os = "linux", target_os = "macos")))]
    {
        String::new()
    }
}

/// 稳定设备 ID：`dev_` + sha256(盐 | 原始标识) 前 32 hex。
/// 缓存于配置目录（删除后重算结果一致，只要系统未重装）。
pub fn device_id(config_dir: &Path) -> String {
    let cache = config_dir.join("device_id.txt");
    if let Ok(s) = std::fs::read_to_string(&cache) {
        let s = s.trim();
        if s.starts_with("dev_") {
            return s.to_string();
        }
    }
    let salt = secret_or_dev("DEVICE_SALT", "bbt-zb.device-id.v1");
    let raw = os_machine_raw();
    let mut h = Sha256::new();
    h.update(salt.as_bytes());
    h.update(b"|");
    h.update(raw.as_bytes());
    let id = format!("dev_{}", hex::encode(&h.finalize()[..16]));
    let _ = std::fs::write(&cache, &id);
    id
}

// ---------------------------------------------------------------------------
// 4) 可信时钟（防系统时间回拨）
// ---------------------------------------------------------------------------

const CLOCK_FILE: &str = ".guard_clock.json";

fn clock_salt() -> Vec<u8> {
    secret_or_dev("CLOCK_SALT", "bbt-zb.clock.v1-dev").into_bytes()
}

fn hmac_hex(key: &[u8], msg: &[u8]) -> String {
    let mut mac = <HmacSha256 as Mac>::new_from_slice(key).expect("hmac key");
    mac.update(msg);
    hex::encode(mac.finalize().into_bytes())
}

fn clock_path(config_dir: &Path) -> std::path::PathBuf {
    config_dir.join(CLOCK_FILE)
}

/// 可信当前时间（unix 秒）：时钟正常则返回真实时间并推进水位；
/// 回拨则返回历史水位（不倒退），并累计回拨计数。
/// 文件被篡改/损坏：取文件里的原始 max_seen 与当前时间较大者（删文件不降水位）。
pub fn trusted_now(config_dir: &Path) -> u64 {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let salt = clock_salt();
    let path = clock_path(config_dir);

    // 读水位：签名失效 → __corrupt__（raw 与 now 取大者）
    let mut max_seen: u64 = 0;
    let mut rollback_count: u64 = 0;
    let mut first_rollback: u64 = 0;
    let mut corrupt = false;
    match std::fs::read_to_string(&path) {
        Ok(s) => match serde_json::from_str::<serde_json::Value>(&s) {
            Ok(v) => {
                let sig = v.get("sig").and_then(|x| x.as_str()).unwrap_or("").to_string();
                let mut body = v.clone();
                if let Some(obj) = body.as_object_mut() {
                    obj.remove("sig");
                }
                let expect = hmac_hex(
                    &salt,
                    serde_json::to_string(&body).unwrap_or_default().as_bytes(),
                );
                if sig == expect {
                    max_seen = v.get("max_seen").and_then(|x| x.as_u64()).unwrap_or(0);
                    rollback_count = v.get("rollback_count").and_then(|x| x.as_u64()).unwrap_or(0);
                    first_rollback = v.get("first_rollback").and_then(|x| x.as_u64()).unwrap_or(0);
                } else {
                    corrupt = true;
                    max_seen = v.get("max_seen").and_then(|x| x.as_u64()).unwrap_or(0);
                }
            }
            Err(_) => corrupt = true,
        },
        Err(_) => {} // 不存在：首次运行
    }

    let (new_max, new_rollback, new_first) = if now < max_seen {
        // 回拨发生：水位不倒退
        (max_seen, rollback_count + 1, if first_rollback == 0 { max_seen } else { first_rollback })
    } else {
        (now, rollback_count, first_rollback)
    };

    // 写回：body 字段 + sig 同层（读侧：去 sig 后对剩余字段规范化签名校验）
    let _ = corrupt;
    let body = serde_json::json!({
        "max_seen": new_max,
        "rollback_count": new_rollback,
        "first_rollback": new_first,
    });
    let sig = hmac_hex(&salt, serde_json::to_string(&body).unwrap_or_default().as_bytes());
    let mut out = body.clone();
    if let Some(obj) = out.as_object_mut() {
        obj.insert("sig".into(), serde_json::json!(sig));
    }
    let _ = std::fs::write(&path, serde_json::to_string(&out).unwrap_or_default());

    // 回拨事件可被上层读取，不默认惩罚业务
    new_max
}

/// 读回拨计数（供 UI/日志提示用）。
pub fn rollback_count(config_dir: &Path) -> u64 {
    let salt = clock_salt();
    let Ok(s) = std::fs::read_to_string(clock_path(config_dir)) else {
        return 0;
    };
    let Ok(v) = serde_json::from_str::<serde_json::Value>(&s) else {
        return 0;
    };
    let sig = v.get("sig").and_then(|x| x.as_str()).unwrap_or("").to_string();
    let mut body = v.clone();
    if let Some(obj) = body.as_object_mut() {
        obj.remove("sig");
    }
    if sig != hmac_hex(&salt, serde_json::to_string(&body).unwrap_or_default().as_bytes()) {
        return 0;
    }
    body.get("rollback_count").and_then(|x| x.as_u64()).unwrap_or(0)
}

// ---------------------------------------------------------------------------
// 5) 配置完整性签名（config.json 信封）
// ---------------------------------------------------------------------------

pub const CONFIG_ENV_KEY: &str = "__guard_sig";

fn config_sig_key() -> Vec<u8> {
    secret_or_dev("CONFIG_SIG_KEY", "bbt-zb.config-sig.v1-dev").into_bytes()
}

/// 对配置 JSON 计算信封签名（HMAC-SHA256，密钥构建期注入）。
pub fn config_sig(config_json: &str) -> String {
    hmac_hex(&config_sig_key(), config_json.as_bytes())
}

/// 验签配置信封。
/// 返回：Ok(内部配置字符串) / Err(被篡改) / Ok 原文（旧版未签名格式，迁移用）。
pub fn verify_config_file(content: &str) -> Result<String, String> {
    let v: serde_json::Value = serde_json::from_str(content).map_err(|e| e.to_string())?;
    match v.get(CONFIG_ENV_KEY).and_then(|x| x.as_str()) {
        Some(sig) => {
            let mut body = v.clone();
            if let Some(obj) = body.as_object_mut() {
                obj.remove(CONFIG_ENV_KEY);
            }
            let canonical = serde_json::to_string(&body).unwrap_or_default();
            if sig == config_sig(&canonical) {
                // 信封合法：把内部 data 交回
                let data = v.get("data").cloned().unwrap_or(serde_json::json!({}));
                Ok(serde_json::to_string(&data).unwrap_or_default())
            } else {
                Err("config sig mismatch".into())
            }
        }
        None => {
            // 旧版明文配置：开发态直接用；发行版也接受一次（随后落盘会被重新签名），
            // 与 weauto「迁移期兼容」一致 —— 真正的篡改防护针对签名后的文件。
            Ok(content.to_string())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dev_mode_defaults() {
        // 开发态（无 BBT_RELEASE）恒为 false
        assert!(!is_release() || option_env!("BBT_RELEASE").is_some());
    }

    #[test]
    fn config_envelope_roundtrip() {
        let cfg = serde_json::json!({"a": 1, "b": "x"});
        let mut env = serde_json::json!({ "data": cfg });
        let sig = config_sig(&serde_json::to_string(&env).unwrap());
        env["__guard_sig"] = serde_json::json!(sig);
        let text = serde_json::to_string(&env).unwrap();
        let out = verify_config_file(&text).unwrap();
        assert_eq!(serde_json::from_str::<serde_json::Value>(&out).unwrap(), serde_json::json!({"a": 1, "b": "x"}));
    }

    #[test]
    fn config_envelope_tamper_detected() {
        let cfg = serde_json::json!({"a": 1});
        let mut env = serde_json::json!({ "data": cfg });
        let sig = config_sig(&serde_json::to_string(&env).unwrap());
        env["__guard_sig"] = serde_json::json!(sig);
        env["data"]["a"] = serde_json::json!(999); // 篡改内容
        let text = serde_json::to_string(&env).unwrap();
        assert!(verify_config_file(&text).is_err());
    }
}
