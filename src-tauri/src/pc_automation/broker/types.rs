// Copyright (c) 2026 tupAI
//
// Broker types: order request / ack, position, balance, health.
// Kept deliberately small and independent of any specific broker
// SDK — the actual CTP / OpenD / iFinD / 华泰 / Choice wire
// formats are translated by the adapter layer in `adapter.rs`.

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

/// Side of a trade. We do NOT model "short" in `OrderSide`
/// because A-share / HK retail APIs do not natively support it;
/// shorting is expressed as a `PositionSide::Short` opened via
/// a securities-lending agreement which is out of scope.
#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum OrderSide {
    Buy,
    Sell,
}

#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum OrderType {
    Market,
    Limit,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct OrderRequest {
    pub symbol: String,
    pub side: OrderSide,
    pub order_type: OrderType,
    pub quantity: f64,
    pub price: Option<f64>,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct OrderAck {
    pub order_id: String,
    pub accepted_at: DateTime<Utc>,
    pub broker_id: String,
}

#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum PositionSide {
    Long,
    Short,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct Position {
    pub symbol: String,
    pub quantity: f64,
    pub avg_price: f64,
    pub side: PositionSide,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct Balance {
    pub currency: String,
    pub cash: f64,
    pub equity: f64,
    pub margin: f64,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct BrokerHealth {
    pub broker_id: String,
    pub connected: bool,
    pub latency_ms: u64,
    pub last_error: Option<String>,
}
