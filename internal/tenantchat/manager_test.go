package tenantchat

import (
	"context"
	"strings"
	"testing"

	"github.com/ceoadmin/CEOadmin/internal/ai"
	"github.com/ceoadmin/CEOadmin/internal/store"
)

// memConfig is an in-memory configStore for tests.
type memConfig struct {
	m map[string]string
}

func newMemConfig() *memConfig { return &memConfig{m: map[string]string{}} }

func (s *memConfig) GetConfig(k string) (string, error) { return s.m[k], nil }
func (s *memConfig) SetConfig(k, v string) error        { s.m[k] = v; return nil }
func (s *memConfig) DeleteConfig(k string) error        { delete(s.m, k); return nil }
func (s *memConfig) ListConfigByPrefix(p string) (map[string]string, error) {
	out := map[string]string{}
	for k, v := range s.m {
		if strings.HasPrefix(k, p) {
			out[k] = v
		}
	}
	return out, nil
}

// withFakeAI swaps in a deterministic LLM response for the duration of the test.
func withFakeAI() func() {
	SetAICompletion(func(_ context.Context, cfg store.AIConfig, _ []ai.Message, _ []ai.Tool) (*ai.CompletionResult, error) {
		return &ai.CompletionResult{Content: "你好，我是" + cfg.Model}, nil
	})
	return func() { SetAICompletion(ai.CompleteMessages) }
}

func newManager(store configStore) *Manager {
	m := &Manager{}
	m.Init(store)
	return m
}

func TestCreateAndJoin(t *testing.T) {
	m := newManager(newMemConfig())
	conv, err := m.Create("userA")
	if err != nil {
		t.Fatal(err)
	}
	if conv.Participants[SideA].UserID != "userA" {
		t.Errorf("甲 should be owned by userA")
	}
	if conv.Participants[SideB].UserID != "" {
		t.Errorf("乙 seat should start empty")
	}
	if conv.Status != StatusWaiting {
		t.Errorf("status = %q, want waiting", conv.Status)
	}
	code := conv.InviteCode
	if code == "" {
		t.Fatal("expected a non-empty invite code")
	}

	conv2, err := m.Join(conv.ID, code, "userB")
	if err != nil {
		t.Fatal(err)
	}
	if !conv2.Paired() {
		t.Errorf("conversation should be paired after join")
	}
	if conv2.Status != StatusIdle {
		t.Errorf("status = %q, want idle", conv2.Status)
	}
	if conv2.InviteCode != "" {
		t.Errorf("invite code should be consumed after join")
	}
}

func TestJoinErrors(t *testing.T) {
	m := newManager(newMemConfig())
	conv, _ := m.Create("userA")
	code := conv.InviteCode

	if _, err := m.Join(conv.ID, "wrong", "userB"); err != errBadCode {
		t.Errorf("wrong code: want errBadCode, got %v", err)
	}
	if _, err := m.Join(conv.ID, code, "userA"); err != errSameUser {
		t.Errorf("same user: want errSameUser, got %v", err)
	}
	if _, err := m.Join(conv.ID, code, "userB"); err != nil {
		t.Fatalf("first join failed: %v", err)
	}
	if _, err := m.Join(conv.ID, code, "userC"); err != errSeatTaken {
		t.Errorf("seat taken: want errSeatTaken, got %v", err)
	}
}

func TestSetOwnPersonaIsolation(t *testing.T) {
	m := newManager(newMemConfig())
	conv, _ := m.Create("userA")
	code := conv.InviteCode
	if _, err := m.Join(conv.ID, code, "userB"); err != nil {
		t.Fatal(err)
	}

	if err := m.SetOwnPersona(conv.ID, "userA", "甲方改名", "新人设A"); err != nil {
		t.Fatal(err)
	}
	if err := m.SetOwnPersona(conv.ID, "userB", "乙方改名", "新人设B"); err != nil {
		t.Fatal(err)
	}
	// userB tries to "edit A" — but SetOwnPersona always targets the caller's
	// own seat, so this only touches B. A must remain unchanged.
	m.SetOwnPersona(conv.ID, "userB", "黑客A", "x")

	// Reload from the persisted store to verify isolation + durability.
	m2 := newManager(m.store)
	c2, ok := m2.Get(conv.ID)
	if !ok {
		t.Fatal("conversation not reloaded")
	}
	if c2.Participants[SideA].Name != "甲方改名" || c2.Participants[SideA].SystemPrompt != "新人设A" {
		t.Errorf("甲 was modified by 乙: %+v", c2.Participants[SideA])
	}
	if c2.Participants[SideB].Name != "黑客A" {
		t.Errorf("乙 should reflect 乙's last edit: %+v", c2.Participants[SideB])
	}
}

func TestSetOwnPersonaAuth(t *testing.T) {
	m := newManager(newMemConfig())
	conv, _ := m.Create("userA")
	code := conv.InviteCode
	m.Join(conv.ID, code, "userB")

	// A non-participant cannot edit any seat.
	if err := m.SetOwnPersona(conv.ID, "ghost", "x", "y"); err != errNotParticipant {
		t.Errorf("ghost: want errNotParticipant, got %v", err)
	}
	// Unknown conversation.
	if err := m.SetOwnPersona("nope", "userA", "x", "y"); err != errNotFound {
		t.Errorf("unknown: want errNotFound, got %v", err)
	}
}

func TestStepRequiresAI(t *testing.T) {
	// No ai.api_key configured, and no platform system LLM interface
	// (ACC_PRODUCT_CONFIG_V2) available — Step must refuse without an LLM.
	t.Setenv("ACC_PRODUCT_CONFIG_V2", "")
	m := newManager(newMemConfig())
	conv, _ := m.Create("userA")
	m.Join(conv.ID, conv.InviteCode, "userB")
	if err := m.Step(conv.ID); err != errNoAI {
		t.Errorf("want errNoAI, got %v", err)
	}
}

func TestStartRequiresPaired(t *testing.T) {
	m := newManager(newMemConfig())
	m.store.SetConfig("ai.api_key", "sk-test")
	conv, _ := m.Create("userA") // waiting, not paired yet
	if err := m.Start(conv.ID); err != errNotPaired {
		t.Errorf("want errNotPaired, got %v", err)
	}
}

func TestStepGeneratesMessage(t *testing.T) {
	m := newManager(newMemConfig())
	m.store.SetConfig("ai.api_key", "sk-test")
	m.store.SetConfig("ai.model", "gpt-test")
	conv, _ := m.Create("userA")
	m.Join(conv.ID, conv.InviteCode, "userB")

	defer withFakeAI()()

	if err := m.Step(conv.ID); err != nil {
		t.Fatal(err)
	}
	c, _ := m.Get(conv.ID)
	if len(c.Messages) != 1 {
		t.Fatalf("messages = %d, want 1", len(c.Messages))
	}
	if c.Messages[0].Side != SideA {
		t.Errorf("first message should be from 甲")
	}
	if c.Turn != SideB {
		t.Errorf("turn should flip to 乙, got %q", c.Turn)
	}
	if c.Status != StatusIdle {
		t.Errorf("status = %q, want idle", c.Status)
	}
}

func TestConversationForUser(t *testing.T) {
	m := newManager(newMemConfig())
	conv, _ := m.Create("userA")
	m.Join(conv.ID, conv.InviteCode, "userB")

	list, err := m.ConversationsForUser("userA")
	if err != nil || len(list) != 1 || list[0].ID != conv.ID {
		t.Errorf("userA should find exactly 1 conversation, got %d", len(list))
	}
	listB, err := m.ConversationsForUser("userB")
	if err != nil || len(listB) != 1 || listB[0].ID != conv.ID {
		t.Errorf("userB should find exactly 1 conversation")
	}
	listNone, err := m.ConversationsForUser("ghost")
	if err != nil || len(listNone) != 0 {
		t.Errorf("ghost should have no conversation")
	}
}

func TestPassiveProfile(t *testing.T) {
	m := newManager(newMemConfig())

	p, err := m.GetPassiveProfile("userA")
	if err != nil || p.Enabled {
		t.Errorf("default passive profile should be disabled")
	}

	if err := m.SetPassiveProfile("userA", true, "alice", "Alice", "I am Alice", "topic", 10, 800); err != nil {
		t.Fatal(err)
	}
	p, _ = m.GetPassiveProfile("userA")
	if !p.Enabled || p.Handle != "alice" || p.Name != "Alice" || p.SystemPrompt != "I am Alice" {
		t.Errorf("passive profile not saved: %+v", p)
	}

	// Handle must be unique across users.
	if err := m.SetPassiveProfile("userB", true, "alice", "Bob", "x", "", 12, 1500); err != errHandleTaken {
		t.Errorf("handle taken: want errHandleTaken, got %v", err)
	}

	// Resolve by handle, and by raw user id as fallback.
	if uid, err := m.ResolvePassive("alice"); err != nil || uid != "userA" {
		t.Errorf("resolve 'alice' -> userA, got %q err %v", uid, err)
	}
	if uid, err := m.ResolvePassive("userA"); err != nil || uid != "userA" {
		t.Errorf("resolve 'userA' (fallback) -> userA, got %q err %v", uid, err)
	}

	// Changing the handle frees the old one and claims the new one.
	if err := m.SetPassiveProfile("userA", true, "alice2", "Alice", "x", "", 12, 1500); err != nil {
		t.Fatal(err)
	}
	if _, err := m.ResolvePassive("alice"); err == nil {
		t.Errorf("old handle 'alice' should be freed")
	}
	if uid, err := m.ResolvePassive("alice2"); err != nil || uid != "userA" {
		t.Errorf("new handle 'alice2' should resolve to userA, got %q err %v", uid, err)
	}
}

func TestStartPassive(t *testing.T) {
	m := newManager(newMemConfig())
	m.store.SetConfig("ai.api_key", "sk-test")

	if err := m.SetPassiveProfile("userB", true, "bob", "Bob", "I am Bob", "我们聊点啥", 7, 600); err != nil {
		t.Fatal(err)
	}

	conv, err := m.StartPassive("bob", "userA")
	if err != nil {
		t.Fatal(err)
	}
	if conv.Participants[SideA].UserID != "userA" {
		t.Errorf("甲 should be the active user (userA)")
	}
	if conv.Participants[SideB].UserID != "userB" {
		t.Errorf("乙 should be the passive user (userB)")
	}
	if conv.Participants[SideB].Name != "Bob" || conv.Participants[SideB].SystemPrompt != "I am Bob" {
		t.Errorf("乙 persona should come from the passive profile: %+v", conv.Participants[SideB])
	}
	if conv.Topic != "我们聊点啥" || conv.MaxRounds != 7 || conv.DelayMs != 600 {
		t.Errorf("topic/rounds/delay should come from the passive profile: %+v", conv)
	}
	if !conv.Paired() {
		t.Errorf("a passive-started conversation should be paired immediately")
	}
}

func TestPassiveHandleNormalisationAndValidation(t *testing.T) {
	m := newManager(newMemConfig())

	// Handles fold to lower case, so "Alice" cannot masquerade as "alice".
	if err := m.SetPassiveProfile("userA", true, "  Alice  ", "Alice", "x", "", 12, 1500); err != nil {
		t.Fatal(err)
	}
	p, _ := m.GetPassiveProfile("userA")
	if p.Handle != "alice" {
		t.Errorf("handle = %q, want normalised to %q", p.Handle, "alice")
	}
	if err := m.SetPassiveProfile("userB", true, "ALICE", "Bob", "x", "", 12, 1500); err != errHandleTaken {
		t.Errorf("case-different handle should collide, got %v", err)
	}
	if uid, err := m.ResolvePassive("ALICE"); err != nil || uid != "userA" {
		t.Errorf("resolve should be case-insensitive, got %q err %v", uid, err)
	}

	for _, bad := range []string{"a", "hasupper_已经归一化了但含中文", "has space", "bad!char", strings.Repeat("x", 33)} {
		if err := m.SetPassiveProfile("userC", true, bad, "C", "x", "", 12, 1500); err != errBadHandle {
			t.Errorf("handle %q should be rejected, got %v", bad, err)
		}
	}

	// A handle is optional.
	if err := m.SetPassiveProfile("userD", true, "", "D", "x", "", 12, 1500); err != nil {
		t.Errorf("empty handle should be allowed: %v", err)
	}

	// Turning the switch off must not release the name.
	if err := m.SetPassiveProfile("userA", false, "alice", "Alice", "x", "", 12, 1500); err != nil {
		t.Fatal(err)
	}
	if err := m.SetPassiveProfile("userB", true, "alice", "Bob", "x", "", 12, 1500); err != errHandleTaken {
		t.Errorf("a disabled profile should keep its handle, got %v", err)
	}
}

func TestStartPassiveErrors(t *testing.T) {
	m := newManager(newMemConfig())

	// Disabled passive user.
	m.SetPassiveProfile("userB", false, "bob", "Bob", "x", "", 12, 1500)
	if _, err := m.StartPassive("bob", "userA"); err != errPassiveDisabled {
		t.Errorf("disabled: want errPassiveDisabled, got %v", err)
	}

	// Unknown handle. This reports "找不到该用户" rather than the generic
	// errNotFound ("对聊会话不存在"), which would misdescribe a mistyped handle.
	if _, err := m.StartPassive("ghost", "userA"); err != errPassiveNotFound {
		t.Errorf("unknown: want errPassiveNotFound, got %v", err)
	}

	// Cannot start a passive chat with yourself.
	m.SetPassiveProfile("userB", true, "bob", "Bob", "x", "", 12, 1500)
	if _, err := m.StartPassive("bob", "userB"); err != errSameUser {
		t.Errorf("self: want errSameUser, got %v", err)
	}
}
