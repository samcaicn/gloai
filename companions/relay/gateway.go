package main

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
)

const maxBody = 1 << 20

// Both ends must speak the same tunnel protocol; a phone offering another version is refused before upgrade.
const subprotocol = "mika-relay.v2"

type frame struct {
	Type    string            `json:"type"`
	ID      string            `json:"id,omitempty"`
	Method  string            `json:"method,omitempty"`
	Path    string            `json:"path,omitempty"`
	Query   string            `json:"query,omitempty"`
	Headers map[string]string `json:"headers,omitempty"`
	Body    []byte            `json:"body,omitempty"`
	Status  int               `json:"status,omitempty"`
	// Client is the caller's network identity as seen by the gateway. The phone keys its
	// failed-token cooldown on it, so one anonymous caller cannot lock out the others.
	Client string `json:"client,omitempty"`
}
type deviceLink struct {
	conn    *websocket.Conn
	mu      sync.Mutex
	pending map[string]chan frame
	done    chan struct{}
	once    sync.Once
}

func (l *deviceLink) close() { l.once.Do(func() { close(l.done); _ = l.conn.CloseNow() }) }
func (l *deviceLink) send(ctx context.Context, f frame) error {
	b, err := json.Marshal(f)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	return l.conn.Write(ctx, websocket.MessageText, b)
}

type limitEntry struct {
	Count int
	Until time.Time
}
type gateway struct {
	base, ownerHash string
	store           *store
	mu              sync.Mutex
	device          *deviceLink
	pairHash        string
	pairUntil       time.Time
	limits          map[string]limitEntry
	console         *consoleStore
	now             func() time.Time
	requestTimeout  time.Duration
	// trustProxy reads the caller's address from the reverse proxy's X-Forwarded-For entry.
	trustProxy bool
}

func newGateway(base, owner string, s *store) *gateway {
	return &gateway{base: base, ownerHash: digest(owner), store: s, limits: map[string]limitEntry{}, console: newConsoleStore(), now: time.Now, requestTimeout: 65 * time.Second}
}
func (g *gateway) disconnect() {
	g.mu.Lock()
	l := g.device
	g.device = nil
	g.mu.Unlock()
	if l != nil {
		l.close()
	}
}
func (g *gateway) limited(r *http.Request, category string, count int) bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	now := g.now()
	key := category + ":" + g.client(r)
	for k, v := range g.limits {
		if !now.Before(v.Until) {
			delete(g.limits, k)
		}
	}
	if len(g.limits) >= 1024 {
		return true
	}
	v := g.limits[key]
	if v.Until.IsZero() {
		v.Until = now.Add(time.Minute)
	}
	v.Count++
	g.limits[key] = v
	return v.Count > count
}

// client names the caller for rate limits and for the phone's failed-token cooldown. Behind a
// reverse proxy every connection arrives from the proxy, so with trustProxy the last
// X-Forwarded-For entry is used: the proxy appends it, callers can only add entries before it.
// It is read only when the direct peer is a loopback or private address, i.e. the local proxy.
// IPv6 callers are grouped by /64, the smallest block a single subscriber usually holds.
func (g *gateway) client(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	ip, err := netip.ParseAddr(host)
	if err != nil {
		return ""
	}
	if g.trustProxy && (ip.IsLoopback() || ip.IsPrivate()) {
		if values := r.Header.Values("X-Forwarded-For"); len(values) > 0 {
			parts := strings.Split(values[len(values)-1], ",")
			if forwarded, e := netip.ParseAddr(strings.TrimSpace(parts[len(parts)-1])); e == nil {
				ip = forwarded
			}
		}
	}
	ip = ip.WithZone("").Unmap()
	if ip.Is6() {
		prefix, _ := ip.Prefix(64)
		return prefix.String()
	}
	return ip.String()
}

func relayPath(path string) bool {
	return path == "/mcp" || path == "/api/v1" || strings.HasPrefix(path, "/api/v1/")
}
func (g *gateway) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/device" {
		c := http.NewResponseController(w)
		_ = c.SetReadDeadline(time.Now().Add(20 * time.Second))
		_ = c.SetWriteDeadline(time.Now().Add(80 * time.Second))
	}
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Referrer-Policy", "same-origin")
	if strings.HasPrefix(g.base, "https://") {
		w.Header().Set("Strict-Transport-Security", "max-age=31536000")
	}
	w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'self'; script-src 'self'; connect-src 'self'; img-src 'self' data:; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
	if !strings.EqualFold(r.Host, strings.TrimPrefix(strings.TrimPrefix(g.base, "https://"), "http://")) {
		failure(w, 400, "invalid_host")
		return
	}
	switch r.URL.Path {
	case "/style.css", "/app.js":
		g.asset(w, r)
	case "/language":
		g.language(w, r)
	case "/healthz":
		if r.Method != "GET" {
			failure(w, 405, "method_not_allowed")
			return
		}
		writeJSON(w, 200, map[string]string{"status": "ok"})
	case "/", "/admin":
		g.admin(w, r)
	case "/pair":
		g.pair(w, r)
	case "/device":
		g.deviceEndpoint(w, r)
	case "/app":
		g.consolePage(w, r)
	case "/console.css", "/console.js":
		g.asset(w, r)
	case "/web/session":
		g.consoleSession(w, r)
	case "/web/login":
		g.consoleLogin(w, r)
	case "/web/logout":
		g.consoleLogout(w, r)
	default:
		if strings.HasPrefix(r.URL.Path, "/web/api/") {
			g.consoleProxy(w, r)
		} else if relayPath(r.URL.Path) {
			g.proxy(w, r)
		} else {
			failure(w, 404, "not_found")
		}
	}
}

var errDeviceRevoked = errors.New("device revoked")

func (g *gateway) clearDevice(deviceToken string) error {
	g.mu.Lock()
	if deviceToken != "" && !matches(deviceToken, g.store.view().DeviceHash) {
		g.mu.Unlock()
		return errDeviceRevoked
	}
	if err := g.store.update(func(d *diskState) error { d.DeviceHash = ""; return nil }); err != nil {
		g.mu.Unlock()
		return err
	}
	g.pairHash = ""
	l := g.device
	g.device = nil
	g.mu.Unlock()
	if l != nil {
		l.close()
	}
	return nil
}

func (g *gateway) pair(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		failure(w, 405, "method_not_allowed")
		return
	}
	if r.Header.Get("Origin") != "" || g.limited(r, "pair", 5) {
		failure(w, 403, "access_denied")
		return
	}
	var input struct {
		Code string `json:"code"`
	}
	if !decode(w, r, &input) {
		return
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	if !g.now().Before(g.pairUntil) || !matches(input.Code, g.pairHash) {
		failure(w, 403, "invalid_pairing_code")
		return
	}
	deviceToken := secret()
	if err := g.store.update(func(d *diskState) error {
		if d.DeviceHash != "" {
			return errors.New("already paired")
		}
		d.DeviceHash = digest(deviceToken)
		return nil
	}); err != nil {
		failure(w, 409, "pairing_unavailable")
		return
	}
	g.pairHash = ""
	writeJSON(w, 200, map[string]string{"deviceToken": deviceToken, "mcpUrl": g.base + "/mcp"})
}
func (g *gateway) deviceEndpoint(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("Origin") != "" || !matches(bearer(r), g.store.view().DeviceHash) {
		failure(w, 401, "invalid_device_token")
		return
	}
	if r.Method == "DELETE" {
		if err := g.clearDevice(bearer(r)); err != nil {
			if errors.Is(err, errDeviceRevoked) {
				failure(w, 401, "invalid_device_token")
				return
			}
			failure(w, 500, "storage_unavailable")
			return
		}
		w.WriteHeader(204)
		return
	}
	if r.Method != "GET" {
		failure(w, 405, "method_not_allowed")
		return
	}
	// Refuse other protocol versions before upgrading, so the phone can show an update-required state.
	found := false
	for _, p := range strings.Split(r.Header.Get("Sec-WebSocket-Protocol"), ",") {
		found = found || strings.TrimSpace(p) == subprotocol
	}
	if !found {
		failure(w, 426, "update_phone_and_gateway")
		return
	}
	c, err := websocket.Accept(w, r, &websocket.AcceptOptions{Subprotocols: []string{subprotocol}})
	if err != nil {
		return
	}
	defer c.CloseNow()
	c.SetReadLimit(2 * maxBody)
	l := &deviceLink{conn: c, pending: map[string]chan frame{}, done: make(chan struct{})}
	g.mu.Lock()
	if !matches(bearer(r), g.store.view().DeviceHash) {
		g.mu.Unlock()
		return
	}
	previous := g.device
	g.device = l
	g.mu.Unlock()
	if previous != nil {
		previous.close()
	}
	defer func() {
		g.mu.Lock()
		if g.device == l {
			g.device = nil
		}
		g.mu.Unlock()
		l.close()
	}()
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	go func() {
		ticker := time.NewTicker(25 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-l.done:
				return
			case <-ticker.C:
				pctx, stop := context.WithTimeout(ctx, 10*time.Second)
				err := c.Ping(pctx)
				stop()
				if err != nil {
					l.close()
					return
				}
			}
		}
	}()
	for {
		kind, b, err := c.Read(ctx)
		if err != nil {
			return
		}
		var f frame
		if kind != websocket.MessageText || json.Unmarshal(b, &f) != nil || f.Type != "response" || f.ID == "" || f.Status < 200 || f.Status > 599 || len(f.Body) > maxBody || !safeHeaders(f.Headers) {
			return
		}
		l.mu.Lock()
		result := l.pending[f.ID]
		l.mu.Unlock()
		if result != nil {
			select {
			case result <- f:
			default:
			}
		}
	}
}
func safeHeaders(h map[string]string) bool {
	if len(h) > 16 {
		return false
	}
	total := 0
	for k, v := range h {
		total += len(k) + len(v)
		if strings.ContainsAny(k+v, "\r\n") {
			return false
		}
	}
	return total <= 8192
}

// Values are opaque to the gateway. Authentication and Origin policy belong to the phone.
var requestHeaders = map[string]bool{"authorization": true, "origin": true, "x-mika-client": true, "content-type": true, "accept": true, "mcp-protocol-version": true, "mcp-method": true, "mcp-name": true, "mcp-session-id": true, "last-event-id": true}
var responseHeaders = map[string]bool{"content-type": true, "allow": true, "retry-after": true, "www-authenticate": true, "mcp-protocol-version": true}

func proxyFailure(w http.ResponseWriter, r *http.Request, status int, code string) {
	if r.URL.Path != "/mcp" {
		writeJSON(w, status, map[string]any{"error": map[string]string{"code": code, "message": code}})
	} else {
		failure(w, status, code)
	}
}
func (g *gateway) proxy(w http.ResponseWriter, r *http.Request) {
	reject := func(status int, code string) { proxyFailure(w, r, status, code) }
	if g.limited(r, "relay", 240) {
		w.Header().Set("Retry-After", "60")
		reject(429, "rate_limited")
		return
	}
	if r.Method != "GET" && r.Method != "POST" && r.Method != "DELETE" {
		w.Header().Set("Allow", "GET, POST, DELETE")
		reject(405, "method_not_allowed")
		return
	}
	if len(r.URL.RawQuery) > 8192 {
		reject(414, "query_too_large")
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxBody))
	if err != nil {
		reject(413, "body_too_large")
		return
	}
	headers := map[string]string{}
	for k, v := range r.Header {
		lower := strings.ToLower(k)
		if requestHeaders[lower] {
			headers[lower] = strings.Join(v, ", ")
		}
	}
	if !safeHeaders(headers) {
		reject(400, "invalid_headers")
		return
	}
	g.mu.Lock()
	l := g.device
	g.mu.Unlock()
	if l == nil {
		reject(503, "device_offline")
		return
	}
	id := secret()
	reply := make(chan frame, 1)
	l.mu.Lock()
	if len(l.pending) >= 8 {
		l.mu.Unlock()
		reject(429, "device_busy")
		return
	}
	l.pending[id] = reply
	l.mu.Unlock()
	defer func() { l.mu.Lock(); delete(l.pending, id); l.mu.Unlock() }()
	ctx, cancel := context.WithTimeout(r.Context(), g.requestTimeout)
	defer cancel()
	if err = l.send(ctx, frame{Type: "request", ID: id, Method: r.Method, Path: r.URL.Path, Query: r.URL.RawQuery, Headers: headers, Body: body, Client: g.client(r)}); err != nil {
		l.close()
		reject(502, "delivery_unknown")
		return
	}
	select {
	case f := <-reply:
		for k, v := range f.Headers {
			if responseHeaders[strings.ToLower(k)] {
				w.Header().Set(k, v)
			}
		}
		w.WriteHeader(f.Status)
		_, _ = w.Write(f.Body)
	case <-l.done:
		reject(502, "delivery_unknown")
	case <-ctx.Done():
		cancelCtx, stop := context.WithTimeout(context.Background(), time.Second)
		_ = l.send(cancelCtx, frame{Type: "cancel", ID: id})
		stop()
		reject(504, "delivery_unknown")
	}
}
