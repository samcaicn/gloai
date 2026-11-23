package main

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
)

const testOwner = "test-owner-key-at-least-thirty-two-characters"

type fixture struct {
	g             *gateway
	server        *httptest.Server
	client        *http.Client
	device, token string
}

func newFixture(t *testing.T) *fixture {
	t.Helper()
	s, err := openStore(filepath.Join(t.TempDir(), "relay.json"))
	if err != nil {
		t.Fatal(err)
	}
	g := newGateway("", testOwner, s)
	server := httptest.NewServer(g)
	g.base = server.URL
	f := &fixture{g: g, server: server, client: &http.Client{Timeout: 3 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}}
	t.Cleanup(func() { g.disconnect(); server.Close() })
	return f
}

func (f *fixture) request(t *testing.T, method, path, token, body, kind string) (int, http.Header, []byte) {
	t.Helper()
	req, err := http.NewRequest(method, f.server.URL+path, strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	if kind != "" {
		req.Header.Set("Content-Type", kind)
	}
	r, err := f.client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer r.Body.Close()
	b, err := io.ReadAll(r.Body)
	if err != nil {
		t.Fatal(err)
	}
	return r.StatusCode, r.Header, b
}

func (f *fixture) pair(t *testing.T) string {
	t.Helper()
	form := url.Values{"owner_key": {testOwner}, "action": {"pair"}}
	status, _, html := f.request(t, "POST", "/admin", "", form.Encode(), "application/x-www-form-urlencoded")
	if status != 200 {
		t.Fatalf("admin status %d", status)
	}
	m := regexp.MustCompile(`<strong>([A-Za-z0-9_-]{16})</strong>`).FindSubmatch(html)
	if m == nil {
		t.Fatal("missing pairing code")
	}
	code := string(m[1])
	f.token = secret()
	input, _ := json.Marshal(map[string]string{"code": code})
	status, _, body := f.request(t, "POST", "/pair", "", string(input), "application/json")
	if status != 200 {
		t.Fatalf("pair status %d", status)
	}
	var response map[string]string
	_ = json.Unmarshal(body, &response)
	f.device = response["deviceToken"]
	if f.device == "" || response["mcpUrl"] != f.server.URL+"/mcp" || f.device == f.token {
		t.Fatal("invalid pairing")
	}
	return string(input)
}

func (f *fixture) connect(t *testing.T) *websocket.Conn {
	t.Helper()
	c, _, err := websocket.Dial(context.Background(), strings.Replace(f.server.URL, "http:", "ws:", 1)+"/device", &websocket.DialOptions{
		HTTPHeader: http.Header{"Authorization": []string{"Bearer " + f.device}}, Subprotocols: []string{subprotocol},
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.CloseNow() })
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		f.g.mu.Lock()
		ready := f.g.device != nil
		f.g.mu.Unlock()
		if ready {
			return c
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal("device not online")
	return nil
}

func readFrame(t *testing.T, c *websocket.Conn) frame {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	_, b, err := c.Read(ctx)
	if err != nil {
		t.Error(err)
		return frame{}
	}
	var f frame
	if err = json.Unmarshal(b, &f); err != nil {
		t.Error(err)
	}
	return f
}

func sendFrame(t *testing.T, c *websocket.Conn, f frame) {
	t.Helper()
	b, _ := json.Marshal(f)
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := c.Write(ctx, websocket.MessageText, b); err != nil {
		t.Error(err)
	}
}

func TestPairingIsSingleUsePersistsOnlyHashesAndSeparatesCredentials(t *testing.T) {
	f := newFixture(t)
	input := f.pair(t)
	status, _, _ := f.request(t, "POST", "/pair", "", input, "application/json")
	if status != 403 {
		t.Fatalf("replay status %d", status)
	}
	status, _, _ = f.request(t, "POST", "/mcp", f.device, `{}`, "application/json")
	if status != 503 {
		t.Fatal("offline response should not authenticate the phone access token")
	}
	status, _, _ = f.request(t, "GET", "/device", f.token, "", "")
	if status != 401 {
		t.Fatal("client credential accepted as device credential")
	}
	d := f.g.store.view()
	b, _ := json.Marshal(d)
	if bytes.Contains(b, []byte(f.device)) || bytes.Contains(b, []byte(f.token)) {
		t.Fatal("plaintext token persisted")
	}
	reopened, err := openStore(f.g.store.path)
	if err != nil || !matches(f.device, reopened.view().DeviceHash) {
		t.Fatal("state not persisted")
	}
}

func TestPairExpiryAndBrowserGuards(t *testing.T) {
	f := newFixture(t)
	now := time.Now()
	f.g.now = func() time.Time { return now }
	_, _, _ = f.request(t, "POST", "/admin", "", url.Values{"owner_key": {testOwner}, "action": {"pair"}}.Encode(), "application/x-www-form-urlencoded")
	f.g.mu.Lock()
	codeHash := f.g.pairHash
	f.g.mu.Unlock()
	if codeHash == "" {
		t.Fatal("code not created")
	}
	now = now.Add(6 * time.Minute)
	status, _, _ := f.request(t, "POST", "/pair", "", `{"code":"expired"}`, "application/json")
	if status != 403 {
		t.Fatal(status)
	}
	req, _ := http.NewRequest("POST", f.server.URL+"/admin", strings.NewReader("owner_key="+testOwner+"&action=pair"))
	req.Header.Set("Origin", "https://evil.example")
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	r, err := f.client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	r.Body.Close()
	if r.StatusCode != 403 {
		t.Fatal("cross-origin admin request accepted")
	}
}

func TestProxyPreservesMcpMetadataAndOpaquePhoneAuthorization(t *testing.T) {
	f := newFixture(t)
	f.pair(t)
	c := f.connect(t)
	done := make(chan struct{})
	go func() {
		defer close(done)
		request := readFrame(t, c)
		if request.Type != "request" || request.Path != "/mcp" || request.Headers["authorization"] != "Bearer "+f.token || request.Headers["mcp-method"] != "tools/list" || string(request.Body) != `{"jsonrpc":"2.0"}` {
			t.Error("request changed")
		}
		sendFrame(t, c, frame{Type: "response", ID: request.ID, Status: 200, Headers: map[string]string{"content-type": "application/json", "set-cookie": "not-forwarded"}, Body: []byte(`{"ok":true}`)})
	}()
	req, _ := http.NewRequest("POST", f.server.URL+"/mcp", strings.NewReader(`{"jsonrpc":"2.0"}`))
	req.Header.Set("Authorization", "Bearer "+f.token)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Mcp-Method", "tools/list")
	r, err := f.client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(r.Body)
	r.Body.Close()
	<-done
	if r.StatusCode != 200 || string(b) != `{"ok":true}` || r.Header.Get("Set-Cookie") != "" {
		t.Fatal("response not preserved or cookie leaked")
	}
}

func TestOfflineDisconnectAndRevocationNeverQueueOrReplay(t *testing.T) {
	f := newFixture(t)
	f.pair(t)
	status, _, b := f.request(t, "POST", "/mcp", f.token, `{}`, "application/json")
	if status != 503 || !bytes.Contains(b, []byte("device_offline")) {
		t.Fatal("offline request queued")
	}
	c := f.connect(t)
	done := make(chan struct{})
	go func() { defer close(done); _ = readFrame(t, c); _ = c.CloseNow() }()
	status, _, b = f.request(t, "POST", "/mcp", f.token, `{}`, "application/json")
	<-done
	if status != 502 || !bytes.Contains(b, []byte("delivery_unknown")) {
		t.Fatal("disconnect incorrectly reported as not executed")
	}
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		f.g.mu.Lock()
		off := f.g.device == nil
		f.g.mu.Unlock()
		if off {
			break
		}
		time.Sleep(time.Millisecond)
	}
	c = f.connect(t)
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	_, _, err := c.Read(ctx)
	if err == nil {
		t.Fatal("request replayed on reconnect")
	}
	status, _, _ = f.request(t, "DELETE", "/device", f.device, "", "")
	if status != 204 {
		t.Fatal(status)
	}
	status, _, _ = f.request(t, "POST", "/mcp", f.token, `{}`, "application/json")
	if status != 503 {
		t.Fatal("unpaired device must be offline")
	}
}

func TestTimeoutSendsCancellationAndRejectsOversizeBody(t *testing.T) {
	f := newFixture(t)
	f.pair(t)
	f.g.requestTimeout = 50 * time.Millisecond
	c := f.connect(t)
	done := make(chan struct{})
	go func() {
		defer close(done)
		request := readFrame(t, c)
		cancel := readFrame(t, c)
		if cancel.Type != "cancel" || cancel.ID != request.ID {
			t.Error("missing cancellation")
		}
	}()
	status, _, _ := f.request(t, "POST", "/mcp", f.token, `{}`, "application/json")
	<-done
	if status != 504 {
		t.Fatal(status)
	}
	status, _, _ = f.request(t, "POST", "/mcp", f.token, strings.Repeat("x", maxBody+1), "application/json")
	if status != 413 {
		t.Fatal(status)
	}
}

func TestPhoneDecidesAuthorizationAndNoOAuthEndpointsRemain(t *testing.T) {
	f := newFixture(t)
	f.pair(t)
	c := f.connect(t)
	done := make(chan struct{})
	go func() {
		defer close(done)
		for _, token := range []string{"", "Bearer arbitrary-client-value"} {
			request := readFrame(t, c)
			if request.Headers["authorization"] != token {
				t.Error("opaque token changed")
			}
			sendFrame(t, c, frame{Type: "response", ID: request.ID, Status: 401, Headers: map[string]string{"www-authenticate": "Bearer"}, Body: []byte(`{"error":"phone_denied"}`)})
		}
	}()
	for _, token := range []string{"", "arbitrary-client-value"} {
		status, h, b := f.request(t, "POST", "/mcp", token, `{}`, "application/json")
		if status != 401 || h.Get("WWW-Authenticate") != "Bearer" || !bytes.Contains(b, []byte("phone_denied")) {
			t.Fatal("phone authentication response changed")
		}
	}
	<-done
	for _, path := range []string{"/.well-known/oauth-protected-resource/mcp", "/.well-known/oauth-authorization-server", "/authorize", "/token", "/register"} {
		status, _, _ := f.request(t, "GET", path, "", "", "")
		if status != 404 {
			t.Fatalf("old authorization endpoint %s status %d", path, status)
		}
	}
}

func TestCliMethodsQueryAndClientMetadataAreTransparent(t *testing.T) {
	f := newFixture(t)
	f.pair(t)
	c := f.connect(t)
	done := make(chan struct{})
	go func() {
		defer close(done)
		for _, method := range []string{"GET", "POST", "DELETE"} {
			request := readFrame(t, c)
			if request.Method != method || request.Path != "/api/v1/tasks" || request.Query != "limit=2&source=remote%20test" || request.Headers["x-mika-client"] != "cli-test" || request.Headers["origin"] != "https://browser.example" {
				t.Error("CLI request changed")
			}
			sendFrame(t, c, frame{Type: "response", ID: request.ID, Status: 202, Body: []byte(`{"ok":true}`)})
		}
	}()
	for _, method := range []string{"GET", "POST", "DELETE"} {
		req, _ := http.NewRequest(method, f.server.URL+"/api/v1/tasks?limit=2&source=remote%20test", strings.NewReader(`{}`))
		req.Header.Set("X-Mika-Client", "cli-test")
		req.Header.Set("Origin", "https://browser.example")
		r, err := f.client.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		r.Body.Close()
		if r.StatusCode != 202 {
			t.Fatal(r.StatusCode)
		}
	}
	<-done
}

func TestOtherPhoneProtocolIsRejectedBeforeUpgrade(t *testing.T) {
	f := newFixture(t)
	f.pair(t)
	c, r, err := websocket.Dial(context.Background(), strings.Replace(f.server.URL, "http:", "ws:", 1)+"/device", &websocket.DialOptions{HTTPHeader: http.Header{"Authorization": []string{"Bearer " + f.device}}, Subprotocols: []string{"mika-relay.v1"}})
	if c != nil {
		c.CloseNow()
	}
	if err == nil || r == nil || r.StatusCode != 426 {
		t.Fatal("phone with another protocol version accepted")
	}
}

func TestRevokedDeviceCannotUnpairItsReplacement(t *testing.T) {
	f := newFixture(t)
	f.pair(t)
	old := f.device
	if err := f.g.clearDevice(old); err != nil {
		t.Fatal(err)
	}
	f.pair(t)
	if err := f.g.clearDevice(old); err != errDeviceRevoked {
		t.Fatal("old device removed replacement")
	}
	if !matches(f.device, f.g.store.view().DeviceHash) {
		t.Fatal("replacement pairing lost")
	}
}

func TestCallerIdentityComesFromTheLocalProxyOnly(t *testing.T) {
	g := &gateway{}
	req := func(remote string, forwarded ...string) *http.Request {
		r := httptest.NewRequest("GET", "/mcp", nil)
		r.RemoteAddr = remote
		for _, v := range forwarded {
			r.Header.Add("X-Forwarded-For", v)
		}
		return r
	}
	if got := g.client(req("127.0.0.1:5000", "203.0.113.9")); got != "127.0.0.1" {
		t.Fatalf("header trusted without -trust-proxy: %q", got)
	}
	g.trustProxy = true
	cases := []struct {
		r    *http.Request
		want string
	}{
		// The proxy appends the real caller last; earlier entries come from the caller and are ignored.
		{req("127.0.0.1:5000", "10.9.9.9, 203.0.113.9"), "203.0.113.9"},
		{req("172.18.0.3:5000", "1.1.1.1", "203.0.113.9"), "203.0.113.9"},
		{req("198.51.100.1:5000", "203.0.113.9"), "198.51.100.1"},
		{req("127.0.0.1:5000", "not-an-address"), "127.0.0.1"},
		{req("127.0.0.1:5000", "2001:db8:1:2:aaaa::1"), "2001:db8:1:2::/64"},
		{req("127.0.0.1:5000", "::ffff:203.0.113.9"), "203.0.113.9"},
		{req("127.0.0.1:5000"), "127.0.0.1"},
	}
	for i, c := range cases {
		if got := g.client(c.r); got != c.want {
			t.Errorf("case %d: got %q, want %q", i, got, c.want)
		}
	}
}

func TestOneCallerCannotExhaustAnotherCallersLimits(t *testing.T) {
	f := newFixture(t)
	f.g.trustProxy = true
	post := func(caller string) int {
		form := url.Values{"owner_key": {"wrong"}, "action": {"pair"}}
		req, _ := http.NewRequest("POST", f.server.URL+"/admin", strings.NewReader(form.Encode()))
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		req.Header.Set("X-Forwarded-For", caller)
		r, err := f.client.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		r.Body.Close()
		return r.StatusCode
	}
	for i := 0; i < 5; i++ {
		post("203.0.113.9")
	}
	if s := post("203.0.113.9"); s != 429 {
		t.Fatalf("attacker not limited: %d", s)
	}
	if s := post("198.51.100.7"); s != 403 {
		t.Fatalf("owner blocked by another caller: %d", s)
	}
}

func TestForwardedRequestCarriesCallerForPhoneCooldown(t *testing.T) {
	f := newFixture(t)
	f.g.trustProxy = true
	f.pair(t)
	c := f.connect(t)
	done := make(chan struct{})
	go func() {
		defer close(done)
		req, _ := http.NewRequest("GET", f.server.URL+"/api/v1", nil)
		req.Header.Set("X-Forwarded-For", "203.0.113.9")
		if r, err := f.client.Do(req); err == nil {
			r.Body.Close()
		}
	}()
	in := readFrame(t, c)
	if in.Client != "203.0.113.9" {
		t.Fatalf("caller not forwarded: %q", in.Client)
	}
	if _, ok := in.Headers["x-forwarded-for"]; ok {
		t.Fatal("proxy header must not reach the phone")
	}
	sendFrame(t, c, frame{Type: "response", ID: in.ID, Status: 200})
	<-done
}
