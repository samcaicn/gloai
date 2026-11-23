package main

import "testing"

func TestGatewayOriginMatchesPhoneAndRestrictsLocalHttp(t *testing.T) {
	for raw, want := range map[string]string{"https://RELAY.example:443/": "https://relay.example", "https://relay.example:8443": "https://relay.example:8443", "https://[::1]:443/": "https://[::1]"} {
		got, err := gatewayOrigin(raw, "127.0.0.1:8787", false)
		if err != nil || got != want {
			t.Fatalf("origin canonicalization failed: %s", raw)
		}
	}
	for _, raw := range []string{"http://relay.example", "https://user:pass@relay.example", "https://relay.example/path", "https://relay.example?", "https://relay.example#", "https://relay.example:65536", "https://:443"} {
		if _, err := gatewayOrigin(raw, "127.0.0.1:8787", false); err == nil {
			t.Fatal("unsafe origin accepted")
		}
	}
	if _, err := gatewayOrigin("http://127.0.0.1:8787", "0.0.0.0:8787", true); err == nil {
		t.Fatal("local HTTP exposed to all interfaces")
	}
	if got, err := gatewayOrigin("http://127.0.0.1:8787", "127.0.0.1:8787", true); err != nil || got != "http://127.0.0.1:8787" {
		t.Fatal("local test mode failed")
	}
}
