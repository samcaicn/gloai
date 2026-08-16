// Copyright (c) 2026 tupAI
//
// OCR types: regions, matches, anchors, engine choice. Kept in
// their own file so the engine-agnostic healing code can pull them
// in without dragging the Paddle binding.

use serde::{Deserialize, Serialize};

use crate::pc_automation::parse_error::ParseError;

/// Rectangle in screen coordinates (pixels). `w` / `h` are signed
/// so an underflow on a bad input is caught at parse time rather
/// than crashing the OCR call.
#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
#[derive(Default)]
pub struct OcrRegion {
    pub x: i32,
    pub y: i32,
    pub w: i32,
    pub h: i32,
}


/// One text line returned by the engine. `confidence` is the
/// engine's own score in `[0, 1]`; we never renormalise so the
/// healing subsystem can threshold against the original
/// distribution.
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct OcrMatch {
    pub text: String,
    pub confidence: f32,
    pub x: i32,
    pub y: i32,
    pub w: i32,
    pub h: i32,
}

/// Which engine to dispatch to. `PpOcrV5` is the fast path (CPU,
/// ~30ms); `PaddleVl16` is the deep path (iGPU, ~400ms) reserved
/// for complex layouts (handwriting, classical Chinese, math).
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum OcrEngine {
    PpOcrV5,
    PaddleVl16,
}

/// What to find via OCR. `full_screen = true` means "scan the
/// entire visible desktop, not just `region`" — used by app
/// profiles that want to locate a coordinate without a hint
/// rectangle.
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct OcrAnchor {
    pub region: Option<OcrRegion>,
    pub match_text: String,
    pub full_screen: bool,
    pub engine: OcrEngine,
}

/// Parse an `ocr:` literal. Grammar:
///
/// ```text
/// ocr:engine=paddleVl16;match=提交;region=100,100,800,600;fullScreen=true
/// ```
///
/// The `region` value is `x,y,w,h` (commas, not semicolons, so
/// semicolons can keep separating the top-level key/value pairs).
pub fn parse_ocr_anchor(s: &str) -> Result<OcrAnchor, ParseError> {
    const PREFIX: &str = "ocr:";
    let body = s.strip_prefix(PREFIX).ok_or_else(|| {
        ParseError::InvalidPrefix(s.chars().take(4).collect::<String>())
    })?;

    let mut region: Option<OcrRegion> = None;
    let mut match_text = String::new();
    let mut full_screen = false;
    let mut engine = OcrEngine::PpOcrV5;

    if body.is_empty() {
        return Ok(OcrAnchor {
            region,
            match_text,
            full_screen,
            engine,
        });
    }

    for kv in body.split(';') {
        if kv.is_empty() {
            continue;
        }
        let (k, v) = kv.split_once('=').ok_or(ParseError::MissingField("key=value"))?;
        match k.trim() {
            "engine" => {
                engine = match v {
                    "ppOcrV5" | "pp_ocr_v5" | "ppocr" => OcrEngine::PpOcrV5,
                    "paddleVl16" | "paddle_vl_1_6" | "vl16" => OcrEngine::PaddleVl16,
                    other => {
                        return Err(ParseError::MissingField(match other {
                            "ppOcrV5" => "ppOcrV5",
                            "paddleVl16" => "paddleVl16",
                            _ => "ocr engine",
                        }))
                    }
                };
            }
            "match" | "text" => match_text = v.to_string(),
            "fullScreen" | "full_screen" => {
                full_screen = matches!(v, "true" | "1" | "yes");
            }
            "region" => {
                let parts: Vec<&str> = v.split(',').collect();
                if parts.len() != 4 {
                    return Err(ParseError::BadNumber(v.to_string()));
                }
                let x = parts[0].parse::<i32>().map_err(|_| ParseError::BadNumber(parts[0].to_string()))?;
                let y = parts[1].parse::<i32>().map_err(|_| ParseError::BadNumber(parts[1].to_string()))?;
                let w = parts[2].parse::<i32>().map_err(|_| ParseError::BadNumber(parts[2].to_string()))?;
                let h = parts[3].parse::<i32>().map_err(|_| ParseError::BadNumber(parts[3].to_string()))?;
                region = Some(OcrRegion { x, y, w, h });
            }
            other => {
                return Err(ParseError::MissingField(match other {
                    "engine" => "engine",
                    "match" => "match",
                    "fullScreen" => "fullScreen",
                    "region" => "region",
                    _ => "ocr field",
                }))
            }
        }
    }

    Ok(OcrAnchor {
        region,
        match_text,
        full_screen,
        engine,
    })
}
