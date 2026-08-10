package relay

import (
	"encoding/json"
	"sync"
	"testing"
	"time"
)

// fakeConn simulates a Conn with a readable send channel for testing.
func fakeConn(channelID, botID string, hub *Hub) *Conn {
	return &Conn{
		ChannelID: channelID,
		BotID:     botID,
		hub:       hub,
		send:      make(chan []byte, 64),
	}
}

func readEnvelope(c *Conn, timeout time.Duration) (*Envelope, bool) {
	select {
	case data := <-c.send:
		var env Envelope
		json.Unmarshal(data, &env)
		return &env, true
	case <-time.After(timeout):
		return nil, false
	}
}

func TestHubRegisterUnregister(t *testing.T) {
	hub := NewHub(nil)

	c := fakeConn("ch-1", "bot-1", hub)
	hub.Register(c)

	if hub.ConnectedCount() != 1 {
		t.Fatalf("count = %d, want 1", hub.ConnectedCount())
	}

	hub.Unregister(c)
	if hub.ConnectedCount() != 0 {
		t.Fatalf("count = %d after unregister, want 0", hub.ConnectedCount())
	}
}

func TestHubRegisterReplacesExisting(t *testing.T) {
	hub := NewHub(nil)

	c1 := fakeConn("ch-1", "bot-1", hub)
	hub.Register(c1)

	c2 := fakeConn("ch-1", "bot-1", hub)
	hub.Register(c2)

	if hub.ConnectedCount() != 1 {
		t.Fatalf("count = %d, want 1 (replaced)", hub.ConnectedCount())
	}

	// c1's send channel should be closed
	_, ok := <-c1.send
	if ok {
		t.Error("old conn send channel should be closed")
	}
}

func TestHubSendTo(t *testing.T) {
	hub := NewHub(nil)

	c := fakeConn("ch-1", "bot-1", hub)
	hub.Register(c)

	env := NewEnvelope("test", map[string]string{"key": "value"})
	hub.SendTo("ch-1", env)

	received, ok := readEnvelope(c, 100*time.Millisecond)
	if !ok {
		t.Fatal("no message received")
	}
	if received.Type != "test" {
		t.Errorf("type = %q, want test", received.Type)
	}

	// SendTo non-existent channel should not panic
	hub.SendTo("ch-999", env)
}

func TestHubBroadcast(t *testing.T) {
	hub := NewHub(nil)

	c1 := fakeConn("ch-1", "bot-A", hub)
	c2 := fakeConn("ch-2", "bot-A", hub)
	c3 := fakeConn("ch-3", "bot-B", hub)
	hub.Register(c1)
	hub.Register(c2)
	hub.Register(c3)

	env := NewEnvelope("message", MessageData{Sender: "user1"})
	hub.Broadcast("bot-A", env)

	if _, ok := readEnvelope(c1, 100*time.Millisecond); !ok {
		t.Error("c1 should receive broadcast")
	}
	if _, ok := readEnvelope(c2, 100*time.Millisecond); !ok {
		t.Error("c2 should receive broadcast")
	}
	if _, ok := readEnvelope(c3, 50*time.Millisecond); ok {
		t.Error("c3 (different bot) should NOT receive broadcast")
	}
}

func TestHubUpstreamHandler(t *testing.T) {
	var received []Envelope
	var mu sync.Mutex

	hub := NewHub(func(conn *Conn, env Envelope) {
		mu.Lock()
		received = append(received, env)
		mu.Unlock()
	})

	c := fakeConn("ch-1", "bot-1", hub)
	hub.Register(c)

	env := Envelope{Type: "send_text", ReqID: "req-1"}
	hub.HandleUpstream(c, env)

	mu.Lock()
	defer mu.Unlock()
	if len(received) != 1 {
		t.Fatalf("received %d messages, want 1", len(received))
	}
	if received[0].Type != "send_text" || received[0].ReqID != "req-1" {
		t.Errorf("received = %+v", received[0])
	}
}

func TestHubConcurrentAccess(t *testing.T) {
	hub := NewHub(nil)
	var wg sync.WaitGroup

	for i := 0; i < 50; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			c := fakeConn("ch-concurrent", "bot-1", hub)
			hub.Register(c)
			hub.Broadcast("bot-1", Envelope{Type: "ping"})
			hub.Unregister(c)
		}(i)
	}
	wg.Wait()

	if hub.ConnectedCount() != 0 {
		t.Errorf("count = %d after concurrent ops, want 0", hub.ConnectedCount())
	}
}
