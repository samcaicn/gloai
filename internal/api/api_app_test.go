package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"

	authapi "github.com/ceoadmin/CEOadmin/internal/api/auth"
	"github.com/ceoadmin/CEOadmin/internal/auth"
	"github.com/ceoadmin/CEOadmin/internal/config"
	"github.com/ceoadmin/CEOadmin/internal/registry"
	"github.com/ceoadmin/CEOadmin/internal/store"
	"github.com/ceoadmin/CEOadmin/internal/store/sqlite"
)

func TestAppAPI_CRUD(t *testing.T) {
	env := setupTestEnv(t)

	var createdAppID string

	t.Run("create app", func(t *testing.T) {
		resp := doJSON(t, env.ts, "POST", "/api/apps", map[string]any{
			"name":   "My Test App",
			"slug":   "my-test-app",
			"scopes": []string{"message:write", "bot:read"},
		}, withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusCreated {
			body := decodeJSON(t, resp)
			t.Fatalf("expected 201, got %d: %v", resp.StatusCode, body)
		}
		body := decodeJSON(t, resp)
		createdAppID, _ = body["id"].(string)
		if createdAppID == "" {
			t.Fatal("created app has no id")
		}
		if slug, _ := body["slug"].(string); slug != "my-test-app" {
			t.Errorf("slug = %q, want %q", slug, "my-test-app")
		}
	})

	t.Run("get app", func(t *testing.T) {
		resp := doJSON(t, env.ts, "GET", "/api/apps/"+createdAppID, nil,
			withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			t.Fatalf("expected 200, got %d", resp.StatusCode)
		}
		body := decodeJSON(t, resp)
		if name, _ := body["name"].(string); name != "My Test App" {
			t.Errorf("name = %q, want %q", name, "My Test App")
		}
	})

	t.Run("update app", func(t *testing.T) {
		newName := "Updated App"
		resp := doJSON(t, env.ts, "PUT", "/api/apps/"+createdAppID, map[string]any{
			"name":   &newName,
			"scopes": []string{"message:write", "contact:read"},
		}, withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			body := decodeJSON(t, resp)
			t.Fatalf("expected 200, got %d: %v", resp.StatusCode, body)
		}

		// Verify update persisted.
		app, _ := env.store.GetApp(createdAppID)
		if app.Name != "Updated App" {
			t.Errorf("name after update = %q, want %q", app.Name, "Updated App")
		}
	})

	t.Run("marketplace app cannot be edited", func(t *testing.T) {
		// Create a marketplace (registry) app directly via store.
		mApp, err := env.store.CreateApp(&store.App{
			OwnerID:  env.user.ID,
			Name:     "Marketplace App",
			Slug:     "marketplace-app",
			Registry: "https://registry.example.com",
		})
		if err != nil {
			t.Fatalf("create marketplace app: %v", err)
		}

		resp := doJSON(t, env.ts, "PUT", "/api/apps/"+mApp.ID, map[string]any{
			"name": "Hacked Name",
		}, withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusForbidden {
			t.Errorf("expected 403 for marketplace app edit, got %d", resp.StatusCode)
		}
	})

	t.Run("delete app", func(t *testing.T) {
		resp := doJSON(t, env.ts, "DELETE", "/api/apps/"+createdAppID, nil,
			withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			t.Fatalf("expected 200, got %d", resp.StatusCode)
		}

		// Verify deleted.
		_, err := env.store.GetApp(createdAppID)
		if err == nil {
			t.Error("app should be deleted")
		}
	})

	t.Run("create app with invalid slug fails", func(t *testing.T) {
		resp := doJSON(t, env.ts, "POST", "/api/apps", map[string]any{
			"name": "Bad Slug",
			"slug": "-bad-",
		}, withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusBadRequest {
			t.Errorf("expected 400 for invalid slug, got %d", resp.StatusCode)
		}
	})

	t.Run("create app without name fails", func(t *testing.T) {
		resp := doJSON(t, env.ts, "POST", "/api/apps", map[string]any{
			"slug": "no-name-app",
		}, withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusBadRequest {
			t.Errorf("expected 400 for missing name, got %d", resp.StatusCode)
		}
	})
}

// ---------------------------------------------------------------------------
// Test: Install endpoint (POST /api/apps/{id}/install)
// ---------------------------------------------------------------------------

func TestInstallApp(t *testing.T) {
	env := setupTestEnv(t)
	bot := createTestBot(t, env.store, env.user.ID, "install-bot")

	t.Run("install by app_id", func(t *testing.T) {
		app := createTestApp(t, env.store, env.user.ID, "Direct App", "direct-app",
			[]string{"message:write"})

		resp := doJSON(t, env.ts, "POST", "/api/apps/"+app.ID+"/install", map[string]any{
			"bot_id": bot.ID,
		}, withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusCreated {
			body := decodeJSON(t, resp)
			t.Fatalf("expected 201, got %d: %v", resp.StatusCode, body)
		}
	})

	t.Run("handle conflict returns 409", func(t *testing.T) {
		app := createTestApp(t, env.store, env.user.ID, "Handle App", "handle-app",
			[]string{"message:write"})

		// First install with a handle.
		resp := doJSON(t, env.ts, "POST", "/api/apps/"+app.ID+"/install", map[string]any{
			"bot_id": bot.ID,
			"handle": "myhandle",
		}, withCookie(env.cookie))
		resp.Body.Close()
		if resp.StatusCode != http.StatusCreated {
			t.Fatalf("first install expected 201, got %d", resp.StatusCode)
		}

		// Second install with the same handle should conflict.
		app2 := createTestApp(t, env.store, env.user.ID, "Handle App2", "handle-app2",
			[]string{"message:write"})
		resp2 := doJSON(t, env.ts, "POST", "/api/apps/"+app2.ID+"/install", map[string]any{
			"bot_id": bot.ID,
			"handle": "myhandle",
		}, withCookie(env.cookie))
		defer resp2.Body.Close()

		if resp2.StatusCode != http.StatusConflict {
			t.Errorf("expected 409 for handle conflict, got %d", resp2.StatusCode)
		}
	})

	t.Run("missing bot_id returns 400", func(t *testing.T) {
		app := createTestApp(t, env.store, env.user.ID, "NoBotID App", "nobotid-app",
			[]string{"message:write"})

		resp := doJSON(t, env.ts, "POST", "/api/apps/"+app.ID+"/install", map[string]any{},
			withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusBadRequest {
			t.Errorf("expected 400, got %d", resp.StatusCode)
		}
	})

	t.Run("nonexistent bot returns 404", func(t *testing.T) {
		app := createTestApp(t, env.store, env.user.ID, "Good App", "good-app",
			[]string{"message:write"})

		resp := doJSON(t, env.ts, "POST", "/api/apps/"+app.ID+"/install", map[string]any{
			"bot_id": "nonexistent-id",
		}, withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusNotFound {
			t.Errorf("expected 404 for nonexistent bot, got %d", resp.StatusCode)
		}
	})
}

// ---------------------------------------------------------------------------
// Test: Slug uniqueness via API
// ---------------------------------------------------------------------------

func TestSlugUniqueness(t *testing.T) {
	env := setupTestEnv(t)

	t.Run("duplicate local slug returns 409", func(t *testing.T) {
		// First app succeeds.
		resp := doJSON(t, env.ts, "POST", "/api/apps", map[string]any{
			"name": "First App",
			"slug": "unique-slug",
		}, withCookie(env.cookie))
		resp.Body.Close()
		if resp.StatusCode != http.StatusCreated {
			t.Fatalf("first create expected 201, got %d", resp.StatusCode)
		}

		// Second app with same slug should fail.
		resp2 := doJSON(t, env.ts, "POST", "/api/apps", map[string]any{
			"name": "Second App",
			"slug": "unique-slug",
		}, withCookie(env.cookie))
		defer resp2.Body.Close()

		if resp2.StatusCode != http.StatusConflict {
			t.Errorf("expected 409 for duplicate slug, got %d", resp2.StatusCode)
		}
	})

	t.Run("builtin app with same slug as local succeeds", func(t *testing.T) {
		// Create a local app.
		resp := doJSON(t, env.ts, "POST", "/api/apps", map[string]any{
			"name": "Local App",
			"slug": "shared-slug",
		}, withCookie(env.cookie))
		resp.Body.Close()
		if resp.StatusCode != http.StatusCreated {
			t.Fatalf("local create expected 201, got %d", resp.StatusCode)
		}

		// Create a builtin app with the same slug directly via store
		// (builtin apps are created via template install, not the API).
		_, err := env.store.CreateApp(&store.App{
			OwnerID:  env.user.ID,
			Name:     "Builtin App",
			Slug:     "shared-slug",
			Registry: "builtin",
		})
		if err != nil {
			t.Fatalf("expected builtin app creation to succeed, got: %v", err)
		}
	})
}

// ---------------------------------------------------------------------------
// Test: Session auth edge cases
// ---------------------------------------------------------------------------

func TestAppAPI_List(t *testing.T) {
	env := setupTestEnv(t)

	// Create some apps.
	createTestApp(t, env.store, env.user.ID, "App A", "app-aaa", []string{"message:write"})
	createTestApp(t, env.store, env.user.ID, "App B", "app-bbb", []string{"bot:read"})

	t.Run("list own apps", func(t *testing.T) {
		resp := doJSON(t, env.ts, "GET", "/api/apps", nil, withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			t.Fatalf("expected 200, got %d", resp.StatusCode)
		}

		var apps []map[string]any
		json.NewDecoder(resp.Body).Decode(&apps)
		if len(apps) < 2 {
			t.Errorf("expected at least 2 apps, got %d", len(apps))
		}
	})
}

// ---------------------------------------------------------------------------
// Test: CORS headers
// ---------------------------------------------------------------------------

func TestAppAPI_RequestListingValidation(t *testing.T) {
	env := setupTestEnv(t)

	t.Run("missing required fields rejected", func(t *testing.T) {
		// Create a minimal app (missing description, readme, version, webhook, events/tools, scopes).
		app := createTestApp(t, env.store, env.user.ID, "Bare App", "bare-app", nil)

		resp := doJSON(t, env.ts, "POST", "/api/apps/"+app.ID+"/request-listing", nil,
			withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusBadRequest {
			t.Fatalf("expected 400, got %d", resp.StatusCode)
		}
		body := decodeJSON(t, resp)
		msg, _ := body["error"].(string)
		// Should mention multiple missing fields.
		for _, want := range []string{"description", "readme", "version", "webhook_url", "event", "scope"} {
			if !containsSubstring(msg, want) {
				t.Errorf("error message %q should mention %q", msg, want)
			}
		}
	})

	t.Run("complete app can be listed", func(t *testing.T) {
		tools, _ := json.Marshal([]map[string]string{{"name": "ping", "description": "ping"}})
		events, _ := json.Marshal([]string{"command"})
		scopes, _ := json.Marshal([]string{"message:write"})
		app, err := env.store.CreateApp(&store.App{
			OwnerID:     env.user.ID,
			Name:        "Complete App",
			Slug:        "complete-app",
			Description: "A complete app",
			Readme:      "# Complete App",
			Version:     "1.0.0",
			WebhookURL:  "https://example.com/webhook",
			Tools:       tools,
			Events:      events,
			Scopes:      scopes,
		})
		if err != nil {
			t.Fatalf("CreateApp: %v", err)
		}
		// Mark webhook as verified.
		_ = env.store.SetAppWebhookVerified(app.ID, true)

		resp := doJSON(t, env.ts, "POST", "/api/apps/"+app.ID+"/request-listing", nil,
			withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			body := decodeJSON(t, resp)
			t.Fatalf("expected 200, got %d: %v", resp.StatusCode, body)
		}

		got, _ := env.store.GetApp(app.ID)
		if got.Listing != "pending" {
			t.Errorf("listing = %q, want %q", got.Listing, "pending")
		}
	})

	t.Run("unverified webhook allowed", func(t *testing.T) {
		// Webhook verification is no longer required for listing —
		// apps with OAuth/PKCE don't need it, and verification can happen post-listing.
		tools, _ := json.Marshal([]map[string]string{{"name": "ping", "description": "ping"}})
		scopes, _ := json.Marshal([]string{"message:write"})
		app, _ := env.store.CreateApp(&store.App{
			OwnerID:     env.user.ID,
			Name:        "Unverified Webhook App",
			Slug:        "unverified-webhook-app",
			Description: "has unverified webhook",
			Readme:      "# App",
			Version:     "1.0.0",
			WebhookURL:  "https://example.com/webhook",
			Tools:       tools,
			Scopes:      scopes,
		})

		resp := doJSON(t, env.ts, "POST", "/api/apps/"+app.ID+"/request-listing", nil,
			withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			t.Fatalf("expected 200 for unverified webhook, got %d", resp.StatusCode)
		}
	})
}

// ---------------------------------------------------------------------------
// Test: Pending freeze (cannot modify core fields while pending)
// ---------------------------------------------------------------------------

func TestAppAPI_PendingFreeze(t *testing.T) {
	env := setupTestEnv(t)

	// Create a complete app and set it to pending via store directly.
	tools, _ := json.Marshal([]map[string]string{{"name": "ping", "description": "ping"}})
	events, _ := json.Marshal([]string{"command"})
	scopes, _ := json.Marshal([]string{"message:write"})
	app, _ := env.store.CreateApp(&store.App{
		OwnerID:     env.user.ID,
		Name:        "Freeze Test App",
		Slug:        "freeze-test-app",
		Description: "for freeze test",
		Readme:      "# Freeze",
		Version:     "1.0.0",
		WebhookURL:  "https://example.com/webhook",
		Tools:       tools,
		Events:      events,
		Scopes:      scopes,
	})
	_ = env.store.SetAppWebhookVerified(app.ID, true)
	_ = env.store.RequestListing(app.ID)

	t.Run("core field update blocked during pending", func(t *testing.T) {
		resp := doJSON(t, env.ts, "PUT", "/api/apps/"+app.ID, map[string]any{
			"tools": []map[string]string{{"name": "new-tool", "description": "new"}},
		}, withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusForbidden {
			t.Errorf("expected 403 for core field update during pending, got %d", resp.StatusCode)
		}
	})

	t.Run("cosmetic update allowed during pending", func(t *testing.T) {
		newDesc := "Updated description"
		resp := doJSON(t, env.ts, "PUT", "/api/apps/"+app.ID, map[string]any{
			"description": &newDesc,
		}, withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			body := decodeJSON(t, resp)
			t.Fatalf("expected 200 for cosmetic update during pending, got %d: %v", resp.StatusCode, body)
		}
	})
}

// ---------------------------------------------------------------------------
// Test: Withdraw listing request
// ---------------------------------------------------------------------------

func TestAppAPI_WithdrawListing(t *testing.T) {
	env := setupTestEnv(t)

	app, _ := env.store.CreateApp(&store.App{
		OwnerID:     env.user.ID,
		Name:        "Withdraw Test App",
		Slug:        "withdraw-api-test",
		Description: "for withdraw test",
	})
	_ = env.store.RequestListing(app.ID)

	t.Run("withdraw pending listing", func(t *testing.T) {
		resp := doJSON(t, env.ts, "POST", "/api/apps/"+app.ID+"/withdraw-listing", nil,
			withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			body := decodeJSON(t, resp)
			t.Fatalf("expected 200, got %d: %v", resp.StatusCode, body)
		}

		got, _ := env.store.GetApp(app.ID)
		if got.Listing != "unlisted" {
			t.Errorf("listing = %q, want %q", got.Listing, "unlisted")
		}
	})

	t.Run("withdraw non-pending fails", func(t *testing.T) {
		resp := doJSON(t, env.ts, "POST", "/api/apps/"+app.ID+"/withdraw-listing", nil,
			withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusBadRequest {
			t.Errorf("expected 400 for non-pending withdraw, got %d", resp.StatusCode)
		}
	})
}

// ---------------------------------------------------------------------------
// Test: Auto-revert to pending on core change for listed apps
// ---------------------------------------------------------------------------

func TestAppAPI_AutoRevertOnCoreChange(t *testing.T) {
	env := setupTestEnv(t)

	tools, _ := json.Marshal([]map[string]string{{"name": "ping", "description": "ping"}})
	scopes, _ := json.Marshal([]string{"message:write"})
	app, _ := env.store.CreateApp(&store.App{
		OwnerID:     env.user.ID,
		Name:        "Revert Test App",
		Slug:        "revert-test-app",
		Description: "for revert test",
		Tools:       tools,
		Scopes:      scopes,
	})
	// Set to listed directly via store.
	_ = env.store.SetListing(app.ID, "listed")

	t.Run("core change reverts listed to pending", func(t *testing.T) {
		resp := doJSON(t, env.ts, "PUT", "/api/apps/"+app.ID, map[string]any{
			"scopes": []string{"message:write", "contact:read"},
		}, withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			body := decodeJSON(t, resp)
			t.Fatalf("expected 200, got %d: %v", resp.StatusCode, body)
		}

		got, _ := env.store.GetApp(app.ID)
		if got.Listing != "pending" {
			t.Errorf("listing = %q, want %q after core change", got.Listing, "pending")
		}
	})

	t.Run("cosmetic change does not revert listed", func(t *testing.T) {
		// Reset to listed.
		_ = env.store.SetListing(app.ID, "listed")

		newName := "Still Listed App"
		resp := doJSON(t, env.ts, "PUT", "/api/apps/"+app.ID, map[string]any{
			"name": &newName,
		}, withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			t.Fatalf("expected 200, got %d", resp.StatusCode)
		}

		got, _ := env.store.GetApp(app.ID)
		if got.Listing != "listed" {
			t.Errorf("listing = %q, want %q (cosmetic change should not revert)", got.Listing, "listed")
		}
	})
}

// ---------------------------------------------------------------------------
// Test: Full App listing lifecycle (end-to-end)
// ---------------------------------------------------------------------------

func TestAppAPI_FullListingLifecycle(t *testing.T) {
	env := setupTestEnv(t)

	// Stand up a test HTTP server that responds to webhook verification challenges.
	webhookServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var req map[string]any
		json.NewDecoder(r.Body).Decode(&req)
		if req["type"] == "url_verification" {
			json.NewEncoder(w).Encode(map[string]string{"challenge": req["challenge"].(string)})
			return
		}
		w.WriteHeader(200)
	}))
	defer webhookServer.Close()

	// Step 1: Create app via API, then set version via store (version is not
	// exposed in the create-app API but is required for listing submission).
	tools, _ := json.Marshal([]map[string]string{{"name": "ping", "description": "pong"}})
	events, _ := json.Marshal([]string{"command"})
	scopes, _ := json.Marshal([]string{"message:write"})

	var appID string
	t.Run("1_create_app", func(t *testing.T) {
		resp := doJSON(t, env.ts, "POST", "/api/apps", map[string]any{
			"name":        "Lifecycle App",
			"slug":        "lifecycle-app",
			"description": "An app for lifecycle testing",
			"readme":      "# Lifecycle App\nFull lifecycle test.",
			"tools":       json.RawMessage(tools),
			"events":      json.RawMessage(events),
			"scopes":      json.RawMessage(scopes),
		}, withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusCreated {
			body := decodeJSON(t, resp)
			t.Fatalf("expected 201, got %d: %v", resp.StatusCode, body)
		}
		body := decodeJSON(t, resp)
		appID, _ = body["id"].(string)
		if appID == "" {
			t.Fatal("created app has no id")
		}
	})
	if appID == "" {
		t.Fatal("step 1 failed, cannot continue")
	}

	// Delete and recreate via store to include the version field
	// (no API/store method exposes version update independently).
	_ = env.store.DeleteApp(appID)
	storeApp, err := env.store.CreateApp(&store.App{
		OwnerID:     env.user.ID,
		Name:        "Lifecycle App",
		Slug:        "lifecycle-app",
		Description: "An app for lifecycle testing",
		Readme:      "# Lifecycle App\nFull lifecycle test.",
		Version:     "1.0.0",
		Tools:       tools,
		Events:      events,
		Scopes:      scopes,
	})
	if err != nil {
		t.Fatalf("recreate app with version: %v", err)
	}
	appID = storeApp.ID

	// Step 2: Set webhook_url.
	t.Run("2_set_webhook_url", func(t *testing.T) {
		resp := doJSON(t, env.ts, "PUT", "/api/apps/"+appID, map[string]any{
			"webhook_url": &webhookServer.URL,
		}, withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			body := decodeJSON(t, resp)
			t.Fatalf("expected 200, got %d: %v", resp.StatusCode, body)
		}

		app, _ := env.store.GetApp(appID)
		if app.WebhookURL != webhookServer.URL {
			t.Fatalf("webhook_url = %q, want %q", app.WebhookURL, webhookServer.URL)
		}
	})

	// Step 3: Verify webhook URL.
	t.Run("3_verify_webhook", func(t *testing.T) {
		resp := doJSON(t, env.ts, "POST", "/api/apps/"+appID+"/verify-url", nil,
			withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			body := decodeJSON(t, resp)
			t.Fatalf("expected 200, got %d: %v", resp.StatusCode, body)
		}

		app, _ := env.store.GetApp(appID)
		if !app.WebhookVerified {
			t.Fatal("webhook should be verified after successful challenge")
		}
	})

	// Step 4: Submit for listing.
	t.Run("4_submit_for_listing", func(t *testing.T) {
		resp := doJSON(t, env.ts, "POST", "/api/apps/"+appID+"/request-listing", nil,
			withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			body := decodeJSON(t, resp)
			t.Fatalf("expected 200, got %d: %v", resp.StatusCode, body)
		}

		app, _ := env.store.GetApp(appID)
		if app.Listing != "pending" {
			t.Fatalf("listing = %q, want %q", app.Listing, "pending")
		}
	})

	// Step 5: Try modifying core field while pending — should be blocked.
	t.Run("5_core_field_blocked_while_pending", func(t *testing.T) {
		resp := doJSON(t, env.ts, "PUT", "/api/apps/"+appID, map[string]any{
			"tools": []map[string]string{{"name": "new-tool", "description": "blocked"}},
		}, withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusForbidden {
			t.Fatalf("expected 403 for core field update during pending, got %d", resp.StatusCode)
		}
	})

	// Step 6: Admin review — reject.
	t.Run("6_admin_reject", func(t *testing.T) {
		resp := doJSON(t, env.ts, "PUT", "/api/admin/apps/"+appID+"/review-listing", map[string]any{
			"approve": false,
			"reason":  "needs work",
		}, withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			body := decodeJSON(t, resp)
			t.Fatalf("expected 200, got %d: %v", resp.StatusCode, body)
		}
	})

	// Step 7: Verify app is rejected.
	t.Run("7_verify_rejected", func(t *testing.T) {
		resp := doJSON(t, env.ts, "GET", "/api/apps/"+appID, nil,
			withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			t.Fatalf("expected 200, got %d", resp.StatusCode)
		}
		body := decodeJSON(t, resp)
		if listing, _ := body["listing"].(string); listing != "rejected" {
			t.Fatalf("listing = %q, want %q", listing, "rejected")
		}
		if reason, _ := body["listing_reject_reason"].(string); reason != "needs work" {
			t.Fatalf("listing_reject_reason = %q, want %q", reason, "needs work")
		}
	})

	// Step 8: Fix and resubmit.
	t.Run("8_resubmit_after_rejection", func(t *testing.T) {
		resp := doJSON(t, env.ts, "POST", "/api/apps/"+appID+"/request-listing", nil,
			withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			body := decodeJSON(t, resp)
			t.Fatalf("expected 200, got %d: %v", resp.StatusCode, body)
		}

		app, _ := env.store.GetApp(appID)
		if app.Listing != "pending" {
			t.Fatalf("listing = %q, want %q", app.Listing, "pending")
		}
	})

	// Step 9: Admin review — approve.
	t.Run("9_admin_approve", func(t *testing.T) {
		resp := doJSON(t, env.ts, "PUT", "/api/admin/apps/"+appID+"/review-listing", map[string]any{
			"approve": true,
		}, withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			body := decodeJSON(t, resp)
			t.Fatalf("expected 200, got %d: %v", resp.StatusCode, body)
		}
	})

	// Step 10: Verify app is listed.
	t.Run("10_verify_listed", func(t *testing.T) {
		resp := doJSON(t, env.ts, "GET", "/api/apps/"+appID, nil,
			withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			t.Fatalf("expected 200, got %d", resp.StatusCode)
		}
		body := decodeJSON(t, resp)
		if listing, _ := body["listing"].(string); listing != "listed" {
			t.Fatalf("listing = %q, want %q", listing, "listed")
		}
	})

	// Step 11: Check registry output.
	t.Run("11_registry_output", func(t *testing.T) {
		// Enable the registry.
		if err := env.store.SetConfig("registry.enabled", "true"); err != nil {
			t.Fatalf("SetConfig: %v", err)
		}

		// Registry endpoint is public — no auth needed.
		resp := doJSON(t, env.ts, "GET", "/api/registry/v1/apps.json", nil)
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			t.Fatalf("expected 200, got %d", resp.StatusCode)
		}

		var manifest struct {
			Version int `json:"version"`
			Apps    []struct {
				Slug string `json:"slug"`
				Name string `json:"name"`
			} `json:"apps"`
		}
		if err := json.NewDecoder(resp.Body).Decode(&manifest); err != nil {
			t.Fatalf("decode manifest: %v", err)
		}
		if manifest.Version != 1 {
			t.Errorf("manifest version = %d, want 1", manifest.Version)
		}

		found := false
		for _, a := range manifest.Apps {
			if a.Slug == "lifecycle-app" {
				found = true
				if a.Name != "Lifecycle App" {
					t.Errorf("app name = %q, want %q", a.Name, "Lifecycle App")
				}
				break
			}
		}
		if !found {
			t.Fatalf("listed app not found in registry manifest; apps = %+v", manifest.Apps)
		}
	})

	// -----------------------------------------------------------------------
	// Step 11b–11f: Installation verification steps (app is listed at this point)
	// -----------------------------------------------------------------------

	// Create a second user + session for non-owner operations.
	user2, err := env.store.CreateUserFull("user2", "user2@test.com", "User Two", "hashed", string(store.RoleMember))
	if err != nil {
		t.Fatalf("CreateUserFull(user2): %v", err)
	}
	_ = env.store.UpdateUserStatus(user2.ID, store.StatusActive)
	session2Token, err := auth.CreateSession(env.store, user2.ID)
	if err != nil {
		t.Fatalf("CreateSession(user2): %v", err)
	}
	cookie2 := &http.Cookie{Name: "session", Value: session2Token}

	// Step 11b: Another user can see the listed app.
	t.Run("11b_listed_app_visible_to_non_owner", func(t *testing.T) {
		resp := doJSON(t, env.ts, "GET", "/api/apps/"+appID, nil, withCookie(cookie2))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			body := decodeJSON(t, resp)
			t.Fatalf("expected 200, got %d: %v", resp.StatusCode, body)
		}
		body := decodeJSON(t, resp)
		if name, _ := body["name"].(string); name != "Lifecycle App" {
			t.Errorf("name = %q, want %q", name, "Lifecycle App")
		}
	})

	// Create a bot for user2 (needed for installation steps).
	bot2 := createTestBot(t, env.store, user2.ID, "User2 Bot")

	// Step 11c: Another user installs the listed app.
	var installationID, appToken string
	t.Run("11c_non_owner_installs_listed_app", func(t *testing.T) {
		resp := doJSON(t, env.ts, "POST", "/api/apps/"+appID+"/install", map[string]any{
			"bot_id": bot2.ID,
			"handle": "test-install",
		}, withCookie(cookie2))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusCreated {
			body := decodeJSON(t, resp)
			t.Fatalf("expected 201, got %d: %v", resp.StatusCode, body)
		}
		body := decodeJSON(t, resp)
		installationID, _ = body["id"].(string)
		appToken, _ = body["app_token"].(string)
		if installationID == "" {
			t.Fatal("installation has no id")
		}
		if appToken == "" {
			t.Fatal("installation has no app_token")
		}
	})
	if appToken == "" {
		t.Fatal("step 11c failed (no token), cannot continue")
	}

	// Step 11d: Installation works — verify token authenticates.
	// The app has message:write scope, so test POST /bot/v1/message/send.
	// The bot isn't actually connected so we won't get 200, but auth/scope
	// checks should pass (i.e. we must NOT get 401 or 403).
	t.Run("11d_app_token_authenticates", func(t *testing.T) {
		resp := doJSON(t, env.ts, "POST", "/bot/v1/message/send",
			map[string]string{"content": "hello from installed app"},
			withBearer(appToken))
		defer resp.Body.Close()

		if resp.StatusCode == http.StatusUnauthorized {
			t.Fatal("expected token to be valid, got 401")
		}
		if resp.StatusCode == http.StatusForbidden {
			body := decodeJSON(t, resp)
			t.Fatalf("expected scope check to pass for message:write, got 403: %v", body)
		}
		// Any other status (500, 503, etc.) is acceptable — the bot isn't running.
	})

	// Step 11e: Registry manifest includes full app details, excludes secrets.
	t.Run("11e_registry_manifest_details", func(t *testing.T) {
		resp := doJSON(t, env.ts, "GET", "/api/registry/v1/apps.json", nil)
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			t.Fatalf("expected 200, got %d", resp.StatusCode)
		}

		// Decode the full manifest to check individual app fields.
		var raw struct {
			Apps []map[string]any `json:"apps"`
		}
		if err := json.NewDecoder(resp.Body).Decode(&raw); err != nil {
			t.Fatalf("decode manifest: %v", err)
		}

		var found map[string]any
		for _, a := range raw.Apps {
			if slug, _ := a["slug"].(string); slug == "lifecycle-app" {
				found = a
				break
			}
		}
		if found == nil {
			t.Fatal("lifecycle-app not found in manifest")
		}

		// Verify essential fields are present.
		if v, _ := found["webhook_url"].(string); v == "" {
			t.Error("manifest app missing webhook_url")
		}
		if v, ok := found["tools"]; !ok || v == nil {
			t.Error("manifest app missing tools")
		}
		if v, ok := found["events"]; !ok || v == nil {
			t.Error("manifest app missing events")
		}
		if v, ok := found["scopes"]; !ok || v == nil {
			t.Error("manifest app missing scopes")
		}

		// Verify webhook_secret is NOT in the manifest.
		if _, ok := found["webhook_secret"]; ok {
			t.Error("manifest app must not include webhook_secret")
		}
	})

	// Step 11f: Install endpoint works for marketplace-style install.
	// Admin user installs the same listed app to their own bot via
	// POST /api/apps/{id}/install with bot_id.
	adminBot := createTestBot(t, env.store, env.user.ID, "Admin Lifecycle Bot")
	t.Run("11f_install_endpoint", func(t *testing.T) {
		resp := doJSON(t, env.ts, "POST", "/api/apps/"+appID+"/install", map[string]any{
			"bot_id": adminBot.ID,
			"handle": "unified-test",
		}, withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusCreated {
			body := decodeJSON(t, resp)
			t.Fatalf("expected 201, got %d: %v", resp.StatusCode, body)
		}
		body := decodeJSON(t, resp)
		if id, _ := body["id"].(string); id == "" {
			t.Error("unified install returned no installation id")
		}
		if tok, _ := body["app_token"].(string); tok == "" {
			t.Error("unified install returned no app_token")
		}
	})

	// Step 12: Update core field on listed app — should auto-revert to pending.
	t.Run("12_core_change_reverts_listed", func(t *testing.T) {
		resp := doJSON(t, env.ts, "PUT", "/api/apps/"+appID, map[string]any{
			"scopes": []string{"message:write", "contact:read"},
		}, withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			body := decodeJSON(t, resp)
			t.Fatalf("expected 200, got %d: %v", resp.StatusCode, body)
		}
	})

	// Step 13: Verify reverted to pending.
	t.Run("13_verify_reverted_to_pending", func(t *testing.T) {
		resp := doJSON(t, env.ts, "GET", "/api/apps/"+appID, nil,
			withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			t.Fatalf("expected 200, got %d", resp.StatusCode)
		}
		body := decodeJSON(t, resp)
		if listing, _ := body["listing"].(string); listing != "pending" {
			t.Fatalf("listing = %q, want %q after core change", listing, "pending")
		}
	})

	// Step 14: Withdraw listing.
	t.Run("14_withdraw_listing", func(t *testing.T) {
		resp := doJSON(t, env.ts, "POST", "/api/apps/"+appID+"/withdraw-listing", nil,
			withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			body := decodeJSON(t, resp)
			t.Fatalf("expected 200, got %d: %v", resp.StatusCode, body)
		}
	})

	// Step 15: Verify unlisted.
	t.Run("15_verify_unlisted", func(t *testing.T) {
		resp := doJSON(t, env.ts, "GET", "/api/apps/"+appID, nil,
			withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			t.Fatalf("expected 200, got %d", resp.StatusCode)
		}
		body := decodeJSON(t, resp)
		if listing, _ := body["listing"].(string); listing != "unlisted" {
			t.Fatalf("listing = %q, want %q", listing, "unlisted")
		}
	})
}

func TestScopeSnapshotAtInstall(t *testing.T) {
	env := setupTestEnv(t)

	// Create app with initial scopes
	app := createTestApp(t, env.store, env.user.ID, "Scope Test App", "scope-test",
		[]string{"message:write", "message:read"})

	bot := createTestBot(t, env.store, env.user.ID, "scope-bot-2")

	t.Run("installation gets app scopes snapshot", func(t *testing.T) {
		// Install without specifying scopes via the API
		resp := doJSON(t, env.ts, "POST", "/api/apps/"+app.ID+"/install",
			map[string]any{"bot_id": bot.ID, "handle": "scope-test"},
			withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusCreated {
			body := decodeJSON(t, resp)
			t.Fatalf("expected 201, got %d: %v", resp.StatusCode, body)
		}

		body := decodeJSON(t, resp)
		instID, _ := body["id"].(string)

		// Verify installation has scopes
		inst, err := env.store.GetInstallation(instID)
		if err != nil {
			t.Fatalf("GetInstallation: %v", err)
		}
		var instScopes []string
		json.Unmarshal(inst.Scopes, &instScopes)
		if len(instScopes) != 2 {
			t.Fatalf("expected 2 scopes, got %d: %v", len(instScopes), instScopes)
		}
	})

	t.Run("app scope change does not affect existing installation", func(t *testing.T) {
		// Update app scopes (add tools:write)
		newScopes, _ := json.Marshal([]string{"message:write", "message:read", "tools:write"})
		env.store.UpdateApp(app.ID, app.Name, app.Description, app.Icon, app.IconURL,
			app.Homepage, app.OAuthSetupURL, app.OAuthRedirectURL, app.ConfigSchema,
			app.Version, app.Readme, app.Guide, app.Tools, app.Events, newScopes)

		// Existing installation should still have old scopes
		installations, _ := env.store.ListInstallationsByApp(app.ID)
		for _, inst := range installations {
			var instScopes []string
			json.Unmarshal(inst.Scopes, &instScopes)
			for _, s := range instScopes {
				if s == "tools:write" {
					t.Error("existing installation should not have new scope tools:write")
				}
			}
		}
	})

	t.Run("reauthorize updates scopes", func(t *testing.T) {
		installations, _ := env.store.ListInstallationsByApp(app.ID)
		inst := installations[0]

		resp := doJSON(t, env.ts, "POST",
			"/api/apps/"+app.ID+"/installations/"+inst.ID+"/reauthorize",
			nil, withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			body := decodeJSON(t, resp)
			t.Fatalf("expected 200, got %d: %v", resp.StatusCode, body)
		}

		// Now should have new scope
		updated, _ := env.store.GetInstallation(inst.ID)
		var updatedScopes []string
		json.Unmarshal(updated.Scopes, &updatedScopes)
		found := false
		for _, s := range updatedScopes {
			if s == "tools:write" {
				found = true
			}
		}
		if !found {
			t.Error("reauthorized installation should have tools:write")
		}
	})

	t.Run("widening scopes beyond app is rejected", func(t *testing.T) {
		bot2 := createTestBot(t, env.store, env.user.ID, "widen-bot")
		resp := doJSON(t, env.ts, "POST", "/api/apps/"+app.ID+"/install",
			map[string]any{
				"bot_id": bot2.ID,
				"handle": "scope-widen-test",
				"scopes": []string{"message:write", "message:read", "tools:write", "admin:write"},
			}, withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusBadRequest {
			t.Fatalf("expected 400 for widened scopes, got %d", resp.StatusCode)
		}
	})

	t.Run("narrowing scopes is allowed", func(t *testing.T) {
		bot3 := createTestBot(t, env.store, env.user.ID, "narrow-bot")
		resp := doJSON(t, env.ts, "POST", "/api/apps/"+app.ID+"/install",
			map[string]any{
				"bot_id": bot3.ID,
				"handle": "scope-narrow-test",
				"scopes": []string{"message:write"},
			}, withCookie(env.cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusCreated {
			body := decodeJSON(t, resp)
			t.Fatalf("expected 201 for narrowed scopes, got %d: %v", resp.StatusCode, body)
		}

		body := decodeJSON(t, resp)
		instID := body["id"].(string)
		inst, _ := env.store.GetInstallation(instID)
		var instScopes []string
		json.Unmarshal(inst.Scopes, &instScopes)
		if len(instScopes) != 1 || instScopes[0] != "message:write" {
			t.Errorf("expected [message:write], got %v", instScopes)
		}
	})
}

// ---------------------------------------------------------------------------
// Test: Bot API update installation tools
// ---------------------------------------------------------------------------

func TestMarketplaceInstalledStatusIsPerUser(t *testing.T) {
	dbPath := filepath.Join(t.TempDir(), "test.db")
	s, err := sqlite.Open(dbPath)
	if err != nil {
		t.Fatalf("sqlite.Open: %v", err)
	}
	t.Cleanup(func() { s.Close() })

	user1, err := s.CreateUserFull("user1", "", "User One", "hashed", store.RoleMember)
	if err != nil {
		t.Fatalf("CreateUserFull(user1): %v", err)
	}
	user2, err := s.CreateUserFull("user2", "", "User Two", "hashed", store.RoleMember)
	if err != nil {
		t.Fatalf("CreateUserFull(user2): %v", err)
	}
	_ = s.UpdateUserStatus(user1.ID, store.StatusActive)
	_ = s.UpdateUserStatus(user2.ID, store.StatusActive)

	session1, err := auth.CreateSession(s, user1.ID)
	if err != nil {
		t.Fatalf("CreateSession(user1): %v", err)
	}
	session2, err := auth.CreateSession(s, user2.ID)
	if err != nil {
		t.Fatalf("CreateSession(user2): %v", err)
	}
	cookie1 := &http.Cookie{Name: "session", Value: session1}
	cookie2 := &http.Cookie{Name: "session", Value: session2}

	registryApps := []map[string]any{
		{
			"slug":        "remote-app",
			"name":        "Remote App",
			"description": "Remote app from registry",
			"version":     "1.0.0",
			"author":      "Registry Author",
			"homepage":    "https://example.com/remote-app",
			"tools":       []any{},
			"events":      []any{},
			"scopes":      []string{"message:write"},
		},
	}
	registryTS := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"version": 1,
			"apps":    registryApps,
		})
	}))
	t.Cleanup(registryTS.Close)

	reg := registry.NewClient(time.Hour)
	reg.AddSource("Test Registry", registryTS.URL)

	srv := &Server{
		Store:       s,
		Registry:    reg,
		Config:      &config.Config{RPOrigin: "http://localhost"},
		OAuthStates: authapi.NewOAuthStateStore(),
	}
	ts := httptest.NewServer(srv.Handler())
	t.Cleanup(ts.Close)

	app, err := s.CreateApp(&store.App{
		Name:        "Remote App",
		Slug:        "remote-app",
		Description: "Remote app from registry",
		Registry:    registryTS.URL,
		Version:     "1.0.0",
		Listing:     "listed",
		Scopes:      json.RawMessage(`["message:write"]`),
	})
	if err != nil {
		t.Fatalf("CreateApp(remote): %v", err)
	}

	bot1 := createTestBot(t, s, user1.ID, "User1 Bot")
	if _, err := s.InstallApp(app.ID, bot1.ID); err != nil {
		t.Fatalf("InstallApp(user1): %v", err)
	}

	assertInstalled := func(t *testing.T, cookie *http.Cookie, wantInstalled bool) {
		t.Helper()
		resp := doJSON(t, ts, "GET", "/api/marketplace", nil, withCookie(cookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			body := decodeJSON(t, resp)
			t.Fatalf("expected 200, got %d: %v", resp.StatusCode, body)
		}

		var body []map[string]any
		if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
			t.Fatalf("decode marketplace response: %v", err)
		}
		var remote map[string]any
		for _, app := range body {
			if slug, _ := app["slug"].(string); slug == "remote-app" {
				remote = app
				break
			}
		}
		if remote == nil {
			t.Fatalf("remote-app not found in marketplace response: %+v", body)
		}

		gotInstalled, _ := remote["installed"].(bool)
		if gotInstalled != wantInstalled {
			t.Fatalf("installed = %v, want %v; body = %+v", gotInstalled, wantInstalled, remote)
		}
	}

	t.Run("owner of installation sees installed", func(t *testing.T) {
		assertInstalled(t, cookie1, true)
	})

	t.Run("other user does not inherit installed status", func(t *testing.T) {
		assertInstalled(t, cookie2, false)
	})

	admin, err := s.CreateUserFull("admin", "", "Admin", "hashed", store.RoleAdmin)
	if err != nil {
		t.Fatalf("CreateUserFull(admin): %v", err)
	}
	_ = s.UpdateUserStatus(admin.ID, store.StatusActive)
	adminSession, err := auth.CreateSession(s, admin.ID)
	if err != nil {
		t.Fatalf("CreateSession(admin): %v", err)
	}
	adminCookie := &http.Cookie{Name: "session", Value: adminSession}

	t.Run("unlisting clears installed status for owner", func(t *testing.T) {
		resp := doJSON(t, ts, "PUT", "/api/admin/apps/"+app.ID+"/listing", map[string]any{
			"listing": "unlisted",
		}, withCookie(adminCookie))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			body := decodeJSON(t, resp)
			t.Fatalf("expected 200, got %d: %v", resp.StatusCode, body)
		}

		assertInstalled(t, cookie1, false)
	})
}

func TestUpdateListedAppWithEquivalentJSONDoesNotUnlist(t *testing.T) {
	env := setupTestEnv(t)

	app, err := env.store.CreateApp(&store.App{
		OwnerID:      env.user.ID,
		Name:         "Listed App",
		Slug:         "listed-app",
		Description:  "listed app",
		Listing:      "listed",
		Tools:        json.RawMessage(`[{"name":"tool","description":"Tool"}]`),
		Events:       json.RawMessage(`["reaction.added","message"]`),
		Scopes:       json.RawMessage(`["message:write","message:read"]`),
		ConfigSchema: `{"properties":{"name":{"type":"string"}},"type":"object"}`,
		Version:      "1.0.0",
	})
	if err != nil {
		t.Fatalf("CreateApp: %v", err)
	}
	bot := createTestBot(t, env.store, env.user.ID, "Listed Bot")
	inst, err := env.store.InstallApp(app.ID, bot.ID)
	if err != nil {
		t.Fatalf("InstallApp: %v", err)
	}

	resp := doJSON(t, env.ts, "PUT", "/api/apps/"+app.ID, map[string]any{
		"tools":         []map[string]any{{"description": "Tool", "name": "tool"}},
		"events":        []string{"message", "reaction.added", "message"},
		"scopes":        []string{"message:read", "message:write", "message:read"},
		"config_schema": "{\n  \"type\": \"object\",\n  \"properties\": {\n    \"name\": {\n      \"type\": \"string\"\n    }\n  }\n}",
		"version":       "1.0.0",
	}, withCookie(env.cookie))
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		body := decodeJSON(t, resp)
		t.Fatalf("expected 200, got %d: %v", resp.StatusCode, body)
	}

	updated, err := env.store.GetApp(app.ID)
	if err != nil {
		t.Fatalf("GetApp: %v", err)
	}
	if updated.Listing != "listed" {
		t.Fatalf("listing = %q, want listed", updated.Listing)
	}
	gotInst, err := env.store.GetInstallation(inst.ID)
	if err != nil {
		t.Fatalf("GetInstallation: %v", err)
	}
	if gotInst.ID != inst.ID {
		t.Fatalf("installation id = %q, want %q", gotInst.ID, inst.ID)
	}
}
