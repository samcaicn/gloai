// Copyright (c) 2026 tupAI
//
// TerminatorUiaBackend — adapts terminator's cross-platform
// `Desktop` / `UIElement` to tupai's `UiaBackend` trait.
//
// Key design decisions:
//
// 1. **Synchronous tree traversal** — terminator's `UIElement::children()`
//    is synchronous (the underlying platform API is blocking). We avoid
//    `Locator::first()` (which is async) and instead walk the tree
//    ourselves with `children()`, matching the pattern from the existing
//    `WindowsUiaBackend`. This sidesteps the "block_on inside tokio"
//    problem entirely.
//
// 2. **Selector mapping** — `UiaSelector` has multiple optional fields
//    (control_type, name, name_contains, automation_id, class_name).
//    We build a single `Selector` from the most specific field, then
//    verify the remaining fields manually after the element is found.
//    Priority: automation_id > name > control_type > class_name.
//
// 3. **Element re-resolution** — `click` / `type_text` receive a
//    serialised `UiaNode` (no live handle). We rebuild a `UiaSelector`
//    from the node's non-empty fields and re-find the live element.
//    This mirrors the `WindowsUiaBackend::resolve_live_element` pattern.
//
// 4. **Cross-platform** — works on Windows (UIAutomation COM), macOS
//    (AXUIElement), and Linux (AT-SPI). The old `WindowsUiaBackend`
//    only worked on Windows; on macOS/Linux the router cascaded to
//    OCR + VLM rescue.

use crate::pc_automation::uia::backend::UiaBackend;
use crate::pc_automation::uia::types::{UiaNode, UiaSelector};

use terminator::UIElement;

/// Cross-platform UIA backend backed by terminator's `Desktop`.
pub struct TerminatorUiaBackend;

impl TerminatorUiaBackend {
    /// Convert a terminator `UIElement` into tupai's `UiaNode` shape.
    /// Recursively walks children to populate the `children` field.
    fn to_node(element: &UIElement, depth: usize) -> Result<UiaNode, String> {
        let name = element.name().unwrap_or_default();
        let role = element.role();
        let automation_id = element.id().unwrap_or_default();

        // class_name: terminator doesn't expose this as a first-class
        // field, but on Windows it may be in the properties HashMap.
        // We check for it as a best-effort.
        let attrs = element.attributes();
        let class_name = attrs
            .properties
            .get("ClassName")
            .and_then(|v| v.as_ref())
            .and_then(|v| v.as_str())
            .map(|s| s.to_string())
            .unwrap_or_default();

        let bounding_rect = match element.bounds() {
            Ok((x, y, w, h)) => (x as i32, y as i32, w as u32, h as u32),
            Err(_) => (0, 0, 0, 0),
        };

        // Lazy children walk — limit depth to prevent infinite recursion
        // on cyclic UI trees (rare but possible with framework bugs).
        let children = if depth < 50 {
            match element.children() {
                Ok(child_elements) => {
                    let mut nodes = Vec::with_capacity(child_elements.len());
                    for child in &child_elements {
                        match Self::to_node(child, depth + 1) {
                            Ok(n) => nodes.push(n),
                            Err(_) => continue, // Skip children that error
                        }
                    }
                    nodes
                }
                Err(_) => Vec::new(),
            }
        } else {
            Vec::new()
        };

        Ok(UiaNode {
            name,
            class_name,
            automation_id,
            control_type: role,
            bounding_rect,
            children,
            runtime_id: None,
        })
    }

    /// Check if a live `UIElement` matches every populated field of `sel`.
    /// All comparisons are case-sensitive (matches the existing
    /// `WindowsUiaBackend::matches` behaviour).
    fn matches(element: &UIElement, sel: &UiaSelector) -> bool {
        if let Some(control_type) = sel.control_type.as_deref() {
            if element.role() != control_type {
                return false;
            }
        }
        if let Some(name) = sel.name.as_deref() {
            if element.name().as_deref() != Some(name) {
                return false;
            }
        }
        if let Some(needle) = sel.name_contains.as_deref() {
            match element.name() {
                Some(n) if n.contains(needle) => {}
                _ => return false,
            }
        }
        if let Some(automation_id) = sel.automation_id.as_deref() {
            if element.id().as_deref() != Some(automation_id) {
                return false;
            }
        }
        if let Some(class_name) = sel.class_name.as_deref() {
            let attrs = element.attributes();
            let actual = attrs
                .properties
                .get("ClassName")
                .and_then(|v| v.as_ref())
                .and_then(|v| v.as_str())
                .unwrap_or("");
            if actual != class_name {
                return false;
            }
        }
        true
    }

    /// Depth-first search rooted at `element`, returning the first
    /// child (or self) that matches every populated field of `sel`.
    /// Returns a serialised `UiaNode`.
    fn find_in(element: &UIElement, sel: &UiaSelector, depth: usize) -> Result<Option<UiaNode>, String> {
        if Self::matches(element, sel) {
            return Self::to_node(element, depth).map(Some);
        }
        // Guard against infinite recursion
        if depth >= 50 {
            return Ok(None);
        }
        let children = element
            .children()
            .map_err(|e| format!("children: {}", e))?;
        for child in &children {
            if let Some(n) = Self::find_in(child, sel, depth + 1)? {
                return Ok(Some(n));
            }
        }
        Ok(None)
    }

    /// Same as `find_in` but returns the live `UIElement` handle
    /// (not a serialised `UiaNode`). Used by `click` / `type_text`
    /// to re-resolve a `UiaNode` back into something interactive.
    fn find_live_in(
        element: &UIElement,
        sel: &UiaSelector,
        depth: usize,
    ) -> Result<Option<UIElement>, String> {
        if Self::matches(element, sel) {
            return Ok(Some(element.clone()));
        }
        if depth >= 50 {
            return Ok(None);
        }
        let children = element
            .children()
            .map_err(|e| format!("children: {}", e))?;
        for child in &children {
            if let Some(found) = Self::find_live_in(child, sel, depth + 1)? {
                return Ok(Some(found));
            }
        }
        Ok(None)
    }

    /// Re-resolve a serialised `UiaNode` back into a live `UIElement`.
    /// Builds a `UiaSelector` from the node's non-empty fields and
    /// DFS from the focused window (falling back to the desktop root).
    fn resolve_live_element(node: &UiaNode) -> Result<UIElement, String> {
        let desktop = super::shared_desktop()?;
        let root = match desktop.focused_element() {
            Ok(el) => el,
            Err(_) => desktop.root(),
        };
        let sel = UiaSelector {
            control_type: if node.control_type.is_empty() {
                None
            } else {
                Some(node.control_type.clone())
            },
            name: if node.name.is_empty() {
                None
            } else {
                Some(node.name.clone())
            },
            name_contains: None,
            automation_id: if node.automation_id.is_empty() {
                None
            } else {
                Some(node.automation_id.clone())
            },
            class_name: if node.class_name.is_empty() {
                None
            } else {
                Some(node.class_name.clone())
            },
            path: vec![],
        };
        Self::find_live_in(&root, &sel, 0)?.ok_or_else(|| {
            format!(
                "无法重新定位 UIA 元素: name={:?} automation_id={:?} class={:?} control_type={:?}",
                node.name, node.automation_id, node.class_name, node.control_type
            )
        })
    }

    /// Get the search root: the focused element (preferred) or the
    /// desktop root (fallback). This is the starting point for
    /// `find_by` and `get_root`.
    fn search_root() -> Result<UIElement, String> {
        let desktop = super::shared_desktop()?;
        match desktop.focused_element() {
            Ok(el) => Ok(el),
            Err(_) => Ok(desktop.root()),
        }
    }
}

impl UiaBackend for TerminatorUiaBackend {
    fn get_focused_window(&self) -> Result<Option<UiaNode>, String> {
        let desktop = super::shared_desktop()?;
        let focused = desktop
            .focused_element()
            .map_err(|e| format!("focused_element: {}", e))?;
        // Walk up to the enclosing top-level window. terminator's
        // `UIElement::parent()` returns `Ok(None)` at the desktop
        // root, so we follow the chain until parent is None.
        let mut current = focused;
        loop {
            match current.parent() {
                Ok(Some(parent)) => {
                    let role = parent.role().to_lowercase();
                    // The desktop root's role is typically "desktop"
                    // or "pane" with no name. If we've reached it,
                    // the current element is the top-level window.
                    if role == "desktop" || role == "root" || role.is_empty() {
                        return Self::to_node(&current, 0).map(Some);
                    }
                    // If the parent is a window, the current element
                    // is a window (or inside one). We want to return
                    // the window itself.
                    if role == "window" {
                        return Self::to_node(&parent, 0).map(Some);
                    }
                    current = parent;
                }
                Ok(None) => {
                    // Reached the root — current is the top-level
                    return Self::to_node(&current, 0).map(Some);
                }
                Err(_) => {
                    // Can't walk further up — return what we have
                    return Self::to_node(&current, 0).map(Some);
                }
            }
        }
    }

    fn find_by(&self, sel: &UiaSelector) -> Result<Option<UiaNode>, String> {
        let root = Self::search_root()?;
        Self::find_in(&root, sel, 0)
    }

    fn click(&self, node: &UiaNode) -> Result<(), String> {
        let element = Self::resolve_live_element(node)?;
        element
            .click()
            .map_err(|e| format!("UIElement::click: {}", e))?;
        Ok(())
    }

    fn type_text(&self, node: &UiaNode, text: &str) -> Result<(), String> {
        let element = Self::resolve_live_element(node)?;
        element
            .focus()
            .map_err(|e| format!("UIElement::focus: {}", e))?;
        element
            .type_text(text, false)
            .map_err(|e| format!("UIElement::type_text: {}", e))?;
        Ok(())
    }

    fn get_root(&self) -> Result<UiaNode, String> {
        let root = Self::search_root()?;
        Self::to_node(&root, 0)
    }
}
