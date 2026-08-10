package relay

import (
	"encoding/json"
	"log/slog"
	"sync"
)

// UpstreamHandler is called when a channel client sends a message upstream.
type UpstreamHandler func(conn *Conn, env Envelope)

// Hub manages all active WebSocket connections.
type Hub struct {
	mu              sync.RWMutex
	conns           map[string]*Conn // channelID -> conn
	upstreamHandler UpstreamHandler
}

func NewHub(handler UpstreamHandler) *Hub {
	return &Hub{
		conns:           make(map[string]*Conn),
		upstreamHandler: handler,
	}
}

func (h *Hub) Register(c *Conn) {
	h.mu.Lock()
	defer h.mu.Unlock()

	if old, ok := h.conns[c.ChannelID]; ok {
		close(old.send)
	}
	h.conns[c.ChannelID] = c
	slog.Info("ws registered", "channel", c.ChannelID)
}

func (h *Hub) Unregister(c *Conn) {
	h.mu.Lock()
	defer h.mu.Unlock()

	if existing, ok := h.conns[c.ChannelID]; ok && existing == c {
		delete(h.conns, c.ChannelID)
		close(c.send)
		slog.Info("ws unregistered", "channel", c.ChannelID)
	}
}

// SendTo sends an envelope to a specific channel.
func (h *Hub) SendTo(channelID string, env Envelope) {
	h.mu.RLock()
	c, ok := h.conns[channelID]
	h.mu.RUnlock()
	if ok {
		c.Send(env)
	}
}

// Broadcast sends an envelope to all connected channels for a given bot.
func (h *Hub) Broadcast(botID string, env Envelope) {
	h.mu.RLock()
	defer h.mu.RUnlock()

	data, err := json.Marshal(env)
	if err != nil {
		return
	}
	for _, c := range h.conns {
		if c.BotID == botID {
			select {
			case c.send <- data:
			default:
			}
		}
	}
}

// HandleUpstream routes a message from a channel client.
func (h *Hub) HandleUpstream(c *Conn, env Envelope) {
	if h.upstreamHandler != nil {
		h.upstreamHandler(c, env)
	}
}

// ConnectedCount returns the number of active connections.
func (h *Hub) ConnectedCount() int {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.conns)
}
