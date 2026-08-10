package messageapi_test

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/ceoadmin/CEOadmin/internal/apptest"
	"github.com/ceoadmin/CEOadmin/internal/provider/ilink/mockserver"
	"github.com/ceoadmin/CEOadmin/internal/store"
)

func TestWebhookDelivery(t *testing.T) {
	t.Skip("SKIP: channel webhook sink not wired into inbound delivery chain (sink.Webhook never invoked by bot.Manager.onInbound); pending feature — see docs/architecture-optimization-plan.md")
	env := apptest.Setup(t)
	defer env.Close()

	// Set up a webhook receiver
	var received []map[string]any
	var receivedHeaders http.Header
	hookSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedHeaders = r.Header
		var body map[string]any
		json.NewDecoder(r.Body).Decode(&body)
		received = append(received, body)
		w.WriteHeader(200)
	}))
	defer hookSrv.Close()

	env.Register("hookuser", "password123")
	botObj := env.CreateBotForUser("Bot1")
	env.Mgr.StartBot(context.Background(), botObj)

	// Create channel with webhook
	ch, _ := env.Store.CreateChannel(botObj.ID, "HookChan", "", nil, nil)
	env.Store.UpdateChannel(ch.ID, ch.Name, ch.Handle, &ch.FilterRule, &ch.AIConfig,
		&store.WebhookConfig{URL: hookSrv.URL, Auth: &store.WebhookAuth{Type: "bearer", Token: "test-token"}}, true)

	inst, _ := env.Mgr.GetInstance(botObj.ID)
	mock := inst.Provider.(*mockserver.Provider)

	mock.Engine().InjectInbound(mockserver.InboundRequest{
		Sender: "hook@wx",
		Text:   "webhook test",
	})

	// Wait for async webhook delivery
	time.Sleep(500 * time.Millisecond)

	if len(received) != 1 {
		t.Fatalf("want 1 webhook delivery, got %d", len(received))
	}

	msg := received[0]
	if msg["event"] != "message" {
		t.Errorf("event = %v", msg["event"])
	}
	if msg["sender"] != "hook@wx" {
		t.Errorf("sender = %v", msg["sender"])
	}
	if msg["content"] != "webhook test" {
		t.Errorf("content = %v", msg["content"])
	}
	if msg["channel_id"] != ch.ID {
		t.Errorf("channel_id = %v, want %s", msg["channel_id"], ch.ID)
	}

	// Verify bearer auth header
	auth := receivedHeaders.Get("Authorization")
	if auth != "Bearer test-token" {
		t.Errorf("Authorization = %q, want Bearer test-token", auth)
	}
}

func TestWebhookHMACSignature(t *testing.T) {
	t.Skip("SKIP: channel webhook sink not wired into inbound delivery chain (sink.Webhook never invoked by bot.Manager.onInbound); pending feature — see docs/architecture-optimization-plan.md")
	env := apptest.Setup(t)
	defer env.Close()

	var signature string
	hookSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		signature = r.Header.Get("X-Hub-Signature")
		w.WriteHeader(200)
	}))
	defer hookSrv.Close()

	env.Register("hmacuser", "password123")
	botObj := env.CreateBotForUser("Bot1")
	env.Mgr.StartBot(context.Background(), botObj)

	ch, _ := env.Store.CreateChannel(botObj.ID, "HmacChan", "", nil, nil)
	env.Store.UpdateChannel(ch.ID, ch.Name, ch.Handle, &ch.FilterRule, &ch.AIConfig,
		&store.WebhookConfig{URL: hookSrv.URL, Auth: &store.WebhookAuth{Type: "hmac", Secret: "my-secret"}}, true)

	inst, _ := env.Mgr.GetInstance(botObj.ID)
	mock := inst.Provider.(*mockserver.Provider)

	mock.Engine().InjectInbound(mockserver.InboundRequest{
		Sender: "hmac@wx",
		Text:   "signed",
	})

	time.Sleep(500 * time.Millisecond)

	if !strings.HasPrefix(signature, "sha256=") {
		t.Errorf("signature = %q, want sha256=...", signature)
	}
	if len(signature) != 7+64 { // "sha256=" + 64 hex chars
		t.Errorf("signature length = %d", len(signature))
	}
}

func TestWebhookWithScript(t *testing.T) {
	t.Skip("SKIP: channel webhook sink not wired into inbound delivery chain (sink.Webhook never invoked by bot.Manager.onInbound); pending feature — see docs/architecture-optimization-plan.md")
	env := apptest.Setup(t)
	defer env.Close()

	var receivedBody string
	var receivedHeaders http.Header
	hookSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedHeaders = r.Header
		b := new(bytes.Buffer)
		b.ReadFrom(r.Body)
		receivedBody = b.String()
		w.WriteHeader(200)
	}))
	defer hookSrv.Close()

	env.Register("scriptuser", "password123")
	botObj := env.CreateBotForUser("Bot1")
	env.Mgr.StartBot(context.Background(), botObj)

	ch, _ := env.Store.CreateChannel(botObj.ID, "ScriptChan", "", nil, nil)

	// Script uses onRequest to modify, onResponse to reply
	script := `
function onRequest(ctx) {
  ctx.req.headers["X-Custom"] = "hello";
  ctx.req.body = JSON.stringify({text: ctx.msg.sender + ": " + ctx.msg.content});
}
`
	env.Store.UpdateChannel(ch.ID, ch.Name, ch.Handle, &ch.FilterRule, &ch.AIConfig,
		&store.WebhookConfig{URL: hookSrv.URL, Script: script}, true)

	inst, _ := env.Mgr.GetInstance(botObj.ID)
	mock := inst.Provider.(*mockserver.Provider)

	mock.Engine().InjectInbound(mockserver.InboundRequest{
		Sender: "alice@wx",
		Text:   "script test",
	})

	time.Sleep(500 * time.Millisecond)

	if receivedBody == "" {
		t.Fatal("no webhook received")
	}

	var body map[string]any
	json.Unmarshal([]byte(receivedBody), &body)
	if body["text"] != "alice@wx: script test" {
		t.Errorf("body = %v", body)
	}
	if receivedHeaders.Get("X-Custom") != "hello" {
		t.Errorf("X-Custom = %q", receivedHeaders.Get("X-Custom"))
	}
}

func TestWebhookScriptSkip(t *testing.T) {
	t.Skip("SKIP: channel webhook sink not wired into inbound delivery chain (sink.Webhook never invoked by bot.Manager.onInbound); pending feature — see docs/architecture-optimization-plan.md")
	env := apptest.Setup(t)
	defer env.Close()

	received := false
	hookSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		received = true
		w.WriteHeader(200)
	}))
	defer hookSrv.Close()

	env.Register("skipuser", "password123")
	botObj := env.CreateBotForUser("Bot1")
	env.Mgr.StartBot(context.Background(), botObj)

	ch, _ := env.Store.CreateChannel(botObj.ID, "SkipChan", "", nil, nil)

	// Script skips non-text messages
	script := `
function onRequest(ctx) {
  if (ctx.msg.msg_type !== "text") skip();
}
`
	env.Store.UpdateChannel(ch.ID, ch.Name, ch.Handle, &ch.FilterRule, &ch.AIConfig,
		&store.WebhookConfig{URL: hookSrv.URL, Script: script}, true)

	inst, _ := env.Mgr.GetInstance(botObj.ID)
	mock := inst.Provider.(*mockserver.Provider)

	// Text message → should deliver
	mock.Engine().InjectInbound(mockserver.InboundRequest{
		Sender: "u@wx",
		Text:   "hello",
	})
	time.Sleep(300 * time.Millisecond)
	if !received {
		t.Error("text message should trigger webhook")
	}

	// Image message → script returns null, should skip
	received = false
	mock.Engine().InjectInbound(mockserver.InboundRequest{
		Sender: "u@wx",
		Items:  []mockserver.ItemRequest{{Type: "image"}},
	})
	time.Sleep(300 * time.Millisecond)
	if received {
		t.Error("image message should be skipped by script")
	}
}

func TestWebhookOnResponse(t *testing.T) {
	t.Skip("SKIP: channel webhook sink not wired into inbound delivery chain (sink.Webhook never invoked by bot.Manager.onInbound); pending feature — see docs/architecture-optimization-plan.md")
	env := apptest.Setup(t)
	defer env.Close()

	// Webhook server returns {"answer": "42"}
	hookSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"answer": "42"}`))
	}))
	defer hookSrv.Close()

	env.Register("respuser", "password123")
	botObj := env.CreateBotForUser("Bot1")
	env.Mgr.StartBot(context.Background(), botObj)

	ch, _ := env.Store.CreateChannel(botObj.ID, "RespChan", "", nil, nil)
	script := `
function onResponse(ctx) {
  var data = JSON.parse(ctx.res.body);
  if (data.answer) reply(data.answer);
}
`
	env.Store.UpdateChannel(ch.ID, ch.Name, ch.Handle, &ch.FilterRule, &ch.AIConfig,
		&store.WebhookConfig{URL: hookSrv.URL, Script: script}, true)

	inst, _ := env.Mgr.GetInstance(botObj.ID)
	mock := inst.Provider.(*mockserver.Provider)

	mock.Engine().InjectInbound(mockserver.InboundRequest{
		Sender: "u@wx",
		Text:   "question",
	})

	time.Sleep(500 * time.Millisecond)

	// Verify bot sent reply "42" back to user
	sent := mock.Engine().SentMessages()
	found := false
	for _, m := range sent {
		if m.Text == "42" {
			found = true
		}
	}
	if !found {
		t.Errorf("expected reply '42', sent = %+v", sent)
	}

	// Verify reply saved in DB
	msgs, _ := env.Store.ListChannelMessages(ch.ID, "u@wx", 10)
	replyFound := false
	for _, m := range msgs {
		if strings.Contains(string(m.ItemList), "42") && m.Direction == "outbound" {
			replyFound = true
		}
	}
	if !replyFound {
		t.Error("reply not saved in DB")
	}
}

func TestWebhookAutoReplyWithoutScript(t *testing.T) {
	t.Skip("SKIP: channel webhook sink not wired into inbound delivery chain (sink.Webhook never invoked by bot.Manager.onInbound); pending feature — see docs/architecture-optimization-plan.md")
	env := apptest.Setup(t)
	defer env.Close()

	// Server returns {"reply": "auto-reply"}
	hookSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"reply": "auto-reply"}`))
	}))
	defer hookSrv.Close()

	env.Register("autouser", "password123")
	botObj := env.CreateBotForUser("Bot1")
	env.Mgr.StartBot(context.Background(), botObj)

	ch, _ := env.Store.CreateChannel(botObj.ID, "AutoChan", "", nil, nil)
	// No script — auto-reply from {"reply": "..."} in response
	env.Store.UpdateChannel(ch.ID, ch.Name, ch.Handle, &ch.FilterRule, &ch.AIConfig,
		&store.WebhookConfig{URL: hookSrv.URL}, true)

	inst, _ := env.Mgr.GetInstance(botObj.ID)
	mock := inst.Provider.(*mockserver.Provider)

	mock.Engine().InjectInbound(mockserver.InboundRequest{
		Sender: "u@wx",
		Text:   "hi",
	})

	time.Sleep(500 * time.Millisecond)

	sent := mock.Engine().SentMessages()
	found := false
	for _, m := range sent {
		if m.Text == "auto-reply" {
			found = true
		}
	}
	if !found {
		t.Errorf("expected auto-reply, sent = %+v", sent)
	}
}

func TestWebhookNotTriggeredWithoutURL(t *testing.T) {
	t.Skip("SKIP: channel webhook sink not wired into inbound delivery chain (sink.Webhook never invoked by bot.Manager.onInbound); pending feature — see docs/architecture-optimization-plan.md")
	env := apptest.Setup(t)
	defer env.Close()

	env.Register("nohook", "password123")
	botObj := env.CreateBotForUser("Bot1")
	env.Mgr.StartBot(context.Background(), botObj)

	// Channel without webhook
	ch, _ := env.Store.CreateChannel(botObj.ID, "NoHook", "", nil, nil)
	ws := env.ConnectWS(t, ch.APIKey)
	defer ws.Close()
	apptest.ReadWS(t, ws)

	inst, _ := env.Mgr.GetInstance(botObj.ID)
	mock := inst.Provider.(*mockserver.Provider)

	mock.Engine().InjectInbound(mockserver.InboundRequest{
		Sender: "u@wx",
		Text:   "no hook",
	})

	// WS should still receive
	if apptest.ReadWSTimeout(t, ws, 2*time.Second) == nil {
		t.Error("WS should still receive without webhook")
	}
}

// ==================== AI context isolation ====================
