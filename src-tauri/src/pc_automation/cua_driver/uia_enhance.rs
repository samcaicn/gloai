// Copyright (c) 2026 AIMarketing
//
// Cua Driver UIA 增强 — 使用 Cua Driver 的 get_accessibility_tree /
// get_window_state 作为现有 terminator UIA backend 的补充。
//
// 当 terminator 的 UIA 查找失败时，可以尝试通过 Cua Driver 重新获取
// 无障碍树。Cua Driver 使用各平台原生的无障碍 API（Windows
// UIAutomation / macOS AXUIElement / Linux AT-SPI），可能在某些场景下
// 比 terminator 返回更完整的元素信息（如 element tokens）。
//
// 使用方式：
//   1. TerminatorUiaBackend.find_by() 未命中 → 尝试 cua_get_tree_fallback
//   2. cua_get_tree_fallback 通过 Cua Driver get_accessibility_tree 获取
//      完整树，然后在树中搜索匹配 selector 的节点
//   3. 找到后返回 UiaNode（含 element_token，可用于后续 click/type）

use serde_json::Value;

use crate::pc_automation::cua_driver::CuaDriverClient;
use crate::pc_automation::uia::types::{UiaNode, UiaSelector};

/// 通过 Cua Driver 获取无障碍树，并在其中搜索匹配 selector 的节点。
///
/// 这是 terminator UIA backend 的补充路径：当 terminator 的 find_by
/// 未命中时调用此函数，可能通过 Cua Driver 的不同实现路径找到元素。
///
/// 返回 `None` 表示 Cua Driver 不可用或未找到匹配节点。
pub async fn cua_find_by_selector(selector: &UiaSelector) -> Option<UiaNode> {
    let cua = CuaDriverClient::shared();
    if !cua.is_available() {
        return None;
    }

    let tree = match cua.get_accessibility_tree().await {
        Ok(v) => v,
        Err(e) => {
            log::debug!(target: "pc_automation", "cua-driver accessibility tree failed: {}", e);
            return None;
        }
    };

    // 在树中递归搜索匹配 selector 的节点
    search_tree(&tree, selector)
}

/// 通过 Cua Driver 获取窗口状态（含截图、元素树、element tokens）。
///
/// 比 `cua_find_by_selector` 更强大：返回整个窗口的无障碍树 +
/// 截图（可选 vision 模式）+ element tokens（可用于后续 click/type）。
///
/// `window_id` / `pid` 可选，用于指定窗口。不指定时获取整个桌面。
pub async fn cua_get_window_state(
    window_id: Option<i64>,
    pid: Option<i64>,
) -> Option<Value> {
    let cua = CuaDriverClient::shared();
    if !cua.is_available() {
        return None;
    }

    cua.get_window_state(window_id, pid).await.ok()
}

/// 在 Cua Driver 返回的无障碍树中递归搜索匹配 selector 的节点。
///
/// Cua Driver 的树结构（JSON）：
///   {
///     "role": "button",
///     "name": "OK",
///     "properties": { "class_name": "...", "automation_id": "..." },
///     "children": [ ... ]
///   }
///
/// 匹配规则与 `UiaSelector` 一致：
///   * control_type: 匹配 role（忽略大小写）
///   * name: 精确匹配
///   * name_contains: 子串匹配
///   * automation_id: 匹配 properties.automation_id
///   * class_name: 匹配 properties.class_name
fn search_tree(node: &Value, selector: &UiaSelector) -> Option<UiaNode> {
    // 检查当前节点是否匹配
    if matches_cua_node(node, selector) {
        return Some(cua_node_to_uia_node(node));
    }

    // 递归搜索子节点
    if let Some(children) = node.get("children").and_then(|c| c.as_array()) {
        for child in children {
            if let Some(found) = search_tree(child, selector) {
                return Some(found);
            }
        }
    }

    None
}

/// 检查 Cua Driver 树节点是否匹配 selector。
fn matches_cua_node(node: &Value, selector: &UiaSelector) -> bool {
    // control_type 匹配
    if let Some(ct) = &selector.control_type {
        let role = node
            .get("role")
            .and_then(|r| r.as_str())
            .unwrap_or("");
        if !role.eq_ignore_ascii_case(ct) {
            return false;
        }
    }

    // name 精确匹配
    if let Some(name) = &selector.name {
        let node_name = node
            .get("name")
            .and_then(|n| n.as_str())
            .unwrap_or("");
        if node_name != name {
            return false;
        }
    }

    // name_contains 子串匹配
    if let Some(contains) = &selector.name_contains {
        let node_name = node
            .get("name")
            .and_then(|n| n.as_str())
            .unwrap_or("");
        if !node_name.contains(contains) {
            return false;
        }
    }

    // automation_id 匹配
    if let Some(aid) = &selector.automation_id {
        let node_aid = node
            .get("properties")
            .and_then(|p| p.get("automation_id"))
            .and_then(|a| a.as_str())
            .unwrap_or("");
        if node_aid != aid {
            return false;
        }
    }

    // class_name 匹配
    if let Some(cn) = &selector.class_name {
        let node_cn = node
            .get("properties")
            .and_then(|p| p.get("class_name"))
            .and_then(|c| c.as_str())
            .unwrap_or("");
        if node_cn != cn {
            return false;
        }
    }

    true
}

/// 将 Cua Driver 树节点转换为 tupai 的 UiaNode。
fn cua_node_to_uia_node(node: &Value) -> UiaNode {
    let name = node
        .get("name")
        .and_then(|n| n.as_str())
        .unwrap_or("")
        .to_string();
    let role = node
        .get("role")
        .and_then(|r| r.as_str())
        .unwrap_or("")
        .to_string();
    let props = node.get("properties").unwrap_or(&Value::Null);
    let class_name = props
        .get("class_name")
        .and_then(|c| c.as_str())
        .unwrap_or("")
        .to_string();
    let automation_id = props
        .get("automation_id")
        .and_then(|a| a.as_str())
        .unwrap_or("")
        .to_string();

    // bounding_rect: 尝试从 properties.bounding_rectangle 提取
    // 格式可能是 { "x": 0, "y": 0, "width": 100, "height": 50 }
    let bounding_rect = props
        .get("bounding_rectangle")
        .map(|br| {
            let x = br.get("x").and_then(|v| v.as_i64()).unwrap_or(0) as i32;
            let y = br.get("y").and_then(|v| v.as_i64()).unwrap_or(0) as i32;
            let w = br.get("width").and_then(|v| v.as_i64()).unwrap_or(0) as u32;
            let h = br.get("height").and_then(|v| v.as_i64()).unwrap_or(0) as u32;
            (x, y, w, h)
        })
        .unwrap_or((0, 0, 0, 0));

    // 递归转换子节点
    let children = node
        .get("children")
        .and_then(|c| c.as_array())
        .map(|arr| {
            arr.iter()
                .map(cua_node_to_uia_node)
                .collect()
        })
        .unwrap_or_default();

    // element_token — Cua Driver 特有，可用于后续 click/type
    let runtime_id = node
        .get("element_token")
        .and_then(|t| t.as_str())
        .and_then(|s| s.parse::<i64>().ok());

    UiaNode {
        name,
        class_name,
        automation_id,
        control_type: role,
        bounding_rect,
        children,
        runtime_id,
    }
}
