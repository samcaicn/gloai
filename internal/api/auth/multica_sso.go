package authapi

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"strconv"
	"time"

	"github.com/ceoadmin/CEOadmin/internal/api/shared"
	"github.com/ceoadmin/CEOadmin/internal/auth"
)

// tenantSSOExpiry 是 SSO token 的有效期。
const tenantSSOExpiry = 5 * time.Minute

// HandleMulticaSSO 为内置 multica 应用签发「租户登录」token。
//
// 调用方是经 hub 登录的浏览器（携带 hub 会话 cookie），multica 前端在同源
// 下请求本端点换取当前租户身份，再交给 multica 服务端校验后发多卡会话。
// token = hex(HMAC-SHA256(secret, email|exp))，secret 由 MULTICA_SSO_SECRET
// 注入，multica 服务端使用同一密钥校验。
func (s *AuthHandler) HandleMulticaSSO(w http.ResponseWriter, r *http.Request) {
	userID := auth.UserIDFromContext(r.Context())
	user, err := s.Store.GetUserByID(userID)
	if err != nil {
		shared.JSONError(w, "user not found", http.StatusNotFound)
		return
	}
	if user.Email == "" {
		shared.JSONError(w, "user has no email", http.StatusForbidden)
		return
	}

	exp := time.Now().Add(tenantSSOExpiry).Unix()
	secret := s.Config.MulticaSSOSecret
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(user.Email + "|" + strconv.FormatInt(exp, 10)))
	sig := hex.EncodeToString(mac.Sum(nil))

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(struct {
		Email string `json:"email"`
		Name  string `json:"name"`
		Exp   int64  `json:"exp"`
		Sig   string `json:"sig"`
	}{
		Email: user.Email,
		Name:  user.DisplayName,
		Exp:   exp,
		Sig:   sig,
	})
}