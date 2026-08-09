package middleware

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"net/http"
	"path"
	"strings"
	"time"
)

// LauncherDashboardCookieName is the HttpOnly cookie set after a successful bind.
const LauncherDashboardCookieName = "colearn_launcher_auth"

// launcherDashboardSessionMaxAgeSec is the dashboard session cookie lifetime.
// The bind-only flow authenticates once and never expires the session.
const launcherDashboardSessionMaxAgeSec = 10 * 365 * 24 * 3600

const (
	launcherSessionCookieBytes = 32
	// LauncherDashboardSetupPath is the bind page used before the dashboard is
	// authenticated. It is the only launcher auth page (password login removed).
	LauncherDashboardSetupPath = "/launcher-setup"
)

// NewLauncherDashboardSessionCookie creates the per-process session cookie value.
func NewLauncherDashboardSessionCookie() (string, error) {
	return randomURLToken(launcherSessionCookieBytes)
}

func randomURLToken(n int) (string, error) {
	buf := make([]byte, n)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(buf), nil
}

// LauncherDashboardAuthConfig holds runtime material for dashboard access checks.
type LauncherDashboardAuthConfig struct {
	ExpectedCookie string
	// SecureCookie sets the session cookie's Secure flag. If nil, DefaultLauncherDashboardSecureCookie is used.
	SecureCookie func(*http.Request) bool
}

// DefaultLauncherDashboardSecureCookie mirrors typical production HTTPS detection (TLS or X-Forwarded-Proto).
func DefaultLauncherDashboardSecureCookie(r *http.Request) bool {
	if r.TLS != nil {
		return true
	}
	return strings.EqualFold(r.Header.Get("X-Forwarded-Proto"), "https")
}

// SetLauncherDashboardSessionCookie writes the HttpOnly session cookie after a successful bind.
func SetLauncherDashboardSessionCookie(
	w http.ResponseWriter,
	r *http.Request,
	sessionValue string,
	secure func(*http.Request) bool,
) {
	if secure == nil {
		secure = DefaultLauncherDashboardSecureCookie
	}
	http.SetCookie(w, &http.Cookie{
		Name:     LauncherDashboardCookieName,
		Value:    sessionValue,
		Path:     "/",
		MaxAge:   launcherDashboardSessionMaxAgeSec,
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		Secure:   secure(r),
	})
}

// ClearLauncherDashboardSessionCookie clears the dashboard session (e.g. logout).
func ClearLauncherDashboardSessionCookie(w http.ResponseWriter, r *http.Request, secure func(*http.Request) bool) {
	if secure == nil {
		secure = DefaultLauncherDashboardSecureCookie
	}
	http.SetCookie(w, &http.Cookie{
		Name:     LauncherDashboardCookieName,
		Value:    "",
		Path:     "/",
		MaxAge:   -1,
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		Secure:   secure(r),
		Expires:  time.Unix(0, 0),
	})
}

// LauncherDashboardAuth requires a valid session cookie before calling next.
// Public paths are the bind page and /api/auth/* handlers.
func LauncherDashboardAuth(cfg LauncherDashboardAuthConfig, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		p := canonicalAuthPath(r.URL.Path)
		if isPublicLauncherDashboardPath(r.Method, p) {
			next.ServeHTTP(w, r)
			return
		}
		if validLauncherDashboardAuth(r, cfg) {
			next.ServeHTTP(w, r)
			return
		}
		rejectLauncherDashboardAuth(w, r, p)
	})
}

// canonicalAuthPath matches path cleaning used for routing decisions so
// prefixes like /assets/../ cannot bypass auth (CVE-class traversal).

func canonicalAuthPath(raw string) string {
	if raw == "" {
		return "/"
	}
	c := path.Clean(raw)
	switch c {
	case ".", "":
		return "/"
	default:
		if c[0] != '/' {
			return "/" + c
		}
		return c
	}
}

func isPublicLauncherDashboardPath(method, p string) bool {
	if isPublicLauncherDashboardStatic(method, p) {
		return true
	}
	switch p {
	case "/api/auth/logout":
		return method == http.MethodPost
	case "/api/auth/status":
		return method == http.MethodGet
	case "/api/auth/bind":
		return method == http.MethodPost
	}
	return false
}

// isPublicLauncherDashboardStatic allows the SPA bind route and embedded
// frontend assets without a session (GET/HEAD only).
func isPublicLauncherDashboardStatic(method, p string) bool {
	if method != http.MethodGet && method != http.MethodHead {
		return false
	}
	if p == LauncherDashboardSetupPath {
		return true
	}
	if strings.HasPrefix(p, "/assets/") {
		return true
	}
	switch p {
	case "/favicon.ico", "/favicon.svg", "/favicon-96x96.png",
		"/apple-touch-icon.png", "/site.webmanifest", "/robots.txt":
		return true
	default:
		return false
	}
}

func validLauncherDashboardAuth(r *http.Request, cfg LauncherDashboardAuthConfig) bool {
	if c, err := r.Cookie(LauncherDashboardCookieName); err == nil {
		if subtle.ConstantTimeCompare([]byte(c.Value), []byte(cfg.ExpectedCookie)) == 1 {
			return true
		}
	}
	return false
}

func rejectLauncherDashboardAuth(w http.ResponseWriter, r *http.Request, canonicalPath string) {
	if canonicalPath == "/pico/ws" {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	if strings.HasPrefix(canonicalPath, "/api/") {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte(`{"error":"unauthorized"}`))
		return
	}
	http.Redirect(w, r, LauncherDashboardSetupPath, http.StatusFound)
}
