// Package media provides the usage-recording hook for media-generation calls
// (image / video / audio). Generation clients call Record() after each upstream
// request; the recorder (installed once at startup, see main.go) persists the
// (tenant, model, media type, count, duration) so the admin "LLM 配置" page can
// later bill each tenant by media type.
package media

import (
	"context"

	"github.com/ceoadmin/CEOadmin/internal/store"
)

// UsageRecord is emitted by a media-generation client after each upstream call.
type UsageRecord struct {
	TenantID        string
	ChannelID       string
	Model           string
	MediaType       store.MediaType
	Count           int // number of generated items (images / clips)
	DurationSeconds int // total seconds of generated media (0 for images)
}

// Recorder receives a UsageRecord.
type Recorder func(ctx context.Context, r UsageRecord)

var recorder Recorder

// SetRecorder installs the global recorder. Pass nil to disable recording.
func SetRecorder(fn Recorder) {
	recorder = fn
}

type metaKey struct{}

type meta struct {
	tenantID  string
	channelID string
	system    bool
}

// ContextWithMeta attaches the tenant and channel identity to a media call so the
// usage can be attributed.
func ContextWithMeta(ctx context.Context, tenantID, channelID string) context.Context {
	return context.WithValue(ctx, metaKey{}, meta{tenantID: tenantID, channelID: channelID})
}

// ContextSystem marks a context as a system call which should not be billed.
func ContextSystem(ctx context.Context) context.Context {
	return context.WithValue(ctx, metaKey{}, meta{system: true})
}

// Record emits a Record if a recorder is installed and the context is not a
// system call. It is a no-op otherwise and is guarded so a recording failure can
// never break the generation call.
func Record(ctx context.Context, mediaType store.MediaType, model string, count, durationSeconds int) {
	if recorder == nil {
		return
	}
	m, _ := ctx.Value(metaKey{}).(meta)
	if m.system {
		return
	}
	func() {
		defer func() { _ = recover() }()
		recorder(ctx, UsageRecord{
			TenantID:        m.tenantID,
			ChannelID:       m.channelID,
			Model:           model,
			MediaType:       mediaType,
			Count:           count,
			DurationSeconds: durationSeconds,
		})
	}()
}
