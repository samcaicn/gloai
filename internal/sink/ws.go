package sink

import "github.com/ceoadmin/CEOadmin/internal/relay"

// WS pushes messages to connected WebSocket clients via the relay hub.
type WS struct {
	Hub *relay.Hub
}

func (s *WS) Name() string { return "ws" }

func (s *WS) Handle(d Delivery) {
	s.Hub.SendTo(d.Channel.ID, d.Envelope)
}
