package main

import (
	"context"
	"net/http"
	"strings"
	"sync"
	"time"
)

// The console keeps the phone access token in gateway memory only. It is never written to the
// pairing store, never sent to the browser, and disappears when the gateway restarts.
type consoleSession struct {
	token   string
	created time.Time
	seen    time.Time
}

const (
	consoleCookie      = "mika_console"
	consoleIdle        = 8 * time.Hour
	consoleMax         = 24 * time.Hour
	consoleMaxSessions = 16
)

type consoleStore struct {
	mu       sync.Mutex
	sessions map[string]*consoleSession
	now      func() time.Time
}

func newConsoleStore() *consoleStore {
	return &consoleStore{sessions: map[string]*consoleSession{}, now: time.Now}
}

func (c *consoleStore) open(token string) string {
	id := secret()
	c.mu.Lock()
	defer c.mu.Unlock()
	now := c.now()
	c.cleanupLocked(now)
	if len(c.sessions) >= consoleMaxSessions {
		var oldest string
		var at time.Time
		for k, s := range c.sessions {
			if oldest == "" || s.seen.Before(at) {
				oldest, at = k, s.seen
			}
		}
		delete(c.sessions, oldest)
	}
	c.sessions[id] = &consoleSession{token: token, created: now, seen: now}
	return id
}

func (c *consoleStore) token(id string) (string, bool) {
	if id == "" {
		return "", false
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	now := c.now()
	c.cleanupLocked(now)
	s := c.sessions[id]
	if s == nil {
		return "", false
	}
	s.seen = now
	return s.token, true
}

func (c *consoleStore) close(id string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.sessions, id)
}

func (c *consoleStore) cleanupLocked(now time.Time) {
	for k, s := range c.sessions {
		if now.Sub(s.seen) > consoleIdle || now.Sub(s.created) > consoleMax {
			delete(c.sessions, k)
		}
	}
}

// consoleStore returns the process-wide session table, creating it for tests that build a gateway literal.
func (g *gateway) consoleStore() *consoleStore {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.console == nil {
		g.console = newConsoleStore()
	}
	return g.console
}

func consolePath(path string) bool {
	switch path {
	case "/api/v1", "/api/v1/tasks", "/api/v1/running", "/api/v1/conversations":
		return true
	}
	for _, prefix := range []string{"/api/v1/tasks/", "/api/v1/conversations/"} {
		if rest, ok := strings.CutPrefix(path, prefix); ok {
			return rest != "" && !strings.Contains(rest, "/")
		}
	}
	return false
}

func (g *gateway) consolePage(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		failure(w, 405, "method_not_allowed")
		return
	}
	b, err := webFiles.ReadFile("web/console.html")
	if err != nil {
		failure(w, 404, "not_found")
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_, _ = w.Write(b)
}

func (g *gateway) consoleSession(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		failure(w, 405, "method_not_allowed")
		return
	}
	connected := false
	if c, err := r.Cookie(consoleCookie); err == nil {
		_, connected = g.consoleStore().token(c.Value)
	}
	writeJSON(w, 200, map[string]bool{"connected": connected})
}

func (g *gateway) consoleLogin(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		failure(w, 405, "method_not_allowed")
		return
	}
	if o := r.Header.Get("Origin"); o != "" && o != g.base {
		failure(w, 403, "invalid_origin")
		return
	}
	if g.limited(r, "console", 10) {
		w.Header().Set("Retry-After", "60")
		failure(w, 429, "rate_limited")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 4096)
	if err := r.ParseForm(); err != nil {
		failure(w, 400, "invalid_request")
		return
	}
	token := strings.TrimSpace(r.PostForm.Get("token"))
	if token == "" || len(token) > 512 || strings.ContainsAny(token, "\r\n") {
		failure(w, 400, "invalid_token")
		return
	}
	// The phone owns token validation. Only authenticated tokens can occupy a session slot.
	ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
	defer cancel()
	probe := r.Clone(ctx)
	probe.Method = http.MethodGet
	probe.URL.Path, probe.URL.RawPath, probe.URL.RawQuery = "/api/v1", "", ""
	probe.Body = http.NoBody
	probe.Header = http.Header{"Authorization": {"Bearer " + token}, "Accept": {"application/json"}}
	if forwarded := r.Header.Values("X-Forwarded-For"); len(forwarded) != 0 {
		probe.Header["X-Forwarded-For"] = forwarded
	}
	result := consoleAuthResponse{headers: make(http.Header)}
	g.proxy(&result, probe)
	switch result.status {
	case http.StatusOK:
	case http.StatusUnauthorized, http.StatusForbidden:
		failure(w, http.StatusUnauthorized, "invalid_token")
		return
	case http.StatusTooManyRequests:
		if retry := result.headers.Get("Retry-After"); retry != "" {
			w.Header().Set("Retry-After", retry)
		}
		failure(w, http.StatusTooManyRequests, "rate_limited")
		return
	default:
		failure(w, http.StatusServiceUnavailable, "device_unavailable")
		return
	}
	id := g.consoleStore().open(token)
	http.SetCookie(w, &http.Cookie{
		Name: consoleCookie, Value: id, Path: "/web", HttpOnly: true,
		Secure: strings.HasPrefix(g.base, "https://"), SameSite: http.SameSiteStrictMode,
		MaxAge: int(consoleIdle.Seconds()),
	})
	writeJSON(w, 200, map[string]bool{"connected": true})
}

// The login probe needs only the phone's status and optional retry delay.
type consoleAuthResponse struct {
	headers http.Header
	status  int
}

func (r *consoleAuthResponse) Header() http.Header            { return r.headers }
func (r *consoleAuthResponse) WriteHeader(status int)         { r.status = status }
func (r *consoleAuthResponse) Write(body []byte) (int, error) { return len(body), nil }

func (g *gateway) consoleLogout(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		failure(w, 405, "method_not_allowed")
		return
	}
	if o := r.Header.Get("Origin"); o != "" && o != g.base {
		failure(w, 403, "invalid_origin")
		return
	}
	if c, err := r.Cookie(consoleCookie); err == nil {
		g.consoleStore().close(c.Value)
	}
	http.SetCookie(w, &http.Cookie{
		Name: consoleCookie, Value: "", Path: "/web", HttpOnly: true,
		Secure: strings.HasPrefix(g.base, "https://"), SameSite: http.SameSiteStrictMode, MaxAge: -1,
	})
	writeJSON(w, 200, map[string]bool{"connected": false})
}

// consoleProxy forwards a read-only request to the phone. The phone rejects browser requests
// (Origin), so the gateway authenticates the browser session here and removes Origin before
// forwarding with the stored token. Only GET and a fixed path list are allowed.
func (g *gateway) consoleProxy(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		w.Header().Set("Allow", "GET")
		failure(w, 405, "method_not_allowed")
		return
	}
	c, err := r.Cookie(consoleCookie)
	if err != nil {
		failure(w, 401, "not_connected")
		return
	}
	token, ok := g.consoleStore().token(c.Value)
	if !ok {
		failure(w, 401, "not_connected")
		return
	}
	path := strings.TrimPrefix(r.URL.Path, "/web")
	if !consolePath(path) {
		failure(w, 404, "not_found")
		return
	}
	next := r.Clone(r.Context())
	next.URL.Path = path
	next.Header.Del("Cookie")
	next.Header.Del("Origin")
	next.Header.Set("Authorization", "Bearer "+token)
	next.Header.Set("Accept", "application/json")
	g.proxy(w, next)
}
