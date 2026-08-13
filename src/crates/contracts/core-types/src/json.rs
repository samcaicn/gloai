//! Lossless JSON values accepted by the session log.

use serde::{Deserialize, Serialize};
use serde_json::Value;

/// JSON value that a session event may carry. Non-JSON types are rejected at append.
pub type JsonValue = Value;

/// True when `value` is finite JSON (objects, arrays, strings, numbers, bool, null).
pub fn is_json_value(value: &JsonValue) -> bool {
    match value {
        Value::Number(n) => n.as_f64().is_some_and(f64::is_finite) || n.as_i64().is_some() || n.as_u64().is_some(),
        Value::Array(items) => items.iter().all(is_json_value),
        Value::Object(map) => map.values().all(is_json_value),
        Value::Null | Value::Bool(_) | Value::String(_) => true,
    }
}

/// Snapshot a value through JSON so the log never retains a caller's alias.
pub fn snapshot_json_value<T: Serialize>(value: &T) -> Result<JsonValue, serde_json::Error> {
    serde_json::to_value(value)
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(untagged)]
pub enum JsonMap {
    Object(serde_json::Map<String, JsonValue>),
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn rejects_non_finite_numbers() {
        let nan = JsonValue::from(f64::NAN);
        assert!(!is_json_value(&nan));
        assert!(is_json_value(&json!({"ok": 1})));
    }
}
