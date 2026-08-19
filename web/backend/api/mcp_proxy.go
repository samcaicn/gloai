package api

import (
	"net/http"
	"net/http/httputil"

	"github.com/colearn/colearn/pkg/mcp"
)

// registerMcpRoutes binds the MCP Streamable HTTP passthrough endpoint.
// This endpoint transparently proxies MCP protocol traffic (POST for JSON-RPC,
// GET for SSE event streams, DELETE for session termination) from the launcher
// to the gateway's MCP server, forwarding the Mcp-Session-Id header.
func (h *Handler) registerMcpRoutes(mux *http.ServeMux) {
	mux.HandleFunc("POST /api/v2/mcp", h.handleMcpProxy())
	mux.HandleFunc("GET /api/v2/mcp", h.handleMcpProxy())
	mux.HandleFunc("DELETE /api/v2/mcp", h.handleMcpProxy())
}

// handleMcpProxy returns an http.HandlerFunc that transparently proxies MCP
// Streamable HTTP traffic to the gateway's MCP endpoint at /api/v2/mcp.
//
// The proxy:
//   - Forwards Mcp-Session-Id automatically (ReverseProxy copies all headers)
//   - Injects the pico token as a Bearer token for upstream auth
//   - For GET (SSE): sets text/event-stream content type and flushes without buffering
//   - For POST (JSON-RPC) and DELETE (session end): standard HTTP proxying
func (h *Handler) handleMcpProxy() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !h.gatewayAvailableForProxy() {
			http.Error(w, "Gateway not available", http.StatusServiceUnavailable)
			return
		}

		gateway.mu.Lock()
		picoToken := gateway.picoToken
		gateway.mu.Unlock()
		if picoToken == "" {
			http.Error(w, "Pico channel not configured", http.StatusServiceUnavailable)
			return
		}

		proxy := &httputil.ReverseProxy{
			Rewrite: func(req *httputil.ProxyRequest) {
				target := h.gatewayProxyURL()
				// Set URL directly (not via SetURL) to avoid path joining:
				// SetURL joins target.Path with the incoming request path,
				// which would produce /api/v2/mcp/api/v2/mcp.
				req.Out.URL.Scheme = target.Scheme
				req.Out.URL.Host = target.Host
				req.Out.URL.Path = mcp.StreamableHTTPPath
				req.Out.URL.RawQuery = ""
				req.Out.Host = ""
				req.Out.Header.Set("Authorization", "Bearer "+picoToken)
			},
			ErrorHandler: func(rw http.ResponseWriter, req *http.Request, err error) {
				http.Error(rw, "Gateway unavailable: "+err.Error(), http.StatusBadGateway)
			},
		}

		// For GET requests (SSE streams), configure the response for streaming
		// by disabling buffering and setting appropriate headers.
		if r.Method == http.MethodGet {
			if f, ok := w.(http.Flusher); ok {
				w.Header().Set("Content-Type", "text/event-stream")
				w.Header().Set("Cache-Control", "no-cache")
				w.Header().Set("Connection", "keep-alive")
				w.Header().Set("X-Accel-Buffering", "no")
				// Use a custom response writer that flushes after each Write.
				_ = f
				rw := &flushingResponseWriter{ResponseWriter: w}
				proxy.ServeHTTP(rw, r)
				return
			}
		}

		proxy.ServeHTTP(w, r)
	}
}

// flushingResponseWriter wraps an http.ResponseWriter to flush after every
// Write call, ensuring SSE events are sent to the client immediately without
// buffering.
type flushingResponseWriter struct {
	http.ResponseWriter
}

func (f *flushingResponseWriter) Write(b []byte) (int, error) {
	n, err := f.ResponseWriter.Write(b)
	if flusher, ok := f.ResponseWriter.(http.Flusher); ok {
		flusher.Flush()
	}
	return n, err
}
