package messageapi

import "github.com/ceoadmin/CEOadmin/internal/api/shared"

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"time"

	"github.com/ceoadmin/CEOadmin/internal/store"
)

func encodeCursor(id int64) string {
	return base64.RawURLEncoding.EncodeToString([]byte(fmt.Sprintf("v1:%d", id)))
}

func decodeCursor(cursor string) (int64, error) {
	data, err := base64.RawURLEncoding.DecodeString(cursor)
	if err != nil {
		return 0, err
	}
	var id int64
	_, err = fmt.Sscanf(string(data), "v1:%d", &id)
	return id, err
}

// authenticateChannel extracts and validates the channel API key from the request.

// GET /api/v1/channels/messages?key=xxx&cursor=xxx&limit=50
func (s *MessageHandler) HandleChannelMessages(w http.ResponseWriter, r *http.Request) {
	ch, err := shared.AuthenticateChannel(s.Store, r)
	if ch == nil {
		if err != nil {
			shared.JSONError(w, "invalid key", http.StatusUnauthorized)
		} else {
			shared.JSONError(w, "api key required", http.StatusUnauthorized)
		}
		return
	}

	afterSeq := int64(0)
	if cursor := r.URL.Query().Get("cursor"); cursor != "" {
		if id, err := decodeCursor(cursor); err == nil {
			afterSeq = id
		} else {
			shared.JSONError(w, "invalid cursor", http.StatusBadRequest)
			return
		}
	}
	limit := 50
	if v := r.URL.Query().Get("limit"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 && n <= 200 {
			limit = n
		}
	}

	msgs, err := s.Store.GetMessagesSince(ch.BotID, afterSeq, limit)
	if err != nil {
		shared.JSONError(w, "query failed", http.StatusInternalServerError)
		return
	}

	// Update last_seq
	if len(msgs) > 0 {
		s.Store.UpdateChannelLastSeq(ch.ID, msgs[len(msgs)-1].ID)
	}

	// Build response with next_cursor
	var nextCursor string
	if len(msgs) == limit {
		nextCursor = encodeCursor(msgs[len(msgs)-1].ID)
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"messages":    msgs,
		"next_cursor": nextCursor,
	})
}

// POST /api/v1/channels/send?key=xxx
// Supports JSON (text) or multipart/form-data (media).
func (s *MessageHandler) HandleChannelSend(w http.ResponseWriter, r *http.Request) {
	ch, err := shared.AuthenticateChannel(s.Store, r)
	if ch == nil {
		if err != nil {
			shared.JSONError(w, "invalid key", http.StatusUnauthorized)
		} else {
			shared.JSONError(w, "api key required", http.StatusUnauthorized)
		}
		return
	}

	inst, ok := s.BotManager.GetInstance(ch.BotID)
	if !ok {
		bot, _ := s.Store.GetBot(ch.BotID)
		if bot != nil && bot.Status == "session_expired" {
			shared.JSONError(w, "session expired", http.StatusConflict)
		} else {
			shared.JSONError(w, "bot not connected", http.StatusServiceUnavailable)
		}
		return
	}

	// Check if the bot can send (context_token freshness)
	if canSend, reason := shared.CheckSendability(s.Store, s.BotManager, ch.BotID, inst.Status()); !canSend {
		shared.JSONError(w, reason, http.StatusConflict)
		return
	}

	msg, msgType, err := shared.ParseSendRequest(r)
	if err != nil {
		shared.JSONError(w, err.Error(), http.StatusBadRequest)
		return
	}

	// Auto-fill context_token from latest message if not provided
	if msg.ContextToken == "" {
		msg.ContextToken = s.Store.GetLatestContextToken(ch.BotID)
	}

	clientID, err := inst.Send(context.Background(), msg)
	if err != nil {
		shared.JSONError(w, err.Error(), http.StatusBadGateway)
		return
	}

	content := msg.Text
	if content == "" && msg.FileName != "" {
		content = msg.FileName
	}
	itemList, _ := json.Marshal([]map[string]any{{"type": msgType, "text": content}})
	mediaStatus := ""
	mediaKeys := json.RawMessage(`{}`)
	if len(msg.Data) > 0 && s.ObjectStore != nil {
		ct := shared.DetectContentType(msgType)
		ext := shared.DetectExt(msg.FileName, msgType)
		key := fmt.Sprintf("%s/%s/out_%d%s", ch.BotID,
			time.Now().Format("2006/01/02"), time.Now().UnixMilli(), ext)
		if _, err := s.ObjectStore.Put(r.Context(), key, ct, msg.Data); err == nil {
			mediaStatus = "ready"
			mediaKeys, _ = json.Marshal(map[string]string{"0": key})
		}
	}

	s.Store.SaveMessage(&store.Message{
		BotID:       ch.BotID,
		Direction:   "outbound",
		ToUserID:    msg.Recipient,
		MessageType: 2,
		ItemList:    itemList,
		MediaStatus: mediaStatus,
		MediaKeys:   mediaKeys,
	})

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"ok":        true,
		"client_id": clientID,
	})
}

// POST /api/v1/channels/typing?key=xxx
func (s *MessageHandler) HandleChannelTyping(w http.ResponseWriter, r *http.Request) {
	ch, err := shared.AuthenticateChannel(s.Store, r)
	if ch == nil {
		if err != nil {
			shared.JSONError(w, "invalid key", http.StatusUnauthorized)
		} else {
			shared.JSONError(w, "api key required", http.StatusUnauthorized)
		}
		return
	}

	var req struct {
		Ticket string `json:"ticket"`
		Status string `json:"status"` // "typing" or "cancel"
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}

	inst, ok := s.BotManager.GetInstance(ch.BotID)
	if !ok {
		shared.JSONError(w, "bot not connected", http.StatusServiceUnavailable)
		return
	}

	typing := req.Status != "cancel"
	if err := inst.Provider.SendTyping(context.Background(), "", req.Ticket, typing); err != nil {
		shared.JSONError(w, err.Error(), http.StatusBadGateway)
		return
	}
	shared.JSONOK(w)
}

// POST /api/v1/channels/config?key=xxx
func (s *MessageHandler) HandleChannelConfig(w http.ResponseWriter, r *http.Request) {
	ch, err := shared.AuthenticateChannel(s.Store, r)
	if ch == nil {
		if err != nil {
			shared.JSONError(w, "invalid key", http.StatusUnauthorized)
		} else {
			shared.JSONError(w, "api key required", http.StatusUnauthorized)
		}
		return
	}

	var req struct {
		ContextToken string `json:"context_token"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}

	inst, ok := s.BotManager.GetInstance(ch.BotID)
	if !ok {
		shared.JSONError(w, "bot not connected", http.StatusServiceUnavailable)
		return
	}

	cfg, err := inst.Provider.GetConfig(context.Background(), "", req.ContextToken)
	if err != nil {
		shared.JSONError(w, err.Error(), http.StatusBadGateway)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(cfg)
}

// GET /api/v1/channels/status?key=xxx
func (s *MessageHandler) HandleChannelStatus(w http.ResponseWriter, r *http.Request) {
	ch, err := shared.AuthenticateChannel(s.Store, r)
	if ch == nil {
		if err != nil {
			shared.JSONError(w, "invalid key", http.StatusUnauthorized)
		} else {
			shared.JSONError(w, "api key required", http.StatusUnauthorized)
		}
		return
	}

	botStatus := "disconnected"
	if inst, ok := s.BotManager.GetInstance(ch.BotID); ok {
		botStatus = inst.Status()
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"channel_id":   ch.ID,
		"channel_name": ch.Name,
		"bot_id":       ch.BotID,
		"bot_status":   botStatus,
	})
}
