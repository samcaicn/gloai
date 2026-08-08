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

// PasswordStore is the interface for dashboard password persistence.
// Implemented by dashboardauth.Store and launcherconfig.PasswordStore.
type PasswordStore interface {
	IsInitialized(ctx context.Context) (bool, error)
	SetPassword(ctx context.Context, plain string) error
	VerifyPassword(ctx context.Context, plain string) (bool, error)
}

// LauncherAuthRouteOpts configures dashboard auth handlers.
type LauncherAuthRouteOpts struct {
	SessionCookie string
	SecureCookie  func(*http.Request) bool
	// PasswordStore enables password login. It must be non-nil for auth to work.
	PasswordStore PasswordStore
	// StoreError holds the error returned when opening the password store. When
	// non-nil and PasswordStore is nil, auth endpoints fail closed with a
	// recovery message.
	StoreError error
	// ConfigPath is the path to the app config.json, used to read the tupai
	// model's api_base / device_token for the device bind flow. When empty,
	// bind falls back to dummy initialization.
	ConfigPath string
}

type launcherAuthLoginBody struct {
	Password string `json:"password"`
}

type launcherAuthSetupBody struct {
	Password string `json:"password"`
	Confirm  string `json:"confirm"`
}

type launcherAuthStatusResponse struct {
	Authenticated bool `json:"authenticated"`
	Initialized   bool `json:"initialized"`
}

// RegisterLauncherAuthRoutes registers /api/auth/login|logout|status|setup.
func RegisterLauncherAuthRoutes(mux *http.ServeMux, opts LauncherAuthRouteOpts) {
	secure := opts.SecureCookie
	if secure == nil {
		secure = middleware.DefaultLauncherDashboardSecureCookie
	}
	h := &launcherAuthHandlers{
		sessionCookie: opts.SessionCookie,
		secureCookie:  secure,
		store:         opts.PasswordStore,
		storeErr:      opts.StoreError,
		configPath:    opts.ConfigPath,
		loginLimit:    newLoginRateLimiter(),
	}
	mux.HandleFunc("POST /api/auth/login", h.handleLogin)
	mux.HandleFunc("POST /api/auth/logout", h.handleLogout)
	mux.HandleFunc("GET /api/auth/status", h.handleStatus)
	mux.HandleFunc("POST /api/auth/setup", h.handleSetup)
	mux.HandleFunc("POST /api/auth/bind", h.handleBind)
	mux.HandleFunc("POST /api/auth/reset", h.handleReset)
}

type launcherAuthHandlers struct {
	sessionCookie string
	secureCookie  func(*http.Request) bool
	store         PasswordStore
	storeErr      error // set when the store failed to open; drives recovery messages
	configPath    string
	loginLimit    *loginRateLimiter
}

// isStoreInitialized safely queries the store.
// Returns (false, err) on store errors — callers must treat this as a 5xx, not as
// "uninitialized", to keep auth fail-closed.
func (h *launcherAuthHandlers) isStoreInitialized(ctx context.Context) (bool, error) {
	if h.store == nil {
		if h.storeErr != nil {
			return false, fmt.Errorf(
				"password store unavailable (%w); "+
					"to recover, stop the application, reset dashboard password storage, and restart",
				h.storeErr)
		}
		return false, fmt.Errorf("password store not configured")
	}
	return h.store.IsInitialized(ctx)
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

func (h *launcherAuthHandlers) handleLogin(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	var body launcherAuthLoginBody
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&body); err != nil {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"error":"invalid JSON"}`))
		return
	}
	ip := clientIPForLimiter(r)
	if !h.loginLimit.allow(ip) {
		w.WriteHeader(http.StatusTooManyRequests)
		_, _ = w.Write([]byte(`{"error":"too many login attempts"}`))
		return
	}
	in := strings.TrimSpace(body.Password)

	initialized, initErr := h.isStoreInitialized(r.Context())
	if initErr != nil {
		w.WriteHeader(http.StatusServiceUnavailable)
		writeErrorf(w, "%v", initErr)
		return
	}
	if !initialized {
		w.WriteHeader(http.StatusConflict)
		_, _ = w.Write([]byte(`{"error":"password has not been set"}`))
		return
	}

	ok, err := h.store.VerifyPassword(r.Context(), in)
	if err != nil {
		w.WriteHeader(http.StatusInternalServerError)
		writeErrorf(w, "password verification failed: %v", err)
		return
	}
	if !ok {
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte(`{"error":"invalid password"}`))
		return
	}

	middleware.SetLauncherDashboardSessionCookie(w, r, h.sessionCookie, h.secureCookie)
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"status":"ok"}`))
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
	initialized, initErr := h.isStoreInitialized(r.Context())
	if initErr != nil {
		w.WriteHeader(http.StatusServiceUnavailable)
		writeErrorf(w, "%v", initErr)
		return
	}
	resp := launcherAuthStatusResponse{
		Authenticated: authed,
		Initialized:   initialized,
	}
	enc, err := json.Marshal(resp)
	if err != nil {
		w.WriteHeader(http.StatusInternalServerError)
		writeErrorf(w, "marshal response failed: %v", err)
		return
	}
	_, _ = w.Write(enc)
}

// handleSetup sets or changes the dashboard password.
//
// Rules:
//   - If the store has no password yet, anyone who can reach the setup endpoint
//     may initialize the password.
//   - If a password is already set, the caller must hold a valid session cookie.
func (h *launcherAuthHandlers) handleSetup(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")

	if launcherSetupCrossSite(r) {
		w.WriteHeader(http.StatusForbidden)
		_, _ = w.Write([]byte(`{"error":"cross-site setup request rejected"}`))
		return
	}

	if h.store == nil {
		w.WriteHeader(http.StatusServiceUnavailable)
		if h.storeErr != nil {
			writeErrorf(w, "password store unavailable: %v", h.storeErr)
		} else {
			_, _ = w.Write([]byte(`{"error":"password store not configured"}`))
		}
		return
	}

	initialized, initErr := h.isStoreInitialized(r.Context())
	if initErr != nil {
		w.WriteHeader(http.StatusServiceUnavailable)
		writeErrorf(w, "%v", initErr)
		return
	}

	// If already initialized, require an active session (change-password flow).
	if initialized {
		authed := false
		if c, err := r.Cookie(middleware.LauncherDashboardCookieName); err == nil {
			authed = subtle.ConstantTimeCompare([]byte(c.Value), []byte(h.sessionCookie)) == 1
		}
		if !authed {
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"error":"must be authenticated to change password"}`))
			return
		}
	}

	var body launcherAuthSetupBody
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&body); err != nil {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"error":"invalid JSON"}`))
		return
	}

	pw := strings.TrimSpace(body.Password)
	if pw == "" {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"error":"password must not be empty"}`))
		return
	}
	if pw != strings.TrimSpace(body.Confirm) {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"error":"passwords do not match"}`))
		return
	}
	if len([]rune(pw)) < 8 {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"error":"password must be at least 8 characters"}`))
		return
	}

	if err := h.store.SetPassword(r.Context(), pw); err != nil {
		w.WriteHeader(http.StatusInternalServerError)
		writeErrorf(w, "failed to save password: %v", err)
		return
	}

	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"status":"ok"}`))
}

// handleBind binds the dashboard using an 8-digit join code.
// If the store has no password yet, anyone who can reach the bind endpoint
// may initialize the binding. If a password is already set, the caller
// must hold a valid session cookie (change-bind flow).
func (h *launcherAuthHandlers) handleBind(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")

	if launcherSetupCrossSite(r) {
		w.WriteHeader(http.StatusForbidden)
		_, _ = w.Write([]byte(`{"error":"cross-site bind request rejected"}`))
		return
	}

	if h.store == nil {
		w.WriteHeader(http.StatusServiceUnavailable)
		if h.storeErr != nil {
			writeErrorf(w, "password store unavailable: %v", h.storeErr)
		} else {
			_, _ = w.Write([]byte(`{"error":"password store not configured"}`))
		}
		return
	}

	initialized, initErr := h.isStoreInitialized(r.Context())
	if initErr != nil {
		w.WriteHeader(http.StatusServiceUnavailable)
		writeErrorf(w, "%v", initErr)
		return
	}

	// If already initialized, require an active session (change-bind flow).
	if initialized {
		authed := false
		if c, err := r.Cookie(middleware.LauncherDashboardCookieName); err == nil {
			authed = subtle.ConstantTimeCompare([]byte(c.Value), []byte(h.sessionCookie)) == 1
		}
		if !authed {
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"error":"must be authenticated to change bind"}`))
			return
		}
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
		// Approval pending (e.g. iLink admin confirm). Do not mark initialized.
		resp := map[string]any{"status": "pending", "message": status.Message}
		if status.RequestID != "" {
			resp["request_id"] = status.RequestID
		}
		w.WriteHeader(http.StatusOK)
		enc, _ := json.Marshal(resp)
		_, _ = w.Write(enc)
		return
	}

	// Bind approved: mark initialized with the join code as the dashboard
	// password (so the same code logs the user in), and auto-login so the main
	// interface launches directly after binding.
	if err := h.store.SetPassword(r.Context(), code); err != nil {
		w.WriteHeader(http.StatusInternalServerError)
		writeErrorf(w, "failed to save bind: %v", err)
		return
	}
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

// handleReset clears the dashboard password (for testing/recovery).
func (h *launcherAuthHandlers) handleReset(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")

	if h.store == nil {
		w.WriteHeader(http.StatusServiceUnavailable)
		if h.storeErr != nil {
			writeErrorf(w, "password store unavailable: %v", h.storeErr)
		} else {
			_, _ = w.Write([]byte(`{"error":"password store not configured"}`))
		}
		return
	}

	// Clear password by setting empty string (implementation depends on store).
	// For SQLite store, this may require direct DB access.
	// For JSON config store, we can clear the hash.
	if err := h.store.SetPassword(r.Context(), ""); err != nil {
		w.WriteHeader(http.StatusInternalServerError)
		writeErrorf(w, "failed to reset password: %v", err)
		return
	}

	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"status":"ok"}`))
}
