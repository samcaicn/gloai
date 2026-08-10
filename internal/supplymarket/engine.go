package supplymarket

import (
	"encoding/json"
	"fmt"
	"sort"
	"strings"
	"sync"
	"time"
)

// ConfigStore is the minimal storage surface the engine depends on (the same
// shape used by the tenant-chat builtin app). Items and chats are persisted as
// JSON blobs under stable key prefixes.
type ConfigStore interface {
	GetConfig(key string) (string, error)
	SetConfig(key, value string) error
	DeleteConfig(key string) error
	ListConfigByPrefix(prefix string) (map[string]string, error)
}

// Key prefixes.
const (
	itemKeyPrefix = "supplymarket:item:"
	chatKeyPrefix = "supplymarket:chat:"
)

func itemKey(tenantID, itemID string) string { return itemKeyPrefix + tenantID + ":" + itemID }
func chatKey(sessionID string) string        { return chatKeyPrefix + sessionID }

// ErrNotFound is returned when an item or session does not exist.
type ErrNotFound struct{ Kind string }

func (e *ErrNotFound) Error() string { return e.Kind + " 不存在" }

// ErrForbidden is returned when a user tries to mutate someone else's item.
type ErrForbidden struct{ msg string }

func (e *ErrForbidden) Error() string { return e.msg }

// Engine is the supply & procurement marketplace. It is safe for concurrent
// use. All items live under itemKeyPrefix; chat sessions under chatKeyPrefix.
type Engine struct {
	mu    sync.Mutex
	store ConfigStore
}

// Default is the process-wide singleton used by the API handlers.
var Default = &Engine{}

// Init wires the storage backend.
func (e *Engine) Init(store ConfigStore) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.store = store
}

// PublishResult summarises a publish / clarify action for the caller.
type PublishResult struct {
	ItemID        string                  `json:"item_id"`
	ItemType      string                  `json:"item_type"`
	State         string                  `json:"state"`
	Score         float64                 `json:"score"`
	RoundNo       int                     `json:"round_no"`
	NextQuestions []ClarificationQuestion `json:"next_questions"`
	IsFinal       bool                    `json:"is_final,omitempty"`
	Reason        string                  `json:"reason,omitempty"`
	Refined       string                  `json:"refined,omitempty"`
}

// Publish validates and persists a new supply / procurement item, scoring it
// immediately and deciding the initial state.
func (e *Engine) Publish(itemType, tenantID, title, description, category string, price float64, currency, location, contact string) (*PublishResult, error) {
	if itemType != ItemTypeSupply && itemType != ItemTypeProcurement {
		return nil, fmt.Errorf("item_type 必须是 supply 或 procurement")
	}
	if tenantID == "" {
		return nil, fmt.Errorf("缺少用户身份")
	}
	now := time.Now().Unix()
	it := NewItem()
	it.ItemID = newID()
	it.ItemType = itemType
	it.TenantID = tenantID
	it.Title = strings.TrimSpace(title)
	it.Description = strings.TrimSpace(description)
	it.Category = strings.TrimSpace(category)
	it.Price = price
	it.Currency = strings.TrimSpace(currency)
	it.Location = strings.TrimSpace(location)
	it.Contact = strings.TrimSpace(contact)
	it.CreatedAt = now
	it.UpdatedAt = now

	it.Score = computeScore(it)
	var next []ClarificationQuestion
	if it.Score >= ScoreThreshold {
		it.State = StateVerified
		it.VerifiedAt = now
		it.Description = refineDescription(it)
	} else {
		it.State = StatePendingClarification
		next = generateClarificationQuestions(it)
	}
	if err := e.saveItem(it); err != nil {
		return nil, err
	}
	return &PublishResult{
		ItemID:        it.ItemID,
		ItemType:      it.ItemType,
		State:         it.State,
		Score:         it.Score,
		RoundNo:       it.RoundNo,
		NextQuestions: next,
		Refined:       it.Description,
	}, nil
}

// Clarify accepts one round of answers from the owner. On success the item
// either becomes VERIFIED (score threshold met), REJECTED (rounds exhausted),
// or stays PENDING_CLARIFICATION with the next set of questions.
func (e *Engine) Clarify(itemID, tenantID string, answers []ClarificationAnswer) (*PublishResult, error) {
	if itemID == "" || tenantID == "" {
		return nil, fmt.Errorf("item_id / tenant_id required")
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	it, err := e.loadItem(itemID, tenantID)
	if err != nil {
		return nil, err
	}
	if !it.IsOwner(tenantID) {
		return nil, &ErrForbidden{fmt.Sprintf("item %s 属于用户 %s", itemID, it.TenantID)}
	}
	if it.State != StatePendingClarification && it.State != StateDraft {
		return &PublishResult{
			ItemID:   it.ItemID,
			ItemType: it.ItemType,
			State:    it.State,
			Score:    it.Score,
			RoundNo:  it.RoundNo,
			IsFinal:  true,
			Reason:   "already_terminal",
		}, nil
	}

	questions := generateClarificationQuestions(it)
	round := ClarificationRound{
		RoundNo:   it.RoundNo + 1,
		Questions: questions,
		Answers:   answers,
		ScoredAt:  time.Now().Unix(),
	}
	applyAnswersToItem(it, answers)
	it.ClarificationRounds = append(it.ClarificationRounds, round)
	it.RoundNo = round.RoundNo

	newScore := computeScore(it)
	round.ScoreDelta = round2(newScore - it.Score)
	it.Score = newScore

	var next []ClarificationQuestion
	isFinal := false
	switch {
	case newScore >= ScoreThreshold:
		it.State = StateVerified
		it.VerifiedAt = time.Now().Unix()
		it.Description = refineDescription(it)
		isFinal = true
	case it.RoundNo >= MaxClarifyRounds:
		it.State = StateRejected
		isFinal = true
	default:
		it.State = StatePendingClarification
		next = generateClarificationQuestions(it)
	}
	it.UpdatedAt = time.Now().Unix()
	if err := e.saveItem(it); err != nil {
		return nil, err
	}
	return &PublishResult{
		ItemID:        it.ItemID,
		ItemType:      it.ItemType,
		State:         it.State,
		Score:         it.Score,
		RoundNo:       it.RoundNo,
		NextQuestions: next,
		IsFinal:       isFinal,
		Refined:       it.Description,
	}, nil
}

// Close marks an owner's item as CLOSED (kept for history, removed from the
// marketplace and excluded from matching).
func (e *Engine) Close(itemID, tenantID string) error {
	if itemID == "" || tenantID == "" {
		return fmt.Errorf("item_id / tenant_id required")
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	it, err := e.loadItem(itemID, tenantID)
	if err != nil {
		return err
	}
	if !it.IsOwner(tenantID) {
		return &ErrForbidden{fmt.Sprintf("item %s 属于用户 %s", itemID, it.TenantID)}
	}
	it.State = StateClosed
	it.UpdatedAt = time.Now().Unix()
	return e.saveItem(it)
}

// Delete removes an owner's item entirely.
func (e *Engine) Delete(itemID, tenantID string) error {
	if itemID == "" || tenantID == "" {
		return fmt.Errorf("item_id / tenant_id required")
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	it, err := e.loadItem(itemID, tenantID)
	if err != nil {
		return err
	}
	if !it.IsOwner(tenantID) {
		return &ErrForbidden{fmt.Sprintf("item %s 属于用户 %s", itemID, it.TenantID)}
	}
	return e.store.DeleteConfig(itemKey(it.TenantID, it.ItemID))
}

// MyItems lists the calling user's items, newest first, with optional state /
// type filters.
func (e *Engine) MyItems(tenantID string, state, itemType string) ([]*Item, error) {
	if tenantID == "" {
		return nil, fmt.Errorf("缺少用户身份")
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	raw, err := e.store.ListConfigByPrefix(itemKeyPrefix + tenantID + ":")
	if err != nil {
		return nil, err
	}
	out := make([]*Item, 0, len(raw))
	for _, blob := range raw {
		it, err := unmarshalItem(blob)
		if err != nil {
			continue
		}
		if state != "" && it.State != state {
			continue
		}
		if itemType != "" && it.ItemType != itemType {
			continue
		}
		out = append(out, it)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].UpdatedAt > out[j].UpdatedAt })
	return out, nil
}

// Get returns a single item by id, searching the owner namespace first and
// falling back to a global scan (marketplace items are cross-tenant).
func (e *Engine) Get(itemID, tenantID string) (*Item, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if it, err := e.loadItem(itemID, tenantID); err == nil {
		return it, nil
	}
	raw, err := e.store.ListConfigByPrefix(itemKeyPrefix)
	if err != nil {
		return nil, err
	}
	for _, blob := range raw {
		it, err := unmarshalItem(blob)
		if err == nil && it.ItemID == itemID {
			return it, nil
		}
	}
	return nil, &ErrNotFound{Kind: "item"}
}

// MarketplaceList returns every VERIFIED item (cross-tenant, both supply and
// procurement), with optional filters. Sorted by (score, updated_at) desc.
func (e *Engine) MarketplaceList(itemType, category, location string, priceMin, priceMax *float64, limit int) ([]*Item, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	raw, err := e.store.ListConfigByPrefix(itemKeyPrefix)
	if err != nil {
		return nil, err
	}
	items := make([]*Item, 0, len(raw))
	for _, blob := range raw {
		it, err := unmarshalItem(blob)
		if err != nil {
			continue
		}
		if it.State != StateVerified {
			continue
		}
		if itemType != "" && it.ItemType != itemType {
			continue
		}
		if category != "" && it.Category != category {
			continue
		}
		if location != "" {
			loc := strings.ToLower(it.Location)
			flt := strings.ToLower(location)
			if !strings.Contains(loc, flt) && !strings.Contains(flt, loc) {
				continue
			}
		}
		if priceMin != nil && it.Price < *priceMin {
			continue
		}
		if priceMax != nil && it.Price > *priceMax {
			continue
		}
		items = append(items, it)
	}
	sort.Slice(items, func(i, j int) bool {
		if items[i].Score != items[j].Score {
			return items[i].Score > items[j].Score
		}
		return items[i].UpdatedAt > items[j].UpdatedAt
	})
	if limit > 0 && len(items) > limit {
		items = items[:limit]
	}
	return items, nil
}

// MatchCandidate is one recommended counterpart for a source item.
type MatchCandidate struct {
	ItemID      string  `json:"item_id"`
	ItemType    string  `json:"item_type"`
	Title       string  `json:"title"`
	Description string  `json:"description"`
	Category    string  `json:"category"`
	Price       float64 `json:"price"`
	Currency    string  `json:"currency"`
	Location    string  `json:"location"`
	OwnerTenant string  `json:"owner_tenant_id"`
	MatchScore  float64 `json:"match_score"`
	ItemScore   float64 `json:"item_score"`
	MatchHit    bool    `json:"match_hit"` // match_score >= MatchThreshold
}

// MatchThreshold is the score at which a pair counts as a hit (60).
const MatchThreshold = 60.0

// Match recommends the best opposite-type items for a source item, using the
// deterministic match score: category +30, title overlap +5/word, description
// overlap +2/word, price proximity up to +20.
func (e *Engine) Match(itemID, tenantID string, limit int) ([]*MatchCandidate, error) {
	source, err := e.Get(itemID, tenantID)
	if err != nil {
		return nil, err
	}
	if source.State != StateVerified {
		return nil, fmt.Errorf("只有 VERIFIED 状态的供需项可参与撮合")
	}
	opposite := ItemTypeProcurement
	if source.ItemType == ItemTypeProcurement {
		opposite = ItemTypeSupply
	}
	candidates, err := e.MarketplaceList(opposite, "", "", nil, nil, 500)
	if err != nil {
		return nil, err
	}
	srcTitleWords := words(source.Title)
	srcDescWords := words(source.Description)

	type scored struct {
		it *Item
		s  float64
	}
	list := make([]scored, 0, len(candidates))
	for _, c := range candidates {
		if c.ItemID == source.ItemID || c.TenantID == source.TenantID {
			continue
		}
		list = append(list, scored{it: c, s: matchScore(source, c, srcTitleWords, srcDescWords)})
	}
	sort.Slice(list, func(i, j int) bool { return list[i].s > list[j].s })
	if limit <= 0 || limit > len(list) {
		limit = len(list)
	}
	out := make([]*MatchCandidate, 0, limit)
	for _, m := range list[:limit] {
		c := m.it
		out = append(out, &MatchCandidate{
			ItemID:      c.ItemID,
			ItemType:    c.ItemType,
			Title:       c.Title,
			Description: c.Description,
			Category:    c.Category,
			Price:       c.Price,
			Currency:    c.Currency,
			Location:    c.Location,
			OwnerTenant: c.TenantID,
			MatchScore:  round2(m.s),
			ItemScore:   c.Score,
			MatchHit:    m.s >= MatchThreshold,
		})
	}
	return out, nil
}

func matchScore(source, c *Item, srcTitle, srcDesc []string) float64 {
	var s float64
	if source.Category != "" && source.Category == c.Category {
		s += 30
	}
	cTitle := words(c.Title)
	cDesc := words(c.Description)
	s += 5 * float64(overlapCount(srcTitle, cTitle))
	s += 2 * float64(overlapCount(srcDesc, cDesc))
	if source.Price > 0 && c.Price > 0 {
		diff := abs(source.Price - c.Price)
		den := source.Price
		if c.Price > den {
			den = c.Price
		}
		ratio := diff / den
		if ratio > 1 {
			ratio = 1
		}
		s += 20 * (1 - ratio)
	}
	return s
}

func words(s string) []string {
	parts := strings.Fields(s)
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		if len(p) > 1 {
			out = append(out, strings.ToLower(p))
		}
	}
	return out
}

func overlapCount(a, b []string) int {
	set := map[string]bool{}
	for _, w := range a {
		set[w] = true
	}
	n := 0
	for _, w := range b {
		if set[w] {
			n++
		}
	}
	return n
}

func abs(v float64) float64 {
	if v < 0 {
		return -v
	}
	return v
}

// ---- chats ----

// StartChat opens (or reuses) a chat session between the inquirer and the item
// owner. One inquirer may only have one active session per item.
func (e *Engine) StartChat(itemID, inquirerID string) (*ChatSession, error) {
	if itemID == "" || inquirerID == "" {
		return nil, fmt.Errorf("item_id / user_id required")
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	it, err := e.loadItemAny(itemID)
	if err != nil {
		return nil, err
	}
	if it.TenantID == inquirerID {
		return nil, fmt.Errorf("不能和自己发布的信息建立会话")
	}
	for _, sid := range it.ChatSessionIDs {
		s, err := e.loadChat(sid)
		if err != nil {
			continue
		}
		if s.InquirerTenantID == inquirerID {
			return s, nil
		}
	}
	s := NewChat()
	s.SessionID = newID()
	s.ItemID = it.ItemID
	s.ItemType = it.ItemType
	s.OwnerTenantID = it.TenantID
	s.InquirerTenantID = inquirerID
	it.ChatSessionIDs = append(it.ChatSessionIDs, s.SessionID)
	it.UpdatedAt = time.Now().Unix()
	if err := e.saveItem(it); err != nil {
		return nil, err
	}
	if err := e.saveChat(s); err != nil {
		return nil, err
	}
	return s, nil
}

// MyChats lists the chat sessions the calling user participates in (as owner
// or inquirer), newest first. MyRole is annotated per session.
func (e *Engine) MyChats(tenantID string) ([]*ChatSession, error) {
	if tenantID == "" {
		return nil, fmt.Errorf("缺少用户身份")
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	raw, err := e.store.ListConfigByPrefix(chatKeyPrefix)
	if err != nil {
		return nil, err
	}
	out := make([]*ChatSession, 0)
	for _, blob := range raw {
		s, err := unmarshalChat(blob)
		if err != nil {
			continue
		}
		switch {
		case s.OwnerTenantID == tenantID:
			s.MyRole = "owner"
		case s.InquirerTenantID == tenantID:
			s.MyRole = "inquirer"
		default:
			continue
		}
		out = append(out, s)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].LastMessageAt > out[j].LastMessageAt })
	return out, nil
}

// GetChat returns a session if the caller participates in it.
func (e *Engine) GetChat(sessionID, tenantID string) (*ChatSession, error) {
	if sessionID == "" {
		return nil, fmt.Errorf("session_id required")
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	s, err := e.loadChat(sessionID)
	if err != nil {
		return nil, err
	}
	switch {
	case s.OwnerTenantID == tenantID:
		s.MyRole = "owner"
	case s.InquirerTenantID == tenantID:
		s.MyRole = "inquirer"
	default:
		return nil, &ErrForbidden{"无权访问该会话"}
	}
	return s, nil
}

// SendChatMessage appends a message to a session (both sides may send) and
// bumps the timestamps.
func (e *Engine) SendChatMessage(sessionID, tenantID, text string) (*ChatSession, error) {
	text = strings.TrimSpace(text)
	if text == "" {
		return nil, fmt.Errorf("消息不能为空")
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	s, err := e.loadChat(sessionID)
	if err != nil {
		return nil, err
	}
	role := "inquirer"
	if s.OwnerTenantID == tenantID {
		role = "owner"
	} else if s.InquirerTenantID != tenantID {
		return nil, &ErrForbidden{"无权访问该会话"}
	}
	now := time.Now().Unix()
	s.Messages = append(s.Messages, ChatMessage{
		FromRole:   role,
		Role:       role,
		Text:       text,
		TS:         now,
		FromUserID: tenantID,
	})
	s.LastMessageAt = now
	if err := e.saveChat(s); err != nil {
		return nil, err
	}
	return s, nil
}

// ---- internal helpers ----

// loadItem loads an item by id, first trying the given tenant's namespace,
// then scanning globally.
func (e *Engine) loadItem(itemID, tenantID string) (*Item, error) {
	if tenantID != "" {
		if blob, err := e.store.GetConfig(itemKey(tenantID, itemID)); err == nil && blob != "" {
			return unmarshalItem(blob)
		}
	}
	return e.loadItemAny(itemID)
}

// loadItemAny scans the whole store for an item id.
func (e *Engine) loadItemAny(itemID string) (*Item, error) {
	raw, err := e.store.ListConfigByPrefix(itemKeyPrefix)
	if err != nil {
		return nil, err
	}
	for _, blob := range raw {
		it, err := unmarshalItem(blob)
		if err == nil && it.ItemID == itemID {
			return it, nil
		}
	}
	return nil, &ErrNotFound{Kind: "item"}
}

func (e *Engine) loadChat(sessionID string) (*ChatSession, error) {
	blob, err := e.store.GetConfig(chatKey(sessionID))
	if err != nil {
		return nil, err
	}
	if blob == "" {
		return nil, &ErrNotFound{Kind: "会话"}
	}
	return unmarshalChat(blob)
}

func (e *Engine) saveItem(it *Item) error {
	blob, err := it.marshal()
	if err != nil {
		return err
	}
	return e.store.SetConfig(itemKey(it.TenantID, it.ItemID), blob)
}

func (e *Engine) saveChat(s *ChatSession) error {
	blob, err := s.marshal()
	if err != nil {
		return err
	}
	return e.store.SetConfig(chatKey(s.SessionID), blob)
}

// categoriesJSON is a small helper to expose the known categories.
var _ = json.Marshal
