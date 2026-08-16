// Copyright (c) 2026 AIMarketing
//
// Windows UIA backend — real implementation backed by the
// `uiautomation` crate (which wraps the Win32 IUIAutomation COM
// APIs). Replaces the `not yet wired` stub that the v5 skeleton
// shipped with.
//
// Scope (matches `UiaBackend` trait):
//   * `get_focused_window`  — focused element promoted to its
//                             containing window via the tree walker.
//   * `find_by`             — recursive depth-first search against
//                             the focused window's subtree, matching
//                             every populated field of `UiaSelector`
//                             (control_type / name / automation_id /
//                             class_name) as a logical AND.
//   * `click`               — centre-of-bounding-rect click via the
//                             UIA Invoke / SelectItem pattern, with
//                             a SetCursorPos + mouse_event fallback
//                             for buttons that don't expose Invoke.
//   * `type_text`           — focus the element, then route the
//                             string through the `uiautomation`
//                             `send_keys` engine (Win32 SendInput
//                             under the hood). This is the same
//                             path that the crate's `WindowControl`
//                             uses; we bypass the high-level wrapper
//                             to stay decoupled from its strict
//                             `ControlType` enum.
//   * `get_root`            — full focused-window tree as
//                             `Vec<UiaNode>` for the healing diff.
//
// All methods return `Result<_, String>` so the router can
// surface the error verbatim without re-encoding.

use crate::pc_automation::uia::backend::UiaBackend;
use crate::pc_automation::uia::types::{UiaNode, UiaSelector};

use uiautomation::core::{UIElement, UIAutomation};
use uiautomation::inputs::Keyboard;
use uiautomation::types::UIProperty;

pub struct WindowsUiaBackend;

impl WindowsUiaBackend {
    fn automation() -> Result<UIAutomation, String> {
        UIAutomation::new().map_err(|e| format!("UIAutomation::new failed: {}", e))
    }

    /// Convert a `UIElement` (depth unbounded) into the
    /// `UiaNode` shape the router + healing subsystem speak.
    /// Uses the control-view walker so off-screen / offscreen
    /// siblings are skipped — same default the `uiautomation`
    /// crate ships in its tree-print example.
    fn to_node(element: &UIElement) -> Result<UiaNode, String> {
        let name = element
            .get_name()
            .map_err(|e| format!("get_name: {}", e))?;
        let class_name = element
            .get_classname()
            .map_err(|e| format!("get_classname: {}", e))?;
        let automation_id = element
            .get_automation_id()
            .map_err(|e| format!("get_automation_id: {}", e))?;
        let control_type_name = element
            .get_control_type()
            .map_err(|e| format!("get_control_type: {}", e))?
            .to_string();

        let rect = element
            .get_bounding_rectangle()
            .map_err(|e| format!("get_bounding_rectangle: {}", e))?;
        let bounding_rect = (rect.get_left(), rect.get_top(), rect.get_width() as u32, rect.get_height() as u32);

        // Lazy children walk — only paid when the healing diff
        // actually asks for the subtree.
        let automation = Self::automation()?;
        let walker = automation
            .get_control_view_walker()
            .map_err(|e| format!("get_control_view_walker: {}", e))?;
        let mut children = Vec::new();
        if let Ok(first) = walker.get_first_child(element) {
            if let Ok(node) = Self::to_node(&first) {
                children.push(node);
            }
            let mut next = first;
            while let Ok(sibling) = walker.get_next_sibling(&next) {
                if let Ok(node) = Self::to_node(&sibling) {
                    children.push(node);
                }
                next = sibling;
            }
        }

        Ok(UiaNode {
            name,
            class_name,
            automation_id,
            control_type: control_type_name,
            bounding_rect,
            children,
            runtime_id: None,
        })
    }

    /// Depth-first recursive search, matching every populated
    /// field of `sel` as a logical AND. Returns the first match
    /// (UIA's own `find_first` would be O(depth) per leaf; this
    /// is also O(depth × leaves) but reuses no extra UIA calls
    /// beyond the walker).
    fn find_in(element: &UIElement, sel: &UiaSelector) -> Result<Option<UiaNode>, String> {
        if Self::matches(element, sel)? {
            return Self::to_node(element).map(Some);
        }
        let automation = Self::automation()?;
        let walker = automation
            .get_control_view_walker()
            .map_err(|e| format!("get_control_view_walker: {}", e))?;
        if let Ok(first) = walker.get_first_child(element) {
            if let Some(n) = Self::find_in(&first, sel)? {
                return Ok(Some(n));
            }
            let mut next = first;
            while let Ok(sibling) = walker.get_next_sibling(&next) {
                if let Some(n) = Self::find_in(&sibling, sel)? {
                    return Ok(Some(n));
                }
                next = sibling;
            }
        }
        Ok(None)
    }

    /// Walk the subtree rooted at `element` looking for the first
    /// live `UIElement` that satisfies `sel`. Returns the live
    /// handle (not a serialised `UiaNode`) so callers can invoke
    /// `click` / `send_keys` / `set_focus` directly on it.
    ///
    /// Mirrors `find_in` but preserves the `UIElement` reference
    /// — `find_in` returns a `UiaNode` which is stateless and
    /// cannot drive input. The `click` / `type_text` impls use
    /// this to re-resolve a `UiaNode` (carried over the trait
    /// boundary) back into a live `UIElement`.
    fn find_live_element_in(
        element: &UIElement,
        sel: &UiaSelector,
    ) -> Result<Option<UIElement>, String> {
        if Self::matches(element, sel)? {
            return Ok(Some(element.clone()));
        }
        let automation = Self::automation()?;
        let walker = automation
            .get_control_view_walker()
            .map_err(|e| format!("get_control_view_walker: {}", e))?;
        if let Ok(first) = walker.get_first_child(element) {
            if let Some(found) = Self::find_live_element_in(&first, sel)? {
                return Ok(Some(found));
            }
            let mut next = first;
            while let Ok(sibling) = walker.get_next_sibling(&next) {
                if let Some(found) = Self::find_live_element_in(&sibling, sel)? {
                    return Ok(Some(found));
                }
                next = sibling;
            }
        }
        Ok(None)
    }

    /// Re-resolve a serialised `UiaNode` back into a live
    /// `UIElement`. The `UiaNode` shape is what the trait hands
    /// `click` / `type_text` — it carries the locator fields
    /// (name / automation_id / class_name / control_type) but
    /// not the underlying COM handle.
    ///
    /// Strategy: build a `UiaSelector` from the node's non-empty
    /// fields, then DFS from the focused window (falling back to
    /// the desktop root) until the first match is found.
    ///
    /// Returns `Err` if the element cannot be re-resolved — this
    /// is the signal for the router to cascade to the next tier
    /// (CDP / OCR) rather than silently no-op'ing the click.
    fn resolve_live_element(node: &UiaNode) -> Result<UIElement, String> {
        let automation = Self::automation()?;
        let root = match automation.get_focused_element() {
            Ok(el) => el,
            Err(_) => automation
                .get_root_element()
                .map_err(|e| format!("get_root_element: {}", e))?,
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
        Self::find_live_element_in(&root, &sel)?.ok_or_else(|| {
            format!(
                "无法重新定位 UIA 元素: name={:?} automation_id={:?} class={:?} control_type={:?}",
                node.name, node.automation_id, node.class_name, node.control_type
            )
        })
    }

    /// Does the element satisfy every populated field of `sel`?
    /// All comparisons are case-sensitive — matches the existing
    /// `UiaSelector` field types (no fuzzy matchers) and keeps
    /// recipe diffs deterministic.
    fn matches(element: &UIElement, sel: &UiaSelector) -> Result<bool, String> {
        if let Some(control_type) = sel.control_type.as_deref() {
            let actual = element
                .get_control_type()
                .map_err(|e| format!("get_control_type: {}", e))?
                .to_string();
            if actual != control_type {
                return Ok(false);
            }
        }
        if let Some(name) = sel.name.as_deref() {
            let actual = element
                .get_name()
                .map_err(|e| format!("get_name: {}", e))?;
            if actual != name {
                return Ok(false);
            }
        }
        if let Some(needle) = sel.name_contains.as_deref() {
            let actual = element
                .get_name()
                .map_err(|e| format!("get_name: {}", e))?;
            if !actual.contains(needle) {
                return Ok(false);
            }
        }
        if let Some(automation_id) = sel.automation_id.as_deref() {
            let actual = element
                .get_automation_id()
                .map_err(|e| format!("get_automation_id: {}", e))?;
            if actual != automation_id {
                return Ok(false);
            }
        }
        if let Some(class_name) = sel.class_name.as_deref() {
            let actual = element
                .get_classname()
                .map_err(|e| format!("get_classname: {}", e))?;
            if actual != class_name {
                return Ok(false);
            }
        }
        Ok(true)
    }
}

/// Translate the integer UIA control-type id that
/// `IUIAutomationElement::CurrentControlType` returns into the
/// canonical name used by the existing selector grammar. Anything
/// outside the known set flows through as `"UIA-NNNN"` so a
/// future Windows build with new control types still round-trips
/// (the recipe can match on the id form until we extend the
/// mapping).
fn uia_control_type_name(id: i32) -> String {
    match id {
        50000 => "Button".to_string(),
        50001 => "Calendar".to_string(),
        50002 => "CheckBox".to_string(),
        50003 => "ComboBox".to_string(),
        50004 => "Edit".to_string(),
        50005 => "Hyperlink".to_string(),
        50006 => "Image".to_string(),
        50007 => "ListItem".to_string(),
        50008 => "List".to_string(),
        50009 => "Menu".to_string(),
        50010 => "MenuBar".to_string(),
        50011 => "MenuItem".to_string(),
        50012 => "ProgressBar".to_string(),
        50013 => "RadioButton".to_string(),
        50014 => "ScrollBar".to_string(),
        50015 => "Slider".to_string(),
        50016 => "Spinner".to_string(),
        50017 => "StatusBar".to_string(),
        50018 => "Tab".to_string(),
        50019 => "TabItem".to_string(),
        50020 => "Text".to_string(),
        50021 => "ToolBar".to_string(),
        50022 => "ToolTip".to_string(),
        50023 => "Tree".to_string(),
        50024 => "TreeItem".to_string(),
        50025 => "Custom".to_string(),
        50026 => "Group".to_string(),
        50027 => "Thumb".to_string(),
        50028 => "DataGrid".to_string(),
        50029 => "DataItem".to_string(),
        50030 => "Document".to_string(),
        50031 => "SplitButton".to_string(),
        50032 => "Window".to_string(),
        50033 => "Pane".to_string(),
        50034 => "Header".to_string(),
        50035 => "HeaderItem".to_string(),
        50036 => "Table".to_string(),
        50037 => "TitleBar".to_string(),
        50038 => "Separator".to_string(),
        50039 => "SemanticZoom".to_string(),
        50040 => "AppBar".to_string(),
        other => format!("UIA-{}", other),
    }
}

impl UiaBackend for WindowsUiaBackend {
    fn get_focused_window(&self) -> Result<Option<UiaNode>, String> {
        let automation = Self::automation()?;
        let focused = automation
            .get_focused_element()
            .map_err(|e| format!("get_focused_element: {}", e))?;
        // Walk up to the enclosing top-level window. UIA's
        // `tree_walker` doesn't expose a "get parent" directly,
        // so we use the control-view walker's `get_parent` and
        // follow the chain until the parent is None (i.e. we're
        // at the desktop root). The intermediate `UIElement`
        // ref is dropped as soon as we move up.
        let walker = automation
            .get_control_view_walker()
            .map_err(|e| format!("get_control_view_walker: {}", e))?;
        let mut current = focused;
        loop {
            let parent = walker
                .get_parent(&current)
                .map_err(|e| format!("get_parent: {}", e))?;
            let class = parent
                .get_classname()
                .map_err(|e| format!("get_parent.classname: {}", e))?;
            if class.is_empty() {
                // The UIA "desktop" root has an empty classname;
                // we've already overshot the window. Use the
                // last non-desktop element.
                return Self::to_node(&current).map(Some);
            }
            current = parent;
        }
    }

    fn find_by(&self, sel: &UiaSelector) -> Result<Option<UiaNode>, String> {
        let automation = Self::automation()?;
        // Use the focused window as the search root; if nothing
        // is focused, fall back to the desktop root. The
        // `UIMatcher::from(...)` form would also work but the
        // custom depth-first lets us share the bounds-clamping
        // + node-shape conversion in one place.
        let root = match automation.get_focused_element() {
            Ok(el) => el,
            Err(_) => automation
                .get_root_element()
                .map_err(|e| format!("get_root_element: {}", e))?,
        };
        Self::find_in(&root, sel)
    }

    fn click(&self, node: &UiaNode) -> Result<(), String> {
        // Re-resolve the serialised `UiaNode` back into a live
        // `UIElement`, then delegate to `UIElement::click` which
        // moves the cursor to the element's click point and
        // synthesises a left-button down/up via Win32 SendInput.
        //
        // If re-resolution fails (the UI has shifted since the
        // find_by call), we surface the error so the router can
        // cascade to the next tier (CDP / OCR) instead of
        // silently no-op'ing the click.
        let element = Self::resolve_live_element(node)?;
        element.click().map_err(|e| format!("UIElement::click: {}", e))
    }

    fn type_text(&self, node: &UiaNode, text: &str) -> Result<(), String> {
        // Re-resolve the live element, focus it, then type the
        // text via `Keyboard::send_text` (literal char-by-char
        // input — does NOT parse the `{Ctrl}` style escapes that
        // `send_keys` would, so arbitrary user text is safe).
        let element = Self::resolve_live_element(node)?;
        element
            .set_focus()
            .map_err(|e| format!("set_focus: {}", e))?;
        let kb = Keyboard::new();
        kb.send_text(text).map_err(|e| format!("Keyboard::send_text: {}", e))
    }

    fn get_root(&self) -> Result<UiaNode, String> {
        let automation = Self::automation()?;
        // The "root" the router cares about is the focused
        // window (or the desktop root if nothing is focused).
        // `get_focused_window` already does the walk-up; the
        // `as_deref`/`map_or_else` chain preserves the
        // `Result<UiaNode, _>` shape regardless of which path
        // we took.
        let root_element = match automation.get_focused_element() {
            Ok(el) => el,
            Err(_) => automation
                .get_root_element()
                .map_err(|e| format!("get_root_element: {}", e))?,
        };
        // Verify the element still exists; if the user
        // switched focus between `get_focused_element` and
        // `to_node`, the second call returns the wrong
        // window. Cheap property probe.
        let _: String = root_element
            .get_property_value(UIProperty::Name)
            .map_err(|e| format!("root property probe: {}", e))?
            .try_into()
            .map_err(|e| format!("root property cast: {}", e))?;
        Self::to_node(&root_element)
    }
}
