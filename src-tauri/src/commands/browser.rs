// Copyright (c) 2026 AIMarketing
//
// Tauri commands — 嵌入式浏览器面板（BrowserPanel）后端桥接。
//
// 前端 BrowserPanel 通过 @tauri-apps/api/webview 创建原生 Webview
// 子窗口（label 形如 embedded-browser-panel-view-N），并调用：
//   * browser_webview_eval  — 在指定 webview 中执行 JS（元素审查脚本、
//                             history.back/forward、location.reload、空白页拦截等）
//   * browser_get_url       — 读取指定 webview 当前 URL（地址栏同步）
//
// 两个命令都按 webview label 精确寻址；webview 不存在时返回
// "webview not found: <label>"，前端会按该错误降级处理。

use serde::Deserialize;
use tauri::{AppHandle, Manager, WebviewWindow};

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BrowserEvalRequest {
    pub label: String,
    pub script: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BrowserUrlRequest {
    pub label: String,
}

fn resolve_webview(app: &AppHandle, label: &str) -> Result<WebviewWindow<tauri::Wry>, String> {
    // `get_webview_window` is the ungated lookup (the `unstable`-gated
    // `get_webview` for child webviews is not enabled in this build).
    // `WebviewWindow` exposes `eval()` / `url()` directly, which is all we
    // need for the embedded browser panel.
    app.get_webview_window(label)
        .ok_or_else(|| format!("webview not found: {}", label))
}

// ---- browser_webview_eval -------------------------------------------------

/// 在指定 label 的原生 webview 中执行任意 JS。
/// 前端用它跑元素审查脚本、history.back()/forward()、location.reload()、
/// 以及空白页拦截脚本。执行是 fire-and-forget（页面副作用），不返回求值结果。
#[tauri::command]
pub fn browser_webview_eval(app: AppHandle, request: BrowserEvalRequest) -> Result<(), String> {
    if request.label.trim().is_empty() {
        return Err("browser_webview_eval: label is required".to_string());
    }
    if request.script.trim().is_empty() {
        return Err("browser_webview_eval: script is empty".to_string());
    }
    let webview = resolve_webview(&app, &request.label)?;
    webview
        .eval(request.script)
        .map_err(|e| format!("eval in webview '{}' failed: {}", request.label, e))
}

// ---- browser_get_url ------------------------------------------------------

/// 读取指定 label 的 webview 当前 URL，用于地址栏在页内导航后同步。
/// 直接用 Tauri 的 `Webview::url()`，无需在页面里注入状态。
#[tauri::command]
pub fn browser_get_url(app: AppHandle, request: BrowserUrlRequest) -> Result<String, String> {
    if request.label.trim().is_empty() {
        return Err("browser_get_url: label is required".to_string());
    }
    let webview = resolve_webview(&app, &request.label)?;
    let url = webview
        .url()
        .map_err(|e| format!("get url from webview '{}' failed: {}", request.label, e))?;
    Ok(url.to_string())
}
