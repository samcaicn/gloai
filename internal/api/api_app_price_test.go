package api

import (
	"encoding/json"
	"io"
	"net/http"
	"testing"

	"github.com/ceoadmin/CEOadmin/internal/store"
)

// TestAppVisibility_AllActiveVisible 验证需求:默认所有 status=active 的应用对用户可见
// (去掉了 listing='listed' 的上架门槛)。这里创建一个 unlisted 的应用,仍应出现在公开市场列表中。
func TestAppVisibility_AllActiveVisible(t *testing.T) {
	env := setupTestEnv(t)

	// 创建一个未上架(unlisted)但 active 的应用。
	app, err := env.store.CreateApp(&store.App{
		OwnerID: env.user.ID,
		Name:    "未上架但可见的应用",
		Slug:    "unlisted-but-visible",
		Listing: "unlisted",
	})
	if err != nil {
		t.Fatalf("create app: %v", err)
	}

	resp := doJSON(t, env.ts, "GET", "/api/apps?listing=listed", nil, withCookie(env.cookie))
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("GET /api/apps?listing=listed status = %d, want 200", resp.StatusCode)
	}
	bodyBytes, _ := io.ReadAll(resp.Body)
	var list []map[string]any
	if err := json.Unmarshal(bodyBytes, &list); err != nil {
		t.Fatalf("decode list: %v", err)
	}
	found := false
	for _, item := range list {
		if id, _ := item["id"].(string); id == app.ID {
			found = true
		}
	}
	if !found {
		t.Errorf("unlisted active app %s not visible in public marketplace list", app.ID)
	}
}

// TestPaidAppInstallGate 验证需求:付费应用(price>0)需先购买(授权记录)才能安装(402 拦截);
// 零元应用可直接安装。
func TestPaidAppInstallGate(t *testing.T) {
	env := setupTestEnv(t)

	bot, err := env.store.CreateBot(env.user.ID, "gate-bot", "ilink", "", nil)
	if err != nil {
		t.Fatalf("create bot: %v", err)
	}

	// 一个他人拥有的付费应用。
	paidApp, err := env.store.CreateApp(&store.App{
		OwnerID:  "other-owner",
		Name:     "付费应用",
		Slug:     "paid-app-gate",
		Price:    9.9,
		Currency: "CNY",
		Listing:  "unlisted",
	})
	if err != nil {
		t.Fatalf("create paid app: %v", err)
	}

	install := func() int {
		resp := doJSON(t, env.ts, "POST", "/api/apps/"+paidApp.ID+"/install", map[string]any{
			"bot_id": bot.ID,
		}, withCookie(env.cookie))
		defer resp.Body.Close()
		return resp.StatusCode
	}

	// 未购买 -> 402 Payment Required
	if code := install(); code != http.StatusPaymentRequired {
		t.Fatalf("install without purchase status = %d, want %d (402)", code, http.StatusPaymentRequired)
	}

	// 购买(创建授权记录)后 -> 201
	if _, err := env.store.CreateAppPurchase(paidApp.ID, env.user.ID); err != nil {
		t.Fatalf("create purchase: %v", err)
	}
	if code := install(); code != http.StatusCreated {
		t.Fatalf("install after purchase status = %d, want %d (201)", code, http.StatusCreated)
	}

	// 零元应用可直接安装,无需购买。
	freeApp, err := env.store.CreateApp(&store.App{
		OwnerID: "other-owner",
		Name:    "零元应用",
		Slug:    "free-app-gate",
		Price:   0,
		Listing: "unlisted",
	})
	if err != nil {
		t.Fatalf("create free app: %v", err)
	}
	resp := doJSON(t, env.ts, "POST", "/api/apps/"+freeApp.ID+"/install", map[string]any{
		"bot_id": bot.ID,
	}, withCookie(env.cookie))
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("free app install status = %d, want %d (201)", resp.StatusCode, http.StatusCreated)
	}
}
