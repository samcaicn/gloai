// Copyright (c) 2026 tupAI
//
// Stub broker adapters. Five brokers are pre-registered by the
// v5 router: CTP (通用), OpenD (华泰 OpenAPI), iFinD (同花顺
// iFinD), Huatai (华泰自有), Choice (东方财富). All five are
// stubs in this initial cut — real wire formats are deferred to
// follow-up PRs that can also pull in the right Rust bindings.
//
// The stubs are NOT no-ops. They return `Err("broker not
// configured")` from every state-changing method so it is
// impossible to accidentally send an order against an
// unconfigured broker at runtime.

use crate::pc_automation::broker::adapter::BrokerAdapter;
use crate::pc_automation::broker::types::{
    Balance, BrokerHealth, OrderAck, OrderRequest, Position,
};

const STUB_ERR: &str = "broker not configured";

fn stub_health(broker_id: &str) -> BrokerHealth {
    BrokerHealth {
        broker_id: broker_id.to_string(),
        connected: false,
        latency_ms: 0,
        last_error: Some("stub — wire up in follow-up PR".to_string()),
    }
}

// ---------------------------------------------------------------------------
// CTP — Shanghai Futures / generic CTP-style adapter.
// ---------------------------------------------------------------------------
pub struct CtpAdapter;

impl BrokerAdapter for CtpAdapter {
    fn id(&self) -> &str {
        "ctp"
    }
    fn place_order(&self, _req: OrderRequest) -> Result<OrderAck, String> {
        Err(STUB_ERR.to_string())
    }
    fn cancel_order(&self, _order_id: &str) -> Result<(), String> {
        Err(STUB_ERR.to_string())
    }
    fn query_positions(&self) -> Result<Vec<Position>, String> {
        Err(STUB_ERR.to_string())
    }
    fn query_balance(&self) -> Result<Balance, String> {
        Err(STUB_ERR.to_string())
    }
    fn health(&self) -> Result<BrokerHealth, String> {
        Ok(stub_health(self.id()))
    }
}

// ---------------------------------------------------------------------------
// OpenD — 华泰 OpenAPI front door.
// ---------------------------------------------------------------------------
pub struct OpenDAdapter;

impl BrokerAdapter for OpenDAdapter {
    fn id(&self) -> &str {
        "opend"
    }
    fn place_order(&self, _req: OrderRequest) -> Result<OrderAck, String> {
        Err(STUB_ERR.to_string())
    }
    fn cancel_order(&self, _order_id: &str) -> Result<(), String> {
        Err(STUB_ERR.to_string())
    }
    fn query_positions(&self) -> Result<Vec<Position>, String> {
        Err(STUB_ERR.to_string())
    }
    fn query_balance(&self) -> Result<Balance, String> {
        Err(STUB_ERR.to_string())
    }
    fn health(&self) -> Result<BrokerHealth, String> {
        Ok(stub_health(self.id()))
    }
}

// ---------------------------------------------------------------------------
// iFinD — 同花顺 iFinD's official Python API. The Rust port will
// drive the same REST endpoints the Python client uses.
// ---------------------------------------------------------------------------
pub struct IFindAdapter;

impl BrokerAdapter for IFindAdapter {
    fn id(&self) -> &str {
        "ifind"
    }
    fn place_order(&self, _req: OrderRequest) -> Result<OrderAck, String> {
        Err(STUB_ERR.to_string())
    }
    fn cancel_order(&self, _order_id: &str) -> Result<(), String> {
        Err(STUB_ERR.to_string())
    }
    fn query_positions(&self) -> Result<Vec<Position>, String> {
        Err(STUB_ERR.to_string())
    }
    fn query_balance(&self) -> Result<Balance, String> {
        Err(STUB_ERR.to_string())
    }
    fn health(&self) -> Result<BrokerHealth, String> {
        Ok(stub_health(self.id()))
    }
}

// ---------------------------------------------------------------------------
// Huatai — 华泰自研 Java client. Stubbed until the JNI bridge lands.
// ---------------------------------------------------------------------------
pub struct HuataiAdapter;

impl BrokerAdapter for HuataiAdapter {
    fn id(&self) -> &str {
        "huatai"
    }
    fn place_order(&self, _req: OrderRequest) -> Result<OrderAck, String> {
        Err(STUB_ERR.to_string())
    }
    fn cancel_order(&self, _order_id: &str) -> Result<(), String> {
        Err(STUB_ERR.to_string())
    }
    fn query_positions(&self) -> Result<Vec<Position>, String> {
        Err(STUB_ERR.to_string())
    }
    fn query_balance(&self) -> Result<Balance, String> {
        Err(STUB_ERR.to_string())
    }
    fn health(&self) -> Result<BrokerHealth, String> {
        Ok(stub_health(self.id()))
    }
}

// ---------------------------------------------------------------------------
// Choice — 东方财富 Choice. REST + token-based auth.
// ---------------------------------------------------------------------------
pub struct ChoiceAdapter;

impl BrokerAdapter for ChoiceAdapter {
    fn id(&self) -> &str {
        "choice"
    }
    fn place_order(&self, _req: OrderRequest) -> Result<OrderAck, String> {
        Err(STUB_ERR.to_string())
    }
    fn cancel_order(&self, _order_id: &str) -> Result<(), String> {
        Err(STUB_ERR.to_string())
    }
    fn query_positions(&self) -> Result<Vec<Position>, String> {
        Err(STUB_ERR.to_string())
    }
    fn query_balance(&self) -> Result<Balance, String> {
        Err(STUB_ERR.to_string())
    }
    fn health(&self) -> Result<BrokerHealth, String> {
        Ok(stub_health(self.id()))
    }
}
