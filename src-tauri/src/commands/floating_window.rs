// Copyright (c) 2026 MeeJoy
//
// Implements: 悬浮窗 (cross-window floating windows)
//
// State-of-truth for the floating window system moves to Rust so each
// independent Tauri webview (主窗口 / `floating-window` 独立 webview) 看到
// 的都是同一份状态。`open` / `close` / `move` / `resize` / `dock` 全部
// 通过命令走到这里，更新完 state 后 emit
// `floating_window:state-changed`，两端订阅者拉新快照重渲。
//
// Tauri 窗口的生命周期也跟着 state 走：第一次 `fw_open` 时按 entry id
// 懒建一个 `floating-window` webview，关闭时 destroy。主窗口关掉时
// 这里只 hide() 不 destroy()，所以浮窗能"主窗口关闭后单独存在"。

use std::collections::HashMap;
use std::sync::Mutex;

use serde::{Deserialize, Serialize};
use serde_json;
use tauri::{
    AppHandle, Emitter, LogicalPosition, LogicalSize, Manager, PhysicalPosition, PhysicalSize,
    WebviewUrl, WebviewWindowBuilder, WindowEvent,
};

// 事件名 —— 主窗口 + floating-window webview 都 listen 这个。
pub const STATE_CHANGED_EVENT: &str = "floating_window:state-changed";
// 窗口 label 前缀。`floating-window-quick-notes` 这种形式。
// Tauri label 只允许 [a-zA-Z0-9_-]，所以 id 直接拼上去没问题。
const WINDOW_LABEL_PREFIX: &str = "floating-window-";
/// dock 后的 peek 宽度（像素，逻辑尺寸）。前端在这个宽度内画一个小半圆，
/// 鼠标 hover 上去即可触发 restore —— 见前端 `FloatingWindow` 的 `docked`
/// UI。20px = 半圆半径，前端用 height: 40px + border-radius: 20px 画出半圆。
const PEEK_WIDTH: u32 = 20;

fn window_label_for(id: &str) -> String {
    format!("{}{}", WINDOW_LABEL_PREFIX, id)
}

// === Wire types ===
//
// 字段名刻意走 camelCase，方便前端 useFloatingWindow / FloatingWindow
// 那套组件直接消费，不做转换。

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FloatingPosition {
    pub x: i32,
    pub y: i32,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FloatingSize {
    pub width: u32,
    pub height: u32,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FloatingEntry {
    pub id: String,
    #[serde(default)]
    pub title: String,
    pub width: u32,
    pub height: u32,
    #[serde(default = "default_min_width")]
    pub min_width: u32,
    #[serde(default = "default_min_height")]
    pub min_height: u32,
    pub position: FloatingPosition,
    #[serde(default = "default_anchor")]
    pub anchor: String,
    #[serde(default)]
    pub minimized: bool,
    #[serde(default)]
    pub docked: bool,
    #[serde(default)]
    pub dock_edge: Option<String>,
    #[serde(default = "default_dock_offset")]
    pub dock_offset: f64,
    /// 进入 dock 之前的原始尺寸 —— dock 时窗口缩到 peek 宽 (6px)，
    /// restore 时用这个字段还原。`min_width` 比 peek 大，
    /// 走 `resize()` 会被 clamp 卡住，所以这里直接绕开。
    #[serde(default)]
    pub pre_dock_size: Option<FloatingSize>,
    /// 进入 dock 之前的原始位置。
    #[serde(default)]
    pub pre_dock_position: Option<FloatingPosition>,
    #[serde(default)]
    pub last_session_id: Option<String>,
    #[serde(default)]
    pub payload: Option<serde_json::Value>,
    pub z_index: i32,
    pub opened_at: i64,
}

fn default_min_width() -> u32 {
    280
}
fn default_min_height() -> u32 {
    180
}
fn default_anchor() -> String {
    "right".to_string()
}
fn default_dock_offset() -> f64 {
    0.5
}

#[derive(Clone, Debug, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct OpenWindowInput {
    pub id: String,
    #[serde(default)]
    pub title: Option<String>,
    #[serde(default = "default_open_width")]
    pub width: u32,
    #[serde(default = "default_open_height")]
    pub height: u32,
    #[serde(default = "default_min_width")]
    pub min_width: u32,
    #[serde(default = "default_min_height")]
    pub min_height: u32,
    #[serde(default)]
    pub position: Option<FloatingPosition>,
    #[serde(default)]
    pub anchor: Option<String>,
    #[serde(default)]
    pub payload: Option<serde_json::Value>,
}

fn default_open_width() -> u32 {
    420
}
fn default_open_height() -> u32 {
    320
}

// === Global state ===

#[derive(Default)]
pub struct FloatingWindowState {
    inner: Mutex<HashMap<String, FloatingEntry>>,
}

impl FloatingWindowState {
    pub fn new() -> Self {
        Self::default()
    }

    /// Returns a snapshot of all current floating window entries,
    /// sorted by `opened_at` (oldest first). Read-only — safe to
    /// call from tray callbacks / IPC handlers that just need to
    /// inspect state without mutating it.
    pub fn snapshot(&self) -> Vec<FloatingEntry> {
        let guard = self.inner.lock().unwrap_or_else(|p| p.into_inner());
        let mut list: Vec<FloatingEntry> = guard.values().cloned().collect();
        list.sort_by_key(|w| w.opened_at);
        list
    }

    fn next_z_index(&self) -> i32 {
        let guard = self.inner.lock().unwrap_or_else(|p| p.into_inner());
        let mut max = 60i32;
        for win in guard.values() {
            if win.z_index > max {
                max = win.z_index;
            }
        }
        max + 1
    }

    fn default_position(width: u32, height: u32) -> FloatingPosition {
        // Anchor near the right edge, vertically centered.
        let win_w = 1280i32;
        let win_h = 800i32;
        let x = ((win_w - width as i32 - 96).max(24)).min(win_w - width as i32 - 24);
        let y = ((win_h - height as i32) / 2).max(24);
        FloatingPosition { x, y }
    }

    pub fn open(
        &self,
        app: &AppHandle,
        input: OpenWindowInput,
    ) -> Result<FloatingEntry, String> {
        if input.id.is_empty() {
            return Err("floating_window.open: id is required".to_string());
        }
        let now = chrono::Utc::now().timestamp_millis();
        let mut guard = self.inner.lock().map_err(|e| e.to_string())?;

        // 只允许同时存在 1 个悬浮窗：打开新窗口前关闭所有其他窗口。
        // 收集要清理的 id，从 state 移除，然后释放锁再销毁 Tauri 窗口
        // （避免与 close 方法死锁）。
        let to_close: Vec<String> = guard
            .keys()
            .filter(|k| *k != &input.id)
            .cloned()
            .collect();
        for id in &to_close {
            guard.remove(id);
        }
        drop(guard);
        for id in &to_close {
            let label = window_label_for(id);
            if let Some(win) = app.get_webview_window(&label) {
                let _ = win.destroy();
            }
        }
        let mut guard = self.inner.lock().map_err(|e| e.to_string())?;

        let position = input
            .position
            .clone()
            .or_else(|| guard.get(&input.id).map(|w| w.position.clone()))
            .unwrap_or_else(|| Self::default_position(input.width, input.height));

        let next = FloatingEntry {
            id: input.id.clone(),
            title: input
                .title
                .clone()
                .or_else(|| guard.get(&input.id).map(|w| w.title.clone()))
                .unwrap_or_else(|| input.id.clone()),
            width: input.width,
            height: input.height,
            min_width: input.min_width,
            min_height: input.min_height,
            position,
            anchor: input
                .anchor
                .clone()
                .or_else(|| guard.get(&input.id).map(|w| w.anchor.clone()))
                .unwrap_or_else(default_anchor),
            minimized: false,
            docked: guard.get(&input.id).map(|w| w.docked).unwrap_or(false),
            dock_edge: guard.get(&input.id).and_then(|w| w.dock_edge.clone()),
            dock_offset: guard
                .get(&input.id)
                .map(|w| w.dock_offset)
                .unwrap_or_else(default_dock_offset),
            // 复用 entry 时，把上一次 dock 前的尺寸/位置也带过来，
            // 避免 restore 后窗口变 6px。新 open() 调用会按 input 重设
            // 尺寸/位置，下次 dock 之前 pre_dock 字段会被 dock() 重新覆盖。
            pre_dock_size: guard
                .get(&input.id)
                .and_then(|w| w.pre_dock_size.clone()),
            pre_dock_position: guard
                .get(&input.id)
                .and_then(|w| w.pre_dock_position.clone()),
            last_session_id: guard.get(&input.id).and_then(|w| w.last_session_id.clone()),
            payload: input
                .payload
                .clone()
                .or_else(|| guard.get(&input.id).and_then(|w| w.payload.clone())),
            z_index: Self::next_z_index_static(&guard),
            opened_at: guard.get(&input.id).map(|w| w.opened_at).unwrap_or(now),
        };
        guard.insert(input.id.clone(), next.clone());
        drop(guard);

        // Make sure the underlying Tauri window exists. 创建失败也要
        // 把 state 回滚 —— 不然前端看到 state 里有 entry 但窗口其实
        // 不存在，行为会很怪。
        if let Err(error) = ensure_window(app, &next) {
            let mut guard = self.inner.lock().map_err(|e| e.to_string())?;
            guard.remove(&input.id);
            return Err(error);
        }
        // 修复浮窗不显示的 bug：`ensure_window` 默认 `.visible(false)`,
        // 调用方(open)负责显隐。原先这里只 emit 不 show, 导致 dock
        // 按钮点下去 state 计数涨了, 但 Tauri 窗口本体永远不出现。
        // 不管是新建的还是复用的窗口, 都 show + focus 一次。
        // 防御性: 如果 entry 已经是 docked=true(罕见的"重新唤起同一
        // id"路径), 维持原 docked 状态, 不强行 un-hide Tauri 窗口。
        let label = window_label_for(&next.id);
        if let Some(win) = app.get_webview_window(&label) {
            if !next.docked {
                let _ = win.unminimize();
                let _ = win.show();
                let _ = win.set_focus();
            }
        }
        self.emit(app);
        Ok(next)
    }

    fn next_z_index_static(map: &HashMap<String, FloatingEntry>) -> i32 {
        let mut max = 60i32;
        for win in map.values() {
            if win.z_index > max {
                max = win.z_index;
            }
        }
        max + 1
    }

    pub fn close(&self, app: &AppHandle, id: &str) -> bool {
        let removed = {
            let mut guard = self.inner.lock().unwrap_or_else(|p| p.into_inner());
            guard.remove(id).is_some()
        };
        if removed {
            // 先 hide 再 destroy，避免 webview 渲染表面撕裂导致黑屏。
            let label = window_label_for(id);
            if let Some(win) = app.get_webview_window(&label) {
                let _ = win.hide();
                let _ = win.destroy();
            }
            self.emit(app);
        }
        removed
    }

    pub fn focus(&self, app: &AppHandle, id: &str) -> Option<FloatingEntry> {
        let updated = {
            let mut guard = self.inner.lock().unwrap_or_else(|p| p.into_inner());
            // 先单独计算下一个 z-index，让 guard.values() 的不可变借用
            // 在 get_mut() 的可变借用开始前结束。
            let next_z = {
                let mut max = 60i32;
                for w in guard.values() {
                    if w.z_index > max {
                        max = w.z_index;
                }
            }
                max + 1
            };
            let win = guard.get_mut(id)?;
            win.z_index = next_z;
            win.minimized = false;
            Some(win.clone())
        };
        if let Some(_entry) = updated.as_ref() {
            // dock 现在是 peek 模式（窗口缩到 6px 但仍 visible），
            // 所以 docked 也要 set_focus —— 用户需要能点中 peek 条。
            // 同理 minimized 也由 dock 派生，总是 false（dock 会清掉）。
            let label = window_label_for(id);
            if let Some(win) = app.get_webview_window(&label) {
                let _ = win.unminimize();
                let _ = win.set_focus();
                let _ = win.show();
            }
            self.emit(app);
        }
        updated
    }

    pub fn dock(&self, app: &AppHandle, id: &str) -> Option<FloatingEntry> {
        let updated = {
            let mut guard = self.inner.lock().unwrap_or_else(|p| p.into_inner());
            let win = guard.get_mut(id)?;
            // 只贴左/右边：peek UI 是为左右半圆设计的（PEEK_WIDTH 缩宽 +
            // 垂直半圆），top/bottom 贴边需要完全不同的水平半圆形态。
            // 且原先 top/bottom 分支不会把窗口推到顶/底边，只是原地缩成
            // 20px 竖条 —— 既不贴边、半圆方向也不对。所以这里固定二选一。
            // 用主屏的实际分辨率，不再硬编码 1280。
            let screen_w = app
                .primary_monitor()
                .ok()
                .flatten()
                .map(|m| {
                    let s = m.size();
                    // Tauri 返回的是物理像素，逻辑尺寸需要除 scale；
                    // 这里只用来比"离哪条边近"，误差几像素不影响二选一。
                    let scale = m.scale_factor();
                    (s.width as f64 / scale) as i32
                })
                .unwrap_or(1280);
            let left = win.position.x;
            let right = screen_w - (win.position.x + win.width as i32);
            let edge = if left <= right { "left" } else { "right" };

            // 记录 dock 前的尺寸/位置，仅在还没保存时记录。
            // 已经 docked 的 entry 再 dock 一次不应该把当前的 peek
            // 尺寸 (PEEK_WIDTH) 覆盖掉原始尺寸。
            if win.pre_dock_size.is_none() {
                win.pre_dock_size = Some(FloatingSize {
                    width: win.width,
                    height: win.height,
                });
            }
            if win.pre_dock_position.is_none() {
                win.pre_dock_position = Some(win.position.clone());
            }

            win.docked = true;
            win.dock_edge = Some(edge.to_string());
            win.minimized = false;
            Some(win.clone())
        };
        if updated.is_some() {
            let label = window_label_for(id);
            if let Some(win) = app.get_webview_window(&label) {
                // Peek 模式：缩到 PEEK_WIDTH 宽但不 hide。
                // 高度保留 dock 前的原高度；垂直 y 位置也不动，
                // 让用户能看到"一个小半圆挂在原位"。
                // 水平位置按贴边方向推到底（left=0 / right=screen_w-PEEK_WIDTH）。
                let entry = updated.as_ref().unwrap();
                let screen_logical_w = app
                    .primary_monitor()
                    .ok()
                    .flatten()
                    .map(|m| {
                        let s = m.size();
                        let scale = m.scale_factor();
                        (s.width as f64 / scale) as i32
                    })
                    .unwrap_or(1280);
                let peek_w = PEEK_WIDTH;
                let new_x = match entry.dock_edge.as_deref() {
                    Some("left") => 0,
                    Some("right") => screen_logical_w - peek_w as i32,
                    _ => entry.position.x,
                };
                let new_y = entry.position.y;
                let new_h = entry.height.max(120);

                let scale = win.scale_factor().unwrap_or(1.0);
                let _ = win.set_size(PhysicalSize::new(
                    (peek_w as f64 * scale) as u32,
                    (new_h as f64 * scale) as u32,
                ));
                let _ = win.set_position(PhysicalPosition::new(
                    (new_x as f64 * scale) as i32,
                    (new_y as f64 * scale) as i32,
                ));
                // 不要 hide —— 前端 docked UI 接管可见性表达。
                // 但要确保窗口在前台（dock 之后如果之前在后台，
                // 用户在主窗口里点不到 peek 条）。
                let _ = win.show();
            }
            self.emit(app);
        }
        updated
    }

    pub fn undock(&self, app: &AppHandle, id: &str) -> Option<FloatingEntry> {
        let updated = {
            let mut guard = self.inner.lock().unwrap_or_else(|p| p.into_inner());
            // 先单独计算下一个 z-index，让 guard.values() 的不可变借用
            // 在 get_mut() 的可变借用开始前结束。
            let next_z = {
                let mut max = 60i32;
                for w in guard.values() {
                    if w.z_index > max {
                        max = w.z_index;
                    }
                }
                max + 1
            };
            let win = guard.get_mut(id)?;
            // 还原 dock 前的尺寸/位置（如果有记录），避免 restore
            // 后窗口还是 6px 宽。
            if let Some(size) = win.pre_dock_size.take() {
                win.width = size.width.max(win.min_width);
                win.height = size.height.max(win.min_height);
            }
            if let Some(pos) = win.pre_dock_position.take() {
                win.position = pos;
            }
            win.docked = false;
            win.minimized = false;
            win.z_index = next_z;
            Some(win.clone())
        };
        if updated.is_some() {
            let label = window_label_for(id);
            if let Some(win) = app.get_webview_window(&label) {
                // 同步尺寸/位置到 Tauri 窗口。直接走 set_size / set_position
                // 绕开 `resize()` 的 min_width clamp（peek 阶段已被缩到 6px，
                // entry 里的 size 已经被上面的 take 还原成正常值）。
                if let Some(entry) = updated.as_ref() {
                    let scale = win.scale_factor().unwrap_or(1.0);
                    let _ = win.set_size(PhysicalSize::new(
                        (entry.width as f64 * scale) as u32,
                        (entry.height as f64 * scale) as u32,
                    ));
                    let _ = win.set_position(PhysicalPosition::new(
                        (entry.position.x as f64 * scale) as i32,
                        (entry.position.y as f64 * scale) as i32,
                    ));
                }
                let _ = win.show();
                let _ = win.set_focus();
            }
            self.emit(app);
        }
        updated
    }

    pub fn move_to(
        &self,
        app: &AppHandle,
        id: &str,
        position: FloatingPosition,
    ) -> Option<FloatingEntry> {
        let updated = {
            let mut guard = self.inner.lock().unwrap_or_else(|p| p.into_inner());
            let win = guard.get_mut(id)?;
            win.position = FloatingPosition {
                x: position.x.clamp(-120, 4096),
                y: position.y.clamp(0, 4096),
            };
            Some(win.clone())
        };
        if let Some(entry) = updated.as_ref() {
            // 同步到 Tauri 窗口位置。scale_factor 拿不到时按 1.0
            // 退化，逻辑位置就够用 —— 浮窗的 px 精度不需要亚像素。
            let label = window_label_for(id);
            if let Some(win) = app.get_webview_window(&label) {
                if let Ok(scale) = win.scale_factor() {
                    let _ = win.set_position(PhysicalPosition::new(
                        (entry.position.x as f64 * scale) as i32,
                        (entry.position.y as f64 * scale) as i32,
                    ));
                } else {
                    let _ = win.set_position(LogicalPosition::new(
                        entry.position.x,
                        entry.position.y,
                    ));
                }
            }
            self.emit(app);
        }
        updated
    }

    pub fn resize(&self, app: &AppHandle, id: &str, size: FloatingSize) -> Option<FloatingEntry> {
        let updated = {
            let mut guard = self.inner.lock().unwrap_or_else(|p| p.into_inner());
            let win = guard.get_mut(id)?;
            win.width = size.width.clamp(win.min_width, 1400);
            win.height = size.height.clamp(win.min_height, 1000);
            Some(win.clone())
        };
        if let Some(entry) = updated.as_ref() {
            let label = window_label_for(id);
            if let Some(win) = app.get_webview_window(&label) {
                if let Ok(scale) = win.scale_factor() {
                    let _ = win.set_size(PhysicalSize::new(
                        (entry.width as f64 * scale) as u32,
                        (entry.height as f64 * scale) as u32,
                    ));
                } else {
                    let _ = win.set_size(LogicalSize::new(
                        entry.width,
                        entry.height,
                    ));
                }
            }
            self.emit(app);
        }
        updated
    }

    pub fn set_payload(
        &self,
        app: &AppHandle,
        id: &str,
        payload: Option<serde_json::Value>,
    ) -> Option<FloatingEntry> {
        let updated = {
            let mut guard = self.inner.lock().unwrap_or_else(|p| p.into_inner());
            let win = guard.get_mut(id)?;
            win.payload = payload;
            Some(win.clone())
        };
        if updated.is_some() {
            self.emit(app);
        }
        updated
    }

    pub fn set_dock_offset(&self, app: &AppHandle, id: &str, offset: f64) -> Option<FloatingEntry> {
        let updated = {
            let mut guard = self.inner.lock().unwrap_or_else(|p| p.into_inner());
            let win = guard.get_mut(id)?;
            win.dock_offset = offset.clamp(0.0, 1.0);
            Some(win.clone())
        };
        if updated.is_some() {
            self.emit(app);
        }
        updated
    }

    pub fn set_dock_edge(&self, app: &AppHandle, id: &str, edge: String) -> Option<FloatingEntry> {
        let updated = {
            let mut guard = self.inner.lock().unwrap_or_else(|p| p.into_inner());
            let win = guard.get_mut(id)?;
            if !matches!(edge.as_str(), "left" | "right" | "top" | "bottom") {
                return None;
            }
            win.dock_edge = Some(edge);
            Some(win.clone())
        };
        if updated.is_some() {
            self.emit(app);
        }
        updated
    }

    pub fn set_last_session_id(
        &self,
        app: &AppHandle,
        id: &str,
        session_id: Option<String>,
    ) -> Option<FloatingEntry> {
        let updated = {
            let mut guard = self.inner.lock().unwrap_or_else(|p| p.into_inner());
            let win = guard.get_mut(id)?;
            win.last_session_id = session_id;
            Some(win.clone())
        };
        if updated.is_some() {
            self.emit(app);
        }
        updated
    }

    pub fn minimize(&self, app: &AppHandle, id: &str) -> Option<FloatingEntry> {
        // 最小化 = 贴边（保持视觉一致）
        self.dock(app, id)
    }

    pub fn restore(&self, app: &AppHandle, id: &str) -> Option<FloatingEntry> {
        let updated = {
            let mut guard = self.inner.lock().unwrap_or_else(|p| p.into_inner());
            // 先单独计算下一个 z-index，让 guard.values() 的不可变借用
            // 在 get_mut() 的可变借用开始前结束。
            let next_z = {
                let mut max = 60i32;
                for w in guard.values() {
                    if w.z_index > max {
                        max = w.z_index;
                    }
                }
                max + 1
            };
            let win = guard.get_mut(id)?;
            // 修 minimized/docked 撕裂: minimize 走的就是 dock(),
            // 两个 flag 必然同步, "restore" 语义上就是"完全恢复"，
            // 必须同时清 docked, 不然 state 里会出现
            // minimized=false + docked=true 的非法组合, 配合
            // unminimize/show 还会造成 visible 跟 docked 矛盾。
            win.minimized = false;
            win.docked = false;
            win.z_index = next_z;
            // restore 必须还原 dock 前的尺寸/位置 —— 否则 dock 时窗口被
            // 缩到 PEEK_WIDTH (20px), restore 只清 flag 不还原 size, 窗口
            // 会一直停留在 20px 宽的 peek 状态，三条 restore 路径
            // (peek 条点击 / ChatFloaterButton / 托盘悬浮聊天) 全部失效。
            // 与 undock() 保持一致：take 出 pre_dock 字段并写回 width/height/position。
            if let Some(size) = win.pre_dock_size.take() {
                win.width = size.width.max(win.min_width);
                win.height = size.height.max(win.min_height);
            }
            if let Some(pos) = win.pre_dock_position.take() {
                win.position = pos;
            }
            Some(win.clone())
        };
        if updated.is_some() {
            let label = window_label_for(id);
            if let Some(win) = app.get_webview_window(&label) {
                // 同步尺寸/位置到 Tauri 窗口（与 undock() 一致，绕开
                // resize() 的 min_width clamp —— peek 阶段窗口已被缩到
                // PEEK_WIDTH，这里直接按还原后的 entry 尺寸设置）。
                if let Some(entry) = updated.as_ref() {
                    let scale = win.scale_factor().unwrap_or(1.0);
                    let _ = win.set_size(PhysicalSize::new(
                        (entry.width as f64 * scale) as u32,
                        (entry.height as f64 * scale) as u32,
                    ));
                    let _ = win.set_position(PhysicalPosition::new(
                        (entry.position.x as f64 * scale) as i32,
                        (entry.position.y as f64 * scale) as i32,
                    ));
                }
                let _ = win.unminimize();
                let _ = win.show();
                let _ = win.set_focus();
            }
            self.emit(app);
        }
        updated
    }

    fn emit(&self, app: &AppHandle) {
        let snapshot = self.snapshot();
        // Fire-and-forget: emit 失败只 log 一行，不影响 state 更新。
        if let Err(error) = app.emit(STATE_CHANGED_EVENT, &snapshot) {
            log::warn!(
                "[floating_window] emit {} failed: {}",
                STATE_CHANGED_EVENT,
                error
            );
        }
    }
}

fn ensure_window(app: &AppHandle, entry: &FloatingEntry) -> Result<(), String> {
    let label = window_label_for(&entry.id);

    // 防御性：如果 Tauri 已有一个同 label 的 webview（之前 build 漏下来的
    // 或上次 panic 的残留），先彻底 destroy 掉。否则新 build 会冲突
    // 导致 webview2 panic 或静默失败。
    if let Some(existing) = app.get_webview_window(&label) {
        log::warn!(
            "[floating_window] ensure_window found existing window label='{}', destroying first",
            label
        );
        let _ = existing.destroy();
        // 给 webview2 一点时间释放底层句柄，否则紧接着 build 同一 label 会冲突
        std::thread::sleep(std::time::Duration::from_millis(150));
    }

    let url = format!("index.html#/floating-window?id={}", entry.id);
    let title = if entry.title.is_empty() {
        entry.id.clone()
    } else {
        entry.title.clone()
    };
    let builder = WebviewWindowBuilder::new(app, &label, WebviewUrl::App(url.into()))
        .title(title)
        .decorations(false)
        .transparent(false)
        .background_color(tauri::webview::Color(10, 10, 18, 255))
        .always_on_top(true)
        .skip_taskbar(true)
        .resizable(true)
        .devtools(true)
        .min_inner_size(
            entry.min_width as f64,
            entry.min_height as f64,
        )
        .inner_size(entry.width as f64, entry.height as f64)
        .position(entry.position.x as f64, entry.position.y as f64)
        .focused(false)
        .visible(true);
    // macOS 上要"无视桌面点击穿透"，得开 private API。
    #[cfg(target_os = "macos")]
    let builder = builder.hidden_title(true);
    let window = builder.build().map_err(|e| {
        format!(
            "floating_window.ensure_window build failed for `{}`: {}",
            label, e
        )
    })?;

    // CloseRequested 处理器：覆盖两条路径
    //
    // 路径 A（前端 fwClose）：state.close() 已执行 hide() + destroy()。
    //   destroy() 会触发 CloseRequested，但此时窗口已经 hide 过了，
    //   guard.remove() 返回 None（已被 state.close 移除），不会重复 emit。
    //   hide() 调用是 no-op（窗口已隐藏）。prevent_close 确保 OS 不再
    //   重复走关闭流程（窗口已被 destroy）。
    //
    // 路径 B（OS 发起关闭，如 Alt+F4 / 任务栏右键关闭）：
    //   1) prevent_close() 阻止 OS 默认关闭流程（避免 webview 撕裂黑屏）
    //   2) hide() 让窗口瞬间从屏幕消失
    //   3) 从 state 移除 + emit 通知
    //   4) destroy() 清理 webview（此时已不可见，撕裂不可见）
    let app_for_event = app.clone();
    let id_for_event = entry.id.clone();
    let label_for_event = window_label_for(&entry.id);
    window.on_window_event(move |event| {
        if let WindowEvent::CloseRequested { api, .. } = event {
            // 阻止 OS 默认关闭流程——我们手动控制 hide + destroy，
            // 避免 OS 关闭过程中 webview 渲染表面撕裂暴露黑屏。
            api.prevent_close();

            if let Some(win) = app_for_event.get_webview_window(&label_for_event) {
                let _ = win.hide();
            }
            if let Some(state) = app_for_event.try_state::<FloatingWindowState>() {
                let removed = {
                    let mut guard =
                        state.inner.lock().unwrap_or_else(|p| p.into_inner());
                    guard.remove(&id_for_event).is_some()
                };
                if removed {
                    state.emit(&app_for_event);
                }
            }
            // 最后 destroy 清理 webview。此时窗口已 hide，撕裂不可见。
            if let Some(win) = app_for_event.get_webview_window(&label_for_event) {
                let _ = win.destroy();
            }
        }
    });

    Ok(())
}

// === Tauri commands ===
//
// 全部走 state.method，最后 emit。错误情况只在 state 操作失败时返回，
// 没找到 entry 之类的"软失败"返回 None / false 让前端走 happy path。

#[tauri::command]
pub fn fw_get_state(state: tauri::State<'_, FloatingWindowState>) -> Vec<FloatingEntry> {
    state.snapshot()
}

#[tauri::command]
pub async fn fw_open(
    app: AppHandle,
    state: tauri::State<'_, FloatingWindowState>,
    input: OpenWindowInput,
) -> Result<FloatingEntry, String> {
    state.open(&app, input)
}

#[tauri::command]
pub fn fw_close(
    app: AppHandle,
    state: tauri::State<'_, FloatingWindowState>,
    id: String,
) -> bool {
    state.close(&app, &id)
}

#[tauri::command]
pub fn fw_focus(
    app: AppHandle,
    state: tauri::State<'_, FloatingWindowState>,
    id: String,
) -> Option<FloatingEntry> {
    state.focus(&app, &id)
}

#[tauri::command]
pub fn fw_dock(
    app: AppHandle,
    state: tauri::State<'_, FloatingWindowState>,
    id: String,
) -> Option<FloatingEntry> {
    state.dock(&app, &id)
}

#[tauri::command]
pub fn fw_undock(
    app: AppHandle,
    state: tauri::State<'_, FloatingWindowState>,
    id: String,
) -> Option<FloatingEntry> {
    state.undock(&app, &id)
}

#[tauri::command]
pub fn fw_move(
    app: AppHandle,
    state: tauri::State<'_, FloatingWindowState>,
    id: String,
    position: FloatingPosition,
) -> Option<FloatingEntry> {
    state.move_to(&app, &id, position)
}

#[tauri::command]
pub fn fw_resize(
    app: AppHandle,
    state: tauri::State<'_, FloatingWindowState>,
    id: String,
    size: FloatingSize,
) -> Option<FloatingEntry> {
    state.resize(&app, &id, size)
}

#[tauri::command]
pub fn fw_set_payload(
    app: AppHandle,
    state: tauri::State<'_, FloatingWindowState>,
    id: String,
    payload: Option<serde_json::Value>,
) -> Option<FloatingEntry> {
    state.set_payload(&app, &id, payload)
}

#[tauri::command]
pub fn fw_set_dock_offset(
    app: AppHandle,
    state: tauri::State<'_, FloatingWindowState>,
    id: String,
    offset: f64,
) -> Option<FloatingEntry> {
    state.set_dock_offset(&app, &id, offset)
}

#[tauri::command]
pub fn fw_set_dock_edge(
    app: AppHandle,
    state: tauri::State<'_, FloatingWindowState>,
    id: String,
    edge: String,
) -> Option<FloatingEntry> {
    state.set_dock_edge(&app, &id, edge)
}

#[tauri::command]
pub fn fw_set_last_session_id(
    app: AppHandle,
    state: tauri::State<'_, FloatingWindowState>,
    id: String,
    session_id: Option<String>,
) -> Option<FloatingEntry> {
    state.set_last_session_id(&app, &id, session_id)
}

#[tauri::command]
pub fn fw_minimize(
    app: AppHandle,
    state: tauri::State<'_, FloatingWindowState>,
    id: String,
) -> Option<FloatingEntry> {
    state.minimize(&app, &id)
}

#[tauri::command]
pub fn fw_restore(
    app: AppHandle,
    state: tauri::State<'_, FloatingWindowState>,
    id: String,
) -> Option<FloatingEntry> {
    state.restore(&app, &id)
}

/// 拉起主窗口到前台。录制/重录停止时调用，让用户查看编辑步骤。
#[tauri::command]
pub fn fw_show_main_window(app: AppHandle) -> Result<(), String> {
    let main = app
        .get_webview_window("main")
        .ok_or_else(|| "main window not found".to_string())?;
    let _ = main.unminimize();
    let _ = main.show();
    let _ = main.set_focus();
    Ok(())
}

/// 隐藏主窗口。录制/重录开始时调用，让用户专注于屏幕操作，
/// 只保留悬浮球（recorder 浮窗）可见。
#[tauri::command]
pub fn fw_hide_main_window(app: AppHandle) -> Result<(), String> {
    let main = app
        .get_webview_window("main")
        .ok_or_else(|| "main window not found".to_string())?;
    let _ = main.hide();
    Ok(())
}

/// 录制/执行浮窗关闭时调用：拉起主窗口到前台，并通知主窗口
/// 打开「会话区」入口、加载该软件已录制的流程图节点（后端已去重合并，可编辑）。
/// 浮窗侧在关闭前调用本命令，主窗口订阅 `session:finish-recording`
/// 事件后跳转到流程图场景并刷新节点。
#[tauri::command]
pub fn fw_finish_session(app: AppHandle, app_name: String) -> Result<(), String> {
    // 1) 先把主窗口拉回前台（之前录制时被 hide 了）。
    fw_show_main_window(app.clone())?;
    // 2) 通知主窗口：本次会话结束，请打开流程图入口并加载节点。
    app.emit(
        "session:finish-recording",
        serde_json::json!({ "appName": app_name }),
    )
    .map_err(|e| format!("emit session:finish-recording failed: {}", e))?;
    Ok(())
}

/// 悬浮聊天窗 → 主窗口：拉起主窗口并通知其创建新会话、发送消息。
/// 悬浮聊天窗用户输入文字后调用本命令，主窗口订阅
/// `chat-floater:new-message` 事件后切换到 session 场景、
/// 创建新 FlowChat 会话并发送该消息。
#[tauri::command]
pub fn fw_chat_to_main(app: AppHandle, message: String) -> Result<(), String> {
    // 1) 先把主窗口拉回前台。
    fw_show_main_window(app.clone())?;
    // 2) 通知主窗口：创建新会话并发送消息。
    app.emit(
        "chat-floater:new-message",
        serde_json::json!({ "message": message }),
    )
    .map_err(|e| format!("emit chat-floater:new-message failed: {}", e))?;
    Ok(())
}

/// 悬浮聊天窗 → 主窗口：把整段会话历史带回到主窗口。
/// 用户点击悬浮聊天窗的「最大化」按钮时调用本命令，后端拉起主窗口
/// 并把消息历史（role + content 列表）通过 `chat-floater:transfer-history`
/// 事件发给主窗口；主窗口订阅后创建新 FlowChat 会话并把历史回放为
/// 已完成的 dialog turn，用户可在主窗口继续对话。
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TransferMessage {
    pub role: String,
    pub content: String,
}

#[tauri::command]
pub fn fw_chat_transfer_to_main(
    app: AppHandle,
    messages: Vec<TransferMessage>,
) -> Result<(), String> {
    // 1) 先把主窗口拉回前台。
    fw_show_main_window(app.clone())?;
    // 2) 把消息历史打包发给主窗口。
    app.emit(
        "chat-floater:transfer-history",
        serde_json::json!({ "messages": messages }),
    )
    .map_err(|e| format!("emit chat-floater:transfer-history failed: {}", e))?;
    Ok(())
}

// 主窗口的 close 行为：默认是 destroy 整个进程。我们把 main 的
// close 拦下来，转成 hide —— 用户在主窗口关掉后能继续用浮窗，
// 通过托盘或再次启动 main 来恢复主界面。
#[tauri::command]
pub fn fw_install_main_close_intercept(
    app: AppHandle,
) -> Result<(), String> {
    let main = app
        .get_webview_window("main")
        .ok_or_else(|| "main window not found".to_string())?;
    let app_for_event = app.clone();
    main.on_window_event(move |event| {
        if let WindowEvent::CloseRequested { api, .. } = event {
            // 阻止默认关闭，只 hide。
            api.prevent_close();
            if let Some(main_win) = app_for_event.get_webview_window("main") {
                let _ = main_win.hide();
            }
        }
    });
    Ok(())
}
