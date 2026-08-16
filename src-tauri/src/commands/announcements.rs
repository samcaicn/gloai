// Copyright (c) 2026 AIMarketing
//
// 公告 / 通知系统后端命令。
//
// 背景：前端 `shared/announcement-system/*` 是从 BitFun 克隆过来的
// 公告 UI（Toast + FeatureModal），`AnnouncementProvider` 已挂在
// `app/App.tsx`，启动后调用 `get_pending_announcements` 拉卡片。
// 但 tupai 后端此前从未实现这些命令（Rust 侧 `core/src/service/
// announcement/types.rs` 从未移植），导致公告功能整段失效。本模块
// 补齐 6 个前端契约命令，并接入「通过 MCP 查询客户端更新」生成
// 更新提示卡片。
//
// 前端契约（src/web-ui/.../shared/announcement-system/services/
// AnnouncementService.ts）：
//   get_pending_announcements()               → AnnouncementCard[]
//   mark_announcement_seen({ request:{id} })  → void
//   dismiss_announcement({ request:{id} })    → void
//   never_show_announcement({ request:{id} }) → void
//   trigger_announcement({ request:{id} })    → AnnouncementCard | null
//   get_announcement_tips()                   → AnnouncementCard[]
//
// 类型与前端 `types/index.ts` 一一对应（snake_case wire format）。
//
// 持久化：announcements_state.json 落在 app_data_dir 根目录，
// 复用 tenant.rs 的「文件级 tokio Mutex + 原子写 .tmp→rename」模式。

#![allow(dead_code)]

use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Manager};

const STATE_FILE_NAME: &str = "announcements_state.json";

/// 文件级锁，串行化 state 的读写，避免并发拉卡片 / 标已读时读到
/// 半截 JSON 或写覆盖。参考 tenant.rs 的 TENANT_FILE_LOCK。
static STATE_FILE_LOCK: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

// ─────────────────────────────────────────────────────────────────
// Wire types（与前端 types/index.ts 镜像，snake_case）
// ─────────────────────────────────────────────────────────────────

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum CardType {
    Feature,
    News,
    Tip,
    Announcement,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum CardSource {
    Local,
    Remote,
    BuiltinTip,
}

/// 触发条件，tagged union（前端 `{ type: '...' }`）。
#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum TriggerCondition {
    /// 新版本首次打开时展示一次
    VersionFirstOpen,
    /// 应用第 n 次打开时展示
    AppNthOpen { n: u32 },
    /// 某功能被使用后展示（后端暂不跟踪功能使用，pending 阶段跳过）
    FeatureUsed { feature: String },
    /// 仅手动触发（不进 pending 队列）
    Manual,
    /// 每次都可展示（受 once_per_version / seen 约束）
    Always,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct TriggerRule {
    pub condition: TriggerCondition,
    #[serde(default)]
    pub delay_ms: u64,
    #[serde(default)]
    pub once_per_version: bool,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct ToastConfig {
    pub icon: String,
    pub title: String,
    pub description: String,
    pub action_label: String,
    pub dismissible: bool,
    #[serde(default)]
    pub auto_dismiss_ms: Option<u64>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "snake_case")]
pub enum ModalSize {
    Sm,
    Md,
    Lg,
    Xl,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "snake_case")]
pub enum CompletionAction {
    Dismiss,
    NeverShowAgain,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "snake_case")]
pub enum PageLayout {
    TextOnly,
    MediaLeft,
    MediaRight,
    MediaTop,
    FullscreenMedia,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "snake_case")]
pub enum MediaType {
    Lottie,
    Video,
    Image,
    Gif,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct MediaConfig {
    pub media_type: MediaType,
    /// `public/announcements/` 下的相对路径或 HTTPS URL
    pub src: String,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct ModalPage {
    pub layout: PageLayout,
    pub title: String,
    pub body: String,
    #[serde(default)]
    pub media: Option<MediaConfig>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct ModalConfig {
    pub size: ModalSize,
    pub closable: bool,
    pub pages: Vec<ModalPage>,
    pub completion_action: CompletionAction,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct AnnouncementCard {
    pub id: String,
    pub card_type: CardType,
    pub source: CardSource,
    #[serde(default)]
    pub app_version: Option<String>,
    pub priority: i32,
    pub trigger: TriggerRule,
    pub toast: ToastConfig,
    #[serde(default)]
    pub modal: Option<ModalConfig>,
    #[serde(default)]
    pub expires_at: Option<i64>,
}

/// 前端所有「单 id」命令的入参：`{ request: { id } }`。
#[derive(Deserialize, Debug)]
pub struct IdRequest {
    pub id: String,
}

// ─────────────────────────────────────────────────────────────────
// 持久化状态
// ─────────────────────────────────────────────────────────────────

/// 落盘状态。`seen` / `dismissed` 是「当前 app 版本内」的集合，版本
/// 变化时清空（让 version_first_open 卡片能在新版本再次出现）；
/// `never_show` 跨版本永久保留；`open_count` 累计。
#[derive(Serialize, Deserialize, Clone, Debug, Default)]
struct AnnouncementState {
    /// 记录该 state 对应的 app 版本；与当前版本不一致时重置 seen/dismissed。
    #[serde(default)]
    app_version: String,
    #[serde(default)]
    open_count: u32,
    #[serde(default)]
    seen: Vec<String>,
    #[serde(default)]
    dismissed: Vec<String>,
    #[serde(default)]
    never_show: Vec<String>,
}

fn state_path(app: &AppHandle) -> Result<std::path::PathBuf, String> {
    let dir = app
        .path()
        .app_data_dir()
        .map_err(|e| format!("app_data_dir failed: {}", e))?;
    Ok(dir.join(STATE_FILE_NAME))
}

fn current_app_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

async fn load_state(app: &AppHandle) -> AnnouncementState {
    let _guard = STATE_FILE_LOCK.lock().await;
    load_state_locked(app).await
}

async fn load_state_locked(app: &AppHandle) -> AnnouncementState {
    let path = match state_path(app) {
        Ok(p) => p,
        Err(e) => {
            log::warn!("[announcements] cannot resolve state path: {}", e);
            return AnnouncementState {
                app_version: current_app_version(),
                ..Default::default()
            };
        }
    };
    let mut state: AnnouncementState = match tokio::fs::read_to_string(&path).await {
        Ok(text) => serde_json::from_str(&text).unwrap_or_default(),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => AnnouncementState::default(),
        Err(e) => {
            log::warn!("[announcements] read {} failed: {}", path.display(), e);
            AnnouncementState::default()
        }
    };
    // 版本变化 → 清空当前版本内的 seen/dismissed，保留 never_show/open_count。
    let cur = current_app_version();
    if state.app_version != cur {
        log::info!(
            "[announcements] app version changed {} -> {}, resetting per-version state",
            state.app_version,
            cur
        );
        state.seen.clear();
        state.dismissed.clear();
        state.app_version = cur;
    }
    state
}

async fn save_state_locked(app: &AppHandle, state: &AnnouncementState) -> Result<(), String> {
    let path = state_path(app)?;
    if let Some(parent) = path.parent() {
        tokio::fs::create_dir_all(parent)
            .await
            .map_err(|e| format!("create announcements dir failed: {}", e))?;
    }
    let tmp = path.with_extension("json.tmp");
    let text = serde_json::to_string_pretty(state)
        .map_err(|e| format!("serialize announcements state failed: {}", e))?;
    tokio::fs::write(&tmp, &text)
        .await
        .map_err(|e| format!("write announcements tmp failed: {}", e))?;
    tokio::fs::rename(&tmp, &path)
        .await
        .map_err(|e| format!("rename announcements tmp->final failed: {}", e))?;
    Ok(())
}

/// 读-改-写一次 state（持锁），用于 mark/dismiss/never_show。
async fn mutate_state<F>(app: &AppHandle, f: F) -> Result<(), String>
where
    F: FnOnce(&mut AnnouncementState),
{
    let _guard = STATE_FILE_LOCK.lock().await;
    let mut state = load_state_locked(app).await;
    f(&mut state);
    save_state_locked(app, &state).await
}

fn push_unique(v: &mut Vec<String>, id: &str) {
    if !v.iter().any(|x| x == id) {
        v.push(id.to_string());
    }
}

// ─────────────────────────────────────────────────────────────────
// 内置卡片注册表
// ─────────────────────────────────────────────────────────────────

/// 内置公告卡片。这里定义静态卡片（欢迎 / 功能引导 / 使用技巧）。
/// 远程 / 动态卡片（如客户端更新）单独在 get_pending 中拼装。
fn builtin_cards() -> Vec<AnnouncementCard> {
    let ver = current_app_version();
    vec![
        // 欢迎卡片：新版本首次打开展示一次。id 与前端 DEBUG_CARD_IDS
        // 中的 'feature_welcome' 对齐，方便 Ctrl+Shift+Alt+D 预览。
        AnnouncementCard {
            id: "feature_welcome".to_string(),
            card_type: CardType::Feature,
            source: CardSource::Local,
            app_version: Some(ver.clone()),
            priority: 100,
            trigger: TriggerRule {
                condition: TriggerCondition::VersionFirstOpen,
                delay_ms: 1200,
                once_per_version: true,
            },
            toast: ToastConfig {
                icon: "🎉".to_string(),
                title: format!("欢迎使用 tupai v{}", ver),
                description: "自进化跟踪助手已就绪，点击查看本次更新亮点。".to_string(),
                action_label: "查看".to_string(),
                dismissible: true,
                auto_dismiss_ms: None,
            },
            modal: Some(ModalConfig {
                size: ModalSize::Md,
                closable: true,
                pages: vec![ModalPage {
                    layout: PageLayout::TextOnly,
                    title: format!("tupai v{}", ver),
                    body: "· 监控 / 自动化 / 自进化能力持续在线\n· 悬浮球随时唤起主界面\n· 录制可一键转流程图\n\n祝使用愉快！".to_string(),
                    media: None,
                }],
                completion_action: CompletionAction::Dismiss,
            }),
            expires_at: None,
        },
        // 快捷键提示卡片：id 与前端 DEBUG_CARD_IDS 的
        // 'feature_shortcuts_v0_2_2' 对齐。
        AnnouncementCard {
            id: "feature_shortcuts_v0_2_2".to_string(),
            card_type: CardType::Tip,
            source: CardSource::BuiltinTip,
            app_version: Some(ver.clone()),
            priority: 40,
            trigger: TriggerRule {
                condition: TriggerCondition::AppNthOpen { n: 3 },
                delay_ms: 2000,
                once_per_version: true,
            },
            toast: ToastConfig {
                icon: "⌨️".to_string(),
                title: "小技巧：快捷键".to_string(),
                description: "开发模式下 Ctrl+Shift+Alt+D 可预览所有公告卡片。".to_string(),
                action_label: "知道了".to_string(),
                dismissible: true,
                auto_dismiss_ms: Some(8000),
            },
            modal: None,
            expires_at: None,
        },
    ]
}

/// 通过 MCP `client.check_update` 查询客户端更新，若有更新则生成一张
/// announcement 卡片。best-effort：任何网络 / 解析错误都只记日志并返回
/// None，绝不阻塞公告加载。
///
/// token 由前端从 localStorage(`trae_device_token`) 读取后透传；无 token
/// 时仍尝试匿名查询（后端可能允许），失败即跳过。
async fn fetch_update_card(token: Option<String>) -> Option<AnnouncementCard> {
    let cur = current_app_version();
    let params = serde_json::json!({
        "current_version": cur,
        "platform": std::env::consts::OS,
    });
    // 复用 mcp_proxy 的 mcp_call_v2（走 rustls，绕开 WebView2 TLS）。
    let resp = match crate::commands::mcp_proxy::mcp_call_v2(
        "client.check_update".to_string(),
        params,
        Some(15),
        token,
    )
    .await
    {
        Ok(v) => v,
        Err(e) => {
            log::info!("[announcements] client.check_update skipped: {}", e);
            return None;
        }
    };

    // 兼容多种响应形状：`{ ok, data:{...} }` 或直接 `{...}`。
    let data = resp.get("data").unwrap_or(&resp);

    // 是否有更新：优先看 has_update / update_available 布尔；否则比较版本串。
    let latest = data
        .get("latest_version")
        .or_else(|| data.get("version"))
        .and_then(|v| v.as_str())
        .map(|s| s.to_string());
    let has_update = data
        .get("has_update")
        .or_else(|| data.get("update_available"))
        .and_then(|v| v.as_bool())
        .unwrap_or_else(|| match &latest {
            Some(l) => version_gt(l, &cur),
            None => false,
        });

    if !has_update {
        log::debug!("[announcements] client.check_update: 已是最新版本");
        return None;
    }

    let latest = latest.unwrap_or_else(|| "new".to_string());
    let notes = data
        .get("release_notes")
        .or_else(|| data.get("notes"))
        .or_else(|| data.get("changelog"))
        .and_then(|v| v.as_str())
        .unwrap_or("发现新版本，建议尽快更新以获得最新功能与修复。")
        .to_string();
    let url = data
        .get("download_url")
        .or_else(|| data.get("url"))
        .and_then(|v| v.as_str())
        .map(|s| s.to_string());

    // 版本号进 id，保证「每个新版本」是一张新卡片（旧的 dismissed 不影响）。
    let id = format!("client_update_{}", latest);
    let body = match &url {
        Some(u) => format!("{}\n\n下载地址：{}", notes, u),
        None => notes.clone(),
    };

    Some(AnnouncementCard {
        id,
        card_type: CardType::Announcement,
        source: CardSource::Remote,
        app_version: Some(latest.clone()),
        priority: 200, // 更新提示优先级最高
        trigger: TriggerRule {
            condition: TriggerCondition::Always,
            delay_ms: 500,
            once_per_version: false,
        },
        toast: ToastConfig {
            icon: "⬆️".to_string(),
            title: format!("发现新版本 v{}", latest),
            description: "点击查看更新内容。".to_string(),
            action_label: "查看更新".to_string(),
            dismissible: true,
            auto_dismiss_ms: None,
        },
        modal: Some(ModalConfig {
            size: ModalSize::Md,
            closable: true,
            pages: vec![ModalPage {
                layout: PageLayout::TextOnly,
                title: format!("tupai v{} 可用", latest),
                body,
                media: None,
            }],
            completion_action: CompletionAction::Dismiss,
        }),
        expires_at: None,
    })
}

/// 朴素语义化版本比较：a > b 返回 true。非法段按 0 处理，避免 panic。
fn version_gt(a: &str, b: &str) -> bool {
    let parse = |s: &str| -> Vec<u64> {
        s.trim_start_matches('v')
            .split(['.', '-', '+'])
            .map(|p| p.parse::<u64>().unwrap_or(0))
            .collect()
    };
    let (va, vb) = (parse(a), parse(b));
    let n = va.len().max(vb.len());
    for i in 0..n {
        let x = va.get(i).copied().unwrap_or(0);
        let y = vb.get(i).copied().unwrap_or(0);
        if x != y {
            return x > y;
        }
    }
    false
}

/// 卡片是否已过期。
fn is_expired(card: &AnnouncementCard) -> bool {
    match card.expires_at {
        Some(ts) => {
            let now = chrono::Utc::now().timestamp_millis();
            now > ts
        }
        None => false,
    }
}

/// 根据 state + 触发条件判断某卡片是否应进入 pending 队列。
fn should_show(card: &AnnouncementCard, state: &AnnouncementState) -> bool {
    if is_expired(card) {
        return false;
    }
    if state.never_show.iter().any(|x| x == &card.id) {
        return false;
    }
    if state.dismissed.iter().any(|x| x == &card.id) {
        return false;
    }
    let already_seen = state.seen.iter().any(|x| x == &card.id);
    match &card.trigger.condition {
        TriggerCondition::VersionFirstOpen => !already_seen,
        TriggerCondition::AppNthOpen { n } => state.open_count >= *n && !already_seen,
        // 后端不跟踪功能使用，pending 阶段不主动弹（仅可手动 trigger）。
        TriggerCondition::FeatureUsed { .. } => false,
        TriggerCondition::Manual => false,
        TriggerCondition::Always => {
            if card.trigger.once_per_version {
                !already_seen
            } else {
                true
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Commands
// ─────────────────────────────────────────────────────────────────

/// 拉取本次会话应展示的卡片（有序）。会 +1 open_count 并触发更新检查。
/// 每个应用启动调用一次。
///
/// `token`：设备 token（前端从 localStorage `trae_device_token` 读取后
/// 透传），用于 MCP `client.check_update`。可为空。
#[tauri::command]
pub async fn get_pending_announcements(
    app: AppHandle,
    token: Option<String>,
) -> Result<Vec<AnnouncementCard>, String> {
    // 1. +1 open_count 并落盘。
    {
        let _guard = STATE_FILE_LOCK.lock().await;
        let mut state = load_state_locked(&app).await;
        state.open_count = state.open_count.saturating_add(1);
        if let Err(e) = save_state_locked(&app, &state).await {
            log::warn!("[announcements] persist open_count failed: {}", e);
        }
    }

    // 2. 读最新 state 做过滤（不持锁，避免 MCP 网络调用长时间占锁）。
    let state = load_state(&app).await;

    // 3. 组装候选：内置卡片 + 动态更新卡片。
    let mut candidates = builtin_cards();
    if let Some(update_card) = fetch_update_card(token).await {
        candidates.push(update_card);
    }

    // 4. 过滤 + 排序（priority 降序）。
    let mut out: Vec<AnnouncementCard> = candidates
        .into_iter()
        .filter(|c| should_show(c, &state))
        .collect();
    out.sort_by_key(|b| std::cmp::Reverse(b.priority));

    log::debug!("[announcements] pending cards: {}", out.len());
    Ok(out)
}

/// 标记卡片已读（modal 打开或 action 点击）。
#[tauri::command]
pub async fn mark_announcement_seen(app: AppHandle, request: IdRequest) -> Result<(), String> {
    mutate_state(&app, |s| push_unique(&mut s.seen, &request.id)).await
}

/// 在当前版本周期内忽略某卡片。
#[tauri::command]
pub async fn dismiss_announcement(app: AppHandle, request: IdRequest) -> Result<(), String> {
    mutate_state(&app, |s| {
        push_unique(&mut s.dismissed, &request.id);
        push_unique(&mut s.seen, &request.id);
    })
    .await
}

/// 永久屏蔽某卡片（跨版本）。
#[tauri::command]
pub async fn never_show_announcement(app: AppHandle, request: IdRequest) -> Result<(), String> {
    mutate_state(&app, |s| {
        push_unique(&mut s.never_show, &request.id);
        push_unique(&mut s.seen, &request.id);
    })
    .await
}

/// 按 id 手动触发一张卡片，绕过所有调度过滤（seen/dismissed/version）。
/// 用于开发预览或「查看提示」入口。未注册的 id 返回 null。
#[tauri::command]
pub async fn trigger_announcement(
    _app: AppHandle,
    request: IdRequest,
) -> Result<Option<AnnouncementCard>, String> {
    Ok(builtin_cards().into_iter().find(|c| c.id == request.id))
}

/// 获取所有当前可展示的 tip 卡片（供「提示浏览器」用）。
#[tauri::command]
pub async fn get_announcement_tips(app: AppHandle) -> Result<Vec<AnnouncementCard>, String> {
    let state = load_state(&app).await;
    let out: Vec<AnnouncementCard> = builtin_cards()
        .into_iter()
        .filter(|c| c.card_type == CardType::Tip && !is_expired(c))
        .filter(|c| !state.never_show.iter().any(|x| x == &c.id))
        .collect();
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_compare() {
        assert!(version_gt("1.2.0", "1.1.9"));
        assert!(version_gt("v2.0.0", "1.9.9"));
        assert!(!version_gt("1.0.0", "1.0.0"));
        assert!(!version_gt("1.0.0", "1.0.1"));
        assert!(version_gt("1.0.10", "1.0.2"));
    }

    #[test]
    fn wire_format_snake_case() {
        // 确认序列化出的 tag / 字段是前端期望的 snake_case。
        let c = &builtin_cards()[0];
        let json = serde_json::to_string(c).unwrap();
        assert!(json.contains("\"card_type\":\"feature\""));
        assert!(json.contains("\"type\":\"version_first_open\""));
        assert!(json.contains("\"once_per_version\""));
    }

    #[test]
    fn should_show_respects_never_show() {
        let card = &builtin_cards()[0];
        let mut state = AnnouncementState::default();
        state.never_show.push(card.id.clone());
        assert!(!should_show(card, &state));
    }
}
