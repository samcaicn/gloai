package appapi_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/ceoadmin/CEOadmin/internal/apptest"
	"github.com/ceoadmin/CEOadmin/internal/provider/ilink/mockserver"
	"github.com/ceoadmin/CEOadmin/internal/store"
	"github.com/gorilla/websocket"
)

func TestAppEventDelivery_HTTPAndWebSocket(t *testing.T) {
	env := apptest.Setup(t)
	defer env.Close()

	// --- Webhook receiver ---
	var webhookCalls atomic.Int32
	var webhookMu sync.Mutex
	var lastWebhookBody map[string]any
	hookSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		webhookCalls.Add(1)
		var body map[string]any
		json.NewDecoder(r.Body).Decode(&body)
		webhookMu.Lock()
		lastWebhookBody = body
		webhookMu.Unlock()
		w.WriteHeader(http.StatusOK)
	}))
	defer hookSrv.Close()

	// --- Create user, bot, app, installation ---
	env.Register("appdeliveryuser", "password123")
	botObj := env.CreateBotForUser("DeliveryBot")
	if err := env.Mgr.StartBot(context.Background(), botObj); err != nil {
		t.Fatalf("StartBot: %v", err)
	}

	uid := env.UserID()
	appObj, err := env.Store.CreateApp(&store.App{
		OwnerID:    uid,
		Name:       "DeliveryApp",
		Slug:       "delivery-app-inttest",
		Events:     json.RawMessage(`["message"]`),
		Scopes:     json.RawMessage(`["message:read","message:write"]`),
		Tools:      json.RawMessage(`[]`),
		WebhookURL: hookSrv.URL,
	})
	if err != nil {
		t.Fatalf("CreateApp: %v", err)
	}

	inst, err := env.Store.InstallApp(appObj.ID, botObj.ID)
	if err != nil {
		t.Fatalf("InstallApp: %v", err)
	}
	// Grant scopes — handleInstallApp does this via UpdateInstallation; direct
	// db.InstallApp leaves scopes as "[]", which fails instHasScope checks.
	if err := env.Store.UpdateInstallation(inst.ID, "", inst.Config, appObj.Scopes, true); err != nil {
		t.Fatalf("UpdateInstallation scopes: %v", err)
	}
	time.Sleep(100 * time.Millisecond) // allow bot dispatcher to settle

	botInst, ok := env.Mgr.GetInstance(botObj.ID)
	if !ok {
		t.Fatal("bot instance not found")
	}
	engine := botInst.Provider.(*mockserver.Provider).Engine()

	// ==============================================================
	// Phase 1: HTTP (webhook) delivery — no WS connected yet
	// ==============================================================
	engine.InjectInbound(mockserver.InboundRequest{
		Sender: "user-http@test",
		Text:   "hello via http",
	})
	// Poll until webhook is called instead of a fixed sleep.
	deadline := time.After(3 * time.Second)
	for webhookCalls.Load() < 1 {
		select {
		case <-deadline:
			t.Fatal("phase 1: timed out waiting for webhook call")
		default:
			time.Sleep(50 * time.Millisecond)
		}
	}

	if n := webhookCalls.Load(); n != 1 {
		t.Errorf("phase 1: want 1 webhook call, got %d", n)
	} else {
		// Verify payload shape
		webhookMu.Lock()
		body := lastWebhookBody
		webhookMu.Unlock()
		event, _ := body["event"].(map[string]any)
		data, _ := event["data"].(map[string]any)
		if data["content"] != "hello via http" {
			t.Errorf("phase 1: webhook content = %v, want 'hello via http'", data["content"])
		}
	}

	// ==============================================================
	// Phase 2: WebSocket delivery — connect, then inject a message
	// ==============================================================
	wsURL := "ws" + strings.TrimPrefix(env.Srv.URL, "http") + "/bot/v1/ws?token=" + inst.AppToken
	ws, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		t.Fatalf("ws dial: %v", err)
	}
	defer ws.Close()

	// Consume and verify the init message
	ws.SetReadDeadline(time.Now().Add(3 * time.Second))
	_, initRaw, err := ws.ReadMessage()
	if err != nil {
		t.Fatalf("ws: read init: %v", err)
	}
	ws.SetReadDeadline(time.Time{})
	var initMsg map[string]any
	if err := json.Unmarshal(initRaw, &initMsg); err != nil {
		t.Fatalf("ws: unmarshal init: %v", err)
	}
	if initMsg["type"] != "init" {
		t.Errorf("ws init type = %v, want 'init'", initMsg["type"])
	}

	webhookBefore := webhookCalls.Load()

	engine.InjectInbound(mockserver.InboundRequest{
		Sender: "user-ws@test",
		Text:   "hello via websocket",
	})

	// Read the event envelope from the WS connection
	ws.SetReadDeadline(time.Now().Add(3 * time.Second))
	_, raw, err := ws.ReadMessage()
	ws.SetReadDeadline(time.Time{})
	if err != nil {
		t.Fatalf("phase 2: timed out waiting for WS event: %v", err)
	}

	var envelope map[string]any
	if err := json.Unmarshal(raw, &envelope); err != nil {
		t.Fatalf("phase 2: unmarshal ws envelope: %v", err)
	}
	if envelope["type"] != "event" {
		t.Errorf("phase 2: envelope type = %v, want 'event'", envelope["type"])
	}
	if envelope["installation_id"] != inst.ID {
		t.Errorf("phase 2: installation_id = %v, want %q", envelope["installation_id"], inst.ID)
	}
	wsEvent, _ := envelope["event"].(map[string]any)
	wsData, _ := wsEvent["data"].(map[string]any)
	if wsData["content"] != "hello via websocket" {
		t.Errorf("phase 2: ws content = %v, want 'hello via websocket'", wsData["content"])
	}

	// Webhook must ALSO have been called — channels are independent (#208 fix).
	time.Sleep(500 * time.Millisecond)
	if webhookCalls.Load() != webhookBefore+1 {
		t.Errorf("phase 2: want 1 extra webhook call (channels are independent), got %d", webhookCalls.Load()-webhookBefore)
	}
}
