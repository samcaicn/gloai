package botapi

import "github.com/ceoadmin/CEOadmin/internal/api/shared"

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"time"

	"github.com/ceoadmin/CEOadmin/internal/auth"
	"github.com/ceoadmin/CEOadmin/internal/builtin"
	ilinkProvider "github.com/ceoadmin/CEOadmin/internal/provider/ilink"
	"github.com/ceoadmin/CEOadmin/internal/store"
)

func (s *BotHandler) HandleListBots(w http.ResponseWriter, r *http.Request) {
	userID := auth.UserIDFromContext(r.Context())
	bots, err := s.Store.ListBotsByUser(userID)
	if err != nil {
		shared.JSONError(w, "list failed", http.StatusInternalServerError)
		return
	}

	type botResp struct {
		ID                 string          `json:"id"`
		Name               string          `json:"name"`
		DisplayName        string          `json:"display_name"`
		Provider           string          `json:"provider"`
		Status             string          `json:"status"`
		CanSend            bool            `json:"can_send"`
		SendDisabledReason string          `json:"send_disabled_reason,omitempty"`
		AIEnabled          bool            `json:"ai_enabled"`
		AIModel            string          `json:"ai_model"`
		MsgCount           int64           `json:"msg_count"`
		ReminderHours      int             `json:"reminder_hours"`
		LastMsgAt          *int64          `json:"last_msg_at,omitempty"`
		LastRemindedAt     *int64          `json:"last_reminded_at,omitempty"`
		CreatedAt          int64           `json:"created_at"`
		Extra              json.RawMessage `json:"extra,omitempty"`
	}
	// Batch check context_token freshness to avoid N+1 queries
	botIDs := make([]string, len(bots))
	for i, b := range bots {
		botIDs[i] = b.ID
	}
	freshTokens := s.Store.BatchHasFreshContextToken(botIDs, shared.ContextTokenMaxAge)

	var result []botResp
	for _, b := range bots {
		status := b.Status
		if inst, ok := s.BotManager.GetInstance(b.ID); ok {
			status = inst.Status()
		}
		canSend, reason := shared.CheckSendStatus(status, freshTokens[b.ID])
		extra := extractPublicCredentials(b.Provider, b.Credentials)
		result = append(result, botResp{
			ID: b.ID, Name: b.Name, DisplayName: b.DisplayName, Provider: b.Provider,
			Status: status, CanSend: canSend, SendDisabledReason: reason,
			AIEnabled: b.AIEnabled, AIModel: b.AIModel,
			MsgCount: b.MsgCount, ReminderHours: b.ReminderHours,
			LastMsgAt: b.LastMsgAt, LastRemindedAt: b.LastRemindedAt,
			CreatedAt: b.CreatedAt, Extra: extra,
		})
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(result)
}

// extractPublicCredentials returns non-secret info from credentials for the API response.
func extractPublicCredentials(prov string, creds json.RawMessage) json.RawMessage {
	if prov == "ilink" {
		var c struct {
			BotID       string `json:"bot_id"`
			ILinkUserID string `json:"ilink_user_id"`
		}
		json.Unmarshal(creds, &c)
		data, _ := json.Marshal(map[string]string{
			"bot_id":        c.BotID,
			"ilink_user_id": c.ILinkUserID,
		})
		return data
	}
	return nil
}

// checkSendability queries the DB and returns send capability for a single bot.

func (s *BotHandler) HandleBindStart(w http.ResponseWriter, r *http.Request) {
	userID := auth.UserIDFromContext(r.Context())

	sessionID, qrURL, err := ilinkProvider.StartBind(r.Context(), userID)
	if err != nil {
		shared.JSONError(w, err.Error(), http.StatusBadGateway)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{
		"session_id": sessionID,
		"qr_url":     qrURL,
	})
}

func (s *BotHandler) HandleBindStatus(w http.ResponseWriter, r *http.Request) {
	sessionID := r.PathValue("sessionID")

	ilinkProvider.PendingBinds.Lock()
	entry, ok := ilinkProvider.PendingBinds.M[sessionID]
	ilinkProvider.PendingBinds.Unlock()
	if !ok {
		shared.JSONError(w, "session not found", http.StatusNotFound)
		return
	}

	ws, err := shared.Upgrader.Upgrade(w, r, nil)
	if err != nil {
		slog.Error("ws upgrade failed", "err", err)
		return
	}
	defer ws.Close()

	// Read pump: detect client disconnect
	done := make(chan struct{})
	go func() {
		defer close(done)
		for {
			if _, _, err := ws.ReadMessage(); err != nil {
				return
			}
		}
	}()

	sendEvent := func(event, data string) {
		var parsed json.RawMessage
		if err := json.Unmarshal([]byte(data), &parsed); err != nil {
			parsed, _ = json.Marshal(data)
		}
		msg := map[string]any{"event": event}
		var fields map[string]any
		if json.Unmarshal(parsed, &fields) == nil {
			for k, v := range fields {
				msg[k] = v
			}
		}
		ws.WriteJSON(msg)
	}

	for {
		select {
		case <-done:
			return
		default:
		}

		result, err := ilinkProvider.PollBind(context.Background(), sessionID)
		if err != nil {
			sendEvent("error", `{"message":"poll failed"}`)
			return
		}

		switch result.Status {
		case "wait":
			sendEvent("status", `{"status":"wait"}`)
		case "scanned":
			sendEvent("status", `{"status":"scanned"}`)
		case "expired":
			j, _ := json.Marshal(map[string]string{"status": "refreshed", "qr_url": result.QRURL})
			sendEvent("status", string(j))
		case "confirmed":
			var creds struct {
				BotID       string `json:"bot_id"`
				ILinkUserID string `json:"ilink_user_id"`
			}
			json.Unmarshal(result.Credentials, &creds)

			var bot *store.Bot

			// 1. Match by provider_id (exact bot_id)
			if creds.BotID != "" {
				existing, _ := s.Store.FindBotByProviderID("ilink", creds.BotID)
				if existing != nil {
					if existing.UserID != entry.UserID {
						sendEvent("error", `{"message":"this account is already bound by another user"}`)
						return
					}
					s.BotManager.StopBot(existing.ID)
					if err := s.Store.UpdateBotCredentials(existing.ID, creds.BotID, result.Credentials); err != nil {
						slog.Error("rebind update failed", "err", err)
						sendEvent("error", `{"message":"rebind failed"}`)
						return
					}
					existing.Credentials = result.Credentials
					existing.Status = "connected"
					bot = existing
				}
			}

			// 2. Match by ilink_user_id (same WeChat user, new bot_id)
			if bot == nil && creds.ILinkUserID != "" {
				sibling, _ := s.Store.FindBotByCredential("ilink_user_id", creds.ILinkUserID)
				if sibling != nil && sibling.UserID == entry.UserID {
					s.BotManager.StopBot(sibling.ID)
					if err := s.Store.UpdateBotCredentials(sibling.ID, creds.BotID, result.Credentials); err != nil {
						slog.Error("rebind update failed", "err", err)
						sendEvent("error", `{"message":"rebind failed"}`)
						return
					}
					sibling.Credentials = result.Credentials
					sibling.ProviderID = creds.BotID
					sibling.Status = "connected"
					bot = sibling
				}
			}

			isNew := bot == nil
			if isNew {
				var err error
				bot, err = s.Store.CreateBot(entry.UserID, "", "ilink", creds.BotID, result.Credentials)
				if err != nil {
					slog.Error("save bot failed", "err", err)
					sendEvent("error", `{"message":"save failed"}`)
					return
				}
				// Auto-create default channel for new bots only
				s.Store.CreateChannel(bot.ID, "默认", "", nil, nil)
				// Auto-install builtin apps so every tenant gets them by default
				if err := builtin.EnsureInstalled(s.Store, bot.ID); err != nil {
					slog.Warn("auto-install builtin apps failed", "bot", bot.ID, "err", err)
				}
			}

			s.BotManager.StartBot(context.Background(), bot)

			j, _ := json.Marshal(map[string]any{"status": "connected", "bot_id": bot.ID, "is_new": isNew})
			sendEvent("status", string(j))
			return
		}
	}
}

func (s *BotHandler) HandleReconnect(w http.ResponseWriter, r *http.Request) {
	botID := r.PathValue("id")
	userID := auth.UserIDFromContext(r.Context())

	bot, err := s.Store.GetBot(botID)
	if err != nil || bot.UserID != userID {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return
	}

	if bot.Status == "session_expired" {
		shared.JSONError(w, "会话已过期，请先在微信中发送一条消息以恢复连接，若仍无法恢复请重新扫码绑定", http.StatusConflict)
		return
	}

	s.BotManager.StartBot(r.Context(), bot)
	shared.JSONOK(w)
}

func (s *BotHandler) HandleDeleteBot(w http.ResponseWriter, r *http.Request) {
	botID := r.PathValue("id")
	userID := auth.UserIDFromContext(r.Context())

	bot, err := s.Store.GetBot(botID)
	if err != nil || bot.UserID != userID {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return
	}

	s.BotManager.StopBot(botID)
	s.Store.DeleteBot(botID)
	shared.JSONOK(w)
}

func (s *BotHandler) HandleUpdateBot(w http.ResponseWriter, r *http.Request) {
	botID := r.PathValue("id")
	userID := auth.UserIDFromContext(r.Context())

	bot, err := s.Store.GetBot(botID)
	if err != nil || bot.UserID != userID {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return
	}

	var req struct {
		Name          *string `json:"name"`
		DisplayName   *string `json:"display_name"`
		ReminderHours *int    `json:"reminder_hours"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}

	if req.Name != nil && *req.Name != "" {
		if err := s.Store.UpdateBotName(botID, *req.Name); err != nil {
			shared.JSONError(w, "update failed", http.StatusInternalServerError)
			return
		}
	}
	if req.DisplayName != nil {
		if len(*req.DisplayName) > 64 {
			shared.JSONError(w, "display_name too long (max 64)", http.StatusBadRequest)
			return
		}
		if err := s.Store.UpdateBotDisplayName(botID, *req.DisplayName); err != nil {
			shared.JSONError(w, "update failed", http.StatusInternalServerError)
			return
		}
	}
	if req.ReminderHours != nil {
		hours := *req.ReminderHours
		if hours != 0 && hours != 22 && hours != 23 {
			shared.JSONError(w, "reminder_hours must be 0, 22 or 23", http.StatusBadRequest)
			return
		}
		if err := s.Store.UpdateBotReminder(botID, hours); err != nil {
			shared.JSONError(w, "update failed", http.StatusInternalServerError)
			return
		}
	}
	shared.JSONOK(w)
}

func (s *BotHandler) HandleStats(w http.ResponseWriter, r *http.Request) {
	userID := auth.UserIDFromContext(r.Context())
	stats, err := s.Store.GetBotStats(userID)
	if err != nil {
		shared.JSONError(w, "stats failed", http.StatusInternalServerError)
		return
	}
	stats.ConnectedWS = s.Hub.ConnectedCount()
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(stats)
}

func (s *BotHandler) HandleAdminStats(w http.ResponseWriter, r *http.Request) {
	stats, err := s.Store.GetAdminStats()
	if err != nil {
		shared.JSONError(w, "stats failed", http.StatusInternalServerError)
		return
	}
	stats.ConnectedWS = s.Hub.ConnectedCount()
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(stats)
}

// POST /api/bots/{id}/send
// Supports JSON body (text) or multipart/form-data (media).
// JSON: {"text": "hello", "recipient": "optional"}
// Multipart: file=@image.jpg, text=caption (optional), recipient=xxx (optional)
func (s *BotHandler) HandleBotSend(w http.ResponseWriter, r *http.Request) {
	botID := r.PathValue("id")
	userID := auth.UserIDFromContext(r.Context())

	bot, err := s.Store.GetBot(botID)
	if err != nil || bot.UserID != userID {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return
	}

	inst, ok := s.BotManager.GetInstance(botID)
	if !ok {
		if bot.Status == "session_expired" {
			shared.JSONError(w, "会话已过期，请先在微信中发送一条消息以恢复连接，若仍无法恢复请重新扫码绑定", http.StatusConflict)
		} else {
			shared.JSONError(w, "Bot 未连接", http.StatusServiceUnavailable)
		}
		return
	}

	canSend, reason := shared.CheckSendability(s.Store, s.BotManager, botID, inst.Status())
	if !canSend {
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
		msg.ContextToken = s.Store.GetLatestContextToken(botID)
	}

	clientID, err := inst.Send(r.Context(), msg)
	if err != nil {
		shared.JSONError(w, err.Error(), http.StatusBadGateway)
		return
	}

	// Save outbound message
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
		key := fmt.Sprintf("%s/%s/out_%d%s", botID,
			time.Now().Format("2006/01/02"), time.Now().UnixMilli(), ext)
		if _, err := s.ObjectStore.Put(r.Context(), key, ct, msg.Data); err == nil {
			mediaStatus = "ready"
			mediaKeys, _ = json.Marshal(map[string]string{"0": key})
		}
	}

	s.Store.SaveMessage(&store.Message{
		BotID:       botID,
		Direction:   "outbound",
		ToUserID:    msg.Recipient,
		MessageType: 2,
		ItemList:    itemList,
		MediaStatus: mediaStatus,
		MediaKeys:   mediaKeys,
	})

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{"ok": true, "client_id": clientID})
}

// PUT /api/bots/{id}/ai
func (s *BotHandler) HandleSetBotAI(w http.ResponseWriter, r *http.Request) {
	botID := r.PathValue("id")
	userID := auth.UserIDFromContext(r.Context())

	bot, err := s.Store.GetBot(botID)
	if err != nil || bot.UserID != userID {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return
	}

	var req struct {
		Enabled bool `json:"enabled"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}

	if err := s.Store.UpdateBotAIEnabled(botID, req.Enabled); err != nil {
		shared.JSONError(w, "update failed", http.StatusInternalServerError)
		return
	}
	// Sync to in-memory instance so it takes effect immediately
	if inst, ok := s.BotManager.GetInstance(botID); ok {
		inst.AIEnabled = req.Enabled
	}
	shared.JSONOK(w)
}

// PUT /api/bots/{id}/ai_model
func (s *BotHandler) HandleSetBotAIModel(w http.ResponseWriter, r *http.Request) {
	botID := r.PathValue("id")
	userID := auth.UserIDFromContext(r.Context())

	bot, err := s.Store.GetBot(botID)
	if err != nil || bot.UserID != userID {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return
	}

	var req struct {
		Model string `json:"model"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}

	if err := s.Store.UpdateBotAIModel(botID, req.Model); err != nil {
		shared.JSONError(w, "update failed", http.StatusInternalServerError)
		return
	}
	if s.BotManager != nil {
		s.BotManager.SetBotAIModel(botID, req.Model)
	}
	shared.JSONOK(w)
}

func (s *BotHandler) HandleBotContacts(w http.ResponseWriter, r *http.Request) {
	botID := r.PathValue("id")
	userID := auth.UserIDFromContext(r.Context())

	bot, err := s.Store.GetBot(botID)
	if err != nil || bot.UserID != userID {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return
	}

	contacts, err := s.Store.ListRecentContacts(botID, 100)
	if err != nil {
		shared.JSONError(w, "query failed", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(contacts)
}
