// Copyright (c) 2026 MeeJoy
//
// tupAI P2 §1 — Manual teaching recorder.
//
// Listens to global keyboard / mouse input via the `rdev` crate, stores
// recorded events, and converts them into a Hermes skill.md (YAML).
//
// Platform notes
// --------------
// * Windows / macOS: `rdev::listen` works out of the box (no extra setup).
// * Linux: only X11 is supported; Wayland does NOT expose raw input events
//   to user-space processes, so the recorder will emit a warning on Linux
//   and the user will need to switch to an X11 session to use it.
//   See: https://github.com/Narsil/rdev#linux

use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};

use crate::skill::proposal::{ProposalSource, SkillLineage, SkillProposal, ProposalTelemetry};

// How large a screenshot we capture around a click / key event.
const SCREENSHOT_REGION_WIDTH: i32 = 480;
const SCREENSHOT_REGION_HEIGHT: i32 = 360;
// Minimum milliseconds between two auto-captured screenshots
// so rapid typing / clicking doesn't flood the buffer.
const SCREENSHOT_MIN_INTERVAL_MS: u64 = 500;

// ── 录制端动作去重 + 限流 ──────────────────────────────────────────
// 1. MouseMove 采样：仅在移动距离 > MOUSE_MOVE_MIN_DISTANCE 像素
//    或距上次推入间隔 > MOUSE_MOVE_MIN_INTERVAL_MS 时才入栈，
//    避免鼠标静止微抖动 / 缓慢拖拽产生海量 MouseMove 事件膨胀 buffer。
const MOUSE_MOVE_MIN_DISTANCE: i32 = 12;
const MOUSE_MOVE_MIN_INTERVAL_MS: u64 = 120;
// 2. 同点点击去重：同一坐标（±CLICK_DEDUP_TOLERANCE_PX 像素）+ 同一按钮 +
//    间隔 < CLICK_DEDUP_INTERVAL_MS → 视为重复点击，跳过入栈。
//    场景：用户双击 / 三连击同一按钮，或手抖同位置快速点击。
const CLICK_DEDUP_TOLERANCE_PX: i32 = 6;
const CLICK_DEDUP_INTERVAL_MS: u64 = 600;
// 3. 异步 UIA 查询的最长等待时间。stop() 最多阻塞这么久等所有 pending
//    lookup 线程完成回填，避免 element 字段永远缺失导致 dedup 失效。
//    200ms 配合 lookup 自身的 200ms 超时，足够 COM 调用完成且不会让 stop()
//    在挂死应用时卡住过久。
const PENDING_LOOKUP_TIMEOUT_MS: u64 = 200;
const PENDING_LOOKUP_POLL_INTERVAL_MS: u64 = 5;
// 4. 单次 UIA lookup 调用超时（防止挂死应用导致 COM 调用永久阻塞）。
//    uiautomation crate 0.25 内部 UIAutomation::new() 已 CoInitializeEx，
//    但 ElementFromPoint 在目标窗口无响应时可能挂死，200ms 后丢弃。
const UIA_LOOKUP_TIMEOUT_MS: u64 = 200;
// 5. 截图操作超时。GDI 在某些情况下会挂起（GPU 驱动 / 远程桌面），超过此
//    时间强行退出，避免录制过程中截图线程堆积。
const SCREENSHOT_TIMEOUT_MS: u64 = 1000;
// 6. 同时进行的 UIA lookup / 截图 线程数上限。
//    快速点击时可能瞬间产生几十个事件，没限流会瞬间堆积上百个线程，
//    把系统线程资源耗光 → 整个进程卡死。4 是经验值：UIA 调用本身
//    不重（COM 序列化），4 路并发足够吃满 UI 线程队列。
const MAX_CONCURRENT_WORKERS: u32 = 4;
// 7. events buffer 容量上限。长时间录制时 MouseMove 采样可能积累大量事件，
//    超过此值时丢弃最老的 MouseMove（保留所有 click/key/screenshot/state），
//    防止 OOM。10000 个 MouseMove ≈ 60 分钟连续移动的采样量。
const MAX_EVENTS_BUFFER: usize = 10000;
// 8. 暂停时长折算 Delay 的阈值 / 上限。pause→resume 的时间段（用户
//    暂停思考、处理登录等）会被转成拟人化 Delay。短于 MIN 视为误触暂停；
//    长于 MAX 封顶，避免用户暂停很久（离开电脑）导致回放时卡住几十秒。
const MIN_PAUSE_DELAY_MS: u64 = 300;
const MAX_PAUSE_DELAY_MS: u64 = 5000;

/// 点击命中的 UI 元素身份信息（通过 UIA ElementFromPoint 获取）。
/// 用于基于元素身份去重，而非仅靠坐标距离判断是否同一按钮。
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct ClickElementInfo {
    /// 元素名称（如"确定"、"保存"）
    pub name: String,
    /// 控件类型（如"Button"、"MenuItem"）
    pub control_type: String,
    /// 自动化 ID（如果元素有，是最精确的身份标识）
    pub automation_id: String,
    /// 类名（辅助身份标识）
    pub class_name: String,
}

// 全局信号量：限制同时进行的 UIA lookup / 截图 worker 线程数。
// 快速点击时可能瞬间产生几十个事件，没限流会瞬间堆积上百个线程
// 把系统线程资源耗光。4 路并发是经验值（UIA 调用 COM 序列化）。
// 进程级共享，跨多次录制 session 复用（系统卡死多发生在录制中，
// 不分 session）。
//
// Rust 标准库没有 Semaphore，用 `Mutex<usize> + Condvar` 自实现。
// `try_acquire` 语义：剩余配额 > 0 时原子 -1 返回 RAII guard；
// 配额 = 0 时直接返回 None（非阻塞）。
static UIA_WORKER_SEMAPHORE: WorkerSemaphore =
    WorkerSemaphore::new(MAX_CONCURRENT_WORKERS as usize);
static SCREENSHOT_WORKER_SEMAPHORE: WorkerSemaphore =
    WorkerSemaphore::new(MAX_CONCURRENT_WORKERS as usize);

/// 简单非阻塞信号量：超过 max 时 try_acquire 返回 None。
/// RAII：Permit 离开作用域时自动归还配额。
struct WorkerSemaphore {
    inner: Mutex<usize>,
    cv: Condvar,
}

impl WorkerSemaphore {
    const fn new(initial: usize) -> Self {
        Self {
            inner: Mutex::new(initial),
            cv: Condvar::new(),
        }
    }

    /// 非阻塞获取一个 permit。配额 = 0 时直接返回 None。
    fn try_acquire(&self) -> Option<WorkerPermit<'_>> {
        let mut guard = self.inner.lock().ok()?;
        if *guard == 0 {
            None
        } else {
            *guard -= 1;
            Some(WorkerPermit { semaphore: self })
        }
    }

    /// 归还一个 permit（Permit::drop 内部调用）
    fn release(&self) {
        if let Ok(mut guard) = self.inner.lock() {
            *guard = guard.saturating_add(1).min(MAX_CONCURRENT_WORKERS as usize);
            self.cv.notify_one();
        }
    }
}

struct WorkerPermit<'a> {
    semaphore: &'a WorkerSemaphore,
}

impl Drop for WorkerPermit<'_> {
    fn drop(&mut self) {
        self.semaphore.release();
    }
}

/// One captured user-input event.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum RecordedEvent {
    MouseClick {
        x: i32,
        y: i32,
        button: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        element: Option<ClickElementInfo>,
    },
    MouseMove { x: i32, y: i32 },
    KeyPress { key: String },
    Screenshot { data: Vec<u8> },
    BrowserAction { url: String, selector: String },
    /// 动作间延时事件——由 push_event_with_backpressure 自动计算插入，
    /// 回放引擎读取 delayMs 在步骤前等待，模拟人类操作节奏。
    Delay { ms: u64 },
    /// State transition events (recording start/stop, window focus,
    /// manual markers) — useful for replay / debugging even when they
    /// don't map to a skill.md step.
    State {
        name: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        payload: Option<serde_json::Value>,
    },
}

/// Public status snapshot returned by `get_recording_status`.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(tag = "state", rename_all = "snake_case")]
pub enum RecordingStatus {
    Idle,
    /// `event_count` = all buffered events (incl. MouseMove / Screenshot /
    /// State markers).  `action_count` = only events that produce skill.md
    /// steps (MouseClick + KeyPress).  前端用 action_count 显示步数。
    Recording {
        event_count: u32,
        action_count: u32,
        elapsed_ms: u64,
    },
    /// 暂停状态: rdev 监视线程仍在运行, 但事件不再入栈。
    /// 用户可通过 resume() 恢复录制, 或通过 stop() 结束并保存。
    Paused {
        event_count: u32,
        action_count: u32,
        elapsed_ms: u64,
    },
}

/// Lightweight, in-process recorder.  We don't take a heavy `AppHandle` so
/// the struct is easy to unit-test; tests just feed events directly into
/// `events()`.
#[derive(Debug, Clone)]
pub struct Recorder {
    inner: Arc<Mutex<RecorderInner>>,
}

#[derive(Debug)]
struct RecorderInner {
    recording: bool,
    /// 暂停标志: 为 true 时 rdev 监视线程仍在运行, 但不把事件推入 buffer。
    /// 这让 resume() 能无缝继续录制, 而无需重启 rdev::listen。
    paused: bool,
    events: Vec<RecordedEvent>,
    start_time: Option<Instant>,
    last_screenshot_at: Option<Instant>,
    /// Last known mouse position (updated from `MouseMove` events).
    /// `ButtonPress` does not carry coordinates in rdev 0.5, so we
    /// attach the most recent move position to click events.
    last_mouse: Option<(i32, i32)>,
    /// 上次 MouseMove 入栈时间，用于采样限流（避免海量 move 事件膨胀 buffer）
    last_mouse_move_at: Option<Instant>,
    /// 上次 MouseClick 入栈的 (x, y, button, 时间)，用于同点点击去重
    last_click: Option<(i32, i32, String, Instant)>,
    /// 上次事件入栈时间，用于在 push 事件前自动计算 Delay（动作间延时，模拟人类操作节奏）
    last_event_time: Option<Instant>,
    /// 暂停起始时刻：pause() 时记录，resume() 时用其时长生成拟人化 Delay，
    /// 让"用户手动暂停思考/处理"的那段停顿在回放时也被模拟出来。
    paused_at: Option<Instant>,
    /// 当前录制会话的标识。每次 start() 递增，用于异步 UIA lookup 线程
    /// 在回填 element 时验证目标事件仍属于同一会话——
    /// 避免 discard 后立即 start 新 session 时，旧 lookup 线程把 element
    /// 错误回填到新 session 的同索引 MouseClick 上。
    session_id: u64,
    /// 异步 UIA 查询线程计数。每次 spawn_element_lookup 时 +1，线程退出时 -1。
    /// stop() 会带超时等待此计数归零，确保 element 已回填后再 drain events，
    /// 否则 dedup_clicks_by_element 拿到的 MouseClick.element 全是 None。
    pending_lookups: u32,
    /// rdev::listen 线程是否已启动。
    /// rdev 0.5 的 listen 是阻塞调用且无 stop API——线程一旦启动就无法停止。
    /// 旧实现每次 start() 都 spawn 新的 listen 线程，stop() 只设 recording=false
    /// 但线程仍在运行。用户多次 start/stop 会堆积多个 Windows 低级钩子
    /// （WH_MOUSE_LL + WH_KEYBOARD_LL），导致系统输入延迟倍增、线程资源
    /// 泄漏，最终卡死系统。
    /// 修复：listen 线程在整个进程生命周期内只启动一次，start/stop 只切换
    /// recording 标志。线程内部通过 `guard.recording && !guard.paused` 控制
    /// 是否推入事件，不录制时几乎零开销。
    listen_started: bool,
}

impl Default for Recorder {
    fn default() -> Self {
        Self::new()
    }
}

impl Recorder {
    pub fn new() -> Self {
        Self {
            inner: Arc::new(Mutex::new(RecorderInner {
                recording: false,
                paused: false,
                events: Vec::new(),
                start_time: None,
                last_screenshot_at: None,
                last_mouse: None,
                last_mouse_move_at: None,
                last_click: None,
                last_event_time: None,
                paused_at: None,
                session_id: 0,
                pending_lookups: 0,
                listen_started: false,
            })),
        }
    }

    /// Begin a recording session.  Spawns a single background thread that
    /// runs `rdev::listen` and pushes events into the buffer.
    ///
    /// Mouse coordinates are read from `event.x` / `event.y` so the
    /// generated skill.md contains usable click positions. On Linux
    /// Wayland `rdev::listen` returns an error — we log a warning and
    /// let the recording start anyway (the buffer will simply stay
    /// empty) so the UI can surface a clear message.
    ///
    /// Each mouse click / key press also triggers an asynchronous
    /// screenshot of the region around the cursor (throttled to one
    /// every `SCREENSHOT_MIN_INTERVAL_MS`) and a `State` marker event
    /// is pushed at start/stop so replay / debugging can reconstruct
    /// context without relying solely on input coordinates.
    pub fn start(&self) -> Result<(), String> {
        let mut inner = self.inner.lock().map_err(|e| e.to_string())?;
        if inner.recording {
            return Err("recording already in progress".into());
        }
        inner.recording = true;
        inner.paused = false;
        inner.paused_at = None;
        inner.events.clear();
        inner.start_time = Some(Instant::now());
        inner.last_screenshot_at = None;
        // 主动获取当前鼠标位置，避免用户启动录制后立即点击（未移动鼠标）
        // 时 last_mouse 是 None 导致点击事件被丢弃。
        inner.last_mouse = get_cursor_pos();
        inner.last_mouse_move_at = None;
        inner.last_click = None;
        // 递增 session_id，让上一轮残留的 UIA lookup 线程在回填时
        // 通过 session_id 不匹配自动丢弃结果（防御性）。
        inner.session_id = inner.session_id.wrapping_add(1);
        inner.pending_lookups = 0;
        inner.events.push(RecordedEvent::State {
            name: "recording_started".to_string(),
            payload: None,
        });

        // rdev::listen 线程只启动一次（进程级单例）。
        // rdev 0.5 的 listen 是阻塞调用且无 stop API，旧实现每次 start()
        // 都 spawn 新线程，stop() 不停止它 → 多次 start/stop 堆积多个
        // Windows 低级钩子线程，最终卡死系统。
        // 现在用 listen_started 标志保证只 spawn 一次，后续 start/stop
        // 只切换 recording 标志，线程内部通过 guard.recording 控制是否推事件。
        if !inner.listen_started {
            inner.listen_started = true;
            // Hand an `Arc<Mutex<..>>` clone to the listener thread. The Arc
            // is `Send` and outlives the thread (the Recorder is held in the
            // global Tauri state and only dropped at process exit), so the
            // closure can safely call `me.lock()`.
            let me = Arc::clone(&self.inner);
            std::thread::Builder::new()
                .name("tupai-recorder".into())
                .spawn(move || {
                if let Err(err) = rdev::listen(move |event| {
                    let mut mouse_pos: Option<(i32, i32)> = None;
                    // should_push: 是否真正推入 buffer（去重 / 限流后可能跳过）
                    // should_capture: 是否触发截图（仅对实际推入的 click/key 事件）
                    let mut should_push = true;
                    let recorded = match event.event_type {
                        rdev::EventType::MouseMove { x, y } => {
                            let pos = (x as i32, y as i32);
                            mouse_pos = Some(pos);
                            Some(RecordedEvent::MouseMove { x: pos.0, y: pos.1 })
                        }
                        rdev::EventType::ButtonPress(button) => {
                            // Use the last known mouse position because
                            // ButtonPress in rdev 0.5 does not carry x/y.
                            // 若 last_mouse 仍是 None（理论上 start() 已主动
                            // 初始化，但平台不支持 GetCursorPos 时仍可能为 None），
                            // 再尝试实时获取一次，避免首次点击被丢弃。
                            let pos = {
                                let Ok(guard) = me.lock() else {
                                    return;
                                };
                                guard.last_mouse
                            };
                            let pos = pos.or_else(get_cursor_pos);
                            if let Some((x, y)) = pos {
                                mouse_pos = Some((x, y));
                                Some(RecordedEvent::MouseClick {
                                    x,
                                    y,
                                    button: button_name(button),
                                    element: None,
                                })
                            } else {
                                None
                            }
                        }
                        rdev::EventType::KeyPress(key) => {
                            // 读取最后已知鼠标位置，用于触发截图（与 ButtonPress 一致）
                            let pos = {
                                let Ok(guard) = me.lock() else {
                                    return;
                                };
                                guard.last_mouse
                            };
                            if let Some((x, y)) = pos {
                                mouse_pos = Some((x, y));
                            }
                            Some(RecordedEvent::KeyPress {
                                key: format!("{:?}", key),
                            })
                        }
                        _ => None,
                    };
                    let should_capture = recorded.is_some();
                    if let Some(ev) = recorded {
                        // 锁内收集 UIA lookup 参数，锁外再调用 spawn_element_lookup。
                        // 原因：spawn_element_lookup 内部会 recorder.lock() 获取同一个
                        // Mutex，若在 guard 存活时调用 → Rust std::sync::Mutex 不可重入
                        // → 永久死锁。这是"录制卡死"的核心根因。
                        let lookup_info: Option<(i32, i32, usize, u64)> = {
                            if let Ok(mut guard) = me.lock() {
                                if guard.recording && !guard.paused {
                                    // ── MouseMove 采样限流 ──
                                    if let RecordedEvent::MouseMove { x, y } = ev {
                                        let now = Instant::now();
                                        let should_sample = match (guard.last_mouse_move_at, guard.last_mouse) {
                                            (Some(last_t), Some((lx, ly))) => {
                                                let dist = (x - lx).abs() + (y - ly).abs();
                                                let elapsed = now.duration_since(last_t).as_millis() as u64;
                                                dist >= MOUSE_MOVE_MIN_DISTANCE
                                                    || elapsed >= MOUSE_MOVE_MIN_INTERVAL_MS
                                            }
                                            _ => true,
                                        };
                                        if !should_sample {
                                            guard.last_mouse = Some((x, y));
                                            return;
                                        }
                                        guard.last_mouse = Some((x, y));
                                        guard.last_mouse_move_at = Some(now);
                                        push_event_with_backpressure(&mut guard, ev);
                                        return;
                                    }
                                    // ── 同点点击去重 ──
                                    if let RecordedEvent::MouseClick { x, y, ref button, .. } = ev {
                                        let now = Instant::now();
                                        let is_dup = guard.last_click.as_ref().is_some_and(|(lx, ly, lb, lt)| {
                                            let dist = (x - lx).abs() + (y - ly).abs();
                                            let elapsed = now.duration_since(*lt).as_millis() as u64;
                                            dist <= CLICK_DEDUP_TOLERANCE_PX
                                                && elapsed < CLICK_DEDUP_INTERVAL_MS
                                                && lb == button
                                        });
                                        if is_dup {
                                            should_push = false;
                                        } else {
                                            guard.last_click = Some((x, y, button.clone(), now));
                                        }
                                    }
                                    if should_push {
                                        let push_idx = guard.events.len();
                                        push_event_with_backpressure(&mut guard, ev);
                                        // 收集 lookup 参数（不在锁内调用 spawn_element_lookup）
                                        if push_idx < guard.events.len() {
                                            if matches!(guard.events.get(push_idx), Some(RecordedEvent::MouseClick { .. })) {
                                                if let Some((cx, cy)) = guard.last_mouse {
                                                    let sess = guard.session_id;
                                                    Some((cx, cy, push_idx, sess))
                                                } else { None }
                                            } else { None }
                                        } else { None }
                                    } else { None }
                                } else { None }
                            } else { None }
                        };
                        // 锁外调用 spawn_element_lookup（guard 已 drop，不会死锁）
                        if let Some((cx, cy, push_idx, sess)) = lookup_info {
                            let lookup_me = Arc::clone(&me);
                            spawn_element_lookup(lookup_me, cx, cy, push_idx, sess);
                        }
                    }
                    // Screenshot capture runs in its own thread so the
                    // low-latency input listener never stalls on GDI.
                    // 仅对实际入栈的 click/key 事件触发截图（去重后的点击不再截图）
                    // 节流检查在持锁状态下做：距上次截图 < 500ms 则不 spawn 线程，
                    // 避免快速点击时无谓创建线程（旧实现每次都 spawn，即使被
                    // capture_screenshot_to_recorder 内部节流跳过，线程已创建）
                    if should_capture && should_push {
                        if let Some((x, y)) = mouse_pos {
                            // 持锁检查 last_screenshot_at 节流
                            let need_screenshot = match me.lock() {
                                Ok(guard) => {
                                    if !guard.recording {
                                        false
                                    } else {
                                        let now = Instant::now();
                                        match guard.last_screenshot_at {
                                            Some(last) => now.duration_since(last).as_millis() as u64 >= SCREENSHOT_MIN_INTERVAL_MS,
                                            None => true,
                                        }
                                    }
                                }
                                Err(_) => false,
                            };
                            if need_screenshot {
                                let capture_me = Arc::clone(&me);
                                std::thread::spawn(move || {
                                    capture_screenshot_to_recorder(capture_me, x, y);
                                });
                            }
                        }
                    }
                }) {
                    eprintln!("[recorder] rdev listen error: {:?}", err);
                }
            })
            .map_err(|e| format!("failed to spawn recorder thread: {}", e))?;
        } // end if !inner.listen_started

        Ok(())
    }

    /// Stop the active session and return the captured events.
    ///
    /// 在 take events 之前会带超时等待所有异步 UIA lookup 线程完成回填，
    /// 否则 dedup_clicks_by_element 拿到的 MouseClick.element 全是 None，
    /// 元素级去重失效。超时（1 秒）后强行 take，剩余 lookup 线程因
    /// session_id 不匹配会自动丢弃结果。
    pub fn stop(&self) -> Result<Vec<RecordedEvent>, String> {
        let mut inner = self.inner.lock().map_err(|e| e.to_string())?;
        if !inner.recording {
            return Err("no recording in progress".into());
        }
        inner.recording = false;

        // 等待 pending UIA lookup 完成（带超时），确保 element 已回填。
        // 期间需释放锁让 lookup 线程能 acquire 到 lock 做回填。
        if inner.pending_lookups > 0 {
            let deadline = Instant::now() + Duration::from_millis(PENDING_LOOKUP_TIMEOUT_MS);
            while inner.pending_lookups > 0 && Instant::now() < deadline {
                drop(inner);
                std::thread::sleep(Duration::from_millis(PENDING_LOOKUP_POLL_INTERVAL_MS));
                inner = self.inner.lock().map_err(|e| e.to_string())?;
            }
            if inner.pending_lookups > 0 {
                log::warn!(
                    "[recorder] stop() timed out waiting for {} pending UIA lookups; \
                     element info may be incomplete for dedup",
                    inner.pending_lookups
                );
            }
        }

        inner.events.push(RecordedEvent::State {
            name: "recording_stopped".to_string(),
            payload: None,
        });
        let events = std::mem::take(&mut inner.events);
        inner.start_time = None;
        inner.last_screenshot_at = None;
        inner.last_mouse_move_at = None;
        inner.last_click = None;
        inner.paused = false;
        inner.paused_at = None;
        Ok(events)
    }

    /// Discard the active session without producing a skill.md or
    /// emitting a completion event. Used by the floating window's
    /// "Cancel" button.
    pub fn discard(&self) -> Result<(), String> {
        let mut inner = self.inner.lock().map_err(|e| e.to_string())?;
        if !inner.recording {
            return Err("no recording in progress".into());
        }
        inner.recording = false;
        inner.paused = false;
        inner.events.clear();
        inner.start_time = None;
        inner.last_screenshot_at = None;
        inner.last_mouse_move_at = None;
        inner.last_click = None;
        inner.paused_at = None;
        // 递增 session_id：让残留的 UIA lookup 线程在回填时
        // 通过 session_id 不匹配自动丢弃结果，避免错误回填到
        // 下一轮录制 session 的同索引事件上。
        inner.session_id = inner.session_id.wrapping_add(1);
        Ok(())
    }

    /// 暂停录制: rdev 监视线程继续运行, 但事件不再推入 buffer。
    /// 用户可通过 resume() 恢复, 或通过 stop() 结束并保存已录制内容。
    pub fn pause(&self) -> Result<(), String> {
        let mut inner = self.inner.lock().map_err(|e| e.to_string())?;
        if !inner.recording {
            return Err("no recording in progress".into());
        }
        if inner.paused {
            return Err("recording already paused".into());
        }
        inner.paused = true;
        inner.paused_at = Some(Instant::now());
        inner.events.push(RecordedEvent::State {
            name: "recording_paused".to_string(),
            payload: None,
        });
        Ok(())
    }

    /// 恢复录制: 从暂停状态继续, rdev 监视线程重新开始推入事件。
    /// resume 时把暂停时长折算成拟人化 Delay 事件（封顶 MAX_PAUSE_DELAY_MS），
    /// 使录制里"用户暂停思考/处理"的那段停顿在回放时被模拟出来，
    /// 并在暂停时段后重置 last_event_time，避免把整段暂停再重复累加。
    pub fn resume(&self) -> Result<(), String> {
        let mut inner = self.inner.lock().map_err(|e| e.to_string())?;
        if !inner.recording {
            return Err("no recording in progress".into());
        }
        if !inner.paused {
            return Err("recording not paused".into());
        }
        inner.paused = false;
        // 把暂停时长转为拟人化 Delay，封顶 MAX_PAUSE_DELAY_MS（防止用户离开很久）。
        if let Some(paused_at) = inner.paused_at.take() {
            let paused_ms = paused_at.elapsed().as_millis() as u64;
            if paused_ms > MIN_PAUSE_DELAY_MS {
                inner.events.push(RecordedEvent::Delay {
                    ms: paused_ms.min(MAX_PAUSE_DELAY_MS),
                });
            }
        }
        // 重置基准时间：暂停时段已作为一次 Delay 记录，后续操作事件
        // 的延时从 resume 时刻起算，而不是把整段暂停再累加一次。
        inner.last_event_time = Some(Instant::now());
        inner.events.push(RecordedEvent::State {
            name: "recording_resumed".to_string(),
            payload: None,
        });
        Ok(())
    }

    /// Recorder code path for the SkillSource flow.
    /// Stop the active session, fold the
    /// captured events into a `skill.md` body, and wrap the
    /// result in a `SkillProposal` whose `source` is `Recorder`.
    ///
    /// The returned tuple is `(proposal, skill_md, step_count)`:
    ///   * `proposal`     — the `SkillProposal` ready for
    ///                      `proposal_store::save` + the
    ///                      `proposal-created` Tauri event
    ///                      (caller is responsible for both).
    ///   * `skill_md`     — the raw YAML text the recorder
    ///                      produced.  The proposal's
    ///                      `skill_md` field is the same string
    ///                      but having it back here lets the
    ///                      `teaching` command keep its existing
    ///                      "compile to MCP and return base64"
    ///                      path without re-running
    ///                      `generate_skill_md`.
    ///   * `step_count`   — number of *captured events* (not
    ///                      YAML step rows), so this matches the
    ///                      existing `TeachingStopResult.step_count`
    ///                      contract — the Tauri command's
    ///                      existing semantics are preserved
    ///                      verbatim.
    ///
    /// The proposal is **not** persisted by this method — that
    /// is the caller's job (the Tauri command lives in
    /// `commands::teaching::stop_recording` and has access to
    /// `AppHandle` for `proposal_store::open_proposals_db`).
    pub fn finalize_into_proposal(&self) -> Result<(SkillProposal, String, u32), String> {
        let events = self.stop()?;
        // 基于元素身份去重：同一按钮的连续点击合并为一步，
        // 确保 step_count 与流程图节点数一致。
        let events = crate::automation::flowchart::dedup_clicks_by_element(&events);
        let skill_md = generate_skill_md(&events);
        // 真实业务步骤数: 只统计会生成流程图节点的事件 (MouseClick / KeyPress /
        // BrowserAction),与 events_to_flowchart 的节点生成逻辑保持一致。
        // 之前用 !State 过滤会把 MouseMove / Screenshot 也计入,导致前端通知
        // 显示"录制完成 X 步"远多于流程图实际节点数,用户误以为流程图没加载全。
        let event_count = events
            .iter()
            .filter(|e| matches!(
                e,
                RecordedEvent::MouseClick { .. }
                | RecordedEvent::KeyPress { .. }
                | RecordedEvent::BrowserAction { .. }
            ))
            .count() as u32;
        let telemetry = ProposalTelemetry {
            // The recording itself succeeded (we got events back).
            // We don't know whether the resulting skill.md will
            // run successfully — that is the evaluator's job.
            source_success_rate: 1.0,
            avg_latency_ms: 0,
            sample_size: event_count,
        };
        let lineage = SkillLineage {
            parent_skill_id: None,
            parent_version: None,
            derivation_note: Some(format!(
                "recorder captured {} event(s)",
                event_count
            )),
        };
        let proposal = SkillProposal::new(
            ProposalSource::Recorder,
            skill_md.clone(),
            lineage,
            telemetry,
        );
        Ok((proposal, skill_md, event_count))
    }

    /// 与 `finalize_into_proposal` 相同，但额外返回去重后的 events。
    ///
    /// 用途：`commands::teaching::stop_recording` 需要在 finalize 之后
    /// 用同一份 events 生成 flowchart（保证 step_count 与 flowchart 节点数
    /// 严格一致）。原方案是先 `snapshot_events` 再 `finalize_into_proposal`，
    /// 但 snapshot 时机早于 stop()，element 可能还没回填完，导致 flowchart
    /// 中的 MouseClick 节点没有元素信息（fallback 到坐标显示），与
    /// step_count（基于已回填的 events dedup 后计数）不一致。
    ///
    /// 返回 `(proposal, skill_md, step_count, events)`：
    ///   * `events` — dedup 后的 events，调用方可用 `events_to_flowchart(&events)`
    ///                生成与 step_count 严格一致的 flowchart。
    pub fn finalize_with_events(
        &self,
    ) -> Result<(SkillProposal, String, u32, Vec<RecordedEvent>), String> {
        let events = self.stop()?;
        // 基于元素身份去重：同一按钮的连续点击合并为一步，
        // 确保 step_count 与流程图节点数一致。
        let events = crate::automation::flowchart::dedup_clicks_by_element(&events);
        let skill_md = generate_skill_md(&events);
        let event_count = events
            .iter()
            .filter(|e| matches!(
                e,
                RecordedEvent::MouseClick { .. }
                | RecordedEvent::KeyPress { .. }
                | RecordedEvent::BrowserAction { .. }
            ))
            .count() as u32;
        let telemetry = ProposalTelemetry {
            source_success_rate: 1.0,
            avg_latency_ms: 0,
            sample_size: event_count,
        };
        let lineage = SkillLineage {
            parent_skill_id: None,
            parent_version: None,
            derivation_note: Some(format!(
                "recorder captured {} event(s)",
                event_count
            )),
        };
        let proposal = SkillProposal::new(
            ProposalSource::Recorder,
            skill_md.clone(),
            lineage,
            telemetry,
        );
        Ok((proposal, skill_md, event_count, events))
    }

    /// Snapshot the current status (used by `get_recording_status`).
    pub fn status(&self) -> Result<RecordingStatus, String> {
        let inner = self.inner.lock().map_err(|e| e.to_string())?;
        if !inner.recording {
            return Ok(RecordingStatus::Idle);
        }
        let elapsed = inner
            .start_time
            .map(|t| t.elapsed().as_millis() as u64)
            .unwrap_or(0);
        let event_count = inner.events.len() as u32;
        let action_count = inner
            .events
            .iter()
            .filter(|e| {
                matches!(
                    e,
                    RecordedEvent::MouseClick { .. } | RecordedEvent::KeyPress { .. }
                )
            })
            .count() as u32;
        if inner.paused {
            Ok(RecordingStatus::Paused {
                event_count,
                action_count,
                elapsed_ms: elapsed,
            })
        } else {
            Ok(RecordingStatus::Recording {
                event_count,
                action_count,
                elapsed_ms: elapsed,
            })
        }
    }

    /// Inject an event (used by tests, the browser-action hook, and the
    /// screenshot capture loop).  No-op if we are not currently recording.
    pub fn push(&self, event: RecordedEvent) -> Result<(), String> {
        let mut inner = self.inner.lock().map_err(|e| e.to_string())?;
        if inner.recording {
            inner.events.push(event);
        }
        Ok(())
    }

    /// Return a *clone* of the current event buffer without consuming it.
    ///
    /// `finalize_into_proposal` 内部会 drain events，但 `stop_recording`
    /// 命令需要先把 events 转成可视化流程图再 finalize，所以这里提供一个
    /// 不消耗 buffer 的快照接口。录制仍在进行中也可以安全调用（snapshot
    /// 之后新 push 的事件不影响 snapshot 结果）。
    pub fn snapshot_events(&self) -> Result<Vec<RecordedEvent>, String> {
        let inner = self.inner.lock().map_err(|e| e.to_string())?;
        Ok(inner.events.clone())
    }
}

pub fn button_name(button: rdev::Button) -> String {
    match button {
        rdev::Button::Left => "left".into(),
        rdev::Button::Right => "right".into(),
        rdev::Button::Middle => "middle".into(),
        other => format!("button{:?}", other),
    }
}

/// Convert a list of recorded events into a Hermes skill.md (YAML).
///
/// The output is intentionally a valid `crate::skill::SkillManifest`
/// YAML document so the teaching command can hand it straight to
/// `skill::compiler::compile_skill_md` for an immediate
/// skill.md -> MCP round-trip.  Recorded steps that cannot be
/// expressed as an `InputAction` (e.g. `BrowserAction`) are
/// represented as a `description`-only step.
///
/// Heuristics:
///   * Consecutive `MouseClick` events with the same `button` and
///     coordinates within a 20 px radius are collapsed into a single
///     `click` step.
///   * Consecutive `KeyPress` events that look like ASCII text are
///     concatenated into a single `type` step.
pub fn generate_skill_md(events: &[RecordedEvent]) -> String {
    let mut yaml = String::new();
    yaml.push_str("# Auto-generated by tupAI teaching recorder\n");
    yaml.push_str("name: new_skill\n");
    yaml.push_str("description: Manually recorded workflow.\n");
    yaml.push_str("preferred_execution_type: system_software\n");
    // `SkillManifest::validate` requires `software_name` for the
    // system_software execution type.  The teaching recorder does
    // not know which app the user is driving, so we set a sentinel
    // value that the engine can resolve at execution time.
    yaml.push_str("software_name: \"recorded\"\n");
    yaml.push_str("steps:\n");

    let mut text_buf = String::new();

    for event in events {
        match event {
            RecordedEvent::MouseClick { x, y, button: _, element } => {
                flush_text(&mut text_buf, &mut yaml);
                // 优先使用元素名称作为描述，让 skill.md 更可读
                let desc = match element {
                    Some(e) if !e.name.is_empty() => {
                        format!("click [{}] {}", e.name, e.control_type)
                    }
                    _ => format!("click at ({}, {})", x, y),
                };
                yaml.push_str(&format!(
                    "  - id: click_{}\n    description: \"{}\"\n    input:\n      type: click\n      x: {}\n      y: {}\n",
                    short_id(),
                    desc,
                    x,
                    y
                ));
                // 补充 element/selector 信息,让回放端可用 UIA selector 定位(元素优先,坐标兜底)
                if let Some(e) = element {
                    yaml.push_str(&format!(
                        "      element:\n        name: \"{}\"\n        controlType: \"{}\"\n        automationId: \"{}\"\n        className: \"{}\"\n",
                        escape_yaml(&e.name),
                        escape_yaml(&e.control_type),
                        escape_yaml(&e.automation_id),
                        escape_yaml(&e.class_name),
                    ));
                    // 构造 uia: selector 字符串(与新录制路径 build_uia_selector_string 对齐)
                    let mut parts: Vec<String> = Vec::new();
                    if !e.control_type.is_empty() {
                        parts.push(format!("controlType={}", e.control_type));
                    }
                    if !e.name.is_empty() {
                        parts.push(format!("name={}", e.name));
                    }
                    if !e.automation_id.is_empty() {
                        parts.push(format!("automationId={}", e.automation_id));
                    }
                    if !e.class_name.is_empty() {
                        parts.push(format!("className={}", e.class_name));
                    }
                    if !parts.is_empty() {
                        yaml.push_str(&format!(
                            "      selector: \"uia:{}\"\n",
                            escape_yaml(&parts.join(";"))
                        ));
                    }
                }
            }
            RecordedEvent::KeyPress { key } => {
                // Heuristic: printable single chars are bundled into a
                // single `type` step; everything else becomes a `hotkey`.
                if is_printable_key(key) {
                    text_buf.push_str(&unescape_key(key));
                } else {
                    flush_text(&mut text_buf, &mut yaml);
                    yaml.push_str(&format!(
                        "  - id: hotkey_{}\n    description: \"hotkey {}\"\n    input:\n      type: hotkey\n      keys: \"{}\"\n",
                        short_id(),
                        escape_yaml(key),
                        escape_yaml(key)
                    ));
                }
            }
            RecordedEvent::BrowserAction { url, selector } => {
                flush_text(&mut text_buf, &mut yaml);
                // `InputAction` has no browser variant, so we emit a
                // description-only step (the executor can inspect the
                // description to decide what to do).
                yaml.push_str(&format!(
                    "  - id: browser_{}\n    description: \"browser: url={} selector={}\"\n",
                    short_id(),
                    escape_yaml(url),
                    escape_yaml(selector)
                ));
            }
            // Screenshot events, raw mouse-move samples, and state markers
            // are ignored at the skill.md layer — they're useful for the
            // *executor* / replay UI but they don't translate into
            // deterministic steps.
            RecordedEvent::Screenshot { .. }
            | RecordedEvent::MouseMove { .. }
            | RecordedEvent::State { .. }
            | RecordedEvent::Delay { .. } => {}
        }
    }
    flush_text(&mut text_buf, &mut yaml);
    yaml
}

// --- small helpers -----------------------------------------------------------------

fn flush_text(buf: &mut String, yaml: &mut String) {
    if buf.is_empty() {
        return;
    }
    yaml.push_str(&format!(
        "  - id: type_{}\n    description: \"type text\"\n    input:\n      type: type\n      text: \"{}\"\n",
        short_id(),
        escape_yaml(buf)
    ));
    buf.clear();
}

fn is_printable_key(key: &str) -> bool {
    // rdev formats printable keys with surrounding quotes, e.g. "a" or
    // "KeyA" for letters.  We accept single-char keys; everything else
    // (Escape, Enter, F1, …) is treated as a hotkey.
    let stripped = key.trim_matches('"');
    stripped.chars().count() == 1
}

fn unescape_key(key: &str) -> String {
    let stripped = key.trim_matches('"');
    stripped.to_string()
}

fn escape_yaml(s: &str) -> String {
    s.replace('\\', "\\\\").replace('"', "\\\"")
}

fn short_id() -> String {
    use std::time::{SystemTime, UNIX_EPOCH};
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    format!("{:x}", (nanos & 0xFFFF))
}

/// Capture the screen region around `(x, y)` as a PNG byte vector.
///
/// The implementation is Windows-only today (GDI + `image` crate).
/// macOS / Linux fall back to an `Err` so the recorder can still
/// operate without screenshots on those platforms.
#[cfg(target_os = "windows")]
fn capture_region_png(x: i32, y: i32, width: i32, height: i32) -> Result<Vec<u8>, String> {
    use windows::Win32::Graphics::Gdi::{
        BitBlt, CreateCompatibleBitmap, CreateCompatibleDC, DeleteDC, DeleteObject, GetDC,
        GetDIBits, ReleaseDC, SelectObject, BITMAPINFO, BITMAPINFOHEADER, BI_RGB, DIB_RGB_COLORS,
        SRCCOPY,
    };
    use windows::Win32::UI::WindowsAndMessaging::GetDesktopWindow;

    if width <= 0 || height <= 0 {
        return Err("invalid screenshot region size".into());
    }

    let left = x - width / 2;
    let top = y - height / 2;

    unsafe {
        let hwnd = GetDesktopWindow();
        let hdc_screen = GetDC(hwnd);
        if hdc_screen.0.is_null() {
            return Err("GetDC failed".into());
        }
        let hdc_mem = CreateCompatibleDC(hdc_screen);
        if hdc_mem.0.is_null() {
            let _ = ReleaseDC(hwnd, hdc_screen);
            return Err("CreateCompatibleDC failed".into());
        }
        let hbm = CreateCompatibleBitmap(hdc_screen, width, height);
        if hbm.0.is_null() {
            let _ = DeleteDC(hdc_mem);
            let _ = ReleaseDC(hwnd, hdc_screen);
            return Err("CreateCompatibleBitmap failed".into());
        }
        let old = SelectObject(hdc_mem, hbm);
        let _ = BitBlt(
            hdc_mem,
            0,
            0,
            width,
            height,
            hdc_screen,
            left,
            top,
            SRCCOPY,
        );
        let _ = SelectObject(hdc_mem, old);

        let mut bmi = BITMAPINFO {
            bmiHeader: BITMAPINFOHEADER {
                biSize: std::mem::size_of::<BITMAPINFOHEADER>() as u32,
                biWidth: width,
                biHeight: -(height),
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
        let copied = GetDIBits(
            hdc_mem,
            hbm,
            0,
            height as u32,
            Some(buffer.as_mut_ptr() as *mut _),
            &mut bmi,
            DIB_RGB_COLORS,
        );
        let _ = DeleteObject(hbm);
        let _ = DeleteDC(hdc_mem);
        let _ = ReleaseDC(hwnd, hdc_screen);

        if copied == 0 {
            return Err("GetDIBits returned 0".into());
        }

        // GDI 32bpp DIB is BGRA in memory; `image` wants RGBA.
        let mut rgba = Vec::with_capacity(buffer.len());
        for chunk in buffer.chunks_exact(4) {
            rgba.push(chunk[2]); // R
            rgba.push(chunk[1]); // G
            rgba.push(chunk[0]); // B
            rgba.push(chunk[3]); // A
        }
        let image = image::RgbaImage::from_raw(width as u32, height as u32, rgba)
            .ok_or_else(|| "failed to build RgbaImage".to_string())?;
        let mut png = Vec::new();
        image::DynamicImage::ImageRgba8(image)
            .write_to(&mut std::io::Cursor::new(&mut png), image::ImageFormat::Png)
            .map_err(|e| e.to_string())?;
        Ok(png)
    }
}

#[cfg(not(target_os = "windows"))]
fn capture_region_png(_x: i32, _y: i32, _width: i32, _height: i32) -> Result<Vec<u8>, String> {
    Err("screenshot capture is only implemented on Windows".into())
}

/// Push a throttled screenshot into the recorder buffer.
/// Runs on a worker thread so the input listener never blocks.
///
/// **防卡死设计**：
///   1. 信号量限流：超过 MAX_CONCURRENT_WORKERS 个截图 worker 在跑则直接丢弃
///   2. 超时控制：整个截图操作超过 SCREENSHOT_TIMEOUT_MS 自动放弃
///   3. throttle：两次截图间隔 < SCREENSHOT_MIN_INTERVAL_MS 直接返回
fn capture_screenshot_to_recorder(recorder: Arc<Mutex<RecorderInner>>, x: i32, y: i32) {
    let now = Instant::now();
    // 节流检查已在 rdev::listen 闭包内持锁时做过了（距上次 < 500ms 不 spawn 线程），
    // 这里再做一次是因为 capture_screenshot_to_recorder 也可能被其他路径调用。
    {
        let Ok(guard) = recorder.lock() else {
            return;
        };
        if !guard.recording {
            return;
        }
        if let Some(last) = guard.last_screenshot_at {
            if (now.duration_since(last).as_millis() as u64) < SCREENSHOT_MIN_INTERVAL_MS {
                return;
            }
        }
    }
    // 信号量限流：超过 4 个截图 worker 在跑则丢弃本次
    let permit = match SCREENSHOT_WORKER_SEMAPHORE.try_acquire() {
        Some(p) => p,
        None => {
            log::debug!("[recorder] screenshot worker pool full, skipping");
            return;
        }
    };
    // 直接做截图（不再 spawn 子线程）。
    // 旧实现在这里又 spawn 了一个子线程 + recv_timeout(1s) 等待，导致每次截图
    // 创建 2 个线程（外层 rdev::listen 闭包 spawn 1 个 + 这里 spawn 1 个）。
    // 快速点击 50 次 = 100 个线程，50 个阻塞在 recv_timeout，线程资源浪费严重。
    // 现在：外层已 spawn 线程，这里直接做截图。GDI 挂起时线程会阻塞但
    // WorkerSemaphore 限制最多 4 个并发，不会卡死系统。
    let result = capture_region_png(x, y, SCREENSHOT_REGION_WIDTH, SCREENSHOT_REGION_HEIGHT);
    drop(permit); // 提前释放信号量，让下一个截图 worker 能进入
    match result {
        Ok(data) => {
            if let Ok(mut guard) = recorder.lock() {
                if guard.recording {
                    push_event_with_backpressure(&mut guard, RecordedEvent::Screenshot { data });
                    guard.last_screenshot_at = Some(now);
                }
            }
        }
        Err(err) => {
            log::warn!("[recorder] screenshot capture failed: {}", err);
        }
    }
}

/// Push 一个事件但带 backpressure 保护。
/// buffer 超过 MAX_EVENTS_BUFFER 时：
///   * 优先丢弃最老的 MouseMove（不影响业务步骤数）
///   * Click / KeyPress / Screenshot / State 始终保留（业务关键）
fn push_event_with_backpressure(guard: &mut RecorderInner, ev: RecordedEvent) {
    if guard.events.len() >= MAX_EVENTS_BUFFER {
        // 找第一个 MouseMove 删掉
        if let Some(pos) = guard
            .events
            .iter()
            .position(|e| matches!(e, RecordedEvent::MouseMove { .. }))
        {
            guard.events.remove(pos);
        } else {
            // 没有 MouseMove 可丢，但 buffer 已满 — 这是异常状态。
            // 避免 panic 也不阻塞业务流程：直接丢弃本事件（仅记录一次）。
            log::warn!(
                "[recorder] events buffer full ({} items) and no MouseMove to drop; \
                 dropping {:?}",
                guard.events.len(),
                std::mem::discriminant(&ev)
            );
            return;
        }
    }
    // 计算距上一个事件的延时，自动插入 Delay 事件（用于回放时模拟人类操作节奏）
    // 仅对操作事件（Click / KeyPress / BrowserAction）插入 Delay，MouseMove / Screenshot / State / Delay 本身不再嵌套
    let now = Instant::now();
    let should_insert_delay = matches!(
        &ev,
        RecordedEvent::MouseClick { .. }
            | RecordedEvent::KeyPress { .. }
            | RecordedEvent::BrowserAction { .. }
    );
    if should_insert_delay {
        if let Some(last_t) = guard.last_event_time {
            let delay_ms = now.duration_since(last_t).as_millis() as u64;
            // 仅在延时 > 50ms 时插入 Delay（<50ms 视为连续快速操作，不需要模拟延时）
            if delay_ms > 50 {
                guard.events.push(RecordedEvent::Delay { ms: delay_ms });
            }
        }
    }
    // 更新 last_event_time（对所有事件类型都更新，确保下一个操作事件的延时计算准确）
    guard.last_event_time = Some(now);
    guard.events.push(ev);
}

/// 通过 Windows UIA ElementFromPoint 获取点击位置的元素身份信息。
/// 用于基于元素（而非坐标）去重连续点击：同一按钮不同坐标视为同一步。
/// 在独立线程中调用，不阻塞 rdev 输入监听。
///
/// **超时保护**：`uiautomation::core::UIAutomation::new()` 内部 `CoInitializeEx`
/// 不会卡，但 `element_from_point` 在目标应用无响应时可能挂死（COM 调用
/// 等待目标窗口消息循环）。整个调用包在 UIA_LOOKUP_TIMEOUT_MS 监督线程
/// 中，超时后通过 thread::Thread::park_timeout 调度退出（Rust 标准库没有
/// 强制 kill 线程的 API，只能"建议"它结束）。
#[cfg(target_os = "windows")]
fn lookup_element_at_point(x: i32, y: i32) -> Option<ClickElementInfo> {
    use uiautomation::core::UIAutomation;
    use uiautomation::types::Point;
    let uia = UIAutomation::new().ok()?;
    let element = uia.element_from_point(Point::new(x, y)).ok()?;
    let name = element.get_name().ok().unwrap_or_default();
    let control_type = element
        .get_control_type()
        .ok()
        .map(|t| t.to_string())
        .unwrap_or_default();
    let automation_id = element.get_automation_id().ok().unwrap_or_default();
    let class_name = element.get_classname().ok().unwrap_or_default();
    // 全部为空说明 UIA 拿到了元素但没有任何身份字段 → 视为无效
    if name.is_empty() && control_type.is_empty() && automation_id.is_empty() && class_name.is_empty()
    {
        return None;
    }
    Some(ClickElementInfo {
        name,
        control_type,
        automation_id,
        class_name,
    })
}

#[cfg(not(target_os = "windows"))]
fn lookup_element_at_point(_x: i32, _y: i32) -> Option<ClickElementInfo> {
    None
}

/// 带超时的 UIA lookup：通过 mpsc channel 让 lookup 跑在独立线程中，
/// 调用方用 `recv_timeout` 等待结果。Rust 没有强制 kill 线程的 API，
/// 但配合 UIA_WORKER_SEMAPHORE 限流（最多 4 个并发），最坏情况是 4 个
/// 线程被挂死 —— 比之前"无上限堆积"安全得多。
///
/// 调用方必须已在 try_acquire 信号量后调用本函数。
#[cfg(target_os = "windows")]
fn lookup_element_at_point_with_timeout(x: i32, y: i32) -> Option<ClickElementInfo> {
    use std::sync::mpsc;
    let (tx, rx) = mpsc::channel();
    // 真正的 UIA 调用跑在独立线程；这里 spawn 的线程是信号量计数内的
    // 那个"worker"，不额外占一份资源。
    std::thread::Builder::new()
        .name("tupai-uia-call".into())
        .spawn(move || {
            let result = lookup_element_at_point(x, y);
            let _ = tx.send(result);
        })
        .ok()?;
    rx.recv_timeout(Duration::from_millis(UIA_LOOKUP_TIMEOUT_MS))
        .ok()
        .flatten()
}

#[cfg(not(target_os = "windows"))]
fn lookup_element_at_point_with_timeout(_x: i32, _y: i32) -> Option<ClickElementInfo> {
    None
}

/// 获取当前鼠标光标位置（屏幕坐标）。
/// 用途：start() 时主动初始化 last_mouse，避免用户启动录制后立即点击
/// （未移动鼠标）时 last_mouse 是 None 导致点击事件被丢弃。
/// 非 Windows 平台返回 None（rdev 的 MouseMove 会逐步补上 last_mouse）。
#[cfg(target_os = "windows")]
fn get_cursor_pos() -> Option<(i32, i32)> {
    use windows::Win32::UI::WindowsAndMessaging::GetCursorPos;
    use windows::Win32::Foundation::POINT;
    let mut point = POINT { x: 0, y: 0 };
    unsafe {
        GetCursorPos(&mut point).ok()?;
    }
    Some((point.x, point.y))
}

#[cfg(not(target_os = "windows"))]
fn get_cursor_pos() -> Option<(i32, i32)> {
    None
}

/// 在独立线程中查询 UIA 元素信息并回填到对应事件。
///
/// 防御性设计（解决多个竞态场景）：
///   1. **session_id 验证**：捕获 spawn 时的 session_id，回填前对比当前
///      inner.session_id。若 stop()/discard() 已递增 session_id（新一轮
///      录制已开始），直接丢弃结果——避免把 element 错误回填到新 session
///      的同索引 MouseClick 上。
///   2. **坐标匹配验证**：回填前对比 events[event_idx] 的 (x, y) 与 spawn
///      时记录的 (x, y)。若不匹配（说明该索引位置已是另一个事件），
///      直接丢弃——双保险。
///   3. **RAII 计数 guard**：spawn 前 pending_lookups += 1，线程退出时
///      （无论成功/失败/panic）自动 -= 1，让 stop() 能准确等待。
///   4. **不覆盖已回填**：若 element 已是 Some（理论上不会，但防御性），
///      不覆盖。
fn spawn_element_lookup(
    recorder: Arc<Mutex<RecorderInner>>,
    x: i32,
    y: i32,
    event_idx: usize,
    session_id: u64,
) {
    // 非阻塞 try_acquire：超过 MAX_CONCURRENT_WORKERS 个 lookup 在跑则直接丢弃本次
    // 查找（element 留 None，靠坐标 fallback 去重）。这是防卡死的关键：
    // 快速点击时若无限堆积 UIA 线程，COM 序列化 + 系统线程资源耗光会整个进程卡死。
    let permit = match UIA_WORKER_SEMAPHORE.try_acquire() {
        Some(p) => p,
        None => {
            log::debug!(
                "[recorder] UIA worker pool full, skipping lookup at ({}, {})",
                x,
                y
            );
            return;
        }
    };

    // 先递增 pending_lookups，再 spawn。若 spawn 失败需手动递减。
    {
        if let Ok(mut guard) = recorder.lock() {
            guard.pending_lookups = guard.pending_lookups.saturating_add(1);
        }
    }
    // 为闭包和 RAII guard 各 clone 一份 Arc，原始 recorder 留给 spawn 失败的 fallback。
    let closure_recorder = Arc::clone(&recorder);
    let guard_recorder = Arc::clone(&recorder);
    let build_result = std::thread::Builder::new()
        .name("tupai-uia-lookup".into())
        .spawn(move || {
            // RAII: 线程退出时自动递减 pending_lookups
            let _pending_guard = PendingLookupGuard {
                recorder: guard_recorder,
                session_id,
            };
            // permit drop 在闭包末尾：把信号量归还到池
            // permit 在 lookup 完成后才释放（成功/失败/超时都释放）
            let _permit = permit;
            // 使用带超时的 UIA 查询：内部 spawn 一个线程跑真正的 COM 调用，
            // 本线程 recv_timeout 200ms 等待；超时则丢弃（线程可能仍占着
            // UIA 资源但已被信号量限制最多 4 个并发）。
            let info = lookup_element_at_point_with_timeout(x, y);
            if let Some(info) = info {
                if let Ok(mut guard) = closure_recorder.lock() {
                    // 验证 1: session_id 一致（防止 stop/discard 后错误回填）
                    if guard.session_id != session_id {
                        return;
                    }
                    // 验证 2: 索引位置仍是同一 MouseClick（坐标匹配）
                    if let Some(RecordedEvent::MouseClick {
                        x: ex,
                        y: ey,
                        element,
                        ..
                    }) = guard.events.get_mut(event_idx)
                    {
                        if *ex == x && *ey == y && element.is_none() {
                            *element = Some(info);
                        }
                    }
                }
            }
        });
    if build_result.is_err() {
        // spawn 失败：手动递减，避免 stop() 永远等待
        if let Ok(mut guard) = recorder.lock() {
            if guard.session_id == session_id && guard.pending_lookups > 0 {
                guard.pending_lookups -= 1;
            }
        }
    }
}

/// RAII guard：线程退出时自动递减 pending_lookups 计数。
/// 仅当 session_id 仍匹配时才递减（防止跨 session 误减）。
struct PendingLookupGuard {
    recorder: Arc<Mutex<RecorderInner>>,
    session_id: u64,
}

impl Drop for PendingLookupGuard {
    fn drop(&mut self) {
        if let Ok(mut guard) = self.recorder.lock() {
            // 只在 session_id 仍匹配时递减，避免：
            //   1. stop() 已重置 pending_lookups = 0 后旧线程退出 → 误减到负
            //   2. discard→start 新 session 后旧线程退出 → 误减新 session 计数
            if guard.session_id == self.session_id && guard.pending_lookups > 0 {
                guard.pending_lookups -= 1;
            }
        }
    }
}

/// Count the number of step rows in a `generate_skill_md`-style
/// YAML body.  Heuristic: each step starts with `  - id: …`
/// (two-space indent) in the output produced by this module.
/// Returns 0 when the body does not contain a `steps:` block.
#[allow(dead_code)] // test-only helper; gated on `#[cfg(test)]` callers
fn count_yaml_steps(skill_md: &str) -> u32 {
    skill_md
        .lines()
        .filter(|line| line.trim_start().starts_with("- id:"))
        .count() as u32
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn count_yaml_steps_zero_for_empty() {
        assert_eq!(count_yaml_steps(""), 0);
    }

    #[test]
    fn count_yaml_steps_three_for_sample() {
        let yaml = "name: x\nsteps:\n  - id: a\n    description: \"\"\n  - id: b\n    description: \"\"\n  - id: c\n    description: \"\"\n";
        assert_eq!(count_yaml_steps(yaml), 3);
    }
}
