use super::*;

#[test]
fn test_goclaw_config_default() {
    let config = GoClawConfig::default();
    assert!(config.enabled);
    assert!(config.auto_start);
    assert_eq!(config.ws_url, "ws://127.0.0.1:9876/ws");
    assert_eq!(config.http_url, "http://127.0.0.1:9876");
}
