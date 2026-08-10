package ai

import (
	"context"
)

// UsageRecord is emitted by the AI client after each LLM call so the platform
// can account token consumption per tenant / model / model-type. The recorder is
// wired up once at startup (see main.go) and writes into the usage store.
// Every system-LLM call records token usage, the call count (one row per call,
// summed by the aggregate), and the call duration in milliseconds.
type UsageRecord struct {
	TenantID         string // bot/account id (or synthetic feature id)
	ChannelID        string
	Model            string
	ModelType        string // "chat" | "embedding"
	PromptTokens     int
	CompletionTokens int
	TotalTokens      int
	CachedTokens     int
	ReasoningTokens  int
	DurationMS       int64 // wall-clock duration of the LLM call, milliseconds
}

// UsageRecorder receives a UsageRecord after every LLM call.
type UsageRecorder func(ctx context.Context, r UsageRecord)

var usageRecorder UsageRecorder

// SetUsageRecorder installs the global recorder. Pass nil to disable recording.
func SetUsageRecorder(fn UsageRecorder) {
	usageRecorder = fn
}

// Context keys for tagging LLM calls with tenant/channel metadata and for
// marking system (non-billable) calls such as health probes.
type usageMetaKey struct{}

type usageMeta struct {
	tenantID  string
	channelID string
	system    bool
}

// ContextWithMeta attaches the tenant and channel identity to an LLM call so the
// usage can be attributed. Both may be empty; the recorder decides how to handle
// unattributed usage.
func ContextWithMeta(ctx context.Context, tenantID, channelID string) context.Context {
	return context.WithValue(ctx, usageMetaKey{}, usageMeta{tenantID: tenantID, channelID: channelID})
}

// ContextSystem marks a context as a system call (e.g. model-health probing)
// which should not be billed to any tenant.
func ContextSystem(ctx context.Context) context.Context {
	return context.WithValue(ctx, usageMetaKey{}, usageMeta{system: true})
}

// recordUsage emits a UsageRecord if a recorder is installed and the call is not
// a system call. It is a no-op when usage is nil or no recorder is set. The
// recorder call is guarded so a failure in accounting can never break the LLM
// response path. durationMS is the wall-clock time the underlying HTTP call took.
func recordUsage(ctx context.Context, model, modelType string, u *Usage, durationMS int64) {
	if usageRecorder == nil || u == nil {
		return
	}
	meta, _ := ctx.Value(usageMetaKey{}).(usageMeta)
	if meta.system {
		return
	}
	func() {
		defer func() { _ = recover() }()
		usageRecorder(ctx, UsageRecord{
			TenantID:         meta.tenantID,
			ChannelID:        meta.channelID,
			Model:            model,
			ModelType:        modelType,
			PromptTokens:     u.PromptTokens,
			CompletionTokens: u.CompletionTokens,
			TotalTokens:      u.TotalTokens,
			CachedTokens:     u.CachedTokens,
			ReasoningTokens:  u.ReasoningTokens,
			DurationMS:       durationMS,
		})
	}()
}
