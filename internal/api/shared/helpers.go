package shared

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"github.com/ceoadmin/CEOadmin/internal/bot"
	"github.com/ceoadmin/CEOadmin/internal/provider"
	"github.com/ceoadmin/CEOadmin/internal/store"
	"github.com/gorilla/websocket"
)

// ContextTokenMaxAge is the maximum age of a cached context token that still
// counts as "fresh" for sending.
const ContextTokenMaxAge = 24 * time.Hour

// Upgrader upgrades plain HTTP connections to WebSocket connections. Exported
// here so the auth, bot and ws sub-packages share one definition.
var Upgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool { return true },
}

// KnownOAuthProviders is the set of statically recognised OAuth provider slugs.
var KnownOAuthProviders = map[string]bool{
	"github":  true,
	"linuxdo": true,
}

// SlugRe validates app / skill slugs.
var SlugRe = regexp.MustCompile(`^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$`)

// MaskSecret masks all but the first/last 4 chars of a secret for display.
func MaskSecret(s string) string {
	if len(s) <= 8 {
		return strings.Repeat("*", len(s))
	}
	return s[:4] + strings.Repeat("*", len(s)-8) + s[len(s)-4:]
}

// RegistrationEnabled reports whether public registration is allowed.
func RegistrationEnabled(st store.Store) bool {
	val, err := st.GetConfig("registration.enabled")
	if err != nil || val != "false" {
		return true
	}
	return false
}

// ScanLoginRole returns the role assigned to a new user via iLink scan login.
func ScanLoginRole(st store.Store) string {
	val, err := st.GetConfig("scan_login.role")
	if err != nil || val == "" || !store.IsValidRole(val) {
		return store.RoleMember
	}
	return val
}

// AuthenticateChannel extracts and validates the channel API key from a request.
func AuthenticateChannel(store store.Store, r *http.Request) (*store.Channel, error) {
	key := r.URL.Query().Get("key")
	if key == "" {
		key = r.Header.Get("X-API-Key")
	}
	if key == "" {
		return nil, nil
	}
	ch, err := store.GetChannelByAPIKey(key)
	if err != nil {
		return nil, err
	}
	if !ch.Enabled {
		return nil, nil
	}
	return ch, nil
}

// CheckSendStatus is a pure function that determines send capability from
// pre-fetched data.
func CheckSendStatus(status string, hasFreshToken bool) (bool, string) {
	if status == "session_expired" {
		return false, "会话已过期，请先在微信中发送一条消息以恢复连接，若仍无法恢复请重新扫码绑定"
	}
	if status != "connected" {
		return false, "Bot 未连接"
	}
	if !hasFreshToken {
		return false, "暂无法发送：需要先收到用户消息"
	}
	return true, ""
}

// CheckSendability queries the DB and returns send capability for a single bot.
func CheckSendability(store store.Store, botMgr *bot.Manager, botID, status string) (bool, string) {
	hasFresh := store.HasFreshContextToken(botID, ContextTokenMaxAge)
	return CheckSendStatus(status, hasFresh)
}

// DetectMediaType guesses a message media type from filename + mime.
func DetectMediaType(filename, mime string) string {
	lower := strings.ToLower(filename)
	switch {
	case strings.HasPrefix(mime, "image/"),
		strings.HasSuffix(lower, ".jpg"), strings.HasSuffix(lower, ".jpeg"),
		strings.HasSuffix(lower, ".png"), strings.HasSuffix(lower, ".gif"),
		strings.HasSuffix(lower, ".webp"):
		return "image"
	case strings.HasPrefix(mime, "video/"),
		strings.HasSuffix(lower, ".mp4"), strings.HasSuffix(lower, ".mov"),
		strings.HasSuffix(lower, ".avi"):
		return "video"
	case strings.HasPrefix(mime, "audio/"),
		strings.HasSuffix(lower, ".mp3"), strings.HasSuffix(lower, ".wav"),
		strings.HasSuffix(lower, ".ogg"):
		return "voice"
	default:
		return "file"
	}
}

// DetectContentType maps a message type to an HTTP content type.
func DetectContentType(msgType string) string {
	switch msgType {
	case "image":
		return "image/jpeg"
	case "video":
		return "video/mp4"
	case "voice":
		return "audio/wav"
	default:
		return "application/octet-stream"
	}
}

// DetectExt returns a file extension for a message, preferring the uploaded one.
func DetectExt(filename, msgType string) string {
	if ext := filepath.Ext(filename); ext != "" {
		return ext
	}
	switch msgType {
	case "image":
		return ".jpg"
	case "video":
		return ".mp4"
	case "voice":
		return ".wav"
	default:
		return ""
	}
}

// ParseSendRequest parses an outbound send request (multipart upload or JSON).
func ParseSendRequest(r *http.Request) (provider.OutboundMessage, string, error) {
	ct := r.Header.Get("Content-Type")
	if strings.HasPrefix(ct, "multipart/") {
		if err := r.ParseMultipartForm(32 << 20); err != nil {
			return provider.OutboundMessage{}, "", fmt.Errorf("parse multipart: %w", err)
		}
		file, header, err := r.FormFile("file")
		if err != nil {
			return provider.OutboundMessage{}, "", fmt.Errorf("file required for multipart")
		}
		defer file.Close()
		data, _ := io.ReadAll(file)
		msgType := DetectMediaType(header.Filename, header.Header.Get("Content-Type"))
		return provider.OutboundMessage{
			Recipient: r.FormValue("recipient"),
			Text:      r.FormValue("text"),
			Data:      data,
			FileName:  header.Filename,
		}, msgType, nil
	}
	var req struct {
		Recipient string `json:"recipient"`
		Text      string `json:"text"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Text == "" {
		return provider.OutboundMessage{}, "", fmt.Errorf("text required")
	}
	return provider.OutboundMessage{
		Recipient: req.Recipient,
		Text:      req.Text,
	}, "text", nil
}

// IsPrivateIP reports whether ip is private/internal/loopback/link-local or the
// cloud metadata endpoint.
func IsPrivateIP(ip net.IP) bool {
	if ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast() ||
		ip.IsLinkLocalMulticast() || ip.IsUnspecified() {
		return true
	}
	if ip.Equal(net.ParseIP("169.254.169.254")) {
		return true
	}
	return false
}

// SSRFSafeDialContext returns a DialContext that rejects connections to private
// IPs. This protects against DNS rebinding and redirect-based SSRF because the
// check happens at actual connect time, not at URL parse time.
func SSRFSafeDialContext(ctx context.Context, network, addr string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return nil, fmt.Errorf("invalid address: %w", err)
	}
	ips, err := net.DefaultResolver.LookupIPAddr(ctx, host)
	if err != nil {
		return nil, fmt.Errorf("cannot resolve host: %w", err)
	}
	var safeAddrs []string
	for _, ipAddr := range ips {
		if IsPrivateIP(ipAddr.IP) {
			continue
		}
		safeAddrs = append(safeAddrs, net.JoinHostPort(ipAddr.IP.String(), port))
	}
	if len(safeAddrs) == 0 {
		return nil, fmt.Errorf("all resolved IPs for %s are private/internal", host)
	}
	var d net.Dialer
	return d.DialContext(ctx, network, safeAddrs[0])
}
