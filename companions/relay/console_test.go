package main

import (
	"io"
	"net/http"
	"net/url"
	"strings"
	"testing"

	"github.com/coder/websocket"
)

func expectConsoleAuth(t *testing.T, c *websocket.Conn, token string, status int) <-chan struct{} {
	t.Helper()
	done := make(chan struct{})
	go func() {
		defer close(done)
		request := readFrame(t, c)
		if request.Method != http.MethodGet || request.Path != "/api/v1" || request.Headers["authorization"] != "Bearer "+token {
			t.Errorf("unexpected login probe: method=%s path=%s", request.Method, request.Path)
		}
		sendFrame(t, c, frame{Type: "response", ID: request.ID, Status: status})
	}()
	return done
}

func consoleLogin(t *testing.T, f *fixture, c *websocket.Conn) *http.Cookie {
	t.Helper()
	auth := expectConsoleAuth(t, c, f.token, http.StatusOK)
	form := url.Values{"token": {f.token}}
	status, headers, _ := f.request(t, "POST", "/web/login", "", form.Encode(), "application/x-www-form-urlencoded")
	if status != 200 {
		t.Fatalf("login status %d", status)
	}
	<-auth
	response := &http.Response{Header: headers}
	cookies := response.Cookies()
	if len(cookies) == 0 || cookies[0].Name != consoleCookie || !cookies[0].HttpOnly {
		t.Fatalf("missing console cookie: %v", cookies)
	}
	return cookies[0]
}

func TestConsolePageAndSessionLifecycle(t *testing.T) {
	f := newFixture(t)
	status, headers, page := f.request(t, "GET", "/app", "", "", "")
	if status != 200 || !strings.Contains(string(page), "Mika Anywhere") {
		t.Fatalf("console page: %d", status)
	}
	if csp := headers.Get("Content-Security-Policy"); !strings.Contains(csp, "connect-src 'self'") {
		t.Fatalf("console CSP does not allow same-origin fetch: %q", csp)
	}

	status, _, body := f.request(t, "GET", "/web/session", "", "", "")
	if status != 200 || !strings.Contains(string(body), `"connected":false`) {
		t.Fatalf("session before login: %d %s", status, body)
	}

	f.pair(t)
	cookie := consoleLogin(t, f, f.connect(t))
	status, _, body = requestWithCookie(t, f, "GET", "/web/session", cookie)
	if status != 200 || !strings.Contains(string(body), `"connected":true`) {
		t.Fatalf("session after login: %d %s", status, body)
	}

	status, _, body = requestWithCookie(t, f, "POST", "/web/logout", cookie)
	if status != 200 || !strings.Contains(string(body), `"connected":false`) {
		t.Fatalf("logout: %d %s", status, body)
	}
	status, _, _ = requestWithCookie(t, f, "GET", "/web/session", cookie)
	if status != 200 {
		t.Fatalf("session request after logout: %d", status)
	}
	_, _, body = requestWithCookie(t, f, "GET", "/web/session", cookie)
	if !strings.Contains(string(body), `"connected":false`) {
		t.Fatalf("session still connected after logout: %s", body)
	}
}

func TestConsoleRequiresSessionAndOnlyAllowsReadPaths(t *testing.T) {
	f := newFixture(t)
	if status, _, _ := f.request(t, "GET", "/web/api/v1/conversations", "", "", ""); status != 401 {
		t.Fatalf("unauthenticated console request: %d", status)
	}
	f.pair(t)
	cookie := consoleLogin(t, f, f.connect(t))
	if status, _, _ := requestWithCookie(t, f, "GET", "/web/api/v1/tasks/1/cancel", cookie); status != 404 {
		t.Fatalf("write path should not be reachable from the console: %d", status)
	}
	if status, _, _ := requestWithCookie(t, f, "POST", "/web/api/v1/conversations", cookie); status != 405 {
		t.Fatalf("console proxy should be GET only: %d", status)
	}
}

func TestConsoleForwardsWithStoredTokenAndStripsBrowserOrigin(t *testing.T) {
	f := newFixture(t)
	f.pair(t)
	c := f.connect(t)
	cookie := consoleLogin(t, f, c)

	done := make(chan struct{})
	go func() {
		defer close(done)
		request := readFrame(t, c)
		if request.Path != "/api/v1/conversations" {
			t.Errorf("path = %q", request.Path)
		}
		if request.Headers["authorization"] != "Bearer "+f.token {
			t.Errorf("authorization = %q", request.Headers["authorization"])
		}
		if _, ok := request.Headers["origin"]; ok {
			t.Errorf("browser Origin must not reach the phone: %v", request.Headers)
		}
		if request.Headers["cookie"] != "" {
			t.Errorf("browser cookies must not reach the phone: %v", request.Headers)
		}
		sendFrame(t, c, frame{
			Type: "response", ID: request.ID, Status: 200,
			Headers: map[string]string{"content-type": "application/json"},
			Body:    []byte(`{"conversations":[]}`),
		})
	}()

	req, err := http.NewRequest("GET", f.server.URL+"/web/api/v1/conversations?limit=5", nil)
	if err != nil {
		t.Fatal(err)
	}
	req.AddCookie(cookie)
	req.Header.Set("Origin", f.server.URL)
	response, err := f.client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	body, _ := io.ReadAll(response.Body)
	<-done
	if response.StatusCode != 200 || !strings.Contains(string(body), `"conversations":[]`) {
		t.Fatalf("proxied response: %d %s", response.StatusCode, body)
	}
}

func TestConsoleLoginRejectsForeignOrigin(t *testing.T) {
	f := newFixture(t)
	form := url.Values{"token": {"t"}}
	req, _ := http.NewRequest("POST", f.server.URL+"/web/login", strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Origin", "https://attacker.example")
	response, err := f.client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != 403 {
		t.Fatalf("foreign origin login: %d", response.StatusCode)
	}
}

func TestConsoleLoginRequiresAnOnlinePhone(t *testing.T) {
	f := newFixture(t)
	form := url.Values{"token": {"unverified-token"}}
	status, headers, _ := f.request(t, "POST", "/web/login", "", form.Encode(), "application/x-www-form-urlencoded")
	if status != http.StatusServiceUnavailable || headers.Get("Set-Cookie") != "" {
		t.Fatalf("offline login: status=%d cookie=%q", status, headers.Get("Set-Cookie"))
	}
}

func TestConsoleInvalidTokenDoesNotOccupyASessionSlot(t *testing.T) {
	f := newFixture(t)
	f.pair(t)
	c := f.connect(t)
	owner := consoleLogin(t, f, c)
	auth := expectConsoleAuth(t, c, "invalid-token", http.StatusUnauthorized)
	form := url.Values{"token": {"invalid-token"}}
	status, headers, _ := f.request(t, "POST", "/web/login", "", form.Encode(), "application/x-www-form-urlencoded")
	if status != http.StatusUnauthorized || headers.Get("Set-Cookie") != "" {
		t.Fatalf("invalid login: status=%d cookie=%q", status, headers.Get("Set-Cookie"))
	}
	<-auth
	store := f.g.consoleStore()
	store.mu.Lock()
	count := len(store.sessions)
	store.mu.Unlock()
	if count != 1 {
		t.Fatalf("invalid login changed the session count to %d", count)
	}
	if _, ok := store.token(owner.Value); !ok {
		t.Fatal("authenticated owner's session was lost")
	}
}

func requestWithCookie(t *testing.T, f *fixture, method, path string, cookie *http.Cookie) (int, http.Header, []byte) {
	t.Helper()
	req, err := http.NewRequest(method, f.server.URL+path, nil)
	if err != nil {
		t.Fatal(err)
	}
	req.AddCookie(cookie)
	response, err := f.client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	return response.StatusCode, response.Header, body
}
