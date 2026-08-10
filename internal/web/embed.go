package web

import (
	"embed"
	"io"
	"io/fs"
	"mime"
	"net/http"
	"os"
	"path"
	"strings"
)

//go:embed all:dist
var distFS embed.FS

// Handler returns an http.Handler that serves the embedded frontend.
// Falls back to index.html for SPA routing.
// If dist/ doesn't exist (dev mode), returns nil.
func Handler() http.Handler {
	sub, err := fs.Sub(distFS, "dist")
	if err != nil {
		return nil
	}

	// Check if dist has content
	entries, _ := fs.ReadDir(sub, ".")
	if len(entries) == 0 {
		return nil
	}

	fileServer := http.FileServer(http.FS(sub))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Clean the path and reject traversal.
		upath := path.Clean("/" + r.URL.Path)
		if strings.Contains(upath, "..") {
			http.NotFound(w, r)
			return
		}
		name := strings.TrimPrefix(upath, "/")
		if name == "" {
			name = "index.html"
		}

		// Directories: serve their index.html.
		if fi, err := fs.Stat(sub, name); err == nil && fi.IsDir() {
			name = strings.TrimSuffix(name, "/") + "/index.html"
		}

		f, err := sub.Open(name)
		if err != nil {
			// SPA fallback: serve index.html
			r.URL.Path = "/"
			fileServer.ServeHTTP(w, r)
			return
		}
		defer f.Close()
		fi, err := f.Stat()
		if err != nil || fi.IsDir() {
			// SPA fallback: serve index.html
			r.URL.Path = "/"
			fileServer.ServeHTTP(w, r)
			return
		}
		if rs, ok := f.(io.ReadSeeker); ok {
			http.ServeContent(w, r, fi.Name(), fi.ModTime(), rs)
			return
		}
		// Non-seekable fs.File fallback (e.g. future non-embed FS).
		if ct := mime.TypeByExtension(path.Ext(name)); ct != "" {
			w.Header().Set("Content-Type", ct)
		}
		io.Copy(w, f)
	})
}

// DevDistExists checks if a local dist/ directory exists (for dev builds).
func DevDistExists() bool {
	info, err := os.Stat("dist")
	return err == nil && info.IsDir()
}
