package store

// LLMUsageRecord is a single accounting row for one LLM call (chat completion or
// embedding). It captures token consumption so the platform can later bill each
// tenant (bot/account) per model type.
type LLMUsageRecord struct {
	ID               int64
	TenantID         string // bot/account id, or a synthetic feature id for builtin apps
	ChannelID        string // optional channel/conversation id
	Model            string
	ModelType        string // "chat" | "embedding" (the kind of LLM operation)
	PromptTokens     int
	CompletionTokens int
	TotalTokens      int
	CachedTokens     int
	ReasoningTokens  int
	DurationMS       int64 // wall-clock duration of the LLM call, milliseconds
	CreatedAt        int64 // unix seconds
}

// UsageFilter narrows the aggregation query.
type UsageFilter struct {
	TenantID  string
	Model     string
	ModelType string
	From      int64 // unix seconds, inclusive
	To        int64 // unix seconds, inclusive
	Limit     int
}

// UsageAggregate is one grouping row (tenant × model × type) of summed usage.
type UsageAggregate struct {
	TenantID         string
	TenantName       string
	Model            string
	ModelType        string
	PromptTokens     int
	CompletionTokens int
	TotalTokens      int
	CachedTokens     int
	ReasoningTokens  int
	DurationMS       int64 // total call duration across all calls, milliseconds
	CallCount        int
	LastAt           int64
}

// UsageStore records and aggregates LLM token usage for per-tenant billing.
type UsageStore interface {
	// RecordLLMUsage appends a single usage row.
	RecordLLMUsage(r *LLMUsageRecord) error
	// ListLLMUsageAgg returns usage summed by (tenant, model, type), newest
	// activity first, honoring the filter and limit.
	ListLLMUsageAgg(filter UsageFilter) ([]UsageAggregate, error)
}

// MediaType classifies a generated-media call.
type MediaType string

const (
	MediaImage MediaType = "image"
	MediaVideo MediaType = "video"
	MediaAudio MediaType = "audio"
)

// MediaUsageRecord is one accounting row for a media-generation call (image /
// video / audio). We record how many items were produced and their total
// duration in seconds (0 for images), so per-tenant billing can later price by
// media type.
type MediaUsageRecord struct {
	ID              int64
	TenantID        string
	ChannelID       string
	Model           string
	MediaType       MediaType
	Count           int // number of generated items (e.g. images / clips)
	DurationSeconds int // total seconds of generated media (video/audio; 0 for image)
	CreatedAt       int64
}

// MediaUsageFilter narrows the media aggregation query.
type MediaUsageFilter struct {
	TenantID  string
	Model     string
	MediaType MediaType
	From      int64
	To        int64
	Limit     int
}

// MediaUsageAggregate is one grouping row (tenant × model × media type) of summed
// media generation.
type MediaUsageAggregate struct {
	TenantID        string
	TenantName      string
	Model           string
	MediaType       MediaType
	Count           int
	DurationSeconds int
	CallCount       int // number of generation requests (rows)
	LastAt          int64
}

// MediaUsageStore records and aggregates media-generation usage for billing.
type MediaUsageStore interface {
	RecordMediaUsage(r *MediaUsageRecord) error
	ListMediaUsageAgg(filter MediaUsageFilter) ([]MediaUsageAggregate, error)
}
