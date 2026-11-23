package main

import (
	"bytes"
	"embed"
	"encoding/json"
	"html/template"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"
)

//go:embed web
var webFiles embed.FS
var webPage = template.Must(template.ParseFS(webFiles, "web/page.html"))
var translations = func() map[string]map[string]string {
	result := map[string]map[string]string{}
	for _, lang := range []string{"en", "fa", "zh-Hans", "zh-Hant"} {
		b, err := webFiles.ReadFile("web/" + lang + ".json")
		if err != nil {
			panic(err)
		}
		var words map[string]string
		if err = json.Unmarshal(b, &words); err != nil {
			panic(err)
		}
		result[lang] = words
	}
	return result
}()

type pageData struct {
	Lang          string
	Preference    string
	Copy          map[string]string
	Message, Code string
	Bad, Confirm  bool
}

func normalizeLanguage(tag string) string {
	parts := strings.Split(strings.ToLower(strings.ReplaceAll(tag, "_", "-")), "-")
	if parts[0] == "en" {
		return "en"
	}
	if parts[0] == "fa" {
		return "fa"
	}
	if parts[0] != "zh" {
		return ""
	}
	for _, part := range parts[1:] {
		if part == "hant" {
			return "zh-Hant"
		}
		if part == "hans" {
			return "zh-Hans"
		}
	}
	for _, part := range parts[1:] {
		if part == "tw" || part == "hk" || part == "mo" {
			return "zh-Hant"
		}
	}
	return "zh-Hans"
}
func pageLanguage(r *http.Request) string {
	if c, err := r.Cookie("mika_language"); err == nil && translations[c.Value] != nil {
		return c.Value
	}
	type choice struct {
		lang string
		q    float64
	}
	var choices []choice
	header := r.Header.Get("Accept-Language")
	if len(header) > 8192 {
		header = ""
	}
	for _, part := range strings.Split(header, ",") {
		pieces := strings.Split(strings.TrimSpace(part), ";")
		lang := normalizeLanguage(strings.TrimSpace(pieces[0]))
		q := 1.0
		for _, p := range pieces[1:] {
			p = strings.TrimSpace(p)
			if strings.HasPrefix(p, "q=") {
				v, err := strconv.ParseFloat(strings.TrimPrefix(p, "q="), 64)
				if err != nil || !(v >= 0 && v <= 1) {
					q = 0
				} else {
					q = v
				}
			}
		}
		if lang != "" && q > 0 {
			choices = append(choices, choice{lang, q})
		}
	}
	sort.SliceStable(choices, func(i, j int) bool { return choices[i].q > choices[j].q })
	if len(choices) > 0 {
		return choices[0].lang
	}
	return "en"
}
func (g *gateway) render(w http.ResponseWriter, r *http.Request, status int, message, code string, bad, confirm bool) {
	lang := pageLanguage(r)
	data := pageData{Lang: lang, Copy: translations[lang], Message: message, Code: code, Bad: bad, Confirm: confirm}
	data.Preference = "auto"
	if c, err := r.Cookie("mika_language"); err == nil && translations[c.Value] != nil {
		data.Preference = c.Value
	}
	var b bytes.Buffer
	if err := webPage.Execute(&b, data); err != nil {
		failure(w, 500, "page_unavailable")
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Content-Language", lang)
	w.Header().Set("Vary", "Accept-Language, Cookie")
	w.WriteHeader(status)
	_, _ = w.Write(b.Bytes())
}
func (g *gateway) asset(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		failure(w, 405, "method_not_allowed")
		return
	}
	name, kind := "web/style.css", "text/css; charset=utf-8"
	switch r.URL.Path {
	case "/app.js":
		name = "web/app.js"
		kind = "text/javascript; charset=utf-8"
	case "/console.css":
		name = "web/console.css"
	case "/console.js":
		name = "web/console.js"
		kind = "text/javascript; charset=utf-8"
	}
	b, err := webFiles.ReadFile(name)
	if err != nil {
		failure(w, 404, "not_found")
		return
	}
	w.Header().Set("Content-Type", kind)
	_, _ = w.Write(b)
}
func (g *gateway) language(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		failure(w, 405, "method_not_allowed")
		return
	}
	if o := r.Header.Get("Origin"); o != "" && o != g.base {
		failure(w, 403, "invalid_origin")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 1024)
	if r.ParseForm() != nil {
		failure(w, 400, "invalid_request")
		return
	}
	lang := r.PostForm.Get("lang")
	if lang != "auto" && translations[lang] == nil {
		failure(w, 400, "invalid_language")
		return
	}
	cookie := &http.Cookie{Name: "mika_language", Value: lang, Path: "/", Secure: strings.HasPrefix(g.base, "https://"), HttpOnly: true, SameSite: http.SameSiteLaxMode, MaxAge: 365 * 24 * 60 * 60}
	if lang == "auto" {
		cookie.MaxAge = -1
	}
	http.SetCookie(w, cookie)
	http.Redirect(w, r, "/", http.StatusSeeOther)
}
func (g *gateway) admin(w http.ResponseWriter, r *http.Request) {
	if r.Method == "GET" {
		g.render(w, r, 200, "", "", false, false)
		return
	}
	if r.Method != "POST" {
		failure(w, 405, "method_not_allowed")
		return
	}
	if o := r.Header.Get("Origin"); o != "" && o != g.base {
		g.render(w, r, 403, "invalidOrigin", "", true, false)
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 4096)
	if r.ParseForm() != nil {
		g.render(w, r, 400, "invalidRequest", "", true, false)
		return
	}
	if g.limited(r, "owner", 5) {
		g.render(w, r, 429, "rateLimited", "", true, false)
		return
	}
	if !matches(r.PostForm.Get("owner_key"), g.ownerHash) {
		g.render(w, r, 403, "invalidOwner", "", true, false)
		return
	}
	switch r.PostForm.Get("action") {
	case "pair":
		g.mu.Lock()
		if g.store.view().DeviceHash != "" {
			g.mu.Unlock()
			g.render(w, r, 409, "alreadyPaired", "", true, false)
			return
		}
		code := secret()[:16]
		g.pairHash = digest(code)
		g.pairUntil = g.now().Add(5 * time.Minute)
		g.mu.Unlock()
		g.render(w, r, 200, "codeCreated", code, false, false)
	case "revoke":
		if r.PostForm.Get("confirm") != "yes" {
			g.render(w, r, 200, "confirmHint", "", false, true)
			return
		}
		if err := g.clearDevice(""); err != nil {
			g.render(w, r, 500, "storageError", "", true, false)
			return
		}
		g.render(w, r, 200, "revoked", "", false, false)
	default:
		g.render(w, r, 400, "invalidRequest", "", true, false)
	}
}
