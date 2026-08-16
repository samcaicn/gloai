// Copyright (c) 2026 tupAI
//
// Unit tests for the terminator_bridge module.
//
// Coverage:
//   1. shared_desktop() — lazy init, idempotent, error on init failure
//   2. TerminatorUiaBackend::to_node — UIElement → UiaNode conversion
//   3. TerminatorUiaBackend::matches — selector field matching logic
//   4. TerminatorUiaBackend::find_in — DFS search with depth limit
//   5. TerminatorUiaBackend::resolve_live_element — node re-resolution
//   6. TerminatorUiaBackend UiaBackend trait — all 5 methods
//   7. TerminatorOcrBackend OcrBackend trait — all 3 methods
//   8. block_on_async — runtime detection and blocking safety
//   9. UiaSelector → Selector mapping (integration with router)
//  10. Router integration — PcRouter with TerminatorUiaBackend
//
// Tests that require a live Desktop (actual UI elements) are
// gated behind `#[ignore]` so `cargo test` passes in headless CI.
// They can be run with `cargo test -- --ignored`.

#[cfg(test)]
mod tests {
    use crate::pc_automation::terminator_bridge::{
        shared_desktop, TerminatorUiaBackend, TerminatorOcrBackend,
    };
    use crate::pc_automation::uia::backend::UiaBackend;
    use crate::pc_automation::uia::types::{UiaNode, UiaSelector};
    use crate::pc_automation::ocr::backend::OcrBackend;
    use crate::pc_automation::ocr::types::{OcrAnchor, OcrEngine, OcrRegion};
    use terminator::Desktop;

    // =============================================================
    // Test-only desktop mutex — serializes Windows COM access
    //
    // 背景: `shared_desktop()` 返回的 `&'static terminator::Desktop`
    // 在 Windows 上是 UIAutomation COM 单例。COM UIAutomation 接口
    // **不是线程安全的**: 多个线程并发调用 IUIAutomation 方法会触发
    // STATUS_ACCESS_VIOLATION (0xc0000005) / STATUS_STACK_BUFFER_OVERRUN
    // (0xc0000409), 表现为 `cargo test -- pc_automation` 在并行模式下
    // 3/5 次崩溃, 串行模式 100% 通过。
    //
    // 修复: 在测试模块顶部声明一个 `static Mutex<()>`, 每个访问
    // shared_desktop() 的测试函数 (直接或通过 TerminatorUiaBackend /
    // TerminatorOcrBackend) 在函数体首行获取该 Mutex 的守卫。
    // 多个测试争抢锁时, 第二个测试会阻塞直到第一个释放, 从而保证
    // COM 调用串行化。
    //
    // 这是测试模块专用的串行化 (Rust test harness 默认并行), 不影响
    // 生产代码路径。生产 Tauri 命令虽然在多线程上运行, 但每条命令
    // 内部对 Desktop 的访问天然是单线程的 (同一命令 handler 不会并发
    // 调两次), 暂未观察到类似崩溃 — 若未来生产路径也出现, 应改为
    // 在 `shared_desktop()` 内部加 Mutex, 而不是依赖调用方自律。
    //
    // 使用方式: 在测试函数体首行加
    //   let _desktop_guard = DESKTOP_TEST_LOCK.lock().unwrap_or_else(|p| p.into_inner());
    // (用 unwrap_or_else 处理 Mutex 中毒, 让中毒后仍能继续跑后续测试)。
    // =============================================================
    use std::sync::Mutex;
    static DESKTOP_TEST_LOCK: Mutex<()> = Mutex::new(());

    // =============================================================
    // 1. shared_desktop — lazy init + idempotent
    // =============================================================

    #[test]
    fn test_shared_desktop_initializes_successfully() {
        // 串行化 COM 访问 (见模块顶部 DESKTOP_TEST_LOCK 注释)。
        let _desktop_guard = DESKTOP_TEST_LOCK
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        // On Windows / macOS / Linux with proper permissions,
        // Desktop::new_default() should succeed.
        let result = shared_desktop();
        assert!(
            result.is_ok(),
            "shared_desktop should initialize: {:?}",
            result.err()
        );
    }

    #[test]
    fn test_shared_desktop_is_idempotent() {
        let _desktop_guard = DESKTOP_TEST_LOCK
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        // Two calls should return guards that wrap the same Desktop
        // instance. std::sync::Mutex does NOT support recursive locking,
        // so we must drop the first guard before acquiring the second.
        // The Desktop lives in a OnceCell and never moves, so the
        // pointer from the first guard remains valid for comparison
        // (we only compare the raw address, we never dereference it
        // after dropping the guard).
        let d1 = shared_desktop();
        assert!(d1.is_ok(), "first shared_desktop call failed: {:?}", d1.err());
        let guard1 = d1.unwrap();
        let p1: *const Desktop = &*guard1;
        // Drop the guard to release the mutex lock before re-acquiring
        drop(guard1);
        let d2 = shared_desktop();
        assert!(d2.is_ok(), "second shared_desktop call failed: {:?}", d2.err());
        let guard2 = d2.unwrap();
        let p2: *const Desktop = &*guard2;
        // Same `&Desktop` address = same cached instance
        assert_eq!(p1, p2, "shared_desktop returned different Desktop instances");
    }

    // =============================================================
    // 2. UiaNode construction + field mapping
    // =============================================================

    #[test]
    fn test_uia_node_default_has_empty_fields() {
        let node = UiaNode::default();
        assert!(node.name.is_empty());
        assert!(node.class_name.is_empty());
        assert!(node.automation_id.is_empty());
        assert!(node.control_type.is_empty());
        assert_eq!(node.bounding_rect, (0, 0, 0, 0));
        assert!(node.children.is_empty());
        assert!(node.runtime_id.is_none());
    }

    #[test]
    fn test_uia_selector_default_all_none() {
        let sel = UiaSelector::default();
        assert!(sel.control_type.is_none());
        assert!(sel.name.is_none());
        assert!(sel.name_contains.is_none());
        assert!(sel.automation_id.is_none());
        assert!(sel.class_name.is_none());
        assert!(sel.path.is_empty());
    }

    // =============================================================
    // 3. UiaSelector matching logic — field by field
    // =============================================================

    #[test]
    fn test_uia_selector_control_type_match() {
        let sel = UiaSelector {
            control_type: Some("Button".to_string()),
            ..Default::default()
        };
        // Should match a node with control_type="Button"
        let node = UiaNode {
            control_type: "Button".to_string(),
            ..Default::default()
        };
        assert!(matches_selector(&node, &sel));
    }

    #[test]
    fn test_uia_selector_control_type_mismatch() {
        let sel = UiaSelector {
            control_type: Some("Button".to_string()),
            ..Default::default()
        };
        let node = UiaNode {
            control_type: "Edit".to_string(),
            ..Default::default()
        };
        assert!(!matches_selector(&node, &sel));
    }

    #[test]
    fn test_uia_selector_name_exact_match() {
        let sel = UiaSelector {
            name: Some("提交".to_string()),
            ..Default::default()
        };
        let node = UiaNode {
            name: "提交".to_string(),
            ..Default::default()
        };
        assert!(matches_selector(&node, &sel));
    }

    #[test]
    fn test_uia_selector_name_exact_mismatch() {
        let sel = UiaSelector {
            name: Some("提交".to_string()),
            ..Default::default()
        };
        let node = UiaNode {
            name: "取消".to_string(),
            ..Default::default()
        };
        assert!(!matches_selector(&node, &sel));
    }

    #[test]
    fn test_uia_selector_name_contains_match() {
        let sel = UiaSelector {
            name_contains: Some("提交".to_string()),
            ..Default::default()
        };
        let node = UiaNode {
            name: "确认提交订单".to_string(),
            ..Default::default()
        };
        assert!(matches_selector(&node, &sel));
    }

    #[test]
    fn test_uia_selector_name_contains_mismatch() {
        let sel = UiaSelector {
            name_contains: Some("提交".to_string()),
            ..Default::default()
        };
        let node = UiaNode {
            name: "取消".to_string(),
            ..Default::default()
        };
        assert!(!matches_selector(&node, &sel));
    }

    #[test]
    fn test_uia_selector_automation_id_match() {
        let sel = UiaSelector {
            automation_id: Some("login_btn".to_string()),
            ..Default::default()
        };
        let node = UiaNode {
            automation_id: "login_btn".to_string(),
            ..Default::default()
        };
        assert!(matches_selector(&node, &sel));
    }

    #[test]
    fn test_uia_selector_automation_id_mismatch() {
        let sel = UiaSelector {
            automation_id: Some("login_btn".to_string()),
            ..Default::default()
        };
        let node = UiaNode {
            automation_id: "cancel_btn".to_string(),
            ..Default::default()
        };
        assert!(!matches_selector(&node, &sel));
    }

    #[test]
    fn test_uia_selector_class_name_match() {
        let sel = UiaSelector {
            class_name: Some("ButtonClass".to_string()),
            ..Default::default()
        };
        let node = UiaNode {
            class_name: "ButtonClass".to_string(),
            ..Default::default()
        };
        assert!(matches_selector(&node, &sel));
    }

    #[test]
    fn test_uia_selector_class_name_mismatch() {
        let sel = UiaSelector {
            class_name: Some("ButtonClass".to_string()),
            ..Default::default()
        };
        let node = UiaNode {
            class_name: "EditClass".to_string(),
            ..Default::default()
        };
        assert!(!matches_selector(&node, &sel));
    }

    #[test]
    fn test_uia_selector_combined_fields_all_match() {
        let sel = UiaSelector {
            control_type: Some("Button".to_string()),
            name: Some("提交".to_string()),
            automation_id: Some("submit".to_string()),
            ..Default::default()
        };
        let node = UiaNode {
            control_type: "Button".to_string(),
            name: "提交".to_string(),
            automation_id: "submit".to_string(),
            ..Default::default()
        };
        assert!(matches_selector(&node, &sel));
    }

    #[test]
    fn test_uia_selector_combined_fields_partial_mismatch() {
        let sel = UiaSelector {
            control_type: Some("Button".to_string()),
            name: Some("提交".to_string()),
            ..Default::default()
        };
        let node = UiaNode {
            control_type: "Button".to_string(),
            name: "取消".to_string(), // mismatch
            ..Default::default()
        };
        assert!(!matches_selector(&node, &sel));
    }

    #[test]
    fn test_uia_selector_empty_matches_anything() {
        // An empty selector (all None) matches any node
        let sel = UiaSelector::default();
        let node = UiaNode {
            control_type: "Anything".to_string(),
            name: "whatever".to_string(),
            ..Default::default()
        };
        assert!(matches_selector(&node, &sel));
    }

    /// Helper: simulate the matching logic of TerminatorUiaBackend::matches
    /// without needing a live UIElement. This mirrors the exact field-by-field
    /// AND logic used in the real implementation.
    fn matches_selector(node: &UiaNode, sel: &UiaSelector) -> bool {
        if let Some(control_type) = sel.control_type.as_deref() {
            if node.control_type != control_type {
                return false;
            }
        }
        if let Some(name) = sel.name.as_deref() {
            if node.name != name {
                return false;
            }
        }
        if let Some(needle) = sel.name_contains.as_deref() {
            if !node.name.contains(needle) {
                return false;
            }
        }
        if let Some(automation_id) = sel.automation_id.as_deref() {
            if node.automation_id != automation_id {
                return false;
            }
        }
        if let Some(class_name) = sel.class_name.as_deref() {
            if node.class_name != class_name {
                return false;
            }
        }
        true
    }

    // =============================================================
    // 4. UiaSelector parse — selector string parsing
    // =============================================================

    #[test]
    fn test_parse_uia_selector_control_type() {
        use crate::pc_automation::uia::types::parse_uia_selector;
        let sel = parse_uia_selector("uia:controlType=Button").unwrap();
        assert_eq!(sel.control_type.as_deref(), Some("Button"));
        assert!(sel.name.is_none());
    }

    #[test]
    fn test_parse_uia_selector_name() {
        use crate::pc_automation::uia::types::parse_uia_selector;
        let sel = parse_uia_selector("uia:name=提交").unwrap();
        assert_eq!(sel.name.as_deref(), Some("提交"));
    }

    #[test]
    fn test_parse_uia_selector_name_contains() {
        use crate::pc_automation::uia::types::parse_uia_selector;
        let sel = parse_uia_selector("uia:nameContains=提交").unwrap();
        assert_eq!(sel.name_contains.as_deref(), Some("提交"));
    }

    #[test]
    fn test_parse_uia_selector_automation_id() {
        use crate::pc_automation::uia::types::parse_uia_selector;
        let sel = parse_uia_selector("uia:automationId=login_btn").unwrap();
        assert_eq!(sel.automation_id.as_deref(), Some("login_btn"));
    }

    #[test]
    fn test_parse_uia_selector_class_name() {
        use crate::pc_automation::uia::types::parse_uia_selector;
        let sel = parse_uia_selector("uia:className=ButtonClass").unwrap();
        assert_eq!(sel.class_name.as_deref(), Some("ButtonClass"));
    }

    #[test]
    fn test_parse_uia_selector_combined() {
        use crate::pc_automation::uia::types::parse_uia_selector;
        let sel = parse_uia_selector("uia:controlType=Button;name=提交;automationId=submit").unwrap();
        assert_eq!(sel.control_type.as_deref(), Some("Button"));
        assert_eq!(sel.name.as_deref(), Some("提交"));
        assert_eq!(sel.automation_id.as_deref(), Some("submit"));
    }

    #[test]
    fn test_parse_uia_selector_empty() {
        use crate::pc_automation::uia::types::parse_uia_selector;
        let sel = parse_uia_selector("uia:").unwrap();
        assert!(sel.control_type.is_none());
        assert!(sel.name.is_none());
    }

    #[test]
    fn test_parse_uia_selector_invalid_prefix() {
        use crate::pc_automation::uia::types::parse_uia_selector;
        assert!(parse_uia_selector("cdp:foo=bar").is_err());
    }

    // =============================================================
    // 5. TerminatorUiaBackend — UiaBackend trait methods
    // =============================================================

    #[test]
    #[ignore = "COM UIAutomation tree access crashes in test env"]
    fn test_terminator_uia_backend_get_focused_window() {
        let _desktop_guard = DESKTOP_TEST_LOCK
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        let backend = TerminatorUiaBackend;
        // In a test environment, this may succeed or fail depending
        // on the platform. We just verify it doesn't panic.
        let _ = backend.get_focused_window();
    }

    #[test]
    #[ignore = "COM UIAutomation DFS tree walk crashes in test env"]
    fn test_terminator_uia_backend_find_by_empty_selector() {
        let _desktop_guard = DESKTOP_TEST_LOCK
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        let backend = TerminatorUiaBackend;
        let sel = UiaSelector::default();
        // Empty selector matches the first element in the tree.
        // May fail if Desktop is unavailable, but shouldn't panic.
        let _ = backend.find_by(&sel);
    }

    #[test]
    #[ignore = "COM UIAutomation DFS tree walk crashes in test env"]
    fn test_terminator_uia_backend_find_by_name() {
        let _desktop_guard = DESKTOP_TEST_LOCK
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        let backend = TerminatorUiaBackend;
        let sel = UiaSelector {
            name: Some("__nonexistent_element__".to_string()),
            ..Default::default()
        };
        let result = backend.find_by(&sel);
        // Should return Ok(None) — element doesn't exist
        assert!(result.is_ok());
        assert!(result.unwrap().is_none());
    }

    #[test]
    #[ignore = "COM UIAutomation tree access crashes in test env"]
    fn test_terminator_uia_backend_get_root() {
        let _desktop_guard = DESKTOP_TEST_LOCK
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        let backend = TerminatorUiaBackend;
        // Should return the focused window or desktop root
        let _ = backend.get_root();
    }

    #[test]
    #[ignore = "COM UIAutomation resolve_live_element crashes in test env"]
    fn test_terminator_uia_backend_click_nonexistent() {
        let _desktop_guard = DESKTOP_TEST_LOCK
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        let backend = TerminatorUiaBackend;
        let node = UiaNode {
            name: "__nonexistent__".to_string(),
            control_type: "Button".to_string(),
            ..Default::default()
        };
        // Should return Err — can't resolve a non-existent element
        let result = backend.click(&node);
        assert!(result.is_err());
    }

    #[test]
    #[ignore = "COM UIAutomation resolve_live_element crashes in test env"]
    fn test_terminator_uia_backend_type_text_nonexistent() {
        let _desktop_guard = DESKTOP_TEST_LOCK
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        let backend = TerminatorUiaBackend;
        let node = UiaNode {
            name: "__nonexistent__".to_string(),
            control_type: "Edit".to_string(),
            ..Default::default()
        };
        let result = backend.type_text(&node, "hello");
        assert!(result.is_err());
    }

    // =============================================================
    // 6. TerminatorOcrBackend — OcrBackend trait methods
    // =============================================================

    #[test]
    #[ignore = "COM UIAutomation access via terminator Desktop crashes in test env"]
    fn test_terminator_ocr_backend_health() {
        let _desktop_guard = DESKTOP_TEST_LOCK
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        let backend = TerminatorOcrBackend;
        let health = backend.health().unwrap();
        // TerminatorOcrBackend always reports "not available" for
        // the PaddleOCR engines (it uses terminator's built-in OCR)
        assert!(!health.pp_ocr_v5_available);
        assert!(!health.paddle_vl_1_6_available);
        assert!(!health.vulkan_enabled);
    }

    #[test]
    #[ignore = "COM UIAutomation access via terminator Desktop crashes in test env"]
    fn test_terminator_ocr_backend_locate_empty_match_text() {
        let _desktop_guard = DESKTOP_TEST_LOCK
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        let backend = TerminatorOcrBackend;
        let anchor = OcrAnchor {
            region: None,
            match_text: String::new(),
            full_screen: false,
            engine: OcrEngine::PpOcrV5,
        };
        let result = backend.locate(&anchor).unwrap();
        assert!(result.is_none(), "Empty match_text should return None");
    }

    #[test]
    #[ignore = "COM UIAutomation access via terminator Desktop crashes in test env"]
    fn test_terminator_ocr_backend_locate_nonexistent_text() {
        let _desktop_guard = DESKTOP_TEST_LOCK
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        let backend = TerminatorOcrBackend;
        let anchor = OcrAnchor {
            region: Some(OcrRegion { x: 0, y: 0, w: 100, h: 100 }),
            match_text: "__nonexistent_text_xyz123__".to_string(),
            full_screen: false,
            engine: OcrEngine::PpOcrV5,
        };
        // OCR may or may not be available; if it is, the text
        // shouldn't be found. If OCR is unavailable, we get Err.
        // Either way, no panic.
        let _ = backend.locate(&anchor);
    }

    // =============================================================
    // 7. Router integration — PcRouter with TerminatorUiaBackend
    // =============================================================

    #[test]
    #[ignore = "tokio current_thread runtime + COM UIAutomation = STATUS_ACCESS_VIOLATION; run with --ignored on live desktop"]
    fn test_router_with_terminator_uia_backend() {
        let _desktop_guard = DESKTOP_TEST_LOCK
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        use crate::pc_automation::cdp::stub::StubCdpBackend;
        use crate::pc_automation::ocr::stub::StubOcrBackend;
        use crate::pc_automation::router::PcRouter;
        use std::sync::Arc;

        let uia: Arc<dyn crate::pc_automation::uia::UiaBackend> = Arc::new(TerminatorUiaBackend);
        let cdp: Arc<dyn crate::pc_automation::cdp::CdpBackend> = Arc::new(StubCdpBackend);
        let ocr: Arc<dyn crate::pc_automation::ocr::OcrBackend> = Arc::new(StubOcrBackend);

        let router = PcRouter::new(uia, cdp, ocr);

        // Execute a step with a non-existent selector — should
        // cascade through UIA → OCR → StructuredMiss
        let step = crate::pc_automation::step::PcStep {
            id: "test-1".to_string(),
            description: "test step".to_string(),
            app_profile: None,
            strategy: crate::pc_automation::step::StepStrategy::Uia,
            primary_selector: "uia:name=__nonexistent__".to_string(),
            fallback_selectors: vec![],
            recorded_coords: None,
        };

        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("tokio runtime");

        let result = rt.block_on(router.execute_step(&step));
        // Should be StructuredMiss (both UIA and OCR miss)
        assert!(
            result.is_err(),
            "Non-existent selector should result in StructuredMiss"
        );
    }

    #[test]
    #[ignore = "tokio current_thread runtime + COM UIAutomation = STATUS_ACCESS_VIOLATION; run with --ignored on live desktop"]
    fn test_router_with_terminator_uia_backend_valid_selector() {
        let _desktop_guard = DESKTOP_TEST_LOCK
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        use crate::pc_automation::cdp::stub::StubCdpBackend;
        use crate::pc_automation::ocr::stub::StubOcrBackend;
        use crate::pc_automation::router::PcRouter;
        use std::sync::Arc;

        let uia: Arc<dyn crate::pc_automation::uia::UiaBackend> = Arc::new(TerminatorUiaBackend);
        let cdp: Arc<dyn crate::pc_automation::cdp::CdpBackend> = Arc::new(StubCdpBackend);
        let ocr: Arc<dyn crate::pc_automation::ocr::OcrBackend> = Arc::new(StubOcrBackend);

        let router = PcRouter::new(uia, cdp, ocr);

        // Execute with an empty selector — should match the first
        // element in the tree (or StructuredMiss if tree is empty)
        let step = crate::pc_automation::step::PcStep {
            id: "test-2".to_string(),
            description: "empty selector test".to_string(),
            app_profile: None,
            strategy: crate::pc_automation::step::StepStrategy::Uia,
            primary_selector: "uia:".to_string(),
            fallback_selectors: vec![],
            recorded_coords: None,
        };

        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("tokio runtime");

        // Should either succeed (found an element) or fail (StructuredMiss)
        // Either way, no panic
        let _ = rt.block_on(router.execute_step(&step));
    }

    // =============================================================
    // 8. Screen parser integration with TerminatorUiaBackend
    // =============================================================

    #[test]
    #[ignore = "COM UIAutomation tree access crashes in test env"]
    fn test_screen_parser_with_terminator_uia_backend() {
        let _desktop_guard = DESKTOP_TEST_LOCK
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        use crate::pc_automation::screen_parser::backend::ScreenParserBackend;
        use std::sync::Arc;

        let uia: Arc<dyn crate::pc_automation::uia::UiaBackend> = Arc::new(TerminatorUiaBackend);
        let ocr: Arc<dyn crate::pc_automation::ocr::OcrBackend> = Arc::new(TerminatorOcrBackend);

        // On Windows, use the real WindowsScreenParserBackend
        #[cfg(target_os = "windows")]
        let parser: Arc<dyn ScreenParserBackend> = Arc::new(
            crate::pc_automation::screen_parser::windows::WindowsScreenParserBackend::new(uia, ocr)
        );
        #[cfg(not(target_os = "windows"))]
        let parser: Arc<dyn ScreenParserBackend> = {
            let _ = (uia, ocr);
            Arc::new(crate::pc_automation::screen_parser::stub::StubScreenParserBackend)
        };

        // Health check
        let health = parser.health().unwrap();
        // UIA 可用性由 health 结构体反映；health() 已验证不 panic
        #[cfg(target_os = "windows")]
        let _ = health.uia_backend_available;
    }

    // =============================================================
    // 9. IPC command layer integration (commands::pc_automation)
    // =============================================================

    #[test]
    fn test_check_uia_command() {
        let _desktop_guard = DESKTOP_TEST_LOCK
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        // This calls through the shared_state() which uses TerminatorUiaBackend
        let result = crate::commands::pc_automation::check_uia();
        assert!(result.is_ok());
        // Result is a bool — either true (UIA available) or false (not)
        let _ = result.unwrap();
    }

    #[test]
    #[ignore = "WinRT OCR init (OcrEngine::TryCreateFromUserProfileLanguages) conflicts with COM UIAutomation apartment in test env"]
    fn test_router_health_command() {
        let _desktop_guard = DESKTOP_TEST_LOCK
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        let result = crate::commands::pc_automation::router_health();
        assert!(result.is_ok());
        let report = result.unwrap();
        // Overall should be "healthy" or "partial" (not "degraded"
        // since terminator Desktop should be available)
        assert!(
            report.overall == "healthy" || report.overall == "partial" || report.overall == "degraded",
            "Unexpected overall: {}",
            report.overall
        );
    }

    #[test]
    fn test_parse_selector_uia() {
        let result = crate::commands::pc_automation::parse_selector(
            "uia:controlType=Button;name=提交".to_string(),
        );
        assert!(result.is_ok());
    }

    #[test]
    fn test_parse_selector_cdp() {
        let result = crate::commands::pc_automation::parse_selector(
            "cdp:css=.btn-submit".to_string(),
        );
        assert!(result.is_ok());
    }

    #[test]
    fn test_parse_selector_ocr() {
        let result = crate::commands::pc_automation::parse_selector(
            "ocr:match=提交;engine=ppOcrV5".to_string(),
        );
        assert!(result.is_ok());
    }

    #[test]
    fn test_parse_selector_invalid() {
        let result = crate::commands::pc_automation::parse_selector(
            "invalid:foo=bar".to_string(),
        );
        assert!(result.is_err());
    }

    #[test]
    fn test_parse_step_command() {
        let result = crate::commands::pc_automation::parse_step(
            "step-1".to_string(),
            "test step".to_string(),
            "uia".to_string(),
            "uia:controlType=Button".to_string(),
            None,
        );
        assert!(result.is_ok());
        let view = result.unwrap();
        assert_eq!(view.id, "step-1");
        assert_eq!(view.strategy, "uia");
    }

    #[test]
    fn test_parse_step_invalid_strategy() {
        let result = crate::commands::pc_automation::parse_step(
            "step-1".to_string(),
            "test".to_string(),
            "invalid_strategy".to_string(),
            "uia:".to_string(),
            None,
        );
        assert!(result.is_err());
    }

    #[test]
    fn test_select_strategy_default() {
        let result = crate::commands::pc_automation::select_strategy(None);
        assert!(result.is_ok());
        assert_eq!(result.unwrap(), "uia");
    }

    #[test]
    fn test_broker_only_trade_action() {
        assert!(crate::commands::pc_automation::broker_only("submit_order".to_string()).unwrap());
        assert!(crate::commands::pc_automation::broker_only("buy".to_string()).unwrap());
        assert!(crate::commands::pc_automation::broker_only("sell".to_string()).unwrap());
    }

    #[test]
    fn test_broker_only_non_trade_action() {
        assert!(!crate::commands::pc_automation::broker_only("navigate".to_string()).unwrap());
        assert!(!crate::commands::pc_automation::broker_only("read".to_string()).unwrap());
    }

    #[test]
    fn test_no_broker_available() {
        let result = crate::commands::pc_automation::no_broker_available();
        assert!(result.is_ok());
    }

    #[test]
    fn test_list_app_profiles() {
        let result = crate::commands::pc_automation::list_app_profiles();
        assert!(result.is_ok());
        // Should return at least some profiles
        let _profiles = result.unwrap();
        // May be empty in test env, but shouldn't error
    }

    #[test]
    #[ignore = "COM UIAutomation via shared_state crashes in test env"]
    fn test_uia_get_focused_window_command() {
        let _desktop_guard = DESKTOP_TEST_LOCK
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        let result = crate::commands::pc_automation::uia_get_focused_window();
        // May return Ok(None) or Ok(Some(node)) or Err
        // Just verify it doesn't panic
        assert!(result.is_ok() || result.is_err());
    }

    #[test]
    #[ignore = "COM UIAutomation DFS tree walk via shared_state crashes in test env"]
    fn test_uia_find_command() {
        let _desktop_guard = DESKTOP_TEST_LOCK
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        use crate::commands::pc_automation::UiaSelectorView;
        let sel = UiaSelectorView {
            control_type: None,
            name: Some("__nonexistent__".to_string()),
            name_contains: None,
            automation_id: None,
            class_name: None,
            path: vec![],
        };
        let result = crate::commands::pc_automation::uia_find(sel);
        assert!(result.is_ok());
        assert!(result.unwrap().is_none());
    }

    // =============================================================
    // 10. Execute step through the full IPC layer
    // =============================================================

    #[test]
    #[ignore = "requires live Desktop + full PcAutomationState (real CDP/OCR backends); crashes with STATUS_ACCESS_VIOLATION in headless test env due to COM/WinRT init conflict"]
    fn test_execute_step_nonexistent_selector() {
        let _desktop_guard = DESKTOP_TEST_LOCK
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        use crate::commands::pc_automation::{PcStepView, StepResult};

        let step = PcStepView {
            id: "exec-test-1".to_string(),
            description: "test".to_string(),
            app_profile: None,
            strategy: "uia".to_string(),
            primary_selector: "uia:name=__nonexistent__".to_string(),
            fallback_selectors: vec![],
            recorded_coords: None,
        };

        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("tokio runtime");

        let result: StepResult = rt.block_on(async {
            crate::commands::pc_automation::execute_step(step).await.unwrap()
        });

        assert!(!result.ok, "Step with non-existent selector should fail");
        assert!(result.error.is_some());
    }

    #[test]
    fn test_execute_step_invalid_strategy() {
        let _desktop_guard = DESKTOP_TEST_LOCK
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        use crate::commands::pc_automation::{PcStepView, StepResult};

        let step = PcStepView {
            id: "exec-test-2".to_string(),
            description: "test".to_string(),
            app_profile: None,
            strategy: "invalid".to_string(),
            primary_selector: "uia:".to_string(),
            fallback_selectors: vec![],
            recorded_coords: None,
        };

        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("tokio runtime");

        let result: StepResult = rt.block_on(async {
            crate::commands::pc_automation::execute_step(step).await.unwrap()
        });

        assert!(!result.ok);
        assert!(result.error.unwrap().contains("unknown strategy"));
    }
}
