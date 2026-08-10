package api

import (
	"github.com/ceoadmin/CEOadmin/internal/api/shared"

	"bufio"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"runtime/debug"
	"time"
)

// recovery wraps a handler to recover from panics, log the stack trace,
// and return a 500 JSON error instead of crashing the process.
func recovery(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if err := recover(); err != nil {
				stack := string(debug.Stack())
				slog.Error("panic recovered",
					"method", r.Method,
					"path", r.URL.Path,
					"error", fmt.Sprint(err),
					"stack", stack,
				)
				// Only write if headers haven't been sent
				if w.Header().Get("Content-Type") == "" {
					shared.JSONError(w, fmt.Sprintf("internal error: %v", err), http.StatusInternalServerError)
				}
			}
		}()
		next.ServeHTTP(w, r)
	})
}

// responseWriter wraps http.ResponseWriter to capture status code.
type responseWriter struct {
	http.ResponseWriter
	status int
	wrote  bool
}

func (rw *responseWriter) WriteHeader(code int) {
	if !rw.wrote {
		rw.status = code
		rw.wrote = true
	}
	rw.ResponseWriter.WriteHeader(code)
}

func (rw *responseWriter) Write(b []byte) (int, error) {
	if !rw.wrote {
		rw.status = 200
		rw.wrote = true
	}
	return rw.ResponseWriter.Write(b)
}

// Unwrap returns the underlying ResponseWriter for http.Flusher etc.
func (rw *responseWriter) Unwrap() http.ResponseWriter {
	return rw.ResponseWriter
}

// Hijack implements http.Hijacker for WebSocket support.
func (rw *responseWriter) Hijack() (net.Conn, *bufio.ReadWriter, error) {
	if hj, ok := rw.ResponseWriter.(http.Hijacker); ok {
		return hj.Hijack()
	}
	return nil, nil, fmt.Errorf("underlying ResponseWriter does not support Hijack")
}

// Flush implements http.Flusher for SSE support.
func (rw *responseWriter) Flush() {
	if f, ok := rw.ResponseWriter.(http.Flusher); ok {
		f.Flush()
	}
}

// requestLogger logs every API request with method, path, status, and duration.
// Skips noisy paths like media proxy and websocket pings.
func requestLogger(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Skip noisy endpoints
		if r.URL.Path == "/api/info" {
			next.ServeHTTP(w, r)
			return
		}

		start := time.Now()
		rw := &responseWriter{ResponseWriter: w, status: 200}
		next.ServeHTTP(rw, r)
		duration := time.Since(start)

		level := slog.LevelInfo
		if rw.status >= 500 {
			level = slog.LevelError
		} else if rw.status >= 400 {
			level = slog.LevelWarn
		}

		slog.Log(r.Context(), level, "api",
			"method", r.Method,
			"path", r.URL.Path,
			"status", rw.status,
			"duration", duration.Round(time.Millisecond),
		)
	})
}
