package wsapi

import "github.com/ceoadmin/CEOadmin/internal/api/shared"

import (
	"log/slog"
	"net/http"

	"github.com/ceoadmin/CEOadmin/internal/auth"
	"github.com/ceoadmin/CEOadmin/internal/push"
)

func (s *WSHandler) HandlePushWebSocket(w http.ResponseWriter, r *http.Request) {
	userID := auth.UserIDFromContext(r.Context())
	if userID == "" {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	ws, err := shared.Upgrader.Upgrade(w, r, nil)
	if err != nil {
		slog.Error("push ws upgrade failed", "err", err)
		return
	}

	c := push.NewConn(userID, ws, s.PushHub)
	s.PushHub.Register(c)

	go c.WritePump()
	c.ReadPump() // blocks until disconnect
}
