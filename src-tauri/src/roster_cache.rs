//! 团员名册高性能本地缓存（2026-11-08）
//!
//! 解决的问题：以前 `channel_targets` / 团员二维码页每次打开都要打
//! `leader/ledger` + 每个团一次 `leader/listMembers`（**串行**），页面越多、
//! 团越多越慢；网络抖动 / 未登录时直接「识别不到团员」。
//!
//! 现在：
//!  * **高性能**：多团名册并发拉取；TTL 内纯内存命中（0 网络）；同一时刻的
//!    并发刷新由 `REFRESH_LOCK` 合并为一次；过期走 stale-while-revalidate
//!    （先给旧数据、后台静默更新），用户点「刷新名册」才同步强刷。
//!  * **正常**：刷新失败 / 后端返回空 / 离线时回退本地缓存并明确标注来源，
//!    绝不让已有名册因为一次网络失败而消失。
//!  * **本地缓存**：独立 `member_roster.json` 落在 app_config_dir，与
//!    config.json 的 HMAC 信封解耦；重启 / 升级 exe 后立即可用，无需重新登录
//!    或重新扫码。按 `base_url|telephone` 隔离，换账号自动失效。

use futures_util::future::join_all;
use serde_json::{json, Map, Value};
use std::collections::HashSet;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Mutex, MutexGuard, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};
use tauri::{AppHandle, Emitter, Manager};
use tokio::sync::Mutex as AsyncMutex;

use crate::commands::call_with_relogin;
use crate::state::AppState;
use crate::util;

/// 名册新鲜期。期间完全不触网；过期后先给缓存再后台刷新。
const TTL_MS: i64 = 5 * 60 * 1000;
/// 后台刷新最小间隔：避免缓存一直过期（服务端不可用）时每次开页面都白打一次请求。
const RETRY_MS: i64 = 60 * 1000;
/// 「无权限」这类权限性失败重试也没用（当前登录的是团员账号，后端没有
/// 团员可读的名册接口），退避拉长到 30min，避免每 60s 白打一次 2s 的请求。
const PERM_RETRY_MS: i64 = 30 * 60 * 1000;
/// 用户连点「刷新名册」的去抖窗口：窗口内的第二次强刷直接复用刚拉到的结果。
const FORCE_DEDUP_MS: i64 = 1500;
const CACHE_FILE: &str = "member_roster.json";
const BACKUP_FILE: &str = "member_roster.json.bak";

#[derive(Clone, Default)]
struct Snapshot {
    /// 账号身份（base_url|telephone），换账号立即失效
    key: String,
    fetched_at: i64,
    /// leader/ledger 原始团列表（含 id / name）
    groups: Vec<Value>,
    /// groupId(字符串) → 该团成员数组，供名册页切团 0 网络
    by_group: Map<String, Value>,
    /// 所有团合并去重后的成员，供 channel_targets 下拉
    members: Vec<Value>,
}

impl Snapshot {
    fn to_value(&self) -> Value {
        json!({
            "v": 1,
            "key": self.key,
            "fetched_at": self.fetched_at,
            "groups": self.groups,
            "by_group": self.by_group,
            "members": self.members,
        })
    }

    /// 内容指纹：只有「名册真的变了」才通知前端重绘，避免每 5 分钟一次
    /// 定时刷新把用户正在操作的下拉框 / 团选择冲掉。
    fn digest(&self) -> u64 {
        use std::hash::{Hash, Hasher};
        let mut h = std::collections::hash_map::DefaultHasher::new();
        self.key.hash(&mut h);
        serde_json::to_string(&self.groups).unwrap_or_default().hash(&mut h);
        serde_json::to_string(&self.by_group).unwrap_or_default().hash(&mut h);
        serde_json::to_string(&self.members).unwrap_or_default().hash(&mut h);
        h.finish()
    }

    fn from_value(v: &Value) -> Option<Self> {
        if v.get("v").and_then(|x| x.as_i64()).unwrap_or(0) != 1 {
            return None;
        }
        let members = v.get("members").and_then(|x| x.as_array()).cloned().unwrap_or_default();
        if members.is_empty() {
            return None; // 空缓存没有回退价值，直接当无效
        }
        Some(Self {
            key: v.get("key").and_then(|x| x.as_str()).unwrap_or("").to_string(),
            fetched_at: v.get("fetched_at").and_then(|x| x.as_i64()).unwrap_or(0),
            groups: v.get("groups").and_then(|x| x.as_array()).cloned().unwrap_or_default(),
            by_group: v.get("by_group").and_then(|x| x.as_object()).cloned().unwrap_or_default(),
            members,
        })
    }
}

/// 内存镜像（惰性从磁盘加载一次）。
static MEM: OnceLock<Mutex<Option<Snapshot>>> = OnceLock::new();
static DISK_LOADED: OnceLock<AtomicBool> = OnceLock::new();
/// 串行化网络刷新：并发调用只发一次请求，后来者命中刚写好的缓存。
static REFRESH_LOCK: OnceLock<AsyncMutex<()>> = OnceLock::new();
/// 是否已有后台刷新在跑，避免过期时每个页面都 spawn 一个。
static REFRESHING: AtomicBool = AtomicBool::new(false);
/// 上次真正触网的时间戳（进程内），用于失败退避。
static LAST_ATTEMPT: AtomicI64 = AtomicI64::new(0);
/// 上次成功回源的时间戳，用于「连点刷新」去抖。
static LAST_SUCCESS: AtomicI64 = AtomicI64::new(0);
/// 最近一次在线刷新失败原因（前端据此说明「为什么显示的是旧名册」）。
static LAST_ERROR: Mutex<String> = Mutex::new(String::new());
/// 最近一次失败是否为权限性失败（重试无用，长退避）。
static LAST_PERMANENT: AtomicBool = AtomicBool::new(false);

fn mem_cell() -> &'static Mutex<Option<Snapshot>> {
    MEM.get_or_init(|| Mutex::new(None))
}

/// 统一走这里取锁：任何一次 panic 造成的中毒都不会让整个名册功能永久失效。
fn mem_guard() -> MutexGuard<'static, Option<Snapshot>> {
    mem_cell().lock().unwrap_or_else(|p| p.into_inner())
}

fn disk_loaded() -> &'static AtomicBool {
    DISK_LOADED.get_or_init(|| AtomicBool::new(false))
}

fn refresh_lock() -> &'static AsyncMutex<()> {
    REFRESH_LOCK.get_or_init(|| AsyncMutex::new(()))
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

fn cache_path(app: &AppHandle) -> Option<PathBuf> {
    app.path().app_config_dir().ok().map(|d| d.join(CACHE_FILE))
}

fn last_error() -> String {
    LAST_ERROR.lock().unwrap_or_else(|p| p.into_inner()).clone()
}

fn note_failure(reason: &str) {
    *LAST_ERROR.lock().unwrap_or_else(|p| p.into_inner()) = reason.to_string();
    LAST_PERMANENT.store(reason.contains("无权限"), Ordering::Relaxed);
}

fn clear_failure() {
    *LAST_ERROR.lock().unwrap_or_else(|p| p.into_inner()) = String::new();
    LAST_PERMANENT.store(false, Ordering::Relaxed);
}

fn fail(reason: String) -> (Option<Snapshot>, String, &'static str) {
    note_failure(&reason);
    (None, reason, "error")
}

fn retry_window() -> i64 {
    if LAST_PERMANENT.load(Ordering::Relaxed) { PERM_RETRY_MS } else { RETRY_MS }
}

/// 账号身份：换服务地址 / 换登录账号都会让旧缓存立即失效。
fn identity(app: &AppHandle) -> String {
    let st = app.state::<AppState>();
    let c = st.conn.lock().unwrap_or_else(|p| p.into_inner()).clone();
    format!(
        "{}|{}",
        c.base_url.trim_end_matches('/'),
        c.telephone.clone().unwrap_or_default()
    )
}

fn backup_path(app: &AppHandle) -> Option<PathBuf> {
    cache_path(app).map(|p| p.with_file_name(BACKUP_FILE))
}

fn parse_snapshot(path: &PathBuf) -> Option<Snapshot> {
    let text = std::fs::read_to_string(path).ok()?;
    let v: Value = serde_json::from_str(&text).ok()?;
    Snapshot::from_value(&v)
}

/// 主文件损坏（断电 / 写盘中断）时自动用上一份有效备份恢复，
/// 并把备份写回主文件，避免每次启动都走恢复分支。
fn read_disk(app: &AppHandle) -> Option<Snapshot> {
    let p = cache_path(app)?;
    if let Some(s) = parse_snapshot(&p) {
        return Some(s);
    }
    let b = backup_path(app)?;
    let s = parse_snapshot(&b)?;
    eprintln!("[roster] 名册主缓存损坏，已从 {} 恢复", BACKUP_FILE);
    if let Ok(text) = std::fs::read_to_string(&b) {
        let _ = util::write_atomic(&p, &text);
    }
    Some(s)
}

fn mem(app: &AppHandle) -> Option<Snapshot> {
    let mut g = mem_guard();
    if g.is_none() && !disk_loaded().swap(true, Ordering::Relaxed) {
        *g = read_disk(app);
    }
    g.clone()
}

fn set_mem(s: Snapshot) {
    *mem_guard() = Some(s);
}

fn write_disk(app: &AppHandle, s: &Snapshot) {
    let Some(p) = cache_path(app) else { return };
    let Ok(text) = serde_json::to_string_pretty(&s.to_value()) else { return };
    // 先把上一份「有效」缓存留作备份，再写主文件（与 config.json.bak 同策略）
    if parse_snapshot(&p).is_some() {
        if let Some(b) = backup_path(app) {
            let _ = util::write_atomic(&b, &std::fs::read_to_string(&p).unwrap_or_default());
        }
    }
    let _ = util::write_atomic(&p, &text);
}

/// 清缓存（供 UI「强制刷新」或换账号时调用）。
pub fn clear(app: &AppHandle) {
    *mem_guard() = None;
    disk_loaded().store(true, Ordering::Relaxed);
    LAST_ATTEMPT.store(0, Ordering::Relaxed);
    LAST_SUCCESS.store(0, Ordering::Relaxed);
    clear_failure();
    if let Some(p) = cache_path(app) {
        let _ = std::fs::remove_file(p);
    }
    if let Some(b) = backup_path(app) {
        let _ = std::fs::remove_file(b);
    }
    let _ = app.emit("roster-updated", json!({ "source": "cleared", "count": 0, "changed": true }));
}

/// 给前端的可观测状态。不要只靠颜色，文案里直接写来源与新鲜度。
fn cache_meta(source: &str, s: Option<&Snapshot>, refreshing: bool) -> Value {
    let (age_ms, cached_at, count) = match s {
        Some(x) => (now_ms().saturating_sub(x.fetched_at).max(0), x.fetched_at, x.members.len()),
        None => (-1, 0, 0),
    };
    let mut v = json!({
        "source": source,           // memory | disk | stale | network | error
        "age_ms": age_ms,
        "cached_at": cached_at,
        "count": count,
        "stale": source == "stale" || source == "error",
        "refreshing": refreshing,
        "ttl_ms": TTL_MS,
        "retry_ms": retry_window(),
        "last_attempt_ms": LAST_ATTEMPT.load(Ordering::Relaxed),
        "last_success_ms": LAST_SUCCESS.load(Ordering::Relaxed),
        "path": CACHE_FILE,
        "backup": BACKUP_FILE,
    });
    let err = last_error();
    if !err.is_empty() {
        v["error"] = json!(err);
    }
    v
}

pub struct RosterResult {
    pub groups: Vec<Value>,
    pub by_group: Map<String, Value>,
    pub members: Vec<Value>,
    /// 仅异常/部分失败时非空，正常缓存状态放 `cache`，避免刷屏前端日志
    pub note: String,
    pub cache: Value,
}

fn result(s: Snapshot, source: &str, note: String, refreshing: bool) -> RosterResult {
    let cache = cache_meta(source, Some(&s), refreshing);
    RosterResult {
        groups: s.groups,
        by_group: s.by_group,
        members: s.members,
        note,
        cache,
    }
}

fn empty(note: String) -> RosterResult {
    RosterResult {
        groups: Vec::new(),
        by_group: Map::new(),
        members: Vec::new(),
        note,
        cache: cache_meta("error", None, false),
    }
}

fn id_key(v: &Value) -> String {
    match v {
        Value::String(s) => s.trim().to_string(),
        Value::Number(n) => n.to_string(),
        _ => v.to_string(),
    }
}

/// `bbt::call` 对 `result.status=false` 会原样返回（只有会话失效才抛错），
/// 所以「无权限 / 未登录 / 服务器繁忙」会被误读成「名册为空」。
/// 这里把业务失败显式转成原因，UI 才能给出可执行的提示。
fn api_error(r: &Value) -> Option<String> {
    let res = r.get("result").unwrap_or(r);
    let status = res.get("status").and_then(|v| v.as_bool());
    let msg = res.get("msg").and_then(|v| v.as_str()).unwrap_or("").trim();
    if status == Some(false) && !msg.is_empty() && msg != "ok" {
        return Some(msg.to_string());
    }
    None
}

/// 真正触网刷新一次：ledger 取团 → 多团 listMembers 并发 → 合并去重 → 落盘。
/// 返回 (新快照或 None, 说明, 来源)。失败时**不覆盖**已有缓存。
async fn refresh(app: &AppHandle, force: bool) -> (Option<Snapshot>, String, &'static str) {
    let last_success = LAST_SUCCESS.load(Ordering::Relaxed);
    let _guard = refresh_lock().lock().await;
    LAST_ATTEMPT.store(now_ms(), Ordering::Relaxed);

    let key = identity(app);
    // 连点「刷新名册」/ 多个页面同时 force：窗口内刚成功回源过就直接复用，
    // 不再重复打一轮 ledger + N 次 listMembers。
    if force
        && last_success > 0
        && now_ms().saturating_sub(last_success) < FORCE_DEDUP_MS
    {
        if let Some(s) = mem(app) {
            if s.key == key && !s.members.is_empty() {
                return (Some(s), String::new(), "memory");
            }
        }
    }
    // 拿锁期间可能已被别的请求刷新过（in-flight 合并的核心）。
    // force=true 是用户主动点「刷新名册」，必须真回源，不能被这段吃掉。
    if !force {
        if let Some(s) = mem(app) {
            if s.key == key && now_ms().saturating_sub(s.fetched_at) < TTL_MS && !s.members.is_empty() {
                return (Some(s), String::new(), "memory");
            }
        }
    }

    // 1) 团列表（一次请求；token 失效时由 call_with_relogin 自动重登）
    let ledger = match call_with_relogin(app, "leader/ledger", &json!({})).await {
        Ok(r) => {
            if let Some(e) = api_error(&r) {
                let why = if e.contains("无权限") {
                    format!(
                        "{}（当前登录账号无团长名册读权限；后端暂无团员可读名册接口，已保留本地缓存。用团长账号登录后可在线更新）",
                        e
                    )
                } else {
                    e
                };
                return fail(format!("拉取团列表失败: {}", why));
            }
            r
        }
        Err(e) => return fail(format!("拉取团列表失败: {}", e)),
    };
    let groups: Vec<Value> = ledger
        .pointer("/result/data/list")
        .and_then(|l| l.as_array())
        .cloned()
        .unwrap_or_default();
    let group_ids: Vec<Value> = groups
        .iter()
        .filter_map(|g0| g0.get("id").cloned())
        .filter(|v| match v {
            Value::Null => false,
            Value::String(s) => !s.trim().is_empty(),
            _ => true,
        })
        .collect();
    if group_ids.is_empty() {
        return fail("leader/ledger 未返回可用团（团长账号未管理任何团？）".into());
    }

    // 2) 多团并发（原来串行 N 次 RTT，现在 ≈ 1 次 RTT）
    let futs = group_ids.iter().map(|gid| {
        let cond = json!({ "groupId": gid.clone() });
        async move {
            let r = call_with_relogin(app, "leader/listMembers", &cond).await;
            (gid.clone(), r)
        }
    });
    let fetched = join_all(futs).await;

    let mut by_group = Map::new();
    let mut members: Vec<Value> = Vec::new();
    let mut seen: HashSet<String> = HashSet::new();
    let mut errs: Vec<String> = Vec::new();
    for (gid, res) in fetched {
        match res {
            Ok(r) => {
                if let Some(e) = api_error(&r) {
                    errs.push(format!("团{}: {}", id_key(&gid), e));
                    continue;
                }
                let rows = r
                    .pointer("/result/data/list")
                    .and_then(|l| l.as_array())
                    .cloned()
                    .unwrap_or_default();
                by_group.insert(id_key(&gid), Value::Array(rows.clone()));
                for m in rows {
                    let k = m
                        .get("id")
                        .map(id_key)
                        .unwrap_or_else(|| serde_json::to_string(&m).unwrap_or_default());
                    if seen.insert(k) {
                        members.push(m);
                    }
                }
            }
            Err(e) => errs.push(format!("团{}: {}", id_key(&gid), e)),
        }
    }

    let note = if !errs.is_empty() {
        format!("部分团名册拉取失败: {}", errs.join("；"))
    } else {
        String::new()
    };
    if members.is_empty() {
        // 空结果一律不覆盖旧名册：后端偶发空列表 / 半故障时会把缓存洗掉
        let why = if !errs.is_empty() {
            format!("拉取名册失败: {}", errs.join("；"))
        } else {
            format!("已查 {} 个团，名册均为空", group_ids.len())
        };
        return fail(why);
    }

    // 部分团失败但已有可用成员：保留新名册，同时把原因留给 UI 展示
    if note.is_empty() {
        clear_failure();
    } else {
        note_failure(&note);
    }
    let snap = Snapshot { key, fetched_at: now_ms(), groups, by_group, members };
    let changed = mem(app).map(|old| old.digest() != snap.digest()).unwrap_or(true);
    set_mem(snap.clone());
    write_disk(app, &snap);
    LAST_SUCCESS.store(now_ms(), Ordering::Relaxed);
    if changed {
        // 后台刷新完成后前端才能看到新名册；只在内容真变化时通知，避免冲掉
        // 用户正在操作的下拉框。前端收到后调 member_roster/channel_targets（0 网络）。
        let _ = app.emit(
            "roster-updated",
            json!({
                "source": "network",
                "changed": true,
                "count": snap.members.len(),
                "cache": cache_meta("network", Some(&snap), false),
            }),
        );
    }
    (Some(snap), note, "network")
}

/// 后台刷新标志的 RAII 复位：任务 panic / 被取消也不会把 REFRESHING 永久卡在 true。
struct RefreshingGuard;
impl Drop for RefreshingGuard {
    fn drop(&mut self) {
        REFRESHING.store(false, Ordering::SeqCst);
    }
}

fn spawn_refresh(app: AppHandle) {
    if REFRESHING.swap(true, Ordering::SeqCst) {
        return;
    }
    tauri::async_runtime::spawn(async move {
        let _reset = RefreshingGuard;
        let _ = refresh(&app, false).await;
    });
}

/// 启动预热：setup 后延迟触发一次取名册。磁盘缓存新鲜时是纯内存命中（0 网络）；
/// 缓存缺失 / 过期时在后台补齐，用户打开任意页面时名册已就绪。
pub async fn warm_up(app: AppHandle) {
    tokio::time::sleep(std::time::Duration::from_secs(3)).await;
    if app.try_state::<AppState>().is_none() {
        return;
    }
    let c = {
        let st = app.state::<AppState>();
        let out = st.conn.lock().unwrap_or_else(|p| p.into_inner()).clone();
        out
    };
    if c.base_url.is_empty() {
        return; // 未配置接口地址时不白打请求
    }
    let r = get(&app, false).await;
    eprintln!(
        "[roster] 预热完成 source={} count={}",
        r.cache.get("source").and_then(|v| v.as_str()).unwrap_or("error"),
        r.members.len()
    );
}

/// 取名册的统一入口。
///  * `force=false`：新鲜 → 纯内存；过期 → 立刻给旧数据 + 后台刷新（秒开）。
///  * `force=true` ：同步强刷（用户点「刷新名册」），失败回退旧缓存。
pub async fn get(app: &AppHandle, force: bool) -> RosterResult {
    if app.try_state::<AppState>().is_none() {
        return empty("AppState 未初始化".into());
    }
    let key = identity(app);
    let snap = mem(app);
    let same_key = snap
        .as_ref()
        .map(|s| s.key == key && !s.members.is_empty())
        .unwrap_or(false);
    let age = snap
        .as_ref()
        .map(|s| now_ms().saturating_sub(s.fetched_at))
        .unwrap_or(i64::MAX);

    if !force && same_key && age < TTL_MS {
        // 重启后第一次调用会惰性把磁盘缓存载入内存，同样属于 0 网络命中
        return result(snap.unwrap(), "memory", String::new(), false);
    }
    if !force && same_key {
        // 权限性失败（当前是团员账号、后端无团员可读名册接口）重试无用，
        // 退避 30min；一般网络抖动仍按 60s 快速重试。
        let due = now_ms().saturating_sub(LAST_ATTEMPT.load(Ordering::Relaxed)) >= retry_window();
        if due {
            spawn_refresh(app.clone());
        }
        return result(snap.unwrap(), "stale", String::new(), due);
    }

    let (fresh, why, src) = refresh(app, force).await;
    match fresh {
        Some(s) => {
            let note = if src == "network" { why } else { String::new() };
            result(s, src, note, false)
        }
        None => {
            // 关键：刷新失败也把旧名册交出去，页面不会「识别不到团员」
            if let Some(old) = snap {
                let mut r = result(
                    old,
                    "stale",
                    format!("{}；当前显示本地缓存（离线可用）", why),
                    false,
                );
                if let Some(obj) = r.cache.as_object_mut() {
                    obj.insert("error".into(), json!(why));
                }
                r
            } else {
                empty(why)
            }
        }
    }
}

/// 缓存状态（供 UI / 诊断）：不触网。
pub fn status(app: &AppHandle) -> Value {
    if app.try_state::<AppState>().is_none() {
        return cache_meta("error", None, false);
    }
    let key = identity(app);
    let snap = mem(app);
    match snap {
        Some(s) if s.key == key => cache_meta(
            if now_ms().saturating_sub(s.fetched_at) < TTL_MS { "memory" } else { "stale" },
            Some(&s),
            REFRESHING.load(Ordering::SeqCst),
        ),
        Some(s) => {
            let mut v = cache_meta("stale", Some(&s), false);
            if let Some(obj) = v.as_object_mut() {
                obj.insert("error".into(), json!("缓存属于其它登录账号，等待重新拉取"));
            }
            v
        }
        None => cache_meta("error", None, false),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn snap() -> Snapshot {
        Snapshot {
            key: "https://api.example.com|13800000000".into(),
            fetched_at: 1_700_000_000_000,
            groups: vec![json!({ "id": 1, "name": "一团" })],
            by_group: Map::new(),
            members: vec![json!({ "id": 33, "name": "张三" })],
        }
    }

    #[test]
    fn disk_roundtrip_and_invalid_cache() {
        let s = snap();
        let restored = Snapshot::from_value(&s.to_value()).expect("有效缓存应可恢复");
        assert_eq!(restored.key, s.key);
        assert_eq!(restored.members.len(), 1);
        assert_eq!(restored.digest(), s.digest());

        assert!(Snapshot::from_value(&json!({ "v": 2, "members": [1] })).is_none());
        assert!(Snapshot::from_value(&json!({ "v": 1, "members": [] })).is_none());
        assert!(Snapshot::from_value(&json!({ "v": 1 })).is_none());
        // fetched_at 不参与指纹：续期不算「名册变了」，不该触发前端重绘
        let mut renewed = s.clone();
        renewed.fetched_at += 60_000;
        assert_eq!(renewed.digest(), s.digest());
    }

    #[test]
    fn digest_detects_real_changes() {
        let s = snap();
        let mut changed = s.clone();
        changed.members.push(json!({ "id": 34, "name": "李四" }));
        assert_ne!(s.digest(), changed.digest());
    }

    #[test]
    fn business_failure_is_not_empty_roster() {
        assert_eq!(
            api_error(&json!({ "result": { "status": false, "msg": "无权限" } })),
            Some("无权限".into())
        );
        assert_eq!(
            api_error(&json!({ "code": 0, "message": "x", "result": { "status": true, "msg": "ok" } })),
            None
        );
        assert_eq!(api_error(&json!({ "result": { "status": false, "msg": "ok" } })), None);
        assert_eq!(api_error(&json!({ "result": { "data": { "list": [] } } })), None);
    }

    #[test]
    fn cache_meta_exposes_freshness() {
        let s = snap();
        let fresh = cache_meta("memory", Some(&s), false);
        assert_eq!(fresh["stale"], false);
        assert_eq!(fresh["count"], 1);
        assert_eq!(fresh["source"], "memory");
        let stale = cache_meta("stale", Some(&s), true);
        assert_eq!(stale["stale"], true);
        assert_eq!(stale["refreshing"], true);
        let bad = cache_meta("error", None, false);
        assert_eq!(bad["count"], 0);
        assert_eq!(bad["age_ms"], -1);
    }

    #[test]
    fn id_key_normalizes_numbers_and_strings() {
        assert_eq!(id_key(&json!(33)), "33");
        assert_eq!(id_key(&json!(" 33 ")), "33");
        assert_eq!(id_key(&json!("a")), "a");
    }

    #[test]
    fn permission_failure_backs_off_longer_than_network_flap() {
        clear_failure();
        assert_eq!(retry_window(), RETRY_MS);
        note_failure("拉取团列表失败: 无权限");
        assert_eq!(retry_window(), PERM_RETRY_MS);
        assert_eq!(last_error(), "拉取团列表失败: 无权限");
        note_failure("网络错误: timeout");
        assert_eq!(retry_window(), RETRY_MS);
        clear_failure();
        assert_eq!(retry_window(), RETRY_MS);
    }
}
