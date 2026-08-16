// Copyright (c) 2026 AIMarketing
//
// ============================================================================
// Domain-aware router. v5 §1.4 / uirap改造技术方案.md §4.
// ============================================================================
//
// Every `PcStep` is dispatched by:
//
//   1. Determining its *domain* (Desktop vs Web) from the
//      `AppProfile.renderer` it carries:
//        * `Mfc` / `SelfDraw` → Desktop  → UIA primary, OCR fallback
//        * `Electron` / `Web` → Web      → CDP primary, OCR fallback
//
//   2. Trying the primary tier for that domain. On miss, the
//      router cascades to OCR (works for self-drawn buttons on
//      the desktop side and for canvas / image-only elements on
//      the web side).
//
//   3. Returning `RouterError::StructuredMiss { primary,
//      fallback }` if BOTH tiers miss. The executor interprets
//      this as "escalate to VLM rescue" — the rescue lives in
//      `pc_automation::vlm_rescue` and is NOT a tier in the
//      cascade.
//
// ── 不跨域降级原则 ────────────────────────────────────────
// UIA/Mac 自动化不降级到 CDP，CDP 也不降级到 UIA。
// 当具体节点识别不够时，智能调用 OCR/VLM（逐节点补充），
// 而不是整体切换自动化类型。
//
//   Desktop domain:  UIA → OCR → VLM  （绝不走 CDP）
//   Web domain:      CDP → OCR → VLM  （绝不走 UIA）
//
// 特例：当 step.strategy == StepStrategy::Ocr 时，
// 跳过 primary tier 直接走 OCR（避免 UIA parse 浪费时间）。
//
// The router is async (so the IPC layer can `.await` it without
// spinning a thread) and uses `Arc<dyn Backend>` so the real
// backends can be swapped in without changing the call sites.
// ============================================================================

use std::sync::Arc;
use std::time::Instant;

use crate::pc_automation::apps::{find_profile, RendererType};
use crate::pc_automation::cdp::backend::CdpBackend;
use crate::pc_automation::cdp::types::{parse_cdp_selector, CdpAction};
use crate::pc_automation::cua_driver::CuaDriverClient;
use crate::pc_automation::logger as pc_log;
use crate::pc_automation::ocr::backend::OcrBackend;
use crate::pc_automation::ocr::types::parse_ocr_anchor;
use crate::pc_automation::step::{PcStep, RouterError, StepOutcome, StepStrategy};
use crate::pc_automation::uia::backend::UiaBackend;
use crate::pc_automation::uia::types::parse_uia_selector;

/// Domain the step is being executed in. Drives the *primary*
/// tier choice; OCR is the shared fallback for both domains.
///
/// 不跨域降级：Desktop domain 永远不会走 CDP，
/// Web domain 永远不会走 UIA。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Domain {
    /// Native desktop app (MFC / Win32 / self-drawn GDI / macOS
    /// Accessibility). UIA is the primary tier.
    /// UIA → OCR fallback → VLM rescue。绝不降级到 CDP。
    Desktop,
    /// Browser-rendered surface (Electron / Web). CDP is the
    /// primary tier.
    /// CDP → OCR fallback → VLM rescue。绝不降级到 UIA。
    Web,
}

impl Domain {
    /// Map an `AppProfile.renderer` value to the execution
    /// domain. We deliberately collapse the four renderer types
    /// into the two execution domains because the router only
    /// cares about "is this a structured desktop window" vs
    /// "is this a browser surface I can talk CDP to".
    pub fn from_renderer(r: RendererType) -> Self {
        match r {
            RendererType::Mfc | RendererType::SelfDraw => Domain::Desktop,
            RendererType::Electron | RendererType::Web => Domain::Web,
        }
    }
}

/// Look up the domain for a `PcStep` from its `app_profile`
/// field. If the profile is missing or unknown, we fall back to
/// Desktop (UIA primary) — the safer default for an unknown
/// trading / finance app, which is almost always a native
/// window. Callers that need the other default can override
/// via `PcStep.strategy` (we still consult that as a hint
/// below).
///
/// 注意：当 strategy == Cdp 时强制走 Web domain，
/// 确保录制时声明的自动化类型被尊重。
fn domain_for_step(step: &PcStep) -> Domain {
    if let Some(profile_id) = step.app_profile.as_deref() {
        if let Some(profile) = find_profile(profile_id) {
            return Domain::from_renderer(profile.renderer);
        }
    }
    // No profile → trust the step's declared strategy: CDP-only
    // steps (web) ask for CDP primary; everything else falls
    // back to Desktop (UIA primary).
    match step.strategy {
        StepStrategy::Cdp => Domain::Web,
        _ => Domain::Desktop,
    }
}

/// Per-tier latency collected during `execute_step`. Written to
/// the module log so the executor can surface it in the
/// `uirpa_step_succeeded` event.
#[derive(Debug, Clone)]
pub struct StepTiming {
    pub primary_ms: Option<u64>,
    pub fallback_ms: Option<u64>,
}

pub struct PcRouter {
    pub uia: Arc<dyn UiaBackend>,
    pub cdp: Arc<dyn CdpBackend>,
    pub ocr: Arc<dyn OcrBackend>,
}

impl PcRouter {
    pub fn new(
        uia: Arc<dyn UiaBackend>,
        cdp: Arc<dyn CdpBackend>,
        ocr: Arc<dyn OcrBackend>,
    ) -> Self {
        Self { uia, cdp, ocr }
    }

    /// Run a single step through the domain-aware cascade.
    /// Returns the `StepOutcome` of the first successful tier, or
    /// `RouterError::StructuredMiss { primary, fallback }` if
    /// both miss. The caller (executor) is expected to escalate
    /// to VLM rescue on `StructuredMiss`.
    ///
    /// 不跨域降级原则：
    ///   Desktop → UIA primary → OCR fallback → VLM rescue（不走 CDP）
    ///   Web     → CDP primary → OCR fallback → VLM rescue（不走 UIA）
    ///
    /// 特例：step.strategy == Ocr 时跳过 primary，直接走 OCR。
    pub async fn execute_step(&self, step: &PcStep) -> Result<StepOutcome, RouterError> {
        let mut timing = StepTiming { primary_ms: None, fallback_ms: None };

        // ── OCR 直达快捷路径 ──────────────────────────────
        // 当 step 显式声明 strategy == Ocr 时，跳过 domain primary
        // （UIA/CDP），直接走 OCR fallback。避免对 OCR selector
        // 做 UIA parse 白费时间。
        if step.strategy == StepStrategy::Ocr {
            let ocr_start = Instant::now();
            let ocr_result = self.try_ocr(step).await;
            timing.fallback_ms = Some(ocr_start.elapsed().as_millis() as u64);
            if let Ok(outcome) = ocr_result {
                pc_log::info(&format!(
                    "step[{}] ocr direct hit in {}ms (strategy=Ocr)",
                    step.id, outcome.latency_ms
                ));
                return Ok(outcome);
            }
            let ocr_err = ocr_result.err().unwrap_or_else(|| "(no error)".to_string());
            pc_log::warn(&format!(
                "step[{}] ocr direct miss ({}ms, {}) — escalate to VLM",
                step.id,
                timing.fallback_ms.unwrap_or(0),
                ocr_err,
            ));
            return Err(RouterError::StructuredMiss {
                primary: "skipped (strategy=Ocr)".to_string(),
                fallback: ocr_err,
            });
        }

        let domain = domain_for_step(step);

        // ---- 1. Primary (domain-dependent) ---------------------------
        // Desktop → UIA, Web → CDP。不跨域降级。
        let primary_start = Instant::now();
        let primary_result = match domain {
            Domain::Desktop => self.try_uia(step).await,
            Domain::Web => self.try_cdp(step).await,
        };
        timing.primary_ms = Some(primary_start.elapsed().as_millis() as u64);
        if let Ok(outcome) = primary_result {
            pc_log::info(&format!(
                "step[{}] primary hit in {}ms (domain={:?})",
                step.id, outcome.latency_ms, domain
            ));
            return Ok(outcome);
        }
        let primary_err = primary_result.err().unwrap_or_else(|| "(no error)".to_string());

        // ---- 2. OCR fallback (domain-agnostic) ----------------------
        // OCR 是跨域的逐节点补充，不是整体切换自动化类型。
        // Desktop 域：UIA 未命中 → OCR 补充（不走 CDP）
        // Web 域：CDP 未命中 → OCR 补充（不走 UIA）
        let fallback_start = Instant::now();
        let fallback_result = self.try_ocr(step).await;
        timing.fallback_ms = Some(fallback_start.elapsed().as_millis() as u64);
        if let Ok(outcome) = fallback_result {
            pc_log::info(&format!(
                "step[{}] ocr fallback hit in {}ms (primary miss={})",
                step.id, outcome.latency_ms, primary_err
            ));
            return Ok(outcome);
        }
        let fallback_err = fallback_result.err().unwrap_or_else(|| "(no error)".to_string());

        // ---- 3. Both miss — escalate to VLM rescue ----------------
        // VLM rescue 也是逐节点补充，不是整体切换自动化类型。
        pc_log::warn(&format!(
            "step[{}] STRUCTURED miss — primary={}ms ({}) fallback={}ms ({}) — escalate to VLM",
            step.id,
            timing.primary_ms.unwrap_or(0),
            primary_err,
            timing.fallback_ms.unwrap_or(0),
            fallback_err,
        ));
        Err(RouterError::StructuredMiss {
            primary: primary_err,
            fallback: fallback_err,
        })
    }

    async fn try_uia(&self, step: &PcStep) -> Result<StepOutcome, String> {
        // parse 失败时也走坐标 fallback(例如 Coordinate 类型 selector 的 value 是 "x,y",
        // parse_uia_selector 无法识别),避免直接 `?` 短路掉坐标 fallback 链。
        let sel = match parse_uia_selector(&step.primary_selector) {
            Ok(s) => s,
            Err(e) => {
                let start = Instant::now();
                if let Some((x, y)) = step.recorded_coords {
                    cua_click(x, y).await?;
                    return Ok(StepOutcome {
                        strategy_used: StepStrategy::Uia,
                        latency_ms: start.elapsed().as_millis() as u64,
                        action_taken: format!("coord_fallback_click({},{}) [parse_fail:{}]", x, y, e),
                    });
                }
                return Err(format!("parse: {}", e));
            }
        };
        let start = Instant::now();
        match self.uia.find_by(&sel) {
            Ok(Some(node)) => {
                self.uia.click(&node)?;
                Ok(StepOutcome {
                    strategy_used: StepStrategy::Uia,
                    latency_ms: start.elapsed().as_millis() as u64,
                    action_taken: format!("click(name={})", node.name),
                })
            }
            Ok(None) => {
                // UiaSelector find_by 未找到 → 坐标 fallback（enigo 点击）
                // 在进入 OCR/VLM 链之前先尝试录制坐标，避免大开销的截图+AI 推理。
                // 坐标时效性：窗口位移/DPI 缩放可能导致坐标失效，这是 best effort。
                if let Some((x, y)) = step.recorded_coords {
                    cua_click(x, y).await?;
                    Ok(StepOutcome {
                        strategy_used: StepStrategy::Uia,
                        latency_ms: start.elapsed().as_millis() as u64,
                        action_taken: format!("coord_fallback_click({},{})", x, y),
                    })
                } else {
                    Err("uia: not found and no recorded_coords".to_string())
                }
            }
            Err(find_err) => {
                // find_by 出错 → 仍然尝试坐标 fallback，但保留原始错误信息
                // 方便后续 OCR/VLM 链在日志中看到 UIA 层的具体失败原因。
                if let Some((x, y)) = step.recorded_coords {
                    cua_click(x, y).await?;
                    Ok(StepOutcome {
                        strategy_used: StepStrategy::Uia,
                        latency_ms: start.elapsed().as_millis() as u64,
                        action_taken: format!("coord_fallback_click({},{}) [find_err:{}]", x, y, find_err),
                    })
                } else {
                    Err(format!("uia find_by error: {}", find_err))
                }
            }
        }
    }

    async fn try_cdp(&self, step: &PcStep) -> Result<StepOutcome, String> {
        // parse 失败时也走坐标 fallback（与 try_uia 保持一致），
        // 避免 CDP selector 格式错误时直接短路掉坐标 fallback 链。
        let sel = match parse_cdp_selector(&step.primary_selector) {
            Ok(s) => s,
            Err(e) => {
                let start = Instant::now();
                if let Some((x, y)) = step.recorded_coords {
                    cua_click(x, y).await?;
                    return Ok(StepOutcome {
                        strategy_used: StepStrategy::Cdp,
                        latency_ms: start.elapsed().as_millis() as u64,
                        action_taken: format!("coord_fallback_click({},{}) [parse_fail:{}]", x, y, e),
                    });
                }
                return Err(format!("parse: {}", e));
            }
        };
        // sel 会被 move 到 spawn_blocking 闭包，提前 clone 一份用于日志
        let sel_for_log = sel.clone();
        let start = Instant::now();
        // Attach lazily — if a previous step already attached
        // the CdpBackend will reuse the target. This call is a
        // no-op in that case.
        //
        // 注意：`CdpBackend::send` 内部用 `rt.block_on()` 驱动异步 WS。
        // 直接在 async 上下文里调用会 panic
        // ("Cannot block the current thread from within a runtime."),
        // 所以这里必须：
        //   1. 把 `attach_or_launch` 也搬到 spawn_blocking 里(它同样是同步
        //      接口,内部可能也会 block_on);
        //   2. 用 `tokio::task::spawn_blocking` 在专用阻塞线程池执行 send,
        //      避开当前 async 上下文。
        let cdp = self.cdp.clone();
        let result = tokio::task::spawn_blocking(move || {
            cdp.attach_or_launch(None)
                .map_err(|e| format!("attach: {}", e))?;
            cdp.send(CdpAction::Click {
                sel: sel.clone(),
                button: crate::pc_automation::cdp::types::CdpMouseButton::Left,
            })
        })
        .await
        .map_err(|e| format!("spawn_blocking failed: {}", e))??;
        if !result.success {
            // CDP click 失败 → 坐标 fallback（与 try_uia 保持一致）
            // DOM 结构可能已变但视觉位置不变，录制坐标仍是有效的 last-resort。
            let cdp_err = result.error.unwrap_or_else(|| "cdp: click failed".to_string());
            if let Some((x, y)) = step.recorded_coords {
                // 使用 cua_click 保持与其他降级路径一致（Cua Driver 优先，enigo fallback）
                cua_click(x, y).await?;
                return Ok(StepOutcome {
                    strategy_used: StepStrategy::Cdp,
                    latency_ms: start.elapsed().as_millis() as u64,
                    action_taken: format!("coord_fallback_click({},{}) [cdp_fail:{}]", x, y, cdp_err),
                });
            }
            return Err(cdp_err);
        }
        Ok(StepOutcome {
            strategy_used: StepStrategy::Cdp,
            latency_ms: result.latency_ms.max(start.elapsed().as_millis() as u64),
            action_taken: format!("click(css={})", sel_for_log.css.as_deref().unwrap_or("?")),
        })
    }

    async fn try_ocr(&self, step: &PcStep) -> Result<StepOutcome, String> {
        let anchor = parse_ocr_anchor(&step.primary_selector)
            .map_err(|e| format!("parse: {}", e))?;
        let start = Instant::now();
        let m = self
            .ocr
            .locate(&anchor)?
            .ok_or_else(|| "ocr: anchor not found".to_string())?;
        // 之前只构造 "click(...)" 字符串但不真点击,导致 OCR 路径空转。
        // OcrMatch 已携带中心坐标,这里用 enigo 真实点击。
        let cx = m.x + m.w / 2;
        let cy = m.y + m.h / 2;
        cua_click(cx, cy).await?;
        Ok(StepOutcome {
            strategy_used: StepStrategy::Ocr,
            latency_ms: start.elapsed().as_millis() as u64,
            action_taken: format!("click(text={}, conf={:.2})", m.text, m.confidence),
        })
    }
}

/// 在屏幕绝对坐标 (x, y) 执行鼠标左键点击。
///
/// 优先使用 Cua Driver sidecar（后台输入、跨平台），
/// 当 Cua Driver 不可用时降级到 enigo（前台输入）。
///
/// Cua Driver 优势：
///   * 后台输入（PostMessage / CGEventPostToPid / XSendEvent）
///   * 跨平台一致行为
///   * 安全策略 + 会话授权
///
/// enigo fallback：
///   * 前台输入（物理鼠标移动）
///   * 仅作为 last resort，Cua Driver 未安装或崩溃时使用
pub(crate) async fn cua_click(x: i32, y: i32) -> Result<(), String> {
    let cua = CuaDriverClient::shared();
    if cua.is_available() {
        match cua.click(x, y).await {
            Ok(()) => return Ok(()),
            Err(e) => {
                pc_log::warn(&format!(
                    "cua-driver click failed ({}) — falling back to enigo",
                    e
                ));
            }
        }
    }
    // 降级到 enigo
    tokio::task::spawn_blocking(move || enigo_click(x, y))
        .await
        .map_err(|e| format!("join error: {}", e))?
}

/// 用 enigo 在屏幕绝对坐标 (x, y) 执行鼠标左键点击。
/// 逻辑复用 automation/engine.rs::perform_step_input 的 Click 分支。
/// 必须在 spawn_blocking 中调用（enigo 是同步阻塞）。
/// `pub(crate)` 让 executor 模块在 VLM rescue 命中后也能调用此函数执行点击。
pub(crate) fn enigo_click(x: i32, y: i32) -> Result<(), String> {
    use enigo::{Button, Coordinate, Direction, Enigo, Mouse, Settings};
    let mut enigo = Enigo::new(&Settings::default())
        .map_err(|e| format!("初始化输入设备失败: {}", e))?;
    enigo
        .move_mouse(x, y, Coordinate::Abs)
        .map_err(|e| format!("移动鼠标失败: {}", e))?;
    enigo
        .button(Button::Left, Direction::Press)
        .map_err(|e| format!("按下鼠标失败: {}", e))?;
    enigo
        .button(Button::Left, Direction::Release)
        .map_err(|e| format!("释放鼠标失败: {}", e))?;
    Ok(())
}
