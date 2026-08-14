package golershop

import (
	"encoding/json"

	"github.com/ceoadmin/CEOadmin/internal/builtin"
)

func init() {
	builtin.Register(builtin.AppManifest{
		Slug:        "golershop",
		Name:        "Golershop 商城",
		Description: "GoFrame 商城系统，提供商品管理、订单处理、会员中心等电商功能",
		Icon:        "🛍️",
		Readme:      "",
		Guide:       "",
		Homepage:    "/apps/golershop",
		Scopes:      []string{},
		Events:      []string{},
		ConfigSchema: json.RawMessage(`{}`),
	}, nil)
}
