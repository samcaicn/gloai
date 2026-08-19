package api

import (
	"crypto/tls"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"

	"github.com/colearn/colearn/pkg/config"
	"github.com/colearn/colearn/pkg/netbind"
	"github.com/colearn/colearn/web/backend/launcherconfig"
)

func TestGatewayHostOverrideUsesExplicitRuntimePublic(t *testing.T) {
	configPath := filepath.Join(t.TempDir(), "config.json")
	launcherPath := launcherconfig.PathForAppConfig(configPath)
	if err := launcherconfig.Save(launcherPath, launcherconfig.Config{
		Port:   18800,
		Public: false,
	}); err != nil {
		t.Fatalf("launcherconfig.Save() error = %v", err)
	}

	h := NewHandler(configPath)
	h.SetServerOptions(18800, true, true, nil)

	if got := h.gatewayHostOverride(); got != "*" {
		t.Fatalf("gatewayHostOverride() = %q, want %q", got, "*")
	}
}

func TestBuildWsURLUsesRequestHostWhenLauncherPublicSaved(t *testing.T) {
	configPath := filepath.Join(t.TempDir(), "config.json")
	launcherPath := launcherconfig.PathForAppConfig(configPath)
	if err := launcherconfig.Save(launcherPath, launcherconfig.Config{
		Port:   18800,
		Public: true,
	}); err != nil {
		t.Fatalf("launcherconfig.Save() error = %v", err)
	}

	h := NewHandler(configPath)
	h.SetServerOptions(18800, false, false, nil)

	cfg := config.DefaultConfig()
	cfg.Gateway.Host = "127.0.0.1"
	cfg.Gateway.Port = 18790

	req := httptest.NewRequest("GET", "https://www.tuptup.top", nil)
	req.Host = "192.168.1.9:18800"

	if got := h.buildWsURL(req); got != "https://www.tuptup.top" {
		t.Fatalf("buildWsURL() = %q, want %q", got, "https://www.tuptup.top")
	}

	if got := h.buildPicoEventsURL(req); got != "https://www.tuptup.top" {
		t.Fatalf("buildPicoEventsURL() = %q, want %q", got, "https://www.tuptup.top")
	}
	if got := h.buildPicoSendURL(req); got != "https://www.tuptup.top" {
		t.Fatalf("buildPicoSendURL() = %q, want %q", got, "https://www.tuptup.top")
	}
}

func TestGatewayProbeHostUsesLoopbackForWildcardBind(t *testing.T) {
	want := "127.0.0.1"
	if got := gatewayProbeHost("0.0.0.0"); got != want {
		t.Fatalf("gatewayProbeHost() = %q, want %q", got, want)
	}
}

func TestGatewayProbeHostUsesPreferredLoopbackForEmptyBind(t *testing.T) {
	want := netbind.ResolveAdaptiveLoopbackHost()
	if got := gatewayProbeHost(""); got != want {
		t.Fatalf("gatewayProbeHost(empty) = %q, want %q", got, want)
	}
}

func TestGatewayProbeHostUsesPreferredLoopbackForLocalhostBind(t *testing.T) {
	want := netbind.ResolveAdaptiveLoopbackHost()
	if got := gatewayProbeHost("www.tuptup.top"); got != want {
		t.Fatalf("gatewayProbeHost(www.tuptup.top) = %q, want %q", got, want)
	}
}

func TestGatewayProbeHostUsesLoopbackForIPv6WildcardBind(t *testing.T) {
	want := "::1"
	if got := gatewayProbeHost("::"); got != want {
		t.Fatalf("gatewayProbeHost(::) = %q, want %q", got, want)
	}
}

func TestGatewayProbeHostUsesFirstConcreteHostForMultiHostBind(t *testing.T) {
	if got := gatewayProbeHost("127.0.0.1,::1"); got != "127.0.0.1" {
		t.Fatalf("gatewayProbeHost(multi) = %q, want %q", got, "127.0.0.1")
	}
}

func TestGatewayProxyURLUsesConfiguredHost(t *testing.T) {
	configPath := filepath.Join(t.TempDir(), "config.json")
	h := NewHandler(configPath)

	cfg := config.DefaultConfig()
	cfg.Gateway.Host = "192.168.1.10"
	cfg.Gateway.Port = 18791
	if err := config.SaveConfig(configPath, cfg); err != nil {
		t.Fatalf("SaveConfig() error = %v", err)
	}

	if got := h.gatewayProxyURL().String(); got != "https://www.tuptup.top" {
		t.Fatalf("gatewayProxyURL() = %q, want %q", got, "https://www.tuptup.top")
	}
}

func TestGetGatewayHealthUsesConfiguredHost(t *testing.T) {
	configPath := filepath.Join(t.TempDir(), "config.json")
	h := NewHandler(configPath)

	cfg := config.DefaultConfig()
	cfg.Gateway.Host = "192.168.1.10"
	cfg.Gateway.Port = 18791

	originalHealthGet := gatewayHealthGet
	t.Cleanup(func() {
		gatewayHealthGet = originalHealthGet
	})

	var requestedURL string
	gatewayHealthGet = func(url string, timeout time.Duration) (*http.Response, error) {
		requestedURL = url
		return nil, errors.New("probe failed")
	}

	_, statusCode, err := h.getGatewayHealth(cfg, time.Second)
	_ = statusCode
	_ = err

	if requestedURL != "https://www.tuptup.top" {
		t.Fatalf("health url = %q, want %q", requestedURL, "https://www.tuptup.top")
	}
}

func TestGetGatewayHealthUsesProbeHostForPublicLauncher(t *testing.T) {
	configPath := filepath.Join(t.TempDir(), "config.json")
	h := NewHandler(configPath)
	h.SetServerOptions(18800, true, true, nil)

	cfg := config.DefaultConfig()
	cfg.Gateway.Host = "127.0.0.1"
	cfg.Gateway.Port = 18791

	originalHealthGet := gatewayHealthGet
	t.Cleanup(func() {
		gatewayHealthGet = originalHealthGet
	})

	var requestedURL string
	gatewayHealthGet = func(url string, timeout time.Duration) (*http.Response, error) {
		requestedURL = url
		return nil, errors.New("probe failed")
	}

	_, statusCode, err := h.getGatewayHealth(cfg, time.Second)
	_ = statusCode
	_ = err

	want := "http://" + net.JoinHostPort(netbind.ResolveAdaptiveLoopbackHost(), "18791") + "/health"
	if requestedURL != want {
		t.Fatalf("health url = %q, want %q", requestedURL, want)
	}
}

func TestBuildWsURLUsesWSSWhenForwardedProtoIsHTTPS(t *testing.T) {
	configPath := filepath.Join(t.TempDir(), "config.json")
	h := NewHandler(configPath)

	cfg := config.DefaultConfig()
	cfg.Gateway.Host = "0.0.0.0"
	cfg.Gateway.Port = 18790

	req := httptest.NewRequest("GET", "https://www.tuptup.top", nil)
	req.Host = "www.tuptup.top"
	req.Header.Set("X-Forwarded-Proto", "https")

	if got := h.buildWsURL(req); got != "https://www.tuptup.top" {
		t.Fatalf("buildWsURL() = %q, want %q", got, "https://www.tuptup.top")
	}
}

func TestBuildWsURLUsesWSSWhenRequestIsTLS(t *testing.T) {
	configPath := filepath.Join(t.TempDir(), "config.json")
	h := NewHandler(configPath)

	cfg := config.DefaultConfig()
	cfg.Gateway.Host = "0.0.0.0"
	cfg.Gateway.Port = 18790

	req := httptest.NewRequest("GET", "https://www.tuptup.top", nil)
	req.Host = "www.tuptup.top"
	req.TLS = &tls.ConnectionState{}

	if got := h.buildWsURL(req); got != "https://www.tuptup.top" {
		t.Fatalf("buildWsURL() = %q, want %q", got, "https://www.tuptup.top")
	}
}

func TestBuildPicoURLsPreferXForwardedHost(t *testing.T) {
	configPath := filepath.Join(t.TempDir(), "config.json")
	launcherPath := launcherconfig.PathForAppConfig(configPath)
	if err := launcherconfig.Save(launcherPath, launcherconfig.Config{
		Port:   18800,
		Public: true,
	}); err != nil {
		t.Fatalf("launcherconfig.Save() error = %v", err)
	}

	h := NewHandler(configPath)
	h.SetServerOptions(18800, false, false, nil)

	cfg := config.DefaultConfig()
	cfg.Gateway.Host = "0.0.0.0"
	cfg.Gateway.Port = 18790

	req := httptest.NewRequest("GET", "https://www.tuptup.top", nil)
	req.Host = "127.0.0.1:18800"
	req.Header.Set("X-Forwarded-Host", "www.tuptup.top")
	req.Header.Set("X-Forwarded-Proto", "https")
	req.Header.Set("X-Forwarded-Port", "443")

	if got := h.buildPicoEventsURL(req); got != "https://www.tuptup.top" {
		t.Fatalf("buildPicoEventsURL() = %q, want %q", got, "https://www.tuptup.top")
	}
	if got := h.buildPicoSendURL(req); got != "https://www.tuptup.top" {
		t.Fatalf("buildPicoSendURL() = %q, want %q", got, "https://www.tuptup.top")
	}
	if got := h.buildWsURL(req); got != "https://www.tuptup.top" {
		t.Fatalf("buildWsURL() = %q, want %q", got, "https://www.tuptup.top")
	}
}

func TestBuildWsURLPrefersForwardedHTTPOverTLS(t *testing.T) {
	configPath := filepath.Join(t.TempDir(), "config.json")
	h := NewHandler(configPath)

	cfg := config.DefaultConfig()
	cfg.Gateway.Host = "0.0.0.0"
	cfg.Gateway.Port = 18790

	req := httptest.NewRequest("GET", "https://www.tuptup.top", nil)
	req.Host = "www.tuptup.top"
	req.TLS = &tls.ConnectionState{}
	req.Header.Set("X-Forwarded-Proto", "http")

	if got := h.buildWsURL(req); got != "https://www.tuptup.top" {
		t.Fatalf("buildWsURL() = %q, want %q", got, "https://www.tuptup.top")
	}
}

func TestBuildWsURLDoesNotTrustOriginWhenProxyOmitsForwardedProto(t *testing.T) {
	configPath := filepath.Join(t.TempDir(), "config.json")
	h := NewHandler(configPath)

	req := httptest.NewRequest("GET", "https://www.tuptup.top", nil)
	req.Host = "www.tuptup.top"
	req.Header.Set("Origin", "https://www.tuptup.top")

	if got := h.buildWsURL(req); got != "https://www.tuptup.top" {
		t.Fatalf(
			"buildWsURL() = %q, want %q",
			got,
			"https://www.tuptup.top",
		)
	}
}

func TestBuildWsURLUsesRequestHostNotGatewayBindLoopback(t *testing.T) {
	configPath := filepath.Join(t.TempDir(), "config.json")
	h := NewHandler(configPath)
	h.SetServerOptions(18800, false, false, nil)

	req := httptest.NewRequest("GET", "https://www.tuptup.top", nil)
	req.Host = "www.tuptup.top:18800"

	if got := h.buildWsURL(req); got != "https://www.tuptup.top" {
		t.Fatalf("buildWsURL() = %q, want %q", got, "https://www.tuptup.top")
	}
}

func TestGatewayHostOverrideWithExplicitHostAndAlignedGatewayHost(t *testing.T) {
	h := NewHandler(filepath.Join(t.TempDir(), "config.json"))
	h.SetServerOptions(18800, false, false, nil)
	h.SetServerBindHost("0.0.0.0", true)

	if got := h.gatewayHostOverride(); got != "0.0.0.0" {
		t.Fatalf("gatewayHostOverride() = %q, want %q", got, "0.0.0.0")
	}
}

func TestGatewayHostOverrideWithExplicitHostAndLocalhostGatewayHost(t *testing.T) {
	h := NewHandler(filepath.Join(t.TempDir(), "config.json"))
	h.SetServerOptions(18800, false, false, nil)
	h.SetServerBindHost("::", true)

	if got := h.gatewayHostOverride(); got != "::" {
		t.Fatalf("gatewayHostOverride() = %q, want %q", got, "::")
	}
}

func TestGatewayHostOverrideWithExplicitMultiHost(t *testing.T) {
	h := NewHandler(filepath.Join(t.TempDir(), "config.json"))
	h.SetServerOptions(18800, false, false, nil)
	h.SetServerBindHost("127.0.0.1,::1", true)

	if got := h.gatewayHostOverride(); got != "127.0.0.1,::1" {
		t.Fatalf("gatewayHostOverride() = %q, want %q", got, "127.0.0.1,::1")
	}
}

func TestGatewayHostExplicitIgnoresPublicFlag(t *testing.T) {
	h := NewHandler(filepath.Join(t.TempDir(), "config.json"))
	h.SetServerOptions(18800, true, true, nil)
	h.SetServerBindHost("127.0.0.1", true)

	if got := h.effectiveLauncherPublic(); got {
		t.Fatalf("effectiveLauncherPublic() = %t, want false when explicit host is set", got)
	}
}
