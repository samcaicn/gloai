package authapi

import "github.com/ceoadmin/CEOadmin/internal/api/shared"

import (
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"github.com/ceoadmin/CEOadmin/internal/auth"
	"github.com/ceoadmin/CEOadmin/internal/store"
)

// --- Session ---

func (s *AuthHandler) HandleLogout(w http.ResponseWriter, r *http.Request) {
	if cookie, err := r.Cookie("session"); err == nil {
		auth.DeleteSession(s.Store, cookie.Value)
	}
	http.SetCookie(w, &http.Cookie{
		Name: "session", Value: "", Path: "/",
		HttpOnly: true, MaxAge: -1, Expires: time.Unix(0, 0),
	})
	shared.JSONOK(w)
}

// --- Profile (authenticated) ---

func (s *AuthHandler) HandleMe(w http.ResponseWriter, r *http.Request) {
	userID := auth.UserIDFromContext(r.Context())
	user, err := s.Store.GetUserByID(userID)
	if err != nil {
		shared.JSONError(w, "user not found", http.StatusNotFound)
		return
	}

	// Check security state for the frontend
	hasPassword := user.PasswordHash != ""
	creds, _ := s.Store.GetCredentialsByUserID(userID)
	hasPasskey := len(creds) > 0
	oauthAccounts, _ := s.Store.ListOAuthAccountsByUser(userID)
	hasOAuth := len(oauthAccounts) > 0

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(struct {
		*store.User
		HasPassword bool `json:"has_password"`
		HasPasskey  bool `json:"has_passkey"`
		HasOAuth    bool `json:"has_oauth"`
	}{
		User:        user,
		HasPassword: hasPassword,
		HasPasskey:  hasPasskey,
		HasOAuth:    hasOAuth,
	})
}

func (s *AuthHandler) HandleUpdateProfile(w http.ResponseWriter, r *http.Request) {
	userID := auth.UserIDFromContext(r.Context())
	var req struct {
		DisplayName string `json:"display_name"`
		Email       string `json:"email"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}
	if err := s.Store.UpdateUserProfile(userID, req.DisplayName, req.Email); err != nil {
		shared.JSONError(w, "update failed", http.StatusInternalServerError)
		return
	}
	shared.JSONOK(w)
}

func (s *AuthHandler) HandleUpdateUsername(w http.ResponseWriter, r *http.Request) {
	userID := auth.UserIDFromContext(r.Context())
	var req struct {
		Username string `json:"username"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}

	if err := store.ValidateUsername(req.Username); err != nil {
		shared.JSONError(w, err.Error(), http.StatusBadRequest)
		return
	}

	existing, err := s.Store.GetUserByUsername(req.Username)
	if err == nil && existing.ID != userID {
		shared.JSONError(w, "username already taken", http.StatusConflict)
		return
	}

	if err := s.Store.UpdateUserUsername(userID, req.Username); err != nil {
		if strings.Contains(strings.ToLower(err.Error()), "unique") {
			shared.JSONError(w, "username already taken", http.StatusConflict)
		} else {
			shared.JSONError(w, "update failed", http.StatusInternalServerError)
		}
		return
	}
	shared.JSONOK(w)
}

// --- Helpers ---

func setSessionCookie(w http.ResponseWriter, token string) {
	http.SetCookie(w, &http.Cookie{
		Name: "session", Value: token, Path: "/",
		HttpOnly: true, SameSite: http.SameSiteLaxMode, MaxAge: 7 * 24 * 3600,
	})
}
