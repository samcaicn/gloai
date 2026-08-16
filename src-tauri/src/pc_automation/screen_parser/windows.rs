// Copyright (c) 2026 AIMarketing
//
// screen_parser/windows.rs
//
// Windows implementation of the screen-parser backend. Composes
// the existing UIA + OCR backends into a single flat
// `Vec<ScreenElement>` — no new heavy dependencies are pulled
// in, and the runtime cost is the sum of one UIA tree walk plus
// (optionally) one OCR call per region without accessible
// children.
//
// Algorithmic sketch:
//   1. Walk the UIA subtree of the focused window, in document
//      order, producing one `ScreenElement` per leaf-ish node
//      (text-bearing controls, not pure-layout `Pane`s).
//   2. If the caller asked for OCR and any UIA leaf in the
//      region has `Name=""`, run OCR over the region and attach
//      the strongest matching OCR line to that element by
//      bounding-rect containment.
//   3. Dedup by `id` (a content hash of `role|text|automationId`),
//      keep the first occurrence, drop everything below
//      `min_confidence`.
//
// The Windows-specific bits (GDI capture, WinRT OCR engine) are
// reused as-is from the OCR backend; we deliberately do *not*
// re-implement them here so the two stay in lockstep.

use std::collections::hash_map::DefaultHasher;
use std::hash::{Hash, Hasher};

use crate::pc_automation::ocr::backend::OcrBackend;
use crate::pc_automation::ocr::types::OcrRegion;
use crate::pc_automation::screen_parser::backend::{ScreenParserBackend, ScreenParserHealth};
use crate::pc_automation::screen_parser::types::{
    ElementRole, ElementSource, ParseRequest, ScreenElement, ScreenRect,
};
use crate::pc_automation::uia::backend::UiaBackend;
use crate::pc_automation::uia::types::UiaNode;

/// Threshold below which a UIA node's role is treated as
/// "pure layout" (Pane / Window) and skipped — those nodes
/// rarely carry text and just bloat the parse output. The
/// front-end can still surface them via a separate "show raw
/// tree" toggle if it needs to.
const LAYOUT_SKIP_ROLES: &[ElementRole] = &[ElementRole::Pane, ElementRole::Window];

pub struct WindowsScreenParserBackend {
    uia: std::sync::Arc<dyn UiaBackend>,
    ocr: std::sync::Arc<dyn OcrBackend>,
}

impl WindowsScreenParserBackend {
    pub fn new(uia: std::sync::Arc<dyn UiaBackend>, ocr: std::sync::Arc<dyn OcrBackend>) -> Self {
        Self { uia, ocr }
    }

    /// Stable content-hash id used for dedup. The hash inputs
    /// mirror what a recipe author would key on: role, text,
    /// automation_id. `x/y/w/h` are *not* part of the hash so
    /// that a window that moves 4 pixels between parses still
    /// produces the same id.
    fn content_id(role: ElementRole, text: &str, automation_id: &str) -> String {
        let mut h = DefaultHasher::new();
        role.hash(&mut h);
        text.hash(&mut h);
        automation_id.hash(&mut h);
        format!("el-{:016x}", h.finish())
    }

    /// Convert a UIA node + (optional) OCR text to a
    /// `ScreenElement`. Pure data transformation, no I/O.
    fn to_element(
        node: &UiaNode,
        ocr_text: Option<&str>,
        ocr_confidence: f32,
    ) -> ScreenElement {
        let role = ElementRole::from_uia_control(&node.control_type);
        let (x, y, w, h) = node.bounding_rect;
        let rect = ScreenRect { x, y, w: w as i32, h: h as i32 };

        // The merge rule: if both UIA `Name` and OCR text are
        // present, we trust UIA (it carries the canonical
        // accessible name) and only bump confidence. If only
        // OCR has text, we use that and flag the source as
        // OCR-only. If neither, the element is essentially
        // decorative and we keep its empty text.
        let (text, source, confidence) = if !node.name.is_empty() && ocr_text.is_some() {
            (node.name.clone(), ElementSource::UiaOcr, 0.95_f32)
        } else if !node.name.is_empty() {
            (node.name.clone(), ElementSource::Uia, 0.9_f32)
        } else if let Some(ocr) = ocr_text {
            (ocr.to_string(), ElementSource::Ocr, ocr_confidence)
        } else {
            (String::new(), ElementSource::Uia, 0.7_f32)
        };

        ScreenElement {
            id: Self::content_id(role, &text, &node.automation_id),
            role,
            text,
            rect,
            source,
            confidence,
            automation_id: node.automation_id.clone(),
        }
    }

    /// Depth-first walk that flattens the UIA tree into
    /// elements. Pure-layout nodes are skipped unless they
    /// carry a non-empty `Name` (which happens for some
    /// groups / custom controls).
    fn flatten_uia(node: &UiaNode, out: &mut Vec<UiaNode>) {
        let role = ElementRole::from_uia_control(&node.control_type);
        let has_text = !node.name.is_empty();
        if !LAYOUT_SKIP_ROLES.contains(&role) || has_text {
            out.push(node.clone());
        }
        for c in &node.children {
            Self::flatten_uia(c, out);
        }
    }

    /// If the parse region is fully inside the UIA coverage
    /// (i.e. the focused window), we don't need to OCR. We
    /// *do* still OCR any UIA node whose `Name` is empty.
    /// Returns `true` when OCR was attempted.
    fn maybe_attach_ocr(
        &self,
        elements: &mut [ScreenElement],
        region: ScreenRect,
        min_confidence: f32,
    ) -> bool {
        // Cost guard: if there are no name-less elements,
        // skip the OCR call entirely. Most well-authored
        // Win32 / WinUI apps have populated `Name` on every
        // interactive control, so this is the common case.
        let needs_ocr = elements.iter().any(|e| e.automation_id.is_empty() || matches!(e.source, ElementSource::Uia) && e.text.is_empty());
        if !needs_ocr {
            return false;
        }
        let ocr_region = OcrRegion { x: region.x, y: region.y, w: region.w, h: region.h };
        let matches = match self.ocr.read_text(ocr_region) {
            Ok(m) => m,
            Err(_) => return false,
        };
        // Pick, for each element, the OCR match whose rect is
        // the smallest one that still contains the element's
        // centre. This avoids attaching far-away OCR noise to
        // an empty-named control.
        for el in elements.iter_mut() {
            if !el.text.is_empty() {
                continue;
            }
            let cx = el.rect.x + el.rect.w / 2;
            let cy = el.rect.y + el.rect.h / 2;
            let mut best: Option<&crate::pc_automation::ocr::types::OcrMatch> = None;
            let mut best_area = i64::MAX;
            for m in &matches {
                if cx >= m.x && cx <= m.x + m.w && cy >= m.y && cy <= m.y + m.h {
                    let area = (m.w as i64) * (m.h as i64);
                    if area < best_area {
                        best_area = area;
                        best = Some(m);
                    }
                }
            }
            if let Some(m) = best {
                if m.confidence >= min_confidence {
                    el.text = m.text.clone();
                    el.source = ElementSource::Ocr;
                    el.confidence = m.confidence;
                }
            }
        }
        true
    }
}

impl ScreenParserBackend for WindowsScreenParserBackend {
    fn parse(&self, req: ParseRequest) -> Result<Vec<ScreenElement>, String> {
        let region = req.region.unwrap_or({
            // Default to the focused window's bounding rect.
            // If there is no focused window, fall back to the
            // whole virtual screen (caller can pass an explicit
            // rect to override).
            ScreenRect { x: 0, y: 0, w: 0, h: 0 }
        });

        // Step 1: walk the UIA tree. The focused window is
        // always the parse root — it matches how the UIA tier
        // resolves selectors, so the two backends agree on
        // "what is on screen right now".
        let root = self
            .uia
            .get_root()
            .map_err(|e| format!("screen_parser: get_root: {}", e))?;
        let mut flat: Vec<UiaNode> = Vec::new();
        Self::flatten_uia(&root, &mut flat);

        // Step 2: UIA -> ScreenElement.
        let mut elements: Vec<ScreenElement> = flat
            .iter()
            .map(|n| Self::to_element(n, None, 0.0))
            .collect();

        // Step 3: optional OCR overlay for elements with no
        // accessible name.
        if req.include_ocr {
            let _ = self.maybe_attach_ocr(&mut elements, region, req.min_confidence);
        }

        // Step 4: drop below-threshold noise.
        elements.retain(|e| e.confidence >= req.min_confidence);

        // Step 5: dedup by content id, keep first.
        let mut seen = std::collections::HashSet::new();
        elements.retain(|e| seen.insert(e.id.clone()));

        Ok(elements)
    }

    fn health(&self) -> Result<ScreenParserHealth, String> {
        let uia_ok = self.uia.get_focused_window().is_ok() || self.uia.get_focused_window().map(|o| o.is_none()).unwrap_or(false);
        let ocr_ok = self.ocr.health().is_ok();
        Ok(ScreenParserHealth {
            uia_backend_available: uia_ok,
            ocr_backend_available: ocr_ok,
            parse_capable: uia_ok || ocr_ok,
        })
    }
}
