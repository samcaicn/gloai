// Copyright (c) 2026 AIMarketing
//
// AIMarketing v5 §6.2 — Hermes messenger in-process transport.
//
// The Doc1 design's "client → remote server over RabbitMQ" is
// replaced with a *local* in-process bus:
//   * `tx` is a `tokio::sync::mpsc::UnboundedSender<ClientRequest>` —
//     the public surface that callers (VLM rescue, skill loader)
//     use to publish requests.
//   * `responses: Arc<Mutex<Vec<ServerResponse>>>` is the sink the
//     background task writes into. In production, the task would
//     dispatch the request to either `LocalSkillStorage` (for
//     `SkillRequest`) or `VlmRescue::try_rescue` (for `VlmRequest`).
//
// For the v1 cut the dispatch task is **not** spawned — the public
// methods (`request_skill` / `request_vlm`) short-circuit to a stub
// `Err("... not wired — ...")`. The bus plumbing is in place so
// flipping the switch in a follow-up PR is a 3-line change.

use std::sync::{Arc, Mutex};

use tokio::sync::mpsc::{unbounded_channel, UnboundedReceiver, UnboundedSender};

use crate::pc_automation::hermes_messenger::events::{ClientRequest, ServerResponse};

/// In-process bus wrapper. Cheap to clone (`tx` is `UnboundedSender`
/// which is `Clone`); `responses` is `Arc<...>` so reads can happen
/// from any thread.
#[allow(dead_code)] // 6.2
#[derive(Clone)]
pub struct HermesMessenger {
    /// Public sender half. Spawned tasks and the executor both
    /// hold clones of this.
    pub tx: UnboundedSender<ClientRequest>,
    /// Receiver half. Owned by the dispatch task (not spawned in
    /// the v1 cut). The messenger keeps it inside an
    /// `Arc<Mutex<...>>` so we can pull it out once a real dispatch
    /// loop is added.
    rx: Arc<Mutex<Option<UnboundedReceiver<ClientRequest>>>>,
    /// In-process response log. The dispatch task appends here;
    /// tests inspect this directly.
    pub responses: Arc<Mutex<Vec<ServerResponse>>>,
}

impl HermesMessenger {
    /// Construct a fresh messenger with the in-process bus wired up.
    /// Does not spawn the dispatch task — the v1 cut is
    /// stub-only. See module-level doc for the follow-up plan.
    #[allow(dead_code)] // 6.2
    pub fn new() -> Self {
        let (tx, rx) = unbounded_channel::<ClientRequest>();
        Self {
            tx,
            rx: Arc::new(Mutex::new(Some(rx))),
            responses: Arc::new(Mutex::new(Vec::new())),
        }
    }

    /// Take the receiver out of the messenger. The dispatch loop
    /// (when it lands) is expected to call this *once* and then
    /// iterate. Tests can also use this to drive a mock dispatch
    /// task.
    #[allow(dead_code)] // 6.2
    pub fn take_receiver(&self) -> Option<UnboundedReceiver<ClientRequest>> {
        self.rx.lock().ok().and_then(|mut slot| slot.take())
    }

    /// Push a response into the in-process log. Visible to tests;
    /// the real dispatch task will use this as its sink.
    #[allow(dead_code)] // 6.2
    pub fn record_response(&self, resp: ServerResponse) {
        if let Ok(mut log) = self.responses.lock() {
            log.push(resp);
        }
    }

    /// Snapshot of the response log.
    #[allow(dead_code)] // 6.2
    pub fn responses_snapshot(&self) -> Vec<ServerResponse> {
        self.responses
            .lock()
            .map(|v| v.clone())
            .unwrap_or_default()
    }

    /// Send a request through the in-process channel. Returns
    /// `Err` if the receiver half has been taken / dropped (i.e. no
    /// dispatch task is running).
    #[allow(dead_code)] // 6.2
    pub fn send(&self, req: ClientRequest) -> Result<(), String> {
        self.tx
            .send(req)
            .map_err(|e| format!("hermes_messenger channel closed: {}", e))
    }

    // -----------------------------------------------------------------
    // Stub public methods — see `mod.rs` for the contract.
    // -----------------------------------------------------------------

    #[allow(dead_code)] // 6.2
    pub async fn request_skill(&self, intent: &str) -> Result<ServerResponse, String> {
        // v1 cut: the doc1 wire shape is preserved but the dispatch
        // task is not yet wired. The user is expected to call
        // `pc_automation::skill::LocalSkillStorage` directly until
        // the dispatcher lands.
        let _ = self
            .send(ClientRequest::SkillRequest {
                intent: intent.to_string(),
                context: None,
            })
            .ok(); // channel may be closed; the stub still returns the same Err.
        Err(
            "skill retrieval via hermes_messenger not wired — use LocalSkillStorage directly"
                .to_string(),
        )
    }

    #[allow(dead_code)] // 6.2
    pub async fn request_vlm(
        &self,
        screenshot_b64: &str,
        failed_step: &serde_json::Value,
        intent: &str,
    ) -> Result<ServerResponse, String> {
        let _ = self
            .send(ClientRequest::VlmRequest {
                screenshot_b64: screenshot_b64.to_string(),
                failed_step: failed_step.clone(),
                intent: intent.to_string(),
            })
            .ok();
        Err("VLM request via hermes_messenger not wired — call VlmRescue directly".to_string())
    }
}

impl Default for HermesMessenger {
    fn default() -> Self {
        Self::new()
    }
}

impl std::fmt::Debug for HermesMessenger {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("HermesMessenger")
            .field("tx_open", &!self.tx.is_closed())
            .field("rx_taken", &self.rx.lock().map(|s| s.is_none()).unwrap_or(true))
            .field("response_count", &self.responses_snapshot().len())
            .finish()
    }
}
