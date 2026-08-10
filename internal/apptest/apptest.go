// Package apptest provides the shared harness for HTTP integration tests.
//
// It spins up a full api.Server (the same wiring used in production) and exposes
// convenience helpers so per-domain tests can focus on behavior. The data source
// is dual-backend (see OpenStore): Postgres when TEST_DATABASE_URL (or the
// default local DSN) is reachable, otherwise an in-process sqlite database — the
// same pattern internal/store/storetest uses to run CRUD tests against both
// backends, so the integration suite is self-contained with no external DB.
package apptest

import (
	"bytes"
	"database/sql"
	"encoding/json"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/ceoadmin/CEOadmin/internal/api"
	authapi "github.com/ceoadmin/CEOadmin/internal/api/auth"
	appdelivery "github.com/ceoadmin/CEOadmin/internal/app"
	"github.com/ceoadmin/CEOadmin/internal/auth"
	"github.com/ceoadmin/CEOadmin/internal/bot"
	"github.com/ceoadmin/CEOadmin/internal/config"
	"github.com/ceoadmin/CEOadmin/internal/provider/ilink/mockserver"
	"github.com/ceoadmin/CEOadmin/internal/relay"
	"github.com/ceoadmin/CEOadmin/internal/sink"
	"github.com/ceoadmin/CEOadmin/internal/store"
	"github.com/ceoadmin/CEOadmin/internal/store/postgres"
	"github.com/ceoadmin/CEOadmin/internal/store/sqlite"
	"github.com/gorilla/websocket"
)

// Env is the shared integration-test environment: a full api.Server backed by
// Postgres plus HTTP/WebSocket helpers.
type Env struct {
	T        *testing.T
	Store    store.Store
	Srv      *httptest.Server
	Client   *http.Client
	Mgr      *bot.Manager
	Hub      *relay.Hub
	Cfg      *config.Config
	AppWSHub *appdelivery.WSHub
}

// OpenStore opens the test data source, preferring Postgres when reachable and
// otherwise falling back to an in-process sqlite database. This mirrors the
// internal/store/storetest dual-backend pattern (tests parameterized by
// store.Store run against both backends) so the integration suite is
// self-contained with no external database required.
func OpenStore(t *testing.T) store.Store {
	t.Helper()
	if s := tryPostgres(t); s != nil {
		return s
	}
	return openSQLite(t)
}

// tryPostgres attempts to open the test Postgres database and returns it, or nil
// (without skipping) when it is unavailable so the caller can fall back.
func tryPostgres(t *testing.T) store.Store {
	dsn := os.Getenv("TEST_DATABASE_URL")
	if dsn == "" {
		dsn = "postgres://ceoadmin:ceoadmin@localhost:15432/ceoadmin_test?sslmode=disable"
	}
	// Pre-connect to reset schema if migrations were consolidated
	preDB, err := sql.Open("pgx", dsn)
	if err != nil {
		return nil
	}
	// Drop goose version table and legacy tables so migrations re-run from scratch
	preDB.Exec("DROP TABLE IF EXISTS goose_db_version, schema_version, plugin_installs, plugin_versions, plugins CASCADE")
	preDB.Close()

	db, err := postgres.Open(dsn)
	if err != nil {
		return nil
	}
	for _, table := range []string{"app_event_logs", "app_api_logs", "app_installations", "apps", "plugin_installs", "plugin_versions", "plugins", "webhook_logs", "messages", "channels", "bots", "oauth_accounts", "sessions", "credentials", "users", "system_config"} {
		db.Exec("DELETE FROM " + table)
	}
	return db
}

// openSQLite opens a throwaway sqlite database in the test's temp dir.
func openSQLite(t *testing.T) store.Store {
	dbPath := filepath.Join(t.TempDir(), "test.db")
	s, err := sqlite.Open(dbPath)
	if err != nil {
		t.Fatalf("sqlite.Open: %v", err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}

// Setup builds a full server and returns the test environment. It skips when
// Postgres is unavailable.
func Setup(t *testing.T) *Env {
	t.Helper()
	db := OpenStore(t)

	cfg := &config.Config{
		RPOrigin: "http://localhost",
		RPID:     "localhost",
		RPName:   "Test",
		Secret:   "test-secret",
	}

	server := &api.Server{
		Store:        db,
		SessionStore: auth.NewSessionStore(),
		Config:       cfg,
		OAuthStates:  authapi.SetupOAuth(cfg),
	}

	hub := relay.NewHub(server.SetupUpstreamHandler())
	aiSink := &sink.AI{Store: db}
	mgr := bot.NewManager(db, hub, aiSink, nil, "http://localhost")
	appWSHub := api.NewAppWSHub()
	mgr.SetAppWSHub(appWSHub)
	server.BotManager = mgr
	server.Hub = hub
	server.AppWSHub = appWSHub

	ts := httptest.NewServer(server.Handler())
	jar, _ := cookiejar.New(nil)

	return &Env{
		T: t, Store: db, Srv: ts, Cfg: cfg,
		Client: &http.Client{Jar: jar},
		Mgr:    mgr, Hub: hub, AppWSHub: appWSHub,
	}
}

// Close tears down the environment.
func (e *Env) Close() {
	e.Mgr.StopAll()
	e.Srv.Close()
	e.Store.Close()
}

// NewClient returns a fresh HTTP client (separate cookie jar = separate session).
func (e *Env) NewClient() *http.Client {
	jar, _ := cookiejar.New(nil)
	return &http.Client{Jar: jar}
}

// ==================== HTTP helpers ====================

// PostRaw sends a JSON POST and returns the raw response.
func (e *Env) PostRaw(path string, body any) *http.Response {
	e.T.Helper()
	data, _ := json.Marshal(body)
	resp, err := e.Client.Post(e.Srv.URL+path, "application/json", bytes.NewReader(data))
	if err != nil {
		e.T.Fatalf("POST %s: %v", path, err)
	}
	return resp
}

// PostCode sends a JSON POST and returns status + decoded body.
func (e *Env) PostCode(path string, body any) (int, map[string]any) {
	e.T.Helper()
	resp := e.PostRaw(path, body)
	defer resp.Body.Close()
	var result map[string]any
	json.NewDecoder(resp.Body).Decode(&result)
	return resp.StatusCode, result
}

// Post sends a JSON POST and returns the decoded body.
func (e *Env) Post(path string, body any) map[string]any {
	e.T.Helper()
	_, result := e.PostCode(path, body)
	return result
}

// Get sends a GET and returns status + decoded body.
func (e *Env) Get(path string) (int, map[string]any) {
	e.T.Helper()
	resp, err := e.Client.Get(e.Srv.URL + path)
	if err != nil {
		e.T.Fatalf("GET %s: %v", path, err)
	}
	defer resp.Body.Close()
	var result map[string]any
	json.NewDecoder(resp.Body).Decode(&result)
	return resp.StatusCode, result
}

// GetList sends a GET and returns status + decoded list.
func (e *Env) GetList(path string) (int, []any) {
	e.T.Helper()
	resp, err := e.Client.Get(e.Srv.URL + path)
	if err != nil {
		e.T.Fatalf("GET %s: %v", path, err)
	}
	defer resp.Body.Close()
	var result []any
	json.NewDecoder(resp.Body).Decode(&result)
	return resp.StatusCode, result
}

// Del sends a DELETE and returns status + decoded body.
func (e *Env) Del(path string) (int, map[string]any) {
	e.T.Helper()
	req, _ := http.NewRequest("DELETE", e.Srv.URL+path, nil)
	resp, err := e.Client.Do(req)
	if err != nil {
		e.T.Fatalf("DELETE %s: %v", path, err)
	}
	defer resp.Body.Close()
	var result map[string]any
	json.NewDecoder(resp.Body).Decode(&result)
	return resp.StatusCode, result
}

// Put sends a JSON PUT and returns status + decoded body.
func (e *Env) Put(path string, body any) (int, map[string]any) {
	e.T.Helper()
	data, _ := json.Marshal(body)
	req, _ := http.NewRequest("PUT", e.Srv.URL+path, bytes.NewReader(data))
	req.Header.Set("Content-Type", "application/json")
	resp, err := e.Client.Do(req)
	if err != nil {
		e.T.Fatalf("PUT %s: %v", path, err)
	}
	defer resp.Body.Close()
	var result map[string]any
	json.NewDecoder(resp.Body).Decode(&result)
	return resp.StatusCode, result
}

// Register registers a user via the HTTP API.
func (e *Env) Register(username, password string) {
	e.T.Helper()
	code, result := e.PostCode("/api/auth/register", map[string]string{"username": username, "password": password})
	if code != 200 {
		e.T.Fatalf("register %s failed: %d %v", username, code, result["error"])
	}
}

// Login logs in a user via the HTTP API.
func (e *Env) Login(username, password string) {
	e.T.Helper()
	code, result := e.PostCode("/api/auth/login", map[string]string{"username": username, "password": password})
	if code != 200 {
		e.T.Fatalf("login %s failed: %d %v", username, code, result["error"])
	}
}

// UserID returns the current user's id (from /api/me).
func (e *Env) UserID() string {
	e.T.Helper()
	_, me := e.Get("/api/me")
	return me["id"].(string)
}

// CreateBotForUser creates a mock bot owned by the current user.
func (e *Env) CreateBotForUser(name string) *store.Bot {
	e.T.Helper()
	uid := e.UserID()
	b, err := e.Store.CreateBot(uid, name, "mock", "", mockserver.MockCredentials())
	if err != nil {
		e.T.Fatalf("createBot: %v", err)
	}
	return b
}

// SubmitPlugin submits a webhook plugin via the HTTP API and returns its IDs.
func (e *Env) SubmitPlugin(script string) (pluginID, versionID string) {
	e.T.Helper()
	code, result := e.PostCode("/api/webhook-plugins/submit", map[string]string{"script": script})
	if code != 200 {
		e.T.Fatalf("submit: %d %v", code, result)
	}
	pid, _ := result["plugin_id"].(string)
	vid, _ := result["version_id"].(string)
	if pid == "" || vid == "" {
		e.T.Fatalf("submit returned empty IDs: %v", result)
	}
	return pid, vid
}

// ApproveVersion approves a plugin version by version ID.
func (e *Env) ApproveVersion(versionID string) {
	e.T.Helper()
	e.Put("/api/admin/webhook-plugins/"+versionID+"/review", map[string]string{"status": "approved"})
}

// ==================== WebSocket helpers ====================

// ConnectWS dials the channel WebSocket for the given api key.
func (e *Env) ConnectWS(t *testing.T, apiKey string) *websocket.Conn {
	t.Helper()
	wsURL := "ws" + e.Srv.URL[4:] + "/api/v1/channels/connect?key=" + apiKey
	ws, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		t.Fatalf("ws dial: %v", err)
	}
	return ws
}

// ReadWS reads a single JSON message from the WebSocket.
func ReadWS(t *testing.T, ws *websocket.Conn) map[string]any {
	t.Helper()
	return ReadWSTimeout(t, ws, 2*time.Second)
}

// ReadWSTimeout reads a single JSON message with a deadline.
func ReadWSTimeout(t *testing.T, ws *websocket.Conn, d time.Duration) map[string]any {
	t.Helper()
	ws.SetReadDeadline(time.Now().Add(d))
	_, msg, err := ws.ReadMessage()
	ws.SetReadDeadline(time.Time{})
	if err != nil {
		return nil
	}
	var m map[string]any
	json.Unmarshal(msg, &m)
	return m
}

// DrainWS drains queued WebSocket messages until they stop arriving.
func DrainWS(t *testing.T, ws *websocket.Conn) {
	t.Helper()
	for ReadWSTimeout(t, ws, 300*time.Millisecond) != nil {
	}
}

// AssertCode fails the test if got != want.
func AssertCode(t *testing.T, label string, got, want int) {
	t.Helper()
	if got != want {
		t.Errorf("%s: got %d, want %d", label, got, want)
	}
}

// ==================== Plain (no cookie jar) request helpers ====================

// HTTPGet performs a plain GET.
func HTTPGet(t *testing.T, url string) *http.Response {
	t.Helper()
	resp, err := http.DefaultClient.Do(MustReq(t, "GET", url, nil))
	if err != nil {
		t.Fatalf("GET %s: %v", url, err)
	}
	return resp
}

// HTTPGetWithHeader performs a plain GET with an extra header.
func HTTPGetWithHeader(t *testing.T, url, header, value string) *http.Response {
	t.Helper()
	req := MustReq(t, "GET", url, nil)
	req.Header.Set(header, value)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("GET %s: %v", url, err)
	}
	return resp
}

// HTTPPost performs a plain JSON POST.
func HTTPPost(t *testing.T, url string, body any) *http.Response {
	t.Helper()
	data, _ := json.Marshal(body)
	req := MustReq(t, "POST", url, bytes.NewReader(data))
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("POST %s: %v", url, err)
	}
	return resp
}

// HTTPPostMultipart performs a plain multipart POST.
func HTTPPostMultipart(t *testing.T, url, contentType string, body []byte) *http.Response {
	t.Helper()
	req, _ := http.NewRequest("POST", url, bytes.NewReader(body))
	req.Header.Set("Content-Type", contentType)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("POST multipart %s: %v", url, err)
	}
	return resp
}

// MustReq builds an *http.Request, failing the test on error.
func MustReq(t *testing.T, method, url string, body *bytes.Reader) *http.Request {
	t.Helper()
	var req *http.Request
	var err error
	if body != nil {
		req, err = http.NewRequest(method, url, body)
	} else {
		req, err = http.NewRequest(method, url, nil)
	}
	if err != nil {
		t.Fatal(err)
	}
	return req
}
