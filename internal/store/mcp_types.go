package store

// SkillUploadTicketRequest represents a request to create a skill upload ticket
type SkillUploadTicketRequest struct {
	SkillName    string `json:"skill_name"`
	Filename     string `json:"filename"`
	Version      string `json:"version"`
	SkillType    string `json:"skill_type"`
	Description  string `json:"description"`
	RequiredCaps []any  `json:"required_capabilities"`
	ContentType  string `json:"content_type"`
	TTLSeconds   int    `json:"ttl_seconds"`
}

// SkillUploadTicket represents a COS upload ticket for skill bundles
type SkillUploadTicket struct {
	TicketID  string            `json:"ticket_id"`
	UploadURL string            `json:"upload_url"`
	Method    string            `json:"method"`
	Headers   map[string]string `json:"headers"`
	Key       string            `json:"key"`
	MaxSize   int64             `json:"max_size"`
	ExpiresAt int64             `json:"expires_at"`
}

// SkillInstallConfirm represents a skill installation confirmation
type SkillInstallConfirm struct {
	SkillID             string `json:"skill_id"`
	InstallPath         string `json:"install_path"`
	InstallVersion      string `json:"install_version"`
	InstallSizeBytes    int64  `json:"install_size_bytes"`
	IsExternal          bool   `json:"is_external"`
	ExternalDownloadURL string `json:"external_download_url"`
}

// SkillExecutionReport represents a skill execution report for Hermes
type SkillExecutionReport struct {
	SkillID      string         `json:"skill_id"`
	SkillVersion string         `json:"skill_version"`
	ClientID     string         `json:"client_id"`
	TenantID     string         `json:"tenant_id"`
	Params       map[string]any `json:"params"`
	Result       map[string]any `json:"result"`
	ErrorMessage string         `json:"error_message"`
	DurationMs   int64          `json:"duration_ms"`
	Timestamp    int64          `json:"timestamp"`
}

// SkillEvaluation represents a skill quality evaluation
type SkillEvaluation struct {
	SkillID       string  `json:"skill_id"`
	OverallScore  float64 `json:"overall_score"`
	QualityScore  float64 `json:"quality_score"`
	UsageScore    float64 `json:"usage_score"`
	SampleCount   int     `json:"sample_count"`
	LastEvaluated int64   `json:"last_evaluated"`
}

// UploadTicket represents a COS upload ticket for general files
type UploadTicket struct {
	TicketID  string            `json:"ticket_id"`
	UploadURL string            `json:"upload_url"`
	Method    string            `json:"method"`
	Headers   map[string]string `json:"headers"`
	Key       string            `json:"key"`
	MaxSize   int64             `json:"max_size_bytes"`
	ExpiresAt int64             `json:"expires_at"`
}

// BillingConfig holds billing configuration
type BillingConfig struct {
	SignupBonusGarlic  int     `json:"signup_bonus_garlic"`
	DailyBonusGarlic   int     `json:"daily_bonus_garlic"`
	LLMCostPerToken    float64 `json:"llm_cost_per_token"`
	SkillCallCost      int     `json:"skill_call_cost"`
	TaskCompleteReward int     `json:"task_complete_reward"`
}

// GarlicLedger represents a billing ledger entry
type GarlicLedger struct {
	ClientID    string        `json:"client_id"`
	Balance     int           `json:"balance"`
	TotalEarned int           `json:"total_earned"`
	TotalSpent  int           `json:"total_spent"`
	Entries     []LedgerEntry `json:"entries"`
}

type LedgerEntry struct {
	Timestamp int64  `json:"timestamp"`
	Type      string `json:"type"` // earn/spend
	Amount    int    `json:"amount"`
	Reason    string `json:"reason"`
	RefID     string `json:"ref_id,omitempty"`
}

// SearchSignalsReport represents a search interaction report
type SearchSignalsReport struct {
	ClientID      string         `json:"client_id"`
	TenantID      string         `json:"tenant_id"`
	Query         string         `json:"query"`
	Results       []any          `json:"results"`
	ClickedResult map[string]any `json:"clicked_result"`
	DwellTimeMs   int64          `json:"dwell_time_ms"`
	Timestamp     int64          `json:"timestamp"`
}
