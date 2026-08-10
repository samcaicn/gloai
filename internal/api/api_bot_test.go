package api

import (
	"encoding/json"
	"net/http"
	"testing"

	"github.com/ceoadmin/CEOadmin/internal/store"
)

func TestBotAPI_ScopeChecks(t *testing.T) {
	env := setupTestEnv(t)
	bot := createTestBot(t, env.store, env.user.ID, "scope-bot")

	// App with only message:write scope.
	appMsgOnly := createTestApp(t, env.store, env.user.ID, "msg-app", "msg-app", []string{"message:write"})
	instMsgOnly := installTestApp(t, env.store, appMsgOnly.ID, bot.ID)

	// App with all three scopes.
	appAll := createTestApp(t, env.store, env.user.ID, "all-app", "all-app",
		[]string{"message:write", "contact:read", "bot:read"})
	instAll := installTestApp(t, env.store, appAll.ID, bot.ID)

	t.Run("message:write scope allows send", func(t *testing.T) {
		resp := doJSON(t, env.ts, "POST", "/bot/v1/message/send",
			map[string]string{"content": "hello"},
			withBearer(instMsgOnly.AppToken))
		defer resp.Body.Close()
		// Scope check should pass. We expect some non-403 error because
		// BotManager is nil (no live bot). 503 or panic-recovery 500 are OK.
		if resp.StatusCode == http.StatusForbidden {
			body := decodeJSON(t, resp)
			t.Fatalf("expected scope check to pass, got 403: %v", body)
		}
	})

	t.Run("missing contact:read scope denied", func(t *testing.T) {
		resp := doJSON(t, env.ts, "GET", "/bot/v1/contact", nil,
			withBearer(instMsgOnly.AppToken))
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusForbidden {
			t.Errorf("expected 403 for missing contact:read scope, got %d", resp.StatusCode)
		}
		body := decodeJSON(t, resp)
		if msg, _ := body["error"].(string); msg != "missing scope: contact:read" {
			t.Errorf("unexpected error message: %q", msg)
		}
	})

	t.Run("missing bot:read scope denied", func(t *testing.T) {
		resp := doJSON(t, env.ts, "GET", "/bot/v1/info", nil,
			withBearer(instMsgOnly.AppToken))
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusForbidden {
			t.Errorf("expected 403 for missing bot:read scope, got %d", resp.StatusCode)
		}
	})

	t.Run("all scopes allow contact endpoint", func(t *testing.T) {
		resp := doJSON(t, env.ts, "GET", "/bot/v1/contact", nil,
			withBearer(instAll.AppToken))
		defer resp.Body.Close()
		if resp.StatusCode == http.StatusForbidden {
			t.Fatalf("expected scope check to pass for contact:read, got 403")
		}
		// 200 or 500 (if store query fails) are both acceptable.
	})

	t.Run("all scopes allow bot info endpoint", func(t *testing.T) {
		resp := doJSON(t, env.ts, "GET", "/bot/v1/info", nil,
			withBearer(instAll.AppToken))
		defer resp.Body.Close()
		if resp.StatusCode == http.StatusForbidden {
			t.Fatalf("expected scope check to pass for bot:read, got 403")
		}
	})

	t.Run("backward compat paths also check scopes", func(t *testing.T) {
		// /bot/v1/contacts (old path) with missing scope
		resp := doJSON(t, env.ts, "GET", "/bot/v1/contacts", nil,
			withBearer(instMsgOnly.AppToken))
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusForbidden {
			t.Errorf("old path /bot/v1/contacts should deny missing scope, got %d", resp.StatusCode)
		}

		// /bot/v1/bot (old path) with missing scope
		resp2 := doJSON(t, env.ts, "GET", "/bot/v1/bot", nil,
			withBearer(instMsgOnly.AppToken))
		defer resp2.Body.Close()
		if resp2.StatusCode != http.StatusForbidden {
			t.Errorf("old path /bot/v1/bot should deny missing scope, got %d", resp2.StatusCode)
		}
	})

	t.Run("invalid token returns 401", func(t *testing.T) {
		resp := doJSON(t, env.ts, "GET", "/bot/v1/contact", nil,
			withBearer("totally-invalid-token"))
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusUnauthorized {
			t.Errorf("expected 401 for invalid token, got %d", resp.StatusCode)
		}
	})

	t.Run("missing auth header returns 401", func(t *testing.T) {
		resp := doJSON(t, env.ts, "GET", "/bot/v1/contact", nil)
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusUnauthorized {
			t.Errorf("expected 401 for missing header, got %d", resp.StatusCode)
		}
	})

	t.Run("disabled installation returns 403", func(t *testing.T) {
		// Disable the installation.
		_ = env.store.UpdateInstallation(instMsgOnly.ID, instMsgOnly.Handle, instMsgOnly.Config, instMsgOnly.Scopes, false)

		resp := doJSON(t, env.ts, "POST", "/bot/v1/message/send",
			map[string]string{"content": "hello"},
			withBearer(instMsgOnly.AppToken))
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusForbidden {
			t.Errorf("expected 403 for disabled installation, got %d", resp.StatusCode)
		}

		// Re-enable for other tests.
		_ = env.store.UpdateInstallation(instMsgOnly.ID, instMsgOnly.Handle, instMsgOnly.Config, instMsgOnly.Scopes, true)
	})
}

// ---------------------------------------------------------------------------
// Test: App CRUD via dashboard API (session auth)
// ---------------------------------------------------------------------------

func TestBotAPI_NewScopeFormat(t *testing.T) {
	env := setupTestEnv(t)
	bot := createTestBot(t, env.store, env.user.ID, "scope-format-bot")

	// App explicitly using new colon-separated scope names.
	app := createTestApp(t, env.store, env.user.ID, "New Scope App", "new-scope-app",
		[]string{"message:write", "message:read", "contact:read", "bot:read"})
	inst := installTestApp(t, env.store, app.ID, bot.ID)

	endpoints := []struct {
		method string
		path   string
		scope  string
		body   any
	}{
		{"POST", "/bot/v1/message/send", "message:write", map[string]string{"content": "hi"}},
		{"GET", "/bot/v1/contact", "contact:read", nil},
		{"GET", "/bot/v1/info", "bot:read", nil},
	}

	for _, ep := range endpoints {
		t.Run(ep.scope+" allows "+ep.path, func(t *testing.T) {
			resp := doJSON(t, env.ts, ep.method, ep.path, ep.body, withBearer(inst.AppToken))
			defer resp.Body.Close()
			if resp.StatusCode == http.StatusForbidden {
				body := decodeJSON(t, resp)
				t.Fatalf("scope %q should grant access to %s, got 403: %v", ep.scope, ep.path, body)
			}
		})
	}

	// App with hypothetical OLD scope names (if someone stored them wrong).
	appOld := createTestApp(t, env.store, env.user.ID, "Old Scope App", "old-scope-app",
		[]string{"send_message", "read_contacts", "read_bot"})
	instOld := installTestApp(t, env.store, appOld.ID, bot.ID)

	for _, ep := range endpoints {
		t.Run("old scope format denied for "+ep.path, func(t *testing.T) {
			resp := doJSON(t, env.ts, ep.method, ep.path, ep.body, withBearer(instOld.AppToken))
			defer resp.Body.Close()
			if resp.StatusCode != http.StatusForbidden {
				t.Errorf("old scope format should be denied for %s, got %d", ep.path, resp.StatusCode)
			}
		})
	}
}

// ---------------------------------------------------------------------------
// Test: App list endpoint returns correct data
// ---------------------------------------------------------------------------

func TestBotAPI_UnknownEndpoint(t *testing.T) {
	env := setupTestEnv(t)
	bot := createTestBot(t, env.store, env.user.ID, "404-bot")
	app := createTestApp(t, env.store, env.user.ID, "404 App", "four-oh-four", []string{"message:write"})
	inst := installTestApp(t, env.store, app.ID, bot.ID)

	resp := doJSON(t, env.ts, "GET", "/bot/v1/nonexistent", nil, withBearer(inst.AppToken))
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusNotFound {
		t.Errorf("expected 404, got %d", resp.StatusCode)
	}
}

// ---------------------------------------------------------------------------
// Test: Listing submission validation
// ---------------------------------------------------------------------------

func TestBotAPI_UpdateTools(t *testing.T) {
	env := setupTestEnv(t)

	// Create app with tools:write scope
	app := createTestApp(t, env.store, env.user.ID, "Dynamic Tools App", "dynamic-tools-app", []string{"tools:write"})

	// Create bot and install
	bot := createTestBot(t, env.store, env.user.ID, "tools-bot")
	inst := installTestApp(t, env.store, app.ID, bot.ID)

	t.Run("update tools succeeds with scope", func(t *testing.T) {
		newTools := []map[string]any{
			{"name": "hn", "description": "HackerNews top", "command": "hn"},
			{"name": "weather", "description": "Check weather", "command": "weather"},
		}
		toolsJSON, _ := json.Marshal(newTools)

		resp := doJSON(t, env.ts, "PUT", "/bot/v1/app/tools",
			map[string]any{"tools": json.RawMessage(toolsJSON)},
			withBearer(inst.AppToken))
		defer resp.Body.Close()

		if resp.StatusCode != 200 {
			body := decodeJSON(t, resp)
			t.Fatalf("expected 200, got %d: %v", resp.StatusCode, body)
		}
		body := decodeJSON(t, resp)
		if body["tool_count"] != float64(2) {
			t.Errorf("tool_count = %v, want 2", body["tool_count"])
		}
		if body["scope"] != "app" {
			t.Errorf("scope = %v, want app", body["scope"])
		}

		// Verify tools were actually updated
		updated, err := env.store.GetApp(app.ID)
		if err != nil {
			t.Fatalf("GetApp: %v", err)
		}
		var tools []map[string]any
		json.Unmarshal(updated.Tools, &tools)
		if len(tools) != 2 {
			t.Errorf("stored tools count = %d, want 2", len(tools))
		}
	})

	t.Run("update tools fails without scope", func(t *testing.T) {
		// Create app WITHOUT tools:write
		app2 := createTestApp(t, env.store, env.user.ID, "No Scope App", "no-scope-app", []string{"message:write"})
		inst2 := installTestApp(t, env.store, app2.ID, bot.ID)

		resp := doJSON(t, env.ts, "PUT", "/bot/v1/app/tools",
			map[string]any{"tools": json.RawMessage(`[{"name":"test"}]`)},
			withBearer(inst2.AppToken))
		defer resp.Body.Close()

		if resp.StatusCode != 403 {
			t.Fatalf("expected 403, got %d", resp.StatusCode)
		}
	})

	t.Run("update tools on marketplace app falls back to installation tools", func(t *testing.T) {
		// Create marketplace app (has registry set)
		mktScopes, _ := json.Marshal([]string{"tools:write"})
		mktApp, err := env.store.CreateApp(&store.App{
			OwnerID:  env.user.ID,
			Name:     "Marketplace App",
			Slug:     "mkt-tools-app",
			Scopes:   mktScopes,
			Registry: "https://some-registry.com",
		})
		if err != nil {
			t.Fatalf("CreateApp: %v", err)
		}
		mktInst := installTestApp(t, env.store, mktApp.ID, bot.ID)

		resp := doJSON(t, env.ts, "PUT", "/bot/v1/app/tools",
			map[string]any{"tools": json.RawMessage(`[{"name":"test"}]`)},
			withBearer(mktInst.AppToken))
		defer resp.Body.Close()

		if resp.StatusCode != 200 {
			body := decodeJSON(t, resp)
			t.Fatalf("expected 200, got %d: %v", resp.StatusCode, body)
		}
		body := decodeJSON(t, resp)
		if body["scope"] != "installation" {
			t.Errorf("scope = %v, want installation", body["scope"])
		}

		// AppDef must remain untouched — parse the tools and assert it's still empty
		updatedApp, err := env.store.GetApp(mktApp.ID)
		if err != nil {
			t.Fatalf("GetApp: %v", err)
		}
		var appTools []map[string]any
		if len(updatedApp.Tools) > 0 {
			if err := json.Unmarshal(updatedApp.Tools, &appTools); err != nil {
				t.Fatalf("unmarshal app tools: %v", err)
			}
		}
		if len(appTools) != 0 {
			t.Errorf("marketplace app tools should not be modified, got %s", string(updatedApp.Tools))
		}

		// Installation tools should be set
		updatedInst, err := env.store.GetInstallation(mktInst.ID)
		if err != nil {
			t.Fatalf("GetInstallation: %v", err)
		}
		var instTools []map[string]any
		if err := json.Unmarshal(updatedInst.Tools, &instTools); err != nil {
			t.Fatalf("unmarshal installation tools: %v", err)
		}
		if len(instTools) != 1 || instTools[0]["name"] != "test" {
			t.Errorf("installation tools = %v, want [{name:test}]", instTools)
		}
	})

	t.Run("update tools on builtin app falls back to installation tools", func(t *testing.T) {
		// Create builtin app
		biScopes, _ := json.Marshal([]string{"tools:write"})
		biApp, err := env.store.CreateApp(&store.App{
			OwnerID:  env.user.ID,
			Name:     "Builtin App",
			Slug:     "bi-tools-app",
			Scopes:   biScopes,
			Registry: "builtin",
		})
		if err != nil {
			t.Fatalf("CreateApp: %v", err)
		}
		biInst := installTestApp(t, env.store, biApp.ID, bot.ID)

		resp := doJSON(t, env.ts, "PUT", "/bot/v1/app/tools",
			map[string]any{"tools": json.RawMessage(`[{"name":"test"}]`)},
			withBearer(biInst.AppToken))
		defer resp.Body.Close()

		if resp.StatusCode != 200 {
			body := decodeJSON(t, resp)
			t.Fatalf("expected 200, got %d: %v", resp.StatusCode, body)
		}
		body := decodeJSON(t, resp)
		if body["scope"] != "installation" {
			t.Errorf("scope = %v, want installation", body["scope"])
		}
	})

	t.Run("invalid tools format rejected", func(t *testing.T) {
		resp := doJSON(t, env.ts, "PUT", "/bot/v1/app/tools",
			map[string]any{"tools": "not an array"},
			withBearer(inst.AppToken))
		defer resp.Body.Close()

		if resp.StatusCode != 400 {
			t.Fatalf("expected 400, got %d", resp.StatusCode)
		}
	})
}

func TestBotAPI_UpdateInstallationTools(t *testing.T) {
	env := setupTestEnv(t)
	bot := createTestBot(t, env.store, env.user.ID, "inst-tools-bot")

	// App with tools:write scope.
	appWithScope := createTestApp(t, env.store, env.user.ID, "tools-app", "tools-app",
		[]string{"tools:write"})
	instWithScope := installTestApp(t, env.store, appWithScope.ID, bot.ID)

	// App without tools:write scope.
	appNoScope := createTestApp(t, env.store, env.user.ID, "no-tools-app", "no-tools-app",
		[]string{"message:write"})
	instNoScope := installTestApp(t, env.store, appNoScope.ID, bot.ID)

	t.Run("update installation tools succeeds", func(t *testing.T) {
		tools := []map[string]string{
			{"name": "hn", "description": "Hacker News", "command": "hn"},
			{"name": "weather", "description": "Weather report", "command": "weather"},
		}
		resp := doJSON(t, env.ts, "PUT", "/bot/v1/installation/tools",
			map[string]any{"tools": tools},
			withBearer(instWithScope.AppToken))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			body := decodeJSON(t, resp)
			t.Fatalf("expected 200, got %d: %v", resp.StatusCode, body)
		}
		body := decodeJSON(t, resp)
		if body["ok"] != true {
			t.Errorf("expected ok=true, got %v", body["ok"])
		}
		if tc, _ := body["tool_count"].(float64); tc != 2 {
			t.Errorf("expected tool_count=2, got %v", tc)
		}

		// Verify tools stored on installation (not app).
		inst, err := env.store.GetInstallation(instWithScope.ID)
		if err != nil {
			t.Fatalf("GetInstallation: %v", err)
		}
		var storedTools []store.AppTool
		if err := json.Unmarshal(inst.Tools, &storedTools); err != nil {
			t.Fatalf("unmarshal tools: %v", err)
		}
		if len(storedTools) != 2 {
			t.Errorf("expected 2 tools on installation, got %d", len(storedTools))
		}

		// App-level tools should remain unchanged (empty).
		app, _ := env.store.GetApp(appWithScope.ID)
		var appTools []store.AppTool
		json.Unmarshal(app.Tools, &appTools)
		if len(appTools) != 0 {
			t.Errorf("app tools should be empty, got %d", len(appTools))
		}
	})

	t.Run("missing tools:write scope returns 403", func(t *testing.T) {
		resp := doJSON(t, env.ts, "PUT", "/bot/v1/installation/tools",
			map[string]any{"tools": []any{}},
			withBearer(instNoScope.AppToken))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusForbidden {
			t.Errorf("expected 403, got %d", resp.StatusCode)
		}
	})

	t.Run("invalid tools format returns 400", func(t *testing.T) {
		resp := doJSON(t, env.ts, "PUT", "/bot/v1/installation/tools",
			map[string]any{"tools": "not-an-array"},
			withBearer(instWithScope.AppToken))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusBadRequest {
			t.Errorf("expected 400, got %d", resp.StatusCode)
		}
	})

	t.Run("missing tools field returns 400", func(t *testing.T) {
		resp := doJSON(t, env.ts, "PUT", "/bot/v1/installation/tools",
			map[string]any{},
			withBearer(instWithScope.AppToken))
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusBadRequest {
			t.Errorf("expected 400, got %d", resp.StatusCode)
		}
	})
}

func TestSetBotAIModel(t *testing.T) {
	env := setupTestEnv(t)
	bot := createTestBot(t, env.store, env.user.ID, "model-bot")

	// Set a model
	resp := doJSON(t, env.ts, "PUT", "/api/bots/"+bot.ID+"/ai_model",
		map[string]string{"model": "gpt-4o"},
		withCookie(env.cookie))
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}

	// Verify it was saved
	updated, err := env.store.GetBot(bot.ID)
	if err != nil {
		t.Fatalf("GetBot: %v", err)
	}
	if updated.AIModel != "gpt-4o" {
		t.Errorf("expected AIModel %q, got %q", "gpt-4o", updated.AIModel)
	}

	// Clear model (use global default)
	resp2 := doJSON(t, env.ts, "PUT", "/api/bots/"+bot.ID+"/ai_model",
		map[string]string{"model": ""},
		withCookie(env.cookie))
	defer resp2.Body.Close()
	if resp2.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d", resp2.StatusCode)
	}

	updated, err = env.store.GetBot(bot.ID)
	if err != nil {
		t.Fatalf("GetBot: %v", err)
	}
	if updated.AIModel != "" {
		t.Errorf("expected AIModel %q, got %q", "", updated.AIModel)
	}
}
