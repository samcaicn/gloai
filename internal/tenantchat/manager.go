// Package tenantchat implements the "甲乙方 AI 对聊" builtin app.
//
// Design (per product requirement):
//   - 甲 and 乙 are two REAL tenants — each is a scanned iLink user (a real
//     Hub user account). They hold a cross-tenant AI conversation.
//   - Each tenant only configures their OWN seat (name + system prompt /
//     persona). They never touch the OpenAI credentials.
//   - The actual LLM call always goes through the platform's system OpenAI
//     interface, i.e. the global `ai.` configuration (base URL, API key,
//     model, custom headers). The two tenants share that single system
//     interface; only their personas differ.
//   - Every conversation is persisted (as a JSON blob in the system config
//     store) so each tenant's parameters survive restarts and stay isolated
//     from other tenants' conversations.
package tenantchat

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log/slog"
	"math"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/ceoadmin/CEOadmin/internal/ai"
	"github.com/ceoadmin/CEOadmin/internal/coldstore"
	"github.com/ceoadmin/CEOadmin/internal/memory"
	"github.com/ceoadmin/CEOadmin/internal/store"
)

// configStore is the minimal storage surface the manager depends on. Using a
// narrow interface keeps the manager decoupled and easy to test. Conversations
// are persisted as JSON blobs under a stable key prefix, which works for both
// the SQLite and PostgreSQL backends without schema migrations.
type configStore interface {
	GetConfig(key string) (string, error)
	SetConfig(key, value string) error
	DeleteConfig(key string) error
	ListConfigByPrefix(prefix string) (map[string]string, error)
}

const (
	convKeyPrefix = "tenantchat:conv:"
	indexKey      = "tenantchat:index"
	passivePrefix = "tenantchat:passive:"
)

func passiveKey(uid string) string { return passivePrefix + uid }

// aiComplete performs the LLM call. It is a package-level variable so tests
// can substitute a fake implementation without a real AI provider.
var aiComplete = ai.CompleteMessages

// SetAICompletion overrides the LLM call used by the manager. Intended for tests.
func SetAICompletion(fn func(context.Context, store.AIConfig, []ai.Message, []ai.Tool) (*ai.CompletionResult, error)) {
	aiComplete = fn
}

// aiEmbed turns text into vectors. Like aiComplete it is a package-level
// variable so tests can run the hot/cold search path without a real embedding
// endpoint.
var aiEmbed = ai.Embed

// SetAIEmbedding overrides the embedding call used by the manager. Intended for
// tests; pass nil to restore the real implementation.
func SetAIEmbedding(fn func(context.Context, store.AIConfig, []string) ([][]float32, error)) {
	if fn == nil {
		fn = ai.Embed
	}
	aiEmbed = fn
}

// Side identifies which tenant is speaking. A = 甲, B = 乙.
type Side string

const (
	SideA Side = "A" // 甲租户
	SideB Side = "B" // 乙租户
)

// Status is the run state of the conversation.
type Status string

const (
	StatusWaiting Status = "waiting" // 仅甲已就位，等待乙加入
	StatusIdle    Status = "idle"
	StatusRunning Status = "running"
	StatusPaused  Status = "paused"
	StatusError   Status = "error"
)

// Participant is one tenant's seat in the conversation. UserID ties the seat to
// a real scanned iLink user (the tenant). It is empty until the seat is claimed.
type Participant struct {
	Name         string   `json:"name"`
	SystemPrompt string   `json:"system_prompt"`
	UserID       string   `json:"user_id"` // real iLink user (tenant) that owns this seat; "" if unclaimed
	Joined       bool     `json:"joined"`
	Tags         []string `json:"tags,omitempty"` // 租户标签
}

// Message is a single turn in the conversation.
//
// Embedding and Thinking are the "heavy" columns: they dominate the size of the
// hot SQLite blob. Once a message is durable in the cold tier and older than
// the retention window, those columns are shed from the hot copy (see TrimHot)
// and Archived is set — the text stays hot for history rendering, the vector is
// served from object storage.
type Message struct {
	Seq       int       `json:"seq"`
	Side      Side      `json:"side"`
	Content   string    `json:"content"`
	Thinking  string    `json:"thinking,omitempty"`
	Embedding []float32 `json:"embedding,omitempty"` // query embedding for vector search
	Archived  bool      `json:"archived,omitempty"`  // heavy columns now live only in object storage
	CreatedAt int64     `json:"created_at"`
}

// Conversation is the full serializable state of one 甲乙方对聊 session.
type Conversation struct {
	ID           string                `json:"id"`
	Participants map[Side]*Participant `json:"participants"`
	Messages     []Message             `json:"messages"`
	Status       Status                `json:"status"`
	Topic        string                `json:"topic"`
	MaxRounds    int                   `json:"max_rounds"`
	DelayMs      int                   `json:"delay_ms"`
	Turn         Side                  `json:"turn"`
	RoundCount   int                   `json:"round_count"`
	Error        string                `json:"error,omitempty"`
	InviteCode   string                `json:"invite_code,omitempty"`
	CreatedBy    string                `json:"created_by"`
	CreatedAt    int64                 `json:"created_at"`
	UpdatedAt    int64                 `json:"updated_at"`
}

// IsParticipant reports whether the given user owns any seat.
func (c *Conversation) IsParticipant(userID string) bool {
	if userID == "" {
		return false
	}
	for _, p := range c.Participants {
		if p != nil && p.UserID == userID {
			return true
		}
	}
	return false
}

// SideOf returns the seat owned by the given user, if any.
func (c *Conversation) SideOf(userID string) (Side, bool) {
	for side, p := range c.Participants {
		if p != nil && p.UserID == userID {
			return side, true
		}
	}
	return "", false
}

// Paired reports whether both seats are claimed (甲 and 乙 both joined).
func (c *Conversation) Paired() bool {
	a, okA := c.Participants[SideA]
	b, okB := c.Participants[SideB]
	return okA && okB && a.UserID != "" && b.UserID != "" && a.Joined && b.Joined
}

// PassiveProfile is a tenant's published settings for "被动会话" — the
// parameters used when another user starts a chat with them (别人找你聊).
// Enabling the switch is all it takes to opt in; there is no passcode. The
// passive user never touches the OpenAI credentials; they only set their own
// persona and per-session defaults.
type PassiveProfile struct {
	UserID string `json:"user_id"`
	// Handle is an optional, globally unique short name others use to find
	// this tenant, so nobody has to paste a raw user id around. It is
	// normalised to lower case, because a handle that differs from another
	// only by case is an impersonation vector, not a distinct identity.
	Handle       string `json:"handle"`
	Enabled      bool   `json:"enabled"` // 是否允许别人找我聊
	Name         string `json:"name"`    // 被动会话中展示给对方看的名称
	SystemPrompt string `json:"system_prompt"`
	Topic        string `json:"topic"`
	MaxRounds    int    `json:"max_rounds"`
	DelayMs      int    `json:"delay_ms"`
	UpdatedAt    int64  `json:"updated_at"`
}

// convRuntime holds a conversation plus its run-loop control primitives.
type convRuntime struct {
	mu     sync.Mutex
	conv   *Conversation
	cancel context.CancelFunc
	stopCh chan struct{}
}

func (rt *convRuntime) stopLoopLocked() {
	if rt.cancel != nil {
		rt.cancel()
		rt.cancel = nil
	}
	if rt.stopCh != nil {
		close(rt.stopCh)
		rt.stopCh = nil
	}
}

// Manager is the registry of all 甲乙方对聊 conversations for the process.
type Manager struct {
	mu    sync.Mutex
	store configStore
	convs map[string]*convRuntime
	// cold is the optional object-storage tier consulted by SearchMessages for
	// vectors that have been trimmed out of the hot tier. nil = hot-only search.
	cold ColdSearcher
	// memoryStore holds each tenant's personalized memory (profile + memories).
	// When set, a tenant's relevant memories are injected into its system prompt.
	memoryStore memory.Store
}

// Default is the process-wide singleton used by the API handlers.
var Default = &Manager{convs: map[string]*convRuntime{}}

// Init wires the store and loads any persisted conversations. Conversations
// that were mid-run at shutdown are restored as paused (the loop goroutine is
// not restarted automatically).
func (m *Manager) Init(s configStore) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.store = s
	m.convs = map[string]*convRuntime{}
	if m.memoryStore == nil {
		m.memoryStore = memory.NewFileStore(memory.DefaultDir())
	}
	if s == nil {
		return
	}
	raw, _ := s.GetConfig(indexKey)
	var ids []string
	if raw != "" {
		_ = json.Unmarshal([]byte(raw), &ids)
	}
	for _, id := range ids {
		b, err := s.GetConfig(convKey(id))
		if err != nil || b == "" {
			continue
		}
		var conv Conversation
		if json.Unmarshal([]byte(b), &conv) != nil {
			continue
		}
		if conv.Status == StatusRunning {
			// No active loop after restart; pause it.
			conv.Status = StatusPaused
		}
		m.convs[id] = &convRuntime{conv: &conv}
	}
}

func convKey(id string) string { return convKeyPrefix + id }

func newID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func newCode() string {
	b := make([]byte, 4)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// ---- Queries ----

// Get returns a snapshot of a conversation by id.
func (m *Manager) Get(id string) (*Conversation, bool) {
	m.mu.Lock()
	rt, ok := m.convs[id]
	m.mu.Unlock()
	if !ok {
		return nil, false
	}
	rt.mu.Lock()
	cp := cloneConv(rt.conv)
	rt.mu.Unlock()
	return cp, true
}

// ConversationsForUser returns all conversations the given user participates in
// (a user may be in several — e.g. one active + several passive chats).
func (m *Manager) ConversationsForUser(userID string) ([]*Conversation, error) {
	if userID == "" {
		return nil, errNoUser
	}
	m.mu.Lock()
	rts := make([]*convRuntime, 0, len(m.convs))
	for _, rt := range m.convs {
		rts = append(rts, rt)
	}
	m.mu.Unlock()
	var out []*Conversation
	for _, rt := range rts {
		rt.mu.Lock()
		part := rt.conv.IsParticipant(userID)
		rt.mu.Unlock()
		if part {
			rt.mu.Lock()
			out = append(out, cloneConv(rt.conv))
			rt.mu.Unlock()
		}
	}
	return out, nil
}

// GlobalAIConfigured reports whether the system OpenAI interface is usable.
func (m *Manager) GlobalAIConfigured() bool {
	_, err := m.globalAIConfig()
	return err == nil
}

// ---- Mutations ----

// Create starts a new active conversation. The calling user becomes 甲 (SideA)
// and receives an invite code to share with the 乙 user.
func (m *Manager) Create(userID string) (*Conversation, error) {
	if userID == "" {
		return nil, errNoUser
	}
	now := time.Now().Unix()
	conv := &Conversation{
		ID: newID(),
		Participants: map[Side]*Participant{
			SideA: {
				Name:         "甲（我）",
				SystemPrompt: defaultPromptA,
				UserID:       userID,
				Joined:       true,
			},
			SideB: {
				Name:         "乙（对方）",
				SystemPrompt: defaultPromptB,
				UserID:       "",
				Joined:       false,
			},
		},
		Status:     StatusWaiting,
		Topic:      defaultTopic,
		MaxRounds:  12,
		DelayMs:    1500,
		Turn:       SideA,
		CreatedBy:  userID,
		CreatedAt:  now,
		UpdatedAt:  now,
		InviteCode: newCode(),
	}
	rt := &convRuntime{conv: conv}
	m.mu.Lock()
	m.convs[conv.ID] = rt
	m.mu.Unlock()

	if err := m.persist(rt); err != nil {
		return nil, err
	}
	return cloneConv(conv), nil
}

// Join lets the calling user claim the 乙 (SideB) seat using the invite code.
func (m *Manager) Join(id, code, userID string) (*Conversation, error) {
	if userID == "" {
		return nil, errNoUser
	}
	m.mu.Lock()
	rt, ok := m.convs[id]
	m.mu.Unlock()
	if !ok {
		return nil, errNotFound
	}
	rt.mu.Lock()
	b := rt.conv.Participants[SideB]
	if b == nil || b.UserID != "" {
		rt.mu.Unlock()
		return nil, errSeatTaken
	}
	if rt.conv.InviteCode == "" || rt.conv.InviteCode != code {
		rt.mu.Unlock()
		return nil, errBadCode
	}
	if rt.conv.Participants[SideA].UserID == userID {
		rt.mu.Unlock()
		return nil, errSameUser
	}
	b.UserID = userID
	b.Joined = true
	rt.conv.Status = StatusIdle
	rt.conv.InviteCode = "" // single-use: one pair per conversation
	rt.conv.UpdatedAt = time.Now().Unix()
	rt.mu.Unlock()

	if err := m.persist(rt); err != nil {
		return nil, err
	}
	rt.mu.Lock()
	cp := cloneConv(rt.conv)
	rt.mu.Unlock()
	return cp, nil
}

// ---- Passive session (别人找你聊) ----

// GetPassiveProfile returns the passive-session settings for a user. A missing
// profile is returned as an empty (disabled) profile rather than an error.
func (m *Manager) GetPassiveProfile(userID string) (*PassiveProfile, error) {
	if userID == "" {
		return nil, errNoUser
	}
	if m.store == nil {
		return nil, errNoStore
	}
	raw, err := m.store.GetConfig(passiveKey(userID))
	if err != nil {
		return nil, err
	}
	if raw == "" {
		return &PassiveProfile{UserID: userID}, nil
	}
	var p PassiveProfile
	if json.Unmarshal([]byte(raw), &p) != nil {
		return &PassiveProfile{UserID: userID}, nil
	}
	return &p, nil
}

// SetPassiveProfile saves a user's passive-session settings. Enabling the
// switch is sufficient to opt in; no passcode is required.
//
// The handle is optional but must be globally unique when set. Uniqueness is
// checked against every stored profile, including disabled ones — toggling
// "别人找你聊" off must not quietly release your name for someone else to take.
func (m *Manager) SetPassiveProfile(userID string, enabled bool, handle, name, prompt, topic string, maxRounds, delayMs int) error {
	if userID == "" {
		return errNoUser
	}
	if m.store == nil {
		return errNoStore
	}
	if maxRounds < 0 || maxRounds > 200 {
		return errBadBounds
	}
	if delayMs < 0 || delayMs > 30000 {
		return errBadBounds
	}
	handle = normalizeHandle(handle)
	if handle != "" && !validHandle(handle) {
		return errBadHandle
	}
	if handle != "" {
		owner, err := m.userByHandle(handle)
		if err != nil {
			return err
		}
		if owner != "" && owner != userID {
			return errHandleTaken
		}
	}
	p := PassiveProfile{
		UserID:       userID,
		Handle:       handle,
		Enabled:      enabled,
		Name:         name,
		SystemPrompt: prompt,
		Topic:        topic,
		MaxRounds:    maxRounds,
		DelayMs:      delayMs,
		UpdatedAt:    time.Now().Unix(),
	}
	b, _ := json.Marshal(p)
	return m.store.SetConfig(passiveKey(userID), string(b))
}

// normalizeHandle folds a handle to its canonical form.
func normalizeHandle(h string) string { return strings.ToLower(strings.TrimSpace(h)) }

// validHandle keeps handles URL- and eyeball-safe.
func validHandle(h string) bool {
	if len(h) < 2 || len(h) > 32 {
		return false
	}
	for _, r := range h {
		switch {
		case r >= 'a' && r <= 'z', r >= '0' && r <= '9', r == '-', r == '_':
		default:
			return false
		}
	}
	return true
}

// allPassiveProfiles returns every stored profile, enabled or not. The set is
// small (one entry per user who ever configured one), so scanning it is the
// simpler and safer alternative to maintaining a separate handle index that
// could drift out of sync with the profiles themselves.
func (m *Manager) allPassiveProfiles() ([]PassiveProfile, error) {
	if m.store == nil {
		return nil, errNoStore
	}
	raw, err := m.store.ListConfigByPrefix(passivePrefix)
	if err != nil {
		return nil, err
	}
	out := make([]PassiveProfile, 0, len(raw))
	for _, v := range raw {
		var p PassiveProfile
		if json.Unmarshal([]byte(v), &p) != nil {
			continue
		}
		out = append(out, p)
	}
	return out, nil
}

// userByHandle returns the id of the user holding the handle, or "" if free.
func (m *Manager) userByHandle(handle string) (string, error) {
	profiles, err := m.allPassiveProfiles()
	if err != nil {
		return "", err
	}
	for _, p := range profiles {
		if p.Handle != "" && p.Handle == handle {
			return p.UserID, nil
		}
	}
	return "", nil
}

// ResolvePassive maps a handle to the user id behind it, falling back to
// treating the input as a raw user id so links built before handles existed
// keep working.
func (m *Manager) ResolvePassive(handleOrUID string) (string, error) {
	if handleOrUID == "" {
		return "", errNoUser
	}
	if uid, err := m.userByHandle(normalizeHandle(handleOrUID)); err != nil {
		return "", err
	} else if uid != "" {
		return uid, nil
	}
	// Fallback: an id that actually has a profile.
	raw, err := m.store.GetConfig(passiveKey(handleOrUID))
	if err != nil {
		return "", err
	}
	if raw == "" {
		return "", errPassiveNotFound
	}
	return handleOrUID, nil
}

// ListPassiveUsers returns all users that have enabled passive chat, so a
// separate "find someone to chat with" page can discover and initiate them.
func (m *Manager) ListPassiveUsers() ([]PassiveProfile, error) {
	if m.store == nil {
		return nil, errNoStore
	}
	raw, err := m.store.ListConfigByPrefix(passivePrefix)
	if err != nil {
		return nil, err
	}
	out := make([]PassiveProfile, 0, len(raw))
	for _, v := range raw {
		var p PassiveProfile
		if json.Unmarshal([]byte(v), &p) != nil {
			continue
		}
		if p.Enabled {
			out = append(out, p)
		}
	}
	return out, nil
}

// StartPassive lets an active user start a chat with a passive user
// (别人找你聊). The target is given as a handle, or as a raw user id for
// callers that already have one. The caller becomes 甲 (SideA); the passive
// user is 乙 (SideB) and their seat is pre-filled from their published passive
// profile. The conversation is paired immediately, so it can run at once.
func (m *Manager) StartPassive(target, activeUID string) (*Conversation, error) {
	if activeUID == "" || target == "" {
		return nil, errNoUser
	}
	targetUID, err := m.ResolvePassive(target)
	if err != nil {
		return nil, err
	}
	if targetUID == activeUID {
		return nil, errSameUser
	}
	prof, err := m.GetPassiveProfile(targetUID)
	if err != nil {
		return nil, err
	}
	if !prof.Enabled {
		return nil, errPassiveDisabled
	}
	name := prof.Name
	if name == "" {
		name = "对方"
	}
	prompt := prof.SystemPrompt
	if prompt == "" {
		prompt = defaultPromptB
	}
	topic := prof.Topic
	if topic == "" {
		topic = defaultTopic
	}
	maxRounds := prof.MaxRounds
	if maxRounds <= 0 {
		maxRounds = 12
	}
	delayMs := prof.DelayMs
	if delayMs < 0 {
		delayMs = 1500
	}
	now := time.Now().Unix()
	conv := &Conversation{
		ID: newID(),
		Participants: map[Side]*Participant{
			SideA: {
				Name:         "甲（我）",
				SystemPrompt: defaultPromptA,
				UserID:       activeUID,
				Joined:       true,
			},
			SideB: {
				Name:         name,
				SystemPrompt: prompt,
				UserID:       targetUID,
				Joined:       true,
			},
		},
		Status:    StatusIdle,
		Topic:     topic,
		MaxRounds: maxRounds,
		DelayMs:   delayMs,
		Turn:      SideA,
		CreatedBy: activeUID,
		CreatedAt: now,
		UpdatedAt: now,
	}
	rt := &convRuntime{conv: conv}
	m.mu.Lock()
	m.convs[conv.ID] = rt
	m.mu.Unlock()
	if err := m.persist(rt); err != nil {
		return nil, err
	}
	return cloneConv(conv), nil
}

// SetOwnPersona updates ONLY the seat owned by the calling user. This is how
// "每个租户只设置自己的参数" is enforced at the data layer: a tenant can never
// modify the other party's persona.
func (m *Manager) SetOwnPersona(id, userID, name, prompt string) error {
	if userID == "" {
		return errNoUser
	}
	m.mu.Lock()
	rt, ok := m.convs[id]
	if !ok {
		m.mu.Unlock()
		return errNotFound
	}
	side, ok := rt.conv.SideOf(userID)
	if !ok {
		m.mu.Unlock()
		return errNotParticipant
	}
	rt.mu.Lock()
	p := rt.conv.Participants[side]
	if name != "" {
		p.Name = name
	}
	if prompt != "" {
		p.SystemPrompt = prompt
	}
	rt.conv.UpdatedAt = time.Now().Unix()
	rt.mu.Unlock()
	m.mu.Unlock()

	return m.persist(rt)
}

// SetConfig updates the topic / round / delay settings shared by the pair.
func (m *Manager) SetConfig(id, topic string, maxRounds, delayMs int) error {
	m.mu.Lock()
	rt, ok := m.convs[id]
	if !ok {
		m.mu.Unlock()
		return errNotFound
	}
	rt.mu.Lock()
	if topic != "" {
		rt.conv.Topic = topic
	}
	if maxRounds > 0 {
		rt.conv.MaxRounds = maxRounds
	}
	if delayMs >= 0 {
		rt.conv.DelayMs = delayMs
	}
	rt.conv.UpdatedAt = time.Now().Unix()
	rt.mu.Unlock()
	m.mu.Unlock()

	return m.persist(rt)
}

// Start begins the auto-running conversation loop (requires both tenants).
func (m *Manager) Start(id string) error {
	m.mu.Lock()
	rt, ok := m.convs[id]
	if !ok {
		m.mu.Unlock()
		return errNotFound
	}
	rt.mu.Lock()
	if rt.conv.Status == StatusRunning {
		rt.mu.Unlock()
		m.mu.Unlock()
		return nil
	}
	if !rt.conv.Paired() {
		rt.mu.Unlock()
		m.mu.Unlock()
		return errNotPaired
	}
	if !m.globalAIConfigured() {
		rt.mu.Unlock()
		m.mu.Unlock()
		return errNoAI
	}
	rt.stopLoopLocked()
	ctx, cancel := context.WithCancel(context.Background())
	rt.cancel = cancel
	rt.stopCh = make(chan struct{})
	rt.conv.Status = StatusRunning
	rt.conv.Error = ""
	stopCh := rt.stopCh
	rt.mu.Unlock()
	m.mu.Unlock()

	go m.loop(rt, ctx, stopCh)
	return nil
}

// Pause stops the auto loop but keeps the conversation.
func (m *Manager) Pause(id string) {
	m.mu.Lock()
	rt, ok := m.convs[id]
	m.mu.Unlock()
	if !ok {
		return
	}
	rt.mu.Lock()
	if rt.conv.Status == StatusRunning {
		rt.stopLoopLocked()
		rt.conv.Status = StatusPaused
	}
	rt.mu.Unlock()
	_ = m.persist(rt)
}

// Step generates exactly one reply from the current turn's tenant.
func (m *Manager) Step(id string) error {
	m.mu.Lock()
	rt, ok := m.convs[id]
	m.mu.Unlock()
	if !ok {
		return errNotFound
	}
	rt.mu.Lock()
	if rt.conv.Status == StatusRunning {
		rt.mu.Unlock()
		return errRunning
	}
	if !rt.conv.Paired() {
		rt.mu.Unlock()
		return errNotPaired
	}
	if !m.globalAIConfigured() {
		rt.mu.Unlock()
		return errNoAI
	}
	rt.mu.Unlock()

	if err := m.stepOnce(rt); err != nil {
		rt.mu.Lock()
		rt.conv.Status = StatusError
		rt.conv.Error = err.Error()
		rt.mu.Unlock()
		_ = m.persist(rt)
		return err
	}
	rt.mu.Lock()
	if rt.conv.Status != StatusError {
		rt.conv.Status = StatusIdle
	}
	rt.mu.Unlock()
	_ = m.persist(rt)
	return nil
}

// Reset clears the conversation and returns to idle.
func (m *Manager) Reset(id string) {
	m.mu.Lock()
	rt, ok := m.convs[id]
	m.mu.Unlock()
	if !ok {
		return
	}
	rt.mu.Lock()
	rt.stopLoopLocked()
	rt.conv.Messages = nil
	rt.conv.RoundCount = 0
	rt.conv.Turn = SideA
	rt.conv.Status = StatusIdle
	rt.conv.Error = ""
	rt.mu.Unlock()
	_ = m.persist(rt)
}

// ---- Run loop ----

func (m *Manager) loop(rt *convRuntime, ctx context.Context, stopCh chan struct{}) {
	for {
		rt.mu.Lock()
		if rt.conv.Status != StatusRunning {
			rt.mu.Unlock()
			return
		}
		if rt.conv.MaxRounds > 0 && rt.conv.RoundCount >= rt.conv.MaxRounds {
			rt.conv.Status = StatusPaused
			rt.mu.Unlock()
			_ = m.persist(rt)
			return
		}
		delay := rt.conv.DelayMs
		rt.mu.Unlock()

		if err := m.stepOnce(rt); err != nil {
			rt.mu.Lock()
			rt.conv.Status = StatusError
			rt.conv.Error = err.Error()
			rt.mu.Unlock()
			_ = m.persist(rt)
			return
		}

		rt.mu.Lock()
		rt.conv.RoundCount++
		rt.mu.Unlock()
		_ = m.persist(rt)

		select {
		case <-ctx.Done():
			return
		case <-stopCh:
			return
		case <-time.After(time.Duration(delay) * time.Millisecond):
		}
	}
}

// stepOnce generates one message from the current turn's tenant and flips the
// turn. The LLM call uses the system OpenAI interface (global `ai.` config).
func (m *Manager) stepOnce(rt *convRuntime) error {
	rt.mu.Lock()
	side := rt.conv.Turn
	participant := rt.conv.Participants[side]
	if participant == nil {
		rt.mu.Unlock()
		return errNoParticipant
	}
	messages := rt.conv.Messages
	topic := rt.conv.Topic
	rt.mu.Unlock()

	cfg, err := m.globalAIConfig()
	if err != nil {
		return err
	}

	sysBase := participant.SystemPrompt
	// 注入本租户的个性化记忆（向量召回最相关 top-K），实现个性化回复。
	if memCtx := m.memorySystemPrompt(participant.UserID, messages, topic); memCtx != "" {
		sysBase += "\n\n" + memCtx
	}
	msgs := []ai.Message{{Role: "system", Content: sysBase}}

	firstMessage := len(messages) == 0
	for _, msg := range messages {
		if msg.Side == side {
			msgs = append(msgs, ai.Message{Role: "assistant", Content: msg.Content})
		} else {
			msgs = append(msgs, ai.Message{Role: "user", Content: msg.Content})
		}
	}
	if firstMessage {
		msgs = append(msgs, ai.Message{
			Role: "user",
			Content: "【开场白】今天想聊的话题是：「" + topic + "」。请你以" + participant.Name +
				"的身份，主动说第一句话，自然地开启这场对话（1~3 句即可）。",
		})
	}

	result, err := aiComplete(ai.ContextWithMeta(context.Background(), rt.conv.ID, rt.conv.ID), cfg, msgs, nil)
	if err != nil {
		return err
	}
	content := strings.TrimSpace(result.Content)
	if content == "" {
		content = "（对方思考了一下，没有说话）"
	}

	// Best-effort embedding for vector search; silently skipped if the AI
	// interface / embedding endpoint is unavailable (e.g. in tests).
	var emb []float32
	if cfg, e := m.globalAIConfig(); e == nil {
		if vecs, e2 := aiEmbed(ai.ContextWithMeta(context.Background(), rt.conv.ID, rt.conv.ID), cfg, []string{content}); e2 == nil && len(vecs) > 0 {
			emb = vecs[0]
		}
	}

	rt.mu.Lock()
	defer rt.mu.Unlock()
	seq := len(rt.conv.Messages) + 1
	rt.conv.Messages = append(rt.conv.Messages, Message{
		Seq:       seq,
		Side:      side,
		Content:   content,
		Thinking:  result.Thinking,
		Embedding: emb,
		CreatedAt: time.Now().Unix(),
	})
	rt.conv.Turn = otherSide(side)
	return nil
}

func otherSide(s Side) Side {
	if s == SideA {
		return SideB
	}
	return SideA
}

// ---- Persistence ----

// cloneConv returns a deep-ish copy safe to hand to callers without holding a
// lock (participants map and messages slice are copied).
func cloneConv(c *Conversation) *Conversation {
	cp := *c
	if c.Participants != nil {
		cp.Participants = make(map[Side]*Participant, len(c.Participants))
		for k, v := range c.Participants {
			if v != nil {
				p := *v
				cp.Participants[k] = &p
			}
		}
	}
	if c.Messages != nil {
		cp.Messages = make([]Message, len(c.Messages))
		copy(cp.Messages, c.Messages)
	}
	return &cp
}

// persist writes the conversation blob and refreshes the index. It does not
// hold any locks while touching the store.
func (m *Manager) persist(rt *convRuntime) error {
	rt.mu.Lock()
	b, err := json.Marshal(rt.conv)
	rt.mu.Unlock()
	if err != nil {
		return err
	}
	if err := m.store.SetConfig(convKey(rt.conv.ID), string(b)); err != nil {
		return err
	}
	return m.saveIndex()
}

func (m *Manager) saveIndex() error {
	m.mu.Lock()
	ids := make([]string, 0, len(m.convs))
	for id := range m.convs {
		ids = append(ids, id)
	}
	m.mu.Unlock()
	sort.Strings(ids)
	b, _ := json.Marshal(ids)
	return m.store.SetConfig(indexKey, string(b))
}

// ---- System OpenAI interface ----

func (m *Manager) globalAIConfigured() bool {
	_, err := m.globalAIConfig()
	return err == nil
}

// globalAIConfig builds a store.AIConfig from the platform's system OpenAI
// interface so both tenants share the same system LLM; only their persona
// differs. It prefers the operator-configured global `ai.*` settings, and when
// those are absent falls back to the platform's unified system LLM interface
// (ACC_PRODUCT_CONFIG_V2) — the very one the tenant-memory sidecar uses — so
// the Hub's tenants and the application reach the identical system LLM.
func (m *Manager) globalAIConfig() (store.AIConfig, error) {
	if m.store == nil {
		return store.AIConfig{}, errNoStore
	}
	cfg, ok := ai.ResolveSystemAIConfig(m.store.ListConfigByPrefix)
	if !ok {
		return store.AIConfig{}, errNoAI
	}
	return cfg, nil
}

const (
	defaultTopic   = "AI 到底能不能帮企业真正降本增效？"
	defaultPromptA = "你是「甲」的 AI 助手。你是一家科技公司的产品负责人，说话干练、注重落地与数据，喜欢用结构化方式表达观点。请始终以甲的身份、用第一人称与对方自然对话。"
	defaultPromptB = "你是「乙」的 AI 助手。你是一家传统制造企业的运营总监，务实、稳健，关注成本与风险，偶尔带点幽默。请始终以乙的身份、用第一人称与对方自然对话。"
)

// ---- Tenant tags (租户标签) ----

// SetOwnTags sets the tags for the seat owned by the calling user. A tenant can
// only tag their own seat, never the counterpart's.
func (m *Manager) SetOwnTags(id, userID string, tags []string) error {
	if userID == "" {
		return errNoUser
	}
	m.mu.Lock()
	rt, ok := m.convs[id]
	if !ok {
		m.mu.Unlock()
		return errNotFound
	}
	side, ok := rt.conv.SideOf(userID)
	if !ok {
		m.mu.Unlock()
		return errNotParticipant
	}
	rt.mu.Lock()
	if p := rt.conv.Participants[side]; p != nil {
		p.Tags = tags
	}
	rt.conv.UpdatedAt = time.Now().Unix()
	rt.mu.Unlock()
	m.mu.Unlock()
	return m.persist(rt)
}

// ---- Vector search over chat messages ----

// SearchHit is a single match returned by SearchMessages. Tier records whether
// the vector was scored in memory ("hot") or read back from object storage ("cold").
type SearchHit struct {
	ConvID     string  `json:"conv_id"`
	Seq        int     `json:"seq"`
	Side       Side    `json:"side"`
	Content    string  `json:"content"`
	Similarity float32 `json:"similarity"`
	Tier       string  `json:"tier"`
}

// Tier values reported by SearchHit.
const (
	TierHot  = "hot"
	TierCold = "cold"
)

// SearchMessages performs a cosine-similarity nearest-neighbour search over the
// calling tenant's chat messages across BOTH tiers: the in-memory hot tier and,
// when configured, the partitioned dataset on object storage. Requires the global AI
// interface to be configured (used to embed the query). Returns up to k hits,
// best first.
//
// The two tiers are disjoint by construction — a message keeps its embedding in
// memory until TrimHot sheds it, after which object storage holds the only copy — but results
// are still de-duplicated on (conv_id, seq), with the hot tier winning, so an
// overlap during the export window cannot produce doubles.
func (m *Manager) SearchMessages(tenantID, query string, k int) ([]SearchHit, error) {
	if tenantID == "" {
		return nil, errNoUser
	}
	if k <= 0 {
		k = 5
	}
	qvec, err := m.embedQuery(query)
	if err != nil {
		return nil, err
	}

	m.mu.Lock()
	rts := make([]*convRuntime, 0, len(m.convs))
	for _, rt := range m.convs {
		rts = append(rts, rt)
	}
	cold := m.cold
	m.mu.Unlock()

	var results []SearchHit
	seen := map[string]struct{}{}
	var convIDs []string

	// --- hot tier: score the vectors still resident in memory ---
	for _, rt := range rts {
		rt.mu.Lock()
		part := rt.conv.IsParticipant(tenantID)
		msgs := rt.conv.Messages
		cid := rt.conv.ID
		rt.mu.Unlock()
		if !part {
			continue
		}
		convIDs = append(convIDs, cid)
		for _, msg := range msgs {
			if len(msg.Embedding) == 0 {
				continue // never embedded, or already trimmed to the cold tier
			}
			sim, ok := cosine(qvec, msg.Embedding)
			if !ok {
				continue
			}
			seen[hitKey(cid, msg.Seq)] = struct{}{}
			results = append(results, SearchHit{
				ConvID: cid, Seq: msg.Seq, Side: msg.Side,
				Content: msg.Content, Similarity: sim, Tier: TierHot,
			})
		}
	}

	// --- cold tier: fall through to object storage for everything already trimmed ---
	if cold != nil && len(convIDs) > 0 {
		q := coldstore.Query{ConvIDs: convIDs, TenantID: tenantID}
		hits, err := cold.SearchVector(context.Background(), qvec, q, k)
		if err != nil {
			// A cold tier hiccup must not fail an otherwise good hot search.
			slog.Warn("tenantchat: cold tier search failed", "err", err)
		}
		for _, h := range hits {
			if _, dup := seen[hitKey(h.Row.ConvID, h.Row.Seq)]; dup {
				continue
			}
			results = append(results, SearchHit{
				ConvID: h.Row.ConvID, Seq: h.Row.Seq, Side: Side(h.Row.Side),
				Content: h.Row.Content, Similarity: h.Similarity, Tier: TierCold,
			})
		}
	}

	sort.Slice(results, func(i, j int) bool {
		if results[i].Similarity != results[j].Similarity {
			return results[i].Similarity > results[j].Similarity
		}
		if results[i].ConvID != results[j].ConvID {
			return results[i].ConvID < results[j].ConvID
		}
		return results[i].Seq < results[j].Seq
	})
	if len(results) > k {
		results = results[:k]
	}
	return results, nil
}

func hitKey(conv string, seq int) string { return conv + "#" + strconv.Itoa(seq) }

// embedQuery turns free text into a vector using the global AI embedding API.
func (m *Manager) embedQuery(text string) ([]float32, error) {
	cfg, err := m.globalAIConfig()
	if err != nil {
		return nil, fmt.Errorf("embeddings unavailable: %w", err)
	}
	vecs, err := aiEmbed(ai.ContextWithMeta(context.Background(), "tenantchat", ""), cfg, []string{text})
	if err != nil {
		return nil, err
	}
	if len(vecs) == 0 || len(vecs[0]) == 0 {
		return nil, fmt.Errorf("embeddings: empty vector returned")
	}
	return vecs[0], nil
}

// cosine returns the cosine similarity of two equal-length vectors.
func cosine(a, b []float32) (float32, bool) {
	if len(a) == 0 || len(b) == 0 || len(a) != len(b) {
		return 0, false
	}
	var dot, na, nb float64
	for i := range a {
		dot += float64(a[i]) * float64(b[i])
		na += float64(a[i]) * float64(a[i])
		nb += float64(b[i]) * float64(b[i])
	}
	if na == 0 || nb == 0 {
		return 0, false
	}
	return float32(dot / math.Sqrt(na*nb)), true
}

// ---- Cold tier (object storage, S3-compatible) export & hot/cold tiering ----

// MessagesSince implements coldstore.Source: it returns only the messages a
// conversation has produced beyond its high-water mark, which is what makes the
// the export incremental instead of a full rewrite every tick.
//
// Messages that were already trimmed from the hot tier are skipped — their
// authoritative copy is the cold one, and re-exporting a stripped row would
// overwrite a good part with a vector-less version.
func (m *Manager) MessagesSince(watermarks map[string]int) []coldstore.Row {
	m.mu.Lock()
	rts := make([]*convRuntime, 0, len(m.convs))
	for _, rt := range m.convs {
		rts = append(rts, rt)
	}
	m.mu.Unlock()

	var out []coldstore.Row
	for _, rt := range rts {
		rt.mu.Lock()
		conv := cloneConv(rt.conv)
		rt.mu.Unlock()

		tenantIDs, tenantTags := tenantsOf(conv)
		mark := watermarks[conv.ID]
		for _, msg := range conv.Messages {
			if msg.Seq <= mark || msg.Archived {
				continue
			}
			out = append(out, coldstore.Row{
				ConvID:     conv.ID,
				TenantIDs:  tenantIDs,
				TenantTags: tenantTags,
				Seq:        msg.Seq,
				Side:       string(msg.Side),
				Content:    msg.Content,
				Thinking:   msg.Thinking,
				Embedding:  msg.Embedding,
				CreatedAt:  msg.CreatedAt,
			})
		}
	}
	return out
}

// AllEmbeddedMessages returns every stored chat message, for a full re-export
// or a one-off dump.
func (m *Manager) AllEmbeddedMessages() []coldstore.Row { return m.MessagesSince(nil) }

// TrimHot implements coldstore.HotTrimmer. It sheds the heavy columns
// (embedding + thinking trace) of messages that are simultaneously:
//
//   - durable in object storage (Seq at or below the conversation's watermark), and
//   - older than the retention cut-off.
//
// The message text is deliberately kept, so conversation history still renders
// entirely from the hot tier; only vector search falls through to object storage. Nothing
// is destroyed that is not already in the cold copy, and re-running is a no-op.
func (m *Manager) TrimHot(durable map[string]int, before int64) (int, error) {
	m.mu.Lock()
	rts := make([]*convRuntime, 0, len(m.convs))
	for _, rt := range m.convs {
		rts = append(rts, rt)
	}
	m.mu.Unlock()

	total := 0
	var firstErr error
	for _, rt := range rts {
		rt.mu.Lock()
		mark, ok := durable[rt.conv.ID]
		if !ok {
			rt.mu.Unlock()
			continue
		}
		trimmed, changed := 0, 0
		for i := range rt.conv.Messages {
			msg := &rt.conv.Messages[i]
			if msg.Seq > mark || msg.CreatedAt >= before || msg.Archived {
				continue
			}
			if len(msg.Embedding) > 0 || msg.Thinking != "" {
				msg.Embedding = nil
				msg.Thinking = ""
				trimmed++
			}
			// Mark even when there was nothing heavy to shed, so the message is
			// not re-exported later as a stripped row.
			msg.Archived = true
			changed++
		}
		rt.mu.Unlock()
		if changed == 0 {
			continue
		}
		if err := m.persist(rt); err != nil {
			if firstErr == nil {
				firstErr = err
			}
			continue
		}
		total += trimmed
	}
	return total, firstErr
}

func tenantsOf(conv *Conversation) (ids, tags []string) {
	for _, side := range []Side{SideA, SideB} { // stable order
		p := conv.Participants[side]
		if p == nil {
			continue
		}
		if p.UserID != "" {
			ids = append(ids, p.UserID)
		}
		tags = append(tags, p.Tags...)
	}
	return ids, tags
}

// ColdSearcher is the cold-tier query surface the manager falls back to for
// messages whose vectors have been trimmed from the hot tier.
// *coldstore.Reader implements it.
type ColdSearcher interface {
	SearchVector(ctx context.Context, vec []float32, q coldstore.Query, k int) ([]coldstore.Hit, error)
}

// SetColdSearcher attaches the cold tier to vector search. Passing nil (the
// default) keeps search hot-only.
func (m *Manager) SetColdSearcher(c ColdSearcher) {
	m.mu.Lock()
	m.cold = c
	m.mu.Unlock()
}

// SetMemoryStore attaches the per-tenant memory backend. Passing nil (the
// default) keeps the file-backed store created in Init.
func (m *Manager) SetMemoryStore(s memory.Store) {
	m.mu.Lock()
	m.memoryStore = s
	m.mu.Unlock()
}

// ---- Per-tenant memory (个性化记忆) ----

// EnsureMemoryTenant makes sure the tenant's memory space exists.
func (m *Manager) EnsureMemoryTenant(tenantID string) error {
	if m.memoryStore == nil {
		return errNoStore
	}
	return m.memoryStore.EnsureTenant(tenantID)
}

// GetMemoryProfile returns a tenant's memory profile (name + preferences).
func (m *Manager) GetMemoryProfile(tenantID string) (*memory.Profile, error) {
	if m.memoryStore == nil {
		return nil, errNoStore
	}
	return m.memoryStore.GetProfile(tenantID)
}

// SetMemoryProfile updates a tenant's name / preferences.
func (m *Manager) SetMemoryProfile(tenantID, name string, prefs map[string]string) error {
	if m.memoryStore == nil {
		return errNoStore
	}
	return m.memoryStore.SetProfile(tenantID, name, prefs)
}

// ListMemories returns a tenant's memories, optionally filtered by type.
func (m *Manager) ListMemories(tenantID, typ string) ([]memory.Memory, error) {
	if m.memoryStore == nil {
		return nil, errNoStore
	}
	return m.memoryStore.ListMemories(tenantID, typ)
}

// AddMemory stores a new memory for a tenant.
func (m *Manager) AddMemory(tenantID, typ, content string) (*memory.Memory, error) {
	if m.memoryStore == nil {
		return nil, errNoStore
	}
	return m.memoryStore.AddMemory(tenantID, typ, content)
}

// DeleteMemory removes a memory by id.
func (m *Manager) DeleteMemory(tenantID, mid string) error {
	if m.memoryStore == nil {
		return errNoStore
	}
	return m.memoryStore.DeleteMemory(tenantID, mid)
}

// RetrieveMemories returns the k memories most relevant to query for a tenant,
// using the platform embedding interface. Falls back to recent memories when
// embeddings are unavailable.
func (m *Manager) RetrieveMemories(tenantID, query string, k int) ([]memory.Memory, error) {
	if m.memoryStore == nil {
		return nil, errNoStore
	}
	cfg, err := m.globalAIConfig()
	if err != nil {
		return nil, err
	}
	return m.memoryStore.Retrieve(context.Background(), cfg, tenantID, query, k)
}

// memorySystemPrompt returns the tenant's system prompt with relevant personal
// memory injected as context, when the memory store and AI config are available.
func (m *Manager) memorySystemPrompt(tenantID string, msgs []Message, topic string) string {
	if m.memoryStore == nil || tenantID == "" {
		return ""
	}
	cfg, err := m.globalAIConfig()
	if err != nil {
		return ""
	}
	query := topic
	for i := len(msgs) - 1; i >= 0; i-- {
		if msgs[i].Content != "" {
			query = msgs[i].Content
			break
		}
	}
	mems, err := m.memoryStore.Retrieve(context.Background(), cfg, tenantID, query, 5)
	if err != nil || len(mems) == 0 {
		return ""
	}
	p, _ := m.memoryStore.GetProfile(tenantID)
	return memory.RenderText(p, mems)
}
