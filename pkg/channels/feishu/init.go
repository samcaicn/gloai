package feishu

import (
	"github.com/colearn/colearn/pkg/bus"
	"github.com/colearn/colearn/pkg/channels"
	"github.com/colearn/colearn/pkg/config"
)

func init() {
	channels.RegisterFactory(
		config.ChannelFeishu,
		func(channelName, channelType string, cfg *config.Config, b *bus.MessageBus) (channels.Channel, error) {
			bc := cfg.Channels[channelName]
			decoded, err := bc.GetDecoded()
			if err != nil {
				return nil, err
			}
			c, ok := decoded.(*config.FeishuSettings)
			if !ok {
				return nil, channels.ErrSendFailed
			}
			return NewFeishuChannel(bc, c, b)
		},
	)
}
