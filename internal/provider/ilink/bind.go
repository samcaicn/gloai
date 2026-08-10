package ilink

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	ilinkSDK "github.com/openilink/openilink-sdk-go"
	"github.com/ceoadmin/CEOadmin/internal/provider"
)

// PendingBinds tracks in-flight QR bind sessions.
var PendingBinds = struct {
	sync.Mutex
	M map[string]*bindEntry
}{M: make(map[string]*bindEntry)}

type bindEntry struct {
	client *ilinkSDK.Client
	qrCode string
	UserID string
	result *provider.BindPollResult // cached terminal result for reconnecting clients
}

// StartBind initiates a QR code bind flow.
func StartBind(ctx context.Context, userID string) (sessionID, qrURL string, err error) {
	client := ilinkSDK.NewClient("")
	qr, err := client.FetchQRCode(ctx)
	if err != nil {
		return "", "", fmt.Errorf("fetch qr: %w", err)
	}

	sessionID = fmt.Sprintf("bind-%s-%d", userID[:8], time.Now().UnixMilli())

	PendingBinds.Lock()
	PendingBinds.M[sessionID] = &bindEntry{
		client: client,
		qrCode: qr.QRCode,
		UserID: userID,
	}
	PendingBinds.Unlock()

	return sessionID, qr.QRCodeImgContent, nil
}

// PollBind checks the QR status. Returns a BindPollResult.
// On "confirmed", the result is cached so reconnecting clients can retrieve it.
func PollBind(ctx context.Context, sessionID string) (*provider.BindPollResult, error) {
	PendingBinds.Lock()
	entry, ok := PendingBinds.M[sessionID]
	// Return cached terminal result for reconnecting clients (under lock).
	if ok && entry.result != nil {
		r := entry.result
		PendingBinds.Unlock()
		return r, nil
	}
	PendingBinds.Unlock()
	if !ok {
		return nil, fmt.Errorf("session not found")
	}

	status, err := entry.client.PollQRStatus(ctx, entry.qrCode)
	if err != nil {
		return nil, err
	}

	switch status.Status {
	case "wait":
		return &provider.BindPollResult{Status: "wait"}, nil
	case "scaned":
		return &provider.BindPollResult{Status: "scanned"}, nil
	case "expired":
		// Refresh QR
		newQR, err := entry.client.FetchQRCode(ctx)
		if err != nil {
			return nil, fmt.Errorf("refresh qr: %w", err)
		}
		entry.qrCode = newQR.QRCode
		return &provider.BindPollResult{Status: "expired", QRURL: newQR.QRCodeImgContent}, nil
	case "confirmed":
		if status.ILinkBotID == "" {
			return nil, fmt.Errorf("no bot ID in confirmed status")
		}
		entry.client.SetToken(status.BotToken)
		if status.BaseURL != "" {
			entry.client.SetBaseURL(status.BaseURL)
		}

		creds := Credentials{
			BotID:       status.ILinkBotID,
			BotToken:    status.BotToken,
			BaseURL:     status.BaseURL,
			ILinkUserID: status.ILinkUserID,
		}
		credsJSON, _ := json.Marshal(creds)

		result := &provider.BindPollResult{Status: "confirmed", Credentials: credsJSON}

		// Cache the result under lock — reconnecting clients need it.
		// Schedule cleanup after 60s to avoid leaking memory.
		PendingBinds.Lock()
		entry.result = result
		PendingBinds.Unlock()
		go func() {
			time.Sleep(60 * time.Second)
			PendingBinds.Lock()
			delete(PendingBinds.M, sessionID)
			PendingBinds.Unlock()
		}()

		return result, nil
	default:
		return &provider.BindPollResult{Status: status.Status}, nil
	}
}
