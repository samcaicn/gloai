package api

import (
	ws "github.com/ceoadmin/CEOadmin/internal/api/ws"
	"github.com/ceoadmin/CEOadmin/internal/app"
	"github.com/ceoadmin/CEOadmin/internal/relay"
)

// SetupUpstreamHandler builds the relay upstream handler. It delegates to the
// ws handler implementation so the message-routing logic stays in one place.
func (s *Server) SetupUpstreamHandler() relay.UpstreamHandler {
	wsH := ws.NewWSHandler(s.BotManager, s.Config, s.Hub, s.PushHub, s.Store)
	return wsH.SetupUpstreamHandler()
}

// NewAppWSHub constructs the shared app-level WebSocket hub.
func NewAppWSHub() *app.WSHub {
	return app.NewWSHub()
}
