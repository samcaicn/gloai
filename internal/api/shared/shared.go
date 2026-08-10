// Package shared holds tiny HTTP helpers used across the api sub-packages
// (authapi, botapi, appapi, ...). Keeping them here avoids duplicating a few
// lines in every domain package and keeps each domain package self-contained
// except for this one explicit dependency.
package shared

import (
	"encoding/json"
	"net/http"
)

// JSONError writes a JSON error envelope with the given status code.
func JSONError(w http.ResponseWriter, msg string, code int) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": msg})
}

// JSONOK writes a minimal JSON success envelope.
func JSONOK(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "application/json")
	w.Write([]byte(`{"ok":true}`))
}

// OAuthProviderDefs defines the static parts of each supported OAuth provider.
var OAuthProviderDefs = map[string]struct {
	AuthURL, TokenURL, UserInfoURL, Scopes string
}{
	"github": {
		AuthURL:     "",
		TokenURL:    "",
		UserInfoURL: "https://api.github.com/user",
		Scopes:      "read:user user:email",
	},
	"linuxdo": {
		AuthURL:     "https://connect.linux.do/oauth2/authorize",
		TokenURL:    "https://connect.linux.do/oauth2/token",
		UserInfoURL: "https://connect.linux.do/api/user",
		Scopes:      "",
	},
}
