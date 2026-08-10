package appapi

import "github.com/ceoadmin/CEOadmin/internal/api/shared"

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/ceoadmin/CEOadmin/internal/store"
	"github.com/mark3labs/mcp-go/client"
	"github.com/mark3labs/mcp-go/client/transport"
	"github.com/mark3labs/mcp-go/mcp"
)

// perAttemptTimeout caps the SSE fallback attempt so a stuck retry cannot
// exceed the handler's parent-context deadline. The initial Streamable HTTP
// attempt is not capped and uses the full parent budget.
const perAttemptTimeout = 10 * time.Second

// minFallbackBudget is the minimum parent-context time remaining required to
// retry discovery over SSE after a Streamable HTTP timeout.
const minFallbackBudget = 3 * time.Second

// httpStatusRE matches a 4xx status we care about inside an error message.
// The leading `[^\d/:a-zA-Z]` (or start-of-string) rejects digits that are
// really part of a URL path segment (e.g. "/mcp/405") or TCP port
// (e.g. ":405"), which would otherwise false-match with plain `\b`.
var httpStatusRE = regexp.MustCompile(`(?:^|[^\d/:a-zA-Z])(40[13456])\b`)

// findHTTPStatus extracts a matched 4xx status code from err's message, or
// empty string if none is present.
func findHTTPStatus(msg string) string {
	m := httpStatusRE.FindStringSubmatch(msg)
	if len(m) < 2 {
		return ""
	}
	return m[1]
}

const maxImportTools = 200

var blockedHeaderKeys = map[string]bool{
	"host":              true,
	"content-type":      true,
	"content-length":    true,
	"transfer-encoding": true,
	"connection":        true,
}

type mcpImportRequest struct {
	URL     string            `json:"url"`
	Headers map[string]string `json:"headers,omitempty"`
}

type mcpImportResult struct {
	ServerName    string          `json:"server_name,omitempty"`
	ServerVersion string          `json:"server_version,omitempty"`
	Tools         []store.AppTool `json:"tools"`
	Truncated     bool            `json:"truncated,omitempty"`
}

// importError carries stage + user-facing detail so the frontend can show a
// meaningful message instead of a generic "bad gateway".
type importError struct {
	Stage  string // connect | initialize | list_tools | blocked | timeout | panic
	Detail string
	Err    error
}

func (e *importError) Error() string {
	if e.Err != nil {
		return fmt.Sprintf("%s: %s: %v", e.Stage, e.Detail, e.Err)
	}
	return fmt.Sprintf("%s: %s", e.Stage, e.Detail)
}

func (e *importError) Unwrap() error { return e.Err }

// handleImportMCP discovers tools from a remote MCP server.
func (s *AppHandler) HandleImportMCP(w http.ResponseWriter, r *http.Request) {
	start := time.Now()

	body := http.MaxBytesReader(w, r.Body, 8*1024)
	var req mcpImportRequest
	if err := json.NewDecoder(body).Decode(&req); err != nil {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}
	if req.URL == "" {
		shared.JSONError(w, "url is required", http.StatusBadRequest)
		return
	}

	u, err := url.ParseRequestURI(req.URL)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") {
		shared.JSONError(w, "url must be a valid http or https URL", http.StatusBadRequest)
		return
	}

	headers := filterHeaders(req.Headers)

	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
	defer cancel()

	result, err := discoverMCPTools(ctx, s.Version, req.URL, headers)
	if err != nil {
		status, msg, stage := classifyImportError(ctx, err)
		slog.Warn("mcp import failed", "url", req.URL, "stage", stage, "status", status, "err", err, "duration_ms", time.Since(start).Milliseconds())
		shared.JSONError(w, msg, status)
		return
	}

	slog.Info("mcp import", "url", req.URL, "server", result.ServerName, "tools", len(result.Tools), "truncated", result.Truncated, "duration_ms", time.Since(start).Milliseconds())

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(result)
}

// classifyImportError maps a discovery failure to an HTTP status and a
// user-facing message. Returns (status, message, stage-for-logs).
func classifyImportError(ctx context.Context, err error) (int, string, string) {
	var ie *importError
	if errors.As(err, &ie) {
		switch ie.Stage {
		case "blocked":
			return http.StatusBadRequest, ie.Detail, ie.Stage
		case "timeout":
			return http.StatusGatewayTimeout, ie.Detail, ie.Stage
		case "panic":
			return http.StatusInternalServerError, "MCP client crashed while contacting the server", ie.Stage
		default:
			return http.StatusBadGateway, fmt.Sprintf("%s failed: %s", ie.Stage, ie.Detail), ie.Stage
		}
	}

	if ctx.Err() == context.DeadlineExceeded || errors.Is(err, context.DeadlineExceeded) {
		return http.StatusGatewayTimeout, "MCP server did not respond in time", "timeout"
	}
	return http.StatusBadGateway, truncate(err.Error(), 200), "unknown"
}

// truncate trims s to at most n bytes without splitting a multibyte rune and
// appends an ellipsis. Used on error strings that surface in JSON responses.
func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	for n > 0 && !utf8.RuneStart(s[n]) {
		n--
	}
	if n == 0 {
		return "..."
	}
	return s[:n] + "..."
}

func filterHeaders(headers map[string]string) map[string]string {
	if len(headers) == 0 {
		return nil
	}
	filtered := make(map[string]string, len(headers))
	for k, v := range headers {
		lower := stringToLower(k)
		if !blockedHeaderKeys[lower] {
			filtered[k] = v
		}
	}
	if len(filtered) == 0 {
		return nil
	}
	return filtered
}

func stringToLower(s string) string {
	b := make([]byte, len(s))
	for i := range len(s) {
		c := s[i]
		if c >= 'A' && c <= 'Z' {
			c += 'a' - 'A'
		}
		b[i] = c
	}
	return string(b)
}

func discoverMCPTools(ctx context.Context, hubVersion, serverURL string, headers map[string]string) (result *mcpImportResult, err error) {
	defer func() {
		if r := recover(); r != nil {
			err = &importError{Stage: "panic", Detail: fmt.Sprintf("%v", r)}
		}
	}()

	httpClient := &http.Client{
		Transport: &http.Transport{
			DialContext:           shared.SSRFSafeDialContext,
			ResponseHeaderTimeout: 10 * time.Second,
		},
		Timeout: 15 * time.Second,
	}

	// Try Streamable HTTP first (new spec) with the full parent-context budget
	// so slow-but-healthy Streamable servers are not regressed. If the server
	// signals it is a legacy SSE server — or the first attempt timed out while
	// the parent context still has usable budget — fall back to SSE with a
	// bounded retry so a stuck retry cannot exceed the handler deadline.
	result, err = runDiscovery(ctx, hubVersion, serverURL, headers, httpClient, transportStreamable)
	if err != nil && shouldFallbackToSSE(ctx, err) {
		slog.Info("mcp import: retrying with SSE transport", "url", serverURL)
		retryCtx, retryCancel := context.WithTimeout(ctx, perAttemptTimeout)
		defer retryCancel()
		result, err = runDiscovery(retryCtx, hubVersion, serverURL, headers, httpClient, transportSSE)
	}
	return result, err
}

// shouldFallbackToSSE decides whether a failed Streamable HTTP attempt should
// be retried over SSE. Retries on explicit legacy signals or on a first-attempt
// timeout when the parent context still has at least minFallbackBudget left —
// a single slow response should not foreclose the fallback for a server that
// happens to be slow but legacy.
func shouldFallbackToSSE(parent context.Context, err error) bool {
	if isLegacySSESignal(err) {
		return true
	}
	var ie *importError
	if errors.As(err, &ie) && ie.Stage == "timeout" {
		if dl, ok := parent.Deadline(); ok {
			return time.Until(dl) >= minFallbackBudget
		}
		return true
	}
	return false
}

type transportKind int

const (
	transportStreamable transportKind = iota
	transportSSE
)

func runDiscovery(ctx context.Context, hubVersion, serverURL string, headers map[string]string, httpClient *http.Client, kind transportKind) (*mcpImportResult, error) {
	var c *client.Client
	var cerr error

	switch kind {
	case transportSSE:
		opts := []transport.ClientOption{transport.WithHTTPClient(httpClient)}
		if len(headers) > 0 {
			opts = append(opts, transport.WithHeaders(headers))
		}
		c, cerr = client.NewSSEMCPClient(serverURL, opts...)
	default:
		opts := []transport.StreamableHTTPCOption{transport.WithHTTPBasicClient(httpClient)}
		if len(headers) > 0 {
			opts = append(opts, transport.WithHTTPHeaders(headers))
		}
		c, cerr = client.NewStreamableHttpClient(serverURL, opts...)
	}
	if cerr != nil {
		return nil, &importError{Stage: "connect", Detail: "invalid MCP client configuration", Err: cerr}
	}
	defer c.Close()

	if serr := c.Start(ctx); serr != nil {
		return nil, wrapTransportError("connect", serr)
	}

	version := hubVersion
	if version == "" {
		version = "dev"
	}

	initResult, ierr := c.Initialize(ctx, mcp.InitializeRequest{
		Params: mcp.InitializeParams{
			ClientInfo: mcp.Implementation{
				Name:    "CEOadmin Hub",
				Version: version,
			},
			ProtocolVersion: mcp.LATEST_PROTOCOL_VERSION,
		},
	})
	if ierr != nil {
		return nil, wrapTransportError("initialize", ierr)
	}

	result := &mcpImportResult{Tools: []store.AppTool{}}
	if initResult != nil {
		result.ServerName = initResult.ServerInfo.Name
		result.ServerVersion = initResult.ServerInfo.Version
	}

	toolsResult, lerr := c.ListTools(ctx, mcp.ListToolsRequest{})
	if lerr != nil {
		return nil, wrapTransportError("list_tools", lerr)
	}
	if toolsResult == nil {
		return result, nil
	}

	for i, t := range toolsResult.Tools {
		if i >= maxImportTools {
			result.Truncated = true
			break
		}

		appTool := store.AppTool{
			Name:        t.Name,
			Description: t.Description,
		}

		params, err := json.Marshal(t.InputSchema)
		if err == nil && len(params) > 0 && string(params) != `{"type":""}` && string(params) != `{"type":"object"}` {
			appTool.Parameters = params
		}

		result.Tools = append(result.Tools, appTool)
	}

	return result, nil
}

// isLegacySSESignal reports whether the error indicates the server speaks the
// legacy SSE transport instead of Streamable HTTP, so discovery should retry.
// Triggers on mcp-go's explicit "legacy sse" hint, an HTTP 405/406 from the
// Streamable HTTP initialize POST (per MCP spec), or the equivalent textual
// phrases when no numeric status is surfaced in the wrapped error.
func isLegacySSESignal(err error) bool {
	if err == nil {
		return false
	}
	low := strings.ToLower(err.Error())
	if strings.Contains(low, "legacy sse") ||
		strings.Contains(low, "method not allowed") ||
		strings.Contains(low, "not acceptable") {
		return true
	}
	if m := findHTTPStatus(low); m == "405" || m == "406" {
		return true
	}
	return false
}

// isTimeoutErr classifies an error as a transport-level timeout, covering
// context deadlines, net.Error(Timeout), and Go's ResponseHeaderTimeout
// message which does not wrap context.DeadlineExceeded.
func isTimeoutErr(err error, lowerMsg string) bool {
	if err == nil {
		return false
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return true
	}
	var ne net.Error
	if errors.As(err, &ne) && ne.Timeout() {
		return true
	}
	return strings.Contains(lowerMsg, "deadline exceeded") ||
		strings.Contains(lowerMsg, "timeout awaiting response headers") ||
		strings.Contains(lowerMsg, "i/o timeout")
}

// wrapTransportError classifies a network/protocol error into an importError
// with a concise, user-safe message.
func wrapTransportError(stage string, err error) error {
	msg := err.Error()
	low := strings.ToLower(msg)

	switch {
	case isTimeoutErr(err, low):
		return &importError{Stage: "timeout", Detail: "MCP server did not respond in time", Err: err}
	case strings.Contains(low, "private/internal"):
		return &importError{Stage: "blocked", Detail: "MCP server resolves to a private/internal IP and was blocked", Err: err}
	case strings.Contains(low, "cannot resolve host"):
		return &importError{Stage: stage, Detail: "cannot resolve MCP server hostname", Err: err}
	case strings.Contains(low, "connection refused"):
		return &importError{Stage: stage, Detail: "connection refused by MCP server", Err: err}
	case strings.Contains(low, "tls") || strings.Contains(low, "x509"):
		return &importError{Stage: stage, Detail: "TLS handshake failed", Err: err}
	case strings.Contains(low, "legacy sse"):
		return &importError{Stage: stage, Detail: "MCP server uses the legacy SSE transport and the fallback also failed", Err: err}
	case strings.Contains(low, "method not allowed"):
		return &importError{Stage: stage, Detail: "MCP server does not accept Streamable HTTP transport", Err: err}
	}

	switch findHTTPStatus(low) {
	case "401":
		return &importError{Stage: stage, Detail: "MCP server rejected credentials (401)", Err: err}
	case "403":
		return &importError{Stage: stage, Detail: "MCP server forbade access (403)", Err: err}
	case "404":
		return &importError{Stage: stage, Detail: "MCP endpoint not found (404) — check the URL path", Err: err}
	case "405", "406":
		return &importError{Stage: stage, Detail: "MCP server does not accept Streamable HTTP transport", Err: err}
	}

	if strings.Contains(low, "unauthorized") {
		return &importError{Stage: stage, Detail: "MCP server rejected credentials (401)", Err: err}
	}
	if strings.Contains(low, "forbidden") {
		return &importError{Stage: stage, Detail: "MCP server forbade access (403)", Err: err}
	}
	return &importError{Stage: stage, Detail: truncate(msg, 200), Err: err}
}
