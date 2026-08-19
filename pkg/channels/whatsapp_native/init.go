package whatsapp

import (
	"path/filepath"

	"github.com/colearn/colearn/pkg/bus"
	"github.com/colearn/colearn/pkg/channels"
	"github.com/colearn/colearn/pkg/config"
)

func init() {
	channels.RegisterFactory(
		config.ChannelWhatsAppNative,
		func(channelName, channelType string, cfg *config.Config, b *bus.MessageBus) (channels.Channel, error) {
			bc := cfg.Channels[channelName]
			decoded, err := bc.GetDecoded()
			if err != nil {
				return nil, err
			}
			c, ok := decoded.(*config.WhatsAppSettings)
			if !ok {
				return nil, channels.ErrSendFailed
			}
			storePath := c.SessionStorePath
			if storePath == "" {
				storePath = filepath.Join(cfg.WorkspacePath(), "whatsapp")
			}
			ch, err := NewWhatsAppNativeChannel(bc, channelName, c, b, storePath)
			if err != nil {
				return nil, err
			}
			return ch, nil
		},
	)
}
