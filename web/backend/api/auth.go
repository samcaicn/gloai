package api

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"

	"github.com/colearn/colearn/pkg/config"
	"github.com/colearn/colearn/pkg/providers/tupai"
	"github.com/colearn/colearn/web/backend/middleware"
)

const logoutBodyMaxBytes = 4096

// LauncherAuthRouteOpts configures dashboard auth handlers.
type LauncherAuthRouteOpts struct {
	SessionCookie string
	SecureCookie  func(*http.Request) bool
	// ConfigPath is the path to the app config.json, used to read the tupai
	// model's api_base / device_token for the device bind flow. When empty,
	// bind falls back to dummy initialization.
	ConfigPath string
}

type launcherAuthStatusResponse struct {
	Authenticated bool `json:"authenticated"`
}

// RegisterLauncherAuthRoutes registers /api/auth/logout|status|bind.
func RegisterLauncherAuthRoutes(mux *http.ServeMux, opts LauncherAuthRouteOpts) {
	secure := opts.SecureCookie
	if secure == nil {
		secure = middleware.DefaultLauncherDashboardSecureCookie
	}
	h := &launcherAuthHandlers{
		sessionCookie: opts.SessionCookie,
		secureCookie:  secure,
		configPath:    opts.ConfigPath,
	}
	mux.HandleFunc("POST /api/auth/logout", h.handleLogout)
	mux.HandleFunc("GET /api/auth/status", h.handleStatus)
	mux.HandleFunc("POST /api/auth/bind", h.handleBind)
}

type launcherAuthHandlers struct {
	sessionCookie string
	secureCookie  func(*http.Request) bool
	configPath    string
}

func launcherSetupCrossSite(r *http.Request) bool {
	fetchSite := strings.ToLower(strings.TrimSpace(r.Header.Get("Sec-Fetch-Site")))
	if fetchSite == "cross-site" {
		return true
	}

	if origin := strings.TrimSpace(r.Header.Get("Origin")); origin != "" {
		return !sameLauncherRequestOrigin(r, origin)
	}

	if referer := strings.TrimSpace(r.Header.Get("Referer")); referer != "" {
		return !sameLauncherRequestOrigin(r, referer)
	}

	return false
}

func sameLauncherRequestOrigin(r *http.Request, raw string) bool {
	if strings.ContainsAny(raw, " \t\r\n") {
		return false
	}

	u, err := url.Parse(raw)
	if err != nil || u.Scheme == "" || u.Host == "" {
		return false
	}

	wantScheme := launcherRequestScheme(r)
	wantHost := r.Host
	if wantHost == "" {
		wantHost = r.URL.Host
	}
	return strings.EqualFold(u.Scheme, wantScheme) && strings.EqualFold(u.Host, wantHost)
}

func launcherRequestScheme(r *http.Request) string {
	if proto := strings.TrimSpace(r.Header.Get("X-Forwarded-Proto")); proto != "" {
		if i := strings.IndexByte(proto, ','); i >= 0 {
			proto = proto[:i]
		}
		proto = strings.ToLower(strings.TrimSpace(proto))
		if proto == "http" || proto == "https" {
			return proto
		}
	}
	if r.TLS != nil {
		return "https"
	}
	if r.URL != nil && r.URL.Scheme != "" {
		return r.URL.Scheme
	}
	return "http"
}

func (h *launcherAuthHandlers) handleLogout(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	if r.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		_, _ = w.Write([]byte(`{"error":"method not allowed"}`))
		return
	}
	ct := strings.ToLower(strings.TrimSpace(r.Header.Get("Content-Type")))
	if !strings.HasPrefix(ct, "application/json") {
		w.WriteHeader(http.StatusUnsupportedMediaType)
		_, _ = w.Write([]byte(`{"error":"Content-Type must be application/json"}`))
		return
	}
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, logoutBodyMaxBytes))
	if err := dec.Decode(&struct{}{}); err != nil && err != io.EOF {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"error":"invalid JSON body"}`))
		return
	}
	if err := dec.Decode(&struct{}{}); err != io.EOF {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"error":"invalid JSON body"}`))
		return
	}

	middleware.ClearLauncherDashboardSessionCookie(w, r, h.secureCookie)
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"status":"ok"}`))
}

func (h *launcherAuthHandlers) handleStatus(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	authed := false
	if c, err := r.Cookie(middleware.LauncherDashboardCookieName); err == nil {
		authed = subtle.ConstantTimeCompare([]byte(c.Value), []byte(h.sessionCookie)) == 1
	}
	resp := launcherAuthStatusResponse{
		Authenticated: authed,
	}
	enc, err := json.Marshal(resp)
	if err != nil {
		w.WriteHeader(http.StatusInternalServerError)
		writeErrorf(w, "marshal response failed: %v", err)
		return
	}
	_, _ = w.Write(enc)
}

// handleBind binds the dashboard using an 8-digit join code. Binding is the
// only launcher auth mechanism: on approval the caller receives a session
// cookie and is logged in directly.
func (h *launcherAuthHandlers) handleBind(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")

	if launcherSetupCrossSite(r) {
		w.WriteHeader(http.StatusForbidden)
		_, _ = w.Write([]byte(`{"error":"cross-site bind request rejected"}`))
		return
	}

	var body struct {
		JoinCode string `json:"join_code"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&body); err != nil {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"error":"invalid JSON"}`))
		return
	}

	code := strings.TrimSpace(body.JoinCode)
	if code == "" {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"error":"join code must not be empty"}`))
		return
	}
	if len(code) != 8 {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"error":"join code must be 8 digits"}`))
		return
	}

	// Real bind: call the configured tup backend client.bind with the join code.
	// When no tupai model is configured, fall back to the legacy dummy bind so
	// the launcher can still be initialized.
	status, err := h.performTupBind(r.Context(), code)
	if err != nil {
		w.WriteHeader(http.StatusBadGateway)
		writeErrorf(w, "%v", err)
		return
	}
	if status.Pending {
		// Approval pending (e.g. iLink admin confirm). Do not authenticate yet.
		resp := map[string]any{"status": "pending", "message": status.Message}
		if status.RequestID != "" {
			resp["request_id"] = status.RequestID
		}
		w.WriteHeader(http.StatusOK)
		enc, _ := json.Marshal(resp)
		_, _ = w.Write(enc)
		return
	}

	// Bind approved: authenticate directly. The session never expires, so the
	// user does not need to re-authenticate after a one-time bind.
	middleware.SetLauncherDashboardSessionCookie(w, r, h.sessionCookie, h.secureCookie)

	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"status":"ok"}`))
}

// performTupBind finds the tupai model entry in the app config and calls the
// tup MCP client.bind action with the join code. It returns the parsed bind
// result. When the app config has no tupai model, or binding is not configured,
// it returns a synthetic approved result so the dashboard can be initialized
// (legacy behavior).
func (h *launcherAuthHandlers) performTupBind(ctx context.Context, joinCode string) (*tupai.BindResult, error) {
	if h.configPath == "" {
		return &tupai.BindResult{Approved: true, Message: "bind code accepted"}, nil
	}
	cfg, err := config.LoadConfig(h.configPath)
	if err != nil {
		return nil, fmt.Errorf("failed to load app config for bind: %w", err)
	}

	var deviceToken, apiBase string
	for _, m := range cfg.ModelList {
		if m == nil {
			continue
		}
		if strings.EqualFold(strings.TrimSpace(m.Provider), "tupai") {
			deviceToken = m.APIKey()
			apiBase = m.APIBase
			break
		}
	}
	if deviceToken == "" {
		// No tupai device token configured: fall back to legacy dummy bind.
		return &tupai.BindResult{Approved: true, Message: "bind code accepted"}, nil
	}

	prov := tupai.NewProvider(deviceToken, apiBase, "colearn/launcher")
	return prov.Bind(ctx, joinCode)
}

// writeErrorf writes a JSON error response with a formatted message.
// json.Marshal is used to safely escape the message string.
func writeErrorf(w http.ResponseWriter, format string, args ...any) {
	msg, _ := json.Marshal(fmt.Sprintf(format, args...))
	_, _ = w.Write([]byte(`{"error":` + string(msg) + `}`))
}
