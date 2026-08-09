package middleware

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestNewLauncherDashboardSessionCookie(t *testing.T) {
	a, err := NewLauncherDashboardSessionCookie()
	if err != nil {
		t.Fatalf("NewLauncherDashboardSessionCookie() error = %v", err)
	}
	b, err := NewLauncherDashboardSessionCookie()
	if err != nil {
		t.Fatalf("NewLauncherDashboardSessionCookie() second error = %v", err)
	}
	if a == "" || b == "" {
		t.Fatalf("session cookie values should be non-empty: %q %q", a, b)
	}
	if a == b {
		t.Fatal("session cookie values should be random")
	}
}

func TestLauncherDashboardAuth_AllowsPublicPaths(t *testing.T) {
	cfg := LauncherDashboardAuthConfig{ExpectedCookie: "deadbeef"}
	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusTeapot)
	})
	h := LauncherDashboardAuth(cfg, next)

	for _, tc := range []struct {
		method, path string
		want         int
	}{
		{http.MethodGet, "/launcher-setup", http.StatusTeapot},
		{http.MethodGet, "/assets/index.js", http.StatusTeapot},
		{http.MethodGet, "/api/auth/status", http.StatusTeapot},
		{http.MethodPost, "/api/auth/bind", http.StatusTeapot},
		{http.MethodPost, "/api/auth/logout", http.StatusTeapot},
		{http.MethodGet, "/api/auth/logout", http.StatusUnauthorized},
		{http.MethodGet, "/api/config", http.StatusUnauthorized},
		{http.MethodGet, "/pico/ws", http.StatusUnauthorized},
	} {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest(tc.method, tc.path, nil)
		h.ServeHTTP(rec, req)
		if rec.Code != tc.want {
			t.Fatalf("%s %s: status = %d, want %d", tc.method, tc.path, rec.Code, tc.want)
		}
	}
}

func TestLauncherDashboardAuth_RedirectsToSetupWhenUnauthenticated(t *testing.T) {
	cfg := LauncherDashboardAuthConfig{ExpectedCookie: "deadbeef"}
	next := http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		t.Fatal("next handler should not run without session cookie")
	})
	h := LauncherDashboardAuth(cfg, next)

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/?token=secret", nil)
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusFound || rec.Header().Get("Location") != LauncherDashboardSetupPath {
		t.Fatalf("GET /?token=secret: code=%d loc=%q", rec.Code, rec.Header().Get("Location"))
	}
}

func TestLauncherDashboardAuth_DotDotCannotBypass(t *testing.T) {
	cfg := LauncherDashboardAuthConfig{ExpectedCookie: "deadbeef"}
	next := http.HandlerFunc(func(_ http.ResponseWriter, _ *http.Request) {
		t.Fatal("next handler should not run without auth")
	})
	h := LauncherDashboardAuth(cfg, next)

	for _, p := range []string{
		"/assets/../api/config",
		"/launcher-setup/../api/config",
		"/./api/config",
	} {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, p, nil)
		h.ServeHTTP(rec, req)
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("%q: status = %d, want %d", p, rec.Code, http.StatusUnauthorized)
		}
	}
}

func TestLauncherDashboardAuth_CookieOnly(t *testing.T) {
	cookieVal := "session-cookie-value"
	cfg := LauncherDashboardAuthConfig{ExpectedCookie: cookieVal}
	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
	h := LauncherDashboardAuth(cfg, next)

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.AddCookie(&http.Cookie{Name: LauncherDashboardCookieName, Value: cookieVal})
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("cookie auth: status = %d", rec.Code)
	}

	rec2 := httptest.NewRecorder()
	req2 := httptest.NewRequest(http.MethodGet, "/api/config", nil)
	req2.Header.Set("Authorization", "Bearer dashboard-secret-9")
	h.ServeHTTP(rec2, req2)
	if rec2.Code != http.StatusUnauthorized {
		t.Fatalf("bearer auth should not be accepted: status = %d", rec2.Code)
	}
}

func TestLauncherDashboardAuth_WebSocketUnauthorizedDoesNotRedirect(t *testing.T) {
	cfg := LauncherDashboardAuthConfig{ExpectedCookie: "deadbeef"}
	next := http.HandlerFunc(func(_ http.ResponseWriter, _ *http.Request) {
		t.Fatal("next handler should not run without auth")
	})
	h := LauncherDashboardAuth(cfg, next)

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/pico/ws", nil)
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusUnauthorized)
	}
	if got := rec.Header().Get("Location"); got != "" {
		t.Fatalf("Location = %q, want empty", got)
	}
}
