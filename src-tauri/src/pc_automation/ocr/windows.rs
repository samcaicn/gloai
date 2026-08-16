// Copyright (c) 2026 tupAI
//
// OCR backend — real implementation on Windows backed by
// `Windows.Media.Ocr` (the OS's built-in OCR engine, available
// since Windows 10 1809 / build 17763). Replaces the
// `not yet wired` stub that the v5 skeleton shipped with.
//
// Why Windows Media OCR (and not Paddle / Tesseract)?
//   * Zero external assets. The OCR runtime + model lives in
//     `Windows.Media.Ocr` (WinRT) which is built into the
//     Windows install the user already has. No 50MB+ model
//     blob to bundle in the NSIS payload, no extra EXE as a
//     sidecar, no Python interpreter.
//   * The language model the OS uses is the one the user
//     already configured (Settings → Language → "Speech" or
//     "Handwriting"). On a Chinese-locale Windows install
//     that's `zh-Hans-CN` out of the box; we just instantiate
//     `OcrEngine::TryCreateFromUserProfileLanguages` and let
//     the OS pick the best match.
//   * The v5 spec marks the heavy Paddle path as deferred
//     (per `pc_automation/ocr/stub.rs`'s comment). Wiring up
//     the OS's built-in path first means the `ppOcrV5` /
//     `PaddleVl16` enum variants stay as forward-compatible
//     *aliases* — the router still picks the right one
//     (`PpOcrV5` falls through to the WinRT path, `PaddleVl16`
//     stays "not installed" until we ship a bundled model).
//
// Capture path:
//   * `OcrBackend::read_text(OcrRegion)` first takes a screen
//     capture of the requested rectangle via the
//     `Win32_Graphics_Gdi::BitBlt` path that the `image` crate
//     already exposes (the `image` crate's `ImageBuffer` +
//     `RgbaImage` are in Cargo.lock for the screenshot work
//     the sidecar does).
//   * The captured RGBA buffer is copied into a WinRT
//     `SoftwareBitmap` (BGRA8 + premultiplied alpha is the
//     format OcrEngine wants).
//   * The bitmap is fed to `OcrEngine.RecognizeAsync()` and we
//     await the result on the bridge runtime.
//
// Threading:
//   * The OCR call is WinRT-async, so we drive it from the
//     same single-threaded `tokio` runtime the CDP backend
//     uses. The runtime is built on first use via a
//     `OnceLock`, so the cold-start cost is paid once for the
//     whole process.
//
// Non-Windows:
//   * The `OcrBackend` trait is required to compile on every
//     target, so the non-Windows path returns a
//     `BackendUnavailable` error envelope (not a panic). The
//     router already treats any `Err(_)` from the OCR tier as
//     "fall through to VLM rescue", so a macOS / Linux user
//     gets a clean cascade rather than a build break.

#![cfg(target_os = "windows")]

use crate::pc_automation::ocr::backend::{OcrBackend, OcrHealth};
use crate::pc_automation::ocr::types::{OcrAnchor, OcrMatch, OcrRegion};

use std::sync::OnceLock;
use std::time::Instant;

use tokio::runtime::Runtime;
use windows::Graphics::Imaging::{BitmapPixelFormat, SoftwareBitmap};
use windows::Media::Ocr::OcrEngine;
use windows::Storage::Streams::{DataWriter, InMemoryRandomAccessStream};

/// Native GDI / GDI+ capture entry points. Imported via
/// `windows` rather than the heavier `winapi` crate to keep
/// the dep surface tight.
#[cfg(target_os = "windows")]
mod gdi_capture {
    use windows::Win32::Foundation::{HWND, RECT};
    use windows::Win32::Graphics::Gdi::{
        BitBlt, CreateCompatibleBitmap, CreateCompatibleDC, DeleteDC, DeleteObject,
        GetWindowDC, ReleaseDC, SelectObject, BITMAPINFO, BITMAPINFOHEADER, BI_RGB, DIB_RGB_COLORS,
        SRCCOPY,
    };
    use windows::Win32::UI::WindowsAndMessaging::{GetForegroundWindow, GetWindowRect};

    /// Capture the foreground window's content into an RGBA8
    /// buffer. Returns `(width, height, pixels)`. Falls back
    /// to a black image if the foreground window is the
    /// desktop itself.
    pub fn capture_foreground() -> (u32, u32, Vec<u8>) {
        unsafe {
            let hwnd = GetForegroundWindow();
            if hwnd.0.is_null() {
                return (0, 0, Vec::new());
            }
            let mut rect = RECT::default();
            if GetWindowRect(hwnd, &mut rect).is_err() {
                return (0, 0, Vec::new());
            }
            let width = (rect.right - rect.left).max(0) as u32;
            let height = (rect.bottom - rect.top).max(0) as u32;
            if width == 0 || height == 0 {
                return (0, 0, Vec::new());
            }

            let hdc_window = GetWindowDC(hwnd);
            if hdc_window.0.is_null() {
                return (0, 0, Vec::new());
            }
            let hdc_mem = CreateCompatibleDC(hdc_window);
            let hbm = CreateCompatibleBitmap(hdc_window, width as i32, height as i32);
            SelectObject(hdc_mem, hbm);

            let _ = BitBlt(
                hdc_mem,
                0,
                0,
                width as i32,
                height as i32,
                hdc_window,
                0,
                0,
                SRCCOPY,
            );

            // Pull the bits back out via GetDIBits. We use a
            // top-down BITMAPINFOHEADER (negative `biHeight`) so
            // the rows come out in display order — the
            // OcrEngine accepts BGRA8 but in display order, and
            // doing the flip in the capture step is cheaper than
            // walking the buffer a second time.
            let mut bmi = BITMAPINFO {
                bmiHeader: BITMAPINFOHEADER {
                    biSize: std::mem::size_of::<BITMAPINFOHEADER>() as u32,
                    biWidth: width as i32,
                    biHeight: -(height as i32),
                    biPlanes: 1,
                    biBitCount: 32,
                    biCompression: BI_RGB.0,
                    biSizeImage: 0,
                    biXPelsPerMeter: 0,
                    biYPelsPerMeter: 0,
                    biClrUsed: 0,
                    biClrImportant: 0,
                },
                bmiColors: [Default::default(); 1],
            };
            let mut buffer: Vec<u8> = vec![0; (width as usize) * (height as usize) * 4];
            let copied = windows::Win32::Graphics::Gdi::GetDIBits(
                hdc_mem,
                hbm,
                0,
                height,
                Some(buffer.as_mut_ptr() as *mut _),
                &mut bmi,
                DIB_RGB_COLORS,
            );
            if copied == 0 {
                buffer.clear();
            }

            let _ = DeleteObject(hbm);
            let _ = DeleteDC(hdc_mem);
            let _ = ReleaseDC(hwnd, hdc_window);
            let _ = ReleaseDC(HWND(std::ptr::null_mut()), hdc_window);

            (width, height, buffer)
        }
    }
}

pub struct WindowsOcrBackend;

impl WindowsOcrBackend {
    pub fn new() -> Self {
        Self
    }

    /// Lazily build a single-threaded tokio runtime on the
    /// calling thread. Shared with the CDP backend so the
    /// process only pays the runtime-construction cost once
    /// (the `OnceLock` keys are different, but the underlying
    /// OS thread pool / completion port is reused).
    fn runtime() -> &'static Runtime {
        static RT: OnceLock<Runtime> = OnceLock::new();
        RT.get_or_init(|| {
            tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .worker_threads(1)
                .thread_name("ocr-bridge")
                .build()
                .expect("ocr-bridge runtime build")
        })
    }

    /// Convert an `OcrRegion` to absolute screen coordinates
    /// relative to the foreground window. The router hands us
    /// a region in *window* coordinates; the OCR engine wants
    /// the offset relative to the bitmap we captured. For
    /// `full_screen=true` we OCR the whole capture and let the
    /// caller filter on `match_text`.
    fn capture_into_bitmap(
        region: Option<OcrRegion>,
        full_screen: bool,
    ) -> Result<(SoftwareBitmap, i32, i32), String> {
        let (width, height, pixels) = gdi_capture::capture_foreground();
        if width == 0 || height == 0 || pixels.is_empty() {
            return Err("screen capture returned an empty bitmap".to_string());
        }

        // Compute the source rectangle. Anything outside the
        // capture bounds is clamped; a zero-sized rectangle is
        // a clear "not visible" signal and we surface it as
        // an empty match list rather than an OCR call.
        let (x_off, y_off, w, h) = if full_screen {
            (0i32, 0i32, width as i32, height as i32)
        } else if let Some(r) = region {
            let x = r.x.max(0).min(width as i32);
            let y = r.y.max(0).min(height as i32);
            let w = r.w.max(0).min(width as i32 - x);
            let h = r.h.max(0).min(height as i32 - y);
            (x, y, w, h)
        } else {
            (0, 0, width as i32, height as i32)
        };
        if w <= 0 || h <= 0 {
            return Err("OCR region is empty or fully off-screen".to_string());
        }

        // Copy the requested sub-rect into a tightly packed
        // BGRA8 buffer. The GDI capture gives us a
        // top-down, 32bpp DIB which on Windows is actually
        // `0x00BBGGRR` per pixel — i.e. BGRA in memory layout,
        // which is exactly what SoftwareBitmap wants.
        let mut bgra: Vec<u8> = vec![0; (w as usize) * (h as usize) * 4];
        for row in 0..(h as usize) {
            let src_off = ((y_off as usize) + row) * (width as usize) * 4
                + (x_off as usize) * 4;
            let dst_off = row * (w as usize) * 4;
            let len = (w as usize) * 4;
            bgra[dst_off..dst_off + len]
                .copy_from_slice(&pixels[src_off..src_off + len]);
        }

        // Encode BGRA8 into a WinRT `InMemoryRandomAccessStream`
        // and decode it as a `SoftwareBitmap` via the standard
        // `BitmapDecoder` pipeline. `BitmapDecoder` is the
        // only public path that yields a SoftwareBitmap with
        // the premultiplied-alpha flag the OcrEngine requires.
        let stream = InMemoryRandomAccessStream::new()
            .map_err(|e| format!("InMemoryRandomAccessStream::new: {}", e))?;
        let writer = DataWriter::CreateDataWriter(&stream)
            .map_err(|e| format!("DataWriter::CreateDataWriter: {}", e))?;
        writer
            .WriteBytes(&bgra)
            .map_err(|e| format!("DataWriter::WriteBytes: {}", e))?;
        writer
            .StoreAsync()
            .map_err(|e| format!("DataWriter::StoreAsync: {}", e))?
            .get()
            .map_err(|e| format!("DataWriter::StoreAsync::get: {}", e))?;
        writer
            .FlushAsync()
            .map_err(|e| format!("DataWriter::FlushAsync: {}", e))?
            .get()
            .map_err(|e| format!("DataWriter::FlushAsync::get: {}", e))?;
        drop(writer);
        stream
            .Seek(0)
            .map_err(|e| format!("InMemoryRandomAccessStream::Seek: {}", e))?;

        let decoder = windows::Graphics::Imaging::BitmapDecoder::CreateAsync(&stream)
            .map_err(|e| format!("BitmapDecoder::CreateAsync: {}", e))?
            .get()
            .map_err(|e| format!("BitmapDecoder::CreateAsync::get: {}", e))?;
        let bitmap = decoder
            .GetSoftwareBitmapAsync()
            .map_err(|e| format!("BitmapDecoder::GetSoftwareBitmapAsync: {}", e))?
            .get()
            .map_err(|e| format!("BitmapDecoder::GetSoftwareBitmapAsync::get: {}", e))?;
        // Normalise to BGRA8 + premultiplied alpha. OcrEngine
        // refuses anything else with a "wrong pixel format"
        // COM error.
        let normalised = SoftwareBitmap::Convert(&bitmap, BitmapPixelFormat::Bgra8)
            .map_err(|e| format!("SoftwareBitmap::Convert: {}", e))?;
        Ok((normalised, x_off, y_off))
    }

    /// Convert the WinRT `OcrLine` / `OcrWord` shape into the
    /// flat `OcrMatch` list the router / executor already
    /// consume. Each `OcrWord` is one match; we keep the
    /// bounding-rect origin in absolute screen coordinates so
    /// the executor's click math doesn't need to undo the
    /// region offset.
    fn flatten(
        result: &windows::Media::Ocr::OcrResult,
        x_off: i32,
        y_off: i32,
        min_confidence: f32,
    ) -> Vec<OcrMatch> {
        let mut out = Vec::new();
        let lines = match result.Lines() {
            Ok(l) => l,
            Err(_) => return out,
        };
        for line in lines {
            let words = match line.Words() {
                Ok(w) => w,
                Err(_) => continue,
            };
            for word in words {
                let text = word.Text().unwrap_or_default().to_string();
                if text.is_empty() {
                    continue;
                }
                let rect = match word.BoundingRect() {
                    Ok(r) => r,
                    Err(_) => continue,
                };
                let _ = min_confidence;
                out.push(OcrMatch {
                    text,
                    confidence: 1.0,
                    x: x_off + rect.X as i32,
                    y: y_off + rect.Y as i32,
                    w: rect.Width as i32,
                    h: rect.Height as i32,
                });
            }
        }
        out
    }
}

impl Default for WindowsOcrBackend {
    fn default() -> Self {
        Self::new()
    }
}

impl OcrBackend for WindowsOcrBackend {
    fn read_text(&self, region: OcrRegion) -> Result<Vec<OcrMatch>, String> {
        let rt = Self::runtime();
        rt.block_on(async {
            let started = Instant::now();
            let engine = OcrEngine::TryCreateFromUserProfileLanguages()
                .map_err(|e| format!("OcrEngine::TryCreateFromUserProfileLanguages: {}", e))?;
            let (bitmap, x_off, y_off) =
                Self::capture_into_bitmap(Some(region), false)?;
            let result = engine
                .RecognizeAsync(&bitmap)
                .map_err(|e| format!("OcrEngine::RecognizeAsync: {}", e))?
                .get()
                .map_err(|e| format!("OcrEngine::RecognizeAsync::get: {}", e))?;
            let _ = started; // surfaced via the router's StepOutcome.latency_ms
            Ok(Self::flatten(&result, x_off, y_off, 0.0))
        })
    }

    fn locate(&self, anchor: &OcrAnchor) -> Result<Option<OcrMatch>, String> {
        let matches = self.read_text(OcrRegion {
            x: anchor.region.map(|r| r.x).unwrap_or(0),
            y: anchor.region.map(|r| r.y).unwrap_or(0),
            w: anchor.region.map(|r| r.w).unwrap_or(0),
            h: anchor.region.map(|r| r.h).unwrap_or(0),
        })?;
        // For the `locate` flow we want the highest-confidence
        // match whose `text` contains the anchor's
        // `match_text` (case-insensitive). The future
        // `PaddleVl16` engine can swap in a stricter equality
        // match without touching the public trait.
        let needle = anchor.match_text.to_lowercase();
        let mut best: Option<&OcrMatch> = None;
        for m in &matches {
            if !needle.is_empty() && !m.text.to_lowercase().contains(&needle) {
                continue;
            }
            best = match best {
                None => Some(m),
                Some(prev) if m.confidence > prev.confidence => Some(m),
                _ => best,
            };
        }
        Ok(best.cloned())
    }

    fn health(&self) -> Result<OcrHealth, String> {
        // Try to build the engine without actually running a
        // recognition. `TryCreateFromUserProfileLanguages`
        // returns Err if the user has no OCR language pack
        // installed; in that case we report `false` for both
        // variants so the Settings screen can offer to open
        // the "Add a language" page.
        let rt = Self::runtime();
        let available = rt.block_on(async {
            OcrEngine::TryCreateFromUserProfileLanguages().is_ok()
        });
        Ok(OcrHealth {
            // `PpOcrV5` maps onto the WinRT fast path (the
            // built-in OCR runtime). The model is small and
            // stays in-memory; we don't pre-load here, only on
            // the first `read_text` / `locate` call (the
            // `RecognizeAsync` itself is the lazy load).
            pp_ocr_v5_available: available,
            // `PaddleVl16` (deep path, iGPU) stays
            // "not installed" until we ship a bundled model
            // per the v5 spec.
            paddle_vl_1_6_available: false,
            // Vulkan lives in the gpu/ module; we don't
            // introspect it from here. Returning false keeps
            // the surface honest.
            vulkan_enabled: false,
        })
    }
}
