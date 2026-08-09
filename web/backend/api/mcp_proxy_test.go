package api

import (
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/colearn/colearn/pkg/config"
	ppid "github.com/colearn/colearn/pkg/pid"
)

func TestMcpProxy_ForwardsMcpSessionIDHeader(t *testing.T) {
	var receivedSID string
	var receivedAuth string
	var receivedPath string

	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedSID = r.Header.Get("Mcp-Session-Id")
		receivedAuth = r.Header.Get("Authorization")
		receivedPath = r.URL.Path
		w.Header().Set("Mcp-Session-Id", "test-session-id")
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = io.WriteString(w, `{"jsonrpc":"2.0"}`)
	}))
	defer upstream.Close()

	origMatcher := gatewayProcessMatcher
	gatewayProcessMatcher = func(int) (bool, bool) { return true, true }
	t.Cleanup(func() { gatewayProcessMatcher = origMatcher })

	home := t.TempDir()
	t.Setenv("colearn_HOME", home)
	defer ppid.RemovePidFile(globalConfigDir())

	configPath := filepath.Join(t.TempDir(), "config.json")
	h := NewHandler(configPath)

	cfg := config.DefaultConfig()
	cfg.Gateway.Host = "127.0.0.1"
	cfg.Gateway.Port = mustGatewayTestPort(t, upstream.URL)
	if err := config.SaveConfig(configPath, cfg); err != nil {
		t.Fatalf("SaveConfig() error = %v", err)
	}

	gateway.mu.Lock()
	gateway.picoToken = "test-gateway-token"
	gateway.mu.Unlock()

	cmd := startGatewayLikeProcess(t)
	t.Cleanup(func() {
		if cmd.Process != nil {
			_ = cmd.Process.Kill()
		}
		_ = cmd.Wait()
	})
	writeTestPidFile(t, ppid.PidFileData{
		PID:   cmd.Process.Pid,
		Token: "test-token",
		Host:  cfg.Gateway.Host,
		Port:  cfg.Gateway.Port,
	})

	req := httptest.NewRequest(http.MethodPost, "https://www.tuptup.top/api/v2/mcp", strings.NewReader(`{"jsonrpc":"2.0","method":"initialize"}`))
	req.Header.Set("Mcp-Session-Id", "client-session-123")
	rr := httptest.NewRecorder()

	h.handleMcpProxy()(rr, req)

	if receivedSID != "client-session-123" {
		t.Fatalf("Mcp-Session-Id = %q, want %q", receivedSID, "client-session-123")
	}
	if receivedAuth != "Bearer test-gateway-token" {
		t.Fatalf("Authorization = %q, want %q", receivedAuth, "Bearer test-gateway-token")
	}
	if receivedPath != "/api/v2/mcp" {
		t.Fatalf("upstream path = %q, want %q", receivedPath, "/api/v2/mcp")
	}
}

func TestMcpProxy_GETSetsStreamingHeaders(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		w.WriteHeader(http.StatusOK)
		_, _ = io.WriteString(w, "event: test\ndata: hello\n\n")
	}))
	defer upstream.Close()

	origMatcher := gatewayProcessMatcher
	gatewayProcessMatcher = func(int) (bool, bool) { return true, true }
	t.Cleanup(func() { gatewayProcessMatcher = origMatcher })

	home := t.TempDir()
	t.Setenv("colearn_HOME", home)
	defer ppid.RemovePidFile(globalConfigDir())

	configPath := filepath.Join(t.TempDir(), "config.json")
	h := NewHandler(configPath)

	cfg := config.DefaultConfig()
	cfg.Gateway.Host = "127.0.0.1"
	cfg.Gateway.Port = mustGatewayTestPort(t, upstream.URL)
	if err := config.SaveConfig(configPath, cfg); err != nil {
		t.Fatalf("SaveConfig() error = %v", err)
	}

	gateway.mu.Lock()
	gateway.picoToken = "test-gateway-token"
	gateway.mu.Unlock()

	cmd := startGatewayLikeProcess(t)
	t.Cleanup(func() {
		if cmd.Process != nil {
			_ = cmd.Process.Kill()
		}
		_ = cmd.Wait()
	})
	writeTestPidFile(t, ppid.PidFileData{
		PID:   cmd.Process.Pid,
		Token: "test-token",
		Host:  cfg.Gateway.Host,
		Port:  cfg.Gateway.Port,
	})

	req := httptest.NewRequest(http.MethodGet, "https://www.tuptup.top/api/v2/mcp", nil)
	req.Header.Set("Mcp-Session-Id", "sse-session")
	rr := httptest.NewRecorder()

	h.handleMcpProxy()(rr, req)

	if ct := rr.Header().Get("Content-Type"); !strings.HasPrefix(ct, "text/event-stream") {
		t.Fatalf("Content-Type = %q, want text/event-stream prefix", ct)
	}
	if bc := rr.Header().Get("Cache-Control"); bc != "no-cache" {
		t.Fatalf("Cache-Control = %q, want no-cache", bc)
	}
}

func TestMcpProxy_RejectsWhenGatewayUnavailable(t *testing.T) {
	configPath := filepath.Join(t.TempDir(), "config.json")
	h := NewHandler(configPath)

	req := httptest.NewRequest(http.MethodPost, "https://www.tuptup.top/api/v2/mcp", nil)
	rr := httptest.NewRecorder()

	h.handleMcpProxy()(rr, req)

	if rr.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want %d", rr.Code, http.StatusServiceUnavailable)
	}
}

func TestMcpProxy_DELETEEndsSession(t *testing.T) {
	var receivedMethod string
	var receivedSID string

	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedMethod = r.Method
		receivedSID = r.Header.Get("Mcp-Session-Id")
		w.WriteHeader(http.StatusAccepted)
	}))
	defer upstream.Close()

	origMatcher := gatewayProcessMatcher
	gatewayProcessMatcher = func(int) (bool, bool) { return true, true }
	t.Cleanup(func() { gatewayProcessMatcher = origMatcher })

	home := t.TempDir()
	t.Setenv("colearn_HOME", home)
	defer ppid.RemovePidFile(globalConfigDir())

	configPath := filepath.Join(t.TempDir(), "config.json")
	h := NewHandler(configPath)

	cfg := config.DefaultConfig()
	cfg.Gateway.Host = "127.0.0.1"
	cfg.Gateway.Port = mustGatewayTestPort(t, upstream.URL)
	if err := config.SaveConfig(configPath, cfg); err != nil {
		t.Fatalf("SaveConfig() error = %v", err)
	}

	gateway.mu.Lock()
	gateway.picoToken = "test-gateway-token"
	gateway.mu.Unlock()

	cmd := startGatewayLikeProcess(t)
	t.Cleanup(func() {
		if cmd.Process != nil {
			_ = cmd.Process.Kill()
		}
		_ = cmd.Wait()
	})
	writeTestPidFile(t, ppid.PidFileData{
		PID:   cmd.Process.Pid,
		Token: "test-token",
		Host:  cfg.Gateway.Host,
		Port:  cfg.Gateway.Port,
	})

	req := httptest.NewRequest(http.MethodDelete, "https://www.tuptup.top/api/v2/mcp", nil)
	req.Header.Set("Mcp-Session-Id", "session-to-end")
	rr := httptest.NewRecorder()

	h.handleMcpProxy()(rr, req)

	if receivedMethod != http.MethodDelete {
		t.Fatalf("upstream method = %q, want %q", receivedMethod, http.MethodDelete)
	}
	if receivedSID != "session-to-end" {
		t.Fatalf("Mcp-Session-Id = %q, want %q", receivedSID, "session-to-end")
	}
}
