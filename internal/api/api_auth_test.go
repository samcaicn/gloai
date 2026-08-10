package api

import (
	"net/http"
	"testing"
	"time"

	"github.com/ceoadmin/CEOadmin/internal/auth"
	"github.com/ceoadmin/CEOadmin/internal/store"
)

func TestSessionAuth(t *testing.T) {
	env := setupTestEnv(t)

	t.Run("no cookie returns 401", func(t *testing.T) {
		resp := doJSON(t, env.ts, "GET", "/api/apps", nil)
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusUnauthorized {
			t.Errorf("expected 401, got %d", resp.StatusCode)
		}
	})

	t.Run("invalid session token returns 401", func(t *testing.T) {
		badCookie := &http.Cookie{Name: "session", Value: "invalid-token-xyz"}
		resp := doJSON(t, env.ts, "GET", "/api/apps", nil, withCookie(badCookie))
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusUnauthorized {
			t.Errorf("expected 401, got %d", resp.StatusCode)
		}
	})

	t.Run("expired session returns 401", func(t *testing.T) {
		// Create an already-expired session.
		expiredToken := "expired-session-token-123"
		_ = env.store.CreateSession(expiredToken, env.user.ID, time.Now().Add(-1*time.Hour))

		expiredCookie := &http.Cookie{Name: "session", Value: expiredToken}
		resp := doJSON(t, env.ts, "GET", "/api/apps", nil, withCookie(expiredCookie))
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusUnauthorized {
			t.Errorf("expected 401 for expired session, got %d", resp.StatusCode)
		}
	})

	t.Run("valid session returns 200", func(t *testing.T) {
		resp := doJSON(t, env.ts, "GET", "/api/apps", nil, withCookie(env.cookie))
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			t.Errorf("expected 200, got %d", resp.StatusCode)
		}
	})

	t.Run("disabled user returns 403", func(t *testing.T) {
		// Create a disabled user with a valid session.
		u2, _ := env.store.CreateUserFull("disabled-user", "", "Disabled", "hashed", store.RoleMember)
		_ = env.store.UpdateUserStatus(u2.ID, store.StatusDisabled)
		tok, _ := auth.CreateSession(env.store, u2.ID)
		disabledCookie := &http.Cookie{Name: "session", Value: tok}

		resp := doJSON(t, env.ts, "GET", "/api/apps", nil, withCookie(disabledCookie))
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusForbidden {
			t.Errorf("expected 403 for disabled user, got %d", resp.StatusCode)
		}
	})
}

// ---------------------------------------------------------------------------
// Test: Bot API with legacy vs new scope names
// Ensures new scope format (colon-separated) works and old format would fail.
// ---------------------------------------------------------------------------

func TestCORSHeaders(t *testing.T) {
	env := setupTestEnv(t)

	req, _ := http.NewRequest("OPTIONS", env.ts.URL+"/api/info", nil)
	req.Header.Set("Origin", "http://example.com")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != 204 {
		t.Errorf("OPTIONS expected 204, got %d", resp.StatusCode)
	}
	if v := resp.Header.Get("Access-Control-Allow-Origin"); v != "http://example.com" {
		t.Errorf("ACAO = %q, want %q", v, "http://example.com")
	}
	if v := resp.Header.Get("Access-Control-Allow-Credentials"); v != "true" {
		t.Errorf("ACAC = %q, want %q", v, "true")
	}
}

// ---------------------------------------------------------------------------
// Test: Bot API unknown endpoint returns 404
// ---------------------------------------------------------------------------
