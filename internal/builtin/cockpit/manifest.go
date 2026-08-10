package cockpit

import (
	"encoding/json"

	"github.com/ceoadmin/CEOadmin/internal/builtin"
)

func init() {
	builtin.Register(builtin.AppManifest{
		Slug:        "ai-transformation-cockpit",
		Name:        "AI 转型驾驶舱",
		Description: "面向经营管理层的 AI 原生决策引擎",
		Icon:        "📊",
		Readme:      "",
		Guide:       "",
		Homepage:    "",
		Scopes:      []string{},
		Events:      []string{},
		ConfigSchema: json.RawMessage(`{}`),
	}, nil)
}
