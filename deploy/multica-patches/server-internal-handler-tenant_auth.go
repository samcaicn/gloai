package handler

import (
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/multica-ai/multica/server/internal/analytics"
	"github.com/multica-ai/multica/server/internal/auth"
	"github.com/multica-ai/multica/server/internal/logger"
	obsmetrics "github.com/multica-ai/multica/server/internal/metrics"
)

// tenantSSOSecret 返回与 hub (CEOadmin) 共享的 HMAC 密钥，
// 用于校验「租户登录」SSO token。与 hub 的 MULTICA_SSO_SECRET 必须一致。
func tenantSSOSecret() string {
	if s := strings.TrimSpace(os.Getenv("MULTICA_SSO_SECRET")); s != "" {
		return s
	}
	return "change-me-multica-sso"
}

// tenantSSOTokenSignature 计算 hex(HMAC-SHA256(secret, email|exp))。
func tenantSSOTokenSignature(secret, email string, exp int64) string {
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(email + "|" + strconv.FormatInt(exp, 10)))
	return hex.EncodeToString(mac.Sum(nil))
}

// TenantAuth 处理 hub「租户登录」：浏览器从 hub 会话换取身份（email/exp/sig）
// 后 POST 到此端点，校验 HMAC 签名后直接签发 multica 会话（JWT + HttpOnly cookie）。
func (h *Handler) TenantAuth(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Email string `json:"email"`
		Name  string `json:"name"`
		Exp   int64  `json:"exp"`
		Sig   string `json:"sig"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	email := strings.ToLower(strings.TrimSpace(req.Email))
	if email == "" || req.Sig == "" || req.Exp == 0 {
		writeError(w, http.StatusBadRequest, "email, exp and sig are required")
		return
	}
	if time.Now().Unix() > req.Exp {
		writeError(w, http.StatusUnauthorized, "tenant token expired")
		return
	}

	secret := tenantSSOSecret()
	want := tenantSSOTokenSignature(secret, email, req.Exp)
	if subtle.ConstantTimeCompare([]byte(req.Sig), []byte(want)) != 1 {
		slog.Warn("tenant login rejected: bad signature", append(logger.RequestAttrs(r), "email", email)...)
		writeError(w, http.StatusUnauthorized, "invalid tenant token")
		return
	}

	user, isNew, err := h.findOrCreateUser(r.Context(), email)
	if err != nil {
		if errors.Is(err, auth.ErrTemporarilyDisabledUser) {
			writeError(w, http.StatusForbidden, auth.TemporarilyDisabledUserError)
			return
		}
		var signupErr SignupError
		if errors.As(err, &signupErr) {
			writeError(w, http.StatusForbidden, signupErr.Error())
			return
		}
		writeError(w, http.StatusInternalServerError, "failed to create user")
		return
	}
	if isNew {
		obsmetrics.RecordEvent(h.Analytics, h.Metrics, analytics.Signup(uuidToString(user.ID), user.Email, "tenant_sso"))
	}

	tokenString, err := h.issueJWT(user)
	if err != nil {
		if errors.Is(err, auth.ErrTemporarilyDisabledUser) {
			writeError(w, http.StatusForbidden, auth.TemporarilyDisabledUserError)
			return
		}
		slog.Warn("tenant login failed", append(logger.RequestAttrs(r), "error", err, "email", email)...)
		writeError(w, http.StatusInternalServerError, "failed to generate token")
		return
	}

	// 与 VerifyCode 一致：HttpOnly 认证 cookie + CSRF cookie。
	if err := auth.SetAuthCookies(w, tokenString); err != nil {
		slog.Warn("failed to set auth cookies", "error", err)
	}

	// CloudFront 签名 cookie（CDN 访问）。
	if h.CFSigner != nil {
		for _, cookie := range h.CFSigner.SignedCookies(time.Now().Add(auth.AuthTokenTTL())) {
			http.SetCookie(w, cookie)
		}
	}

	slog.Info("user logged in via tenant SSO", append(logger.RequestAttrs(r), "user_id", uuidToString(user.ID), "email", user.Email)...)
	writeJSON(w, http.StatusOK, LoginResponse{
		Token: tokenString,
		User:  h.userToResponse(user),
	})
}
