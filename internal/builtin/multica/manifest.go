package multica

import (
	"encoding/json"

	"github.com/ceoadmin/CEOadmin/internal/builtin"
)

func init() {
	builtin.Register(builtin.AppManifest{
		Slug:         "multica",
		Name:         "Multica 智能体看板",
		Description:  "多智能体团队协作看板：把 issue 分配给 AI 编码智能体（Claude Code/Codex/OpenCode 等 20 个 runtime），本机 daemon 认领执行，执行日志与 review gate 全留痕。",
		Icon:         "🎯",
		Readme:       "",
		Guide:        "",
		Homepage:     "/apps/multica",
		Scopes:       []string{},
		Events:       []string{},
		ConfigSchema: json.RawMessage(`{}`),
	}, nil)
}