package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestRecoveryMiddleware(t *testing.T) {
	handler := recovery(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		panic("test panic")
	}))

	req := httptest.NewRequest("GET", "/api/test", nil)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if rec.Code != 500 {
		t.Errorf("status = %d, want 500", rec.Code)
	}

	var body map[string]string
	json.NewDecoder(rec.Body).Decode(&body)
	if body["error"] == "" {
		t.Error("expected error in body")
	}
	if body["error"] != "internal error: test panic" {
		t.Errorf("error = %q", body["error"])
	}
	t.Logf("recovery response: %v", body)
}

func TestRecoveryMiddlewareNoPanic(t *testing.T) {
	handler := recovery(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(200)
		w.Write([]byte(`{"ok":true}`))
	}))

	req := httptest.NewRequest("GET", "/api/test", nil)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if rec.Code != 200 {
		t.Errorf("status = %d, want 200", rec.Code)
	}
}

func TestRequestLoggerCapturesStatus(t *testing.T) {
	handler := requestLogger(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(404)
		w.Write([]byte(`{"error":"not found"}`))
	}))

	req := httptest.NewRequest("GET", "/api/test", nil)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if rec.Code != 404 {
		t.Errorf("status = %d, want 404", rec.Code)
	}
}

func TestResponseWriterUnwrap(t *testing.T) {
	rec := httptest.NewRecorder()
	rw := &responseWriter{ResponseWriter: rec, status: 200}

	// Unwrap should return underlying writer
	if rw.Unwrap() != rec {
		t.Error("Unwrap should return underlying ResponseWriter")
	}
}
