// Copyright (c) 2026 AIMarketing
//
// Broker adapter trait. Object-safe (no associated types, no
// generic methods) so the router can stash adapters behind
// `Arc<dyn BrokerAdapter>`.

use crate::pc_automation::broker::types::{
    Balance, BrokerHealth, OrderAck, OrderRequest, Position,
};

pub trait BrokerAdapter: Send + Sync {
    /// Stable, human-readable broker id (e.g. "ctp", "opend",
    /// "ifind", "huatai", "choice"). Used as the key in
    /// `BrokerRouter`'s adapter map.
    fn id(&self) -> &str;

    fn place_order(&self, req: OrderRequest) -> Result<OrderAck, String>;

    fn cancel_order(&self, order_id: &str) -> Result<(), String>;

    fn query_positions(&self) -> Result<Vec<Position>, String>;

    fn query_balance(&self) -> Result<Balance, String>;

    fn health(&self) -> Result<BrokerHealth, String>;
}
