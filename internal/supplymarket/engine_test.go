package supplymarket

import (
	"strings"
	"testing"
)

// memConfig is an in-memory ConfigStore for tests.
type memConfig struct {
	m map[string]string
}

func newMemConfig() *memConfig { return &memConfig{m: map[string]string{}} }

func (c *memConfig) GetConfig(key string) (string, error) { return c.m[key], nil }
func (c *memConfig) SetConfig(key, value string) error {
	c.m[key] = value
	return nil
}
func (c *memConfig) DeleteConfig(key string) error {
	delete(c.m, key)
	return nil
}
func (c *memConfig) ListConfigByPrefix(prefix string) (map[string]string, error) {
	out := map[string]string{}
	for k, v := range c.m {
		if strings.HasPrefix(k, prefix) {
			out[k] = v
		}
	}
	return out, nil
}

func newTestEngine(t *testing.T) (*Engine, *memConfig) {
	t.Helper()
	e := &Engine{}
	c := newMemConfig()
	e.Init(c)
	return e, c
}

func goodSupply() (title, desc, category, currency, location, contact string, price float64) {
	return "办公场地出租服务",
		"提供市中心写字楼办公场地出租，包含会议室、共享工位、茶水间与前台服务，适合创业团队与小型企业，拎包入住，价格实惠。",
		"场地", "CNY", "深圳", "wechat: zhangsan", 5000
}

func TestScoringThresholds(t *testing.T) {
	// A complete, well-written item should score >= 40 (auto VERIFIED).
	it := NewItem()
	it.ItemID = "abc"
	it.ItemType = ItemTypeSupply
	it.Title, it.Description, it.Category, it.Currency, it.Location, it.Contact, it.Price = goodSupply()
	if s := computeScore(it); s < ScoreThreshold {
		t.Fatalf("expected score >= %v for a complete item, got %v", ScoreThreshold, s)
	}
	// A sparse item should score < 40 and generate clarification questions.
	it2 := NewItem()
	it2.ItemID = "def"
	it2.ItemType = ItemTypeProcurement
	it2.Title = "x"
	if qs := generateClarificationQuestions(it2); len(qs) == 0 {
		t.Fatal("expected clarification questions for sparse item")
	}
}

func TestPublishAndClarifyLifecycle(t *testing.T) {
	e, _ := newTestEngine(t)

	// Sparse item → PENDING_CLARIFICATION with questions.
	res, err := e.Publish(ItemTypeSupply, "userA", "x", "", "", 0, "", "", "")
	if err != nil {
		t.Fatal(err)
	}
	if res.State != StatePendingClarification {
		t.Fatalf("state = %s, want PENDING_CLARIFICATION", res.State)
	}
	if len(res.NextQuestions) == 0 {
		t.Fatal("expected next questions")
	}
	itemID := res.ItemID

	// Another user cannot clarify.
	if _, err := e.Clarify(itemID, "userB", []ClarificationAnswer{{QID: "q_x", Text: "hi"}}); err == nil {
		t.Fatal("expected permission error for non-owner clarify")
	}

	// Owner answers all questions with smart field-aware text until verified.
	res2 := res
	for round := 0; round < MaxClarifyRounds && res2.State != StateVerified && res2.State != StateRejected; round++ {
		var answers []ClarificationAnswer
		for _, q := range res2.NextQuestions {
			parts := strings.Split(q.QID, "_")
			field := parts[2]
			text := "足够长的补充内容回答" + field
			switch field {
			case "price":
				text = "800"
			case "currency":
				text = "CNY"
			}
			answers = append(answers, ClarificationAnswer{QID: q.QID, Text: text})
		}
		res2, err = e.Clarify(itemID, "userA", answers)
		if err != nil {
			t.Fatal(err)
		}
	}
	if res2.State != StateVerified {
		t.Fatalf("after clarification state = %s, want VERIFIED", res2.State)
	}
	if !res2.IsFinal {
		t.Fatal("expected is_final after verify")
	}

	// Marketplace should now contain the item.
	list, err := e.MarketplaceList(ItemTypeSupply, "", "", nil, nil, 100)
	if err != nil {
		t.Fatal(err)
	}
	if len(list) != 1 || list[0].ItemID != itemID {
		t.Fatalf("marketplace len = %d, want 1 with item %s", len(list), itemID)
	}

	// Close → disappears from marketplace.
	if err := e.Close(itemID, "userA"); err != nil {
		t.Fatal(err)
	}
	list, _ = e.MarketplaceList(ItemTypeSupply, "", "", nil, nil, 100)
	if len(list) != 0 {
		t.Fatalf("marketplace len = %d after close, want 0", len(list))
	}
}

func TestRejectAfterMaxRounds(t *testing.T) {
	e, _ := newTestEngine(t)
	res, err := e.Publish(ItemTypeProcurement, "userA", "求购一批设备", "需要采购设备一批", "设备", 100, "CNY", "", "")
	if err != nil {
		t.Fatal(err)
	}
	if res.State != StatePendingClarification {
		t.Skip("item already verified; adjust fixture")
	}
	itemID := res.ItemID
	for round := 0; round < MaxClarifyRounds; round++ {
		var answers []ClarificationAnswer
		for _, q := range res.NextQuestions {
			answers = append(answers, ClarificationAnswer{QID: q.QID, Text: "x"})
		}
		res, err = e.Clarify(itemID, "userA", answers)
		if err != nil {
			t.Fatal(err)
		}
	}
	if res.State != StateRejected {
		t.Fatalf("after %d weak rounds state = %s, want REJECTED", MaxClarifyRounds, res.State)
	}
	if !res.IsFinal {
		t.Fatal("expected final after reject")
	}
}

func TestMatchPairing(t *testing.T) {
	e, _ := newTestEngine(t)
	_, desc1, cat, cur, loc, contact, price := goodSupply()
	if _, err := e.Publish(ItemTypeSupply, "userA", "办公室出租 南山科技园", desc1, cat, price, cur, loc, contact); err != nil {
		t.Fatal(err)
	}
	// A matching procurement from another user, same category + overlapping words.
	if _, err := e.Publish(ItemTypeProcurement, "userB", "求租办公室 南山科技园", "我们需要在南山区租一间办公室，预算充足，长期租约。", "场地", 4800, "CNY", "深圳", "phone: 13800000000"); err != nil {
		t.Fatal(err)
	}
	// An unrelated supply item from userC.
	if _, err := e.Publish(ItemTypeSupply, "userC", "IT 开发外包服务", "提供企业软件开发外包，前后端一体，按项目报价。", "服务", 80000, "CNY", "北京", "email: dev@x.com"); err != nil {
		t.Fatal(err)
	}

	// Verify both supply & procurement items became VERIFIED (scores high enough).
	supplies, _ := e.MarketplaceList(ItemTypeSupply, "", "", nil, nil, 100)
	if len(supplies) != 2 {
		t.Fatalf("expected 2 verified supplies, got %d", len(supplies))
	}

	// Match against the procurement (userB) should surface the related supply (userA).
	procs, _ := e.MarketplaceList(ItemTypeProcurement, "", "", nil, nil, 100)
	if len(procs) != 1 {
		t.Fatalf("expected 1 verified procurement, got %d", len(procs))
	}
	matches, err := e.Match(procs[0].ItemID, "userB", 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(matches) == 0 {
		t.Fatal("expected at least one match")
	}
	top := matches[0]
	if top.ItemID != supplies[0].ItemID {
		t.Fatalf("top match = %s, want the office supply %s", top.ItemID, supplies[0].ItemID)
	}
	if !top.MatchHit {
		t.Fatalf("expected match_hit for the related pair, match_score=%v", top.MatchScore)
	}
	// The unrelated service should not beat the office supply.
	if len(matches) > 1 && matches[1].MatchScore > top.MatchScore {
		t.Fatal("unrelated item scored higher than the related one")
	}
}

func TestChatFlow(t *testing.T) {
	e, _ := newTestEngine(t)
	_, desc1, cat, cur, loc, contact, price := goodSupply()
	res, err := e.Publish(ItemTypeSupply, "userA", "办公室出租", desc1, cat, price, cur, loc, contact)
	if err != nil {
		t.Fatal(err)
	}
	itemID := res.ItemID
	if res.State != StateVerified {
		t.Fatalf("expected VERIFIED, got %s", res.State)
	}

	// Owner cannot chat with themselves.
	if _, err := e.StartChat(itemID, "userA"); err == nil {
		t.Fatal("expected error when chatting with self")
	}

	// Inquirer starts a session.
	sess, err := e.StartChat(itemID, "userB")
	if err != nil {
		t.Fatal(err)
	}
	if sess.OwnerTenantID != "userA" || sess.InquirerTenantID != "userB" {
		t.Fatalf("session parties wrong: owner=%s inquirer=%s", sess.OwnerTenantID, sess.InquirerTenantID)
	}

	// Re-starting returns the same session.
	sess2, err := e.StartChat(itemID, "userB")
	if err != nil {
		t.Fatal(err)
	}
	if sess2.SessionID != sess.SessionID {
		t.Fatalf("expected same session, got %s vs %s", sess2.SessionID, sess.SessionID)
	}

	// Both sides can send and read.
	if _, err := e.SendChatMessage(sess.SessionID, "userB", "你好，请问场地怎么租？"); err != nil {
		t.Fatal(err)
	}
	if _, err := e.SendChatMessage(sess.SessionID, "userA", "您好，按年签，价格可谈。"); err != nil {
		t.Fatal(err)
	}
	got, err := e.GetChat(sess.SessionID, "userA")
	if err != nil {
		t.Fatal(err)
	}
	if len(got.Messages) != 2 {
		t.Fatalf("expected 2 messages, got %d", len(got.Messages))
	}
	if got.Messages[0].FromRole != "inquirer" || got.Messages[1].FromRole != "owner" {
		t.Fatalf("roles wrong: %s / %s", got.Messages[0].FromRole, got.Messages[1].FromRole)
	}

	// A stranger cannot read it.
	if _, err := e.GetChat(sess.SessionID, "userC"); err == nil {
		t.Fatal("expected permission error for stranger")
	}

	// MyChats surfaces it for both participants.
	chatsB, err := e.MyChats("userB")
	if err != nil {
		t.Fatal(err)
	}
	if len(chatsB) != 1 {
		t.Fatalf("userB chats = %d, want 1", len(chatsB))
	}
	chatsA, _ := e.MyChats("userA")
	if len(chatsA) != 1 {
		t.Fatalf("userA chats = %d, want 1", len(chatsA))
	}
}
