package api

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestLauncherAuthBindRejectsCrossSite(t *testing.T) {
	mux := http.NewServeMux()
	RegisterLauncherAuthRoutes(mux, LauncherAuthRouteOpts{
		SessionCookie: "session-cookie-value",
	})

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(
		http.MethodPost,
		"http://www.tuptup.top/api/auth/bind",
		strings.NewReader(`{"join_code":"12345678"}`),
	)
	req.Host = "www.tuptup.top"
	req.Header.Set("Origin", "https://www.tuptup.top")
	req.Header.Set("Referer", "https://www.tuptup.top")
	req.Header.Set("Sec-Fetch-Site", "cross-site")
	req.Header.Set("Content-Type", "application/json")
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusForbidden {
		t.Fatalf("cross-site bind code = %d body=%s", rec.Code, rec.Body.String())
	}
	if len(rec.Result().Cookies()) != 0 {
		t.Fatalf("cross-site bind set cookies: %#v", rec.Result().Cookies())
	}
}

func TestLauncherAuthBindAllowsSameOrigin(t *testing.T) {
	mux := http.NewServeMux()
	RegisterLauncherAuthRoutes(mux, LauncherAuthRouteOpts{
		SessionCookie: "session-cookie-value",
	})

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(
		http.MethodPost,
		"http://www.tuptup.top/api/auth/bind",
		strings.NewReader(`{"join_code":"12345678"}`),
	)
	req.Host = "www.tuptup.top"
	req.Header.Set("Origin", "http://www.tuptup.top")
	req.Header.Set("Sec-Fetch-Site", "same-origin")
	req.Header.Set("Content-Type", "application/json")
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("same-origin bind code = %d body=%s", rec.Code, rec.Body.String())
	}
	cookies := rec.Result().Cookies()
	if len(cookies) != 1 || cookies[0].Name != "colearn_launcher_auth" {
		t.Fatalf("same-origin bind cookies = %#v", cookies)
	}
}

func TestLauncherAuthBindRejectsInvalidCode(t *testing.T) {
	mux := http.NewServeMux()
	RegisterLauncherAuthRoutes(mux, LauncherAuthRouteOpts{
		SessionCookie: "session-cookie-value",
	})

	for _, body := range []string{
		`{}`,
		`{"join_code":"not-a-code"}`,
		`{"join_code":"1234567"}`,
		`{"join_code":""}`,
	} {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodPost, "/api/auth/bind", strings.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		mux.ServeHTTP(rec, req)
		if rec.Code != http.StatusBadRequest {
			t.Fatalf("bind body=%s code = %d body=%s", body, rec.Code, rec.Body.String())
		}
	}
}
