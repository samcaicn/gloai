package api

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

	authapi "github.com/ceoadmin/CEOadmin/internal/api/auth"
	"github.com/ceoadmin/CEOadmin/internal/auth"
	"github.com/ceoadmin/CEOadmin/internal/config"
	"github.com/ceoadmin/CEOadmin/internal/storage"
	"github.com/ceoadmin/CEOadmin/internal/store"
	"github.com/ceoadmin/CEOadmin/internal/store/sqlite"
)

type testEnv struct {
	ts      *httptest.Server
	store   store.Store
	user    *store.User
	cookie  *http.Cookie
	handler http.Handler
}

func setupTestEnv(t *testing.T) *testEnv {
	t.Helper()

	dbPath := filepath.Join(t.TempDir(), "test.db")
	s, err := sqlite.Open(dbPath)
	if err != nil {
		t.Fatalf("sqlite.Open: %v", err)
	}
	t.Cleanup(func() { s.Close() })

	// Create an admin user.
	u, err := s.CreateUserFull("testadmin", "", "Test Admin", "hashed", store.RoleAdmin)
	if err != nil {
		t.Fatalf("CreateUserFull: %v", err)
	}
	// Ensure user is active.
	_ = s.UpdateUserStatus(u.ID, store.StatusActive)

	// Create a session so dashboard (cookie-auth) requests work.
	sessionToken, err := auth.CreateSession(s, u.ID)
	if err != nil {
		t.Fatalf("CreateSession: %v", err)
	}
	cookie := &http.Cookie{Name: "session", Value: sessionToken}

	// Skill bundles need an object store; a temp filesystem store keeps the
	// marketplace endpoints exercisable in tests.
	bundleStore, err := storage.NewFS(filepath.Join(t.TempDir(), "skill-bundles"), "/api/skills")
	if err != nil {
		t.Fatalf("storage.NewFS: %v", err)
	}

	srv := &Server{
		Store:        s,
		Config:       &config.Config{RPOrigin: "http://localhost"},
		OAuthStates:  authapi.NewOAuthStateStore(),
		SkillStorage: bundleStore,
	}

	handler := srv.Handler()
	ts := httptest.NewServer(handler)
	t.Cleanup(ts.Close)

	return &testEnv{
		ts:      ts,
		store:   s,
		user:    u,
		cookie:  cookie,
		handler: handler,
	}
}

// doJSON is a helper that sends a JSON request to the httptest server and
// returns the response. It supports optional cookies and auth headers.
func doJSON(t *testing.T, ts *httptest.Server, method, path string, body any, opts ...func(*http.Request)) *http.Response {
	t.Helper()
	var bodyReader *bytes.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		bodyReader = bytes.NewReader(b)
	} else {
		bodyReader = bytes.NewReader(nil)
	}
	req, err := http.NewRequest(method, ts.URL+path, bodyReader)
	if err != nil {
		t.Fatalf("NewRequest: %v", err)
	}
	req.Header.Set("Content-Type", "application/json")
	for _, opt := range opts {
		opt(req)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("Do: %v", err)
	}
	return resp
}

func withCookie(c *http.Cookie) func(*http.Request) {
	return func(r *http.Request) { r.AddCookie(c) }
}

func withBearer(token string) func(*http.Request) {
	return func(r *http.Request) { r.Header.Set("Authorization", "Bearer "+token) }
}

func decodeJSON(t *testing.T, resp *http.Response) map[string]any {
	t.Helper()
	var m map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&m); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	resp.Body.Close()
	return m
}

// createTestBot creates a bot owned by the given user via the store.
func createTestBot(t *testing.T, s store.Store, userID, name string) *store.Bot {
	t.Helper()
	b, err := s.CreateBot(userID, name, "test", "", json.RawMessage(`{}`))
	if err != nil {
		t.Fatalf("CreateBot: %v", err)
	}
	return b
}

// createTestApp creates an app with the given scopes via the store.
func createTestApp(t *testing.T, s store.Store, ownerID, name, slug string, scopes []string) *store.App {
	t.Helper()
	scopesJSON, _ := json.Marshal(scopes)
	app, err := s.CreateApp(&store.App{
		OwnerID: ownerID,
		Name:    name,
		Slug:    slug,
		Scopes:  scopesJSON,
	})
	if err != nil {
		t.Fatalf("CreateApp(%q): %v", name, err)
	}
	return app
}

// installTestApp installs an app on a bot via the store and snapshots the app's
// scopes onto the installation (matching the Slack-model install flow).
func installTestApp(t *testing.T, s store.Store, appID, botID string) *store.AppInstallation {
	t.Helper()
	inst, err := s.InstallApp(appID, botID)
	if err != nil {
		t.Fatalf("InstallApp: %v", err)
	}
	// Snapshot app scopes at install time (Slack model)
	app, err := s.GetApp(appID)
	if err == nil && app != nil && len(app.Scopes) > 0 {
		_ = s.UpdateInstallation(inst.ID, inst.Handle, inst.Config, app.Scopes, inst.Enabled)
		inst.Scopes = app.Scopes
	}
	return inst
}

// ---------------------------------------------------------------------------
// Test: Bot API scope checks (app_token auth)
// ---------------------------------------------------------------------------

func containsSubstring(s, sub string) bool {
	return len(s) >= len(sub) && (s == sub || len(s) > 0 && contains(s, sub))
}

// ---------------------------------------------------------------------------
// Test: Bot API UpdateTools
// ---------------------------------------------------------------------------

func contains(s, sub string) bool {
	for i := 0; i <= len(s)-len(sub); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}

// ---------------------------------------------------------------------------
// Test: Scope snapshot at install time (Slack model)
// ---------------------------------------------------------------------------
