package authapi

import (
	"github.com/ceoadmin/CEOadmin/internal/auth"
	"github.com/ceoadmin/CEOadmin/internal/bot"
	"github.com/ceoadmin/CEOadmin/internal/config"
	"github.com/ceoadmin/CEOadmin/internal/store"
	"github.com/go-webauthn/webauthn/webauthn"
)

// AuthHandler groups the authentication / profile / OAuth / OIDC / passkey
// handlers. It holds only the dependencies those handlers need, instead of a
// shared god-object Server, so each concern is independently testable.
type AuthHandler struct {
	Store        store.Store
	BotManager   *bot.Manager
	Config       *config.Config
	OAuthStates  *OAuthStateStore
	SessionStore *auth.SessionStore
	WebAuthn     *webauthn.WebAuthn
}

// NewAuthHandler constructs an AuthHandler from the dependencies it uses.
func NewAuthHandler(store store.Store, botMgr *bot.Manager, cfg *config.Config, oauthStates *OAuthStateStore, sessionStore *auth.SessionStore, wa *webauthn.WebAuthn) *AuthHandler {
	return &AuthHandler{
		Store:        store,
		BotManager:   botMgr,
		Config:       cfg,
		OAuthStates:  oauthStates,
		SessionStore: sessionStore,
		WebAuthn:     wa,
	}
}
