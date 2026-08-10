// Package supplymarket implements the 供采市场 (supply & procurement
// marketplace) builtin app. It is a self-contained port of the supply-demand
// engine originally written for the tupaisaasmcp service:
//
//   - publish supply/procurement items, rule-based scoring (no LLM dependency),
//     state machine DRAFT → PENDING_CLARIFICATION → VERIFIED / REJECTED / CLOSED
//   - a cross-tenant marketplace listing every VERIFIED supply & procurement
//   - two-way matching (supply ↔ procurement) with a deterministic score
//   - cross-user chat between the item owner and an inquirer
//
// Persistence reuses the system config store (JSON blobs keyed by prefix), the
// same approach as the tenant-chat builtin app, so no schema migration is
// needed and it works identically on SQLite and PostgreSQL.
package supplymarket

import (
	"encoding/json"
	"time"
)

// Item types.
const (
	ItemTypeSupply      = "supply"
	ItemTypeProcurement = "procurement"
)

// Item lifecycle states.
const (
	StateDraft                = "DRAFT"
	StatePendingClarification = "PENDING_CLARIFICATION"
	StateVerified             = "VERIFIED"
	StateRejected             = "REJECTED"
	StateClosed               = "CLOSED"
)

// Scoring / workflow constants (mirror the upstream engine).
const (
	ScoreThreshold    = 40.0
	MaxClarifyRounds  = 3
	DescriptionMinLen = 15 // non-LLM path uses a lower bar than 30
)

// Currency whitelist used by the price scoring rule.
var CurrencyWhitelist = map[string]bool{
	"CNY": true, "RMB": true, "USD": true, "EUR": true, "GBP": true,
	"JPY": true, "HKD": true, "TWD": true,
}

// CategoryKeywords are used by the quality scoring rule to detect whether a
// title/description actually talks about the claimed category.
var CategoryKeywords = map[string][]string{
	"服务": {"咨询", "设计", "培训", "开发", "翻译", "运维", "教练", "顾问"},
	"商品": {"出售", "批发", "零售", "二手", "新", "正品", "行货"},
	"场地": {"出租", "租赁", "工位", "会议室", "场地", "办公室"},
	"设备": {"设备", "机器", "工具", "硬件"},
	"知识": {"课程", "资料", "电子书", "培训"},
}

// ClarificationQuestion is one auto-generated question about a missing field.
type ClarificationQuestion struct {
	QID  string `json:"qid"`
	Text string `json:"text"`
}

// ClarificationAnswer is the owner's reply to one question.
type ClarificationAnswer struct {
	QID  string `json:"qid"`
	Text string `json:"text"`
}

// ClarificationRound records one round of Q&A.
type ClarificationRound struct {
	RoundNo    int                     `json:"round_no"`
	Questions  []ClarificationQuestion `json:"questions"`
	Answers    []ClarificationAnswer   `json:"answers"`
	ScoreDelta float64                 `json:"score_delta"`
	ScoredAt   int64                   `json:"scored_at"`
}

// Item is a supply / procurement listing published by a user.
type Item struct {
	ItemID              string               `json:"item_id"`
	ItemType            string               `json:"item_type"`
	TenantID            string               `json:"tenant_id"` // owner user id
	Title               string               `json:"title"`
	Description         string               `json:"description"`
	Category            string               `json:"category"`
	Price               float64              `json:"price"`
	Currency            string               `json:"currency"`
	Location            string               `json:"location"`
	Contact             string               `json:"contact"`
	Metadata            map[string]any       `json:"metadata,omitempty"`
	CreatedAt           int64                `json:"created_at"`
	UpdatedAt           int64                `json:"updated_at"`
	State               string               `json:"state"`
	Score               float64              `json:"score"`
	RoundNo             int                  `json:"round_no"`
	ClarificationRounds []ClarificationRound `json:"clarification_rounds,omitempty"`
	ChatSessionIDs      []string             `json:"chat_session_ids,omitempty"`
	VerifiedAt          int64                `json:"verified_at,omitempty"`
}

// ChatMessage is one message inside a chat session.
type ChatMessage struct {
	FromRole   string `json:"from_role"` // "owner" | "inquirer"
	Role       string `json:"role"`
	Text       string `json:"text"`
	TS         int64  `json:"ts"`
	FromUserID string `json:"from_user_id"`
}

// ChatSession is a cross-user conversation about one item. The item owner
// (owner) and the inquirer can both read it and send messages.
type ChatSession struct {
	SessionID        string        `json:"session_id"`
	ItemID           string        `json:"item_id"`
	ItemType         string        `json:"item_type"`
	OwnerTenantID    string        `json:"owner_tenant_id"`
	OwnerClientID    string        `json:"owner_client_id,omitempty"`
	InquirerTenantID string        `json:"inquirer_tenant_id"`
	InquirerClientID string        `json:"inquirer_client_id,omitempty"`
	StartedAt        int64         `json:"started_at"`
	LastMessageAt    int64         `json:"last_message_at"`
	Messages         []ChatMessage `json:"messages"`
	// MyRole is filled by the API layer with the calling user's role in this
	// session ("owner" | "inquirer"), so the UI can align bubbles correctly.
	MyRole string `json:"my_role,omitempty"`
}

// NewItem creates an Item with fresh timestamps and an empty round.
func NewItem() *Item {
	return &Item{
		State:               StateDraft,
		CreatedAt:           time.Now().Unix(),
		UpdatedAt:           time.Now().Unix(),
		Metadata:            map[string]any{},
		ClarificationRounds: []ClarificationRound{},
		ChatSessionIDs:      []string{},
	}
}

// IsOwner reports whether the given user owns this item.
func (it *Item) IsOwner(userID string) bool { return it != nil && it.TenantID == userID }

// Marshal stores a JSON blob.
func (it *Item) marshal() (string, error) {
	b, err := json.Marshal(it)
	return string(b), err
}

// unmarshalItem reads an Item from a JSON blob.
func unmarshalItem(blob string) (*Item, error) {
	var it Item
	if err := json.Unmarshal([]byte(blob), &it); err != nil {
		return nil, err
	}
	if it.Metadata == nil {
		it.Metadata = map[string]any{}
	}
	if it.ClarificationRounds == nil {
		it.ClarificationRounds = []ClarificationRound{}
	}
	if it.ChatSessionIDs == nil {
		it.ChatSessionIDs = []string{}
	}
	return &it, nil
}

// NewChat creates a chat session with fresh timestamps.
func NewChat() *ChatSession {
	return &ChatSession{
		StartedAt:     time.Now().Unix(),
		LastMessageAt: time.Now().Unix(),
		Messages:      []ChatMessage{},
	}
}

func (s *ChatSession) marshal() (string, error) {
	b, err := json.Marshal(s)
	return string(b), err
}

func unmarshalChat(blob string) (*ChatSession, error) {
	var s ChatSession
	if err := json.Unmarshal([]byte(blob), &s); err != nil {
		return nil, err
	}
	if s.Messages == nil {
		s.Messages = []ChatMessage{}
	}
	return &s, nil
}
