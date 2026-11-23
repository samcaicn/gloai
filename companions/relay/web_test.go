package main

import (
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
)

func TestWebLanguagesAndCatalogCompleteness(t *testing.T) {
	f := newFixture(t)
	for header, want := range map[string]string{"": "en", "fa-IR": "fa", "fa;q=0,en;q=1": "en", "zh-CN": "zh-Hans", "zh-HK": "zh-Hant", "zh-Hans-TW": "zh-Hans", "fr, en;q=0.4,zh-TW;q=0.9": "zh-Hant", "zh;q=0,en;q=1": "en", "zh;q=NaN,en;q=0.1": "en"} {
		req, _ := http.NewRequest("GET", f.server.URL+"/", nil)
		req.Header.Set("Accept-Language", header)
		r, err := f.client.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		r.Body.Close()
		if r.Header.Get("Content-Language") != want {
			t.Errorf("%q got %q want %q", header, r.Header.Get("Content-Language"), want)
		}
	}
	for lang, words := range translations {
		if len(words) != len(translations["en"]) {
			t.Errorf("incomplete catalog %s", lang)
		}
		for key := range translations["en"] {
			if words[key] == "" {
				t.Errorf("missing %s/%s", lang, key)
			}
		}
		req := httptest.NewRequest("GET", f.server.URL+"/", nil)
		req.AddCookie(&http.Cookie{Name: "mika_language", Value: lang})
		w := httptest.NewRecorder()
		f.g.ServeHTTP(w, req)
		if !strings.Contains(w.Body.String(), `<html lang="`+lang+`"`) || !strings.Contains(w.Body.String(), `value="`+lang+`" selected`) {
			t.Fatal("language preference not reflected")
		}
		if lang == "fa" && !strings.Contains(w.Body.String(), `<html lang="fa" dir="rtl">`) {
			t.Fatal("Persian page is not right-to-left")
		}
	}
}

func TestWebLanguageCookieAndAdminSecretsAreNotReflected(t *testing.T) {
	f := newFixture(t)
	status, h, _ := f.request(t, "POST", "/language", "", url.Values{"lang": {"zh-Hant"}, "return": {"https://evil.example"}}.Encode(), "application/x-www-form-urlencoded")
	if status != 303 || h.Get("Location") != "/" || !strings.Contains(h.Get("Set-Cookie"), "HttpOnly") || !strings.Contains(h.Get("Set-Cookie"), "SameSite=Lax") {
		t.Fatal("invalid language redirect/cookie")
	}
	status, _, b := f.request(t, "POST", "/admin", "", url.Values{"owner_key": {"wrong-owner-secret"}, "action": {"pair"}}.Encode(), "application/x-www-form-urlencoded")
	if status != 403 || strings.Contains(string(b), "wrong-owner-secret") || !strings.Contains(string(b), translations["en"]["invalidOwner"]) {
		t.Fatal("owner secret reflected or error not localized")
	}
	f.pair(t)
	body := url.Values{"owner_key": {testOwner}, "action": {"revoke"}}
	status, _, b = f.request(t, "POST", "/admin", "", body.Encode(), "application/x-www-form-urlencoded")
	if status != 200 || f.g.store.view().DeviceHash == "" || !strings.Contains(string(b), `name="confirm" value="yes"`) || strings.Contains(string(b), testOwner) {
		t.Fatal("revoke confirmation did not preserve pairing and hide owner key")
	}
	body.Set("confirm", "yes")
	status, _, _ = f.request(t, "POST", "/admin", "", body.Encode(), "application/x-www-form-urlencoded")
	if status != 200 || f.g.store.view().DeviceHash != "" {
		t.Fatal("confirmed revoke failed")
	}
	for path, kind := range map[string]string{"/style.css": "text/css", "/app.js": "text/javascript"} {
		status, h, b = f.request(t, "GET", path, "", "", "")
		if status != 200 || !strings.HasPrefix(h.Get("Content-Type"), kind) || len(b) == 0 {
			t.Fatal("embedded asset unavailable")
		}
	}
}
