package sink

import (
	"github.com/ceoadmin/CEOadmin/internal/provider"
	"github.com/ceoadmin/CEOadmin/internal/relay"
	"github.com/ceoadmin/CEOadmin/internal/store"
)

// Delivery holds all context for delivering a message to a channel sink.
type Delivery struct {
	BotDBID      string
	Provider     provider.Provider
	Channel      store.Channel
	Message      provider.InboundMessage
	Envelope     relay.Envelope
	SeqID        int64
	MsgType      string
	Content      string
	AIEnabled    bool
	AIModel      string
	Tracer       *store.Tracer
	RootSpan     *store.SpanBuilder
}

// Sink processes messages delivered to a channel.
type Sink interface {
	Name() string
	Handle(d Delivery)
}
