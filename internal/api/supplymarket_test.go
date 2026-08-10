package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/ceoadmin/CEOadmin/internal/supplymarket"
)

// newSupplyMarketServer wires the global supplymarket.Default engine to a
// fresh in-memory config store.
func newSupplyMarketServer(t *testing.T) (*Server, *memConfig) {
	t.Helper()
	mc := newMemConfig()
	supplymarket.Default.Init(mc)
	return &Server{}, mc
}

func decodeItems(t *testing.T, rec *httptest.ResponseRecorder) []*supplymarket.Item {
	t.Helper()
	var items []*supplymarket.Item
	if err := json.Unmarshal(rec.Body.Bytes(), &items); err != nil {
		t.Fatalf("decode items: %v (body=%s)", err, rec.Body.String())
	}
	return items
}

func decodeSession(t *testing.T, rec *httptest.ResponseRecorder) *supplymarket.ChatSession {
	t.Helper()
	var s supplymarket.ChatSession
	if err := json.Unmarshal(rec.Body.Bytes(), &s); err != nil {
		t.Fatalf("decode session: %v (body=%s)", err, rec.Body.String())
	}
	return &s
}

func decodeResult(t *testing.T, rec *httptest.ResponseRecorder) *supplymarket.PublishResult {
	t.Helper()
	var r supplymarket.PublishResult
	if err := json.Unmarshal(rec.Body.Bytes(), &r); err != nil {
		t.Fatalf("decode result: %v (body=%s)", err, rec.Body.String())
	}
	return &r
}

// answerField extracts the field name from a clarification qid of the form
// q_{item_id}_{field}_{i}.
func answerField(qid, itemID string) string {
	parts := strings.Split(qid, "_")
	if len(parts) >= 3 && parts[0] == "q" && len(parts[1]) == len(itemID) {
		return parts[2]
	}
	return ""
}

func TestSupplyMarketPublishClarifyListChat(t *testing.T) {
	s, _ := newSupplyMarketServer(t)

	// userA publishes a sparse item → PENDING_CLARIFICATION.
	pub := `{"item_type":"supply","title":"x","description":"","category":"","price":0,"currency":"","location":"","contact":""}`
	rec := httptest.NewRecorder()
	s.handleSupplyMarketPublish(rec, mkReq(t, http.MethodPost, "/api/supply-market/items", "", []byte(pub), "userA"))
	if rec.Code != 200 {
		t.Fatalf("publish status = %d, body=%s", rec.Code, rec.Body.String())
	}
	res := decodeResult(t, rec)
	if res.State != "PENDING_CLARIFICATION" {
		t.Fatalf("state = %s, want PENDING_CLARIFICATION", res.State)
	}
	if len(res.NextQuestions) == 0 {
		t.Fatal("expected next questions")
	}

	// userA clarifies with field-aware answers until VERIFIED.
	id := res.ItemID
	for round := 0; round < 3 && res.State == "PENDING_CLARIFICATION"; round++ {
		parts := make([]string, 0, len(res.NextQuestions))
		for _, q := range res.NextQuestions {
			text := "足够长的补充内容回答"
			if field := answerField(q.QID, id); field == "price" {
				text = "800"
			} else if field == "currency" {
				text = "CNY"
			}
			parts = append(parts, `{"qid":"`+q.QID+`","text":"`+text+`"}`)
		}
		answersJSON := `{"answers":[` + strings.Join(parts, ",") + `]}`
		rec = httptest.NewRecorder()
		s.handleSupplyMarketClarify(rec, mkReq(t, http.MethodPost, "/api/supply-market/items/"+id+"/clarify", id, []byte(answersJSON), "userA"))
		if rec.Code != 200 {
			t.Fatalf("clarify round %d status = %d, body=%s", round, rec.Code, rec.Body.String())
		}
		res = decodeResult(t, rec)
	}
	if res.State != "VERIFIED" {
		t.Fatalf("after clarify state = %s, want VERIFIED", res.State)
	}

	// Marketplace (cross-tenant) should show it to userB.
	rec = httptest.NewRecorder()
	s.handleSupplyMarketList(rec, mkReq(t, http.MethodGet, "/api/supply-market/marketplace?item_type=supply", "", nil, "userB"))
	if rec.Code != 200 {
		t.Fatalf("marketplace status = %d, body=%s", rec.Code, rec.Body.String())
	}
	items := decodeItems(t, rec)
	if len(items) != 1 || items[0].ItemID != id {
		t.Fatalf("marketplace len = %d, want 1 with item %s", len(items), id)
	}

	// userB starts a chat and both sides exchange messages.
	rec = httptest.NewRecorder()
	s.handleSupplyMarketChatStart(rec, mkReq(t, http.MethodPost, "/api/supply-market/chats", "", []byte(`{"item_id":"`+id+`"}`), "userB"))
	if rec.Code != 200 {
		t.Fatalf("chat start status = %d, body=%s", rec.Code, rec.Body.String())
	}
	sess := decodeSession(t, rec)
	if sess.InquirerTenantID != "userB" || sess.OwnerTenantID != "userA" {
		t.Fatalf("session parties wrong: owner=%s inquirer=%s", sess.OwnerTenantID, sess.InquirerTenantID)
	}

	rec = httptest.NewRecorder()
	s.handleSupplyMarketChatSend(rec, mkReq(t, http.MethodPost, "/api/supply-market/chats/"+sess.SessionID+"/messages", sess.SessionID, []byte(`{"text":"请问怎么租？"}`), "userB"))
	if rec.Code != 200 {
		t.Fatalf("send status = %d, body=%s", rec.Code, rec.Body.String())
	}
	rec = httptest.NewRecorder()
	s.handleSupplyMarketChatSend(rec, mkReq(t, http.MethodPost, "/api/supply-market/chats/"+sess.SessionID+"/messages", sess.SessionID, []byte(`{"text":"按年签，可谈。"}`), "userA"))
	if rec.Code != 200 {
		t.Fatalf("send owner status = %d, body=%s", rec.Code, rec.Body.String())
	}

	// userA reads history and sees my_role=owner.
	rec = httptest.NewRecorder()
	s.handleSupplyMarketChatGet(rec, mkReq(t, http.MethodGet, "/api/supply-market/chats/"+sess.SessionID, sess.SessionID, nil, "userA"))
	if rec.Code != 200 {
		t.Fatalf("chat get status = %d, body=%s", rec.Code, rec.Body.String())
	}
	sess = decodeSession(t, rec)
	if len(sess.Messages) != 2 {
		t.Fatalf("messages = %d, want 2", len(sess.Messages))
	}
	if sess.MyRole != "owner" {
		t.Fatalf("my_role = %s, want owner", sess.MyRole)
	}

	// Stranger userC cannot read the session (403).
	rec = httptest.NewRecorder()
	s.handleSupplyMarketChatGet(rec, mkReq(t, http.MethodGet, "/api/supply-market/chats/"+sess.SessionID, sess.SessionID, nil, "userC"))
	if rec.Code != http.StatusForbidden {
		t.Fatalf("stranger chat get status = %d, want 403", rec.Code)
	}

	// Non-owner cannot delete userA's item (403).
	rec = httptest.NewRecorder()
	s.handleSupplyMarketDelete(rec, mkReq(t, http.MethodDelete, "/api/supply-market/items/"+id, id, nil, "userB"))
	if rec.Code != http.StatusForbidden {
		t.Fatalf("stranger delete status = %d, want 403", rec.Code)
	}

	// owner deletes it; it disappears from the marketplace.
	rec = httptest.NewRecorder()
	s.handleSupplyMarketDelete(rec, mkReq(t, http.MethodDelete, "/api/supply-market/items/"+id, id, nil, "userA"))
	if rec.Code != 200 {
		t.Fatalf("owner delete status = %d, body=%s", rec.Code, rec.Body.String())
	}
	rec = httptest.NewRecorder()
	s.handleSupplyMarketList(rec, mkReq(t, http.MethodGet, "/api/supply-market/marketplace", "", nil, "userB"))
	items = decodeItems(t, rec)
	if len(items) != 0 {
		t.Fatalf("marketplace len = %d after delete, want 0", len(items))
	}
}

func TestSupplyMarketMatch(t *testing.T) {
	s, _ := newSupplyMarketServer(t)

	pub := func(uid string, body string) string {
		rec := httptest.NewRecorder()
		s.handleSupplyMarketPublish(rec, mkReq(t, http.MethodPost, "/api/supply-market/items", "", []byte(body), uid))
		if rec.Code != 200 {
			t.Fatalf("publish status = %d, body=%s", rec.Code, rec.Body.String())
		}
		return decodeResult(t, rec).ItemID
	}

	// userA: a complete supply (auto VERIFIED).
	supplyID := pub("userA", `{"item_type":"supply","title":"办公室出租 南山科技园","description":"市中心写字楼办公室出租，含会议室、共享工位与前台服务，适合创业团队与小企业，拎包入住。","category":"场地","price":5000,"currency":"CNY","location":"深圳","contact":"wechat: zhangsan"}`)
	// userB: a matching procurement (auto VERIFIED).
	procID := pub("userB", `{"item_type":"procurement","title":"求租办公室 南山科技园","description":"我们需要在南山区租一间办公室，预算充足，长期租约，面积五十到一百平米。","category":"场地","price":4800,"currency":"CNY","location":"深圳","contact":"phone: 13800000000"}`)
	// userC: an unrelated service supply.
	pub("userC", `{"item_type":"supply","title":"IT 开发外包服务","description":"提供企业软件开发外包，前后端一体，按项目报价，交付周期可控。","category":"服务","price":80000,"currency":"CNY","location":"北京","contact":"email: dev@x.com"}`)

	// userB matches its procurement → the office supply should rank first.
	rec := httptest.NewRecorder()
	s.handleSupplyMarketMatch(rec, mkReq(t, http.MethodGet, "/api/supply-market/match?item_id="+procID, "", nil, "userB"))
	if rec.Code != 200 {
		t.Fatalf("match status = %d, body=%s", rec.Code, rec.Body.String())
	}
	var matches []*supplymarket.MatchCandidate
	if err := json.Unmarshal(rec.Body.Bytes(), &matches); err != nil {
		t.Fatalf("decode matches: %v", err)
	}
	if len(matches) == 0 {
		t.Fatal("expected matches")
	}
	if matches[0].ItemID != supplyID {
		t.Fatalf("top match = %s, want %s", matches[0].ItemID, supplyID)
	}
	if !matches[0].MatchHit {
		t.Fatalf("expected match_hit, match_score=%v", matches[0].MatchScore)
	}
}
