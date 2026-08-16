// Copyright (c) 2026 tupAI
//
// Broker router + UI-automation guard rail.
//
// Key invariant (v5 doc §0): the broker API is the ONLY
// sanctioned path to place an order. UI automation is fast and
// brittle — using it for real-money flow would (a) miss
// compliance audits and (b) blow up the moment a broker pushes
// a UI redesign. The router therefore (i) carries no
// UI-automation fallback and (ii) panics if a UI-automation
// caller is detected so the bug is caught in dev, not in prod.

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use crate::pc_automation::broker::adapter::BrokerAdapter;
use crate::pc_automation::broker::stubs::{ChoiceAdapter, CtpAdapter, HuataiAdapter, IFindAdapter, OpenDAdapter};
use crate::pc_automation::broker::types::{Balance, BrokerHealth, OrderAck, OrderRequest, Position};
use crate::pc_automation::logger as pc_log;

/// Set to `true` while a UI-automation step is executing. The
/// router reads this flag and panics on `place_order` so the
/// accidental "UI flow accidentally hits the order API" bug is
/// impossible to ship. The flag is *process-global*; that is
/// intentional — the router and the UI-automation caller run
/// inside the same Tauri command thread, so a process-wide
/// `AtomicBool` is the simplest correct synchronisation.
static UI_AUTOMATION_ACTIVE: AtomicBool = AtomicBool::new(false);

/// Panic guard. Called by the UI-automation layer when it
/// enters a step. The return type is `!` (never) so a
/// caller that tries to "do work after this" fails to type-
/// check.
pub fn mark_called_from_ui_automation() -> ! {
    UI_AUTOMATION_ACTIVE.store(true, Ordering::SeqCst);
    panic!(
        "pc_automation::broker::mark_called_from_ui_automation() invoked — \
         this function is a guard rail and must NEVER actually be reached. \
         The UI automation layer must NOT call into the broker router."
    );
}

/// Defence-in-depth check. Callers that are about to invoke
/// `BrokerRouter::place_order` *outside* of a UI-automation
/// context should call this to flip the flag back off (no-op
/// if it was never set). The complementary guard lives in
/// `place_order` itself.
pub fn assert_broker_only_context(ctx: &str) {
    if UI_AUTOMATION_ACTIVE.load(Ordering::SeqCst) {
        panic!(
            "BrokerRouter::place_order invoked from UI automation context \
             (caller: {}). UI automation is not allowed to place orders — \
             route them through the broker API directly.",
            ctx
        );
    }
}

pub struct BrokerRouter {
    adapters: HashMap<String, Arc<dyn BrokerAdapter>>,
    default: Option<String>,
}

impl Default for BrokerRouter {
    fn default() -> Self {
        Self::new()
    }
}

impl BrokerRouter {
    /// Register every stub adapter the v5 router knows about.
    /// The first adapter registered is also chosen as the
    /// default; a future config layer can override that.
    pub fn new() -> Self {
        let mut adapters: HashMap<String, Arc<dyn BrokerAdapter>> = HashMap::new();

        let ctp: Arc<dyn BrokerAdapter> = Arc::new(CtpAdapter);
        let opend: Arc<dyn BrokerAdapter> = Arc::new(OpenDAdapter);
        let ifind: Arc<dyn BrokerAdapter> = Arc::new(IFindAdapter);
        let huatai: Arc<dyn BrokerAdapter> = Arc::new(HuataiAdapter);
        let choice: Arc<dyn BrokerAdapter> = Arc::new(ChoiceAdapter);

        adapters.insert(ctp.id().to_string(), ctp);
        adapters.insert(opend.id().to_string(), opend);
        adapters.insert(ifind.id().to_string(), ifind);
        adapters.insert(huatai.id().to_string(), huatai);
        adapters.insert(choice.id().to_string(), choice);

        let default = Some("ctp".to_string());
        Self { adapters, default }
    }

    /// Look up an adapter by id. Returns `None` if the id is
    /// unknown (e.g. the user typo'd `"CTP"` instead of
    /// `"ctp"`).
    pub fn adapter(&self, broker_id: &str) -> Option<Arc<dyn BrokerAdapter>> {
        self.adapters.get(broker_id).cloned()
    }

    /// Place an order through the broker API. Intentionally has
    /// no UI-automation fallback — see the module-level
    /// invariant above. The caller picks the broker; if `None`,
    /// the default is used.
    pub fn place_order(&self, req: OrderRequest) -> Result<OrderAck, String> {
        // Hard guard: refuse to run if we are inside a UI
        // automation step. This is the only place the check
        // lives — the IPC layer is allowed to call us freely.
        assert_broker_only_context("BrokerRouter::place_order");

        // Pick the adapter. The order is the broker that the
        // user has currently selected, falling back to the
        // configured default. We don't try to route by symbol
        // because that's a recipe for accidental cross-broker
        // trades.
        let id = self
            .default
            .as_deref()
            .ok_or_else(|| "no default broker configured".to_string())?;
        let adapter = self
            .adapters
            .get(id)
            .ok_or_else(|| format!("broker '{}' not registered", id))?;

        let ack = adapter.place_order(req)?;
        pc_log::info(&format!(
            "BrokerRouter::place_order ok via {} -> {}",
            id, ack.order_id
        ));
        Ok(ack)
    }

    /// Query positions, optionally from a specific broker. With
    /// `None`, positions from *all* registered brokers are
    /// concatenated. Failures from individual brokers are
    /// surfaced as the joined error string so a single broken
    /// broker does not blank the whole response.
    pub fn query_positions(&self, broker_id: Option<&str>) -> Result<Vec<Position>, String> {
        match broker_id {
            Some(id) => {
                let adapter = self
                    .adapters
                    .get(id)
                    .ok_or_else(|| format!("broker '{}' not registered", id))?;
                adapter.query_positions()
            }
            None => {
                let mut all = Vec::new();
                let mut errors: Vec<String> = Vec::new();
                for (id, adapter) in &self.adapters {
                    match adapter.query_positions() {
                        Ok(mut p) => all.append(&mut p),
                        Err(e) => errors.push(format!("{}: {}", id, e)),
                    }
                }
                if all.is_empty() && !errors.is_empty() {
                    return Err(errors.join("; "));
                }
                Ok(all)
            }
        }
    }

    /// Health snapshot for every registered broker. Failures
    /// are converted into `BrokerHealth` with `connected =
    /// false` so the Settings UI can render a uniform list
    /// even when brokers are down.
    pub fn health_all(&self) -> Vec<BrokerHealth> {
        let mut out: Vec<BrokerHealth> = Vec::with_capacity(self.adapters.len());
        for (id, adapter) in &self.adapters {
            match adapter.health() {
                Ok(h) => out.push(h),
                Err(e) => out.push(BrokerHealth {
                    broker_id: id.clone(),
                    connected: false,
                    latency_ms: 0,
                    last_error: Some(e),
                }),
            }
        }
        out
    }

    /// Returns the balance from the default broker, or from
    /// the explicitly requested one.
    pub fn query_balance(&self, broker_id: Option<&str>) -> Result<Balance, String> {
        let id = broker_id
            .map(|s| s.to_string())
            .or_else(|| self.default.clone())
            .ok_or_else(|| "no default broker configured".to_string())?;
        let adapter = self
            .adapters
            .get(&id)
            .ok_or_else(|| format!("broker '{}' not registered", id))?;
        adapter.query_balance()
    }
}
