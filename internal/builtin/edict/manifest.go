package edict

import (
	"encoding/json"

	"github.com/ceoadmin/CEOadmin/internal/builtin"
)

func init() {
	builtin.Register(builtin.AppManifest{
		Slug:         "edict",
		Name:         "EDICT 御前奏对",
		Description:  "三省六部任务看板",
		Icon:         "⚔️",
		Readme:       "",
		Guide:        "",
		Homepage:     "",
		Scopes:       []string{},
		Events:       []string{},
		ConfigSchema: json.RawMessage(`{}`),
	}, nil)
}
