// Copyright (c) 2026 tupAI
//
// tupAI P1 §3.4 — CDP step primitives.
//
// Each function takes a `&Page` (from chromiumoxide) and runs a single
// CDP command. They are intentionally side-effect-only and return
// either `()` (for fire-and-forget actions like click) or a typed
// payload (screenshot bytes, extracted text).
//
// We keep the surface small: click / type / hotkey / screenshot /
// wait_for / extract_text. Everything else (drag, scroll, file upload)
// is composed from these by the dispatcher.

use base64::Engine;
use chromiumoxide::cdp::browser_protocol::input::{
    DispatchKeyEventParams, DispatchKeyEventType, DispatchMouseEventParams,
    DispatchMouseEventPointerType, DispatchMouseEventType,
};
use chromiumoxide::page::Page;
use serde::{Deserialize, Serialize};

// CDP "modifiers" bit field (matches the protocol contract documented
// on `DispatchKeyEventParams::modifiers`): Alt=1, Ctrl=2, Meta=4,
// Shift=8.
const MOD_ALT: i64 = 1;
const MOD_CTRL: i64 = 2;
const MOD_META: i64 = 4;
const MOD_SHIFT: i64 = 8;

/// Click a DOM element via CSS selector.
///
/// Uses chromiumoxide's built-in `Page::find_element` + `Element::click`
/// pipeline, which scrolls into view and centers the cursor
/// automatically.
pub async fn click_element(page: &Page, selector: &str) -> Result<(), String> {
    let element = page
        .find_element(selector)
        .await
        .map_err(|e| format!("找不到元素 '{}': {}", selector, e))?;
    // `Element::click` returns the clicked element; we discard it.
    let _ = element
        .click()
        .await
        .map_err(|e| format!("点击失败: {}", e))?;
    Ok(())
}

/// Synthesise a mouse click at absolute viewport coordinates. Used as the
/// visual-fallback when DOM selectors no longer resolve.
pub async fn click_coordinates(page: &Page, x: f64, y: f64) -> Result<(), String> {
    let common = DispatchMouseEventParams {
        r#type: DispatchMouseEventType::MouseMoved,
        x,
        y,
        modifiers: None,
        timestamp: None,
        button: None,
        buttons: None,
        click_count: None,
        force: None,
        tangential_pressure: None,
        tilt_x: None,
        tilt_y: None,
        twist: None,
        delta_x: None,
        delta_y: None,
        pointer_type: Some(DispatchMouseEventPointerType::Mouse),
    };
    page.execute(DispatchMouseEventParams {
        r#type: DispatchMouseEventType::MousePressed,
        ..common.clone()
    })
    .await
    .map_err(|e| format!("mouse_pressed 失败: {}", e))?;
    page.execute(DispatchMouseEventParams {
        r#type: DispatchMouseEventType::MouseReleased,
        ..common
    })
    .await
    .map_err(|e| format!("mouse_released 失败: {}", e))?;
    Ok(())
}

/// Type `text` into the currently focused element. Chromiumoxide does
/// not have a high-level `type_text` helper, so we synthesise one
/// character at a time via key events.
pub async fn type_text(page: &Page, text: &str) -> Result<(), String> {
    for ch in text.chars() {
        page.execute(DispatchKeyEventParams {
            r#type: DispatchKeyEventType::Char,
            text: Some(ch.to_string()),
            ..default_key_params()
        })
        .await
        .map_err(|e| format!("key char 失败: {}", e))?;
    }
    Ok(())
}

fn modifier_bit_for(name: &str) -> Option<i64> {
    match name.to_lowercase().as_str() {
        "alt" | "option" => Some(MOD_ALT),
        "control" | "ctrl" => Some(MOD_CTRL),
        "meta" | "cmd" | "command" => Some(MOD_META),
        "shift" => Some(MOD_SHIFT),
        _ => None,
    }
}

/// Press a hotkey combination such as "Control+A" or "Enter". The
/// string is split on `+`; modifiers (Control / Shift / Alt / Meta)
/// are encoded into the `modifiers` bit field, then the final key is
/// sent as `RawKeyDown` + `KeyUp`.
pub async fn hotkey(page: &Page, combo: &str) -> Result<(), String> {
    let parts: Vec<&str> = combo.split('+').map(str::trim).collect();
    // split('+') 至少返回一个元素("" 当 combo 为空),所以 `parts.is_empty()`
    // 永远为 false —— 旧实现的空检查是死代码。改为检测"split 后只有一个
    // 空串"的真实空输入(combo="" 或 "  " 均归一化到此)。
    if parts.len() == 1 && parts[0].is_empty() {
        return Err("hotkey 不能为空".to_string());
    }

    let mut combined_modifiers: i64 = 0;
    for p in &parts[..parts.len().saturating_sub(1)] {
        if let Some(bit) = modifier_bit_for(p) {
            combined_modifiers |= bit;
        }
    }

    // parts 经上面的空检查保证非空;用 copied().unwrap_or("") 避免裸 unwrap,
    // 同时把 Option<&&str> 拍平为 &str,后续 to_string()/format! 均可直用。
    let final_key = parts.last().copied().unwrap_or("");
    let mods = if combined_modifiers != 0 {
        Some(combined_modifiers)
    } else {
        None
    };

    page.execute(DispatchKeyEventParams {
        r#type: DispatchKeyEventType::RawKeyDown,
        key: Some(final_key.to_string()),
        modifiers: mods,
        ..default_key_params()
    })
    .await
    .map_err(|e| format!("key down '{}': {}", final_key, e))?;
    page.execute(DispatchKeyEventParams {
        r#type: DispatchKeyEventType::KeyUp,
        key: Some(final_key.to_string()),
        modifiers: mods,
        ..default_key_params()
    })
    .await
    .map_err(|e| format!("key up '{}': {}", final_key, e))?;
    Ok(())
}

fn default_key_params() -> DispatchKeyEventParams {
    DispatchKeyEventParams {
        r#type: DispatchKeyEventType::Char,
        modifiers: None,
        timestamp: None,
        text: None,
        unmodified_text: None,
        key_identifier: None,
        code: None,
        key: None,
        windows_virtual_key_code: None,
        native_virtual_key_code: None,
        auto_repeat: None,
        is_keypad: None,
        is_system_key: None,
        location: None,
        commands: None,
    }
}

/// Capture the current page as a PNG. Returns the raw bytes — the
/// caller can base64-encode for transport to the frontend.
pub async fn screenshot(page: &Page) -> Result<Vec<u8>, String> {
    let bytes = page
        .screenshot(
            chromiumoxide::page::ScreenshotParams::builder()
                .format(chromiumoxide::cdp::browser_protocol::page::CaptureScreenshotFormat::Png)
                .build(),
        )
        .await
        .map_err(|e| format!("screenshot 失败: {}", e))?;
    Ok(bytes)
}

/// Wait for `selector` to appear in the DOM, up to `timeout_ms`
/// milliseconds.
pub async fn wait_for_element(
    page: &Page,
    selector: &str,
    timeout_ms: u32,
) -> Result<(), String> {
    let deadline = std::time::Instant::now() + std::time::Duration::from_millis(timeout_ms as u64);
    let mut backoff_ms: u64 = 50;
    loop {
        match page.find_element(selector).await {
            Ok(_) => return Ok(()),
            Err(_) if std::time::Instant::now() < deadline => {
                tokio::time::sleep(std::time::Duration::from_millis(backoff_ms)).await;
                backoff_ms = (backoff_ms * 2).min(1000);
            }
            Err(e) => return Err(format!("等待元素超时 '{}': {}", selector, e)),
        }
    }
}

/// Read the visible text content of `selector`. Returns the trimmed
/// innerText as UTF-8.
pub async fn extract_text(page: &Page, selector: &str) -> Result<String, String> {
    let script = format!(
        r#"
        (() => {{
            const el = document.querySelector({sel:?});
            if (!el) return null;
            return (el.innerText || el.textContent || '').trim();
        }})()
        "#,
        sel = selector,
    );
    let value = page
        .evaluate(script)
        .await
        .map_err(|e| format!("evaluate 失败: {}", e))?;
    // 修复:之前 `value.into_value().ok().flatten()` 把反序列化失败
    // (类型不匹配)也退化成 None → 返回空串。自动化流程会把"元素没有文本"
    // 和"evaluate 返回值无法反序列化"混为一谈,后续基于文本的断言/分支
    // 会以错误数据继续执行。改为把反序列化错误传播出去。
    let result: Option<String> = value
        .into_value()
        .map_err(|e| format!("extract_text 反序列化返回值失败: {}", e))?;
    Ok(result.unwrap_or_default())
}

/// One-shot helper used by the dispatcher to run a single step and
/// return a normalised `ActionResult` for telemetry.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ActionResult {
    pub action: String,
    pub success: bool,
    pub error: Option<String>,
    /// base64-encoded screenshot, populated only for `screenshot` actions.
    pub screenshot_b64: Option<String>,
}

pub async fn run_action(
    page: &Page,
    action: &BrowserAction,
) -> Result<ActionResult, String> {
    match action {
        BrowserAction::Click { selector } => {
            click_element(page, selector).await?;
            Ok(ActionResult {
                action: "click".to_string(),
                success: true,
                error: None,
                screenshot_b64: None,
            })
        }
        BrowserAction::ClickCoordinates { x, y } => {
            click_coordinates(page, *x as f64, *y as f64).await?;
            Ok(ActionResult {
                action: "click_coordinates".to_string(),
                success: true,
                error: None,
                screenshot_b64: None,
            })
        }
        BrowserAction::Type { text } => {
            type_text(page, text).await?;
            Ok(ActionResult {
                action: "type".to_string(),
                success: true,
                error: None,
                screenshot_b64: None,
            })
        }
        BrowserAction::Hotkey { keys } => {
            hotkey(page, keys).await?;
            Ok(ActionResult {
                action: "hotkey".to_string(),
                success: true,
                error: None,
                screenshot_b64: None,
            })
        }
        BrowserAction::Screenshot => {
            let bytes = screenshot(page).await?;
            Ok(ActionResult {
                action: "screenshot".to_string(),
                success: true,
                error: None,
                screenshot_b64: Some(base64::engine::general_purpose::STANDARD.encode(&bytes)),
            })
        }
        BrowserAction::WaitFor { selector, timeout_ms } => {
            wait_for_element(page, selector, *timeout_ms).await?;
            Ok(ActionResult {
                action: "wait_for".to_string(),
                success: true,
                error: None,
                screenshot_b64: None,
            })
        }
        BrowserAction::ExtractText { selector } => {
            let text = extract_text(page, selector).await?;
            Ok(ActionResult {
                action: "extract_text".to_string(),
                success: true,
                error: Some(text),
                screenshot_b64: None,
            })
        }
        BrowserAction::Navigate { url } => {
            page.goto(url.as_str()).await.map_err(|e| format!("导航失败: {}", e))?;
            Ok(ActionResult {
                action: "navigate".to_string(),
                success: true,
                error: None,
                screenshot_b64: None,
            })
        }
        BrowserAction::Evaluate { expression } => {
            let result = page.evaluate(expression.as_str()).await
                .map_err(|e| format!("eval 失败: {}", e))?;
            let value = result.value().unwrap_or_default();
            // serde_json::Value 的 Display 对 Value::String("5") 输出 "\"5\"" (带引号),
            // 下游 JSON.parse('\"5\"') 得到字符串 "5" 而非对象/数字, 使
            // waitForResults 的 parseInt('"5"',10) 返回 NaN → count 恒为 0。
            // 对字符串剥去引号还原原始内容; 其他类型仍用 Display (对象/数字/布尔)。
            let text = match value {
                serde_json::Value::String(s) => s.clone(),
                other => other.to_string(),
            };
            Ok(ActionResult {
                action: "evaluate".to_string(),
                success: true,
                error: Some(text),
                screenshot_b64: None,
            })
        }
        // 注：BrowserAction::GetTargets 已移除（v1.9.6）——它是空壳 stub，
        // 从不返回真实 targets 数据，导致 ensureCdp 永远失败。目标枚举改由
        // 独立命令 `list_browser_targets_cmd`（commands/automation.rs）处理，
        // 它直接调 `Browser::fetch_targets` 并返回 Vec<TargetInfoDto>。
        BrowserAction::TypeIn { selector, text } => {
            // 先点击元素获取焦点，再输入文本
            click_element(page, selector).await?;
            type_text(page, text).await?;
            Ok(ActionResult {
                action: "type_in".to_string(),
                success: true,
                error: None,
                screenshot_b64: None,
            })
        }
    }
}

/// Wire-format action passed from the frontend (and from `dispatcher`)
/// into `run_action`. Keep this enum flat — serde will round-trip it
/// with the same JSON shape the React layer emits.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum BrowserAction {
    Click { selector: String },
    ClickCoordinates { x: i32, y: i32 },
    Type { text: String },
    Hotkey { keys: String },
    Screenshot,
    WaitFor { selector: String, timeout_ms: u32 },
    ExtractText { selector: String },
    /// 导航到指定 URL（技能 cap.cdp.navigate 用）
    Navigate { url: String },
    /// 在页面上下文执行 JS 表达式（技能 cap.cdp.eval 用）
    Evaluate { expression: String },
    /// 在指定元素中输入文本（先 focus 再 type）
    TypeIn { selector: String, text: String },
}
